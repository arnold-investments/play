package play.mvc;

import play.mvc.results.WebSocketDisconnect;

public class WebSocketController implements PlayController {
    protected final Context context;

	public WebSocketController(Context context) {
		this.context = context;
	}

	protected static void disconnect() {
        throw new WebSocketDisconnect();
    }
}
