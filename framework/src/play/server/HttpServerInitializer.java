package play.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import play.Logger;
import play.Play;
import play.exceptions.UnexpectedException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty 4 replacement for HttpServerPipelineFactory.
 * Builds a pipeline from the Play configuration key "play.netty.pipeline" and requires the last handler to be PlayHandler.
 */
public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

	protected static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

	// Keep the exact default from the old factory for backward compatibility
	private final String pipelineConfig = Play.configuration.getProperty(
			"play.netty.pipeline",
			"io.netty.handler.codec.http.HttpServerCodec," +
			"play.server.StreamChunkAggregator," +
			"io.netty.handler.stream.ChunkedWriteHandler," +
			"play.server.PlayHandler"
	);

	@Override
	protected void initChannel(SocketChannel ch) {
		addConfiguredHandlers(ch, pipelineConfig, play.server.PlayHandler.class);
	}

	protected final void addConfiguredHandlers(SocketChannel ch, String pipelineConfig, Class<?> requiredLastHandlerType) {
		ChannelPipeline pipeline = ch.pipeline();

		String[] handlers = pipelineConfig.split(",");
		if (handlers.length == 0) {
			Logger.error("You must define at least the playHandler in \"play.netty.pipeline\"");
			return;
		}

		// Last must be PlayHandler (or provided type)
		String lastFqcn = handlers[handlers.length - 1].trim();
		ChannelHandler lastInstance = newHandlerInstance(lastFqcn);
		if (!requiredLastHandlerType.isInstance(lastInstance)) {
			Logger.error("The last handler must be %s (configured via \"%s\")", requiredLastHandlerType.getName(), "play.netty.pipeline");
			return;
		}

		// Add all but last
		for (int i = 0; i < handlers.length - 1; i++) {
			String fqcn = handlers[i].trim();
			try {
				ChannelHandler instance = newHandlerInstance(fqcn);
				if (instance != null) {
					String name = simpleName(fqcn);
					pipeline.addLast(name, instance);
				}
			} catch (Throwable e) {
				Logger.error(e, " error adding %s", fqcn);
			}
		}

		// Finally, the Play handler
		String handlerName = lastInstance.getClass().getSimpleName();
		pipeline.addLast(handlerName, lastInstance);
	}

	protected static String simpleName(String fqcn) {
		int dot = fqcn.lastIndexOf('.');
		return dot > 0 ? fqcn.substring(dot + 1) : fqcn;
	}

	protected static ChannelHandler newHandlerInstance(String fqcn) {
		try {
			Class<?> clazz = CLASS_CACHE.computeIfAbsent(fqcn, name -> {
				try {
					return Class.forName(name);
				} catch (ClassNotFoundException e) {
					try {
						return Play.classloader.loadClass(name);
					} catch (ClassNotFoundException ex) {
						throw new UnexpectedException(ex);
					}
				}
			});
			if (ChannelHandler.class.isAssignableFrom(clazz)) {
				if (HttpServerCodec.class.isAssignableFrom(clazz)) {
					HttpDecoderConfig config = new HttpDecoderConfig();
					String maxInitialLineLengthStr = Play.configuration.getProperty("http.server.maxInitialLineLength");
					config.setMaxInitialLineLength(maxInitialLineLengthStr == null ? HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH : Integer.parseInt(maxInitialLineLengthStr));

					String maxHeaderSizeStr = Play.configuration.getProperty("http.server.maxHeaderSize");
					config.setMaxHeaderSize(maxHeaderSizeStr == null ? HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE : Integer.parseInt(maxHeaderSizeStr));

					String maxChunkSizeStr = Play.configuration.getProperty("http.server.maxChunkSize");
					config.setMaxChunkSize(maxChunkSizeStr == null ? HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE : Integer.parseInt(maxChunkSizeStr));

					return new HttpServerCodec(config);
				} else {
					return (ChannelHandler) clazz.getDeclaredConstructor().newInstance();
				}
			}
		} catch (Throwable t) {
			Logger.error(t, "Error instantiating %s", fqcn);
		}
		return null;
	}
}