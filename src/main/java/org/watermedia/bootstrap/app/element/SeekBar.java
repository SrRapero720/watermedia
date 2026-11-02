package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * A full-width slider with a buffered range and an amber thumb; pressing or dragging snaps the value (0..1) to the
 * pointer and reports it through {@code onChange}.
 */
public final class SeekBar extends Element<SeekBar> {

    private float value;
    private float buffered;
    private Color accent = AppTheme.NEON;
    private int trackHeight = 5;
    private boolean dragging; // POINTER CAPTURED: BRIGHTENS THE THUMB LIKE hovered DOES
    private Consumer<Float> onChange;

    public SeekBar() {
        this.width = MAX_PARENT;
        this.height = 16;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public SeekBar value(final float v) {
        this.value = MathUtil.clamp(v, 0f, 1f);
        return this;
    }

    public SeekBar buffered(final float v) {
        this.buffered = MathUtil.clamp(v, 0f, 1f);
        return this;
    }

    public SeekBar accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public SeekBar trackHeight(final int h) {
        this.trackHeight = Math.max(1, h);
        return this;
    }

    public SeekBar onChange(final Consumer<Float> handler) {
        this.onChange = handler;
        return this;
    }

    public float value() { return this.value; }

    @Override
    public void onDrag(final double mx, final double my) {
        this.apply(mx);
    }

    @Override
    public void onRelease(final double mx, final double my) {
        this.dragging = false;
        this.invalidate();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = 16;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.innerLeft();
        final int w = this.innerWidth();
        final int trackY = this.innerTop() + (this.innerHeight() - this.trackHeight) / 2;
        canvas.fill(x, trackY, w, this.trackHeight, AppTheme.alpha(AppTheme.NEON_DARK, 90));
        canvas.fill(x, trackY, Math.round(w * this.buffered), this.trackHeight, AppTheme.alpha(this.accent, 120));
        canvas.fill(x, trackY, Math.round(w * this.value), this.trackHeight, this.accent);
        final boolean active = this.hovered || this.dragging;
        final int thumb = active ? 14 : 12;
        final int thumbX = x + Math.round(w * this.value) - thumb / 2;
        final int thumbY = this.innerTop() + (this.innerHeight() - thumb) / 2;
        canvas.glow(thumbX, thumbY, thumb, thumb, 0f, AppTheme.AMBER, active ? 0.5f : 0.3f);
        canvas.fill(thumbX, thumbY, thumb, thumb, AppTheme.AMBER);
    }

    @Override
    protected boolean onPress(final double mx, final double my) {
        // ACCEPTING THE PRESS CAPTURES THE POINTER, SO DRAGS KEEP ARRIVING UNTIL RELEASE
        this.dragging = true;
        this.apply(mx);
        return true;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void apply(final double mx) {
        final int w = this.innerWidth();
        this.value = w <= 0 ? 0f : (float) MathUtil.clamp((mx - this.innerLeft()) / w, 0d, 1d);
        this.invalidate();
        if (this.onChange != null) this.onChange.accept(this.value);
    }
}
