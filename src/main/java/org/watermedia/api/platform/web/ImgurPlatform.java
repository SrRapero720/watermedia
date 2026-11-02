package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
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
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves Imgur images, albums and gallery posts through the API v3, and searches its galleries. */
public final class ImgurPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(ImgurPlatform.class.getSimpleName());
    public static final String NAME = "Imgur";
    private static final String[] HOSTS = { "imgur.com", "www.imgur.com", "m.imgur.com" };
    private static final String API_URL = "https://api.imgur.com/3";
    private static final String API_KEY = "685cdf74b1229b9";

    // URLS FORMAT
    private static final String IMAGE_URL = API_URL + "/image/%s?client_id=" + API_KEY;
    private static final String GALLERY_URL = API_URL + "/gallery/%s?client_id=" + API_KEY;
    private static final String ALBUM_URL = API_URL + "/album/%s?client_id=" + API_KEY;
    private static final String SEARCH_URL = API_URL + "/gallery/search/top/all/0?client_id=" + API_KEY + "&q=%s";

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        // i.imgur.com IS EXCLUDED, THOSE ARE ALREADY STATIC
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final var path = uri.getPath();
        if (path == null || path.length() <= 1) throw PlatformException.unsupported(NAME, uri);

        final var pathSplit = path.substring(1).split("/");

        // POST KINDS BY PATH: /a/{id} IS AN ALBUM (MAY BE HIDDEN, SO MISSING FROM THE GALLERY API),
        // /gallery/{id} AND /t/{tag}/{id} ARE GALLERY POSTS; ANYTHING ELSE IS A BARE IMAGE ID
        final boolean isAlbum = path.startsWith("/a/");
        final boolean isGallery = path.startsWith("/gallery/") || (pathSplit.length >= 3 && pathSplit[0].equals("t"));

        // LAST SEGMENT CARRIES THE ID; MODERN URLS PREFIX A TITLE SLUG (title-slug-id)
        // AND SOME LINKS CARRY AN EXTENSION (id.png)
        var last = pathSplit[pathSplit.length - 1];
        final int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        final var idSplit = last.split("-");
        final var id = idSplit[idSplit.length - 1];
        if (id.isEmpty()) throw PlatformException.unsupported(NAME, uri);
        LOGGER.debug(IT, "Imgur parsed id='{}', album={}, gallery={} from {}", id, isAlbum, isGallery, uri);

        try {
            if (isAlbum || isGallery) {
                final String requestUrl = String.format(isAlbum ? ALBUM_URL : GALLERY_URL, id);

                // GALLERY POSTS COME IN TWO SHAPES: ALBUMS CARRY data.images[] WHILE SINGLE-IMAGE
                // POSTS CARRY THE IMAGE FIELDS DIRECTLY ON data: BIND THE ENVELOPE FIRST, THEN PICK
                final JsonObject root = WebRequests.fetchJson(NAME, requestUrl, JsonObject.class);
                final GalleryResponse res = JSONTool.parse(root, GalleryResponse.class);
                if (!res.success() || res.data() == null)
                    throw new PlatformException(NAME, "Post '" + id + "' response was empty or unsuccessful (status " + res.status() + ")");

                final Gallery data = res.data();
                if (Boolean.TRUE.equals(data.nsfw()) && !WaterMediaConfig.platforms.allowMatureContent)
                    throw new MatureContentException(NAME, "Post '" + id + "' is marked as mature content");

                // SINGLE-IMAGE POST: data IS THE IMAGE ITSELF
                if (data.images() == null && !data.isAlbum()) {
                    final Image img = JSONTool.parse(root.get("data"), Image.class);
                    LOGGER.debug(IT, "Imgur resolved single-image post '{}' ({})", id, img.type());
                    return new Resolution(null, this.entry(img, img.title(), img.accountUrl(), uri));
                }

                if (data.images() == null || data.images().length == 0)
                    throw new PlatformException(NAME, "Post '" + id + "' contains no images");

                // ONE BAD ITEM MUST NOT TAKE THE ALBUM DOWN; AN ALBUM WITH NOTHING LEFT REPORTS ITS FIRST FAILURE,
                // SO A FULLY MATURE ONE STAYS BLOCKED
                final List<Source> entries = new ArrayList<>(data.images().length);
                PlatformException skipped = null;
                for (final Image img: data.images()) {
                    try {
                        entries.add(this.entry(img, data.title(), data.accountUrl(), uri));
                    } catch (final PlatformException | RuntimeException e) {
                        LOGGER.warn(IT, "Imgur skipped item '{}' of post '{}'", img == null ? null : img.id(), id, e);
                        if (skipped == null) skipped = e instanceof final PlatformException platform ? platform
                                : new PlatformException(NAME, "Unexpected item in post '" + id + "'", e);
                    }
                }
                if (entries.isEmpty()) throw skipped;

                LOGGER.debug(IT, "Imgur resolved post '{}' with {} entry(es)", id, entries.size());
                return new Resolution(null, entries);
            }

            // SIMPLE IMAGE
            final String requestUrl = String.format(IMAGE_URL, id);
            final ImageResponse res = WebRequests.fetchJson(NAME, requestUrl, ImageResponse.class);
            if (!res.success() || res.data() == null)
                throw new PlatformException(NAME, "Image '" + id + "' response was empty or unsuccessful (status " + res.status() + ")");

            final Image img = res.data();
            LOGGER.debug(IT, "Imgur resolved image '{}' ({})", id, img.type());
            return new Resolution(null, this.entry(img, img.title(), img.accountUrl(), uri));
        } catch (final RuntimeException e) {
            throw new PlatformException(NAME, "Unexpected API response for " + uri, e);
        }
    }

    @Override
    public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        final NetRequest request = new NetRequest(String.format(SEARCH_URL, URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .accept(NetRequest.ACCEPT_JSON).connectTimeout(PlatformAPI.SEARCH_TIMEOUT_MS).readTimeout(PlatformAPI.SEARCH_TIMEOUT_MS);
        final SearchResponse res = WebRequests.fetchJson(NAME, request, SearchResponse.class);
        if (!res.success() || res.data() == null) return List.of();

        final List<PlatformResult> out = new ArrayList<>(Math.min(res.data().length, limit));
        for (final SearchItem item: res.data()) {
            if (out.size() >= limit) break;
            if (item.id() == null) continue;
            // ALBUM POSTS HAVE NO IMAGE OF THEIR OWN, SO THE COVER THUMBNAILS THEM; SINGLE IMAGES THUMBNAIL THEMSELVES.
            // THE 'm' SUFFIX (MEDIUM, 320px) GOES BEFORE THE EXTENSION AND RETURNS A STATIC FRAME EVEN FOR GIFS
            final String thumbId = item.isAlbum() && item.cover() != null ? item.cover() : item.id();
            final URI page = DataTool.uri("https://imgur.com/gallery/" + item.id());
            if (page == null) continue;
            out.add(new PlatformResult(this, item.title(), DataTool.uri("https://i.imgur.com/" + thumbId + "m.jpg"), page));
        }
        return out;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Source entry(final Image img, final String fallbackTitle, final String accountUrl, final URI uri) throws PlatformException {
        if (Boolean.TRUE.equals(img.nsfw()) && !WaterMediaConfig.platforms.allowMatureContent)
            throw new MatureContentException(NAME, "Item '" + img.id() + "' is marked as mature content");

        final MediaType type = MediaType.of(img.type());
        // IMGUR REPORTS "image/gif" FOR ITEMS IT SERVES AS mp4, WHICH PLAY AS VIDEO
        final String link = switch (type) {
            case IMAGE -> img.link();
            case VIDEO -> img.hasSound() ? img.mp4() : img.link();
            default -> {
                LOGGER.warn(IT, "Imgur item '{}' has unexpected media type '{}', defaulting to its link", img.id(), img.type());
                yield img.link();
            }
        };

        // account_url IS THE UPLOADER'S USERNAME, NOT A LINK
        final String title = img.title() != null ? img.title() : fallbackTitle;
        final Instant date = img.datetime() > 0 ? Instant.ofEpochSecond(img.datetime()) : null;
        final Metadata metadata = new Metadata(title, img.description(), date, 0, accountUrl);

        final URI target = DataTool.uri(link);
        if (target == null)
            throw new PlatformException(NAME, "Item '" + img.id() + "' (" + img.type() + ") has no usable link: " + link);

        return new Source(type, null, metadata,
                HttpHeaders.defaults(uri),
                List.of(new Quality(target, Math.max(0, img.width()), Math.max(0, img.height()))),
                null, null);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // DATA RECORDS BIND ONLY THE CONSUMED FIELDS: IMGUR SENDS null FOR MANY NUMERIC FIELDS
    // DEPENDING ON THE ENDPOINT, AND A null INTO A PRIMITIVE RECORD COMPONENT MAKES GSON THROW
    private record ImageResponse(Image data, boolean success, int status) {}
    private record GalleryResponse(Gallery data, boolean success, int status) {}
    // GALLERY SEARCH RETURNS A MIXED ARRAY OF ALBUMS AND SINGLE IMAGES; ONLY THE DISPLAY FIELDS ARE BOUND
    private record SearchResponse(SearchItem[] data, boolean success, int status) {}
    private record SearchItem(String id, String title, String cover, @SerializedName("is_album") boolean isAlbum) {}

    private record Gallery(
            String title,
            @SerializedName("account_url") String accountUrl,
            @SerializedName("is_album") boolean isAlbum,
            Boolean nsfw,
            Image[] images
    ) {}

    private record Image(
            String id,
            @SerializedName("account_url") String accountUrl,
            String title,
            String description,
            String type,
            int width,
            int height,
            long datetime,
            Boolean nsfw,
            @SerializedName("has_sound") boolean hasSound,
            String link,
            String mp4
    ) {}
}
