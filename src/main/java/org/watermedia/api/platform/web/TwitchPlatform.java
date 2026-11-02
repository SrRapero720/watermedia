package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;
import org.watermedia.tools.M3UTool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

/** Resolves Twitch live streams, VODs and clips through GQL and the Usher playlists, gated by the mature classification, and searches its channels. */
public final class TwitchPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(TwitchPlatform.class.getSimpleName());
    public static final String NAME = "Twitch";
    private static final String[] HOSTS = {
            "twitch.tv", "www.twitch.tv", "m.twitch.tv", "go.twitch.tv",
            "player.twitch.tv", "clips.twitch.tv"
    };

    // TWITCH GQL ENDPOINT AND USHER CDN URLS
    private static final URI GQL = URI.create("https://gql.twitch.tv/gql");
    private static final String LIVE_URL = "https://usher.ttvnw.net/api/channel/hls/%s.m3u8";
    private static final String VOD_URL = "https://usher.ttvnw.net/vod/%s.m3u8";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String SITE = "https://www.twitch.tv/";

    // INLINE GQL QUERY FOR ACCESS TOKENS
    private static final String ACCESS_TOKEN_QUERY =
            "query PlaybackAccessToken_Template($login: String!, $isLive: Boolean!, $vodID: ID!, $isVod: Boolean!, $playerType: String!) {" +
            "  streamPlaybackAccessToken(channelName: $login, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isLive) { value signature }" +
            "  videoPlaybackAccessToken(id: $vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: $playerType}) @include(if: $isVod) { value signature }" +
            "}";
    // INLINE GQL SEARCH (PERSISTED HASHES ROTATE AND FAIL WITH PersistedQueryNotFound); ONLY userQuery/platform ARE
    // REQUIRED. THE channels SECTION CARRIES THE MOST USEFUL HITS WITH A TITLE AND A THUMBNAIL (LIVE PREVIEW OR PROFILE)
    private static final String SEARCH_QUERY =
            "query Search($q: String!) { searchFor(userQuery: $q, platform: \"web\") {" +
                    " channels { edges { item { ... on User {" +
                    " login displayName profileImageURL(width: 300) broadcastSettings { title } stream { previewImageURL } } } } } } }";

    // PERSISTED QUERY HASHES, ALIGNED WITH YT-DLP'S TWITCH EXTRACTOR
    private static final String HASH_STREAM_METADATA = "ad022ca32220d5523d03a23cbcb5beaa1e0999889c1f8f78f9f2520dafb5cae6";
    private static final String HASH_COMSCORE = "e1edae8122517d013405f237ffcc124515dc6ded82480a88daef69c83b53ac01";
    private static final String HASH_VIDEO_PREVIEW = "9515480dee68a77e667cb19de634739d33f243572b007e98e67184b1a5d8369f";
    private static final String HASH_VIDEO_METADATA = "45111672eea2e507f8ba44d101a61862f9c56b11dee09a15634cb75cb9b9084d";
    private static final String HASH_CLIP = "0a02bb974443b576f5579aab0fef1d4b7f44e58a8a256f0c5adfead0db70640f";
    private static final String HASH_CONTENT_CLASS = "57bb6c1aca3631b2b3e74b1c3c8adbecbbcc3becb70ec52d7c5ef0f90d7c3b02";

    // SIGN POST FLAGGED BY TWITCH WHEN THE CONTENT CLASSIFICATION LABELS MARK A STREAM/VOD/CLIP AS MATURE
    private static final String MATURE_SIGN_POST = "SIGN_POST_TYPE_MATURE";

    // LIVE-PREVIEW URLS COME TEMPLATED WITH {width}x{height}; SUBSTITUTE A 16:9 THUMBNAIL SIZE
    private static final String PREVIEW_WIDTH = "960";
    private static final String PREVIEW_HEIGHT = "540";
    // USHER PLAYLISTS CARRY SHORT-LIVED ACCESS TOKENS
    private static final Duration TTL = Duration.ofMinutes(30);

    // /videos/{id}, /{user}/v/{id}, /{user}/video/{id}
    private static final Pattern VOD_PATH = Pattern.compile("^/(?:[^/]+/v(?:ideo)?|videos)/(\\d+)");
    // /{user}/clip/{slug}
    private static final Pattern CLIP_PATH = Pattern.compile("^/(?:[^/]+/)?clip/([^/?#&]+)");
    // /{channel}
    private static final Pattern CHANNEL_PATH = Pattern.compile("^/([^/#?]+)/?$");

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        final String host = uri.getHost();
        if (!DataTool.equalsAnyIgnoreCase(host, HOSTS)) return null;

        final String path = uri.getPath();
        final Map<String, String> query = NetRequest.query(uri);

        try {
            // clips.twitch.tv/{slug} OR clips.twitch.tv/embed?clip={slug}
            if ("clips.twitch.tv".equalsIgnoreCase(host)) {
                final String[] segments = path == null ? new String[0] : path.split("/");
                final String slug = "/embed".equals(path) ? query.get("clip") : segments.length == 0 ? null : segments[segments.length - 1];
                if (slug == null || slug.isEmpty()) throw PlatformException.unsupported(NAME, uri);
                return this.resolveClip(slug);
            }

            // player.twitch.tv/?video={id} OR ?channel={name}
            if ("player.twitch.tv".equalsIgnoreCase(host)) {
                final String videoId = query.get("video");
                if (videoId != null) return this.resolveVod(videoId.replaceFirst("^v", ""));
                final String channel = query.get("channel");
                if (channel != null) return this.resolveStream(channel.toLowerCase(Locale.ROOT));
                throw PlatformException.unsupported(NAME, uri);
            }

            if (path == null || path.length() <= 1) throw PlatformException.unsupported(NAME, uri);

            // VOD: /videos/{id}, /{user}/v/{id}, /{user}/video/{id}
            Matcher m = VOD_PATH.matcher(path);
            if (m.find()) return this.resolveVod(m.group(1));

            // SCHEDULE: ?vodID={id}
            final String vodId = query.get("vodID");
            if (vodId != null) return this.resolveVod(vodId);

            // CLIP: /{user}/clip/{slug}
            m = CLIP_PATH.matcher(path);
            if (m.find()) return this.resolveClip(m.group(1));

            // STREAM: /{channel}
            m = CHANNEL_PATH.matcher(path);
            if (m.find()) return this.resolveStream(m.group(1).toLowerCase(Locale.ROOT));

            throw PlatformException.unsupported(NAME, uri);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        final Map<String, Object> body = new HashMap<>(3);
        body.put("operationName", "Search");
        body.put("query", SEARCH_QUERY);
        body.put("variables", Map.of("q", query));

        final JsonObject data;
        try {
            data = obj(gql(body, JsonObject.class, PlatformAPI.SEARCH_TIMEOUT_MS), "data");
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Search for '" + query + "' failed", e);
        }
        return this.hits(data, limit);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // THE PLAYABLE CHANNELS OF A SEARCH: AN OFFLINE ONE (NO stream) FAILS THE MOMENT IT IS OPENED, SO ONLY LIVE ONES ARE HITS
    List<PlatformResult> hits(final JsonObject data, final int limit) {
        final JsonArray edges = arr(obj(data, "searchFor", "channels"), "edges");
        if (edges == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(edges.size(), limit));
        for (final JsonElement edge: edges) {
            if (out.size() >= limit) break;
            final JsonObject item = obj(edge, "item");
            final JsonObject stream = obj(item, "stream");
            if (stream == null) continue;

            // THE PAGE URL NEEDS THE CHANNEL LOGIN
            final String login = str(item, "login");
            final URI page = login == null ? null : DataTool.uri(SITE + login);
            if (page == null) continue;

            // PREFER THE STREAM TITLE; FALL BACK TO THE CHANNEL NAME WHEN THE CHANNEL HAS NEVER SET ONE
            String title = str(obj(item, "broadcastSettings"), "title");
            if (title == null) title = str(item, "displayName");

            // THE STREAM PREVIEW (TEMPLATED), OR THE PROFILE PICTURE WHILE A FRESH STREAM HAS NONE YET
            final String preview = str(stream, "previewImageURL");
            URI thumbnail = preview == null ? null : DataTool.uri(preview.replace("{width}", PREVIEW_WIDTH).replace("{height}", PREVIEW_HEIGHT));
            if (thumbnail == null) thumbnail = DataTool.uri(str(item, "profileImageURL"));

            out.add(new PlatformResult(this, title, thumbnail, page));
        }
        return out;
    }

    // ==========================================================================
    // CONTENT-TYPE HANDLERS
    // ==========================================================================

    private Resolution resolveStream(final String channel) throws IOException {
        ensureNotMature(ContentKind.STREAM, channel);

        // THE StreamMetadata QUERY ALSO REPORTS WHETHER THE CHANNEL EXISTS AND IS LIVE (user.stream IS AN OBJECT WHEN
        // LIVE, null WHEN OFFLINE); A METADATA HICCUP NEVER BREAKS A LIVE WATCH, THE STREAM RESOLVES WITHOUT IT
        MetadataWithThumbnail mwt = null;
        boolean apiAnswered = false;
        try {
            mwt = fetchStreamMetadata(channel);
            apiAnswered = true;
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn(IT, "Twitch failed to fetch stream metadata for '{}'; resolving without it", channel, e);
        }

        // ONLY THE API CAN DECLARE A CHANNEL MISSING OR OFFLINE (CLEAR ERROR, NO USHER CALL); WHEN IT DID NOT ANSWER,
        // THE STREAM RESOLVES RESILIENTLY AND PLAYBACK FAILS ON ITS OWN IF IT TURNS OUT NOT TO WORK
        if (apiAnswered && mwt == null) throw new PlatformException(NAME, "Channel '" + channel + "' does not exist");
        if (apiAnswered && !mwt.online()) throw new PlatformException(NAME, "Streamer '" + channel + "' is offline");

        final HttpHeaders headers = HttpHeaders.defaults(URI.create(SITE));
        final List<Quality> variants = WebRequests.hls(playlist(channel, false), headers);

        LOGGER.debug(IT, "Twitch resolved stream '{}' with {} variant(s)", channel, variants.size());
        return new Resolution(Instant.now().plus(TTL),
                new Source(MediaType.VIDEO, mwt != null ? mwt.thumbnail() : null, mwt != null ? mwt.metadata() : null,
                        headers, variants, null, null));
    }

    private Resolution resolveVod(final String vodId) throws IOException {
        ensureNotMature(ContentKind.VOD, vodId);

        // VODS RESOLVE STRICTLY (UNLIKE STREAMS), SO A 403/404 FROM USHER FAILS HERE AS A DELETED OR SUB-ONLY VOD
        final HttpHeaders headers = HttpHeaders.defaults(URI.create(SITE));
        final URI master = playlist(vodId, true);
        final M3UTool.Playlist playlist = M3UTool.fetch(master, headers);
        final List<Quality> variants = WebRequests.qualities(master, playlist instanceof final M3UTool.Master m ? m.variants() : List.of());

        MetadataWithThumbnail mwt = null;
        try {
            mwt = fetchVodMetadata(vodId);
            if (mwt == null) LOGGER.warn(IT, "Twitch VOD '{}' plays but its metadata query found no video", vodId);
        } catch (final IOException | RuntimeException e) {
            LOGGER.warn(IT, "Twitch failed to fetch VOD metadata for '{}'; resolving without it", vodId, e);
        }

        LOGGER.debug(IT, "Twitch resolved VOD '{}' with {} variant(s)", vodId, variants.size());
        return new Resolution(Instant.now().plus(TTL),
                new Source(MediaType.VIDEO, mwt != null ? mwt.thumbnail() : null, mwt != null ? mwt.metadata() : null,
                        headers, variants, null, null));
    }

    private Resolution resolveClip(final String slug) throws IOException {
        ensureNotMature(ContentKind.CLIP, slug);
        final JsonObject clip = obj(gql(List.of(persistedQuery("ShareClipRenderStatus", HASH_CLIP, Map.of("slug", slug))), JsonArray.class).get(0), "data", "clip");
        if (clip == null) throw new PlatformException(NAME, "Clip not found or no longer available: " + slug);

        final JsonObject accessToken = obj(clip, "playbackAccessToken");
        final String sig = str(accessToken, "signature");
        final String token = str(accessToken, "value");
        if (sig == null || token == null) throw new PlatformException(NAME, "No access token for clip: " + slug);
        final String accessQuery = "sig=" + URLEncoder.encode(sig, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        final JsonArray assets = arr(clip, "assets");
        final JsonObject asset = assets == null || assets.isEmpty() ? null : obj(assets.get(0));
        final JsonArray videoQualities = arr(asset, "videoQualities");
        final List<Quality> clipVariants = new ArrayList<>();
        if (videoQualities != null) {
            for (final JsonElement element: videoQualities) {
                final JsonObject q = obj(element);
                final String sourceUrl = str(q, "sourceURL");
                final int height = intOr(q, "quality", 0);
                if (sourceUrl == null || height <= 0) continue;
                final URI url = DataTool.uri(sourceUrl + (sourceUrl.contains("?") ? "&" : "?") + accessQuery);
                if (url != null) clipVariants.add(new Quality(url, 0, height));
            }
        }
        if (clipVariants.isEmpty()) throw new PlatformException(NAME, "No video qualities found for clip: " + slug);

        // TWITCH REPORTS clip durationSeconds IN SECONDS; Metadata IS MILLISECONDS
        final long durationMs = Math.max(0, intOr(clip, "durationSeconds", 0)) * 1_000L;
        final String author = str(obj(clip, "broadcaster"), "displayName");

        Instant publishedAt = null;
        final String createdAt = str(clip, "createdAt");
        if (createdAt != null) {
            try { publishedAt = Instant.parse(createdAt); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "Twitch unparseable createdAt '{}' for clip '{}'", createdAt, slug, e); }
        }

        final Metadata clipMetadata = new Metadata(str(clip, "title"), null, publishedAt, durationMs, author);
        LOGGER.debug(IT, "Twitch resolved clip '{}' with {} variant(s)", slug, clipVariants.size());
        return new Resolution(Instant.now().plus(TTL),
                new Source(MediaType.VIDEO, DataTool.uri(str(asset, "thumbnailURL")), clipMetadata,
                        HttpHeaders.defaults(URI.create(SITE)),
                        clipVariants, null, null));
    }

    // ==========================================================================
    // HLS PLAYLIST
    // ==========================================================================

    // USHER MASTER PLAYLIST URL SIGNED WITH A FRESH PLAYBACK ACCESS TOKEN
    private static URI playlist(final String id, final boolean isVod) throws IOException {
        final Map<String, Object> variables = new HashMap<>();
        variables.put("isLive", !isVod);
        variables.put("isVod", isVod);
        variables.put("login", isVod ? "" : id);
        variables.put("vodID", isVod ? id : "");
        variables.put("playerType", "site");

        final Map<String, Object> body = new HashMap<>();
        body.put("operationName", "PlaybackAccessToken_Template");
        body.put("query", ACCESS_TOKEN_QUERY);
        body.put("variables", variables);

        final String tokenKey = isVod ? "videoPlaybackAccessToken" : "streamPlaybackAccessToken";
        final JsonObject token = obj(gql(body, JsonObject.class), "data", tokenKey);
        final String signature = str(token, "signature");
        final String value = str(token, "value");
        if (signature == null || value == null) throw new PlatformException(NAME, "Access token not found (key: " + tokenKey + ")");

        final long p = ThreadLocalRandom.current().nextLong(1_000_000, 10_000_001);
        final String sessionId = UUID.randomUUID().toString().replace("-", "");
        return URI.create(String.format(isVod ? VOD_URL : LIVE_URL, id) +
                "?allow_source=true&allow_audio_only=true&allow_spectre=true" +
                "&p=" + p +
                "&play_session_id=" + sessionId +
                "&player=twitchweb&platform=web&player_backend=mediaplayer" +
                "&playlist_include_framerate=true" +
                "&supported_codecs=av1,h265,h264" +
                "&sig=" + URLEncoder.encode(signature, StandardCharsets.UTF_8) +
                "&token=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    // ==========================================================================
    // METADATA
    // ==========================================================================

    // null MEANS THE CHANNEL DOES NOT EXIST; TRANSPORT AND SHAPE FAILURES PROPAGATE TO THE CALLER
    private static MetadataWithThumbnail fetchStreamMetadata(final String channel) throws IOException {
        final List<Map<String, Object>> batch = new ArrayList<>(3);

        final Map<String, Object> streamVars = new HashMap<>();
        streamVars.put("channelLogin", channel);
        streamVars.put("includeIsDJ", true);
        batch.add(persistedQuery("StreamMetadata", HASH_STREAM_METADATA, streamVars));

        final Map<String, Object> comscoreVars = new HashMap<>();
        comscoreVars.put("channel", channel);
        comscoreVars.put("clipSlug", "");
        comscoreVars.put("isClip", false);
        comscoreVars.put("isLive", true);
        comscoreVars.put("isVodOrCollection", false);
        comscoreVars.put("vodID", "");
        batch.add(persistedQuery("ComscoreStreamingQuery", HASH_COMSCORE, comscoreVars));

        batch.add(persistedQuery("VideoPreviewOverlay", HASH_VIDEO_PREVIEW, Map.of("login", channel)));

        final JsonArray results = gql(batch, JsonArray.class);

        // STREAM METADATA: USER EXISTENCE + STREAM STATE; A MISSING data IS A GQL ERROR, NOT A MISSING CHANNEL
        final JsonObject data = obj(results.get(0), "data");
        if (data == null) throw new IOException("Twitch StreamMetadata for '" + channel + "' answered without data");
        final JsonObject user = obj(data, "user");
        if (user == null) return null;
        final JsonObject stream = obj(user, "stream");

        Instant createdAt = null;
        final String createdStr = str(stream, "createdAt");
        if (createdStr != null) {
            try { createdAt = Instant.parse(createdStr); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "Twitch unparseable createdAt '{}' for '{}'", createdStr, channel, e); }
        }

        // COMSCORE: TITLE + AUTHOR
        final JsonObject sqUser = results.size() > 1 ? obj(results.get(1), "data", "user") : null;
        final String displayName = str(sqUser, "displayName");
        String title = str(obj(sqUser, "broadcastSettings"), "title");

        // VIDEO PREVIEW: STREAM THUMBNAIL (NOT PROFILE IMAGE)
        final URI thumbnailUri = results.size() > 2 ? DataTool.uri(str(obj(results.get(2), "data", "user", "stream"), "previewImageURL")) : null;

        if ("rerun".equals(str(stream, "type")) && title != null) title = title + " (rerun)";

        return new MetadataWithThumbnail(new Metadata(title, null, createdAt, 0, displayName), thumbnailUri, stream != null);
    }

    // null MEANS THE VOD DOES NOT EXIST; TRANSPORT AND SHAPE FAILURES PROPAGATE TO THE CALLER
    private static MetadataWithThumbnail fetchVodMetadata(final String vodId) throws IOException {
        final Map<String, Object> vars = new HashMap<>();
        vars.put("channelLogin", "");
        vars.put("videoID", vodId);

        final JsonObject video = obj(gql(List.of(persistedQuery("VideoMetadata", HASH_VIDEO_METADATA, vars)), JsonArray.class).get(0), "data", "video");
        if (video == null) return null;

        final String title = str(video, "title");
        // TWITCH REPORTS VOD lengthSeconds IN SECONDS; Metadata IS MILLISECONDS
        final long durationMs = Math.max(0, intOr(video, "lengthSeconds", 0)) * 1_000L;

        // FULL-SIZE THUMBNAIL: THE DIMENSION PLACEHOLDER BECOMES 0x0 (640x480.jpg → 0x0.jpg)
        final String thumbUrl = str(video, "previewThumbnailURL");
        final URI thumbnailUri = thumbUrl == null ? null : DataTool.uri(thumbUrl.replaceAll("\\d+x\\d+(\\.\\w+)($|(?=[?#]))", "0x0$1$2"));

        Instant publishedAt = null;
        final String publishedStr = str(video, "postedAt");
        if (publishedStr != null) {
            try { publishedAt = Instant.parse(publishedStr); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "Twitch unparseable postedAt '{}' for VOD '{}'", publishedStr, vodId, e); }
        }

        final Metadata metadata = new Metadata(title != null ? title : "Untitled Broadcast", str(video, "description"), publishedAt, durationMs,
                str(obj(video, "owner"), "displayName"));
        return new MetadataWithThumbnail(metadata, thumbnailUri, true);
    }

    // ==========================================================================
    // MATURE CONTENT
    // ==========================================================================

    // QUERIES TWITCH'S CONTENT CLASSIFICATION SIGN POST AND ABORTS WHEN THE CONTENT IS MATURE
    // AND THE HOST DISABLED MATURE CONTENT. RUNS BEFORE ANY PLAYLIST/CLIP FETCH SO NO DATA IS RETRIEVED.
    private static void ensureNotMature(final ContentKind kind, final String id) throws IOException {
        if (WaterMediaConfig.platforms.allowMatureContent) return;

        final Map<String, Object> vars = new HashMap<>();
        vars.put("login", kind == ContentKind.STREAM ? id : "");
        vars.put("vodID", kind == ContentKind.VOD ? id : "");
        vars.put("clipSlug", kind == ContentKind.CLIP ? id : "");
        vars.put("isStream", kind == ContentKind.STREAM);
        vars.put("isVOD", kind == ContentKind.VOD);
        vars.put("isClip", kind == ContentKind.CLIP);

        final JsonObject data = obj(gql(List.of(persistedQuery("ContentClassificationContext", HASH_CONTENT_CLASS, vars)), JsonArray.class).get(0), "data");
        // THE GATE FAILS CLOSED: WITHOUT A CLASSIFICATION (A ROTATED PERSISTED QUERY, A GQL ERROR) NOTHING IS PLAYED
        if (data == null) throw new PlatformException(NAME, "Content classification of " + kind.name().toLowerCase(Locale.ROOT) + " '" + id
                + "' is unavailable; enable platforms.allowMatureContent to skip the check");

        // STREAM NESTS UNDER user.stream; VOD UNDER video; CLIP UNDER clip
        final JsonObject node = switch (kind) {
            case STREAM -> obj(data, "user", "stream");
            case VOD -> obj(data, "video");
            case CLIP -> obj(data, "clip");
        };
        if (MATURE_SIGN_POST.equals(str(obj(node, "contentClassificationLabelPolicyProperties", "signPostProperties"), "signPost")))
            throw new MatureContentException(NAME, kind.name().toLowerCase(Locale.ROOT) + " '" + id + "' is marked as mature content");
    }

    // ==========================================================================
    // GQL HELPERS
    // ==========================================================================

    // POSTS ONE GQL OPERATION (AN OBJECT ANSWER) OR A BATCH (AN ARRAY ANSWER)
    private static <T> T gql(final Object body, final Class<T> type) throws PlatformException {
        return gql(body, type, WaterMediaConfig.network.timeout);
    }

    // A SEARCH CAPS ITS WAIT AT PlatformAPI.SEARCH_TIMEOUT_MS; EVERY OTHER QUERY TAKES THE CONFIGURED NETWORK TIMEOUT
    private static <T> T gql(final Object body, final Class<T> type, final int timeoutMs) throws PlatformException {
        return WebRequests.fetchJson(NAME, new NetRequest(GQL)
                .method("POST")
                .accept(NetRequest.ACCEPT_JSON)
                .contentType("application/json; charset=utf-8")
                .header("Client-ID", CLIENT_ID)
                .connectTimeout(timeoutMs).readTimeout(timeoutMs)
                .body(JSONTool.write(body)), type);
    }

    private static Map<String, Object> persistedQuery(final String opName, final String hash, final Map<String, ?> variables) {
        final Map<String, Object> op = new HashMap<>(3);
        op.put("operationName", opName);
        op.put("variables", variables);
        op.put("extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", hash)));
        return op;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // online IS STREAM-ONLY (user.stream != null); IT IS ALWAYS true FOR A RESOLVED VOD
    private record MetadataWithThumbnail(Metadata metadata, URI thumbnail, boolean online) {}
    private enum ContentKind { STREAM, VOD, CLIP }
}
