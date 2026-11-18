package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.player.MediaPlayer.StatusListener;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shared control contract on {@link TxMediaPlayer}: stop(), the restarts from STOPPED and ENDED through
 * seek() and togglePlay() in both playback modes, the failure each restart clears, a timed still that ends, and a
 * listener that hears every transition as one unbroken chain.
 */
@DisplayName("TxMediaPlayer control contract")
public class TxControlContractTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long DISPLAY_MS = 150L;
    private static final long SEEK_WINDOW_MS = 100L; // ONE GIF FRAME: A SCRUB LANDS ON THE FRAME THAT COVERS ITS TARGET
    private static final int TEXTURE_CAP = 16;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static TxMediaPlayer open(final Path fixture, final HeadlessGFXEngine gfx) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(fixture));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
        return new TxMediaPlayer(mrl, 0, gfx);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Terminal states")
    class Terminal {

        @Test
        @DisplayName("stop() answers true and is heard once, rewinds to zero and keeps the media playable")
        void stopContract() {
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, new HeadlessGFXEngine(false));
            final AtomicInteger stops = new AtomicInteger();
            final StatusListener counter = (p, previous, current) -> { if (current == Status.STOPPED) stops.incrementAndGet(); };
            try {
                player.onStatus(counter);
                assertFalse(player.stop(), "nothing started yet");
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
                assertTrue(PlayerWait.awaitCondition(() -> player.time() > 0L, PLAYER_TIMEOUT_MS), "the animation advanced before the stop");
                assertTrue(player.stop());
                assertEquals(Status.STOPPED, player.status());
                assertEquals(0L, player.time(), "a stop rewinds to zero");
                assertTrue(PlayerWait.awaitCondition(() -> stops.get() == 1, PLAYER_TIMEOUT_MS), "the listener heard the stop");
                // STOPPED CONSUMED THE LISTENER: REGISTERED AGAIN, IT HEARS WHETHER THE SECOND STOP REPORTS ANYTHING
                player.onStatus(counter);
                assertFalse(player.stop(), "already stopped");
                assertEquals(1, stops.get(), "the second stop reports no transition");
                assertTrue(player.canPlay(), "a stopped player can start again");
                assertFalse(player.pause(), "nothing to pause");
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("startPaused() and a togglePlay() restart clear the old failure, so the next one is recorded")
        void restartsClearTheFailure(@TempDir final Path directory) throws Exception {
            final Path input = directory.resolve("removed.gif");
            Files.copy(Fixtures.GIF_ANIMATED, input);
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(input));
            assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
            // THE SOURCE VANISHES AFTER IT RESOLVED, SO EVERY RUN FAILS WITH ITS OWN EXCEPTION
            Files.delete(input);
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ERROR), () -> "the vanished source fails the run, not " + player.status());
                final Throwable first = player.exception();
                assertNotNull(first, "the failed run is recorded");
                assertTrue(player.startPaused());
                assertTrue(PlayerWait.awaitCondition(() -> player.exception() != null && player.exception() != first, PLAYER_TIMEOUT_MS),
                        "the paused restart failed again and replaced the old failure");
                final Throwable second = player.exception();
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ERROR), () -> "the paused restart fails too, not " + player.status());
                assertTrue(player.stop());
                assertTrue(player.togglePlay(), "a stopped player restarts on toggle");
                assertTrue(PlayerWait.awaitCondition(() -> player.exception() != null && player.exception() != second, PLAYER_TIMEOUT_MS),
                        "the toggle restart failed again and replaced the old failure");
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("seek() after stop restarts paused at the target and togglePlay() resumes it")
        void seekAfterStopRestartsPaused() {
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, new HeadlessGFXEngine(false));
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
                final long target = Math.max(1L, player.duration() / 2);
                assertTrue(player.stop());
                assertTrue(player.seek(target));
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED), () -> "the restart lands paused, not " + player.status());
                assertTrue(PlayerWait.awaitCondition(() -> player.time() >= target - SEEK_WINDOW_MS, PLAYER_TIMEOUT_MS),
                        "the restart lands at the scrubbed position");
                assertTrue(player.togglePlay());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the toggle resumes, not " + player.status());
            } finally {
                player.release();
            }
        }

        @ParameterizedTest(name = "frame textures: {0}")
        @ValueSource(booleans = { false, true })
        @DisplayName("seek() after ENDED lands PAUSED at the target in both playback modes and togglePlay() resumes it")
        void seekAfterEndedRestartsPaused(final boolean textures) {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(textures);
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, gfx);
            try {
                // A CAPPED SIZE KEEPS THE FRAME SET INSIDE THE TEXTURE BUDGET, SO THE PRELOADING ENGINE TAKES THE PASSIVE CLOCK
                player.scale(TEXTURE_CAP, TEXTURE_CAP);
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
                assertEquals(textures, gfx.preloadedFrames() != null, "the engine picks the playback mode under test");
                final long duration = player.duration();
                assertTrue(duration > 2 * SEEK_WINDOW_MS, "the fixture spans more than two scrub windows");
                // THE WHOLE ANIMATION OUTLASTS THE WAIT BELOW, SO ONLY A SCRUB THAT REALLY MOVED THE CLOCK REACHES ENDED IN TIME
                assertTrue(duration > PLAYER_TIMEOUT_MS, "the fixture must outlast the wait");
                assertTrue(player.seek(duration - SEEK_WINDOW_MS), "a live scrub runs the animation into its end");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ENDED), () -> "the scrub ran into the end, not " + player.status());

                final long target = duration / 2;
                assertTrue(player.seek(target), "a scrub of a finished animation is taken");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED), () -> "the scrub lands paused, not " + player.status());
                // THE PLAYER SHOWS THE FRAME THAT COVERS THE TARGET AND HOLDS THERE
                assertTrue(PlayerWait.awaitCondition(() -> player.time() <= target && player.time() > target - SEEK_WINDOW_MS, PLAYER_TIMEOUT_MS),
                        () -> "the scrub lands on the frame of the target, not at " + player.time());
                assertTrue(player.canPlay());
                assertTrue(player.togglePlay());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the toggle resumes, not " + player.status());
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("A timed still ends from PLAYING even when repeat is on; pause() refuses and togglePlay() restarts it")
        void endedStillRestartsOnToggle() {
            final TxMediaPlayer player = open(Fixtures.JPEG_GRAY, new HeadlessGFXEngine(false));
            final AtomicReference<Status> endedFrom = new AtomicReference<>();
            try {
                player.onStatus((p, previous, current) -> { if (current == Status.ENDED) endedFrom.set(previous); });
                player.repeat(true);
                player.displayTime(DISPLAY_MS);
                assertTrue(player.start());
                // THE PASSIVE CLOCK ENDS THE STILL ON THE THREAD THAT READS status(), SO THE LISTENER RAN BEFORE THE WAIT RETURNS
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.ENDED), () -> "the display window ends the still, not " + player.status());
                assertEquals(Status.PLAYING, endedFrom.get(), "the display window ends a playing still");
                assertEquals(DISPLAY_MS, player.duration(), "a timed still reports its display window as its duration");
                assertFalse(player.pause(), "an ended still has nothing to pause");
                assertFalse(player.resume(), "nor to resume");
                assertFalse(player.nextFrame(), "an ended player is not paused, so it cannot step");
                assertTrue(player.togglePlay(), "toggling an ended player starts it again");
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the toggle restarts the still, not " + player.status());
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Listener and queries")
    class Listener {

        @Test
        @DisplayName("The listener hears every transition as one chain from WAITING to the STOPPED of release()")
        void listenerHearsEveryTransition() {
            final TxMediaPlayer player = open(Fixtures.GIF_ANIMATED, new HeadlessGFXEngine(false));
            final List<Transition> heard = new CopyOnWriteArrayList<>();
            try {
                player.onStatus((p, previous, current) -> heard.add(new Transition(previous, current)));
                assertFalse(player.canPlay(), "nothing loaded yet");
                assertFalse(player.canSeek(), "nothing loaded yet");
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
                assertTrue(player.canSeek(), "a live animation is seekable");
                assertTrue(player.pause());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED), () -> "the pause landed, not " + player.status());
                assertFalse(player.pause(), "already paused");
                assertTrue(player.resume());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the resume landed, not " + player.status());
            } finally {
                player.release();
            }
            assertEquals(Status.STOPPED, player.status());
            assertFalse(player.canPlay(), "released players are not playable");
            // BUFFERING REFILLS COME AND GO WITH THE DECODE PACE, SO THE CHAIN IS CHECKED INSTEAD OF A FIXED SEQUENCE
            assertFalse(heard.isEmpty(), "the listener heard the run");
            assertEquals(new Transition(Status.WAITING, Status.LOADING), heard.get(0), () -> "the run starts from WAITING: " + heard);
            for (int i = 1; i < heard.size(); i++)
                assertEquals(heard.get(i - 1).current(), heard.get(i).previous(), "transition " + i + " starts where the one before ended: " + heard);
            assertTrue(heard.contains(new Transition(Status.PLAYING, Status.PAUSED)), () -> "the pause is heard: " + heard);
            assertTrue(heard.contains(new Transition(Status.PAUSED, Status.PLAYING)), () -> "the resume is heard: " + heard);
            assertEquals(Status.STOPPED, heard.get(heard.size() - 1).current(), "release publishes the final stop");
        }
    }

    private record Transition(Status previous, Status current) {}
}
