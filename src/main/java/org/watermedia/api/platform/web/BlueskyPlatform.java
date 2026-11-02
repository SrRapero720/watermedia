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
import org.watermedia.api.util.Track;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.tools.JSONTool.arr;
import static org.watermedia.tools.JSONTool.intOr;
import static org.watermedia.tools.JSONTool.obj;
import static org.watermedia.tools.JSONTool.str;

/** Resolves Bluesky posts (web or {@code at://} links) through the public XRPC API into their videos and images, quoted posts included. */
public final class BlueskyPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(BlueskyPlatform.class.getSimpleName());
    public static final String NAME = "Bluesky";
    private static final String[] HOSTS = { "bsky.app", "www.bsky.app", "main.bsky.dev", "www.main.bsky.dev" };
    private static final String API_URL = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread";
    private static final String BLOB_URL_TMPL = "%s/xrpc/com.atproto.sync.getBlob?did=%s&cid=%s";
    private static final String PLC_DIRECTORY_URL = "https://plc.directory/%s";
    private static final String DEFAULT_PDS = "https://bsky.social";
    private static final Pattern WEB_PATTERN = Pattern.compile("/profile/([\\w.:%-]+)/post/(\\w+)");
    private static final Pattern AT_PATTERN = Pattern.compile("at://([\\w.:%-]+)/app\\.bsky\\.feed\\.post/(\\w+)", Pattern.CASE_INSENSITIVE);
    // LABELS THE NETWORK APPLIES TO ADULT AND GRAPHIC POSTS
    private static final Set<String> MATURE_LABELS = Set.of("porn", "sexual", "nudity", "graphic-media");
    private static final int TITLE_LENGTH = 72;
    // VIDEO PLAYLISTS ARE SERVED THROUGH A CDN WITH SHORT-LIVED SESSION TOKENS
    private static final Duration TTL = Duration.ofMinutes(30);

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        final String handle, postId;
        if ("at".equalsIgnoreCase(uri.getScheme())) {
            final Matcher m = AT_PATTERN.matcher(uri.toString());
            if (!m.matches()) throw PlatformException.unsupported(NAME, uri);
            handle = m.group(1);
            postId = m.group(2);
        } else {
            if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
            final Matcher m = WEB_PATTERN.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!m.find()) throw PlatformException.unsupported(NAME, uri);
            handle = m.group(1);
            postId = m.group(2);
        }

        LOGGER.debug(IT, "Fetching Bluesky post '{}' by '{}'", postId, handle);
        try {
            // RESPONSE SHAPE: { thread: { post: {...} } }; A BLOCKED/NOT-FOUND THREAD OMITS "post"
            final String atUri = "at://" + handle + "/app.bsky.feed.post/" + postId;
            final JsonObject post = obj(WebRequests.fetchJson(NAME, API_URL + "?uri=" + enc(atUri) + "&depth=0&parentHeight=0", JsonObject.class), "thread", "post");
            if (post == null)
                throw new PlatformException(NAME, "Post '" + postId + "' by '" + handle + "' was not found, blocked, or deleted");
            ensureNotMature(post, postId);
            final HttpHeaders headers = HttpHeaders.defaults(uri);

            final List<Source> entries = new ArrayList<>(2);
            final JsonObject embed = obj(post, "embed");
            final JsonObject record = obj(post, "record");

            // DIRECT EMBED (app.bsky.embed.video#view OR app.bsky.embed.images#view)
            extractVideo(entries, post, embed, obj(record, "embed"), postId, headers);
            extractImages(entries, post, embed, postId, headers);

            // RECORD WITH MEDIA (app.bsky.embed.recordWithMedia#view): THE MEDIA LIVES IN embed.media
            final JsonObject mediaEmbed = obj(embed, "media");
            if (mediaEmbed != null) {
                extractVideo(entries, post, mediaEmbed, obj(record, "embed", "media"), postId, headers);
                extractImages(entries, post, mediaEmbed, postId, headers);
            }

            // QUOTED POST (app.bsky.embed.record#view): THE MEDIA LIVES IN THE NESTED POST'S EMBEDS; A recordWithMedia
            // WRAPS THE QUOTED VIEW ONE LEVEL DEEPER
            final JsonObject quoted = obj(embed, "record");
            final JsonObject nestedPost = quoted == null ? null : Objects.requireNonNullElse(obj(quoted, "record"), quoted);
            if (nestedPost != null) {
                ensureNotMature(nestedPost, postId);
                final JsonArray embeds = arr(nestedPost, "embeds");
                final JsonObject nestedEmbed = embeds == null || embeds.isEmpty() ? null : obj(embeds.get(0));
                if (nestedEmbed != null) {
                    extractVideo(entries, nestedPost, nestedEmbed, obj(nestedPost, "value", "embed"), postId, headers);
                    extractImages(entries, nestedPost, nestedEmbed, postId, headers);
                }
            }

            if (entries.isEmpty())
                throw new PlatformException(NAME, "Post '" + postId + "' has no playable media (text-only, external link, or unsupported embed): " + uri);

            LOGGER.debug(IT, "Bluesky resolved {} media entry(es) for post '{}'", entries.size(), postId);
            return new Resolution(Instant.now().plus(TTL), entries);
        } catch (final RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE NETWORK'S LABELS TRAVEL ON THE POST VIEW AND ON ITS AUTHOR; THE GATE RUNS BEFORE ANY MEDIA IS TOUCHED, FOR
    // THE POST ITSELF AND FOR THE POST IT QUOTES
    private static void ensureNotMature(final JsonObject post, final String postId) throws MatureContentException {
        if (WaterMediaConfig.platforms.allowMatureContent) return;
        for (final JsonArray labels: new JsonArray[] { arr(post, "labels"), arr(obj(post, "author"), "labels") }) {
            if (labels == null) continue;
            for (final JsonElement label: labels) {
                final String value = str(obj(label), "val");
                if (value != null && MATURE_LABELS.contains(value))
                    throw new MatureContentException(NAME, "Post '" + postId + "' is labelled '" + value + "'");
            }
        }
    }

    private static void extractVideo(final List<Source> entries, final JsonObject postCtx, final JsonObject embedView,
                                     final JsonObject recordEmbed, final String postId, final HttpHeaders headers) {
        final String playlist = str(embedView, "playlist");
        if (playlist == null) return;

        final URI playlistUri = DataTool.uri(playlist);
        if (playlistUri == null) {
            LOGGER.warn(IT, "Bluesky post '{}' carries a video without a usable playlist URL; skipping it", postId);
            return;
        }

        // SUBTITLE TRACKS THROUGH THE BLOB ENDPOINT OF THE AUTHOR'S PDS
        final List<Track> subtitleTracks = new ArrayList<>();
        final String did = str(obj(postCtx, "author"), "did");
        final JsonArray captions = arr(recordEmbed, "captions");
        if (did != null && captions != null && !captions.isEmpty()) {
            final String endpoint = serviceEndpoint(did);
            for (final JsonElement element: captions) {
                final JsonObject cap = obj(element);
                final String fileCid = str(obj(cap, "file", "ref"), "$link");
                if (fileCid == null) continue;
                // THE ENDPOINT COMES FROM A REMOTE DID DOCUMENT, SO ONLY A WEB URL IS ACCEPTED
                final URI blob = DataTool.uri(String.format(BLOB_URL_TMPL, endpoint, enc(did), enc(fileCid)));
                if (blob == null) {
                    LOGGER.warn(IT, "Bluesky post '{}' names an unusable PDS endpoint '{}'; skipping its captions", postId, endpoint);
                    break;
                }
                final String lang = Objects.requireNonNullElse(str(cap, "lang"), "und");
                subtitleTracks.add(new Track(lang, lang, blob));
            }
        }

        entries.add(new Source(MediaType.VIDEO, DataTool.uri(str(embedView, "thumbnail")), metadata(postCtx, "Bluesky video #" + postId),
                headers, WebRequests.hls(playlistUri, headers), null, subtitleTracks.isEmpty() ? null : subtitleTracks));
    }

    private static void extractImages(final List<Source> entries, final JsonObject postCtx, final JsonObject embedView,
                                      final String postId, final HttpHeaders headers) {
        final JsonArray images = arr(embedView, "images");
        if (images == null) return;

        final Metadata metadata = metadata(postCtx, "Bluesky image #" + postId);
        for (final JsonElement element: images) {
            final JsonObject img = obj(element);
            if (img == null) continue;

            // ONE BROKEN PICTURE MUST NOT TAKE THE REST OF THE POST DOWN
            final URI fullsize = DataTool.uri(str(img, "fullsize"));
            if (fullsize == null) {
                LOGGER.warn(IT, "Bluesky post '{}' carries an image without a usable URL; skipping it", postId);
                continue;
            }

            final JsonObject aspectRatio = obj(img, "aspectRatio");
            entries.add(new Source(MediaType.IMAGE, DataTool.uri(str(img, "thumb")), metadata, headers,
                    List.of(new Quality(fullsize, Math.max(0, intOr(aspectRatio, "width", 0)), Math.max(0, intOr(aspectRatio, "height", 0)))),
                    null, null));
        }
    }

    // ONE METADATA PER POST: ITS TEXT (A QUOTED POST KEEPS IT UNDER value) TITLES IT AND ITS AUTHOR SIGNS IT
    private static Metadata metadata(final JsonObject postCtx, final String fallbackTitle) {
        String text = str(obj(postCtx, "record"), "text");
        if (text == null) text = str(obj(postCtx, "value"), "text");
        final String oneLine = text == null ? null : text.replace('\n', ' ');
        final String title = oneLine == null || oneLine.isBlank() ? fallbackTitle
                : oneLine.length() > TITLE_LENGTH ? oneLine.substring(0, TITLE_LENGTH) + "..." : oneLine;

        Instant indexedAt = null;
        final String indexed = str(postCtx, "indexedAt");
        if (indexed != null) {
            try { indexedAt = Instant.parse(indexed); }
            catch (final DateTimeParseException e) { LOGGER.warn(IT, "Bluesky unparseable indexedAt '{}'", indexed, e); }
        }

        final JsonObject author = obj(postCtx, "author");
        final String displayName = str(author, "displayName");
        return new Metadata(title, text, indexedAt, 0, displayName != null ? displayName : str(author, "handle"));
    }

    // PDS HOSTING THE AUTHOR'S BLOBS, READ FROM ITS DID DOCUMENT; ANY FAILURE FALLS BACK TO THE DEFAULT PDS
    private static String serviceEndpoint(final String did) {
        final String url = did.startsWith("did:web:")
                ? "https://" + did.substring(8) + "/.well-known/did.json"
                : String.format(PLC_DIRECTORY_URL, did);
        try {
            final JsonArray services = arr(WebRequests.fetchJson(NAME, url, JsonObject.class), "service");
            if (services != null) {
                for (final JsonElement element: services) {
                    final JsonObject svc = obj(element);
                    final String endpoint = str(svc, "serviceEndpoint");
                    if (endpoint != null && "AtprotoPersonalDataServer".equals(str(svc, "type"))) return endpoint;
                }
            }
            LOGGER.warn(IT, "Bluesky DID document of '{}' names no PDS; using the default one", did);
        } catch (final PlatformException e) {
            LOGGER.warn(IT, "Bluesky failed to resolve the service endpoint for '{}'; using the default PDS", did, e);
        }
        return DEFAULT_PDS;
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
