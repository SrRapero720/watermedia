package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the {@link TxMediaPlayer} frame-texture mode: a capped animation preloads every frame at the target size. */
@DisplayName("TxMediaPlayer frame textures")
public class TxFrameTexturesTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final int CAP = 16;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("A capped animation preloads every frame at the target size")
    void cappedAnimationPreloadsScaledFrames() {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine(true);
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            player.scale(CAP, CAP);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));

            final ByteBuffer[] frames = gfx.preloadedFrames();
            assertNotNull(frames, "A capped animation within the budget must take the frame-texture path");
            assertTrue(frames.length > 1);
            assertEquals(0L, gfx.uploadCount(), "Preloaded animations never upload per frame");
            assertTrue(player.width() <= CAP && player.height() <= CAP);
            assertTrue(player.width() < player.sourceWidth() || player.height() < player.sourceHeight());
            // THE FILTER SHRANK EVERY FRAME WHILE DECODING: EACH BUFFER HOLDS EXACTLY THE TARGET-SIZE FRAME
            final long bytes = gfx.format().frameBytes(player.width(), player.height(), 8);
            for (final ByteBuffer frame: frames) assertEquals(bytes, frame.remaining());
        } finally {
            player.release();
        }
    }
}
