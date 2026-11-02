package org.watermedia.api.codecs.common.gif;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The GIF89a Graphic Control Extension (section 23): disposal, transparency and the delay of the next image. */
public record GraphicExtension(
    int disposalMethod,
    boolean userInputFlag,
    boolean transparentColorFlag,
    int delayTime,
    int transparentColorIndex) implements IChunk {

    public static final int GCE_LABEL = 0xF9;
    /** Full block body: block size + packed fields + delay + transparent index + terminator. */
    public static final int BODY_SIZE = 6;

    /** Reads the {@value #BODY_SIZE}-byte body after the introducer and label at the buffer position, advancing past it; delay is in centiseconds. */
    public static GraphicExtension read(final ByteBuffer buffer) throws CodecException {
        // VALIDATION LIVES HERE: A RECORD CANONICAL CONSTRUCTOR CANNOT THROW CodecException
        if (buffer.remaining() < BODY_SIZE) throw new CodecException("Truncated GIF graphic control extension");
        final int at = buffer.position();
        // BLOCK SIZE AND TERMINATOR ARE FIXED BY THE SPEC. SKIPPING THEM MAKES THIS PARSER ACCEPT FILES
        // A STRICTER ONE REJECTS, AND TWO PARSERS DISAGREEING OVER ONE FILE IS A VALIDATION BYPASS
        final int blockSize = buffer.get(at) & 0xFF;
        if (blockSize != 4) throw new CodecException("Invalid GCE block size: " + blockSize + " (must be 4)");
        final int terminator = buffer.get(at + 5) & 0xFF;
        if (terminator != 0) throw new CodecException("Invalid GCE block terminator: " + terminator + " (must be 0)");

        final int packedFields = buffer.get(at + 1) & 0xFF;
        buffer.position(at + BODY_SIZE);
        return new GraphicExtension((packedFields & 0b0001_1100) >> 2, (packedFields & 0b0000_0010) != 0,
                (packedFields & 0b0000_0001) != 0, DataTool.u16(buffer, at + 2, ByteOrder.LITTLE_ENDIAN), buffer.get(at + 4) & 0xFF);
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(BODY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 4);
        int packed = 0;
        packed |= (this.disposalMethod & 0b111) << 2;
        if (this.userInputFlag) packed |= 0b0000_0010;
        if (this.transparentColorFlag) packed |= 0b0000_0001;
        buf.put((byte) packed);
        buf.putShort((short) this.delayTime);
        buf.put((byte) this.transparentColorIndex);
        buf.put((byte) 0);
        return buf.array();
    }
}
