package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.IntPredicate;

/**
 * A labeled progress strip: numbered square cells joined by rules, where a finished step shows a check and the step in
 * progress lights its border. Step states are read on every draw, so the strip follows live progress.
 */
public final class Stepper extends Element<Stepper> {
    private static final int CELL = 30;
    private static final int LABEL_X = 42;
    private static final int STEP_GAP = 56;

    private final String[] labels;
    private IntPredicate complete = step -> false;
    private IntPredicate active = step -> false;

    public Stepper(final String... labels) {
        this.labels = labels == null ? new String[0] : labels.clone();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets the live test for finished steps; steps count from 1. */
    public Stepper complete(final IntPredicate test) {
        this.complete = test == null ? step -> false : test;
        return this;
    }

    /** Sets the live test for the step in progress; steps count from 1. */
    public Stepper active(final IntPredicate test) {
        this.active = test == null ? step -> false : test;
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        int w = 0;
        if (this.ctx != null && this.ctx.text != null)
            for (int i = 0; i < this.labels.length; i++) {
                if (i > 0) w += STEP_GAP;
                w += LABEL_X + this.ctx.text.width(this.labels[i], AppTheme.TEXT_BODY);
            }
        this.contentWidth = w;
        this.contentHeight = CELL;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int y = this.innerTop();
        int x = this.innerLeft();
        for (int i = 0; i < this.labels.length; i++) {
            final int step = i + 1;
            final boolean done = this.complete.test(step);
            final boolean act = this.active.test(step);
            final Color color = done ? AppTheme.GREEN : act ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
            canvas.fill(x, y, CELL, CELL, AppTheme.alpha(AppTheme.BG_2, 220));
            canvas.stroke(x, y, CELL, CELL, color, act || done ? Theme.BORDER_HEAVY : Theme.BORDER_HAIRLINE);
            if (done) canvas.icon("check", x + 8, y + 8, 14, color);
            else canvas.text(String.valueOf(step), x + 11, y + 7, color, AppTheme.TEXT_BODY, false);
            canvas.text(this.labels[i], x + LABEL_X, y + 8, color, AppTheme.TEXT_BODY, false);
            final int end = x + LABEL_X + canvas.textWidth(this.labels[i], AppTheme.TEXT_BODY, false);
            // THE RULE TO THE NEXT STEP STARTS 10 PX AFTER THIS LABEL AND STOPS 12 PX BEFORE THE NEXT CELL
            if (i < this.labels.length - 1) canvas.fill(end + 10, y + 15, STEP_GAP - 22, 1, AppTheme.STROKE_BRIGHT);
            x = end + STEP_GAP;
        }
    }
}
