package play.mvc.results;

import play.exceptions.UnexpectedException;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

/**
 * 200 OK with a text/plain
 */
public class RenderHtml extends Result {
    
    private final String html;
    
    public RenderHtml(CharSequence html) {
        this.html = html.toString();
    }

    @Override
    public void apply(Context context) {
        try {
            Http.Response response = context.getResponse();

            setContentTypeIfNotSet(response, "text/html");
            response.out.write(html.getBytes(getEncoding(response)));
        } catch(Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public String getHtml() {
        return html;
    }
}
