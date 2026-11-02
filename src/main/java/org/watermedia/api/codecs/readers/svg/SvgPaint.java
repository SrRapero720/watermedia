package org.watermedia.api.codecs.readers.svg;

import java.util.Arrays;

/**
 * Resolved fill or stroke paint, a solid colour or a linear gradient; {@code fill="none"} is a {@code null} paint.
 * {@link #sampler} turns it into a per-pixel {@link Sampler} from the device transform and the shape's user-space
 * bounds (which {@code objectBoundingBox} gradients need).
 */
sealed interface SvgPaint permits SvgPaint.Solid, SvgPaint.Linear {
    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    Sampler sampler(Affine ctm, double minX, double minY, double maxX, double maxY);

    static SvgPaint solid(final int argb) {
        return new Solid(argb);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    record Solid(int argb) implements SvgPaint {
        @Override
        public Sampler sampler(final Affine ctm, final double minX, final double minY, final double maxX, final double maxY) {
            final int c = this.argb;
            return (px, py) -> c;
        }
    }

    // offsets ARE ASCENDING IN [0,1] AND colors STRAIGHT ARGB; gradientTransform IS NEVER NULL
    // (SvgParser.parseTransform DEFAULTS TO IDENTITY)
    record Linear(double x1, double y1, double x2, double y2, boolean userSpace,
                  Affine gradientTransform, float[] offsets, int[] colors) implements SvgPaint {
        @Override
        public Sampler sampler(final Affine ctm, final double minX, final double minY, final double maxX, final double maxY) {
            if (this.offsets.length == 0) return (px, py) -> 0;
            if (this.offsets.length == 1) { final int c = this.colors[0]; return (px, py) -> c; }

            // BUILD gradient-space → user-space, THEN COMPOSE WITH THE DEVICE TRANSFORM AND INVERT
            final Affine gradToUser;
            if (this.userSpace) {
                gradToUser = this.gradientTransform;
            } else {
                final double w = maxX - minX, h = maxY - minY;
                if (w == 0 || h == 0) { final int c = this.colors[0]; return (px, py) -> c; }
                final Affine bbox = Affine.translate(minX, minY).concat(Affine.scale(w, h));
                gradToUser = bbox.concat(this.gradientTransform);
            }

            final Affine inv = ctm.concat(gradToUser).invert();
            if (inv == null) { final int c = this.colors[0]; return (px, py) -> c; }

            final double dx = this.x2 - this.x1, dy = this.y2 - this.y1;
            final double lenSq = dx * dx + dy * dy;
            if (lenSq == 0) { final int c = this.colors[this.colors.length - 1]; return (px, py) -> c; } // DEGENERATE → LAST STOP

            final double gx1 = this.x1, gy1 = this.y1;
            final float[] off = this.offsets;
            final int[] col = this.colors;
            return (px, py) -> {
                final double gx = inv.x(px, py), gy = inv.y(px, py);
                final double t = ((gx - gx1) * dx + (gy - gy1) * dy) / lenSq;
                // NEGATED TEST SO A NaN t (OVERFLOWING GRADIENT SPACE) LANDS ON THE FIRST STOP INSTEAD
                // OF REACHING THE BINARY SEARCH, WHERE NaN SORTS PAST THE LAST OFFSET
                if (!(t > 0)) return col[0];
                if (t >= 1) return col[col.length - 1];
                return lookup(off, col, (float) t);
            };
        }

        // LINEAR INTERPOLATION BETWEEN THE TWO BRACKETING STOPS, STRAIGHT (NON-PREMULTIPLIED) sRGB
        private static int lookup(final float[] off, final int[] col, final float t) {
            // PAD BEYOND THE STOP RANGE (STOPS NEED NOT SPAN [0,1])
            if (t <= off[0]) return col[0];
            if (t >= off[off.length - 1]) return col[col.length - 1];
            // BINARY SEARCH, NOT A LINEAR WALK: THIS RUNS ONCE PER COVERED PIXEL, SO WITH MANY STOPS A
            // SCAN COSTS O(stops) PER PIXEL. THE TWO TESTS ABOVE PIN THE RESULT INSIDE [1, len-1]
            int hi = Arrays.binarySearch(off, t);
            if (hi < 0) hi = -hi - 1; else if (hi == 0) hi = 1;
            final int lo = hi - 1;
            final float span = off[hi] - off[lo];
            final float f = span <= 0 ? 0 : (t - off[lo]) / span;
            final int c0 = col[lo], c1 = col[hi];
            final int a = lerp((c0 >>> 24) & 0xFF, (c1 >>> 24) & 0xFF, f);
            final int r = lerp((c0 >>> 16) & 0xFF, (c1 >>> 16) & 0xFF, f);
            final int g = lerp((c0 >>> 8) & 0xFF, (c1 >>> 8) & 0xFF, f);
            final int b = lerp(c0 & 0xFF, c1 & 0xFF, f);
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        private static int lerp(final int a, final int b, final float f) {
            return a + Math.round((b - a) * f);
        }
    }

    // PER-PIXEL COLOUR SOURCE OF THE RASTERIZER: A STRAIGHT 0xAARRGGBB VALUE FOR A DEVICE-SPACE PIXEL CENTRE
    @FunctionalInterface
    interface Sampler {
        int argb(double px, double py);
    }
}
