package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * pHYs (physical pixel dimensions) chunk: the intended pixel size or aspect ratio for display.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11pHYs">PNG Specification - pHYs</a>
 */
public record PHYS(long pixelsPerUnitX, long pixelsPerUnitY, int unit) implements IChunk {
    public static final int SIGNATURE = 0x70_48_59_73; // "pHYs"
    public static final int LENGTH = 9;

    // UNIT SPECIFIERS
    public static final int UNIT_UNKNOWN = 0;
    public static final int UNIT_METER = 1;

    /** Reads a complete pHYs chunk (length, type, data and CRC) from the buffer. */
    public static PHYS read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to pHYs. */
    public static PHYS convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for pHYs: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("pHYs data must be 9 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new PHYS(
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT X
                buffer.getInt() & 0xFFFFFFFFL, // PIXELS PER UNIT Y
                buffer.get() & 0xFF            // UNIT SPECIFIER
        );
    }

    /** Returns whether the unit is the meter, which allows the DPI calculation. */
    public boolean isMetric() {
        return this.unit == UNIT_METER;
    }

    /** Returns the aspect ratio (X/Y). */
    public double aspectRatio() {
        return (double) this.pixelsPerUnitX / this.pixelsPerUnitY;
    }

    /** Returns the DPI of the X axis, or 0 when the unit is not the meter. */
    public double dpiX() {
        if (this.unit != UNIT_METER) return 0;
        return this.pixelsPerUnitX * 0.0254; // METERS TO INCHES
    }

    /** Returns the DPI of the Y axis, or 0 when the unit is not the meter. */
    public double dpiY() {
        if (this.unit != UNIT_METER) return 0;
        return this.pixelsPerUnitY * 0.0254;
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) this.pixelsPerUnitX);
        buf.putInt((int) this.pixelsPerUnitY);
        buf.put((byte) this.unit);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
