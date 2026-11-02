package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.common.Tiff;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * eXIf (exchangeable image file format) chunk: EXIF metadata from cameras and image editing software.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11eXIf">PNG Specification - eXIf</a>
 */
public record EXIF(byte[] data) implements IChunk {
    public static final int SIGNATURE = 0x65_58_49_66; // "eXIf"

    // TIFF/EXIF BYTE ORDER MARKERS
    public static final short BIG_ENDIAN_MARKER = 0x4D4D;    // "MM"
    public static final short LITTLE_ENDIAN_MARKER = 0x4949; // "II"

    /** Reads a complete eXIf chunk (length, type, data and CRC) from the buffer. */
    public static EXIF read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to eXIf. */
    public static EXIF convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for eXIf: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        // AT LEAST THE 8-BYTE TIFF HEADER
        if (data.length < 8) throw new CodecException("eXIf data too short: " + data.length + " bytes");
        // "MM" AND "II" READ THE SAME IN EITHER BYTE ORDER
        final short byteOrder = (short) ((data[0] << 8) | (data[1] & 0xFF));
        if (byteOrder != BIG_ENDIAN_MARKER && byteOrder != LITTLE_ENDIAN_MARKER) throw new CodecException("Invalid EXIF byte order marker");
        return new EXIF(data.clone());
    }

    /** Returns whether the EXIF data uses big-endian byte order. */
    public boolean isBigEndian() {
        return (this.data[0] == 'M' && this.data[1] == 'M');
    }

    /** Returns the EXIF orientation (1-8) declared in IFD0, or 1 when absent. */
    public int orientation() {
        return Tiff.orientation(this.data);
    }

    /** Returns the size of the EXIF data. */
    public int size() {
        return this.data.length;
    }

    @Override
    public byte[] toBytes() {
        return this.data.clone();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.data);
    }
}
