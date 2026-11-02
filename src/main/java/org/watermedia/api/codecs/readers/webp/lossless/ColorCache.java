package org.watermedia.api.codecs.readers.webp.lossless;

import org.watermedia.api.codecs.CodecException;

// VP8L COLOR CACHE: RECENTLY USED ARGB COLORS IN A POWER-OF-TWO TABLE INDEXED BY A MULTIPLICATIVE HASH
final class ColorCache {
    private static final int HASH_MUL = 0x1E35A7BD;

    private final int[] colors;
    private final int hashShift;
    private final int hashMask;

    // CACHE BITS 1-11 CREATE 2^BITS SLOTS
    ColorCache(final int cacheBits) throws CodecException {
        if (cacheBits < 1 || cacheBits > 11) throw new CodecException("Invalid color cache bits: " + cacheBits);
        final int size = 1 << cacheBits;
        this.colors = new int[size];
        this.hashShift = 32 - cacheBits;
        this.hashMask = size - 1;
    }

    // STORES A COLOR IN ITS HASH SLOT, REPLACING WHATEVER HELD IT
    void insert(final int argb) {
        this.colors[(argb * HASH_MUL) >>> this.hashShift] = argb;
    }

    // READS A SLOT, WRAPPING THE INDEX TO THE CACHE SIZE
    int lookup(final int idx) {
        return this.colors[idx & this.hashMask];
    }

    int size() {
        return this.colors.length;
    }
}
