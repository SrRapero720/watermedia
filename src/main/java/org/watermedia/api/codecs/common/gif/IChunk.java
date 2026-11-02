package org.watermedia.api.codecs.common.gif;

/** A GIF descriptor, color table or extension body that serializes its stored fields without validating them. */
public interface IChunk {
    /**
     * Returns the block bytes in little-endian order. Descriptors omit their signature or separator;
     * the graphic extension keeps its size and terminator but omits the introducer and label.
     */
    byte[] toBytes();
}
