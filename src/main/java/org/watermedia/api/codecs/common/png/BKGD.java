package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * bKGD (background color) chunk: a default background color to present the image against.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11bKGD">PNG Specification - bKGD</a>
 */
public record BKGD(int gray, int red, int green, int blue, int paletteIndex) implements IChunk {
    public static final int SIGNATURE = 0x62_4B_47_44; // "bKGD"

    /** Creates bKGD for a greyscale image (color type 0 or 4). */
    public BKGD(final int gray) {
        this(gray, -1, -1, -1, -1);
    }

    /** Creates bKGD for a truecolor image (color type 2 or 6). */
    public BKGD(final int red, final int green, final int blue) {
        this(-1, red, green, blue, -1);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Creates bKGD for an indexed-color image (color type 3). */
    public static BKGD forPalette(final int paletteIndex) {
        return new BKGD(-1, -1, -1, -1, paletteIndex);
    }

    /** Reads a complete bKGD chunk (length, type, data and CRC) for the color type from the buffer. */
    public static BKGD read(final ByteBuffer buffer, final int colorType) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE), colorType);
    }

    /** Converts a generic chunk to bKGD for the color type. */
    public static BKGD convert(final PngChunk chunk, final int colorType) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for bKGD: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return switch (ColorType.of(colorType)) {
            case GREYSCALE, GREYSCALE_ALPHA -> {
                if (data.length != 2) throw new CodecException("bKGD for greyscale must be 2 bytes, got " + data.length);
                final int gray = buffer.getShort() & 0xFFFF;
                yield new BKGD(gray);
            }
            case TRUECOLOR, TRUECOLOR_ALPHA -> {
                if (data.length != 6) throw new CodecException("bKGD for truecolor must be 6 bytes, got " + data.length);
                final int red = buffer.getShort() & 0xFFFF;
                final int green = buffer.getShort() & 0xFFFF;
                final int blue = buffer.getShort() & 0xFFFF;
                yield new BKGD(red, green, blue);
            }
            case INDEXED -> {
                if (data.length != 1) throw new CodecException("bKGD for indexed must be 1 byte, got " + data.length);
                final int index = data[0] & 0xFF;
                yield BKGD.forPalette(index);
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException("Forbidden color type: " + colorType);
        };
    }

    /** Returns whether this background is for greyscale. */
    public boolean isGreyscale() {
        return this.gray >= 0;
    }

    /** Returns whether this background is for truecolor. */
    public boolean isTruecolor() {
        return this.red >= 0;
    }

    /** Returns whether this background is for indexed-color. */
    public boolean isIndexed() {
        return this.paletteIndex >= 0;
    }

    /**
     * Returns the greyscale or truecolor background as packed RGB scaled to 8 bits; an indexed background resolves
     * through the palette and yields black here.
     */
    public int toRGB8(final int bitDepth) {
        if (this.isGreyscale()) {
            final int g = scaleTo8Bit(this.gray, bitDepth);
            return (g << 16) | (g << 8) | g;
        }
        if (this.isTruecolor()) {
            final int r = scaleTo8Bit(this.red, bitDepth);
            final int g = scaleTo8Bit(this.green, bitDepth);
            final int b = scaleTo8Bit(this.blue, bitDepth);
            return (r << 16) | (g << 8) | b;
        }
        return 0;
    }

    @Override
    public byte[] toBytes() {
        if (this.isGreyscale()) {
            final ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.gray);
            return buf.array();
        }
        if (this.isTruecolor()) {
            final ByteBuffer buf = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
            buf.putShort((short) this.red);
            buf.putShort((short) this.green);
            buf.putShort((short) this.blue);
            return buf.array();
        }
        return new byte[] { (byte) this.paletteIndex };
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }

    /** Scales a 1, 2, 4, 8 or 16-bit PNG sample to eight bits; 16-bit samples keep their high byte. */
    public static int scaleTo8Bit(final int value, final int depth) {
        if (depth == 8) return value;
        if (depth == 16) return value >> 8;
        if (depth <= 8) return (value * 255) / ((1 << depth) - 1);
        return value;
    }
}
