package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A bordered 22px status chip: a glowing pip and an uppercase label tinted by a {@link StatusSquare.Status}, with an
 * optional strike-through for unsupported features. Status and strike are re-read on every draw.
 */
public final class StatusText extends Element<StatusText> {

    private static final int H = 22;

    private String label = "";
    private Supplier<StatusSquare.Status> status = () -> StatusSquare.Status.OFF;
    private BooleanSupplier strike = () -> false;

    public StatusText() {}

    public StatusText(final String label) {
        this.label(label);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets the chip label (rendered uppercase). */
    public StatusText label(final String value) {
        this.label = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return this;
    }

    /** Sets a fixed status. */
    public StatusText status(final StatusSquare.Status value) {
        final StatusSquare.Status fixed = value == null ? StatusSquare.Status.OFF : value;
        this.status = () -> fixed;
        return this;
    }

    /** Sets a live status source, re-read on every draw. */
    public StatusText status(final Supplier<StatusSquare.Status> supplier) {
        this.status = supplier == null ? () -> StatusSquare.Status.OFF : supplier;
        return this;
    }

    /** Sets a fixed strike-through flag. */
    public StatusText strike(final boolean value) {
        this.strike = () -> value;
        return this;
    }

    /** Sets a live strike-through source, re-read on every draw. */
    public StatusText strike(final BooleanSupplier supplier) {
        this.strike = supplier == null ? () -> false : supplier;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null) {
            this.contentWidth = 0;
            this.contentHeight = H;
            return;
        }
        // 24 PX PIP BAND BEFORE THE LABEL PLUS 20 PX AFTER IT
        this.contentWidth = this.ctx.text.width(this.label, AppTheme.TEXT_BODY) + 44;
        this.contentHeight = H;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || this.ctx.text == null) return;
        final Color c = StatusSquare.tint(this.status.get());
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, 190));
        canvas.stroke(x, y, w, h, AppTheme.STROKE_BRIGHT, 1f);
        canvas.fill(x + 8, y + 7, 8, 8, c);
        canvas.glow(x + 8, y + 7, 8, 8, 0f, c, 0.42f);

        final int textX = x + 24;
        final int glyphH = this.ctx.text.glyphHeight(AppTheme.TEXT_BODY);
        final int textY = y + Math.max(0, (h - glyphH) / 2);
        canvas.text(this.label, textX, textY, c, AppTheme.TEXT_BODY, false);
        if (this.strike.getAsBoolean())
            canvas.lineH(textX, textY + Math.max(1, glyphH / 2), this.ctx.text.width(this.label, AppTheme.TEXT_BODY), c, 1f);
    }
}
