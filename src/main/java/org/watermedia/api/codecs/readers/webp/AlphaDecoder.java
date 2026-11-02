package org.watermedia.api.codecs.readers.webp;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.readers.webp.lossless.VP8LosslessDecoder;
import org.watermedia.api.util.MathUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.watermedia.WaterMedia.LOGGER;

/** WebP ALPH chunk decoder: uncompressed or VP8L-compressed planes with optional horizontal, vertical or gradient filtering. */
public final class AlphaDecoder {
    private static final Marker IT = MarkerManager.getMarker(AlphaDecoder.class.getSimpleName());

    // COMPRESSION METHODS
    private static final int COMPRESS_NONE = 0;
    private static final int COMPRESS_LOSSLESS = 1;

    // FILTERING METHODS; 3 IS GRADIENT
    private static final int FILTER_NONE = 0;
    private static final int FILTER_HORIZ = 1;
    private static final int FILTER_VERT = 2;

    private AlphaDecoder() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Decodes a WebP ALPH payload, inverse filter included, into one alpha byte per pixel; an unknown compression throws {@link UnsupportedFormatException}. */
    public static byte[] decode(final ByteBuffer data, final int w, final int h) throws CodecException {
        final ByteBuffer buffer = data.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.remaining() < 1) throw new CodecException("Alpha chunk too small");

        // HEADER BYTE: [RESERVED:2][PREPROC:2][FILTER:2][COMPRESSION:2]; PREPROCESSING IS A HINT WITHOUT EFFECT
        final int hdr = buffer.get() & 0xFF;
        final int filterMeth = (hdr >> 2) & 0x03;
        final int compMeth = hdr & 0x03;
        LOGGER.debug(IT, "Alpha: filter={}, compression={}", filterMeth, compMeth);

        final byte[] alpha;
        if (compMeth == COMPRESS_NONE) {
            // UNCOMPRESSED: RAW BYTES. THE TRUNCATION CHECK COMES FIRST SO A ONE-BYTE CHUNK CANNOT
            // BUY A FULL-CANVAS PLANE BEFORE FAILING
            final int size = w * h;
            if (buffer.remaining() < size)
                throw new CodecException("Alpha data truncated: declared " + size + " bytes, " + buffer.remaining() + " available");
            alpha = new byte[size];
            buffer.get(alpha);
        } else if (compMeth == COMPRESS_LOSSLESS) {
            // VP8L COMPRESSED WITHOUT ITS HEADER: THE GREEN CHANNEL CARRIES THE ALPHA
            final int[] argb = VP8LosslessDecoder.decode(buffer, w, h);
            alpha = new byte[w * h];
            for (int i = 0; i < argb.length; i++) alpha[i] = (byte) ((argb[i] >> 8) & 0xFF);
        } else {
            throw new UnsupportedFormatException("Unsupported WebP alpha compression method: " + compMeth);
        }

        if (filterMeth != FILTER_NONE) unfilter(alpha, w, h, filterMeth);
        return alpha;
    }

    /** Replaces alpha in the shared prefix of an ARGB array and an alpha plane, leaving RGB unchanged. */
    public static void applyAlpha(final int[] argb, final byte[] alpha) {
        final int len = Math.min(argb.length, alpha.length);
        for (int i = 0; i < len; i++) argb[i] = ((alpha[i] & 0xFF) << 24) | (argb[i] & 0x00FFFFFF);
    }

    /** Replaces the alpha byte of each BGRA pixel up to the shorter buffer capacity or alpha plane. */
    public static void applyAlpha(final ByteBuffer bgra, final byte[] alpha) {
        final int pixelCount = Math.min(bgra.capacity() / 4, alpha.length);
        for (int i = 0; i < pixelCount; i++) bgra.put(i * 4 + 3, alpha[i]);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // INVERSE FILTER (RFC 9649): EVERY METHOD PREDICTS ROW 0 FROM THE LEFT AND COLUMN 0 FROM ABOVE; INTERIOR PIXELS
    // ADD THE LEFT NEIGHBOUR (HORIZONTAL), THE TOP ONE (VERTICAL) OR CLAMP(LEFT + TOP - TOP_LEFT) (GRADIENT), MOD 256
    private static void unfilter(final byte[] alpha, final int w, final int h, final int method) {
        for (int x = 1; x < w; x++) alpha[x] += alpha[x - 1];
        for (int y = 1; y < h; y++) {
            final int row = y * w;
            alpha[row] += alpha[row - w];
            if (method == FILTER_HORIZ) {
                for (int pos = row + 1; pos < row + w; pos++) alpha[pos] += alpha[pos - 1];
            } else if (method == FILTER_VERT) {
                for (int pos = row + 1; pos < row + w; pos++) alpha[pos] += alpha[pos - w];
            } else {
                for (int pos = row + 1; pos < row + w; pos++) {
                    final int pred = (alpha[pos - 1] & 0xFF) + (alpha[pos - w] & 0xFF) - (alpha[pos - w - 1] & 0xFF);
                    alpha[pos] += (byte) MathUtil.clip255(pred);
                }
            }
        }
    }
}
