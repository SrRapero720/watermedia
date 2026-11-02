package org.watermedia.api.codecs.common.png;

/**
 * IDAT (image data) chunk: one slice of the zlib-compressed image stream; serialization only.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11IDAT">PNG Specification - IDAT</a>
 */
public record IDAT(byte[] data) implements IChunk {
    public static final int SIGNATURE = 0x49_44_41_54; // "IDAT"

    @Override
    public byte[] toBytes() {
        return this.data.clone();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.data);
    }
}
