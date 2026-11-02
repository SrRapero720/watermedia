package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.player.Colorimetry.Matrix;
import org.watermedia.api.player.Colorimetry.Transfer;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.test.support.YuvFrames;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies {@link Colorimetry}: untagged guesses, shader constants, FFmpeg tag mapping and the tags a stream hands the engine. */
@DisplayName("Colorimetry")
public class ColorimetryTest {

    private static final float EPSILON = 1e-4f;

    @Nested
    @DisplayName("Untagged guess")
    class GuessTests {

        @Test
        @DisplayName("SD sizes guess BT.601 and HD sizes BT.709, limited range with MPEG-2 siting except full-range gray")
        void guessesBySize() {
            assertEquals(Matrix.BT601, Colorimetry.guess(PixelFormat.YUV420P, 720, 576).matrix());
            assertEquals(Matrix.BT601, Colorimetry.guess(PixelFormat.YUV420P, 853, 480).matrix());
            assertEquals(Matrix.BT709, Colorimetry.guess(PixelFormat.YUV420P, 1_280, 720).matrix());
            assertEquals(Matrix.BT709, Colorimetry.guess(PixelFormat.YUV420P, 1_024, 768).matrix(), "more than 576 lines is HD");
            final Colorimetry yuv = Colorimetry.guess(PixelFormat.NV12, 1_920, 1_080);
            assertEquals(new Colorimetry(Matrix.BT709, false, true, false, Transfer.SDR, false), yuv);
            assertTrue(Colorimetry.guess(PixelFormat.GRAY, 640, 480).full(), "gray is full range");
            assertEquals(Matrix.BT601, Colorimetry.guess(null, 640, 480).matrix(), "an unmapped format still gets a guess");
        }
    }

    @Nested
    @DisplayName("Shader constants")
    class ConstantTests {

        @Test
        @DisplayName("Limited and full range scale every depth from its own code range")
        void rangeConstants() {
            final Colorimetry limited = new Colorimetry(Matrix.BT709, false, true, false, Transfer.SDR, false);
            final Colorimetry full = new Colorimetry(Matrix.BT709, true, true, false, Transfer.SDR, false);
            assertRange(new float[] { 255f / 219f, 16f / 219f, 255f / 224f, 128f / 224f }, limited.constants(PixelFormat.YUV420P, 4, 4, 8));
            assertRange(new float[] { 1_023f / 876f, 16f / 219f, 1_023f / 896f, 128f / 224f }, limited.constants(PixelFormat.YUV420P, 4, 4, 10));
            assertRange(new float[] { 65_535f / 56_064f, 16f / 219f, 65_535f / 57_344f, 128f / 224f }, limited.constants(PixelFormat.NV12, 4, 4, 16));
            assertRange(new float[] { 1f, 0f, 1f, 128f / 255f }, full.constants(PixelFormat.YUV420P, 4, 4, 8));
            assertRange(new float[] { 1f, 0f, 1f, 512f / 1_023f }, full.constants(PixelFormat.YUV420P, 4, 4, 10));
        }

        @Test
        @DisplayName("Matrix constants are the standard BT.601, BT.709 and BT.2020 coefficients")
        void matrixConstants() {
            assertMatrix(new float[] { 1.402f, 0.34414f, 0.71414f, 1.772f }, Matrix.BT601);
            assertMatrix(new float[] { 1.5748f, 0.18732f, 0.46812f, 1.8556f }, Matrix.BT709);
            assertMatrix(new float[] { 1.4746f, 0.16455f, 0.57135f, 1.8814f }, Matrix.BT2020);
        }

        @Test
        @DisplayName("Chroma coordinates follow the rounded-up plane and the siting, and stay identity without subsampling")
        void chromaConstants() {
            final Colorimetry cosited = new Colorimetry(Matrix.BT709, false, true, true, Transfer.SDR, false);
            final Colorimetry centered = new Colorimetry(Matrix.BT709, false, false, false, Transfer.SDR, false);
            assertArrayEquals(new float[] { 853f / 854f, 481f / 482f, 0.25f / 427f, 0.25f / 241f },
                    slice(cosited.constants(PixelFormat.YUV420P, 853, 481, 8), 8), EPSILON);
            assertArrayEquals(new float[] { 1f, 1f, 0f, 0f }, slice(centered.constants(PixelFormat.NV12, 4, 4, 8), 8), EPSILON);
            assertArrayEquals(new float[] { 1f, 1f, 0.25f / 2f, 0f }, slice(cosited.constants(PixelFormat.YUV422P, 4, 4, 8), 8), EPSILON,
                    "4:2:2 subsamples only horizontally");
            assertArrayEquals(new float[] { 1f, 1f, 0f, 0f }, slice(cosited.constants(PixelFormat.YUV444P, 5, 3, 8), 8), EPSILON);
            assertArrayEquals(new float[] { 1f, 1f, 0f, 0f }, slice(cosited.constants(PixelFormat.YUYV, 5, 3, 8), 8), EPSILON,
                    "packed pairs fetch their own chroma");
        }

        @Test
        @DisplayName("Display constants carry the transfer and the BT.2020 primaries")
        void displayConstants() {
            assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, slice(Colorimetry.guess(PixelFormat.YUV420P, 4, 4).constants(PixelFormat.YUV420P, 4, 4, 8), 12));
            final Colorimetry pq = new Colorimetry(Matrix.BT2020, false, true, false, Transfer.PQ, true);
            assertArrayEquals(new float[] { 1f, 1f, 0f, 0f }, slice(pq.constants(PixelFormat.YUV420P, 4, 4, 10), 12));
            final Colorimetry hlg = new Colorimetry(Matrix.BT2020, false, true, false, Transfer.HLG, false);
            assertArrayEquals(new float[] { 2f, 0f, 0f, 0f }, slice(hlg.constants(PixelFormat.YUV420P, 4, 4, 10), 12));
        }

        private static void assertRange(final float[] expected, final float[] constants) {
            assertArrayEquals(expected, slice(constants, 0), EPSILON);
        }

        private static void assertMatrix(final float[] expected, final Matrix matrix) {
            final Colorimetry color = new Colorimetry(matrix, false, true, false, Transfer.SDR, false);
            assertArrayEquals(expected, slice(color.constants(PixelFormat.YUV420P, 4, 4, 8), 4), EPSILON, matrix.name());
        }

        private static float[] slice(final float[] constants, final int from) {
            final float[] out = new float[4];
            System.arraycopy(constants, from, out, 0, 4);
            return out;
        }
    }

    @Nested
    @DisplayName("FFmpeg tags")
    class StreamTests {

        @Test
        @DisplayName("Tags map to matrix, range, siting, curve and primaries; untagged gray and YUVJ frames are full range")
        void tagsMap() throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping");
            final Method map = FFMediaPlayer.class.getDeclaredMethod("colorimetry", int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
            map.setAccessible(true);
            final Colorimetry hdr = (Colorimetry) map.invoke(null, AV_PIX_FMT_YUV420P10LE, 3_840, 2_160, AVCOL_SPC_BT2020_NCL, AVCOL_RANGE_MPEG,
                    AVCHROMA_LOC_TOPLEFT, AVCOL_TRC_SMPTE2084, AVCOL_PRI_BT2020);
            assertEquals(new Colorimetry(Matrix.BT2020, false, true, true, Transfer.PQ, true), hdr);
            final Colorimetry sd = (Colorimetry) map.invoke(null, AV_PIX_FMT_YUV420P, 1_920, 1_080, AVCOL_SPC_SMPTE170M, AVCOL_RANGE_UNSPECIFIED,
                    AVCHROMA_LOC_CENTER, AVCOL_TRC_ARIB_STD_B67, AVCOL_PRI_UNSPECIFIED);
            assertEquals(new Colorimetry(Matrix.BT601, false, false, false, Transfer.HLG, false), sd, "tags beat the HD guess");
            for (final int gray: new int[] { AV_PIX_FMT_GRAY8, AV_PIX_FMT_YA8, AV_PIX_FMT_GRAY14LE, AV_PIX_FMT_YUVJ420P, AV_PIX_FMT_YUVJ440P }) {
                final Colorimetry untagged = (Colorimetry) map.invoke(null, gray, 640, 480, AVCOL_SPC_UNSPECIFIED, AVCOL_RANGE_UNSPECIFIED,
                        AVCHROMA_LOC_UNSPECIFIED, AVCOL_TRC_UNSPECIFIED, AVCOL_PRI_UNSPECIFIED);
                assertTrue(untagged.full(), "format " + gray + " defaults to full range");
                assertEquals(Matrix.BT601, untagged.matrix(), "an untagged SD frame guesses BT.601");
            }
            final Colorimetry limited = (Colorimetry) map.invoke(null, AV_PIX_FMT_YUVJ420P, 640, 480, AVCOL_SPC_UNSPECIFIED, AVCOL_RANGE_MPEG,
                    AVCHROMA_LOC_UNSPECIFIED, AVCOL_TRC_UNSPECIFIED, AVCOL_PRI_UNSPECIFIED);
            assertFalse(limited.full(), "an explicit range tag wins over the YUVJ default");
            assertFalse(((Colorimetry) map.invoke(null, AV_PIX_FMT_NV12, 640, 480, AVCOL_SPC_UNSPECIFIED, AVCOL_RANGE_UNSPECIFIED,
                    AVCHROMA_LOC_UNSPECIFIED, AVCOL_TRC_UNSPECIFIED, AVCOL_PRI_UNSPECIFIED)).full(), "untagged YUV is limited range");
        }

        @Test
        @DisplayName("A WebM tagged BT.709 limited reaches the engine as BT.709 even at an SD size, with BT.709 luma")
        void webmTagsReachTheEngine(@TempDir final Path directory) throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping");
            // A MAGENTA WHOSE LUMA DIFFERS BY ABOUT 23 CODES BETWEEN BT.601 AND BT.709
            final int r = 230, g = 20, b = 230;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            // 256x160 IS SD, SO ONLY THE TAG MAKES IT BT.709, AND LARGE ENOUGH FOR HARDWARE VP9 DECODERS (128 PX MINIMUM)
            try (final Encoder writer = CodecsAPI.encoder(Container.WEBM, out, new Encoder.Video(VideoCodec.VP9, 256, 160, PixelFormat.BGRA, 30, 100, 0), null)) {
                final ByteBuffer frame = ByteBuffer.allocateDirect(256 * 160 * 4);
                for (int i = 0; i < 256 * 160; i++) frame.put((byte) b).put((byte) g).put((byte) r).put((byte) 0xFF);
                frame.flip();
                for (int f = 0; f < 10; f++) writer.video(frame, f * 1_000_000L / 30);
            }
            final Path file = directory.resolve("color.webm");
            Files.write(file, out.toByteArray());

            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(file));
            assertTrue(mrl.await(3_000L));
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            final MediaPlayer player = PlayerAPI.player(mrl, () -> gfx, () -> null);
            try {
                player.start();
                assertTrue(PlayerWait.awaitStatus(player, 15_000L, Status.PLAYING, Status.BUFFERING, Status.PAUSED, Status.ENDED));
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, 8_000L), "decoded frames reach the engine");
                final Colorimetry color = gfx.color;
                assertNotNull(color);
                assertEquals(Matrix.BT709, color.matrix(), "the tag wins over the BT.601 guess for 256x160");
                assertFalse(color.full(), "tagged limited range");
                assertEquals(Transfer.SDR, color.transfer());
                // SOFTWARE VP9 GIVES YUV420P, A HARDWARE DECODER NV12 (ITS COPY BACK TO RAM MUST KEEP THE TAGS); LUMA COMES FIRST IN BOTH
                assertTrue(gfx.format() == PixelFormat.YUV420P || gfx.format() == PixelFormat.NV12, "the engine converts the YUV itself: " + gfx.format());
                final ByteBuffer luma = gfx.lastUpload();
                final int y = luma.get(luma.position()) & 0xFF;
                final int expected = YuvFrames.ycbcr(0.2126, 0.0722, r / 255.0, g / 255.0, b / 255.0)[0];
                assertTrue(Math.abs(y - expected) <= 3, "BT.709 luma " + expected + ", decoded " + y);
            } finally {
                player.stop();
                player.release();
            }
        }
    }
}
