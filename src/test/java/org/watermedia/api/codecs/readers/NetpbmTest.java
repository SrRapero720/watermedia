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
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.FrameReplay;
import org.watermedia.test.support.PamImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the Netpbm reader: every binary fixture decodes to the raster read straight from the samples of its 8-bit
 * P4, P5 or P6 file and replays through {@link ImageReader#reset()}; hand-written plain (ASCII), PAM alpha and 16-bit
 * rasters decode pixel by pixel; and headers split into unsupported and malformed ones.
 */
@DisplayName("Netpbm reader")
public class NetpbmTest {
    private static final int SIZE = 600; // EVERY FIXTURE IS 600x600
    private static final List<String> FIXTURES = List.of("test.ppm", "rgb.pam", "rgba.pam", "test.pgm", "grayscale.pam", "test.pbm", "bw.pam");

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE ARGB RASTER A FIXTURE MUST DECODE TO, READ FROM THE SAMPLES OF THE 8-BIT FILES BEHIND A FIXED HEADER; THE PAM
    // FILES HOLD THE SAME PICTURES IN OTHER ENCODINGS
    private static int[] expected(final String name) {
        return switch (name) {
            case "test.ppm" -> {
                final byte[] rgb = samples(name, "P6\n600 600\n255\n");
                final int[] argb = new int[SIZE * SIZE];
                for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | (rgb[i * 3] & 0xFF) << 16 | (rgb[i * 3 + 1] & 0xFF) << 8 | rgb[i * 3 + 2] & 0xFF;
                yield argb;
            }
            case "test.pgm" -> {
                final byte[] gray = samples(name, "P5\n600 600\n255\n");
                final int[] argb = new int[SIZE * SIZE];
                for (int i = 0; i < argb.length; i++) argb[i] = 0xFF000000 | (gray[i] & 0xFF) * 0x010101;
                yield argb;
            }
            // ONE BIT PER PIXEL, MOST SIGNIFICANT FIRST, 75 BYTES PER ROW, 1 IS BLACK
            case "test.pbm" -> {
                final byte[] bits = samples(name, "P4\n600 600\n");
                final int[] argb = new int[SIZE * SIZE];
                for (int i = 0; i < argb.length; i++) {
                    final int x = i % SIZE, y = i / SIZE;
                    argb[i] = ((bits[y * (SIZE / 8) + (x >> 3)] >> (7 - (x & 7))) & 1) == 1 ? 0xFF000000 : 0xFFFFFFFF;
                }
                yield argb;
            }
            // 16-BIT SAMPLES AT MAXVAL 65535, THE ALPHA OF rgba.pam OPAQUE
            case "rgb.pam", "rgba.pam" -> expected("test.ppm");
            case "grayscale.pam" -> expected("test.pgm");
            // test.pgm WHITE FROM 128 UP; test.pbm THRESHOLDS ANOTHER LUMA OF THE PICTURE, SO THE TWO DIFFER IN 1.3% OF PIXELS
            case "bw.pam" -> {
                final int[] argb = expected("test.pgm");
                for (int i = 0; i < argb.length; i++) argb[i] = (argb[i] & 0xFF) >= 128 ? 0xFFFFFFFF : 0xFF000000;
                yield argb;
            }
            default -> throw new IllegalArgumentException("No expected raster for " + name);
        };
    }

    // THE BYTES PAST A HEADER THE FILE MUST START WITH
    private static byte[] samples(final String name, final String header) {
        final byte[] file = Fixtures.readAll(Fixtures.NETPBM_DIR.resolve(name));
        assertEquals(header, new String(file, 0, header.length(), StandardCharsets.US_ASCII), name + " header");
        return Arrays.copyOfRange(file, header.length(), file.length);
    }

    private static ImageData decode(final String text) throws IOException {
        return decode(text, new byte[0]);
    }

    private static ImageData decode(final String header, final byte[] raster) throws IOException {
        return CodecsAPI.decode(bytes(header, raster));
    }

    private static byte[] bytes(final String header, final byte[] raster) {
        final byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(head.length + raster.length).put(head).put(raster).array();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Fixture files")
    class Corpus {

        @TestFactory
        @DisplayName("Every fixture decodes to the raster its 8-bit samples give and replays through reset()")
        Iterable<DynamicTest> fixtures() throws IOException {
            // A DELETED, RENAMED OR NEW FIXTURE FAILS LOUDLY INSTEAD OF SILENTLY CHANGING THE CASES
            final Set<String> onDisk;
            try (final Stream<Path> entries = Files.list(Fixtures.NETPBM_DIR)) {
                onDisk = entries.map(p -> p.getFileName().toString()).filter(n -> n.matches(".*\\.(pbm|pgm|ppm|pam)")).collect(Collectors.toSet());
            }
            assertEquals(Set.copyOf(FIXTURES), onDisk, "every expected fixture is on disk and every fixture has an expectation");

            final List<DynamicTest> tests = new ArrayList<>();
            for (final String name: FIXTURES) {
                tests.add(dynamicTest("Decode " + name, () -> {
                    final ImageData image = CodecsAPI.decode(Fixtures.readAll(Fixtures.NETPBM_DIR.resolve(name)));
                    assertEquals(SIZE, image.width(), name + " width");
                    assertEquals(SIZE, image.height(), name + " height");
                    assertArrayEquals(expected(name), PamImage.argb(image, 0), name + " raster");
                    try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.NETPBM_DIR.resolve(name))))) {
                        FrameReplay.verify(reader, name);
                    }
                }));
            }
            return tests;
        }
    }

    @Nested
    @DisplayName("Plain, alpha and 16-bit variants")
    class Variants {

        @Test
        @DisplayName("P1 bits need no separator and accept comments")
        void plainBitmap() throws IOException {
            final ImageData image = decode("P1\n# two rows\n3 2\n101\n0 1 0\n");
            assertEquals(3, image.width());
            assertEquals(2, image.height());
            assertArrayEquals(new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF }, PamImage.argb(image, 0));
        }

        @Test
        @DisplayName("P2 samples scale by maxval")
        void plainGraymap() throws IOException {
            assertArrayEquals(new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF777777, 0xFF333333 }, PamImage.argb(decode("P2 2 2 15\n0 15\n7 3\n"), 0));
        }

        @Test
        @DisplayName("P3 triplets become opaque BGRA, even without a trailing newline")
        void plainPixmap() throws IOException {
            assertArrayEquals(new int[] { 0xFFFF0000, 0xFF0000FF }, PamImage.argb(decode("P3 2 1 255 255 0 0 0 0 255"), 0));
        }

        @Test
        @DisplayName("PAM GRAYSCALE_ALPHA keeps its alpha")
        void grayscaleAlpha() throws IOException {
            final ImageData image = decode("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 2\nMAXVAL 255\nTUPLTYPE GRAYSCALE_ALPHA\nENDHDR\n",
                    new byte[] { 0x2A, (byte) 0x80, (byte) 0xFF, 0x00 });
            assertArrayEquals(new int[] { 0x802A2A2A, 0x00FFFFFF }, PamImage.argb(image, 0));
        }

        @Test
        @DisplayName("PAM BLACKANDWHITE_ALPHA keeps its alpha")
        void blackAndWhiteAlpha() throws IOException {
            final ImageData image = decode("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 2\nMAXVAL 1\nTUPLTYPE BLACKANDWHITE_ALPHA\nENDHDR\n",
                    new byte[] { 1, 1, 0, 0 });
            assertArrayEquals(new int[] { 0xFFFFFFFF, 0x00000000 }, PamImage.argb(image, 0));
        }

        @Test
        @DisplayName("16-bit samples are read big-endian and rescaled")
        void sixteenBit() throws IOException {
            final ImageData image = decode("P5 2 1 65535\n", new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0x80, 0x00 });
            assertArrayEquals(new int[] { 0xFFFFFFFF, 0xFF7F7F7F }, PamImage.argb(image, 0));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("A PAM tuple type outside the six known ones, or none at all, is unsupported at header time")
        void unknownTupleType() {
            for (final String tuple: List.of("CMYK", "")) {
                final byte[] pam = bytes("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\n" + (tuple.isEmpty() ? "" : "TUPLTYPE " + tuple + "\n") + "ENDHDR\n", new byte[4]);
                final UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(pam)));
                assertEquals("(wm_err:4888) Failed to parse the PAM header due to unsupported tuple type: " + (tuple.isEmpty() ? "(none)" : tuple), e.getMessage());
            }
        }

        @Test
        @DisplayName("A black-and-white PAM with a MAXVAL other than 1 is malformed")
        void blackAndWhiteMaxVal() {
            final byte[] pam = bytes("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nMAXVAL 255\nTUPLTYPE BLACKANDWHITE\nENDHDR\n", new byte[1]);
            final CodecException e = assertThrows(CodecException.class, () -> CodecsAPI.reader(ByteBuffer.wrap(pam)));
            assertFalse(e instanceof UnsupportedFormatException, "malformed input reported as unsupported: " + e.getMessage());
            assertEquals("(wm_err:4890) Failed to parse the PAM header due to invalid MAXVAL for BLACKANDWHITE, it requires 1: 255", e.getMessage());
        }

        @Test
        @DisplayName("A version outside P1..P7 is an argument error")
        void invalidVersion() {
            final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new NetpbmReader(ByteBuffer.wrap(bytes(" 1 1 255\n", new byte[1])), 8));
            assertEquals("(wm_err:4870) Failed to create the Netpbm reader due to invalid version, it must be 1 to 7: 8", e.getMessage());
        }
    }
}
