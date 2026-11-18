package org.watermedia.api.network;

import org.apache.logging.log4j.core.LogEvent;
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
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * Verifies {@link NetworkServer} over real HTTP on loopback: uploads and their filename guard, ID downloads (GET and
 * HEAD) with their ranges, the root answer and the playback of a stored video through FFmpeg's own HTTP client; its
 * limits: token refusals and snapshots, the once-per-server rejection warning, storage accounting and ownership, the
 * startup sweep of incomplete uploads, request slots and the exchange cap, the idle timeout and an incomplete
 * shutdown; and the startup check of a fixed port, which must be free and reached through {@code remoteUrl}, giving
 * the port and the storage back on any failure.
 */
@DisplayName("NetworkServer")
public class NetworkServerTest {
    private static final String TOKEN = "local-test-upload-token";
    @TempDir Path directory;

    // THE network.* KEYS ARE PROCESS-WIDE CONFIG: EVERY CASE PUTS THEM BACK
    private String previousBind;
    private String previousRemote;
    private String previousToken;
    private int previousRequests;
    private int previousTimeout;
    private int previousStorage;
    private int previousUpload;

    // ==========================================================================
    // INTERNAL API
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
            if (!occupied.getMessage().contains("storage already in use")) throw occupied;
        }
        if (!Files.exists(directory.resolve("watermedia/files/.upload-Live1234/partial.bin")))
            throw new AssertionError("The competing process deleted a live upload");
        System.out.println("PROBE_OK storage-lock");
    }

    @BeforeEach
    void snapshotConfig() {
        final var config = WaterMediaConfig.network;
        this.previousBind = config.serverBindAddress;
        this.previousRemote = config.remoteUrl;
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
        config.remoteUrl = this.previousRemote;
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
        assertNotNull(address, "the file server is running");
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

    // A RAW REQUEST, FOR HEADERS AND BODIES THAT STALL OR NEVER END
    private static Socket request(final String request) throws IOException {
        final Socket socket = new Socket("127.0.0.1", port());
        socket.setSoTimeout(4_000);
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    // THE STATUS CODE THAT ANSWERS A RAW REQUEST, OR -1 WHEN THE SERVER CLOSED WITHOUT ONE
    private static int statusOf(final Socket socket) throws IOException {
        final String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        return line == null ? -1 : Integer.parseInt(line.split(" ", 3)[1]);
    }

    // THE PORT BINDS AGAIN ONLY ONCE THE SERVER CLOSED ITS LISTENER
    private static void assertPortReleased(final int port) {
        assertDoesNotThrow(() -> new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1")).close(), "the port was given back");
    }

    // A CONTENDER TAKES THE STORAGE LOCK ONLY ONCE THE SERVER GAVE IT BACK; WHILE THIS JVM HOLDS IT, tryLock THROWS
    private static void assertStorageReleased(final Path directory) throws IOException {
        try (final FileChannel contender = FileChannel.open(directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE)) {
            assertNotNull(assertDoesNotThrow(() -> contender.tryLock(), "the storage lock was given back"), "the storage lock was given back");
        }
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
        @DisplayName("An upload returns an 8-character lowercase ID that GET serves back and HEAD sizes")
        void uploadedFileIsServedById() throws IOException {
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
        @DisplayName("An upload with a traversal filename is refused with 400")
        void traversalFilenameIsRefused() throws IOException {
            assertEquals(400, upload(TOKEN, "../evil", "x".getBytes(StandardCharsets.UTF_8)).getResponseCode());
        }

        @Test
        @DisplayName("Ranges clip at EOF and a suffix serves the tail, unsatisfiable ones and ranges of an empty file answer 416, malformed or multipart ones are ignored and HEAD honours them")
        void clipsRangesAtEofAndHandlesEmptyResources() throws IOException {
            final String id = this.store("bounds.bin", "payload".getBytes(StandardCharsets.UTF_8));
            for (final String range: new String[] { "bytes=0-999", "bytes=-999", "bytes=0-", "bytes=0-999999999999999999999999999" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(206, request.getResponseCode(), range);
                assertEquals("bytes 0-6/7", request.getHeaderField("Content-Range"), range);
                assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8), range);
            }
            for (final String range: new String[] { "bytes=2-", "BYTES=2-6" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(206, request.getResponseCode(), range);
                assertEquals("bytes 2-6/7", request.getHeaderField("Content-Range"), range);
                assertEquals("yload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8), range);
            }
            for (final String range: new String[] { "bytes=7-", "bytes=-0", "bytes=99999999999999999999-" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(416, request.getResponseCode(), range);
                assertEquals("bytes */7", request.getHeaderField("Content-Range"), range);
            }
            for (final String range: new String[] { "bytes=abc-", "bytes=5-3", "bytes=-", "bytes=+1-2", "bytes=0-1,3-4" }) {
                final HttpURLConnection request = open("/" + id, "GET");
                request.setRequestProperty("Range", range);
                assertEquals(200, request.getResponseCode(), range);
                assertNull(request.getHeaderField("Content-Range"), range);
                assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8), range);
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
        @DisplayName("GET / answers the server name alone, without its version, and an unknown ID is 404")
        void rootNamesTheServerAndAnUnknownIdIsMissing() throws IOException {
            final HttpURLConnection root = open("/", "GET");
            assertEquals(200, root.getResponseCode());
            assertEquals(WaterMedia.NAME, new String(root.getInputStream().readAllBytes(), StandardCharsets.UTF_8), "an unauthenticated answer carries the name and never the version");

            assertEquals(404, open("/zzzzzzzz", "GET").getResponseCode());
        }

        @Test
        @DisplayName("A stored MP4 whose index trails its media plays through FFmpeg's own HTTP client")
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
                assertTrue(mrl.await(3_000L), "the stored file resolved");
                player = PlayerAPI.player(mrl, () -> gfx, () -> null);
                assertTrue(player.start(), "the player started");
                // THE MOV DEMUXER SEEKS TO THE TRAILING moov AND BACK BEFORE THE FIRST FRAME, EACH SEEK ON A NEW REQUEST
                final MediaPlayer active = player;
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0 || active.error(), 15_000L), "the player uploaded a frame or failed");
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

        // TWO SLOTS, A 1 S IDLE TIMEOUT AND 1 MIB FOR BOTH THE UPLOAD AND THE STORAGE BUDGET
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
        @DisplayName("The public default token off loopback and a blank token are refused before the server starts")
        void refusesPublicDefaultAndBlankTokens() {
            final Path directory = NetworkServerTest.this.directory;
            NetworkServer.stop();
            WaterMediaConfig.network.serverBindAddress = "0.0.0.0";
            WaterMediaConfig.network.token = WaterMediaConfig.DEFAULT_NETWORK_TOKEN;
            final IllegalStateException publicDefault = assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
            assertEquals("(wm_err:0724) Failed to start the file server outside loopback due to default network.token, set your own!", publicDefault.getMessage());
            assertNull(NetworkServer.address(), "nothing is running");
            WaterMediaConfig.network.serverBindAddress = "127.0.0.1";
            WaterMediaConfig.network.token = " ";
            final IllegalStateException blank = assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
            assertEquals("(wm_err:0723) Failed to start the file server due to missing network.token", blank.getMessage());
            assertNull(NetworkServer.address(), "nothing is running");
        }

        @Test
        @DisplayName("A running server keeps the token it started with when the config changes")
        void runningServerKeepsItsToken() throws Exception {
            WaterMediaConfig.network.token = "replacement";
            assertEquals(401, upload("replacement", "data.bin", new byte[1]).getResponseCode(), "the token of the config is refused");
            assertEquals(200, upload(TOKEN, "data.bin", new byte[1]).getResponseCode(), "the token of the start is accepted");
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
                final List<LogEvent> warnings = capture.events().stream()
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
                assertTrue(capture.events().stream().noneMatch(event -> event.getMessage().getFormattedMessage().contains("untrusted-secret")),
                        "the refused token never reaches the log");
            }
        }

        @Test
        @DisplayName("The storage budget counts the files that survived a restart")
        void totalStorageIncludesExistingFilesAfterRestart() throws Exception {
            // THE 1 MIB BUDGET HOLDS ONE 600 KB FILE BUT NOT TWO
            assertEquals(200, upload(TOKEN, "data.bin", new byte[600_000]).getResponseCode());
            NetworkServer.stop();
            NetworkServer.start(0, NetworkServerTest.this.directory);
            try (final Socket rejected = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: next.bin\r\nContent-Length: 600000\r\n\r\n")) {
                assertEquals(507, statusOf(rejected), "the stored file still counts");
            }
        }

        @Test
        @DisplayName("Startup removes only the incomplete upload directories it recognizes, and their bytes leave the budget")
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
            assertFalse(Files.exists(incomplete), "a temporary with one file is removed");
            assertFalse(Files.exists(empty), "an empty temporary is removed");
            assertEquals("keep", Files.readString(unknown.resolve("keep.txt")));
            assertEquals("one", Files.readString(multiple.resolve("one.txt")));
            assertEquals("two", Files.readString(multiple.resolve("two.txt")));
            assertEquals("saved", Files.readString(complete.resolve("file.txt")));
            // 400 KB MORE FITS THE 1 MIB BUDGET ONLY WITHOUT THE REMOVED 800 KB PARTIAL
            assertEquals(200, upload(TOKEN, "data.bin", new byte[400_000]).getResponseCode());
        }

        @Test
        @DisplayName("Storage held by another server is neither taken over nor cleaned, and is taken and swept once free")
        void occupiedStorageCannotCleanAnotherServerUpload() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            NetworkServer.stop();
            final Path storage = directory.resolve("watermedia/files");
            final Path incomplete = Files.createDirectory(storage.resolve(".upload-Live1234"));
            final Path partial = Files.writeString(incomplete.resolve("partial.bin"), "still uploading");
            final Path lock = storage.resolve(".server.lock");
            try (final FileChannel owner = FileChannel.open(lock, StandardOpenOption.WRITE);
                 final FileLock held = owner.lock()) {
                final IOException occupied = assertThrows(IOException.class, () -> NetworkServer.start(0, directory));
                assertTrue(occupied.getMessage().startsWith("(wm_err:0727) Failed to start the file server due to storage already in use: "), occupied.getMessage());
                assertNull(NetworkServer.address(), "nothing is running");
                assertEquals("still uploading", Files.readString(partial));
            }

            NetworkServer.start(0, directory);
            assertFalse(Files.exists(incomplete), "the free storage was swept");
            try (final FileChannel contender = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, contender::tryLock, "the server holds the storage");
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
            assertEquals(200, open("/", "GET").getResponseCode(), "this server keeps serving");
        }

        @Test
        @DisplayName("Startup cleanup never follows symbolic links out of the storage")
        void startupDoesNotFollowIncompleteUploadSymlinks() throws Exception {
            NetworkServer.stop();
            final Path storage = NetworkServerTest.this.directory.resolve("watermedia/files");
            // THE OUTSIDE FOLDER HOLDS ONE REGULAR FILE LIKE A REAL TEMPORARY: ONLY NOFOLLOW_LINKS KEEPS THE SWEEP OUT OF IT
            final Path outside = Files.createDirectory(NetworkServerTest.this.directory.resolve("outside"));
            final Path kept = Files.writeString(outside.resolve("kept.txt"), "external");
            final Path linked = storage.resolve(".upload-Link1234");
            final Path child = Files.createDirectory(storage.resolve(".upload-Child123"));
            try {
                Files.createSymbolicLink(linked, outside);
                Files.createSymbolicLink(child.resolve("outside"), kept);
            } catch (final FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
                abort("Symbolic links unavailable: " + unavailable.getMessage());
            }

            NetworkServer.start(0, NetworkServerTest.this.directory);
            assertTrue(Files.exists(linked, LinkOption.NOFOLLOW_LINKS), "a linked temporary is kept");
            assertTrue(Files.exists(child.resolve("outside"), LinkOption.NOFOLLOW_LINKS), "a temporary holding a link is kept");
            assertEquals("external", Files.readString(kept), "the file outside the storage survives");
        }

        @Test
        @DisplayName("Storage reservations bound concurrent uploads and a disconnect releases them")
        void reservationsBoundConcurrentUploadsAndDisconnectReleasesThem() throws Exception {
            try (final Socket stalled = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 700000\r\n\r\nx")) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500), "the stalled upload reserved its bytes");
                try (final Socket rejected = request("POST /upload HTTP/1.1\r\nHost: localhost\r\n"
                        + "X-WaterMedia-Token: " + TOKEN + "\r\nX-WaterMedia-Filename: other.bin\r\nContent-Length: 700000\r\n\r\n")) {
                    assertEquals(507, statusOf(rejected), "the reservation counts against the budget");
                }
            }
            assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 0, 3_000), "the disconnect removed the temporary");
            assertEquals(200, upload(TOKEN, "data.bin", new byte[700_000]).getResponseCode(), "the released reservation fits again");
        }

        @Test
        @DisplayName("Requests past the slots are refused with 503 until a request finishes")
        void requestsPastTheSlotsAreRefusedUntilOneFinishes() throws Exception {
            // TWO UPLOADS THAT NEVER SEND THEIR BODY HOLD BOTH SLOTS INSIDE THE HANDLER
            final String stalled = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                    + "\r\nX-WaterMedia-Filename: slot.bin\r\nContent-Length: 10\r\n\r\n";
            try (final Socket first = request(stalled); final Socket second = request(stalled)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 2, 500), "both uploads hold a slot");
                assertEquals(503, open("/", "GET").getResponseCode());
            }
            // THE CLOSED UPLOADS END THEIR HANDLERS, WHICH GIVES BOTH SLOTS BACK
            assertTrue(PlayerWait.awaitCondition(() -> {
                try { return open("/", "GET").getResponseCode() == 200; }
                catch (final IOException refused) { return false; }
            }, 3_000), "a slot was given back");
        }

        @Test
        @DisplayName("Slow headers hold a worker but no slot, and the deadline closes them")
        void slowHeadersHoldNoSlotAndTheDeadlineClosesThem() throws Exception {
            try (final Socket first = request("GET / HTTP/1.1\r\nHost: localhost");
                 final Socket second = request("GET / HTTP/1.1\r\nHost: localhost")) {
                final ThreadPoolExecutor executor = this.executor();
                assertTrue(PlayerWait.awaitCondition(() -> executor.getActiveCount() == 2, 500), "both header reads hold a worker");
                // A REQUEST STILL WAITING FOR ITS HEADERS NEVER REACHED ADMISSION: A COMPLETE ONE IS SERVED ON A SPARE WORKER
                assertEquals(200, open("/", "GET").getResponseCode(), "no slot is taken by the slow headers");
                this.assertDropped(first);
                this.assertDropped(second);
            }
        }

        @Test
        @DisplayName("Exchanges are capped at three per slot: the connection past them is dropped instead of queued")
        void exchangesAreCappedAtThreePerSlot() throws Exception {
            // THREE EXCHANGES FOR EACH OF THE TWO SLOTS
            final Socket[] stalled = new Socket[6];
            try {
                for (int i = 0; i < stalled.length; i++) stalled[i] = request("GET / HTTP/1.1\r\nHost: localhost");
                final ThreadPoolExecutor executor = this.executor();
                assertTrue(PlayerWait.awaitCondition(() -> executor.getActiveCount() == 6, 500), "the six header reads hold a worker each");
                try (final Socket rejected = request("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")) {
                    this.assertDropped(rejected);
                }
                for (final Socket socket: stalled) this.assertDropped(socket);
            } finally {
                for (final Socket socket: stalled) if (socket != null) socket.close();
            }
            assertTrue(PlayerWait.awaitCondition(() -> {
                try { return open("/", "GET").getResponseCode() == 200; }
                catch (final IOException rejected) { return false; }
            }, 3_000), "the server accepts again once the exchanges ended");
        }

        @Test
        @DisplayName("Workers busy outside any exchange never refuse the next request")
        void busyWorkersOutsideAnExchangeNeverRefuseTheNextRequest() throws Exception {
            // TASKS SUBMITTED STRAIGHT TO THE POOL STAND IN FOR WORKERS STILL WINDING DOWN THEIR LAST RESPONSE; SIX OF THEM
            // EXCEED THE TWO CORE THREADS AND MATCH THE EXCHANGE CAP, YET THEY HOLD NO EXCHANGE
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
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the six tasks hold a worker each");
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
                assertEquals(200, statusOf(slow), "the upload was stored");
            }
        }

        @Test
        @DisplayName("The deadline and stop() remove incomplete uploads, and a stopped server gives its port back")
        void deadlineAndStopRemoveIncompleteUploads() throws Exception {
            final String upload = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-WaterMedia-Token: " + TOKEN
                    + "\r\nX-WaterMedia-Filename: partial.bin\r\nContent-Length: 1000\r\n\r\nx";
            try (final Socket stalled = request(upload)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500), "the stalled upload created its temporary");
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() == 0, 3_000), "the deadline removed the temporary");
            }
            final int port = port();
            try (final Socket stalled = request(upload)) {
                assertTrue(PlayerWait.awaitCondition(() -> this.temporaries() > 0, 500), "the stalled upload created its temporary");
                NetworkServer.stop();
                assertEquals(0, this.temporaries(), "stop() removed the temporary");
                assertNull(NetworkServer.address(), "nothing is running");
            }
            NetworkServer.stop();
            // A FIXED PORT IS VALIDATED THROUGH THE ANNOUNCED ADDRESS
            WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
            NetworkServer.start(port, NetworkServerTest.this.directory);
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        @Test
        @DisplayName("An incomplete shutdown keeps ownership until the workers actually exit")
        void incompleteShutdownKeepsOwnershipUntilWorkersActuallyExit() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            final Object state = this.running();
            final ThreadPoolExecutor executor = this.executor();
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch finish = new CountDownLatch(1);
            // A WORKER THAT IGNORES THE INTERRUPT OF stop() OUTLASTS ITS 5 S WAIT
            executor.execute(() -> {
                entered.countDown();
                boolean done = false;
                while (!done) {
                    try { finish.await(); done = true; }
                    catch (final InterruptedException ignored) {}
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the task holds a worker");
                final IllegalStateException busy = assertThrows(IllegalStateException.class, NetworkServer::stop);
                assertEquals("(wm_err:0720) Failed to stop the file server due to active file uploads, try again!", busy.getMessage());
                assertSame(state, this.running(), "the server state outlives the failed stop");
                final IllegalStateException restart = assertThrows(IllegalStateException.class, () -> NetworkServer.start(0, directory));
                assertEquals("(wm_err:0721) Failed to start the file server, it is already running", restart.getMessage());
                try (final FileChannel contender = FileChannel.open(directory.resolve("watermedia/files/.server.lock"), StandardOpenOption.WRITE)) {
                    assertThrows(OverlappingFileLockException.class, contender::tryLock, "the server still holds the storage");
                }
            } finally {
                finish.countDown();
                NetworkServer.stop();
            }
            assertStorageReleased(directory);
            NetworkServer.start(0, directory);
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        // THE UPLOAD TEMPORARIES IN THE STORAGE, ONE PER UPLOAD IN FLIGHT
        private long temporaries() {
            try (final var paths = Files.list(NetworkServerTest.this.directory.resolve("watermedia/files"))) {
                return paths.filter(path -> path.getFileName().toString().startsWith(".upload-")).count();
            } catch (final IOException e) { throw new UncheckedIOException(e); }
        }

        // THE RUNNING SERVER'S PRIVATE STATE
        private Object running() throws ReflectiveOperationException {
            final Field running = NetworkServer.class.getDeclaredField("running");
            running.setAccessible(true);
            return running.get(null);
        }

        // THE RUNNING SERVER'S WORKER POOL, REACHED THROUGH ITS PRIVATE STATE
        private ThreadPoolExecutor executor() throws ReflectiveOperationException {
            final Object state = this.running();
            final Field workers = state.getClass().getDeclaredField("executor");
            workers.setAccessible(true);
            return (ThreadPoolExecutor) workers.get(state);
        }

        // A DROPPED CONNECTION EITHER READS EOF OR IS RESET BY THE SERVER
        private void assertDropped(final Socket socket) throws IOException {
            try { assertEquals(-1, socket.getInputStream().read(), "the server dropped the connection"); }
            catch (final SocketException reset) {}
        }
    }

    @Nested
    @DisplayName("Port")
    class Port {

        @Test
        @DisplayName("A busy port fails the start before the storage is touched")
        void busyPortFailsBeforeTheStorage() throws Exception {
            final Path fresh = NetworkServerTest.this.directory.resolve("fresh");
            try (final ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                final IOException closed = assertThrows(IOException.class, () -> NetworkServer.start(occupied.getLocalPort(), fresh));
                assertTrue(closed.getMessage().startsWith("(wm_err:0726) Failed to start the file server due to closed port " + occupied.getLocalPort()), closed.getMessage());
            }
            assertNull(NetworkServer.address(), "nothing is running");
            assertFalse(Files.exists(fresh), "the storage was never created");
        }

        @Test
        @DisplayName("A port its remoteUrl reaches starts the server")
        void reachablePortStarts() throws Exception {
            final int port = LocalHttp.freePort();
            WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
            NetworkServer.start(port, NetworkServerTest.this.directory);
            assertEquals(port, port());
            assertEquals(200, open("/", "GET").getResponseCode());
        }

        @Test
        @DisplayName("A remoteUrl nothing answers fails the start and gives the port and the storage back")
        void unreachableRemoteFailsAndReleases() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            final int port = LocalHttp.freePort();
            int silent = LocalHttp.freePort();
            while (silent == port) silent = LocalHttp.freePort();
            WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + silent + "/";
            final IOException closed = assertThrows(IOException.class, () -> NetworkServer.start(port, directory));
            assertEquals("(wm_err:0729) Failed to start the file server due to closed port " + port + ", http://127.0.0.1:" + silent + " does not reach it", closed.getMessage());
            assertNull(NetworkServer.address(), "nothing is running");
            assertPortReleased(port);
            assertStorageReleased(directory);
            WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
            NetworkServer.start(port, directory);
            assertEquals(port, port());
        }

        @Test
        @DisplayName("A remoteUrl answered by another service fails the start and gives the port and the storage back")
        void foreignAnswerFails() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            final int port;
            try (final LocalHttp other = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", "nginx".getBytes(StandardCharsets.UTF_8), 0))) {
                // ASKED ONCE THE OTHER SERVICE LISTENS, SO THE FREE PORT CANNOT BE ITS PORT
                port = LocalHttp.freePort();
                WaterMediaConfig.network.remoteUrl = other.uri("/").toString();
                final IOException closed = assertThrows(IOException.class, () -> NetworkServer.start(port, directory));
                assertEquals("(wm_err:0729) Failed to start the file server due to closed port " + port + ", " + other.uri("") + " does not reach it", closed.getMessage());
            }
            assertNull(NetworkServer.address(), "nothing is running");
            assertPortReleased(port);
            assertStorageReleased(directory);
        }

        @Test
        @DisplayName("A remoteUrl that is no http(s) address fails the start with its coded refusal and gives the port and the storage back")
        void invalidRemoteFails() throws Exception {
            final Path directory = NetworkServerTest.this.directory;
            final int port = LocalHttp.freePort();
            WaterMediaConfig.network.remoteUrl = "remote.invalid/upload";
            final IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> NetworkServer.start(port, directory));
            assertEquals("(wm_err:0706) Failed to read network.remoteUrl, it is not an absolute http(s) URL: remote.invalid/upload", invalid.getMessage());
            assertNull(NetworkServer.address(), "nothing is running");
            assertPortReleased(port);
            assertStorageReleased(directory);
        }
    }
}
