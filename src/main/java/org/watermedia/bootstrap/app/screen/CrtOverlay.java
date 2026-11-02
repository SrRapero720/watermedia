package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.util.function.Consumer;

/**
 * Paints the CRT effect: scanlines every 3px and a neon band travelling down the box. It draws only while
 * {@link AppContext#crt} is on, which the {@code C} shortcut and the Settings row toggle; the band is a live painter,
 * so it moves every frame while the rest of the tree stays cached.
 */
final class CrtOverlay extends Element<CrtOverlay> {
    private static final float NEON_R = AppTheme.NEON.getRed() / 255f;
    private static final float NEON_G = AppTheme.NEON.getGreen() / 255f;
    private static final float NEON_B = AppTheme.NEON.getBlue() / 255f;

    private final Consumer<Canvas> bands = this::bands;

    public CrtOverlay() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
        this.enabled = false; // DISABLED SO HOVER AND CLICKS REACH THE ELEMENTS UNDER THE OVERLAY
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || !this.ctx.crt) return;
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        canvas.pushClip(x, y, w, h);
        for (int sy = y; sy < y + h; sy += 3) {
            canvas.fill(x, sy + 2, w, 1, 0f, 0f, 0f, 0.22f);
        }
        canvas.live(this.bands);
        canvas.popClip();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // THE BAND MOVES 1 px PER STEP FROM FULLY ABOVE THE BOX TO FULLY BELOW IT, SO ONE BAND NEVER NEEDS A WRAP COPY;
    // THE MAIN LOOP WAKES ONCE PER STEP TO COMPOSITE IT
    private void bands(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        final int bandH = Math.max(38, h / 9);
        final int travel = Math.max(1, h + bandH * 2);
        final int bandY = y - bandH * 2 + (int) ((System.currentTimeMillis() / AppContext.CRT_STEP_MS) % travel);
        canvas.gradientV(x, bandY, w, bandH, NEON_R, NEON_G, NEON_B, 0f, NEON_R, NEON_G, NEON_B, 0.14f);
        canvas.gradientV(x, bandY + bandH, w, bandH, NEON_R, NEON_G, NEON_B, 0.14f, NEON_R, NEON_G, NEON_B, 0f);
    }
}
