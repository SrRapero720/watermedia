package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * cLLi (content light level information) chunk: HDR metadata describing the light levels of the content.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#cLLi-chunk">PNG Specification - cLLi</a>
 */
public record CLLI(long maxContentLightLevel, long maxFrameAverageLightLevel) implements IChunk {
    public static final int SIGNATURE = 0x63_4C_4C_69; // "cLLi"
    public static final int LENGTH = 8;

    /** Reads a complete cLLi chunk (length, type, data and CRC) from the buffer. */
    public static CLLI read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to cLLi. */
    public static CLLI convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for cLLi: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("cLLi data must be 8 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new CLLI(
                buffer.getInt() & 0xFFFFFFFFL, // MAX CONTENT LIGHT LEVEL
                buffer.getInt() & 0xFFFFFFFFL  // MAX FRAME-AVERAGE LIGHT LEVEL
        );
    }

    /** Returns the maximum content light level in cd/m^2. */
    public float maxCLLCdm2() { return this.maxContentLightLevel * 0.0001f; }
    /** Returns the maximum frame-average light level in cd/m^2. */
    public float maxFALLCdm2() { return this.maxFrameAverageLightLevel * 0.0001f; }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) this.maxContentLightLevel);
        buf.putInt((int) this.maxFrameAverageLightLevel);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
