package org.watermedia.api.codecs.readers.svg;

/**
 * Immutable 2x3 affine transform of the SVG rasterizer, independent of AWT: maps {@code (x, y)} to
 * {@code (a*x + c*y + e, b*x + d*y + f)}, the column-vector convention of SVG {@code matrix(a b c d e f)}.
 */
record Affine(double a, double b, double c, double d, double e, double f) {
    static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

    static Affine translate(final double tx, final double ty) {
        return new Affine(1, 0, 0, 1, tx, ty);
    }

    static Affine scale(final double sx, final double sy) {
        return new Affine(sx, 0, 0, sy, 0, 0);
    }

    static Affine rotate(final double degrees) {
        final double r = Math.toRadians(degrees);
        final double cos = Math.cos(r), sin = Math.sin(r);
        return new Affine(cos, sin, -sin, cos, 0, 0);
    }

    static Affine rotate(final double degrees, final double cx, final double cy) {
        // TRANSLATE TO PIVOT, ROTATE, TRANSLATE BACK
        return translate(cx, cy).concat(rotate(degrees)).concat(translate(-cx, -cy));
    }

    static Affine skewX(final double degrees) {
        return new Affine(1, 0, Math.tan(Math.toRadians(degrees)), 1, 0, 0);
    }

    static Affine skewY(final double degrees) {
        return new Affine(1, Math.tan(Math.toRadians(degrees)), 0, 1, 0, 0);
    }

    // RETURNS this * other: THE RESULT APPLIES `other` FIRST, THEN `this` (SVG LEFT-TO-RIGHT NESTING)
    Affine concat(final Affine o) {
        return new Affine(
                this.a * o.a + this.c * o.b,
                this.b * o.a + this.d * o.b,
                this.a * o.c + this.c * o.d,
                this.b * o.c + this.d * o.d,
                this.a * o.e + this.c * o.f + this.e,
                this.b * o.e + this.d * o.f + this.f);
    }

    double x(final double px, final double py) { return this.a * px + this.c * py + this.e; }
    double y(final double px, final double py) { return this.b * px + this.d * py + this.f; }

    double det() { return this.a * this.d - this.b * this.c; }

    // UNIFORM DEVICE SCALE ESTIMATE (SQUARE ROOT OF |det|) THAT MAPS A USER-SPACE STROKE WIDTH TO PIXELS
    double scaleFactor() {
        final double det = Math.abs(this.det());
        return det > 0 ? Math.sqrt(det) : 1.0;
    }

    // RETURNS THE INVERSE, OR null WHEN THE MATRIX IS SINGULAR (DEGENERATE TRANSFORM)
    Affine invert() {
        final double det = this.det();
        if (det == 0 || !Double.isFinite(det)) return null;
        final double inv = 1.0 / det;
        return new Affine(
                this.d * inv,
                -this.b * inv,
                -this.c * inv,
                this.a * inv,
                (this.c * this.f - this.d * this.e) * inv,
                (this.b * this.e - this.a * this.f) * inv);
    }
}
