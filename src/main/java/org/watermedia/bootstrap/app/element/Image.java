package org.watermedia.bootstrap.app.element;

import java.awt.Color;

/**
 * Displays a GPU texture fitted by {@link Fit}: {@code CONTAIN} letterboxes, {@code COVER} center-crops through UV and
 * {@code STRETCH} distorts to fill. An optional tint multiplies the sampled color.
 */
public final class Image extends Element<Image> {

    private int textureId;
    private int imageWidth;
    private int imageHeight;
    private Fit fit = Fit.CONTAIN;
    private Color tint;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Image image(final int id, final int sourceWidth, final int sourceHeight) {
        this.textureId = id;
        this.imageWidth = sourceWidth;
        this.imageHeight = sourceHeight;
        return this;
    }

    public Image fit(final Fit value) {
        this.fit = value == null ? Fit.CONTAIN : value;
        return this;
    }

    public Image tint(final Color value) {
        this.tint = value;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = Math.max(0, this.imageWidth);
        this.contentHeight = Math.max(0, this.imageHeight);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.textureId <= 0) return;
        final int bx = this.innerLeft();
        final int by = this.innerTop();
        final int bw = this.innerWidth();
        final int bh = this.innerHeight();
        if (bw <= 0 || bh <= 0) return;

        if (this.fit == Fit.STRETCH || this.imageWidth <= 0 || this.imageHeight <= 0) {
            canvas.image(this.textureId, bx, by, bw, bh, this.tint);
            return;
        }

        final float sourceAspect = (float) this.imageWidth / this.imageHeight;
        final float boxAspect = (float) bw / bh;
        if (this.fit == Fit.CONTAIN) {
            final int w;
            final int h;
            if (sourceAspect > boxAspect) {
                w = bw;
                h = Math.round(bw / sourceAspect);
            } else {
                h = bh;
                w = Math.round(bh * sourceAspect);
            }
            canvas.image(this.textureId, bx + (bw - w) / 2, by + (bh - h) / 2, w, h, this.tint);
        } else {
            // COVER: FILL THE BOX AND CENTER-CROP THE OVERFLOWING AXIS THROUGH THE SAMPLED UV RANGE
            float u0 = 0f;
            float v0 = 0f;
            float u1 = 1f;
            float v1 = 1f;
            if (sourceAspect > boxAspect) {
                final float frac = boxAspect / sourceAspect;
                u0 = (1f - frac) / 2f;
                u1 = 1f - u0;
            } else {
                final float frac = sourceAspect / boxAspect;
                v0 = (1f - frac) / 2f;
                v1 = 1f - v0;
            }
            canvas.image(this.textureId, bx, by, bw, bh, u0, v0, u1, v1, this.tint);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    public enum Fit {
        CONTAIN,
        COVER,
        STRETCH
    }
}
