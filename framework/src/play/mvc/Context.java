package play.mvc;


import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Stack;
import play.Invoker;
import play.data.binding.CachedBoundActionMethodArgs;
import play.data.validation.Validation;

public class Context {
	private Validation validation;
	private Scope.Flash flash;
	private Scope.Session session;
	private Scope.RenderArgs renderArgs;
	private Scope.RouteArgs routeArgs;
	// Scope.Params is already part of request

	private Http.Request request;
	private Http.Response response;
	private Http.Inbound inbound;
	private Http.Outbound outbound;

	private CachedBoundActionMethodArgs cachedBoundActionMethodArgs;

	private String localeStr;

	private Locale locale;

	private Invoker.InvocationContext invocationContext;

	public Context(Http.Request request, Http.Response response) {
		this(request, response, null, null);
	}

	public Context(Http.Request request, Http.Inbound inbound, Http.Outbound outbound) {
		this(request, null, inbound, outbound);
	}

	private Context(Http.Request request, Http.Response response, Http.Inbound inbound, Http.Outbound outbound) {
		this.request = request;
		this.response = response;
		this.inbound = inbound;
		this.outbound = outbound;
	}

	public void clear() {
		renderArgs = new Scope.RenderArgs();
		routeArgs = new Scope.RouteArgs();
		session = this.request == null ? new Scope.Session() : Scope.Session.restore(this);
		flash = this.request == null ? new Scope.Flash() : Scope.Flash.restore(request);

		initCachedBoundActionMethodArgs();
	}

	public Method getActionMethod() {
		return request == null ? null : request.invokedMethod;
	}

	private void initCachedBoundActionMethodArgs() {
		cachedBoundActionMethodArgs = new CachedBoundActionMethodArgs();
	}

	public void clearCachedBoundActionMethodArgs() {
		cachedBoundActionMethodArgs = null;
	}

	public Scope.Params getParams() {
		return request == null ? null : request.params;
	}

	public Http.Request getRequest() {
		return request;
	}

	public void setRequest(Http.Request request) {
		this.request = request;
	}

	public Http.Response getResponse() {
		return response;
	}

	public void setResponse(Http.Response response) {
		this.response = response;
	}

	public Scope.Session getSession() {
		return session;
	}

	public Scope.Flash getFlash() {
		return flash;
	}

	public Scope.RenderArgs getRenderArgs() {
		return renderArgs;
	}

	public void setRenderArgs(Scope.RenderArgs renderArgs) {
		this.renderArgs = renderArgs;
	}

	public CachedBoundActionMethodArgs getCachedBoundActionMethodArgs() {
		return cachedBoundActionMethodArgs;
	}

	public Http.Inbound getInbound() {
		return inbound;
	}

	public void setInbound(Http.Inbound inbound) {
		this.inbound = inbound;
	}

	public Http.Outbound getOutbound() {
		return outbound;
	}

	public void setOutbound(Http.Outbound outbound) {
		this.outbound = outbound;
	}

	public Validation getValidation() {
		return validation;
	}

	public void setValidation(Validation validation) {
		this.validation = validation;
	}

	public Scope.RouteArgs getRouteArgs() {
		return routeArgs;
	}

	public Locale getLocale() {
		return locale;
	}

	public void setLocale(Locale locale) {
		this.locale = locale;
	}

	public String getLocaleStr() {
		return localeStr;
	}

	public void setLocaleStr(String localeStr) {
		this.localeStr = localeStr;
	}

	public Invoker.InvocationContext getInvocationContext() {
		return invocationContext;
	}

	public void setInvocationContext(Invoker.InvocationContext invocationContext) {
		this.invocationContext = invocationContext;
	}
}
