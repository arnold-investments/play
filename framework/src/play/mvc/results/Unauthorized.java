package play.mvc.results;

import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

/**
 * 401 Unauthorized
 */
public class Unauthorized extends Result {
    
    private final String realm;
    
    public Unauthorized(String realm) {
        super(realm);
        this.realm = realm;
    }

    @Override
    public void apply(Context context) {
        context.getResponse().status = Http.StatusCode.UNAUTHORIZED;
        context.getResponse().setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"");
    }

    public String getRealm() {
        return realm;
    }
}
