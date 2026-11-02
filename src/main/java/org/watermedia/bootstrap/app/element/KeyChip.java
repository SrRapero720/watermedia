package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.util.Locale;

/**
 * A key-cap that shows one shortcut key as a bold label in a neon-outlined cap. With an {@link #icon} it draws the
 * footer style instead: a 22px band with a pixel icon beside the uppercase key.
 */
public final class KeyChip extends Element<KeyChip> {

    private static final float SCALE = AppTheme.TEXT_TINY;

    private String key = "";
    // THE FOOTER CAP SHOWS THE KEY UPPERCASE; KEPT HERE SO MEASURE AND DRAW DO NOT CONVERT IT EVERY FRAME
    private String upper = "";
    private String icon = "";

    public KeyChip() {
        this.padding = Spacing.hv(6, 2);
    }

    public KeyChip(final String key) {
        this();
        this.key(key);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public KeyChip key(final String value) {
        this.key = value == null ? "" : value;
        this.upper = this.key.toUpperCase(Locale.ROOT);
        return this;
    }

    /** Sets the pixel icon; a non-empty one switches to the self-padded footer style. */
    public KeyChip icon(final String name) {
        this.icon = name == null ? "" : name;
        if (!this.icon.isEmpty()) this.padding = Spacing.ZERO;
        return this;
    }

    /** Returns the footer icon for a key name: arrows, play for ENTER, x for ESC, pause for SPACE, info otherwise. */
    public static String keyIcon(final String key) {
        final String k = key == null ? "" : key.toUpperCase(Locale.ROOT);
        if (k.contains("ARROW") || k.contains("UP") || k.contains("DOWN") || k.contains("LEFT") || k.contains("RIGHT")) return "arrows";
        if (k.contains("ENTER")) return "play";
        if (k.contains("ESC")) return "x";
        if (k.contains("SPACE")) return "pause";
        return "info";
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
        if (!this.icon.isEmpty()) {
            this.contentWidth = Math.max(36, this.ctx.text.width(this.upper, AppTheme.TEXT_BODY) + 36);
            this.contentHeight = 22;
            return;
        }
        this.contentWidth = this.ctx.text.widthBold(this.key, SCALE);
        this.contentHeight = this.ctx.text.glyphHeightBold(SCALE);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (!this.icon.isEmpty()) {
            canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.BG_2, 220));
            canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.STROKE_BRIGHT, 1f);
            canvas.icon(this.icon, this.left + 7, this.top + (this.measuredHeight - 12) / 2, 12, AppTheme.TEXT_FAINT);
            if (!this.key.isEmpty())
                canvas.text(this.upper, this.left + 24, this.top + (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2,
                        AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            return;
        }
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON_DARK, 60));
        canvas.stroke(this.left, this.top, this.measuredWidth, this.measuredHeight, AppTheme.alpha(AppTheme.NEON, 110), 1f);
        if (!this.key.isEmpty()) {
            final int tw = canvas.textWidth(this.key, SCALE, true);
            final int th = canvas.textHeight(SCALE, true);
            canvas.text(this.key, this.left + (this.measuredWidth - tw) / 2,
                    this.top + (this.measuredHeight - th) / 2, AppTheme.TEXT_SOFT, SCALE, true);
        }
    }
}
