package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.util.MathUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A parsed SVG document ready to rasterize: resolves the intrinsic size and {@code viewBox}, builds every gradient
 * up front (so {@code url(#id)} resolves in any document order) and walks the element tree per render, deriving
 * {@link RenderState} down the hierarchy into a {@link SvgRasterizer}; non-rendered containers are skipped.
 */
final class SvgDocument {
    // SVG FALLBACK INTRINSIC SIZE WHEN NEITHER width/height NOR viewBox GIVE ONE
    private static final double DEFAULT_W = 300, DEFAULT_H = 150;
    // GUARD AGAINST ADVERSARIAL DEEP NESTING: REAL DOCUMENTS NEST A FEW LEVELS, NEVER HUNDREDS
    private static final int MAX_DEPTH = 512;
    // THE href TEMPLATE CHAIN IS WALKED ONCE PER GRADIENT AND AGAIN PER ATTRIBUTE LOOKUP, SO AN
    // UNBOUNDED LENGTH IS QUADRATIC IN THE GRADIENT COUNT. REAL DOCUMENTS USE A SINGLE HOP
    private static final int MAX_CHAIN = 16;
    // STOPS ARE SCANNED PER COVERED PIXEL AND <stop> NEEDS NO id, SO IT AMPLIFIES PERFECTLY THROUGH
    // ENTITY EXPANSION. EVERY REAL GRADIENT USES A HANDFUL
    private static final int MAX_STOPS = 256;
    // <switch> CHILDREN THAT NEVER TAKE PART IN ITS SELECTION
    private static final Set<String> SWITCH_IGNORED = Set.of("title", "desc", "metadata", "defs", "style", "script",
            "lineargradient", "radialgradient", "pattern", "clippath", "mask", "symbol", "marker", "filter");

    private final SvgNode root;
    private final Map<String, SvgPaint> gradients;

    private final boolean hasViewBox;
    private final double vbX, vbY, vbW, vbH;
    private final double intrinsicW, intrinsicH;
    private final double refW, refH, refD; // PERCENTAGE REFERENCE LENGTHS: VIEWPORT WIDTH/HEIGHT/DIAGONAL
    private final boolean aspectNone; // preserveAspectRatio="none"

    SvgDocument(final SvgNode root) throws CodecException {
        this.root = root;

        final double[] vb = SvgParser.numbers(root.attr("viewBox"));
        if (vb.length == 4 && vb[2] > 0 && vb[3] > 0) {
            this.hasViewBox = true;
            this.vbX = vb[0]; this.vbY = vb[1]; this.vbW = vb[2]; this.vbH = vb[3];
        } else {
            this.hasViewBox = false;
            this.vbX = this.vbY = 0; this.vbW = this.vbH = 0;
        }

        final String w = root.attr("width"), h = root.attr("height");
        final boolean wAbs = w != null && !SvgParser.isPercent(w);
        final boolean hAbs = h != null && !SvgParser.isPercent(h);
        if (wAbs && hAbs) {
            this.intrinsicW = SvgParser.length(w, DEFAULT_W);
            this.intrinsicH = SvgParser.length(h, DEFAULT_H);
        } else if (this.hasViewBox) {
            this.intrinsicW = this.vbW;
            this.intrinsicH = this.vbH;
        } else {
            this.intrinsicW = wAbs ? SvgParser.length(w, DEFAULT_W) : DEFAULT_W;
            this.intrinsicH = hAbs ? SvgParser.length(h, DEFAULT_H) : DEFAULT_H;
        }

        // PERCENTAGE REFERENCES PER SVG SPEC: HORIZONTAL LENGTHS RESOLVE AGAINST THE VIEWPORT WIDTH,
        // VERTICAL AGAINST THE HEIGHT, OTHERS (r, stroke-width) AGAINST sqrt(w^2+h^2)/sqrt(2)
        this.refW = this.hasViewBox ? this.vbW : this.intrinsicW;
        this.refH = this.hasViewBox ? this.vbH : this.intrinsicH;
        this.refD = Math.sqrt((this.refW * this.refW + this.refH * this.refH) / 2);

        final String par = root.attr("preserveAspectRatio");
        this.aspectNone = par != null && par.trim().toLowerCase(Locale.ROOT).startsWith("none");

        this.gradients = new HashMap<>();
        this.collectGradients(root);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    double intrinsicWidth() { return this.intrinsicW; }
    double intrinsicHeight() { return this.intrinsicH; }

    void render(final SvgRasterizer out) throws CodecException {
        // display:none ON THE ROOT HIDES THE WHOLE DOCUMENT
        if (hidden(this.root)) return;
        final Affine view = this.viewTransform(out.width(), out.height());
        // THE ROOT <svg> ITSELF CAN CARRY PRESENTATION ATTRS (e.g. fill="#000000") THAT CASCADE DOWN
        final RenderState base = RenderState.root(view).derive(this.root, this.gradients, this.refD);
        for (final SvgNode child: this.root.children()) this.renderNode(child, base, out, 1);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private Affine viewTransform(final int outW, final int outH) {
        if (this.hasViewBox) {
            final double sx = outW / this.vbW, sy = outH / this.vbH;
            if (this.aspectNone) return new Affine(sx, 0, 0, sy, -this.vbX * sx, -this.vbY * sy);
            final double s = Math.min(sx, sy); // xMidYMid meet
            final double tx = (outW - this.vbW * s) / 2 - this.vbX * s;
            final double ty = (outH - this.vbH * s) / 2 - this.vbY * s;
            return new Affine(s, 0, 0, s, tx, ty);
        }
        return Affine.scale(outW / this.intrinsicW, outH / this.intrinsicH);
    }

    private void renderNode(final SvgNode node, final RenderState state, final SvgRasterizer out, final int depth) throws CodecException {
        if (depth > MAX_DEPTH) throw new CodecException("SVG nesting exceeds " + MAX_DEPTH + " levels");
        // display:none REMOVES THE ELEMENT AND ITS ENTIRE SUBTREE FROM RENDERING (NOT INHERITED, SO CHECKED PER NODE)
        if (hidden(node)) return;
        switch (node.tag()) {
            case "defs", "lineargradient", "radialgradient", "stop", "filter", "clippath",
                 "mask", "pattern", "symbol", "marker", "title", "desc", "metadata", "style", "script", "use" -> {
                // NON-RENDERED OR UNSUPPORTED: SKIP THE SUBTREE
            }
            case "g", "a", "svg" -> {
                final RenderState child = state.derive(node, this.gradients, this.refD);
                for (final SvgNode c: node.children()) this.renderNode(c, child, out, depth + 1);
            }
            case "switch" -> {
                // ONLY THE FIRST CHILD WHOSE CONDITIONS HOLD IS RENDERED. NO EXTENSION IS SUPPORTED, SO ANY
                // requiredExtensions FAILS; systemLanguage MUST LIST THE DEFAULT LOCALE'S LANGUAGE; DESCRIPTIVE AND
                // DEFINITION ELEMENTS ARE NOT CANDIDATES AT ALL
                final RenderState child = state.derive(node, this.gradients, this.refD);
                final String language = Locale.getDefault().getLanguage();
                for (final SvgNode c: node.children()) {
                    if (SWITCH_IGNORED.contains(c.tag()) || c.attr("requiredExtensions") != null) continue;
                    final String languages = c.attr("systemLanguage");
                    if (languages != null) {
                        boolean spoken = false;
                        for (final String tag: languages.split(",")) {
                            final String primary = tag.trim();
                            final int dash = primary.indexOf('-');
                            spoken |= (dash < 0 ? primary : primary.substring(0, dash)).equalsIgnoreCase(language);
                        }
                        if (!spoken) continue;
                    }
                    this.renderNode(c, child, out, depth + 1);
                    break;
                }
            }
            default -> {
                final RenderState s = state.derive(node, this.gradients, this.refD);
                // visibility:hidden LAYS OUT THE SHAPE BUT PAINTS NOTHING (LEAVES HAVE NO CHILDREN TO RE-SHOW)
                if (!s.visible()) return;
                final Path geo = this.geometry(node);
                if (geo == null || geo.isEmpty()) return;
                if (s.fill() != null) out.fill(geo, s.ctm(), s.evenOdd(), s.fill(), s.fillOpacity() * s.opacity());
                if (s.stroke() != null && s.strokeWidth() > 0) out.stroke(geo, s.ctm(), s.strokeWidth(), s.stroke(), s.strokeOpacity() * s.opacity());
            }
        }
    }

    private Path geometry(final SvgNode node) throws CodecException {
        final Path p = new Path();
        switch (node.tag()) {
            case "rect" -> {
                final double x = SvgParser.length(node.attr("x"), 0, this.refW), y = SvgParser.length(node.attr("y"), 0, this.refH);
                final double w = SvgParser.length(node.attr("width"), 0, this.refW), h = SvgParser.length(node.attr("height"), 0, this.refH);
                if (w <= 0 || h <= 0) return null;
                final String rxs = node.attr("rx"), rys = node.attr("ry");
                double rx = rxs != null ? SvgParser.length(rxs, 0, this.refW) : Double.NaN;
                double ry = rys != null ? SvgParser.length(rys, 0, this.refH) : Double.NaN;
                if (Double.isNaN(rx)) rx = Double.isNaN(ry) ? 0 : ry;
                if (Double.isNaN(ry)) ry = rx;
                p.rect(x, y, w, h, rx, ry);
            }
            case "circle" -> {
                final double cx = SvgParser.length(node.attr("cx"), 0, this.refW), cy = SvgParser.length(node.attr("cy"), 0, this.refH);
                final double r = SvgParser.length(node.attr("r"), 0, this.refD);
                if (r <= 0) return null;
                p.ellipse(cx, cy, r, r);
            }
            case "ellipse" -> {
                final double cx = SvgParser.length(node.attr("cx"), 0, this.refW), cy = SvgParser.length(node.attr("cy"), 0, this.refH);
                final double rx = SvgParser.length(node.attr("rx"), 0, this.refW), ry = SvgParser.length(node.attr("ry"), 0, this.refH);
                if (rx <= 0 || ry <= 0) return null;
                p.ellipse(cx, cy, rx, ry);
            }
            case "line" -> {
                final double x1 = SvgParser.length(node.attr("x1"), 0, this.refW), y1 = SvgParser.length(node.attr("y1"), 0, this.refH);
                final double x2 = SvgParser.length(node.attr("x2"), 0, this.refW), y2 = SvgParser.length(node.attr("y2"), 0, this.refH);
                p.moveTo(x1, y1);
                p.lineTo(x2, y2);
            }
            case "polygon" -> {
                final double[] pts = SvgParser.numbers(node.attr("points"));
                p.poly(pts, pts.length & ~1, true);
            }
            case "polyline" -> {
                final double[] pts = SvgParser.numbers(node.attr("points"));
                p.poly(pts, pts.length & ~1, false);
            }
            case "path" -> PathParser.parse(node.attr("d"), p);
            default -> { return null; }
        }
        return p;
    }

    private void collectGradients(final SvgNode node) throws CodecException {
        // FIRST INDEX ALL GRADIENT NODES BY id (FOR href TEMPLATE RESOLUTION), THEN BUILD PAINTS
        final Map<String, SvgNode> nodes = new HashMap<>();
        indexGradients(node, nodes, 1);
        for (final Map.Entry<String, SvgNode> e: nodes.entrySet()) {
            final String tag = e.getValue().tag();
            if (tag.equals("lineargradient")) {
                this.gradients.put(e.getKey(), this.buildLinear(e.getValue(), nodes));
            } else if (tag.equals("radialgradient")) {
                // RADIAL RENDERING IS OUT OF SCOPE: APPROXIMATE WITH A SOLID (AVERAGE OF STOPS) SO A
                // SHAPE PAINTED WITH url(#radial) STILL SHOWS A PLAUSIBLE COLOUR INSTEAD OF VANISHING
                final SvgPaint fallback = buildRadialFallback(e.getValue(), nodes);
                if (fallback != null) this.gradients.put(e.getKey(), fallback);
            }
        }
    }

    private SvgPaint buildLinear(final SvgNode node, final Map<String, SvgNode> nodes) throws CodecException {
        final List<SvgNode> chain = resolveChain(node, nodes);
        final boolean userSpace = "userSpaceOnUse".equalsIgnoreCase(attr(chain, "gradientUnits"));
        final Affine gt = SvgParser.parseTransform(attr(chain, "gradientTransform"));

        final double x1 = coord(attr(chain, "x1"), 0.0, userSpace, this.refW);
        final double y1 = coord(attr(chain, "y1"), 0.0, userSpace, this.refH);
        final double x2 = coord(attr(chain, "x2"), 1.0, userSpace, this.refW);
        final double y2 = coord(attr(chain, "y2"), 0.0, userSpace, this.refH);

        final List<SvgNode> stopNodes = stopsOf(chain);
        final float[] offs = new float[stopNodes.size()];
        final int[] cols = new int[stopNodes.size()];
        double prev = -1;
        for (int i = 0; i < stopNodes.size(); i++) {
            final SvgNode stop = stopNodes.get(i);
            double off = SvgParser.ratioOrNumber(stop.attr("offset"), 0);
            if (off < 0) off = 0; else if (off > 1) off = 1;
            if (off <= prev) off = prev + 1e-6; // KEEP STRICTLY INCREASING (HARD-EDGE STOPS)
            prev = off;
            offs[i] = (float) off;
            cols[i] = stopColor(stop);
        }
        return new SvgPaint.Linear(x1, y1, x2, y2, userSpace, gt, offs, cols);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static boolean hidden(final SvgNode node) {
        final String display = node.attr("display");
        return display != null && display.trim().equalsIgnoreCase("none");
    }

    private static void indexGradients(final SvgNode node, final Map<String, SvgNode> out, final int depth) throws CodecException {
        if (depth > MAX_DEPTH) throw new CodecException("SVG nesting exceeds " + MAX_DEPTH + " levels");
        final String tag = node.tag();
        if (tag.equals("lineargradient") || tag.equals("radialgradient")) {
            final String id = node.attr("id");
            if (id != null) out.put(id, node);
        }
        for (final SvgNode c: node.children()) indexGradients(c, out, depth + 1);
    }

    // PARSES A <stop>: stop-color (BLACK BY DEFAULT) WITH stop-opacity FOLDED INTO THE ALPHA
    private static int stopColor(final SvgNode stop) throws CodecException {
        final String sc = stop.attr("stop-color");
        final long parsed = sc == null ? SvgColor.INVALID : SvgColor.parse(sc, 0xFF000000);
        int c = parsed == SvgColor.INVALID ? 0xFF000000 : (int) parsed;
        final String so = stop.attr("stop-opacity");
        if (so != null) {
            final double a = MathUtil.clamp(SvgParser.ratioOrNumber(so, 1), 0, 1);
            final int alpha = (int) Math.round(((c >>> 24) & 0xFF) * a);
            c = (alpha << 24) | (c & 0x00FFFFFF);
        }
        return c;
    }

    // SOLID APPROXIMATION FOR AN UNSUPPORTED (RADIAL) GRADIENT: THE AVERAGE OF ITS STOP COLOURS
    private static SvgPaint buildRadialFallback(final SvgNode node, final Map<String, SvgNode> nodes) throws CodecException {
        final List<SvgNode> stops = stopsOf(resolveChain(node, nodes));
        if (stops.isEmpty()) return null;
        long a = 0, r = 0, g = 0, b = 0;
        for (final SvgNode stop: stops) {
            final int c = stopColor(stop);
            a += (c >>> 24) & 0xFF; r += (c >>> 16) & 0xFF; g += (c >>> 8) & 0xFF; b += c & 0xFF;
        }
        final int n = stops.size();
        return SvgPaint.solid((int) ((a / n) << 24 | (r / n) << 16 | (g / n) << 8 | (b / n)));
    }

    // FOLLOWS THE href/xlink:href TEMPLATE CHAIN (SELF FIRST), WHERE ATTRIBUTES AND STOPS MAY LIVE ON ANY ANCESTOR.
    // THE IDENTITY GUARD STOPS CYCLES; MAX_CHAIN BOUNDS THE LENGTH (SEE THE CONSTANT)
    private static List<SvgNode> resolveChain(final SvgNode node, final Map<String, SvgNode> nodes) throws CodecException {
        final List<SvgNode> chain = new ArrayList<>();
        final Set<SvgNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SvgNode cur = node;
        while (cur != null && seen.add(cur)) {
            if (chain.size() == MAX_CHAIN) throw new CodecException("Gradient href chain exceeds " + MAX_CHAIN);
            chain.add(cur);
            String href = cur.attr("href");
            if (href == null) href = cur.attr("xlink:href");
            cur = (href != null && href.startsWith("#")) ? nodes.get(href.substring(1)) : null;
        }
        return chain;
    }

    // FIRST NON-NULL ATTRIBUTE ALONG THE TEMPLATE CHAIN
    private static String attr(final List<SvgNode> chain, final String name) {
        for (final SvgNode n: chain) {
            final String v = n.attr(name);
            if (v != null) return v;
        }
        return null;
    }

    // STOPS FROM THE FIRST CHAIN ELEMENT THAT DECLARES ANY
    private static List<SvgNode> stopsOf(final List<SvgNode> chain) throws CodecException {
        for (final SvgNode n: chain) {
            final List<SvgNode> stops = new ArrayList<>();
            for (final SvgNode c: n.children()) {
                if (!c.tag().equals("stop")) continue;
                if (stops.size() == MAX_STOPS) throw new CodecException("Gradient exceeds " + MAX_STOPS + " stops");
                stops.add(c);
            }
            if (!stops.isEmpty()) return stops;
        }
        return List.of();
    }

    // GRADIENT COORDINATE: OBB → FRACTION; userSpaceOnUse → USER UNITS (PERCENT SCALES BY VIEWPORT)
    private static double coord(final String value, final double defFraction, final boolean userSpace, final double ref) throws CodecException {
        if (value == null) return userSpace ? defFraction * ref : defFraction;
        if (userSpace) return SvgParser.isPercent(value) ? SvgParser.ratioOrNumber(value, defFraction) * ref : SvgParser.length(value, defFraction);
        return SvgParser.ratioOrNumber(value, defFraction);
    }
}
