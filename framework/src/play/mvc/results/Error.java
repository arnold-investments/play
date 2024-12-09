package play.mvc.results;

import java.util.Map;
import play.Play;
import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.Context;
import play.mvc.Http;
import play.templates.TemplateLoader;

/**
 * 500 Error
 */
public class Error extends Result {

    private final int status;

    public Error(String reason) {
        super(reason);
        this.status = Http.StatusCode.INTERNAL_ERROR;
    }

    public Error(int status, String reason) {
        super(reason);
        this.status = status;
    }

    @Override
    public void apply(Context context) {
        Http.Request request = context.getRequest();
        Http.Response response = context.getResponse();

        response.status = status;
        String format = request.format;
        if (request.isAjax() && "html".equals(format)) {
            format = "txt";
        }
        response.contentType = MimeTypes.getContentType(context, "xx." + format);
        Map<String, Object> binding = context.getRenderArgs().data;
        binding.put("exception", this);
        binding.put("result", this);
        binding.put("session", context.getSession());
        binding.put("request", request);
        binding.put("flash", context.getFlash());
        binding.put("params", context.getParams());
        binding.put("play", new Play());
        String errorHtml = getMessage();
        try {
            errorHtml = TemplateLoader.load("errors/" + this.status + "." + (format == null ? "html" : format)).render(context, binding);
        } catch (Exception e) {
            // no template in desired format, just display the default response
        }
        try {
            response.out.write(errorHtml.getBytes(getEncoding(context.getResponse())));
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public int getStatus() {
        return status;
    }
}
