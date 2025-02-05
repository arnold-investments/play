package play.templates;

import java.util.HashMap;
import java.util.Map;
import play.mvc.Context;

public abstract class Template {

    public String name;
    public String source;

    public abstract void compile();

    /**
     * Starts the rendering process without modifying the args-map
     * 
     * @param args
     *            map containing data binding info
     * @return the result of the complete rendering
     */
    public String render(Context context, Map<String, Object> args) {
        // starts the internal recursive rendering process with
        // a copy of the passed args-map. This is done
        // to prevent us from pollution the users map with
        // template-rendering-specific internal data
        //
        // Since the original args is not polluted it can be used as input
        // to another rendering operation later
        return internalRender(context, new HashMap<>(args));
    }

    /**
     * The internal rendering method - When one template calls another template, this method is used. The input args-map
     * is constantly being modified, as different templates "communicate" with each other by storing info in the map
     * 
     * @param args
     *            List of arguments use in render
     * @return The template result as string
     */
    protected abstract String internalRender(Context context, Map<String, Object> args);

    public String render(Context context) {
        return internalRender(context, new HashMap<String, Object>());
    }

    public String getName() {
        return name;
    }

}
