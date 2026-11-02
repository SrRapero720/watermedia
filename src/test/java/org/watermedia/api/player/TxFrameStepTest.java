package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that {@link TxMediaPlayer} steps frames both ways only while paused, staying {@link Status#PAUSED}. */
@DisplayName("TxMediaPlayer frame step")
public class TxFrameStepTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long STEP_SETTLE_MS = 2_000L;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("nextFrame and previousFrame move the clock only while paused")
    void testNextAndPreviousFrameMoveClockWhilePaused() {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_DIR.resolve("2.gif")));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
        try {
            player.repeat(true); // THE ANIMATION MUST NOT END WHILE THE STEPS ARE CHECKED
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING));
            assertFalse(player.nextFrame(), "a playing animation refuses to step");
            assertFalse(player.previousFrame(), "a playing animation refuses to step back");

            assertTrue(player.pause());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PAUSED));
            final long initial = player.time();

            assertTrue(player.nextFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > initial, STEP_SETTLE_MS));
            assertEquals(Status.PAUSED, player.status());

            final long afterNext = player.time();
            assertTrue(player.previousFrame());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() < afterNext, STEP_SETTLE_MS));
            assertEquals(Status.PAUSED, player.status());
        } finally {
            player.release();
        }
    }
}
