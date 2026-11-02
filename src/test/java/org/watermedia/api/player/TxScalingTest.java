package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.LodLevel;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that {@link TxMediaPlayer} uploads at the size scale and {@link LodLevel} resolve to; {@link MediaPlayerBaseTest} pins the rule. */
@DisplayName("TxMediaPlayer scaling")
public class TxScalingTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long HOT_CHANGE_MS = 3_000L;
    // Fixtures.PNG_STATIC IS 64x40; THE CAP KEEPS ITS ASPECT
    private static final int NATIVE_WIDTH = 64;
    private static final int NATIVE_HEIGHT = 40;
    private static final int CAP_WIDTH = 32;
    private static final int CAP_HEIGHT = 20;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // BUILDS A STATIC-IMAGE PLAYER, APPLIES THE SCALING CONFIG, STARTS IT AND WAITS UNTIL THE FIRST FRAME IS LIVE SO
    // width()/height() REFLECT THE UPLOAD SIZE
    private static TxMediaPlayer startStatic(final HeadlessGFXEngine gfx, final Consumer<TxMediaPlayer> config) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        config.accept(player);
        assertTrue(player.start());
        assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
        return player;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Static image")
    class StaticImage {

        @Test
        @DisplayName("No scaling uploads the native size")
        void testNoScalingUploadsNativeSize() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final TxMediaPlayer player = startStatic(gfx, p -> {});
            try {
                assertEquals(NATIVE_WIDTH, player.sourceWidth());
                assertEquals(NATIVE_HEIGHT, player.sourceHeight());
                assertEquals(NATIVE_WIDTH, player.width());
                assertEquals(NATIVE_HEIGHT, player.height());
                assertEquals(1L, gfx.uploadCount(), "a still uploads its single frame once");
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("scale caps the upload below the native size")
        void testScaleCapsBelowNative() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final TxMediaPlayer player = startStatic(gfx, p -> p.scale(CAP_WIDTH, CAP_HEIGHT));
            try {
                assertEquals(CAP_WIDTH, player.width());
                assertEquals(CAP_HEIGHT, player.height());
                assertEquals(NATIVE_WIDTH, player.sourceWidth());
                assertEquals(NATIVE_HEIGHT, player.sourceHeight());
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("scale and LOD compose")
        void testScaleAndLodCompose() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final TxMediaPlayer player = startStatic(gfx, p -> {
                p.scale(CAP_WIDTH, CAP_HEIGHT);
                p.lod(LodLevel.NEAR);
            });
            try {
                // THE CAP FIRST, THEN 50% OF IT
                assertEquals(CAP_WIDTH / 2, player.width());
                assertEquals(CAP_HEIGHT / 2, player.height());
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Streaming animation")
    class Streaming {

        @Test
        @DisplayName("Hot LOD change shrinks the live upload")
        void testHotLodChangeShrinksUpload() {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));
            // NO FRAME-TEXTURE PATH: THE GIF RUNS THE STREAMING LOOP, WHICH RESOLVES THE UPLOAD SIZE ON EVERY ITERATION
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
                final int fullWidth = player.width();
                assertTrue(fullWidth > 0);
                assertEquals(player.sourceWidth(), fullWidth);

                player.lod(LodLevel.FAR_AWAY);
                assertTrue(PlayerWait.awaitCondition(() -> player.width() < fullWidth, HOT_CHANGE_MS),
                        "upload width should shrink after a hot LOD change");
            } finally {
                player.release();
            }
        }
    }
}
