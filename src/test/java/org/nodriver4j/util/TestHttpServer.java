package org.nodriver4j.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Lightweight embedded HTTP server for integration tests.
 *
 * <p>Serves static files from a given root directory on a random available port.
 * Uses the JDK's built-in {@link com.sun.net.httpserver.HttpServer}.</p>
 */
public class TestHttpServer {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".html", "text/html; charset=UTF-8",
            ".css", "text/css; charset=UTF-8",
            ".js", "application/javascript; charset=UTF-8",
            ".json", "application/json; charset=UTF-8",
            ".png", "image/png",
            ".jpg", "image/jpeg",
            ".svg", "image/svg+xml",
            ".txt", "text/plain; charset=UTF-8"
    );

    private final HttpServer server;
    private final int port;

    private TestHttpServer(HttpServer server, int port) {
        this.server = server;
        this.port = port;
    }

    /**
     * Starts a new HTTP server serving files from {@code resourceDir} on a random available port.
     *
     * <p>The resource directory is resolved relative to the classpath. Files are served
     * from the root context path {@code /}.</p>
     *
     * @param resourceDir classpath-relative directory to serve (e.g. {@code "test-site"})
     * @return a started TestHttpServer instance
     * @throws IOException if the server cannot bind
     */
    public static TestHttpServer start(String resourceDir) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/", exchange -> handleRequest(exchange, resourceDir));
        server.setExecutor(null);
        server.start();

        return new TestHttpServer(server, port);
    }

    /**
     * Starts a new HTTP server serving files from an absolute filesystem path.
     *
     * @param rootPath absolute path to the directory to serve
     * @return a started TestHttpServer instance
     * @throws IOException if the server cannot bind
     */
    public static TestHttpServer startFromPath(Path rootPath) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/", exchange -> handleFileRequest(exchange, rootPath));
        server.setExecutor(null);
        server.start();

        return new TestHttpServer(server, port);
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    public void stop() {
        server.stop(0);
    }

    private static void handleRequest(HttpExchange exchange, String resourceDir) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }

        String resourcePath = resourceDir + path;
        try (InputStream is = TestHttpServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                sendError(exchange, 404, "Not Found: " + path);
                return;
            }

            byte[] body = is.readAllBytes();
            String contentType = contentTypeFor(path);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static void handleFileRequest(HttpExchange exchange, Path rootPath) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }

        // Prevent path traversal
        Path resolved = rootPath.resolve(path.substring(1)).normalize();
        if (!resolved.startsWith(rootPath)) {
            sendError(exchange, 403, "Forbidden");
            return;
        }

        if (!Files.exists(resolved) || Files.isDirectory(resolved)) {
            sendError(exchange, 404, "Not Found: " + path);
            return;
        }

        byte[] body = Files.readAllBytes(resolved);
        String contentType = contentTypeFor(path);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static void sendError(HttpExchange exchange, int code, String message) throws IOException {
        byte[] body = message.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static String contentTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            String ext = path.substring(dot).toLowerCase();
            String type = CONTENT_TYPES.get(ext);
            if (type != null) return type;
        }
        return "application/octet-stream";
    }
}
