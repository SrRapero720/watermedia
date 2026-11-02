package org.watermedia.test.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;

/** Loopback HTTP server with one handler, opened per test and closed through try-with-resources. */
public final class LocalHttp implements AutoCloseable {
    private final HttpServer server;

    private LocalHttp(final HttpServer server) {
        this.server = server;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Boots an HTTP server on a random loopback port with the given handler. */
    public static LocalHttp start(final String path, final HttpHandler handler) {
        return start(path, handler, null);
    }

    /** Boots the server on a caller-owned executor, for requests that must run concurrently. */
    public static LocalHttp start(final String path, final HttpHandler handler, final Executor executor) {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext(path, handler);
            server.setExecutor(executor);
            server.start();
            return new LocalHttp(server);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to start local HTTP server", e);
        }
    }

    /** Returns the absolute URI of a path served by this server. */
    public URI uri(final String path) {
        final InetSocketAddress addr = this.server.getAddress();
        return URI.create("http://" + addr.getHostString() + ":" + addr.getPort() + path);
    }

    /** Writes a standard 200 OK response with the given body and headers. */
    public static void respond(final HttpExchange exchange, final String contentType, final byte[] body, final long cacheMaxAgeSec) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (cacheMaxAgeSec > 0) exchange.getResponseHeaders().set("Cache-Control", "max-age=" + cacheMaxAgeSec);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /** Writes {@code body} like a media CDN: a {@code Range} request gets a 206 with its {@code Content-Range}, any other the whole body. */
    public static void respondRange(final HttpExchange exchange, final String contentType, final byte[] body) throws IOException {
        final String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0;
        int end = body.length - 1;
        final boolean partial = range != null && range.startsWith("bytes=");
        if (partial) {
            final String[] bounds = range.substring("bytes=".length()).split("-", 2);
            start = Integer.parseInt(bounds[0].strip());
            if (bounds.length > 1 && !bounds[1].isBlank()) end = Math.min(end, Integer.parseInt(bounds[1].strip()));
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        // THE SERVER DROPS EVERY CONNECTION A CLIENT ASKED TO CLOSE WITHOUT SAYING SO; ANNOUNCING IT STOPS A PERSISTENT
        // CLIENT (FFMPEG) FROM WRITING ITS NEXT REQUEST INTO THAT DEAD SOCKET
        exchange.getResponseHeaders().set("Connection", "close");
        if (start > end) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + body.length);
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
        }
        if (partial) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + body.length);
        exchange.sendResponseHeaders(partial ? 206 : 200, end - start + 1L);
        try (final OutputStream out = exchange.getResponseBody()) { out.write(body, start, end - start + 1); }
    }

    /** Returns an RFC 1123 timestamp {@code seconds} in the future. */
    public static String expiresIn(final long seconds) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().plusSeconds(seconds).atZone(ZoneOffset.UTC));
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
