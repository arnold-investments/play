package play.server.ssl;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import play.Play;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

/** Netty 4 replacement for SslHttpServerContextFactory */
public final class Netty4SslContexts {

	/**
	 * Build an SslContext using the same configuration semantics as the old SslHttpServerContextFactory.
	 *
	 * Properties supported (same keys as before):
	 * - certificate.key.file (PEM private key)
	 * - certificate.file (PEM cert chain)
	 * - certificate.password (PEM key password) – optional
	 * - trustmanager.algorithm (default JKS) – used only for parity
	 * - keystore.algorithm (default JKS)
	 * - keystore.password (default secret)
	 * - keystore.file (default conf/certificate.jks)
	 *
	 * Additional HTTPS behavior is applied later in the initializer (client auth, ciphers, protocols).
	 */
	public static SslContext buildServerContextFromPlayConfig() throws Exception {
		var p = Play.configuration;

		File keyPem = Play.getFile(p.getProperty("certificate.key.file", "conf/host.key"));
		File certPem = Play.getFile(p.getProperty("certificate.file", "conf/host.cert"));

		if (keyPem.exists() && certPem.exists()) {
			String keyPassword = p.getProperty("certificate.password"); // nullable
			return SslContextBuilder.forServer(certPem, keyPem, keyPassword)
					.build();
		}

		// Fallback: keystore path
		String ksType = p.getProperty("keystore.algorithm", "JKS");
		String ksFile = p.getProperty("keystore.file", "conf/certificate.jks");
		char[] ksPass = p.getProperty("keystore.password", "secret").toCharArray();

		KeyStore ks = KeyStore.getInstance(ksType);
		try (FileInputStream fis = new FileInputStream(Play.getFile(ksFile))) {
			ks.load(fis, ksPass);
		}

		String kmfAlgo = System.getProperty("ssl.KeyManagerFactory.algorithm", "SunX509");
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(kmfAlgo);
		kmf.init(ks, ksPass);

		TrustManagerFactory tmf = TrustManagerFactory.getInstance(kmfAlgo);
		tmf.init(ks);

		return SslContextBuilder.forServer(kmf)
				.trustManager(tmf)
				.build();
	}
}