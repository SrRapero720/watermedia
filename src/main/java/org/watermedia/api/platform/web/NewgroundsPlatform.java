package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.arr;
import static org.watermedia.tools.JSONTool.bool;
import static org.watermedia.tools.JSONTool.intOr;
import static org.watermedia.tools.JSONTool.obj;
import static org.watermedia.tools.JSONTool.str;

/**
 * Resolves Newgrounds movies, audio and art from their pages, solving the NG Guard proof-of-work that fronts the
 * site for guests: the guard hands out an argon2id (or SHA-256) puzzle, and a verified answer clears this
 * machine's address for a while, so one solve serves every resolution that follows.
 */
public final class NewgroundsPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(NewgroundsPlatform.class.getSimpleName());
    public static final String NAME = "Newgrounds";
    // HOST SUFFIXES, MATCHED WITH A DOT BOUNDARY SO EVERY SUBDOMAIN IS COVERED
    private static final String[] HOSTS = { "newgrounds.com" };

    // SITE
    private static final String ORIGIN = "https://www.newgrounds.com";
    private static final Pattern PORTAL_PATH = Pattern.compile("^/portal/view/(\\d+)");
    private static final Pattern AUDIO_PATH = Pattern.compile("^/audio/listen/(\\d+)");
    private static final Pattern ART_PATH = Pattern.compile("^/art/view/([^/?#]+)/([^/?#]+)");
    private static final String LOGIN_TITLE = "<title>Login required";
    private static final Pattern RATING = Pattern.compile("class=\"rated-([etma])\"");
    private static final Pattern PUBLISHED = Pattern.compile("itemprop=\"(?:datePublished|uploadDate)\"\\s+content=\"([^\"]+)\"");
    private static final Pattern AUTHOR = Pattern.compile("class=\"item-details-main\">\\s*<h4>\\s*<a[^>]*>([^<]+)</a>");
    private static final Pattern AUDIO_FILE = Pattern.compile("(?:fromListenPage\\(\\{|embedController\\(\\[\\{)[^}]*?[\"']url[\"']\\s*:\\s*(\"[^\"]+\")");
    private static final Pattern AUDIO_DURATION = Pattern.compile("[\"']duration[\"']\\s*:\\s*[\"']?(\\d+)");
    private static final String IMAGE_DATA = "imageData = ";
    private static final Pattern ENTITY = Pattern.compile("&(#\\d{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);");

    // NG GUARD
    private static final String GUARD_TITLE = "<title>NG Guard</title>";
    private static final String CHALLENGE_URL = ORIGIN + "/_guard/api/v1/challenge";
    private static final String VERIFY_URL = ORIGIN + "/_guard/api/v1/verify";
    private static final long SOLVE_BUDGET_MS = 60_000L; // THE BROWSER GIVES UP AFTER FOUR PUZZLES OF 30 s
    private static final int MAX_MEMORY_KIB = 65_536; // A PUZZLE ASKING FOR MORE IS NOT WORTH THE HEAP
    private static final int MAX_LANES = 16;

    // BLAKE2B (THE HASH UNDER ARGON2ID)
    private static final long[] BLAKE2B_IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L };
    private static final byte[][] BLAKE2B_SIGMA = {
            { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 },
            { 14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3 },
            { 11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4 },
            { 7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8 },
            { 9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13 },
            { 2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9 },
            { 12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11 },
            { 13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10 },
            { 6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5 },
            { 10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0 },
            { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 },
            { 14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3 } };

    // THE LAST TIME THIS INSTANCE CLEARED THE GUARD, GUARDED BY guardLock; A BLOCK SEEN BEFORE IT IS ALREADY LIFTED
    private final Object guardLock = new Object();
    private long clearedAt = System.nanoTime();

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!WebRequests.hostMatches(uri.getHost(), HOSTS)) return null;
        final String path = uri.getPath() == null ? "" : uri.getPath();
        try {
            final Matcher portal = PORTAL_PATH.matcher(path);
            if (portal.find()) return this.movie(portal.group(1));
            final Matcher audio = AUDIO_PATH.matcher(path);
            if (audio.find()) return this.audio(audio.group(1));
            final Matcher art = ART_PATH.matcher(path);
            if (art.find()) return this.art(art.group(1), art.group(2));
            throw PlatformException.unsupported(NAME, uri);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    // ==========================================================================
    // MOVIES, AUDIO AND ART
    // ==========================================================================

    // THE PAGE CARRIES THE RATING AND METADATA; THE PLAYER JSON THE FILES. GUESTS HIT A LOGIN WALL ON ADULT MOVIES,
    // YET THE JSON IS STILL SERVED, SO WITH THE GATE OPEN THEY PLAY WITH THE JSON'S OWN TITLE AND AUTHOR
    private Resolution movie(final String id) throws IOException {
        final String item = "Movie " + id;
        final Fetched page = this.fetch(ORIGIN + "/portal/view/" + id, false);
        if (page.uri().getPath() != null && page.uri().getPath().contains("/rip/")) throw new PlatformException(NAME, item + " was removed from Newgrounds");
        final boolean walled = page.adultWall(item);
        if (!walled && page.status() != 200) throw new PlatformException(NAME, "HTTP " + page.status() + " for " + item);
        final Fetched video = this.fetch(ORIGIN + "/portal/video/" + id, true);
        if (video.status() != 200) throw new PlatformException(NAME, "HTTP " + video.status() + " for the files of " + item);
        return new Resolution(null, parseMovie(walled ? "" : page.body(), video.body(), id));
    }

    static Source parseMovie(final String html, final String json, final String id) throws PlatformException {
        final String item = "Movie " + id;
        gate(html, item);
        // THE SITE SERVES THE PLAYER JSON AS text/html
        final JsonObject video = JSONTool.parse(json, JsonObject.class);
        final JsonObject error = obj(video, "error");
        if (error != null) throw new PlatformException(NAME, item + ": " + str(error, "msg"));

        // ONE FILE PER TIER ("1080p", "720p", "360p"); THE TIER NAME IS THE ONLY SIZE THE SITE TELLS
        final JsonObject sources = obj(video, "sources");
        final List<Quality> qualities = new ArrayList<>();
        if (sources != null) for (final String tier: sources.keySet()) {
            final JsonArray files = arr(sources, tier);
            if (files == null) continue;
            for (final JsonElement fe: files) {
                final JsonObject file = obj(fe);
                final URI src = DataTool.uri(str(file, "src"));
                final String type = str(file, "type");
                if (src == null || (type != null && !type.startsWith("video/"))) continue;
                qualities.add(new Quality(src, 0, DataTool.parseInt(tier.replaceAll("\\D", ""), 0)));
                break;
            }
        }
        if (qualities.isEmpty()) throw new PlatformException(NAME, item + " has no video file; Flash-only submissions cannot play");

        String title = meta(html, "og:title");
        if (title == null) title = str(video, "title");
        final Metadata metadata = new Metadata(title, meta(html, "og:description"), published(html), 0L, str(video, "author"));
        LOGGER.info(IT, "Resolved {} '{}' with {} quality tier(s)", item, title, qualities.size());
        return new Source(MediaType.VIDEO, DataTool.uri(meta(html, "og:image")), metadata, HttpHeaders.defaults(qualities.get(0).uri()), qualities, null, null);
    }

    private Resolution audio(final String id) throws IOException {
        final String item = "Audio " + id;
        final Fetched page = this.fetch(ORIGIN + "/audio/listen/" + id, false);
        if (page.uri().getPath() != null && page.uri().getPath().contains("/rip/")) throw new PlatformException(NAME, item + " was removed from Newgrounds");
        if (page.adultWall(item)) throw new PlatformException(NAME, item + " needs a Newgrounds login");
        if (page.status() != 200) throw new PlatformException(NAME, "HTTP " + page.status() + " for " + item);
        return new Resolution(null, parseAudio(page.body(), id));
    }

    static Source parseAudio(final String html, final String id) throws PlatformException {
        final String item = "Audio " + id;
        gate(html, item);
        // THE PLAYER IS BOOTED INLINE WITH ITS FILE AS A JSON STRING LITERAL
        final Matcher player = AUDIO_FILE.matcher(html);
        final URI file = player.find() ? DataTool.uri(JSONTool.parse(player.group(1), String.class)) : null;
        if (file == null) throw new PlatformException(NAME, item + " exposes no audio file");
        final Matcher duration = AUDIO_DURATION.matcher(html);
        final long durationMs = duration.find() ? DataTool.parseLong(duration.group(1), 0L) * 1_000L : 0L;
        if (durationMs == 0L) LOGGER.warn(IT, "{} reports no duration", item);

        final String title = meta(html, "og:title");
        final Matcher author = AUTHOR.matcher(html);
        final Metadata metadata = new Metadata(title, meta(html, "og:description"), published(html), durationMs, author.find() ? unescape(author.group(1).strip()) : null);
        LOGGER.info(IT, "Resolved {} '{}'", item, title);
        return new Source(MediaType.AUDIO, DataTool.uri(meta(html, "og:image")), metadata, HttpHeaders.defaults(file), List.of(new Quality(file, 0, 0)), null, null);
    }

    private Resolution art(final String user, final String slug) throws IOException {
        final String item = "Art " + user + "/" + slug;
        final Fetched page = this.fetch(ORIGIN + "/art/view/" + user + "/" + slug, false);
        if (page.adultWall(item)) throw new PlatformException(NAME, item + " needs a Newgrounds login");
        if (page.status() != 200) throw new PlatformException(NAME, "HTTP " + page.status() + " for " + item);
        return new Resolution(null, parseArt(page.body(), item));
    }

    // ONE IMAGE SOURCE PER PIECE OF THE GALLERY, FROM THE imageData ARRAY THE PAGE SCRIPT STARTS WITH
    static List<Source> parseArt(final String html, final String item) throws PlatformException {
        gate(html, item);
        final int at = html.indexOf(IMAGE_DATA);
        if (at < 0) throw new PlatformException(NAME, item + " exposes no images");
        final JsonElement data = JsonParser.parseReader(new JsonReader(new StringReader(html.substring(at + IMAGE_DATA.length()))));
        if (!data.isJsonArray()) throw new PlatformException(NAME, item + " exposes no images");

        final String title = meta(html, "og:title");
        final Matcher author = AUTHOR.matcher(html);
        final Metadata metadata = new Metadata(title, meta(html, "og:description"), published(html), 0L, author.find() ? unescape(author.group(1).strip()) : null);
        final List<Source> sources = new ArrayList<>();
        for (final JsonElement e: data.getAsJsonArray()) {
            final JsonObject image = obj(e);
            final URI full = DataTool.uri(str(image, "image"));
            if (full == null) {
                LOGGER.warn(IT, "{} skipped an image without a file", item);
                continue;
            }
            URI preview = DataTool.uri(str(image, "medium_image"));
            if (preview == null) preview = DataTool.uri(str(image, "icon"));
            sources.add(new Source(MediaType.IMAGE, preview, metadata, HttpHeaders.defaults(full),
                    List.of(new Quality(full, Math.max(0, intOr(image, "width", 0)), Math.max(0, intOr(image, "height", 0)))), null, null));
        }
        if (sources.isEmpty()) throw new PlatformException(NAME, item + " exposes no images");
        LOGGER.info(IT, "Resolved {} '{}' with {} image(s)", item, title, sources.size());
        return sources;
    }

    // ==========================================================================
    // PAGE HELPERS
    // ==========================================================================

    // THE E/T/M/A RATING OF A PAGE: MATURE (17+) AND ADULT (18+) ARE BLOCKED WHILE THE GATE IS OFF; AUDIO PAGES SHOW NONE
    private static void gate(final String html, final String item) throws MatureContentException {
        final Matcher rating = RATING.matcher(html);
        if (!rating.find()) return;
        final char level = rating.group(1).charAt(0);
        if ((level == 'm' || level == 'a') && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, item + " is rated " + (level == 'a' ? "Adult" : "Mature"));
    }

    // THE DECODED content OF AN OPEN GRAPH META TAG, OR NULL
    private static String meta(final String html, final String property) {
        final Matcher m = Pattern.compile("<meta\\s+property=\"" + Pattern.quote(property) + "\"\\s+content=\"([^\"]*)\"").matcher(html);
        return m.find() && !m.group(1).isEmpty() ? unescape(m.group(1)) : null;
    }

    private static Instant published(final String html) {
        final Matcher m = PUBLISHED.matcher(html);
        if (!m.find()) return null;
        try { return OffsetDateTime.parse(m.group(1)).toInstant(); }
        catch (final DateTimeParseException e) {
            LOGGER.warn(IT, "Unparseable publication date '{}'", m.group(1), e);
            return null;
        }
    }

    // THE FEW ENTITIES THE SITE'S META TAGS USE: &amp; &lt; &gt; &quot; &apos; AND NUMERIC REFERENCES
    private static String unescape(final String text) {
        if (text.indexOf('&') < 0) return text;
        final Matcher m = ENTITY.matcher(text);
        final StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            final String entity = m.group(1);
            String decoded = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                default -> null;
            };
            if (decoded == null) {
                final boolean hex = entity.charAt(1) == 'x' || entity.charAt(1) == 'X';
                final int codePoint = Integer.parseInt(entity.substring(hex ? 2 : 1), hex ? 16 : 10);
                decoded = codePoint <= Character.MAX_CODE_POINT ? new String(Character.toChars(codePoint)) : m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(decoded));
        }
        return m.appendTail(out).toString();
    }

    // GETS url, CLEARING THE GUARD AND RETRYING ONCE WHEN IT ANSWERS WITH ITS PUZZLE PAGE INSTEAD; xhr ASKS THE
    // PLAYER JSON THE WAY THE SITE'S OWN SCRIPTS DO
    private Fetched fetch(final String url, final boolean xhr) throws IOException {
        final URI uri = URI.create(url);
        for (int attempt = 0; ; attempt++) {
            final long seenAt = System.nanoTime();
            final NetRequest req = new NetRequest(uri).userAgent(NetRequest.UserAgent.GENERIC).referer(ORIGIN + "/").header("Accept-Language", "en-US,en;q=0.9");
            if (xhr) req.accept(NetRequest.ACCEPT_JSON).header("X-Requested-With", "XMLHttpRequest");
            else req.accept("text/html,application/xhtml+xml,*/*");
            try (req) {
                req.send();
                final String body = req.text();
                if (!body.contains(GUARD_TITLE)) return new Fetched(req.uri(), req.statusCode(), body);
                if (attempt > 0) throw new PlatformException(NAME, "NG Guard still blocks " + uri + " after a solved puzzle");
                LOGGER.debug(IT, "NG Guard interposed on {}", uri);
            }
            this.clearGuard(seenAt);
        }
    }

    // ==========================================================================
    // NG GUARD: THE PROOF-OF-WORK INTERSTITIAL EVERY GUEST REQUEST MEETS
    // ==========================================================================

    // SOLVES THE GUARD'S PUZZLE AND SUBMITS THE ANSWER, WHICH CLEARS THIS MACHINE'S ADDRESS SERVER-SIDE (NO COOKIE IS
    // GRANTED); CONCURRENT CALLERS WAIT FOR ONE SOLVE, AND ONE BLOCKED BEFORE THE LAST CLEARANCE IS COVERED BY IT
    private void clearGuard(final long seenAt) throws IOException {
        synchronized (this.guardLock) {
            if (this.clearedAt - seenAt > 0) return;
            final JsonObject challenge = WebRequests.fetchJson(NAME, new NetRequest(CHALLENGE_URL).accept(NetRequest.ACCEPT_JSON).userAgent(NetRequest.UserAgent.GENERIC), JsonObject.class);
            final String algo = str(challenge, "algo");
            final int bits = intOr(challenge, "bits", -1);
            final String payload = str(challenge, "payload");
            final String sig = str(challenge, "sig");
            if (bits < 0 || payload == null || sig == null) throw new PlatformException(NAME, "NG Guard sent an unreadable puzzle");
            final JsonObject params = obj(challenge, "params");
            final boolean argon = "argon2id".equals(algo);
            if (argon && params == null) throw new PlatformException(NAME, "NG Guard sent an argon2id puzzle without parameters");
            LOGGER.debug(IT, "NG Guard puzzle: {} with {} leading zero bits", algo, bits);

            // THE GUARD HASHES WITH AN ALL-ZERO 8-BYTE SALT
            final long started = System.nanoTime();
            final long nonce = solve(Base64.getUrlDecoder().decode(payload), bits, argon ? new Argon2id(
                    intOr(params, "memorySize", 0), intOr(params, "iterations", 0), intOr(params, "parallelism", 0), intOr(params, "hashLength", 0), new byte[8]) : null);
            final long solveMs = (System.nanoTime() - started) / 1_000_000L;

            // THE ANSWER ECHOES THE SIGNED PUZZLE, SO THE SERVER VERIFIES WITHOUT STATE
            final JsonObject answer = new JsonObject();
            answer.addProperty("algo", argon ? "argon2id" : "sha256");
            answer.addProperty("bits", bits);
            answer.addProperty("demo", false);
            answer.addProperty("nonce", String.valueOf(nonce));
            if (argon) answer.add("params", params);
            answer.addProperty("payload", payload);
            answer.addProperty("sig", sig);
            answer.addProperty("solveTimeMs", solveMs);
            final NetRequest verify = new NetRequest(VERIFY_URL).method("POST").contentType(NetRequest.MIMETYPE_JSON).accept(NetRequest.ACCEPT_JSON)
                    .userAgent(NetRequest.UserAgent.GENERIC).body(JSONTool.write(answer));
            try (verify) {
                verify.send();
                final JsonObject result = verify.statusCode() == 200 ? verify.json(JsonObject.class) : null;
                if (result == null || !bool(result, "ok")) throw new PlatformException(NAME, "NG Guard rejected the puzzle answer with HTTP " + verify.statusCode());
            }
            this.clearedAt = System.nanoTime();
            LOGGER.info(IT, "NG Guard cleared with nonce {} in {} ms", nonce, solveMs);
        }
    }

    // THE BROWSER WORKER'S LOOP: HASHES "<payload>:<nonce>" FOR nonce = 0, 1, 2... UNTIL THE DIGEST STARTS WITH bits
    // ZERO BITS; argon null MEANS PLAIN SHA-256
    static long solve(final byte[] payload, final int bits, final Argon2id argon) throws PlatformException {
        final MessageDigest sha256;
        try {
            sha256 = argon == null ? MessageDigest.getInstance("SHA-256") : null;
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        final byte[] password = new byte[payload.length + 1 + 20];
        System.arraycopy(payload, 0, password, 0, payload.length);
        password[payload.length] = ':';
        final long deadline = System.nanoTime() + SOLVE_BUDGET_MS * 1_000_000L;
        for (long nonce = 0; ; nonce++) {
            if ((nonce & 0xFF) == 0 && Thread.currentThread().isInterrupted()) throw new PlatformException(NAME, "Interrupted while solving the NG Guard puzzle");
            if ((nonce & 0xFF) == 0 && System.nanoTime() > deadline) throw new PlatformException(NAME, "NG Guard puzzle unsolved after " + SOLVE_BUDGET_MS / 1_000L + " s");
            final byte[] digits = Long.toString(nonce).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(digits, 0, password, payload.length + 1, digits.length);
            final int length = payload.length + 1 + digits.length;
            final byte[] hash;
            if (argon != null) hash = argon.hash(password, length);
            else {
                sha256.update(password, 0, length);
                hash = sha256.digest();
            }
            int zeros = 0;
            for (final byte b: hash) {
                if (b != 0) { zeros += Integer.numberOfLeadingZeros(b & 0xFF) - 24; break; }
                zeros += 8;
            }
            if (zeros >= bits) return nonce;
        }
    }

    // ==========================================================================
    // BLAKE2B (RFC 7693), UNKEYED, DIGESTS OF 1 TO 64 BYTES
    // ==========================================================================

    static byte[] blake2b(final int length, final byte[] data) {
        final long[] h = BLAKE2B_IV.clone();
        h[0] ^= 0x0101_0000L ^ length; // PARAMETER BLOCK: DIGEST LENGTH, NO KEY, FANOUT 1, DEPTH 1
        final long[] m = new long[16];
        final long[] v = new long[16];
        final int blocks = Math.max(1, (data.length + 127) / 128);
        for (int block = 0; block < blocks; block++) {
            final int offset = block * 128;
            final int count = Math.min(128, data.length - offset);
            for (int i = 0; i < 16; i++) {
                long word = 0;
                for (int j = 7; j >= 0; j--) {
                    final int at = i * 8 + j;
                    word = (word << 8) | (at < count ? data[offset + at] & 0xFFL : 0L);
                }
                m[i] = word;
            }
            System.arraycopy(h, 0, v, 0, 8);
            System.arraycopy(BLAKE2B_IV, 0, v, 8, 8);
            v[12] ^= offset + count; // BYTES HASHED SO FAR; THE HIGH COUNTER WORD STAYS ZERO BELOW 16 EiB
            if (block == blocks - 1) v[14] = ~v[14];
            for (int round = 0; round < 12; round++) {
                final byte[] s = BLAKE2B_SIGMA[round];
                blake2bMix(v, 0, 4, 8, 12, m[s[0]], m[s[1]]); blake2bMix(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
                blake2bMix(v, 2, 6, 10, 14, m[s[4]], m[s[5]]); blake2bMix(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
                blake2bMix(v, 0, 5, 10, 15, m[s[8]], m[s[9]]); blake2bMix(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
                blake2bMix(v, 2, 7, 8, 13, m[s[12]], m[s[13]]); blake2bMix(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
            }
            for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
        }
        final byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) (h[i >>> 3] >>> ((i & 7) * 8));
        return out;
    }

    private static void blake2bMix(final long[] v, final int a, final int b, final int c, final int d, final long x, final long y) {
        v[a] += v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] += v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] += v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] += v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // A SITE RESPONSE AFTER THE GUARD: WHERE IT LANDED, ITS STATUS AND BODY
    private record Fetched(URI uri, int status, String body) {
        // WHETHER THIS IS THE "LOGIN REQUIRED" WALL GUESTS GET ON ADULT ITEMS, A MATURE BLOCK WHILE THE GATE IS OFF
        boolean adultWall(final String item) throws MatureContentException {
            if (this.status != 403 || !this.body.contains(LOGIN_TITLE)) return false;
            if (!WaterMediaConfig.platforms.allowMatureContent) throw new MatureContentException(NAME, item + " is rated Adult");
            return true;
        }
    }

    // ARGON2ID (RFC 9106): ONE INSTANCE HASHES EVERY NONCE OF A PUZZLE OVER THE SAME MEMORY MATRIX
    static final class Argon2id {
        private static final int BLOCK_LONGS = 128; // 1 KiB BLOCKS
        private static final int VERSION = 0x13;
        private static final int TYPE_ID = 2;

        private final int memoryKiB, passes, lanes, tagLength;
        private final byte[] salt;
        private final int blocks, laneLength, segmentLength;
        private final long[] memory;
        // SCRATCH OF THE COMPRESSION FUNCTION AND OF THE ARGON2I ADDRESS GENERATOR
        private final long[] r = new long[BLOCK_LONGS], z = new long[BLOCK_LONGS];
        private final long[] input = new long[BLOCK_LONGS], address = new long[BLOCK_LONGS], zero = new long[BLOCK_LONGS];

        Argon2id(final int memoryKiB, final int passes, final int lanes, final int tagLength, final byte[] salt) throws PlatformException {
            if (lanes < 1 || lanes > MAX_LANES || passes < 1 || tagLength < 4 || memoryKiB < 8 * lanes || memoryKiB > MAX_MEMORY_KIB)
                throw new PlatformException(NAME, "NG Guard asked for an unreasonable argon2id puzzle (m=" + memoryKiB + ", t=" + passes + ", p=" + lanes + ", tag=" + tagLength + ")");
            this.memoryKiB = memoryKiB;
            this.passes = passes;
            this.lanes = lanes;
            this.tagLength = tagLength;
            this.salt = salt;
            this.segmentLength = memoryKiB / (4 * lanes);
            this.laneLength = this.segmentLength * 4;
            this.blocks = this.laneLength * lanes;
            this.memory = new long[this.blocks * BLOCK_LONGS];
        }

        // THE TAG OF THE FIRST length BYTES OF password
        byte[] hash(final byte[] password, final int length) {
            // H0 BINDS EVERY PARAMETER, THE PASSWORD AND THE SALT; SECRET AND ASSOCIATED DATA ARE EMPTY
            final ByteBuffer h0in = ByteBuffer.allocate(40 + length + this.salt.length).order(ByteOrder.LITTLE_ENDIAN);
            h0in.putInt(this.lanes).putInt(this.tagLength).putInt(this.memoryKiB).putInt(this.passes).putInt(VERSION).putInt(TYPE_ID);
            h0in.putInt(length).put(password, 0, length).putInt(this.salt.length).put(this.salt).putInt(0).putInt(0);
            final byte[] h0 = blake2b(64, h0in.array());

            // THE FIRST TWO BLOCKS OF EACH LANE SEED THE MATRIX
            final byte[] seed = new byte[72];
            System.arraycopy(h0, 0, seed, 0, 64);
            for (int lane = 0; lane < this.lanes; lane++) {
                seed[68] = (byte) lane; seed[69] = (byte) (lane >>> 8); seed[70] = (byte) (lane >>> 16); seed[71] = (byte) (lane >>> 24);
                for (int column = 0; column < 2; column++) {
                    seed[64] = (byte) column;
                    ByteBuffer.wrap(hprime(seed, 1_024)).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(this.memory, (lane * this.laneLength + column) * BLOCK_LONGS, BLOCK_LONGS);
                }
            }

            // ARGON2ID: THE FIRST HALF OF PASS 0 ADDRESSES INDEPENDENTLY OF THE DATA (ARGON2I), THE REST FROM IT (ARGON2D)
            for (int pass = 0; pass < this.passes; pass++) {
                for (int slice = 0; slice < 4; slice++) {
                    final boolean independent = pass == 0 && slice < 2;
                    for (int lane = 0; lane < this.lanes; lane++) {
                        if (independent) {
                            Arrays.fill(this.input, 0L);
                            this.input[0] = pass; this.input[1] = lane; this.input[2] = slice; this.input[3] = this.blocks; this.input[4] = this.passes; this.input[5] = TYPE_ID;
                        }
                        final int start = pass == 0 && slice == 0 ? 2 : 0;
                        if (independent && start == 2) this.nextAddresses();
                        for (int index = start; index < this.segmentLength; index++) {
                            final int current = lane * this.laneLength + slice * this.segmentLength + index;
                            final int previous = current % this.laneLength == 0 ? current + this.laneLength - 1 : current - 1;
                            final long pseudo;
                            if (independent) {
                                if (index % BLOCK_LONGS == 0) this.nextAddresses();
                                pseudo = this.address[index % BLOCK_LONGS];
                            } else pseudo = this.memory[previous * BLOCK_LONGS];

                            // THE REFERENCE LANE AND, WITHIN IT, A BLOCK PICKED FROM THE AREA ALREADY FILLED, SKEWED TOWARDS RECENT ONES
                            final int referenceLane = pass == 0 && slice == 0 ? lane : (int) Long.remainderUnsigned(pseudo >>> 32, this.lanes);
                            final int tail = referenceLane == lane ? index - 1 : (index == 0 ? -1 : 0);
                            final int area = pass == 0 ? (slice == 0 ? index - 1 : slice * this.segmentLength + tail) : this.laneLength - this.segmentLength + tail;
                            long relative = pseudo & 0xFFFF_FFFFL;
                            relative = (relative * relative) >>> 32;
                            relative = area - 1 - ((area * relative) >>> 32);
                            final int startPosition = pass == 0 || slice == 3 ? 0 : (slice + 1) * this.segmentLength;
                            final int reference = referenceLane * this.laneLength + (int) ((startPosition + relative) % this.laneLength);
                            this.fill(this.memory, previous * BLOCK_LONGS, this.memory, reference * BLOCK_LONGS, this.memory, current * BLOCK_LONGS, pass > 0);
                        }
                    }
                }
            }

            // THE TAG IS H' OF THE XOR OF EVERY LANE'S LAST BLOCK
            final long[] last = new long[BLOCK_LONGS];
            for (int lane = 0; lane < this.lanes; lane++) {
                final int offset = (lane * this.laneLength + this.laneLength - 1) * BLOCK_LONGS;
                for (int i = 0; i < BLOCK_LONGS; i++) last[i] ^= this.memory[offset + i];
            }
            final ByteBuffer out = ByteBuffer.allocate(1_024).order(ByteOrder.LITTLE_ENDIAN);
            out.asLongBuffer().put(last);
            return hprime(out.array(), this.tagLength);
        }

        // THE NEXT 128 ARGON2I ADDRESSES: G(0, G(0, input)) WITH THE COUNTER BUMPED
        private void nextAddresses() {
            this.input[6]++;
            this.fill(this.zero, 0, this.input, 0, this.address, 0, false);
            this.fill(this.zero, 0, this.address, 0, this.address, 0, false);
        }

        // THE COMPRESSION FUNCTION G: R = X ^ Y, Z = P OVER THE ROWS THEN THE COLUMNS OF R, OUTPUT R ^ Z (XORED INTO next WHEN xor)
        private void fill(final long[] x, final int xo, final long[] y, final int yo, final long[] next, final int no, final boolean xor) {
            final long[] r = this.r, z = this.z;
            for (int i = 0; i < BLOCK_LONGS; i++) z[i] = r[i] = x[xo + i] ^ y[yo + i];
            for (int row = 0; row < 8; row++) permute(z, row * 16, 2);
            for (int column = 0; column < 8; column++) permute(z, column * 2, 16);
            if (xor) for (int i = 0; i < BLOCK_LONGS; i++) next[no + i] ^= r[i] ^ z[i];
            else for (int i = 0; i < BLOCK_LONGS; i++) next[no + i] = r[i] ^ z[i];
        }

        // THE PERMUTATION P OVER THE 16 WORDS v[base + (k / 2) * step + k % 2], A BLAKE2B ROUND WITH THE 32-BIT PRODUCT MIXED IN
        private static void permute(final long[] v, final int base, final int step) {
            final int s1 = base + 1, s2 = base + step, s3 = base + step + 1, s4 = base + 2 * step, s5 = base + 2 * step + 1, s6 = base + 3 * step, s7 = base + 3 * step + 1;
            final int s8 = base + 4 * step, s9 = base + 4 * step + 1, s10 = base + 5 * step, s11 = base + 5 * step + 1, s12 = base + 6 * step, s13 = base + 6 * step + 1, s14 = base + 7 * step, s15 = base + 7 * step + 1;
            mix(v, base, s4, s8, s12); mix(v, s1, s5, s9, s13); mix(v, s2, s6, s10, s14); mix(v, s3, s7, s11, s15);
            mix(v, base, s5, s10, s15); mix(v, s1, s6, s11, s12); mix(v, s2, s7, s8, s13); mix(v, s3, s4, s9, s14);
        }

        private static void mix(final long[] v, final int a, final int b, final int c, final int d) {
            v[a] = v[a] + v[b] + 2 * (v[a] & 0xFFFF_FFFFL) * (v[b] & 0xFFFF_FFFFL);
            v[d] = Long.rotateRight(v[d] ^ v[a], 32);
            v[c] = v[c] + v[d] + 2 * (v[c] & 0xFFFF_FFFFL) * (v[d] & 0xFFFF_FFFFL);
            v[b] = Long.rotateRight(v[b] ^ v[c], 24);
            v[a] = v[a] + v[b] + 2 * (v[a] & 0xFFFF_FFFFL) * (v[b] & 0xFFFF_FFFFL);
            v[d] = Long.rotateRight(v[d] ^ v[a], 16);
            v[c] = v[c] + v[d] + 2 * (v[c] & 0xFFFF_FFFFL) * (v[d] & 0xFFFF_FFFFL);
            v[b] = Long.rotateRight(v[b] ^ v[c], 63);
        }

        // THE VARIABLE-LENGTH HASH H': BLAKE2B OF LE32(length) || input, CHAINED 32 BYTES AT A TIME PAST 64 BYTES
        private static byte[] hprime(final byte[] input, final int length) {
            final byte[] prefixed = new byte[4 + input.length];
            prefixed[0] = (byte) length; prefixed[1] = (byte) (length >>> 8); prefixed[2] = (byte) (length >>> 16); prefixed[3] = (byte) (length >>> 24);
            System.arraycopy(input, 0, prefixed, 4, input.length);
            if (length <= 64) return blake2b(length, prefixed);
            final int rounds = (length + 31) / 32 - 2;
            final byte[] out = new byte[length];
            byte[] v = blake2b(64, prefixed);
            System.arraycopy(v, 0, out, 0, 32);
            for (int i = 1; i < rounds; i++) {
                v = blake2b(64, v);
                System.arraycopy(v, 0, out, i * 32, 32);
            }
            v = blake2b(length - 32 * rounds, v);
            System.arraycopy(v, 0, out, rounds * 32, v.length);
            return out;
        }
    }
}
