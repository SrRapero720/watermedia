package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves Kick live channels, VODs and clips through its web API, and searches its channels. */
public final class KickPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(KickPlatform.class.getSimpleName());
    public static final String NAME = "Kick";
    private static final String[] HOSTS = { "kick.com", "www.kick.com" };
    // VODS ARE ONLY SERVED BY v1; CHANNELS AND CLIPS STAY ON v2
    private static final String VIDEO_API = "https://kick.com/api/v1/video/%s";
    private static final String CHANNELS_API = "https://kick.com/api/v2/channels/%s";
    private static final String CLIPS_API = "https://kick.com/api/v2/clips/%s/play";
    private static final String SEARCH_API = "https://kick.com/api/search?searched_word=";
    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String CLIP_PREFIX = "clip_";
    // LIVE AND VOD PLAYLIST LINKS ROTATE; RE-RESOLVING KEEPS A STALE PLAYLIST FROM BEING SERVED
    private static final Duration PLAYLIST_TTL = Duration.ofMinutes(30);

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String clipId = clipId(uri);
        if (clipId != null) {
            LOGGER.debug(IT, "Kick resolving clip '{}' from {}", clipId, uri);
            return this.resolveClip(uri, clipId);
        }

        final String[] path = segments(uri);
        if (path.length == 0 || path[0].isEmpty()) throw PlatformException.unsupported(NAME, uri);

        // A SINGLE SEGMENT IS A CHANNEL NAME
        if (path.length == 1) {
            final String slug = path[0];
            LOGGER.debug(IT, "Kick resolving channel '{}' from {}", slug, uri);
            final Channel channel = WebRequests.fetchJson(NAME, String.format(CHANNELS_API, slug), Channel.class);

            if (channel.livestream == null || !channel.livestream.live)
                throw new PlatformException(NAME, "Streamer '" + slug + "' is offline");

            if (channel.banned)
                throw new PlatformException(NAME, "Streamer '" + slug + "' is banned");

            if (channel.livestream.mature && !WaterMediaConfig.platforms.allowMatureContent)
                throw new MatureContentException(NAME, "Streamer '" + slug + "' is marked as mature content");

            final URI playback = DataTool.uri(channel.url);
            if (playback == null)
                throw new PlatformException(NAME, "Streamer '" + slug + "' is live but exposes no usable playback URL");

            final String username = channel.user != null ? channel.user.username : slug;
            if (channel.livestream.sessionTitle == null)
                LOGGER.warn(IT, "Kick channel '{}' is live but reports no session_title", slug);

            final HttpHeaders headers = HttpHeaders.defaults(uri);
            final List<Quality> variants = WebRequests.hls(playback, headers);

            // THE STREAM TITLE IS THE CONTENT; THE STREAMER IS ITS AUTHOR
            final Metadata metadata = new Metadata(
                    channel.livestream.sessionTitle,
                    null,
                    startTime(channel.livestream.startTime, slug),
                    0,
                    username);

            LOGGER.debug(IT, "Kick resolved live channel '{}' with {} variant(s)", slug, variants.size());
            final var entry = new Source(MediaType.VIDEO, channel.user != null ? DataTool.uri(channel.user.profilePic) : null, metadata,
                    headers,
                    variants,
                    null, null);
            return new Resolution(Instant.now().plus(PLAYLIST_TTL), entry);
        }

        final String id = vodId(uri);
        if (id == null) throw PlatformException.unsupported(NAME, uri);

        LOGGER.debug(IT, "Kick resolving VOD '{}' from {}", id, uri);
        final Video video = WebRequests.fetchJson(NAME, String.format(VIDEO_API, id), Video.class);

        final URI playback = DataTool.uri(video.source);
        if (video.livestream == null || playback == null)
            throw new PlatformException(NAME, "VOD '" + id + "' is unavailable (no usable playback URL)");

        final User user = video.livestream.channel != null ? video.livestream.channel.user : null;
        final String username = user != null ? user.username : null;

        if (video.livestream.channel != null && video.livestream.channel.banned)
            throw new PlatformException(NAME, "Streamer '" + username + "' is banned");

        if (video.livestream.mature && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "VOD '" + id + "' is marked as mature content");

        if (video.livestream.sessionTitle == null)
            LOGGER.warn(IT, "Kick VOD '{}' reports no session_title", id);

        // THE LADDER COMES FROM THE VOD'S PLAYBACK URL, NOT THE PAGE URI
        final HttpHeaders headers = HttpHeaders.defaults(uri);
        final List<Quality> vodVariants = WebRequests.hls(playback, headers);

        // KICK REPORTS THE VOD duration IN MILLISECONDS ALREADY
        final Metadata vodMetadata = new Metadata(
                video.livestream.sessionTitle,
                null,
                startTime(video.livestream.startTime, id),
                Math.max(0L, video.livestream.duration),
                username);

        LOGGER.debug(IT, "Kick resolved VOD '{}' with {} variant(s)", id, vodVariants.size());
        final var entry = new Source(MediaType.VIDEO,
                user != null ? DataTool.uri(user.profilePic) : null,
                vodMetadata,
                headers,
                vodVariants,
                null, null);
        return new Resolution(Instant.now().plus(PLAYLIST_TTL), entry);
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        // KICK SEARCH IS CHANNEL-CENTRIC: channels[] IS THE ONLY SECTION CARRYING A SLUG + AVATAR. NOTE THE
        // NESTED user OBJECT IS camelCase (profilePic/username), UNLIKE THE snake_case /api/v2/channels PAYLOAD
        final NetRequest request = new NetRequest(SEARCH_API + URLEncoder.encode(query, StandardCharsets.UTF_8))
                .accept(NetRequest.ACCEPT_JSON).connectTimeout(PlatformAPI.SEARCH_TIMEOUT_MS).readTimeout(PlatformAPI.SEARCH_TIMEOUT_MS);
        return this.hits(WebRequests.fetchJson(NAME, request, SearchResponse.class), limit);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // THE PLAYABLE CHANNELS OF A SEARCH: AN OFFLINE OR BANNED ONE FAILS THE MOMENT IT IS OPENED, SO ONLY LIVE ONES ARE HITS
    List<PlatformResult> hits(final SearchResponse res, final int limit) {
        if (res.channels == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(res.channels.length, limit));
        for (final SearchChannel channel: res.channels) {
            if (out.size() >= limit) break;
            if (channel.slug == null || !channel.live || channel.banned) continue;
            final String username = channel.user != null ? channel.user.username : null;
            final String pic = channel.user != null ? channel.user.profilePic : null;
            // SAFE-PARSE THE NETWORK AVATAR URL: A MALFORMED ONE MUST NOT ABORT THE WHOLE RESULT SET
            final URI thumbnail = DataTool.uri(pic);
            final URI page = DataTool.uri("https://kick.com/" + channel.slug);
            if (page == null) continue;
            // THE USERNAME IS THE SEARCH LABEL: THIS ENDPOINT REPORTS NO LIVE STREAM TITLE
            out.add(new PlatformResult(this, username != null ? username : channel.slug, thumbnail, page));
        }
        return out;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Resolution resolveClip(final URI uri, final String clipId) throws PlatformException {
        final ClipResponse response = WebRequests.fetchJson(NAME, String.format(CLIPS_API, clipId), ClipResponse.class);
        final Clip clip = response.clip;
        if (clip == null)
            throw new PlatformException(NAME, "Clip '" + clipId + "' is unavailable");

        final URI clipUrl = DataTool.uri(clip.clipUrl);
        if (clipUrl == null)
            throw new PlatformException(NAME, "Clip '" + clipId + "' has no usable playback URL (removed or still processing)");

        if (clip.mature && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "Clip '" + clipId + "' is marked as mature content");

        // CLIP URL MAY BE A DIRECT FILE (mp4/webm) OR AN M3U8 PLAYLIST
        final HttpHeaders headers = HttpHeaders.defaults(uri);
        final List<Quality> variants;
        final String clipPath = clipUrl.getPath();
        if (clipPath != null && clipPath.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            variants = WebRequests.hls(clipUrl, headers);
        } else {
            LOGGER.debug(IT, "Kick clip '{}' is a direct file: {}", clipId, clipUrl);
            variants = List.of(new Quality(clipUrl, 0, 0));
        }

        final Metadata metadata = new Metadata(
                clip.title,
                clip.category != null ? clip.category.name : null,
                instant(clip.createdAt),
                (long) Math.max(0f, clip.duration * 1_000f),
                clip.creator != null ? clip.creator.username : null);

        LOGGER.debug(IT, "Kick resolved clip '{}' with {} variant(s)", clipId, variants.size());
        final var entry = new Source(MediaType.VIDEO, DataTool.uri(clip.thumbnail), metadata,
                headers,
                variants,
                null, null);
        // A CLIP FILE IS SERVED FROM THE CDN; ONLY A SIGNED URL CARRIES AN EXPIRY
        return new Resolution(DataTool.expiry(clipUrl), entry);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // PATH SEGMENTS WITHOUT THE LEADING SLASH; A HOST-ONLY URL HAS NONE
    private static String[] segments(final URI uri) {
        final String path = uri.getPath();
        return path == null || path.length() < 2 ? new String[0] : path.substring(1).split("/");
    }

    // RESOLVES THE clip_... ID FROM BOTH URL SHAPES: /<channel>/clips/clip_... AND /<channel>?clip=clip_...
    // RETURNS NULL WHEN THE URI IS NOT A CLIP (LIVE/VOD)
    static String clipId(final URI uri) {
        final String clip = NetRequest.query(uri).get("clip");
        if (clip != null && clip.regionMatches(true, 0, CLIP_PREFIX, 0, CLIP_PREFIX.length())) return clip;

        final String[] path = segments(uri);
        for (int i = 0; i < path.length - 1; i++) {
            if (path[i].equalsIgnoreCase("clips") && path[i + 1].regionMatches(true, 0, CLIP_PREFIX, 0, CLIP_PREFIX.length())) return path[i + 1];
        }
        return null;
    }

    // RESOLVES THE VOD UUID FROM BOTH PAGE SHAPES: THE LEGACY /video/<uuid> AND THE CURRENT /<channel>/videos/<uuid>
    // RETURNS NULL FOR ANY OTHER SHAPE, INCLUDING THE /<channel>/videos LISTING
    static String vodId(final URI uri) {
        final String[] path = segments(uri);
        if (path.length == 2 && path[0].equalsIgnoreCase("video")) return path[1];
        if (path.length == 3 && path[1].equalsIgnoreCase("videos")) return path[2];
        return null;
    }

    // A LIVE OR VOD start_time; A MALFORMED ONE WARNS INSTEAD OF ABORTING THE WHOLE RESOLUTION
    private static Instant startTime(final String value, final String ctx) {
        final Instant parsed = instant(value);
        if (parsed == null && value != null)
            LOGGER.warn(IT, "Kick unparseable start_time '{}' for {}", value, ctx);
        return parsed;
    }

    // CLIP AND VOD TIMESTAMPS ARE ISO-8601, LIVE ONES "yyyy-MM-dd HH:mm:ss"; NULL WHEN NEITHER PARSES
    private static Instant instant(final String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (final DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value, DATE_PATTERN).toInstant(ZoneOffset.UTC);
            } catch (final DateTimeParseException ignored) {
                return null;
            }
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // URLS BIND AS TEXT: A MALFORMED OR NON-WEB ONE IS DROPPED BY DataTool.uri INSTEAD OF FAILING THE WHOLE BINDING
    private record Channel(int id, @SerializedName("is_banned") boolean banned, Livestream livestream, User user, @SerializedName("playback_url") String url) {}
    private record Livestream(int id, @SerializedName("is_live") boolean live, @SerializedName("is_mature") boolean mature,
                              long duration, @SerializedName("session_title") String sessionTitle,
                              @SerializedName("start_time") String startTime, Channel channel) {}
    // THE CHANNEL PAYLOAD SPELLS THE AVATAR profile_pic, THE VOD ONE profilepic
    private record User(int id, String username, @SerializedName(value = "profile_pic", alternate = "profilepic") String profilePic) {}
    private record Video(int id, String source, Livestream livestream) {}

    private record ClipResponse(Clip clip) {}
    private record Clip(@SerializedName("clip_url") String clipUrl, String title, Creator creator,
                        @SerializedName("thumbnail_url") String thumbnail, float duration, Category category,
                        @SerializedName("created_at") String createdAt, @SerializedName("is_mature") boolean mature) {}
    private record Creator(int id, String username) {}
    private record Category(int id, String name) {}

    // SEARCH PAYLOAD: A SEPARATE SHAPE FROM Channel/User; channels[] MIXES camelCase isLive AND user{username, profilePic}
    // WITH snake_case is_banned (PACKAGE-PRIVATE FOR THE TESTS)
    record SearchResponse(SearchChannel[] channels) {}
    record SearchChannel(String slug, @SerializedName("isLive") boolean live, @SerializedName("is_banned") boolean banned, SearchUser user) {}
    record SearchUser(String username, String profilePic) {}
}
