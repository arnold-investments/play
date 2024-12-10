package play.mvc;

import com.google.gson.Gson;
import com.google.gson.JsonSerializer;
import com.thoughtworks.xstream.XStream;
import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import org.w3c.dom.Document;
import play.Invoker;
import play.Invoker.Suspend;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.ControllersEnhancer.ControllerSupport;
import play.data.binding.Unbinder;
import play.exceptions.NoRouteFoundException;
import play.exceptions.PlayException;
import play.exceptions.TemplateNotFoundException;
import play.exceptions.UnexpectedException;
import play.libs.F;
import play.mvc.Router.ActionDefinition;
import play.mvc.results.BadRequest;
import play.mvc.results.Error;
import play.mvc.results.Forbidden;
import play.mvc.results.NotFound;
import play.mvc.results.NotModified;
import play.mvc.results.Ok;
import play.mvc.results.Redirect;
import play.mvc.results.RedirectToStatic;
import play.mvc.results.RenderBinary;
import play.mvc.results.RenderHtml;
import play.mvc.results.RenderJson;
import play.mvc.results.RenderTemplate;
import play.mvc.results.RenderText;
import play.mvc.results.RenderXml;
import play.mvc.results.Unauthorized;
import play.templates.Template;
import play.templates.TemplateLoader;
import play.utils.Default;
import play.utils.Java;
import play.vfs.VirtualFile;

/**
 * Application controller support: The controller receives input and initiates a response by making calls on model
 * objects.
 *
 * This is the class that your controllers should extend in most cases.
 */
public class Controller implements PlayController, ControllerSupport {

    protected Context context;

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    /**
     *
     */
    private static ITemplateNameResolver templateNameResolver = null;

    /**
     * Return a 200 OK text/plain response
     *
     * @param text
     *            The response content
     */
    protected static void renderText(Object text) {
        throw new RenderText(text == null ? "" : text.toString());
    }

    /**
     * Return a 200 OK text/html response
     *
     * @param html
     *            The response content
     */
    protected static void renderHtml(Object html) {
        throw new RenderHtml(html == null ? "" : html.toString());
    }

    /**
     * Return a 200 OK text/plain response
     *
     * @param pattern
     *            The response content to be formatted (with String.format)
     * @param args
     *            Args for String.format
     */
    protected static void renderText(CharSequence pattern, Object... args) {
        throw new RenderText(pattern == null ? "" : String.format(pattern.toString(), args));
    }

    /**
     * Return a 200 OK text/xml response
     *
     * @param xml
     *            The XML string
     */
    protected static void renderXml(String xml) {
        throw new RenderXml(xml);
    }

    /**
     * Return a 200 OK text/xml response
     *
     * @param xml
     *            The DOM document object
     */
    protected static void renderXml(Document xml) {
        throw new RenderXml(xml);
    }

    /**
     * Return a 200 OK text/xml response. Use renderXml(Object, XStream) to customize the result.
     *
     * @param o
     *            the object to serialize
     */
    protected static void renderXml(Object o) {
        throw new RenderXml(o);
    }

    /**
     * Return a 200 OK text/xml response
     *
     * @param o
     *            the object to serialize
     * @param xstream
     *            the XStream object to use for serialization. See XStream's documentation for details about customizing
     *            the output.
     */
    protected static void renderXml(Object o, XStream xstream) {
        throw new RenderXml(o, xstream);
    }

    /**
     * Return a 200 OK application/binary response. Content is fully loaded in memory, so it should not be used with
     * large data.
     *
     * @param is
     *            The stream to copy
     */
    protected static void renderBinary(InputStream is) {
        throw new RenderBinary(is, null, true);
    }

    /**
     * Return a 200 OK application/binary response. Content is streamed.
     *
     * @param is
     *            The stream to copy
     * @param length
     *            Stream's size in bytes.
     */
    protected static void renderBinary(InputStream is, long length) {
        throw new RenderBinary(is, null, length, true);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment. Content is fully loaded in
     * memory, so it should not be used with large data.
     *
     * @param is
     *            The stream to copy
     * @param name
     *            Name of file user is downloading.
     */
    protected static void renderBinary(InputStream is, String name) {
        throw new RenderBinary(is, name, false);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment.
     *
     * @param is
     *            The stream to copy. Content is streamed.
     * @param name
     *            Name of file user is downloading.
     * @param length
     *            Stream's size in bytes.
     */
    protected static void renderBinary(InputStream is, String name, long length) {
        throw new RenderBinary(is, name, length, false);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment. Content is fully loaded in
     * memory, so it should not be used with large data.
     *
     * @param is
     *            The stream to copy
     * @param name
     *            Name of file user is downloading.
     * @param inline
     *            true to set the response Content-Disposition to inline
     */
    protected static void renderBinary(InputStream is, String name, boolean inline) {
        throw new RenderBinary(is, name, inline);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment.
     *
     * @param is
     *            The stream to copy
     * @param name
     *            The attachment name
     * @param length
     *            Stream's size in bytes.
     * @param inline
     *            true to set the response Content-Disposition to inline
     */
    protected static void renderBinary(InputStream is, String name, long length, boolean inline) {
        throw new RenderBinary(is, name, length, inline);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment. Content is fully loaded in
     * memory, so it should not be used with large data.
     *
     * @param is
     *            The stream to copy
     * @param name
     *            The attachment name
     * @param contentType
     *            The content type of the attachment
     * @param inline
     *            true to set the response Content-Disposition to inline
     */
    protected static void renderBinary(InputStream is, String name, String contentType, boolean inline) {
        throw new RenderBinary(is, name, contentType, inline);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment.
     *
     * @param is
     *            The stream to copy
     * @param name
     *            The attachment name
     * @param length
     *            Content's byte size.
     * @param contentType
     *            The content type of the attachment
     * @param inline
     *            true to set the response Content-Disposition to inline
     */
    protected static void renderBinary(InputStream is, String name, long length, String contentType, boolean inline) {
        throw new RenderBinary(is, name, length, contentType, inline);
    }

    /**
     * Return a 200 OK application/binary response
     *
     * @param file
     *            The file to copy
     */
    protected static void renderBinary(File file) {
        throw new RenderBinary(file);
    }

    /**
     * Return a 200 OK application/binary response with content-disposition attachment
     *
     * @param file
     *            The file to copy
     * @param name
     *            The attachment name
     */
    protected static void renderBinary(File file, String name) {
        throw new RenderBinary(file, name);
    }

    /**
     * Render a 200 OK application/json response
     *
     * @param jsonString
     *            The JSON string
     */
    protected static void renderJSON(String jsonString) {
        throw new RenderJson(jsonString);
    }

    /**
     * Render a 200 OK application/json response
     *
     * @param o
     *            The Java object to serialize
     */
    protected static void renderJSON(Object o) {
        throw new RenderJson(o);
    }

    /**
     * Render a 200 OK application/json response
     *
     * @param o
     *            The Java object to serialize
     * @param type
     *            The Type information for complex generic types
     */
    protected static void renderJSON(Object o, Type type) {
        throw new RenderJson(o, type);
    }

    /**
     * Render a 200 OK application/json response.
     *
     * @param o
     *            The Java object to serialize
     * @param adapters
     *            A set of GSON serializers/deserializers/instance creator to use
     */
    protected static void renderJSON(Object o, JsonSerializer<?>... adapters) {
        throw new RenderJson(o, adapters);
    }

    /**
     * Render a 200 OK application/json response.
     *
     * @param o
     *            The Java object to serialize
     * @param gson
     *            The GSON serializer object use
     */
    protected static void renderJSON(Object o, Gson gson) {
        throw new RenderJson(o, gson);
    }

    /**
     * Send a 304 Not Modified response
     */
    protected static void notModified() {
        throw new NotModified();
    }

    /**
     * Send a 400 Bad request
     * 
     * @param msg
     *            The message
     */
    protected static void badRequest(String msg) {
        throw new BadRequest(msg);
    }

    /**
     * Send a 400 Bad request
     */
    protected static void badRequest() {
        throw new BadRequest("Bad request");
    }

    /**
     * Send a 401 Unauthorized response
     *
     * @param realm
     *            The realm name
     */
    protected static void unauthorized(String realm) {
        throw new Unauthorized(realm);
    }

    /**
     * Send a 401 Unauthorized response
     */
    protected static void unauthorized() {
        throw new Unauthorized("Unauthorized");
    }

    /**
     * Send a 404 Not Found response
     *
     * @param what
     *            The Not Found resource name
     */
    protected static void notFound(String what) {
        throw new NotFound(what);
    }

    /**
     * Send a 200 OK response
     */
    protected static void ok() {
        throw new Ok();
    }

    /**
     * Send a 404 Not Found response if object is null
     *
     * @param o
     *            The object to check
     */
    protected static void notFoundIfNull(Object o) {
        if (o == null) {
            notFound();
        }
    }

    /**
     * Send a 404 Not Found response if object is null
     *
     * @param o
     *            The object to check
     * @param what
     *            The Not Found resource name
     */
    protected static void notFoundIfNull(Object o, String what) {
        if (o == null) {
            notFound(what);
        }
    }

    /**
     * Send a 404 Not Found response
     */
    protected static void notFound() {
        throw new NotFound("");
    }

    /**
     * Check that the token submitted from a form is valid.
     *
     * @see play.templates.FastTags#_authenticityToken
     */
    protected static void checkAuthenticity(Context context) {
        if (context.getParams().get("authenticityToken") == null
                || !context.getParams().get("authenticityToken").equals(context.getSession().getAuthenticityToken())) {
            forbidden("Bad authenticity token");
        }
    }

    /**
     * Send a 403 Forbidden response
     *
     * @param reason
     *            The reason
     */
    protected static void forbidden(String reason) {
        throw new Forbidden(reason);
    }

    /**
     * Send a 403 Forbidden response
     */
    protected static void forbidden() {
        throw new Forbidden("Access denied");
    }

    /**
     * Send a 5xx Error response
     *
     * @param status
     *            The exact status code
     * @param reason
     *            The reason
     */
    protected static void error(int status, String reason) {
        throw new Error(status, reason);
    }

    /**
     * Send a 500 Error response
     *
     * @param reason
     *            The reason
     */
    protected static void error(String reason) {
        throw new Error(reason);
    }

    /**
     * Send a 500 Error response
     *
     * @param reason
     *            The reason
     */
    protected static void error(Exception reason) {
        Logger.error(reason, "error()");
        throw new Error(reason.toString());
    }

    /**
     * Send a 500 Error response
     */
    protected static void error() {
        throw new Error("Internal Error");
    }

    /**
     * Send a 302 redirect response.
     *
     * @param url
     *            The Location to redirect
     */
    protected static void redirect(String url) {
        redirect(url, false);
    }

    /**
     * Send a 302 redirect response.
     *
     * @param file
     *            The Location to redirect
     */
    protected static void redirectToStatic(Http.Request request, String file) {
        try {
            VirtualFile vf = Play.getVirtualFile(file);
            if (vf == null || !vf.exists()) {
                throw new NoRouteFoundException(file);
            }
            throw new RedirectToStatic(Router.reverse(request, Play.getVirtualFile(file)));
        } catch (NoRouteFoundException e) {
            StackTraceElement element = PlayException.getInterestingStackTraceElement(e);
            if (element != null) {
                throw new NoRouteFoundException(file, Play.classes.getApplicationClass(element.getClassName()), element.getLineNumber());
            } else {
                throw e;
            }
        }
    }

    /**
     * Send a Redirect response.
     *
     * @param url
     *            The Location to redirect
     * @param permanent
     *            true -&gt; 301, false -&gt; 302
     */
    protected static void redirect(String url, boolean permanent) {
        if (url.indexOf("/") == -1) { // fix Java !
            redirect(url, permanent, new Object[0]);
        }
        throw new Redirect(url, permanent);
    }

    /**
     * 302 Redirect to another action
     *
     * @param action
     *            The fully qualified action name (ex: Application.index)
     * @param args
     *            Method arguments
     */
    public static void redirect(String action, Object... args) {
        redirect(action, false, args);
    }

    /**
     * Redirect to another action
     *
     * @param action
     *            The fully qualified action name (ex: Application.index)
     * @param permanent
     *            true -&gt; 301, false -&gt; 302
     * @param args
     *            Method arguments
     */
    protected static void redirect(Context context, String action, boolean permanent, Object... args) {
        try {
            Map<String, Object> newArgs = new HashMap<>(args.length);
            Method actionMethod = (Method) ActionInvoker.getActionMethod(action)[1];
            String[] names = Java.parameterNames(actionMethod);
            for (int i = 0; i < names.length && i < args.length; i++) {
                Annotation[] annotations = actionMethod.getParameterAnnotations()[i];
                boolean isDefault = false;
                try {
                    Method defaultMethod = actionMethod.getDeclaringClass()
                            .getDeclaredMethod(actionMethod.getName() + "$default$" + (i + 1));
                    // Patch for scala defaults
                    if (!Modifier.isStatic(actionMethod.getModifiers()) && actionMethod.getDeclaringClass().getSimpleName().endsWith("$")) {
                        Object instance = actionMethod.getDeclaringClass().getDeclaredField("MODULE$").get(null);
                        if (defaultMethod.invoke(instance).equals(args[i])) {
                            isDefault = true;
                        }
                    }
                } catch (NoSuchMethodException e) {
                    //
                }

                // Bind the argument

                if (isDefault) {
                    newArgs.put(names[i], new Default(args[i]));
                } else {
                    Unbinder.unBind(context, newArgs, args[i], names[i], annotations);
                }

            }
            try {
                ActionDefinition actionDefinition = Router.reverse(context, action, newArgs);

                if (_currentReverse.get() != null) {
                    ActionDefinition currentActionDefinition = _currentReverse.get();
                    currentActionDefinition.action = actionDefinition.action;
                    currentActionDefinition.url = actionDefinition.url;
                    currentActionDefinition.method = actionDefinition.method;
                    currentActionDefinition.star = actionDefinition.star;
                    currentActionDefinition.args = actionDefinition.args;

                    _currentReverse.remove();
                } else {
                    throw new Redirect(actionDefinition.toString(), permanent);
                }
            } catch (NoRouteFoundException e) {
                StackTraceElement element = PlayException.getInterestingStackTraceElement(e);
                if (element != null) {
                    throw new NoRouteFoundException(action, newArgs, Play.classes.getApplicationClass(element.getClassName()),
                            element.getLineNumber());
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            if (e instanceof Redirect) {
                throw (Redirect) e;
            }
            if (e instanceof PlayException) {
                throw (PlayException) e;
            }
            throw new UnexpectedException(e);
        }
    }

    protected boolean templateExists(String templateName) {
        try {
            TemplateLoader.load(template(templateName));
            return true;
        } catch (TemplateNotFoundException ex) {
            return false;
        }
    }

    /**
     * Render a specific template
     *
     * @param templateName
     *            The template name
     */
    protected static void renderTemplate(Context context, String templateName) {
        // Template datas
        Map<String, Object> templateBinding = new HashMap<>(16);

        renderTemplate(context, templateName, templateBinding);
    }

    protected void renderTemplate(String templateName) {
        renderTemplate(context, templateName);
    }

    /**
     * Render a specific template.
     *
     * @param templateName
     *            The template name.
     * @param args
     *            The template data.
     */
    protected static void renderTemplate(Context context, String templateName, Map<String, Object> args) {
        // Template datas
        Scope.RenderArgs templateBinding = context.getRenderArgs();
        templateBinding.data.putAll(args);
        templateBinding.put("session", context.getSession());
        templateBinding.put("request", context.getRequest());
        templateBinding.put("flash", context.getFlash());
        templateBinding.put("params", context.getParams());
        templateBinding.put("errors", context.getValidation().errors());
        try {
            Template template = TemplateLoader.load(template(context, templateName));
            throw new RenderTemplate(context, template, templateBinding.data);
        } catch (TemplateNotFoundException ex) {
            if (ex.isSourceAvailable()) {
                throw ex;
            }
            StackTraceElement element = PlayException.getInterestingStackTraceElement(ex);
            if (element != null) {
                ApplicationClass applicationClass = Play.classes.getApplicationClass(element.getClassName());
                if (applicationClass != null) {
                    throw new TemplateNotFoundException(templateName, applicationClass, element.getLineNumber());
                }
            }
            throw ex;
        }
    }

    protected void renderTemplate(String templateName, Map<String, Object> args) {
        renderTemplate(context, templateName, args);
    }

    /**
     * Render the template corresponding to the action's package-class-method name (@see <code>template()</code>).
     *
     * @param args
     *            The template data.
     */
    public static void renderTemplate(Context context, Map<String, Object> args) {
        renderTemplate(context, template(context), args);
    }

    protected void renderTemplate(Map<String, Object> args) {
        renderTemplate(context, args);
    }

    /**
     * Render the corresponding template (@see <code>template()</code>).
     *
     */
    protected static void render(Context context) {
        String templateName = template(context);

        renderTemplate(context, templateName);
    }

    protected void render() {
        render(context);
    }

    /**
     * Work out the default template to load for the invoked action. E.g. "controllers.Pages.index" returns
     * "views/Pages/index.html".
     * 
     * @return The template name
     */
    protected static String template(Context context) {
	    Http.Request request = context.getRequest();

        String format = request.format;
        String templateName = request.action.replace('.', '/') + "." + (format == null ? "html" : format);
        if (templateName.startsWith("@")) {
            templateName = templateName.substring(1);
            if (!templateName.contains(".")) {
                templateName = request.controller + "." + templateName;
            }
            templateName = templateName.replace('.', '/') + "." + (format == null ? "html" : format);
        }
        return null == templateNameResolver ? templateName : templateNameResolver.resolveTemplateName(templateName);
    }

    protected String template() {
        return template(context);
    }

    /**
     * Work out the default template to load for the action. E.g. "controllers.Pages.index" returns
     * "views/Pages/index.html".
     * 
     * @param templateName
     *            The template name to work out
     * @return The template name
     */
    protected static String template(Context context, String templateName) {
        Http.Request request = context.getRequest();

        String format = request.format;
        if (templateName.startsWith("@")) {
            templateName = templateName.substring(1);
            if (!templateName.contains(".")) {
                templateName = request.controller + "." + templateName;
            }
            templateName = templateName.replace('.', '/') + "." + (format == null ? "html" : format);
        }
        return templateName;
    }

    protected String template(String templateName) {
        return template(context, templateName);
    }

    /**
     * Retrieve annotation for the action method
     *
     * @param clazz
     *            The annotation class
     * @param <T>
     *            The class type
     * @return Annotation object or null if not found
     */
    protected static <T extends Annotation> T getActionAnnotation(Context context, Class<T> clazz) {
        Method m = (Method) ActionInvoker.getActionMethod(context.getRequest().action)[1];
        if (m.isAnnotationPresent(clazz)) {
            return m.getAnnotation(clazz);
        }
        return null;
    }

    /**
     * Retrieve annotation for the controller class
     *
     * @param clazz
     *            The annotation class
     * @param <T>
     *            The class type
     * @return Annotation object or null if not found
     */
    protected static <T extends Annotation> T getControllerAnnotation(Http.Request request, Class<T> clazz) {
        if (getControllerClass(request).isAnnotationPresent(clazz)) {
            return getControllerClass(request).getAnnotation(clazz);
        }
        return null;
    }

    /**
     * Retrieve annotation for the controller class
     *
     * @param clazz
     *            The annotation class
     * @param <T>
     *            The class type
     * @return Annotation object or null if not found
     */
    protected static <T extends Annotation> T getControllerInheritedAnnotation(Http.Request request, Class<T> clazz) {
        Class<?> c = getControllerClass(request);
        while (!c.equals(Object.class)) {
            if (c.isAnnotationPresent(clazz)) {
                return c.getAnnotation(clazz);
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * Retrieve the controller class
     *
     * @return Annotation object or null if not found
     */
    @SuppressWarnings("unchecked")
    protected static Class<? extends Controller> getControllerClass(Http.Request request) {
        return (Class<? extends Controller>) request.controllerClass;
    }

    /**
     * Suspend this request and wait for the task completion
     *
     * <p>
     * <b>Important:</b> The method will not resume on the line after you call this. The method will be called again as
     * if there was a new HTTP request.
     * </p>
     * 
     * @param task
     *            Taks to wait for
     * @deprecated
     */
    @Deprecated
    protected static void waitFor(Http.Request request, Future<?> task) {
        request.isNew = false;
        throw new Suspend(task);
    }

    protected static <T> void await(F.Promise<T> promise, F.Action<T> callback) {
        F.Promise<Void> chainedPromise = new F.Promise<>();

        promise.onRedeem(p -> {
            Throwable throwable = null;

            try {
                callback.invoke(p.getOrNull());
            } catch (Throwable t) {
                throwable = t;
            } finally {
                chainedPromise.invokeWithException(throwable);
            }
        });


        throw new Invoker.AsyncRequest(chainedPromise);
    }

    /**
     * Don't use this directly if you don't know why
     */
    public static final ThreadLocal<ActionDefinition> _currentReverse = new ThreadLocal<>();

    /**
     * @play.todo TODO - this "Usage" example below doesn't make sense.
     *
     *            Usage:
     *
     *            <code>
     * ActionDefinition action = reverse(); {
     *     Application.anyAction(anyParam, "toto");
     * }
     * String url = action.url;
     * </code>
     *
     * @return The ActionDefiniton
     */
    protected static ActionDefinition reverse() {
        ActionDefinition actionDefinition = new ActionDefinition();
        _currentReverse.set(actionDefinition);
        return actionDefinition;
    }

    /**
     * Register a customer template name resolver. This allows to override the way templates are resolved.
     * 
     * @param templateNameResolver
     *            The template resolver
     */
    public static void registerTemplateNameResolver(ITemplateNameResolver templateNameResolver) {
        if (null != Controller.templateNameResolver)
            Logger.warn("Existing template name resolver will be overridden!");
        Controller.templateNameResolver = templateNameResolver;
    }

    /**
     * This allows people that implement their own template engine to override the way templates are resolved.
     */
    public interface ITemplateNameResolver {
        /**
         * Return the template path given a template name.
         * 
         * @param templateName
         *            The template name
         * @return The template path
         */
        String resolveTemplateName(String templateName);
    }

}
