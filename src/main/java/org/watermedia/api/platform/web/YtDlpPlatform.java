package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.Track;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.binaries.YtDlpBinary;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.arr;
import static org.watermedia.tools.JSONTool.bool;
import static org.watermedia.tools.JSONTool.intOr;
import static org.watermedia.tools.JSONTool.longOr;
import static org.watermedia.tools.JSONTool.num;
import static org.watermedia.tools.JSONTool.obj;
import static org.watermedia.tools.JSONTool.str;

/**
 * Generic yt-dlp platform for every site whose extraction needs nothing beyond yt-dlp's defaults (SoundCloud,
 * Facebook, Instagram); a new site only needs its host in {@link #HOSTS}.
 * <p>
 * It is also the yt-dlp engine {@link YouTubePlatform} extends: it runs the binary the Binaries module provisions
 * ({@link YtDlpBinary}) out of process, so no yt-dlp failure touches the JVM, and maps its JSON into sources
 * ({@link #extract}). Each {@code formats[].url} is playable directly but bound to the {@code http_headers}
 * yt-dlp used, which the source carries so the player sends them too.
 */
public sealed class YtDlpPlatform implements Platform permits YouTubePlatform {
    private static final Marker IT = MarkerManager.getMarker(YtDlpPlatform.class.getSimpleName());
    public static final String NAME = "yt-dlp";
    // HOST SUFFIXES, MATCHED WITH A DOT BOUNDARY SO SUBDOMAINS (www./m./web.) ARE COVERED
    private static final String[] HOSTS = {
            "soundcloud.com", "snd.sc",     // SOUNDCLOUD
            "facebook.com", "fb.watch",     // FACEBOOK
            "instagram.com", "instagr.am",  // INSTAGRAM
    };

    private static final long PROCESS_TIMEOUT_SECONDS = 120; // SINGLE VIDEO ~SECONDS; PLAYLISTS CAN BE SLOWER
    private static final int RETRIES = 3; // yt-dlp'S OWN PER-REQUEST RETRIES
    // LIVE MANIFESTS ROTATE QUICKLY; A CDN URL WITHOUT AN expire PARAMETER IS KEPT FOR A FEW HOURS
    private static final Duration LIVE_TTL = Duration.ofMinutes(30);
    private static final Duration FALLBACK_TTL = Duration.ofHours(4);
    private static final Pattern RESOLUTION = Pattern.compile("(\\d+)p");
    private static final Pattern HEIGHT_IN_RES = Pattern.compile("\\d+x(\\d+)");

    private final YtDlpBinary binary = new YtDlpBinary();

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!WebRequests.hostMatches(uri.getHost(), HOSTS)) return null;
        // A BARE HOST NAMES NOTHING TO PLAY; SPAWNING yt-dlp TO FIND THAT OUT WOULD COST TWO MINUTES
        if (uri.getPath() == null || uri.getPath().length() < 2) throw PlatformException.unsupported(this.name(), uri);
        final JsonObject info;
        try {
            info = this.info(uri.toString(), List.of());
        } catch (final IOException e) {
            throw new PlatformException(this.name(), "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
        return this.extract(info);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    /** Runs yt-dlp with the configured network timeout; shorthand for {@link #info(String, List, int)}. */
    protected JsonObject info(final String url, final List<String> extraArgs) throws IOException {
        return this.info(url, extraArgs, WaterMediaConfig.network.timeout);
    }

    /** Runs yt-dlp on {@code url} (or a {@code ytsearchN:query} pseudo-URL) with a {@code timeoutMs} socket timeout per request and returns its parsed JSON; failures carry yt-dlp's last stderr line. */
    protected JsonObject info(final String url, final List<String> extraArgs, final int timeoutMs) throws IOException {
        final Path exe;
        try {
            exe = this.binary.executable();
        } catch (final IOException e) {
            throw new IOException("Failed to provision yt-dlp: " + e.getMessage(), e);
        }

        final List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.add("-J");
        command.add("--no-warnings");
        // HERMETIC: THE USER'S yt-dlp.conf NEVER APPLIES
        command.add("--ignore-config");
        command.add("--cache-dir");
        command.add(WaterMedia.tmp().resolve(WaterMediaBinaries.YTDLP_ID).resolve("cache").toString());
        // yt-dlp TAKES WHOLE SECONDS, SO THE TIMEOUT ROUNDS DOWN TO AT LEAST ONE
        command.add("--socket-timeout");
        command.add(String.valueOf(Math.max(1, timeoutMs / 1_000)));
        command.add("--retries");
        command.add(String.valueOf(RETRIES));
        if (extraArgs != null) command.addAll(extraArgs);
        command.add(url);

        final String stdout = runProcess(command, PROCESS_TIMEOUT_SECONDS, "yt-dlp");
        final JsonObject info;
        try {
            info = JSONTool.parse(stdout, JsonObject.class);
        } catch (final JsonParseException e) {
            throw new IOException("yt-dlp produced unparseable JSON", e);
        }
        if (info == null) throw new IOException("yt-dlp produced no JSON");
        return info;
    }

    /** Runs {@code command} as a child process bounded by {@code timeoutSeconds} and returns its stdout; a non-zero exit, timeout or interrupt throws with the child's last stderr line. */
    protected static String runProcess(final List<String> command, final long timeoutSeconds, final String tool) throws IOException {
        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final IOException e) {
            throw new IOException("Could not start " + tool, e);
        }

        // BOTH PIPES DRAIN ON THEIR OWN THREADS BEFORE THE WAIT, SO THE TIMEOUT BOUNDS THE CHILD: READING STDOUT HERE
        // WOULD BLOCK UNTIL THE CHILD CLOSED IT, AND A STUCK CHILD WOULD WEDGE A PlatformAPI SEARCH THREAD. A KILLED
        // CHILD CLOSES ITS PIPES, SO THE DRAINS REACH EOF; EACH BUFFER IS READ ONLY AFTER ITS join()
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        final Thread outDrain = ThreadTool.thread(tool + "-stdout", () -> out.append(readAll(process.getInputStream())));
        final Thread errDrain = ThreadTool.thread(tool + "-stderr", () -> err.append(readAll(process.getErrorStream())));

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(tool + " timed out after " + timeoutSeconds + "s");
            }
            // THE CHILD HAS EXITED, SO ITS STDOUT/STDERR ARE AT EOF AND THE JOINS RETURN ONCE EACH DRAIN
            // FINISHES READING; THEY STAY UNBOUNDED SO LARGE OUTPUTS LIKE BIG PLAYLISTS ARE NEVER TRUNCATED
            outDrain.join();
            errDrain.join();
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + tool, e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            final String stderr = err.toString().trim();
            throw new IOException(tool + " exited " + exit + ": " + (stderr.isEmpty() ? "(no stderr)" : stderr.lines().reduce((a, b) -> b).orElse(stderr)));
        }
        return out.toString();
    }

    /** Builds the {@link Resolution} from yt-dlp's JSON, one source per media object or playlist entry; failures carry {@link #name()}, so YouTube names itself. */
    protected Resolution extract(final JsonObject info) throws PlatformException {
        try {
            final JsonArray entries = arr(info, "entries");
            if (entries == null) {
                final Result r = this.single(info);
                return new Resolution(r.expires(), r.source());
            }

            // ONE BAD ITEM MUST NOT TAKE THE PLAYLIST DOWN; A PLAYLIST WITH NOTHING LEFT REPORTS ITS FIRST
            // FAILURE, SO A FULLY AGE-RESTRICTED ONE STAYS BLOCKED
            final List<Source> sources = new ArrayList<>();
            PlatformException skipped = null;
            Instant earliest = null;
            for (final JsonElement e: entries) {
                // yt-dlp NULLS OUT UNAVAILABLE PLAYLIST ITEMS
                final JsonObject entry = obj(e);
                if (entry == null) continue;
                try {
                    final Result r = this.single(entry);
                    sources.add(r.source());
                    if (earliest == null || (r.expires() != null && r.expires().isBefore(earliest))) earliest = r.expires();
                } catch (final PlatformException | RuntimeException failure) {
                    LOGGER.warn(IT, "{} skipped a playlist entry", this.name(), failure);
                    if (skipped == null) skipped = failure instanceof final PlatformException platform ? platform
                            : new PlatformException(this.name(), "Unexpected yt-dlp JSON for a playlist entry", failure);
                }
            }
            if (sources.isEmpty()) throw skipped != null ? skipped : new PlatformException(this.name(), "No playable entries in playlist");
            return new Resolution(earliest, sources);
        } catch (final RuntimeException e) {
            // A JSON SHAPE yt-dlp NEVER DOCUMENTED
            throw new PlatformException(this.name(), "Unexpected yt-dlp JSON", e);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Result single(final JsonObject media) throws PlatformException {
        // RESPECT THE MATURE-CONTENT GATE (yt-dlp REPORTS age_limit; >=18 IS AGE-RESTRICTED)
        if (intOr(media, "age_limit", 0) >= 18 && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(this.name(), "Age-restricted: " + str(media, "webpage_url"));

        final JsonArray formats = arr(media, "formats");
        if (formats == null || formats.isEmpty()) throw new PlatformException(this.name(), "No formats for " + str(media, "webpage_url"));

        final URI sourceUri = DataTool.uri(str(media, "webpage_url"));
        final boolean live = bool(media, "is_live") || "is_live".equals(str(media, "live_status"));
        final URI thumbnail = DataTool.uri(str(media, "thumbnail"));
        final Metadata metadata = metadata(media);
        final List<Track> subtitles = subtitles(media);

        // SPLIT FORMATS: VIDEO-ONLY (acodec none), MUXED (BOTH), AUDIO-ONLY (vcodec none); STORYBOARDS ARE SKIPPED
        final List<Quality> variants = new ArrayList<>();
        final Set<Integer> seenHeights = new HashSet<>();
        JsonObject headerSource = null;
        // THE BEST AUDIO-ONLY FORMAT PER language ("" WITHOUT ONE), SO EVERY DUB OF A VIDEO SURVIVES
        final Map<String, JsonObject> audios = new LinkedHashMap<>();

        // VIDEO-ONLY FIRST (BEST QUALITY, AUDIO CARRIED BY A TRACK), THEN MUXED FILLS REMAINING TIERS
        for (int pass = 0; pass < 2; pass++) {
            for (final JsonElement fe: formats) {
                // A FORMAT WITHOUT A URL, A STORYBOARD (mhtml) OR ONE WITHOUT ANY CODEC CARRIES NO MEDIA
                final JsonObject f = obj(fe);
                if (f == null || str(f, "url") == null || "mhtml".equals(str(f, "protocol"))) continue;
                if (f.get("vcodec") == null && f.get("acodec") == null) continue;
                final boolean hasVideo = !"none".equals(str(f, "vcodec"));
                final boolean hasAudio = !"none".equals(str(f, "acodec"));
                if (!hasVideo && !hasAudio) continue;

                if (!hasVideo) {
                    if (pass == 0) audios.merge(Objects.requireNonNullElse(str(f, "language"), ""), f, (kept, next) -> bitrate(next) > bitrate(kept) ? next : kept);
                    continue;
                }
                final boolean videoOnly = !hasAudio;
                if ((pass == 0) != videoOnly) continue;
                final int height = heightOf(f);
                if (height > 0 && !seenHeights.add(height)) continue;
                final URI url = DataTool.uri(str(f, "url"));
                if (url == null) continue;
                // PER Quality'S CONTRACT, PASS 0 FOR AN UNKNOWN DIMENSION; NEVER FABRICATE A 16:9 WIDTH
                final int width = Math.max(0, intOr(f, "width", 0));
                variants.add(new Quality(url, width, height, (float) Math.max(0d, num(f, "fps"))));
                if (headerSource == null) headerSource = f;
            }
        }

        // ONE AUDIO PER LANGUAGE, THE ORIGINAL FIRST: yt-dlp RANKS LANGUAGES BY language_preference (YOUTUBE: 10 ORIGINAL, 5
        // DEFAULT, -1 OTHER, -10 DESCRIPTIVE), THEN THE BITRATE BREAKS TIES. ONCE ANY FORMAT NAMES ITS LANGUAGE, ONE THAT NAMES
        // NONE IS THE SAME AUDIO THROUGH ANOTHER PROTOCOL, NOT A TRACK OF ITS OWN
        if (audios.size() > 1) audios.remove("");
        final List<JsonObject> ranked = new ArrayList<>(audios.values());
        ranked.sort(Comparator.comparingInt((final JsonObject a) -> intOr(a, "language_preference", -1))
                .thenComparingDouble(YtDlpPlatform::bitrate).reversed());
        final JsonObject bestAudio = ranked.isEmpty() ? null : ranked.get(0);
        final HttpHeaders headers = headers(headerSource != null ? headerSource : bestAudio, sourceUri);

        if (!variants.isEmpty()) {
            final List<Track> audioTracks = new ArrayList<>();
            for (final JsonObject audio: ranked) {
                final URI url = DataTool.uri(str(audio, "url"));
                if (url == null) continue;
                // YOUTUBE STARTS format_note WITH THE TRACK'S DISPLAY NAME ("English (US) original (default), medium"): IT KEEPS
                // THAT HEAD WITHOUT yt-dlp'S FLAGS, AND NONE FOR A NOTE THAT IS ONLY A QUALITY ("medium", AS WITHOUT LANGUAGES)
                final String lang = str(audio, "language");
                final String note = str(audio, "format_note");
                String name = lang == null || note == null ? "" : note.split(",", 2)[0].strip();
                while (name.endsWith(" (default)") || name.endsWith(" (original)")) name = name.substring(0, name.lastIndexOf(" (")).strip();
                if (name.isEmpty() || DataTool.equalsAnyIgnoreCase(name, "ultralow", "low", "medium", "high")) name = null;
                audioTracks.add(new Track(name, lang, url));
            }
            final Source source = new Source(MediaType.VIDEO, thumbnail, metadata, headers, variants, audioTracks, subtitles);
            return new Result(expiry(variants.get(0).uri(), live), source);
        }

        // AUDIO-ONLY (SOUNDCLOUD, YOUTUBE MUSIC): THE ORIGINAL LANGUAGE'S BEST FORMAT
        if (bestAudio != null) {
            final URI audio = DataTool.uri(str(bestAudio, "url"));
            if (audio != null) {
                final Source source = new Source(MediaType.AUDIO, thumbnail, metadata, headers,
                        List.of(new Quality(audio, 0, 0)), null, subtitles);
                return new Result(expiry(audio, live), source);
            }
        }

        throw new PlatformException(this.name(), "No usable streams for " + str(media, "webpage_url"));
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static String readAll(final InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return "";
        }
    }

    // A FORMAT'S AUDIO BITRATE, ELSE ITS TOTAL ONE; 0 WHEN yt-dlp KNOWS NEITHER, AS ON HLS AUDIO RENDITIONS
    private static double bitrate(final JsonObject format) {
        final double abr = num(format, "abr");
        return abr > 0 ? abr : num(format, "tbr");
    }

    private static int heightOf(final JsonObject f) {
        // A MISSING OR UNUSABLE height FALLS BACK TO THE resolution AND format_note TEXT
        final int h = intOr(f, "height", -1);
        if (h >= 0) return h;
        final String res = str(f, "resolution");
        if (res != null) {
            final Matcher m = HEIGHT_IN_RES.matcher(res);
            if (m.find()) return DataTool.parseInt(m.group(1), 0);
        }
        final String note = str(f, "format_note");
        if (note != null) {
            final Matcher m = RESOLUTION.matcher(note);
            if (m.find()) return DataTool.parseInt(m.group(1), 0);
        }
        return 0;
    }

    // yt-dlp'S PER-FORMAT http_headers (User-Agent ETC.), WHICH THE PLAYER MUST SEND: THE GOOGLEVIDEO URLS ANSWER 403
    // WITHOUT THE MATCHING UA. A FORMAT WITHOUT THEM FALLS BACK TO THE WATERMEDIA DEFAULTS
    private static HttpHeaders headers(final JsonObject format, final URI sourceUri) {
        final JsonObject hh = obj(format, "http_headers");
        if (hh == null || hh.size() == 0) return sourceUri == null ? new HttpHeaders() : HttpHeaders.defaults(sourceUri);
        final HttpHeaders headers = new HttpHeaders();
        for (final String name: hh.keySet()) {
            final String value = str(hh, name);
            if (value != null) headers.set(name, value);
        }
        return headers;
    }

    private static Metadata metadata(final JsonObject media) {
        final String title = str(media, "title");
        String author = str(media, "uploader");
        if (author == null) author = str(media, "channel");
        final String description = str(media, "description");
        final long durationMs = (long) Math.max(0d, num(media, "duration") * 1_000d);

        // THE EXACT timestamp WINS; upload_date (YYYYMMDD) ONLY NAMES THE DAY
        Instant publishedAt = null;
        final long timestamp = longOr(media, "timestamp", Long.MIN_VALUE);
        final String date = str(media, "upload_date");
        if (timestamp != Long.MIN_VALUE) publishedAt = Instant.ofEpochSecond(timestamp);
        else if (date != null) {
            try { publishedAt = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneOffset.UTC).toInstant(); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "yt-dlp unparseable upload_date '{}'", date, e); }
        }
        return new Metadata(title, description, publishedAt, durationMs, author);
    }

    // BUILDS SUBTITLE TRACKS FROM subtitles{} (MANUAL) THEN automatic_captions{} (AUTO), ONE PER LANGUAGE
    private static List<Track> subtitles(final JsonObject media) {
        final List<Track> out = new ArrayList<>();
        final Set<String> langs = new HashSet<>();
        collectSubs(obj(media, "subtitles"), false, langs, out);
        collectSubs(obj(media, "automatic_captions"), true, langs, out);
        return out.isEmpty() ? null : out;
    }

    private static void collectSubs(final JsonObject subs, final boolean auto,
                                    final Set<String> langs, final List<Track> out) {
        if (subs == null) return;
        for (final String lang: subs.keySet()) {
            final JsonArray tracks = arr(subs, lang);
            if (tracks == null || tracks.isEmpty() || !langs.add(lang)) continue;
            final JsonObject track = obj(tracks.get(0));
            final URI url = DataTool.uri(str(track, "url"));
            if (url == null) continue;
            String name = str(track, "name");
            if (name == null) name = lang;
            if (auto) name = name + " (auto-generated)";
            out.add(new Track(name, lang, url));
        }
    }

    // READS THE expire=<epoch> PARAM FROM A GOOGLEVIDEO/CDN URL; LIVE OR MISSING → SHORT/FALLBACK TTL
    private static Instant expiry(final URI uri, final boolean live) {
        if (live) return Instant.now().plus(LIVE_TTL);
        final Instant signed = DataTool.expiry(uri);
        return signed != null ? signed : Instant.now().plus(FALLBACK_TTL);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // PAIRS A RESOLVED SOURCE WITH ITS EXPIRATION
    private record Result(Instant expires, Source source) {}
}
