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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies the {@link FFMediaPlayer} decode pipeline and upload scaling on a local H.264 MP4, video only through a {@link HeadlessGFXEngine}. */
@DisplayName("FFMediaPlayer mp4")
public class FFMediaPlayerTest {

    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long PLAY_OBSERVE_MS = 8_000L;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer tests");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // OPENS A VIDEO-ONLY FF PLAYER ON THE MP4 FIXTURE, APPLIES THE SCALING CONFIG, STARTS IT AND WAITS UNTIL THE DECODE
    // PIPELINE IS LIVE (THE SOURCE OPENED AND THE ENGINE WAS CONFIGURED BEFORE THE CLOCK LEFT LOADING)
    private static FFMediaPlayer open(final HeadlessGFXEngine gfx, final Consumer<FFMediaPlayer> config) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
        config.accept(player);
        assertTrue(player.start());
        assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING, Status.BUFFERING, Status.PAUSED));
        return player;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Playback")
    class Playback {

        @Test
        @DisplayName("Plays and uploads frames at the native size")
        void testPlaysAndUploadsNativeSize() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final FFMediaPlayer player = open(gfx, p -> {});
            try {
                assertTrue(player.canPlay());
                assertTrue(player.sourceWidth() > 0);
                assertTrue(player.sourceHeight() > 0);
                assertEquals(player.sourceWidth(), player.width());
                assertEquals(player.sourceHeight(), player.height());
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                assertNotNull(gfx.format());
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Scaling")
    class Scaling {

        @Test
        @DisplayName("scale caps each axis of the upload below the native size")
        void testScaleCapsUploadDimensions() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final FFMediaPlayer player = open(gfx, p -> p.scale(160, 90));
            try {
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                assertTrue(player.sourceWidth() > 160 && player.sourceHeight() > 90, "the fixture must be larger than the cap");
                assertEquals(MediaPlayer.scaled(player.sourceWidth(), 160, 100), player.width());
                assertEquals(MediaPlayer.scaled(player.sourceHeight(), 90, 100), player.height());
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("LOD reduces the upload dimensions")
        void testLodReducesUploadDimensions() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final FFMediaPlayer player = open(gfx, p -> p.lod(LodLevel.NEAR));
            try {
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                assertEquals(MediaPlayer.scaled(player.sourceWidth(), 0, LodLevel.NEAR.percent()), player.width());
                assertEquals(MediaPlayer.scaled(player.sourceHeight(), 0, LodLevel.NEAR.percent()), player.height());
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("Hot LOD change shrinks the live upload")
        void testHotLodChangeShrinksUpload() {
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final FFMediaPlayer player = open(gfx, p -> {});
            try {
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS));
                final int fullWidth = player.width();
                assertEquals(player.sourceWidth(), fullWidth);

                // THE NEXT DECODED FRAME RESOLVES THE NEW TARGET
                player.lod(LodLevel.FAR_AWAY);
                assertTrue(PlayerWait.awaitCondition(() -> player.width() < fullWidth, PLAY_OBSERVE_MS),
                        "upload width should shrink after a hot LOD change");
            } finally {
                player.release();
            }
        }
    }
}
