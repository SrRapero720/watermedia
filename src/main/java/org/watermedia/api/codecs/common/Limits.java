package org.watermedia.api.codecs.common;

/** Dimension ceilings shared by every decoder: 16K per axis keeps {@code width * height * 4} within an int, 64 megapixels bounds a canvas. */
public final class Limits {
    public static final int MAX_DIM = 16_384;
    public static final int MAX_PIXELS = 1 << 26;

    private Limits() {}

    /** Returns whether a canvas of the given size stays within both ceilings. */
    public static boolean fits(final int width, final int height) {
        return width > 0 && height > 0 && width <= MAX_DIM && height <= MAX_DIM && (long) width * height <= MAX_PIXELS;
    }
}
