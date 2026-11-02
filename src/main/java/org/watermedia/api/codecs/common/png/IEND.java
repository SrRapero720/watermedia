package org.watermedia.api.codecs.common.png;

/**
 * IEND (image trailer) chunk: the empty last chunk of a PNG datastream; serialization only.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11IEND">PNG Specification - IEND</a>
 */
public record IEND() implements IChunk {
    public static final int SIGNATURE = 0x49_45_4E_44; // "IEND"

    @Override
    public byte[] toBytes() {
        return new byte[0];
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
