package org.watermedia.api.codecs.encoders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.common.png.ACTL;
import org.watermedia.api.codecs.common.png.FCTL;
import org.watermedia.api.codecs.common.png.FDAT;
import org.watermedia.api.codecs.common.png.IDAT;
import org.watermedia.api.codecs.common.png.IEND;
import org.watermedia.api.codecs.common.png.IHDR;
import org.watermedia.api.codecs.common.png.PngChunk;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.ImageBytes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the house-made PNG encoder by decoding what it writes: still pictures in every accepted layout, adaptive
 * filtering, APNG animations with cropped frames, sequenced chunks and exact timing, and the refusals of its contract.
 */
@DisplayName("PNG encoder")
public class PngEncoderTest {
    private static final int FPS = 30;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ENCODES THE FRAMES AT THEIR TIMESTAMPS INTO MEMORY THROUGH CodecsAPI
    private static byte[] encode(final int width, final int height, final PixelFormat format, final int repeat, final long[] ptsUs, final byte[]... frames) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final Encoder.Video video = new Encoder.Video(VideoCodec.PNG, width, height, format, FPS, Encoder.DEFAULT_QUALITY, repeat);
        try (final Encoder encoder = CodecsAPI.encoder(Container.PNG, out, video, null)) {
            for (int f = 0; f < frames.length; f++) encoder.video(ByteBuffer.wrap(frames[f]), ptsUs[f]);
        }
        return out.toByteArray();
    }

    // DECODES IN THE LAYOUT CLOSEST TO format AND MATCHES EVERY FRAME BYTE FOR BYTE; RGB COMES BACK AS OPAQUE RGBA
    private static ImageData assertFrames(final byte[] png, final PixelFormat format, final byte[]... frames) throws IOException {
        final ImageData image = CodecsAPI.decode(png, format == PixelFormat.RGB ? PixelFormat.RGBA : format);
        assertEquals(frames.length, image.frames().length, "frame count");
        for (int f = 0; f < frames.length; f++) {
            final byte[] expected = format == PixelFormat.RGB ? new byte[frames[f].length / 3 * 4] : frames[f];
            if (format == PixelFormat.RGB) for (int i = 0, o = 0; i < frames[f].length; i += 3, o += 4) {
                System.arraycopy(frames[f], i, expected, o, 3);
                expected[o + 3] = (byte) 0xFF;
            }
            final byte[] actual = new byte[image.frames()[f].remaining()];
            image.frames()[f].duplicate().get(actual);
            assertArrayEquals(expected, actual, "frame " + f);
        }
        return image;
    }

    // SPLITS A PNG FILE INTO ITS CHUNKS, CHECKING THE SIGNATURE AND EVERY CRC
    private static List<PngChunk> chunks(final byte[] png) throws IOException {
        final ByteBuffer buffer = ByteBuffer.wrap(png);
        final byte[] signature = new byte[ImageBytes.PNG_SIGNATURE.length];
        buffer.get(signature);
        assertArrayEquals(ImageBytes.PNG_SIGNATURE, signature);
        final List<PngChunk> chunks = new ArrayList<>();
        while (buffer.hasRemaining()) {
            final PngChunk chunk = PngChunk.read(buffer);
            assertFalse(chunk.corrupted(), chunk.typeName() + " CRC");
            chunks.add(chunk);
        }
        return chunks;
    }

    // CHECKS THE APNG LAYOUT (IHDR, acTL, THE FIRST fcTL AHEAD OF IDAT, THEN fcTL AND fdAT PER FRAME, IEND) AND THE
    // SEQUENCE NUMBERS fcTL AND fdAT SHARE FROM ZERO, RETURNING EVERY fcTL IN ORDER
    private static List<FCTL> controls(final byte[] png) throws IOException {
        final List<PngChunk> chunks = chunks(png);
        assertEquals(IHDR.SIGNATURE, chunks.get(0).type(), "IHDR comes first");
        assertEquals(ACTL.SIGNATURE, chunks.get(1).type(), "acTL precedes the image data");
        assertEquals(FCTL.SIGNATURE, chunks.get(2).type(), "the first frame is the default image");
        assertEquals(IEND.SIGNATURE, chunks.get(chunks.size() - 1).type(), "IEND comes last");
        final List<FCTL> controls = new ArrayList<>();
        int sequence = 0;
        for (int i = 2; i < chunks.size() - 1; i++) {
            final PngChunk chunk = chunks.get(i);
            if (chunk.type() == FCTL.SIGNATURE) {
                final FCTL control = FCTL.convert(chunk);
                assertEquals(sequence++, control.sequence(), "fcTL sequence");
                assertEquals(FCTL.DISPOSE_OP_NONE, control.dispose());
                assertEquals(FCTL.BLEND_OP_SOURCE, control.blend());
                final int next = chunks.get(i + 1).type();
                assertTrue(next == (controls.isEmpty() ? IDAT.SIGNATURE : FDAT.SIGNATURE), "frame " + controls.size() + " has data");
                controls.add(control);
            } else if (chunk.type() == FDAT.SIGNATURE) {
                assertTrue(controls.size() > 1, "fdAT only carries later frames");
                assertEquals(sequence++, FDAT.convert(chunk).sequence(), "fdAT sequence");
            } else {
                assertEquals(IDAT.SIGNATURE, chunk.type(), "unexpected " + chunk.typeName());
                assertEquals(1, controls.size(), "IDAT only carries the first frame");
            }
        }
        assertEquals(controls.size(), ACTL.convert(chunks.get(1)).frameCount(), "acTL counts every frame");
        return controls;
    }

    // MATCHES THE REGION AN fcTL COVERS
    private static void assertRegion(final FCTL control, final int x, final int y, final int width, final int height) {
        assertEquals(List.of(x, y, width, height), List.of(control.xOffset(), control.yOffset(), control.width(), control.height()), "fcTL " + control.sequence() + " region");
    }

    // A SMOOTH GRADIENT OVER THE TOP HALF AND SEEDED NOISE BELOW; ANOTHER SEED CHANGES EVERY ROW
    private static byte[] picture(final int width, final int height, final int channels, final int seed) {
        final Random random = new Random(seed);
        final byte[] out = new byte[width * height * channels];
        for (int y = 0, o = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                for (int c = 0; c < channels; c++, o++) out[o] = (byte) (y < height / 2 ? x * 3 + y * 5 + c * 40 + seed : random.nextInt(256));
        return out;
    }

    // COPIES A FRAME WITH THE BITS OF A RECTANGLE FLIPPED, SO EVERY BYTE IN IT CHANGES
    private static byte[] flip(final byte[] frame, final int width, final int channels, final int x, final int y, final int w, final int h) {
        final byte[] out = frame.clone();
        for (int row = y; row < y + h; row++)
            for (int i = (row * width + x) * channels; i < (row * width + x + w) * channels; i++) out[i] ^= 0x5A;
        return out;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Still pictures")
    class Still {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = { "RGBA", "BGRA", "RGB", "GRAY" })
        @DisplayName("One frame round-trips pixel-exact as a plain PNG without animation chunks")
        void roundTrip(final PixelFormat format) throws IOException {
            final byte[] frame = picture(37, 23, format.texelBytes(0, 8), 1);
            final byte[] png = encode(37, 23, format, 0, new long[] { 0L }, frame);

            final List<PngChunk> chunks = chunks(png);
            final IHDR header = IHDR.convert(chunks.get(0));
            assertEquals(List.of(37, 23, 8, 0), List.of(header.width(), header.height(), header.depth(), header.interlace()));
            assertEquals(switch (format) {
                case RGB -> 2;
                case GRAY -> 0;
                default -> 6;
            }, header.colorType(), "BGRA is stored as RGBA");
            for (final PngChunk chunk: chunks)
                assertTrue(chunk.type() == IHDR.SIGNATURE || chunk.type() == IDAT.SIGNATURE || chunk.type() == IEND.SIGNATURE, chunk.typeName());
            assertEquals(IEND.SIGNATURE, chunks.get(chunks.size() - 1).type());

            final ImageData image = assertFrames(png, format, frame);
            assertArrayEquals(new long[] { 0L }, image.delays());
            assertEquals(1, image.repeat(), "a still picture plays once");
        }

        @Test
        @DisplayName("Rows pick the filter with the smallest sum and decode exactly")
        void adaptiveFiltering() throws IOException {
            // EACH BAND OF ROWS FAVOURS ONE FILTER: PAETH, SUB THEN UP, NOISE, AVERAGE AND NONE
            final int width = 64, height = 45;
            final Random random = new Random(7);
            final int[] column = new int[width];
            for (int x = 0; x < width; x++) column[x] = 128 + random.nextInt(128);
            final byte[] frame = new byte[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final int o = y * width + x;
                    final int above = y > 0 ? frame[o - width] & 0xFF : 0;
                    if (y < 16) frame[o] = (byte) (x < width / 2 ? 7 * y : column[x]);
                    else if (y < 32) frame[o] = (byte) x;
                    else if (y < 40) frame[o] = (byte) random.nextInt(256);
                    else if (y < 44) frame[o] = (byte) (((x > 0 ? frame[o - 1] & 0xFF : 0) + above) >> 1);
                    else frame[o] = (byte) (x & 1);
                }
            }
            final byte[] png = encode(width, height, PixelFormat.GRAY, 0, new long[] { 0L }, frame);
            assertFrames(png, PixelFormat.GRAY, frame);

            final ByteArrayOutputStream zlib = new ByteArrayOutputStream();
            for (final PngChunk chunk: chunks(png)) if (chunk.type() == IDAT.SIGNATURE) zlib.writeBytes(chunk.data());
            final byte[] filtered = new InflaterInputStream(new ByteArrayInputStream(zlib.toByteArray())).readAllBytes();
            assertEquals((width + 1) * height, filtered.length);
            for (int y = 1; y < 16; y++) assertEquals(4, filtered[y * (width + 1)], "row " + y + " takes Paeth");
            assertEquals(1, filtered[16 * (width + 1)], "the first ramp row takes Sub");
            for (int y = 17; y < 32; y++) assertEquals(2, filtered[y * (width + 1)], "row " + y + " repeats the one above and takes Up");
            for (int y = 40; y < 44; y++) assertEquals(3, filtered[y * (width + 1)], "row " + y + " takes Average");
            assertEquals(0, filtered[44 * (width + 1)], "the row of zeros and ones takes None");
        }
    }

    @Nested
    @DisplayName("Animation")
    class Animation {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = { "RGBA", "BGRA", "RGB", "GRAY" })
        @DisplayName("Every frame round-trips pixel-exact with cropped regions, millisecond delays and the exact length")
        void roundTrip(final PixelFormat format) throws IOException {
            final int channels = format.texelBytes(0, 8);
            final byte[] first = picture(48, 32, channels, 1);
            final byte[] moved = flip(first, 48, channels, 10, 5, 6, 4);
            final byte[] fresh = picture(48, 32, channels, 2);
            final byte[] corner = flip(fresh, 48, channels, 47, 31, 1, 1);
            final byte[] spread = flip(flip(corner, 48, channels, 0, 0, 1, 1), 48, channels, 47, 31, 1, 1);
            final long[] ptsUs = { 0L, 33_333L, 66_667L, 100_250L, 180_100L, 250_999L };
            final byte[] png = encode(48, 32, format, 0, ptsUs, first, moved, moved, fresh, corner, spread);

            final List<FCTL> controls = controls(png);
            assertRegion(controls.get(0), 0, 0, 48, 32);
            assertRegion(controls.get(1), 10, 5, 6, 4);
            assertRegion(controls.get(2), 0, 0, 1, 1);
            assertRegion(controls.get(3), 0, 0, 48, 32);
            assertRegion(controls.get(4), 47, 31, 1, 1);
            assertRegion(controls.get(5), 0, 0, 48, 32);

            final ImageData image = assertFrames(png, format, first, moved, moved, fresh, corner, spread);
            final long totalUs = ptsUs[5] + 1_000_000L / FPS;
            for (int f = 0; f < ptsUs.length; f++) {
                final long durationUs = (f + 1 < ptsUs.length ? ptsUs[f + 1] : totalUs) - ptsUs[f];
                assertTrue(Math.abs(image.delays()[f] * 1_000L - durationUs) < 1_000L, "frame " + f + " lasts " + image.delays()[f] + " ms for " + durationUs + " us");
            }
            assertEquals(Math.floorDiv(totalUs + 500L, 1_000L), image.duration(), "the rounding never drifts the animation length");
            assertEquals(ImageData.REPEAT_FOREVER, image.repeat());
        }

        @ParameterizedTest(name = "{0} plays")
        @ValueSource(ints = { 0, 1, 3 })
        @DisplayName("The repeat count becomes the acTL play count")
        void repeat(final int plays) throws IOException {
            final byte[] png = encode(8, 8, PixelFormat.RGBA, plays, new long[] { 0L, 100_000L }, picture(8, 8, 4, 1), picture(8, 8, 4, 2));
            assertEquals(plays, ACTL.convert(chunks(png).get(1)).repeat());
            assertEquals(plays, CodecsAPI.decode(png).repeat(), "0 plays forever");
        }

        @Test
        @DisplayName("Unchanged and slightly changed frames store only their changed region")
        void croppedFrames() throws IOException {
            final byte[] first = picture(64, 64, 4, 3);
            final byte[] pixel = first.clone();
            // ONLY THE ALPHA OF PIXEL (10, 20)
            pixel[(20 * 64 + 10) * 4 + 3] ^= 1;
            final byte[] block = flip(pixel, 64, 4, 40, 50, 5, 3);
            final byte[] png = encode(64, 64, PixelFormat.RGBA, 0, new long[] { 0L, 40_000L, 80_000L, 120_000L }, first, first, pixel, block);

            final List<FCTL> controls = controls(png);
            assertRegion(controls.get(0), 0, 0, 64, 64);
            assertRegion(controls.get(1), 0, 0, 1, 1);
            assertRegion(controls.get(2), 10, 20, 1, 1);
            assertRegion(controls.get(3), 40, 50, 5, 3);
            assertFrames(png, PixelFormat.RGBA, first, first, pixel, block);
            final byte[] still = encode(64, 64, PixelFormat.RGBA, 0, new long[] { 0L }, first);
            assertTrue(png.length < still.length * 2L, "four frames take " + png.length + " bytes, one full frame " + still.length);
        }

        @Test
        @DisplayName("Large frames split their data across sequenced IDAT and fdAT chunks")
        void largeFrames() throws IOException {
            final byte[] first = picture(300, 300, 4, 4);
            final byte[] second = picture(300, 300, 4, 5);
            final byte[] png = encode(300, 300, PixelFormat.RGBA, 0, new long[] { 0L, 50_000L }, first, second);
            controls(png);
            final List<PngChunk> chunks = chunks(png);
            assertTrue(chunks.stream().filter(chunk -> chunk.type() == IDAT.SIGNATURE).count() > 1, "the first frame spans several IDAT");
            assertTrue(chunks.stream().filter(chunk -> chunk.type() == FDAT.SIGNATURE).count() > 1, "the second frame spans several fdAT");
            assertFrames(png, PixelFormat.RGBA, first, second);
        }

        @Test
        @DisplayName("Long pauses switch to a coarser delay unit and keep their length")
        void longPauses() throws IOException {
            final long[] ptsUs = { 0L, 70_000_000L, 2_070_000_000L };
            final byte[] png = encode(4, 4, PixelFormat.RGBA, 0, ptsUs, picture(4, 4, 4, 1), picture(4, 4, 4, 2), picture(4, 4, 4, 3));
            final List<FCTL> controls = controls(png);
            final List<List<Integer>> delays = new ArrayList<>();
            for (final FCTL control: controls) delays.add(List.of(control.delayNum() & 0xFFFF, control.delayDen() & 0xFFFF));
            assertEquals(List.of(List.of(7_000, 100), List.of(20_000, 10), List.of(33, 1_000)), delays);
            assertArrayEquals(new long[] { 70_000L, 2_000_000L, 33L }, CodecsAPI.decode(png).delays());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = { "RGBA", "BGRA", "RGB", "GRAY" })
        @DisplayName("A decoder without APNG support, the JDK's, shows the first frame")
        void defaultImage(final PixelFormat format) throws IOException {
            final int channels = format.texelBytes(0, 8);
            final byte[] first = picture(300, 200, channels, 6);
            final byte[] png = encode(300, 200, format, 0, new long[] { 0L, 100_000L }, first, picture(300, 200, channels, 7));
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            // THE RASTER HANDS BACK RGBA ORDER, SO A BGRA SOURCE TRADES ITS RED AND BLUE
            final int[] expected = new int[first.length];
            for (int i = 0; i < first.length; i++) expected[i] = first[format == PixelFormat.BGRA && (i & 1) == 0 ? i ^ 2 : i] & 0xFF;
            assertArrayEquals(expected, image.getRaster().getPixels(0, 0, 300, 200, (int[]) null));
        }
    }

    @Nested
    @DisplayName("Contract")
    class Contract {

        @Test
        @DisplayName("Pixel formats other than RGBA, BGRA, RGB and GRAY are refused")
        void refusedFormats() {
            for (final PixelFormat format: PixelFormat.values()) {
                if (format.compressed() || format == PixelFormat.RGBA || format == PixelFormat.BGRA || format == PixelFormat.RGB || format == PixelFormat.GRAY) continue;
                final Encoder.Video video = new Encoder.Video(VideoCodec.PNG, 4, 4, format, FPS);
                assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.PNG, new ByteArrayOutputStream(), video, null), format.name());
            }
        }

        @Test
        @DisplayName("Closing without frames throws IllegalStateException, writes nothing and closes the stream once")
        void noFrames() throws IOException {
            final boolean[] closed = { false };
            final ByteArrayOutputStream out = new ByteArrayOutputStream() {
                @Override
                public void close() {
                    assertFalse(closed[0], "the stream closes once");
                    closed[0] = true;
                }
            };
            final Encoder encoder = CodecsAPI.encoder(Container.PNG, out, new Encoder.Video(VideoCodec.PNG, 4, 4, PixelFormat.RGBA, FPS), null);
            assertThrows(IllegalStateException.class, encoder::close);
            assertTrue(closed[0]);
            assertEquals(0, out.size());
            assertDoesNotThrow(encoder::close, "a second close does nothing");
        }

        @Test
        @DisplayName("Quality does not change the lossless output")
        void qualityIgnored() throws IOException {
            final byte[] frame = picture(20, 20, 4, 8);
            final ByteArrayOutputStream lowest = new ByteArrayOutputStream();
            final ByteArrayOutputStream highest = new ByteArrayOutputStream();
            try (final Encoder encoder = CodecsAPI.encoder(Container.PNG, lowest, new Encoder.Video(VideoCodec.PNG, 20, 20, PixelFormat.RGBA, FPS, 0, 0), null)) {
                encoder.video(ByteBuffer.wrap(frame), 0L);
            }
            try (final Encoder encoder = CodecsAPI.encoder(Container.PNG, highest, new Encoder.Video(VideoCodec.PNG, 20, 20, PixelFormat.RGBA, FPS, 100, 0), null)) {
                encoder.video(ByteBuffer.wrap(frame), 0L);
            }
            assertArrayEquals(lowest.toByteArray(), highest.toByteArray());
        }

        @Test
        @DisplayName("CodecsAPI.encoder writes an animation into a file")
        void writesFile() throws IOException {
            // UNDER build/ INSTEAD OF @TempDir: DELETING A JUST-WRITTEN FILE FLAKES AGAINST WINDOWS AV SCANS
            final Path file = Path.of("build", "test-png-encoder", "animation.png");
            Files.createDirectories(file.getParent());
            final byte[][] frames = { picture(24, 16, 4, 9), picture(24, 16, 4, 10), picture(24, 16, 4, 11) };
            try (final Encoder encoder = CodecsAPI.encoder(Container.PNG, file, new Encoder.Video(VideoCodec.PNG, 24, 16, PixelFormat.RGBA, FPS), null)) {
                for (int f = 0; f < frames.length; f++) encoder.video(ByteBuffer.wrap(frames[f]), f * 100_000L);
            }
            final ImageData image = assertFrames(Files.readAllBytes(file), PixelFormat.RGBA, frames);
            assertArrayEquals(new long[] { 100L, 100L, 33L }, image.delays());
        }
    }
}
