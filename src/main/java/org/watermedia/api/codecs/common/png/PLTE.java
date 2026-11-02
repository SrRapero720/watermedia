package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * PLTE (palette) chunk: 1 to 256 palette entries, stored as packed RGB integers ({@code 0xRRGGBB}).
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11PLTE">PNG Specification - PLTE</a>
 */
public record PLTE(int[] colors) implements IChunk {
    public static final int SIGNATURE = 0x50_4C_54_45; // "PLTE"
    // THE SPEC CAPS THE PALETTE AT 256 ENTRIES; WITHOUT IT A HUGE PLTE INFLATES TO A 1.33x int[] COPY
    public static final int MAX_LENGTH = 256 * 3;

    /** Reads a complete PLTE chunk (length, type, data and CRC) from the buffer. */
    public static PLTE read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to PLTE. */
    public static PLTE convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for PLTE: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length == 0 || data.length % 3 != 0)
            throw new CodecException("PLTE data length must be a positive multiple of 3, got " + data.length);
        if (data.length > MAX_LENGTH) throw new CodecException("PLTE holds more than 256 entries: " + (data.length / 3));

        final int count = data.length / 3;
        final int[] colors = new int[count];
        for (int i = 0; i < count; i++) {
            final int offset = i * 3;
            final int r = data[offset] & 0xFF;
            final int g = data[offset + 1] & 0xFF;
            final int b = data[offset + 2] & 0xFF;
            colors[i] = (r << 16) | (g << 8) | b;
        }
        return new PLTE(colors);
    }

    /** Returns the number of palette entries. */
    public int size() {
        return this.colors.length;
    }

    /** Returns the packed {@code 0xRRGGBB} color at a palette index (0-255). */
    public int color(final int index) {
        return this.colors[index];
    }

    @Override
    public byte[] toBytes() {
        final byte[] data = new byte[this.colors.length * 3];
        for (int i = 0; i < this.colors.length; i++) {
            final int c = this.colors[i];
            data[i * 3] = (byte) ((c >> 16) & 0xFF);
            data[i * 3 + 1] = (byte) ((c >> 8) & 0xFF);
            data[i * 3 + 2] = (byte) (c & 0xFF);
        }
        return data;
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
