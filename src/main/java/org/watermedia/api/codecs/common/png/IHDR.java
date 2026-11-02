package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * IHDR (image header) chunk: the first chunk of a PNG datastream, holding the image geometry and coding.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11IHDR">PNG Specification - IHDR</a>
 */
public record IHDR(int width, int height, int depth, int colorType, int compression, int filter, int interlace) implements IChunk {
    public static final int SIGNATURE = 0x49_48_44_52; // "IHDR"
    public static final int LENGTH = 13;

    /** Reads a complete IHDR chunk (length, type, data and CRC) from the buffer. */
    public static IHDR read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to IHDR. */
    public static IHDR convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for IHDR: 0x" + Integer.toHexString(chunk.type()));
        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("Invalid IHDR data length: " + data.length + " (expected 13)");

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new IHDR(
                buffer.getInt(),    // WIDTH
                buffer.getInt(),    // HEIGHT
                buffer.get() & 0xFF, // BIT DEPTH
                buffer.get() & 0xFF, // COLOR TYPE
                buffer.get() & 0xFF, // COMPRESSION METHOD
                buffer.get() & 0xFF, // FILTER METHOD
                buffer.get() & 0xFF  // INTERLACE METHOD
        );
    }

    /** Returns the byte distance used by PNG filters, at least one for packed samples; reserved color types throw. */
    public int bytesPerPixel() throws CodecException {
        final int samplesPerPixel = switch (ColorType.of(this.colorType)) {
            case GREYSCALE, INDEXED -> 1;
            case TRUECOLOR -> 3;
            case GREYSCALE_ALPHA -> 2;
            case TRUECOLOR_ALPHA -> 4;
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException("Forbidden color type: " + this.colorType);
        };
        return Math.max(1, (samplesPerPixel * this.depth) / 8);
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.width);
        buf.putInt(this.height);
        buf.put((byte) this.depth);
        buf.put((byte) this.colorType);
        buf.put((byte) this.compression);
        buf.put((byte) this.filter);
        buf.put((byte) this.interlace);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
