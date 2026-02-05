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
        wrapInPlayInvocation(invocation);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext, ExtensionContext extensionContext) throws Throwable {
        wrapInPlayInvocation(invocation);
    }

    @Override
    public <T> T interceptTestFactoryMethod(Invocation<T> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        return wrapInPlayInvocation(invocation);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
        wrapInPlayInvocation(invocation);
    }

    private <T> T wrapInPlayInvocation(Invocation<T> invocation) throws Throwable {
        if (useCustomRunner) {
            try {
                final Object[] result = new Object[1];
                DirectInvocation directInvocation = new DirectInvocation(new Context(null, null)) {
                    @Override
                    public void execute() throws Exception {
                        try {
                            result[0] = invocation.proceed();
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
                return (T) result[0];
            } catch (Throwable throwable) {
                Throwable rootCause = ExceptionUtils.getRootCause(throwable);
                throw rootCause != null ? rootCause : throwable;
            }
        } else {
            return invocation.proceed();
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
