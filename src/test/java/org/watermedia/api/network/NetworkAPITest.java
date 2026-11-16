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
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies how {@link NetworkAPI} learns that the file server is up: the root line {@code answers}
 * accepts, and {@code serverEnabled()} answering true at once on the host that started it and, elsewhere,
 * false while the remote server is silent, asking it once per pause, logging that once and keeping the
 * first true answer.
 */
@DisplayName("NetworkAPI")
public class NetworkAPITest {
    private static final byte[] NAME = WaterMedia.NAME.getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "nginx".getBytes(StandardCharsets.UTF_8);
    private static final long WAIT_MS = 15_000;
    private String previousRemote;

    // CHILD-JVM PROBE: THE MODULE STARTS ONCE PER PROCESS, SO THE HOST OF THE FILE SERVER GETS ITS OWN JVM
    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("host")) throw new IllegalArgumentException("Unknown file server probe");
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        final int port;
        try (final ServerSocket free = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { port = free.getLocalPort(); }
        WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.DEDICATED;
        WaterMediaConfig.network.serverPort = port;
        WaterMediaConfig.network.remoteUrl = "http://127.0.0.1:" + port + "/";
        WaterMedia.start("HOST_TEST", root.resolve("temp"), root, false);
        if (NetworkServer.address() == null) throw new AssertionError("The file server did not start");
        if (!NetworkAPI.serverEnabled()) throw new AssertionError("The host of the file server did not report it up");
        // ITS DISPATCHER IS NOT A DAEMON THREAD AND WOULD KEEP THIS JVM ALIVE
        NetworkServer.stop();
        System.out.println("PROBE_OK host");
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeAll
    static void bootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void snapshot() throws ReflectiveOperationException {
        this.previousRemote = WaterMediaConfig.network.remoteUrl;
        forget();
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        WaterMediaConfig.network.remoteUrl = this.previousRemote;
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

    // CLEARS WHAT serverEnabled() LEARNED AND ENDS ITS PAUSE, ONCE THE REQUEST IN FLIGHT IS OVER
    private static void forget() throws ReflectiveOperationException {
        final AtomicBoolean probing = probing();
        assertTrue(PlayerWait.awaitCondition(() -> !probing.get(), WAIT_MS));
        field("serverUp").setBoolean(null, false);
        field("probeWarned").setBoolean(null, false);
        field("probeAt").setLong(null, System.nanoTime());
    }

    private static long warnings(final LogCapture capture) {
        return capture.events().stream().filter(event -> event.getMessage().getFormattedMessage().startsWith("Could not reach the remote file server")).count();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
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
        @DisplayName("Another service answers false")
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
        void silentPortThrows() throws Exception {
            final int port;
            try (final ServerSocket free = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) { port = free.getLocalPort(); }
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
        @DisplayName("Answers false until the remote server replies, then true for good")
        void falseUntilTheServerReplies() {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID);
                 final LocalHttp server = LocalHttp.start("/", exchange -> LocalHttp.respond(exchange, "text/plain", NAME, 0))) {
                WaterMediaConfig.network.remoteUrl = server.uri("/").toString();
                // THE FIRST CALL ONLY SENDS THE REQUEST
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(NetworkAPI::serverEnabled, WAIT_MS));
                assertEquals(0, warnings(capture));
            }
            // THE SERVER IS GONE AND THE ANSWER STAYS
            assertTrue(NetworkAPI.serverEnabled());
        }

        @Test
        @DisplayName("A silent server is asked once per pause, logged once and found when it shows up")
        void silentServerIsAskedOncePerPause() throws Exception {
            final AtomicInteger requests = new AtomicInteger();
            final AtomicBoolean up = new AtomicBoolean();
            final AtomicBoolean probing = probing();
            try (final LogCapture capture = new LogCapture(WaterMedia.ID);
                 final LocalHttp server = LocalHttp.start("/", exchange -> {
                     requests.incrementAndGet();
                     LocalHttp.respond(exchange, "text/plain", up.get() ? NAME : OTHER, 0);
                 })) {
                WaterMediaConfig.network.remoteUrl = server.uri("/").toString();
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> requests.get() == 1 && !probing.get(), WAIT_MS));

                // THE PAUSE HOLDS HOWEVER OFTEN THE CALLER POLLS
                for (int call = 0; call < 100; call++) assertFalse(NetworkAPI.serverEnabled());
                assertFalse(probing.get());
                assertEquals(1, requests.get());

                // THE PAUSE IS OVER: THE NEXT CALL ASKS AGAIN, AND ONLY THE FIRST SILENCE WAS LOGGED
                field("probeAt").setLong(null, System.nanoTime());
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> requests.get() == 2 && !probing.get(), WAIT_MS));
                assertEquals(1, warnings(capture));

                up.set(true);
                field("probeAt").setLong(null, System.nanoTime());
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(NetworkAPI::serverEnabled, WAIT_MS));
                assertEquals(3, requests.get());
            }
        }

        @Test
        @DisplayName("A remoteUrl that is no http(s) address stays false without a request")
        void invalidRemoteStaysFalse() throws Exception {
            final AtomicBoolean probing = probing();
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                WaterMediaConfig.network.remoteUrl = "remote.invalid/upload";
                assertFalse(NetworkAPI.serverEnabled());
                assertTrue(PlayerWait.awaitCondition(() -> warnings(capture) == 1 && !probing.get(), WAIT_MS));
                assertFalse(NetworkAPI.serverEnabled());
            }
        }
    }
}
