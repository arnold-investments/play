package play.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import play.Logger;
import play.Play;
import play.Play.Mode;
import play.libs.IO;
import play.server.ssl.HttpsServerInitializer;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Properties;

public class Server {

    public static int httpPort;
    public static int httpsPort;

    public static final String PID_FILE = "server.pid";

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private Channel httpChannel;
	private Channel httpsChannel;

    public Server(String[] args) {
        System.setProperty("file.encoding", "utf-8");
        Properties p = Play.configuration;

        httpPort = Integer.parseInt(getOpt(args, "http.port", p.getProperty("http.port", "-1")));
        httpsPort = Integer.parseInt(getOpt(args, "https.port", p.getProperty("https.port", "-1")));

        if (httpPort == -1 && httpsPort == -1) {
            httpPort = 9000;
        }

        if (httpPort == httpsPort) {
            Logger.error("Could not bind on https and http on the same port " + httpPort);
            Play.fatalServerErrorOccurred();
        }

        InetAddress address = null;
        InetAddress secureAddress = null;
        try {
            if (p.getProperty("http.address") != null) {
                address = InetAddress.getByName(p.getProperty("http.address"));
            } else if (System.getProperties().containsKey("http.address")) {
                address = InetAddress.getByName(System.getProperty("http.address"));
            }

        } catch (Exception e) {
            Logger.error(e, "Could not understand http.address");
            Play.fatalServerErrorOccurred();
        }

	    try {
		    if (p.getProperty("https.address") != null) {
			    secureAddress = InetAddress.getByName(p.getProperty("https.address"));
		    } else if (System.getProperties().containsKey("https.address")) {
			    secureAddress = InetAddress.getByName(System.getProperty("https.address"));
		    }
	    } catch (Exception e) {
		    Logger.error(e, "Could not understand https.address");
		    Play.fatalServerErrorOccurred();
	    }

		start(address, secureAddress);

        if (Play.mode == Mode.DEV || Play.runningInTestMode()) {
           // print this line to STDOUT - not using logger, so auto test runner will not block if logger is misconfigured (see #1222)     
           System.out.println("~ Server is up and running");
        }
    }

	private void start(InetAddress httpAddress, InetAddress httpsAddress) {
		bossGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
		workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

		try {
			if (httpPort != -1) {
				ServerBootstrap http = baseBootstrap()
						.childHandler(new HttpServerInitializer());

				ChannelFuture bind = http.bind(new InetSocketAddress(httpAddress, httpPort)).syncUninterruptibly();
				httpChannel = bind.channel();
				InetSocketAddress local = (InetSocketAddress) httpChannel.localAddress();
				if (Play.mode == Mode.DEV) {
					if (httpAddress == null) {
						Logger.info("Listening for HTTP on port %s (Waiting a first request to start) ...", local.getPort());
					} else {
						Logger.info("Listening for HTTP at %2$s:%1$s (Waiting a first request to start) ...", local.getPort(), local.getAddress());
					}
				} else {
					if (httpAddress == null) {
						Logger.info("Listening for HTTP on port %s ...", local.getPort());
					} else {
						Logger.info("Listening for HTTP at %2$s:%1$s  ...", local.getPort(), local.getAddress());
					}
				}
			}

			if (httpsPort != -1) {
				SslContext sslCtx = buildServerSslContext();

				ServerBootstrap https = baseBootstrap()
						.childHandler(new HttpsServerInitializer(sslCtx));

				ChannelFuture bind = https.bind(new InetSocketAddress(httpsAddress, httpsPort)).syncUninterruptibly();
				httpsChannel = bind.channel();
				InetSocketAddress local = (InetSocketAddress) httpsChannel.localAddress();
				if (Play.mode == Mode.DEV) {
					if (httpsAddress == null) {
						Logger.info("Listening for HTTPS on port %s (Waiting a first request to start) ...", local.getPort());
					} else {
						Logger.info("Listening for HTTPS at %2$s:%1$s (Waiting a first request to start) ...", local.getPort(), local.getAddress());
					}
				} else {
					if (httpsAddress == null) {
						Logger.info("Listening for HTTPS on port %s ...", local.getPort());
					} else {
						Logger.info("Listening for HTTPS at %2$s:%1$s  ...", local.getPort(), local.getAddress());
					}
				}

			}

			if (httpChannel == null && httpsChannel == null) {
				Logger.error("No HTTP/HTTPS channel bound");
				Play.fatalServerErrorOccurred();
			}

		} catch (Throwable t) {
			Logger.error(t, "Failed to start Netty server");
			shutdown();
			Play.fatalServerErrorOccurred();
		}
	}

	private ServerBootstrap baseBootstrap() {
		return new ServerBootstrap()
				.group(bossGroup, workerGroup)
				.channel(NioServerSocketChannel.class)
				.option(ChannelOption.SO_BACKLOG, 1024)
				.childOption(ChannelOption.TCP_NODELAY, true)
				.childOption(ChannelOption.SO_KEEPALIVE, true);
	}

	// Replace this with your real key/cert loading (from Play config). This placeholder just makes it compile.
	private SslContext buildServerSslContext() throws Exception {
		// Example self-signed; in your code, load from configured cert + key files
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
	}

	/** Block until the server is requested to stop. Call this from main. */
	public void awaitTermination() throws InterruptedException {
		if (httpChannel != null && httpsChannel != null) {
			httpsChannel.closeFuture().sync();
			httpChannel.close().syncUninterruptibly();
		} else if (httpChannel != null) {
			httpChannel.closeFuture().sync();
		} else if (httpsChannel != null) {
			httpsChannel.closeFuture().sync();
		}
		shutdown();
	}

	private void shutdown() {
		try { if (httpChannel != null) httpChannel.close().syncUninterruptibly(); } catch (Exception ignore) {}
		try { if (httpsChannel != null) httpsChannel.close().syncUninterruptibly(); } catch (Exception ignore) {}
		try { if (bossGroup != null) bossGroup.shutdownGracefully().syncUninterruptibly(); } catch (Exception ignore) {}
		try { if (workerGroup != null) workerGroup.shutdownGracefully().syncUninterruptibly(); } catch (Exception ignore) {}
	}

    private String getOpt(String[] args, String arg, String defaultValue) {
        String s = "--" + arg + "=";
        for (String a : args) {
            if (a.startsWith(s)) {
                return a.substring(s.length());
            }
        }
        return defaultValue; 
    }

    private static void writePID(File root) {
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        File pidfile = new File(root, PID_FILE);
        if (pidfile.exists()) {
            throw new RuntimeException("The " + PID_FILE + " already exists. Is the server already running?");
        }
        IO.write(pid.getBytes(), pidfile);
    }

    public static void main(String[] args) throws Exception {
        try {
            File root = new File(System.getProperty("application.path", "."));
            if (System.getProperty("precompiled", "false").equals("true")) {
                Play.usePrecompiled = true;
            }
            if (System.getProperty("writepid", "false").equals("true")) {
                writePID(root);
            }

            Play.init(root, System.getProperty("play.id", ""));

            if (System.getProperty("precompile") == null) {
	            Server server = new Server(args);
				server.awaitTermination();
            } else {
                Logger.info("Done.");
            }
        }
        catch (Throwable e) {
            Logger.fatal(e, "Failed to start");
            System.exit(1);
        }
    }
}
