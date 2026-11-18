package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link BcReader} block access over DDS texture arrays without a native BC encoder: the slices, the BC layout
 * and the footer delays of BC1, BC3 and BC7 arrays, a final {@code close()}, and the reader's own refusals of a texture
 * past the memory budget and of a negative footer delay.
 */
@DisplayName("BcReader")
public class BcReaderTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A 4x4 BC1 TEXTURE ARRAY OF ZEROED BLOCKS FOLLOWED BY THE GIVEN TRAILING BYTES
    private static ByteBuffer texture(final int slices, final byte[] trailer) {
        final int blocks = slices * PixelFormat.BC1.blockBytes();
        return ByteBuffer.allocate(DdsHeader.BYTES + blocks + trailer.length)
                .put(DdsFixture.write(4, 4, PixelFormat.BC1, slices)).put(new byte[blocks]).put(trailer).flip();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Blocks")
    class Blocks {

        @ParameterizedTest(name = "{displayName}: {0}")
        @EnumSource(value = PixelFormat.class, names = { "BC1", "BC3", "BC7" })
        @DisplayName("Compressed slices, the BC layout and footer delays read back without a native encoder")
        void readsCompressedSlicesWithoutNativeEncoder(final PixelFormat format) throws CodecException {
            // A 5x7 ARRAY OF TWO SLICES BEHIND 3 FOREIGN BYTES, EVERY BLOCK BYTE NUMBERED SO SLICE 1 STARTS AT BYTE size
            final int size = (int) format.frameBytes(5, 7, 8);
            final ByteBuffer file = ByteBuffer.allocate(3 + DdsHeader.BYTES + size * 2 + 28);
            file.position(3);
            file.put(DdsFixture.write(5, 7, format, 2));
            for (int i = 0; i < size * 2; i++) file.put((byte) i);
            file.put(DdsFixture.writeFooter(25, 75));
            file.flip().position(3);
            try (final BcReader reader = new BcReader(file)) {
                assertEquals(3, file.position(), "the caller's buffer keeps its position");
                assertSame(format, reader.pixelFormat(), "the reader reports the file's BC layout");
                assertEquals(2, reader.frameCount(), "one frame per slice");
                assertEquals(100, reader.duration(), "the duration sums the footer delays");
                assertTrue(reader.blocks()[0].isDirect(), "slices are direct buffers an engine uploads as they are");
                assertEquals(size, reader.blocks()[0].remaining(), "a slice holds exactly one frame of blocks");
                assertEquals((byte) size, reader.blocks()[1].get(0), "slice 1 starts right after slice 0");
                assertEquals(25, reader.next().delayMs(), "first footer delay");
                assertEquals(75, reader.next().delayMs(), "second footer delay");
                assertNull(reader.next(), "the cursor ends after the last slice");
                reader.reset();
                assertTrue(reader.hasNext(), "reset() rewinds to the first slice");
            }
        }

        @Test
        @DisplayName("close() is final: the cursor stays exhausted and reset() is a state error")
        void closeIsFinal() throws CodecException {
            final BcReader reader = new BcReader(texture(2, new byte[0]));
            reader.next();
            reader.close();
            assertFalse(reader.hasNext(), "close() exhausts the cursor");
            assertNull(reader.next(), "a closed reader yields no frame");
            assertThrows(IllegalStateException.class, reader::reset);
            assertFalse(reader.hasNext(), "a refused reset leaves the reader exhausted");
            assertEquals(2, reader.frameCount(), "descriptors stay readable after close");
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("Textures past the memory budget and negative footer delays are malformed")
        void refusesOversizedTexturesAndNegativeDelays() {
            // HEADER FIELD REFUSALS ARE DdsHeader's, PINNED BY DdsHeaderTest; THESE TWO ARE THE READER'S OWN CHECKS.
            // NINE 8192x8192 BC7 SLICES OF 64 MiB EACH PASS THE 512 MiB BUDGET
            final ByteBuffer large = ByteBuffer.wrap(DdsFixture.write(8_192, 8_192, PixelFormat.BC7, 9));
            assertEquals("(wm_err:4000) Failed to read the BC texture due to excessive size: 603979776 bytes",
                    assertThrowsExactly(CodecException.class, () -> new BcReader(large)).getMessage());
            assertEquals("(wm_err:4002) Failed to read the BC frame delays due to invalid values",
                    assertThrowsExactly(CodecException.class, () -> new BcReader(texture(1, DdsFixture.writeFooter(-1)))).getMessage());
        }
    }
}
