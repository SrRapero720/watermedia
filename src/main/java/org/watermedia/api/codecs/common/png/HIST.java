package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * hIST (palette histogram) chunk: the approximate usage frequency of each palette color.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11hIST">PNG Specification - hIST</a>
 */
public record HIST(int[] frequencies) implements IChunk {
    public static final int SIGNATURE = 0x68_49_53_54; // "hIST"
    // ONE FREQUENCY PER PALETTE ENTRY (2 BYTES EACH), AND THE SPEC CAPS THE PALETTE AT 256 ENTRIES
    public static final int MAX_LENGTH = 256 * 2;

    /** Reads a complete hIST chunk (length, type, data and CRC) for a palette of the given size from the buffer. */
    public static HIST read(final ByteBuffer buffer, final int paletteSize) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE), paletteSize);
    }

    /** Converts a generic chunk to hIST for a palette of the given size. */
    public static HIST convert(final PngChunk chunk, final int paletteSize) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for hIST: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length > MAX_LENGTH) throw new CodecException("hIST holds more than 256 entries: " + (data.length / 2));
        if (data.length != paletteSize * 2)
            throw new CodecException("hIST data must have " + paletteSize + " entries (" + (paletteSize * 2) + " bytes), got " + data.length + " bytes");

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int[] frequencies = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) frequencies[i] = buffer.getShort() & 0xFFFF;
        return new HIST(frequencies);
    }

    /** Returns the frequency for a specific palette index. */
    public int frequency(final int index) {
        return this.frequencies[index];
    }

    /** Returns the number of entries. */
    public int size() {
        return this.frequencies.length;
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(this.frequencies.length * 2).order(ByteOrder.BIG_ENDIAN);
        for (final int freq: this.frequencies) buf.putShort((short) freq);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
