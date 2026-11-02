package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A GIF image's position, dimensions and packed flags, without the image separator or palette. */
public record ImageDescriptor(
    int left, int top, int width, int height,
    boolean localColorTableFlag, boolean interlacedFlag, boolean sortFlag,
    int localColorTableSize) implements IChunk {

    public static final int LENGTH = 9;
    public static final int LOCAL_COLOR_TABLE_SIZE_CODES = 8;

    /** Reads and validates the {@value #LENGTH}-byte descriptor after the image separator at the buffer position, advancing past it. */
    public static ImageDescriptor read(final ByteBuffer buffer) throws CodecException {
        if (buffer.remaining() < LENGTH) throw new CodecException("Truncated GIF image descriptor");
        final int at = buffer.position();
        final int packedFields = buffer.get(at + 8) & 0xFF;
        final ImageDescriptor id = new ImageDescriptor(DataTool.u16(buffer, at, ByteOrder.LITTLE_ENDIAN),
                DataTool.u16(buffer, at + 2, ByteOrder.LITTLE_ENDIAN), DataTool.u16(buffer, at + 4, ByteOrder.LITTLE_ENDIAN),
                DataTool.u16(buffer, at + 6, ByteOrder.LITTLE_ENDIAN), (packedFields & 0b1000_0000) != 0,
                (packedFields & 0b0100_0000) != 0, (packedFields & 0b0010_0000) != 0, packedFields & 0b0000_0111);
        id.validate();
        buffer.position(at + LENGTH);
        return id;
    }

    /** Returns the local palette color count, {@code 2^(localColorTableSize + 1)}, even when no palette is present. */
    public int localColorCount() {
        return 1 << (this.localColorTableSize + 1);
    }

    /** Rejects negative offsets, empty dimensions and palette-size codes outside the three-bit range. */
    public void validate() throws CodecException {
        // A RECORD CANONICAL CONSTRUCTOR CANNOT THROW CodecException, SO read() CALLS THIS AT THE PARSE BOUNDARY
        if (this.left < 0 || this.top < 0 || this.width <= 0 || this.height <= 0)
            throw new CodecException("Invalid dimensions for ImageDescriptor");
        if (this.localColorTableSize < 0 || this.localColorTableSize >= LOCAL_COLOR_TABLE_SIZE_CODES)
            throw new CodecException("Local color table size must be between 0 and " + (LOCAL_COLOR_TABLE_SIZE_CODES - 1));
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) this.left);
        buf.putShort((short) this.top);
        buf.putShort((short) this.width);
        buf.putShort((short) this.height);
        int packed = 0;
        if (this.localColorTableFlag) packed |= 0b1000_0000;
        if (this.interlacedFlag) packed |= 0b0100_0000;
        if (this.sortFlag) packed |= 0b0010_0000;
        packed |= this.localColorTableSize & 0b0000_0111;
        buf.put((byte) packed);
        return buf.array();
    }
}
