package net.jr.http.webserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import net.jr.http.webserver.WebServer.RequestHandler;
import net.jr.http.webserver.WebServer.ResponseBuilder;

/**
 * Serves the content of a fs directory
 * 
 * @author J Rialland
 * 
 */
public class FileSystemRequestHandler implements RequestHandler {

	private Path root;

	public FileSystemRequestHandler(String root) {
		this.root = Paths.get(root);
	}

	@Override
	public void handleRequest(String method, String path, Map<String, String> headers, Map<String, String> parameters, InputStream requestIn, ResponseBuilder responseBuilder) throws IOException {
		path = path.replaceAll("^/", "");
		Path p = root.resolve(path);
		if (!Files.exists(p)) {
			responseBuilder.writeStatus(404, "Not Found");
			responseBuilder.writeHeader("Content-Type", "text/plain;charset=utf-8");
			responseBuilder.endHeader();
			PrintWriter pw = new PrintWriter(responseBuilder.getOutputStream());
			pw.write(String.format("Error 404 : No Such file or Directory. (%s)", path));
			pw.flush();
			return;

		} else if (Files.isRegularFile(p)) {
			responseBuilder.writeStatus(200, "OK");
			String contentType = Files.probeContentType(p);
			contentType = contentType == null ? "application/binary" : contentType;
			responseBuilder.writeHeader("Content-Type", contentType);
			responseBuilder.writeHeader("Content-Length", Long.toString(Files.size(p)));
			responseBuilder.endHeader();
			Files.copy(p, responseBuilder.getOutputStream());
		} else if (Files.isDirectory(p)) {
			responseBuilder.writeStatus(200, "OK");
			responseBuilder.writeHeader("Content-Type", "text/html;charset=utf-8");
			responseBuilder.endHeader();
			PrintWriter pw = new PrintWriter(responseBuilder.getOutputStream());
			pw.println("<!doctype html>");
			pw.println("<html><head><title>Index of /" + path + "</title></head>");
			pw.println("<body><ul>");
			if (!p.equals(root)) {
				pw.println("<li><a href=\"..\">..</a></li>");
			}
			for (Path child : Files.newDirectoryStream(p)) {
				String name = child.getFileName().toString();
				pw.print("<li><a href=\"");
				if (!path.isEmpty()) {
					pw.print("/");
				}
				pw.print(path + "/" + name);
				pw.println("\">" + name + "</a></li>");
			}
			pw.println("</ul></body>");
			pw.println("</html>");
			pw.flush();
		}
	}
}
