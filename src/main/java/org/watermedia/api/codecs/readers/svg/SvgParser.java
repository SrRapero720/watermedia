package org.watermedia.api.codecs.readers.svg;

import org.watermedia.api.codecs.CodecException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the {@link SvgNode} tree from SVG bytes with the JDK StAX reader and hosts the value parsers the renderer
 * shares (transform lists, lengths, point lists). Hardened against XXE, SSRF and entity bombs: external entities are
 * off, every external DTD resolves through an {@link XMLResolver} to an empty stream, and expansion is capped twice.
 */
final class SvgParser {
    // DECODER-OWN PARSE BUDGETS. EVERY EXPANDED START_ELEMENT RETAINS A NODE (RECORD + MAP + LIST + TAG)
    // AND EVERY ATTRIBUTE RETAINS ITS EXPANDED TEXT, SO AN ENTITY BOMB IS FIRST OF ALL A HEAP BOMB.
    // THESE SIT ORDERS OF MAGNITUDE ABOVE ANY REAL DOCUMENT AND ARE INDEPENDENT OF THE JDK LIMITS BELOW
    private static final int MAX_ELEMENTS = 100_000;
    private static final int MAX_ATTR_CHARS = 1_000_000;   // ONE ATTRIBUTE VALUE (A LONG d="" IS LEGITIMATE)
    private static final long MAX_DOC_CHARS = 16_000_000L; // ALL ATTRIBUTE VALUES OF THE DOCUMENT

    private static final XMLInputFactory FACTORY;
    static {
        final XMLInputFactory f = XMLInputFactory.newFactory();
        setProperty(f, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        setProperty(f, XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        // EVERY EXTERNAL DTD/ENTITY RESOLVES EMPTY: A <!DOCTYPE> WITH A SYSTEM ID (INKSCAPE, W3C) PARSES WITHOUT A
        // NETWORK REQUEST, WHERE ACCESS_EXTERNAL_DTD="" WOULD FAIL THE WHOLE PARSE
        f.setXMLResolver((publicID, systemID, baseURI, namespace) -> new ByteArrayInputStream(new byte[0]));
        // NAMESPACE-UNAWARE: STAY LENIENT LIKE THE REST OF THE DECODER. NAMESPACE-AWARE MODE MAKES AN
        // UNDECLARED PREFIX (e.g. xlink:href WITH NO xmlns:xlink) A FATAL ERROR; ATTRIBUTES ARE READ
        // BY LOCAL NAME (href / xlink:href BOTH HANDLED), SO PREFIX RESOLUTION IS NOT NEEDED
        setProperty(f, XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
        // NEVER INHERIT THE JDK ENTITY DEFAULTS: THEY DIFFER BY ORDERS OF MAGNITUDE ACROSS THE RELEASES
        // THIS RUNS ON, SO A DOCUMENT THAT LOOKS DEFUSED ON ONE IS A SEVERAL-HUNDRED-MEGABYTE BOMB ON
        // ANOTHER. REAL SVGs EXPAND NO ENTITIES AT ALL, WHICH MAKES EVEN THESE VALUES GENEROUS
        setProperty(f, "jdk.xml.entityExpansionLimit", 2_000);
        setProperty(f, "jdk.xml.entityReplacementLimit", 100_000);
        setProperty(f, "jdk.xml.totalEntitySizeLimit", 1_000_000);
        setProperty(f, "jdk.xml.maxGeneralEntitySizeLimit", 100_000);
        FACTORY = f;
    }

    private SvgParser() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    static SvgNode parse(final InputStream in) throws CodecException {
        XMLStreamReader r = null;
        try {
            r = FACTORY.createXMLStreamReader(in);
            final Deque<SvgNode> stack = new ArrayDeque<>();
            SvgNode root = null;
            int elements = 0;
            long chars = 0;
            while (r.hasNext()) {
                final int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    // BUDGETS CHECKED BEFORE ANY ALLOCATION: THE WHOLE POINT IS TO NOT RETAIN THE NODE
                    if (++elements > MAX_ELEMENTS) throw new CodecException("SVG element count exceeds " + MAX_ELEMENTS);
                    final Map<String, String> attrs = new HashMap<>();
                    final int count = r.getAttributeCount();
                    for (int i = 0; i < count; i++) {
                        // ENTITIES EXPAND INSIDE ATTRIBUTE VALUES TOO: A SINGLE d="" CAN REACH TENS OF
                        // MILLIONS OF CHARACTERS AND HAND THE PATH PARSER A SEGMENT BOMB
                        final String value = r.getAttributeValue(i);
                        chars += value.length();
                        if (value.length() > MAX_ATTR_CHARS || chars > MAX_DOC_CHARS)
                            throw new CodecException("SVG attribute data exceeds the decoder budget");
                        attrs.put(r.getAttributeLocalName(i), value);
                    }
                    // THE INLINE style="prop: value; ..." DECLARATIONS OVERLAY THE PRESENTATION ATTRIBUTES
                    final String style = attrs.get("style");
                    if (style != null) {
                        for (final String decl: style.split(";")) {
                            final int sep = decl.indexOf(':');
                            if (sep <= 0) continue;
                            final String prop = decl.substring(0, sep).trim().toLowerCase(Locale.ROOT);
                            final String val = decl.substring(sep + 1).trim();
                            if (!prop.isEmpty() && !val.isEmpty()) attrs.put(prop, val);
                        }
                    }

                    // NAMESPACE-UNAWARE StAX RETURNS THE RAW QNAME ("svg:svg"): STRIP THE PREFIX SO PREFIXED DOCUMENTS
                    // DISPATCH LIKE UNPREFIXED ONES, AND LOWERCASE ONCE, SINCE EVERY DISPATCH SITE COMPARES LOWERCASE TAGS
                    String tag = r.getLocalName();
                    final int colon = tag.indexOf(':');
                    if (colon >= 0) tag = tag.substring(colon + 1);
                    final SvgNode node = new SvgNode(tag.toLowerCase(Locale.ROOT), attrs);
                    if (stack.isEmpty()) {
                        if (root == null) root = node;
                    } else {
                        stack.peek().children().add(node);
                    }
                    stack.push(node);
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (!stack.isEmpty()) stack.pop();
                }
            }
            return root;
        } catch (final XMLStreamException e) {
            // EVERY StAX FAILURE FUNNELS HERE, INCLUDING EACH JDK ENTITY-LIMIT TRIP. THE JDK MESSAGE IS
            // LOCALE-DEPENDENT, SO IT IS ONLY EVER CARRIED ALONG, NEVER INSPECTED
            throw new CodecException("Malformed SVG document: " + e.getMessage(), e);
        } finally {
            if (r != null) {
                try { r.close(); }
                catch (final XMLStreamException ignored) {} // BEST EFFORT
            }
        }
    }

    // TRANSFORM LIST → AFFINE, FUNCTIONS COMPOSED LEFT TO RIGHT
    static Affine parseTransform(final String s) throws CodecException {
        if (s == null || s.isBlank()) return Affine.IDENTITY;
        Affine m = Affine.IDENTITY;
        int i = 0;
        final int n = s.length();
        while (i < n) {
            while (i < n && !Character.isLetter(s.charAt(i))) i++;
            final int nameStart = i;
            while (i < n && Character.isLetter(s.charAt(i))) i++;
            if (i >= n || nameStart == i) break;
            final String name = s.substring(nameStart, i);
            final int open = s.indexOf('(', i);
            final int close = open < 0 ? -1 : s.indexOf(')', open);
            if (open < 0 || close < 0) break;
            final double[] a = numbers(s.substring(open + 1, close));
            i = close + 1;
            m = m.concat(switch (name) {
                case "translate" -> Affine.translate(a.length > 0 ? a[0] : 0, a.length > 1 ? a[1] : 0);
                case "scale" -> Affine.scale(a.length > 0 ? a[0] : 1, a.length > 1 ? a[1] : (a.length > 0 ? a[0] : 1));
                case "rotate" -> a.length >= 3 ? Affine.rotate(a[0], a[1], a[2]) : Affine.rotate(a.length > 0 ? a[0] : 0);
                case "matrix" -> a.length >= 6 ? new Affine(a[0], a[1], a[2], a[3], a[4], a[5]) : Affine.IDENTITY;
                case "skewX" -> Affine.skewX(a.length > 0 ? a[0] : 0);
                case "skewY" -> Affine.skewY(a.length > 0 ? a[0] : 0);
                default -> Affine.IDENTITY;
            });
        }
        return m;
    }

    // PARSES A LENGTH INTO USER UNITS (px). UNKNOWN UNITS AND "%" FALL BACK TO THE RAW NUMBER.
    static double length(final String v, final double def) throws CodecException {
        if (v == null || v.isBlank()) return def;
        final String s = v.trim();
        int end = 0;
        final int n = s.length();
        if (end < n && (s.charAt(end) == '+' || s.charAt(end) == '-')) end++;
        boolean dot = false;
        while (end < n) {
            final char c = s.charAt(end);
            if (c >= '0' && c <= '9') end++;
            else if (c == '.' && !dot) { dot = true; end++; }
            else if (c == 'e' || c == 'E') {
                // CONSUME 'e'/'E' AS AN EXPONENT MARKER ONLY WHEN A (SIGNED) DIGIT FOLLOWS; OTHERWISE
                // IT IS A UNIT LETTER (em/ex) AND THE NUMBER ENDS HERE
                int k = end + 1;
                if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) k++;
                if (k < n && s.charAt(k) >= '0' && s.charAt(k) <= '9') end = k;
                else break;
            } else break;
        }
        if (end == 0) return def;
        final double num;
        try { num = Double.parseDouble(s.substring(0, end)); }
        catch (final NumberFormatException e) { return def; }
        final String unit = s.substring(end).trim().toLowerCase(Locale.ROOT);
        return finite(switch (unit) {
            case "", "px" -> num;
            case "pt" -> num * 96.0 / 72.0;
            case "pc", "em" -> num * 16.0;
            case "in" -> num * 96.0;
            case "cm" -> num * 96.0 / 2.54;
            case "mm" -> num * 96.0 / 25.4;
            case "ex" -> num * 8.0;
            default -> num; // INCLUDING "%": HANDLED BY CALLERS THAT HAVE A REFERENCE LENGTH
        });
    }

    // PARSES A LENGTH, RESOLVING "%" AGAINST THE GIVEN REFERENCE LENGTH (SVG VIEWPORT AXIS/DIAGONAL)
    static double length(final String v, final double def, final double ref) throws CodecException {
        if (v == null || v.isBlank()) return def;
        final String s = v.trim();
        if (s.endsWith("%")) {
            final double pct;
            try { pct = Double.parseDouble(s.substring(0, s.length() - 1)); }
            catch (final NumberFormatException e) { return def; }
            return finite(pct / 100.0 * ref);
        }
        return length(s, def);
    }

    static boolean isPercent(final String v) {
        return v != null && v.trim().endsWith("%");
    }

    // PARSES A NUMBER POSSIBLY EXPRESSED AS A PERCENT (RETURNS THE FRACTION FOR "%", ELSE THE NUMBER)
    static double ratioOrNumber(final String v, final double def) throws CodecException {
        if (v == null || v.isBlank()) return def;
        final String s = v.trim();
        final double num;
        try {
            num = s.endsWith("%")
                    ? Double.parseDouble(s.substring(0, s.length() - 1)) / 100.0
                    : Double.parseDouble(s);
        } catch (final NumberFormatException e) {
            return def;
        }
        return finite(num);
    }

    // SCANS A NUMBER LIST, TOLERATING SVG'S COMPACT FORMS: NUMBERS SEPARATED BY WHITESPACE, COMMAS,
    // OR ONLY A SIGN (e.g. points="100-50") OR A DECIMAL POINT, PLUS EXPONENTS
    static double[] numbers(final String s) throws CodecException {
        if (s == null || s.isBlank()) return new double[0];
        final int n = s.length();
        double[] out = new double[8];
        int k = 0, i = 0;
        while (i < n) {
            final char sep = s.charAt(i);
            if (sep == ' ' || sep == ',' || sep == '\t' || sep == '\n' || sep == '\r' || sep == '\f') { i++; continue; }
            final int start = i;
            if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
            boolean dot = false, digits = false;
            while (i < n) {
                final char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (digits && i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int j = i + 1;
                if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) j++;
                if (j < n && s.charAt(j) >= '0' && s.charAt(j) <= '9') {
                    i = j + 1;
                    while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                }
            }
            if (!digits) { i = start + 1; continue; } // SKIP A STRAY NON-NUMERIC CHARACTER
            final double num;
            try { num = Double.parseDouble(s.substring(start, i)); }
            catch (final NumberFormatException e) { continue; } // DROP MALFORMED TOKEN WITHOUT CONSUMING A SLOT
            if (k == out.length) out = Arrays.copyOf(out, out.length * 2);
            out[k++] = finite(num);
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static void setProperty(final XMLInputFactory factory, final String name, final Object value) {
        try { factory.setProperty(name, value); }
        catch (final IllegalArgumentException ignored) {} // SOME IMPLEMENTATIONS REJECT UNKNOWN PROPERTIES: BEST EFFORT
    }

    // NO NUMBER PARSED OUT OF A DOCUMENT MAY BE NON-FINITE: THE SCANNERS ACCEPT EXPONENTS, SO "1e999" READS AS
    // INFINITY, WHICH MAKES FLATNESS TESTS FALSE-BY-NaN (FULL-DEPTH SUBDIVISION) AND SCANLINE CROSSINGS NaN.
    // THE DOCUMENT IS REJECTED INSTEAD OF RENDERED WRONG
    private static double finite(final double v) throws CodecException {
        if (!Double.isFinite(v)) throw new CodecException("Non-finite number in SVG document");
        return v;
    }
}
