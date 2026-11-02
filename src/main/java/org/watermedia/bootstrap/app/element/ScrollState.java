package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;

// SHARED WHEEL-MOMENTUM SCROLL STATE OF THE VERTICAL SCROLLERS (ListView, ParentScroll): THE OFFSET EASES TOWARD AN
// ACCUMULATED WHEEL target WITH EXPONENTIAL DECAY. IDLE INVARIANT: animPos == target == offset
final class ScrollState {

    static final int UNBOUNDED = 1 << 20; // MEASURE CONTENT AT ITS NATURAL HEIGHT, NOT THE VIEWPORT'S
    static final int SCROLL_STEP = 48;
    private static final float TAU_MS = 70f; // MOMENTUM DECAY TIME CONSTANT (~250 MS SETTLE AFTER THE LAST NOTCH)

    int offset;
    int max;
    private float animPos;
    private int target;
    private long tick;

    boolean animate() {
        // EXPONENTIAL APPROACH TOWARD THE WHEEL TARGET: IT NEVER OVERSHOOTS, SO AT A CLAMPED TARGET THE MOTION DIES
        // WITHOUT BOUNCE. TRUE WHILE STILL MOVING, SO THE CALLER KEEPS REQUESTING FRAMES
        if (this.animPos == this.target) return false;
        final long now = System.currentTimeMillis();
        // dt CLAMPED TO [0,64] MS: A STALLED FRAME NEVER TELEPORTS AND A CLOCK JUMP NEVER DIVERGES
        final float dt = MathUtil.clamp(now - this.tick, 0L, 64L);
        this.tick = now;
        this.animPos += (this.target - this.animPos) * (1f - (float) Math.exp(-dt / TAU_MS));
        if (Math.abs(this.target - this.animPos) < 0.5f) this.animPos = this.target;
        this.offset = Math.round(this.animPos);
        return true;
    }

    void clamp(final int maxScroll) {
        // AFTER A RESIZE OR REBUILD NO STALE TARGET SURVIVES OUTSIDE [0, max]
        this.max = Math.max(0, maxScroll);
        this.offset = MathUtil.clamp(this.offset, 0, this.max);
        this.target = MathUtil.clamp(this.target, 0, this.max);
        this.animPos = MathUtil.clamp(this.animPos, 0f, this.max);
    }

    boolean scroll(final double amount) {
        // A WHEEL NOTCH STARTS COASTING FROM THE IDLE OFFSET IF NEEDED, THEN SHIFTS THE TARGET; TRUE WHEN IT MOVED
        if (this.animPos == this.target) {
            this.animPos = this.offset;
            this.target = this.offset;
            this.tick = System.currentTimeMillis();
        }
        final int next = MathUtil.clamp(this.target - (int) (amount * SCROLL_STEP), 0, this.max);
        if (next == this.target) return false;
        this.target = next;
        return true;
    }

    void jump(final int newOffset) {
        // INSTANT AND KILLS ANY MOMENTUM: KEYBOARD NAV AND PROGRAMMATIC SCROLLS EXPECT EXACT GEOMETRY
        this.offset = Math.max(0, newOffset);
        this.target = this.offset;
        this.animPos = this.offset;
    }

    int thumbHeight(final int trackH) {
        // CALLERS DRAW THE THUMB ONLY WHEN max > 0
        return Math.max(24, (int) ((long) trackH * trackH / (trackH + this.max)));
    }

    int thumbY(final int trackTop, final int trackH, final int thumbH) {
        return trackTop + (int) ((long) (trackH - thumbH) * this.offset / this.max);
    }
}
