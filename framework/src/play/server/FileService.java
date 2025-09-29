package play.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import org.apache.commons.io.IOUtils;
import play.Logger;
import play.exceptions.UnexpectedException;
import play.libs.MimeTypes;
import play.mvc.Http.Response;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;


public class FileService  {
    public static void serve(File localFile, HttpRequest nettyRequest, HttpResponse nettyResponse, ChannelHandlerContext ctx, Response response, Channel channel) throws FileNotFoundException {
        RandomAccessFile raf = new RandomAccessFile(localFile, "r");

        try {
            long fileLength = raf.length();
            
            boolean isKeepAlive = HttpUtil.isKeepAlive(nettyRequest) && nettyRequest.protocolVersion().equals(HttpVersion.HTTP_1_1);
            
            if(Logger.isTraceEnabled()) {
                Logger.trace("keep alive %s", String.valueOf(isKeepAlive));
                Logger.trace("content type %s", (response.contentType != null ? response.contentType : MimeTypes.getContentType(response, localFile.getName(), "text/plain")));
            }
            
            if (!nettyResponse.status().equals(HttpResponseStatus.NOT_MODIFIED)) {
                // Add the 'Content-Length' header only for a keep-alive connection.
                if(Logger.isTraceEnabled()){
                    Logger.trace("file length " + fileLength);
                }
            }

            // Write the initial line and the header.
	        final String contentType = (response.contentType != null
			        ? response.contentType
			        : MimeTypes.getContentType(response, localFile.getName(), "text/plain"));

	        boolean isRange = ByteRangeInput.accepts(nettyRequest);
	        ByteRangeInput bri;

			if (isRange) {
				bri = new ByteRangeInput(raf, contentType, nettyRequest);
			} else {
				bri = null;
			}

	        boolean isHead = nettyRequest.method().equals(HttpMethod.HEAD);

	        nettyResponse.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");

	        boolean zeroCopyPossible;
	        if (!isRange && !isHead && fileLength > 0) {
	        	// Zero-copy is only possible when there's no SSL/TLS in the pipeline
	        	zeroCopyPossible = (ctx.pipeline().get(SslHandler.class) == null);
	        } else {
		        zeroCopyPossible = false;
	        }

	        // NOTE: HEAD request with 'range' header is invalid
	        if (isRange) {
		        nettyResponse.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);

		        // Range path: ByteRangeInput will set status, Content-Range, and Content-Length
		        bri.prepareNettyResponse(nettyResponse);

		        // For single-range responses, ensure the response Content-Type is the file type
		        // (multipart is already set inside prepareNettyResponse)
	        } else if (zeroCopyPossible) {
		        nettyResponse.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
		        HttpUtil.setContentLength(nettyResponse, fileLength);
	        } else {
		        // Fallback: stream with ChunkedFile
		        nettyResponse.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
		        HttpUtil.setTransferEncodingChunked(nettyResponse, true);
	        }

	        if (nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE) == null) {
		        nettyResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
	        }

	        // Now send headers and optionally the body
	        if (!isHead) {
		        if (channel.isOpen()) {
			        addWriteFutureLogging(ctx.write(nettyResponse));

			        ChannelFuture writeFuture;

			        if (isRange) {
				        // Go through encoder; ByteRangeInput already set exact Content-Length
				        writeFuture = ctx.writeAndFlush(new HttpChunkedInput(bri));
			        } else if (zeroCopyPossible) {
				        // Bypass ONLY the encoder for FileRegion
				        addWriteFutureLogging(ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength)));
				        writeFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			        } else {
				        writeFuture = ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(raf)));
			        }

					handleWriteFuture(writeFuture, isKeepAlive, raf);
		        } else {
		        	Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection", String.valueOf(isKeepAlive));
			        IOUtils.closeQuietly(raf);
		        }
	        } else {
		        nettyResponse.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
		        HttpUtil.setContentLength(nettyResponse, fileLength);
		
		        // HEAD: send headers only (with correct framing) and close the file
		        if (channel.isOpen()) {
		        	handleWriteFuture(channel.writeAndFlush(nettyResponse), isKeepAlive, raf);
		        } else {
		        	Logger.debug("Try to write on a closed channel[keepAlive:%s]: Remote host may have closed the connection", String.valueOf(isKeepAlive));
			        IOUtils.closeQuietly(raf);
		        }
	        }
        } catch (Throwable exx) {
            exx.printStackTrace();
            IOUtils.closeQuietly(raf);
            try {
                if (ctx.channel().isOpen()) {
                    ctx.channel().close();
                }
            } catch (Throwable ex) { /* Left empty */ }
        }


    }

	private static void handleWriteFuture(ChannelFuture writeFuture, boolean isKeepAlive, RandomAccessFile raf) {
		if (writeFuture != null) {
			writeFuture.addListener(_ -> IOUtils.closeQuietly(raf));

			addWriteFutureLogging(writeFuture);
		}

		if (writeFuture != null && !isKeepAlive) {
			writeFuture.addListener(ChannelFutureListener.CLOSE);
		}
	}

	private static void addWriteFutureLogging(ChannelFuture writeFuture) {
		if (writeFuture != null) {
			writeFuture.addListener(f -> {
				if (!f.isSuccess()) {
					Logger.error(f.cause(), "FileService body write failed");
				} else {
					Logger.trace("FileService body write succeeded");
				}
			});
		}
	}
    

    public static class ByteRangeInput implements ChunkedInput<ByteBuf> {
        RandomAccessFile raf;
        HttpRequest request;
        int chunkSize = 8096;
        ByteRange[] byteRanges;
        int currentByteRange = 0;
        String contentType;
        
        boolean unsatisfiable = false;
        
        long fileLength;

	    long totalResponseLength = -1L;
	    long bytesSent = 0L;

	    private byte[] finalBoundary = null;   // only for multipart
	    private int servedFinalBoundary = 0;   // progress within finalBoundary
        
        public ByteRangeInput(File file, String contentType, HttpRequest request) throws FileNotFoundException, IOException {
            this(new RandomAccessFile(file, "r"), contentType, request);
        }
        
        public ByteRangeInput(RandomAccessFile raf, String contentType, HttpRequest request) throws FileNotFoundException, IOException {
            this.raf = raf;
            this.request = request;
            fileLength = raf.length();
            this.contentType = contentType;
            initRanges();
            if (Logger.isDebugEnabled()) {
                Logger.debug("Invoked ByteRangeServer, found byteRanges: %s (with header Range: %s)",
                        Arrays.toString(byteRanges), request.headers().get("range"));
            }
        }

	    public void prepareNettyResponse(HttpResponse nettyResponse) {
		    if (unsatisfiable) {
			    nettyResponse.setStatus(HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
			    nettyResponse.headers().set(HttpHeaderNames.CONTENT_RANGE, "bytes */" + fileLength);
			    nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
			    totalResponseLength = 0L;
			    return;
		    }

		    nettyResponse.setStatus(HttpResponseStatus.PARTIAL_CONTENT);
		    if (byteRanges.length == 1) {
			    ByteRange range = byteRanges[0];
			    nettyResponse.headers().set(
					    HttpHeaderNames.CONTENT_RANGE,
					    "bytes " + range.start + "-" + range.end + "/" + fileLength
			    );
			    // Content-Type for single-range is set in serve(...)
		    } else {
			    nettyResponse.headers().set(
					    HttpHeaderNames.CONTENT_TYPE,
					    "multipart/byteranges; boundary=" + DEFAULT_SEPARATOR
			    );
		    }

		    long length = 0;
		    for (ByteRange range : byteRanges) {
			    length += range.computeTotalLength();
		    }
		    if (byteRanges.length > 1 && finalBoundary != null) {
			    length += finalBoundary.length; // include final boundary in response length
		    }
		    totalResponseLength = length;
		    nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
	    }

	    @Override
	    public ByteBuf readChunk(ByteBufAllocator allocator) throws Exception {
	        if (Logger.isTraceEnabled())
	            Logger.trace("FileService nextChunk (allocator)");
	        try {
	            int count = 0;
	            byte[] buffer = new byte[chunkSize];
	            while (count < chunkSize && currentByteRange < byteRanges.length && byteRanges[currentByteRange] != null) {
	                if (byteRanges[currentByteRange].remaining() > 0) {
	                    count += byteRanges[currentByteRange].fill(buffer, count);
	                } else {
	                    currentByteRange++;
	                }
	            }
	            if (count == 0) {
		            if (finalBoundary != null && servedFinalBoundary < finalBoundary.length) {
			            int toCopy = Math.min(chunkSize, finalBoundary.length - servedFinalBoundary);
			            ByteBuf out = allocator.buffer(toCopy);
			            out.writeBytes(finalBoundary, servedFinalBoundary, toCopy);
			            servedFinalBoundary += toCopy;
			            bytesSent += toCopy;
			            return out;
		            }
	                return null;
	            }
		        bytesSent += count; // NEW: track progress
		        // Use the allocator to create a ByteBuf sized to the actual bytes read
	            ByteBuf out = allocator.buffer(count);
	            out.writeBytes(buffer, 0, count);
	            return out;
	        } catch (Exception e) {
	            Logger.error(e, "error sending file");
	            throw e;
	        }
	    }

        @Override
        public ByteBuf readChunk(ChannelHandlerContext ctx) throws Exception {
            if(Logger.isTraceEnabled())
                Logger.trace("FileService nextChunk");
            try {
                int count = 0;
                byte[] buffer = new byte[chunkSize];
                while(count < chunkSize && currentByteRange < byteRanges.length && byteRanges[currentByteRange] != null) {
                    if(byteRanges[currentByteRange].remaining() > 0) {
                        count += byteRanges[currentByteRange].fill(buffer, count);
                    } else {
                        currentByteRange++;
                    }
                }
                if(count == 0){
	                if (finalBoundary != null && servedFinalBoundary < finalBoundary.length) {
		                int toCopy = Math.min(chunkSize, finalBoundary.length - servedFinalBoundary);
		                ByteBuf out = Unpooled.wrappedBuffer(finalBoundary, servedFinalBoundary, toCopy);
		                servedFinalBoundary += toCopy;
		                bytesSent += toCopy;
		                return out;
	                }
                    return null;
                }
	            bytesSent += count; // NEW: track progress
	            return Unpooled.wrappedBuffer(buffer, 0, count);
            } catch (Exception e) {
                Logger.error(e, "error sending file");
                throw e;
            }
        }



	    @Override
	    public long length() {
		    return totalResponseLength >= 0 ? totalResponseLength : -1L;
	    }

	    @Override
	    public long progress() {
		    return bytesSent;
	    }

	    @Override
        public boolean isEndOfInput() throws Exception {
		    // Walk remaining ranges and check header, data, tail
		    int idx = currentByteRange;
		    while (idx < byteRanges.length) {
			    ByteRange r = byteRanges[idx];
			    boolean headerDone = r.servedHeader >= r.header.length;
			    boolean dataRemaining = r.remaining() > 0;
			    boolean tailDone = r.servedTail >= r.tail.length;
			    if (!headerDone || dataRemaining || !tailDone) {
				    if (Logger.isTraceEnabled()) Logger.trace("FileService isEndOfInput(): false");
				    return false;
			    }
			    idx++;
		    }
		    boolean end = (finalBoundary == null) || (servedFinalBoundary >= finalBoundary.length);
		    if (Logger.isTraceEnabled()) Logger.trace("FileService isEndOfInput(): " + end);
		    return end;
        }
        
        @Override
        public void close() throws Exception {
            raf.close();
        }
        
        public static boolean accepts(HttpRequest request) {
	        String v = request.headers().get("range");
	        return v != null && v.trim().toLowerCase(Locale.ROOT).startsWith("bytes=");
        }
        
        private void initRanges() {
            try {
                String headerValue = request.headers().get("range").trim().substring("bytes=".length());
                String[] rangesValues = headerValue.split(",");
                ArrayList<long[]> ranges = new ArrayList<>(rangesValues.length);
                for (String rangeValue : rangesValues) {
                    long start, end;
                    if(rangeValue.startsWith("-")) {
	                    long n = Long.parseLong(rangeValue.substring(1));
	                    if (n <= 0) continue; // ignore invalid
	                    if (n > fileLength) n = fileLength;
	                    start = fileLength - n;      // FIX
	                    end   = fileLength - 1;
                    } else {
                        String[] range = rangeValue.split("-");
                        start = Long.parseLong(range[0]);
                        end = range.length > 1 ? Long.parseLong(range[1]) : fileLength - 1;
                    }
                    if (end > fileLength - 1) {
                        end = fileLength - 1;
                    }
                    if(start <= end){
                        ranges.add(new long[] { start, end });
                    }
                }
                long[][] reducedRanges = reduceRanges(ranges.toArray(new long[0][]));
                ByteRange[] byteRanges = new ByteRange[reducedRanges.length];
                for(int i = 0; i < reducedRanges.length; i++) {
                    long[] range = reducedRanges[i];
                    byteRanges[i] = new ByteRange(range[0], range[1], fileLength, contentType, reducedRanges.length > 1);
                }
                this.byteRanges = byteRanges;
                if(this.byteRanges.length == 0){
                    unsatisfiable = true;
                }

				// NEW: if multipart, add the final closing boundary
	            if (this.byteRanges.length > 1) {
		            // RFC: final closing boundary ends with -- and CRLF
		            finalBoundary = ("--" + DEFAULT_SEPARATOR + "--\r\n").getBytes();
	            }
            } catch (Exception e) {
                if(Logger.isDebugEnabled())
                    Logger.debug(e, "byterange error");
                unsatisfiable = true;
            }
        }
        
        private static boolean rangesIntersect(long[] r1, long[] r2) {
	        return r1[0] <= r2[1] && r2[0] <= r1[1];
        }

        private static long[] mergeRanges(long[] r1, long[] r2) {
            return new long[] {Math.min(r1[0], r2[0]),
		            Math.max(r1[1], r2[1])};
        }

        private static long[][] reduceRanges(long[]... chunks) {
            if (chunks.length == 0)
                return new long[0][];
            long[][] sortedChunks = Arrays.copyOf(chunks, chunks.length);
            Arrays.sort(sortedChunks, Comparator.comparingLong(t -> t[0]));
            ArrayList<long[]> result = new ArrayList<>();
            result.add(sortedChunks[0]);
            for (int i = 1; i < sortedChunks.length; i++) {
                long[] c1 = sortedChunks[i];
                long[] r1 = result.getLast();
                if (rangesIntersect(c1, r1)) {
                    result.set(result.size() - 1, mergeRanges(c1, r1));
                } else {
                    result.add(c1);
                }
            }
            return result.toArray(new long[0][]);
        }
        
        private static String makeRangeBodyHeader(String separator, String contentType, long start, long end, long fileLength) {
            return  "--" + separator + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + fileLength + "\r\n" +
                    "\r\n";
        }

	    private class ByteRange {
		    public final long start;
		    public final long end;
		    public final byte[] header;
		    public final byte[] tail; // CRLF after part body in multipart

		    public long length() { return end - start + 1; }
		    public long remaining() { return end - start + 1 - servedRange; }

		    public long computeTotalLength() {
			    return length() + header.length + tail.length;
		    }

		    public int servedHeader = 0;
		    public long servedRange = 0;
		    public int servedTail = 0;

		    public ByteRange(long start, long end, long fileLength, String contentType, boolean includeHeader) {
			    this.start = start;
			    this.end = end;
			    if (includeHeader) {
				    header = makeRangeBodyHeader(DEFAULT_SEPARATOR, contentType, start, end, fileLength).getBytes();
				    tail = "\r\n".getBytes();
			    } else {
				    header = new byte[0];
				    tail = new byte[0];
			    }
		    }

		    public int fill(byte[] into, int offset) throws IOException {
			    if (Logger.isTraceEnabled())
				    Logger.trace("FileService fill at " + offset);
			    int count = 0;
			    // header first
			    for (; offset < into.length && servedHeader < header.length; offset++, servedHeader++, count++) {
				    into[offset] = header[servedHeader];
			    }
			    // range data
			    if (offset < into.length && servedRange < length()) {
				    try {
					    raf.seek(start + servedRange);
					    long maxToRead = remaining() > (into.length - offset) ? (into.length - offset) : remaining();
					    if (maxToRead > Integer.MAX_VALUE) {
						    if (Logger.isDebugEnabled())
							    Logger.debug("FileService: maxToRead >= 2^32 !");
						    maxToRead = Integer.MAX_VALUE;
					    }
					    int read = raf.read(into, offset, (int) maxToRead);
					    if (read < 0) {
						    throw new UnexpectedException("error while reading file : no more to read ! length=" + raf.length() + ", seek=" + (start + servedRange));
					    }
					    count += read;
					    servedRange += read;
					    offset += read;
				    } catch (IOException e) {
					    throw new UnexpectedException(e);
				    }
			    }
			    // tail (CRLF) after part content when multipart
			    for (; offset < into.length && servedTail < tail.length; offset++, servedTail++, count++) {
				    into[offset] = tail[servedTail];
			    }
			    return count;
		    }

		    @Override
		    public String toString() { return "ByteRange(" + start + "," + end + ")"; }
	    }
        
        private static final String DEFAULT_SEPARATOR = "$$$THIS_STRING_SEPARATES$$$";
    }
}
