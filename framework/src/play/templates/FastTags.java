package play.templates;

import groovy.lang.Closure;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.codehaus.groovy.runtime.NullObject;
import play.cache.Cache;
import play.data.validation.Error;
import play.exceptions.TagInternalException;
import play.exceptions.TemplateExecutionException;
import play.exceptions.TemplateNotFoundException;
import play.mvc.Router.ActionDefinition;
import play.templates.BaseTemplate.RawData;
import play.templates.GroovyTemplate.ExecutableTemplate;
import play.utils.HTML;

public class FastTags {

    public static void _cache(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        String key = args.get("arg").toString();
        String duration = null;
        if (args.containsKey("for")) {
            duration = args.get("for").toString();
        }
        Object cached = Cache.get(key);
        if (cached != null) {
            out.print(cached);
            return;
        }
        String result = JavaExtensions.toString(body);
        Cache.set(key, result, duration);
        out.print(result);
    }

    public static void _verbatim(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        out.println(JavaExtensions.toString(body));
    }

    public static void _jsAction(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        String html = "";
        String minimize = "";
        if (args.containsKey("minimize") && Boolean.FALSE.equals(Boolean.valueOf(args.get("minimize").toString()))) {
            minimize = "\n";
        }
        html += "function(options) {" + minimize;
        html += "var pattern = '" + args.get("arg").toString().replace("&amp;", "&") + "';" + minimize;
        html += "for(key in options) {" + minimize;
        html += "var val = options[key];" + minimize;
        // Encode URI script
        if (args.containsKey("encodeURI") && Boolean.TRUE.equals(Boolean.valueOf(args.get("encodeURI").toString()))) {
            html += "val = encodeURIComponent(val.replace('&amp;', '&'));" + minimize;
        }
        // Custom script
        if (args.containsKey("customScript")) {
            html += "val = " + args.get("customScript") + minimize;
        }
        html += "pattern = pattern.replace(':' + encodeURIComponent(key), ( (val===undefined || val===null)?'': val));" + minimize;
        html += "}" + minimize;
        html += "return pattern;" + minimize;
        html += "}" + minimize;
        out.println(html);
    }

    public static void _jsRoute(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        Object arg = args.get("arg");
        if (!(arg instanceof ActionDefinition)) {
            throw new TemplateExecutionException(template.template, fromLine,
                    "Wrong parameter type, try #{jsRoute @Application.index() /}", new TagInternalException("Wrong parameter type"));
        }
        ActionDefinition action = (ActionDefinition) arg;
        out.print("{");
        if (action.args.isEmpty()) {
            out.print("url: function() { return '" + action.url.replace("&amp;", "&") + "'; },");
        } else {
            out.print("url: function(args) { var pattern = '" + action.url.replace("&amp;", "&")
                    + "'; for (var key in args) { pattern = pattern.replace(':'+key, args[key] || ''); } return pattern; },");
        }
        out.print("method: '" + action.method + "'");
        out.print("}");
    }

    public static void _authenticityToken(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        out.println("<input type=\"hidden\" name=\"authenticityToken\" value=\"" + template.getContext().getSession().getAuthenticityToken() + "\"/>");
    }

    public static void _option(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        Object value = args.get("arg");
        Object selectedValue = TagContext.parent("select").data.get("selected");
        boolean selected = selectedValue != null && value != null && (selectedValue.toString()).equals(value.toString());
        out.print("<option value=\"" + (value == null ? "" : value) + "\" " + (selected ? "selected=\"selected\"" : "") + " "
                + serialize(args, "selected", "value") + ">");
        out.println(JavaExtensions.toString(body));
        out.print("</option>");
    }

    public static void _ifErrors(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        if (template.getContext().getValidation().hasErrors()) {
            body.call();
            TagContext.parent().data.put("_executeNextElse", false);
        } else {
            TagContext.parent().data.put("_executeNextElse", true);
        }
    }

    public static void _ifError(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        if (args.get("arg") == null) {
            throw new TemplateExecutionException(template.template, fromLine, "Please specify the error key", new TagInternalException(
                    "Please specify the error key"));
        }
        if (template.getContext().getValidation().hasError(args.get("arg").toString())) {
            body.call();
            TagContext.parent().data.put("_executeNextElse", false);
        } else {
            TagContext.parent().data.put("_executeNextElse", true);
        }
    }

    public static void _error(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        if (args.get("arg") == null && args.get("key") == null) {
            throw new TemplateExecutionException(template.template, fromLine, "Please specify the error key", new TagInternalException(
                    "Please specify the error key"));
        }
        String key = args.get("arg") == null ? args.get("key") + "" : args.get("arg") + "";
        Error error = template.getContext().getValidation().error(key);
        if (error != null) {
            if (args.get("field") == null) {
                out.print(error.message());
            } else {
                out.print(error.message(args.get("field") + ""));
            }
        }
    }

    static boolean _evaluateCondition(Object test) {
        if (test != null) {
	        return switch (test) {
		        case Boolean b -> b;
		        case String s -> !s.isEmpty();
		        case Number number -> number.intValue() != 0;
		        case Collection<?> collection -> !collection.isEmpty();
		        case NullObject nullObject -> false;
		        default -> true;
	        };
        }
        return false;
    }

    static String __safe(Template template, Object val) {
        if (val instanceof RawData) {
            return ((RawData) val).data;
        }
        if (!template.name.endsWith(".html") || TagContext.hasParentTag("verbatim")) {
            return val.toString();
        }
        return HTML.htmlEscape(val.toString());
    }

    public static void _doLayout(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        out.print("____%LAYOUT%____");
    }

    public static void _get(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        Object name = args.get("arg");
        if (name == null) {
            throw new TemplateExecutionException(template.template, fromLine, "Specify a variable name", new TagInternalException(
                    "Specify a variable name"));
        }
        Object value = BaseTemplate.layoutData.get().get(name);
        if (value != null) {
            out.print(value);
        } else {
            if (body != null) {
                out.print(JavaExtensions.toString(body));
            }
        }
    }

    public static void _set(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        // Simple case : #{set title:'Yop' /}
        for (Map.Entry<?, ?> entry : args.entrySet()) {
            Object key = entry.getKey();
            if (!key.toString().equals("arg")) {
                BaseTemplate.layoutData.get().put(
                        key,
                        (entry.getValue() != null && entry.getValue() instanceof String) ? __safe(template.template, entry.getValue())
                                : entry.getValue());
                return;
            }
        }
        // Body case
        Object name = args.get("arg");
        if (name != null && body != null) {
            Object oldOut = body.getProperty("out");
            StringWriter sw = new StringWriter();
            body.setProperty("out", new PrintWriter(sw));
            body.call();
            BaseTemplate.layoutData.get().put(name, sw.toString());
            body.setProperty("out", oldOut);
        }
    }

    public static void _extends(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        try {
            if (!args.containsKey("arg") || args.get("arg") == null) {
                throw new TemplateExecutionException(template.template, fromLine, "Specify a template name", new TagInternalException(
                        "Specify a template name"));
            }
            String name = args.get("arg").toString();
            if (name.startsWith("./")) {
                String ct = BaseTemplate.currentTemplate.get().name;
                if (ct.matches("^/lib/[^/]+/app/views/.*")) {
                    ct = ct.substring(ct.indexOf('/', 5));
                }
                ct = ct.substring(0, ct.lastIndexOf('/'));
                name = ct + name.substring(1);
            }
            BaseTemplate.layout.set((BaseTemplate) TemplateLoader.load(name));
        } catch (TemplateNotFoundException e) {
            throw new TemplateNotFoundException(e.getPath(), template.template, fromLine);
        }
    }

    @SuppressWarnings("unchecked")
    public static void _include(Map<?, ?> args, Closure body, PrintWriter out, ExecutableTemplate template, int fromLine) {
        try {
            if (!args.containsKey("arg") || args.get("arg") == null) {
                throw new TemplateExecutionException(template.template, fromLine, "Specify a template name", new TagInternalException(
                        "Specify a template name"));
            }
            String name = args.get("arg").toString();
            if (name.startsWith("./")) {
                String ct = BaseTemplate.currentTemplate.get().name;
                if (ct.matches("^/lib/[^/]+/app/views/.*")) {
                    ct = ct.substring(ct.indexOf('/', 5));
                }
                ct = ct.substring(0, ct.lastIndexOf('/'));
                name = ct + name.substring(1);
            }
            BaseTemplate t = (BaseTemplate) TemplateLoader.load(name);
            Map<String, Object> newArgs = new HashMap<>(template.getBinding().getVariables());
            newArgs.put("_isInclude", true);
            t.internalRender(template.getContext(), newArgs);
        } catch (TemplateNotFoundException e) {
            throw new TemplateNotFoundException(e.getPath(), template.template, fromLine);
        }
    }

    public static String serialize(Map<?, ?> args, String... unless) {
        StringBuilder attrs = new StringBuilder();
        Arrays.sort(unless);
        for (Object o : args.keySet()) {
            String attr = o.toString();
            String value = args.get(o) == null ? "" : args.get(o).toString();
            if (Arrays.binarySearch(unless, attr) < 0 && !attr.equals("arg")) {
                attrs.append(attr);
                attrs.append("=\"");
                attrs.append(value);
                attrs.append("\" ");
            }
        }
        return attrs.toString();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public static @interface Namespace {

        String value() default "";
    }
}
