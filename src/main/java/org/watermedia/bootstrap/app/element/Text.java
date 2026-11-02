package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;

/**
 * A single line of text that truncates with an ellipsis when its box is narrower, aligns by {@link Element#gravity}
 * and centers vertically.
 */
public final class Text extends Element<Text> {

    // GAP BEFORE THE TRAILING CHIP, WHOSE PAD AND HEIGHT COME FROM Theme LIKE THE Button CHIP
    private static final int CHIP_GAP = 12;

    private String text = "";
    private String subText = "";
    private float scale = AppTheme.TEXT_BODY;
    private Color color = AppTheme.TEXT;
    private boolean bold;
    private int letterSpacing;

    public Text() {}

    public Text(final String text) {
        this.text = text == null ? "" : text;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Text text(final String value) {
        this.text = value == null ? "" : value;
        return this;
    }

    /** Sets a trailing chip after the text, such as a keybind, drawn like the {@link Button} chip; blank hides it. */
    public Text subText(final String value) {
        this.subText = value == null ? "" : value;
        return this;
    }

    public Text scale(final float value) {
        this.scale = value;
        return this;
    }

    public Text color(final Color value) {
        this.color = value;
        return this;
    }

    public Text bold(final boolean value) {
        this.bold = value;
        return this;
    }

    /** Sets extra pixels between characters; a spaced run is drawn glyph by glyph and never truncates. */
    public Text letterSpacing(final int value) {
        this.letterSpacing = Math.max(0, value);
        return this;
    }

    public String text() { return this.text; }

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
        if (this.text.isEmpty()) this.contentWidth = 0;
        else this.contentWidth = this.letterSpacing > 0
                ? this.ctx.text.spacedWidth(this.text, this.scale, this.bold, this.letterSpacing)
                : this.bold ? this.ctx.text.widthBold(this.text, this.scale) : this.ctx.text.width(this.text, this.scale);
        this.contentHeight = this.bold ? this.ctx.text.glyphHeightBold(this.scale) : this.ctx.text.glyphHeight(this.scale);
        if (!this.subText.isBlank()) {
            this.contentWidth += CHIP_GAP + this.ctx.text.width(this.subText, AppTheme.TEXT_SUBTITLE) + Theme.CHIP_PAD * 2;
            this.contentHeight = Math.max(this.contentHeight, Theme.CHIP_H);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final boolean chip = !this.subText.isBlank();
        if (this.text.isEmpty() && !chip) return;
        final int avail = this.innerWidth();
        final int chipW = chip ? canvas.textWidth(this.subText, AppTheme.TEXT_SUBTITLE, false) + Theme.CHIP_PAD * 2 : 0;
        final int textAvail = chip ? Math.max(0, avail - CHIP_GAP - chipW) : avail;
        final String draw = this.letterSpacing <= 0 && canvas.textWidth(this.text, this.scale, this.bold) > textAvail
                ? canvas.text().truncateToWidth(this.text, textAvail, this.scale, this.bold ? Font.BOLD : Font.PLAIN) : this.text;
        final int tw = this.letterSpacing > 0 ? canvas.textSpacedWidth(draw, this.scale, this.bold, this.letterSpacing)
                : canvas.textWidth(draw, this.scale, this.bold);
        final int th = canvas.textHeight(this.scale, this.bold);
        final int blockW = tw + (chip ? CHIP_GAP + chipW : 0);
        final int tx = switch (this.gravity) {
            case CENTER, FILL -> this.innerLeft() + (avail - blockW) / 2;
            case RIGHT -> this.innerLeft() + avail - blockW;
            default -> this.innerLeft();
        };
        final int ty = this.innerTop() + Math.max(0, (this.innerHeight() - th) / 2);
        if (this.letterSpacing > 0) canvas.textSpaced(draw, tx, ty, this.color, this.scale, this.bold, this.letterSpacing);
        else canvas.text(draw, tx, ty, this.color, this.scale, this.bold);
        if (chip) {
            // CHIP TEXT SHARES THE LABEL BASELINE LIKE Button.render, SINCE CELL-CENTERING DRIFTS WITH DESCENDER SPACE
            final int chipX = tx + tw + CHIP_GAP;
            final float cy = ty + th / 2f;
            canvas.stroke(chipX, cy - Theme.CHIP_H / 2f, chipW, Theme.CHIP_H, AppTheme.STROKE, 1f);
            final float baseline = ty + (this.bold
                    ? canvas.text().baselineOffsetBold(this.scale)
                    : canvas.text().baselineOffset(this.scale));
            canvas.text(this.subText, chipX + Theme.CHIP_PAD,
                    baseline - canvas.text().baselineOffset(AppTheme.TEXT_SUBTITLE),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }
    }
}
