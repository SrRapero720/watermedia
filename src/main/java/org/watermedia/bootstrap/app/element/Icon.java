package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;

import java.awt.Color;

/** Draws a tinted {@link PixelIcon} by key at a fixed size, vertically centered in its box. */
public final class Icon extends Element<Icon> {

    private String icon = "";
    private int iconSize = 24;
    private Color color = AppTheme.NEON;

    public Icon() {}

    public Icon(final String icon) {
        this.icon = icon == null ? "" : icon;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Icon icon(final String value) {
        this.icon = value == null ? "" : value;
        return this;
    }

    public Icon iconSize(final int size) {
        this.iconSize = Math.max(1, size);
        return this;
    }

    public Icon color(final Color value) {
        this.color = value;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = this.iconSize;
        this.contentHeight = this.iconSize;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.icon.isEmpty()) return;
        canvas.icon(this.icon, this.innerLeft(), this.innerTop() + Math.max(0, (this.innerHeight() - this.iconSize) / 2),
                this.iconSize, this.color);
    }
}
