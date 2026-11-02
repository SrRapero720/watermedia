package org.watermedia.tools;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Parser of the M3U family: {@link #parse(String, URI)} classifies a body as an HLS {@link Master} (variant ladder), an
 * HLS {@link Media} playlist (segments, live or VOD) or a flat {@link Iptv} catalog, in that order of precedence.
 * {@link #parse} and {@link #fetch} throw {@link IOException} naming the source and the defect; {@link #qualities}
 * never throws and falls back to the raw URL. Entry URLs come back absolute; malformed ones and local files or
 * archives named by a remote playlist are skipped.
 */
public final class M3UTool {
    private static final Marker IT = MarkerManager.getMarker(M3UTool.class.getSimpleName());

    // ATTRIBUTE PATTERN COVERS BOTH HLS (UPPERCASE KEYS, QUOTED OR BARE VALUES)
    // AND IPTV (LOWERCASE tvg-* KEYS, ALWAYS QUOTED VALUES)
    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9-]+)=(?:\"([^\"]*)\"|([^,\\s]+))");
    private static final Pattern RES = Pattern.compile("(\\d+)x(\\d+)");

    private M3UTool() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Fetches and parses the playlist at {@code uri} with WaterMedia's User-Agent; see {@link #parse}. */
    public static Playlist fetch(final URI uri) throws IOException {
        return fetch(uri, null);
    }

    /** Fetches and parses the playlist at {@code uri} with the headers its media request carries, each replacing the request default; a status other than 200 throws. */
    public static Playlist fetch(final URI uri, final HttpHeaders headers) throws IOException {
        // THE CONFIGURED network.timeout APPLIES, LIKE EVERY OTHER REQUEST
        try (final NetRequest req = new NetRequest(uri).headers(headers).send()) {
            final int code = req.statusCode();
            if (code != 200)
                throw new IOException("Cannot fetch playlist from " + uri + ": server returned HTTP " + code + " (expected 200 OK)");
            return parse(req.text(), uri);
        }
    }

    /** Parses a playlist body (a leading BOM is tolerated) whose entries resolve against {@code source}; blank text, no {@code #EXTM3U} or no known tag throws. */
    public static Playlist parse(final String raw, final URI source) throws IOException {
        if (raw == null || raw.isBlank())
            throw new IOException("Empty playlist from " + source + ": the response body was null or blank, nothing to parse");

        // STRIP A LEADING UTF-8 BOM (SOME IPTV PROVIDERS EMIT IT) BEFORE LOOKING FOR THE HEADER
        final String content = raw.charAt(0) == '\uFEFF' ? raw.substring(1) : raw;

        final String head = content.stripLeading();
        if (!head.startsWith("#EXTM3U")) {
            // SHORT, SINGLE-LINE EXCERPT OF THE BODY FOR THE ERROR MESSAGE
            final String preview = head.substring(0, Math.min(32, head.length())).replace('\n', ' ').replace('\r', ' ');
            throw new IOException("Invalid playlist from " + source + ": missing the mandatory #EXTM3U header (body starts with '"
                    + preview + "'), so it is not an M3U/M3U8 document");
        }

        // STREAM-INF WINS (MASTER); ANY OTHER #EXT-X- MARKS A MEDIA PLAYLIST; ONLY A TAG-LESS #EXTINF LIST IS IPTV
        if (content.contains("#EXT-X-STREAM-INF")) return parseMaster(content, source);
        if (content.contains("#EXT-X-"))           return parseMedia(content, source);
        if (content.contains("#EXTINF:"))          return parseIptv(content, source);

        throw new IOException("Unrecognized playlist from " + source
                + ": has #EXTM3U but none of #EXT-X-STREAM-INF (master), #EXT-X-* (media) or #EXTINF (iptv), so its shape is unknown");
    }

    /** Returns the variants of an HLS master fetched with the stream's {@code headers}, or one variant of {@code source} itself for a media playlist or any failure; never throws. */
    public static List<Variant> qualities(final URI source, final HttpHeaders headers) {
        try {
            final Playlist playlist = fetch(source, headers);
            if (playlist instanceof final Master master && !master.variants().isEmpty()) {
                LOGGER.debug(IT, "Successfully resolved {} HLS rendition(s) from master {}", master.variants().size(), source);
                return master.variants();
            }
            if (playlist instanceof Iptv)
                LOGGER.warn(IT, "Expected an HLS stream but {} is an IPTV playlist; falling back to the raw URL", source);
            // MEDIA PLAYLIST, OR MASTER WITH NO USABLE VARIANTS: THE URL ITSELF IS THE ONLY QUALITY
            return List.of(Variant.self(source));
        } catch (final IOException e) {
            LOGGER.warn(IT, "Failed to resolve HLS qualities for {}; falling back to the raw URL", source, e);
            return List.of(Variant.self(source));
        }
    }

    // ==========================================================================
    // PARSERS: ONE indexOf PASS OVER THE BODY EACH, NO REGEX SPLIT OR String[]
    // ==========================================================================
    private static Master parseMaster(final String content, final URI source) {
        final List<Variant> variants = new ArrayList<>();
        final List<Rendition> renditions = new ArrayList<>();
        final Map<String, Rendition> videoGroups = new HashMap<>();
        final boolean[] warned = new boolean[1];
        int version = 1;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-VERSION:")) version = DataTool.parseInt(line.substring(15), 1);
            else if (line.startsWith("#EXT-X-MEDIA:")) {
                final Map<String, String> a = attributes(line.substring(13), false);
                final String type = a.get("TYPE");
                final String groupId = a.get("GROUP-ID");
                if (type != null && groupId != null) {
                    final Rendition r = new Rendition(
                            type, groupId,
                            a.getOrDefault("NAME", ""),
                            a.get("LANGUAGE"),
                            resolve(source, a.get("URI"), warned),
                            "YES".equalsIgnoreCase(a.get("DEFAULT")),
                            "YES".equalsIgnoreCase(a.get("AUTOSELECT")));
                    renditions.add(r);
                    if ("VIDEO".equalsIgnoreCase(type)) videoGroups.put(groupId, r);
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // PEEK THE FIRST NON-COMMENT, NON-EMPTY LINE AS THE VARIANT URI (NULL IF A TAG IS HIT FIRST);
                // THE MAIN LOOP THEN SKIPS THAT LINE SINCE IT MATCHES NO TAG
                String peeked = null;
                int j = i;
                while (j < len) {
                    final int pnl = content.indexOf('\n', j);
                    final int pend = pnl < 0 ? len : pnl;
                    final String pl = content.substring(j, pend).trim();
                    j = pnl < 0 ? len : pnl + 1;
                    if (pl.isEmpty()) continue;
                    if (pl.startsWith("#EXT-X-") || pl.startsWith("#EXTINF")) break;
                    if (pl.charAt(0) == '#') continue;
                    peeked = pl;
                    break;
                }
                final URI uri = resolve(source, peeked, warned);
                if (uri != null) {
                    final Map<String, String> a = attributes(line.substring(18), false);
                    int w = 0, h = 0;
                    final String res = a.get("RESOLUTION");
                    if (res != null) {
                        final Matcher m = RES.matcher(res);
                        if (m.find()) {
                            w = DataTool.parseInt(m.group(1), 0);
                            h = DataTool.parseInt(m.group(2), 0);
                        }
                    }

                    final String videoGroup = a.get("VIDEO");
                    String name = "";
                    if (videoGroup != null) {
                        final Rendition r = videoGroups.get(videoGroup);
                        if (r != null) name = r.name();
                    }

                    // A MISSING, NEGATIVE, NaN OR OVERSIZED FRAME-RATE IS UNKNOWN, WHICH THE QUALITY RECORDS READ AS ZERO
                    final double fps = DataTool.parseDouble(a.get("FRAME-RATE"), 0d);
                    variants.add(new Variant(
                            uri,
                            DataTool.parseLong(a.get("BANDWIDTH"), 0),
                            w, h,
                            fps > 0d && fps <= Float.MAX_VALUE ? fps : 0d,
                            a.get("CODECS"),
                            videoGroup,
                            a.get("AUDIO"),
                            name));
                }
            }
        }

        return new Master(source, version, List.copyOf(variants), List.copyOf(renditions));
    }

    private static Media parseMedia(final String content, final URI source) {
        final List<Segment> segments = new ArrayList<>();
        final boolean[] warned = new boolean[1];
        double targetDuration = 0;
        long sequence = 0;
        int version = 1;
        boolean vod = false;
        boolean endList = false;
        double totalDuration = 0;

        double segDuration = 0;
        String segTitle = "";
        String dateTime = null;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-VERSION:")) version = DataTool.parseInt(line.substring(15), 1);
            else if (line.startsWith("#EXT-X-TARGETDURATION:")) targetDuration = DataTool.parseDouble(line.substring(22), 0);
            else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = DataTool.parseLong(line.substring(22), 0);
            else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) vod = "VOD".equalsIgnoreCase(line.substring(21).trim());
            else if (line.equals("#EXT-X-ENDLIST")) endList = true;
            else if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) dateTime = line.substring(25).trim();
            else if (line.startsWith("#EXTINF:")) {
                final String extinf = line.substring(8);
                final int comma = extinf.indexOf(',');
                segDuration = DataTool.parseDouble(comma >= 0 ? extinf.substring(0, comma) : extinf, 0);
                segTitle = comma >= 0 ? extinf.substring(comma + 1).trim() : "";
            } else if (line.charAt(0) != '#' && segDuration > 0) {
                final URI uri = resolve(source, line, warned);
                if (uri != null) {
                    segments.add(new Segment(uri, segDuration, segTitle, sequence + segments.size(), dateTime));
                    totalDuration += segDuration;
                }
                segDuration = 0;
                segTitle = "";
                dateTime = null;
            }
        }

        return new Media(source, version, List.copyOf(segments), targetDuration, sequence, !endList, vod, totalDuration);
    }

    private static Iptv parseIptv(final String content, final URI source) {
        final List<Channel> channels = new ArrayList<>();
        final boolean[] warned = new boolean[1];
        String title = null;
        Map<String, String> attrs = null;

        final int len = content.length();
        int i = 0;
        while (i < len) {
            final int nl = content.indexOf('\n', i);
            final int end = nl < 0 ? len : nl;
            final String line = content.substring(i, end).trim();
            i = nl < 0 ? len : nl + 1;
            if (line.isEmpty() || line.startsWith("#EXTM3U")) continue;

            if (line.startsWith("#EXTINF:")) {
                // FORMAT: #EXTINF:<DURATION> [attr="value"]* ,TITLE
                final String text = line.substring(8);
                // FIND THE LAST UNQUOTED COMMA THAT SEPARATES ATTRS FROM TITLE
                int commaIdx = -1;
                boolean inQuotes = false;
                for (int k = 0; k < text.length(); k++) {
                    final char c = text.charAt(k);
                    if (c == '"') inQuotes = !inQuotes;
                    else if (c == ',' && !inQuotes) commaIdx = k;
                }
                final String header = commaIdx >= 0 ? text.substring(0, commaIdx) : text;
                title = commaIdx >= 0 ? text.substring(commaIdx + 1).trim() : null;

                attrs = attributes(header, true);
                continue;
            }

            if (line.charAt(0) == '#') continue;

            // PAYLOAD LINE: FLUSH THE PENDING ENTRY (OR A BARE-URL ONE)
            final URI url = resolve(source, line, warned);
            if (url != null) {
                final Map<String, String> a = attrs != null ? attrs : Map.of();
                final String name = title != null && !title.isEmpty() ? title : a.getOrDefault("tvg-name", line);
                channels.add(new Channel(name, url,
                        a.get("tvg-id"),
                        a.get("tvg-name"),
                        a.get("tvg-logo"),
                        a.get("tvg-country"),
                        a.get("group-title"),
                        a.isEmpty() ? Map.of() : Map.copyOf(a)));
            }
            title = null;
            attrs = null;
        }

        return new Iptv(source, List.copyOf(channels));
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // KEY=VALUE / KEY="VALUE" PAIRS OF A TAG BODY; IPTV KEYS ARE LOWER-CASED FOR STABLE tvg-* LOOKUPS
    private static Map<String, String> attributes(final String body, final boolean lower) {
        final Map<String, String> attrs = new HashMap<>();
        final Matcher m = ATTR.matcher(body);
        while (m.find()) attrs.put(lower ? m.group(1).toLowerCase(Locale.ROOT) : m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        return attrs;
    }

    // RESOLVES spec AGAINST base: ANY STREAMING SCHEME FFMPEG SPEAKS PASSES, BUT ONLY A LOCAL PLAYLIST MAY LIST LOCAL
    // FILES AND NONE MAY LIST ARCHIVES. A SKIPPED ENTRY YIELDS NULL; warned LOGS ONLY THE FIRST ONE OF A PLAYLIST
    private static URI resolve(final URI base, final String spec, final boolean[] warned) {
        if (spec == null || spec.isEmpty()) return null;
        try {
            final URI resolved = base.resolve(spec);
            final String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            final boolean allowed = IOTool.web(resolved) || (!scheme.isEmpty() && !scheme.equals("jar") && !scheme.equals("water")
                    && (!scheme.equals("file") || "file".equalsIgnoreCase(base.getScheme())));
            if (allowed) return resolved;
            if (!warned[0]) LOGGER.warn(IT, "Skipping local or relative URI '{}' in {}", spec, base);
        } catch (final IllegalArgumentException e) {
            if (!warned[0]) LOGGER.warn(IT, "Skipping malformed URI '{}' relative to {}", spec, base);
        }
        warned[0] = true;
        return null;
    }

    // ==========================================================================
    // NESTED TYPES: PLAYLISTS
    // ==========================================================================
    /** A parsed playlist of one of the three M3U shapes. */
    public sealed interface Playlist permits Master, Media, Iptv {
        /** The URI this playlist was parsed from; entry URLs are already resolved against it. */
        URI source();

        /** Lower-case discriminator ({@code "master"}, {@code "media"}, {@code "iptv"}). */
        String kind();
    }

    /** HLS master playlist: a quality ladder ({@code variants}) plus alternate {@code renditions}. */
    public record Master(URI source, int version, List<Variant> variants, List<Rendition> renditions) implements Playlist {
        @Override public String kind() { return "master"; }
    }

    /** HLS media playlist: ordered {@code segments} plus live/VOD timing metadata. */
    public record Media(URI source, int version, List<Segment> segments, double targetDuration,
                        long sequence, boolean live, boolean vod, double totalDuration) implements Playlist {
        @Override public String kind() { return "media"; }
    }

    /** Flat IPTV catalog: a list of independent {@code channels}. */
    public record Iptv(URI source, List<Channel> channels) implements Playlist {
        @Override public String kind() { return "iptv"; }
    }

    // ==========================================================================
    // NESTED TYPES: DATA RECORDS
    // ==========================================================================
    /** One rendition of a {@link Master}'s quality ladder. URL is absolute. */
    public record Variant(URI uri, long bandwidth, int width, int height, double fps,
                          String codecs, String videoGroup, String audioGroup, String name) {
        /** A metadata-less variant that just points at a stream URL (media playlist / fallback). */
        public static Variant self(final URI uri) {
            return new Variant(uri, 0L, 0, 0, 0d, null, null, null, null);
        }
    }

    /** One segment of a {@link Media} playlist. URL is absolute. */
    public record Segment(URI uri, double duration, String title, long sequence, String dateTime) {}

    /** An alternate {@code #EXT-X-MEDIA} rendition (audio/subtitle/video group); {@code preferred} mirrors {@code DEFAULT=YES}. URI may be null. */
    public record Rendition(String type, String groupId, String name, String language, URI uri,
                            boolean preferred, boolean autoSelect) {}

    /**
     * A single channel entry parsed from an IPTV M3U.
     *
     * @param title       trailing label, or {@code tvg-name}/URL when missing
     * @param uri         the stream URI (absolute)
     * @param tvgId       optional {@code tvg-id} (EPG cross-reference)
     * @param tvgName     optional {@code tvg-name}
     * @param tvgLogo     optional {@code tvg-logo} (channel icon URL, passed through verbatim)
     * @param tvgCountry  optional {@code tvg-country} ISO code
     * @param tvgGroup    optional {@code group-title}
     * @param attrs       unmodifiable view of every raw {@code key="value"} attribute, lower-cased
     */
    public record Channel(String title, URI uri, String tvgId, String tvgName, String tvgLogo,
                          String tvgCountry, String tvgGroup, Map<String, String> attrs) {}
}