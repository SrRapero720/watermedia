package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link TxMediaPlayer} animated image: each bundled GIF streams seekable, timed frames that upload over
 * time, and frame stepping moves the clock both ways only while paused, in the streaming and the frame-texture modes.
 */
@DisplayName("TxMediaPlayer animated image")
public class TxAnimatedImageTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long PLAYBACK_OBSERVATION_MS = 1_500L;
    private static final long STEP_SETTLE_MS = 2_000L;
    private static final int TEXTURE_CAP = 16;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = { "1.gif", "2.gif", "3.gif" })
    @DisplayName("A GIF streams seekable, timed frames that upload over time")
    void gifStreamsFrames(final String name) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_DIR.resolve(name)));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
        // AN ENGINE WITHOUT PRELOAD TAKES THE STREAMING LOOP, WHICH UPLOADS EVERY FRAME IT SHOWS
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
            assertTrue(player.canSeek(), "an animation is seekable");
            assertTrue(player.duration() > 0L, "an animation reports its duration");
            final long shown = gfx.uploadCount();
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > shown, PLAYBACK_OBSERVATION_MS),
                    "the clock moved on to a newly uploaded frame");
        } finally {
            player.release();
        }
    }

    @ParameterizedTest(name = "frame textures: {0}")
    @ValueSource(booleans = { false, true })
    @DisplayName("nextFrame() and previousFrame() move the clock only while paused and keep the player paused")
    void frameStepWhilePaused(final boolean textures) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("2.gif")));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(textures);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            // A CAPPED SIZE KEEPS THE FRAME SET INSIDE THE TEXTURE BUDGET, SO THE PRELOADING ENGINE TAKES THE PASSIVE CLOCK;
            // REPEAT KEEPS THE ANIMATION FROM ENDING WHILE THE STEPS ARE CHECKED
            player.scale(TEXTURE_CAP, TEXTURE_CAP);
            player.repeat(true);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
            assertEquals(textures, gfx.preloadedFrames() != null, "the engine picks the playback mode under test");
            assertFalse(player.nextFrame(), "a playing animation refuses to step");
            assertFalse(player.previousFrame(), "a playing animation refuses to step back");

            assertTrue(player.pause());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED), () -> "the pause landed, not " + player.status());
            final long paused = player.time();
            assertTrue(player.nextFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > paused, STEP_SETTLE_MS), "the step forward moved the clock");
            assertEquals(Status.PAUSED, player.status(), "a step keeps the player paused");

            final long stepped = player.time();
            assertTrue(player.previousFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() < stepped, STEP_SETTLE_MS), "the step back moved the clock");
            assertEquals(Status.PAUSED, player.status(), "a step back keeps the player paused");
        } finally {
            player.release();
        }
    }
}
