package play.mvc.results;

import play.exceptions.UnexpectedException;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

/**
 * 200 OK with a text/plain
 */
public class RenderText extends Result {
    
    private final String text;
    
    public RenderText(CharSequence text) {
        this.text = text.toString();
    }

    @Override
    public void apply(Context context) {
        try {
            Http.Response response = context.getResponse();

            setContentTypeIfNotSet(response, "text/plain; charset=" + response.encoding);
            response.out.write(text.getBytes(getEncoding()));
        } catch(Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public String getText() {
        return text;
    }
}
