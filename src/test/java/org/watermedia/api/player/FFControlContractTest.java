package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link FFMediaPlayer} control contract: a pause, a seek and a speed chosen before the source opens, stop() while a
 * held HTTP origin keeps the load in FFmpeg or in the network cache, an unbounded HTTP source streamed past the cache, the
 * restart of an ended player, a still image timed by displayTime, and a terminal callback that cannot release its player.
 */
@DisplayName("FFMediaPlayer control contract")
public class FFControlContractTest {

    // TIMEOUTS
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long PLAY_OBSERVE_MS = 8_000L;
    private static final long STOP_RETURN_MS = 1_000L;   // stop() ONLY SIGNALS THE PIPELINE, IT NEVER WAITS FOR THE OPEN
    private static final long STOP_SETTLE_MS = 3_000L;   // FFMPEG POLLS ITS INTERRUPT CALLBACK EVERY 100ms
    private static final long THREAD_EXIT_MS = 5_000L;
    // PLAYBACK
    private static final long SEEK_LANDING_MS = 1_500L;  // A PAUSED RESTART LANDS ON THE KEYFRAME NEAREST THE TARGET
    private static final float SPEED = 2.0f;
    private static final double RATE_FLOOR = 1.25;       // A 2x CLOCK MAY LOSE 3/8 OF THE WINDOW TO A STALL ON A SLOW HOST; A CLOCK RESET TO 1x NEVER REACHES IT
    private static final long RATE_WINDOW_MS = 1_000L;
    private static final int RATE_ATTEMPTS = 3;          // A CLOCK-DEPENDENT CASE GETS TWO MORE TRIES ON A STALLED HOST
    private static final long RATE_RETRY_MS = 5_000L;
    private static final long DISPLAY_MS = 400L;
    private static final int WEBM_FRAMES = 90;

    private boolean cache; // THE PROCESS-WIDE CACHE SWITCH AS THE CASE FOUND IT

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer control tests");
    }

    @BeforeEach
    void rememberCache() { this.cache = WaterMediaConfig.codecs.ffmpeg.cache; }

    @AfterEach
    void restoreCache() { WaterMediaConfig.codecs.ffmpeg.cache = this.cache; }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // HOLDS THE ORIGIN UNTIL A NEW PIPELINE THREAD BLOCKS WHERE blocked SAYS, THEN STOPS THE LOADING PLAYER THERE. ONLY A
    // STOP INSIDE FFMPEG'S OPEN CUTS THAT OPEN SHORT ("Open aborted"); A STOP INSIDE THE CACHE WAIT NEVER REACHES THE OPEN
    private static void stopWhileLoading(final boolean cached, final Predicate<StackTraceElement> blocked) throws Exception {
        WaterMediaConfig.codecs.ffmpeg.cache = cached;
        try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
            final MRL mrl = resolve(origin.uri(".mp4"));
            final Set<Thread> baseline = Set.copyOf(Thread.getAllStackTraces().keySet());
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(false), null);
            try {
                origin.hold();
                assertTrue(player.start());
                assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the open must reach the held origin");
                assertTrue(PlayerWait.awaitCondition(() -> pipeline(baseline).stream()
                        .anyMatch(thread -> Arrays.stream(thread.getStackTrace()).anyMatch(blocked)), LOAD_TIMEOUT_MS),
                        "the load must block where this case expects");
                assertEquals(Status.LOADING, player.status());

                try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                    final long before = System.nanoTime();
                    assertTrue(player.stop(), "a loading player has a pipeline to stop");
                    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                    assertTrue(elapsedMs < STOP_RETURN_MS, "stop() must not wait for the open, it took " + elapsedMs + " ms");
                    assertTrue(PlayerWait.awaitStatus(player, STOP_SETTLE_MS, Status.STOPPED), () -> "still " + player.status());
                    assertNull(player.exception(), "an open cut short by stop() is not a failure");
                    assertFalse(player.canPlay(), "the source never opened");
                    assertTrue(PlayerWait.awaitCondition(() -> pipeline(baseline).isEmpty(), THREAD_EXIT_MS),
                            () -> "pipeline threads left behind: " + pipeline(baseline));
                    final boolean aborted = capture.events().stream().anyMatch(event -> event.getThreadName().startsWith("FFThread-")
                            && event.getMessage().getFormattedMessage().startsWith("Open aborted"));
                    assertEquals(!cached, aborted, cached ? "the stopped load opened the source it abandoned" : "the stop must cut the open short");
                }
            } finally {
                origin.release();
                player.release();
            }
        }
    }

    // RESOLVES A FRESH MRL; AN ORIGIN'S PROBE GOES THROUGH BEFORE ANY hold()
    private static MRL resolve(final URI uri) {
        final MRL mrl = PlayerAPI.mrl(uri);
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL must resolve");
        assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
        return mrl;
    }

    // PIPELINE THREADS (FFThread-*) STARTED AFTER THE BASELINE AND STILL ALIVE
    private static List<Thread> pipeline(final Set<Thread> baseline) {
        final List<Thread> alive = new ArrayList<>();
        for (final Thread thread: Thread.getAllStackTraces().keySet())
            if (!baseline.contains(thread) && thread.getName().startsWith("FFThread-")) alive.add(thread);
        return alive;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Controls before the source opens")
    class Loading {

        @Test
        @DisplayName("A pause during the first load is latched and lands PAUSED; a seek there is refused until the source opens")
        void pauseDuringFirstLoad() throws Exception {
            WaterMediaConfig.codecs.ffmpeg.cache = false;
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                final MRL mrl = resolve(origin.uri(".mp4"));
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
                try {
                    origin.hold();
                    assertTrue(player.start());
                    assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the open must reach the held origin");
                    assertEquals(Status.LOADING, player.status());
                    assertTrue(player.pause(), "a pause while loading is latched");
                    assertFalse(player.pause(), "the latched intent is already a pause");
                    assertFalse(player.seek(1_000L), "nothing is seekable before the source opens");
                    assertEquals(Status.LOADING, player.status(), "no control may leave LOADING before the pipeline opens");

                    origin.release();
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "landed " + player.status());
                    assertEquals(0L, player.time(), "the latched pause holds the start");
                    assertTrue(player.canSeek(), "an opened source is seekable");
                    assertTrue(player.resume());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "resumed to " + player.status());
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS), "the resumed video must reach the engine");
                } finally {
                    origin.release();
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("A seek and a pause during a restart's load are latched and land PAUSED at the target")
        void seekDuringReload() throws Exception {
            WaterMediaConfig.codecs.ffmpeg.cache = false;
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                final MRL mrl = resolve(origin.uri(".mp4"));
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, new HeadlessGFXEngine(false), null);
                try {
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "landed " + player.status());
                    final long duration = player.duration();
                    assertTrue(duration > 2 * SEEK_LANDING_MS, "the fixture must be long enough to tell the target apart");
                    final long target = duration / 2;
                    assertTrue(player.stop());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED), () -> "stopped to " + player.status());

                    origin.hold();
                    assertTrue(player.start());
                    assertTrue(origin.awaitEntered(LOAD_TIMEOUT_MS), "the reload must reach the held origin");
                    assertEquals(Status.LOADING, player.status());
                    assertTrue(player.seek(target), "a source that opened before stays seekable while it reloads");
                    assertTrue(player.pause(), "a pause while reloading is latched");
                    assertEquals(Status.LOADING, player.status(), "a latched seek must not leave LOADING before the pipeline opens");

                    origin.release();
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "landed " + player.status());
                    assertTrue(Math.abs(player.time() - target) < SEEK_LANDING_MS, "the reload lands near the latched target: " + player.time());
                } finally {
                    origin.release();
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("A speed chosen before start() survives into playback")
        void speedBeforeStart() throws Exception {
            // THE RATE IS MEASURED AGAINST THE WALL CLOCK WHILE THE PIPELINE DECODES AT 2x, SO A SLOW CI RUNNER THAT STALLS IT
            // GETS A FRESH PLAYER AND TWO MORE TRIES BEFORE THE CASE FAILS
            AssertionError failure = null;
            for (int attempt = 1; attempt <= RATE_ATTEMPTS; attempt++) {
                if (attempt > 1) Thread.sleep(RATE_RETRY_MS);
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(resolve(Fixtures.fileUri(Fixtures.MP4_H264)), 0, gfx, null);
                try {
                    assertTrue(player.speed(SPEED), "a player that never ran takes a new speed");
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "landed " + player.status());
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS), "the video must reach the engine");
                    assertEquals(SPEED, player.speed(), "the run keeps the chosen speed");

                    // BRACKET BOTH READINGS: THE CLOCK MUST RUN AT THE CHOSEN RATE, NOT AT THE 1x A FRESH RUN COULD RESET IT TO
                    final long before = System.nanoTime();
                    final long from = player.time();
                    final long started = System.nanoTime();
                    Thread.sleep(RATE_WINDOW_MS);
                    final long reading = System.nanoTime();
                    final long to = player.time();
                    final long after = System.nanoTime();
                    final long minimum = (long) (TimeUnit.NANOSECONDS.toMillis(reading - started) * RATE_FLOOR);
                    // EACH READING TRUNCATES THE CLOCK TO WHOLE MILLISECONDS
                    final long maximum = (long) (TimeUnit.NANOSECONDS.toMillis(after - before) * SPEED) + 2L;
                    assertTrue(to - from >= minimum && to - from <= maximum,
                            "the clock must advance within [" + minimum + ", " + maximum + "] ms, advanced " + (to - from));
                    return;
                } catch (final AssertionError e) {
                    failure = e;
                } finally {
                    player.release();
                }
            }
            throw failure;
        }
    }

    @Nested
    @DisplayName("Slow and unbounded HTTP origins")
    class Origins {

        @Test
        @DisplayName("stop() while FFmpeg opens a held origin returns at once, cuts the open short and lands STOPPED without leaking threads")
        void stopWhileFFmpegOpens() throws Exception {
            stopWhileLoading(false, frame -> frame.getMethodName().equals("avformat_open_input"));
        }

        @Test
        @DisplayName("stop() while the network cache downloads a held origin lands STOPPED without opening the source in FFmpeg")
        void stopWhileTheCacheDownloads() throws Exception {
            stopWhileLoading(true, frame -> frame.getClassName().equals(NetworkCache.class.getName()));
        }

        @Test
        @DisplayName("An HTTP source without Content-Length plays while the network cache is on, streamed instead of cached")
        void unboundedSourceBypassesTheCache() throws Exception {
            WaterMediaConfig.codecs.ffmpeg.cache = true;
            try (final Origin origin = Origin.endless(Fixtures.webm(96, 64, WEBM_FRAMES, 30), "video/webm")) {
                final MRL mrl = resolve(origin.uri(".webm"));
                final int probes = origin.requests();
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
                try {
                    assertTrue(player.start());
                    // A CACHE DOWNLOADING THE ENDLESS BODY WOULD HOLD THE LOAD UNTIL ITS READ TIMEOUT, LONG AFTER THIS WAIT
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS),
                            () -> "no frame reached the engine, status " + player.status());
                    assertNull(player.exception(), "the streamed source plays without a failure");
                    assertTrue(origin.requests() - probes >= 2, "the cache must ask first and FFmpeg then stream the source itself");
                } finally {
                    player.release();
                }
            }
        }
    }

    @Nested
    @DisplayName("Ended, still and released players")
    class Terminal {

        @Test
        @DisplayName("A seek after ENDED restarts paused at the target, stop() rewinds and a released player lands STOPPED and refuses to start")
        void seekAfterEndedRestartsPaused() {
            final FFMediaPlayer player = new FFMediaPlayer(resolve(Fixtures.fileUri(Fixtures.MP4_H264)), 0, new HeadlessGFXEngine(false), null);
            final List<Status> seen = new CopyOnWriteArrayList<>();
            // THE TERMINAL CALLBACK REGISTERS AGAIN, THE WAY A PLAYLIST CONSUMER STAYS FOR THE RESTART IT TRIGGERS
            final MediaPlayer.StatusListener[] collector = new MediaPlayer.StatusListener[1];
            collector[0] = (p, prev, next) -> {
                seen.add(next);
                if (next == Status.ENDED) p.onStatus(collector[0]);
            };
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "landed " + player.status());
                player.onStatus(collector[0]);
                assertTrue(player.fps() > 0f, "a decoded video stream reports its rate");
                assertTrue(player.canPlay());
                final long duration = player.duration();
                assertTrue(duration > 1_000L, "the fixture must outlast the seek to its end");
                assertTrue(player.seek(duration - 500L));
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.ENDED), () -> "the seek to the end landed " + player.status());
                assertTrue(player.canPlay(), "an ended media is still playable");
                assertFalse(player.pause(), "nothing to pause after the end");

                final long target = duration / 2;
                assertTrue(player.seek(target), "a seek after the end restarts the player");
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "the restart landed " + player.status());
                assertTrue(Math.abs(player.time() - target) < SEEK_LANDING_MS, "the restart lands near the scrubbed position: " + player.time());

                assertTrue(player.togglePlay());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "toggled to " + player.status());
                assertTrue(player.stop());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED), () -> "stopped to " + player.status());
                assertEquals(0L, player.time(), "stop() rewinds");
                assertFalse(player.stop(), "already stopped");
            } finally {
                player.release();
            }
            assertEquals(Status.STOPPED, player.status());
            assertFalse(player.canPlay(), "a released player has nothing to play");
            assertTrue(seen.contains(Status.LOADING) && seen.contains(Status.PAUSED), "intermediate transitions are published: " + seen);
            assertFalse(player.start(), "a released player refuses to start");
        }

        @Test
        @DisplayName("A still image through FFmpeg stays up until displayTime ends it, ignoring repeat")
        void stillImageDisplayTime() {
            // A PPM IS A TRUE SINGLE-FRAME SOURCE DECODED IN SOFTWARE (THE GRAY JPEG IS REFUSED BY HARDWARE DECODERS)
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final FFMediaPlayer player = new FFMediaPlayer(resolve(Fixtures.fileUri(Fixtures.NETPBM_PPM)), 0, gfx, null);
            try {
                player.repeat(true);
                player.displayTime(DISPLAY_MS);
                assertTrue(player.start());
                // THE DISPLAY WINDOW IS SHORT, SO THE FRAME UPLOAD IS THE SIGNAL THAT PLAYBACK STARTED, NOT A STATUS POLL
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, LOAD_TIMEOUT_MS), "the still must reach the engine");
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.ENDED), () -> "the display time must end the still, it is " + player.status());
                assertEquals(DISPLAY_MS, player.duration(), "a timed still lasts its display time");
                assertEquals(1L, gfx.uploadCount(), "the still is uploaded once, never looped");
            } finally {
                player.release();
            }
        }

        @ParameterizedTest(name = "{displayName}: {0}")
        @EnumSource(value = Status.class, names = { "ENDED", "ERROR" })
        @DisplayName("A terminal callback cannot release the player from a playback thread, which keeps its graphics")
        void terminalCallbackCannotFreeItsOwnPlaybackResources(final Status terminal, @TempDir final Path directory) throws Exception {
            final Path input = terminal == Status.ERROR ? directory.resolve("removed.mp4") : Fixtures.MP4_H264;
            if (terminal == Status.ERROR) Files.copy(Fixtures.MP4_H264, input);
            final MRL mrl = resolve(Fixtures.fileUri(input));
            // REMOVE ONLY THE TEMP COPY AFTER RESOLUTION TO EXERCISE A REAL DEMUX OPEN FAILURE
            if (terminal == Status.ERROR) Files.delete(input);
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
            final CompletableFuture<Throwable> callback = new CompletableFuture<>();
            final Thread[] callbackThread = new Thread[1];
            final boolean[] retained = new boolean[1];
            player.onStatus((p, previous, next) -> {
                if (next != terminal || callback.isDone()) return;
                callbackThread[0] = Thread.currentThread();
                Throwable failure = null;
                try { player.release(); }
                catch (final Throwable rejected) { failure = rejected; }
                retained[0] = !gfx.released();
                callback.complete(failure);
            });
            try {
                assertTrue(player.start());
                if (terminal == Status.ENDED) {
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, LOAD_TIMEOUT_MS), "the video must reach the engine");
                    assertTrue(player.duration() > 0, "the fixture reports its duration");
                    assertTrue(player.seek(Math.max(0L, player.duration() - 1_000L)));
                }
                assertInstanceOf(IllegalStateException.class, callback.get(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the release from the callback is refused");
                assertNotSame(Thread.currentThread(), callbackThread[0], "the terminal callback runs on a playback thread");
                assertTrue(retained[0], "the refused release keeps the graphics engine");
                player.release();
                assertTrue(gfx.released(), "a release from the host thread frees the graphics engine");
            } finally {
                player.release();
            }
        }
    }
}
