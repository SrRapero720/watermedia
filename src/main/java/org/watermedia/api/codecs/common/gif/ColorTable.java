package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;

/** A GIF palette of ARGB values; reading supplies opaque alpha and serialization emits only RGB triples. */
public record ColorTable(int size, int[] colors) implements IChunk {
    public static final int MAX_COLORS = 256;

    /** Reads {@code size} RGB triples (up to {@link #MAX_COLORS}) at the buffer position as opaque ARGB colors, advancing past them. */
    public static ColorTable read(final int size, final ByteBuffer buffer) throws CodecException {
        // VALIDATION LIVES HERE: A RECORD CANONICAL CONSTRUCTOR CANNOT THROW CodecException
        if (size < 0 || size > MAX_COLORS) throw new CodecException("Color table size must be between 0 and " + MAX_COLORS);
        final int at = buffer.position();
        if (buffer.remaining() < size * 3)
            throw new CodecException("Truncated GIF color table: " + size * 3 + " bytes declared, " + buffer.remaining() + " available");

        // ARGB INTS, WHICH READ AS BGRA BYTES THROUGH AN IntBuffer OVER A LITTLE-ENDIAN DIRECT BUFFER
        final int[] colors = new int[size];
        for (int i = 0, p = at; i < size; i++, p += 3)
            colors[i] = 0xFF000000 | ((buffer.get(p) & 0xFF) << 16) | ((buffer.get(p + 1) & 0xFF) << 8) | (buffer.get(p + 2) & 0xFF);
        buffer.position(at + size * 3);
        return new ColorTable(size, colors);
    }

    @Override
    public byte[] toBytes() {
        final byte[] data = new byte[this.size * 3];
        for (int i = 0; i < this.size; i++) {
            final int c = this.colors[i];
            data[i * 3] = (byte) ((c >> 16) & 0xFF);
            data[i * 3 + 1] = (byte) ((c >> 8) & 0xFF);
            data[i * 3 + 2] = (byte) (c & 0xFF);
        }
        return data;
    }
}
