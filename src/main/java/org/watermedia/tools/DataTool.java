package org.watermedia.tools;

import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Byte reads, lenient parsing, log redaction and pixel helpers shared across WaterMedia. */
public final class DataTool {
    // SECRETS: URI PASSWORDS (scheme://user:password@), JWTS (eyJ<header>.<payload>.<signature>), MINECRAFT SESSION
    // ARGUMENTS AND TRIPLES, Authorization AND Cookie VALUES AND SECRET-LOOKING key=value, key: value OR "key":"value" PAIRS
    // LINKS STAY READABLE; THE SCHEME AND THE JWT PREFIX ARE BOUNDED SO A LONG RUN OF DOTS OR "eyJ" STAYS LINEAR
    private static final Pattern URI_PASSWORD = Pattern.compile("(?i)(?<![\\w+.-])([a-z][a-z0-9+.-]{0,31}://[^\\s/?#@:]+:)[^\\s/?#@]+(?=@)");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}");
    private static final Pattern LAUNCH_ARG = Pattern.compile("(?i)(--(?:accessToken|session)\\s+)(\\S+)");
    private static final Pattern SESSION_TRIPLE = Pattern.compile("(?i)\\btoken:[^\\s:]+(?::[0-9a-fA-F-]+)?");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:[a-z]+\\s+)?)(\\S+)");
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)((?:set-)?cookie\\s*[:=]\\s*)([^\\r\\n]+)");
    private static final Pattern SECRET_KV = Pattern.compile("(?i)(\"?\\b(?:access[_-]?token|session[_-]?token|sessionId|auth[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|secret|password|passwd|pwd|api[_-]?key|apikey)\\b\"?\\s*[:=]\\s*\"?)([^\\s\"',&}]+)");
    private static final String SECRET_MASK = "********";

    private DataTool() {}

    // ==========================================================================
    // PUBLIC API: BYTES, TEXT AND REDACTION
    // ==========================================================================
    public static boolean startsWith(final ByteBuffer buffer, final int offset, final byte[] header) {
        if (buffer.limit() - offset < header.length) return false;
        for (int i = 0; i < header.length; i++) {
            if (buffer.get(offset + i) != header[i]) return false;
        }
        return true;
    }

    public static boolean startsWith(final String str, final String... prefixes) {
        if (str == null || prefixes == null) throw new IllegalArgumentException("String and prefixes are required");
        for (final var prefix: prefixes) {
            if (str.startsWith(prefix)) return true;
        }
        return false;
    }

    // UNSIGNED FIELD READS AT AN ABSOLUTE OFFSET IN EITHER BYTE ORDER; THE JIT INLINES THEM INTO THE PARSERS
    public static int u16(final ByteBuffer buffer, final int offset, final ByteOrder order) {
        final int a = buffer.get(offset) & 0xFF;
        final int b = buffer.get(offset + 1) & 0xFF;
        return order == ByteOrder.LITTLE_ENDIAN ? a | (b << 8) : (a << 8) | b;
    }

    public static int u24(final ByteBuffer buffer, final int offset, final ByteOrder order) {
        final int a = buffer.get(offset) & 0xFF;
        final int b = buffer.get(offset + 1) & 0xFF;
        final int c = buffer.get(offset + 2) & 0xFF;
        return order == ByteOrder.LITTLE_ENDIAN ? a | (b << 8) | (c << 16) : (a << 16) | (b << 8) | c;
    }

    public static long u32(final ByteBuffer buffer, final int offset, final ByteOrder order) {
        final long a = buffer.get(offset) & 0xFF;
        final long b = buffer.get(offset + 1) & 0xFF;
        final long c = buffer.get(offset + 2) & 0xFF;
        final long d = buffer.get(offset + 3) & 0xFF;
        return order == ByteOrder.LITTLE_ENDIAN ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
    }

    /** Converts a configuration value in megabytes (MiB) to bytes. */
    public static long megabytes(final long megabytes) {
        return megabytes << 20;
    }

    // CASE-INSENSITIVE HEADER-NAME MATCH; equalsIgnoreCase IS ALLOCATION-FREE UNLIKE toLowerCase().equals(...)
    public static boolean equalsIgnoreCase(final String a, final String b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    public static boolean equalsAnyIgnoreCase(final String o, final String... arr) {
        for (final String element: arr)
            if (element != null && element.equalsIgnoreCase(o)) return true;
        return false;
    }

    /** Jaro-Winkler similarity of two texts from 0 (nothing in common) to 1 (equal ignoring case); one keystroke apart scores above 0.8. */
    public static float similarity(final String a, final String b) {
        if (a == null || b == null) throw new IllegalArgumentException("Both texts are required");
        final int la = a.length(), lb = b.length();
        if (la == 0 || lb == 0) return la == lb ? 1f : 0f;
        // LETTERS MATCH WHEN THEY SIT WITHIN HALF THE LONGER LENGTH OF EACH OTHER, EACH LETTER AT MOST ONCE
        final int window = Math.max(0, Math.max(la, lb) / 2 - 1);
        final boolean[] matchedA = new boolean[la];
        final boolean[] matchedB = new boolean[lb];
        int matches = 0;
        for (int i = 0; i < la; i++) {
            final char c = Character.toLowerCase(a.charAt(i));
            for (int j = Math.max(0, i - window), end = Math.min(lb, i + window + 1); j < end; j++) {
                if (matchedB[j] || Character.toLowerCase(b.charAt(j)) != c) continue;
                matchedA[i] = matchedB[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0f;
        // MATCHED LETTERS OUT OF ORDER ARE TRANSPOSITIONS, EACH PAIR COSTING ONE MATCH
        int transposed = 0;
        for (int i = 0, j = 0; i < la; i++) {
            if (!matchedA[i]) continue;
            while (!matchedB[j]) j++;
            if (Character.toLowerCase(a.charAt(i)) != Character.toLowerCase(b.charAt(j))) transposed++;
            j++;
        }
        final float jaro = (matches / (float) la + matches / (float) lb + (matches - transposed / 2) / (float) matches) / 3f;
        // UP TO FOUR LEADING LETTERS IN COMMON PULL THE SCORE TOWARDS ONE, SO A TYPO LATE IN A WORD STAYS CLOSE
        int prefix = 0;
        while (prefix < 4 && prefix < la && prefix < lb && Character.toLowerCase(a.charAt(prefix)) == Character.toLowerCase(b.charAt(prefix))) prefix++;
        return jaro + prefix * 0.1f * (1f - jaro);
    }

    public static int parseInt(final String s, final int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static long parseLong(final String s, final long def) {
        if (s == null) return def;
        try { return Long.parseLong(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    public static double parseDouble(final String s, final double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); }
        catch (final NumberFormatException e) { return def; }
    }

    /** Parses a web URL leniently: a scheme-relative value gets https and anything malformed or not http(s) yields null. */
    public static URI uri(final String uri) {
        if (uri == null || uri.isEmpty()) return null;
        try {
            final URI parsed = URI.create(uri.startsWith("//") ? "https:" + uri : uri);
            return IOTool.web(parsed) ? parsed : null;
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    // EXPIRY CARRIED BY A SIGNED CDN URL: THE FIRST expire/expires/validto/x-expires QUERY VALUE, IN EPOCH SECONDS
    // OR MILLISECONDS, OR NULL WHEN THE URL CARRIES NONE
    public static Instant expiry(final URI uri) {
        final String query = uri == null ? null : uri.getRawQuery();
        if (query == null) return null;
        for (final String param: query.split("&")) {
            final int eq = param.indexOf('=');
            if (eq < 0) continue;
            final String key = param.substring(0, eq).toLowerCase(Locale.ROOT);
            if (!key.equals("expire") && !key.equals("expires") && !key.equals("validto") && !key.equals("x-expires")) continue;
            // BELOW 2001 IN SECONDS IT IS A RELATIVE LIFETIME OR GARBAGE, NOT AN EPOCH; ABOVE 100 BILLION IT IS MILLISECONDS
            final long value = parseLong(param.substring(eq + 1), -1L);
            if (value < 1_000_000_000L) continue;
            return value > 100_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        return null;
    }

    /** Masks the secrets in a text (URI passwords, bare JWTs, session arguments, Authorization and Cookie values, secret-looking pairs) and keeps links readable. */
    public static String redact(final String text) {
        if (text == null || text.isEmpty()) return text;
        String out = URI_PASSWORD.matcher(text).replaceAll("$1" + SECRET_MASK);
        out = JWT.matcher(out).replaceAll(SECRET_MASK);
        out = LAUNCH_ARG.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = AUTH_HEADER.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = COOKIE_HEADER.matcher(out).replaceAll("$1" + SECRET_MASK);
        out = SECRET_KV.matcher(out).replaceAll("$1" + SECRET_MASK);
        return SESSION_TRIPLE.matcher(out).replaceAll("token:" + SECRET_MASK);
    }

    // ONE-SHOT SHA-256 OF data; SHA-256 IS MANDATED BY THE JVM SPEC, SO ABSENCE MEANS A BROKEN RUNTIME
    public static byte[] sha256(final byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ==========================================================================
    // PUBLIC API: PIXELS
    // ==========================================================================
    // CONVERTS BT.601 LIMITED-RANGE YUV 4:2:0 PLANES (VP8) TO PACKED OPAQUE ARGB INTS, WHICH LAND AS [B,G,R,A]
    // BYTES ONCE WRITTEN LITTLE-ENDIAN. STRIDES MAY EXCEED THE WIDTH (MACROBLOCK PADDING)
    public static int[] yuvToBgra(final byte[] yP, final byte[] uP, final byte[] vP, final int w, final int h, final int yS, final int uvS) {
        final int[] bgra = new int[w * h];
        for (int py = 0; py < h; py++) {
            final int yRow = py * yS;
            final int uvRow = (py >> 1) * uvS;
            final int dstRow = py * w;
            for (int px = 0; px < w; px++) {
                final int y = yP[yRow + px] & 0xFF;
                final int i = uvRow + (px >> 1);
                final int u = uP[i] & 0xFF;
                final int v = vP[i] & 0xFF;
                final int c = y - 16, d = u - 128, e = v - 128;
                final int r = MathUtil.clip255((298 * c + 409 * e + 128) >> 8);
                final int g = MathUtil.clip255((298 * c - 100 * d - 208 * e + 128) >> 8);
                final int b = MathUtil.clip255((298 * c + 516 * d + 128) >> 8);
                bgra[dstRow + px] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return bgra;
    }

    // ONE BULK PUT OF THE PACKED ARGB CANVAS INTO A LITTLE-ENDIAN DIRECT BUFFER, SO EACH PIXEL LANDS AS [B,G,R,A] BYTES
    public static ByteBuffer bgraToBuffer(final int[] bgra) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bgra.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asIntBuffer().put(bgra);
        return buffer;
    }

    // NON-PREMULTIPLIED SOURCE-OVER OF TWO PACKED ARGB PIXELS (APNG AND WEBP FRAME BLENDING). THE DESTINATION
    // WEIGHT IS COMPUTED ONCE AND SHARED BY THE ALPHA SUM AND EVERY CHANNEL, SO NO CHANNEL CAN EXCEED 255
    public static int blendOver(final int src, final int dst) {
        final int srcA = src >>> 24;
        if (srcA == 255) return src;
        if (srcA == 0) return dst;
        final int dstW = (dst >>> 24) * (255 - srcA) / 255;
        final int outA = srcA + dstW;
        final int r = (((src >> 16) & 0xFF) * srcA + ((dst >> 16) & 0xFF) * dstW) / outA;
        final int g = (((src >> 8) & 0xFF) * srcA + ((dst >> 8) & 0xFF) * dstW) / outA;
        final int b = ((src & 0xFF) * srcA + (dst & 0xFF) * dstW) / outA;
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    // PREMULTIPLIES 8-BIT FOUR-CHANNEL PIXELS WITH ALPHA LAST (BGRA OR RGBA) IN PLACE, FROM position TO limit.
    // EACH CHANNEL BECOMES round(c * a / 255); OPAQUE PIXELS ARE SKIPPED, SO OPAQUE VIDEO COSTS ONE READ PER PIXEL.
    public static void premultiply(final ByteBuffer pixels) {
        final ByteBuffer view = pixels.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = view.position(), end = view.limit() - 3; i < end; i += 4) {
            final int p = view.getInt(i);
            final int a = p >>> 24;
            if (a == 0xFF) continue;
            // TWO CHANNELS PER MULTIPLY; (y + (y >> 8)) >> 8 WITH y = c * a + 128 IS THE EXACT ROUNDED DIVISION BY 255
            final int rb = (p & 0x00FF00FF) * a + 0x00800080;
            final int g = (p & 0x0000FF00) * a + 0x00008000;
            view.putInt(i, (p & 0xFF000000) | (((rb + ((rb >>> 8) & 0x00FF00FF)) >>> 8) & 0x00FF00FF)
                    | (((g + ((g >>> 8) & 0x0000FF00)) >>> 8) & 0x0000FF00));
        }
    }

    // REMAPS A TIGHTLY PACKED RASTER OF 1 OR 4 BYTES PER PIXEL INTO ITS EXIF ORIENTATION (1-8), FROM THE START OF
    // src INTO THE START OF dst; 5-8 SWAP THE AXES, SO dst ENDS UP height x width. OUTPUT PIXEL (x, y) COMES FROM
    // SOURCE INDEX a + y * b + x * step
    public static void orient(final ByteBuffer src, final ByteBuffer dst, final int w, final int h,
                              final int bytesPerPixel, final int orientation) {
        final boolean swap = orientation >= 5;
        final int ow = swap ? h : w, oh = swap ? w : h;
        final int a, b, step;
        switch (orientation) {
            case 1 -> { a = 0; b = w; step = 1; }
            case 2 -> { a = w - 1; b = w; step = -1; }
            case 3 -> { a = (h - 1) * w + w - 1; b = -w; step = -1; }
            case 4 -> { a = (h - 1) * w; b = -w; step = 1; }
            case 5 -> { a = 0; b = 1; step = w; }
            case 6 -> { a = (h - 1) * w; b = 1; step = -w; }
            case 7 -> { a = (h - 1) * w + w - 1; b = -1; step = -w; }
            case 8 -> { a = w - 1; b = -1; step = w; }
            default -> throw new IllegalArgumentException("Invalid EXIF orientation: " + orientation);
        }
        final ByteBuffer in = src.duplicate().clear();
        final ByteBuffer out = dst.duplicate().clear();
        if (bytesPerPixel == 4) {
            final IntBuffer pixels = in.asIntBuffer();
            final IntBuffer target = out.asIntBuffer();
            for (int y = 0, di = 0; y < oh; y++) {
                for (int x = 0, si = a + y * b; x < ow; x++, si += step) target.put(di++, pixels.get(si));
            }
        } else if (bytesPerPixel == 1) {
            for (int y = 0, di = 0; y < oh; y++) {
                for (int x = 0, si = a + y * b; x < ow; x++, si += step) out.put(di++, in.get(si));
            }
        } else throw new IllegalArgumentException("Unsupported bytes per pixel: " + bytesPerPixel);
        dst.position(0).limit(ow * oh * bytesPerPixel);
    }

    // ==========================================================================
    // PUBLIC API: PIXEL-PLANE AREA SCALING (TxMediaPlayer, DownscaleFilter)
    // ==========================================================================
    // WHETHER THE AREA SCALER UNDERSTANDS format: PACKED YUYV/UYVY (PIXEL PAIRS SHARE CHROMA) AND
    // COMPRESSED BCn (GPU-SIDE BLOCKS) CANNOT BE AVERAGED BYTE-WISE.
    public static boolean scalable(final PixelFormat format) {
        return !format.compressed() && format != PixelFormat.YUYV && format != PixelFormat.UYVY;
    }

    // AREA-AVERAGE SCALE OF AN 8-BIT FRAME OF TIGHTLY PACKED PLANES (PixelFormat GEOMETRY) FROM sw x sh INTO dst AT
    // dw x dh; EACH OUTPUT TEXEL AVERAGES ITS WHOLE SOURCE BLOCK, A CHROMA PAIR OR PIXEL KEEPING ITS COMPONENTS APART
    public static void scaleArea(final ByteBuffer src, final int sw, final int sh,
                                 final ByteBuffer dst, final int dw, final int dh, final PixelFormat format) {
        if (!scalable(format)) throw new IllegalArgumentException(format + " cannot be area-scaled");
        int offset = src.position();
        for (int p = 0; p < format.planes(); p++) {
            final int pw = format.planeWidth(p, sw);
            final int ph = format.planeHeight(p, sh);
            final int components = format.texelBytes(p, 8);
            scalePlane(src, offset, pw, ph, dst, format.planeWidth(p, dw), format.planeHeight(p, dh), components);
            offset += pw * ph * components;
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // AREA-AVERAGE DOWNSCALE OF AN 8-BIT PLANE WITH ch INTERLEAVED COMPONENTS.
    // STRONG REDUCTIONS STAY ALIAS-FREE. READS src ABSOLUTE, WRITES dst RELATIVE.
    private static void scalePlane(final ByteBuffer src, final int srcOff, final int sw, final int sh,
                                   final ByteBuffer dst, final int dw, final int dh, final int ch) {
        final int[] acc = new int[ch];
        for (int dy = 0; dy < dh; dy++) {
            final int sy0 = (int) ((long) dy * sh / dh);
            final int sy1 = Math.max(sy0 + 1, (int) ((long) (dy + 1) * sh / dh));
            for (int dx = 0; dx < dw; dx++) {
                final int sx0 = (int) ((long) dx * sw / dw);
                final int sx1 = Math.max(sx0 + 1, (int) ((long) (dx + 1) * sw / dw));
                Arrays.fill(acc, 0);
                for (int sy = sy0; sy < sy1; sy++) {
                    int p = srcOff + (sy * sw + sx0) * ch;
                    for (int sx = sx0; sx < sx1; sx++) {
                        for (int c = 0; c < ch; c++) acc[c] += src.get(p++) & 0xFF;
                    }
                }
                final int count = (sy1 - sy0) * (sx1 - sx0);
                for (int c = 0; c < ch; c++) dst.put((byte) (acc[c] / count));
            }
        }
    }
}
