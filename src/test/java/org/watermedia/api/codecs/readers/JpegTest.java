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
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the JPEG decoder over baseline and APP-marker fixtures across the four common chroma
 * layouts, plus its metadata and the split between unsupported and malformed streams.
 *
 * <p>Two tests are emitted per fixture: a native pixel-format probe (color stays BGRA, grayscale
 * stays one luma byte per pixel) and a tolerance-bounded pixel comparison of the requested BGRA
 * output against a pre-generated PAM reference. Tolerances are relaxed because JPEG decoders
 * legitimately differ at the IDCT/upsampling layer.
 */
@DisplayName("JPEG decoder")
public class JpegTest {
    // PER-CHANNEL DIFFERENCE BOUNDS: JPEG IS LOSSY AND DECODER VARIANTS ARE NORMAL
    private static final int SMALL_DIFF = 8;
    private static final int MAX_DIFF = 80;
    private static final double MAX_MEAN_DIFF = 6.0;
    private static final double MAX_SMALL_DIFF_PERCENT = 10.0;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @TestFactory
    @DisplayName("Native format and pixel match")
    Iterable<DynamicTest> testJPEGFixtures() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Fixture fixture: List.of(
                new Fixture(1, "jpeg400jfif", PixelFormat.GRAY),
                new Fixture(2, "jpeg420exif", PixelFormat.BGRA),
                new Fixture(3, "jpeg422jfif", PixelFormat.BGRA),
                new Fixture(4, "jpeg444", PixelFormat.BGRA),
                new Fixture(5, "jpeg444_all_appx_markers", PixelFormat.BGRA),
                new Fixture(6, "jpeg420prog_unpadded", PixelFormat.BGRA)
        )) {
            // NATIVE LAYOUT WITH NO FORMAT REQUESTED: COLOR IS CONVERTED TO BGRA, GRAYSCALE STAYS ONE BYTE PER PIXEL
            tests.add(dynamicTest("JPEG native format [" + fixture.id + " " + fixture.encoding + "]", () -> {
                final ByteBuffer source = ByteBuffer.wrap(Fixtures.readAll(fixture.jpeg()));
                try (final ImageReader reader = CodecsAPI.reader(source)) {
                    assertEquals(fixture.nativeFormat, reader.pixelFormat(), fixture.encoding);
                    final ByteBuffer frame = reader.next();
                    assertTrue(frame.isDirect(), fixture.encoding);
                    final int pixels = reader.width() * reader.height();
                    assertEquals(fixture.nativeFormat == PixelFormat.GRAY ? pixels : pixels * 4, frame.remaining(), fixture.encoding);
                }
            }));

            // PIXEL DIFFERENCE AGAINST PAM REFERENCE (BGRA OUTPUT)
            tests.add(dynamicTest("JPEG pixels [" + fixture.id + " " + fixture.encoding + "]", () -> {
                final ImageData image = CodecsAPI.decode(Fixtures.readAll(fixture.jpeg()), PixelFormat.BGRA);
                final PamImage reference = PamImage.read(fixture.raw());

                assertEquals(reference.width(), image.width(), fixture.encoding);
                assertEquals(reference.height(), image.height(), fixture.encoding);
                assertEquals(1, image.frames().length, fixture.encoding);

                // COMPARE ONLY RGB CHANNELS, REORDERED FROM BGRA: THE PAM REFERENCE IS DEPTH 3, BGRA HAS AN EXTRA A
                final ByteBuffer bgra = image.frames()[0].duplicate();
                final int pixelCount = reference.width() * reference.height();
                final byte[] refRgb = reference.pixels();
                final byte[] actualRgb = new byte[pixelCount * 3];
                for (int i = 0; i < pixelCount; i++) {
                    final int srcOffset = i * 4;
                    final int dstOffset = i * 3;
                    actualRgb[dstOffset] = bgra.get(srcOffset + 2);
                    actualRgb[dstOffset + 1] = bgra.get(srcOffset + 1);
                    actualRgb[dstOffset + 2] = bgra.get(srcOffset);
                }

                final PixelDiff diff = PixelDiff.compare(refRgb, actualRgb, SMALL_DIFF);
                assertTrue(diff.maxDiff() <= MAX_DIFF,
                        fixture.encoding + " max diff " + diff.maxDiff() + " exceeds " + MAX_DIFF);
                assertTrue(diff.meanDiff() <= MAX_MEAN_DIFF,
                        fixture.encoding + " mean diff " + diff.meanDiff() + " exceeds " + MAX_MEAN_DIFF);
                assertTrue(diff.percentAboveSmall() <= MAX_SMALL_DIFF_PERCENT,
                        fixture.encoding + " has " + diff.percentAboveSmall() + "% values above " + SMALL_DIFF);
            }));
        }
        return tests;
    }

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
    @DisplayName("Metadata")
    class Metadata {

        @Test
        @DisplayName("The EXIF payload and its orientation reach the metadata")
        void exifFixture() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.JPEG_DIR.resolve("2.jpg"))))) {
                final byte[] exif = assertInstanceOf(byte[].class, reader.metadata().value(CodecsAPI.METAKEY_EXIF));
                assertTrue((exif[0] == 'M' && exif[1] == 'M') || (exif[0] == 'I' && exif[1] == 'I'), "TIFF byte order marker");
                final Integer orientation = assertInstanceOf(Integer.class, reader.metadata().value(CodecsAPI.METAKEY_ORIENTATION));
                assertTrue(orientation >= 1 && orientation <= 8, "orientation " + orientation);
            }
        }

        @Test
        @DisplayName("Orientation 6 rotates the picture 90 degrees clockwise and swaps its size")
        void orientationRotates() throws IOException {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_BASELINE);
            // "Exif\0\0" THEN A LITTLE-ENDIAN TIFF WHOSE IFD0 HOLDS ONE ENTRY: ORIENTATION (0x0112), SHORT, COUNT 1, VALUE 6
            final byte[] exif = { 'E', 'x', 'i', 'f', 0, 0, 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0,
                    0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0 };
            final ImageData upright = CodecsAPI.decode(plain, PixelFormat.BGRA);
            final ImageData turned = CodecsAPI.decode(withSegment(plain, 0xE1, exif), PixelFormat.BGRA);
            final int w = upright.width(), h = upright.height();
            assertEquals(h, turned.width(), "rotated width");
            assertEquals(w, turned.height(), "rotated height");
            final IntBuffer a = upright.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            final IntBuffer b = turned.frames()[0].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            // OUTPUT PIXEL (x, y) COMES FROM SOURCE PIXEL (y, h - 1 - x)
            for (final int[] p: new int[][] { { 0, 0 }, { h - 1, 0 }, { 0, w - 1 }, { h / 2, w / 3 }, { h - 1, w - 1 } }) {
                final int x = p[0], y = p[1];
                assertEquals(a.get((h - 1 - x) * w + y), b.get(y * h + x), "pixel " + x + "," + y);
            }
        }

        @Test
        @DisplayName("Every COM segment becomes a comment, in stream order")
        void comments() throws IOException {
            final byte[] once = withSegment(Fixtures.readAll(Fixtures.JPEG_BASELINE), 0xFE, "second".getBytes(StandardCharsets.ISO_8859_1));
            final byte[] jpeg = withSegment(once, 0xFE, "first".getBytes(StandardCharsets.ISO_8859_1));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(jpeg))) {
                assertEquals(List.of("first", "second"), reader.metadata().comments());
                reader.next();
                assertTrue(reader.reset());
                reader.next();
                assertEquals(List.of("first", "second"), reader.metadata().comments(), "a replay must not duplicate comments");
            }
        }

        @Test
        @DisplayName("Comments past the cap of 64 are dropped without failing the decode")
        void commentCap() throws IOException {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_BASELINE);
            // 65 COM SEGMENTS "c00".."c64" RIGHT AFTER SOI, 7 BYTES EACH
            final ByteBuffer jpeg = ByteBuffer.allocate(plain.length + 65 * 7).put(plain, 0, 2);
            for (int i = 0; i <= 64; i++)
                jpeg.put((byte) 0xFF).put((byte) 0xFE).putShort((short) 5).put(String.format(Locale.ROOT, "c%02d", i).getBytes(StandardCharsets.ISO_8859_1));
            jpeg.put(plain, 2, plain.length - 2);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(jpeg.array()))) {
                final List<String> comments = reader.metadata().comments();
                assertEquals(64, comments.size());
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
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_BASELINE);
            for (final int[] process: new int[][] { { 0xC3, 'l' }, { 0xC5, 'h' }, { 0xC9, 'a' }, { 0xCD, 'h' } }) {
                final byte[] jpeg = plain.clone();
                jpeg[sof(jpeg) + 1] = (byte) process[0];
                final UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(jpeg)));
                assertTrue(e.getMessage().contains(String.format("FF%02X (%c", process[0], process[1])), e.getMessage());
            }
        }

        @Test
        @DisplayName("12-bit extended or progressive samples, four components and a DNL height are unsupported, other values are malformed")
        void unsupportedFrameFields() {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_BASELINE);
            final int sof = sof(plain);
            // SOF LAYOUT: FF Cn, LENGTH (2), PRECISION, HEIGHT (2), WIDTH (2), COMPONENT COUNT
            for (final int process: new int[] { 0xC1, 0xC2 })
                assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(patch(patch(plain, sof + 1, process), sof + 4, 12))));
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(patch(plain, sof + 9, 4))));
            // A ZERO HEIGHT DEFERS THE LINE COUNT TO A DNL MARKER AFTER THE FIRST SCAN
            final UnsupportedFormatException dnl = assertThrows(UnsupportedFormatException.class,
                    () -> CodecsAPI.reader(ByteBuffer.wrap(patch(patch(plain, sof + 5, 0), sof + 6, 0))));
            assertTrue(dnl.getMessage().contains("DNL"), dnl.getMessage());
            // BASELINE (SOF0) ALLOWS 8 BITS ONLY
            assertMalformed(patch(patch(plain, sof + 1, 0xC0), sof + 4, 12));
            assertMalformed(patch(plain, sof + 4, 7));
            assertMalformed(patch(plain, sof + 9, 0));
        }

        @Test
        @DisplayName("Unknown markers and truncated segments stay malformed, not unsupported")
        void malformed() {
            final byte[] plain = Fixtures.readAll(Fixtures.JPEG_BASELINE);
            final byte[] unknown = new byte[plain.length + 2];
            System.arraycopy(plain, 0, unknown, 0, 2);
            unknown[2] = (byte) 0xFF;
            unknown[3] = 0x01;
            System.arraycopy(plain, 2, unknown, 4, plain.length - 2);
            final CodecException e = assertMalformed(unknown);
            assertTrue(e.getMessage().contains("FF01"), e.getMessage());
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
    private record Fixture(int id, String encoding, PixelFormat nativeFormat) {
        Path jpeg() {
            return Fixtures.JPEG_DIR.resolve(this.id + ".jpg");
        }

        Path raw() {
            return Fixtures.JPEG_DIR.resolve("raw").resolve(this.id + ".pam");
        }
    }
}
