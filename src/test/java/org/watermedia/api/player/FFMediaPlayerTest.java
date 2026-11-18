package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.LodLevel;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the upload size of {@link FFMediaPlayer} on a local H.264 MP4, video only through a {@link HeadlessGFXEngine}: the
 * native size, the cap of {@code scale}, a LOD level set before the start and a hot LOD change.
 */
@DisplayName("FFMediaPlayer upload size")
public class FFMediaPlayerTest {

    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long PLAY_OBSERVE_MS = 8_000L;
    // EVEN, AS EVERY SCALED AXIS IS
    private static final int CAP_WIDTH = 160;
    private static final int CAP_HEIGHT = 90;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer tests");
    }

    @Test
    @DisplayName("scale() caps each axis of the upload below the native size")
    void scaleCapsUploadDimensions() {
        final FFMediaPlayer player = open(p -> p.scale(CAP_WIDTH, CAP_HEIGHT));
        try {
            assertTrue(player.sourceWidth() > CAP_WIDTH && player.sourceHeight() > CAP_HEIGHT, "the fixture must be larger than the cap");
            assertEquals(CAP_WIDTH, player.width());
            assertEquals(CAP_HEIGHT, player.height());
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("A LOD level set before the start shrinks each axis of the upload by its percentage")
    void lodReducesUploadDimensions() {
        final FFMediaPlayer player = open(p -> p.lod(LodLevel.NEAR));
        try {
            assertEquals(MediaPlayer.scaled(player.sourceWidth(), 0, LodLevel.NEAR.percent()), player.width());
            assertEquals(MediaPlayer.scaled(player.sourceHeight(), 0, LodLevel.NEAR.percent()), player.height());
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("Without scaling the upload has the native size, and a hot LOD change shrinks it to the size of the level")
    void hotLodChangeShrinksUpload() {
        final FFMediaPlayer player = open(p -> {});
        try {
            assertEquals(player.sourceWidth(), player.width());
            assertEquals(player.sourceHeight(), player.height());

            // THE NEXT DECODED FRAME RESOLVES THE NEW TARGET
            final int width = MediaPlayer.scaled(player.sourceWidth(), 0, LodLevel.FAR_AWAY.percent());
            final int height = MediaPlayer.scaled(player.sourceHeight(), 0, LodLevel.FAR_AWAY.percent());
            player.lod(LodLevel.FAR_AWAY);
            assertTrue(PlayerWait.awaitCondition(() -> player.width() == width && player.height() == height, PLAY_OBSERVE_MS),
                    () -> "the upload must shrink to " + width + "x" + height + ", it is " + player.width() + "x" + player.height());
        } finally {
            player.release();
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A VIDEO-ONLY PLAYER ON THE MP4 FIXTURE WITH ITS SCALING CONFIG, WAITED UNTIL ITS FIRST FRAME REACHED THE ENGINE SO
    // width() AND height() ARE THE UPLOAD SIZE; A PLAYER THAT NEVER GETS THERE IS RELEASED BEFORE THE FAILURE LEAVES
    private static FFMediaPlayer open(final Consumer<FFMediaPlayer> config) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL must resolve");
        assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
        try {
            config.accept(player);
            assertTrue(player.start(), "a fresh player starts");
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING, Status.BUFFERING, Status.PAUSED),
                    () -> "the fixture must open, landed " + player.status());
            assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, PLAY_OBSERVE_MS), () -> "no frame reached the engine, status " + player.status());
            return player;
        } catch (final RuntimeException | Error e) {
            player.release();
            throw e;
        }
    }
}
