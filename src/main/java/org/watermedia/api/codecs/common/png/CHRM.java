package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * cHRM (primary chromaticities) chunk: the 1931 CIE x,y chromaticities of the RGB primaries and the white point.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11cHRM">PNG Specification - cHRM</a>
 */
public record CHRM(int whiteX, int whiteY, int redX, int redY, int greenX, int greenY, int blueX, int blueY) implements IChunk {
    public static final int SIGNATURE = 0x63_48_52_4D; // "cHRM"
    public static final int LENGTH = 32;

    /** Reads a complete cHRM chunk (length, type, data and CRC) from the buffer. */
    public static CHRM read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to cHRM. */
    public static CHRM convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for cHRM: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("cHRM data must be 32 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new CHRM(
                buffer.getInt(), // WHITE POINT X
                buffer.getInt(), // WHITE POINT Y
                buffer.getInt(), // RED X
                buffer.getInt(), // RED Y
                buffer.getInt(), // GREEN X
                buffer.getInt(), // GREEN Y
                buffer.getInt(), // BLUE X
                buffer.getInt()  // BLUE Y
        );
    }

    /** Returns the white point CIE x chromaticity coordinate. */
    public float whitePointX() { return this.whiteX / 100_000.0f; }
    /** Returns the white point CIE y chromaticity coordinate. */
    public float whitePointY() { return this.whiteY / 100_000.0f; }
    /** Returns the red primary CIE x chromaticity coordinate. */
    public float redPrimaryX() { return this.redX / 100_000.0f; }
    /** Returns the red primary CIE y chromaticity coordinate. */
    public float redPrimaryY() { return this.redY / 100_000.0f; }
    /** Returns the green primary CIE x chromaticity coordinate. */
    public float greenPrimaryX() { return this.greenX / 100_000.0f; }
    /** Returns the green primary CIE y chromaticity coordinate. */
    public float greenPrimaryY() { return this.greenY / 100_000.0f; }
    /** Returns the blue primary CIE x chromaticity coordinate. */
    public float bluePrimaryX() { return this.blueX / 100_000.0f; }
    /** Returns the blue primary CIE y chromaticity coordinate. */
    public float bluePrimaryY() { return this.blueY / 100_000.0f; }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.whiteX);
        buf.putInt(this.whiteY);
        buf.putInt(this.redX);
        buf.putInt(this.redY);
        buf.putInt(this.greenX);
        buf.putInt(this.greenY);
        buf.putInt(this.blueX);
        buf.putInt(this.blueY);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
