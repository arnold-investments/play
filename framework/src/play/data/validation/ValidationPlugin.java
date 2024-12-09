package play.data.validation;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import play.PlayPlugin;
import play.exceptions.UnexpectedException;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Cookie;
import play.mvc.Scope;
import play.mvc.results.Result;

public class ValidationPlugin extends PlayPlugin {
    @Override
    public void onActionInvocationResult(Context context, Result result) {
        save(context);
    }

    @Override
    public void onInvocationException(Context context, Throwable e) {
        clear(context.getResponse());
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
