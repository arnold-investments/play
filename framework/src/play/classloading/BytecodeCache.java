package play.classloading;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.FileUtils.writeByteArrayToFile;

/**
 * Used to speed up compilation time
 */
public class BytecodeCache {
    private static String replaceSpecialChars(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        StringBuilder result = null;
        int length = name.length();
        
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '{' || c == '}' || c == ':') {
                if (result == null) {
                    // Lazy initialization - only create StringBuilder when needed
                    result = new StringBuilder(length);
                    result.append(name, 0, i);
                }
                result.append('_');
            } else if (result != null) {
                result.append(c);
            }
        }
        
        return result != null ? result.toString() : name;
    }

    /**
     * Delete the bytecode
     * @param name Cache name
     */
    public static void deleteBytecode(String name) {
        try {
            if (Play.tmpDir == null || Play.readOnlyTmp || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return;
            }
            File f = cacheFile(replaceSpecialChars(name));
            if (f.exists()) {
                boolean _ = f.delete();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve the bytecode if the source has not changed
     * @param name The cache name
     * @param source The source code
     * @return The bytecode
     */
    public static byte[] getBytecode(String name, String source) {
        return getBytecode(name, source, new HashSet<>());
    }

    private static String getSourceHash(ApplicationClass ac, String source) {
        if (ac != null) {
            return ac.getJavaSourceHash();
        }

        return hash(source);
    }

    private static byte[] getBytecode(String name, String source, Set<String> visited) {
        try {
            if (Play.tmpDir == null || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return null;
            }
            ApplicationClass ac = Play.classes.getApplicationClass(name);
            if (source == null && ac != null) {
                // If source is not provided (dependency validation), ensure the ApplicationClass is not stale
                if (ac.javaFile != null && ac.timestamp > 0 && ac.timestamp < ac.javaFile.lastModified()) {
                    ac.refresh();
                }
                source = ac.javaSource;
            }
            if (source == null) {
                return null;
            }

            File f = cacheFile(replaceSpecialChars(name));
            if (!f.exists()) {
                if (Logger.isTraceEnabled() && !visited.contains(name)) {
                    Logger.trace("Cache MISS for %s", name);
                }
                return null;
            }

            visited.add(name);

            try (DataInputStream dis = new DataInputStream(new FileInputStream(f))) {
                // 1. Source Hash
                byte[] hashBytes = new byte[32];
                dis.readFully(hashBytes);

                if (!Arrays.equals(getSourceHash(ac, source).getBytes(UTF_8), hashBytes)) {
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("Bytecode too old for %s", name);
                    }
                    return null;
                }

                // 2. sigChecksum
                int cachedSigChecksum = dis.readInt();
                
                // 3. staticFinalSigChecksum
                int cachedStaticFinalSigChecksum = dis.readInt();
                
                // 4. Dependencies
                int depCount = dis.readShort();
                for (int i = 0; i < depCount; i++) {
                    String depName = dis.readUTF();
                    int expectedDepStaticFinalSig = dis.readInt();

                    if (ac == null) {
                        continue;
                    }

                    if (visited.contains(depName)) {
                        continue;
                    }

                    byte[] depBc = getBytecode(depName, null, visited);
                    if (depBc == null) {
                        if (Logger.isTraceEnabled()) {
                            Logger.trace("Dependency %s for %s is invalid", depName, name);
                        }
                        return null;
                    }

                    ApplicationClass depAc = Play.classes.getApplicationClass(depName);
                    if (depAc == null || depAc.staticFinalSigChecksum != expectedDepStaticFinalSig) {
                        if (Logger.isTraceEnabled()) {
                            Logger.trace("Dependency %s for %s has changed static final properties", depName, name);
                        }
                        return null;
                    }
                }

                // 5. Bytecode
                int bytecodeLen = dis.readInt();
                byte[] byteCode = new byte[bytecodeLen];
                dis.readFully(byteCode);

                // Update ApplicationClass metadata
                if (ac != null) {
                    ac.sigChecksum = cachedSigChecksum;
                    ac.staticFinalSigChecksum = cachedStaticFinalSigChecksum;
                    ac.staticFinalSigComputed = true;
                }

                return byteCode;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Cache the bytecode
     * @param byteCode The bytecode
     * @param name The cache name
     * @param source The corresponding source
     */
    public static void cacheBytecode(byte[] byteCode, String name, String source) {
        try {
            if (Play.tmpDir == null || Play.readOnlyTmp || !Play.configuration.getProperty("play.bytecodeCache", "true").equals("true")) {
                return;
            }
            ApplicationClass ac = Play.classes.getApplicationClass(name);

            File f = cacheFile(replaceSpecialChars(name));
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(f))) {
                dos.write(getSourceHash(ac, source).getBytes(UTF_8));
                dos.writeInt(ac != null ? ac.sigChecksum : 0);
                dos.writeInt(ac != null ? ac.staticFinalSigChecksum : 0);

                // Filter dependencies: only those that have static final properties
                List<String> filteredDeps = new ArrayList<>();
                if (ac != null) {
                    for (String depName : ac.dependencies) {
                        ApplicationClass depAc = Play.classes.getApplicationClass(depName);
                        if (depAc != null) {
                            if (!depAc.staticFinalSigComputed) {
                                // Recursive signature lookup: try to get it from cache
                                getBytecode(depName, null);
                            }
    
                            if (depAc.staticFinalSigComputed) {
                                if (depAc.staticFinalSigChecksum != 0) {
                                    filteredDeps.add(depName);
                                }
                            } else if (depAc.javaByteCode != null) {
                                // If signatures not yet computed, check bytecode for static final fields
                                int depStaticFinalSig = getStaticFinalSigFromBytecode(depAc.javaByteCode);
                                depAc.staticFinalSigChecksum = depStaticFinalSig;
                                depAc.staticFinalSigComputed = true;
                                if (depStaticFinalSig != 0) {
                                    filteredDeps.add(depName);
                                }
                            }
                        }
                    }
                }

                dos.writeShort(filteredDeps.size());
                for (String depName : filteredDeps) {
                    ApplicationClass depAc = Play.classes.getApplicationClass(depName);
                    dos.writeUTF(depName);
                    dos.writeInt(depAc.staticFinalSigChecksum);
                }

                dos.writeInt(byteCode.length);
                dos.write(byteCode);
            }

            // emit bytecode to standard class layout as well
            if (!name.contains("/") && !name.contains("{")) {
                f = new File(Play.tmpDir, "classes/" + name.replace('.', '/') + ".class");
                if (!f.getParentFile().exists()) {
                    f.getParentFile().mkdirs();
                }
                writeByteArrayToFile(f, byteCode);
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("%s cached", name);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int getStaticFinalSigFromBytecode(byte[] byteCode) {
        try {
            javassist.CtClass ctClass = play.classloading.enhancers.Enhancer.classPool.makeClass(new ByteArrayInputStream(byteCode));
            StringBuilder sb = new StringBuilder();
            for (javassist.CtField field : ctClass.getDeclaredFields()) {
                if (javassist.Modifier.isStatic(field.getModifiers()) && javassist.Modifier.isFinal(field.getModifiers())) {
                    Object constantValue = field.getConstantValue();
                    if (constantValue != null) {
                        sb.append(field.getName()).append(":").append(field.getSignature()).append("=").append(constantValue).append(",");
                    }
                }
            }
            return !sb.isEmpty() ? sb.toString().hashCode() : 0;
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /**
     * Build a hash of the source code.
     * To efficiently track source code modifications.
     */
    static String hash(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.reset();
            messageDigest.update((Play.version + text).getBytes(UTF_8));
            byte[] digest = messageDigest.digest();
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve the real file that will be used as a cache.
     */
    static File cacheFile(String id) {
        File dir = new File(Play.tmpDir, "bytecode/" + Play.mode.name());
        if (!dir.exists() && Play.tmpDir != null && !Play.readOnlyTmp) {
            dir.mkdirs();
        }
        return new File(dir, id);
    }
}
