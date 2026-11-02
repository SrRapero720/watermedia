package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.awt.Font;

/**
 * A clickable control with an optional leading icon, a label that truncates before an optional subtext chip pinned
 * right. The static {@link #render} and {@link #width} paint and size the same button for screens that lay out by hand.
 */
public final class Button extends Element<Button> {

    // INTERNAL METRICS SHARED BY THE VIEW AND THE STATIC render(), SO BOTH DRAW THE SAME BUTTON
    private static final int LEFT_PAD = 12;
    private static final int RIGHT_PAD = 8;
    private static final int ICON_SIZE = 14;
    private static final int ICON_GAP = 8;

    private String label = "";
    private String subText = "";
    private int subtextSpacing = 12;
    private String icon = "";
    private Color accent = AppTheme.NEON;
    private Color textColor = AppTheme.TEXT;
    private Color subTextColor = AppTheme.TEXT_FAINT;
    private boolean filled;

    public Button() {
        this.padding = Spacing.ZERO; // THE STATIC render() OWNS THE INTERNAL PADDING (LEFT_PAD/RIGHT_PAD)
    }

    public Button(final String label) {
        this();
        this.label = label == null ? "" : label;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Button label(final String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    public Button subText(final String value) {
        this.subText = value == null ? "" : value;
        return this;
    }

    public Button subtextSpacing(final int px) {
        this.subtextSpacing = Math.max(0, px);
        return this;
    }

    public Button icon(final String key) {
        this.icon = key == null ? "" : key;
        return this;
    }

    public Button accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public Button textColor(final Color value) {
        this.textColor = value;
        return this;
    }

    /** Sets the color of the subtext chip's text, for a chip that shows a value instead of a keybind. */
    public Button subTextColor(final Color value) {
        this.subTextColor = value == null ? AppTheme.TEXT_FAINT : value;
        return this;
    }

    public Button filled(final boolean value) {
        this.filled = value;
        return this;
    }

    /** Returns the wrap width of a button: internal padding, icon, label, spacing and subtext chip. */
    public static int width(final TextRenderer text, final String label, final String subText,
                            final String icon, final int subtextSpacing) {
        final int iconW = icon == null || icon.isEmpty() ? 0 : ICON_SIZE + ICON_GAP;
        final int labelW = label == null || label.isEmpty() ? 0 : text.widthBold(label, AppTheme.TEXT_BUTTON);
        final int chipW = subText == null || subText.isEmpty() ? 0 : text.width(subText, AppTheme.TEXT_SUBTITLE) + Theme.CHIP_PAD * 2;
        return LEFT_PAD + iconW + labelW + (chipW == 0 ? 0 : subtextSpacing + chipW) + RIGHT_PAD;
    }

    /** Paints a button into ({@code x,y,w,h}) with the default faint subtext chip color. */
    public static void render(final Canvas canvas, final int x, final int y, final int w, final int h,
                              final String label, final String subText, final String icon, final int subtextSpacing,
                              final Color accent, final Color textColor, final boolean filled,
                              final boolean hover, final boolean enabled) {
        render(canvas, x, y, w, h, label, subText, icon, subtextSpacing, accent, textColor, AppTheme.TEXT_FAINT, filled, hover, enabled);
    }

    /** Paints a button into ({@code x,y,w,h}) with an explicit subtext chip color. */
    public static void render(final Canvas canvas, final int x, final int y, final int w, final int h,
                              final String label, final String subText, final String icon, final int subtextSpacing,
                              final Color accent, final Color textColor, final Color subTextColor, final boolean filled,
                              final boolean hover, final boolean enabled) {
        box(canvas, x, y, w, h, accent, filled, hover, enabled);

        final TextRenderer text = canvas.text();
        final Color tc = !enabled ? AppTheme.TEXT_FAINT : filled ? AppTheme.BG_0 : textColor;
        final int cy = y + h / 2;
        int cx = x + LEFT_PAD;
        if (icon != null && !icon.isEmpty()) {
            canvas.icon(icon, cx, cy - ICON_SIZE / 2, ICON_SIZE, tc);
            cx += ICON_SIZE + ICON_GAP;
        }

        final int chipW = subText == null || subText.isEmpty() ? 0 : text.width(subText, AppTheme.TEXT_SUBTITLE) + Theme.CHIP_PAD * 2;
        final int chipX = x + w - RIGHT_PAD - chipW;
        if (chipW > 0) {
            canvas.stroke(chipX, cy - Theme.CHIP_H / 2, chipW, Theme.CHIP_H, AppTheme.STROKE, 1f);
            // CHIP TEXT SHARES THE LABEL BASELINE, SINCE CELL-CENTERING DRIFTS WITH EACH SIZE'S DESCENDER SPACE
            final float baseline = cy - text.glyphHeightBold(AppTheme.TEXT_BUTTON) / 2f + text.baselineOffsetBold(AppTheme.TEXT_BUTTON);
            canvas.text(subText, chipX + Theme.CHIP_PAD, baseline - text.baselineOffset(AppTheme.TEXT_SUBTITLE),
                    subTextColor, AppTheme.TEXT_SUBTITLE, false);
        }

        if (label != null && !label.isEmpty()) {
            final int avail = Math.max(0, (chipW == 0 ? x + w - RIGHT_PAD : chipX - subtextSpacing) - cx);
            final String draw = text.widthBold(label, AppTheme.TEXT_BUTTON) > avail
                    ? text.truncateToWidth(label, avail, AppTheme.TEXT_BUTTON, Font.BOLD) : label;
            canvas.text(draw, cx, cy - text.glyphHeightBold(AppTheme.TEXT_BUTTON) / 2f, tc, AppTheme.TEXT_BUTTON, true);
        }
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
        this.contentWidth = width(this.ctx.text, this.label, this.subText, this.icon, this.subtextSpacing);
        this.contentHeight = Math.max(this.ctx.text.glyphHeightBold(AppTheme.TEXT_BUTTON), Theme.CHIP_H);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || this.ctx.text == null) return;
        render(canvas, this.left, this.top, this.measuredWidth, this.measuredHeight,
                this.label, this.subText, this.icon, this.subtextSpacing,
                this.accent, this.textColor, this.subTextColor, this.filled, this.hovered, this.enabled);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    static void box(final Canvas canvas, final int x, final int y, final int w, final int h,
                    final Color accent, final boolean filled, final boolean hover, final boolean enabled) {
        // SHARED WITH IconButton: EVERY STATE DERIVES FROM accent, SO EACH BUTTON HOVERS IN ITS OWN HUE
        if (filled) {
            canvas.fill(x, y, w, h, hover && enabled ? accent : AppTheme.alpha(accent, 210));
        } else {
            canvas.fill(x, y, w, h, hover && enabled ? AppTheme.alpha(accent, 60) : AppTheme.alpha(AppTheme.BG_2, 220));
            canvas.stroke(x, y, w, h, enabled ? accent : AppTheme.alpha(accent, 90), hover ? 1.7f : Theme.BORDER_ACTIVE);
        }
        if (enabled) canvas.glow(x, y, w, h, 0f, accent, hover ? 0.26f : 0.16f);
    }
}
