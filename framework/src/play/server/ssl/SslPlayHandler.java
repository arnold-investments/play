package play.server.ssl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import play.Logger;
import play.mvc.Http.Request;
import play.server.PlayHandler;
import play.server.Server;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;

import static io.netty.handler.codec.http.HttpHeaders.Names.LOCATION;

public class SslPlayHandler extends PlayHandler {

    @Override
    public Request parseRequest(ChannelHandlerContext ctx, FullHttpRequest nettyRequest, Object msg) throws Exception {
        Request request = super.parseRequest(ctx, nettyRequest, msg);
        request.secure = true;
        return request;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Get the SslHandler in the current pipeline.
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);

        // Get notified when the SSL handshake is done.
	    sslHandler.handshakeFuture().addListener(new SslListener());
    }

    private static final class SslListener implements GenericFutureListener<Future<? super Channel>> {
	    @Override
	    public void operationComplete(Future<? super Channel> future) throws Exception {
		    if (!future.isSuccess()) {
			    Logger.debug(future.cause(), "Invalid certificate");
		    }
	    }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        // We have to redirect to https://, as it was targeting http://
        // Redirect to the root as we don't know the url at that point
        if (cause instanceof SSLException) {
            Logger.debug(cause, "");
            InetSocketAddress inet = ((InetSocketAddress) ctx.channel().localAddress());
            ctx.pipeline().remove("ssl");
            HttpResponse nettyResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.TEMPORARY_REDIRECT);
            nettyResponse.headers().set(LOCATION, "https://" + inet.getHostName() + ":" + Server.httpsPort + "/");
            ChannelFuture writeFuture = ctx.channel().writeAndFlush(nettyResponse);
            writeFuture.addListener(ChannelFutureListener.CLOSE);
        } else {
            Logger.error(cause, "");
            ctx.channel().close();
        }
    }

}
