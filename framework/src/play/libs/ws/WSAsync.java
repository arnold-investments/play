package play.libs.ws;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.commons.lang3.NotImplementedException;
import org.asynchttpclient.AsyncCompletionHandler;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.BoundRequestBuilder;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClientConfig;
import org.asynchttpclient.Realm;
import org.asynchttpclient.Realm.AuthScheme;
import org.asynchttpclient.Response;
import org.asynchttpclient.proxy.ProxyServer;
import org.asynchttpclient.request.body.multipart.ByteArrayPart;
import org.asynchttpclient.request.body.multipart.FilePart;
import org.asynchttpclient.request.body.multipart.Part;
import play.Logger;
import play.Play;
import play.exceptions.UnexpectedException;
import play.libs.F.Promise;
import play.libs.MimeTypes;
import play.libs.WS.HttpResponse;
import play.libs.WS.WSImpl;
import play.libs.WS.WSRequest;
import play.mvc.Http.Header;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Simple HTTP client to make webservices requests.
 * 
 * <p>
 * Get latest BBC World news as a RSS content
 * 
 * <pre>
 * HttpResponse response = WS.url("http://newsrss.bbc.co.uk/rss/newsonline_world_edition/front_page/rss.xml").get();
 * Document xmldoc = response.getXml();
 * // the real pain begins here...
 * </pre>
 * <p>
 * 
 * Search what Yahoo! thinks of google (starting from the 30th result).
 * 
 * <pre>
 * HttpResponse response = WS.url("http://search.yahoo.com/search?p=<em>%s</em>&amp;pstart=1&amp;b=<em>%s</em>", "Google killed me", "30").get();
 * if (response.getStatus() == 200) {
 *     html = response.getString();
 * }
 * </pre>
 */
public class WSAsync implements WSImpl {

    private AsyncHttpClient httpClient;
    private static SSLContext sslCTX = null;

	public WSAsync() {
		String proxyHost = Play.configuration.getProperty("http.proxyHost", System.getProperty("http.proxyHost"));
		String proxyPort = Play.configuration.getProperty("http.proxyPort", System.getProperty("http.proxyPort"));
		String proxyUser = Play.configuration.getProperty("http.proxyUser", System.getProperty("http.proxyUser"));
		String proxyPassword = Play.configuration.getProperty("http.proxyPassword", System.getProperty("http.proxyPassword"));
		String nonProxyHosts = Play.configuration.getProperty("http.nonProxyHosts", System.getProperty("http.nonProxyHosts"));
		String userAgent = Play.configuration.getProperty("http.userAgent");

		// Optional key/trust stores (JKS paths)
		String keyStore = Play.configuration.getProperty("ssl.keyStore", System.getProperty("javax.net.ssl.keyStore"));
		String keyStorePass = Play.configuration.getProperty("ssl.keyStorePassword", System.getProperty("javax.net.ssl.keyStorePassword"));
		String trustStore = Play.configuration.getProperty("ssl.trustStore", System.getProperty("javax.net.ssl.trustStore"));
		String trustStorePass = Play.configuration.getProperty("ssl.trustStorePassword", System.getProperty("javax.net.ssl.trustStorePassword"));

		boolean caValidation = Boolean.parseBoolean(Play.configuration.getProperty("ssl.cavalidation", "true"));
		boolean trustAll = Boolean.parseBoolean(Play.configuration.getProperty("ssl.trustAll", "false"));

		// Build client config (ms)
		DefaultAsyncHttpClientConfig.Builder conf = new DefaultAsyncHttpClientConfig.Builder()
				.setDisableUrlEncodingForBoundRequests(true)
				.setConnectTimeout(Duration.ofMillis(10000))
				.setHandshakeTimeout(10000);

		// ---- SSL context (Netty) ----
		try {
			SslContextBuilder scb = SslContextBuilder.forClient()
					.protocols("TLSv1.3", "TLSv1.2");

			if (trustAll && !caValidation) {
				scb = scb.trustManager(InsecureTrustManagerFactory.INSTANCE);
			} else if (trustStore != null && !trustStore.isEmpty()) {
				// Load explicit trust store
				java.security.KeyStore ts = java.security.KeyStore.getInstance("JKS");
				try (java.io.InputStream in = new java.io.FileInputStream(trustStore)) {
					ts.load(in, trustStorePass != null ? trustStorePass.toCharArray() : null);
				}
				TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(ts);
				scb = scb.trustManager(tmf);
			} // else: JVM default trust store

			// Optional client certs
			if (keyStore != null && !keyStore.isEmpty()) {
				java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
				try (java.io.InputStream in = new java.io.FileInputStream(keyStore)) {
					ks.load(in, keyStorePass != null ? keyStorePass.toCharArray() : null);
				}
				javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
				kmf.init(ks, keyStorePass != null ? keyStorePass.toCharArray() : new char[0]);
				scb = scb.keyManager(kmf);

				Logger.info("Keystore configured, loading from '%s', CA validation enabled: %s", keyStore, String.valueOf(caValidation));
			}

			SslContext nettySsl = scb.build();
			conf.setSslContext(nettySsl);
		} catch (Exception e) {
			throw new RuntimeException("Failed to configure SSL", e);
		}

		// ---- Proxy (optional) ----
		if (proxyHost != null && !proxyHost.isEmpty()) {
			int proxyPortInt;
			try {
				proxyPortInt = Integer.parseInt(proxyPort);
			} catch (Exception e) {
				Logger.error(e, "Cannot parse the proxy port '%s' (property http.proxyPort)", proxyPort);
				throw new IllegalStateException("WS proxy is misconfigured -- check the logs for details");
			}

			ProxyServer.Builder proxyBuilder = new ProxyServer.Builder(proxyHost, proxyPortInt);

			if (proxyUser != null && !proxyUser.isEmpty()) {
				Realm.Builder rb = new Realm.Builder(proxyUser, proxyPassword != null ? proxyPassword : "");
				rb.setScheme(Realm.AuthScheme.BASIC).setUsePreemptiveAuth(true);
				proxyBuilder.setRealm(rb.build());
			}

			if (nonProxyHosts != null && !nonProxyHosts.isEmpty()) {
				for (String pattern : nonProxyHosts.split("\\|")) {
					if (!pattern.isEmpty()) {
						proxyBuilder.setNonProxyHost(pattern);
					}
				}
			}

			conf.setProxyServer(proxyBuilder.build());
		}

		if (userAgent != null && !userAgent.isEmpty()) {
			conf.setUserAgent(userAgent);
		}

		// Build the client
		httpClient = new DefaultAsyncHttpClient(conf.build());
	}

	@Override
    public void stop() {
        Logger.trace("Releasing http client connections...");
		try {
			httpClient.close();
		} catch (IOException e) {
			throw new UnexpectedException(e);
		}
    }

    @Override
    public WSRequest newRequest(String url, Charset encoding) {
        return new WSAsyncRequest(url, encoding);
    }

    public class WSAsyncRequest extends WSRequest {

        protected String type = null;
        private String generatedContentType = null;

        protected WSAsyncRequest(String url, Charset encoding) {
            super(url, encoding);
        }

        /**
         * Returns the URL but removed the queryString-part of it The QueryString-info is later added with
         * addQueryString()
         * 
         * @return The URL without the queryString-part
         */
        protected String getUrlWithoutQueryString() {
            int i = url.indexOf('?');
            if (i > 0) {
                return url.substring(0, i);
            } else {
                return url;
            }
        }

        /**
         * Adds the queryString-part of the url to the BoundRequestBuilder
         * 
         * @param requestBuilder
         *            : The request buider to add the queryString-part
         */
        protected void addQueryString(BoundRequestBuilder requestBuilder) {

            // AsyncHttpClient is by default encoding everything in utf-8 so for
            // us to be able to use
            // different encoding we have configured AHC to use raw urls. When
            // using raw urls,
            // AHC does not encode url and QueryParam with utf-8 - but there is
            // another problem:
            // If we send raw (none-encoded) url (with queryString) to AHC, it
            // does not url-encode it,
            // but transform all illegal chars to '?'.
            // If we pre-encoded the url with QueryString before sending it to
            // AHC, ahc will decode it, and then
            // later break it with '?'.

            // This method basically does the same as
            // RequestBuilderBase.buildUrl() except from destroying the
            // pre-encoding

            // does url contain query_string?
            int i = url.indexOf('?');
            if (i > 0) {

                // extract query-string-part
                String queryPart = url.substring(i + 1);

                // parse queryPart - and decode it... (it is going to be
                // re-encoded later)
                for (String param : queryPart.split("&")) {

                    i = param.indexOf('=');
                    String name;
                    String value = null;
                    if (i <= 0) {
                        // only a flag
                        name = URLDecoder.decode(param, encoding);
                    } else {
                        name = URLDecoder.decode(param.substring(0, i), encoding);
                        value = URLDecoder.decode(param.substring(i + 1), encoding);
                    }

                    if (value == null) {
                        requestBuilder.addQueryParam(URLEncoder.encode(name, encoding), null);
                    } else {
                        requestBuilder.addQueryParam(URLEncoder.encode(name, encoding), URLEncoder.encode(value, encoding));
                    }

                }

            }
        }

        private BoundRequestBuilder prepareAll(BoundRequestBuilder requestBuilder) {
            checkFileBody(requestBuilder);
            addQueryString(requestBuilder);
            addGeneratedContentType(requestBuilder);
            return requestBuilder;
        }

        public BoundRequestBuilder prepareGet() {
            return prepareAll(httpClient.prepareGet(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder prepareOptions() {
            return prepareAll(httpClient.prepareOptions(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder prepareHead() {
            return prepareAll(httpClient.prepareHead(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder preparePatch() {
            return prepareAll(httpClient.preparePatch(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder preparePost() {
            return prepareAll(httpClient.preparePost(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder preparePut() {
            return prepareAll(httpClient.preparePut(getUrlWithoutQueryString()));
        }

        public BoundRequestBuilder prepareDelete() {
            return prepareAll(httpClient.prepareDelete(getUrlWithoutQueryString()));
        }

        /** Execute a GET request synchronously. */
        @Override
        public HttpResponse get() {
            this.type = "GET";
            try {
                return new HttpAsyncResponse(prepare(prepareGet()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a GET request asynchronously. */
        @Override
        public Promise<HttpResponse> getAsync() {
            this.type = "GET";
            return execute(prepareGet());
        }

        /** Execute a PATCH request. */
        @Override
        public HttpResponse patch() {
            this.type = "PATCH";
            try {
                return new HttpAsyncResponse(prepare(preparePatch()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a PATCH request asynchronously. */
        @Override
        public Promise<HttpResponse> patchAsync() {
            this.type = "PATCH";
            return execute(preparePatch());
        }

        /** Execute a POST request. */
        @Override
        public HttpResponse post() {
            this.type = "POST";
            try {
                return new HttpAsyncResponse(prepare(preparePost()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a POST request asynchronously. */
        @Override
        public Promise<HttpResponse> postAsync() {
            this.type = "POST";
            return execute(preparePost());
        }

        /** Execute a PUT request. */
        @Override
        public HttpResponse put() {
            this.type = "PUT";
            try {
                return new HttpAsyncResponse(prepare(preparePut()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a PUT request asynchronously. */
        @Override
        public Promise<HttpResponse> putAsync() {
            this.type = "PUT";
            return execute(preparePut());
        }

        /** Execute a DELETE request. */
        @Override
        public HttpResponse delete() {
            this.type = "DELETE";
            try {
                return new HttpAsyncResponse(prepare(prepareDelete()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a DELETE request asynchronously. */
        @Override
        public Promise<HttpResponse> deleteAsync() {
            this.type = "DELETE";
            return execute(prepareDelete());
        }

        /** Execute a OPTIONS request. */
        @Override
        public HttpResponse options() {
            this.type = "OPTIONS";
            try {
                return new HttpAsyncResponse(prepare(prepareOptions()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a OPTIONS request asynchronously. */
        @Override
        public Promise<HttpResponse> optionsAsync() {
            this.type = "OPTIONS";
            return execute(prepareOptions());
        }

        /** Execute a HEAD request. */
        @Override
        public HttpResponse head() {
            this.type = "HEAD";
            try {
                return new HttpAsyncResponse(prepare(prepareHead()).execute().get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /** Execute a HEAD request asynchronously. */
        @Override
        public Promise<HttpResponse> headAsync() {
            this.type = "HEAD";
            return execute(prepareHead());
        }

        /** Execute a TRACE request. */
        @Override
        public HttpResponse trace() {
            this.type = "TRACE";
            throw new NotImplementedException();
        }

        /** Execute a TRACE request asynchronously. */
        @Override
        public Promise<HttpResponse> traceAsync() {
            this.type = "TRACE";
            throw new NotImplementedException();
        }

        private BoundRequestBuilder prepare(BoundRequestBuilder builder) {
            if (this.username != null && this.password != null && this.scheme != null) {
                AuthScheme authScheme;
                switch (this.scheme) {
                case DIGEST:
                    authScheme = AuthScheme.DIGEST;
                    break;
                case NTLM:
                    authScheme = AuthScheme.NTLM;
                    break;
                case KERBEROS:
                    authScheme = AuthScheme.KERBEROS;
                    break;
                case SPNEGO:
                    authScheme = AuthScheme.SPNEGO;
                    break;
                case BASIC:
                    authScheme = AuthScheme.BASIC;
                    break;
                default:
                    throw new RuntimeException("Scheme " + this.scheme + " not supported by the UrlFetch WS backend.");
                }
                builder.setRealm((new Realm.Builder(this.username, this.password))
	                .setScheme(authScheme)
                    .setUsePreemptiveAuth(true)
	                .build());
            }
            for (String key : this.headers.keySet()) {
                builder.addHeader(key, headers.get(key));
            }
            builder.setFollowRedirect(this.followRedirects);
            builder.setRequestTimeout(Duration.ofMillis(this.timeout * 1000));
            if (this.virtualHost != null) {
                builder.setVirtualHost(this.virtualHost);
            }
            return builder;
        }

        private Promise<HttpResponse> execute(BoundRequestBuilder builder) {
            try {
                final Promise<HttpResponse> smartFuture = new Promise<>();
                prepare(builder).execute(new AsyncCompletionHandler<HttpResponse>() {
                    @Override
                    public HttpResponse onCompleted(Response response) throws Exception {
                        HttpResponse httpResponse = new HttpAsyncResponse(response);
                        smartFuture.invoke(httpResponse);
                        return httpResponse;
                    }

                    @Override
                    public void onThrowable(Throwable t) {
                        // An error happened - must "forward" the exception to
                        // the one waiting for the result
                        smartFuture.invokeWithException(t);
                    }
                });

                return smartFuture;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void checkFileBody(BoundRequestBuilder builder) {
            setResolvedContentType(null);
            if (this.fileParams != null) {
                // could be optimized, we know the size of this array.
                for (int i = 0; i < this.fileParams.length; i++) {
                    builder.addBodyPart(new FilePart(this.fileParams[i].paramName, this.fileParams[i].file,
                            MimeTypes.getMimeType(this.fileParams[i].file.getName()), encoding));
                }
                if (this.parameters != null) {
                    // AHC only supports ascii chars in keys in multipart
                    for (String key : this.parameters.keySet()) {
                        Object value = this.parameters.get(key);
                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                Part part = new ByteArrayPart(key, v.toString().getBytes(encoding), "text/plain",
                                        encoding, null);
                                builder.addBodyPart(part);
                            }
                        } else {
                            Part part = new ByteArrayPart(key, value.toString().getBytes(encoding), "text/plain",
                                    encoding, null);
                            builder.addBodyPart(part);
                        }
                    }

                }

                // Don't have to set content-type: AHC will automatically choose
                // multipart

                return;
            }
            if (this.parameters != null && !this.parameters.isEmpty()) {
                boolean isPostPut = "POST".equals(this.type) || ("PUT".equals(this.type));

                if (isPostPut) {
                    // Since AHC is hard-coded to encode to use UTF-8, we must
                    // build
                    // the content ourself..
                    StringBuilder sb = new StringBuilder();

                    for (String key : this.parameters.keySet()) {
                        Object value = this.parameters.get(key);
                        if (value == null)
                            continue;

                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                if (sb.length() > 0) {
                                    sb.append('&');
                                }
                                sb.append(encode(key));
                                sb.append('=');
                                sb.append(encode(v.toString()));
                            }
                        } else {
                            // Since AHC is hard-coded to encode using UTF-8, we
                            // must build
                            // the content ourself..
                            if (sb.length() > 0) {
                                sb.append('&');
                            }
                            sb.append(encode(key));
                            sb.append('=');
                            sb.append(encode(value.toString()));
                        }
                    }

                    byte[] bodyBytes = sb.toString().getBytes(this.encoding);
                    builder.setBody(bodyBytes);

                    setResolvedContentType("application/x-www-form-urlencoded; charset=" + encoding);

                } else {
                    for (String key : this.parameters.keySet()) {
                        Object value = this.parameters.get(key);
                        if (value == null)
                            continue;
                        if (value instanceof Collection<?> || value.getClass().isArray()) {
                            Collection<?> values = value.getClass().isArray() ? Arrays.asList((Object[]) value) : (Collection<?>) value;
                            for (Object v : values) {
                                // must encode it since AHC uses raw urls
                                builder.addQueryParam(encode(key), encode(v.toString()));
                            }
                        } else {
                            // must encode it since AHC uses raw urls
                            builder.addQueryParam(encode(key), encode(value.toString()));
                        }
                    }
                    setResolvedContentType("text/html; charset=" + encoding);
                }
            }
            if (this.body != null) {
                if (this.parameters != null && !this.parameters.isEmpty()) {
                    throw new RuntimeException("POST or PUT method with parameters AND body are not supported.");
                }
                if (this.body instanceof InputStream) {
                    builder.setBody((InputStream) this.body);
                } else {
                    byte[] bodyBytes = this.body.toString().getBytes(this.encoding);
                    builder.setBody(bodyBytes);
                }
                setResolvedContentType("text/html; charset=" + encoding);
            }

            if (this.mimeType != null) {
                // User has specified mimeType
                this.headers.put("Content-Type", this.mimeType);
            }
        }

        /**
         * Sets the resolved Content-type - This is added as Content-type-header to AHC if ser has not specified
         * Content-type or mimeType manually (Cannot add it directly to this.header since this cause problem when
         * Request-object is used multiple times with first GET, then POST)
         */
        private void setResolvedContentType(String contentType) {
            generatedContentType = contentType;
        }

        /**
         * If generatedContentType is present AND if Content-type header is not already present, add
         * generatedContentType as Content-Type to headers in requestBuilder
         */
        private void addGeneratedContentType(BoundRequestBuilder requestBuilder) {
            if (!headers.containsKey("Content-Type") && generatedContentType != null) {
                requestBuilder.addHeader("Content-Type", generatedContentType);
            }
        }

    }

    /**
     * An HTTP response wrapper
     */
    public static class HttpAsyncResponse extends HttpResponse {

        private Response response;

        /**
         * You shouldn't have to create an HttpResponse yourself
         * 
         * @param response
         *            The given response
         */
        public HttpAsyncResponse(Response response) {
            this.response = response;
        }

        /**
         * The HTTP status code
         * 
         * @return the status code of the http response
         */
        @Override
        public Integer getStatus() {
            return this.response.getStatusCode();
        }

        /**
         * the HTTP status text
         * 
         * @return the status text of the http response
         */
        @Override
        public String getStatusText() {
            return this.response.getStatusText();
        }

        @Override
        public String getHeader(String key) {
            return response.getHeader(key);
        }

        @Override
        public List<Header> getHeaders() {
	        HttpHeaders hdrs = response.getHeaders();
            List<Header> result = new ArrayList<>();
            for (String key : hdrs.names()) {
                result.add(new Header(key, hdrs.get(key)));
            }
            return result;
        }

        @Override
        public String getString() {
            try {
                return response.getResponseBody(getEncoding());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String getString(Charset encoding) {
            try {
                return response.getResponseBody(encoding);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * get the response as a stream
         * 
         * @return an inputstream
         */
        @Override
        public InputStream getStream() {
            try {
                return response.getResponseBodyAsStream();
            } catch (IllegalStateException e) {
                return new ByteArrayInputStream(new byte[] {}); // Workaround
                                                                // AHC's bug on
                                                                // empty
                                                                // responses
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }
}
