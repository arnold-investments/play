package play.mvc.results;

import java.util.Map;

import play.Play;
import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Scope;
import play.templates.TemplateLoader;

/**
 * 404 not found
 */
public class NotFound extends Result {

    /**
     * @param why a description of the problem
     */
    public NotFound(String why) {
        super(why);
    }

    /**
     * @param method routed method
     * @param path  routed path 
     */
    public NotFound(String method, String path) {
        super(method + " " + path);
    }

    @Override
    public void apply(Context context) {
        Http.Request request = context.getRequest();
        Http.Response response = context.getResponse();

        response.status = Http.StatusCode.NOT_FOUND;
        String format = request.format;
        if(request.isAjax() && "html".equals(format)) {
            format = "txt";
        }
        response.contentType = MimeTypes.getContentType("xx."+format);
        Map<String, Object> binding = context.getRenderArgs().data;
        binding.put("result", this);
        binding.put("session", context.getSession());
        binding.put("request", request);
        binding.put("flash", context.getFlash());
        binding.put("params", context.getParams());
        binding.put("play", new Play());
        String errorHtml = "Not found";
        try {
            errorHtml = TemplateLoader.load("errors/404." + (format == null ? "html" : format)).render(binding);
        } catch(Exception e) {
        }
        try {
            response.out.write(errorHtml.getBytes(getEncoding()));
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }
}
