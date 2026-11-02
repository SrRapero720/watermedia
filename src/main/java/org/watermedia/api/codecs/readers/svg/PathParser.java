package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;

/**
 * Parses the full SVG {@code d} grammar ({@code M L H V C S Q T A Z}, absolute and relative, implicit lineto,
 * smooth reflection, arcs as cubics) into a {@link Path}, tolerating compact numbers such as {@code "10-20"}.
 */
final class PathParser {
    private final String d;
    private final int len;
    private int pos;

    // CURRENT POINT, SUBPATH START, AND LAST CONTROL POINT FOR SMOOTH REFLECTION
    private double cx, cy, sx, sy, lastCtrlX, lastCtrlY;
    private char lastCmd;

    private PathParser(final String d) {
        this.d = d;
        this.len = d.length();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    static void parse(final String d, final Path path) throws CodecException {
        if (d == null || d.isBlank()) return;
        new PathParser(d).run(path);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void run(final Path path) throws CodecException {
        char cmd = 0;
        while (true) {
            this.skipSep();
            if (this.pos >= this.len) break;
            final char ch = this.d.charAt(this.pos);
            if (isCommand(ch)) {
                cmd = ch;
                this.pos++;
            } else if (cmd == 0) {
                break; // MALFORMED: OPERANDS BEFORE ANY COMMAND
            }

            // A num() OF NaN MEANS THE COMMAND RAN OUT OF OPERANDS AT A FOLLOWING COMMAND LETTER;
            // continue DROPS THE INCOMPLETE COMMAND AND LETS THE LOOP RE-DISPATCH THAT LETTER
            switch (cmd) {
                case 'M', 'm' -> {
                    final boolean rel = cmd == 'm';
                    double x = this.num(), y = this.num();
                    if (rel) { x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    path.moveTo(x, y);
                    this.cx = this.sx = x; this.cy = this.sy = y;
                    this.lastCmd = cmd;
                    cmd = rel ? 'l' : 'L'; // SUBSEQUENT IMPLICIT PAIRS ARE LINETO
                }
                case 'L', 'l' -> {
                    final boolean rel = cmd == 'l';
                    double x = this.num(), y = this.num();
                    if (rel) { x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    path.lineTo(x, y);
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'H', 'h' -> {
                    double x = this.num();
                    if (cmd == 'h') x += this.cx;
                    if (Double.isNaN(x)) continue;
                    path.lineTo(x, this.cy);
                    this.cx = x; this.lastCmd = cmd;
                }
                case 'V', 'v' -> {
                    double y = this.num();
                    if (cmd == 'v') y += this.cy;
                    if (Double.isNaN(y)) continue;
                    path.lineTo(this.cx, y);
                    this.cy = y; this.lastCmd = cmd;
                }
                case 'C', 'c' -> {
                    final boolean rel = cmd == 'c';
                    double c1x = this.num(), c1y = this.num();
                    double c2x = this.num(), c2y = this.num();
                    double x = this.num(), y = this.num();
                    if (rel) { c1x += this.cx; c1y += this.cy; c2x += this.cx; c2y += this.cy; x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y);
                    this.lastCtrlX = c2x; this.lastCtrlY = c2y;
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'S', 's' -> {
                    final boolean rel = cmd == 's';
                    double c2x = this.num(), c2y = this.num();
                    double x = this.num(), y = this.num();
                    if (rel) { c2x += this.cx; c2y += this.cy; x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    final boolean reflect = this.lastCmd == 'C' || this.lastCmd == 'c' || this.lastCmd == 'S' || this.lastCmd == 's';
                    final double c1x = reflect ? 2 * this.cx - this.lastCtrlX : this.cx;
                    final double c1y = reflect ? 2 * this.cy - this.lastCtrlY : this.cy;
                    path.cubicTo(c1x, c1y, c2x, c2y, x, y);
                    this.lastCtrlX = c2x; this.lastCtrlY = c2y;
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'Q', 'q' -> {
                    final boolean rel = cmd == 'q';
                    double cxp = this.num(), cyp = this.num();
                    double x = this.num(), y = this.num();
                    if (rel) { cxp += this.cx; cyp += this.cy; x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    path.quadTo(cxp, cyp, x, y);
                    this.lastCtrlX = cxp; this.lastCtrlY = cyp;
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'T', 't' -> {
                    final boolean rel = cmd == 't';
                    double x = this.num(), y = this.num();
                    if (rel) { x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    final boolean reflect = this.lastCmd == 'Q' || this.lastCmd == 'q' || this.lastCmd == 'T' || this.lastCmd == 't';
                    final double cxp = reflect ? 2 * this.cx - this.lastCtrlX : this.cx;
                    final double cyp = reflect ? 2 * this.cy - this.lastCtrlY : this.cy;
                    path.quadTo(cxp, cyp, x, y);
                    this.lastCtrlX = cxp; this.lastCtrlY = cyp;
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'A', 'a' -> {
                    final boolean rel = cmd == 'a';
                    final double rx = this.num(), ry = this.num(), rot = this.num();
                    final boolean large = this.flag(), sweep = this.flag();
                    double x = this.num(), y = this.num();
                    if (rel) { x += this.cx; y += this.cy; }
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    this.arc(path, rx, ry, rot, large, sweep, x, y);
                    this.cx = x; this.cy = y; this.lastCmd = cmd;
                }
                case 'Z', 'z' -> {
                    path.close();
                    this.cx = this.sx; this.cy = this.sy; this.lastCmd = cmd;
                    // Z TAKES NO OPERANDS AND CONSUMES NO INPUT: CLEAR cmd SO STRAY OPERANDS AFTER IT
                    // (e.g. d="z5") BREAK THE LOOP INSTEAD OF RE-ENTERING THIS CASE FOREVER
                    cmd = 0;
                }
                default -> { return; } // UNKNOWN COMMAND: STOP
            }
        }
    }

    // ELLIPTICAL ARC → ONE OR MORE CUBICS (SVG IMPLEMENTATION NOTES, ENDPOINT→CENTER)
    private void arc(final Path path, double rx, double ry, final double rotDeg,
                     final boolean large, final boolean sweep, final double x, final double y) throws CodecException {
        final double x0 = this.cx, y0 = this.cy;
        if (x0 == x && y0 == y) return;
        if (rx == 0 || ry == 0) { path.lineTo(x, y); return; }
        rx = Math.abs(rx); ry = Math.abs(ry);

        final double phi = Math.toRadians(rotDeg % 360.0);
        final double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
        final double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        final double x1p = cosPhi * dx2 + sinPhi * dy2;
        final double y1p = -sinPhi * dx2 + cosPhi * dy2;

        double rxSq = rx * rx, rySq = ry * ry;
        final double lambda = (x1p * x1p) / rxSq + (y1p * y1p) / rySq;
        if (lambda > 1) {
            final double s = Math.sqrt(lambda);
            rx *= s; ry *= s; rxSq = rx * rx; rySq = ry * ry;
        }

        final double sign = (large != sweep) ? 1.0 : -1.0;
        final double num = rxSq * rySq - rxSq * y1p * y1p - rySq * x1p * x1p;
        final double den = rxSq * y1p * y1p + rySq * x1p * x1p;
        final double co = den == 0 ? 0 : sign * Math.sqrt(Math.max(0, num / den));
        final double cxp = co * (rx * y1p / ry);
        final double cyp = co * (-ry * x1p / rx);

        final double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0;
        final double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0;

        final double ux = (x1p - cxp) / rx, uy = (y1p - cyp) / ry;
        final double vx = (-x1p - cxp) / rx, vy = (-y1p - cyp) / ry;
        final double theta1 = angle(1, 0, ux, uy);
        double dtheta = angle(ux, uy, vx, vy);
        if (!sweep && dtheta > 0) dtheta -= 2 * Math.PI;
        else if (sweep && dtheta < 0) dtheta += 2 * Math.PI;

        final int segments = (int) Math.ceil(Math.abs(dtheta) / (Math.PI / 2.0));
        final double delta = dtheta / segments;
        final double t = (4.0 / 3.0) * Math.tan(delta / 4.0);

        double th = theta1;
        for (int i = 0; i < segments; i++) {
            final double cosA = Math.cos(th), sinA = Math.sin(th);
            final double cosB = Math.cos(th + delta), sinB = Math.sin(th + delta);
            // SEGMENT END POINTS AND TANGENTS IN ROTATED ELLIPSE COORDINATES
            final double p1x = cx + rx * cosPhi * cosA - ry * sinPhi * sinA;
            final double p1y = cy + rx * sinPhi * cosA + ry * cosPhi * sinA;
            final double p2x = cx + rx * cosPhi * cosB - ry * sinPhi * sinB;
            final double p2y = cy + rx * sinPhi * cosB + ry * cosPhi * sinB;
            final double d1x = -rx * cosPhi * sinA - ry * sinPhi * cosA;
            final double d1y = -rx * sinPhi * sinA + ry * cosPhi * cosA;
            final double d2x = -rx * cosPhi * sinB - ry * sinPhi * cosB;
            final double d2y = -rx * sinPhi * sinB + ry * cosPhi * cosB;
            path.cubicTo(p1x + t * d1x, p1y + t * d1y, p2x - t * d2x, p2y - t * d2y, p2x, p2y);
            th += delta;
        }
    }

    private void skipSep() {
        while (this.pos < this.len) {
            final char ch = this.d.charAt(this.pos);
            if (ch == ' ' || ch == ',' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\f') this.pos++;
            else break;
        }
    }

    private boolean flag() throws CodecException {
        this.skipSep();
        if (this.pos < this.len) {
            final char ch = this.d.charAt(this.pos);
            if (ch == '0') { this.pos++; return false; }
            if (ch == '1') { this.pos++; return true; }
        }
        // FALLBACK: TREAT A GENERAL NUMBER AS A FLAG (LENIENT)
        return this.num() != 0;
    }

    private double num() throws CodecException {
        this.skipSep();
        final int start = this.pos;
        if (this.pos < this.len && (this.d.charAt(this.pos) == '+' || this.d.charAt(this.pos) == '-')) this.pos++;
        boolean dot = false, digits = false;
        while (this.pos < this.len) {
            final char ch = this.d.charAt(this.pos);
            if (ch >= '0' && ch <= '9') { digits = true; this.pos++; }
            else if (ch == '.' && !dot) { dot = true; this.pos++; }
            else break;
        }
        // OPTIONAL EXPONENT
        if (digits && this.pos < this.len && (this.d.charAt(this.pos) == 'e' || this.d.charAt(this.pos) == 'E')) {
            final int save = this.pos;
            this.pos++;
            if (this.pos < this.len && (this.d.charAt(this.pos) == '+' || this.d.charAt(this.pos) == '-')) this.pos++;
            boolean expDigits = false;
            while (this.pos < this.len && this.d.charAt(this.pos) >= '0' && this.d.charAt(this.pos) <= '9') { expDigits = true; this.pos++; }
            if (!expDigits) this.pos = save; // NOT AN EXPONENT AFTER ALL
        }
        if (!digits) {
            // NO NUMBER HERE. IF WE ARE ON A COMMAND LETTER THE PREVIOUS COMMAND RAN OUT OF OPERANDS:
            // SIGNAL IT WITH NaN WITHOUT CONSUMING, SO run() DISPATCHES THAT LETTER INSTEAD OF EATING
            // IT AS A BOGUS COORDINATE; OTHERWISE SKIP ONE STRAY CHAR TO GUARANTEE FORWARD PROGRESS
            this.pos = start;
            if (this.pos < this.len && isCommand(this.d.charAt(this.pos))) return Double.NaN;
            if (this.pos < this.len) this.pos++;
            return 0;
        }
        final double v;
        try { v = Double.parseDouble(this.d.substring(start, this.pos)); }
        catch (final NumberFormatException e) { return 0; }
        // NaN IS THE "COMMAND RAN OUT OF OPERANDS" SENTINEL ABOVE AND THE SCANNER CANNOT SPELL IT HERE.
        // AN INFINITY ("1e999") CAN BE SPELLED, AND IT POISONS FLATNESS AND SCANLINE MATH ALIKE
        if (Double.isInfinite(v)) throw new CodecException("Non-finite path coordinate");
        return v;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static double angle(final double ux, final double uy, final double vx, final double vy) {
        final double dot = ux * vx + uy * vy;
        final double lenU = Math.sqrt(ux * ux + uy * uy), lenV = Math.sqrt(vx * vx + vy * vy);
        double cos = (lenU == 0 || lenV == 0) ? 1 : dot / (lenU * lenV);
        if (cos < -1) cos = -1; else if (cos > 1) cos = 1;
        final double ang = Math.acos(cos);
        return (ux * vy - uy * vx) < 0 ? -ang : ang;
    }

    private static boolean isCommand(final char ch) {
        return switch (ch) {
            case 'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v', 'C', 'c', 'S', 's', 'Q', 'q', 'T', 't', 'A', 'a', 'Z', 'z' -> true;
            default -> false;
        };
    }
}
