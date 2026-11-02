package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * cICP (coding-independent code points) chunk: the video signal type identification of HDR images.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#cICP-chunk">PNG Specification - cICP</a>
 */
public record CICP(int colorPrimaries, int transferFunction, int matrixCoefficients, int videoFullRangeFlag) implements IChunk {
    public static final int SIGNATURE = 0x63_49_43_50; // "cICP"
    public static final int LENGTH = 4;

    // COMMON COLOR PRIMARIES (ITU-T H.273)
    public static final int PRIMARIES_BT709 = 1;
    public static final int PRIMARIES_BT2020 = 9;
    public static final int PRIMARIES_P3 = 12;

    // COMMON TRANSFER FUNCTIONS
    public static final int TRANSFER_BT709 = 1;
    public static final int TRANSFER_SRGB = 13;
    public static final int TRANSFER_PQ = 16;    // HDR10
    public static final int TRANSFER_HLG = 18;   // HLG HDR

    /** Reads a complete cICP chunk (length, type, data and CRC) from the buffer. */
    public static CICP read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to cICP. */
    public static CICP convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for cICP: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("cICP data must be 4 bytes, got " + data.length);

        return new CICP(
                data[0] & 0xFF, // COLOR PRIMARIES
                data[1] & 0xFF, // TRANSFER FUNCTION
                data[2] & 0xFF, // MATRIX COEFFICIENTS
                data[3] & 0xFF  // VIDEO FULL RANGE FLAG
        );
    }

    /** Returns whether the image uses full range (0-255) rather than limited range (16-235). */
    public boolean isFullRange() {
        return this.videoFullRangeFlag == 1;
    }

    /** Returns whether this indicates an HDR image (PQ or HLG transfer). */
    public boolean isHDR() {
        return this.transferFunction == TRANSFER_PQ || this.transferFunction == TRANSFER_HLG;
    }

    /** Returns whether this uses the sRGB transfer function. */
    public boolean isSRGB() {
        return this.transferFunction == TRANSFER_SRGB;
    }

    @Override
    public byte[] toBytes() {
        return new byte[] {
                (byte) this.colorPrimaries,
                (byte) this.transferFunction,
                (byte) this.matrixCoefficients,
                (byte) this.videoFullRangeFlag
        };
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
