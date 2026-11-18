package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
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
 * confirm you're not a bot" restriction with a BotGuard po_token for the web-family clients, bound to a fresh InnerTube
 * {@code visitorData}. The token comes from rustypipe-botguard ({@link BotGuardBinary}), run out of process so a
 * crash in its bundled JS engine never takes down the JVM. A restriction that survives the token is retried over IPv4, which
 * then stays on for as long as it gets links through.
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
    private final URI visitorEndpoint;
    // YOUTUBE FLAGS WHOLE IPv6 BLOCKS AS BOTS, WHICH NO po_token LIFTS: IPv4 IS THE LAST RESORT
    private volatile boolean ipv4;      // IT GOT A LINK THROUGH: EVERY REQUEST STARTS OVER IPv4
    private volatile boolean ipv4Only; // DO NOT RETRY

    /** Creates the platform on YouTube's own visitor_id endpoint. */
    public YouTubePlatform() { this(VISITOR_ENDPOINT); }

    // TEST SEAM: POINTS THE visitor_id ENDPOINT AT A LOCAL SERVER
    YouTubePlatform(final URI visitorEndpoint) { this.visitorEndpoint = visitorEndpoint; }

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
        if (this.ipv4) args.add("--force-ipv4");

        final JsonObject info;
        try {
            info = this.info(uri.toString(), args);
        } catch (final IOException e) {
            final PlatformException restricted = ageRestricted(uri, e);
            if (restricted != null) throw restricted;

            // yt-dlp'S MESSAGES FOR THE BOT RESTRICTS THAT A po_token CAN BYPASS
            final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("not a bot") || message.contains("sign in to confirm") || message.contains("po_token"))
                return this.retryWithPoToken(uri, args);
            throw new PlatformException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_RESOLVE) + "Failed to resolve " + uri + " with yt-dlp: " + e.getMessage(), e);
        }
        return this.extract(info);
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        // ytsearchN: HITS ONLY YOUTUBE'S SEARCH ENDPOINT; --flat-playlist KEEPS IT TO RAW RESULT METADATA
        // (NO PER-VIDEO STREAM EXTRACTION), SO IT IS FAST AND NEEDS NO PO_TOKEN/BOTGUARD RETRY
        final JsonObject info;
        try {
            info = this.info("ytsearch" + limit + ":" + query, this.ipv4 ? List.of("--flat-playlist", "--force-ipv4") : List.of("--flat-playlist"), PlatformAPI.SEARCH_TIMEOUT_MS);
        } catch (final IOException e) {
            throw new PlatformException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_SEARCH) + "Failed to search '" + query + "' with yt-dlp: " + e.getMessage(), e);
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
            final String visitorData = this.visitorData();
            final String token = this.mintToken(visitorData);
            final List<String> args = new ArrayList<>(baseArgs);
            args.add("--extractor-args");
            args.add("youtube:po_token=web.gvs+" + token + ",web_safari.gvs+" + token
                    + ",mweb.gvs+" + token + ",tv.gvs+" + token
                    + ";visitor_data=" + visitorData + ";player_skip=webpage,configs");
            args.add("--extractor-args");
            args.add("youtubetab:skip=webpage");
            info = this.tokenInfo(uri, args);
        } catch (final IOException retry) {
            final PlatformException restricted = ageRestricted(uri, retry);
            if (restricted != null) throw restricted;
            throw new PlatformException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_RETRY_IO) + "Failed to retry the blocked request with a po_token: " + retry.getMessage(), retry);
        } catch (final RuntimeException retry) {
            throw new PlatformException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_RETRY_RUNTIME) + "Failed to retry the blocked request with a po_token: " + retry.getMessage(), retry);
        }
        return this.extract(info);
    }

    // RUNS yt-dlp WITH THE po_token IN args; A BOT RESTRICTION THAT SURVIVES IT GETS ONE MORE RUN OVER IPv4
    private JsonObject tokenInfo(final URI uri, final List<String> args) throws IOException {
        try {
            return this.info(uri.toString(), args);
        } catch (final IOException e) {
            // RESTRICTION OVER IPv4 TOO: THE NEXT LINK STARTS WITHOUT IT
            final boolean overIpv4 = args.contains("--force-ipv4");
            if (overIpv4 && botRestricted(e)) this.ipv4 = false;
            if (overIpv4 || this.ipv4Only || !botRestricted(e)) throw e;

            args.add("--force-ipv4");
            final JsonObject info;
            try {
                info = this.info(uri.toString(), args);
            } catch (final IOException failed) {
                // ONLY THE RESTRICTION RULES IPv4 OUT
                if (botRestricted(failed)) this.ipv4Only = true;
                failed.addSuppressed(e);
                throw failed;
            }
            this.ipv4 = true;
            LOGGER.warn(IT, "YouTube refuses this network over IPv6, resolving over IPv4...");
            return info;
        }
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
            throw new IOException(DataTool.errCode(WaterMedia.ERR_YOUTUBE_BOTGUARD_PROVISION) + "Failed to provision rustypipe-botguard: " + e.getMessage(), e);
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
        throw new IOException(DataTool.errCode(WaterMedia.ERR_YOUTUBE_NO_TOKEN) + "Failed to mint a po_token due to no token from rustypipe-botguard");
    }

    // A FRESH visitorData FROM YOUTUBE'S INNERTUBE visitor_id ENDPOINT; THE po_token MUST BE BOUND TO THE SAME
    // visitorData HANDED TO yt-dlp
    private String visitorData() throws IOException {
        final JsonObject json = WebRequests.fetchJson(NAME, new NetRequest(this.visitorEndpoint)
                .method("POST")
                .accept(NetRequest.ACCEPT_JSON)
                .contentType(NetRequest.MIMETYPE_JSON)
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .body(VISITOR_BODY), JsonObject.class);
        final String visitorData = str(obj(json, "responseContext"), "visitorData");
        if (visitorData == null) throw new IOException(DataTool.errCode(WaterMedia.ERR_YOUTUBE_NO_VISITOR_DATA) + "Failed to fetch visitorData due to visitor_id endpoint answer without it");
        return visitorData;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // "Sign in to confirm you're not a bot"
    private static boolean botRestricted(final IOException e) {
        return e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("not a bot");
    }

    // THE AGE RESTRICTION NEEDS A SIGNED-IN SESSION NO TOKEN CAN REPLACE: A MATURE BLOCK WHEN MATURE CONTENT IS OFF, A PLAIN
    // FAILURE OTHERWISE, AND null WHEN yt-dlp'S MESSAGE IS NOT AN AGE RESTRICTION
    private static PlatformException ageRestricted(final URI uri, final IOException e) {
        final String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("confirm your age") && !message.contains("age-restricted") && !message.contains("inappropriate for some users"))
            return null;
        if (!WaterMediaConfig.platforms.allowMatureContent) return new MatureContentException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_MATURE) + "Failed to resolve " + uri + " due to age restriction", e);
        return new PlatformException(NAME, DataTool.errCode(WaterMedia.ERR_YOUTUBE_AGE_RESTRICTED) + "Failed to resolve video " + uri + " due to age restriction, unsupported.", e);
    }
}
