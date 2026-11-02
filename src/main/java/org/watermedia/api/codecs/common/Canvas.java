package org.watermedia.api.codecs.common;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/** Canvas operations shared by the animated readers: clipped rectangle disposal and the packed BGRA hand-off. */
public final class Canvas {
    private Canvas() {}

    /** Fills the rectangle, clipped to the canvas, with one ARGB value. */
    public static void fill(final int[] canvas, final int stride, final int height, final int x, final int y,
                            final int w, final int h, final int argb) {
        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(stride, x + w);
        final int y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return; // FULLY OFF-CANVAS FRAMES ARE LEGAL
        for (int row = y0; row < y1; row++) Arrays.fill(canvas, row * stride + x0, row * stride + x1, argb);
    }

    /** Restores the rectangle, clipped to the canvas, from a saved copy of the whole canvas. */
    public static void restore(final int[] canvas, final int[] saved, final int stride, final int height, final int x, final int y,
                               final int w, final int h) {
        final int x0 = Math.max(0, x);
        final int y0 = Math.max(0, y);
        final int x1 = Math.min(stride, x + w);
        final int y1 = Math.min(height, y + h);
        if (x1 <= x0 || y1 <= y0) return;
        for (int row = y0; row < y1; row++) System.arraycopy(saved, row * stride + x0, canvas, row * stride + x0, x1 - x0);
    }

    /** Hands the ARGB canvas over as packed little-endian BGRA bytes, positioned at zero and ready to read. */
    public static void write(final int[] canvas, final int pixels, final IntBuffer ints, final ByteBuffer bytes) {
        bytes.clear();
        ints.clear();
        ints.put(canvas, 0, pixels);
        bytes.position(0).limit(pixels * 4);
    }
}
