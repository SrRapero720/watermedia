package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.DdsFixture;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link BcReader} block access over DDS texture arrays without a native BC encoder. */
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

        @Test
        @DisplayName("Compressed slices, the BC layout and footer delays read back without a native encoder")
        void readsCompressedSlicesWithoutNativeEncoder() throws Exception {
            for (final PixelFormat format: List.of(PixelFormat.BC1, PixelFormat.BC3, PixelFormat.BC7)) {
                final int size = (int) format.frameBytes(5, 7, 8);
                final ByteBuffer file = ByteBuffer.allocate(3 + DdsHeader.BYTES + size * 2 + 28);
                file.position(3);
                file.put(DdsFixture.write(5, 7, format, 2));
                for (int i = 0; i < size * 2; i++) file.put((byte) i);
                file.put(DdsFixture.writeFooter(25, 75));
                file.flip().position(3);
                try (final BcReader reader = new BcReader(file)) {
                    assertEquals(3, file.position());
                    assertSame(format, reader.pixelFormat());
                    assertEquals(2, reader.frameCount());
                    assertEquals(100, reader.duration());
                    assertTrue(reader.blocks()[0].isDirect());
                    assertEquals(size, reader.blocks()[0].remaining());
                    assertEquals((byte) size, reader.blocks()[1].get(0));
                    assertEquals(25, reader.next().delayMs());
                    assertEquals(75, reader.next().delayMs());
                    assertNull(reader.next());
                    reader.reset();
                    assertTrue(reader.hasNext());
                }
            }
        }

        @Test
        @DisplayName("close() is final: the cursor stays exhausted and reset() is a state error")
        void closeIsFinal() throws Exception {
            final BcReader reader = new BcReader(texture(2, new byte[0]));
            reader.next();
            reader.close();
            assertFalse(reader.hasNext());
            assertNull(reader.next());
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
            // HEADER FIELD REFUSALS ARE DdsHeader's, PINNED BY DdsHeaderTest; THESE TWO ARE THE READER'S OWN CHECKS
            final ByteBuffer large = ByteBuffer.wrap(DdsFixture.write(8_192, 8_192, PixelFormat.BC7, 9));
            assertTrue(assertThrowsExactly(CodecException.class, () -> new BcReader(large)).getMessage().contains("too large"));
            assertThrowsExactly(CodecException.class, () -> new BcReader(texture(1, DdsFixture.writeFooter(-1))));
        }
    }
}
