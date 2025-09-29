package play.server.ssl;

import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import play.Logger;
import play.Play;
import play.server.HttpServerInitializer;

import javax.net.ssl.SSLEngine;

/**
 * Netty 4 replacement for SslHttpServerPipelineFactory, implemented as a subclass of HttpServerInitializer.
 */
public class HttpsServerInitializer extends HttpServerInitializer {

	private final SslContext sslCtx;

	// Keep the SSL pipeline property for backward compatibility with your old SSL factory
	private final String sslPipelineConfig = Play.configuration.getProperty(
			"play.ssl.netty.pipeline",
			"play.server.FlashPolicyHandler," +
					"io.netty.handler.codec.http.HttpRequestDecoder," +
					"play.server.StreamChunkAggregator," +
					"io.netty.handler.codec.http.HttpResponseEncoder," +
					"io.netty.handler.stream.ChunkedWriteHandler," +
					"play.server.ssl.SslPlayHandler"
	);

	public HttpsServerInitializer(SslContext sslCtx) {
		this.sslCtx = sslCtx;
	}

	@Override
	protected void initChannel(SocketChannel ch) {
		// 1) Create and configure SSLEngine similar to your previous SslHttpServerPipelineFactory
		SSLEngine engine = sslCtx.newEngine(ch.alloc());
		engine.setUseClientMode(false);

		// play.ssl.enabledCiphers
		String enabledCiphers = Play.configuration.getProperty("play.ssl.enabledCiphers", "");
		if (!enabledCiphers.isBlank()) {
			String[] ciphers = enabledCiphers.replace(" ", "").split(",");
			try { engine.setEnabledCipherSuites(ciphers); } catch (Exception e) { Logger.error(e, "Invalid ciphers configured"); }
		}

		// play.ssl.enabledProtocols
		String enabledProtocols = Play.configuration.getProperty("play.ssl.enabledProtocols", "");
		if (!enabledProtocols.isBlank()) {
			String[] protos = enabledProtocols.replace(" ", "").split(",");
			try { engine.setEnabledProtocols(protos); } catch (Exception e) { Logger.error(e, "Invalid protocols configured"); }
		}

		// play.netty.clientAuth → none | want | need
		String clientAuth = Play.configuration.getProperty("play.netty.clientAuth", "none").toLowerCase();
		switch (clientAuth) {
			case "want": engine.setWantClientAuth(true); break;
			case "need": engine.setNeedClientAuth(true); break;
			default: /* none */
		}

		ch.pipeline().addLast("ssl", new SslHandler(engine));

		// 2) Delegate to base to add the rest of the pipeline, enforcing the last handler type
		addConfiguredHandlers(ch, sslPipelineConfig, play.server.ssl.SslPlayHandler.class);
	}
}