package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Bounded zlib inflation shared by the compressed ancillary chunks. */
public final class Zlib {
    private static final int CHUNK_SIZE = 4_096;

    private Zlib() {}

    /**
     * Inflates {@code input} up to {@code maxBytes}; a stream that ends early returns what inflated when
     * {@code tolerateTruncated} is set and fails otherwise.
     */
    public static byte[] inflate(final byte[] input, final int maxBytes, final String what, final boolean tolerateTruncated) throws CodecException {
        final Inflater inflater = new Inflater();
        inflater.setInput(input);
        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, Math.max(CHUNK_SIZE, input.length * 4)));
        final byte[] buffer = new byte[CHUNK_SIZE];
        try {
            // EVERY ITERATION EITHER PRODUCES BYTES OR LEAVES THE LOOP: A ZLIB HEADER WITH FDICT SET
            // RETURNS Z_NEED_DICT (0 IN, 0 OUT, needsInput() FALSE) AND WOULD OTHERWISE SPIN FOREVER
            while (true) {
                final int length = inflater.inflate(buffer);
                if (length > 0) {
                    if (output.size() + length > maxBytes) throw new CodecException(what + " exceeds " + maxBytes + " bytes decompressed");
                    output.write(buffer, 0, length);
                    continue;
                }
                if (inflater.finished()) break;
                if (inflater.needsDictionary()) throw new CodecException(what + " uses an unsupported compression dictionary");
                if (inflater.needsInput()) {
                    if (tolerateTruncated) break;
                    throw new CodecException("Incomplete compressed " + what);
                }
                throw new CodecException("Invalid compressed " + what + " stream");
            }
        } catch (final DataFormatException e) {
            throw new CodecException("Invalid compressed " + what + ": " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
        return output.toByteArray();
    }
}
