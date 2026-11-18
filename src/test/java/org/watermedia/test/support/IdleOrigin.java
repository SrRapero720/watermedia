package org.watermedia.test.support;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback HTTP/1.1 origin that keeps its connections alive and cuts one once it sat idle past a limit, waiting for its
 * next request or holding a response body its client stopped reading. A resetting origin sends a TCP RST, as media CDNs
 * such as googlevideo do with the segment download of a paused player; a silent one never sends another byte nor closes
 * the connection, as a home router that dropped the mapping of an idle one. Serves fixed bodies by path with byte ranges, or
 * streamed as YouTube serves an HLS segment: chunked, without a length or ranges, at a paced rate.
 */
public final class IdleOrigin implements AutoCloseable {
    private static final int CHUNK_BYTES = 16 * 1_024;
    private static final int SEND_BUFFER_BYTES = 32 * 1_024; // A SMALL SERVER BUFFER BLOCKS THE WRITE SOON AFTER THE CLIENT STOPS READING
    private static final int HEAD_LIMIT_BYTES = 64 * 1_024;

    private final ServerSocket server;
    private final long idleNs;
    private final boolean silent;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, Body> bodies = new ConcurrentHashMap<>();
    private final Map<String, Integer> paths = new ConcurrentHashMap<>(); // REQUESTS PER PATH
    private final Map<String, Integer> stalls = new ConcurrentHashMap<>(); // DOWNLOADS CUT MID-BODY PER PATH
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet(); // SERVED, NOT CUT
    private final Set<Connection> silenced = ConcurrentHashMap.newKeySet();    // CUT BY A SILENT ORIGIN, STILL OPEN
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicInteger cuts = new AtomicInteger();
    private final AtomicInteger strays = new AtomicInteger();
    private volatile boolean closed;

    private IdleOrigin(final long idleMs, final boolean silent) throws IOException {
        this.idleNs = TimeUnit.MILLISECONDS.toNanos(idleMs);
        this.silent = silent;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.workers.execute(this::accept);
        this.workers.execute(this::watch);
    }

    /** Boots an origin on a random loopback port that resets every connection idle for longer than {@code idleMs}. */
    public static IdleOrigin resetting(final long idleMs) { return start(idleMs, false); }

    /** Boots an origin on a random loopback port that goes silent on every connection idle for longer than {@code idleMs}. */
    public static IdleOrigin silent(final long idleMs) { return start(idleMs, true); }

    /** Serves {@code body} as {@code type} under {@code path} (query strings ignored). */
    public void serve(final String path, final String type, final byte[] body) { this.bodies.put(path, new Body(type, body, false, 0L)); }

    /** Serves {@code body} as YouTube serves an HLS segment: 200 chunked, no length nor ranges whatever the request asks, at {@code bytesPerSec} (0 is full speed). */
    public void stream(final String path, final String type, final byte[] body, final long bytesPerSec) {
        this.bodies.put(path, new Body(type, body, true, bytesPerSec));
    }

    /** Returns the absolute URI of a path served by this origin. */
    public URI uri(final String path) { return URI.create("http://" + this.server.getInetAddress().getHostAddress() + ":" + this.server.getLocalPort() + path); }

    /** Returns the number of requests the origin answered or refused for {@code path}. */
    public int requests(final String path) { return this.paths.getOrDefault(path, 0); }
    /** Returns the number of connections the origin cut for idling. */
    public int cuts() { return this.cuts.get(); }
    /** Returns the number of downloads of {@code path} the origin cut in the middle of their body. */
    public int cuts(final String path) { return this.stalls.getOrDefault(path, 0); }
    /** Returns the number of connections the origin still serves, neither closed nor cut. */
    public int connections() { return this.connections.size(); }
    /** Returns the number of requests that arrived after a stray empty line. */
    public int strays() { return this.strays.get(); }

    @Override
    public void close() {
        this.closed = true;
        this.closing.countDown();
        try { this.server.close(); }
        catch (final IOException ignored) {}
        for (final Set<Connection> open: List.of(this.connections, this.silenced)) {
            for (final Connection connection: open) {
                try { connection.socket.close(); }
                catch (final IOException ignored) {}
            }
        }
        this.workers.shutdownNow();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private static IdleOrigin start(final long idleMs, final boolean silent) {
        try {
            return new IdleOrigin(idleMs, silent);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to start the idle origin", e);
        }
    }

    private void accept() {
        while (!this.closed) {
            try {
                final Connection connection = new Connection(this.server.accept());
                this.connections.add(connection);
                this.workers.execute(() -> this.handle(connection));
            } catch (final IOException e) {
                return; // THE SERVER SOCKET CLOSED
            }
        }
    }

    // CUTS EVERY CONNECTION WHOSE BODY WRITE MADE NO PROGRESS FOR THE IDLE LIMIT; A KEPT-ALIVE ONE TIMES OUT ON ITS OWN READ
    private void watch() {
        while (!this.closed) {
            for (final Connection connection: this.connections)
                if (connection.writing && System.nanoTime() - connection.progressNs > this.idleNs) this.cut(connection, connection.path);
            try { Thread.sleep(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(this.idleNs) / 10)); }
            catch (final InterruptedException e) { return; }
        }
    }

    // CUTS A CONNECTION IN THE MIDDLE OF THE BODY OF body, OR IDLE BETWEEN REQUESTS WHEN body IS NULL. A RESET IS A ZERO LINGER
    // CLOSE (RST INSTEAD OF FIN); A SILENCED CONNECTION STAYS OPEN AND ITS HANDLER PARKS AT ITS NEXT STEP
    private void cut(final Connection connection, final String body) {
        if (!this.connections.remove(connection)) return;
        this.cuts.incrementAndGet();
        if (body != null) this.stalls.merge(body, 1, Integer::sum);
        if (this.silent) {
            this.silenced.add(connection);
            connection.silenced = true;
            return;
        }
        try {
            connection.socket.setSoLinger(true, 0);
            connection.socket.close();
        } catch (final IOException ignored) {}
    }

    // A SILENCED HANDLER NEITHER READS, WRITES NOR CLOSES UNTIL THE ORIGIN CLOSES
    private void park() {
        try { this.closing.await(); }
        catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ANSWERS THE REQUESTS OF ONE CONNECTION UNTIL ITS CLIENT CLOSES IT, ASKS FOR Connection: close OR IT IDLES
    private void handle(final Connection connection) {
        final Socket socket = connection.socket;
        try {
            socket.setSoTimeout((int) TimeUnit.NANOSECONDS.toMillis(this.idleNs));
            socket.setSendBufferSize(SEND_BUFFER_BYTES);
            socket.setTcpNoDelay(true);
            final InputStream in = new BufferedInputStream(socket.getInputStream());
            final OutputStream out = socket.getOutputStream();
            while (!this.closed) {
                final String head;
                try {
                    head = this.head(in);
                } catch (final SocketTimeoutException idle) {
                    this.cut(connection, null);
                    if (connection.silenced) this.park();
                    return;
                }
                if (head == null) return;

                // REQUEST LINE AND THE TWO HEADERS THE ORIGIN HONOURS
                final String[] lines = head.split("\r\n");
                final String[] request = lines[0].split(" ");
                final String target = request.length > 1 ? request[1] : "/";
                final String path = target.indexOf('?') < 0 ? target : target.substring(0, target.indexOf('?'));
                String range = null;
                boolean close = false;
                for (int i = 1; i < lines.length; i++) {
                    final String line = lines[i].toLowerCase(Locale.ROOT);
                    if (line.startsWith("range:")) range = line.substring("range:".length()).strip();
                    if (line.startsWith("connection:") && line.contains("close")) close = true;
                }

                this.paths.merge(path, 1, Integer::sum);
                final Body body = this.bodies.get(path);
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    if (close) return;
                    continue;
                }
                // A STREAMED BODY IGNORES RANGE AND NAMES NEITHER ITS LENGTH NOR RANGES, SO FFMPEG CANNOT RESUME IT AT AN OFFSET
                final boolean partial = range != null && !body.streamed;
                final int total = body.bytes.length;
                int start = 0;
                int end = total - 1;
                if (partial && range.startsWith("bytes=")) {
                    final String[] bounds = range.substring("bytes=".length()).split("-", 2);
                    start = Integer.parseInt(bounds[0].strip());
                    if (bounds.length > 1 && !bounds[1].isBlank()) end = Math.min(end, Integer.parseInt(bounds[1].strip()));
                }
                if (start > end && !body.streamed) {
                    out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    if (close) return;
                    continue;
                }
                final StringBuilder response = new StringBuilder(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                        .append("Content-Type: ").append(body.type).append("\r\n");
                if (body.streamed) response.append("Transfer-Encoding: chunked\r\n");
                else response.append("Content-Length: ").append(end - start + 1).append("\r\nAccept-Ranges: bytes\r\n");
                if (partial) response.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
                if (close) response.append("Connection: close\r\n");
                out.write(response.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));

                // THE BODY GOES IN CHUNKS, EACH ONE PROGRESS: A WRITE THE CLIENT'S BACKPRESSURE BLOCKS FOR THE IDLE LIMIT IS CUT
                connection.progressNs = System.nanoTime();
                connection.path = path;
                connection.writing = true;
                final long began = System.nanoTime();
                for (int at = start; at <= end; at += CHUNK_BYTES) {
                    final int length = Math.min(CHUNK_BYTES, end - at + 1);
                    if (body.streamed) out.write((Integer.toHexString(length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body.bytes, at, length);
                    if (body.streamed) out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    connection.progressNs = System.nanoTime();
                    if (connection.silenced) {
                        this.park();
                        return;
                    }
                    // A PACED BODY WAITS FOR ITS SCHEDULE: THE WAIT IS THE ORIGIN'S OWN, SO IT ENDS AS PROGRESS
                    if (body.bytesPerSec > 0) {
                        TimeUnit.NANOSECONDS.sleep(began + (at - start + length) * 1_000_000_000L / body.bytesPerSec - System.nanoTime());
                        connection.progressNs = System.nanoTime();
                    }
                }
                if (body.streamed) out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                connection.writing = false;
                if (close) return;
            }
        } catch (final IOException | RuntimeException e) {
            // A RESET, A CLIENT THAT LEFT OR A MALFORMED REQUEST END THE CONNECTION
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt(); // THE ORIGIN CLOSED DURING A PACED WAIT
        } finally {
            this.connections.remove(connection);
            this.silenced.remove(connection);
            try { socket.close(); }
            catch (final IOException ignored) {}
        }
    }

    // THE REQUEST HEAD UP TO ITS BLANK LINE, OR NULL WHEN THE CLIENT CLOSED BEFORE SENDING ONE. EMPTY LINES BEFORE THE REQUEST
    // LINE ARE SKIPPED (RFC 9112 2.2) AND COUNTED: A CLIENT THAT ENDS ITS HEADERS TWICE LEAVES ONE AHEAD OF ITS NEXT REQUEST
    private String head(final InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        boolean stray = false;
        int matched = 0;
        while (matched < 4) {
            final int next = in.read();
            if (next < 0) {
                if (head.size() == 0) return null;
                throw new IOException("Request head cut short");
            }
            if (head.size() == 0 && (next == '\r' || next == '\n')) {
                stray = true;
                continue;
            }
            head.write(next);
            if (head.size() > HEAD_LIMIT_BYTES) throw new IOException("Request head too large");
            matched = next == (matched % 2 == 0 ? '\r' : '\n') ? matched + 1 : next == '\r' ? 1 : 0;
        }
        if (stray) this.strays.incrementAndGet();
        return head.toString(StandardCharsets.US_ASCII);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // A STREAMED BODY GOES CHUNKED WITHOUT RANGES; bytesPerSec 0 SENDS AT FULL SPEED
    private record Body(String type, byte[] bytes, boolean streamed, long bytesPerSec) {}

    // ONE ACCEPTED SOCKET, THE PATH AND PROGRESS OF THE BODY IT IS WRITING (READ BY THE WATCHDOG) AND WHETHER A SILENT ORIGIN CUT IT
    private static final class Connection {
        private final Socket socket;
        private volatile String path;
        private volatile boolean writing;
        private volatile long progressNs;
        private volatile boolean silenced;

        private Connection(final Socket socket) { this.socket = socket; }
    }
}
