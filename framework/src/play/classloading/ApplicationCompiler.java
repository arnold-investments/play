package play.classloading;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.IModule;
import org.eclipse.jdt.internal.compiler.env.IModuleAwareNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.ModuleBinding;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.exceptions.CompilationException;
import play.exceptions.UnexpectedException;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java compiler (uses eclipse JDT)
 */
public class ApplicationCompiler {

	private static final String JAVA_SOURCE_DEFAULT_VERSION = "22";
	static final Map<String, String> compatibleJavaVersions = Map.ofEntries(
		Map.entry("22", CompilerOptions.VERSION_22),
		Map.entry("23", CompilerOptions.VERSION_23),
		Map.entry("24", CompilerOptions.VERSION_24),
		Map.entry("25", CompilerOptions.VERSION_25)
	);

	final Map<String, char[][]> packagesCache = new HashMap<>();
	final ApplicationClasses applicationClasses;
	final Map<String, String> settings;

    private static final char[] MODULE_JAVA = new char[]{'j','a','v','a'};
    private static final char[] MODULE_JDK = new char[]{'j','d','k'};

    private static final char[] PACKAGE_JAVA = new char[]{'j','a','v','a'};
    private static final char[] PACKAGE_JAVAX = new char[]{'j','a','v','a','x'};
    private static final char[] PACKAGE_SUN = new char[]{'s','u','n'};
    private static final char[] PACKAGE_COM = new char[]{'c','o','m'};

    private static final char[] PACKAGE_PLAY = new char[]{'p','l','a','y'};

    private static final char[][] MODULES_UNNAMED = new char[][]{ModuleBinding.UNNAMED};

	/**
	 * Try to guess the magic configuration options
	 *
	 * @param applicationClasses
	 *            The application classes container
	 */
	public ApplicationCompiler(ApplicationClasses applicationClasses) {
		final String runningJavaVersion = System.getProperty("java.version");
		if (Stream.of("1.5", "1.6", "1.7", "1.8", "9", "10", "11", "12", "13", "14", "15", "16",  "17", "18", "19", "20", "21").anyMatch(runningJavaVersion::startsWith)) {
			throw new CompilationException("JDK version prior to 22 are not supported to run the application");
		}

		final String configSourceVersion = Play.configuration.getProperty("java.source", JAVA_SOURCE_DEFAULT_VERSION);
		final String jdtVersion = compatibleJavaVersions.get(configSourceVersion);
		if (jdtVersion == null) {
			throw new CompilationException(String.format("Incompatible Java version specified (%s). Compatible versions are: %s",
				configSourceVersion, compatibleJavaVersions.keySet()));
		}

		this.applicationClasses = applicationClasses;
		this.settings = Map.ofEntries(
			Map.entry(CompilerOptions.OPTION_ReportMissingSerialVersion, CompilerOptions.IGNORE),
			Map.entry(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE),
			Map.entry(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE),
			Map.entry(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE),
			Map.entry(CompilerOptions.OPTION_ReportUnusedImport, CompilerOptions.IGNORE),
			Map.entry(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE),
			Map.entry(CompilerOptions.OPTION_PreserveUnusedLocal, CompilerOptions.PRESERVE),
			Map.entry(CompilerOptions.OPTION_MethodParametersAttribute, CompilerOptions.GENERATE),
			Map.entry(CompilerOptions.OPTION_Encoding, "UTF-8"),
			Map.entry(CompilerOptions.OPTION_Source, jdtVersion),
			Map.entry(CompilerOptions.OPTION_TargetPlatform, jdtVersion),
			Map.entry(CompilerOptions.OPTION_Compliance, jdtVersion)
		);
	}

	/**
	 * Something to compile
	 */
	final class CompilationUnit implements ICompilationUnit {

		private final String clazzName;
		private final String fileName;
		private final char[] typeName;
		private final char[][] packageName;

		CompilationUnit(String pClazzName) {
			clazzName = pClazzName;
			if (pClazzName.contains("$")) {
				pClazzName = pClazzName.substring(0, pClazzName.indexOf('$'));
			}
			fileName = pClazzName.replace('.', '/') + ".java";
			int dot = pClazzName.lastIndexOf('.');
			if (dot > 0) {
				typeName = pClazzName.substring(dot + 1).toCharArray();
			} else {
				typeName = pClazzName.toCharArray();
			}
			StringTokenizer izer = new StringTokenizer(pClazzName, ".");
			packageName = new char[izer.countTokens() - 1][];
			for (int i = 0; i < packageName.length; i++) {
				packageName[i] = izer.nextToken().toCharArray();
			}
		}

		@Override
		public char[] getFileName() {
			return fileName.toCharArray();
		}

		@Override
		public char[] getContents() {
			return applicationClasses.getApplicationClass(clazzName).javaSource.toCharArray();
		}

		@Override
		public char[] getMainTypeName() {
			return typeName;
		}

		@Override
		public char[][] getPackageName() {
			return packageName;
		}

		@Override
		public boolean ignoreOptionalProblems() {
			return false;
		}
    }


	/**
	 * Please compile this className
	 *
	 * @param classNames
	 *            Arrays of the class name to compile
	 */
	@SuppressWarnings("deprecation")
	public void compile(String[] classNames) {
		// Deduplicate and remove inner class suffixes
		Set<String> uniqueNames = new LinkedHashSet<>();
		for (String className : classNames) {
			if (className.contains("$")) {
				uniqueNames.add(className.substring(0, className.indexOf('$')));
			} else {
				uniqueNames.add(className);
			}
		}

		ICompilationUnit[] compilationUnits = new CompilationUnit[uniqueNames.size()];
		int i = 0;
		for (String name : uniqueNames) {
			compilationUnits[i++] = new CompilationUnit(name);
		}
		IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.exitOnFirstError();
		IProblemFactory problemFactory = new DefaultProblemFactory(Locale.ENGLISH);

        String javaHome = System.getProperty("java.home");

        // Most JDKs ship with lib/jrt-fs.jar
        String jrtFsJar = javaHome + File.separator + "lib" + File.separator + "jrt-fs.jar";
        String[] classpath = new String[] { jrtFsJar };
        FileSystem fs = new FileSystem(classpath, null, "UTF-8");

        List<ApplicationClass> compiledClasses = new ArrayList<>();

		// To find types ...
        INameEnvironment nameEnvironment = new IModuleAwareNameEnvironment() {
            private boolean isJavaModuleOrPackage(char[] moduleName, char[][] name) {
                if (moduleName != null && moduleName.length > 4 && ((moduleName[0] == 'j' && moduleName[1] == 'd' && moduleName[2] == 'k' && moduleName[3] == '.') || (moduleName.length > 5 && moduleName[0] == 'j' && moduleName[1] == 'a' && moduleName[2] == 'v' && moduleName[3] == 'a' && moduleName[4] == '.'))) {
                    return true;
                }

                // do not add javax here, as some packages (especially xml ones) provide their own javax subpackages
                if (name.length > 0 && (Arrays.equals(PACKAGE_JAVA, name[0]) || Arrays.equals(PACKAGE_SUN, name[0]))) {
                    return true;
                }

                return false;
            }


            /**
             * Ignores module for now
             */
            @Override
            public NameEnvironmentAnswer findType(char[][] compoundName, char[] moduleName) {
                NameEnvironmentAnswer a1 = fs.findType(compoundName, moduleName);
                if (a1 != null || isJavaModuleOrPackage(moduleName, compoundName)) {
                    return a1;
                }

                StringBuilder result = new StringBuilder(compoundName.length * 7);
                for (int i = 0; i < compoundName.length; i++) {
                    if (i != 0) {
                        result.append('.');
                    }
                    result.append(compoundName[i]);
                }

                return findType(result.toString());
            }

            /**
             * Ignores module for now
             */
            @Override
            public NameEnvironmentAnswer findType(char[] typeName, char[][] packageName, char[] moduleName) {
                NameEnvironmentAnswer a1 = fs.findType(typeName, packageName, moduleName);
                if (a1 != null || isJavaModuleOrPackage(moduleName, packageName)) {
                    return a1;
                }

                StringBuilder result = new StringBuilder(packageName.length * 7 + 1 + typeName.length);
                for (final char[] element : packageName) {
                    result.append(element);
                    result.append('.');
                }

                result.append(typeName);

                return findType(result.toString());
            }

            private NameEnvironmentAnswer findType(String name) {
                try {
                    if (name.startsWith("play.")) {
                        byte[] bytes = Play.classloader.getClassDefinition(name);
                        if (bytes != null) {
                            ClassFileReader classFileReader = new ClassFileReader(bytes, name.toCharArray(), true);
                            return new NameEnvironmentAnswer(classFileReader, null);
                        }
                        return null;
                    }

                    char[] fileName = name.toCharArray();
                    ApplicationClass applicationClass = applicationClasses.getApplicationClass(name);

                    // ApplicationClass exists
                    if (applicationClass != null) {

                        if (applicationClass.javaByteCode == null) {
                            byte[] bc = BytecodeCache.getBytecode(name, applicationClass.javaSource);
                            if (bc != null) {
                                applicationClass.compiled(bc);
                            }
                        }

                        if (applicationClass.javaByteCode != null) {
                            ClassFileReader classFileReader = new ClassFileReader(applicationClass.javaByteCode, fileName, true);
                            return new NameEnvironmentAnswer(classFileReader, null);
                        }
                        // Cascade compilation
                        ICompilationUnit compilationUnit = new CompilationUnit(name);
                        return new NameEnvironmentAnswer(compilationUnit, null);
                    }

                    // So it's a standard class
                    byte[] bytes = Play.classloader.getClassDefinition(name);
                    if (bytes != null) {
                        ClassFileReader classFileReader = new ClassFileReader(bytes, fileName, true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    }

                    // So it does not exist
                    return null;
                } catch (ClassFormatException e) {
                    // Something very very bad
                    throw new UnexpectedException(e);
                }
            }

            /**
             * This will not handle other modules declaring the same packages as the play project
             */
            @Override
            public char[][] getModulesDeclaringPackage(char[][] packageName, char[] moduleName) {
                if (packageName.length == 0) {
                    throw new IllegalStateException("Empty package name");
                }

                if (isJavaModuleOrPackage(moduleName, packageName)) {
                    return fs.getModulesDeclaringPackage(packageName, moduleName);
                }

                String name = Arrays.stream(packageName).map(String::valueOf).collect(Collectors.joining("."));
                if (packagesCache.containsKey(name)) {
                    return packagesCache.get(name);
                }

                List<char[]> modules;
                char[][] fsModules = fs.getModulesDeclaringPackage(packageName, moduleName);

                if (fsModules != null && fsModules.length > 0) {
                    modules = new ArrayList<>(Arrays.asList(fsModules));
                } else {
                    modules = new ArrayList<>();
                }

                if (!modules.contains(ModuleBinding.UNNAMED) && Play.classloader.getResource(name.replace('.', '/')) != null) {
                    // if a directory matches the package name
                    modules.add(ModuleBinding.UNNAMED);
                }

                char[][] cached = modules.toArray(new char[modules.size()][]);
                packagesCache.put(name, cached);

                return cached;
            }

            private final List<Map<char[], Map<char[][], Boolean>>> hasCUCache = List.of(new HashMap<>(), new HashMap<>());

            @Override
            public boolean hasCompilationUnit(char[][] qualifiedPackageName, char[] moduleName, boolean checkCUs) {
                int index = checkCUs ? 1 : 0;

                return hasCUCache.get(index)
                    .computeIfAbsent(moduleName, m ->
                        new HashMap<>()
                    )
                    .computeIfAbsent(qualifiedPackageName, p -> {
                        boolean res = fs.hasCompilationUnit(qualifiedPackageName, moduleName, checkCUs);
                        if (res || isJavaModuleOrPackage(moduleName, qualifiedPackageName)) {
                            return res;
                        }

                        // TODO
                        return false;

                        /*
                        Enumeration<URL> res;
                        try {
                            res = Play.classloader.getResources(Arrays.stream(qualifiedPackageName).map(String::valueOf).collect(Collectors.joining("/")));
                        } catch (IOException e) {
                            return false;
                        }

                        Iterator<URL> it = res.asIterator();
                        ArrayList<URL> urls = new ArrayList<>();
                        it.forEachRemaining(urls::add);

                        if (urls.isEmpty()) {
                            return false;
                        }

                        VirtualFile vf = new VirtualFile();

                         */
                    });

            }

            @Override
            public boolean isOnModulePath(ICompilationUnit unit) {
                return false; // TODO: probably not correct for everything
            }

            @Override
            public IModule getModule(char[] moduleName) {
                return fs.getModule(moduleName); // unnamed module should return null, so this works for play stuff as well
            }

            @Override
            public char[][] getAllAutomaticModules() {
                return fs.getAllAutomaticModules(); // we're only in the unnamed module, so this should be fine
            }

            @Override
            public char[][] listPackages(char[] moduleName) {
                return fs.listPackages(moduleName); // should be okay, as this only supports named modules anyway
            }

            @Override
            public void cleanup() {
                fs.cleanup();
            }
        };

		// Compilation result
		ICompilerRequestor compilerRequestor = result -> {
			// If error
			if (result.hasErrors()) {
				for (IProblem problem : result.getErrors()) {
					String className = new String(problem.getOriginatingFileName()).replace('/', '.');
					className = className.substring(0, className.length() - 5);
					String message = problem.getMessage();
					if (problem.getID() == IProblem.CannotImportPackage) {
						// Nonsense !
						message = problem.getArguments()[0] + " cannot be resolved";
					}
					throw new CompilationException(Play.classes.getApplicationClass(className).javaFile, message,
							problem.getSourceLineNumber(), problem.getSourceStart(), problem.getSourceEnd());
				}
			}
			// Something has been compiled
			ClassFile[] clazzFiles = result.getClassFiles();
			for (final ClassFile clazzFile : clazzFiles) {
				char[][] compoundName = clazzFile.getCompoundName();
				StringBuilder clazzName = new StringBuilder();
				for (int j = 0; j < compoundName.length; j++) {
					if (j != 0) {
						clazzName.append('.');
					}
					clazzName.append(compoundName[j]);
				}

				if (Logger.isTraceEnabled()) {
					Logger.trace("Compiled %s", clazzName);
				}

				ApplicationClass ac = applicationClasses.getApplicationClass(clazzName.toString());
				ac.compiled(clazzFile.getBytes());
				compiledClasses.add(ac);

				// Capture dependencies
				if (result.qualifiedReferences != null) {
					for (char[][] ref : result.qualifiedReferences) {
						String depName = Arrays.stream(ref).map(String::valueOf).collect(Collectors.joining("."));
						if (applicationClasses.getApplicationClass(depName) != null) {
							ac.dependencies.add(depName);
						} else {
							// Try to resolve as inner class
							while (depName.contains(".")) {
								depName = depName.substring(0, depName.lastIndexOf('.')) + "$" + depName.substring(depName.lastIndexOf('.') + 1);
								if (applicationClasses.getApplicationClass(depName) != null) {
									ac.dependencies.add(depName);
									break;
								}
							}
						}
					}
				}
			}
		};


		// The JDT compiler
		Compiler jdtCompiler = new Compiler(nameEnvironment, policy, new CompilerOptions(settings), compilerRequestor, problemFactory) {

			@Override
			protected void handleInternalException(Throwable e, CompilationUnitDeclaration ud, CompilationResult result) {
			}
		};

		jdtCompiler.useSingleThread = false;

		// Go !
		jdtCompiler.compile(compilationUnits);

		for (ApplicationClass ac : compiledClasses) {
			ac.computeSignatures();
			BytecodeCache.cacheBytecode(ac.javaByteCode, ac.name, ac.javaSource);
		}

	}
}
