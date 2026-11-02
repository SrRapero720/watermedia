package org.watermedia.api.codecs;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;

/**
 * Shrinks every decoded frame with an area-average filter, never enlarging it. Configurable by an
 * absolute target size, a factor resolved against each frame, or a source size pre-multiplied by a factor.
 */
public final class DownscaleFilter implements ReaderFilter {
    private final float scale;
    private final int outWidth;
    private final int outHeight;

    /** Creates a filter shrinking each frame by {@code scale}, within {@code (0, 1]}. */
    public DownscaleFilter(final float scale) {
        if (!(scale > 0f && scale <= 1f)) throw new IllegalArgumentException("Scale must be within (0, 1], got " + scale);
        this.scale = scale;
        this.outWidth = -1;
        this.outHeight = -1;
    }

    /** Creates a filter resizing each frame to at most {@code width} by {@code height} pixels. */
    public DownscaleFilter(final int width, final int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Target size must be positive, got " + width + "x" + height);
        this.outWidth = width;
        this.outHeight = height;
        this.scale = -1f;
    }

    /** Creates a filter resizing each frame to the source size {@code width} by {@code height} pre-multiplied by {@code scale}. */
    public DownscaleFilter(final int width, final int height, final float scale) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Source size must be positive, got " + width + "x" + height);
        if (!(scale > 0f && scale <= 1f)) throw new IllegalArgumentException("Scale must be within (0, 1], got " + scale);
        this.outWidth = Math.max(1, Math.round(width * scale));
        this.outHeight = Math.max(1, Math.round(height * scale));
        this.scale = -1f;
    }

    @Override
    public Frame compute(final Frame src) {
        // FACTOR MODE RESOLVES THE TARGET AGAINST THE INCOMING FRAME; ABSOLUTE MODE IS CAPPED AT THE SOURCE SO NOTHING UPSCALES
        final int dw = Math.min(src.width(), this.scale > 0f ? Math.max(1, Math.round(src.width() * this.scale)) : this.outWidth);
        final int dh = Math.min(src.height(), this.scale > 0f ? Math.max(1, Math.round(src.height() * this.scale)) : this.outHeight);
        // NOTHING TO DO WHEN THE FORMAT CAN'T BE AREA-AVERAGED OR THE TARGET ALREADY MATCHES THE SOURCE
        if (!DataTool.scalable(src.format()) || (dw == src.width() && dh == src.height())) return src;
        final ByteBuffer dst = ByteBuffer.allocateDirect((int) src.format().frameBytes(dw, dh, 8)).order(src.buffer().order());
        DataTool.scaleArea(src.buffer(), src.width(), src.height(), dst, dw, dh, src.format());
        dst.flip();
        return new Frame(dst, dw, dh, src.format());
    }
}
