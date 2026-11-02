package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Generic PNG chunk: every chunk follows the same layout of length, type, data and CRC.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#5Chunk-layout">PNG Specification - Chunk Layout</a>
 */
public record PngChunk(int length, int type, byte[] data, int crc) {
    // HARD SANITY CAP FOR A SINGLE CHUNK READ FROM A STREAM, WHERE THE REMAINING LENGTH IS UNKNOWN
    private static final int MAX_CHUNK_BYTES = 256 * 1_024 * 1_024;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete chunk from the buffer position, advancing past it. */
    public static PngChunk read(final ByteBuffer buffer) throws IOException {
        try {
            if (buffer.remaining() < 8) throw new EOFException("Unexpected EOF while reading chunk header");
            final int length = buffer.getInt();
            // THE PNG LENGTH FIELD IS UNSIGNED, SO 0x80000000 READS BACK NEGATIVE; REPORT THE RAW VALUE
            if (length < 0) throw new CodecException("Invalid chunk length: " + Integer.toUnsignedString(length));
            // LONG MATH: length+4 OVERFLOWS int FOR length >= 0x7FFFFFFC, BYPASSING THE EOF CHECK INTO A ~2GB ALLOC
            if (buffer.remaining() < (long) length + 4) throw new EOFException("Unexpected EOF while reading chunk data");
            final int type = buffer.getInt();
            final byte[] data = new byte[length];
            buffer.get(data, 0, length);
            final int crc = buffer.getInt();
            return new PngChunk(length, type, data, crc);
        } catch (final BufferUnderflowException e) {
            throw new EOFException("Unexpected EOF while reading chunk");
        }
    }

    /** Reads a complete chunk from the buffer and fails when its type is not the expected one. */
    public static PngChunk read(final ByteBuffer buffer, final int expectedType) throws IOException {
        // THE TYPE COMES FROM FILE DATA, SO A MISMATCH IS A CODEC ERROR AND NOT A CALLER BUG
        final PngChunk chunk = read(buffer);
        if (chunk.type() != expectedType) throw new CodecException("Expected " + typeName(expectedType) + " chunk, got " + chunk.typeName());
        return chunk;
    }

    /**
     * Reads a complete big-endian chunk (length, type, data, CRC) from a stream, pulling only the bytes of this chunk;
     * the stream is left positioned at the next chunk.
     */
    public static PngChunk read(final InputStream in) throws IOException {
        final int length = readIntBE(in);
        // THE PNG LENGTH FIELD IS UNSIGNED, SO 0x80000000 READS BACK NEGATIVE; REPORT THE RAW VALUE
        if (length < 0) throw new CodecException("Invalid chunk length: " + Integer.toUnsignedString(length));
        // NO STREAM LENGTH IS KNOWN HERE, SO A HARD SANITY CAP STOPS A ~2GB ALLOC FROM 4 ATTACKER BYTES
        if (length > MAX_CHUNK_BYTES) throw new CodecException("PNG chunk too large: " + length + " bytes (max " + MAX_CHUNK_BYTES + ")");
        final int type = readIntBE(in);
        final byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            final int n = in.read(data, read, length - read);
            if (n < 0) throw new EOFException("Unexpected EOF while reading chunk data (" + read + "/" + length + ")");
            read += n;
        }
        final int crc = readIntBE(in);
        return new PngChunk(length, type, data, crc);
    }

    /** Creates a chunk with the CRC of its type and data; the data array is shared, not copied. */
    public static PngChunk create(final int type, final byte[] data) {
        final PngChunk temp = new PngChunk(data.length, type, data, 0);
        return new PngChunk(data.length, type, data, temp.calculateCRC());
    }

    /** Calculates the CRC-32 of a byte range. */
    public static int crc32(final byte[] data, final int offset, final int length) {
        final CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    /** Returns whether the stored CRC differs from the one calculated over the type and data. */
    public boolean corrupted() {
        return this.crc != this.calculateCRC();
    }

    /** Calculates the CRC-32 of the chunk type and data. */
    public int calculateCRC() {
        // THE JDK CRC-32 IS AN INTRINSIC; THE TYPE IS BIG-ENDIAN AHEAD OF THE DATA
        final CRC32 crc = new CRC32();
        crc.update(new byte[] { (byte) (this.type >>> 24), (byte) (this.type >>> 16), (byte) (this.type >>> 8), (byte) this.type });
        crc.update(this.data);
        return (int) crc.getValue();
    }

    /** Returns the chunk type as a 4-character string. */
    public String typeName() {
        return typeName(this.type);
    }

    /** Returns whether this is a critical chunk (first byte uppercase). */
    public boolean isCritical() {
        return ((this.type >> 24) & 0x20) == 0;
    }

    /** Returns whether this is a public chunk (second byte uppercase). */
    public boolean isPublic() {
        return ((this.type >> 16) & 0x20) == 0;
    }

    /** Returns whether this chunk is safe to copy (fourth byte lowercase). */
    public boolean isSafeToCopy() {
        return (this.type & 0x20) != 0;
    }

    /** Writes length, type, data and CRC into a big-endian buffer with room for the data plus twelve bytes. */
    public void write(final ByteBuffer buffer) {
        buffer.putInt(this.length);
        buffer.putInt(this.type);
        buffer.put(this.data);
        buffer.putInt(this.crc);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static int readIntBE(final InputStream in) throws IOException {
        final int b1 = in.read();
        final int b2 = in.read();
        final int b3 = in.read();
        final int b4 = in.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new EOFException("Unexpected EOF while reading chunk header");
        return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
    }

    // RENDERS THE FOUR BIG-ENDIAN ASCII BYTES OF A CHUNK TYPE
    private static String typeName(final int type) {
        return String.valueOf(new char[] { (char) ((type >> 24) & 0xFF), (char) ((type >> 16) & 0xFF), (char) ((type >> 8) & 0xFF), (char) (type & 0xFF) });
    }
}
