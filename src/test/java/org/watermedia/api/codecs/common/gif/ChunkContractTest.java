package org.watermedia.api.codecs.common.gif;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the GIF {@link IChunk} serialization contract: every block round-trips through its
 * reader, and descriptor validation rejects truncated input and unencodable palette sizes.
 */
@DisplayName("GIF chunk serialization contract")
public class ChunkContractTest {

    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("Serializes every block at its wire length, dropping palette alpha")
        void serializesBlocks() throws CodecException {
            final IChunk[] chunks = {
                    new ScreenDescriptor(320, 240, true, 8, false, 1, 0, 0),
                    new GraphicExtension(2, false, true, 25, 1),
                    new ImageDescriptor(1, 2, 16, 9, true, false, false, 7),
                    new ColorTable(2, new int[] { 0x80123456, 0xFFABCDEF }) };
            final int[] lengths = { 7, 6, 9, 6 };
            for (int i = 0; i < chunks.length; i++) assertEquals(lengths[i], chunks[i].toBytes().length);
            // SERIALIZATION DROPS ALPHA; READING RESTORES IT AS OPAQUE
            final byte[] palette = chunks[3].toBytes();
            assertArrayEquals(new byte[] { 0x12, 0x34, 0x56, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }, palette);
            assertArrayEquals(new int[] { 0xFF123456, 0xFFABCDEF }, ColorTable.read(2, ByteBuffer.wrap(palette)).colors());
        }

        @Test
        @DisplayName("Readers ignore the buffer byte order and advance exactly past their block")
        void readsRelativeAndOrderFree() throws CodecException {
            final ScreenDescriptor screen = new ScreenDescriptor(320, 240, true, 8, false, 1, 0, 0);
            final GraphicExtension control = new GraphicExtension(2, false, true, 25, 1);
            final ImageDescriptor image = new ImageDescriptor(1, 2, 16, 9, true, false, false, 7);
            final ColorTable palette = new ColorTable(2, new int[] { 0xFF123456, 0xFFABCDEF });
            final ByteBuffer stream = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
                    .put(screen.toBytes()).put(control.toBytes()).put(image.toBytes()).put(palette.toBytes()).flip();
            assertEquals(screen, ScreenDescriptor.read(stream));
            assertEquals(ScreenDescriptor.LENGTH, stream.position());
            assertEquals(control, GraphicExtension.read(stream));
            assertEquals(image, ImageDescriptor.read(stream));
            assertArrayEquals(palette.colors(), ColorTable.read(2, stream).colors());
            assertFalse(stream.hasRemaining());
            assertEquals(256, new ScreenDescriptor(1, 1, true, 8, false, 7, 0, 0).globalColorCount());
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Rejects truncated descriptors and unencodable palette sizes")
        void rejectsInvalidDescriptors() throws CodecException {
            final ImageDescriptor valid = new ImageDescriptor(0, 0, 1, 1, true, false, false, 7);
            valid.validate();
            assertEquals(256, valid.localColorCount());
            assertThrows(CodecException.class, () -> ImageDescriptor.read(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)));
            assertThrows(CodecException.class, () -> new ImageDescriptor(0, 0, 1, 1, true, false, false, 8).validate());
            assertThrows(CodecException.class, () -> new ImageDescriptor(0, 0, 1, 1, true, false, false, -1).validate());
        }
    }
}
