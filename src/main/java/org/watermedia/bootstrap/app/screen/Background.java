package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;

/** Paints the app backdrop: a deep vertical gradient, a glow band across the top and 1px scanlines every 3px. */
final class Background extends Element<Background> {
    public Background() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        canvas.gradientV(x, y, w, h,
                12f / 255f, 18f / 255f, 44f / 255f, 1f,
                4f / 255f, 6f / 255f, 22f / 255f, 1f);
        canvas.gradientV(x, y, w, Math.min(150, h / 4),
                31f / 255f, 46f / 255f, 92f / 255f, 0.42f,
                4f / 255f, 6f / 255f, 22f / 255f, 0f);
        // sy + 2 < h KEEPS THE LAST SCANLINE INSIDE THE BOX WHEN h IS NOT A MULTIPLE OF 3
        for (int sy = 0; sy + 2 < h; sy += 3) {
            canvas.fill(x, y + sy + 2, w, 1, 0f, 0f, 0f, 0.15f);
        }
    }
}
