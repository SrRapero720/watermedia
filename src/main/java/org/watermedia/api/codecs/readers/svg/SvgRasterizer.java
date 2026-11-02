package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.util.MathUtil;

import java.util.Arrays;
import java.util.List;

/**
 * AWT-free anti-aliased scanline rasterizer that paints user-space {@link Path} geometry, under the device transform each
 * call brings, into a straight {@code 0xAARRGGBB} {@code int[]} canvas: 4x vertical supersampling, analytic horizontal
 * coverage, non-zero or even-odd winding. A stroke is filled as one non-zero union of a quad per segment plus round discs
 * at caps and sharp joins; cost is budgeted per shape (edges) and per document.
 */
final class SvgRasterizer {
    // VERTICAL SUPERSAMPLES PER PIXEL ROW
    private static final int SS = 4;
    private static final float SS_WEIGHT = 1.0f / SS;
    // DEVICE-SPACE FLATTENING TOLERANCE IN PIXELS, SMALL ENOUGH FOR CRISP CURVES AT ANY SCALE
    private static final double TOL = 0.25;
    // DEVICE-SPACE FLATTENING TOLERANCE FOR THE GENERATED STROKE GEOMETRY
    private static final double STROKE_TOL = 0.2;

    // PER-SHAPE EDGE BUDGET (SIX PARALLEL ARRAYS, ~48 BYTES AN EDGE) AND A DOCUMENT-WIDE CROSSING BUDGET: ONE ACTIVE
    // EDGE ON ONE SUB-SCANLINE IS THE WHOLE COST MODEL. 6 MILLION SITS AN ORDER OF MAGNITUDE ABOVE A DETAILED MAP AT
    // THE 512 px DEFAULT, YET STOPS THOUSANDS OF FULL-HEIGHT EDGES, IN ONE HUGE FILL OR IN THOUSANDS OF SMALL ONES
    private static final int MAX_EDGES = 200_000;
    private static final long MAX_CROSSINGS = 6_000_000L;

    private final int width, height;
    private final int[] canvas;

    private final float[] cov;

    // EDGE TABLE SCRATCH (DEVICE SPACE, HORIZONTAL AND NON-FINITE EDGES DROPPED)
    private double[] eyTop = new double[64], eyBot = new double[64], exTop = new double[64], edxdy = new double[64];
    private int[] edir = new int[64];
    private int edgeN;

    // EDGE INDICES SORTED BY TOP Y, PLUS THE ACTIVE-EDGE WINDOW SWEPT DOWN THE SCANLINES
    private int[] order = new int[64];
    private int[] active = new int[64];
    private long[] sortKeys = new long[64];

    // PER-SCANLINE CROSSINGS: THE PACKED SORT KEYS AND THE DECODED (x, winding) PAIRS
    private long[] ckeys = new long[32];
    private double[] cxs = new double[32];
    private int[] cdir = new int[32];
    private long crossWork; // CROSSINGS EVALUATED SO FAR BY THIS DOCUMENT

    SvgRasterizer(final int width, final int height) {
        this.width = width;
        this.height = height;
        this.canvas = new int[width * height];
        this.cov = new float[width];
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    int width() { return this.width; }
    int height() { return this.height; }
    int[] canvas() { return this.canvas; }

    void fill(final Path geo, final Affine ctm, final boolean evenOdd, final SvgPaint paint, final float opacity) throws CodecException {
        if (paint == null || opacity <= 0 || geo.isEmpty()) return;
        final double[] bb = geo.userBounds();
        if (bb == null) return;
        final SvgPaint.Sampler sampler = paint.sampler(ctm, bb[0], bb[1], bb[2], bb[3]);
        this.fill(geo.flatten(ctm, TOL), evenOdd, sampler, opacity);
    }

    void stroke(final Path geo, final Affine ctm, final double strokeWidthUser, final SvgPaint paint, final float opacity) throws CodecException {
        if (paint == null || opacity <= 0 || strokeWidthUser <= 0 || geo.isEmpty()) return;
        final double[] bb = geo.userBounds();
        if (bb == null) return;
        final SvgPaint.Sampler sampler = paint.sampler(ctm, bb[0], bb[1], bb[2], bb[3]);
        // CLAMP TO THE CANVAS DIAGONAL: EVERY CANVAS PIXEL LIES WITHIN ONE DIAGONAL OF ANY OTHER, SO A
        // WIDER STROKE PAINTS THE SAME PIXELS WHILE ITS ROUND JOIN DISCS WOULD FLATTEN TO MILLIONS OF
        // POINTS (THE DISC RADIUS DRIVES THE SUBDIVISION DEPTH). ONLY VERTICES OFF-CANVAS COULD DIFFER
        final double w = this.width, h = this.height;
        final double halfWidth = Math.min(strokeWidthUser * ctm.scaleFactor() / 2.0, Math.sqrt(w * w + h * h));
        this.stroke(geo.flatten(ctm, TOL), halfWidth, sampler, opacity);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void fill(final List<Path.Subpath> subpaths, final boolean evenOdd, final SvgPaint.Sampler sampler, final float opacity) throws CodecException {
        if (opacity <= 0 || subpaths.isEmpty()) return;
        this.buildEdges(subpaths);
        if (this.edgeN == 0) return;
        this.scan(evenOdd, sampler, opacity);
    }

    private void stroke(final List<Path.Subpath> subpaths, final double halfWidth, final SvgPaint.Sampler sampler, final float opacity) throws CodecException {
        // NEGATED WIDTH TEST SO A NaN HALF-WIDTH IS REJECTED INSTEAD OF BUILDING NaN STROKE GEOMETRY
        if (opacity <= 0 || !(halfWidth > 0) || subpaths.isEmpty()) return;
        // BUILD THE STROKE OUTLINE IN DEVICE SPACE: QUAD PER SEGMENT + ROUND CAP/JOIN DISCS
        final Path g = new Path();
        for (final Path.Subpath sp: subpaths) {
            final double[] p = sp.pts();
            final int n = p.length / 2;
            final boolean closed = sp.closed();
            for (int i = 0; i + 1 < n; i++) segmentQuad(g, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3], halfWidth);
            if (closed && n > 1) segmentQuad(g, p[2 * (n - 1)], p[2 * (n - 1) + 1], p[0], p[1], halfWidth);
            // DISCS ONLY WHERE THEY MATTER: OPEN ENDPOINTS (ROUND CAPS) AND SHARP JOINS. A DISC AT
            // EVERY FLATTENED VERTEX WOULD EXPLODE THE EDGE TABLE ON SMOOTH CURVES FOR NO VISUAL GAIN
            for (int i = 0; i < n; i++) {
                final boolean endpoint = !closed && (i == 0 || i == n - 1);
                if (endpoint || (n > 1 && sharpJoin(p, n, i, closed, halfWidth))) g.ellipse(p[2 * i], p[2 * i + 1], halfWidth, halfWidth);
            }
        }
        // FILL THE UNION WITH NON-ZERO WINDING SO OVERLAPS DO NOT DOUBLE-BLEND
        this.buildEdges(g.flatten(Affine.IDENTITY, STROKE_TOL));
        if (this.edgeN == 0) return;
        this.scan(false, sampler, opacity);
    }

    private void buildEdges(final List<Path.Subpath> subs) throws CodecException {
        this.edgeN = 0;
        for (final Path.Subpath sp: subs) {
            final double[] p = sp.pts();
            final int n = p.length / 2;
            if (n < 2) continue;
            // EVERY SUBPATH IS CLOSED FOR FILLING, SO THE CLOSING EDGE IS INCLUDED
            for (int i = 0; i < n; i++) {
                final int j = (i + 1) % n;
                this.addEdge(p[2 * i], p[2 * i + 1], p[2 * j], p[2 * j + 1]);
            }
        }
    }

    private void addEdge(final double xa, final double ya, final double xb, final double yb) throws CodecException {
        // HORIZONTAL EDGES DO NOT CROSS SCANLINES. NON-FINITE ONES ARE DROPPED FOR A HARDER REASON: THEIR
        // SLOPE FEEDS exTop + (ys - eyTop) * edxdy, WHERE 0 * Infinity IS NaN, AND A NaN CROSSING TURNS
        // INTO AN UNCLAMPED SPAN INDEX. KEEPING THEM OUT OF THE TABLE IS THE INVARIANT scan() RELIES ON
        if (ya == yb || !Double.isFinite(xa) || !Double.isFinite(ya) || !Double.isFinite(xb) || !Double.isFinite(yb)) return;
        final double dxdy = (xb - xa) / (yb - ya);
        if (!Double.isFinite(dxdy)) return;
        if (this.edgeN == MAX_EDGES) throw new CodecException("SVG shape exceeds " + MAX_EDGES + " edges");
        if (this.edgeN == this.edir.length) {
            final int cap = this.edir.length * 2;
            this.eyTop = Arrays.copyOf(this.eyTop, cap);
            this.eyBot = Arrays.copyOf(this.eyBot, cap);
            this.exTop = Arrays.copyOf(this.exTop, cap);
            this.edxdy = Arrays.copyOf(this.edxdy, cap);
            this.edir = Arrays.copyOf(this.edir, cap);
        }
        final int i = this.edgeN++;
        final double yt, yb2, xt;
        final int dir;
        if (ya < yb) { yt = ya; yb2 = yb; xt = xa; dir = 1; }
        else { yt = yb; yb2 = ya; xt = xb; dir = -1; }
        this.eyTop[i] = yt;
        this.eyBot[i] = yb2;
        this.exTop[i] = xt;
        this.edxdy[i] = dxdy;
        this.edir[i] = dir;
    }

    private void scan(final boolean evenOdd, final SvgPaint.Sampler sampler, final float opacity) throws CodecException {
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < this.edgeN; i++) {
            if (this.eyTop[i] < yMin) yMin = this.eyTop[i];
            if (this.eyBot[i] > yMax) yMax = this.eyBot[i];
            final double xa = this.exTop[i];
            final double xb = this.exTop[i] + (this.eyBot[i] - this.eyTop[i]) * this.edxdy[i];
            xMin = Math.min(xMin, Math.min(xa, xb));
            xMax = Math.max(xMax, Math.max(xa, xb));
        }

        final int y0 = Math.max(0, (int) Math.floor(yMin));
        final int y1 = Math.min(this.height, (int) Math.ceil(yMax));
        final int xlo = Math.max(0, (int) Math.floor(xMin));
        final int xhi = Math.min(this.width, (int) Math.ceil(xMax));
        if (y0 >= y1 || xlo >= xhi) return;

        // SORT EDGES BY TOP Y ONCE; A SINGLE ACTIVE-EDGE WINDOW ADVANCES WITH THE MONOTONIC SCANLINE
        // INSTEAD OF RESCANNING THE WHOLE EDGE TABLE PER SUB-SCANLINE
        this.sortEdgesByTop();
        int nextEdge = 0, activeN = 0;

        for (int y = y0; y < y1; y++) {
            Arrays.fill(this.cov, xlo, xhi, 0f);
            for (int s = 0; s < SS; s++) {
                final double ys = y + (s + 0.5) / SS;
                // ADMIT EDGES THAT HAVE STARTED, THEN RETIRE EDGES THAT HAVE ENDED (ys IS INCREASING)
                while (nextEdge < this.edgeN && this.eyTop[this.order[nextEdge]] <= ys) {
                    if (activeN == this.active.length) this.active = Arrays.copyOf(this.active, activeN * 2);
                    this.active[activeN++] = this.order[nextEdge++];
                }
                int keep = 0;
                for (int a = 0; a < activeN; a++) {
                    final int e = this.active[a];
                    if (this.eyBot[e] > ys) this.active[keep++] = e;
                }
                activeN = keep;
                if (activeN < 2) continue;

                // CHARGED BEFORE THE WORK IS DONE, AND ACROSS EVERY SHAPE OF THE DOCUMENT: THIS IS THE
                // ONLY BOUND THAT SURVIVES ANY ARRANGEMENT OF EDGES (ONE HUGE FILL OR THOUSANDS OF THEM)
                this.crossWork += activeN;
                if (this.crossWork > MAX_CROSSINGS) throw new CodecException("SVG rasterization exceeds " + MAX_CROSSINGS + " edge crossings");

                this.crossings(ys, activeN);
                int wind = 0;
                for (int i = 0; i < activeN - 1; i++) {
                    wind += this.cdir[i];
                    final boolean inside = evenOdd ? ((i & 1) == 0) : (wind != 0);
                    if (inside) this.addSpan(this.cxs[i], this.cxs[i + 1], xlo, xhi);
                }
            }
            final int base = y * this.width;
            for (int x = xlo; x < xhi; x++) {
                float c = this.cov[x];
                if (c <= 0) continue;
                if (c > 1) c = 1;
                final int src = sampler.argb(x + 0.5, y + 0.5);
                final float aeff = ((src >>> 24) & 0xFF) / 255f * c * opacity;
                if (aeff <= 0) continue;
                this.blend(base + x, src, aeff);
            }
        }
    }

    // SORTS THE EDGE INDICES BY TOP Y. EACH KEY PACKS eyTop AS A SIGNED-ASCENDING long IN THE HIGH
    // BITS AND THE EDGE INDEX IN THE LOW 24 (edgeN NEVER APPROACHES 2^24), SO Arrays.sort ORDERS THEM.
    private void sortEdgesByTop() {
        if (this.order.length < this.edgeN) {
            this.order = new int[this.edgeN];
            this.sortKeys = new long[this.edgeN];
        }
        final long[] keys = this.sortKeys;
        for (int i = 0; i < this.edgeN; i++) {
            // MAP IEEE bits TO A SIGNED-ORDER-PRESERVING long: FLIP THE LOWER 63 BITS FOR NEGATIVES SO
            // OFF-CANVAS-TOP EDGES (eyTop < 0) STILL SORT BEFORE POSITIVE ONES UNDER Arrays.sort (SIGNED)
            long bits = Double.doubleToLongBits(this.eyTop[i]);
            bits ^= (bits >> 63) & Long.MAX_VALUE;
            keys[i] = (bits & ~0xFFFFFFL) | i;
        }
        Arrays.sort(keys, 0, this.edgeN);
        for (int i = 0; i < this.edgeN; i++) this.order[i] = (int) (keys[i] & 0xFFFFFFL);
    }

    // FILLS cxs/cdir WITH THE X-CROSSINGS OF THE ACTIVE EDGES AT SUB-SCANLINE ys, SORTED ASCENDING. EACH KEY PACKS THE
    // ORDER-PRESERVING X BITS HIGH AND THE WINDING IN BIT 0, SO ONE O(n log n) Arrays.sort ORDERS BOTH (AN INSERTION SORT
    // WOULD BE QUADRATIC PER SUB-SCANLINE); THE DROPPED LOW 24 MANTISSA BITS COST UNDER 1e-5 px
    private void crossings(final double ys, final int activeN) {
        if (this.ckeys.length < activeN) {
            // GEOMETRIC GROWTH: THE ACTIVE SET TYPICALLY GAINS ONE EDGE AT A TIME AS THE SWEEP DESCENDS
            final int cap = Math.max(activeN, this.ckeys.length * 2);
            this.ckeys = new long[cap];
            this.cxs = new double[cap];
            this.cdir = new int[cap];
        }
        final long[] keys = this.ckeys;
        for (int a = 0; a < activeN; a++) {
            final int i = this.active[a];
            final double x = this.exTop[i] + (ys - this.eyTop[i]) * this.edxdy[i];
            long bits = Double.doubleToLongBits(x);
            bits ^= (bits >> 63) & Long.MAX_VALUE; // SIGNED-ORDER-PRESERVING, AS IN sortEdgesByTop
            keys[a] = (bits & ~0xFFFFFFL) | (this.edir[i] > 0 ? 1 : 0);
        }
        Arrays.sort(keys, 0, activeN);
        for (int a = 0; a < activeN; a++) {
            final long key = keys[a];
            long bits = key & ~0xFFFFFFL;
            bits ^= (bits >> 63) & Long.MAX_VALUE; // THE MAPPING IS ITS OWN INVERSE
            this.cxs[a] = Double.longBitsToDouble(bits);
            this.cdir[a] = (key & 1) == 0 ? -1 : 1;
        }
    }

    private void addSpan(double xa, double xb, final int xlo, final int xhi) {
        // EVERY TEST IS NEGATED SO A NaN BOUND FAILS CLOSED: THE POSITIVE FORMS (xa < xlo, xb > xhi, xa >= xb) ARE FALSE
        // FOR NaN AND WOULD LEAVE xa UNCLAMPED, MAKING (int) Math.floor(xa) AN ARBITRARY INDEX INTO cov
        if (!(xb > xa)) return;
        if (!(xa > xlo)) xa = xlo;
        if (!(xb < xhi)) xb = xhi;
        if (!(xb > xa)) return;
        // THE CLAMPS ABOVE ALREADY PIN BOTH ENDS INSIDE [xlo, xhi]; RE-CLAMPING THE INDICES COSTS TWO
        // COMPARES AND MAKES THE cov WRITES SAFE BY CONSTRUCTION RATHER THAN BY PROOF
        final int ia = Math.min(xhi - 1, Math.max(xlo, (int) Math.floor(xa)));
        final int ib = Math.min(xhi, Math.max(xlo, (int) Math.floor(xb)));
        if (ia == ib) {
            this.cov[ia] += SS_WEIGHT * (float) (xb - xa);
            return;
        }
        this.cov[ia] += SS_WEIGHT * (float) ((ia + 1) - xa);
        for (int i = ia + 1; i < ib; i++) this.cov[i] += SS_WEIGHT;
        if (ib < xhi) this.cov[ib] += SS_WEIGHT * (float) (xb - ib);
    }

    // STRAIGHT (NON-PREMULTIPLIED) SOURCE-OVER ONTO A POSSIBLY-TRANSPARENT CANVAS
    private void blend(final int idx, final int src, final float aeff) {
        final int sr = (src >>> 16) & 0xFF, sg = (src >>> 8) & 0xFF, sb = src & 0xFF;
        final int dst = this.canvas[idx];
        final int da = (dst >>> 24) & 0xFF;
        if (da == 0) {
            this.canvas[idx] = (MathUtil.clip255(Math.round(aeff * 255f)) << 24) | (sr << 16) | (sg << 8) | sb;
            return;
        }
        final int dr = (dst >>> 16) & 0xFF, dg = (dst >>> 8) & 0xFF, db = dst & 0xFF;
        final float dAf = da / 255f;
        final float outA = aeff + dAf * (1 - aeff);
        if (outA <= 0) { this.canvas[idx] = 0; return; }
        final float inv = dAf * (1 - aeff);
        final int or = Math.round((sr * aeff + dr * inv) / outA);
        final int og = Math.round((sg * aeff + dg * inv) / outA);
        final int ob = Math.round((sb * aeff + db * inv) / outA);
        this.canvas[idx] = (MathUtil.clip255(Math.round(outA * 255f)) << 24) | (MathUtil.clip255(or) << 16) | (MathUtil.clip255(og) << 8) | MathUtil.clip255(ob);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // TRUE WHEN THE TURN AT VERTEX i BENDS ENOUGH THAT THE TWO SEGMENT QUADS LEAVE A GAP A ROUND JOIN
    // MUST FILL. THE GAP ≈ hw*(1 - cos(turn/2)); EMIT ONLY WHEN IT EXCEEDS ~1/4 px
    private static boolean sharpJoin(final double[] p, final int n, final int i, final boolean closed, final double hw) {
        final int prev = i > 0 ? i - 1 : (closed ? n - 1 : -1);
        final int next = i < n - 1 ? i + 1 : (closed ? 0 : -1);
        if (prev < 0 || next < 0) return false; // OPEN ENDPOINT, HANDLED AS A CAP
        final double inx = p[2 * i] - p[2 * prev], iny = p[2 * i + 1] - p[2 * prev + 1];
        final double outx = p[2 * next] - p[2 * i], outy = p[2 * next + 1] - p[2 * i + 1];
        final double li = Math.sqrt(inx * inx + iny * iny), lo = Math.sqrt(outx * outx + outy * outy);
        if (li < 1e-9 || lo < 1e-9) return true; // DUPLICATE VERTEX: LET A DISC COVER IT
        final double cosTurn = (inx * outx + iny * outy) / (li * lo);
        final double cosHalf = Math.sqrt(Math.max(0, (1 + cosTurn) / 2));
        return hw * (1 - cosHalf) > 0.25;
    }

    private static void segmentQuad(final Path g, final double x0, final double y0, final double x1, final double y1, final double hw) throws CodecException {
        final double dx = x1 - x0, dy = y1 - y0;
        final double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-9) return; // ZERO-LENGTH SEGMENT: THE VERTEX DISC COVERS IT
        final double nx = -dy / len * hw, ny = dx / len * hw;
        // VERTEX ORDER MATCHES THE ELLIPSE (DISC) WINDING SO THE NON-ZERO UNION OF QUADS + CAP/JOIN
        // DISCS NEVER CANCELS IN THEIR OVERLAP (REVERSED ORDER WOULD NOTCH EVERY CAP AND JOINT)
        g.moveTo(x0 + nx, y0 + ny);
        g.lineTo(x0 - nx, y0 - ny);
        g.lineTo(x1 - nx, y1 - ny);
        g.lineTo(x1 + nx, y1 + ny);
        g.close();
    }
}
