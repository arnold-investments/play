package play.classloading;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import play.Logger;
import play.Play;
import play.classloading.enhancers.SigEnhancer;
import play.exceptions.UnexpectedException;
import play.vfs.VirtualFile;

/**
 * Application classes container.
 */
public class ApplicationClasses {

    /**
     * Reference to the eclipse compiler.
     */
    final ApplicationCompiler compiler = new ApplicationCompiler(this);
    /**
     * Cache of all compiled classes
     */
    Map<String, ApplicationClass> classes = new ConcurrentHashMap<>();
    final Map<Path, ApplicationClass> pathMap = new ConcurrentHashMap<>();
    final java.util.Set<String> notFound = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final Object lock = new Object();

    /**
     * Clear the classes cache
     */
    public void clear() {
        classes = new ConcurrentHashMap<>();
        pathMap.clear();
        notFound.clear();
    }

    /**
     * Get a class by name
     * 
     * @param name
     *            The fully qualified class name
     * @return The ApplicationClass or null
     */
    public ApplicationClass getApplicationClass(String name) {
        {
            if (notFound.contains(name)) {
                return null;
            }

            ApplicationClass ac = classes.get(name);
            if (ac != null) {
                return ac;
            }
        }

        synchronized (lock) {
            if (notFound.contains(name)) {
                return null;
            }

            ApplicationClass ac = classes.get(name);
            if (ac != null) {
                return ac;
            }

            VirtualFile javaFile = getJava(name);
            if (javaFile == null) {
                notFound.add(name);
                return null;
            }

            ac = new ApplicationClass(name, javaFile);
            classes.put(name, ac);
            pathMap.put(javaFile.getRealFile().toPath(), ac);
            return ac;
        }
    }

    public ApplicationClass getApplicationClass(String name, Path path) {
        return classes.computeIfAbsent(name, className -> {
            VirtualFile javaFile = VirtualFile.open(path.toFile());

            ApplicationClass applicationClass = new ApplicationClass(className, javaFile);
            pathMap.put(path, applicationClass);
            return applicationClass;
        });
    }

    /**
     * Retrieve all application classes assignable to this class.
     * 
     * @param clazz
     *            The superclass, or the interface.
     * @return A list of application classes.
     */
    public List<ApplicationClass> getAssignableClasses(Class<?> clazz) {
        List<ApplicationClass> results = new ArrayList<>();
        if (clazz != null) {
            for (ApplicationClass applicationClass : new ArrayList<>(classes.values())) {
                if (!applicationClass.isClass()) {
                    continue;
                }
                try {
                    Play.classloader.loadClass(applicationClass.name);
                } catch (ClassNotFoundException ex) {
                    throw new UnexpectedException(ex);
                }
                try {
                    if (clazz.isAssignableFrom(applicationClass.javaClass)
                            && !applicationClass.javaClass.getName().equals(clazz.getName())) {
                        results.add(applicationClass);
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return results;
    }

    /**
     * Retrieve all application classes with a specific annotation.
     * 
     * @param clazz
     *            The annotation class.
     * @return A list of application classes.
     */
    public List<ApplicationClass> getAnnotatedClasses(Class<? extends Annotation> clazz) {
        List<ApplicationClass> results = new ArrayList<>();
        for (ApplicationClass applicationClass : classes.values()) {
            if (!applicationClass.isClass()) {
                continue;
            }
            try {
                Play.classloader.loadClass(applicationClass.name);
            } catch (ClassNotFoundException ex) {
                throw new UnexpectedException(ex);
            }
            if (applicationClass.javaClass != null && applicationClass.javaClass.isAnnotationPresent(clazz)) {
                results.add(applicationClass);
            }
        }
        return results;
    }

    /**
     * All loaded classes.
     * 
     * @return All loaded classes
     */
    public List<ApplicationClass> all() {
        return new ArrayList<>(classes.values());
    }

    /**
     * Put a new class in the cache.
     * 
     * @param applicationClass
     *            The class to add
     */
    public void add(ApplicationClass applicationClass) {
        classes.put(applicationClass.name, applicationClass);
        if (applicationClass.javaFile != null) {
            pathMap.put(applicationClass.javaFile.getRealFile().toPath(), applicationClass);
        }
    }

    /**
     * Remove a class from the cache
     * 
     * @param applicationClass
     *            The class to remove
     */
    public void remove(ApplicationClass applicationClass) {
        classes.remove(applicationClass.name);
        if (applicationClass.javaFile != null) {
            pathMap.remove(applicationClass.javaFile.getRealFile().toPath());
        }
    }

    /**
     * Remove a class from the cache
     * 
     * @param applicationClass
     *            The class name to remove
     */
    public void remove(String applicationClass) {
	    ApplicationClass actualClass = classes.remove(applicationClass);
        if (actualClass != null) {
            pathMap.remove(actualClass.javaFile.getRealFile().toPath());
        }
    }

    /**
     * Is this class already loaded?
     * 
     * @param name
     *            The fully qualified class name
     * @return true if the class is loaded
     */
    public boolean hasClass(String name) {
        return classes.containsKey(name);
    }

    /**
     * Represent an application class
     */
    public static class ApplicationClass {

        /**
         * The fully qualified class name
         */
        public String name;
        /**
         * A reference to the java source file
         */
        public VirtualFile javaFile;
        /**
         * The Java source
         */
        public String javaSource;
        /**
         * The Java source hash
         */
        private String javaSourceHash;
        /**
         * The compiled byteCode
         */
        public byte[] javaByteCode;
        /**
         * The in JVM loaded class
         */
        public Class<?> javaClass;
        /**
         * The in JVM loaded package
         */
        public Package javaPackage;
        /**
         * Last time than this class was compiled
         */
        public Long timestamp = 0L;
        /**
         * Is this class compiled?
         */
        boolean compiled;
        /**
         * Is this class enhanced (signatures computed)?
         */
        public boolean enhanced;
        /**
         * Signatures checksum
         */
        public int sigChecksum;
        /**
         * Static final properties checksum
         */
        public int staticFinalSigChecksum;
        /**
         * Static final properties checksum computed
         */
        public boolean staticFinalSigComputed;
        /**
         * Dependencies of this class
         */
        public java.util.Set<String> dependencies = new java.util.HashSet<>();

        public ApplicationClass() {
        }

        public ApplicationClass(String name) {
            this(name, getJava(name));
        }

        public ApplicationClass(String name, VirtualFile javaFile) {
            this.name = name;
            this.javaFile = javaFile;
            this.refresh();
        }

        /**
         * Need to refresh this class!
         */
        public final void refresh() {
            if (this.javaFile != null) {
                this.javaSource = this.javaFile.contentAsString();
            }
            this.javaSourceHash = null;
            this.javaByteCode = null;
            this.compiled = false;
            this.enhanced = false;
            this.timestamp = 0L;
            this.sigChecksum = 0;
            this.staticFinalSigChecksum = 0;
            this.staticFinalSigComputed = false;
            this.dependencies.clear();
        }

        public final String getJavaSourceHash() {
            if (this.javaSource == null) {
                return null;
            }

            if (this.javaSourceHash == null) {
                this.javaSourceHash = BytecodeCache.hash(this.javaSource);
            }
            return this.javaSourceHash;
        }


		private static SigEnhancer sigEnhancer = new SigEnhancer();

        /**
         * Compute signatures for this class
         */
        public void computeSignatures() {
            if (enhanced) {
                return;
            }
            if (isClass()) {
                try {
                    long start = System.nanoTime();
                    sigEnhancer.computeSignatures(this);
                    enhanced = true;
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("%sns to compute signatures for %s", System.nanoTime() - start, this.name);
                    }
                } catch (Exception e) {
                    throw new UnexpectedException("While computing signatures for " + this.name, e);
                }
            }

            if (System.getProperty("precompile") != null) {
                try {
                    // emit bytecode to standard class layout as well
                    File f = Play.getFile("precompiled/java/" + name.replace('.', '/') + ".class");
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(this.javaByteCode);
                    }
                } catch (Exception e) {
                    Logger.error(e, "Failed to write precompiled class %s to disk", name);
                }
            }
        }

        /**
         * Is this class already compiled but not defined?
         * 
         * @return if the class is compiled but not defined
         */
        public boolean isDefinable() {
            return compiled && javaClass != null;
        }

        public boolean isClass() {
            return isClass(this.name);
        }

        public static boolean isClass(String name) {
            return !name.endsWith("package-info");
        }

        public String getPackage() {
            int dot = name.lastIndexOf('.');
            return dot > -1 ? name.substring(0, dot) : "";
        }

        /**
         * Compile the class from the Java source
         * 
         * @return the bytes that comprise the class file
         */
        public byte[] compile() {
            long start = System.nanoTime();
            Play.classes.compiler.compile(new String[] { this.name });

            if (Logger.isTraceEnabled()) {
                Logger.trace("%sns to compile class %s", System.nanoTime() - start, name);
            }

            return this.javaByteCode;
        }

        /**
         * Unload the class
         */
        public void uncompile() {
            this.javaClass = null;
        }

        /**
         * Call back when a class is compiled.
         * 
         * @param code
         *            The bytecode.
         */
        public void compiled(byte[] code) {
            javaByteCode = code;
            compiled = true;
            this.timestamp = this.javaFile.lastModified();
        }

        @Override
        public String toString() {
            return name + " (compiled:" + compiled + ")";
        }
    }

    // ~~ Utils
    /**
     * Retrieve the corresponding source file for a given class name. It handles innerClass too!
     * 
     * @param name
     *            The fully qualified class name
     * @return The virtualFile if found
     */
    public static VirtualFile getJava(String name) {
        if (name.endsWith("$HibernateProxy") || name.endsWith("$HibernateInstantiator")) {
            return null;
        }
        String fileName = name;
        if (fileName.contains("$")) {
            fileName = fileName.substring(0, fileName.indexOf('$'));
        }
        // the local variable fileOrDir is important!
        String fileOrDir = fileName.replace('.', '/');
        fileName = fileOrDir + ".java";
        for (VirtualFile path : Play.javaPath) {
            // 1. check if there is a folder (without extension)
            VirtualFile javaFile = path.child(fileOrDir);

            if (javaFile.exists() && javaFile.isDirectory() && javaFile.matchName(fileOrDir)) {
                // we found a directory (package)
                return null;
            }
            // 2. check if there is a file
            javaFile = path.child(fileName);
            if (javaFile.exists() && javaFile.matchName(fileName)) {
                return javaFile;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return classes.toString();
    }
}
