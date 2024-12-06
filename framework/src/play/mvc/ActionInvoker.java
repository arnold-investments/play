package play.mvc;

import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.ExecutionException;
import play.Invoker;
import play.Logger;
import play.Play;
import play.cache.Cache;
import play.cache.CacheFor;
import play.classloading.enhancers.ControllersEnhancer.ControllerInstrumentation;
import play.data.binding.Binder;
import play.data.binding.ParamNode;
import play.data.binding.RootParamNode;
import play.data.parsing.UrlEncodedParser;
import play.exceptions.ActionNotFoundException;
import play.exceptions.JavaExecutionException;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.inject.Injector;
import play.mvc.Http.Request;
import play.mvc.Router.Route;
import play.mvc.results.NoResult;
import play.mvc.results.NotFound;
import play.mvc.results.Result;
import play.utils.Java;
import play.utils.Utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoke an action after an HTTP request.
 */
public class ActionInvoker {

    @SuppressWarnings("unchecked")
    public static void resolve(Request request) {
        if (!Play.started) {
            return;
        }

        if (request.resolved) {
            return;
        }

        // Route and resolve format if not already done
        if (request.action == null) {
            Play.pluginCollection.routeRequest(request);
            Route route = Router.route(request);
            Play.pluginCollection.onRequestRouting(route);
        }
        request.resolveFormat();

        // Find the action method
        try {
            Method actionMethod;
            Object[] ca = getActionMethod(request.action);
            actionMethod = (Method) ca[1];
            request.controller = ((Class) ca[0]).getName().substring(12).replace("$", "");
            request.controllerClass = ((Class) ca[0]);
            request.actionMethod = actionMethod.getName();
            request.action = request.controller + "." + request.actionMethod;
            request.invokedMethod = actionMethod;

            if (Logger.isTraceEnabled()) {
                Logger.trace("------- %s", actionMethod);
            }

            request.resolved = true;

        } catch (ActionNotFoundException e) {
            Logger.error(e, "%s action not found", e.getAction());
            throw new NotFound(String.format("%s action not found", e.getAction()));
        }

    }

    public static void invoke(Context context) {
        _invoke(context, ActionInvoker::invokeAction, ActionInvoker::prepareInvokeAction);
    }

    private static void _invoke(Context context, IInvokeAction action, IPrepareInvokeAction prepareAction) {
        var ctx = new WrapInvokeActionCtx();
        boolean skipFinally = false;

        try {
            ctx.monitor = prepareAction.apply(context);

            wrapInvokeAction(context, ctx, action);
        } catch (Result result) {
            Play.pluginCollection.onActionInvocationResult(result);

            // OK there is a result to apply
            // Save session & flash scope now
            context.getSession().save();
            context.getFlash().save();

            result.apply(context.getRequest(), context.getResponse());

            Play.pluginCollection.afterActionInvocation();

            // @Finally
            handleFinallies(context, null);
        } catch (JavaExecutionException e) {
            handleFinallies(context, e.getCause());
            throw e;
        } catch (Invoker.AsyncRequest e) {
            skipFinally = true;
            e.getTask().onRedeem(p -> {
                _invoke(context, (ignore1,ignore2) -> {
	                Throwable throwable = p.getException();
                    if (throwable instanceof Exception exception) {
                        throw exception;
                    } else if (throwable != null){
                        throw new ExecutionException(throwable);
                    }
                }, (ignore1) -> ctx.monitor);
            });
            throw e;
        } catch (PlayException e) {
            handleFinallies(context, e);
            throw e;
        } catch (Throwable e) {
            handleFinallies(context, e);
            throw new UnexpectedException(e);
        } finally {
            if (!skipFinally) {
                Play.pluginCollection.onActionInvocationFinally();

                if (ctx.monitor != null) {
                    ctx.monitor.stop();
                }
            }
        }
    }

    private static class InvokeActionResult {
        private String cacheKey;
        private Result actionResult;
    }

    private static class WrapInvokeActionCtx {
        private Monitor monitor;
    }

    private interface IInvokeAction {
        void apply(Context context, InvokeActionResult invokeActionResult) throws Exception;
    }

    private static void wrapInvokeAction(Context context, WrapInvokeActionCtx wrapInvokeActionCtx, IInvokeAction invokeActionMethod) throws Exception {
        Method actionMethod = context.getRequest().invokedMethod;
        InvokeActionResult res = new InvokeActionResult();

        // 3. Invoke the action
        try {
            invokeActionMethod.apply(context, res);
        } catch (Result result) {
            res.actionResult = result;
            // Cache it if needed
            if (res.cacheKey != null && !res.cacheKey.isEmpty()) {
                Cache.set(res.cacheKey, res.actionResult, actionMethod.getAnnotation(CacheFor.class).value());
            }
        } catch (JavaExecutionException e) {
            invokeControllerCatchMethods(context, e.getCause());
            throw e;
        }

        // @After
        handleAfters(context);

        wrapInvokeActionCtx.monitor.stop();
        wrapInvokeActionCtx.monitor = null;

        // OK, re-throw the original action result
        if (res.actionResult != null) {
            throw res.actionResult;
        }

        throw new NoResult();
    }

    private static void invokeAction(Context context, InvokeActionResult invokeActionResult) throws Exception {
        // @Before
        handleBefores(context);

        // Action

        // Check the cache (only for GET or HEAD)
        if ((context.getRequest().method.equals("GET") || context.getRequest().method.equals("HEAD")) && context.getActionMethod().isAnnotationPresent(CacheFor.class)) {
            CacheFor cacheFor = context.getActionMethod().getAnnotation(CacheFor.class);;
            invokeActionResult.cacheKey = cacheFor.id();
            if (invokeActionResult.cacheKey != null && invokeActionResult.cacheKey.isEmpty()) {
                // Generate a cache key for this request
                invokeActionResult.cacheKey = cacheFor.generator().getDeclaredConstructor().newInstance().generate(context.getRequest());
            }
            if(invokeActionResult.cacheKey != null && !invokeActionResult.cacheKey.isEmpty()) {
                invokeActionResult.actionResult = (Result) Cache.get(invokeActionResult.cacheKey);
            }
        }

        if (invokeActionResult.actionResult == null) {
            ControllerInstrumentation.initActionCall();
            inferResult(invokeControllerMethod(context, context.getActionMethod()));
        }
    }

    private interface IPrepareInvokeAction {
        Monitor apply(Context context) throws NoSuchFieldException, UnsupportedEncodingException, IllegalAccessException;
    }

    private static Monitor prepareInvokeAction(Context context) throws NoSuchFieldException, UnsupportedEncodingException, IllegalAccessException {
        // FIXME: did init here before - do we need to re-init?

        // 1. Prepare request params
        context.getParams().__mergeWith(context.getRequest().routeArgs);

        // add parameters from the URI query string
        String encoding = context.getRequest().encoding;
        context.getParams()
            ._mergeWith(UrlEncodedParser.parseQueryString(new ByteArrayInputStream(context.getRequest().querystring.getBytes(encoding))));

        ControllerInstrumentation.stopActionCall();
        Play.pluginCollection.beforeActionInvocation(context.getActionMethod());

        // Monitoring
        return MonitorFactory.start(context.getRequest().action + "()");
    }

    private static void invokeControllerCatchMethods(Context context, Throwable throwable) throws Exception {
        // @Catch
        Object[] args = new Object[] {throwable};
        List<Method> catches = Java.findAllAnnotatedMethods(getControllerClass(context), Catch.class);
        ControllerInstrumentation.stopActionCall();
        for (Method mCatch : catches) {
            Class[] exceptions = mCatch.getAnnotation(Catch.class).value();
            if (exceptions.length == 0) {
                exceptions = new Class[]{Exception.class};
            }
            for (Class exception : exceptions) {
                if (exception.isInstance(args[0])) {
                    mCatch.setAccessible(true);
                    inferResult(invokeControllerMethod(context, mCatch, args));
                    break;
                }
            }
        }
    }

    private static boolean isActionMethod(Method method) {
        return !method.isAnnotationPresent(Before.class) &&
                !method.isAnnotationPresent(After.class) &&
                !method.isAnnotationPresent(Finally.class) &&
                !method.isAnnotationPresent(Catch.class) &&
                !method.isAnnotationPresent(Util.class);
    }

    /**
     * Find the first public method of a controller class
     *
     * @param name
     *            The method name
     * @param clazz
     *            The class
     * @return The method or null
     */
    public static Method findActionMethod(String name, Class clazz) {
        while (!clazz.getName().equals("java.lang.Object")) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equalsIgnoreCase(name) && Modifier.isPublic(m.getModifiers())) {
                    // Check that it is not an interceptor
                    if (isActionMethod(m)) {
                        return m;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static void handleBefores(Context context) throws Exception {
        Http.Request request = context.getRequest();

        List<Method> befores = Java.findAllAnnotatedMethods(getControllerClass(context), Before.class);
        ControllerInstrumentation.stopActionCall();
        for (Method before : befores) {
            String[] unless = before.getAnnotation(Before.class).unless();
            String[] only = before.getAnnotation(Before.class).only();
            boolean skip = false;
            for (String un : only) {
                if (!un.contains(".")) {
                    un = before.getDeclaringClass().getName().substring(12).replace("$", "") + "." + un;
                }
                if (un.equals(request.action)) {
                    skip = false;
                    break;
                } else {
                    skip = true;
                }
            }
            for (String un : unless) {
                if (!un.contains(".")) {
                    un = before.getDeclaringClass().getName().substring(12).replace("$", "") + "." + un;
                }
                if (un.equals(request.action)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                before.setAccessible(true);
                inferResult(invokeControllerMethod(context, before));
            }
        }
    }

    private static void handleAfters(Context context) throws Exception {
        Http.Request request = context.getRequest();

        List<Method> afters = Java.findAllAnnotatedMethods(getControllerClass(context), After.class);
        ControllerInstrumentation.stopActionCall();
        for (Method after : afters) {
            String[] unless = after.getAnnotation(After.class).unless();
            String[] only = after.getAnnotation(After.class).only();
            boolean skip = false;
            for (String un : only) {
                if (!un.contains(".")) {
                    un = after.getDeclaringClass().getName().substring(12) + "." + un;
                }
                if (un.equals(request.action)) {
                    skip = false;
                    break;
                } else {
                    skip = true;
                }
            }
            for (String un : unless) {
                if (!un.contains(".")) {
                    un = after.getDeclaringClass().getName().substring(12) + "." + un;
                }
                if (un.equals(request.action)) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                after.setAccessible(true);
                inferResult(invokeControllerMethod(context, after));
            }
        }
    }

    /**
     * Checks and calla all methods in controller annotated with @Finally. The
     * caughtException-value is sent as argument to @Finally-method if method
     * has one argument which is Throwable
     *
     * @param context
     * @param caughtException
     *            If @Finally-methods are called after an error, this variable
     *            holds the caught error
     * @throws PlayException
     */
    static void handleFinallies(Context context, Throwable caughtException) throws PlayException {

        if (getControllerClass(context) == null) {
            // skip it
            return;
        }

        try {
            List<Method> allFinally = Java.findAllAnnotatedMethods(context.getRequest().controllerClass, Finally.class);
            ControllerInstrumentation.stopActionCall();
            for (Method aFinally : allFinally) {
                String[] unless = aFinally.getAnnotation(Finally.class).unless();
                String[] only = aFinally.getAnnotation(Finally.class).only();
                boolean skip = false;
                for (String un : only) {
                    if (!un.contains(".")) {
                        un = aFinally.getDeclaringClass().getName().substring(12) + "." + un;
                    }
                    if (un.equals(context.getRequest().action)) {
                        skip = false;
                        break;
                    } else {
                        skip = true;
                    }
                }
                for (String un : unless) {
                    if (!un.contains(".")) {
                        un = aFinally.getDeclaringClass().getName().substring(12) + "." + un;
                    }
                    if (un.equals(context.getRequest().action)) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    aFinally.setAccessible(true);

                    // check if method accepts Throwable as only parameter
                    Class[] parameterTypes = aFinally.getParameterTypes();
                    if (parameterTypes.length == 1 && parameterTypes[0] == Throwable.class) {
                        // invoking @Finally method with caughtException as
                        // parameter
                        invokeControllerMethod(context, aFinally, new Object[] { caughtException });
                    } else {
                        // invoke @Finally-method the regular way without
                        // caughtException
                        invokeControllerMethod(context, aFinally, null);
                    }
                }
            }
        } catch (PlayException e) {
            throw e;
        } catch (Exception e) {
            throw new UnexpectedException("Exception while doing @Finally", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static void inferResult(Object o) {
        // Return type inference
        if (o != null) {

            if (o instanceof NoResult) {
                return;
            }
            if (o instanceof Result) {
                // Of course
                throw (Result) o;
            }
            if (o instanceof InputStream) {
                Controller.renderBinary((InputStream) o);
            }
            if (o instanceof File) {
                Controller.renderBinary((File) o);
            }
            if (o instanceof Map) {
                Controller.renderTemplate((Map<String, Object>) o);
            }

            Controller.renderHtml(o);
        }
    }

    public static Object invokeControllerMethod(Context context, Method method) throws Exception {
        return invokeControllerMethod(context, method, null);
    }

    public static Object invokeControllerMethod(Context context, Method method, Object[] forceArgs) throws Exception {
	    Request request = context.getRequest();

        boolean isStatic = Modifier.isStatic(method.getModifiers());
        String declaringClassName = method.getDeclaringClass().getName();
        boolean isProbablyScala = declaringClassName.contains("$");

        if (!isStatic && request.controllerInstance == null) {
            request.controllerInstance = Injector.getBeanOfType(request.controllerClass);
        }

        Object[] args = forceArgs != null ? forceArgs : getActionMethodArgs(context, request.controllerInstance);

        if (isProbablyScala) {
            try {
                Object scalaInstance = request.controllerClass.getDeclaredField("MODULE$").get(null);
                if (declaringClassName.endsWith("$class")) {
                    args[0] = scalaInstance; // Scala trait method
                } else {
                    request.controllerInstance = (PlayController) scalaInstance; // Scala object method
                }
            } catch (NoSuchFieldException e) {
                // not Scala
            }
        }

        Object methodClassInstance = isStatic ? null :
            (method.getDeclaringClass().isAssignableFrom(request.controllerClass)) ? request.controllerInstance :
                Injector.getBeanOfType(method.getDeclaringClass());

        return invoke(method, methodClassInstance, args);
    }

    static Object invoke(Method method, Object instance, Object ... realArgs) throws Exception {
        try {
//            if (isActionMethod(method)) {
//                return invokeWithContinuation(method, instance, realArgs);
//            } else {
                return method.invoke(instance, realArgs);
//            }
        } catch (InvocationTargetException ex) {
            Throwable originalThrowable = ex.getTargetException();

            if (originalThrowable instanceof Result || originalThrowable instanceof PlayException)
                throw (Exception) originalThrowable;

            StackTraceElement element = PlayException.getInterestingStackTraceElement(originalThrowable);
            if (element != null) {
                throw new JavaExecutionException(Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber(),
                        originalThrowable);
            }
            throw new JavaExecutionException(originalThrowable);
        }
    }

    static final String C = "__continuation";
    static final String A = "__callback";
    static final String F = "__future";
    static final String CONTINUATIONS_STORE_LOCAL_VARIABLE_NAMES = "__CONTINUATIONS_STORE_LOCAL_VARIABLE_NAMES";
    static final String CONTINUATIONS_STORE_RENDER_ARGS = "__CONTINUATIONS_STORE_RENDER_ARGS";
    static final String CONTINUATIONS_STORE_PARAMS = "__CONTINUATIONS_STORE_PARAMS";
    public static final String CONTINUATIONS_STORE_VALIDATIONS = "__CONTINUATIONS_STORE_VALIDATIONS";
    static final String CONTINUATIONS_STORE_VALIDATIONPLUGIN_KEYS = "__CONTINUATIONS_STORE_VALIDATIONPLUGIN_KEYS";

    public static Object[] getActionMethod(String fullAction) {
        Method actionMethod = null;
        Class controllerClass = null;
        try {
            if (!fullAction.startsWith("controllers.")) {
                fullAction = "controllers." + fullAction;
            }
            String controller = fullAction.substring(0, fullAction.lastIndexOf('.'));
            String action = fullAction.substring(fullAction.lastIndexOf('.') + 1);
            controllerClass = Play.classloader.getClassIgnoreCase(controller);
            if (controllerClass == null) {
                throw new ActionNotFoundException(fullAction, new Exception("Controller " + controller + " not found"));
            }
            if (!PlayController.class.isAssignableFrom(controllerClass)) {
                // Try the scala way
                controllerClass = Play.classloader.getClassIgnoreCase(controller + "$");
                if (!PlayController.class.isAssignableFrom(controllerClass)) {
                    throw new ActionNotFoundException(fullAction,
                            new Exception("class " + controller + " does not extend play.mvc.Controller"));
                }
            }
            actionMethod = findActionMethod(action, controllerClass);
            if (actionMethod == null) {
                throw new ActionNotFoundException(fullAction,
                        new Exception("No method public static void " + action + "() was found in class " + controller));
            }
        } catch (PlayException e) {
            throw e;
        } catch (Exception e) {
            throw new ActionNotFoundException(fullAction, e);
        }
        return new Object[] { controllerClass, actionMethod };
    }

    public static Object[] getActionMethodArgs(Context context, Object o) throws Exception {
        Method method = context.getActionMethod();

        String[] paramsNames = Java.parameterNames(method);
        if (paramsNames == null && method.getParameterTypes().length > 0) {
            throw new UnexpectedException("Parameter names not found for method " + method);
        }

        // Check if we have already performed the bind operation
        Object[] rArgs = context.getCachedBoundActionMethodArgs().retrieveActionMethodArgs(method);
        if (rArgs != null) {
            // We have already performed the binding-operation for this method
            // in this request.
            return rArgs;
        }

        rArgs = new Object[method.getParameterTypes().length];
        for (int i = 0; i < method.getParameterTypes().length; i++) {

            Class<?> type = method.getParameterTypes()[i];
            Map<String, String[]> params = new HashMap<>();

            // In case of simple params, we don't want to parse the body.
            if (type.equals(String.class) || Number.class.isAssignableFrom(type) || type.isPrimitive()) {
                params.put(paramsNames[i], context.getParams().getAll(paramsNames[i]));
            } else {
                params.putAll(context.getParams().all());
            }
            Logger.trace("getActionMethodArgs name [" + paramsNames[i] + "] annotation ["
                    + Utils.join(method.getParameterAnnotations()[i], " ") + "]");

            RootParamNode root = ParamNode.convert(params);
            rArgs[i] = Binder.bind(root, paramsNames[i], method.getParameterTypes()[i], method.getGenericParameterTypes()[i],
                    method.getParameterAnnotations()[i], new Binder.MethodAndParamInfo(o, method, i + 1));
        }

        context.getCachedBoundActionMethodArgs().storeActionMethodArgs(method, rArgs);
        return rArgs;
    }

    private static Class<? extends PlayController> getControllerClass(Context context) {
        return context.getRequest().controllerClass;
    }
}
