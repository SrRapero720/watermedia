package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The seven-byte GIF logical screen descriptor, excluding the file signature and global palette. */
public record ScreenDescriptor(
    int width, int height,
    boolean globalColorTableFlag, int colorResolution, boolean sortFlag,
    int globalColorTableSize, int backgroundColorIndex, int pixelAspectRatio) implements IChunk {

    public static final int LENGTH = 7;

    /** Reads the {@value #LENGTH}-byte descriptor that follows the GIF signature at the buffer position, advancing past it. */
    public static ScreenDescriptor read(final ByteBuffer buffer) throws CodecException {
        // VALIDATION LIVES HERE: A RECORD CANONICAL CONSTRUCTOR CANNOT THROW CodecException
        if (buffer.remaining() < LENGTH) throw new CodecException("Truncated GIF screen descriptor");
        final int at = buffer.position();
        final int width = DataTool.u16(buffer, at, ByteOrder.LITTLE_ENDIAN);
        final int height = DataTool.u16(buffer, at + 2, ByteOrder.LITTLE_ENDIAN);
        final int packedFields = buffer.get(at + 4) & 0xFF;
        if (width <= 0 || height <= 0) throw new CodecException("Width and height must be positive integers");
        buffer.position(at + LENGTH);
        return new ScreenDescriptor(width, height, (packedFields & 0b1000_0000) != 0, ((packedFields & 0b0111_0000) >> 4) + 1,
                (packedFields & 0b0000_1000) != 0, packedFields & 0b0000_0111, buffer.get(at + 5) & 0xFF, buffer.get(at + 6) & 0xFF);
    }

    /** Returns the global palette color count, {@code 2^(globalColorTableSize + 1)}, even when no palette is present. */
    public int globalColorCount() {
        return 1 << (this.globalColorTableSize + 1);
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) this.width);
        buf.putShort((short) this.height);
        int packed = 0;
        if (this.globalColorTableFlag) packed |= 0b1000_0000;
        packed |= ((this.colorResolution - 1) & 0b0111) << 4;
        if (this.sortFlag) packed |= 0b0000_1000;
        packed |= this.globalColorTableSize & 0b0000_0111;
        buf.put((byte) packed);
        buf.put((byte) this.backgroundColorIndex);
        buf.put((byte) this.pixelAspectRatio);
        return buf.array();
    }
}
