package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.NetRequest;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Resolves Medal.tv clips through the public, unauthenticated {@code https://medal.tv/api/content/<id>} endpoint.
 * Playback prefers the HLS ladder ({@code contentUrlHls}), then the source MP4 ({@code contentUrl}), then the
 * social-share MP4 ({@code socialMediaVideo}). The {@code contentUrl<height>p} rungs are ignored: without a real
 * transcode Medal hands back the source file for each of them, so they are duplicates rather than qualities.
 */
public final class MedalPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(MedalPlatform.class.getSimpleName());
    public static final String NAME = "Medal";
    private static final String[] HOSTS = { "medal.tv", "www.medal.tv" };
    private static final String API_URL = "https://medal.tv/api/content/%s";
    // MEDAL SWAPS THE REAL CLIP FOR THIS PLACEHOLDER FILE WHEN A GUEST IS NOT ALLOWED TO WATCH IT
    private static final String PRIVACY_PROTECTED = "video/privacy-protected-guest";

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        // MEDAL HAS MANY PAGE TYPES (PROFILES, GAME HUBS, THE HOME FEED); ONLY A CLIP URL CARRIES AN ID WE CAN RESOLVE
        final String clipId = clipId(uri);
        if (clipId == null) throw PlatformException.unsupported(NAME, uri);

        LOGGER.debug(IT, "Medal resolving clip '{}' from {}", clipId, uri);
        // A REMOVED, PRIVATE OR MALFORMED ID ANSWERS 4xx (BAD IDS COME BACK AS 400, NOT 404)
        final Content content = WebRequests.fetchJson(NAME, String.format(API_URL, clipId), Content.class);

        // A TAKEN-DOWN OR LOGIN-WALLED CLIP ONLY EXPOSES PLACEHOLDERS
        if (content.dmcaTakedown)
            throw new PlatformException(NAME, "Clip '" + clipId + "' was removed by a DMCA takedown");
        if (content.requireLogin)
            throw new PlatformException(NAME, "Clip '" + clipId + "' requires login (private or restricted)");

        // PREFERRED: THE HLS MASTER IS MEDAL'S ONLY REAL LADDER (source/540p/360p/240p); A FAILED FETCH OR PARSE
        // FALLS BACK TO THE MASTER URL ITSELF SO FFmpeg CAN STILL PROBE IT
        final HttpHeaders headers = HttpHeaders.defaults(uri);
        final URI hls = DataTool.uri(content.contentUrlHls);
        final List<Quality> variants = hls == null ? new ArrayList<>() : new ArrayList<>(WebRequests.hls(hls, headers));
        variants.removeIf(q -> privacyProtected(q.uri()));
        // FALLBACK: A PROGRESSIVE SOURCE MP4 FOR CLIPS MEDAL NEVER PACKAGED AS HLS (CARRIES REAL SOURCE DIMENSIONS)
        if (variants.isEmpty()) {
            final URI progressive = DataTool.uri(content.contentUrl);
            if (progressive != null && !privacyProtected(progressive))
                variants.add(new Quality(progressive, Math.max(0, content.sourceWidth), Math.max(0, content.sourceHeight)));
        }
        // LAST RESORT: THE PRE-RENDERED SOCIAL-SHARE MP4 (NO RELIABLE DIMENSIONS, SO FFMediaPlayer PROBES THEM)
        if (variants.isEmpty()) {
            final URI social = DataTool.uri(content.socialMediaVideo);
            if (social != null && !privacyProtected(social))
                variants.add(new Quality(social, 0, 0));
        }
        if (variants.isEmpty())
            throw new PlatformException(NAME, "Clip '" + clipId + "' exposes no playable source (login-walled, region-locked, or still processing)");

        final String title = content.contentTitle != null && !content.contentTitle.isBlank() ? content.contentTitle : null;
        final String desc = content.contentDescription != null && !content.contentDescription.isBlank() ? content.contentDescription : null;
        final Instant postedAt = content.created > 0 ? Instant.ofEpochMilli(content.created) : null;
        final long durationMs = (long) Math.max(0d, content.videoLengthSeconds * 1_000d);
        final String author = content.poster != null
                ? (content.poster.displayName != null ? content.poster.displayName : content.poster.userName)
                : null;
        if (author == null) LOGGER.warn(IT, "Medal clip '{}' has no resolvable author", clipId);

        final Metadata metadata = new Metadata(title, desc, postedAt, durationMs, author);
        final URI thumbnail = DataTool.uri(content.thumbnailUrl);

        // MEDAL SIGNS EVERY CDN URL WITH ONE SHARED EXPIRY; HONOR IT SO MRL RE-RESOLVES BEFORE THE LINKS DIE
        final Instant expires = content.urlsExpireAt > 0 ? Instant.ofEpochMilli(content.urlsExpireAt) : null;

        LOGGER.debug(IT, "Medal resolved clip '{}' with {} variant(s)", clipId, variants.size());
        final var entry = new Source(MediaType.VIDEO, thumbnail, metadata,
                headers,
                variants,
                null, null);
        return new Resolution(expires, entry);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // RESOLVES THE CLIP ID FROM EVERY KNOWN MEDAL SHAPE: /games/<slug>/clips/<id>, /<locale>/games/<slug>/clips/<id>,
    // THE SHORT /clip|/clips/<id> REDIRECTS, AND THE /?contentId=<id> QUERY; NULL FOR NON-CLIP MEDAL URLS
    private static String clipId(final URI uri) {
        final String path = uri.getPath();
        if (path != null && path.length() > 1) {
            final String[] seg = path.substring(1).split("/");
            for (int i = 0; i < seg.length - 1; i++) {
                if ((seg[i].equalsIgnoreCase("clips") || seg[i].equalsIgnoreCase("clip")) && !seg[i + 1].isEmpty())
                    return seg[i + 1];
            }
        }
        final String contentId = NetRequest.query(uri).get("contentId");
        return contentId == null || contentId.isEmpty() ? null : contentId;
    }

    private static boolean privacyProtected(final URI uri) {
        return uri.toString().contains(PRIVACY_PROTECTED);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // ONLY THE FIELDS ACTUALLY CONSUMED ARE BOUND; GSON IGNORES THE REST OF MEDAL'S LARGE PAYLOAD
    private record Content(String contentTitle, String contentDescription, String contentUrl, String contentUrlHls,
                           String socialMediaVideo, String thumbnailUrl, int sourceWidth, int sourceHeight,
                           double videoLengthSeconds, long created, long urlsExpireAt, boolean requireLogin,
                           boolean dmcaTakedown, Poster poster) {}
    private record Poster(String displayName, String userName) {}
}
