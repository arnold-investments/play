package play.mvc;


import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;

public class WebSocketInvoker {

    public static void resolve(Context context, Http.Request request) {
        ActionInvoker.resolve(context, request);
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
