package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/** A box with an optional trailing label whose click toggles the checked state and reports it through {@code onChange}. */
public final class CheckBox extends Element<CheckBox> {

    private static final int BOX = 18;
    private static final int GAP = 8;

    private boolean checked;
    private String label = "";
    private Color accent = AppTheme.NEON;
    private Consumer<Boolean> onChange;
    private float scale = AppTheme.TEXT_BODY;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public CheckBox checked(final boolean value) {
        this.checked = value;
        return this;
    }

    public CheckBox label(final String value) {
        this.label = value == null ? "" : value;
        return this;
    }

    public CheckBox accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public CheckBox onChange(final Consumer<Boolean> handler) {
        this.onChange = handler;
        return this;
    }

    public CheckBox scale(final float value) {
        this.scale = value;
        return this;
    }

    public boolean checked() { return this.checked; }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        this.checked = !this.checked;
        this.invalidate();
        if (this.onChange != null) this.onChange.accept(this.checked);
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.ctx == null || this.ctx.text == null) {
            this.contentWidth = BOX;
            this.contentHeight = BOX;
            return;
        }
        final int th = this.ctx.text.glyphHeight(this.scale);
        this.contentWidth = BOX + (this.label.isEmpty() ? 0 : GAP + this.ctx.text.width(this.label, this.scale));
        this.contentHeight = Math.max(BOX, th);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int boxX = this.innerLeft();
        final int boxY = this.top + (this.measuredHeight - BOX) / 2;
        final Color edge = this.enabled ? this.accent : AppTheme.alpha(this.accent, 90);
        if (this.checked) canvas.fill(boxX, boxY, BOX, BOX, AppTheme.alpha(this.accent, 70));
        canvas.stroke(boxX, boxY, BOX, BOX, edge, 1.4f);
        if (this.checked) {
            // TICK: FROM THE LOWER-LEFT DOWN TO THE ELBOW, THEN UP TO THE UPPER-RIGHT
            final Color mark = this.enabled ? AppTheme.TEXT : AppTheme.TEXT_FAINT;
            canvas.line(boxX + 4.5f, boxY + 9f, boxX + 7.5f, boxY + 13f, mark, 2f);
            canvas.line(boxX + 7.5f, boxY + 13f, boxX + 13.5f, boxY + 5f, mark, 2f);
        }
        if (!this.label.isEmpty()) {
            final int th = canvas.textHeight(this.scale, false);
            canvas.text(this.label, boxX + BOX + GAP, this.top + (this.measuredHeight - th) / 2,
                    AppTheme.TEXT, this.scale, false);
        }
    }
}
