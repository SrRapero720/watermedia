package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;

/** An icon-only button that centers one icon inside the {@link Button} box, with one accent driving the whole look. */
public final class IconButton extends Element<IconButton> {

    private static final int DEFAULT_ICON = 14;

    private String icon = "";
    private int iconSize = DEFAULT_ICON;
    private Color accent = AppTheme.NEON;
    private Color iconColor = AppTheme.TEXT;
    // REPLACES BOTH THE ACCENT AND THE ICON COLOR WHILE HOVERED; null KEEPS THEM
    private Color hoverColor;
    private boolean filled;
    private boolean bordered = true;

    public IconButton() {}

    public IconButton(final String icon) {
        this.icon = icon == null ? "" : icon;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public IconButton icon(final String key) {
        this.icon = key == null ? "" : key;
        return this;
    }

    public IconButton iconSize(final int px) {
        this.iconSize = Math.max(1, px);
        return this;
    }

    public IconButton accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public IconButton iconColor(final Color value) {
        this.iconColor = value;
        return this;
    }

    /** Sets the color that tints the box and the icon while hovered, for a control that lights up in its own hue. */
    public IconButton hoverColor(final Color value) {
        this.hoverColor = value;
        return this;
    }

    public IconButton filled(final boolean value) {
        this.filled = value;
        return this;
    }

    /** Sets whether the button box is drawn; {@code false} leaves a bare centered icon. */
    public IconButton bordered(final boolean value) {
        this.bordered = value;
        return this;
    }

    /** Paints an icon button into ({@code x,y,w,h}); {@code bordered=false} draws only the centered icon. */
    public static void render(final Canvas canvas, final int x, final int y, final int w, final int h, final String icon,
                              final int iconSize, final Color accent, final Color iconColor,
                              final boolean filled, final boolean hover, final boolean enabled, final boolean bordered) {
        if (bordered) Button.box(canvas, x, y, w, h, accent, filled, hover, enabled);
        if (icon != null && !icon.isEmpty()) {
            final Color ic = !enabled ? AppTheme.TEXT_FAINT : bordered && filled ? AppTheme.BG_0 : iconColor;
            canvas.icon(icon, x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, ic);
        }
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        // SQUARE BY DEFAULT: THE ICON PLUS EVEN PADDING ON EVERY SIDE
        this.contentWidth = this.iconSize + 12;
        this.contentHeight = this.iconSize + 12;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final boolean lit = this.hovered && this.hoverColor != null;
        render(canvas, this.left, this.top, this.measuredWidth, this.measuredHeight, this.icon, this.iconSize,
                lit ? this.hoverColor : this.accent, lit ? this.hoverColor : this.iconColor,
                this.filled, this.hovered, this.enabled, this.bordered);
    }
}
