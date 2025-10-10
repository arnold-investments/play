package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.WrappedByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import org.apache.commons.io.IOUtils;
import play.Play;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.WARNING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;

public class StreamChunkAggregator extends SimpleChannelInboundHandler<HttpObject> {
	private static final int MAX_CONTENT_LENGTH_INT = Integer.parseInt(
			Play.configuration.getProperty("play.netty.maxContentLength", "-1")
	);

	private HttpRequest currentRequest;
	private boolean chunked;
	private CompositeByteBuf memBody;   // small/known-size in-memory aggregate
	private OutputStream out;           // streaming-to-file
	private File file;
	private long rawSoFar;
	boolean shouldStartFile = false;
	boolean shouldAllocMemBody = false;

	public StreamChunkAggregator() { super(false); }


	@Override
	protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
		boolean release = true;
		try {
			if (msg instanceof HttpRequest req) {
				// --- START a new aggregation cycle; DO NOT forward the plain HttpRequest ---
				resetState();
				currentRequest = req;
				chunked = HttpUtil.isTransferEncodingChunked(req);

				final long cl = HttpUtil.getContentLength(req, -1);

				if (msg instanceof FullHttpRequest alreadyFull) {
					// Upstream already aggregated → just wrap & fire now (it IS a FullHttpRequest)
					ctx.fireChannelRead(alreadyFull);
					release = false;
					resetState();
					return;
				}

				if (chunked) {
					stripChunkedFromTransferEncoding(req.headers());
					shouldStartFile = true;
				} else if (cl == 0) {
					// no request body expected; we'll emit an empty FullHttpRequest upon LastHttpContent
				} else {
					shouldAllocMemBody = true;
				}
				return; // wait for HttpContent (including Last)
			}

			if (msg instanceof HttpContent part) {
				if (currentRequest == null) { // out-of-band
					ctx.fireChannelRead(part);
					release = false;
					return;
				}

				final ByteBuf content = part.content();
				// mirror legacy maxContentLength guard
				if (MAX_CONTENT_LENGTH_INT != -1) {
					long have = (file != null ? rawSoFar : (memBody != null ? memBody.readableBytes() : 0));
					if (have + content.readableBytes() > MAX_CONTENT_LENGTH_INT) {
						currentRequest.headers().set(WARNING, "play.netty.content.length.exceeded");
					}
				}

				if (shouldStartFile) {
					startFile();
					shouldStartFile = false;
				}

				if (shouldAllocMemBody) {
					if (memBody != null) {
						System.out.println("memBody != null");
					}

					memBody = ctx.alloc().compositeBuffer();
					shouldAllocMemBody = false;
				}

				if (file != null) {
					try( ByteBufInputStream in = new ByteBufInputStream(content)) {
						rawSoFar += IOUtils.copyLarge(in, out);
					}
				} else if (memBody != null && content.isReadable()) {
					memBody.addComponent(true, content);
					release = false;
				}

				if (part instanceof LastHttpContent last) {
					emitFull(ctx, last.trailingHeaders());
					release = false;
				}
				return;
			}

			// Non-http → pass through
			ctx.fireChannelRead(msg);
			release = false;
		} finally {
			if (release) {
				ReferenceCountUtil.release(msg);
			}
		}
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		resetState();
		ctx.fireExceptionCaught(cause);
	}

	@Override
	public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
		resetState();
		super.handlerRemoved(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		resetState();
		super.channelInactive(ctx);
	}

	// ==== build & emit ====

	private void emitFull(ChannelHandlerContext ctx, HttpHeaders trailing) throws IOException {
		FullHttpRequest full;

		if (file != null) {
			try { out.flush(); } finally { safeClose(out); out = null; }
			final long len = file.length();
			currentRequest.headers().set(CONTENT_LENGTH, String.valueOf(len));

			// File-backed content with cleanup on release.
			// Use mmap (zero-copy-ish).
			ByteBuf contentBuf;
			MappedByteBuffer mbb;

			try (
				FileInputStream fis = new FileInputStream(file);
			    FileChannel ch = fis.getChannel()
			) {
				mbb = ch.map(FileChannel.MapMode.READ_ONLY, 0, len);
				ByteBuf mapped = Unpooled.wrappedBuffer(mbb);
				File captured = file; // capture BEFORE resetState

				contentBuf = new CleanupOnReleaseByteBuf(mapped, () -> {
					CleanerUtil.tryUnmap(mbb);
					try {
						if (captured != null) {
							captured.delete();
						}
					} catch (Throwable ignore) {}
				});
			}

			full = new DefaultFullHttpRequest(
				currentRequest.protocolVersion(),
				currentRequest.method(),
				currentRequest.uri(),
				contentBuf,
				copyHeadersWithoutTE(currentRequest.headers()),
				trailing
			);

			full.headers().remove(TRANSFER_ENCODING);

			ctx.fireChannelRead(full);
			resetState();

			return;
		}

		if (memBody != null) {
			int len = memBody.readableBytes();
			currentRequest.headers().set(CONTENT_LENGTH, String.valueOf(len));
			full = new DefaultFullHttpRequest(
				currentRequest.protocolVersion(),
				currentRequest.method(),
				currentRequest.uri(),
				memBody, // transfer ownership
				copyHeadersWithoutTE(currentRequest.headers()),
				trailing
			);

			full.headers().remove(TRANSFER_ENCODING);

			currentRequest = null;
			memBody = null;
			ctx.fireChannelRead(full);
			return;
		}

		// no body (e.g., GET or CL=0). Emit empty FullHttpRequest.
		full = new DefaultFullHttpRequest(
			currentRequest.protocolVersion(),
			currentRequest.method(),
			currentRequest.uri(),
			Unpooled.EMPTY_BUFFER,
			copyHeadersWithoutTE(currentRequest.headers()),
			trailing
		);

		full.headers().remove(TRANSFER_ENCODING);
		full.headers().set(CONTENT_LENGTH, "0");

		resetState();
		ctx.fireChannelRead(full);
	}

	// ==== helpers ====

	private void startFile() throws IOException {
		file = new File(Play.tmpDir, UUID.randomUUID().toString());
		out = new FileOutputStream(file, true);
		rawSoFar = 0L;
	}

	private static void stripChunkedFromTransferEncoding(HttpHeaders h) {
		List<String> encodings = new ArrayList<>(h.getAll(TRANSFER_ENCODING));
		encodings.removeIf(CHUNKED::contentEqualsIgnoreCase);

		if (encodings.isEmpty()) {
			h.remove(TRANSFER_ENCODING);
		} else {
			h.set(TRANSFER_ENCODING, encodings);
		}
	}

	private static HttpHeaders copyHeadersWithoutTE(HttpHeaders src) {
		HttpHeaders dst = new DefaultHttpHeaders(false);

		for (Map.Entry<String, String> e : src) {
			if (!e.getKey().equalsIgnoreCase(TRANSFER_ENCODING.toString())) {
				dst.add(e.getKey(), e.getValue());
			}
		}

		return dst;
	}

	private static void safeClose(Closeable c) { try { if (c != null) c.close(); } catch (IOException ignore) {} }

	private void resetState() {
		shouldStartFile = false;
		shouldAllocMemBody = false;
		currentRequest = null;
		chunked = false;
		safeClose(out);
		out = null;

		if (file != null) {
			try {
				file.delete();
			} catch (Throwable ignore) {}
			file = null;
		}

		rawSoFar = 0L;
		if (memBody != null && memBody.refCnt() > 0) {
			memBody.release();

			if (memBody.refCnt() > 0) {
				System.out.println("memBody.refCnt() > 0 after resetState");
			}
		}
		memBody = null;
	}

	// --- cleanup-on-release wrapper (deletes file/unmaps when refCnt -> 0) ---
	private static final class CleanupOnReleaseByteBuf extends WrappedByteBuf {
		private final Runnable onDeallocate;
		private volatile boolean done;

		CleanupOnReleaseByteBuf(ByteBuf delegate, Runnable onDeallocate) {
			super(delegate);
			this.onDeallocate = onDeallocate;
		}

		@Override
		public boolean release() {
			boolean d = super.release();
			if (d) {
				runOnce();
			}
			return d;
		}

		@Override
		public boolean release(int dec) {
			boolean d = super.release(dec);
			if (d) {
				runOnce();
			}
			return d;
		}

		private void runOnce() {
			if (done) {
				return;
			}

			done = true;
			try {
				onDeallocate.run();
			} catch (Throwable ignore) {}
		}
	}

	// --- best-effort unmap for JDK9+; safe no-op if not available ---
	private static final class CleanerUtil {
		private static final Method INVOKE_CLEANER;
		private static final Object UNSAFE;
		static {
			Method m = null; Object u = null;
			try {
				Class<?> unsafeClz = Class.forName("sun.misc.Unsafe");
				Field f = unsafeClz.getDeclaredField("theUnsafe");
				f.setAccessible(true);
				u = f.get(null);
				m = unsafeClz.getMethod("invokeCleaner", ByteBuffer.class);
			} catch (Throwable ignore) { }
			INVOKE_CLEANER = m; UNSAFE = u;
		}

		static void tryUnmap(ByteBuffer bb) {
			if (INVOKE_CLEANER != null && UNSAFE != null) {
				try { INVOKE_CLEANER.invoke(UNSAFE, bb); } catch (Throwable ignore) {}
			}
		}
	}
}