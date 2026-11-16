package org.watermedia.api.codecs.common.png;

import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * sBIT (significant bits) chunk: the original number of significant bits per sample.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sBIT">PNG Specification - sBIT</a>
 */
public record SBIT(int gray, int red, int green, int blue, int alpha) implements IChunk {
    public static final int SIGNATURE = 0x73_42_49_54; // "sBIT"

    /** Creates sBIT for greyscale (color type 0). */
    public SBIT(final int gray) {
        this(gray, -1, -1, -1, -1);
    }

    /** Creates sBIT for greyscale with alpha (color type 4). */
    public static SBIT greyscaleAlpha(final int gray, final int alpha) {
        return new SBIT(gray, -1, -1, -1, alpha);
    }

    /** Creates sBIT for truecolor (color type 2). */
    public static SBIT truecolor(final int red, final int green, final int blue) {
        return new SBIT(-1, red, green, blue, -1);
    }

    /** Creates sBIT for truecolor with alpha (color type 6). */
    public static SBIT truecolorAlpha(final int red, final int green, final int blue, final int alpha) {
        return new SBIT(-1, red, green, blue, alpha);
    }

    /** Creates sBIT for indexed-color (color type 3). */
    public static SBIT indexed(final int red, final int green, final int blue) {
        return new SBIT(-1, red, green, blue, -1);
    }

    /** Reads a complete sBIT chunk (length, type, data and CRC) for the color type from the buffer. */
    public static SBIT read(final ByteBuffer buffer, final int colorType) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE), colorType);
    }

    /** Converts a generic chunk to sBIT for the color type. */
    public static SBIT convert(final PngChunk chunk, final int colorType) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_TYPE) + "Failed to read sBIT due to invalid chunk type: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        return switch (ColorType.of(colorType)) {
            case GREYSCALE -> {
                if (data.length != 1) throw new CodecException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_LENGTH_GREYSCALE) + "Failed to read sBIT due to invalid length: " + data.length + " bytes (expected 1 for greyscale)");
                yield new SBIT(data[0] & 0xFF);
            }
            case TRUECOLOR, INDEXED -> {
                if (data.length != 3) throw new CodecException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_LENGTH_TRUECOLOR) + "Failed to read sBIT due to invalid length: " + data.length + " bytes (expected 3 for truecolor or indexed)");
                yield SBIT.truecolor(data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF);
            }
            case GREYSCALE_ALPHA -> {
                if (data.length != 2) throw new CodecException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_LENGTH_GREYSCALE_ALPHA) + "Failed to read sBIT due to invalid length: " + data.length + " bytes (expected 2 for greyscale with alpha)");
                yield SBIT.greyscaleAlpha(data[0] & 0xFF, data[1] & 0xFF);
            }
            case TRUECOLOR_ALPHA -> {
                if (data.length != 4) throw new CodecException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_LENGTH_TRUECOLOR_ALPHA) + "Failed to read sBIT due to invalid length: " + data.length + " bytes (expected 4 for truecolor with alpha)");
                yield SBIT.truecolorAlpha(data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF);
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException(DataTool.errCode(WaterMedia.ERR_PNG_SBIT_COLOR_TYPE) + "Failed to read sBIT due to forbidden color type: " + colorType);
        };
    }

    @Override
    public byte[] toBytes() {
        if (this.gray >= 0 && this.alpha >= 0) return new byte[] { (byte) this.gray, (byte) this.alpha };
        if (this.gray >= 0) return new byte[] { (byte) this.gray };
        if (this.alpha >= 0) return new byte[] { (byte) this.red, (byte) this.green, (byte) this.blue, (byte) this.alpha };
        return new byte[] { (byte) this.red, (byte) this.green, (byte) this.blue };
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
