package play.mvc;

import play.classloading.enhancers.ControllersEnhancer.ControllerSupport;
import play.mvc.results.WebSocketDisconnect;

public class WebSocketController implements ControllerSupport, PlayController {

    protected Context context;

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    protected static void disconnect() {
        throw new WebSocketDisconnect();
    }
}
