package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;

import java.util.ArrayList;
import java.util.List;

/**
 * User-space vector path of move/line/quad/cubic/close segments (arcs and basic shapes arrive as cubics), flattened
 * on demand into device-space polylines so the flatness tolerance holds in output pixels at any scale. Segments and
 * flattened points are hard-budgeted and no non-finite coordinate is accepted: hostile {@code d=""} data is bounded.
 */
final class Path {
    // SEGMENT OPCODES
    private static final byte MOVE = 0, LINE = 1, QUAD = 2, CUBIC = 3, CLOSE = 4;

    // CONTROL-POINT OFFSET FOR A CUBIC APPROXIMATION OF A QUARTER ELLIPSE ARC
    private static final double KAPPA = 0.5522847498307936;

    private static final int MAX_FLATTEN_DEPTH = 18; // A DEGENERATE CURVE SUBDIVIDES TO AT MOST 2^18 POINTS

    // SEGMENT BUDGET (COORDINATES FOLLOW: AT MOST 6 PER COMMAND) AND FLATTENED-POINT BUDGET. A STROKE
    // OUTLINE ADDS ~5 COMMANDS PER SOURCE VERTEX PLUS A DISC PER SHARP JOIN, SO IT IS BUDGETED TOO
    private static final int MAX_SEGMENTS = 500_000;
    private static final int MAX_POINTS = 500_000;

    private byte[] cmd = new byte[16];
    private double[] coord = new double[64];
    private int cmdN, coordN;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    void moveTo(final double x, final double y) throws CodecException { this.pushCmd(MOVE); this.pushCoord(x, y); }
    void lineTo(final double x, final double y) throws CodecException { this.pushCmd(LINE); this.pushCoord(x, y); }

    void quadTo(final double cx, final double cy, final double x, final double y) throws CodecException {
        this.pushCmd(QUAD); this.pushCoord(cx, cy); this.pushCoord(x, y);
    }

    void cubicTo(final double c1x, final double c1y, final double c2x, final double c2y, final double x, final double y) throws CodecException {
        this.pushCmd(CUBIC); this.pushCoord(c1x, c1y); this.pushCoord(c2x, c2y); this.pushCoord(x, y);
    }

    void close() throws CodecException { this.pushCmd(CLOSE); }

    boolean isEmpty() { return this.cmdN == 0; }

    void rect(final double x, final double y, final double w, final double h, final double rx, final double ry) throws CodecException {
        if (rx <= 0 || ry <= 0) {
            this.moveTo(x, y);
            this.lineTo(x + w, y);
            this.lineTo(x + w, y + h);
            this.lineTo(x, y + h);
            this.close();
            return;
        }
        final double cx = Math.min(rx, w / 2), cy = Math.min(ry, h / 2);
        final double ox = cx * KAPPA, oy = cy * KAPPA;
        final double x1 = x + cx, x2 = x + w - cx, x3 = x + w;
        final double y1 = y + cy, y2 = y + h - cy, y3 = y + h;
        this.moveTo(x1, y);
        this.lineTo(x2, y);
        this.cubicTo(x2 + ox, y, x3, y1 - oy, x3, y1);
        this.lineTo(x3, y2);
        this.cubicTo(x3, y2 + oy, x2 + ox, y3, x2, y3);
        this.lineTo(x1, y3);
        this.cubicTo(x1 - ox, y3, x, y2 + oy, x, y2);
        this.lineTo(x, y1);
        this.cubicTo(x, y1 - oy, x1 - ox, y, x1, y);
        this.close();
    }

    void ellipse(final double cx, final double cy, final double rx, final double ry) throws CodecException {
        final double ox = rx * KAPPA, oy = ry * KAPPA;
        this.moveTo(cx + rx, cy);
        this.cubicTo(cx + rx, cy + oy, cx + ox, cy + ry, cx, cy + ry);
        this.cubicTo(cx - ox, cy + ry, cx - rx, cy + oy, cx - rx, cy);
        this.cubicTo(cx - rx, cy - oy, cx - ox, cy - ry, cx, cy - ry);
        this.cubicTo(cx + ox, cy - ry, cx + rx, cy - oy, cx + rx, cy);
        this.close();
    }

    // POLYLINE / POLYGON FROM A FLAT [x0,y0,x1,y1,...] ARRAY
    void poly(final double[] pts, final int n, final boolean closed) throws CodecException {
        if (n < 2) return;
        this.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < n; i += 2) this.lineTo(pts[i], pts[i + 1]);
        if (closed) this.close();
    }

    // CONTROL-POINT BOX IN USER SPACE (A SUPERSET FOR CURVES) AS {minX, minY, maxX, maxY}, OR null WHEN EMPTY
    double[] userBounds() {
        if (this.coordN == 0) return null;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i + 1 < this.coordN; i += 2) {
            final double x = this.coord[i], y = this.coord[i + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    // FLATTENS INTO DEVICE-SPACE POLYLINES UNDER m, SUBDIVIDING CURVES UNTIL FLAT WITHIN tol PIXELS
    List<Subpath> flatten(final Affine m, final double tol) throws CodecException {
        final List<Subpath> out = new ArrayList<>();
        final Builder b = new Builder(out, m, tol * tol);
        int ci = 0;
        for (int k = 0; k < this.cmdN; k++) {
            switch (this.cmd[k]) {
                case MOVE -> { b.moveTo(this.coord[ci], this.coord[ci + 1]); ci += 2; }
                case LINE -> { b.lineTo(this.coord[ci], this.coord[ci + 1]); ci += 2; }
                case QUAD -> { b.quadTo(this.coord[ci], this.coord[ci + 1], this.coord[ci + 2], this.coord[ci + 3]); ci += 4; }
                case CUBIC -> { b.cubicTo(this.coord[ci], this.coord[ci + 1], this.coord[ci + 2], this.coord[ci + 3], this.coord[ci + 4], this.coord[ci + 5]); ci += 6; }
                case CLOSE -> b.close();
            }
        }
        b.flush();
        return out;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void pushCmd(final byte c) throws CodecException {
        // ONE BUDGET COVERS BOTH ARRAYS: EVERY COORDINATE PAIR BELONGS TO A COMMAND, AND NO COMMAND
        // CARRIES MORE THAN THREE OF THEM, SO coordN CAN NEVER EXCEED 6 * MAX_SEGMENTS
        if (this.cmdN == MAX_SEGMENTS) throw new CodecException("SVG path exceeds " + MAX_SEGMENTS + " segments");
        if (this.cmdN == this.cmd.length) {
            final byte[] grown = new byte[this.cmd.length * 2];
            System.arraycopy(this.cmd, 0, grown, 0, this.cmdN);
            this.cmd = grown;
        }
        this.cmd[this.cmdN++] = c;
    }

    private void pushCoord(final double x, final double y) throws CodecException {
        // SINGLE CHOKE POINT FOR THE "NO NON-FINITE GEOMETRY" INVARIANT: THE PATH PARSER, THE SHAPE
        // BUILDERS AND THE GENERATED STROKE OUTLINE ALL FUNNEL THROUGH HERE
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new CodecException("Non-finite path coordinate");
        if (this.coordN + 2 > this.coord.length) {
            final double[] grown = new double[this.coord.length * 2];
            System.arraycopy(this.coord, 0, grown, 0, this.coordN);
            this.coord = grown;
        }
        this.coord[this.coordN++] = x;
        this.coord[this.coordN++] = y;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static double distSqToLine(final double px, final double py,
                                       final double ax, final double ay, final double bx, final double by) {
        final double dx = bx - ax, dy = by - ay;
        final double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-12) {
            final double ex = px - ax, ey = py - ay;
            return ex * ex + ey * ey;
        }
        final double cross = (px - ax) * dy - (py - ay) * dx;
        return (cross * cross) / lenSq;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // ONE FLATTENED DEVICE-SPACE SUBPATH: THE [x0,y0,x1,y1,...] POINTS AND WHETHER IT WAS CLOSED
    record Subpath(double[] pts, boolean closed) {}

    // ACCUMULATES DEVICE-SPACE VERTICES, SUBDIVIDING CURVES UNTIL FLAT WITHIN tolSq
    private static final class Builder {
        private final List<Subpath> out;
        private final Affine m;
        private final double tolSq;
        private double[] buf = new double[64];
        private int n, total; // total COUNTS EVERY POINT OF THE FLATTEN, INCLUDING ALREADY FLUSHED SUBPATHS
        private double curX, curY, startX, startY;
        private boolean pendingClose;

        Builder(final List<Subpath> out, final Affine m, final double tolSq) {
            this.out = out; this.m = m; this.tolSq = tolSq;
        }

        void moveTo(final double ux, final double uy) throws CodecException {
            this.flush();
            this.curX = this.m.x(ux, uy);
            this.curY = this.m.y(ux, uy);
            this.startX = this.curX;
            this.startY = this.curY;
            this.add(this.curX, this.curY);
        }

        void lineTo(final double ux, final double uy) throws CodecException {
            this.ensureStarted();
            this.curX = this.m.x(ux, uy);
            this.curY = this.m.y(ux, uy);
            this.add(this.curX, this.curY);
        }

        void quadTo(final double ucx, final double ucy, final double ux, final double uy) throws CodecException {
            this.ensureStarted();
            final double cx = this.m.x(ucx, ucy), cy = this.m.y(ucx, ucy);
            final double ex = this.m.x(ux, uy), ey = this.m.y(ux, uy);
            this.quad(this.curX, this.curY, cx, cy, ex, ey, 0);
            this.curX = ex; this.curY = ey;
        }

        void cubicTo(final double uc1x, final double uc1y, final double uc2x, final double uc2y, final double ux, final double uy) throws CodecException {
            this.ensureStarted();
            final double c1x = this.m.x(uc1x, uc1y), c1y = this.m.y(uc1x, uc1y);
            final double c2x = this.m.x(uc2x, uc2y), c2y = this.m.y(uc2x, uc2y);
            final double ex = this.m.x(ux, uy), ey = this.m.y(ux, uy);
            this.cubic(this.curX, this.curY, c1x, c1y, c2x, c2y, ex, ey, 0);
            this.curX = ex; this.curY = ey;
        }

        void close() {
            if (this.n == 0) return;
            this.pendingClose = true;
            this.flush();
            // AFTER Z THE CURRENT POINT RETURNS TO THE SUBPATH START
            this.curX = this.startX;
            this.curY = this.startY;
        }

        void flush() {
            if (this.n >= 4) {
                final double[] line = new double[this.n];
                System.arraycopy(this.buf, 0, line, 0, this.n);
                this.out.add(new Subpath(line, this.pendingClose));
            }
            this.n = 0;
            this.pendingClose = false;
        }

        // SEED A FRESH SUBPATH FROM THE CURRENT POINT WHEN A DRAW FOLLOWS A close()/START
        private void ensureStarted() throws CodecException {
            if (this.n == 0) this.add(this.curX, this.curY);
        }

        // FLATNESS IS THE SQUARED DISTANCE OF EACH CONTROL POINT FROM THE CHORD. THE TESTS ARE NEGATED SO A NaN
        // DISTANCE COUNTS AS FLAT AND STOPS THE RECURSION; THE POSITIVE FORM WOULD SUBDIVIDE EVERY NaN CURVE TO
        // FULL DEPTH, 2^18 POINTS PER SEGMENT (cairo CVE-2019-6461)
        private void quad(final double x0, final double y0, final double cx, final double cy,
                          final double x1, final double y1, final int depth) throws CodecException {
            if (depth >= MAX_FLATTEN_DEPTH || !(distSqToLine(cx, cy, x0, y0, x1, y1) > this.tolSq)) {
                this.add(x1, y1);
                return;
            }
            final double x01 = (x0 + cx) / 2, y01 = (y0 + cy) / 2;
            final double x12 = (cx + x1) / 2, y12 = (cy + y1) / 2;
            final double mx = (x01 + x12) / 2, my = (y01 + y12) / 2;
            this.quad(x0, y0, x01, y01, mx, my, depth + 1);
            this.quad(mx, my, x12, y12, x1, y1, depth + 1);
        }

        private void cubic(final double x0, final double y0, final double c1x, final double c1y,
                           final double c2x, final double c2y, final double x1, final double y1, final int depth) throws CodecException {
            if (depth >= MAX_FLATTEN_DEPTH || (!(distSqToLine(c1x, c1y, x0, y0, x1, y1) > this.tolSq)
                    && !(distSqToLine(c2x, c2y, x0, y0, x1, y1) > this.tolSq))) {
                this.add(x1, y1);
                return;
            }
            final double x01 = (x0 + c1x) / 2, y01 = (y0 + c1y) / 2;
            final double x12 = (c1x + c2x) / 2, y12 = (c1y + c2y) / 2;
            final double x23 = (c2x + x1) / 2, y23 = (c2y + y1) / 2;
            final double x012 = (x01 + x12) / 2, y012 = (y01 + y12) / 2;
            final double x123 = (x12 + x23) / 2, y123 = (y12 + y23) / 2;
            final double mx = (x012 + x123) / 2, my = (y012 + y123) / 2;
            this.cubic(x0, y0, x01, y01, x012, y012, mx, my, depth + 1);
            this.cubic(mx, my, x123, y123, x23, y23, x1, y1, depth + 1);
        }

        private void add(final double x, final double y) throws CodecException {
            // BUDGET THE WHOLE FLATTEN, NOT THE CURRENT SUBPATH: FLUSHED SUBPATHS STAY RETAINED IN out,
            // AND A STROKE OUTLINE IS ONE SUBPATH PER SEGMENT QUAD PLUS ONE PER JOIN DISC
            if (this.total == MAX_POINTS) throw new CodecException("SVG path exceeds " + MAX_POINTS + " flattened points");
            this.total++;
            if (this.n + 2 > this.buf.length) {
                final double[] grown = new double[this.buf.length * 2];
                System.arraycopy(this.buf, 0, grown, 0, this.n);
                this.buf = grown;
            }
            this.buf[this.n++] = x;
            this.buf[this.n++] = y;
        }
    }
}
