package net.jr.http.webserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a toy web server, just wanted to see what could be done in less than 500 lines of java.
 * 
 * @author JRIALLAND
 * 
 */
public class WebServer implements Runnable {

	protected static final String HTTP_VERSION = "HTTP/1.1";

	public interface ResponseBuilder {

		public void writeStatus(int status, String msg) throws IOException;

		public void writeHeader(String header, String value) throws IOException;

		public void endHeader() throws IOException;

		public OutputStream getOutputStream();
	}

	public interface RequestHandler {

		public void handleRequest(String method, String path, Map<String, String> headers, Map<String, String> parameters, InputStream requestIn, ResponseBuilder responseBuilder) throws IOException;
	}

	private SocketAddress bindAddr;

	private RequestHandler requestHandler;

	public WebServer(SocketAddress bindAddr, RequestHandler requestHandler) {
		this.bindAddr = bindAddr;
		this.requestHandler = requestHandler;
	}

	private Thread thisThread = null;

	public void start() {
		if (thisThread != null) {
			stop();
		}
		thisThread = new Thread(this);
		thisThread.setDaemon(true);
		thisThread.setName(toString());
		thisThread.start();
	}

	public void stop() {
		Thread t = thisThread;
		thisThread = null;
		if (t != null) {
			try {
				t.join(1000);
			} catch (InterruptedException e) {

			} finally {
				t.interrupt();
			}
		}
	}

	public void run() {

		Selector selector;
		ServerSocketChannel ss;

		ByteBuffer buffer = ByteBuffer.allocate(1024);
		try {
			selector = Selector.open();
			ss = ServerSocketChannel.open();
			ss.configureBlocking(false);
			ss.register(selector, SelectionKey.OP_ACCEPT);
			ss.socket().bind(bindAddr);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		thisThread = Thread.currentThread();
		while (thisThread == Thread.currentThread()) {
			try {
				int n = selector.select();
				if (n > 0) {
					Iterator<SelectionKey> itKeys = selector.selectedKeys().iterator();
					while (itKeys.hasNext()) {
						SelectionKey sk = itKeys.next();
						itKeys.remove();
						if (sk.isValid()) {
							if (sk.isAcceptable()) {
								try {
									SocketChannel sc = ss.accept();
									sc.configureBlocking(false);
									sc.register(selector, SelectionKey.OP_READ, new RequestParser());
								} catch (Exception e) {
									e.printStackTrace();
								}
							} else if (sk.isReadable()) {
								SocketChannel sc = (SocketChannel) sk.channel();
								int r = sc.read(buffer);
								RequestParser reader = (RequestParser) sk.attachment();
								if (r > 0) {
									buffer.flip();

									reader.incomingBytes(sc, buffer.array(), buffer.arrayOffset(), r);
								} else if (r == -1) {
									sc.close();
									reader.channelClosed();
								}
							}
						}
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private static Pattern pPath = Pattern.compile("(GET|HEAD|POST|PUT|DELETE|TRACE|CONNECT|OPTIONS|PROPFIND|PROPPATCH|MKCOL|COPY|MOVE|LOCK|UNLOCK) (.+) HTTP/(0\\.9|1\\.0|1\\.1)");

	private static class ChannelOutputStream extends OutputStream {
		private WritableByteChannel channel;

		public ChannelOutputStream(WritableByteChannel channel) {
			this.channel = channel;
		}

		@Override
		public void write(int b) throws IOException {
			channel.write(ByteBuffer.wrap(new byte[] { (byte) b }));
		}

		@Override
		public void write(byte[] b) throws IOException {
			channel.write(ByteBuffer.wrap(b));
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			channel.write(ByteBuffer.wrap(b, off, len));
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}
	};

	private static class ChannelInputStream extends InputStream {

		private ReadableByteChannel channel;

		public ChannelInputStream(ReadableByteChannel channel) {
			this.channel = channel;
		}

		@Override
		public int read() throws IOException {
			int r = 0;
			byte[] b = new byte[1];
			ByteBuffer bf = ByteBuffer.wrap(b);
			do {
				r = channel.read(bf);
			} while (r == 0);
			return r > 0 ? b[0] : -1;
		}

		@Override
		public int read(byte[] b) throws IOException {
			return channel.read(ByteBuffer.wrap(b));
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			return channel.read(ByteBuffer.wrap(b, off, len));
		}

		@Override
		public void close() throws IOException {
			channel.close();
		}

	};

	private static class ResponseBuilderImpl implements ResponseBuilder {

		private boolean inStatus = true;

		private boolean inHeader = false;

		private OutputStream out;

		public ResponseBuilderImpl(OutputStream out) {
			this.out = out;
		}

		public void writeStatus(int status, String msg) throws IOException {
			if (inStatus) {
				out.write((WebServer.HTTP_VERSION + " " + status + " " + msg + "\r\n").getBytes());
				inStatus = false;
				inHeader = true;
			} else
				throw new RuntimeException("Status line has already been sent to the client !");
		}

		public void writeHeader(String header, String value) throws IOException {
			if (inStatus) {
				writeStatus(200, "OK");
			}

			if (inHeader) {
				out.write((header + ":" + value + "\r\n").getBytes());
			} else {
				throw new RuntimeException("Header sections has already been sent to the client !");
			}
		}

		public void endHeader() throws IOException {
			out.write("\r\n".getBytes());
			out.flush();
			inHeader = false;
		}

		@Override
		public OutputStream getOutputStream() {
			return out;
		}
	}

	private class RequestParser {

		private boolean readingHeader = true;

		private String rawHeaders = null;

		public void incomingBytes(SocketChannel sc, byte[] data, int offset, int len) throws IOException {
			if (readingHeader) {
				String str = new String(data, offset, len);
				if (rawHeaders == null) {
					rawHeaders = str;
				} else {
					rawHeaders = rawHeaders + str;
				}
				int headerEnd = rawHeaders.indexOf("\r\n\r\n");
				if (headerEnd > -1) {
					readingHeader = false;
					rawHeaders = rawHeaders.substring(0, headerEnd);
					String[] lines = rawHeaders.split("\r\n");

					OutputStream out = new ChannelOutputStream(sc);
					if (lines.length == 0) {
						sendError(out, 400, "Bad request");
					} else {

						try {
							String method = null;
							String path = null;
							Matcher m = pPath.matcher(lines[0]);

							Map<String, String> parameters = new TreeMap<String, String>();
							if (m.matches()) {
								method = m.group(1);
								path = m.group(2);
								int qmark = path.indexOf('?');
								if (qmark > -1) {
									for (String p : path.substring(qmark + 1).split("&")) {
										String[] parts = p.split("=");
										parameters.put(parts[0], URLDecoder.decode(parts[1].trim(), "utf-8"));
									}
									path = path.substring(0, qmark);
								}
							} else {
								sendError(out, 405, "Unsupported Request");
							}
							Map<String, String> headers = new TreeMap<String, String>();
							for (int i = 1; i < lines.length; i++) {
								int index = lines[i].indexOf(':');
								if (index > 0) {
									headers.put(lines[i].substring(0, index), lines[i].substring(index + 1).trim());
								}
							}

							requestHandler.handleRequest(method, path, headers, parameters, new ChannelInputStream(sc), new ResponseBuilderImpl(out));
							out.flush();
							out.close();
						} catch (Exception e) {
							ByteArrayOutputStream baos = new ByteArrayOutputStream();
							PrintStream pw = new PrintStream(baos);
							e.printStackTrace(pw);
							pw.flush();
							sendError(out, 500, baos.toString());
						}
					}
				}
			}
		}

		public void sendError(OutputStream out, int errCode, String msg) throws IOException {
			int contentLength = msg.getBytes().length;
			String header = HTTP_VERSION + " " + errCode + " ERROR\r\nContent-Type:text/plain;charset=UTF-8;Content-Length:" + contentLength + "\r\n\r\n";
			out.write(header.getBytes());
			out.write(msg.getBytes());
			out.close();
		}

		private void reset() {
			readingHeader = true;
			rawHeaders = null;
		}

		public void channelClosed() {
			reset();
		}
	}

	public static void main(String[] args) throws Exception {
		Matcher matcher = null;
		if (args.length == 0 || !(matcher = Pattern.compile("^(.+):([0-9]+)$").matcher(args[0])).matches()) {
			System.err.println("usage: " + WebServer.class.getName() + " <bind address>:<port>");
			System.exit(1);
		}
		String host = matcher.group(1);
		int port = Integer.parseInt(matcher.group(2));
		System.out.println("serving on " + host + ", port=" + port);
		new WebServer(new InetSocketAddress(host, port), new FileSystemRequestHandler(".")).run();
	}
}
