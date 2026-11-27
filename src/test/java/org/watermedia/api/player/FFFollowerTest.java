package org.watermedia.api.player;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies how an {@link FFMediaPlayer} follows a session over an origin that answers late, where every seek flies for a
 * request: its start aligns, a seek of the host lands where the session is by then, a player that falls behind catches up
 * from what it already read without a request, and corrections that keep missing the tolerance are reported once.
 */
@DisplayName("FFMediaPlayer following a session")
public class FFFollowerTest {

    // TIMEOUTS
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long ALIGN_TIMEOUT_MS = 30_000L;
    private static final long SETTLE_MS = 3_000L; // THE COOLDOWN, A FLIGHT AND A SPARE SECOND: A CORRECTION STILL OWED SHOWS UP IN IT
    // ORIGIN: EVERY REQUEST WAITS THIS LONG FOR ITS ANSWER, AND A SEEK IS A REQUEST
    private static final long DELAY_MS = 800L;
    // MEDIA: PRORES IS ALL KEYFRAMES, SO A QUICK SEEK LANDS ON THE FRAME OF ITS TARGET AND ONLY THE FLIGHT IS LEFT TO MISS BY
    private static final int WIDTH = 128;
    private static final int HEIGHT = 72;
    private static final int FPS = 25;
    private static final int SECONDS = 60;
    // FOLLOWER
    private static final long TOLERANCE_MS = 400L; // HALF A FLIGHT: A SEEK AIMED AT WHERE THE SESSION IS NEVER LANDS INSIDE IT
    private static final long SEEK_MS = 30_000L;
    private static final long JUMP_MS = 2_000L;    // PAST THE TOLERANCE, INSIDE THE REACH OF A CATCH-UP (FFMediaPlayer.CATCH_UP_MS)
    private static final int REPORTED_SEEKS = 4;   // THE THIRD CORRECTION IN A ROW IS REPORTED, THE FOURTH MUST STAY QUIET

    @TempDir
    static Path media;
    private static byte[] clip;

    private boolean cache; // THE PROCESS-WIDE CACHE SWITCH AS THE CASE FOUND IT

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() throws Exception {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer follower tests");
        final Path file = media.resolve("clip.mov");
        Fixtures.clip(file, Container.MOV, VideoCodec.PRORES, null, WIDTH, HEIGHT, FPS, SECONDS);
        clip = Files.readAllBytes(file);
    }

    // THE CACHE WOULD DOWNLOAD THE CLIP ONCE AND SEEK IN THE COPY WITHOUT ASKING THE ORIGIN
    @BeforeEach
    void configure() {
        this.cache = WaterMediaConfig.codecs.ffmpeg.cache;
        WaterMediaConfig.codecs.ffmpeg.cache = false;
    }

    @AfterEach
    void restore() {
        WaterMediaConfig.codecs.ffmpeg.cache = this.cache;
    }

    @Test
    @DisplayName("A start aligns with the session and a seek of the host that flies for a request lands where the session is by then")
    void hostSeekLandsOnTheSession() {
        try (final Session session = Session.open(TOLERANCE_MS)) {
            final FFMediaPlayer player = session.player();
            assertTrue(session.clock().start());
            player.sync(session.clock().snapshot());
            // THE PLAYER OPENS BEHIND THE SESSION, JUMPS TO IT A FLIGHT LATE AND MAKES UP FOR THE FLIGHT
            assertTrue(PlayerWait.awaitCondition(() -> aligned(player), ALIGN_TIMEOUT_MS), () -> "the start must align: " + session);
            assertFalse(PlayerWait.awaitCondition(() -> !aligned(player), SETTLE_MS), () -> "the start must stay aligned: " + session);

            // THE HOST MOVES ITS CLOCK AND ECHOES THE SEEK TO THE VIEWER, AS ITS OWN PACKET WOULD
            session.heard().clear();
            assertTrue(session.clock().seek(SEEK_MS));
            player.sync(session.clock().snapshot());
            assertTrue(player.seekQuick(SEEK_MS), "the viewer must take the host's seek");
            assertTrue(PlayerWait.awaitCondition(() -> aligned(player), ALIGN_TIMEOUT_MS), () -> "the seek must align: " + session);
            assertFalse(PlayerWait.awaitCondition(() -> !aligned(player), SETTLE_MS), () -> "the seek must stay aligned: " + session);
            // THE LANDING FOLLOWS THE SESSION WHILE THE SEEK FLIES, SO IT NEEDS NO CORRECTION
            assertEquals(1L, session.seeks(), () -> "the host's seek lands on the session at once: " + session);
        }
    }

    @Test
    @DisplayName("A player that falls behind its session inside what it already read catches up without asking the origin again")
    void catchUpWithoutRequest() {
        try (final Session session = Session.open(TOLERANCE_MS)) {
            final FFMediaPlayer player = session.player();
            assertTrue(session.clock().start());
            player.sync(session.clock().snapshot());
            assertTrue(PlayerWait.awaitCondition(() -> aligned(player), ALIGN_TIMEOUT_MS), () -> "the start must align: " + session);
            assertFalse(PlayerWait.awaitCondition(() -> !aligned(player), SETTLE_MS), () -> "the start must stay aligned: " + session);

            // THE SESSION RUNS AHEAD WITHOUT AN ECHO, AS IT DOES PAST A PLAYER THAT STALLED. THE READERS ARE ALREADY THERE
            final int requests = session.requests().get();
            session.heard().clear();
            assertTrue(session.clock().seek(session.clock().time() + JUMP_MS));
            player.sync(session.clock().snapshot());
            assertTrue(PlayerWait.awaitCondition(() -> session.seeks() > 0 && aligned(player), ALIGN_TIMEOUT_MS), () -> "the player must catch up: " + session);
            assertEquals(requests, session.requests().get(), () -> "the catch-up drops what it had read up to the session, without a request: " + session);
            assertEquals(1L, session.seeks(), () -> "the catch-up lands on the session at once: " + session);
        }
    }

    @Test
    @DisplayName("Corrections that keep missing the tolerance are reported once")
    void missedCorrectionsAreReportedOnce() {
        // NO SEEK LANDS WITHIN A MILLISECOND OF THE SESSION, SO EVERY LANDING ASKS FOR THE NEXT ONE. THE START BUFFERS TOO
        try (final LogCapture capture = new LogCapture(WaterMedia.ID); final Session session = Session.open(1L)) {
            assertTrue(session.clock().start());
            session.player().sync(session.clock().snapshot());
            assertTrue(PlayerWait.awaitCondition(() -> session.seeks() >= REPORTED_SEEKS + 1, ALIGN_TIMEOUT_MS), () -> "the corrections must keep coming: " + session);
            final long reports = capture.events().stream()
                    .filter(event -> event.getLevel() == Level.WARN && event.getMessage().getFormattedMessage().startsWith("Sync keeps correcting")).count();
            assertEquals(1L, reports, () -> "the missed corrections are reported once: " + session);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // PLAYING WITHIN THE TOLERANCE OF THE SESSION
    private static boolean aligned(final MediaPlayer player) {
        return player.playing() && Math.abs(player.drift()) <= TOLERANCE_MS;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // THE CLIP ON A LATE ORIGIN COUNTING ITS REQUESTS, THE PLATFORM THAT RESOLVES IT WITHOUT A REQUEST, A VIDEO-ONLY PLAYER RECORDING
    // EVERY STATUS IT HEARS AND THE SESSION CLOCK IT FOLLOWS. CLOSING RELEASES BOTH PLAYERS, UNREGISTERS THE PLATFORM AND CLOSES THE ORIGIN
    private record Session(LocalHttp origin, AtomicInteger requests, ExecutorService workers, Platform platform, FFMediaPlayer player,
                           ServerMediaPlayer clock, List<Status> heard) implements AutoCloseable {

        private static Session open(final long toleranceMs) {
            final ExecutorService workers = Executors.newCachedThreadPool();
            final AtomicInteger requests = new AtomicInteger();
            final LocalHttp origin = LocalHttp.start("/", exchange -> {
                requests.incrementAndGet();
                try {
                    Thread.sleep(DELAY_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                LocalHttp.respondRange(exchange, "video/quicktime", clip);
            }, workers);
            final URI link = origin.uri("/clip.mov");
            final String host = "p" + link.getPort();
            final Platform platform = new Platform() {
                @Override public String name() { return "FFFollowerTest-" + host; }

                @Override
                public Resolution resolve(final URI uri) {
                    if (!"wmtest-follow".equals(uri.getScheme()) || !host.equals(uri.getHost())) return null;
                    return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(), List.of(new Quality(link, 0, 0)), List.of(), null));
                }
            };
            PlatformAPI.register(platform);
            try {
                final MRL mrl = PlayerAPI.mrl(URI.create("wmtest-follow://" + host + "/" + System.nanoTime()));
                assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL must resolve");
                assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(false), null);
                player.tolerance(toleranceMs);
                final List<Status> heard = new CopyOnWriteArrayList<>();
                player.onStatus((source, previous, current) -> heard.add(current));
                final ServerMediaPlayer clock = PlayerAPI.serverPlayer();
                clock.syncDuration(SECONDS * 1_000L);
                return new Session(origin, requests, workers, platform, player, clock, heard);
            } catch (final RuntimeException | Error e) {
                PlatformAPI.unregister(platform);
                origin.close();
                workers.shutdownNow();
                throw e;
            }
        }

        // EVERY SEEK TAKES THE PLAYER THROUGH BUFFERING ONCE, AND SO DOES ITS START
        private long seeks() {
            return this.heard.stream().filter(status -> status == Status.BUFFERING).count();
        }

        @Override
        public void close() {
            try {
                this.player.release();
            } finally {
                this.clock.release();
                PlatformAPI.unregister(this.platform);
                this.origin.close();
                this.workers.shutdownNow();
            }
        }

        @Override
        public String toString() {
            return "status " + this.player.status() + ", drift " + this.player.drift() + " ms, heard " + this.heard;
        }
    }
}
