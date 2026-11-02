package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * sRGB (standard RGB color space) chunk: the image uses the sRGB color space with the given rendering intent.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sRGB">PNG Specification - sRGB</a>
 */
public record SRGB(int renderingIntent) implements IChunk {
    public static final int SIGNATURE = 0x73_52_47_42; // "sRGB"
    public static final int LENGTH = 1;

    // RENDERING INTENTS
    public static final int PERCEPTUAL = 0;
    public static final int RELATIVE_COLORIMETRIC = 1;
    public static final int SATURATION = 2;
    public static final int ABSOLUTE_COLORIMETRIC = 3;

    /** Reads a complete sRGB chunk (length, type, data and CRC) from the buffer. */
    public static SRGB read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to sRGB. */
    public static SRGB convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for sRGB: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("sRGB data must be 1 byte, got " + data.length);
        return new SRGB(data[0] & 0xFF);
    }

    /** Returns whether the perceptual rendering intent is used. */
    public boolean isPerceptual() {
        return this.renderingIntent == PERCEPTUAL;
    }

    /** Returns the rendering intent name. */
    public String renderingIntentName() {
        return switch (this.renderingIntent) {
            case PERCEPTUAL -> "Perceptual";
            case RELATIVE_COLORIMETRIC -> "Relative colorimetric";
            case SATURATION -> "Saturation";
            case ABSOLUTE_COLORIMETRIC -> "Absolute colorimetric";
            default -> "Unknown (" + this.renderingIntent + ")";
        };
    }

    @Override
    public byte[] toBytes() {
        return new byte[] { (byte) this.renderingIntent };
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
