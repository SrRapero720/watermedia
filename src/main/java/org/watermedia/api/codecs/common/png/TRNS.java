package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * tRNS (transparency) chunk: alpha values for palette entries, or one transparent color for non-indexed images.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11tRNS">PNG Specification - tRNS</a>
 */
public record TRNS(int gray, int red, int green, int blue, byte[] alphaPerPalette) implements IChunk {
    public static final int SIGNATURE = 0x74_52_4E_53; // "tRNS"
    // ONE ALPHA PER PALETTE ENTRY, AND THE SPEC CAPS THE PALETTE AT 256 ENTRIES
    public static final int MAX_PALETTE_ALPHAS = 256;

    /** Creates tRNS for a greyscale image (color type 0). */
    public TRNS(final int gray) {
        this(gray, -1, -1, -1, new byte[0]);
    }

    /** Creates tRNS for a truecolor image (color type 2). */
    public TRNS(final int red, final int green, final int blue) {
        this(-1, red, green, blue, new byte[0]);
    }

    /** Creates tRNS for an indexed-color image (color type 3). */
    public TRNS(final byte[] alphaPerPalette) {
        this(-1, -1, -1, -1, alphaPerPalette);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete tRNS chunk (length, type, data and CRC) for the color type from the buffer. */
    public static TRNS read(final ByteBuffer buffer, final int colorType) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE), colorType);
    }

    /** Converts a generic chunk to tRNS for the color type. */
    public static TRNS convert(final PngChunk chunk, final int colorType) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for tRNS: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return switch (ColorType.of(colorType)) {
            case GREYSCALE -> {
                if (data.length != 2) throw new CodecException("tRNS for greyscale must be 2 bytes, got " + data.length);
                final int gray = buffer.getShort() & 0xFFFF;
                yield new TRNS(gray);
            }
            case TRUECOLOR -> {
                if (data.length != 6) throw new CodecException("tRNS for truecolor must be 6 bytes, got " + data.length);
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new TRNS(red, green, blue);
            }
            case INDEXED -> {
                if (data.length > MAX_PALETTE_ALPHAS) throw new CodecException("tRNS holds more than 256 palette alphas: " + data.length);
                yield new TRNS(data.clone());
            }
            default -> throw new CodecException("tRNS not allowed for color type " + colorType);
        };
    }

    /** Returns the indexed-color alpha (0-255) at a palette index, or 255 when the index has no entry. */
    public int alpha(final int index) {
        if (this.alphaPerPalette == null || index >= this.alphaPerPalette.length) return 255; // FULLY OPAQUE FOR UNDEFINED ENTRIES
        return this.alphaPerPalette[index] & 0xFF;
    }

    @Override
    public byte[] toBytes() {
        if (this.gray >= 0) {
            final ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.gray);
            return buf.array();
        }
        if (this.red >= 0) {
            final ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.red);
            buf.putShort((short) this.green);
            buf.putShort((short) this.blue);
            return buf.array();
        }
        return this.alphaPerPalette.clone();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
