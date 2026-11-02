package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.function.Supplier;

/**
 * An 8x8 glowing status pip whose color follows a {@link Status} resolved on every draw, fixed or from a
 * {@link Supplier}, so live state never needs a tree rebuild; {@code PENDING} pulses.
 */
public final class StatusSquare extends Element<StatusSquare> {

    private static final int SIZE = 8;

    private Supplier<Status> status = () -> Status.OFF;

    public StatusSquare() {}

    public StatusSquare(final Status value) {
        this.status(value);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets a fixed status. */
    public StatusSquare status(final Status value) {
        final Status fixed = value == null ? Status.OFF : value;
        this.status = () -> fixed;
        return this;
    }

    /** Sets a live status source, re-read on every draw. */
    public StatusSquare status(final Supplier<Status> supplier) {
        this.status = supplier == null ? () -> Status.OFF : supplier;
        return this;
    }

    /** Returns the current status. */
    public Status status() {
        final Status value = this.status.get();
        return value == null ? Status.OFF : value;
    }

    /** Returns the pip color for a status: red, amber, green or faint, with {@code PENDING} pulsing its alpha. */
    public static Color tint(final Status status) {
        final Status s = status == null ? Status.OFF : status;
        final Color base = switch (s) {
            case ERROR -> AppTheme.RED;
            case WARN -> AppTheme.AMBER;
            case OK -> AppTheme.GREEN;
            default -> AppTheme.TEXT_FAINT; // PENDING / OFF
        };
        if (s != Status.PENDING) return base;
        // SINE PULSE BETWEEN 42% AND 77% ALPHA WITH A PERIOD OF ABOUT 1.1 S
        final float pulse = 0.42f + 0.35f * (float) ((Math.sin(System.currentTimeMillis() / 180.0) + 1.0) * 0.5);
        return AppTheme.alpha(base, MathUtil.clamp((int) (255 * pulse), 70, 255));
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = SIZE;
        this.contentHeight = SIZE;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final Color c = tint(this.status.get());
        canvas.fill(this.left, this.top, this.measuredWidth, this.measuredHeight, c);
        canvas.glow(this.left, this.top, this.measuredWidth, this.measuredHeight, 0f, c, 0.42f);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** Semantic state of the pip; each value maps to a fixed theme color. */
    public enum Status {
        OK,
        WARN,
        ERROR,
        PENDING,
        OFF
    }
}
