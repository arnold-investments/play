package play.mvc;

import play.mvc.results.WebSocketDisconnect;

public class WebSocketController implements PlayController {
    protected Context context;

    @Override
    public void setContext(Context context) {
        this.context = context;
    }

    protected static void disconnect() {
        throw new WebSocketDisconnect();
    }
}
