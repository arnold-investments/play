package play.mvc.results;

import play.mvc.Context;
import play.mvc.Http;
import play.utils.FastRuntimeException;

/**
 * Result support
 */
public abstract class Result extends FastRuntimeException {

    public Result() {
        super();
    }

    public Result(String description) {
        super(description);
    }

    public abstract void apply(Context context);

    protected void setContentTypeIfNotSet(Http.Response response, String contentType) {
        response.setContentTypeIfNotSet(contentType);
    }

    /**
     * The encoding that should be used when writing this response to the client
     * 
     * @return The encoding of the response
     */
    protected String getEncoding(Http.Response response) {
        return response.encoding;
    }

}
