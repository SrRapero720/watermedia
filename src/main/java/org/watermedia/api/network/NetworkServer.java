package org.watermedia.api.network;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.network.NetworkAPI.*;

/** Local HTTP file server: token-gated uploads into a bounded storage folder and range-capable downloads. */
public final class NetworkServer {
    private static final Marker IT = MarkerManager.getMarker(NetworkServer.class.getSimpleName());
    // LOWERCASE ONLY, SO AN ID NAMES THE SAME FILE ON CASE-INSENSITIVE AND CASE-SENSITIVE FILE SYSTEMS ALIKE
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 8;
    private static final long INVALID_RANGE = -1L;
    // EACH WRITTEN CHUNK TOUCHES THE IDLE WATCHDOG AND BLOCKS UNTIL SENT: SMALL ENOUGH THAT A SLOW PEER STILL MOVES ONE PER TIMEOUT,
    // AND THAT A CANCELLED UPLOAD STOPS WITHIN ONE CHUNK
    static final int CHUNK_BYTES = 8 * 1_024;
    private static final SecureRandom RANDOM = new SecureRandom();
    // THE IDLE WATCHDOG OF THE REQUEST THE CURRENT WORKER IS SERVING; TRANSFERS TOUCH IT PER CHUNK
    private static final ThreadLocal<Watchdog> WATCHDOG = new ThreadLocal<>();
    private static volatile Running running;

    private NetworkServer() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // STARTS THE CONFIGURED LISTENER UNDER cwd; INVALID CONFIGURATION AND BIND FAILURES PROPAGATE TO THE CALLER
    static synchronized void start(final int port, final Path cwd) throws IOException {
        if (running != null)
            throw new IllegalStateException("The file server is already running");
        final var config = WaterMediaConfig.network;
        // THE CONFIGURATION IS PROCESS STATE, NOT AN ARGUMENT OF THIS CALL
        if (config.serverBindAddress == null || config.serverBindAddress.isBlank())
            throw new IllegalStateException("The file server requires an explicit bind address");

        final InetAddress bind = InetAddress.getByName(config.serverBindAddress);
        final String token = config.token;

        if (token == null || token.isBlank())
            throw new IllegalStateException("The file server requires an upload token");

        if (!bind.isLoopbackAddress() && WaterMediaConfig.DEFAULT_NETWORK_TOKEN.equals(token.trim()))
            throw new IllegalStateException("A non-loopback file server requires a custom upload token");

        if (config.maxServerRequests < 1 || config.serverTimeout < 1 || config.maxStorageSize < 1 || config.maxUploadSize < 0)
            throw new IllegalStateException("File server concurrency, idle timeout and storage limits must be positive");

        final Path storage = cwd.resolve("watermedia").resolve("files").toAbsolutePath().normalize();
        Files.createDirectories(storage);
        // OWN THE STORAGE BEFORE CLEANUP, AND KEEP THE LOCK UNTIL ALL WORKERS EXIT.
        final FileChannel storageChannel = FileChannel.open(storage.resolve(".server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean retained = false;
        Throwable startFailure = null;
        try {
            final FileLock storageLock;
            try { storageLock = storageChannel.tryLock(); }
            catch (final OverlappingFileLockException occupied) {
                throw new IOException("File server storage is already in use: " + storage, occupied);
            }
            if (storageLock == null) throw new IOException("File server storage is already in use: " + storage);
            final var incomplete = new ArrayList<Path>();
            try (final DirectoryStream<Path> entries = Files.newDirectoryStream(storage, ".upload-*")) {
                for (final Path entry: entries) incomplete.add(entry);
            }
            for (final Path entry: incomplete) {
                if (!entry.getFileName().toString().matches("\\.upload-[A-Za-z0-9]{8}")
                        || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                Path partial = null;
                boolean owned = true;
                try (final DirectoryStream<Path> contents = Files.newDirectoryStream(entry)) {
                    for (final Path child: contents) {
                        if (partial != null || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                            owned = false;
                            break;
                        }
                        partial = child;
                    }
                } catch (final IOException cleanup) {
                    LOGGER.warn(IT, "Could not inspect incomplete upload {}", entry, cleanup);
                    continue;
                }
                if (!owned) continue;
                try {
                    // AN UPLOAD TEMPORARY IS EMPTY OR CONTAINS ONE REGULAR FILE.
                    if (partial != null) Files.deleteIfExists(partial);
                    Files.deleteIfExists(entry);
                } catch (final IOException cleanup) {
                    LOGGER.warn(IT, "Could not remove incomplete upload {}", entry, cleanup);
                }
            }
            long used = 0;
            try (final var files = Files.walk(storage)) {
                final var entries = files.iterator();
                while (entries.hasNext()) {
                    final Path file = entries.next();
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) used = Math.addExact(used, Files.size(file));
                }
            }
            // 0 KEEPS THE JDK BACKLOG OF 50: IT ONLY HOLDS CONNECTIONS NOT ACCEPTED YET, AND A FULL ONE MAKES LINUX
            // DROP THE HANDSHAKE AND RETRY IT A SECOND LATER; THE SLOTS AND THE EXCHANGE CAP BOUND THE WORK
            final HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            ThreadPoolExecutor executor = null;
            ScheduledThreadPoolExecutor deadlines = null;
            try {
                // THREADS GROW ON DEMAND AND THE EXCHANGE CAP BELOW BOUNDS THEM: A CAPPED POOL ALSO REFUSES WORK WHILE ITS
                // WORKERS STILL WIND DOWN THEIR LAST TASK, AND THE JDK DISPATCHER THEN DROPS THE CONNECTION WITHOUT A STATUS
                executor = new ThreadPoolExecutor(config.maxServerRequests, Integer.MAX_VALUE,
                        10, TimeUnit.SECONDS, new SynchronousQueue<>(), ThreadTool.factory("NetworkServer", Thread.NORM_PRIORITY));
                deadlines = new ScheduledThreadPoolExecutor(1,
                        ThreadTool.factory("NetworkServer-Deadline", Thread.NORM_PRIORITY));
                deadlines.setRemoveOnCancelPolicy(true);
                final Running state = new Running(server, executor, deadlines, storage, storageChannel, token.getBytes(StandardCharsets.UTF_8),
                        config.maxUploadSize * 1_024L * 1_024L, config.maxStorageSize * 1_024L * 1_024L, used, config.serverTimeout, config.maxServerRequests);
                final Filter admission = new Filter() {
                    @Override
                    public void doFilter(final HttpExchange exchange, final Chain chain) throws IOException {
                        // RFC 9112: THE SERVER CLOSES AFTER A REQUEST CARRYING Connection: close AND SHOULD SAY SO, OR A CLIENT THAT
                        // KEEPS SOCKETS (FFMPEG'S HTTP) REUSES THE DEAD ONE AND READS NOTHING; THE SAME TEST THE JDK SERVER USES
                        if ("close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection")))
                            exchange.getResponseHeaders().set("Connection", "close");
                        final boolean admitted;
                        synchronized (state) {
                            admitted = state.active < state.slots;
                            if (admitted) state.active++;
                        }
                        if (!admitted) {
                            if (!state.busyWarned) LOGGER.warn(IT, "Refused a request from {} with all {} slots busy", exchange.getRemoteAddress(), state.slots);
                            state.busyWarned = true;
                            try (exchange) { exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1); }
                            return;
                        }
                        try { chain.doFilter(exchange); }
                        finally { synchronized (state) { state.active--; } }
                    }

                    @Override
                    public String description() { return "Bounds the simultaneous requests to the configured slots"; }
                };
                server.createContext("/upload", exchange -> handleUpload(exchange, state)).getFilters().add(admission);
                server.createContext("/", exchange -> handleRoot(exchange, state)).getFilters().add(admission);
                // THE DISPATCHER HANDS OVER EVERY EXCHANGE, ALSO SLOW HEADERS AND THE EOF OF A DROPPED KEEP-ALIVE CONNECTION: THREE PER
                // SLOT LEAVE ROOM FOR THEM. PAST THE CAP THE DISPATCHER CLOSES THE CONNECTION, SO NO REQUEST QUEUE GROWS
                server.setExecutor(command -> {
                    synchronized (state) {
                        if (state.exchanges >= 3 * state.slots) throw new RejectedExecutionException("All file server exchanges are busy");
                        state.exchanges++;
                    }
                    state.executor.execute(() -> {
                        final Watchdog watchdog = new Watchdog(Thread.currentThread(), state.deadlines, TimeUnit.MILLISECONDS.toNanos(state.timeout));
                        WATCHDOG.set(watchdog);
                        try {
                            watchdog.arm();
                            command.run();
                        } finally {
                            synchronized (state) { state.exchanges--; }
                            watchdog.finish();
                            WATCHDOG.remove();
                        }
                    });
                });
                server.start();
                running = state;
                retained = true;
                LOGGER.info(IT, "Started file server at {} with {} request slots and {} bytes of storage capacity",
                        server.getAddress(), config.maxServerRequests, state.maxStorage);
            } catch (final RuntimeException | Error failure) {
                running = null;
                retained = false;
                try { server.stop(0); }
                catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                if (executor != null) {
                    try { executor.shutdownNow(); }
                    catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                if (deadlines != null) {
                    try { deadlines.shutdownNow(); }
                    catch (final RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        } catch (final IOException | RuntimeException | Error failure) {
            startFailure = failure;
            throw failure;
        } finally {
            if (!retained) {
                try { storageChannel.close(); }
                catch (final IOException cleanup) {
                    if (startFailure == null) throw cleanup;
                    if (cleanup != startFailure) startFailure.addSuppressed(cleanup);
                }
            }
        }
    }

    // CURRENT LISTENER ADDRESS, INCLUDING AN ASSIGNED EPHEMERAL PORT, OR NULL WHEN STOPPED
    static InetSocketAddress address() {
        final Running state = running;
        return state == null || state.stopping ? null : state.server.getAddress();
    }

    // STOPS ADMISSION AND ACTIVE TRANSFERS; COMPLETED FILES REMAIN ON DISK. SAFE TO REPEAT. THE LIFECYCLE NEVER CALLS IT: AT EXIT
    // THE OS RELEASES THE SOCKET AND THE STORAGE LOCK AND THE NEXT START SWEEPS INCOMPLETE UPLOADS; TESTS RESTART THROUGH IT
    static synchronized void stop() {
        final Running state = running;
        if (state == null) return;
        state.stopping = true;
        state.server.stop(0);
        state.executor.shutdownNow();
        state.deadlines.shutdownNow();
        try {
            if (!state.executor.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("File server transfers have not finished; shutdown must be retried");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for file server transfers to finish", e);
        }
        try { state.storageChannel.close(); }
        catch (final IOException failure) {
            throw new IllegalStateException("File server storage lock could not be released", failure);
        }
        running = null;
        LOGGER.info(IT, "Stopped file server");
    }

    // ==========================================================================
    // REQUEST HANDLERS
    // ==========================================================================
    private static void handleUpload(final HttpExchange exchange, final Running state) throws IOException {
        try (exchange) {
            if (!"/upload".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                return;
            }
            final String token = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_TOKEN);
            if (token == null || !MessageDigest.isEqual(state.token, token.getBytes(StandardCharsets.UTF_8))) {
                if (!state.rejectWarned) LOGGER.warn(IT, "Rejected an unauthorized upload from {}", exchange.getRemoteAddress());
                state.rejectWarned = true;
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_UNAUTHORIZED, -1);
                return;
            }
            final String filename = exchange.getRequestHeaders().getFirst(X_WATERMEDIA_FILENAME);
            if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                    || filename.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\' || c == ':')) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            try {
                final Path name = Path.of(filename);
                if (name.isAbsolute() || name.getNameCount() != 1) {
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                    return;
                }
            } catch (final InvalidPathException e) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            final String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
            final long length;
            try { length = lengthHeader == null ? -1 : Long.parseLong(lengthHeader); }
            catch (final NumberFormatException e) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            if (length < 0) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_LENGTH_REQUIRED, -1);
                return;
            }
            if (state.maxUpload > 0 && length > state.maxUpload) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_ENTITY_TOO_LARGE, -1);
                return;
            }
            final boolean admitted;
            final long occupied;
            synchronized (state) {
                occupied = state.used + state.reserved;
                admitted = length <= state.maxStorage - occupied;
                if (admitted) state.reserved += length;
            }
            if (!admitted) {
                // NOTHING EVICTS STORED FILES: THE OPERATOR MUST REMOVE FILES BY HAND OR RAISE THE BUDGET
                if (!state.fullWarned)
                    LOGGER.warn(IT, "Storage is full: refused '{}' ({} bytes) with {} of {} bytes in use under {}", filename, length, occupied, state.maxStorage, state.storage);
                state.fullWarned = true;
                exchange.sendResponseHeaders(507, -1);
                return;
            }
            Path temporary = null;
            Path target = null;
            boolean stored = false;
            String id = null;
            try {
                while (temporary == null) {
                    final StringBuilder name = new StringBuilder(ID_LENGTH);
                    for (int i = 0; i < ID_LENGTH; i++) name.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
                    id = name.toString();
                    if (Files.exists(state.storage.resolve(id), LinkOption.NOFOLLOW_LINKS)) continue;
                    try { temporary = Files.createDirectory(state.storage.resolve(".upload-" + id)); }
                    catch (final FileAlreadyExistsException ignored) {}
                }
                target = temporary.resolve(filename);
                final Watchdog watchdog = WATCHDOG.get();
                try (final var input = exchange.getRequestBody();
                     final var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    final byte[] buffer = new byte[IOTool.BUFFER_SIZE];
                    long remaining = length;
                    while (remaining > 0) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Upload idle for too long");
                        final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) throw new IOException("Upload ended before Content-Length");
                        output.write(buffer, 0, read);
                        remaining -= read;
                        watchdog.touch();
                    }
                }
                if (state.stopping || Thread.currentThread().isInterrupted()) throw new IOException("Upload stopped before publication");
                Files.move(temporary, state.storage.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                stored = true;
            } finally {
                long retained = stored ? length : 0;
                if (!stored && temporary != null) {
                    try {
                        if (target != null) Files.deleteIfExists(target);
                        Files.deleteIfExists(temporary);
                    } catch (final IOException cleanup) {
                        LOGGER.warn(IT, "Could not remove incomplete upload {}", temporary, cleanup);
                        retained = length;
                    }
                }
                synchronized (state) {
                    state.reserved -= length;
                    state.used += retained;
                }
            }
            final byte[] response = id.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
            try (final var output = exchange.getResponseBody()) { output.write(response); }
            LOGGER.info(IT, "Stored '{}' as ID '{}' ({} bytes)", filename, id, length);
        }
    }

    private static void handleRoot(final HttpExchange exchange, final Running state) throws IOException {
        try (exchange) {
            final String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                return;
            }
            final boolean head = "HEAD".equals(method);
            final String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) {
                // A REACHABILITY LINE FOR ANYONE; THE VERSION STAYS OUT OF AN UNAUTHENTICATED ANSWER
                final byte[] info = WaterMedia.NAME.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(info.length));
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, head ? -1 : info.length);
                if (!head) try (final var output = exchange.getResponseBody()) { output.write(info); }
                return;
            }
            final String id = path.substring(1);
            if (!id.matches("[a-zA-Z0-9]{1,64}")) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1);
                return;
            }
            final Path directory = state.storage.resolve(id);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            final Path file;
            try (final var files = Files.list(directory)) {
                file = files.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).findFirst().orElse(null);
            }
            if (file == null) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            final String filename = file.getFileName().toString();
            final String contentType = NetRequest.contentTypeOf(filename);
            final long size = Files.size(file);
            long start = 0;
            long end = size - 1;
            boolean partial = false;
            // ONE SATISFIABLE bytes RANGE IS SERVED, FOR HEAD TOO; A MALFORMED OR MULTIPART ONE IS IGNORED AND THE WHOLE
            // FILE ANSWERED, AS RFC 9110 ALLOWS. ONLY A WELL-FORMED RANGE OUTSIDE THE FILE IS A 416
            final String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.regionMatches(true, 0, "bytes=", 0, 6) && range.indexOf(',') < 0 && range.indexOf('-') > 0) {
                final String spec = range.substring(6);
                final int dash = spec.indexOf('-');
                final boolean suffix = dash == 0;
                final long first = suffix ? 0 : rangeValue(spec.substring(0, dash));
                final long last = spec.length() == dash + 1 ? (suffix ? INVALID_RANGE : Long.MAX_VALUE) : rangeValue(spec.substring(dash + 1));
                if (first != INVALID_RANGE && last != INVALID_RANGE && first <= last) {
                    if (suffix ? last == 0 || size == 0 : first >= size) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                        exchange.sendResponseHeaders(416, -1);
                        return;
                    }
                    start = suffix ? Math.max(0, size - last) : first;
                    end = suffix ? size - 1 : Math.min(last, size - 1);
                    partial = true;
                }
            }
            final long length = size == 0 ? 0 : end - start + 1;
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            if (partial) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + size);
            else exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename.replace('"', '_') + "\"");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(length));
            final int status = partial ? HttpURLConnection.HTTP_PARTIAL : HttpURLConnection.HTTP_OK;
            if (head || length == 0) {
                // NO BODY FOLLOWS; -1 KEEPS THE Content-Length SET ABOVE INSTEAD OF A CHUNKED ENCODING
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.sendResponseHeaders(status, length);
            final Watchdog watchdog = WATCHDOG.get();
            try (final var channel = Files.newByteChannel(file, StandardOpenOption.READ);
                 final var input = Channels.newInputStream(channel);
                 final var output = exchange.getResponseBody()) {
                channel.position(start);
                final byte[] buffer = new byte[CHUNK_BYTES];
                long remaining = length;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download idle for too long");
                    final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Stored file was truncated during download");
                    output.write(buffer, 0, read);
                    remaining -= read;
                    watchdog.touch();
                }
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A RANGE OFFSET: DIGITS ONLY, SATURATING ABOVE Long.MAX_VALUE; ANYTHING ELSE IS INVALID_RANGE
    private static long rangeValue(final String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) return INVALID_RANGE;
        final BigInteger number = new BigInteger(value);
        return number.bitLength() > 63 ? Long.MAX_VALUE : number.longValue();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    private static final class Running {
        private final HttpServer server;
        private final ThreadPoolExecutor executor;
        private final ScheduledThreadPoolExecutor deadlines;
        private final Path storage;
        private final FileChannel storageChannel;
        private final byte[] token;
        private final long maxUpload;
        private final long maxStorage;
        private final int timeout;
        private final int slots;
        private long used;
        private long reserved;
        private int active; // REQUESTS PAST ADMISSION, GUARDED BY THIS MONITOR LIKE THE STORAGE COUNTERS
        private int exchanges; // EXCHANGES RUNNING ON A WORKER, HEADER READS INCLUDED, GUARDED LIKE active
        private volatile boolean stopping;
        // ANOMALY LATCHES: AN UNAUTHORIZED UPLOAD, A FULL STORAGE AND BUSY SLOTS ARE LOGGED ONCE PER SERVER
        private volatile boolean rejectWarned;
        private volatile boolean fullWarned;
        private volatile boolean busyWarned;

        private Running(final HttpServer server, final ThreadPoolExecutor executor, final ScheduledThreadPoolExecutor deadlines,
                        final Path storage, final FileChannel storageChannel, final byte[] token,
                        final long maxUpload, final long maxStorage, final long used, final int timeout, final int slots) {
            this.server = server;
            this.executor = executor;
            this.deadlines = deadlines;
            this.storage = storage;
            this.storageChannel = storageChannel;
            this.token = token;
            this.maxUpload = maxUpload;
            this.maxStorage = maxStorage;
            this.used = used;
            this.timeout = timeout;
            this.slots = slots;
        }
    }

    // IDLE WATCHDOG OF ONE REQUEST: INTERRUPTS ITS WORKER ONCE THE TIMEOUT PASSES WITHOUT A touch(), WHICH EVERY
    // TRANSFERRED CHUNK ISSUES, SO ONLY STALLED HEADERS OR BODIES ARE CUT. THE INTERRUPT CLOSES THE JDK'S SOCKETCHANNEL
    private static final class Watchdog implements Runnable {
        private final Thread worker;
        private final ScheduledThreadPoolExecutor deadlines;
        private final long timeoutNs;
        private volatile long touched = System.nanoTime();
        private boolean finished;
        private ScheduledFuture<?> check;

        private Watchdog(final Thread worker, final ScheduledThreadPoolExecutor deadlines, final long timeoutNs) {
            this.worker = worker;
            this.deadlines = deadlines;
            this.timeoutNs = timeoutNs;
        }

        void touch() { this.touched = System.nanoTime(); }

        synchronized void arm() { this.schedule(this.timeoutNs); }

        synchronized void finish() {
            this.finished = true;
            if (this.check != null) this.check.cancel(false);
        }

        @Override
        public synchronized void run() {
            if (this.finished) return;
            final long idle = System.nanoTime() - this.touched;
            if (idle >= this.timeoutNs) this.worker.interrupt();
            else this.schedule(this.timeoutNs - idle);
        }

        // A STOPPING SERVER HAS SHUT ITS SCHEDULER DOWN AND INTERRUPTS EVERY WORKER ITSELF
        private void schedule(final long delayNs) {
            if (this.finished) return;
            try { this.check = this.deadlines.schedule(this, delayNs, TimeUnit.NANOSECONDS); }
            catch (final RejectedExecutionException stopping) { this.finished = true; }
        }
    }
}
