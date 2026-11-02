package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.watermedia.WaterMedia.LOGGER;

/** Shared HTTP body cache with bounded disk storage, opened once when the player module loads. */
final class NetworkCache {
    private static final Marker IT = MarkerManager.getMarker(NetworkCache.class.getSimpleName());
    private static final int MAGIC = 0x574D4943;
    private static final int VERSION = 5;
    private static final int HASH_BYTES = 32;
    private static final int ENTRY_MIN_BYTES = HASH_BYTES + 8 + 8 + 2;
    private static final String INDEX_FILE = "index.dat";
    private static final String FILE_PREFIX = "wm_";
    private static final String FILE_SUFFIX = ".tmp";
    private static final String PART_SUFFIX = ".part";
    private static final long DEFAULT_TTL_MS = 7L * 24 * 60 * 60 * 1_000;
    // LEFTOVERS YOUNGER THAN THIS MAY BELONG TO ANOTHER PROCESS SHARING THE FOLDER, SO THE STARTUP SWEEP SPARES THEM
    private static final long SWEEP_GRACE_MS = 60_000L;
    // DAEMON WORKERS FOR NETWORK TRANSFERS, ONE PER CONCURRENT DOWNLOAD, RETIRED AFTER A MINUTE IDLE
    private static final ExecutorService TRANSFERS = Executors.newCachedThreadPool(ThreadTool.workerFactory("MediaCache", Thread.NORM_PRIORITY - 1));
    private static volatile Store active;

    private NetworkCache() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Opens the cache store at {@code dir}; a previous store, if any, is simply replaced. */
    public static synchronized void start(final Path dir) throws IOException {
        final Store store = new Store(dir.toAbsolutePath());
        Files.createDirectories(store.dir);
        loadIndex(store);
        // PAYLOADS THE INDEX DOES NOT VOUCH FOR (CRASHED WRITES, EXPIRED ENTRIES, OLD FORMATS) ARE DERIVED DATA
        final long stale = System.currentTimeMillis() - SWEEP_GRACE_MS;
        try (final var paths = Files.list(store.dir)) {
            for (final Path path: paths.toList()) {
                final String name = path.getFileName().toString();
                final boolean payload = name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
                final boolean orphan = name.endsWith(PART_SUFFIX) || (payload && !store.index.containsKey(
                        name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length())));
                if (!orphan) continue;
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < stale) Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // ANOTHER PROCESS MAY HAVE JUST REMOVED OR REPLACED IT; THE NEXT START SWEEPS AGAIN
                }
            }
        }
        active = store;
        LOGGER.info(IT, "Media network cache initialized at {} ({} entries, {} bytes)", store.dir, store.index.size(), store.totalBytes);
    }

    /** Reads at most {@code maxBytes} of any NetRequest source; enabled HTTP(S) reads go through the cache, sharing one download per key. */
    public static CachedBytes read(final URI uri, final HttpHeaders headers, final String accept,
                                   final long maxBytes, final boolean enabled) throws IOException {
        final Store store = active;
        if (!enabled || !isHttp(uri) || store == null) {
            final Abort abort = new Abort();
            return await(transfer(() -> abort.run(() -> fetch(uri, headers, accept, maxBytes, false))), abort);
        }
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = HexFormat.of().formatHex(hash);
        final Entry entry;
        synchronized (store) {
            entry = stored(store, hex);
        }
        // THE PAYLOAD IS READ OUTSIDE THE MONITOR SO ONE SLOW DISK HIT NEVER STALLS THE OTHER CACHE USERS
        if (entry != null && entry.size <= maxBytes) {
            final Path file = file(store, hex);
            try {
                return new CachedBytes(Files.readAllBytes(file), entry.contentType, true, entry.expiresAt);
            } catch (final NoSuchFileException evicted) {
                // ANOTHER WRITE EVICTED THE ENTRY BETWEEN THE LOOKUP AND THE READ, WHICH IS A PLAIN MISS
                LOGGER.debug(IT, "Media cache entry {} was evicted while being read", file);
            } catch (final IOException failure) {
                synchronized (store) {
                    if (store.index.get(hex) == entry) {
                        try { delete(store, hex); }
                        catch (final IOException ignored) { /* THE NEXT LOOKUP RETRIES THE DELETE */ }
                    }
                }
                LOGGER.warn(IT, "Dropping unreadable media cache entry {}", file, failure);
            }
        }
        return fetchShared(store, hex, hash, uri, headers, accept, maxBytes, false).body();
    }

    /** Returns a cached media file, or null when the cache is off, the source is not HTTP(S) or the body is a playlist, uncacheable or unstored. */
    public static CachedFile readFile(final URI uri, final HttpHeaders headers, final String accept,
                                      final long maxBytes, final boolean enabled) throws IOException {
        // ONLY BOUNDED RESPONSES DOWNLOAD: A BODY OVER maxBytes, A MISSING Content-Length OR AN ICY STREAM THROWS AN IOException
        // SO THE CALLER STREAMS THE SOURCE DIRECTLY
        final Store store = active;
        if (!enabled || !isHttp(uri) || store == null) return null;
        final byte[] hash = keyHash(uri, headers, accept);
        final String hex = HexFormat.of().formatHex(hash);
        synchronized (store) {
            final Entry entry = stored(store, hex);
            if (entry != null) {
                if (isPlaylist(entry.contentType)) delete(store, hex);
                else if (entry.size <= maxBytes) return new CachedFile(file(store, hex), true, entry.contentType);
            }
        }
        final Download done = fetchShared(store, hex, hash, uri, headers, accept, maxBytes, true);
        // A PLAYLIST, AN EXPIRED BODY OR A FAILED STORE LEAVES NO FILE TO HAND OUT
        if (done.file() == null || isPlaylist(done.body().contentType)) return null;
        return new CachedFile(done.file(), false, done.body().contentType);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // ONE TRANSFER PER KEY, SHARED BY CONCURRENT CALLERS. SOCKET I/O IGNORES INTERRUPTS, SO THE TRANSFER RUNS ON A CACHE WORKER
    // AND EVERY CALLER WAITS INTERRUPTIBLY: A STOPPED PLAYER LEAVES AT ONCE AND THE SHARED TRANSFER STILL FILLS THE CACHE
    private static Download fetchShared(final Store store, final String hex, final byte[] hash, final URI uri, final HttpHeaders headers,
                                        final String accept, final long maxBytes, final boolean bounded) throws IOException {
        final CompletableFuture<Download> mine = new CompletableFuture<>();
        final CompletableFuture<Download> leader;
        synchronized (store) {
            leader = store.inflight.putIfAbsent(hex, mine);
        }
        if (leader != null) {
            try {
                final Download shared = await(leader, null);
                if (shared.body().bytes.length <= maxBytes) return shared;
            } catch (final IOException failure) {
                // AN INTERRUPT ENDS THE WAIT; ANY OTHER FAILURE WAS THE LEADER'S, SO THIS WAITER FETCHES ON ITS OWN BELOW
                if (Thread.currentThread().isInterrupted()) throw failure;
            }
            final Abort abort = new Abort();
            return await(transfer(() -> abort.run(() -> download(store, hex, hash, uri, headers, accept, maxBytes, bounded))), abort);
        }
        // THE WORKER STORES THE BODY BEFORE IT STOPS SHARING IT, SO A LATE CALLER FINDS THE TRANSFER OR THE ENTRY, NEVER NEITHER
        transfer(() -> download(store, hex, hash, uri, headers, accept, maxBytes, bounded)).whenComplete((done, failure) -> {
            store.inflight.remove(hex, mine);
            if (failure == null) mine.complete(done);
            else mine.completeExceptionally(failure);
        });
        return await(mine, null);
    }

    // FETCHES ONE BODY AND STORES IT WHEN IT MAY BE CACHED (NOT EXPIRED, NOT A PLAYLIST FOR A FILE READ); IT RUNS ON THE WORKER,
    // SO A SHARED DOWNLOAD WHOSE CALLERS LEFT STILL FILLS THE CACHE
    private static Download download(final Store store, final String hex, final byte[] hash, final URI uri, final HttpHeaders headers,
                                     final String accept, final long maxBytes, final boolean bounded) throws IOException {
        final CachedBytes body = fetch(uri, headers, accept, maxBytes, bounded);
        final boolean keep = body.expiresAt > System.currentTimeMillis() && !(bounded && isPlaylist(body.contentType));
        return new Download(body, keep ? store(store, hex, hash, body) : null);
    }

    // STARTS ONE TRANSFER ON A CACHE WORKER; WHEN NO WORKER CAN START, THE TRANSFER FAILS INSTEAD OF NEVER COMPLETING
    private static <T> CompletableFuture<T> transfer(final Callable<T> work) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            TRANSFERS.execute(() -> {
                try {
                    future.complete(work.call());
                } catch (final Throwable failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (final RuntimeException | Error failure) {
            future.completeExceptionally(failure);
        }
        return future;
    }

    // WAITS FOR A TRANSFER, GIVING UP ON INTERRUPT, AND RETHROWS ITS OWN FAILURE. abort IS NON-NULL ONLY FOR AN UNSHARED
    // TRANSFER: ITS CALLER IS THE ONLY ONE WAITING, SO GIVING UP ALSO STOPS IT INSTEAD OF LETTING IT READ ON TO maxBytes
    private static <T> T await(final CompletableFuture<T> transfer, final Abort abort) throws IOException {
        try {
            return transfer.get();
        } catch (final InterruptedException e) {
            if (abort != null) abort.abort();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for a media cache download");
        } catch (final ExecutionException e) {
            if (e.getCause() instanceof final IOException failure) throw failure;
            if (e.getCause() instanceof final RuntimeException failure) throw failure;
            if (e.getCause() instanceof final Error failure) throw failure;
            throw new IOException("Media cache download failed", e.getCause());
        }
    }

    // bounded REFUSES ENDLESS SOURCES (NO Content-Length, ICY RADIO) BEFORE READING A SINGLE BODY BYTE
    private static CachedBytes fetch(final URI uri, final HttpHeaders headers, final String accept, final long maxBytes,
                                     final boolean bounded) throws IOException {
        final NetRequest req = new NetRequest(uri).headers(headers);
        if (accept != null && (headers == null || !headers.has("Accept"))) req.accept(accept);
        // A FULL RANGE REQUEST AVOIDS REAL-TIME THROTTLING ON SOME MEDIA CDNS.
        if (headers == null || !headers.has("Range")) req.header("Range", "bytes=0-");
        req.send();
        try (req) {
            final int status = req.statusCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("HTTP " + status + " for " + uri);
            final long contentLength = req.contentLength();
            if (contentLength > maxBytes)
                throw new IOException("Media source exceeds cache limit (" + contentLength + " > " + maxBytes + " bytes): " + uri);
            if (bounded && (contentLength < 0 || req.header("icy-metaint") != null || req.header("icy-name") != null))
                throw new IOException("Unbounded media source is not cacheable: " + uri);
            final byte[] bytes;
            try (final InputStream in = req.inputStream()) {
                bytes = IOTool.readLimited(in, maxBytes, contentLength, 0L);
            }
            // A CONNECTION THAT CLOSES EARLY READS AS A CLEAN END: A SHORT BODY MUST NEVER REACH THE CALLER OR THE CACHE
            if (contentLength >= 0 && bytes.length != contentLength)
                throw new IOException("Truncated media body (" + bytes.length + " of " + contentLength + " bytes): " + uri);
            return new CachedBytes(bytes, req.contentType(), false, expiry(req));
        }
    }

    // INDEX OPERATIONS HOLD THE STORE MONITOR; NETWORK TRANSFERS, PAYLOAD READS AND PAYLOAD STAGING NEVER HOLD IT.
    // THE INDEX IS ACCESS-ORDERED, SO A LOOKUP MARKS ITS ENTRY AS RECENTLY USED AND THE HEAD IS THE EVICTION CANDIDATE
    private static Entry stored(final Store store, final String hex) throws IOException {
        final Entry entry = store.index.get(hex);
        if (entry != null && (entry.expiresAt <= System.currentTimeMillis() || !Files.isRegularFile(file(store, hex)))) {
            delete(store, hex);
            return null;
        }
        return entry;
    }

    // A CACHE FAILURE NEVER FAILS THE DOWNLOAD THAT ALREADY SUCCEEDED: THE CALLER JUST GETS NO CACHED FILE
    private static Path store(final Store store, final String hex, final byte[] hash, final CachedBytes body) {
        final long budget = DataTool.megabytes(Math.max(1L, WaterMediaConfig.player.cacheBudget));
        final long size = body.bytes.length;
        if (size > budget) return null;
        final Path file = file(store, hex);
        try {
            final Path partial = Files.createTempFile(store.dir, FILE_PREFIX, PART_SUFFIX);
            try {
                Files.write(partial, body.bytes);
                synchronized (store) {
                    if (active != store) return null;
                    final Entry existing = stored(store, hex);
                    if (existing != null) {
                        if (existing.size == size) return file;
                        delete(store, hex);
                    }
                    IOTool.move(partial, file);
                    store.index.put(hex, new Entry(hash, body.expiresAt, size, body.contentType));
                    store.totalBytes += size;
                    // EVICTS FROM THE LEAST RECENTLY USED END UNTIL THE STORE FITS; THE FRESH ENTRY IS THE TAIL AND FITS ALONE
                    final Iterator<Map.Entry<String, Entry>> eldest = store.index.entrySet().iterator();
                    while (store.totalBytes > budget) {
                        final Map.Entry<String, Entry> old = eldest.next();
                        if (old.getKey().equals(hex)) break;
                        Files.deleteIfExists(file(store, old.getKey()));
                        store.totalBytes -= old.getValue().size;
                        eldest.remove();
                    }
                    flushIndex(store);
                    return file;
                }
            } finally {
                Files.deleteIfExists(partial);
            }
        } catch (final IOException failure) {
            if (!store.writeWarned) LOGGER.warn(IT, "Media cache write failed for {}", file, failure);
            store.writeWarned = true;
            return null;
        }
    }

    private static void delete(final Store store, final String hex) throws IOException {
        Files.deleteIfExists(file(store, hex));
        final Entry entry = store.index.remove(hex);
        if (entry != null) store.totalBytes -= entry.size;
        flushIndex(store);
    }

    private static Path file(final Store store, final String hex) {
        return store.dir.resolve(FILE_PREFIX + hex + FILE_SUFFIX);
    }

    private static void loadIndex(final Store store) throws IOException {
        if (!Files.isRegularFile(store.indexPath)) return;
        try (final DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(store.indexPath)))) {
            final int magic = in.readInt();
            final int version = in.readInt();
            final int count = in.readInt();
            if (magic != MAGIC || version != VERSION || count < 0 || count > (Files.size(store.indexPath) - 12) / ENTRY_MIN_BYTES)
                throw new IOException("Unsupported media cache index");
            final long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                final byte[] hash = new byte[HASH_BYTES];
                in.readFully(hash);
                final long expiresAt = in.readLong();
                final long size = in.readLong();
                final String contentType = in.readUTF();
                final String hex = HexFormat.of().formatHex(hash);
                final Path file = file(store, hex);
                // AN ENTRY IS TRUSTED ONLY WHILE ITS PAYLOAD IS WHOLE; THE REST IS LEFT FOR THE STARTUP SWEEP
                if (expiresAt > now && size >= 0 && Files.isRegularFile(file) && Files.size(file) == size) {
                    store.index.put(hex, new Entry(hash, expiresAt, size, contentType.isEmpty() ? null : contentType));
                    store.totalBytes += size;
                }
            }
            if (in.read() != -1) throw new IOException("Trailing media cache index bytes");
        } catch (final IOException | RuntimeException failure) {
            // CACHE FILES ARE DERIVED DATA; OBSOLETE INDEX FORMATS ARE DROPPED, NOT MIGRATED.
            store.index.clear();
            store.totalBytes = 0;
            LOGGER.warn(IT, "Discarding unsupported or corrupt media cache index at {}", store.indexPath, failure);
            Files.deleteIfExists(store.indexPath);
        }
    }

    // THE INDEX IS A FEW DOZEN BYTES PER ENTRY, SO EVERY MUTATION REWRITES IT ATOMICALLY AND NO WRITE IS EVER LOST
    private static void flushIndex(final Store store) throws IOException {
        final Path partial = Files.createTempFile(store.dir, "index", PART_SUFFIX);
        try {
            try (final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(store.index.size());
                for (final Entry entry: store.index.values()) {
                    out.write(entry.hash);
                    out.writeLong(entry.expiresAt);
                    out.writeLong(entry.size);
                    out.writeUTF(entry.contentType == null ? "" : entry.contentType);
                }
            }
            IOTool.move(partial, store.indexPath);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static long expiry(final NetRequest req) {
        final long now = System.currentTimeMillis();
        final String cacheControl = req.header("Cache-Control");
        if (cacheControl != null) {
            long maxAge = -1L;
            for (final String raw: cacheControl.split(",")) {
                final String directive = raw.trim().toLowerCase(Locale.ROOT);
                if (directive.equals("no-store") || directive.equals("no-cache")) return -1L;
                if (directive.startsWith("max-age=")) maxAge = DataTool.parseLong(directive.substring("max-age=".length()).replace("\"", ""), -1L);
            }
            if (maxAge >= 0L) {
                if (maxAge >= Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
                final long millis = maxAge * 1_000L;
                return millis >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + millis;
            }
        }

        final String pragma = req.header("Pragma");
        if (pragma != null && pragma.toLowerCase(Locale.ROOT).contains("no-cache")) return -1L;

        final String expires = req.header("Expires");
        if (expires != null && !expires.isBlank()) {
            try {
                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(expires, Instant::from).toEpochMilli();
            } catch (final DateTimeParseException ignored) {
                return -1L;
            }
        }
        // NO CACHING HEADERS: BOUND THE LIFETIME INSTEAD OF CACHING FOREVER.
        return now + DEFAULT_TTL_MS;
    }

    private static boolean isHttp(final URI uri) {
        final String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static boolean isPlaylist(final String contentType) {
        if (contentType == null) return false;
        final String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("mpegurl") || lower.contains("dash+xml");
    }

    // CANONICAL CACHE KEY: uri + '\n' + LOWERCASED "name:value" LINES + OPTIONAL "accept:" TAIL.
    // THE BYTE STREAM MUST STAY STABLE: ANY DRIFT ORPHANS EVERY EXISTING ON-DISK CACHE.
    private static byte[] keyHash(final URI uri, final HttpHeaders headers, final String accept) {
        final StringBuilder key = new StringBuilder(uri.toASCIIString()).append('\n');
        if (headers != null && !headers.empty()) {
            for (final HttpHeaders.Entry entry: headers.entries()) {
                key.append(entry.name().toLowerCase(Locale.ROOT)).append(':').append(entry.value()).append('\n');
            }
        }
        if (accept != null && (headers == null || !headers.has("Accept"))) key.append("accept:").append(accept);
        return DataTool.sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    public record CachedBytes(byte[] bytes, String contentType, boolean cached, long expiresAt) {}
    public record CachedFile(Path path, boolean cached, String contentType) {}
    // A SHARED DOWNLOAD AND THE CACHE FILE THE WORKER STORED IT AS, null WHEN IT WAS NOT CACHEABLE OR THE STORE FAILED
    private record Download(CachedBytes body, Path file) {}
    private record Entry(byte[] hash, long expiresAt, long size, String contentType) {}

    private static final class Store {
        private final Path dir;
        private final Path indexPath;
        private final Map<String, Entry> index = new LinkedHashMap<>(64, 0.75f, true);
        private final Map<String, CompletableFuture<Download>> inflight = new ConcurrentHashMap<>();
        private long totalBytes;
        private volatile boolean writeWarned; // A FAILING WRITE IS LOGGED ONCE PER STORE

        private Store(final Path dir) {
            this.dir = dir;
            this.indexPath = dir.resolve(INDEX_FILE);
        }
    }

    // LETS THE ONLY CALLER OF A TRANSFER STOP IT WITHOUT BLOCKING: AN INTERRUPT ENDS readLimited AT ITS NEXT CHUNK (A SILENT SOCKET
    // ENDS ON THE READ TIMEOUT, ON THE WORKER ALONE), AND IT NEVER OUTLIVES ITS TRANSFER ON THE POOLED THREAD
    private static final class Abort {
        private Thread worker;   // GUARDED BY this
        private boolean aborted; // GUARDED BY this

        // RUNS THE TRANSFER ON THE WORKER, REACHABLE BY abort() ONLY WHILE IT RUNS
        <T> T run(final Callable<T> work) throws Exception {
            synchronized (this) {
                if (this.aborted) throw new InterruptedIOException("Media cache download abandoned before it started");
                this.worker = Thread.currentThread();
            }
            try {
                return work.call();
            } finally {
                synchronized (this) {
                    this.worker = null;
                    Thread.interrupted(); // AN ABORT THAT LANDED DURING THIS TRANSFER ENDS WITH IT
                }
            }
        }

        synchronized void abort() {
            this.aborted = true;
            if (this.worker != null) this.worker.interrupt();
        }
    }
}
