package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * mDCv (mastering display color volume) chunk: HDR metadata describing the color volume of the mastering display.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#mDCv-chunk">PNG Specification - mDCv</a>
 */
public record MDCV(
        int redX, int redY,
        int greenX, int greenY,
        int blueX, int blueY,
        int whiteX, int whiteY,
        long maxLuminance,
        long minLuminance
) implements IChunk {
    public static final int SIGNATURE = 0x6D_44_43_76; // "mDCv"
    public static final int LENGTH = 24;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete mDCv chunk (length, type, data and CRC) from the buffer. */
    public static MDCV read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to mDCv. */
    public static MDCV convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for mDCv: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("mDCv data must be 24 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new MDCV(
                buffer.getShort() & 0xFFFF, // RED X
                buffer.getShort() & 0xFFFF, // RED Y
                buffer.getShort() & 0xFFFF, // GREEN X
                buffer.getShort() & 0xFFFF, // GREEN Y
                buffer.getShort() & 0xFFFF, // BLUE X
                buffer.getShort() & 0xFFFF, // BLUE Y
                buffer.getShort() & 0xFFFF, // WHITE X
                buffer.getShort() & 0xFFFF, // WHITE Y
                buffer.getInt() & 0xFFFFFFFFL, // MAX LUMINANCE
                buffer.getInt() & 0xFFFFFFFFL  // MIN LUMINANCE
        );
    }

    /** Returns the red primary CIE x chromaticity coordinate. */
    public float redPrimaryX() { return this.redX * 0.00002f; }
    /** Returns the red primary CIE y chromaticity coordinate. */
    public float redPrimaryY() { return this.redY * 0.00002f; }
    /** Returns the green primary CIE x chromaticity coordinate. */
    public float greenPrimaryX() { return this.greenX * 0.00002f; }
    /** Returns the green primary CIE y chromaticity coordinate. */
    public float greenPrimaryY() { return this.greenY * 0.00002f; }
    /** Returns the blue primary CIE x chromaticity coordinate. */
    public float bluePrimaryX() { return this.blueX * 0.00002f; }
    /** Returns the blue primary CIE y chromaticity coordinate. */
    public float bluePrimaryY() { return this.blueY * 0.00002f; }
    /** Returns the white point CIE x chromaticity coordinate. */
    public float whitePointX() { return this.whiteX * 0.00002f; }
    /** Returns the white point CIE y chromaticity coordinate. */
    public float whitePointY() { return this.whiteY * 0.00002f; }

    /** Returns the mastering display maximum luminance in cd/m^2. */
    public float maxLuminanceCdm2() { return this.maxLuminance * 0.0001f; }
    /** Returns the mastering display minimum luminance in cd/m^2. */
    public float minLuminanceCdm2() { return this.minLuminance * 0.0001f; }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) this.redX);
        buf.putShort((short) this.redY);
        buf.putShort((short) this.greenX);
        buf.putShort((short) this.greenY);
        buf.putShort((short) this.blueX);
        buf.putShort((short) this.blueY);
        buf.putShort((short) this.whiteX);
        buf.putShort((short) this.whiteY);
        buf.putInt((int) this.maxLuminance);
        buf.putInt((int) this.minLuminance);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
