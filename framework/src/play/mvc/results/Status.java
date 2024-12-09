package play.mvc.results;

import play.mvc.Context;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

public class Status extends Result {

    private final int code;

    public Status(int code) {
        super(code+"");
        this.code = code;
    }

    @Override
    public void apply(Context context) {
        context.getResponse().status = code;
    }

    public int getCode() {
        return code;
    }
}