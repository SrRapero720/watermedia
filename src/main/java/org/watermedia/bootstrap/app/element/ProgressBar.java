package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/** A full-width determinate progress bar whose accent fill covers {@link #progress} (0..1) of the track. */
public final class ProgressBar extends Element<ProgressBar> {

    private float progress;
    private Color accent = AppTheme.NEON;
    private int trackHeight = 6;

    public ProgressBar() {
        this.width = MAX_PARENT;
        this.height = this.trackHeight;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public ProgressBar progress(final float value) {
        this.progress = MathUtil.clamp(value, 0f, 1f);
        return this;
    }

    public ProgressBar accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public ProgressBar trackHeight(final int value) {
        // A ZERO OR NEGATIVE TRACK WOULD SILENTLY PAINT NOTHING
        this.trackHeight = Math.max(1, value);
        this.height = this.trackHeight;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = this.trackHeight;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON_DARK, 90));
        final int fillW = Math.round(this.measuredWidth * this.progress);
        if (fillW > 0) canvas.fill(this.left, this.top, fillW, this.measuredHeight, this.accent);
    }
}
