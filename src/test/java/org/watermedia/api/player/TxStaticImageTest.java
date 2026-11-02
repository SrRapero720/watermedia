package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the {@link TxMediaPlayer} static-image path end to end on an SVG: load, a single upload and the engine release. */
@DisplayName("TxMediaPlayer static image")
public class TxStaticImageTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("Static SVG loads, uploads one frame and its release frees the engine")
    void testStaticSvgLoadsAndUploadsOneFrame() {
        // EXERCISES MRL → TxMediaPlayer → openSource → SvgReader: openSource MUST NOT REJECT THE
        // SOURCE ON ITS CONTENT-TYPE HEADER (MRL ALREADY ROUTED IT AND CodecsAPI.reader VALIDATES THE BYTES)
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.SVG_DIR.resolve("car-filled-color.svg")));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
            assertTrue(player.canPlay());
            assertTrue(player.width() > 0);
            assertTrue(player.height() > 0);
            assertEquals(1L, gfx.uploadCount(), "a still uploads its single frame once");
        } finally {
            player.release();
        }
        assertTrue(gfx.released(), "the player's release frees its graphics engine");
    }
}
