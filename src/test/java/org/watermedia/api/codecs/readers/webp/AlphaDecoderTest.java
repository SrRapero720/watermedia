package org.watermedia.api.codecs.readers.webp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.test.support.ImageBytes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link AlphaDecoder}: the ALPH header layout {@code [RESERVED:2][PREPROC:2][FILTER:2][COMPRESSION:2]}
 * (most significant bits first), the four inverse filters with their RFC 9649 edge predictions, alpha application
 * onto ARGB pixels and BGRA buffers, and the refusal paths.
 */
@DisplayName("AlphaDecoder")
public class AlphaDecoderTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ONE HEADER BYTE FOLLOWED BY THE PLANE BYTES
    private static ByteBuffer alph(final int header, final int... plane) {
        return ByteBuffer.wrap(new ImageBytes().u8(header).raw(plane).bytes());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Uncompressed filter modes")
    class Filters {

        @Test
        @DisplayName("No filter passes raw values through")
        void noFilter() throws CodecException {
            assertArrayEquals(new byte[] { (byte) 128, 1, 2, 3 }, AlphaDecoder.decode(alph(0x00, 128, 1, 2, 3), 2, 2));
        }

        @Test
        @DisplayName("Pre-processing bits are a hint that never selects a filter")
        void preprocessingIsNotAFilter() throws CodecException {
            // 0x10 SETS ONLY THE LEVEL-REDUCTION HINT: THE PLANE MUST STAY RAW
            assertArrayEquals(new byte[] { 10, 5, 5, 5 }, AlphaDecoder.decode(alph(0x10, 10, 5, 5, 5), 4, 1));
        }

        @Test
        @DisplayName("Horizontal filter adds the left neighbour and the first column adds the pixel above")
        void horizontal() throws CodecException {
            final byte[] result = AlphaDecoder.decode(alph(0x04, 10, 5, 5, 5, 20, 10, 10, 10), 4, 2);
            assertArrayEquals(new byte[] { 10, 15, 20, 25, 30, 40, 50, 60 }, result);
        }

        @Test
        @DisplayName("Vertical filter adds the pixel above and the first row adds the left neighbour")
        void vertical() throws CodecException {
            final byte[] result = AlphaDecoder.decode(alph(0x08, 10, 20, 5, 10, 5, 10, 5, 10), 2, 4);
            assertArrayEquals(new byte[] { 10, 30, 15, 40, 20, 50, 25, 60 }, result);
        }

        @Test
        @DisplayName("Gradient filter adds clamp(left + top - top-left)")
        void gradient() throws CodecException {
            // [1,1] PREDICTS 110 + 120 - 100 = 130 AND ADDS 0
            assertArrayEquals(new byte[] { 100, 110, 120, (byte) 130 }, AlphaDecoder.decode(alph(0x0C, 100, 10, 20, 0), 2, 2));
        }

        @Test
        @DisplayName("Filtered sums wrap modulo 256")
        void wraps() throws CodecException {
            assertArrayEquals(new byte[] { (byte) 200, (byte) 144 }, AlphaDecoder.decode(alph(0x04, 200, 200), 2, 1));
        }
    }

    @Nested
    @DisplayName("Alpha application")
    class Application {

        @Test
        @DisplayName("applyAlpha replaces the high byte of each ARGB pixel, and a shorter alpha array touches only the leading pixels")
        void shorterPlane() {
            final int[] argb = { 0xFF_FF_00_00, 0xFF_00_FF_00, 0xFF_00_00_FF, 0xFF_FF_FF_00, 0xFF_00_FF_FF };
            AlphaDecoder.applyAlpha(argb, new byte[] { (byte) 100, (byte) 150, (byte) 200 });
            assertArrayEquals(new int[] { 0x64_FF_00_00, 0x96_00_FF_00, 0xC8_00_00_FF, 0xFF_FF_FF_00, 0xFF_00_FF_FF }, argb);
        }

        @Test
        @DisplayName("applyAlpha writes only the alpha byte of each BGRA pixel")
        void bgra() {
            final ByteBuffer bgra = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.LITTLE_ENDIAN);
            bgra.put(new byte[] { 0, 0, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255, (byte) 255, 0, 0, (byte) 255 }).flip();
            AlphaDecoder.applyAlpha(bgra, new byte[] { (byte) 128, (byte) 64, 0 });
            final byte[] out = new byte[12];
            bgra.get(0, out);
            assertArrayEquals(new byte[] { 0, 0, (byte) 255, (byte) 128, 0, (byte) 255, 0, 64, (byte) 255, 0, 0, 0 }, out);
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("A truncated raw plane is malformed")
        void truncated() {
            assertThrows(CodecException.class, () -> AlphaDecoder.decode(alph(0x00), 4, 4));
        }

        @Test
        @DisplayName("An empty chunk is malformed")
        void empty() {
            assertThrows(CodecException.class, () -> AlphaDecoder.decode(ByteBuffer.allocate(0), 4, 4));
        }

        @Test
        @DisplayName("An unknown compression method is unsupported, not malformed")
        void unknownCompression() {
            final ByteBuffer buffer = ByteBuffer.allocate(100).put((byte) 0x02).position(0);
            final UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class, () -> AlphaDecoder.decode(buffer, 4, 4));
            assertEquals("Unsupported WebP alpha compression method: 2", e.getMessage());
        }
    }
}
