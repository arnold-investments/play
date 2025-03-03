package play.modules.testrunner;

import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.ConfirmHandler;
import com.gargoylesoftware.htmlunit.DefaultCssErrorHandler;
import com.gargoylesoftware.htmlunit.DefaultPageCreator;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.PromptHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.WebWindow;
import com.gargoylesoftware.htmlunit.javascript.host.Window;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serial;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sourceforge.htmlunit.corejs.javascript.Context;
import net.sourceforge.htmlunit.corejs.javascript.ScriptRuntime;
import net.sourceforge.htmlunit.corejs.javascript.ScriptableObject;

import static org.apache.commons.io.IOUtils.closeQuietly;

public class FirePhoque {

    public static void main(String[] args) throws Exception {

        String app = System.getProperty("application.url", "http://localhost:9000");

        // Tests description
        File root = null;
        String selenium = null;
        List<String> tests = null;
        BufferedReader in = null;
        StringBuilder urlStringBuilder = new StringBuilder(app).append("/@tests.list");
            
        String runUnitTests = System.getProperty("runUnitTests");
        String runFunctionalTests = System.getProperty("runFunctionalTests");
        String runSeleniumTests = System.getProperty("runSeleniumTests");
        
        if(runUnitTests != null || runFunctionalTests != null || runSeleniumTests != null){
            urlStringBuilder.append("?");
            urlStringBuilder.append("runUnitTests=").append(runUnitTests != null);
            System.out.println("~ Run unit tests:" + (runUnitTests != null));

            urlStringBuilder.append("&runFunctionalTests=").append(runFunctionalTests != null);
            System.out.println("~ Run functional tests:" + (runFunctionalTests != null));

            urlStringBuilder.append("&runSeleniumTests=").append(runSeleniumTests != null);
            System.out.println("~ Run selenium tests:" + (runSeleniumTests != null));
        }
        
        try {
            in = new BufferedReader(new InputStreamReader(new URI(urlStringBuilder.toString()).toURL().openStream(), StandardCharsets.UTF_8));
            String marker = in.readLine();
            if (!marker.equals("---")) {
                throw new RuntimeException("Oops");
            }
            root = new File(in.readLine());
            selenium = in.readLine();
            tests = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                tests.add(line);
            }
        } catch(Exception e) {
            System.out.println("~ The application does not start. There are errors: " + e.getMessage());
            e.printStackTrace();
            System.exit(-1);
        } finally {
            closeQuietly(in);
        }

        // Let's tweak WebClient

        String headlessBrowser = System.getProperty("headlessBrowser", "FIREFOX_45");
        BrowserVersion browserVersion = switch (headlessBrowser) {
	        case "CHROME" -> BrowserVersion.CHROME;
	        case "EDGE" -> BrowserVersion.EDGE;
	        case null, default -> BrowserVersion.FIREFOX_45;
        };

	    WebClient firephoque = new WebClient(browserVersion);
        firephoque.setPageCreator(new DefaultPageCreator() {
            /**
            * Generated Serial version UID
            */
            @Serial
            private static final long serialVersionUID = 6690993309672446834L;

            @Override
            public Page createPage(WebResponse wr, WebWindow ww) throws IOException {
		        return createHtmlPage(wr, ww);
            }
        });
        
        firephoque.getOptions().setThrowExceptionOnFailingStatusCode(false);
        
        int timeout = Integer.parseInt(System.getProperty("webclientTimeout", "-1"));
        if(timeout >= 0){
          firephoque.getOptions().setTimeout(timeout);
        }
        
        firephoque.setAlertHandler(new AlertHandler() {
            public void handleAlert(Page page, String message) {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedAlerts.push('" + message.replace("'", "\\'")+ "');";
                    Object result = ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[] {script}, null, 0);

                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        });
        firephoque.setConfirmHandler(new ConfirmHandler() {
            public boolean handleConfirm(Page page, String message) {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedConfirmations.push('" + message.replace("'", "\\'")+ "');" +
                            "var result = parent.selenium.browserbot.nextConfirmResult;" +
                            "parent.selenium.browserbot.nextConfirmResult = true;" +
                            "result";
                    
                   Object result = ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[] {script}, null, 0);
                   // window.execScript(script,  "JavaScript");

                   return (Boolean)result;
                } catch(Exception e) {
                    e.printStackTrace();
                    return false;
                }
            }
        });
        firephoque.setPromptHandler(new PromptHandler() {
            public String handlePrompt(Page page, String message) {
                try {
                    ScriptableObject window = page.getEnclosingWindow().getScriptableObject();
                    String script = "parent.selenium.browserbot.recordedPrompts.push('" + message.replace("'", "\\'")+ "');" +
                            "var result = !parent.selenium.browserbot.nextConfirmResult ? null : parent.selenium.browserbot.nextPromptResult;" +
                            "parent.selenium.browserbot.nextConfirmResult = true;" +
                            "parent.selenium.browserbot.nextPromptResult = '';" +
                            "result";
                    Object result = ScriptRuntime.evalSpecial(Context.getCurrentContext(), window, window, new Object[] {script}, null, 0);
                    //window.execScript(script,  "JavaScript");
                    return (String)result;
                } catch(Exception e) {
                    e.printStackTrace();
                    return "";
                }
            }
        });
        firephoque.getOptions().setThrowExceptionOnScriptError(false);
        firephoque.getOptions().setPrintContentOnFailingStatusCode(false);

        // Go!
        int maxLength = 0;
        for (String test : tests) {
            String testName = test.replace(".class", "").replace(".test.html", "").replace(".", "/").replace("$", "/");
            if (testName.length() > maxLength) {
                maxLength = testName.length();
            }
        }
        System.out.println("~ " + tests.size() + " test" + (tests.size() != 1 ? "s" : "") + " to run:");
        System.out.println("~");
        firephoque.openWindow(new URI(app + "/@tests/init").toURL(), "headless");
        boolean ok = true;
        for (String test : tests) {
            long start = System.currentTimeMillis();
            String testName = test.replace(".class", "").replace(".test.html", "").replace(".", "/").replace("$", "/");
            System.out.print("~ " + testName + "... ");
            for (int i = 0; i < maxLength - testName.length(); i++) {
                System.out.print(" ");
            }
            System.out.print("    ");
            URL url;
            if (test.endsWith(".class")) {
                url = new URI(app + "/@tests/" + test).toURL();
            } else {
                url = new URI(app + "" + selenium + "?baseUrl=" + app + "&test=/@tests/" + test + ".suite&auto=true&resultsUrl=/@tests/" + test).toURL();
            }
            firephoque.openWindow(url, "headless");
            firephoque.waitForBackgroundJavaScript(5 * 60 * 1000);
            int retry = 0;
            while(retry < 5) {
                if (new File(root, test.replace("/", ".") + ".passed.html").exists()) {
                    System.out.print("PASSED     ");
                    break;
                } else if (new File(root, test.replace("/", ".") + ".failed.html").exists()) {
                    System.out.print("FAILED  !  ");
                    ok = false;
                    break;
                } else {
                    if(retry++ == 4) {
                        System.out.print("ERROR   ?  ");
                        ok = false;
                        break;
                    } else {
                        Thread.sleep(1000);
                    }
                }
            }

            //
            int duration = (int) (System.currentTimeMillis() - start);
            int seconds = (duration / 1000) % 60;
            int minutes = (duration / (1000 * 60)) % 60;

            if (minutes > 0) {
                System.out.println(minutes + " min " + seconds + "s");
            } else {
                System.out.println(seconds + "s");
            }
        }
        firephoque.openWindow(new URI(app + "/@tests/end?result=" + (ok ? "passed" : "failed")).toURL(), "headless");
        
        firephoque.close();
    }
}
