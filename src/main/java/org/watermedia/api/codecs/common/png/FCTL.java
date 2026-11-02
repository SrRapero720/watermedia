package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * fcTL (frame control) chunk: the region, delay, disposal and blending of one APNG frame.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#fcTL-chunk">PNG Specification - fcTL</a>
 */
public record FCTL(int sequence, int width, int height, int xOffset, int yOffset,
                   short delayNum, short delayDen, byte dispose, byte blend) implements IChunk {
    public static final int SIGNATURE = 0x66_63_54_4C; // "fcTL"
    public static final int LENGTH = 26;               // FIXED LENGTH OF fcTL DATA

    // DISPOSE OPERATIONS
    public static final byte DISPOSE_OP_NONE = 0;       // LEAVE OUTPUT BUFFER AS IS
    public static final byte DISPOSE_OP_BACKGROUND = 1; // CLEAR OUTPUT BUFFER TO FULLY TRANSPARENT BLACK
    public static final byte DISPOSE_OP_PREVIOUS = 2;   // RESTORE OUTPUT BUFFER TO PREVIOUS CONTENT

    // BLEND OPERATIONS
    public static final byte BLEND_OP_SOURCE = 0;       // REPLACE ALL COMPONENTS INCLUDING ALPHA
    public static final byte BLEND_OP_OVER = 1;         // ALPHA COMPOSITE (PORTER-DUFF "OVER")

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete fcTL chunk (length, type, data and CRC) from the buffer. */
    public static FCTL read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to fcTL. */
    public static FCTL convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for fcTL: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("fcTL data must be 26 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final FCTL fctl = new FCTL(
                buffer.getInt(),     // SEQUENCE NUMBER (4 BYTES)
                buffer.getInt(),     // WIDTH (4 BYTES)
                buffer.getInt(),     // HEIGHT (4 BYTES)
                buffer.getInt(),     // X OFFSET (4 BYTES)
                buffer.getInt(),     // Y OFFSET (4 BYTES)
                buffer.getShort(),   // DELAY NUMERATOR (2 BYTES)
                buffer.getShort(),   // DELAY DENOMINATOR (2 BYTES)
                buffer.get(),        // DISPOSE OP (1 BYTE)
                buffer.get()         // BLEND OP (1 BYTE)
        );
        fctl.validate();
        return fctl;
    }

    /** Rejects dispose and blend operations outside the APNG specification. */
    public void validate() throws CodecException {
        // A RECORD CANONICAL CONSTRUCTOR CANNOT THROW CodecException, SO convert() CALLS THIS
        if ((this.dispose & 0xFF) > DISPOSE_OP_PREVIOUS || (this.blend & 0xFF) > BLEND_OP_OVER)
            throw new CodecException("Invalid fcTL operations: dispose " + (this.dispose & 0xFF) + ", blend " + (this.blend & 0xFF));
    }

    /** Rejects frame bounds that leave the canvas. */
    public void validate(final int canvasWidth, final int canvasHeight) throws CodecException {
        if (this.width <= 0 || this.height <= 0) throw new CodecException("Invalid frame dimensions: " + this.width + "x" + this.height);
        if (this.xOffset < 0 || this.yOffset < 0) throw new CodecException("Invalid frame offset: " + this.xOffset + "," + this.yOffset);
        // LONG MATH: RAW int FIELDS CAN SUM PAST Integer.MAX_VALUE AND WRAP NEGATIVE, FALSELY PASSING
        if ((long) this.xOffset + this.width > canvasWidth || (long) this.yOffset + this.height > canvasHeight)
            throw new CodecException("Frame extends beyond canvas bounds");
    }

    /** Returns the frame delay in milliseconds. */
    public int delayMs() {
        final int num = this.delayNum & 0xFFFF;
        int den = this.delayDen & 0xFFFF;
        if (den == 0) den = 100; // THE SPEC READS A ZERO DENOMINATOR AS 100 (CENTISECONDS)
        return (num * 1_000) / den;
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(this.sequence);
        buf.putInt(this.width);
        buf.putInt(this.height);
        buf.putInt(this.xOffset);
        buf.putInt(this.yOffset);
        buf.putShort(this.delayNum);
        buf.putShort(this.delayDen);
        buf.put(this.dispose);
        buf.put(this.blend);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
