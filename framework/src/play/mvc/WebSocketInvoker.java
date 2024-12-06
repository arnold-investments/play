package play.mvc;


import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;

public class WebSocketInvoker {

    public static void resolve(Http.Request request) {
        ActionInvoker.resolve(request);
    }

    public static void invoke(Context context) {
        try {
            ActionInvoker.invoke(context);
        }catch (PlayException e) {
            throw e;
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }

    }
}
