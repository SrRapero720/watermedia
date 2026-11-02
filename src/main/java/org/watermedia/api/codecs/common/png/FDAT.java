package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * fdAT (frame data) chunk: the compressed data of one APNG frame, an IDAT preceded by a sequence number.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#fdAT-chunk">PNG Specification - fdAT</a>
 */
public record FDAT(int sequence, byte[] data) implements IChunk {
    public static final int SIGNATURE = 0x66_64_41_54; // "fdAT"

    /** Converts a generic chunk to fdAT. */
    public static FDAT convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for fdAT: 0x" + Integer.toHexString(chunk.type()));

        final byte[] rawData = chunk.data();
        if (rawData.length < 4) throw new CodecException("fdAT data must be at least 4 bytes");

        // THE SEQUENCE NUMBER COMES FIRST, THE COMPRESSED FRAME DATA FILLS THE REST
        final int sequence = ByteBuffer.wrap(rawData, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        final byte[] frameData = new byte[rawData.length - 4];
        System.arraycopy(rawData, 4, frameData, 0, frameData.length);
        return new FDAT(sequence, frameData);
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(4 + this.data.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.sequence);
        buf.put(this.data);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
