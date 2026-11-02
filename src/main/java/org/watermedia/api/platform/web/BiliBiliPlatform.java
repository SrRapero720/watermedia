package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.Track;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

/** Resolves BiliBili videos (BV ids and their parts), bangumi episodes and live rooms through the web APIs. */
public final class BiliBiliPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(BiliBiliPlatform.class.getSimpleName());
    public static final String NAME = "BiliBili";
    private static final String[] HOSTS = { "www.bilibili.com", "bilibili.com", "m.bilibili.com", "live.bilibili.com", "b23.tv" };
    private static final String LIVE_HOST = "live.bilibili.com";
    private static final String SHORT_HOST = "b23.tv";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final String FINGERPRINT_API = "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String VIDEO_VIEW_API = "https://api.bilibili.com/x/web-interface/view?bvid=%s";
    private static final String VIDEO_PLAYURL_API = "https://api.bilibili.com/x/player/playurl?bvid=%s&cid=%d&fnval=4048";

    private static final String BANGUMI_SEASON_EP_API = "https://api.bilibili.com/pgc/view/web/season?ep_id=%s";
    private static final String BANGUMI_SEASON_SS_API = "https://api.bilibili.com/pgc/view/web/season?season_id=%s";
    private static final String BANGUMI_PLAYURL_API = "https://api.bilibili.com/pgc/player/web/playurl?ep_id=%s&cid=%s&fnval=4048";

    private static final String LIVE_PLAY_INFO_API = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=%s&no_playurl=0&mask=1&qn=0&platform=web&protocol=0,1&format=0,1,2&codec=0,1,2";
    private static final String LIVE_ROOM_INFO_API = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=%s";
    private static final String LIVE_USER_INFO_API = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=%d";
    // platform=web ANSWERS FLV STREAMS; THE HLS EDGE BEHIND platform=h5 HANGS FOR MANY ROOMS
    private static final String LIVE_PLAYURL_API = "https://api.live.bilibili.com/xlive/web-room/v1/playUrl/playUrl?cid=%s&platform=web&qn=%d";

    private static final Pattern BVID_PATTERN = Pattern.compile("(BV[a-zA-Z0-9]+)");
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");
    private static final Pattern EP_PATTERN = Pattern.compile("/ep(\\d+)");
    private static final Pattern SS_PATTERN = Pattern.compile("/ss(\\d+)");
    private static final Pattern LIVE_ROOM_PATTERN = Pattern.compile("live\\.bilibili\\.com/(\\d+)", Pattern.CASE_INSENSITIVE);
    // LIVE STREAM URLS ROTATE QUICKLY; CDN LINKS OF A VIDEO LAST ROUGHLY TWO HOURS
    private static final Duration LIVE_TTL = Duration.ofMinutes(10);
    private static final Duration VIDEO_TTL = Duration.ofMinutes(90);

    // THE RISK CONTROL ANSWERS HTTP 412 TO WEB API CALLS WITHOUT A buvid3 DEVICE FINGERPRINT COOKIE; ONE IS FETCHED
    // PER PROCESS AND SHARED BY EVERY HANDLER THREAD
    private static final Object FINGERPRINT_LOCK = new Object();
    private static volatile String fingerprint;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI link) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(link.getHost(), HOSTS)) return null;

        try {
            // SHORT LINKS REDIRECT TO THE CANONICAL VIDEO PAGE
            final URI uri = SHORT_HOST.equalsIgnoreCase(link.getHost()) ? WebRequests.follow(link) : link;
            if (LIVE_HOST.equalsIgnoreCase(uri.getHost())) return this.resolveLive(uri);
            final String path = uri.getPath();
            if (path != null && path.contains("/bangumi/play/")) return this.resolveBangumi(uri);
            return this.resolveVideo(uri);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + link, e);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Resolution resolveVideo(final URI uri) throws IOException {
        final String url = uri.toString();

        final Matcher bvidMatcher = BVID_PATTERN.matcher(url);
        if (!bvidMatcher.find()) throw PlatformException.unsupported(NAME, uri);
        final String bvid = bvidMatcher.group(1);

        final Matcher pageMatcher = PAGE_PATTERN.matcher(url);
        final int page = pageMatcher.find() ? DataTool.parseInt(pageMatcher.group(1), 1) : 1;
        LOGGER.debug(IT, "BiliBili resolving video bvid={} page={}", bvid, page);

        final JsonObject viewData = fetchJson(String.format(VIDEO_VIEW_API, bvid), "data");
        final String title = str(viewData, "title");
        // BILIBILI REPORTS VIDEO duration IN SECONDS; Metadata IS MILLISECONDS
        final long durationMs = Math.max(0L, intOr(viewData, "duration", 0)) * 1_000L;
        final long pubdate = longOr(viewData, "pubdate", 0L);

        long cid = longOr(viewData, "cid", 0L);
        String partName = null;
        final JsonArray pages = arr(viewData, "pages");
        if (pages != null && pages.size() > 1) {
            final int index = Math.max(0, Math.min(page - 1, pages.size() - 1));
            final JsonObject pageObj = obj(pages.get(index));
            cid = longOr(pageObj, "cid", 0L);
            partName = str(pageObj, "part");
            if (partName != null && partName.equals(title)) partName = null;
            LOGGER.debug(IT, "BiliBili multi-part video ({} parts), selected part {} (cid={})", pages.size(), index + 1, cid);
        }
        if (cid <= 0) throw new PlatformException(NAME, "Video '" + bvid + "' reports no cid for part " + page);

        final JsonObject playData = fetchJson(String.format(VIDEO_PLAYURL_API, bvid, cid), "data");

        final String fullTitle = title == null ? partName : partName == null ? title : title + " - " + partName;
        final Metadata metadata = new Metadata(fullTitle, str(viewData, "desc"), pubdate > 0 ? Instant.ofEpochSecond(pubdate) : null,
                durationMs, str(obj(viewData, "owner"), "name"));
        return this.buildResult(playData, metadata, DataTool.uri(str(viewData, "pic")));
    }

    private Resolution resolveBangumi(final URI uri) throws IOException {
        final String url = uri.toString();

        String epId = null;
        String ssId = null;

        final Matcher epMatcher = EP_PATTERN.matcher(url);
        if (epMatcher.find()) epId = epMatcher.group(1);

        final Matcher ssMatcher = SS_PATTERN.matcher(url);
        if (ssMatcher.find()) ssId = ssMatcher.group(1);

        if (epId == null && ssId == null) throw PlatformException.unsupported(NAME, uri);
        LOGGER.debug(IT, "BiliBili resolving bangumi epId={} ssId={}", epId, ssId);

        final String seasonApi = epId != null
                ? String.format(BANGUMI_SEASON_EP_API, epId)
                : String.format(BANGUMI_SEASON_SS_API, ssId);

        final JsonObject result = fetchJson(seasonApi, "result");
        final String seasonTitle = str(result, "title");
        final JsonArray episodes = arr(result, "episodes");

        if (epId == null) {
            if (episodes == null || episodes.isEmpty()) throw new PlatformException(NAME, "No episodes found for season " + ssId);

            final Matcher pageMatcher = PAGE_PATTERN.matcher(url);
            final int page = pageMatcher.find() ? DataTool.parseInt(pageMatcher.group(1), 0) : 0;

            final int index = (page > 0 && page <= episodes.size()) ? page - 1 : 0;
            epId = str(obj(episodes.get(index)), "id");
        }

        String cid = null;
        String epTitle = null;
        URI epCover = null;
        long epDurationMs = 0;
        if (episodes != null) {
            for (int i = 0; i < episodes.size(); i++) {
                final JsonObject ep = obj(episodes.get(i));
                if (epId == null || !epId.equals(str(ep, "id"))) continue;
                cid = str(ep, "cid");
                epTitle = str(ep, "share_copy");
                epCover = DataTool.uri(str(ep, "cover"));
                epDurationMs = Math.max(0L, longOr(ep, "duration", 0L));
                break;
            }
        }
        if (cid == null) throw new PlatformException(NAME, "Episode ep" + epId + " not found in season");

        final JsonObject playResult = fetchJson(String.format(BANGUMI_PLAYURL_API, epId, cid), "result");

        if (intOr(playResult, "is_preview", 0) == 1)
            throw new PlatformException(NAME, "Content requires premium membership (VIP)");

        final String fullTitle = seasonTitle == null ? epTitle : epTitle == null ? seasonTitle : seasonTitle + " - " + epTitle;
        final URI thumbnail = epCover != null ? epCover : DataTool.uri(str(result, "cover"));
        // BANGUMI episode duration IS ALREADY MILLISECONDS (SEE epDurationMs); PASS IT THROUGH UNCHANGED
        final Metadata metadata = new Metadata(fullTitle, str(result, "evaluate"), null, epDurationMs, null);
        return this.buildResult(playResult, metadata, thumbnail);
    }

    private Resolution resolveLive(final URI uri) throws IOException {
        final Matcher roomMatcher = LIVE_ROOM_PATTERN.matcher(uri.toString());
        if (!roomMatcher.find()) throw PlatformException.unsupported(NAME, uri);
        final String roomId = roomMatcher.group(1);
        LOGGER.debug(IT, "BiliBili resolving live room {}", roomId);

        final JsonObject playInfo = fetchJson(String.format(LIVE_PLAY_INFO_API, roomId), "data");
        if (intOr(playInfo, "live_status", 0) != 1) throw new PlatformException(NAME, "Live room " + roomId + " is offline");
        final String realRoomId = str(playInfo, "room_id");
        if (realRoomId == null) throw new PlatformException(NAME, "Live room " + roomId + " reports no room_id");
        final long uid = longOr(playInfo, "uid", 0L);

        // TITLE, COVER AND AUTHOR ARE BEST-EFFORT: THE STREAM PLAYS WITHOUT THEM
        String title = "BiliBili Live";
        URI thumbnail = null;
        try {
            final JsonObject roomInfo = fetchJson(String.format(LIVE_ROOM_INFO_API, realRoomId), "data");
            final String roomTitle = str(roomInfo, "title");
            if (roomTitle != null) title = roomTitle;
            thumbnail = DataTool.uri(str(roomInfo, "keyframe"));
            if (thumbnail == null) thumbnail = DataTool.uri(str(roomInfo, "user_cover"));
        } catch (final PlatformException e) {
            LOGGER.warn(IT, "BiliBili failed to fetch live room title for room {}", realRoomId, e);
        }

        String author = null;
        if (uid > 0) {
            try {
                author = str(obj(fetchJson(String.format(LIVE_USER_INFO_API, uid), "data"), "info"), "uname");
            } catch (final PlatformException e) {
                LOGGER.warn(IT, "BiliBili failed to fetch author name for uid {}", uid, e);
            }
        }

        final JsonArray qualityOptions = arr(fetchJson(String.format(LIVE_PLAYURL_API, realRoomId, 0), "data"), "quality_description");
        final int bestQn = qualityOptions == null || qualityOptions.isEmpty() ? 10_000 : intOr(obj(qualityOptions.get(0)), "qn", 10_000);

        final JsonArray durl = arr(fetchJson(String.format(LIVE_PLAYURL_API, realRoomId, bestQn), "data"), "durl");
        final URI stream = durl == null || durl.isEmpty() ? null : DataTool.uri(str(obj(durl.get(0)), "url"));
        if (stream == null) throw new PlatformException(NAME, "Live room " + realRoomId + " returned no usable stream URL (qn=" + bestQn + ")");

        final Metadata metadata = new Metadata(title, null, null, 0, author);
        final var entry = new Source(MediaType.VIDEO, thumbnail, metadata, headers(),
                List.of(new Quality(stream, 0, 0)),
                null, null);

        LOGGER.debug(IT, "BiliBili resolved live room {} (qn={}, author='{}')", realRoomId, bestQn, author);
        return new Resolution(Instant.now().plus(LIVE_TTL), entry);
    }

    // THE DASH LADDER WITH ITS FIRST AUDIO STREAM, ELSE THE MUXED DURL STREAM
    private Resolution buildResult(final JsonObject data, final Metadata metadata, final URI thumbnail) throws PlatformException {
        final JsonObject dash = obj(data, "dash");
        final JsonArray videoStreams = arr(dash, "video");
        if (videoStreams != null && !videoStreams.isEmpty()) {
            final JsonArray audioStreams = arr(dash, "audio");
            final List<Quality> variants = parseDashVideo(videoStreams, arr(data, "support_formats"));
            LOGGER.debug(IT, "BiliBili DASH: {} raw video stream(s), {} audio stream(s) -> {} variant(s)",
                    videoStreams.size(), audioStreams != null ? audioStreams.size() : 0, variants.size());

            if (!variants.isEmpty()) {
                final URI audio = audioStreams == null || audioStreams.isEmpty() ? null : DataTool.uri(str(obj(audioStreams.get(0)), "baseUrl"));
                if (audio == null) LOGGER.warn(IT, "BiliBili DASH '{}' has no usable audio stream; video will play muted", metadata.title());

                LOGGER.debug(IT, "BiliBili resolved '{}' with {} variant(s) (DASH)", metadata.title(), variants.size());
                return new Resolution(Instant.now().plus(VIDEO_TTL),
                        new Source(MediaType.VIDEO, thumbnail, metadata, headers(),
                                variants, audio == null ? null : List.of(new Track(null, null, audio)), null));
            }
        }

        // FLV/MP4 FALLBACK CARRIES NO PER-RENDITION DIMENSIONS, FFMediaPlayer PROBES THEM
        final JsonArray durl = arr(data, "durl");
        final URI flat = durl == null || durl.isEmpty() ? null : DataTool.uri(str(obj(durl.get(0)), "url"));
        if (flat != null) {
            LOGGER.debug(IT, "BiliBili resolved '{}' with a single muxed stream (DURL fallback)", metadata.title());
            return new Resolution(Instant.now().plus(VIDEO_TTL),
                    new Source(MediaType.VIDEO, thumbnail, metadata, headers(),
                            List.of(new Quality(flat, 0, 0)), null, null));
        }

        throw new PlatformException(NAME, "API response carries no playable streams (neither DASH nor DURL) for '" + metadata.title() + "'");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // WITH support_formats (THE BUCKET INDEX) ONE STREAM PER BUCKET IS PICKED BY CODEC, SO NO DUPLICATES; WITHOUT IT EVERY
    // STREAM IS EMITTED AND MRL COLLAPSES OVERLAPS. STREAM WIDTH/HEIGHT WIN, ELSE THE biliHeight FALLBACK
    private static List<Quality> parseDashVideo(final JsonArray videoStreams, final JsonArray supportFormats) {
        final List<Quality> out = new ArrayList<>();
        if (supportFormats != null && !supportFormats.isEmpty()) {
            for (int i = 0; i < supportFormats.size(); i++) {
                final int qualityId = intOr(obj(supportFormats.get(i)), "quality", 0);
                final Quality variant = toVariant(findBestCodecStream(videoStreams, qualityId), qualityId);
                if (variant != null) out.add(variant);
            }
        } else {
            for (int i = 0; i < videoStreams.size(); i++) {
                final JsonObject stream = obj(videoStreams.get(i));
                final Quality variant = toVariant(stream, intOr(stream, "id", 0));
                if (variant != null) out.add(variant);
            }
        }
        return out;
    }

    // A STREAM WITHOUT A USABLE baseUrl IS NO VARIANT
    private static Quality toVariant(final JsonObject stream, final int qualityId) {
        final URI url = DataTool.uri(str(stream, "baseUrl"));
        if (url == null) return null;
        final int h = intOr(stream, "height", 0);
        return new Quality(url, Math.max(0, intOr(stream, "width", 0)), h > 0 ? h : biliHeight(qualityId));
    }

    // AVC (H.264) > HEVC (H.265) > AV1
    private static JsonObject findBestCodecStream(final JsonArray streams, final int targetId) {
        JsonObject best = null;
        int bestScore = -1;

        for (int i = 0; i < streams.size(); i++) {
            final JsonObject stream = obj(streams.get(i));
            if (intOr(stream, "id", -1) != targetId) continue;

            final String codecs = str(stream, "codecs");
            final int score;
            if (codecs == null) score = 0;
            else if (codecs.contains("avc1")) score = 3;
            else if (codecs.contains("hev1")) score = 2;
            else if (codecs.contains("av01")) score = 1;
            else score = 0;

            if (score > bestScore) {
                best = stream;
                bestScore = score;
            }
        }
        return best;
    }

    // NOMINAL VERTICAL RESOLUTION FOR A BILIBILI quality ID, USED ONLY AS A FALLBACK WHEN THE DASH
    // STREAM OMITS ITS OWN height; THESE ARE BILIBILI'S DOCUMENTED BUCKETS, NOT MRL'S ENUM
    private static int biliHeight(final int biliQuality) {
        return switch (biliQuality) {
            case 127 -> 4_320;           // 8K
            case 126, 125, 120 -> 2_160; // 4K HDR / DOLBY / 4K
            case 116, 112, 80 -> 1_080;  // 1080P60 / 1080P+ / 1080P
            case 74, 64 -> 720;          // 720P60 / 720P
            case 32 -> 480;
            case 16 -> 360;
            case 6 -> 240;
            default -> 0;
        };
    }

    // BILIBILI REJECTS NON-BROWSER UAS AND REQUIRES A bilibili.com REFERER; CDN URLS ADDITIONALLY NEED THE USER'S
    // SESSION COOKIE WHEN FETCHING PREMIUM QUALITIES
    private static HttpHeaders headers() {
        final HttpHeaders headers = new HttpHeaders()
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Accept", NetRequest.ACCEPT_ANY)
                .set("Referer", REFERER);
        final String cookie = WaterMediaConfig.platforms.biliBiliCookie;
        if (cookie != null && !cookie.isEmpty()) headers.set("Cookie", cookie);
        return headers;
    }

    // AN API CALL WITH THE BROWSER UA, REFERER, FINGERPRINT AND SESSION COOKIE IT DEMANDS; A NON-ZERO code IS AN API ERROR
    private static JsonObject fetchJson(final String api, final String dataKey) throws PlatformException {
        final NetRequest request = new NetRequest(api).accept(NetRequest.ACCEPT_JSON)
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(REFERER)
                .header("Cookie", cookie(WaterMediaConfig.platforms.biliBiliCookie, fingerprint()));

        final JsonObject json;
        try { json = WebRequests.fetchJson(NAME, request, JsonObject.class); }
        catch (final PlatformException e) {
            fingerprint = null; // A BANNED FINGERPRINT KEEPS ANSWERING HTTP 412; THE NEXT CALL FETCHES A FRESH ONE
            throw e;
        }
        final int code = intOr(json, "code", 0);
        if (code != 0) {
            if (code == -412) fingerprint = null;
            final String message = str(json, "message");
            throw new PlatformException(NAME, "API error: " + (message != null ? message : "code " + code));
        }

        final JsonObject data = obj(json, dataKey);
        if (data == null) throw new PlatformException(NAME, "API returned no '" + dataKey + "' field");
        return data;
    }

    // THE Cookie OF AN API CALL: THE USER COOKIE ALONE WHEN IT CARRIES ITS OWN buvid3, ELSE THE FETCHED FINGERPRINT
    // FOLLOWED BY THE USER COOKIE (PACKAGE-PRIVATE FOR THE TESTS)
    static String cookie(final String user, final String fingerprint) {
        if (user == null || user.isEmpty()) return fingerprint;
        return user.contains("buvid3=") ? user : fingerprint + "; " + user;
    }

    // buvid3 AND buvid4 FROM THE FINGERPRINT API, FETCHED ONCE; A FAILURE FAILS THE CALL THAT NEEDED THEM
    private static String fingerprint() throws PlatformException {
        String cookie = fingerprint;
        if (cookie != null) return cookie;
        synchronized (FINGERPRINT_LOCK) {
            cookie = fingerprint;
            if (cookie != null) return cookie;
            final NetRequest request = new NetRequest(FINGERPRINT_API).accept(NetRequest.ACCEPT_JSON)
                    .userAgent(NetRequest.UserAgent.GENERIC)
                    .referer(REFERER);
            final JsonObject data = obj(WebRequests.fetchJson(NAME, request, JsonObject.class), "data");
            final String buvid3 = str(data, "b_3");
            final String buvid4 = str(data, "b_4");
            if (buvid3 == null) throw new PlatformException(NAME, "Fingerprint API returned no buvid3");
            cookie = buvid4 == null ? "buvid3=" + buvid3 : "buvid3=" + buvid3 + "; buvid4=" + buvid4;
            fingerprint = cookie;
            LOGGER.debug(IT, "BiliBili fetched the device fingerprint cookie");
            return cookie;
        }
    }
}
