package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * gAMA (image gamma) chunk: the relationship between the image samples and the desired display output intensity.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11gAMA">PNG Specification - gAMA</a>
 */
public record GAMA(int gamma) implements IChunk {
    public static final int SIGNATURE = 0x67_41_4D_41; // "gAMA"
    public static final int LENGTH = 4;
    // GAMMA 100.0: ORDERS OF MAGNITUDE ABOVE ANY REAL ENCODER (0.45455 AND 1.0 ARE THE COMMON ONES)
    public static final int MAX_GAMMA = 10_000_000;

    /** Reads a complete gAMA chunk (length, type, data and CRC) from the buffer. */
    public static GAMA read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to gAMA. */
    public static GAMA convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for gAMA: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("gAMA data must be 4 bytes, got " + data.length);

        // THE FIELD IS UNSIGNED gamma * 100000: 0x80000000 READS BACK NEGATIVE AND FEEDS A NEGATIVE EXPONENT
        // TO Math.pow, WHOSE +Infinity SATURATES THE DECODER'S GAMMA LUT AND BLEEDS OVER THE ALPHA BYTE
        final int gamma = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt();
        if (gamma <= 0 || gamma > MAX_GAMMA) throw new CodecException("Invalid gAMA value: " + Integer.toUnsignedString(gamma));
        return new GAMA(gamma);
    }

    /** Returns the gamma value as a float (the chunk stores gamma * 100000). */
    public float gammaValue() {
        return this.gamma / 100_000.0f;
    }

    /** Returns the decoding exponent (1/gamma). */
    public float decodingExponent() {
        return 100_000.0f / this.gamma;
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.gamma);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
