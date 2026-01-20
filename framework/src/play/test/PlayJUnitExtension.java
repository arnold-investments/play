package play.test;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.extension.*;
import play.Invoker;
import play.Invoker.DirectInvocation;
import play.Play;
import play.mvc.Context;

import java.io.File;
import java.lang.reflect.Method;

public class PlayJUnitExtension implements BeforeAllCallback, BeforeEachCallback, InvocationInterceptor {

    public static final String invocationType = "JUnitTest";
    private static boolean useCustomRunner = false;

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        synchronized (Play.class) {
            if (!Play.started) {
                Play.init(new File("."), getPlayId());
                Play.javaPath.add(Play.getVirtualFile("test"));
                if (!Play.started) {
                    Play.start(new Context(null, null));
                }
                useCustomRunner = true;
            }
        }
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        TestEngine.initTest(new Context(null, null), extensionContext.getRequiredTestClass());
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        if (useCustomRunner) {
            try {
                DirectInvocation directInvocation = new DirectInvocation(new Context(null, null)) {
                    @Override
                    public void execute() throws Exception {
                        try {
                            invocation.proceed();
                        } catch (Throwable throwable) {
                            throw new RuntimeException(throwable);
                        }
                    }

                    @Override
                    public Invoker.InvocationContext getInvocationContext() {
                        return new Invoker.InvocationContext(invocationType);
                    }
                };
                Invoker.invokeInThread(directInvocation);
            } catch (Throwable throwable) {
                throw ExceptionUtils.getRootCause(throwable);
            }
        } else {
            invocation.proceed();
        }
    }

    private static String getPlayId() {
        String playId = System.getProperty("play.id", "test");
        if (!(playId.startsWith("test-") && playId.length() >= 6)) {
            playId = "test";
        }
        return playId;
    }
}
