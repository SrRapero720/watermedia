package org.watermedia.api.codecs.common.png;

/** A PNG chunk that serializes its data and its complete chunk envelope. */
public interface IChunk {
    /** Returns the chunk data in big-endian order, without the length, type and CRC fields. */
    byte[] toBytes();

    /** Returns the complete chunk with its data length, type and calculated CRC. */
    PngChunk toChunk();
}
