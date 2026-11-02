package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * A row of equal-width segments with one selected under a sliding amber thumb; a click reports the index through
 * {@code onSelect}. The index reads as {@link #selectedIndex()} because {@link Element} owns a boolean {@code selected()}.
 */
public final class SegmentedControl extends Element<SegmentedControl> {

    private static final int INSET = 4; // GAP BETWEEN THE SLIDING THUMB AND THE OUTLINE
    private static final int ANIM_MS = 140;

    private String[] segments = new String[0];
    private int selectedIndex;
    private Color accent = AppTheme.NEON;
    private Consumer<Integer> onSelect;
    private float scale = AppTheme.TEXT_BUTTON;
    // THUMB SLIDE: animStart 0 MEANS IDLE; WHILE RUNNING THE THUMB EASES FROM animFrom (CELL INDEX DOMAIN) TO THE
    // SELECTED CELL. IT ANIMATES ONLY AFTER THE FIRST DRAW, SO A FRESH REBIND SNAPS
    private long animStart;
    private float animFrom;
    private boolean drawn;

    public SegmentedControl() {
        this.height = Theme.BUTTON;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public SegmentedControl segments(final String[] values) {
        this.segments = values == null ? new String[0] : values;
        return this;
    }

    public SegmentedControl selected(final int index) {
        if (index != this.selectedIndex) {
            this.slide();
            this.selectedIndex = index;
        }
        return this;
    }

    public SegmentedControl accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public SegmentedControl onSelect(final Consumer<Integer> handler) {
        this.onSelect = handler;
        return this;
    }

    public SegmentedControl scale(final float value) {
        this.scale = value;
        return this;
    }

    public int selectedIndex() { return this.selectedIndex; }

    /** Returns one segment's width for a label, clear of the thumb inset; callers that pre-compute row widths share it. */
    public static int segmentWidth(final TextRenderer text, final String label, final float scale) {
        return Math.max(72, text.width(label, scale) + 40);
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        final int n = this.segments.length;
        if (n == 0 || !this.contains(mx, my)) return false;
        final int segW = this.innerWidth() / n;
        final int idx = MathUtil.clamp(segW > 0 ? (int) ((mx - this.innerLeft()) / segW) : 0, 0, n - 1);
        if (idx != this.selectedIndex) {
            // THE LOGICAL SELECTION FLIPS IMMEDIATELY; ONLY THE THUMB DRAW TRAILS BEHIND WITH THE EASE
            this.slide();
            this.selectedIndex = idx;
            this.invalidate();
            if (this.onSelect != null) this.onSelect.accept(idx);
        }
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        // SELF-SUSTAINED ANIMATION: KEEP REQUESTING FRAMES UNTIL THE SLIDE ENDS, THEN LET THE LOOP IDLE
        if (this.animStart == 0L) return;
        if (System.currentTimeMillis() - this.animStart >= ANIM_MS) this.animStart = 0L;
        else this.invalidate();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentHeight = Theme.BUTTON;
        int total = 0;
        if (this.ctx != null && this.ctx.text != null)
            for (final String seg: this.segments) total += segmentWidth(this.ctx.text, seg, this.scale);
        this.contentWidth = total;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        this.drawn = true;
        final int n = this.segments.length;
        if (n == 0) return;
        final int base = this.innerLeft();
        final int w = this.innerWidth();
        final int h = this.innerHeight();
        final int y = this.innerTop();

        // CELL EDGES COME FROM LONG MATH ON THE FULL WIDTH, SO ROUNDING NEVER LEAVES A SEAM BETWEEN WASHES
        for (int i = 0; i < n; i++) {
            final int x0 = base + (int) ((long) w * i / n);
            final int x1 = base + (int) ((long) w * (i + 1) / n);
            canvas.fill(x0, y, x1 - x0, h, AppTheme.alpha(this.accent, 22));
        }

        float center = -1f;
        if (this.selectedIndex >= 0 && this.selectedIndex < n) {
            final float p = this.thumbPos(System.currentTimeMillis());
            final int tx0 = base + Math.round(w * p / n);
            final int tx1 = base + Math.round(w * (p + 1f) / n);
            canvas.glow(tx0 + INSET, y + INSET, tx1 - tx0 - INSET * 2, h - INSET * 2, 0f, AppTheme.AMBER, 0.28f);
            canvas.fill(tx0 + INSET, y + INSET, tx1 - tx0 - INSET * 2, h - INSET * 2, AppTheme.alpha(AppTheme.AMBER, 210));
            center = (tx0 + tx1) / 2f;
        }

        // A LABEL DARKENS WHILE THE THUMB CENTER SITS UNDER ITS CELL, SO THE INK FOLLOWS THE SLIDE
        for (int i = 0; i < n; i++) {
            final String label = this.segments[i];
            if (label == null || label.isEmpty()) continue;
            final int x0 = base + (int) ((long) w * i / n);
            final int x1 = base + (int) ((long) w * (i + 1) / n);
            final boolean under = center >= x0 && center < x1;
            final Color tc = under ? AppTheme.BG_0 : AppTheme.TEXT_SOFT;
            final int tw = canvas.textWidth(label, this.scale, false);
            final int th = canvas.textHeight(this.scale, false);
            canvas.text(label, x0 + (x1 - x0 - tw) / 2, y + (h - th) / 2, tc, this.scale, false);
        }
        canvas.stroke(base, y, w, h, AppTheme.alpha(this.accent, 120), 1f);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void slide() {
        // RESTARTS THE EASE FROM THE CURRENT VISUAL THUMB POSITION; A CONTROL THAT NEVER DREW (A FRESHLY REBUILT ROW
        // BINDING ITS INITIAL VALUE) SNAPS INSTEAD
        if (!this.drawn) return;
        final long now = System.currentTimeMillis();
        this.animFrom = this.thumbPos(now);
        this.animStart = now;
    }

    private float thumbPos(final long now) {
        // VISUAL THUMB POSITION IN THE CELL-INDEX DOMAIN, CUBIC EASE-OUT WHILE ANIMATING
        final float target = this.selectedIndex;
        if (this.animStart == 0L) return target;
        final float t = Math.min(1f, (now - this.animStart) / (float) ANIM_MS);
        final float inv = 1f - t;
        return this.animFrom + (target - this.animFrom) * (1f - inv * inv * inv);
    }
}
