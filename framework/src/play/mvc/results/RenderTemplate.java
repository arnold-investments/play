package play.mvc.results;

import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.templates.Template;

import java.util.Map;

/**
 * 200 OK with a template rendering
 */
public class RenderTemplate extends Result {

    private final String name;
    private final String content;
    private final Map<String, Object> arguments;
    private final long renderTime;

    public RenderTemplate(Context context, Template template, Map<String, Object> arguments) {
        if (arguments.containsKey("out")) {
            throw new RuntimeException("Arguments should not contain out");
        }
        this.name = template.name;
        this.arguments = arguments;
        long start = System.currentTimeMillis();
        this.content = template.render(context, arguments);
        this.renderTime = System.currentTimeMillis() - start;
    }

    @Override
    public void apply(Context context) {
        try {
            Http.Response response = context.getResponse();

            String contentType = MimeTypes.getContentType(response, name, "text/plain");
            response.out.write(content.getBytes(getEncoding(response)));
            setContentTypeIfNotSet(response, contentType);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public long getRenderTime() {
        return renderTime;
    }
}
