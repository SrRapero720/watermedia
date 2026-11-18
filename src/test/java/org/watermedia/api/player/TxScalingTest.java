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

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link TxMediaPlayer} uploads at the size {@code scale()} and {@link LodLevel} resolve to: a still at
 * preparation, a streaming animation on a hot LOD change and a preloaded frame set shrunk while decoding;
 * {@link MediaPlayerBaseTest} pins the sizing rule.
 */
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
    private static final int TEXTURE_CAP = 16;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // STARTS A STATIC-IMAGE PLAYER WITH THE SCALING CONFIG AND WAITS FOR ITS FIRST FRAME, SO width()/height() REFLECT THE
    // UPLOAD SIZE; A START THAT FAILS RELEASES THE PLAYER BEFORE THE FAILURE PROPAGATES
    private static TxMediaPlayer startStatic(final HeadlessGFXEngine gfx, final Consumer<TxMediaPlayer> config) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            config.accept(player);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the still went live, not " + player.status());
            return player;
        } catch (final RuntimeException | Error e) {
            player.release();
            throw e;
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Static image")
    class StaticImage {

        @Test
        @DisplayName("Without scale or LOD a still uploads once at its native size")
        void noScalingUploadsNativeSize() {
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
        @DisplayName("scale() caps the upload and leaves the source size alone")
        void scaleCapsBelowNative() {
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
        @DisplayName("LOD shrinks the scale() cap by its percentage")
        void scaleAndLodCompose() {
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
        @DisplayName("A hot LOD change shrinks the live upload")
        void hotLodChangeShrinksUpload() {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
            assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
            // NO FRAME-TEXTURE PATH: THE GIF RUNS THE STREAMING LOOP, WHICH RESOLVES THE UPLOAD SIZE ON EVERY ITERATION
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());
                final int fullWidth = player.width();
                assertTrue(fullWidth > 0, "the first frame configured the engine");
                assertEquals(player.sourceWidth(), fullWidth);

                player.lod(LodLevel.FAR_AWAY);
                assertTrue(PlayerWait.awaitCondition(() -> player.width() < fullWidth, HOT_CHANGE_MS),
                        "the frames decoded after the change upload at the smaller size");
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Frame textures")
    class FrameTextures {

        @Test
        @DisplayName("A capped animation preloads its frames at the target size and never uploads per frame")
        void cappedAnimationPreloadsScaledFrames() {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.GIF_ANIMATED));
            assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(true);
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
            try {
                player.scale(TEXTURE_CAP, TEXTURE_CAP);
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the animation went live, not " + player.status());

                final ByteBuffer[] frames = gfx.preloadedFrames();
                assertNotNull(frames, "a capped animation inside the budget takes the frame-texture path");
                assertTrue(frames.length > 1, "the animation preloads more than one frame");
                assertEquals(0L, gfx.uploadCount(), "preloaded animations never upload per frame");
                assertTrue(player.width() <= TEXTURE_CAP && player.height() <= TEXTURE_CAP,
                        () -> "the engine holds the capped size, not " + player.width() + "x" + player.height());
                assertTrue(player.width() < player.sourceWidth() || player.height() < player.sourceHeight(), "the target is smaller than the source");
                // THE FILTER SHRANK EVERY FRAME WHILE DECODING: EACH BUFFER HOLDS EXACTLY THE TARGET-SIZE FRAME
                final long bytes = gfx.format().frameBytes(player.width(), player.height(), 8);
                for (final ByteBuffer frame: frames) assertEquals(bytes, frame.remaining(), "a preloaded frame holds the target size");
            } finally {
                player.release();
            }
        }
    }
}
