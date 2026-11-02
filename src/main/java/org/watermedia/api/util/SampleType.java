package org.watermedia.api.util;

/** Canonical interleaved PCM sample type, shared by the audio engines and the encoders. */
public enum SampleType {
    /** Unsigned 8-bit integer. */
    U8(1),
    /** Signed 16-bit integer, little-endian. */
    S16(2),
    /** Signed 32-bit integer, little-endian. */
    S32(4),
    /** IEEE 754 32-bit float, little-endian. Expected range [-1.0, +1.0]. */
    FLT(4),
    /** IEEE 754 64-bit float, little-endian. Expected range [-1.0, +1.0]. */
    DBL(8);

    private final int bytes;

    SampleType(final int bytes) {
        this.bytes = bytes;
    }

    /** Returns the size of one single-channel sample in bytes. */
    public int bytes() { return this.bytes; }
}
