package controllers;

import play.Logger;
import play.Play;
import play.cache.Cache;
import play.jobs.Job;
import play.libs.IO;
import play.libs.Mail;
import play.mvc.Context;
import play.mvc.Controller;
import play.mvc.Router;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.test.TestEngine;
import play.vfs.VirtualFile;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestRunner extends Controller {

    public TestRunner(Context context) {
        super(context);
    }

    public void index() {
        List<Class> unitTests = TestEngine.allUnitTests();
        List<Class> functionalTests = TestEngine.allFunctionalTests();
        List<String> seleniumTests = TestEngine.allSeleniumTests();

        context.getRenderArgs().put("unitTests", unitTests);
        context.getRenderArgs().put("functionalTests", functionalTests);
        context.getRenderArgs().put("seleniumTests", seleniumTests);

        render(context);
    }

    public void list(Boolean runUnitTests, Boolean runFunctionalTests, Boolean runSeleniumTests) {
        StringWriter list = new StringWriter();
        PrintWriter p = new PrintWriter(list);
        p.println("---");
        p.println(Play.getFile("test-result").getAbsolutePath());
        p.println(Router.reverse(context.getRequest(), Play.modules.get("_testrunner").child("/public/test-runner/selenium/TestRunner.html")));
        
        List<Class> unitTests = null;
        List<Class> functionalTests =  null;
        List<String> seleniumTests = null;
        // Check configuration of test
        // method parameters have priority on configuration param
        if (runUnitTests == null || runUnitTests) {
            unitTests = TestEngine.allUnitTests();
        }
        if (runFunctionalTests == null || runFunctionalTests) {
            functionalTests = TestEngine.allFunctionalTests();
        }
        if (runSeleniumTests == null || runSeleniumTests) {
            seleniumTests = TestEngine.allSeleniumTests();
        }
        
        if(unitTests != null){
            for(Class c : unitTests) {
                p.println(c.getName() + ".class");
            }
        }
        if(functionalTests != null){
            for(Class c : functionalTests) {
                p.println(c.getName() + ".class");
            }
        }
        if(seleniumTests != null){
            for(String c : seleniumTests) {
                p.println(c);
            }
        }
        renderText(list);
    }

    public void run(String test) throws Exception {
        if (test.equals("init")) {
           
            File testResults = Play.getFile("test-result");
            if (!testResults.exists()) {
                testResults.mkdir();
            }
            for(File tr : testResults.listFiles()) {
                if ((tr.getName().endsWith(".html") || tr.getName().startsWith("result.")) && !tr.delete()) {
                    Logger.warn("Cannot delete %s ...", tr.getAbsolutePath());
                }
            }

            renderText("done");
        }

        if (test.equals("end")) {

            File testResults = Play.getFile("test-result/result." + context.getParams().get("result"));
          
            IO.writeContent(context.getParams().get("result"), testResults);
            renderText("done");
        }

        if (test.endsWith(".class")) {
            Play.getFile("test-result").mkdir();

            final String testname = test.substring(0, test.length() - 6);
            final String finalTest = test;

            await(new Job<TestEngine.TestResults>(new Context(null, null)) {
                @Override
                public TestEngine.TestResults doJobWithResult() throws Exception {
                    return TestEngine.run(this.context, testname);
                }
            }.now(), (results) -> {
                context.getResponse().status = results.passed ? 200 : 500;
                Template resultTemplate = TemplateLoader.load("TestRunner/results.html");
                Map<String, Object> options = new HashMap<String, Object>();
                options.put("test", finalTest);
                options.put("results", results);
                String result = resultTemplate.render(context, options);
                File testResults = Play.getFile("test-result/" + finalTest + (results.passed ? ".passed" : ".failed") + ".html");
                IO.writeContent(result, testResults);
                try {
                    // Write xml output
                    options.remove("out");
                    resultTemplate = TemplateLoader.load("TestRunner/results-xunit.xml");
                    String resultXunit = resultTemplate.render(context, options);
                    File testXunitResults = Play.getFile("test-result/TEST-" + finalTest.substring(0, finalTest.length()-6) + ".xml");
                    IO.writeContent(resultXunit, testXunitResults);
                } catch(Exception e) {
                    Logger.error(e, "Cannot ouput XML unit output");
                }

                context.getResponse().contentType = "text/html";
                renderText(result);
            });
        } else if (test.endsWith(".test.html.suite")) {
            test = test.substring(0, test.length() - 6);
            context.getRenderArgs().put("test", test);
            renderTemplate(context, "TestRunner/selenium-suite.html");
        } else if (test.endsWith(".test.html")) {
            File testFile = Play.getFile("test/" + test);

            if (!testFile.exists()) {
                for(VirtualFile root : Play.roots) {
                    File moduleTestFile = Play.getFile(root.relativePath()+"/test/" + test);
                    if(moduleTestFile.exists()) {
                        testFile = moduleTestFile;
                    }
                }
            }

            if (testFile.exists()) {
                Template testTemplate = TemplateLoader.load(VirtualFile.open(testFile));
                Map<String, Object> options = new HashMap<String, Object>();
                context.getResponse().contentType = "text/html";
                renderText(testTemplate.render(context, options));
            } else {
                renderText("Test not found, %s", testFile);
            }
        }
        if (test.endsWith(".test.html.result")) {
            context.getFlash().keep();
            test = test.substring(0, test.length() - 7);
            File testResults = Play.getFile("test-result/" + test.replace("/", ".") + ".passed.html");
            if (testResults.exists()) {
                context.getResponse().contentType = "text/html";
                context.getResponse().status = 200;
                renderText(IO.readContentAsString(testResults));
            }
            testResults = Play.getFile("test-result/" + test.replace("/", ".") + ".failed.html");
            if (testResults.exists()) {
                context.getResponse().contentType = "text/html";
                context.getResponse().status = 500;
                renderText(IO.readContentAsString(testResults));
            }

            context.getResponse().status = 404;
            renderText("No test result");
        }
       
    }

    public void saveResult(String test, String result) throws Exception {
        String table = context.getParams().get("testTable.1");
        File testResults = Play.getFile("test-result/" + test.replace("/", ".") + "." + result + ".html");
        Template resultTemplate = TemplateLoader.load("TestRunner/selenium-results.html");
        Map<String, Object> options = new HashMap<String, Object>();
        options.put("test", test);
        options.put("table", table);
        options.put("result", result);
        String rf = resultTemplate.render(context, options);
        IO.writeContent(rf, testResults);
        renderText("done");
    }

    public static void mockEmail(String by) {
        String email = Mail.Mock.getLastMessageReceivedBy(by);
        if(email == null) {
            notFound();
        }

        renderText(email);
    }

	public static void cacheEntry(String key){
    	String value = Cache.get(key,String.class);
    	if(value == null){
    		notFound();
    	}

    	renderText(value);
    }
}

