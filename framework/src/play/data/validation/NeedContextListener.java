package play.data.validation;

import net.sf.oval.Check;
import net.sf.oval.configuration.CheckInitializationListener;
import play.mvc.Context;

public class NeedContextListener implements CheckInitializationListener {
	private final Context context;

	public NeedContextListener(Context context) {
		this.context = context;
	}

	@Override
	public void onCheckInitialized(Check check) {
		if (check instanceof NeedContext neetContextCheck) {
			neetContextCheck.setContext(context);
		}
	}
}
