package org.watermedia.api.player;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link SWEngine} that renders frames into an AWT {@link BufferedImage}.
 * <p>
 * Each frame's BGRA bytes are converted into the image's {@code TYPE_INT_ARGB} pixels, which draw
 * fast through {@code Graphics#drawImage}. Paint {@link #image()} from a component and repaint on the
 * {@code onFrame} hook; re-read it after {@code onFrame} because a resolution change replaces the
 * image. Uses only {@code java.desktop}, so it needs no extra dependency.
 */
public final class AWTEngine extends SWEngine {
    private volatile BufferedImage image;
    private int[] argb;

    AWTEngine(final Runnable onFrame) {
        super(onFrame);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** The current frame image; changes on a resolution change, so re-read it after each frame. */
    public BufferedImage image() {
        return this.image;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    void allocSurface(final int width, final int height, final ByteBuffer bgra) {
        final BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        this.argb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        this.image = img;
    }

    @Override
    void present() {
        final int[] px = this.argb;
        final ByteBuffer b = this.bgra;
        if (px == null || b == null) return;
        if (b.order() == ByteOrder.LITTLE_ENDIAN) {
            // BGRA BYTES READ AS A NATIVE (LE) INT ARE EXACTLY 0xAARRGGBB == TYPE_INT_ARGB: BULK COPY
            b.clear();
            b.asIntBuffer().get(px);
        } else {
            // BIG-ENDIAN: PACK BGRA BYTES INTO 0xAARRGGBB ONE PIXEL AT A TIME
            for (int i = 0, o = 0; i < px.length; i++, o += 4) {
                px[i] = ((b.get(o + 3) & 0xFF) << 24) | ((b.get(o + 2) & 0xFF) << 16)
                        | ((b.get(o + 1) & 0xFF) << 8) | (b.get(o) & 0xFF);
            }
        }
    }

    @Override
    void disposeSurface() {
        this.image = null;
        this.argb = null;
    }
}
