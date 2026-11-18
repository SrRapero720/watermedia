package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.ImageBytes;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the JPEG decoder: the fixture files (six progressive pictures over the gray, 4:2:0, 4:2:2 and 4:4:4 layouts
 * and one baseline 4:2:0 picture) in their native layout and against PAM references, the extended sequential process,
 * the EXIF, orientation and comment metadata, and the split between unsupported and malformed streams.
 *
 * <p>The references come from other decoders, which legitimately differ at the IDCT and the chroma upsampling, so the
 * pixel match bounds the difference instead of asking for equality.
 */
@DisplayName("JPEG decoder")
public class JpegTest {
    // PER-CHANNEL DIFFERENCE BOUNDS AGAINST THE REFERENCES
    private static final int SMALL_DIFF = 8;
    private static final int MAX_DIFF = 80;
    private static final double MAX_MEAN_DIFF = 6.0;
    private static final double MAX_SMALL_DIFF_PERCENT = 10.0;

    // 1.jpg TO 6.jpg ARE PROGRESSIVE (SOF2), 7.jpg IS BASELINE (SOF0, src/test/tools/GenerateJpegReference.java)
    private static final Fixture BASELINE = new Fixture(7, "baseline 4:2:0, partial MCUs", PixelFormat.BGRA);
    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(1, "progressive gray", PixelFormat.GRAY),
            new Fixture(2, "progressive 4:2:0 EXIF", PixelFormat.BGRA),
            new Fixture(3, "progressive 4:2:2", PixelFormat.BGRA),
            new Fixture(4, "progressive 4:4:4", PixelFormat.BGRA),
            new Fixture(5, "progressive 4:4:4, byte copy of 4", PixelFormat.BGRA),
            new Fixture(6, "progressive 4:2:0, partial MCUs", PixelFormat.BGRA),
            BASELINE);

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // OFFSET OF THE FF BYTE OF THE FRAME HEADER, FOUND BY WALKING THE MARKER SEGMENTS THAT PRECEDE IT
    private static int sof(final byte[] jpeg) {
        int at = 2;
        while ((jpeg[at + 1] & 0xFF) != 0xC0 && (jpeg[at + 1] & 0xFF) != 0xC2) at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
        return at;
    }

    // INSERTS ONE MARKER SEGMENT RIGHT AFTER SOI
    private static byte[] withSegment(final byte[] jpeg, final int marker, final byte[] payload) {
        final ByteBuffer out = ByteBuffer.allocate(jpeg.length + 4 + payload.length);
        out.put(jpeg, 0, 2).put((byte) 0xFF).put((byte) marker).putShort((short) (payload.length + 2)).put(payload).put(jpeg, 2, jpeg.length - 2);
        return out.array();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Fixture files")
    class Corpus {

        @TestFactory
        @DisplayName("Every fixture keeps its native layout and stays within the bounds of its PAM reference")
        Iterable<DynamicTest> fixtures() {
            final List<DynamicTest> tests = new ArrayList<>();
            for (final Fixture fixture: FIXTURES) {
                // NO FORMAT REQUESTED: COLOUR COMES OUT AS BGRA, GRAY STAYS ONE BYTE PER PIXEL
                tests.add(dynamicTest("JPEG native format [" + fixture.id + " " + fixture.label + "]", () -> {
                    try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(fixture.jpeg())))) {
                        assertEquals(fixture.nativeFormat, reader.pixelFormat(), fixture.label);
                        final ByteBuffer frame = reader.next();
                        assertTrue(frame.isDirect(), fixture.label + " frame is a direct buffer");
                        final int pixels = reader.width() * reader.height();
                        assertEquals(fixture.nativeFormat == PixelFormat.GRAY ? pixels : pixels * 4, frame.remaining(), fixture.label + " frame bytes");
                    }
                }));

                tests.add(dynamicTest("JPEG pixels [" + fixture.id + " " + fixture.label + "]", () -> {
                    final ImageData image = CodecsAPI.decode(Fixtures.readAll(fixture.jpeg()), PixelFormat.BGRA);
                    final PamImage reference = PamImage.read(fixture.raw());
                    assertEquals(reference.width(), image.width(), fixture.label + " width");
                    assertEquals(reference.height(), image.height(), fixture.label + " height");
                    assertEquals(1, image.frames().length, fixture.label + " frames");
                    final PixelDiff diff = reference.diff(image.frames()[0], SMALL_DIFF);
                    assertTrue(diff.maxDiff() <= MAX_DIFF, fixture.label + " max diff " + diff.maxDiff() + " exceeds " + MAX_DIFF);
                    assertTrue(diff.meanDiff() <= MAX_MEAN_DIFF, fixture.label + " mean diff " + diff.meanDiff() + " exceeds " + MAX_MEAN_DIFF);
                    assertTrue(diff.percentAboveSmall() <= MAX_SMALL_DIFF_PERCENT,
                            fixture.label + " has " + diff.percentAboveSmall() + "% values above " + SMALL_DIFF + " (limit " + MAX_SMALL_DIFF_PERCENT + "%)");
                }));
            }
            return tests;
        }

        @Test
        @DisplayName("An extended sequential (SOF1) frame decodes exactly like the baseline frame it relabels")
        void extendedSequential() throws IOException {
            // 8-BIT SAMPLES AND TWO HUFFMAN TABLES PER CLASS FIT BOTH PROCESSES, SO ONLY THE MARKER CHANGES
            final byte[] baseline = Fixtures.readAll(BASELINE.jpeg());
            final byte[] extended = baseline.clone();
            extended[sof(extended) + 1] = (byte) 0xC1;
            final ImageData expected = CodecsAPI.decode(baseline, PixelFormat.BGRA);
            final ImageData actual = CodecsAPI.decode(extended, PixelFormat.BGRA);
            assertEquals(expected.width(), actual.width());
            assertEquals(expected.height(), actual.height());
            assertEquals(expected.frames()[0], actual.frames()[0], "the SOF1 pixels equal the SOF0 ones");
        }
    }

    @Nested
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("The EXIF payload reaches the metadata without its Exif prefix, and its orientation with it")
        void exifFixture() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.JPEG_DIR.resolve("2.jpg"))))) {
                // 2.jpg CARRIES THE SMALLEST EXIF THERE IS: A LITTLE-ENDIAN TIFF WHOSE IFD0 HOLDS ORIENTATION 1 ALONE
                assertArrayEquals(ImageBytes.exif(1), assertInstanceOf(byte[].class, reader.metadata().value(CodecsAPI.METAKEY_EXIF)), "the TIFF bytes after the Exif\\0\\0 prefix");
                assertEquals(1, reader.metadata().value(CodecsAPI.METAKEY_ORIENTATION));
            }
        }

        @Test
        @DisplayName("Orientation 6 rotates the picture 90 degrees clockwise and swaps its size")
        void orientationRotates() throws IOException {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_GRAY);
            final byte[] exif = new ImageBytes().ascii("Exif").raw(0, 0).raw(ImageBytes.exif(6)).bytes();
            final ImageData upright = CodecsAPI.decode(plain, PixelFormat.BGRA);
            final ImageData turned = CodecsAPI.decode(withSegment(plain, 0xE1, exif), PixelFormat.BGRA);
            final int w = upright.width(), h = upright.height();
            assertEquals(h, turned.width(), "rotated width");
            assertEquals(w, turned.height(), "rotated height");
            // OUTPUT PIXEL (x, y) COMES FROM SOURCE PIXEL (y, h - 1 - x)
            final int[] source = PamImage.argb(upright, 0);
            final int[] expected = new int[w * h];
            for (int y = 0; y < w; y++)
                for (int x = 0; x < h; x++) expected[y * h + x] = source[(h - 1 - x) * w + y];
            assertArrayEquals(expected, PamImage.argb(turned, 0));
        }

        @Test
        @DisplayName("Every COM segment becomes a comment, in stream order, once however many replays")
        void comments() throws IOException {
            final byte[] once = withSegment(Fixtures.readAll(Fixtures.JPEG_GRAY), 0xFE, "second".getBytes(StandardCharsets.ISO_8859_1));
            final byte[] jpeg = withSegment(once, 0xFE, "first".getBytes(StandardCharsets.ISO_8859_1));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(jpeg))) {
                assertEquals(List.of("first", "second"), reader.metadata().comments());
                reader.next();
                assertTrue(reader.reset(), "a still rewinds");
                reader.next();
                assertEquals(List.of("first", "second"), reader.metadata().comments(), "a replay must not duplicate comments");
            }
        }

        @Test
        @DisplayName("Comments past the cap of 64 are dropped without failing the decode")
        void commentCap() throws IOException {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_GRAY);
            // 65 COM SEGMENTS "c00".."c64" RIGHT AFTER SOI, 7 BYTES EACH
            final ByteBuffer jpeg = ByteBuffer.allocate(plain.length + 65 * 7).put(plain, 0, 2);
            for (int i = 0; i <= 64; i++)
                jpeg.put((byte) 0xFF).put((byte) 0xFE).putShort((short) 5).put(String.format(Locale.ROOT, "c%02d", i).getBytes(StandardCharsets.ISO_8859_1));
            jpeg.put(plain, 2, plain.length - 2);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(jpeg.array()))) {
                final List<String> comments = reader.metadata().comments();
                assertEquals(64, comments.size(), "comments kept");
                assertEquals("c00", comments.get(0));
                assertEquals("c63", comments.get(63), "the 65th comment is the one dropped");
                assertEquals(1, reader.readAll().frames().length);
            }
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("Valid coding processes the decoder does not implement are unsupported and named")
        void unsupportedProcesses() {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_GRAY);
            for (final String[] process: new String[][] { { "C3", "lossless" }, { "C5", "hierarchical" }, { "C9", "arithmetic coding" }, { "CD", "hierarchical arithmetic coding" } }) {
                final byte[] jpeg = plain.clone();
                jpeg[sof(jpeg) + 1] = (byte) Integer.parseInt(process[0], 16);
                final UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(jpeg)));
                assertEquals("(wm_err:4751) Failed to decode the JPEG frame due to unsupported frame type FF" + process[0] + " (" + process[1] + ")", e.getMessage());
            }
        }

        @Test
        @DisplayName("12-bit extended or progressive samples, four components and a DNL height are unsupported, other values are malformed")
        void unsupportedFrameFields() {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_GRAY);
            final int sof = sof(plain);
            // SOF LAYOUT: FF Cn, LENGTH (2), PRECISION, HEIGHT (2), WIDTH (2), COMPONENT COUNT
            for (final int process: new int[] { 0xC1, 0xC2 })
                assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(patch(patch(plain, sof + 1, process), sof + 4, 12))));
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(patch(plain, sof + 9, 4))));
            // A ZERO HEIGHT DEFERS THE LINE COUNT TO A DNL MARKER AFTER THE FIRST SCAN
            final UnsupportedFormatException dnl = assertThrows(UnsupportedFormatException.class,
                    () -> CodecsAPI.reader(ByteBuffer.wrap(patch(patch(plain, sof + 5, 0), sof + 6, 0))));
            assertEquals("(wm_err:4760) Failed to read the JPEG frame header due to unsupported height defined by a DNL marker", dnl.getMessage());
            // BASELINE (SOF0) ALLOWS 8 BITS ONLY
            assertMalformed(patch(patch(plain, sof + 1, 0xC0), sof + 4, 12));
            assertMalformed(patch(plain, sof + 4, 7));
            assertMalformed(patch(plain, sof + 9, 0));
        }

        @Test
        @DisplayName("Unknown markers and truncated segments stay malformed, not unsupported")
        void malformed() {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_GRAY);
            final byte[] unknown = new byte[plain.length + 2];
            System.arraycopy(plain, 0, unknown, 0, 2);
            unknown[2] = (byte) 0xFF;
            unknown[3] = 0x01;
            System.arraycopy(plain, 2, unknown, 4, plain.length - 2);
            assertEquals("(wm_err:4755) Failed to parse the JPEG stream due to unknown marker FF01", assertMalformed(unknown).getMessage());
            assertMalformed(Arrays.copyOf(plain, sof(plain) + 6));
        }

        private static CodecException assertMalformed(final byte[] jpeg) {
            final CodecException e = assertThrows(CodecException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(jpeg)));
            assertFalse(e instanceof UnsupportedFormatException, "malformed input reported as unsupported: " + e.getMessage());
            return e;
        }

        private static byte[] patch(final byte[] jpeg, final int offset, final int value) {
            final byte[] copy = jpeg.clone();
            copy[offset] = (byte) value;
            return copy;
        }
    }

    // A FIXTURE JPEG, ITS REFERENCE PAM AND THE LAYOUT THE DECODER HANDS OUT WHEN NO FORMAT IS REQUESTED
    private record Fixture(int id, String label, PixelFormat nativeFormat) {
        Path jpeg() {
            return Fixtures.JPEG_DIR.resolve(this.id + ".jpg");
        }

        Path raw() {
            return Fixtures.JPEG_DIR.resolve("raw").resolve(this.id + ".pam");
        }
    }
}
