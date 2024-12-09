package play.mvc.results;

import play.exceptions.UnexpectedException;
import play.mvc.Context;
import play.mvc.Http;

/**
 * 302 Redirect
 */
public class RedirectToStatic extends Result {

    private final String file;
    
    public RedirectToStatic(String file) {
        this.file = file;
    }

    @Override
    public void apply(Context context) {
        try {
            context.getResponse().status = Http.StatusCode.FOUND;
            context.getResponse().setHeader("Location", file);
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public String getFile() {
        return file;
    }
}
