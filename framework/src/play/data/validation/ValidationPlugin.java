package play.data.validation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.oval.ConstraintViolation;
import net.sf.oval.context.MethodParameterContext;
import net.sf.oval.guard.Guard;
import play.PlayPlugin;
import play.exceptions.ActionNotFoundException;
import play.exceptions.UnexpectedException;
import play.mvc.ActionInvoker;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Cookie;
import play.mvc.Scope;
import play.mvc.results.Result;
import play.utils.Java;

public class ValidationPlugin extends PlayPlugin {
    @Override
    public void beforeInvocation(Context context) {
        context.setValidation(new Validation());
    }

    @Override
    public void beforeActionInvocation(Context context) {
        try {
            context.setValidation(restore(context));

            boolean verify = false;
            for (Annotation[] annotations : context.getActionMethod().getParameterAnnotations()) {
                if (annotations.length > 0) {
                    verify = true;
                    break;
                }
            }

            if (!verify) {
                return;
            }

            List<ConstraintViolation> violations = new Validator().validateAction(context);
            ArrayList<Error> errors = new ArrayList<>();
            String[] paramNames = Java.parameterNames(context.getActionMethod());
            for (ConstraintViolation violation : violations) {
                errors.add(new Error(
                    context,
                    paramNames[((MethodParameterContext) violation.getContext()).getParameterIndex()],
                    violation.getMessage(),
                    violation.getMessageVariables() == null
                        ? new String[0]
                        : violation.getMessageVariables().values().toArray(new String[0]),
                    violation.getSeverity()
                ));
            }

            context.getValidation().errors.addAll(errors);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    @Override
    public void onActionInvocationResult(Context context, Result result) {
        save(context);
    }

    @Override
    public void onInvocationException(Context context, Throwable e) {
        clear(context.getResponse());
    }

    // ~~~~~~
    static class Validator extends Guard {

        public List<ConstraintViolation> validateAction(Context context) throws Exception {
	        Method actionMethod = context.getActionMethod();

            List<ConstraintViolation> violations = new ArrayList<>();
            Object instance = null;
            // Patch for scala defaults
            if (!Modifier.isStatic(actionMethod.getModifiers()) && actionMethod.getDeclaringClass().getSimpleName().endsWith("$")) {
                try {
                    instance = actionMethod.getDeclaringClass().getDeclaredField("MODULE$").get(null);
                } catch (Exception e) {
                    throw new ActionNotFoundException(context.getRequest().action, e);
                }
            }
            Object[] rArgs = ActionInvoker.getActionMethodArgs(context, actionMethod, instance);
            validateMethodParameters(null, actionMethod, rArgs, violations);
            validateMethodPre(null, actionMethod, rArgs, violations);
            return violations;
        }
    }
    static final Pattern errorsParser = Pattern.compile("\u0000([^:]*):([^\u0000]*)\u0000");

    static Validation restore(Context context) {
        try {
            Validation validation = new Validation();
            Http.Cookie cookie = context.getRequest().cookies.get(Scope.COOKIE_PREFIX + "_ERRORS");
            if (cookie != null) {
                String errorsData = URLDecoder.decode(cookie.value, StandardCharsets.UTF_8);
                Matcher matcher = errorsParser.matcher(errorsData);
                while (matcher.find()) {
                    String[] g2 = matcher.group(2).split("\u0001", -1);
                    String message = g2[0];
                    String[] args = new String[g2.length - 1];
                    System.arraycopy(g2, 1, args, 0, args.length);
                    validation.errors.add(new Error(context, matcher.group(1), message, args));
                }
            }
            return validation;
        } catch (Exception e) {
            return new Validation();
        }
    }

    static void save(Context context) {
        if (context == null || context.getResponse() == null) {
            // Some request like WebSocket don't have any response
            return;
        }
        if (context.getValidation().errors().isEmpty()) {
            // Only send "delete cookie" header when the cookie was present in the request
            if(context.getRequest().cookies.containsKey(Scope.COOKIE_PREFIX + "_ERRORS") || !Scope.SESSION_SEND_ONLY_IF_CHANGED) {
                context.getResponse().setCookie(Scope.COOKIE_PREFIX + "_ERRORS", "", null, "/", 0, Scope.COOKIE_SECURE, Scope.SESSION_HTTPONLY);
            }
            return;
        }
        try {
            StringBuilder errors = new StringBuilder();
            if (context.getValidation() != null && context.getValidation().keep) {
                for (Error error : context.getValidation().errors()) {
                    errors.append("\u0000");
                    errors.append(error.key);
                    errors.append(":");
                    errors.append(error.message);
                    for (String variable : error.variables) {
                        errors.append("\u0001");
                        errors.append(variable);
                    }
                    errors.append("\u0000");
                }
            }
            String errorsData = URLEncoder.encode(errors.toString(), StandardCharsets.UTF_8);
            context.getResponse().setCookie(Scope.COOKIE_PREFIX + "_ERRORS", errorsData, null, "/", null, Scope.COOKIE_SECURE, Scope.SESSION_HTTPONLY);
        } catch (Exception e) {
            throw new UnexpectedException("Errors serializationProblem", e);
        }
    }

    static void clear(Http.Response response) {
        try {
            if (response != null && response.cookies != null) {
                Cookie cookie = new Cookie();
                cookie.name = Scope.COOKIE_PREFIX + "_ERRORS";
                cookie.value = "";
                cookie.sendOnError = true;
                response.cookies.put(cookie.name, cookie);
            }
        } catch (Exception e) {
            throw new UnexpectedException("Errors serializationProblem", e);
        }
    }
}
