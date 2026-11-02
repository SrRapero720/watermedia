package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.util.NetRequest;
import org.watermedia.binaries.BotGuardBinary;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.arr;
import static org.watermedia.tools.JSONTool.obj;
import static org.watermedia.tools.JSONTool.str;

/**
 * YouTube platform on the {@link YtDlpPlatform} engine, plus what YouTube alone needs: a retry of the "Sign in to
 * confirm you're not a bot" gate with a BotGuard po_token for the web-family clients, bound to a fresh InnerTube
 * {@code visitorData}. The token comes from rustypipe-botguard ({@link BotGuardBinary}), run out of process so a
 * crash in its bundled JS engine never takes down the JVM.
 */
public final class YouTubePlatform extends YtDlpPlatform {
    private static final Marker IT = MarkerManager.getMarker(YouTubePlatform.class.getSimpleName());
    public static final String NAME = "YouTube";
    private static final String[] HOSTS = { "youtube.com", "youtu.be" };
    // SITE PAGES WITHOUT MEDIA; CHANNELS AND PLAYLISTS STAY PLAYABLE AS PLAYLISTS
    private static final String[] UNPLAYABLE_PATHS = { "/results", "/feed/", "/account", "/premium", "/t/", "/howyoutubeworks" };
    // VIDEO SHAPES, AS OPPOSED TO A PURE PLAYLIST LINK: DECIDES WHETHER TO PASS --no-playlist
    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)", Pattern.CASE_INSENSITIVE);

    private static final long BOTGUARD_TIMEOUT_SECONDS = 60; // COLD START RUNS THE BOTGUARD VM + NETWORK

    // INNERTUBE visitor_id ENDPOINT: A po_token MUST BE BOUND TO THE visitorData THEN HANDED TO yt-dlp
    private static final URI VISITOR_ENDPOINT = URI.create("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false");
    private static final String CLIENT_VERSION = "2.20250601.01.00"; // WEB CLIENT; THE ENDPOINT IS LENIENT
    private static final String VISITOR_BODY =
            "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"" + CLIENT_VERSION
            + "\",\"hl\":\"en\",\"gl\":\"US\"}}}";

    private final BotGuardBinary botGuard = new BotGuardBinary();
    private final Object botGuardLock = new Object(); // SERIALISE MINTS: THE BOTGUARD RUNS SHARE A SNAPSHOT FILE

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!WebRequests.hostMatches(uri.getHost(), HOSTS)) return null;
        // A BARE HOST, A SEARCH PAGE OR A FEED NAMES NOTHING TO PLAY; SPAWNING yt-dlp TO FIND THAT OUT WOULD COST TWO MINUTES
        final String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.length() < 2 || DataTool.startsWith(path, UNPLAYABLE_PATHS)) throw PlatformException.unsupported(NAME, uri);

        // A VIDEO LINK PLAYS THE VIDEO (EVEN IF IT CARRIES &list=); A PURE PLAYLIST LINK PLAYS THE PLAYLIST
        final List<String> args = new ArrayList<>();
        if (YOUTUBE_VIDEO_ID.matcher(uri.toString()).find()) args.add("--no-playlist");

        final JsonObject info;
        try {
            info = this.info(uri.toString(), args);
        } catch (final IOException e) {
            final PlatformException gate = ageGate(uri, e);
            if (gate != null) throw gate;

            // yt-dlp'S MESSAGES FOR THE BOT GATE THAT A po_token CAN BYPASS
            final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("not a bot") || message.contains("sign in to confirm") || message.contains("po_token"))
                return this.retryWithPoToken(uri, args);
            throw new PlatformException(NAME, "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
        return this.extract(info);
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        // ytsearchN: HITS ONLY YOUTUBE'S SEARCH ENDPOINT; --flat-playlist KEEPS IT TO RAW RESULT METADATA
        // (NO PER-VIDEO STREAM EXTRACTION), SO IT IS FAST AND NEEDS NO PO_TOKEN/BOTGUARD RETRY
        final JsonObject info;
        try {
            info = this.info("ytsearch" + limit + ":" + query, List.of("--flat-playlist"), PlatformAPI.SEARCH_TIMEOUT_MS);
        } catch (final IOException e) {
            throw new PlatformException(NAME, "yt-dlp could not search '" + query + "': " + e.getMessage(), e);
        }

        final JsonArray entries = arr(info, "entries");
        if (entries == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(entries.size(), limit));
        for (final JsonElement element: entries) {
            final JsonObject entry = obj(element);
            // A SEARCH ALSO SURFACES PLAYLISTS/CHANNELS (ie_key YoutubeTab); KEEP ONLY PLAYABLE VIDEOS
            if (!"Youtube".equals(str(entry, "ie_key"))) continue;

            final String id = str(entry, "id");
            final String url = str(entry, "url");
            if (id == null && url == null) continue;

            // DERIVE THE THUMBNAIL FROM THE VIDEO ID: hqdefault EXISTS FOR EVERY VIDEO, UNLIKE THE
            // VERSION-DEPENDENT (AND OFTEN EXPIRING) thumbnails[] URLS yt-dlp EMITS IN FLAT MODE
            final URI thumbnail = id != null ? DataTool.uri("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg") : null;
            // yt-dlp'S FLAT-PLAYLIST url CAN BE A BARE VIDEO ID (NO SCHEME, WHICH DataTool.uri REJECTS);
            // THEN THE WATCH URL IS BUILT FROM THE ID SO SELECTING THE RESULT PLAYS IT INSTEAD OF RE-SEARCHING
            final URI parsed = DataTool.uri(url);
            final URI page = parsed != null ? parsed : id != null ? DataTool.uri("https://www.youtube.com/watch?v=" + id) : null;
            if (page == null) continue;
            out.add(new PlatformResult(this, str(entry, "title"), thumbnail, page));
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // RETRIES WITH A po_token BOUND TO A FRESH visitorData FOR EVERY WEB-FAMILY gvs CONTEXT; yt-dlp KEEPS ITS DEFAULT
    // CLIENTS (FORCING THE WEB ONES YIELDS NO FORMATS). A FAILED RETRY REPORTS ITS CAUSE; AN EXTRACTION VERDICT PASSES THROUGH
    private Resolution retryWithPoToken(final URI uri, final List<String> baseArgs) throws PlatformException {
        LOGGER.warn(IT, "YouTube bot check hit for '{}'; retrying with a BotGuard po_token", uri);
        final JsonObject info;
        try {
            final String visitorData = visitorData();
            final String token = this.mintToken(visitorData);
            final List<String> args = new ArrayList<>(baseArgs);
            args.add("--extractor-args");
            args.add("youtube:po_token=web.gvs+" + token + ",web_safari.gvs+" + token
                    + ",mweb.gvs+" + token + ",tv.gvs+" + token
                    + ";visitor_data=" + visitorData + ";player_skip=webpage,configs");
            args.add("--extractor-args");
            args.add("youtubetab:skip=webpage");
            info = this.info(uri.toString(), args);
        } catch (final IOException retry) {
            final PlatformException gate = ageGate(uri, retry);
            if (gate != null) throw gate;
            throw new PlatformException(NAME, "YouTube blocked this request and the po_token retry failed: " + retry.getMessage(), retry);
        } catch (final RuntimeException retry) {
            throw new PlatformException(NAME, "YouTube blocked this request and the po_token retry failed: " + retry.getMessage(), retry);
        }
        return this.extract(info);
    }

    // MINTS A po_token FOR visitorData WITH rustypipe-botguard, WHOSE ONLY OUTPUT LINE STARTS WITH THE TOKEN. RUNS ARE
    // SERIALISED (THEY SHARE --snapshot-file) AND NEVER CACHED: EVERY CALL BRINGS A FRESH visitorData
    private String mintToken(final String visitorData) throws IOException {
        final Path exe;
        final Path snapshot;
        try {
            exe = this.botGuard.executable();
            snapshot = this.botGuard.snapshot();
        } catch (final IOException e) {
            throw new IOException("Failed to provision rustypipe-botguard: " + e.getMessage(), e);
        }

        final List<String> command = List.of(exe.toString(), "--snapshot-file", snapshot.toString(), "--", visitorData);
        final String stdout;
        synchronized (this.botGuardLock) {
            stdout = runProcess(command, BOTGUARD_TIMEOUT_SECONDS, "rustypipe-botguard");
        }

        for (final String line: stdout.split("\\R")) {
            if (!line.isBlank()) {
                final String token = line.trim().split("\\s+")[0];
                if (!token.isEmpty()) return token;
            }
        }
        throw new IOException("rustypipe-botguard produced no token");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE AGE GATE NEEDS A SIGNED-IN SESSION NO TOKEN CAN REPLACE: A MATURE BLOCK WHEN MATURE CONTENT IS OFF, A PLAIN
    // FAILURE OTHERWISE, AND null WHEN yt-dlp'S MESSAGE IS NOT AN AGE GATE
    private static PlatformException ageGate(final URI uri, final IOException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("confirm your age") && !message.contains("age-restricted") && !message.contains("inappropriate for some users"))
            return null;
        if (!WaterMediaConfig.platforms.allowMatureContent) return new MatureContentException(NAME, "Age-restricted: " + uri, e);
        return new PlatformException(NAME, "Age-restricted video " + uri + " needs a signed-in session, which WaterMedia cannot provide", e);
    }

    // A FRESH visitorData FROM YOUTUBE'S INNERTUBE visitor_id ENDPOINT; THE po_token MUST BE BOUND TO THE SAME
    // visitorData HANDED TO yt-dlp
    private static String visitorData() throws IOException {
        final JsonObject json = WebRequests.fetchJson(NAME, new NetRequest(VISITOR_ENDPOINT)
                .method("POST")
                .accept(NetRequest.ACCEPT_JSON)
                .contentType(NetRequest.MIMETYPE_JSON)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .body(VISITOR_BODY), JsonObject.class);
        final String visitorData = str(obj(json, "responseContext"), "visitorData");
        if (visitorData == null) throw new IOException("visitor_id endpoint answered without visitorData");
        return visitorData;
    }
}
