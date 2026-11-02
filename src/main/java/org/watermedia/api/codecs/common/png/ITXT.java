package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * iTXt (international textual data) chunk: optionally compressed UTF-8 text with a language tag.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11iTXt">PNG Specification - iTXt</a>
 */
public record ITXT(String keyword, boolean compressed, int compressionMethod,
                   String languageTag, String translatedKeyword, byte[] textData) implements IChunk {
    public static final int SIGNATURE = 0x69_54_58_74; // "iTXt"
    // CAP DECOMPRESSED TEXT: A FEW COMPRESSED BYTES CAN INFLATE TO GIGABYTES (DECOMPRESSION BOMB)
    private static final int MAX_DECOMPRESSED_BYTES = 2 * 1_024 * 1_024;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete iTXt chunk (length, type, data and CRC) from the buffer. */
    public static ITXT read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to iTXt. */
    public static ITXT convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for iTXt: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        int offset = 0;

        // KEYWORD (1-79 BYTES + NULL). findNull RETURNS data.length WHEN NO TERMINATOR EXISTS
        int nullIndex = findNull(data, offset, 80);
        if (nullIndex < 1 || nullIndex >= data.length) throw new CodecException("Invalid iTXt: missing or empty keyword");
        final String keyword = new String(data, offset, nullIndex - offset, StandardCharsets.ISO_8859_1);
        offset = nullIndex + 1;

        // COMPRESSION FLAG + METHOD (2 BYTES): A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < offset + 2) throw new CodecException("Truncated iTXt: missing compression flag/method");
        final int compressionFlag = data[offset++] & 0xFF;
        final int compressionMethod = data[offset++] & 0xFF;
        if (compressionFlag > 1 || compressionMethod != 0)
            throw new CodecException("Invalid iTXt compression flag " + compressionFlag + " or method " + compressionMethod);
        final boolean compressed = compressionFlag == 1;

        // LANGUAGE TAG (0+ BYTES + NULL)
        nullIndex = findNull(data, offset, data.length);
        if (nullIndex >= data.length) throw new CodecException("Truncated iTXt: missing language tag terminator");
        final String languageTag = new String(data, offset, nullIndex - offset, StandardCharsets.US_ASCII);
        offset = nullIndex + 1;

        // TRANSLATED KEYWORD (0+ BYTES + NULL)
        nullIndex = findNull(data, offset, data.length);
        if (nullIndex >= data.length) throw new CodecException("Truncated iTXt: missing translated keyword terminator");
        final String translatedKeyword = new String(data, offset, nullIndex - offset, StandardCharsets.UTF_8);
        offset = nullIndex + 1;

        // TEXT DATA (REMAINING BYTES)
        final byte[] textData = new byte[data.length - offset];
        System.arraycopy(data, offset, textData, 0, textData.length);
        return new ITXT(keyword, compressed, compressionMethod, languageTag, translatedKeyword, textData);
    }

    /** Returns the text content, decompressing it when necessary. */
    public String text() throws CodecException { return this.text(MAX_DECOMPRESSED_BYTES); }

    /** Returns the text content inflated within the given byte budget, which the 2 MiB chunk ceiling still caps. */
    public String text(final int maxBytes) throws CodecException {
        if (!this.compressed) return new String(this.textData, StandardCharsets.UTF_8);
        // A TRUNCATED STREAM KEEPS WHAT DECOMPRESSED; THE IMAGE IS STILL USABLE
        return new String(Zlib.inflate(this.textData, Math.min(maxBytes, MAX_DECOMPRESSED_BYTES), "iTXt", true), StandardCharsets.UTF_8);
    }

    @Override
    public byte[] toBytes() {
        final byte[] keywordBytes = this.keyword.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] langBytes = this.languageTag.getBytes(StandardCharsets.US_ASCII);
        final byte[] translatedBytes = this.translatedKeyword.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buf = ByteBuffer.allocate(
                keywordBytes.length + 1 + 1 + 1 + langBytes.length + 1 + translatedBytes.length + 1 + this.textData.length
        );
        buf.put(keywordBytes);
        buf.put((byte) 0);
        buf.put((byte) (this.compressed ? 1 : 0));
        buf.put((byte) this.compressionMethod);
        buf.put(langBytes);
        buf.put((byte) 0);
        buf.put(translatedBytes);
        buf.put((byte) 0);
        buf.put(this.textData);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // INDEX OF THE FIRST NUL IN [start, limit), OR data.length WHEN THERE IS NONE
    private static int findNull(final byte[] data, final int start, final int limit) {
        for (int i = start; i < Math.min(data.length, limit); i++)
            if (data[i] == 0) return i;
        return data.length;
    }
}
