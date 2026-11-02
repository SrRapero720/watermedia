package org.watermedia.api.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.player.HeadlessGFXEngine;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies {@link NetworkServer} over real HTTP on an ephemeral loopback port: the upload
 * auth/traversal guards, ID downloads (GET/HEAD), the Range handling, the root answer, the playback
 * of a stored video through FFmpeg's own HTTP client, and its limits: configuration refusals, token
 * snapshots, the once-per-server rejection warning, storage accounting, incomplete-upload cleanup,
 * the idle timeout and ownership across an incomplete shutdown.
 */
@DisplayName("NetworkServer")
public class NetworkServerTest {
    private static final String TOKEN = "local-test-upload-token";
    @TempDir Path directory;
    private String previousBind;
    private String previousToken;
    private int previousRequests;
    private int previousTimeout;
    private int previousStorage;
    private int previousUpload;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    // CHILD-JVM PROBE: A SECOND PROCESS MUST NOT TAKE OVER, NOR CLEAN, THE STORAGE A LIVE SERVER OWNS
    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("storage-lock"))
            throw new IllegalArgumentException("Unknown storage probe");
        final Path directory = Path.of("").toAbsolutePath();
        final var config = WaterMediaConfig.network;
        config.serverBindAddress = "127.0.0.1";
        config.token = TOKEN;
        config.maxServerRequests = 2;
        config.serverTimeout = 1_000;
        config.maxStorageSize = 1;
        config.maxUploadSize = 1;
        try {
            NetworkServer.start(0, directory);
            throw new AssertionError("Another process acquired the live storage directory");
        } catch (final IOException occupied) {
            if (!occupied.getMessage().contains("storage is already in use")) throw occupied;
        }
        if (!Files.exists(directory.resolve("watermedia/files/.upload-Live1234/partial.bin")))
            throw new AssertionError("The competing process deleted a live upload");
        System.out.println("PROBE_OK storage-lock");
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeEach
    void snapshotConfig() {
        final var config = WaterMediaConfig.network;
        this.previousBind = config.serverBindAddress;
        this.previousToken = config.token;
        this.previousRequests = config.maxServerRequests;
        this.previousTimeout = config.serverTimeout;
        this.previousStorage = config.maxStorageSize;
        this.previousUpload = config.maxUploadSize;
        config.serverBindAddress = "127.0.0.1";
        config.token = TOKEN;
    }

    @AfterEach
    void stop() {
        NetworkServer.stop();
        final var config = WaterMediaConfig.network;
        config.serverBindAddress = this.previousBind;
        config.token = this.previousToken;
        config.maxServerRequests = this.previousRequests;
        config.serverTimeout = this.previousTimeout;
        config.maxStorageSize = this.previousStorage;
        config.maxUploadSize = this.previousUpload;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static int port() {
        final InetSocketAddress address = NetworkServer.address();
        assertNotNull(address);
        return address.getPort();
    }

    private static HttpURLConnection open(final String path, final String method) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port() + path).toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(4_000);
        connection.setRequestMethod(method);
        return connection;
    }

    private static HttpURLConnection upload(final String token, final String filename, final byte[] body) throws IOException {
        final HttpURLConnection connection = open("/upload", "POST");
        connection.setDoOutput(true);
        connection.setRequestProperty(NetworkAPI.X_WATERMEDIA_TOKEN, token);
        connection.setRequestProperty(NetworkAPI.X_WATERMEDIA_FILENAME, filename);
        connection.setFixedLengthStreamingMode(body.length);
        try (final OutputStream output = connection.getOutputStream()) { output.write(body); }
        return connection;
    }

    private static Socket request(final String request) throws IOException {
        final Socket socket = new Socket("127.0.0.1", port());
        socket.setSoTimeout(4_000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Routing")
    class Routing {

        @BeforeEach
        void start() throws IOException {
            NetworkServer.start(0, NetworkServerTest.this.directory);
        }

        @Test
        @DisplayName("valid upload returns a short lowercase ID; GET and HEAD serve it back")
        void uploadThenDownload() throws IOException {
            final String id = this.store("clip.bin", "payload".getBytes(StandardCharsets.UTF_8));
            // LOWERCASE ONLY, SO THE ID NAMES THE SAME FILE ON CASE-INSENSITIVE AND CASE-SENSITIVE FILE SYSTEMS ALIKE
            assertTrue(id.matches("[a-z0-9]{8}"), "Unexpected ID: " + id);

            final HttpURLConnection get = open("/" + id, "GET");
            assertEquals(200, get.getResponseCode());
            assertEquals("payload", new String(get.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            final HttpURLConnection head = open("/" + id, "HEAD");
            assertEquals(200, head.getResponseCode());
            assertEquals("7", head.getHeaderField("Content-Length"));
        }

        @Test
        @DisplayName("upload with a traversal filename is rejected with 400")
        void traversalFilename() throws IOException {
            assertEquals(400, upload(TOKEN, "../evil", "x".getBytes(StandardCharsets.UTF_8)).getResponseCode());
        }

        @Test
        @DisplayName("ranges clip at EOF, unsatisfiable ones answer 416, malformed or multipart ones are ignored and HEAD honours them")
        void clipsRangesAtEofAndHandlesEmptyResources() throws IOException {
            final String id = this.store("bounds.bin", "payload".getBytes(StandardCharsets.UTF_8));
            for (final String range: new String[] { "bytes=0-999", "bytes=-999", "bytes=0-", "bytes=0-999999999999999999999999999" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(206, request.getResponseCode(), range);
                assertEquals("bytes 0-6/7", request.getHeaderField("Content-Range"));
                assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            for (final String range: new String[] { "bytes=2-", "BYTES=2-6" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(206, request.getResponseCode(), range);
                assertEquals("bytes 2-6/7", request.getHeaderField("Content-Range"));
                assertEquals("yload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            for (final String range: new String[] { "bytes=7-", "bytes=-0", "bytes=99999999999999999999-" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(416, request.getResponseCode(), range);
                assertEquals("bytes */7", request.getHeaderField("Content-Range"));
            }
            for (final String range: new String[] { "bytes=abc-", "bytes=5-3", "bytes=-", "bytes=+1-2", "bytes=0-1,3-4" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(200, request.getResponseCode(), range);
                assertNull(request.getHeaderField("Content-Range"), range);
                assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            final HttpURLConnection suffix = open("/" + id, "GET");
            suffix.setRequestProperty("Range", "bytes=-3");
            assertEquals(206, suffix.getResponseCode());
            assertEquals("oad", new String(suffix.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            final HttpURLConnection head = open("/" + id, "HEAD");
            head.setRequestProperty("Range", "bytes=0-2");
            assertEquals(206, head.getResponseCode());
            assertEquals("bytes 0-2/7", head.getHeaderField("Content-Range"));
            assertEquals("3", head.getHeaderField("Content-Length"));

            final String emptyId = this.store("empty.bin", new byte[0]);
            final HttpURLConnection get = open("/" + emptyId, "GET");
            assertEquals(200, get.getResponseCode());
            assertEquals(0, get.getInputStream().readAllBytes().length);
            assertEquals("0", get.getHeaderField("Content-Length"));
            for (final String range: new String[] { "bytes=0-", "bytes=-1" }) {
                final HttpURLConnection request = open("/" + emptyId, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(416, request.getResponseCode(), range);
            }
        }

        @Test
        @DisplayName("GET / reports the server name without its version and an unknown ID is 404")
        void infoAndMissing() throws IOException {
            final HttpURLConnection root = open("/", "GET");
            assertEquals(200, root.getResponseCode());
            final String info = new String(root.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(info.contains("WaterMedia"));
            assertFalse(info.contains(WaterMedia.VERSION), "An unauthenticated answer must not reveal the version");

            assertEquals(404, open("/zzzzzzzz", "GET").getResponseCode());
        }

        @Test
        @DisplayName("a stored MP4 whose index trails its media plays through FFmpeg's own HTTP client")
        void storedVideoPlaysThroughFFmpeg() throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            final boolean cache = WaterMediaConfig.codecs.ffmpeg.cache;
            // FILES BEYOND cacheMaxResponse SKIP THE PLAYER'S DOWNLOAD CACHE; TURNING IT OFF TAKES THAT PATH WITH THE 9 MB FIXTURE
            WaterMediaConfig.codecs.ffmpeg.cache = false;
            final Path stored = NetworkServerTest.this.directory.resolve("watermedia").resolve("files").resolve("Trailing1");
            Files.createDirectories(stored);
            Files.copy(Fixtures.MP4_H264, stored.resolve("clip.mp4"));
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            MediaPlayer player = null;
            try {
                final MRL mrl = PlayerAPI.mrl(URI.create("http://127.0.0.1:" + port() + "/Trailing1"));
                assertTrue(mrl.await(3_000L));
                player = PlayerAPI.player(mrl, () -> gfx, () -> null);
                assertTrue(player.start());
                // THE MOV DEMUXER SEEKS TO THE TRAILING moov AND BACK BEFORE THE FIRST FRAME, EACH SEEK ON A NEW REQUEST
                final MediaPlayer active = player;
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0 || active.error(), 15_000L));
                assertNull(player.exception(), "the stored file must open");
                assertTrue(gfx.uploadCount() > 0, "frames must reach the engine");
            } finally {
                if (player != null) player.release();
                WaterMediaConfig.codecs.ffmpeg.cache = cache;
            }
        }

        // UPLOADS A BODY AND RETURNS ITS ID
        private String store(final String filename, final byte[] body) throws IOException {
            final HttpURLConnection up = upload(TOKEN, filename, body);
            assertEquals(200, up.getResponseCode());
            return new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("Limits")
    class Limits {
        // ======================================================================
        // INTERNAL API
        // ======================================================================
        @BeforeEach
        void startRestricted() throws IOException {
            final var config = WaterMediaConfig.network;
            config.maxServerRequests = 2;
            config.serverTimeout = 1_000;
            config.maxStorageSize = 1;
            config.maxUploadSize = 1;
            NetworkServer.start(0, NetworkServerTest.this.directory);
        }

        @Test
        @DisplayName("The public default token and a blank one are refused, a bind failure propagates and the lock is released")
        void rejectsPublicDefaultTokenAndPropagatesBindFailure() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            NetworkServer.stop();
            WaterMediaConfig.network.serverBindAddress = "0.0.0.0";
            WaterMediaConfig.network.token = WaterMediaConfig.DEFAULT_NETWORK_TOKEN;
            assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
            assertNull(NetworkServer.address());
            WaterMediaConfig.network.serverBindAddress = "127.0.0.1";
            WaterMediaConfig.network.token = " ";
            assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
            WaterMediaConfig.network.token = TOKEN;
            try (final ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                assertThrows(IOException.class, () -> NetworkServer.start(occupied.getLocalPort(), directory));
                assertNull(NetworkServer.address());
            }
            try (final FileChannel contender = FileChannel.open(directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE);
                 final FileLock released = contender.tryLock()) {
                assertNotNull(released);
            }
            NetworkServer.start(0, directory);
            final InetSocketAddress address = NetworkServer.address();
            assertNotNull(address);
            assertTrue(address.getAddress().isLoopbackAddress());
        }

        @Test
        @DisplayName("A token changed in the config does not apply to the running server generation")
        void tokenSnapshotDoesNotChangeDuringARequestGeneration() throws Exception {
            WaterMediaConfig.network.token = "replacement";
            assertEquals(401, upload("replacement", "data.bin", new byte[1]).getResponseCode());
            assertEquals(200, upload(TOKEN, "data.bin", new byte[1]).getResponseCode());
        }

        @Test
        @DisplayName("Every unauthorized upload is refused, each server logs the first one and the secret never reaches the log")
        void warnsOnceOnUnauthorizedUploads() throws Exception {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                for (int request = 0; request < 12; request++) {
                    final HttpURLConnection connection = upload("untrusted-secret", "data.bin", new byte[1]);
                    try { assertEquals(401, connection.getResponseCode()); }
                    finally { connection.disconnect(); }
                }
                final var warnings = capture.events().stream()
                        .filter(event -> event.getMessage().getFormattedMessage().contains("unauthorized upload")).toList();
                assertEquals(1, warnings.size(), "a repeated refusal is logged once per server");
                assertEquals("NetworkServer", warnings.get(0).getMarker().getName());
                NetworkServer.stop();
                NetworkServer.start(0, NetworkServerTest.this.directory);
                final HttpURLConnection connection = upload("untrusted-secret", "data.bin", new byte[1]);
                try { assertEquals(401, connection.getResponseCode()); }
                finally { connection.disconnect(); }
                assertEquals(2, capture.events().stream()
                        .filter(event -> event.getMessage().getFormattedMessage().contains("unauthorized upload")).count(),
                        "a restarted server logs its own first refusal");
                assertTrue(capture.events().stream().noneMatch(event -> event.getMessage().getFormattedMessage().contains("untrusted-secret")));
            }
        }

        @Test
        @DisplayName("The storage budget counts the files that survived a restart")
        void totalStorageIncludesExistingFilesAfterRestart() throws Exception {
            final byte[] data = new byte[600_000];
            assertEquals(200, upload(TOKEN, "data.bin", data).getResponseCode());
            NetworkServer.stop();
            NetworkServer.start(0, NetworkServerTest.this.directory);
            try (final Socket rejected = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: next.bin\r\nContent-Length: 600000\r\n\r\n")) {
                assertTrue(new BufferedReader(new InputStreamReader(rejected.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("507"));
            }
        }

        @Test
        @DisplayName("Startup removes only the incomplete upload directories it recognizes")
        void startupRemovesOnlyIncompleteUploadDirectories() throws Exception {
            NetworkServer.stop();
            final Path storage = NetworkServerTest.this.directory.resolve("watermedia/files");
            final Path incomplete = Files.createDirectory(storage.resolve(".upload-AbC123zZ"));
            Files.write(incomplete.resolve("partial.bin"), new byte[800_000]);
            final Path empty = Files.createDirectory(storage.resolve(".upload-Empty123"));
            final Path unknown = Files.createDirectory(storage.resolve(".upload-short"));
            Files.writeString(unknown.resolve("keep.txt"), "keep");
            final Path multiple = Files.createDirectory(storage.resolve(".upload-Two12345"));
            Files.writeString(multiple.resolve("one.txt"), "one");
            Files.writeString(multiple.resolve("two.txt"), "two");
            final Path complete = Files.createDirectory(storage.resolve("Pub12345"));
            Files.writeString(complete.resolve("file.txt"), "saved");
            NetworkServer.start(0, NetworkServerTest.this.directory);
            assertFalse(Files.exists(incomplete));
            assertFalse(Files.exists(empty));
            assertEquals("keep", Files.readString(unknown.resolve("keep.txt")));
            assertEquals("one", Files.readString(multiple.resolve("one.txt")));
            assertEquals("two", Files.readString(multiple.resolve("two.txt")));
            assertEquals("saved", Files.readString(complete.resolve("file.txt")));
            assertEquals(200, upload(TOKEN, "data.bin", new byte[400_000]).getResponseCode());
        }

        @Test
        @DisplayName("Storage held by another server is neither taken over nor cleaned")
        void occupiedStorageCannotCleanAnotherServerUpload() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            NetworkServer.stop();
            final Path storage = directory.resolve("watermedia/files");
            final Path incomplete = Files.createDirectory(storage.resolve(".upload-Live1234"));
            final Path partial = Files.writeString(incomplete.resolve("partial.bin"), "still uploading");
            final Path lock = storage.resolve(".server.lock");
            try (final FileChannel owner = FileChannel.open(lock, StandardOpenOption.WRITE);
                 final FileLock held = owner.lock()) {
                assertThrows(IOException.class, () -> NetworkServer.start(0, directory));
                assertNull(NetworkServer.address());
                assertEquals("still uploading", Files.readString(partial));
            }

            NetworkServer.start(0, directory);
            assertFalse(Files.exists(incomplete));
            try (final FileChannel contender = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, contender::tryLock);
            }
        }

        @Test
        @DisplayName("A second process cannot clean a live upload of this server")
        void secondProcessCannotCleanLiveUpload() throws Exception {
            final Path storage = NetworkServerTest.this.directory.resolve("watermedia/files");
            final Path incomplete = Files.createDirectory(storage.resolve(".upload-Live1234"));
            final Path partial = Files.writeString(incomplete.resolve("partial.bin"), "still uploading");
            runProbe(NetworkServerTest.class, NetworkServerTest.this.directory, "storage-lock", false);
            assertEquals("still uploading", Files.readString(partial));
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        @Test
        @DisplayName("Startup cleanup never follows symbolic links out of the storage")
        void startupDoesNotFollowIncompleteUploadSymlinks() throws Exception {
            NetworkServer.stop();
            final Path storage = NetworkServerTest.this.directory.resolve("watermedia/files");
            final Path outside = Files.writeString(NetworkServerTest.this.directory.resolve("outside.txt"), "external");
            final Path linked = storage.resolve(".upload-Link1234");
            final Path child = Files.createDirectory(storage.resolve(".upload-Child123"));
            try {
                Files.createSymbolicLink(linked, outside);
                Files.createSymbolicLink(child.resolve("outside"), outside);
            } catch (final FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
                abort("Symbolic links unavailable: " + unavailable.getMessage());
            }

            NetworkServer.start(0, NetworkServerTest.this.directory);
            assertTrue(Files.exists(linked, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.exists(child.resolve("outside"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("external", Files.readString(outside));
        }

        @Test
        @DisplayName("Storage reservations bound concurrent uploads and a disconnect releases them")
        void reservationsBoundConcurrentUploadsAndDisconnectReleasesThem() throws Exception {
            try (final Socket stalled = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 700000\r\n\r\nx")) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500));
                try (final Socket rejected = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                        + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: other.bin\r\nContent-Length: 700000\r\n\r\n")) {
                    assertTrue(new BufferedReader(new InputStreamReader(rejected.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("507"));
                }
            }
            assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 0, 3_000));
            assertEquals(200, upload(TOKEN, "data.bin", new byte[700_000]).getResponseCode());
        }

        @Test
        @DisplayName("Requests past the slots are refused with 503 until a request finishes")
        void requestsPastTheSlotsAreRefusedUntilOneFinishes() throws Exception {
            // TWO UPLOADS THAT NEVER SEND THEIR BODY HOLD BOTH SLOTS INSIDE THE HANDLER
            final String stalled = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                    + "\r\nX-WaterMedia-Filename: slot.bin\r\nContent-Length: 10\r\n\r\n";
            try (final Socket first = request(stalled); final Socket second = request(stalled)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 2, 500));
                assertEquals(503, open("/", "GET").getResponseCode());
            }
            // THE CLOSED UPLOADS END THEIR HANDLERS, WHICH GIVES BOTH SLOTS BACK
            assertTrue(PlayerWait.awaitCondition(() -> {
                try { return open("/", "GET").getResponseCode() == 200; }
                catch (final IOException refused) { return false; }
            }, 3_000));
        }

        @Test
        @DisplayName("Slow headers hold a worker but no slot, and the deadline closes them")
        void slowHeadersHoldNoSlotAndTheDeadlineClosesThem() throws Exception {
            try (final Socket first = request("GET / HTTP/1.1\r\nHost: localhost");
                 final Socket second = request("GET / HTTP/1.1\r\nHost: localhost")) {
                final ThreadPoolExecutor executor = this.executor();
                assertTrue(PlayerWait.awaitCondition(() -> executor.getActiveCount() == 2, 500));
                // A REQUEST STILL WAITING FOR ITS HEADERS NEVER REACHED ADMISSION: A COMPLETE ONE IS SERVED ON A SPARE WORKER
                assertEquals(200, open("/", "GET").getResponseCode());
                assertEquals(0, executor.getQueue().size());
                this.assertDropped(first);
                this.assertDropped(second);
            }
        }

        @Test
        @DisplayName("Exchanges are capped at three per slot: a connection past them is dropped and admission has no queue")
        void exchangesAreCappedAtThreePerSlot() throws Exception {
            final Socket[] stalled = new Socket[6];
            try {
                for (int i = 0; i < stalled.length; i++) stalled[i] = request("GET / HTTP/1.1\r\nHost: localhost");
                final ThreadPoolExecutor executor = this.executor();
                assertTrue(PlayerWait.awaitCondition(() -> executor.getActiveCount() == 6, 500));
                try (final Socket rejected = request("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
                    this.assertDropped(rejected);
                }
                assertEquals(0, executor.getQueue().size());
                for (final Socket socket: stalled) this.assertDropped(socket);
            } finally {
                for (final Socket socket: stalled) if (socket != null) socket.close();
            }
            assertTrue(PlayerWait.awaitCondition(() -> {
                try { return open("/", "GET").getResponseCode() == 200; }
                catch (final IOException rejected) { return false; }
            }, 3_000));
        }

        @Test
        @DisplayName("Workers busy outside any exchange never refuse the next request")
        void busyWorkersOutsideAnExchangeNeverRefuseTheNextRequest() throws Exception {
            // TASKS SUBMITTED STRAIGHT TO THE POOL STAND IN FOR WORKERS STILL WINDING DOWN THEIR LAST RESPONSE
            final ThreadPoolExecutor executor = this.executor();
            final CountDownLatch entered = new CountDownLatch(6);
            final CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 6; i++) {
                    executor.execute(() -> {
                        entered.countDown();
                        try { release.await(); }
                        catch (final InterruptedException ignored) {}
                    });
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(200, open("/", "GET").getResponseCode());
            } finally {
                release.countDown();
            }
        }

        @Test
        @DisplayName("A trickled upload outlives the idle timeout as long as bytes keep arriving")
        void trickledUploadOutlivesTheIdleTimeout() throws Exception {
            // THREE CHUNKS 400 MS APART TAKE LONGER THAN THE 1 S TIMEOUT, BUT NEVER LEAVE THE REQUEST IDLE THAT LONG
            final byte[] chunk = new byte[1_000];
            try (final Socket slow = request("POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                    + "\r\nX-WaterMedia-Filename: trickle.bin\r\nContent-Length: 3000\r\n\r\n")) {
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(400);
                    slow.getOutputStream().write(chunk);
                    slow.getOutputStream().flush();
                }
                final String status = new BufferedReader(new InputStreamReader(slow.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertTrue(status.contains("200"), status);
            }
        }

        @Test
        @DisplayName("The deadline and stop() remove incomplete uploads")
        void deadlineAndStopRemoveIncompleteUploads() throws Exception {
            final String upload = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                    + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 1000\r\n\r\nx";
            try (final Socket stalled = request(upload)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500));
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 0, 3_000));
            }
            final int port = port();
            try (final Socket stalled = request(upload)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500));
                NetworkServer.stop();
                assertEquals(0, this.temporaries());
                assertNull(NetworkServer.address());
            }
            NetworkServer.stop();
            NetworkServer.start(port, NetworkServerTest.this.directory);
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        @Test
        @DisplayName("An incomplete shutdown keeps ownership until the workers actually exit")
        void incompleteShutdownKeepsOwnershipUntilWorkersActuallyExit() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            final Field running = NetworkServer.class.getDeclaredField("running");
            running.setAccessible(true);
            final Object state = running.get(null);
            final ThreadPoolExecutor executor = this.executor();
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch finish = new CountDownLatch(1);
            executor.execute(() -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { finish.await(); done = true; }
                    catch (final InterruptedException ignored) {}
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, NetworkServer::stop);
                assertSame(state, running.get(null));
                assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
                try (final FileChannel contender = FileChannel.open(directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE)) {
                    assertThrows(OverlappingFileLockException.class, contender::tryLock);
                }
            } finally {
                finish.countDown();
                NetworkServer.stop();
            }
            try (final FileChannel contender = FileChannel.open(directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE);
                 final FileLock released = contender.tryLock()) {
                assertNotNull(released);
            }
            NetworkServer.start(0, directory);
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        // ======================================================================
        // HELPERS
        // ======================================================================
        private long temporaries() {
            try (final var paths = Files.list(NetworkServerTest.this.directory.resolve("watermedia/files"))) {
                return paths.filter(path -> path.getFileName().toString().startsWith(".upload-")).count();
            } catch (final IOException e) { throw new IllegalStateException(e); }
        }

        // THE RUNNING SERVER'S WORKER POOL, REACHED THROUGH ITS PRIVATE STATE
        private ThreadPoolExecutor executor() throws ReflectiveOperationException {
            final Field running = NetworkServer.class.getDeclaredField("running");
            running.setAccessible(true);
            final Object state = running.get(null);
            final Field workers = state.getClass().getDeclaredField("executor");
            workers.setAccessible(true);
            return (ThreadPoolExecutor) workers.get(state);
        }

        // A DROPPED CONNECTION EITHER READS EOF OR IS RESET BY THE SERVER
        private void assertDropped(final Socket socket) throws IOException {
            try { assertEquals(-1, socket.getInputStream().read()); }
            catch (final SocketException reset) {
                // A CONNECTION THE SERVER RESET IS DROPPED TOO
            }
        }
    }
}
