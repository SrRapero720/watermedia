package org.watermedia.api.codecs.common.dds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

/**
 * Verifies {@link DdsHeader} header and footer parsing and the block math it derives from the
 * frame's {@link PixelFormat}, independently of any native BC codec.
 */
@DisplayName("DDS container")
public class DdsHeaderTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A VALID 4x4 BC1 HEADER WITH ONE LITTLE-ENDIAN FIELD OVERWRITTEN
    private static ByteBuffer patched(final int offset, final int value) {
        return ByteBuffer.wrap(DdsFixture.write(4, 4, PixelFormat.BC1, 1)).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Parsing")
    class Parsing {

        @Test
        @DisplayName("Header parses dimensions, the BC layout and frame count")
        void header() throws CodecException {
            final byte[] header = DdsFixture.write(64, 32, PixelFormat.BC7, 5);
            assertEquals(DdsHeader.BYTES, header.length);

            final DdsHeader.Info info = DdsHeader.read(ByteBuffer.wrap(header));
            assertEquals(64, info.width());
            assertEquals(32, info.height());
            assertEquals(PixelFormat.BC7, info.format());
            assertEquals(5, info.arraySize());
        }

        @Test
        @DisplayName("Truncated, non-DDS and structurally broken headers are malformed")
        void rejectsMalformed() throws CodecException {
            assertEquals(PixelFormat.BC1, DdsHeader.read(patched(0, 0x20534444)).format(), "the unpatched header must parse");
            assertThrowsExactly(CodecException.class, () -> DdsHeader.read(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4 })));
            // MAGIC, HEADER SIZE, PIXEL FORMAT SIZE, MISSING DDSCAPS_TEXTURE, NEGATIVE DEPTH AND MIP COUNT, AN UNKNOWN
            // RESOURCE DIMENSION, DXGI_FORMAT_UNKNOWN, AN EMPTY OR OVERSIZED ARRAY AND A ZERO WIDTH
            for (final int[] field: new int[][] { { 0, 0 }, { 4, 0 }, { 76, 0 }, { 108, 0 }, { 24, -1 }, { 28, -1 }, { 132, 0 },
                    { 128, 0 }, { 140, 0 }, { 140, 4_097 }, { 16, 0 } })
                assertThrowsExactly(CodecException.class, () -> DdsHeader.read(patched(field[0], field[1])), "offset " + field[0] + " = " + field[1]);
        }

        @Test
        @DisplayName("Valid DDS shapes other than a single-level 2D BC1, BC3 or BC7 array are unsupported")
        void rejectsUnsupported() {
            // NO FOURCC FLAG, A LEGACY DXT1 FOURCC, A MIP CHAIN, A VOLUME BY DEPTH AND BY DIMENSION, A 1D TEXTURE,
            // A CUBE MAP, BC2 AND RGBA8
            for (final int[] field: new int[][] { { 80, 0x40 }, { 84, 0x31545844 }, { 28, 2 }, { 24, 2 }, { 132, 4 }, { 132, 2 },
                    { 136, 4 }, { 128, 74 }, { 128, 28 } })
                assertThrowsExactly(UnsupportedFormatException.class, () -> DdsHeader.read(patched(field[0], field[1])), "offset " + field[0] + " = " + field[1]);
        }
    }

    @Nested
    @DisplayName("Footer")
    class Footer {

        @Test
        @DisplayName("Footer round-trips per-frame delays without moving the caller position")
        void roundTrip() throws CodecException {
            final long[] delays = { 10L, 0L, 250L, 33L };
            final ByteBuffer footer = ByteBuffer.wrap(DdsFixture.writeFooter(delays));
            assertArrayEquals(delays, DdsHeader.readFooter(footer, delays.length));
            assertEquals(0, footer.position());
        }

        @Test
        @DisplayName("Bytes that do not start with the footer magic are no footer and give zero delays")
        void absentFooter() throws CodecException {
            assertArrayEquals(new long[2], DdsHeader.readFooter(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), 2));
            assertArrayEquals(new long[2], DdsHeader.readFooter(ByteBuffer.wrap(new byte[0]), 2));
            // A VALID FOOTER ONE BYTE PAST THE POSITION DOES NOT START WITH THE MAGIC
            final ByteBuffer shifted = ByteBuffer.allocate(1 + 28).put((byte) 0).put(DdsFixture.writeFooter(10, 20)).flip();
            assertArrayEquals(new long[2], DdsHeader.readFooter(shifted, 2));
        }

        @Test
        @DisplayName("A footer that starts with the magic is validated: truncation, version and count are malformed")
        void malformedFooter() {
            final byte[] footer = DdsFixture.writeFooter(10, 20);
            // THE MAGIC ALONE, THE HEAD WITHOUT DELAYS AND THE HEAD WITH ONE OF ITS TWO DELAYS
            for (final int length: new int[] { 4, 12, 20 })
                assertThrowsExactly(CodecException.class, () -> DdsHeader.readFooter(ByteBuffer.wrap(Arrays.copyOf(footer, length)), 2), length + " bytes");
            final ByteBuffer version = ByteBuffer.wrap(footer.clone()).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2);
            assertThrowsExactly(CodecException.class, () -> DdsHeader.readFooter(version, 2));
            assertThrowsExactly(CodecException.class, () -> DdsHeader.readFooter(ByteBuffer.wrap(footer), 1));
        }

        @Test
        @DisplayName("Frame counts outside the accepted array sizes are refused before any delay array is allocated")
        void refusesCountOutsideArraySizes() {
            for (final int count: new int[] { -1, 4_097, Integer.MAX_VALUE })
                assertThrowsExactly(IllegalArgumentException.class, () -> DdsHeader.readFooter(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), count), "count " + count);
        }
    }

    @Nested
    @DisplayName("Block math")
    class BlockMath {

        @Test
        @DisplayName("Frame bytes follow the format's block size, edges padded up to the 4x4 grid")
        void frameBytes() throws CodecException {
            // 64x32 FORMS 16x8 BLOCKS; 61x30 PADS UP TO THE SAME GRID
            assertEquals(128 * 16, DdsHeader.read(ByteBuffer.wrap(DdsFixture.write(64, 32, PixelFormat.BC7, 1))).frameBytes());
            assertEquals(128 * 8, DdsHeader.read(ByteBuffer.wrap(DdsFixture.write(61, 30, PixelFormat.BC1, 1))).frameBytes());
            assertEquals(128 * 16, PixelFormat.BC3.frameBytes(61, 30, 8));
        }
    }
}
