package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * zTXt (compressed textual data) chunk: a zlib-compressed Latin-1 keyword-value pair.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11zTXt">PNG Specification - zTXt</a>
 */
public record ZTXT(String keyword, int compressionMethod, byte[] compressedText) implements IChunk {
    public static final int SIGNATURE = 0x7A_54_58_74; // "zTXt"
    // CAP DECOMPRESSED TEXT: A FEW COMPRESSED BYTES CAN INFLATE TO GIGABYTES (DECOMPRESSION BOMB)
    private static final int MAX_DECOMPRESSED_BYTES = 2 * 1_024 * 1_024;

    /** Reads a complete zTXt chunk (length, type, data and CRC) from the buffer. */
    public static ZTXT read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to zTXt. */
    public static ZTXT convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for zTXt: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        // THE KEYWORD TAKES 1-79 BYTES AND ENDS AT A NUL
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }
        if (nullIndex < 1) throw new CodecException("Invalid zTXt: missing or empty keyword");
        // NEED THE COMPRESSION-METHOD BYTE AFTER THE KEYWORD NUL; A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < nullIndex + 2) throw new CodecException("Truncated zTXt: missing compression method");

        final String keyword = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int compressionMethod = data[nullIndex + 1] & 0xFF;
        if (compressionMethod != 0) throw new CodecException("Unknown zTXt compression method: " + compressionMethod);

        final int textStart = nullIndex + 2;
        final byte[] compressedText = new byte[data.length - textStart];
        System.arraycopy(data, textStart, compressedText, 0, compressedText.length);
        return new ZTXT(keyword, compressionMethod, compressedText);
    }

    /** Decompresses and returns the text; a truncated stream keeps what decompressed, the image is still usable. */
    public String text() throws CodecException { return this.text(MAX_DECOMPRESSED_BYTES); }

    /** Inflates the text within the given byte budget, which the 2 MiB chunk ceiling still caps. */
    public String text(final int maxBytes) throws CodecException {
        return new String(Zlib.inflate(this.compressedText, Math.min(maxBytes, MAX_DECOMPRESSED_BYTES), "zTXt", true), StandardCharsets.ISO_8859_1);
    }

    @Override
    public byte[] toBytes() {
        final byte[] keywordBytes = this.keyword.getBytes(StandardCharsets.ISO_8859_1);
        final byte[] data = new byte[keywordBytes.length + 1 + 1 + this.compressedText.length];
        System.arraycopy(keywordBytes, 0, data, 0, keywordBytes.length);
        data[keywordBytes.length] = 0;
        data[keywordBytes.length + 1] = (byte) this.compressionMethod;
        System.arraycopy(this.compressedText, 0, data, keywordBytes.length + 2, this.compressedText.length);
        return data;
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
