package play.classloading;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Strings;
import play.Logger;
import play.Play;
import play.cache.Cache;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.hash.ClassStateHashCreator;
import play.exceptions.RestartNeededException;
import play.exceptions.UnexpectedException;
import play.libs.IO;
import play.vfs.VirtualFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.instrument.ClassDefinition;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.AllPermission;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * The application classLoader. Load the classes from the application Java sources files.
 */
public class ApplicationClassloader extends ClassLoader {

    private final ClassStateHashCreator classStateHashCreator = new ClassStateHashCreator();

    /**
     * The current state of the ApplicationClassloader. It gets a new value each time the state of
     * the classloader changes.
     */
    public ApplicationClassloaderState currentState = new ApplicationClassloaderState();

    /**
     * This protection domain applies to all loaded classes.
     */
    public ProtectionDomain protectionDomain;

    private final Object lock = new Object();

    public ApplicationClassloader() {
        super(ApplicationClassloader.class.getClassLoader());

        // Clean the existing classes
        for (ApplicationClass applicationClass : Play.classes.all()) {
            applicationClass.uncompile();
        }
        pathHash = computePathHash();
        try {
            CodeSource codeSource = new CodeSource(Path.of(Play.applicationPath.getAbsolutePath()).toUri().toURL(), (Certificate[]) null);
            Permissions permissions = new Permissions();
            permissions.add(new AllPermission());
            protectionDomain = new ProtectionDomain(codeSource, permissions);
        } catch (MalformedURLException e) {
            throw new UnexpectedException(e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Look up our cache
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }

        synchronized (lock) {
            // First check if it's an application Class
            Class<?> applicationClass = loadApplicationClass(name);
            if (applicationClass != null) {
                if (resolve) {
                    resolveClass(applicationClass);
                }
                return applicationClass;
            }
        }
        // Delegate to the classic classloader
        return super.loadClass(name, resolve);
    }

    public Class<?> loadApplicationClass(String name) {

        if (ApplicationClass.isClass(name) || !Play.usePrecompiled) {
            Class<?> maybeAlreadyLoaded = findLoadedClass(name);
            if (maybeAlreadyLoaded != null) {
                return maybeAlreadyLoaded;
            }
        }

        if (Play.usePrecompiled) {
            try {
                File file = Play.getFile("precompiled/java/" + name.replace('.', '/') + ".class");
                if (!file.exists()) {
                    return null;
                }
                byte[] code = IO.readContent(file);
                Class<?> clazz = findLoadedClass(name);
                if (clazz == null) {
                    if (name.endsWith("package-info")) {
                        String packageName = getPackageName(name);
                        if (getDefinedPackage(packageName) == null) {
                            definePackage(getPackageName(name), null, null, null, null, null, null, null);
                        }
                    } else {
                        loadPackage(name);
                    }
                    clazz = defineClass(name, code, 0, code.length, protectionDomain);
                }
                ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
                if (applicationClass != null) {
                    applicationClass.javaClass = clazz;
                    if (!applicationClass.isClass()) {
                        applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                    }
                }
                return clazz;
            } catch (Exception e) {
                throw new RuntimeException("Cannot find precompiled class file for " + name, e);
            }
        }

        long start = System.nanoTime();
        ApplicationClass applicationClass = Play.classes.getApplicationClass(name);
        if (applicationClass != null) {
            if (applicationClass.isDefinable()) {
                return applicationClass.javaClass;
            }
            byte[] bc = BytecodeCache.getBytecode(name, applicationClass.javaSource);

            if (Logger.isTraceEnabled()) {
                Logger.trace("Compiling code for %s", name);
            }

            if (!applicationClass.isClass()) {
                String packageName = applicationClass.getPackage();
                if (getDefinedPackage(packageName) == null) {
                    definePackage(packageName, null, null, null, null, null, null, null);
                }
            } else {
                loadPackage(name);
            }
            if (bc != null) {
                applicationClass.enhancedByteCode = bc;
                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0,
                        applicationClass.enhancedByteCode.length, protectionDomain);
                resolveClass(applicationClass.javaClass);
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("%sns to load class %s from cache", System.nanoTime() - start, name);
                }

                return applicationClass.javaClass;
            }
            if (applicationClass.javaByteCode != null || applicationClass.compile() != null) {
                applicationClass.enhance();

                applicationClass.javaClass = defineClass(applicationClass.name, applicationClass.enhancedByteCode, 0,
                        applicationClass.enhancedByteCode.length, protectionDomain);
                BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, name, applicationClass.javaSource);
                resolveClass(applicationClass.javaClass);
                if (!applicationClass.isClass()) {
                    applicationClass.javaPackage = applicationClass.javaClass.getPackage();
                }

                if (Logger.isTraceEnabled()) {
                    Logger.trace("%sns to load class %s", System.nanoTime() - start, name);
                }

                return applicationClass.javaClass;
            }
            Play.classes.classes.remove(name);
        }
        return null;
    }

    private String getPackageName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > -1 ? name.substring(0, dot) : "";
    }

    private void loadPackage(String className) {
        // find the package class name
        int symbol = className.indexOf('$');
        if (symbol > -1) {
            className = className.substring(0, symbol);
        }
        symbol = className.lastIndexOf('.');
        if (symbol > -1) {
            className = className.substring(0, symbol) + ".package-info";
        } else {
            className = "package-info";
        }
        if (this.findLoadedClass(className) == null) {
            this.loadApplicationClass(className);
        }
    }

    /**
     * Search for the byte code of the given class.
     */
    byte[] getClassDefinition(String name) {
        name = name.replace('.', '/') + ".class";
        InputStream is = this.getResourceAsStream(name);
        if (is == null) {
            return null;
        }
        try {
            return IOUtils.toByteArray(is);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        } finally {
            closeQuietly(is);
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                return res.inputstream();
            }
        }

        return super.getResourceAsStream(name);
    }

    @Override
    public URL getResource(String name) {
        URL url = null;
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                try {
                    url = res.getRealFile().toURI().toURL();
                    break;
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        if (url == null) {
            url = super.getResource(name);
            if (url != null) {
                try {
                    File file = new File(url.toURI());
                    String fileName = file.getCanonicalFile().getName();
                    if (!name.endsWith(fileName)) {
                        url = null;
                    }
                } catch (Exception ignore) {
                }
            }
        }
        return url;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (VirtualFile vf : Play.javaPath) {
            VirtualFile res = vf.child(name);
            if (res != null && res.exists()) {
                try {
                    urls.add(res.getRealFile().toURI().toURL());
                } catch (MalformedURLException ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
        Enumeration<URL> parent = super.getResources(name);
        while (parent.hasMoreElements()) {
            URL next = parent.nextElement();
            if (!urls.contains(next)) {
                urls.add(next);
            }
        }
        final Iterator<URL> it = urls.iterator();
        return new Enumeration<>() {

            @Override
            public boolean hasMoreElements() {
                return it.hasNext();
            }

            @Override
            public URL nextElement() {
                return it.next();
            }
        };
    }

    private static final AtomicBoolean watcherInstalled = new AtomicBoolean(false);
    private static final AtomicBoolean runRegularDetectChanges = new AtomicBoolean(false);
    private static final Map<Path, WatchKey> pathWatchKeyMap = new ConcurrentHashMap<>();

    public static void detectChangesWatcherCallback(WatchKey watchKey, List<WatchEvent<?>> events) {
        boolean clearCache = false;
        Path parentDir = (Path)watchKey.watchable();

        for(WatchEvent<?> event : events) {
            Path path = parentDir.resolve((Path)event.context());

            if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
	            WatchKey k = pathWatchKeyMap.remove(path);

                if (k != null) {
                    Play.unregisterWatcher(k);
                }

                if (Files.isDirectory(path)) {
                    // remove all classes in that dir
                    synchronized (Play.classes.pathMap) {
                        List<ApplicationClass> classesToRemove = new ArrayList<>();
                        for (Map.Entry<Path, ApplicationClass> entry : Play.classes.pathMap.entrySet()) {
                            if (entry.getKey().startsWith(path)) {
                                classesToRemove.add(entry.getValue());
                                clearCache = true;
                            }
                        }

                        for (ApplicationClass applicationClass : classesToRemove) {
                            removeClass(applicationClass, false);
                        }
                    }

                    synchronized (pathWatchKeyMap) {
                        // unregister any possible watches in that dir
                        for (Iterator<Map.Entry<Path, WatchKey>> it = pathWatchKeyMap.entrySet().iterator(); it.hasNext(); ) {
                            Map.Entry<Path, WatchKey> entry = it.next();
                            if (entry.getKey().startsWith(path)) {
                                Play.unregisterWatcher(entry.getValue());
                                it.remove();
                            }
                        }
                    }

                } else {
                    synchronized (Play.classes.pathMap) {
                        ApplicationClass applicationClass = Play.classes.pathMap.get(path);

                        if (applicationClass != null) {
                            removeClass(applicationClass, applicationClass.name.contains("$"));
                            clearCache = true;
                        }
                    }
                }
            } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                if (Files.isDirectory(path)) {
                    try {
                        Files.walkFileTree((Path) event.context(), Set.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, detectChangesAddingVisitor);
                    } catch(IOException ex) {
                        throw new UnexpectedException(ex);
                    }
                }
            } else if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                if (!Files.isDirectory(path)) {
                    if (Play.classes.pathMap.containsKey(path)) {
                        // TODO: handle file modified instead of running regular detect
                        clearCache = true;
                    }
                } else {
                    // TODO: dir rename
                    clearCache = true;
                }
            } else if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                clearCache = true;
            }
        }

        if (clearCache) {
            runRegularDetectChanges.compareAndSet(false, true);
        }
    }

    private static class DetectChangesVisitor implements FileVisitor<Path> {
        private final boolean addFiles;

        public DetectChangesVisitor(boolean addFiles) {
            this.addFiles = addFiles;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            synchronized (pathWatchKeyMap) {
                if (!pathWatchKeyMap.containsKey(dir)) {
                    pathWatchKeyMap.put(
                        dir,
                        Play.registerWatcher(dir, ApplicationClassloader::detectChangesWatcherCallback,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.OVERFLOW
                        )
                    );
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (addFiles) {
                String filename = file.getFileName().toString();
                if (file.endsWith(".java")) {
                    synchronized (Play.classes.pathMap) {
                        if (!Play.classes.pathMap.containsKey(file)) {
                            StringBuilder className = new StringBuilder(filename.substring(0, filename.length() - 5)); // ".java".length() = 5
                            AtomicReference<Path> currentPath = new AtomicReference<>(file);

                            while(Play.javaPath.stream().noneMatch(vf -> vf.getRealFile().toPath().equals(currentPath.get()))) {
                                Path cp = currentPath.get();
                                className.insert(0, cp.getFileName() + ".");
                                cp = cp.getParent();

                                if (cp == null) {
                                    throw new UnexpectedException("Unable to build className: " + file);
                                }

                                currentPath.set(cp);
                            }

                            ApplicationClass applicationClass = new ApplicationClass(className.toString());
                            Play.classes.add(applicationClass);
                        }
                    }
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc)  {
            return FileVisitResult.CONTINUE;
        }
    }


    private static final FileVisitor<Path> detectChangesRegisteringVisitor = new DetectChangesVisitor(false);
    private static final FileVisitor<Path> detectChangesAddingVisitor = new DetectChangesVisitor(true);


    /**
     * Detect Java changes
     * 
     * @throws play.exceptions.RestartNeededException
     *             Thrown if the application need to be restarted
     */
    public void detectChanges() throws RestartNeededException {
        if (watcherInstalled.compareAndSet(false, true)) {
            Path appPath = Play.applicationPath.toPath().resolve("app");

            try {
                Files.walkFileTree(appPath, Set.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, detectChangesRegisteringVisitor);
            } catch(IOException ex) {
                throw new UnexpectedException(ex);
            }
        }

        if (!runRegularDetectChanges.compareAndSet(true, false)) {
            return;
        }

        // Now check for file modification
        List<ApplicationClass> modifiedClasses = new ArrayList<>();
        for (ApplicationClass applicationClass : Play.classes.all()) {
            if (applicationClass.timestamp < applicationClass.javaFile.lastModified()) {
                applicationClass.refresh();
                modifiedClasses.add(applicationClass);
            }
        }

        Set<ApplicationClass> modifiedWithDependencies = new HashSet<>(modifiedClasses);

        List<ClassDefinition> newDefinitions = new ArrayList<>();
        boolean dirtySig = false;
        for (ApplicationClass applicationClass : modifiedWithDependencies) {
	        // show others that we have changed...
	        if (applicationClass.compile() == null) {
                Play.classes.classes.remove(applicationClass.name);
	        } else {
                int sigChecksum = applicationClass.sigChecksum;
                applicationClass.enhance();
                if (sigChecksum != applicationClass.sigChecksum) {
                    dirtySig = true;
                }
                BytecodeCache.cacheBytecode(applicationClass.enhancedByteCode, applicationClass.name, applicationClass.javaSource);
                newDefinitions.add(new ClassDefinition(applicationClass.javaClass, applicationClass.enhancedByteCode));
	        }
	        currentState = new ApplicationClassloaderState(); // show others that we have changed...
        }

        if (!newDefinitions.isEmpty()) {
            Cache.clear();
            if (HotswapAgent.enabled) {
                try {
                    HotswapAgent.reload(newDefinitions.toArray(new ClassDefinition[newDefinitions.size()]));
                } catch (Throwable e) {
                    throw new RestartNeededException(newDefinitions.size() + " classes changed", e);
                }
            } else {
                throw new RestartNeededException(newDefinitions.size() + " classes changed (and HotSwap is not enabled)");
            }
        }
        // Check signature (variable name & annotations aware !)
        if (dirtySig) {
            throw new RestartNeededException("Signature change !");
        }

        // Now check if there is new classes or removed classes
        boolean hashChanged = false;
        synchronized (classStateHashCreator) {
            int hash = computePathHash();
            if (hash != this.pathHash) {
                hashChanged = true;
                this.pathHash = hash; // prevent "Path has changed" loop
            }
        }

        if (hashChanged) {
            Set<String> changed = new LinkedHashSet<>();
            // Remove class for deleted files !!
            for (ApplicationClass applicationClass : Play.classes.all()) {
                boolean removed = false;
                if (!applicationClass.javaFile.exists()) {
                    removeClass(applicationClass, false);
                    removed = true;
                }

                if (applicationClass.name.contains("$")) {
                    removeClass(applicationClass, true);
                    removed = true;
                }

                if (removed) {
                    changed.add(applicationClass.name);
                }
            }

            currentState = new ApplicationClassloaderState(); // show others that we have changed...
            throw new RestartNeededException("Path has changed");
        }
    }

    private static void removeClass(ApplicationClass applicationClass, boolean removeAll) {
        Play.classes.remove(applicationClass.name);

        if (removeAll) {
            // Ok we have to remove all classes from the same file ...
            VirtualFile vf = applicationClass.javaFile;
            for (ApplicationClass ac : Play.classes.all()) {
                if (ac.javaFile.equals(vf)) {
                    Play.classes.remove(ac.name);
                }
            }
        }
    }

    /**
     * Used to track change of the application sources path
     */
    private int pathHash = 0;

    private int computePathHash() {
        return classStateHashCreator.computePathHash(Play.javaPath);
    }

    /**
     * Try to load all .java files found.
     * 
     * @return The list of well-defined Class
     */
    public List<Class<?>> getAllClasses() {
        if (allClasses == null) {
            List<Class<?>> result = new ArrayList<>();

            if (Play.usePrecompiled) {

                List<ApplicationClass> applicationClasses = new ArrayList<>();
                scanPrecompiled(applicationClasses, "", Play.getVirtualFile("precompiled/java"));
                Play.classes.clear();
                for (ApplicationClass applicationClass : applicationClasses) {
                    Play.classes.add(applicationClass);
                    Class<?> clazz = loadApplicationClass(applicationClass.name);
                    applicationClass.javaClass = clazz;
                    applicationClass.compiled = true;
                    result.add(clazz);
                }

            } else {

                if (!Play.pluginCollection.compileSources()) {

                    List<ApplicationClass> all = new ArrayList<>();

                    for (VirtualFile virtualFile : Play.javaPath) {
                        all.addAll(getAllClasses(virtualFile));
                    }
                    List<String> classNames = new ArrayList<>();
                    for (ApplicationClass applicationClass : all) {
                        if (applicationClass != null && !applicationClass.compiled && applicationClass.isClass()) {
                            classNames.add(applicationClass.name);
                        }
                    }

                    Play.classes.compiler.compile(classNames.toArray(new String[0]));

                }

                for (ApplicationClass applicationClass : Play.classes.all()) {
                    Class<?> clazz = loadApplicationClass(applicationClass.name);
                    if (clazz != null) {
                        result.add(clazz);
                    }
                }

                result.sort(Comparator.comparing(Class::getName));
            }

            Map<String, ApplicationClass> byNormalizedName = new HashMap<>(result.size());
            for (ApplicationClass clazz : Play.classes.all()) {
                byNormalizedName.put(clazz.name.toLowerCase(), clazz);
                if (clazz.name.contains("$")) {
                    byNormalizedName.put(Strings.CS.replace(clazz.name.toLowerCase(), "$", "."), clazz);
                }
            }

            allClassesByNormalizedName = unmodifiableMap(byNormalizedName);
            allClasses = unmodifiableList(result);
        }
        return allClasses;
    }

    private List<Class<?>> allClasses;
    private Map<String, ApplicationClass> allClassesByNormalizedName;

    /**
     * Retrieve all application classes assignable to this class.
     * 
     * @param clazz
     *            The superclass, or the interface.
     * @return A list of class
     */
    public List<Class> getAssignableClasses(Class<?> clazz) {
        if (clazz == null) {
            return Collections.emptyList();
        }
        getAllClasses();
        List<Class> results = assignableClassesByName.get(clazz.getName());
        if (results != null) {
            return results;
        } else {
            results = new ArrayList<>();
            for (ApplicationClass c : Play.classes.getAssignableClasses(clazz)) {
                results.add(c.javaClass);
            }
            // cache assignable classes
            assignableClassesByName.put(clazz.getName(), unmodifiableList(results));
        }
        return results;
    }

    // assignable classes cache
    private final Map<String, List<Class>> assignableClassesByName = new ConcurrentHashMap<>(100);

    /**
     * Find a class in a case-insensitive way
     * 
     * @param name
     *            The class name.
     * @return a class
     */
    public Class<?> getClassIgnoreCase(String name) {
        getAllClasses();
        String nameLowerCased = name.toLowerCase();
        ApplicationClass c = allClassesByNormalizedName.get(nameLowerCased);
        if (c != null) {
            if (Play.usePrecompiled) {
                return c.javaClass;
            }
            return loadApplicationClass(c.name);
        }
        return null;
    }

    /**
     * Retrieve all application classes with a specific annotation.
     * 
     * @param clazz
     *            The annotation class.
     * @return A list of class
     */
    public List<Class<?>> getAnnotatedClasses(Class<? extends Annotation> clazz) {
        getAllClasses();
        List<Class<?>> results = new ArrayList<>();
        for (ApplicationClass c : Play.classes.getAnnotatedClasses(clazz)) {
            results.add(c.javaClass);
        }
        return results;
    }

    public List<Class<?>> getAnnotatedClasses(Class<? extends Annotation>[] clazz) {
        List<Class<?>> results = new ArrayList<>();
        for (Class<? extends Annotation> cl : clazz) {
            results.addAll(getAnnotatedClasses(cl));
        }
        return results;
    }

    private List<ApplicationClass> getAllClasses(VirtualFile path) {
        return getAllClasses(path, "");
    }

    private List<ApplicationClass> getAllClasses(VirtualFile path, String basePackage) {
        if (!basePackage.isEmpty() && !basePackage.endsWith(".")) {
            basePackage += ".";
        }
        List<ApplicationClass> res = new ArrayList<>();
        for (VirtualFile virtualFile : path.list()) {
            scan(res, basePackage, virtualFile);
        }
        return res;
    }

    private void scan(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".java") && !current.getName().startsWith(".")) {
                String classname = packageName + current.getName().substring(0, current.getName().length() - 5);
                classes.add(Play.classes.getApplicationClass(classname));
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scan(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }

    private void scanPrecompiled(List<ApplicationClass> classes, String packageName, VirtualFile current) {
        if (!current.isDirectory()) {
            if (current.getName().endsWith(".class") && !current.getName().startsWith(".")) {
                String classname = packageName.substring(5) + current.getName().substring(0, current.getName().length() - 6);
                classes.add(new ApplicationClass(classname));
            }
        } else {
            for (VirtualFile virtualFile : current.list()) {
                scanPrecompiled(classes, packageName + current.getName() + ".", virtualFile);
            }
        }
    }
}
