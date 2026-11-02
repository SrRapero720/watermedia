package org.watermedia.api.util;

/**
 * Easing curves interpolating between a start and an end value over a normalized time.
 * <p>
 * Every curve expects the time {@code t} in [0, 1] and never clamps it: outside that range the
 * circle curves return NaN once {@code t} leaves their domain, while the elastic and bounce
 * curves extrapolate into meaningless values. Clamp {@code t} first when it can overshoot.
 * <p>
 * This API is safe to use even if watermedia isn't successfully loaded.
 */
public enum MathEases {
    EASE_IN((start, end, t) -> start + (end - start) * t * t),
    EASE_OUT((start, end, t) -> start + (end - start) * (1 - Math.pow(1 - t, 2))),
    // THE COMPOSITE CURVES HAND OVER AT THE MIDPOINT OF THE RANGE, SO BOTH HALVES STAY CONTINUOUS FOR ANY START
    EASE_IN_OUT((start, end, t) -> t < 0.5
            ? EASE_IN.apply(start, (start + end) / 2, t * 2)
            : EASE_OUT.apply((start + end) / 2, end, (t - 0.5) * 2)),
    EASE_OUT_IN((start, end, t) -> t < 0.5
            ? EASE_OUT.apply(start, (start + end) / 2, t * 2)
            : EASE_IN.apply((start + end) / 2, end, (t - 0.5) * 2)),
    EASE_IN_SINE((start, end, t) -> (end - start) * (1 - cos((float) (t * Math.PI / 2))) + start),
    EASE_OUT_SINE((start, end, t) -> (end - start) * sin((float) (t * Math.PI / 2)) + start),
    EASE_IN_OUT_SINE((start, end, t) -> (end - start) * (-(cos((float) (Math.PI * t)) - 1) / 2) + start),
    EASE_IN_CUBIC((start, end, t) -> (end - start) * (t * t * t) + start),
    EASE_OUT_CUBIC((start, end, t) -> (end - start) * (1 - Math.pow(1 - t, 3)) + start),
    EASE_IN_OUT_CUBIC((start, end, t) -> t < 0.5
            ? (end - start) * (4 * t * t * t) + start
            : (end - start) * (1 - Math.pow(-2 * t + 2, 3) / 2) + start),
    // EASE_IN/EASE_OUT ALREADY COVER QUADRATIC IN/OUT; ONLY THE START↔END IN-OUT SHAPE IS DISTINCT
    EASE_IN_OUT_QUAD((start, end, t) -> t < 0.5
            ? start + (end - start) * (2 * t * t)
            : start + (end - start) * (1 - Math.pow(-2 * t + 2, 2) / 2)),
    EASE_IN_ELASTIC((start, end, t) -> {
        if (t == 0) return start;
        if (t == 1) return end;
        return start - Math.pow(2, 10 * t - 10) * sin((float) ((t * 10 - 10.75) * (2 * Math.PI / 3))) * (end - start);
    }),
    EASE_OUT_ELASTIC((start, end, t) -> {
        if (t == 0) return start;
        if (t == 1) return end;
        return (end - start) * (Math.pow(2, -10 * t) * sin((float) ((t * 10 - 0.75) * (2 * Math.PI / 3))) + 1) + start;
    }),
    EASE_IN_OUT_ELASTIC((start, end, t) -> {
        if (t == 0) return start;
        if (t == 1) return end;
        // THE OSCILLATION IS A FRACTION OF THE RANGE, LIKE THE ONE-SIDED ELASTIC CURVES
        final double wave = Math.pow(2, t < 0.5 ? 20 * t - 10 : 10 - 20 * t) * sin((float) ((20 * t - 11.125) * (2 * Math.PI / 4.5))) / 2;
        return start + (end - start) * (t < 0.5 ? -wave : wave + 1);
    }),
    EASE_IN_QUINT((start, end, t) -> (end - start) * (t * t * t * t * t) + start),
    EASE_OUT_QUINT((start, end, t) -> (end - start) * (1 - Math.pow(1 - t, 5)) + start),
    EASE_IN_OUT_QUINT((start, end, t) -> t < 0.5
            ? (end - start) * (16 * Math.pow(t, 5)) + start
            : (end - start) * (1 - Math.pow(-2 * t + 2, 5) / 2) + start),
    EASE_IN_CIRCLE((start, end, t) -> start + (end - start) * (1 - Math.sqrt(1 - t * t))),
    EASE_OUT_CIRCLE((start, end, t) -> (end - start) * Math.sqrt(1 - Math.pow(t - 1, 2)) + start),
    EASE_IN_OUT_CIRCLE((start, end, t) -> t < 0.5
            ? start + (end - start) * (1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2
            : start + (end - start) * (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2),
    EASE_IN_EXPO((start, end, t) -> t == 0 ? start : (end - start) * (Math.pow(2, 10 * t - 10)) + start),
    EASE_OUT_EXPO((start, end, t) -> t == 1 ? end : start + (end - start) * (1 - Math.pow(2, -10 * t))),
    EASE_IN_OUT_EXPO((start, end, t) -> {
        if (t == 0) return start;
        if (t == 1) return end;
        return t < 0.5
                ? start + (end - start) * (Math.pow(2, 20 * t - 10) / 2)
                : start + (end - start) * ((2 - Math.pow(2, -20 * t + 10)) / 2);
    }),
    // BACK CURVES OVERSHOOT BY PENNER'S 1.70158, SCALED UP BY 1.525 FOR THE IN-OUT SHAPE
    EASE_IN_BACK((start, end, t) -> (end - start) * (2.70158 * t * t * t - 1.70158 * t * t) + start),
    EASE_OUT_BACK((start, end, t) -> start + (end - start) * (1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2))),
    EASE_IN_OUT_BACK((start, end, t) -> {
        final double c2 = 1.70158 * 1.525;
        return t < 0.5
                ? start + (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2 * (end - start)
                : start + (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (2 * t - 2) + c2) + 2) / 2 * (end - start);
    }),
    EASE_IN_BOUNCE((start, end, t) -> start + (end - start) * (1 - bounce(1 - t))),
    EASE_OUT_BOUNCE((start, end, t) -> start + (end - start) * bounce(t)),
    EASE_IN_OUT_BOUNCE((start, end, t) -> t < 0.5
            ? start + (end - start) * (1 - bounce(1 - 2 * t)) / 2
            : start + (end - start) * (1 + bounce(2 * t - 1)) / 2);

    // QUARTER-WAVE SINE TABLE SHARED BY THE TRIGONOMETRIC CURVES; 16 BITS OF ANGLE RESOLUTION
    private static final int SIN_SIZE = 65_536;
    private static final float[] SIN = new float[SIN_SIZE];

    static {
        for (int i = 0; i < SIN_SIZE; i++) SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / SIN_SIZE);
    }

    private final Easing easing;

    MathEases(final Easing easing) {
        this.easing = easing;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Interpolates between start and end at an unclamped time {@code value} (0.0-1.0); elastic and back curves overshoot. */
    public double apply(final double start, final double end, final double value) {
        return this.easing.apply(start, end, value);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static float sin(final float value) {
        return SIN[(int) (value * 10_430.378F) & 0xFFFF];
    }

    private static float cos(final float value) {
        return SIN[(int) (value * 10_430.378F + 16_384.0F) & 0xFFFF];
    }

    // PENNER'S OUT-BOUNCE OVER THE UNIT RANGE, THE BUILDING BLOCK OF THE THREE BOUNCE CURVES
    private static double bounce(final double t) {
        final double n1 = 7.5625;
        final double d1 = 2.75;
        if (t < 1 / d1) return n1 * t * t;
        if (t < 2 / d1) {
            final double u = t - 1.5 / d1;
            return n1 * u * u + 0.75;
        }
        if (t < 2.5 / d1) {
            final double u = t - 2.25 / d1;
            return n1 * u * u + 0.9375;
        }
        final double u = t - 2.625 / d1;
        return n1 * u * u + 0.984375;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** A single easing curve mapping {@code (start, end, t)} to an interpolated value. */
    @FunctionalInterface
    public interface Easing {
        double apply(double start, double end, double t);
    }
}
