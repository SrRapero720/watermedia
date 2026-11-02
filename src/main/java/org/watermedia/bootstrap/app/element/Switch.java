package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * An on/off toggle whose square knob slides with a short ease-out; a click flips the state at once and reports it
 * through {@code onChange}.
 */
public final class Switch extends Element<Switch> {

    private static final int TRACK_W = 40;
    private static final int TRACK_H = 20;
    private static final int KNOB = 16;
    private static final int INSET = 2;
    private static final int ANIM_MS = 140;

    private boolean on;
    private Color accent = AppTheme.NEON;
    private Consumer<Boolean> onChange;
    // KNOB SLIDE: animStart 0 MEANS IDLE; WHILE RUNNING THE KNOB EASES FROM animFrom TO THE CURRENT STATE
    private long animStart;
    private float animFrom;
    private boolean drawn;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Switch on(final boolean value) {
        // ONLY A REAL TRANSITION ANIMATES; A PER-FRAME REBIND TO THE SAME VALUE (SETTINGS SYNC) NEVER DOES
        if (value != this.on) {
            this.slide();
            this.on = value;
        }
        return this;
    }

    public Switch accent(final Color value) {
        this.accent = value == null ? AppTheme.NEON : value;
        return this;
    }

    public Switch onChange(final Consumer<Boolean> handler) {
        this.onChange = handler;
        return this;
    }

    public boolean on() { return this.on; }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        // THE LOGICAL STATE FLIPS IMMEDIATELY; ONLY THE KNOB DRAW TRAILS BEHIND WITH THE EASE
        this.slide();
        this.on = !this.on;
        this.invalidate();
        if (this.onChange != null) this.onChange.accept(this.on);
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
        this.contentWidth = TRACK_W;
        this.contentHeight = TRACK_H;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        this.drawn = true;
        final int trackX = this.innerLeft();
        final int trackY = this.top + (this.measuredHeight - TRACK_H) / 2;
        canvas.fill(trackX, trackY, TRACK_W, TRACK_H,
                this.on ? AppTheme.alpha(this.accent, 190) : AppTheme.alpha(AppTheme.BG_3, 220));
        canvas.stroke(trackX, trackY, TRACK_W, TRACK_H, AppTheme.STROKE, 1f);
        final int travel = TRACK_W - KNOB - INSET * 2;
        final int knobX = trackX + INSET + Math.round(this.pos(System.currentTimeMillis()) * travel);
        canvas.fill(knobX, trackY + INSET, KNOB, KNOB, AppTheme.TEXT);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void slide() {
        // RESTARTS THE EASE FROM THE CURRENT KNOB POSITION; BEFORE THE FIRST DRAW IT SNAPS, SO A FRESHLY BUILT ROW
        // BINDING ITS INITIAL VALUE NEVER PLAYS A SLIDE THE USER DID NOT TRIGGER
        if (this.ctx == null || !this.drawn) return;
        final long now = System.currentTimeMillis();
        this.animFrom = this.pos(now);
        this.animStart = now;
    }

    private float pos(final long now) {
        // VISUAL KNOB POSITION IN [0..1] (0 = LEFT/OFF, 1 = RIGHT/ON), CUBIC EASE-OUT WHILE ANIMATING
        final float target = this.on ? 1f : 0f;
        if (this.animStart == 0L) return target;
        final float t = Math.min(1f, (now - this.animStart) / (float) ANIM_MS);
        final float inv = 1f - t;
        return this.animFrom + (target - this.animFrom) * (1f - inv * inv * inv);
    }
}
