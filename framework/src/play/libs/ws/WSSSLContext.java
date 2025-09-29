package play.libs.ws;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class WSSSLContext {
	public static SSLContext getSslContext(String keyStore, String keyStorePass,
	                                       String trustStore, String trustStorePass,
	                                       boolean CAValidation) {
		try {
			KeyManager[] keyManagers = null;
			if (keyStore != null && !keyStore.isEmpty()) {
				KeyStore ks = KeyStore.getInstance("JKS");
				try (InputStream in = new FileInputStream(keyStore)) {
					ks.load(in, keyStorePass.toCharArray());
				}
				KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
				kmf.init(ks, keyStorePass.toCharArray());
				keyManagers = kmf.getKeyManagers();
			}

			TrustManager[] trustManagers;
			if (!CAValidation) {
				trustManagers = new TrustManager[]{
						new X509TrustManager() {
							@Override
							public void checkClientTrusted(X509Certificate[] chain, String authType) {
								// trust all
							}

							@Override
							public void checkServerTrusted(X509Certificate[] chain, String authType) {
								// trust all
							}

							@Override
							public X509Certificate[] getAcceptedIssuers() {
								return new X509Certificate[0];
							}
						}
				};
			} else if (trustStore != null && !trustStore.isEmpty()) {
				KeyStore ts = KeyStore.getInstance("JKS");
				try (InputStream in = new FileInputStream(trustStore)) {
					ts.load(in, trustStorePass.toCharArray());
				}
				TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(ts);
				trustManagers = tmf.getTrustManagers();
			} else {
				// Use JVM defaults (system cacerts)
				TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init((KeyStore) null);
				trustManagers = tmf.getTrustManagers();
			}

			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(keyManagers, trustManagers, new SecureRandom());
			return ctx;
		} catch (Exception e) {
			throw new RuntimeException("Error setting SSL context " + e, e);
		}
	}
}
