package play.mvc;

import play.classloading.enhancers.ControllersEnhancer.ControllerSupport;
import play.data.validation.Validation;

import play.mvc.results.WebSocketDisconnect;

public class WebSocketController implements ControllerSupport, PlayController {

    protected Http.Request request = null;
    protected Http.Inbound inbound = null;
    protected Http.Outbound outbound = null;
    protected Scope.Params params = null;
    protected Validation validation = null;
    protected Scope.Session session = null;

    protected static void disconnect() {
        throw new WebSocketDisconnect();
    }

}
