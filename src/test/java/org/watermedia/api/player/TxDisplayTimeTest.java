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

/** Verifies that a static image without a display time never ends and has no duration; {@link TxControlContractTest} times it. */
@DisplayName("TxMediaPlayer static image display time")
public class TxDisplayTimeTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long OBSERVE_MS = 400L;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("An unlimited static image never ends")
    void testUnlimitedStaticNeverEnds() {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.JPEG_BASELINE));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
        try {
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertFalse(PlayerWait.awaitCondition(player::ended, OBSERVE_MS), "an unlimited static image must not transition to ENDED");
            assertEquals(0L, player.duration());
        } finally {
            player.release();
        }
    }
}
