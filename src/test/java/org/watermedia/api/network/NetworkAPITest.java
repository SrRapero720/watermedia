package org.watermedia.api.network;

import me.srrapero720.waterconfig.WaterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.WaterMediaConfig.Network.Server;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies {@link NetworkAPI}: the {@code network.server} gate of {@code enable}, the root line {@code answers}
 * accepts, {@code serverEnabled()} answering true at once on the host that started the file server and, elsewhere,
 * false until {@code network.remoteUrl} answers (asking once per pause, warning about the first miss and keeping the
 * first true answer), and the coded message an upload fails with when the server is off, silent or its address is wrong.
 */
@DisplayName("NetworkAPI")
public class NetworkAPITest {
    private static final byte[] NAME = WaterMedia.NAME.getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "nginx".getBytes(StandardCharsets.UTF_8);
    private static final String INVALID_REMOTE = "remote.invalid/upload";
    private static final String INVALID_REMOTE_ERROR = "(wm_err:0706) Failed to read network.remoteUrl, it is not an absolute http(s) URL: " + INVALID_REMOTE;
    private static final long WAIT_MS = 15_000L;

    // THE REMOTE ADDRESS AND THE UPLOAD TIMEOUT ARE PROCESS-WIDE CONFIG: EVERY CASE PUTS THEM BACK
    private String previousRemote;
    private int previousTimeout;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // CHILD-JVM PROBE: THE MODULE STARTS ONCE PER PROCESS, SO THE HOST OF THE FILE SERVER GETS ITS OWN JVM
    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("host")) throw new IllegalArgumentException("Unknown file server probe");
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        final int port = LocalHttp.freePort();
        WaterMediaConfig.network.server = Server.DEDICATED;
        WaterMediaConfig.network.serverPort = port;
        WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
        WaterMedia.start("HOST_TEST", root.resolve("temp"), root, false);
        if (NetworkServer.address() == null) throw new AssertionError("The file server did not start");
        if (!NetworkAPI.serverEnabled()) throw new AssertionError("The host of the file server did not report it up");
        // ITS DISPATCHER IS NOT A DAEMON THREAD AND WOULD KEEP THIS JVM ALIVE
        NetworkServer.stop();
        System.out.println("PROBE_OK host");
    }

    @BeforeAll
    static void bootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void snapshot() throws ReflectiveOperationException {
        this.previousRemote = WaterMediaConfig.network.remoteUrl;
        this.previousTimeout = WaterMediaConfig.network.timeout;
        forget();
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        WaterMediaConfig.network.remoteUrl = this.previousRemote;
        WaterMediaConfig.network.timeout = this.previousTimeout;
        forget();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Field field(final String name) throws ReflectiveOperationException {
        final Field field = NetworkAPI.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static AtomicBoolean probing() throws ReflectiveOperationException { return (AtomicBoolean) field("PROBING").get(null); }

    // THE NEXT serverEnabled() CALL ASKS AT ONCE INSTEAD OF WAITING OUT THE 5 S PAUSE
    private static void endPause() throws ReflectiveOperationException { field("probeAt").setLong(null, System.nanoTime()); }

    // CLEARS WHAT serverEnabled() LEARNED AND ENDS ITS PAUSE, ONCE THE REQUEST IN FLIGHT IS OVER
    private static void forget() throws ReflectiveOperationException {
        final AtomicBoolean probing = probing();
        assertTrue(PlayerWait.awaitCondition(() -> !probing.get(), WAIT_MS), "the request in flight ended");
        field("serverUp").setBoolean(null, false);
        field("probeWarned").setBoolean(null, false);
        endPause();
    }

    // THE WARNINGS serverEnabled() LOGGED ABOUT A REMOTE SERVER IT DID NOT FIND
    private static List<String> warnings(final LogCapture capture) {
        return capture.events().stream().map(event -> event.getMessage().getFormattedMessage())
                .filter(message -> message.startsWith("Could not reach the remote file server at ")).toList();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("enable")
    class Enable {

        @Test
        @DisplayName("OFF starts the file server nowhere")
        void offNeverStarts() {
            assertFalse(NetworkAPI.enable(true, Server.OFF), "not on a client");
            assertFalse(NetworkAPI.enable(false, Server.OFF), "not on a dedicated host");
        }

        @Test
        @DisplayName("DEDICATED starts the file server only on hosts without a client")
        void dedicatedNeedsNoClient() {
            assertFalse(NetworkAPI.enable(true, Server.DEDICATED), "not on a client");
            assertTrue(NetworkAPI.enable(false, Server.DEDICATED), "on a dedicated host");
        }

        @Test
        @DisplayName("ALWAYS starts the file server on clients and dedicated hosts alike")
        void alwaysStartsEverywhere() {
            assertTrue(NetworkAPI.enable(true, Server.ALWAYS), "on a client");
            assertTrue(NetworkAPI.enable(false, Server.ALWAYS), "on a dedicated host");
        }
    }

    @Nested
    @DisplayName("answers")
    class Answers {

        @Test
        @DisplayName("The project name at the root answers true")
        void projectNameAnswersTrue() throws Exception {
            try (final LocalHttp server = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", NAME, 0))) {
                assertTrue(NetworkAPI.answers(server.uri("").toString()));
            }
        }

        @Test
        @DisplayName("Another service at the root answers false")
        void anotherServiceAnswersFalse() throws Exception {
            try (final LocalHttp server = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", OTHER, 0))) {
                assertFalse(NetworkAPI.answers(server.uri("").toString()));
            }
        }

        @Test
        @DisplayName("An error status answers false, whatever its body says")
        void errorStatusAnswersFalse() throws Exception {
            try (final LocalHttp server = LocalHttp.start("/", exchange -> {
                exchange.sendResponseHeaders(503, NAME.length);
                exchange.getResponseBody().write(NAME);
                exchange.close();
            })) {
                assertFalse(NetworkAPI.answers(server.uri("").toString()));
            }
        }

        @Test
        @DisplayName("A port nobody listens on throws")
        void silentPortThrows() {
            final int port = LocalHttp.freePort();
            assertThrows(IOException.class, () -> NetworkAPI.answers("http://127.0.0.1:" + port));
        }
    }

    @Nested
    @DisplayName("serverEnabled")
    class ServerEnabled {

        @Test
        @DisplayName("The host that started the file server answers true at once")
        void hostAnswersTrueAtOnce(@TempDir final Path directory) throws Exception {
            runProbe(NetworkAPITest.class, directory, "host", true);
        }

        @Test
        @DisplayName("Answers false until the remote server replies, then true for good, without a warning")
        void falseUntilTheServerReplies() {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID);
                 final LocalHttp server = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", NAME, 0))) {
                WaterMediaConfig.network.remoteUrl = server.uri("/").toString();
                assertFalse(NetworkAPI.serverEnabled(), "the first call only sends the request");
                assertTrue(PlayerWait.awaitCondition(NetworkAPI::serverEnabled, WAIT_MS), "the remote server was found");
                assertEquals(List.of(), warnings(capture));
            }
            // THE SERVER IS GONE AND THE ANSWER STAYS
            assertTrue(NetworkAPI.serverEnabled(), "a true answer is kept");
        }

        @Test
        @DisplayName("A remoteUrl where another service answers is asked once per pause, warned about once and found once WaterMedia answers")
        void otherServiceIsAskedOncePerPause() throws Exception {
            final AtomicInteger requests = new AtomicInteger();
            final AtomicBoolean probing = probing();
            // THE FIRST TWO REQUESTS MEET ANOTHER SERVICE, THE THIRD ONE WATERMEDIA
            try (final LogCapture capture = new LogCapture(WaterMedia.ID);
                 final LocalHttp server = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", requests.incrementAndGet() < 3 ? OTHER : NAME, 0))) {
                final String remote = server.uri("/").toString();
                WaterMediaConfig.network.remoteUrl = remote;
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> requests.get() == 1 && !probing.get(), WAIT_MS), "the first request was answered");

                // THE PAUSE HOLDS HOWEVER OFTEN THE CALLER POLLS
                for (int call = 0; call < 100; call++) assertFalse(NetworkAPI.serverEnabled());
                assertFalse(probing.get(), "no request is in flight during the pause");
                assertEquals(1, requests.get(), "no request was sent during the pause");

                // THE PAUSE IS OVER: THE NEXT CALL ASKS AGAIN, AND ONLY THE FIRST MISS WAS WARNED ABOUT
                endPause();
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> requests.get() == 2 && !probing.get(), WAIT_MS), "the second request was answered");
                assertEquals(List.of("Could not reach the remote file server at " + remote + ": another service answers there"), warnings(capture));

                endPause();
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(NetworkAPI::serverEnabled, WAIT_MS), "the third request found WaterMedia");
                assertEquals(3, requests.get());
            }
        }

        @Test
        @DisplayName("A remoteUrl that is no http(s) address stays false and is refused with its code before any request")
        void invalidRemoteStaysFalse() throws Exception {
            final AtomicBoolean probing = probing();
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                WaterMediaConfig.network.remoteUrl = INVALID_REMOTE;
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> !warnings(capture).isEmpty() && !probing.get(), WAIT_MS), "the refusal was warned about");
                // THE CODE IS THE ONE OF remoteUrl(), WHICH THROWS BEFORE ANY REQUEST IS BUILT
                assertEquals(List.of("Could not reach the remote file server at " + INVALID_REMOTE + ": " + INVALID_REMOTE_ERROR), warnings(capture));
                assertFalse(NetworkAPI.serverEnabled());
            }
        }
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("A file server that is off fails the upload naming its address instead of a raw exception")
        void unreachableServerNamesItsAddress(@TempDir final Path directory) throws Exception {
            final int port = LocalHttp.freePort();
            WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
            final Path file = Files.write(directory.resolve("picture.png"), new byte[] { 1, 2, 3 });

            final NetworkAPI.UploadStatus status = NetworkAPI.upload(file.toFile());
            assertTrue(PlayerWait.awaitCondition(status::failed, WAIT_MS), "the upload failed");
            assertEquals("(wm_err:0714) Failed to upload, the file server at http://127.0.0.1:" + port + " is off or unreachable", status.error());
        }

        @Test
        @DisplayName("A file server that takes the file and never answers fails the upload as silent, not as off")
        void silentServerIsNotCalledOff(@TempDir final Path directory) throws Exception {
            // ACCEPTS THE CONNECTION AND NEVER READS NOR ANSWERS: THE SMALL FILE FITS THE SOCKET BUFFERS, SO ONLY THE ANSWER TIMES OUT
            try (final ServerSocket silent = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
                WaterMediaConfig.network.timeout = 1_000;
                WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + silent.getLocalPort() + "/";
                final Path file = Files.write(directory.resolve("picture.png"), new byte[] { 1, 2, 3 });

                final NetworkAPI.UploadStatus status = NetworkAPI.upload(file.toFile());
                assertTrue(PlayerWait.awaitCondition(status::failed, WAIT_MS), "the upload failed");
                assertEquals("(wm_err:0715) Failed to upload, the file server at http://127.0.0.1:" + silent.getLocalPort() + " did not answer in time", status.error());
            }
        }

        @Test
        @DisplayName("A remoteUrl that is no http(s) address fails the upload with its coded message alone")
        void invalidRemoteFailsWithItsMessage(@TempDir final Path directory) throws Exception {
            WaterMediaConfig.network.remoteUrl = INVALID_REMOTE;
            final Path file = Files.write(directory.resolve("picture.png"), new byte[] { 1, 2, 3 });

            final NetworkAPI.UploadStatus status = NetworkAPI.upload(file.toFile());
            assertTrue(PlayerWait.awaitCondition(status::failed, WAIT_MS), "the upload failed");
            assertEquals(INVALID_REMOTE_ERROR, status.error());
        }
    }
}
