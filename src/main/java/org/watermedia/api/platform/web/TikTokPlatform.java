package org.watermedia.api.platform.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.*;

/** Resolves TikTok videos from the rehydration JSON of their page, solving the WAF proof-of-work challenge when it shows up. */
public final class TikTokPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(TikTokPlatform.class.getSimpleName());
    public static final String NAME = "TikTok";
    private static final String[] HOSTS = { "www.tiktok.com", "tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com" };
    private static final String[] SHORT_HOSTS = { "vm.tiktok.com", "vt.tiktok.com" };
    private static final String TIKTOK_REFERER = "https://www.tiktok.com/";
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("/video/(\\d+)");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("/embed(?:/v2)?/(\\d+)");
    private static final Pattern UNIVERSAL_DATA_PATTERN = Pattern.compile("<script[^>]+id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>\\s*(\\{.*?})\\s*</script>", Pattern.DOTALL);
    private static final Pattern URL_KEY_PATTERN = Pattern.compile("v[^_]+_([^_]+)_(\\d+)p_(\\d+)");
    private static final String WAF_MARKER = "Please wait...";
    // THE CHALLENGE IS A PROOF OF WORK; THE INTERRUPT FLAG IS CHECKED EVERY FEW THOUSAND HASHES
    private static final int CHALLENGE_ATTEMPTS = 1_000_000;
    private static final int CHALLENGE_CHECK_MASK = 0x3FFF;
    // PLAY URLS ARE SIGNED; WHEN THEY CARRY NO expire PARAMETER THIS IS THE FALLBACK
    private static final Duration TTL = Duration.ofMinutes(30);

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI link) throws PlatformException {
        final String host = link.getHost();
        if (!DataTool.equalsAnyIgnoreCase(host, HOSTS)) return null;

        try {
            // SHORT LINKS (vm/vt HOSTS OR /t/ PATHS) REDIRECT TO THE CANONICAL VIDEO PAGE
            final URI shortLink = DataTool.equalsAnyIgnoreCase(host, SHORT_HOSTS) ? WebRequests.follow(link) : link;
            final String path = shortLink.getPath();
            final URI uri = path != null && path.startsWith("/t/") ? WebRequests.follow(shortLink) : shortLink;

            final String videoId = videoId(uri.toString());
            if (videoId == null) throw PlatformException.unsupported(NAME, uri);

            // THE WAF COOKIES STAY LOCAL TO THIS RESOLUTION: CONCURRENT ONES MUST NOT STAMP EACH OTHER'S COOKIES ONTO
            // THEIR SOURCES
            String cookies = null;
            Webpage page = fetchWebpage(uri, null);
            if (page.cookies() != null) cookies = page.cookies();
            JsonObject videoData = this.extractVideoData(page.html(), videoId);

            if (videoData == null && page.html().contains(WAF_MARKER)) {
                LOGGER.debug(IT, "TikTok WAF challenge detected for video {}, solving...", videoId);
                final String challengeCookies = solveChallenge(page.html());
                page = fetchWebpage(uri, challengeCookies);
                if (page.cookies() != null) cookies = page.cookies();
                videoData = this.extractVideoData(page.html(), videoId);
            }

            if (videoData == null) throw new PlatformException(NAME, "No video data found in page for video " + videoId);

            return this.buildResult(videoData, videoId, cookies);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + link, e);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // null MEANS THE PAGE CARRIED NO VIDEO DATA, WHICH IS NORMAL FOR THE FIRST FETCH OF A WAF-CHALLENGED REQUEST;
    // THE CALLER DECIDES WHETHER TO SOLVE THE CHALLENGE OR FAIL
    private JsonObject extractVideoData(final String html, final String videoId) throws PlatformException {
        final Matcher matcher = UNIVERSAL_DATA_PATTERN.matcher(html);
        if (!matcher.find()) {
            LOGGER.debug(IT, "No __UNIVERSAL_DATA_FOR_REHYDRATION__ found for TikTok video {}", videoId);
            return null;
        }

        final JsonObject videoDetail = obj(JSONTool.parse(matcher.group(1), JsonObject.class), "__DEFAULT_SCOPE__", "webapp.video-detail");
        if (videoDetail == null) return null;

        final int status = intOr(videoDetail, "statusCode", 0);
        if (status == 10_216 || status == 10_222) throw new PlatformException(NAME, "Video " + videoId + " is private or requires login");
        if (status == 10_204) throw new PlatformException(NAME, "IP address is blocked from accessing video " + videoId);
        if (status != 0) LOGGER.warn(IT, "TikTok video {} returned status code {}", videoId, status);

        return obj(videoDetail, "itemInfo", "itemStruct");
    }

    private Resolution buildResult(final JsonObject awemeDetail, final String videoId, final String cookies) throws PlatformException {
        final JsonObject video = obj(awemeDetail, "video");
        if (video == null) throw new PlatformException(NAME, "No video object found for video " + videoId);

        final String desc = str(awemeDetail, "desc");
        final String title = (desc != null && !desc.isEmpty()) ? desc : "TikTok video #" + videoId;

        final long createTime = longOr(awemeDetail, "createTime", 0L);
        final Instant publishedAt = createTime > 0 ? Instant.ofEpochSecond(createTime) : null;

        // TIKTOK REPORTS video duration IN SECONDS; Metadata IS MILLISECONDS
        final long durationMs = Math.max(0L, intOr(video, "duration", 0)) * 1_000L;

        final URI thumbnail = findThumbnail(awemeDetail, video);
        final String author = extractAuthor(awemeDetail);

        final Metadata metadata = new Metadata(title, desc, publishedAt, durationMs, author);
        final List<Quality> variants = new ArrayList<>();

        final JsonArray bitrateInfo = arr(video, "bitrateInfo");
        if (bitrateInfo != null) this.parseBitrateInfo(variants, bitrateInfo, video);

        if (variants.isEmpty()) {
            URI fallback = extractAddrUri(video, "playAddr");
            if (fallback == null) fallback = extractAddrUri(video, "downloadAddr");
            if (fallback == null)
                throw new PlatformException(NAME, "Video '" + videoId + "' exposed no playable URLs (bitrateInfo, playAddr, and downloadAddr all empty)");
            // NO RELIABLE DIMENSIONS ON THIS FALLBACK, FFMediaPlayer PROBES AND RE-BUCKETS IT
            LOGGER.debug(IT, "TikTok video '{}' had no usable bitrateInfo, falling back to {}", videoId, fallback);
            variants.add(new Quality(fallback, 0, 0));
        }

        if (author == null) LOGGER.warn(IT, "TikTok video '{}' has no resolvable author", videoId);
        LOGGER.debug(IT, "TikTok resolved video '{}' with {} variant(s)", videoId, variants.size());
        final Instant signed = DataTool.expiry(variants.get(0).uri());

        // THE SIGNED CDN URLS STILL REJECT REQUESTS WITHOUT A BROWSER UA, THE PAGE REFERER AND THE WAF COOKIES OF THE
        // SCRAPE; FFmpeg REPLAYS THESE HEADERS ON EVERY SEGMENT FETCH
        final HttpHeaders headers = new HttpHeaders()
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Accept", NetRequest.ACCEPT_ANY)
                .set("Referer", TIKTOK_REFERER);
        if (cookies != null && !cookies.isEmpty()) headers.set("Cookie", cookies);
        return new Resolution(signed != null ? signed : Instant.now().plus(TTL), new Source(MediaType.VIDEO, thumbnail, metadata, headers, variants, null, null));
    }

    // ONE QUALITY PER bitrateInfo ENTRY, SIZED BY PlayAddr.Width/Height FIRST, THEN THE HEIGHT ENCODED IN UrlKey
    // ("..._720p_..."), FINALLY THE PARENT VIDEO'S SIZE; MRL MAPS THE BUCKETS, NOTHING IS PRE-COLLAPSED HERE
    private void parseBitrateInfo(final List<Quality> out, final JsonArray bitrateInfos, final JsonObject video) {
        final int playWidth = Math.max(0, intOr(video, "width", 0));
        final int playHeight = Math.max(0, intOr(video, "height", 0));

        for (int i = 0; i < bitrateInfos.size(); i++) {
            final JsonObject playAddr = obj(bitrateInfos.get(i), "PlayAddr");
            final URI url = pickBestUrl(playAddr);
            if (url == null) continue;

            final String urlKey = str(playAddr, "UrlKey");
            if (urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find() && "bytevc2".equals(keyMatcher.group(1))) continue;
            }

            int w = Math.max(0, intOr(playAddr, "Width", 0));
            int h = Math.max(0, intOr(playAddr, "Height", 0));

            if (h <= 0 && urlKey != null) {
                final Matcher keyMatcher = URL_KEY_PATTERN.matcher(urlKey);
                if (keyMatcher.find()) h = Math.max(0, DataTool.parseInt(keyMatcher.group(2), 0));
            }
            if (w <= 0 && h <= 0) { w = playWidth; h = playHeight; }

            out.add(new Quality(url, w, h));
        }
        LOGGER.debug(IT, "TikTok parsed {} variant(s) from {} bitrateInfo entry(es)", out.size(), bitrateInfos.size());
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static Webpage fetchWebpage(final URI uri, final String cookies) throws IOException {
        final NetRequest req = new NetRequest(uri)
                .accept("text/html,application/xhtml+xml,*/*")
                .userAgent(NetRequest.UserAgent.GENERIC)
                .referer(TIKTOK_REFERER)
                .header("Accept-Language", "en-US,en;q=0.9");
        if (cookies != null) req.header("Cookie", cookies);

        req.send();
        try (req) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for " + uri + " returned HTTP " + req.statusCode());
            final String html = req.text();

            // THE name=value PAIRS OF EVERY Set-Cookie HEADER; null WHEN THE RESPONSE SET NONE
            final StringBuilder setCookies = new StringBuilder();
            for (final String header: req.responseHeaders().values("Set-Cookie")) {
                final String nameValue = header.split(";", 2)[0].trim();
                if (nameValue.isEmpty()) continue;
                if (!setCookies.isEmpty()) setCookies.append("; ");
                setCookies.append(nameValue);
            }
            return new Webpage(html, setCookies.isEmpty() ? null : setCookies.toString());
        }
    }

    // WAF CHALLENGE SOLVER (PORTED FROM yt-dlp _solve_challenge_and_set_cookies)
    private static String solveChallenge(final String html) throws PlatformException {
        final String csData = elementClass(html, "cs");
        if (csData == null || csData.isEmpty()) throw new PlatformException(NAME, "Unable to extract WAF challenge data");

        // THE CHALLENGE DATA MAY CARRY WRONG OR NO BASE64 PADDING, SO THE PADDING IS REBUILT BEFORE DECODING
        final String unpadded = csData.replaceAll("=+$", "");
        final byte[] csBytes = Base64.getDecoder().decode(unpadded + "=".repeat((4 - unpadded.length() % 4) % 4));
        final JsonObject challengeData = JSONTool.parse(new String(csBytes, StandardCharsets.UTF_8), JsonObject.class);
        final JsonObject v = obj(challengeData, "v");
        final String a = str(v, "a");
        final String c = str(v, "c");
        if (a == null || c == null) throw new PlatformException(NAME, "WAF challenge data carries no seed or digest");
        final byte[] seed = Base64.getDecoder().decode(a);
        final byte[] expectedDigest = Base64.getDecoder().decode(c);

        boolean solved = false;
        try {
            final MessageDigest baseDigest = MessageDigest.getInstance("SHA-256");
            baseDigest.update(seed);
            for (int i = 0; i <= CHALLENGE_ATTEMPTS; i++) {
                if ((i & CHALLENGE_CHECK_MASK) == 0 && Thread.currentThread().isInterrupted())
                    throw new PlatformException(NAME, "Interrupted while solving the WAF challenge");
                final MessageDigest testDigest = (MessageDigest) baseDigest.clone();
                testDigest.update(String.valueOf(i).getBytes(StandardCharsets.UTF_8));
                if (MessageDigest.isEqual(testDigest.digest(), expectedDigest)) {
                    challengeData.addProperty("d", Base64.getEncoder().encodeToString(String.valueOf(i).getBytes(StandardCharsets.UTF_8)));
                    solved = true;
                    LOGGER.debug(IT, "TikTok WAF challenge solved at iteration {}", i);
                    break;
                }
            }
        } catch (final NoSuchAlgorithmException | CloneNotSupportedException e) {
            // SHA-256 IS MANDATED BY THE JVM SPEC
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }

        if (!solved) throw new PlatformException(NAME, "Unable to solve WAF challenge");

        final String cookieValue = Base64.getEncoder().encodeToString(challengeData.toString().getBytes(StandardCharsets.UTF_8));
        String cookieName = elementClass(html, "wci");
        if (cookieName == null || cookieName.isEmpty()) cookieName = "_wafchallengeid";
        final StringBuilder cookies = new StringBuilder(cookieName).append('=').append(cookieValue);

        final String rciName = elementClass(html, "rci");
        final String rciValue = elementClass(html, "rs");
        if (rciName != null && !rciName.isEmpty() && rciValue != null && !rciValue.isEmpty())
            cookies.append("; ").append(rciName).append('=').append(rciValue);

        return cookies.toString();
    }

    // THE class ATTRIBUTE OF THE <p> ELEMENT WITH THE GIVEN id, WHERE THE CHALLENGE PAGE HIDES ITS VALUES
    private static String elementClass(final String html, final String id) {
        final Matcher tagMatcher = Pattern.compile("<p\\s([^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*)>").matcher(html);
        if (!tagMatcher.find()) return null;

        final Matcher classMatcher = Pattern.compile("\\bclass=\"([^\"]+)\"").matcher(tagMatcher.group(1));
        return classMatcher.find() ? classMatcher.group(1) : null;
    }

    // THE FIRST CDN LINK OF A UrlList; A www.tiktok.com LINK IS ONLY THE FALLBACK
    private static URI pickBestUrl(final JsonObject playAddr) {
        final JsonArray urlList = arr(playAddr, "UrlList");
        if (urlList == null) return null;

        URI fallback = null;
        for (final JsonElement element: urlList) {
            final URI candidate = element.isJsonPrimitive() ? DataTool.uri(element.getAsString()) : null;
            if (candidate == null) continue;
            if (!"www.tiktok.com".equalsIgnoreCase(candidate.getHost())) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    // playAddr/downloadAddr COME AS A BARE URL, AN ARRAY OF {src} OR AN OBJECT WITH A UrlList OR A src
    private static URI extractAddrUri(final JsonObject video, final String key) {
        final JsonElement element = video.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return DataTool.uri(element.getAsString());

        if (element.isJsonArray()) {
            for (final JsonElement item: element.getAsJsonArray()) {
                final URI src = DataTool.uri(str(obj(item), "src"));
                if (src != null) return src;
            }
            return null;
        }

        final URI listed = pickBestUrl(obj(element));
        return listed != null ? listed : DataTool.uri(str(obj(element), "src"));
    }

    private static URI findThumbnail(final JsonObject awemeDetail, final JsonObject video) {
        for (final String key: new String[]{"originCover", "cover", "thumbnail"}) {
            URI uri = DataTool.uri(str(video, key));
            if (uri != null) return uri;
            uri = DataTool.uri(str(awemeDetail, key));
            if (uri != null) return uri;
        }
        return null;
    }

    private static String extractAuthor(final JsonObject awemeDetail) {
        for (final String key: new String[]{"author", "authorInfo"}) {
            final JsonObject author = obj(awemeDetail, key);
            final String nickname = str(author, "nickname");
            if (nickname != null) return nickname;
            final String uniqueId = str(author, "uniqueId");
            if (uniqueId != null) return uniqueId;
        }
        return null;
    }

    // THE NUMERIC ID OF A /video/<id> OR /embed[/v2]/<id> LINK, OR NULL FOR ANY OTHER SHAPE
    static String videoId(final String uri) {
        final Matcher video = VIDEO_ID_PATTERN.matcher(uri);
        if (video.find()) return video.group(1);
        final Matcher embed = EMBED_ID_PATTERN.matcher(uri);
        return embed.find() ? embed.group(1) : null;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // FETCHED PAGE HTML PAIRED WITH THE WAF COOKIES CAPTURED FROM ITS Set-Cookie RESPONSE (null WHEN NONE)
    private record Webpage(String html, String cookies) {}
}
