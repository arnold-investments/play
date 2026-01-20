package play.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Assertions;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import play.Logger;
import play.Play;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.mvc.Scope.RenderArgs;
import play.vfs.VirtualFile;

/**
 * Run application tests
 */
public class TestEngine {

    private static final class ClassNameComparator implements Comparator<Class> {
        @Override
        public int compare(Class aClass, Class bClass) {
            return aClass.getName().compareTo(bClass.getName());
        }
    }

    private static final ClassNameComparator classNameComparator = new ClassNameComparator();

    public static final ExecutorService functionalTestsExecutor = Executors.newSingleThreadExecutor();

    public static List<Class> allUnitTests() {
        List<Class> classes = new ArrayList<>();
        classes.addAll(Play.classloader.getAssignableClasses(BaseTest.class));
        classes.addAll(Play.pluginCollection.getUnitTests());
        for (ListIterator<Class> it = classes.listIterator(); it.hasNext();) {
            Class c = it.next();
            if (Modifier.isAbstract(c.getModifiers())) {
                it.remove();
            } else {
                if (FunctionalTest.class.isAssignableFrom(c)) {
                    it.remove();
                }
            }
        }
        classes.sort(classNameComparator);
        return classes;
    }

    public static List<Class> allFunctionalTests() {
        List<Class> classes = new ArrayList<>();
        classes.addAll(Play.classloader.getAssignableClasses(FunctionalTest.class));
        classes.addAll(Play.pluginCollection.getFunctionalTests());

        classes.removeIf(aClass -> Modifier.isAbstract(aClass.getModifiers()));
        classes.sort(classNameComparator);
        return classes;
    }

    public static List<String> seleniumTests(String testPath, List<String> results) {
        File testDir = Play.getFile(testPath);
        if (testDir.exists()) {
            scanForSeleniumTests(testDir, results);
        }
        return results;
    }

    public static List<String> allSeleniumTests() {
        List<String> results = new ArrayList<>();
        seleniumTests("test", results);
        for (VirtualFile root : Play.roots) {
            seleniumTests(root.relativePath() + "/test", results);
        }
        Collections.sort(results);
        return results;
    }

    private static void scanForSeleniumTests(File dir, List<String> tests) {
        for (File f : dir.listFiles()) {
            if (f.isDirectory()) {
                scanForSeleniumTests(f, tests);
            } else if (f.getName().endsWith(".test.html")) {
                String test = f.getName();
                while (!f.getParentFile().getName().equals("test")) {
                    test = f.getParentFile().getName() + "/" + test;
                    f = f.getParentFile();
                }
                tests.add(test);
            }
        }
    }
    
    public static void initTest(Context context, Class<?> testClass) {
        CleanTest cleanTestAnnot = null;
        if(testClass != null ){
            cleanTestAnnot = testClass.getAnnotation(CleanTest.class) ;
        }
        if(cleanTestAnnot != null && cleanTestAnnot.removeCurrent() == true){
            if (context != null) {
                context.setRequest(null);
                context.setResponse(null);
                context.setRenderArgs(null);
            }
        }
        if (cleanTestAnnot == null || (cleanTestAnnot != null && cleanTestAnnot.createDefault() == true)) {
            if (context != null && context.getRequest() instanceof Request request) {
                // Use base URL to create a request for this host
                // host => with port
                // domain => without port
                String host = Router.getBaseUrl(request);
                String domain = null;
                int port = 80;
                boolean isSecure = false;
                if (host == null || host.equals("application.baseUrl")) {
                    host = "localhost:" + port;
                    domain = "localhost";
                } else if (host.contains("http://")) {
                    host = host.replaceAll("http://", "");
                } else if (host.contains("https://")) {
                    host = host.replaceAll("https://", "");
                    port = 443;
                    isSecure = true;         
                }
                int colonPos =  host.indexOf(':');
                if(colonPos > -1){
                    domain = host.substring(0, colonPos);
                    port = Integer.parseInt(host.substring(colonPos+1));
                }else{
                   domain = host;
                }
                Request newRequest = Request.createRequest(null, "GET", "/", "", null,
                        null, null, host, false, port, domain, isSecure, null, null);
                newRequest.body = new ByteArrayInputStream(new byte[0]);
                context.setRequest(newRequest);
            }

            if (context == null || context.getResponse() == null) {
                Response response = new Response();
                response.out = new ByteArrayOutputStream();
                response.direct = null;
                context = new Context(new Request(), response);
            }

            if (context.getRenderArgs() == null) {
                RenderArgs renderArgs = new RenderArgs();
                context.setRenderArgs(renderArgs);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static TestResults run(Context context, String name) {
        TestResults testResults = new TestResults();

        try {
            // Load test class
            Class testClass = Play.classloader.loadClass(name);
                 
            initTest(context, testClass);
            
            TestResults pluginTestResults = Play.pluginCollection.runTest(testClass);
            if (pluginTestResults != null) {
                return pluginTestResults;
            }

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(testClass))
                    .build();
            Launcher launcher = LauncherFactory.create();
            launcher.registerTestExecutionListeners(new Listener(testClass.getName(), testResults));
            launcher.execute(request);

        } catch (ClassNotFoundException e) {
            Logger.error(e, "Test not found %s", name);
        }

        return testResults;
    }

    // ~~~~~~ Run listener
    static class Listener implements TestExecutionListener {

        final TestResults results;
        final String className;
        TestResult current;

        public Listener(String className, TestResults results) {
            this.results = results;
            this.className = className;
        }

        @Override
        public void executionStarted(TestIdentifier testIdentifier) {
            if (testIdentifier.isTest()) {
                current = new TestResult();
                current.name = testIdentifier.getDisplayName();
                current.time = System.currentTimeMillis();
            }
        }

        @Override
        public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
            if (testIdentifier.isTest()) {
                current.time = System.currentTimeMillis() - current.time;
                if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {
                    current.passed = false;
                    results.passed = false;
                    Throwable throwable = testExecutionResult.getThrowable().orElse(null);
                    if (throwable != null) {
                        if (throwable instanceof AssertionError) {
                            current.error = "Failure, " + throwable.getMessage();
                        } else {
                            current.error = "A " + throwable.getClass().getName() + " has been caught, " + throwable.getMessage();
                        }
                        current.trace = getStackTrace(throwable);
                        for (StackTraceElement stackTraceElement : throwable.getStackTrace()) {
                            if (stackTraceElement.getClassName().equals(className)) {
                                current.sourceInfos = "In " + Play.classes.getApplicationClass(className).javaFile.relativePath() + ", line " + stackTraceElement.getLineNumber();
                                current.sourceCode = Play.classes.getApplicationClass(className).javaSource.split("\n")[stackTraceElement.getLineNumber() - 1];
                                current.sourceFile = Play.classes.getApplicationClass(className).javaFile.relativePath();
                                current.sourceLine = stackTraceElement.getLineNumber();
                            }
                        }
                    }
                }
                results.add(current);
            } else if (testIdentifier.isContainer() && testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {
                // Handle failures in @BeforeAll or class-level setup
                if (current == null) {
                    current = new TestResult();
                    current.name = "Before any test started (Setup error)";
                    current.time = System.currentTimeMillis();
                    current.passed = false;
                    results.passed = false;
                    testExecutionResult.getThrowable().ifPresent(t -> {
                        current.error = "A " + t.getClass().getName() + " has been caught, " + t.getMessage();
                        current.trace = getStackTrace(t);
                    });
                    results.add(current);
                }
            }
        }

        private String getStackTrace(Throwable throwable) {
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            throwable.printStackTrace(pw);
            return sw.toString();
        }
    }

    public static class TestResults {

        public List<TestResult> results = new ArrayList<>();
        public boolean passed = true;
        public int success = 0;
        public int errors = 0;
        public int failures = 0;
        public long time = 0;

        public void add(TestResult result) {
            time = result.time + time;
            this.results.add(result);
            if (result.passed) {
              success++;
            } else {
              if (result.error.startsWith("Failure")) {
                failures++;
              } else {
                errors++;
              }
            }
        }
    }

    public static class TestResult {

        public String name;
        public String error;
        public boolean passed = true;
        public long time;
        public String trace;
        public String sourceInfos;
        public String sourceCode;
        public String sourceFile;
        public int sourceLine;
    }
}
