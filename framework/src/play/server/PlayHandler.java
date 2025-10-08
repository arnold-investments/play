package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import play.Invoker;
import play.Invoker.InvocationContext;
import play.Logger;
import play.Play;
import play.exceptions.PlayException;
import play.exceptions.UnexpectedException;
import play.i18n.Messages;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.mvc.ActionInvoker;
import play.mvc.Context;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Router;
import play.mvc.Scope;
import play.mvc.WebSocketInvoker;
import play.mvc.results.NotFound;
import play.mvc.results.RenderStatic;
import play.templates.JavaExtensions;
import play.templates.TemplateLoader;
import play.utils.HTTP;
import play.utils.Utils;
import play.vfs.VirtualFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PlayHandler extends ChannelInboundHandlerAdapter {
    private static final String X_HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override";

    /**
     * If true (the default), Play will send the HTTP header "Server: Play!
     * Framework; ....". This could be a security problem (old versions having
     * publicly known security bugs), so you can disable the header in
     * application.conf: <code>http.exposePlayServer = false</code>
     */
    private static final String signature = "Play! Framework;" + Play.version + ";" + Play.mode.name().toLowerCase();
    private static final boolean exposePlayServer;

    /**
     * The Pipeline is given for a PlayHandler
     */
    private WebSocketServerHandshaker handshaker;
    
    /**
     * Define allowed methods that will be handled when defined in X-HTTP-Method-Override
     * You can define allowed method in
     * application.conf: <code>http.allowed.method.override=POST,PUT</code>
     */
    private static final Set<String> allowedHttpMethodOverride;

    static {
        exposePlayServer = !"false".equals(Play.configuration.getProperty("http.exposePlayServer"));
        allowedHttpMethodOverride = Stream.of(Play.configuration.getProperty("http.allowed.method.override", "").split(",")).collect(Collectors.toSet());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("messageReceived: begin");
        }

        // Http request
        if (msg instanceof FullHttpRequest nettyRequest) {
			try {
				// Websocket upgrade
				if (HttpHeaderValues.WEBSOCKET.toString().equalsIgnoreCase(nettyRequest.headers().get(HttpHeaderNames.UPGRADE))) {
					websocketHandshake(ctx, nettyRequest, msg);
					return;
				}

				Context context = new Context(new Request(), new Response());

				// Plain old HttpRequest
				try {
					// Reset request object and response object for the current
					// thread.

					Response response = context.getResponse();
					final Request request = parseRequest(ctx, nettyRequest, msg);

					// Buffered in memory output
					response.out = new ByteArrayOutputStream();

					// Direct output (will be set later)
					response.direct = null;

					// Streamed output (using response.writeChunk)
					response.onWriteChunk(result -> writeChunk(request, response, ctx, nettyRequest, result));

					// Raw invocation
					boolean raw = Play.pluginCollection.rawInvocation(new Context(request, response));
					if (raw) {
						copyResponse(ctx, request, response, nettyRequest);
					} else {

						// Delegate to the Play framework
						Invoker.invoke(new NettyInvocation(context, request, response, ctx, nettyRequest, msg));

					}

				} catch (Exception ex) {
					Logger.warn(ex, "Exception on request. serving 500 back");
					serve500(ex, ctx, context, nettyRequest);
				}
			} finally {
				ReferenceCountUtil.release(nettyRequest);
			}
        }

        // Websocket frame
        if (msg instanceof WebSocketFrame frame) {
			try {
				websocketFrameReceived(ctx, frame);
			} finally {
				ReferenceCountUtil.release(frame);
			}
        }

        if (Logger.isTraceEnabled()) {
            Logger.trace("messageReceived: end");
        }
    }

    private static final Map<String, RenderStatic> staticPathsCache = new HashMap<>();

    public class NettyInvocation extends Invoker.Invocation {

        protected final ChannelHandlerContext ctx;

        protected final HttpRequest nettyRequest;
        private final Object msg;

        private final Request request;
        private final Response response;

        public NettyInvocation(Context context, Request request, Response response, ChannelHandlerContext ctx, HttpRequest nettyRequest,
                Object msg) {
            super(context);
	        this.ctx = ctx;
            this.nettyRequest = nettyRequest;
            this.msg = msg;

            this.request = request;
            this.response = response;
        }

        @Override
        public boolean init() {
            Thread.currentThread().setContextClassLoader(Play.classloader);
            if (Logger.isTraceEnabled()) {
                Logger.trace("init: begin");
            }

            // JB: late init, same as in original code
            context.setRequest(request);
            context.setResponse(response);
            context.clear();

            try {
                if (Play.mode == Play.Mode.DEV) { // FIXME: should not need to check routes file on every request
                    Router.detectChanges(Play.ctxPath);

                    // JB: Prevent static request being served in dev before plugins are ready
                    if (!Play.started) {
                        Play.start(context);
                    }
                }
                if (Play.mode == Play.Mode.PROD
                        && staticPathsCache.containsKey(request.domain + " " + request.method + " " + request.path)) {
                    RenderStatic rs = null;
                    synchronized (staticPathsCache) {
                        rs = staticPathsCache.get(request.domain + " " + request.method + " " + request.path);
                    }
                    serveStatic(rs, ctx, context, nettyRequest, msg);
                    if (Logger.isTraceEnabled()) {
                        Logger.trace("init: end false");
                    }
                    return false;
                }
                Router.routeOnlyStatic(request);
                super.init();
            } catch (NotFound nf) {
                serve404(nf, ctx, context, nettyRequest);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            } catch (RenderStatic rs) {
                if (Play.mode == Play.Mode.PROD) {
                    synchronized (staticPathsCache) {
                        staticPathsCache.put(request.domain + " " + request.method + " " + request.path, rs);
                    }
                }
                serveStatic(rs, ctx, context, nettyRequest, msg);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("init: end false");
                }
                return false;
            }

            if (Logger.isTraceEnabled()) {
                Logger.trace("init: end true");
            }
            return true;
        }

        @Override
        public InvocationContext getInvocationContext() {
            ActionInvoker.resolve(context, context.getRequest());
            return new InvocationContext(
                Http.invocationType,
                context.getRequest().invokedMethod.getAnnotations(),
                context.getRequest().invokedMethod.getDeclaringClass().getAnnotations()
            );
        }

        @Override
        public void run() {
            try {
                if (Logger.isTraceEnabled()) {
                    Logger.trace("run: begin");
                }
                super.run();
            } catch (Exception e) {
                PlayHandler.serve500(e, ctx, context, nettyRequest);
            }
            if (Logger.isTraceEnabled()) {
                Logger.trace("run: end");
            }
        }

        @Override
        protected void serve500(Exception e) {
            PlayHandler.serve500(e, ctx, context, nettyRequest);
        }

        @Override
        public void execute() throws Exception {
            if (!ctx.channel().isActive()) {
                try {
                    ctx.channel().close();
                } catch (Throwable e) {
                    // Ignore
                }
                return;
            }

            // Check the exceeded size before re-rendering, so we can render the
            // error if the size is exceeded
            saveExceededSizeError(context, nettyRequest);
            ActionInvoker.invoke(context);
        }

        @Override
        public void onSuccess() throws Exception {
            super.onSuccess();

            Http.Request request = this.context.getRequest();
            Http.Response response = this.context.getResponse();

            if (response.chunked) {
                closeChunked(request, response, ctx, nettyRequest);
            } else {
                copyResponse(ctx, request, response, nettyRequest);
            }
            if (Logger.isTraceEnabled()) {
                Logger.trace("execute: end");
            }
        }
    }

    void saveExceededSizeError(Context context, HttpRequest nettyRequest) {
	    Request request = context.getRequest();

        String warning = nettyRequest.headers().get(HttpHeaderNames.WARNING);
        String length = nettyRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH);
        if (warning != null) {
            if (Logger.isTraceEnabled()) {
                Logger.trace("saveExceededSizeError: begin");
            }

            try {
                StringBuilder error = new StringBuilder();
                error.append("\u0000");
                // Cannot put warning which is
                // play.netty.content.length.exceeded
                // as Key as it will result error when printing error
                error.append("play.netty.maxContentLength");
                error.append(":");
                String size;
                try {
                    size = JavaExtensions.formatSize(Long.parseLong(length));
                } catch (Exception e) {
                    size = length + " bytes";
                }
                error.append(Messages.get(context, warning, size));
                error.append("\u0001");
                error.append(size);
                error.append("\u0000");
                if (request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS") != null
                        && request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value != null) {
                    error.append(request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS").value);
                }
                String errorData = URLEncoder.encode(error.toString(), StandardCharsets.UTF_8);
                Http.Cookie c = new Http.Cookie();
                c.value = errorData;
                c.name = Scope.COOKIE_PREFIX + "_ERRORS";
                request.cookies.put(Scope.COOKIE_PREFIX + "_ERRORS", c);
                if (Logger.isTraceEnabled()) {
                    Logger.trace("saveExceededSizeError: end");
                }
            } catch (Exception e) {
                throw new UnexpectedException("Error serialization problem", e);
            }
        }
    }

    protected static void addToResponse(Response response, HttpResponse nettyResponse) {
        Map<String, Http.Header> headers = response.headers;
        for (Map.Entry<String, Http.Header> entry : headers.entrySet()) {
            Http.Header hd = entry.getValue();
            for (String value : hd.values) {
                nettyResponse.headers().add(entry.getKey(), value);
            }
        }

        nettyResponse.headers().set(HttpHeaderNames.DATE, Utils.getHttpDateFormatter().format(new Date()));

        Map<String, Http.Cookie> cookies = response.cookies;

        for (Http.Cookie cookie : cookies.values()) {
            Cookie c = new DefaultCookie(cookie.name, cookie.value);
            c.setSecure(cookie.secure);
            c.setPath(cookie.path);
            if (cookie.domain != null) {
                c.setDomain(cookie.domain);
            }
            if (cookie.maxAge != null) {
                c.setMaxAge(cookie.maxAge);
            }
            c.setHttpOnly(cookie.httpOnly);
            nettyResponse.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
        }

        if (!response.headers.containsKey(HttpHeaderNames.CACHE_CONTROL.toString()) && !response.headers.containsKey(HttpHeaderNames.EXPIRES.toString())
                && !(response.direct instanceof File)) {
            nettyResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        }

    }

    protected static void writeResponse(ChannelHandlerContext ctx, Response response, HttpResponse nettyResponse,
            HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: begin");
        }

        boolean keepAlive = isKeepAlive(nettyRequest);

	    final boolean isHead = nettyRequest.method().equals(HttpMethod.HEAD);
	    final byte[] bytes = isHead ? new byte[0] : response.out.toByteArray();

	    ByteBuf content = null;

        if (!nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
	        if (!isHead && bytes.length > 0) {
				content = Unpooled.buffer(bytes.length);
		        content.writeBytes(bytes);
	        }

            if (Logger.isTraceEnabled()) {
                Logger.trace("writeResponse: content length [" + response.out.size() + "]");
            }
            setContentLength(nettyResponse, response.out.size());
        } else {
	        // Ensure no Content-Length for 304 and no body
	        nettyResponse.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
        }

        ChannelFuture f = null;
        if (ctx.channel().isActive()) {
	        ctx.write(nettyResponse);
			if (content != null) {
				ctx.write(content);
			}

			f = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } else {
            Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection",
                    String.valueOf(keepAlive));
        }

        // Decide whether to close the connection or not.
        if (f != null && !keepAlive) {
            // Close the connection when the whole content is written out.
            f.addListener(ChannelFutureListener.CLOSE);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("writeResponse: end");
        }
    }

    public void copyResponse(ChannelHandlerContext ctx, Request request, Response response, HttpRequest nettyRequest)
            throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: begin");
        }

        // Decide whether to close the connection or not.

        HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(HttpHeaderNames.SERVER, signature);
        }

        if (response.contentType != null) {
            nettyResponse.headers()
                .set(HttpHeaderNames.CONTENT_TYPE,
                    response.contentType + (
						response.contentType.startsWith("text/")
                        && !response.contentType.contains("charset")
							? "; charset=" + response.encoding
                            : ""
                    )
                );
        } else {
            nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=" + response.encoding);
        }

        addToResponse(response, nettyResponse);

        Object obj = response.direct;
        File file = null;
        ChunkedInput<?> stream = null;
        InputStream is = null;
        if (obj instanceof File) {
            file = (File) obj;
        } else if (obj instanceof InputStream) {
            is = (InputStream) obj;
        } else if (obj instanceof ChunkedInput) {
            // Streaming we don't know the content length
            stream = (ChunkedInput<?>) obj;
        }

        boolean keepAlive = isKeepAlive(nettyRequest);
        if (file != null && file.isFile()) {
            try {
                addEtag(nettyRequest, nettyResponse, file);
                if (nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {

                    // Write the initial line and the header.
                    ChannelFuture writeFuture = ctx.writeAndFlush(nettyResponse);

                    if (!keepAlive) {
                        // Close the connection when the whole content is
                        // written out.
                        writeFuture.addListener(ChannelFutureListener.CLOSE);
                    }
                } else {
                    FileService.serve(file, nettyRequest, nettyResponse, ctx, response, ctx.channel());
                }
            } catch (Exception e) {
                throw e;
            }
        } else if (is != null) {
	        boolean head = nettyRequest.method().equals(HttpMethod.HEAD);
	        boolean notModified = nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED);

	        if (!head && !notModified) {
		        // Use chunked transfer and ensure the last-chunk is emitted.
		        nettyResponse.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
		        nettyResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

		        // Send headers, then the body wrapped in HttpChunkedInput so it appends LastHttpContent

		        ctx.write(nettyResponse);
		        ChannelFuture writeFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedStream(is)));

		        if (!keepAlive) {
			        writeFuture.addListener(ChannelFutureListener.CLOSE);
		        }
	        } else {
		        // No body for HEAD or 304. Just flush the headers.
		        ChannelFuture writeFuture = ctx.writeAndFlush(nettyResponse);
		        if (!keepAlive) {
			        writeFuture.addListener(ChannelFutureListener.CLOSE);
		        }
		        // Close the stream if we opened it but won’t consume it
		        try { is.close(); } catch (Exception ignore) {}
	        }
        } else if (stream != null) {
	        boolean head = nettyRequest.method().equals(HttpMethod.HEAD);
	        boolean notModified = nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED);

	        if (!head && !notModified) {
		        nettyResponse.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
		        nettyResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);

		        ctx.write(nettyResponse);
		        ChannelFuture writeFuture = ctx.writeAndFlush(new HttpChunkedInput((ChunkedInput<ByteBuf>) stream));
		        if (!keepAlive) {
			        writeFuture.addListener(ChannelFutureListener.CLOSE);
		        }
	        } else {
		        ChannelFuture writeFuture = ctx.writeAndFlush(nettyResponse);
		        if (!keepAlive) {
			        writeFuture.addListener(ChannelFutureListener.CLOSE);
		        }
		        try { stream.close(); } catch (Exception ignore) {}
	        }
        } else {
            writeResponse(ctx, response, nettyResponse, nettyRequest);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("copyResponse: end");
        }
    }

    static String getRemoteIPAddress(ChannelHandlerContext ctx) {
        String fullAddress = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
        if (fullAddress.matches("/[0-9]+[.][0-9]+[.][0-9]+[.][0-9]+[:][0-9]+")) {
            fullAddress = fullAddress.substring(1);
            fullAddress = fullAddress.substring(0, fullAddress.indexOf(':'));
        } else if (fullAddress.matches(".*[%].*")) {
            fullAddress = fullAddress.substring(0, fullAddress.indexOf('%'));
        }
        return fullAddress;
    }

    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest, Object msg)
            throws Exception {
        if (Logger.isTraceEnabled()) {
            Logger.trace("parseRequest: begin");
            Logger.trace("parseRequest: URI = " + nettyRequest.uri());
        }

        String uri = nettyRequest.uri();
        // Remove domain and port from URI if it's present.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            // Begins searching / after 9th character (last / of https://)
            int index = uri.indexOf("/", 9);
            // prevent the IndexOutOfBoundsException that was occurring
            if (index >= 0) {
                uri = uri.substring(index);
            } else {
                uri = "/";
            }
        }

        String contentType = nettyRequest.headers().get(HttpHeaderNames.CONTENT_TYPE);

        int i = uri.indexOf('?');
        String querystring = "";
        String path = uri;
        if (i != -1) {
            path = uri.substring(0, i);
            querystring = uri.substring(i + 1);
        }

        String remoteAddress = getRemoteIPAddress(ctx);
        String method = nettyRequest.method().name();

        if (nettyRequest.headers().get(X_HTTP_METHOD_OVERRIDE) != null
                && allowedHttpMethodOverride.contains(nettyRequest.headers().get(X_HTTP_METHOD_OVERRIDE).intern())) {
            method = nettyRequest.headers().get(X_HTTP_METHOD_OVERRIDE).intern();
        }

        InputStream body = null;
	    ByteBuf b = nettyRequest.content();

	    int max = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "-1"));

	    try (ByteBufInputStream buffer = new ByteBufInputStream(b.retainedDuplicate(), true)) {
		    if (max != -1 && buffer.available() > max) {
			    body = new ByteArrayInputStream(new byte[0]);
		    } else {
			    body = new ByteBufInputStream(b, false);
		    }
	    }

        String host = nettyRequest.headers().get(HttpHeaderNames.HOST);
        boolean isLoopback = false;
        try {
            isLoopback = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().isLoopbackAddress()
                    && host.matches("^127\\.0\\.0\\.1:?[0-9]*$");
        } catch (Exception e) {
            // ignore it
        }

        int port = 0;
        String domain = null;
        if (host == null) {
            host = "";
            port = 80;
            domain = "";
        }
        // Check for IPv6 address
        else if (host.startsWith("[")) {
            // There is no port
            if (host.endsWith("]")) {
                domain = host;
                port = 80;
            } else {
                // There is a port so take from the last colon
                int portStart = host.lastIndexOf(':');
                if (portStart > 0 && (portStart + 1) < host.length()) {
                    domain = host.substring(0, portStart);
                    port = Integer.parseInt(host.substring(portStart + 1));
                }
            }
        }
        // Non IPv6 but has port
        else if (host.contains(":")) {
            String[] hosts = host.split(":");
            port = Integer.parseInt(hosts[1]);
            domain = hosts[0];
        } else {
            port = 80;
            domain = host;
        }

        boolean secure = false;

        Request request = Request.createRequest(remoteAddress, method, path, querystring, contentType, body, uri, host,
                isLoopback, port, domain, secure, getHeaders(nettyRequest), getCookies(nettyRequest));

        if (Logger.isTraceEnabled()) {
            Logger.trace("parseRequest: end");
        }
        return request;
    }

    protected static Map<String, Http.Header> getHeaders(HttpRequest nettyRequest) {
        Map<String, Http.Header> headers = new HashMap<>(16);

        for (String key : nettyRequest.headers().names()) {
            Http.Header hd = new Http.Header();
            hd.name = key.toLowerCase();
            hd.values = new ArrayList<>(nettyRequest.headers().getAll(key));
            headers.put(hd.name, hd);
        }

        return headers;
    }

    protected static Map<String, Http.Cookie> getCookies(HttpRequest nettyRequest) {
        Map<String, Http.Cookie> cookies = new HashMap<>(16);
        String value = nettyRequest.headers().get(HttpHeaderNames.COOKIE);
        if (value != null) {
            Set<Cookie> cookieSet = ServerCookieDecoder.STRICT.decode(value);
            for (Cookie cookie : cookieSet) {
                Http.Cookie playCookie = new Http.Cookie();
                playCookie.name = cookie.name();
                playCookie.path = cookie.path();
                playCookie.domain = cookie.domain();
                playCookie.secure = cookie.isSecure();
                playCookie.value = cookie.value();
                playCookie.httpOnly = cookie.isHttpOnly();
                cookies.put(playCookie.name, playCookie);
            }
        }
        return cookies;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
	    try {
		    if (cause instanceof io.netty.handler.codec.TooLongFrameException) {
			    Logger.error("Request exceeds size limit");
		    }

		    if (ctx.channel().isActive()) {
			    ctx.close();
		    }
	    } catch (Exception ignore) {
	    }
    }

    public static void serve404(NotFound e, ChannelHandlerContext ctx, Context context, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: begin");
        }

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        if (exposePlayServer) {
            nettyResponse.headers().set(HttpHeaderNames.SERVER, signature);
        }

        nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html");
        Map<String, Object> binding = getBindingForErrors(context, e, false);

        String format = context.getRequest().format;
        if (format == null) {
            format = "txt";
        }
        nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, (MimeTypes.getContentType(context.getResponse(), "404." + format, "text/plain")));

        String errorHtml = TemplateLoader.load("errors/404." + format).render(context, binding);

        byte[] bytes = errorHtml.getBytes(context.getResponse().encoding);
		nettyResponse.content().clear();
		nettyResponse.content().writeBytes(bytes);
        setContentLength(nettyResponse, bytes.length);

        ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
        writeFuture.addListener(ChannelFutureListener.CLOSE);

        if (Logger.isTraceEnabled()) {
            Logger.trace("serve404: end");
        }
    }

    protected static Map<String, Object> getBindingForErrors(Context context, Exception e, boolean isError) {
        Map<String, Object> binding = new HashMap<>();
        if (!isError) {
            binding.put("result", e);
        } else {
            binding.put("exception", e);
        }
        binding.put("session", context.getSession());
        binding.put("request", context.getRequest());
        binding.put("flash", context.getFlash());
        binding.put("params", context.getParams());
        binding.put("play", new Play());

        try {
            binding.put("errors", context.getValidation().errors());
        } catch (Exception ex) {
            // Logger.error(ex, "Error when getting Validation errors");
        }

        return binding;
    }

    public static void serve500(Exception e, ChannelHandlerContext ctx, Context context, HttpRequest nettyRequest) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: begin");
        }

        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.INTERNAL_SERVER_ERROR);
        if (exposePlayServer) {
            nettyResponse.headers().set(HttpHeaderNames.SERVER, signature);
        }

        Request request = context.getRequest();
        Response response = context.getResponse();

        Charset encoding = response.encoding;

        try {
            if (!(e instanceof PlayException)) {
                e = new play.exceptions.UnexpectedException(e);
            }

            // Flush some cookies
            try {
                Map<String, Http.Cookie> cookies = response.cookies;
                for (Http.Cookie cookie : cookies.values()) {
                    Cookie c = new DefaultCookie(cookie.name, cookie.value);
                    c.setSecure(cookie.secure);
                    c.setPath(cookie.path);
                    if (cookie.domain != null) {
                        c.setDomain(cookie.domain);
                    }
                    if (cookie.maxAge != null) {
                        c.setMaxAge(cookie.maxAge);
                    }
                    c.setHttpOnly(cookie.httpOnly);

                    nettyResponse.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(c));
                }

            } catch (Exception exx) {
                Logger.error(e, "Trying to flush cookies");
                // humm ?
            }
            Map<String, Object> binding = getBindingForErrors(context, e, true);

            String format = request.format;
            if (format == null) {
                format = "txt";
            }

            nettyResponse.headers().set("Content-Type", (MimeTypes.getContentType(context.getResponse(), "500." + format, "text/plain")));
            try {
                String errorHtml = TemplateLoader.load("errors/500." + format).render(context, binding);

                byte[] bytes = errorHtml.getBytes(encoding);

                nettyResponse.content().clear();
				nettyResponse.content().writeBytes(bytes);
	            setContentLength(nettyResponse, bytes.length);

	            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
            } catch (Throwable ex) {
                Logger.error(e, "Internal Server Error (500) for request %s", request.method + " " + request.url);
                Logger.error(ex, "Error during the 500 response generation");

                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(encoding);
	            nettyResponse.content().clear();
	            nettyResponse.content().writeBytes(bytes);
	            setContentLength(nettyResponse, bytes.length);

                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Throwable exxx) {
            try {
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(encoding);
	            nettyResponse.content().clear();
	            nettyResponse.content().writeBytes(bytes);
	            setContentLength(nettyResponse, bytes.length);

                ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
                writeFuture.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception fex) {
                Logger.error(fex, "(encoding ?)");
            }
            if (exxx instanceof RuntimeException) {
                throw (RuntimeException) exxx;
            }
            throw new RuntimeException(exxx);
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serve500: end");
        }
    }

    public void serveStatic(RenderStatic renderStatic, ChannelHandlerContext ctx, Context context, HttpRequest nettyRequest, Object msg) {
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: begin");
        }

        Http.Request request = context.getRequest();
        Http.Response response = context.getResponse();

        HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(response.status));
        if (exposePlayServer) {
            nettyResponse.headers().set(HttpHeaderNames.SERVER, signature);
        }
        try {
            VirtualFile file = Play.getVirtualFile(renderStatic.file);
            if (file != null && file.exists() && file.isDirectory()) {
                file = file.child("index.html");
                if (file != null) {
                    renderStatic.file = file.relativePath();
                }
            }
            if ((file == null || !file.exists())) {
                serve404(new NotFound("The file " + renderStatic.file + " does not exist"), ctx, context, nettyRequest);
            } else {
                boolean raw = Play.pluginCollection.serveStatic(file, request, response);
                if (raw) {
                    copyResponse(ctx, request, response, nettyRequest);
                } else {
                    File localFile = file.getRealFile();
                    boolean keepAlive = isKeepAlive(nettyRequest);
                    addEtag(nettyRequest, nettyResponse, localFile);

                    if (nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                        Channel ch = ctx.channel();

                        // Write the initial line and the header.
                        ChannelFuture writeFuture = ch.writeAndFlush(nettyResponse);
                        if (!keepAlive) {
                            // Write the content.
                            writeFuture.addListener(ChannelFutureListener.CLOSE);
                        }
                    } else {
                        FileService.serve(localFile, nettyRequest, nettyResponse, ctx, response, ctx.channel());
                    }
                }

            }
        } catch (Throwable ez) {
            Logger.error(ez, "serveStatic for request %s", request.method + " " + request.url);
            try {
                FullHttpResponse errorResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.INTERNAL_SERVER_ERROR);
                String errorHtml = "Internal Error (check logs)";
                byte[] bytes = errorHtml.getBytes(response.encoding);
	            errorResponse.content().clear();
	            errorResponse.content().writeBytes(bytes);
	            setContentLength(errorResponse, bytes.length);

                ChannelFuture future = ctx.channel().writeAndFlush(errorResponse);
                future.addListener(ChannelFutureListener.CLOSE);
            } catch (Exception ex) {
                Logger.error(ex, "serveStatic for request %s", request.method + " " + request.url);
            }
        }
        if (Logger.isTraceEnabled()) {
            Logger.trace("serveStatic: end");
        }
    }

    public static boolean isModified(String etag, long last, HttpRequest nettyRequest) {
        String browserEtag = nettyRequest.headers().get(HttpHeaderNames.IF_NONE_MATCH);
        String ifModifiedSince = nettyRequest.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        return HTTP.isModified(etag, last, browserEtag, ifModifiedSince);
    }

    private static HttpResponse addEtag(HttpRequest nettyRequest, HttpResponse httpResponse, File file) {
        if (Play.mode == Play.Mode.DEV) {
            httpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        } else {
            // Check if Cache-Control header is not set
            if (httpResponse.headers().get(HttpHeaderNames.CACHE_CONTROL) == null) {
                String maxAge = Play.configuration.getProperty("http.cacheControl", "3600");
                if (maxAge.equals("0")) {
                    httpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
                } else {
                    httpResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=" + maxAge);
                }
            }
        }
        boolean useEtag = Play.configuration.getProperty("http.useETag", "true").equals("true");
        long last = file.lastModified();
        String etag = "\"" + last + "-" + file.hashCode() + "\"";
        if (!isModified(etag, last, nettyRequest)) {
            if (nettyRequest.method().equals(HttpMethod.GET)) {
                httpResponse.setStatus(HttpResponseStatus.NOT_MODIFIED);
            }
            if (useEtag) {
                httpResponse.headers().set(HttpHeaderNames.ETAG, etag);
            }

        } else {
            httpResponse.headers().set(HttpHeaderNames.LAST_MODIFIED, Utils.getHttpDateFormatter().format(new Date(last)));
            if (useEtag) {
                httpResponse.headers().set(HttpHeaderNames.ETAG, etag);
            }
        }
        return httpResponse;
    }

    public static boolean isKeepAlive(HttpMessage message) {
        return HttpHeaders.isKeepAlive(message) && message.getProtocolVersion().equals(HttpVersion.HTTP_1_1);
    }

    public static void setContentLength(HttpMessage message, long contentLength) {
        message.headers().set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(contentLength));
    }

	static final class LazyChunkedInput implements ChunkedInput<ByteBuf> {

		private final ConcurrentLinkedQueue<ByteBuf> nextChunks = new ConcurrentLinkedQueue<>();
		private volatile boolean closed = false;
		private long transferred = 0; // optional progress reporting

		@Override
		public boolean isEndOfInput() {
			return closed && nextChunks.isEmpty();
		}

		@Override
		public void close() throws Exception {
			closed = true; // Do NOT enqueue "0\r\n\r\n" — HttpChunkedInput will handle last-chunk.
		}

		@Override
		public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
			ByteBuf buf = nextChunks.poll();
			if (buf == null) {
				return null; // nothing ready right now
			}
			transferred += buf.readableBytes();
			return buf;
		}

		// Netty 4.1+ preferred method
		@Override
		public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
			ByteBuf buf = nextChunks.poll();
			if (buf == null) {
				return null; // no chunk ready right now
			}
			transferred += buf.readableBytes();
			return buf;
		}

		// For older 4.0.x you might also implement the deprecated variant:
		// public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception { return readChunk(ctx.alloc()); }

		@Override
		public long length() {
			return -1; // unknown
		}

		@Override
		public long progress() {
			return transferred;
		}

		/** Enqueue a chunk to be sent. */
		public void writeChunk(Response response, Object chunk) throws Exception {
			if (closed) {
				throw new Exception("HTTP output stream closed");
			}

			ByteBuf buf;
			if (chunk instanceof byte[]) {
				buf = Unpooled.wrappedBuffer((byte[]) chunk); // refCnt = 1; will be released by the write pipeline
			} else {
				String s = (chunk == null) ? "" : chunk.toString();
				Charset cs = response.encoding;
				buf = Unpooled.copiedBuffer(s, cs); // create a new buffer with encoded bytes
			}

			nextChunks.offer(buf);
			// If you need to nudge the pipeline to continue, make sure to call ctx.flush()
			// from wherever you're driving the upload after enqueueing a chunk.
		}
	}

    public void writeChunk(Request playRequest, Response playResponse, ChannelHandlerContext ctx,
            HttpRequest nettyRequest, Object chunk) {
        try {
            if (playResponse.direct == null) {
                playResponse.setHeader("Transfer-Encoding", "chunked");
                playResponse.direct = new LazyChunkedInput();
                copyResponse(ctx, playRequest, playResponse, nettyRequest);
            }
            ((LazyChunkedInput) playResponse.direct).writeChunk(playResponse, chunk);
			ctx.flush();

            if (ctx.pipeline().get("ChunkedWriteHandler") instanceof ChunkedWriteHandler wh) {
                wh.resumeTransfer();
            }
            if (ctx.pipeline().get("SslChunkedWriteHandler") instanceof ChunkedWriteHandler wh) {
                wh.resumeTransfer();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    public void closeChunked(Request playRequest, Response playResponse, ChannelHandlerContext ctx,
            HttpRequest nettyRequest) {
        try {
            ((LazyChunkedInput) playResponse.direct).close();
			ctx.flush();

	        if (ctx.pipeline().get("ChunkedWriteHandler") instanceof ChunkedWriteHandler wh) {
		        wh.resumeTransfer();
	        }
	        if (ctx.pipeline().get("SslChunkedWriteHandler") instanceof ChunkedWriteHandler wh) {
		        wh.resumeTransfer();
	        }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
    }

    // ~~~~~~~~~~~ Websocket
    static final Map<ChannelHandlerContext, Http.Inbound> channels = new ConcurrentHashMap<>();

    private void websocketFrameReceived(ChannelHandlerContext ctx, WebSocketFrame frame) {
        Http.Inbound inbound = channels.get(ctx);

	    if (frame instanceof CloseWebSocketFrame) { // Close handshake
		    handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
	    } else if (frame instanceof PingWebSocketFrame) { // Ping -> Pong (retain the content)
		    ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
	    } else if (frame instanceof BinaryWebSocketFrame) { // Binary payload
		    byte[] bytes = ByteBufUtil.getBytes(frame.content()); // copies out of the ByteBuf
		    inbound._received(new Http.WebSocketFrame(bytes));
	    } else if (frame instanceof TextWebSocketFrame) { // Text payload
		    inbound._received(new Http.WebSocketFrame(((TextWebSocketFrame) frame).text()));
	    }
    }

    private String getWebSocketLocation(HttpRequest req) {
        return "ws://" + req.headers().get(HttpHeaderNames.HOST) + req.uri();
    }

    private void websocketHandshake(final ChannelHandlerContext ctx, FullHttpRequest req, Object msg)
            throws Exception {

        int max = Integer.parseInt(Play.configuration.getProperty("play.netty.maxContentLength", "65345"));

        // Upgrade the pipeline as the handshaker needs the HttpStream
        // Aggregator
        ctx.pipeline().addLast("fake-aggregator", new HttpObjectAggregator(max));
        try {
            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                    this.getWebSocketLocation(req), null, false);
            this.handshaker = wsFactory.newHandshaker(req);
            if (this.handshaker == null) {
	            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            } else {
                try {
                    this.handshaker.handshake(ctx.channel(), req);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            // Remove fake aggregator in case handshake was not a success, it is
            // still lying around
            try {
                ctx.pipeline().remove("fake-aggregator");
            } catch (Exception e) {
            }
        }
        Http.Request request = parseRequest(ctx, req, msg);

        // Route the websocket request
        request.method = "WS";

        Map<String, String> route = Router.route(request.method, request.path);
        if (!route.containsKey("action")) {
            // No route found to handle this websocket connection
            ctx.channel().writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
		            .addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Inbound
        Http.Inbound inbound = new Http.Inbound(ctx) {
            @Override
            public boolean isOpen() {
                return ctx.channel().isOpen();
            }
        };
        channels.put(ctx, inbound);

        // Outbound
        Http.Outbound outbound = new Http.Outbound() {
            final List<ChannelFuture> writeFutures = Collections.synchronizedList(new ArrayList<ChannelFuture>());
            Promise<Void> closeTask;

            synchronized void writeAndClose(ChannelFuture writeFuture) {
                if (!writeFuture.isDone()) {
                    writeFutures.add(writeFuture);
                    writeFuture.addListener(cf -> {
                        writeFutures.remove(cf);
                        futureClose();
                    });
                }
            }

            void futureClose() {
                if (closeTask != null && writeFutures.isEmpty()) {
                    closeTask.invoke(null);
                }
            }

            @Override
            public void send(String data) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }
                writeAndClose(ctx.channel().writeAndFlush(new TextWebSocketFrame(data)));
            }

            @Override
            public void send(byte opcode, byte[] data, int offset, int length) {
                if (!isOpen()) {
                    throw new IllegalStateException("The outbound channel is closed");
                }

                writeAndClose(ctx.channel().writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data, offset, length))));
            }

            @Override
            public synchronized boolean isOpen() {
                return ctx.channel().isOpen() && closeTask == null;
            }

            @Override
            public synchronized void close() {
                closeTask = new Promise<>();
                closeTask.onRedeem(completed -> {
                    writeFutures.clear();
                    ctx.channel().disconnect();
                    closeTask = null;
                });
                futureClose();
            }
        };

        Context context = new Context(request, inbound, outbound);

        Logger.trace("invoking");

        Invoker.invoke(new WebSocketInvocation(route, context, ctx, msg));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Http.Inbound inbound = channels.remove(ctx);
        if (inbound != null) {
            inbound.close();
        }

	    ctx.fireChannelInactive();
    }

    public static class WebSocketInvocation extends Invoker.Invocation {

        Map<String, String> route;

        ChannelHandlerContext ctx;
        Object msg;

        public WebSocketInvocation(Map<String, String> route, Context context, ChannelHandlerContext ctx, Object msg) {
	        super(context);
	        this.route = route;

            this.ctx = ctx;
            this.msg = msg;
        }

        @Override
        public InvocationContext getInvocationContext() {
            WebSocketInvoker.resolve(context, context.getRequest());
            return new InvocationContext(Http.invocationType, context.getRequest().invokedMethod.getAnnotations(),
                    context.getRequest().invokedMethod.getDeclaringClass().getAnnotations());
        }

        @Override
        public void execute() throws Exception {
            WebSocketInvoker.invoke(context);
        }

        @Override
        public void onException(Throwable e) {
            Logger.error(e, "Internal Server Error in WebSocket (closing the socket) for request %s",
                    context.getRequest().method + " " + context.getRequest().url);
            ctx.channel().close();
            super.onException(e);
        }

        @Override
        public void onSuccess() throws Exception {
            context.getOutbound().close();
            super.onSuccess();
        }
    }
}
