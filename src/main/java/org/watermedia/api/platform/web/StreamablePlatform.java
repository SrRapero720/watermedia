package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves Streamable share and embed links through the public videos API. */
public final class StreamablePlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(StreamablePlatform.class.getSimpleName());
    public static final String NAME = "Streamable";
    private static final String[] HOSTS = { "streamable.com", "www.streamable.com" };
    private static final String API_URL = "https://api.streamable.com/videos/";
    // STREAMABLE STATUS CODES: 0=UPLOADING, 1=PROCESSING, 2=READY, 3=ERROR
    private static final int STATUS_READY = 2;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        if (uri.getPath() == null || uri.getPath().length() < 2) throw PlatformException.unsupported(NAME, uri);

        // SHARE LINKS ARE /<id>, EMBEDS /e/<id> OR /o/<id> AND /s/<id>/<slug> CARRIES A TITLE; ANY OTHER SHAPE IS UNSUPPORTED
        final String[] segments = uri.getPath().substring(1).split("/");
        final boolean prefixed = segments.length > 1 && DataTool.equalsAnyIgnoreCase(segments[0], "e", "o", "s");
        if (segments.length > (prefixed ? ("s".equalsIgnoreCase(segments[0]) ? 3 : 2) : 1)) throw PlatformException.unsupported(NAME, uri);
        final String videoId = prefixed ? segments[1] : segments[0];
        if (videoId.isEmpty()) throw PlatformException.unsupported(NAME, uri);

        final VideoData video = WebRequests.fetchJson(NAME, API_URL + videoId, VideoData.class);
        LOGGER.debug(IT, "Streamable video '{}' raw status={} percent={}", videoId, video.status, video.percent);

        // A VIDEO STILL PROCESSING HAS NO files YET
        if (video.status != STATUS_READY)
            throw new PlatformException(NAME, "Video '" + videoId + "' is not ready (status=" + video.status
                    + ", " + video.percent + "%" + (video.message != null ? ", message: " + video.message : "") + ")");

        final URI main = video.files == null || video.files.mp4 == null ? null : DataTool.uri(video.files.mp4.url);
        if (main == null) throw new PlatformException(NAME, "Video '" + videoId + "' is marked ready but exposes no mp4 rendition");

        final List<Quality> variants = new ArrayList<>(2);
        variants.add(new Quality(main, Math.max(0, video.files.mp4.width), Math.max(0, video.files.mp4.height)));
        final URI small = video.files.mp4Small == null ? null : DataTool.uri(video.files.mp4Small.url);
        if (small != null) variants.add(new Quality(small, Math.max(0, video.files.mp4Small.width), Math.max(0, video.files.mp4Small.height)));

        // THE THUMBNAIL IS PROTOCOL-RELATIVE; DataTool.uri PROMOTES IT TO https
        final URI thumbnailUri = DataTool.uri(video.thumbnail);
        if (thumbnailUri == null) LOGGER.warn(IT, "Streamable video '{}' has no thumbnail", videoId);

        final long durationMs = video.files.original != null ? (long) Math.max(0f, video.files.original.duration * 1_000f) : 0L;
        final Metadata metadata = new Metadata(video.title, null, null, durationMs, null);

        LOGGER.debug(IT, "Streamable resolved video '{}' with {} variant(s)", videoId, variants.size());
        final var entry = new Source(MediaType.VIDEO, thumbnailUri, metadata,
                HttpHeaders.defaults(uri),
                variants,
                null, null);
        return new Resolution(null, entry);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    private record VideoData(String title, int status, int percent, String message, @SerializedName("thumbnail_url") String thumbnail, VideoFiles files) {}
    private record VideoFiles(VideoFile original, VideoFile mp4, @SerializedName("mp4-mobile") VideoFile mp4Small) {}
    private record VideoFile(int status, String url, float framerate, int width, int height, int bitrate, int size, float duration) {}
}
