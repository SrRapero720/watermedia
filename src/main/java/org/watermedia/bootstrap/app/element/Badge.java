package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;

/**
 * A small status or category tag sized to its label; {@code filled} paints a solid accent chip, otherwise an
 * accent wash with an accent outline.
 */
public final class Badge extends Element<Badge> {

    private static final float SCALE = AppTheme.TEXT_TINY;

    private String label = "";
    private Color color = AppTheme.NEON;
    private boolean filled;

    public Badge() {
        this.padding = Spacing.hv(8, 3);
    }

    public Badge(final String label) {
        this();
        this.label = label == null ? "" : label;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Badge label(final String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    public Badge color(final Color value) {
        this.color = value == null ? AppTheme.NEON : value;
        return this;
    }

    public Badge filled(final boolean value) {
        this.filled = value;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        this.contentWidth = this.ctx.text.width(this.label, SCALE);
        this.contentHeight = this.ctx.text.glyphHeight(SCALE);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight,
                this.filled ? this.color : AppTheme.alpha(this.color, 36));
        if (!this.filled) canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(this.color, 120), 1f);
        if (!this.label.isEmpty()) {
            final Color tc = this.filled ? AppTheme.BG_0 : this.color;
            final int tw = canvas.textWidth(this.label, SCALE, false);
            final int th = canvas.textHeight(SCALE, false);
            canvas.text(this.label, this.left + (this.measuredWidth - tw) / 2,
                    this.top + (this.measuredHeight - th) / 2, tc, SCALE, false);
        }
    }
}
