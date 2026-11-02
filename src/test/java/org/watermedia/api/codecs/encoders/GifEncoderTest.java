package org.watermedia.api.codecs.encoders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;
import org.watermedia.api.util.PixelFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the house-made GIF encoder by decoding what it writes: exact palettes in every accepted layout, one-bit
 * transparency including pixels that turn transparent, quantized gradients with and without dithering, delays, loop
 * counts, cropped frames and the refusals of its contract.
 */
@DisplayName("GIF encoder")
public class GifEncoderTest {
    private static final int FPS = 25;
    private static final long FRAME_US = 1_000_000L / FPS;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ENCODES THE FRAMES AT THEIR TIMESTAMPS INTO MEMORY THROUGH CodecsAPI
    private static byte[] encode(final int width, final int height, final PixelFormat format, final int quality, final int repeat,
                                 final long[] ptsUs, final byte[]... frames) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (final Encoder encoder = CodecsAPI.encoder(Container.GIF, out, new Encoder.Video(VideoCodec.GIF, width, height, format, FPS, quality, repeat), null)) {
            for (int f = 0; f < frames.length; f++) encoder.video(ByteBuffer.wrap(frames[f]), ptsUs[f]);
        }
        return out.toByteArray();
    }

    // DECODES THE GIF AND MATCHES EVERY COMPOSED FRAME AGAINST ITS SOURCE, THRESHOLDED TO ONE-BIT ALPHA
    private static ImageData assertComposition(final byte[] gif, final PixelFormat format, final byte[]... frames) throws IOException {
        final ImageData image = CodecsAPI.decode(gif);
        assertEquals(frames.length, image.frames().length, "frame count");
        for (int f = 0; f < frames.length; f++) assertArrayEquals(argb(frames[f], format), pixels(image, f), "frame " + f);
        return image;
    }

    // TIMESTAMPS OF count FRAMES AT THE TEST RATE
    private static long[] steps(final int count) {
        final long[] pts = new long[count];
        for (int f = 0; f < count; f++) pts[f] = f * FRAME_US;
        return pts;
    }

    // A TILED PICTURE OF 48 COLORS WITH A 10x8 PATCH MOVING EACH FRAME; ON ODD FRAMES THE PATCH IS TRANSPARENT WHEN THE
    // LAYOUT HAS ALPHA, AND EVERY ALPHA AND HIDDEN COLOR VARIES SO ONLY THE THRESHOLD DECIDES WHAT SHOWS
    private static byte[] picture(final int width, final int height, final PixelFormat format, final int frame) {
        final int channels = format.texelBytes(0, 8);
        final byte[] bytes = new byte[width * height * channels];
        for (int y = 0, p = 0; y < height; y++) {
            for (int x = 0; x < width; x++, p += channels) {
                final boolean patch = x >= frame * 4 % width && x < frame * 4 % width + 10 && y >= frame * 3 % height && y < frame * 3 % height + 8;
                final int k = patch ? 40 + frame % 8 : (x / 5 * 7 + y / 3 * 13) % 40;
                final int r = k * 37 & 0xFF, g = k * 91 & 0xFF, b = k * 53 & 0xFF;
                final boolean hidden = patch && (frame & 1) == 1;
                switch (format) {
                    case RGBA, BGRA -> {
                        bytes[p] = (byte) (format == PixelFormat.RGBA ? r : b);
                        bytes[p + 1] = (byte) g;
                        bytes[p + 2] = (byte) (format == PixelFormat.RGBA ? b : r);
                        bytes[p + 3] = (byte) (hidden ? (x + y) % 128 : 128 + (x * 7 + y) % 128);
                    }
                    case RGB -> {
                        bytes[p] = (byte) r;
                        bytes[p + 1] = (byte) g;
                        bytes[p + 2] = (byte) b;
                    }
                    default -> bytes[p] = (byte) (k * 6);
                }
            }
        }
        return bytes;
    }

    // BGRA BYTES OF A BACKGROUND WITH A 6x6 SQUARE AT (x, y); A ZERO COLOR IS TRANSPARENT AND CARRIES A STRAY COLOR AND
    // ALPHA BELOW 128 THAT THE ENCODER MUST IGNORE
    private static byte[] scene(final int width, final int height, final int background, final int square, final int x, final int y) {
        final ByteBuffer bytes = ByteBuffer.allocate(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                final int color = px >= x && px < x + 6 && py >= y && py < y + 6 ? square : background;
                bytes.putInt(color != 0 ? color : (px * 0x01_03_05 + py * 0x07_0B_0D) & 0x7F_FF_FF_FF);
            }
        }
        return bytes.array();
    }

    // THE ARGB A DECODER SHOWS FOR A SOURCE FRAME: ALPHA BELOW 128 IS TRANSPARENT BLACK, ANYTHING ELSE IS OPAQUE
    private static int[] argb(final byte[] frame, final PixelFormat format) {
        final int channels = format.texelBytes(0, 8);
        final int[] pixels = new int[frame.length / channels];
        for (int i = 0, p = 0; i < pixels.length; i++, p += channels) {
            pixels[i] = switch (format) {
                case RGBA -> (frame[p + 3] & 0xFF) < 128 ? 0 : 0xFF000000 | (frame[p] & 0xFF) << 16 | (frame[p + 1] & 0xFF) << 8 | frame[p + 2] & 0xFF;
                case BGRA -> (frame[p + 3] & 0xFF) < 128 ? 0 : 0xFF000000 | (frame[p + 2] & 0xFF) << 16 | (frame[p + 1] & 0xFF) << 8 | frame[p] & 0xFF;
                case RGB -> 0xFF000000 | (frame[p] & 0xFF) << 16 | (frame[p + 1] & 0xFF) << 8 | frame[p + 2] & 0xFF;
                default -> 0xFF000000 | (frame[p] & 0xFF) * 0x01_01_01;
            };
        }
        return pixels;
    }

    // DECODED BGRA READ AS LITTLE-ENDIAN INTS, WHICH IS ARGB
    private static int[] pixels(final ImageData image, final int frame) {
        final int[] pixels = new int[image.width() * image.height()];
        image.frames()[frame].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
    }

    // MEAN AND MAX CHANNEL ERROR, THEN THE MEAN ERROR OF THE 8x8 BLOCK AVERAGES THAT DITHERING PRESERVES
    private static double[] error(final int[] expected, final int[] actual, final int width) {
        long total = 0L;
        int max = 0;
        for (int i = 0; i < expected.length; i++) {
            for (int shift = 0; shift < 24; shift += 8) {
                final int diff = Math.abs((expected[i] >> shift & 0xFF) - (actual[i] >> shift & 0xFF));
                total += diff;
                max = Math.max(max, diff);
            }
        }
        final int height = expected.length / width;
        double blocks = 0.0;
        int count = 0;
        for (int by = 0; by + 8 <= height; by += 8) {
            for (int bx = 0; bx + 8 <= width; bx += 8, count++) {
                for (int shift = 0; shift < 24; shift += 8) {
                    long sum = 0L;
                    for (int y = by; y < by + 8; y++)
                        for (int x = bx; x < bx + 8; x++) sum += (expected[y * width + x] >> shift & 0xFF) - (actual[y * width + x] >> shift & 0xFF);
                    blocks += Math.abs(sum / 64.0);
                }
            }
        }
        return new double[] { (double) total / (expected.length * 3), max, blocks / (count * 3) };
    }

    // WALKS THE BLOCK STREAM AND RETURNS EVERY IMAGE WITH THE GRAPHIC CONTROL EXTENSION BEFORE IT
    private static List<Image> images(final byte[] gif) throws CodecException {
        final ByteBuffer buffer = ByteBuffer.wrap(gif);
        buffer.position(6);
        final ScreenDescriptor screen = ScreenDescriptor.read(buffer);
        if (screen.globalColorTableFlag()) ColorTable.read(screen.globalColorCount(), buffer);
        final List<Image> images = new ArrayList<>();
        GraphicExtension control = null;
        while (true) {
            final int introducer = buffer.get() & 0xFF;
            if (introducer == 0x3B) return images;
            if (introducer == 0x2C) {
                final ImageDescriptor descriptor = ImageDescriptor.read(buffer);
                if (descriptor.localColorTableFlag()) ColorTable.read(descriptor.localColorCount(), buffer);
                // SKIPS THE LZW MINIMUM CODE SIZE, THEN THE SUB-BLOCK CHAIN AND ITS TERMINATOR
                buffer.get();
                for (int size = buffer.get() & 0xFF; size > 0; size = buffer.get() & 0xFF) buffer.position(buffer.position() + size);
                images.add(new Image(descriptor, control));
                control = null;
                continue;
            }
            assertEquals(0x21, introducer, "only extensions sit between images");
            if ((buffer.get() & 0xFF) == GraphicExtension.GCE_LABEL) control = GraphicExtension.read(buffer);
            else for (int size = buffer.get() & 0xFF; size > 0; size = buffer.get() & 0xFF) buffer.position(buffer.position() + size);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Exact palettes")
    class Exact {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = { "RGBA", "BGRA", "RGB", "GRAY" })
        @DisplayName("Frames of at most 256 colors round-trip pixel-exact")
        void roundTrip(final PixelFormat format) throws IOException {
            final byte[][] frames = new byte[6][];
            for (int f = 0; f < frames.length; f++) frames[f] = picture(41, 29, format, f);
            assertComposition(encode(41, 29, format, Encoder.DEFAULT_QUALITY, 0, steps(frames.length), frames), format, frames);
        }

        @Test
        @DisplayName("Exactly 256 opaque colors keep an exact palette")
        void fullPalette() throws IOException {
            final byte[] gray = new byte[256];
            for (int i = 0; i < gray.length; i++) gray[i] = (byte) (i * 167);
            final byte[] gif = encode(16, 16, PixelFormat.GRAY, Encoder.DEFAULT_QUALITY, 0, steps(1), gray);
            assertComposition(gif, PixelFormat.GRAY, gray);
            assertEquals(7, images(gif).get(0).descriptor().localColorTableSize(), "256 colors take the full 8-bit table");
        }

        @Test
        @DisplayName("Noise of 256 colors crosses many LZW table resets and still round-trips, also through the JDK decoder")
        void noise() throws IOException {
            final Random random = new Random(7);
            final byte[] first = new byte[256 * 192 * 3];
            final int[] colors = new int[256];
            for (int i = 0; i < colors.length; i++) colors[i] = i * 0x01_0F_3B & 0xFF_FF_FF;
            for (int p = 0; p < first.length; p += 3) {
                final int c = colors[random.nextInt(colors.length)];
                first[p] = (byte) (c >> 16);
                first[p + 1] = (byte) (c >> 8);
                first[p + 2] = (byte) c;
            }
            // THE SECOND FRAME SHIFTS A RUN OF 20000 PIXELS ONE PLACE LEFT
            final byte[] second = first.clone();
            for (int p = 30_000; p < 90_000; p++) second[p] = first[p + 3];
            final byte[] gif = encode(256, 192, PixelFormat.RGB, Encoder.DEFAULT_QUALITY, 0, steps(2), first, second);
            assertComposition(gif, PixelFormat.RGB, first, second);

            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(gif));
            assertArrayEquals(argb(first, PixelFormat.RGB), image.getRGB(0, 0, 256, 192, null, 0, 256));
        }
    }

    @Nested
    @DisplayName("Transparency")
    class Transparency {

        @Test
        @DisplayName("Alpha below 128 is transparent and the rest is opaque")
        void threshold() throws IOException {
            final byte[] frame = new byte[4 * 4 * 4];
            final int[] alphas = { 0, 1, 127, 128, 129, 200, 254, 255 };
            for (int i = 0; i < 16; i++) {
                frame[i * 4] = (byte) (i * 16);
                frame[i * 4 + 1] = (byte) (255 - i * 16);
                frame[i * 4 + 2] = (byte) (i * 7);
                frame[i * 4 + 3] = (byte) alphas[i % alphas.length];
            }
            final int[] decoded = pixels(CodecsAPI.decode(encode(4, 4, PixelFormat.RGBA, Encoder.DEFAULT_QUALITY, 0, steps(1), frame)), 0);
            for (int i = 0; i < 16; i++) {
                final int expected = alphas[i % alphas.length] < 128 ? 0 : 0xFF000000 | (i * 16) << 16 | (255 - i * 16) << 8 | i * 7;
                assertEquals(expected, decoded[i], "pixel " + i);
            }
        }

        @Test
        @DisplayName("Opaque pixels that turn transparent are cleared instead of showing the previous frame")
        void turnsTransparent() throws IOException {
            final byte[][] frames = {
                    scene(24, 16, 0xFF2040A0, 0xFFE0E000, 4, 4), // OPAQUE BACKGROUND, YELLOW SQUARE
                    scene(24, 16, 0xFF2040A0, 0, 10, 6),         // A TRANSPARENT HOLE WHERE THE BACKGROUND WAS
                    scene(24, 16, 0, 0xFFE0E000, 14, 2),         // ONLY THE SQUARE, SOMEWHERE ELSE
                    scene(24, 16, 0, 0, 0, 0),                   // NOTHING AT ALL
                    scene(24, 16, 0, 0xFF00C000, 2, 8)           // A GREEN SQUARE OVER NOTHING
            };
            final byte[] gif = encode(24, 16, PixelFormat.BGRA, Encoder.DEFAULT_QUALITY, 0, steps(frames.length), frames);
            assertComposition(gif, PixelFormat.BGRA, frames);
            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(gif));
            assertArrayEquals(argb(frames[0], PixelFormat.BGRA), image.getRGB(0, 0, 24, 16, null, 0, 24), "the JDK decoder reads a two-color frame");

            // EVERY FRAME FOLLOWED BY PIXELS TURNING TRANSPARENT RESTORES ITS AREA TO TRANSPARENT, THE LAST ONE IS KEPT
            final List<Image> images = images(gif);
            for (final int f: new int[] { 0, 1, 2 }) {
                assertEquals(2, images.get(f).control().disposalMethod(), "frame " + f + " must clear");
                assertTrue(images.get(f).control().transparentColorFlag(), "frame " + f + " clears to transparent");
            }
            assertEquals(1, images.get(3).control().disposalMethod());
            assertEquals(1, images.get(4).control().disposalMethod());
        }

        @Test
        @DisplayName("A sprite moving over transparency is drawn as small cropped frames")
        void movingSprite() throws IOException {
            final byte[][] frames = new byte[8][];
            for (int f = 0; f < frames.length; f++) frames[f] = scene(64, 32, 0, 0xFFFF8000, 3 + f * 5, 2 + f);
            final byte[] gif = encode(64, 32, PixelFormat.BGRA, Encoder.DEFAULT_QUALITY, 0, steps(frames.length), frames);
            assertComposition(gif, PixelFormat.BGRA, frames);
            final List<Image> images = images(gif);
            for (int f = 1; f < images.size(); f++) {
                final ImageDescriptor descriptor = images.get(f).descriptor();
                assertEquals(6, descriptor.width(), "frame " + f + " redraws only the sprite");
                assertEquals(6, descriptor.height(), "frame " + f + " redraws only the sprite");
            }
        }
    }

    @Nested
    @DisplayName("Quantization")
    class Quantization {

        @Test
        @DisplayName("A photo-like gradient above 256 colors stays within a bounded error, also when cropped, and dithering from quality 50 keeps local averages closer")
        void gradient() throws IOException {
            // SMOOTH WAVES THAT CROSS THE HISTOGRAM BINS AT ODD PLACES, LIKE A PHOTO AND UNLIKE A GRID-ALIGNED RAMP; THE
            // SECOND FRAME SHIFTS THEM INSIDE A 40x30 WINDOW, SO ONLY THAT WINDOW IS QUANTIZED AGAIN
            final byte[][] frames = new byte[2][128 * 128 * 3];
            for (int f = 0; f < frames.length; f++) {
                for (int y = 0, p = 0; y < 128; y++) {
                    for (int x = 0; x < 128; x++, p += 3) {
                        final double phase = f == 1 && x >= 48 && x < 88 && y >= 56 && y < 86 ? 2.0 : 0.0;
                        frames[f][p] = (byte) (128 + 100 * Math.sin(x / 11.0 + y / 23.0 + phase));
                        frames[f][p + 1] = (byte) (128 + 100 * Math.cos(y / 13.0 - x / 29.0 + phase));
                        frames[f][p + 2] = (byte) (128 + 90 * Math.sin((x + y) / 17.0 + phase));
                    }
                }
            }
            final byte[] plain = encode(128, 128, PixelFormat.RGB, Encoder.DEFAULT_QUALITY - 1, 0, steps(2), frames);
            final byte[] dithered = encode(128, 128, PixelFormat.RGB, Encoder.DEFAULT_QUALITY, 0, steps(2), frames);
            final List<Image> images = images(plain);
            assertEquals(7, images.get(0).descriptor().localColorTableSize(), "a lossy frame uses the whole table");
            final ImageDescriptor window = images.get(1).descriptor();
            assertArrayEquals(new int[] { 48, 56, 40, 30 }, new int[] { window.left(), window.top(), window.width(), window.height() });

            final ImageData flatImage = CodecsAPI.decode(plain);
            final ImageData noisyImage = CodecsAPI.decode(dithered);
            for (int f = 0; f < frames.length; f++) {
                final int[] expected = argb(frames[f], PixelFormat.RGB);
                final double[] flat = error(expected, pixels(flatImage, f), 128);
                final double[] noisy = error(expected, pixels(noisyImage, f), 128);
                assertTrue(flat[0] < 8.0 && flat[1] <= 40, "frame " + f + " undithered mean " + flat[0] + ", max " + flat[1]);
                assertTrue(noisy[0] < 10.0 && noisy[1] <= 64, "frame " + f + " dithered mean " + noisy[0] + ", max " + noisy[1]);
                assertTrue(noisy[2] < flat[2], "frame " + f + " dithered block error " + noisy[2] + " against " + flat[2]);
            }
            assertFalse(Arrays.equals(pixels(flatImage, 0), pixels(noisyImage, 0)), "quality 50 dithers and quality 49 does not");

            final BufferedImage image = ImageIO.read(new ByteArrayInputStream(dithered));
            assertArrayEquals(pixels(noisyImage, 0), image.getRGB(0, 0, 128, 128, null, 0, 128), "the JDK decoder reads the same pixels");
        }

        @Test
        @DisplayName("256 opaque colors next to transparency quantize to 255 and keep the transparent pixels exact")
        void fullPaletteWithTransparency() throws IOException {
            final byte[] frame = new byte[32 * 16 * 4];
            for (int i = 0; i < 32 * 16; i++) {
                frame[i * 4] = (byte) (i % 256);
                frame[i * 4 + 1] = (byte) (i % 256);
                frame[i * 4 + 2] = (byte) (i % 256);
                frame[i * 4 + 3] = (byte) (i < 256 ? 255 : 0);
            }
            final int[] decoded = pixels(CodecsAPI.decode(encode(32, 16, PixelFormat.RGBA, 0, 0, steps(1), frame)), 0);
            for (int i = 0; i < 32 * 16; i++) {
                if (i >= 256) {
                    assertEquals(0, decoded[i], "pixel " + i + " stays transparent");
                    continue;
                }
                assertEquals(0xFF, decoded[i] >>> 24, "pixel " + i + " stays opaque");
                assertTrue(Math.abs((decoded[i] & 0xFF) - i) <= 8, "pixel " + i + " drifted to " + (decoded[i] & 0xFF));
            }
        }
    }

    @Nested
    @DisplayName("Timing and looping")
    class Timing {

        @Test
        @DisplayName("Delays round to centiseconds carrying the remainder, so the total length holds")
        void carriedRemainder() throws IOException {
            final long[] pts = new long[31];
            for (int f = 0; f < pts.length; f++) pts[f] = f * 1_000_000L / 30;
            final byte[][] frames = new byte[pts.length][];
            for (int f = 0; f < frames.length; f++) frames[f] = picture(8, 8, PixelFormat.RGB, f);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (final Encoder encoder = CodecsAPI.encoder(Container.GIF, out, new Encoder.Video(VideoCodec.GIF, 8, 8, PixelFormat.RGB, 30), null)) {
                for (int f = 0; f < frames.length; f++) encoder.video(ByteBuffer.wrap(frames[f]), pts[f]);
            }
            final ImageData image = CodecsAPI.decode(out.toByteArray());
            for (final long delay: image.delays()) assertTrue(delay == 30L || delay == 40L, "delay " + delay);
            // 31 FRAMES OF 1/30 S, THE LAST ONE LASTING 1 / fps AS WELL
            assertTrue(Math.abs(image.duration() - 31_000L / 30) <= 5L, "duration " + image.duration());
        }

        @Test
        @DisplayName("Frames shorter than 20 ms play 20 ms, not the 100 ms readers give to 0 and 1 cs, and the last lasts 1 / fps")
        void shortFrames() throws IOException {
            final byte[][] frames = { picture(8, 8, PixelFormat.RGB, 0), picture(8, 8, PixelFormat.RGB, 1), picture(8, 8, PixelFormat.RGB, 2) };
            final ImageData image = CodecsAPI.decode(encode(8, 8, PixelFormat.RGB, Encoder.DEFAULT_QUALITY, 0, new long[] { 0L, 4_000L, 8_000L }, frames));
            assertArrayEquals(new long[] { 20L, 20L, 1_000L / FPS }, image.delays());
        }

        @ParameterizedTest(name = "repeat {0}")
        @ValueSource(ints = { 0, 1, 3, 65_536 })
        @DisplayName("The NETSCAPE loop round-trips the play count, and a single play writes none")
        void repeat(final int repeat) throws IOException {
            final byte[][] frames = { picture(8, 8, PixelFormat.GRAY, 0), picture(8, 8, PixelFormat.GRAY, 1) };
            final byte[] gif = encode(8, 8, PixelFormat.GRAY, Encoder.DEFAULT_QUALITY, repeat, steps(2), frames);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertEquals(repeat, reader.repeat());
                if (repeat == 1) assertNull(reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
                else assertEquals(Math.max(0, repeat - 1), reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
            }
        }

        @Test
        @DisplayName("A single frame is a still image without loop nor delay")
        void still() throws IOException {
            final byte[] gif = encode(8, 8, PixelFormat.GRAY, Encoder.DEFAULT_QUALITY, 0, steps(1), picture(8, 8, PixelFormat.GRAY, 0));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(gif))) {
                assertEquals(1, reader.repeat());
                assertNull(reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
            }
            assertEquals(0, images(gif).get(0).control().delayTime());
        }
    }

    @Nested
    @DisplayName("Cropping")
    class Cropping {

        @Test
        @DisplayName("Identical frames shrink to a single pixel")
        void identical() throws IOException {
            final byte[] frame = picture(64, 48, PixelFormat.RGBA, 0);
            final byte[] gif = encode(64, 48, PixelFormat.RGBA, Encoder.DEFAULT_QUALITY, 0, steps(4), frame, frame, frame, frame);
            assertComposition(gif, PixelFormat.RGBA, frame, frame, frame, frame);
            final List<Image> images = images(gif);
            for (int f = 1; f < images.size(); f++) {
                assertEquals(1, images.get(f).descriptor().width(), "frame " + f);
                assertEquals(1, images.get(f).descriptor().height(), "frame " + f);
            }
        }

        @Test
        @DisplayName("An unchanged frame followed by a vanishing sprite clears only the sprite, not an area grown from its placeholder pixel")
        void unchangedThenCleared() throws IOException {
            final byte[] sprite = scene(64, 32, 0, 0xFF3080FF, 40, 20);
            final byte[] empty = scene(64, 32, 0, 0, 0, 0);
            final byte[] gif = encode(64, 32, PixelFormat.BGRA, Encoder.DEFAULT_QUALITY, 0, steps(3), sprite, sprite, empty);
            assertComposition(gif, PixelFormat.BGRA, sprite, sprite, empty);
            final List<Image> images = images(gif);
            final ImageDescriptor cleared = images.get(1).descriptor();
            assertArrayEquals(new int[] { 40, 20, 6, 6 }, new int[] { cleared.left(), cleared.top(), cleared.width(), cleared.height() });
            assertEquals(2, images.get(1).control().disposalMethod());
            assertEquals(1, images.get(2).descriptor().width(), "nothing is left to draw once the sprite is cleared");
        }

        @Test
        @DisplayName("A changed frame is cropped to the bounding box of what changed")
        void boundingBox() throws IOException {
            final byte[] first = picture(40, 30, PixelFormat.RGB, 0);
            final byte[] second = first.clone();
            for (int y = 11; y <= 13; y++) for (int x = 20; x <= 24; x++) second[(y * 40 + x) * 3] ^= 0x55;
            final byte[] gif = encode(40, 30, PixelFormat.RGB, Encoder.DEFAULT_QUALITY, 0, steps(2), first, second);
            assertComposition(gif, PixelFormat.RGB, first, second);
            final ImageDescriptor descriptor = images(gif).get(1).descriptor();
            assertArrayEquals(new int[] { 20, 11, 5, 3 }, new int[] { descriptor.left(), descriptor.top(), descriptor.width(), descriptor.height() });
        }
    }

    @Nested
    @DisplayName("Contract")
    class Contract {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PixelFormat.class, names = { "YUYV", "NV12", "YUV420P", "YUVA444P" })
        @DisplayName("Layouts other than RGBA, BGRA, RGB and GRAY are refused")
        void refusedFormat(final PixelFormat format) {
            final Encoder.Video video = new Encoder.Video(VideoCodec.GIF, 16, 16, format, FPS);
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.GIF, new ByteArrayOutputStream(), video, null));
        }

        @Test
        @DisplayName("Sides above 65535 pixels and more than 65536 plays are refused")
        void refusedSize() {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(IllegalArgumentException.class, () -> new GifEncoder(out, new Encoder.Video(VideoCodec.GIF, 65_536, 1, PixelFormat.GRAY, FPS)));
            assertThrows(IllegalArgumentException.class, () -> new GifEncoder(out, new Encoder.Video(VideoCodec.GIF, 1, 65_536, PixelFormat.GRAY, FPS)));
            assertThrows(IllegalArgumentException.class, () -> new GifEncoder(out, new Encoder.Video(VideoCodec.GIF, 8, 8, PixelFormat.GRAY, FPS, 50, 65_537)));
            assertDoesNotThrow(() -> new GifEncoder(out, new Encoder.Video(VideoCodec.GIF, 65_535, 1, PixelFormat.GRAY, FPS)));
        }

        @Test
        @DisplayName("Closing without a frame throws IllegalStateException")
        void noFrames() throws IOException {
            final Encoder encoder = CodecsAPI.encoder(Container.GIF, new ByteArrayOutputStream(), new Encoder.Video(VideoCodec.GIF, 8, 8, PixelFormat.RGBA, FPS), null);
            assertThrows(IllegalStateException.class, encoder::close);
        }

        @Test
        @DisplayName("CodecsAPI.encoder writes an animation into a file")
        void writesFile() throws IOException {
            // UNDER build/ INSTEAD OF @TempDir: DELETING A JUST-WRITTEN FILE FLAKES AGAINST WINDOWS AV SCANS
            final Path file = Path.of("build", "test-gif-encoder", "animation.gif");
            Files.createDirectories(file.getParent());
            final byte[][] frames = { picture(30, 20, PixelFormat.RGBA, 0), picture(30, 20, PixelFormat.RGBA, 1), picture(30, 20, PixelFormat.RGBA, 2) };
            try (final Encoder encoder = CodecsAPI.encoder(Container.GIF, file, new Encoder.Video(VideoCodec.GIF, 30, 20, PixelFormat.RGBA, FPS), null)) {
                for (int f = 0; f < frames.length; f++) encoder.video(ByteBuffer.wrap(frames[f]), f * 100_000L);
            }
            final ImageData image = assertComposition(Files.readAllBytes(file), PixelFormat.RGBA, frames);
            assertArrayEquals(new long[] { 100L, 100L, 40L }, image.delays());
            assertEquals(ImageData.REPEAT_FOREVER, image.repeat());
        }
    }

    private record Image(ImageDescriptor descriptor, GraphicExtension control) {}
}
