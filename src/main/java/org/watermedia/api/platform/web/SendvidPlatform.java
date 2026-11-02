package org.watermedia.api.platform.web;

import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves Sendvid video pages, waiting a short while for an upload still in the processing queue. */
public final class SendvidPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(SendvidPlatform.class.getSimpleName());
    public static final String NAME = "Sendvid";
    private static final String[] HOSTS = { "sendvid.com", "www.sendvid.com" };
    private static final String STATUS_API = "https://sendvid.com/api/v1/videos/%s/status.json";
    private static final Pattern PATTERN_SOURCE = Pattern.compile("var\\s+video_source\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_POSTER = Pattern.compile("var\\s+video_poster\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern PATTERN_DURATION = Pattern.compile("var\\s+video_duration\\s*=\\s*\"([^\"]+)\"");
    private static final int MAX_QUEUE = 2;
    // A QUEUED UPLOAD IS POLLED UNTIL IT IS READY OR THE ATTEMPTS RUN OUT (ONE MINUTE IN TOTAL)
    private static final int MAX_POLLS = 30;
    private static final long POLL_DELAY_MS = 2_000L;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;

        final String path = uri.getPath();
        if (path == null || path.length() < 2) throw PlatformException.unsupported(NAME, uri);
        final String videoId = path.substring(1).split("[/#?]")[0];
        if (videoId.isEmpty()) throw PlatformException.unsupported(NAME, uri);
        LOGGER.debug(IT, "Sendvid resolving video '{}'", videoId);

        try {
            final String statusUrl = String.format(STATUS_API, videoId);
            final StatusResponse status = WebRequests.fetchJson(NAME, statusUrl, StatusResponse.class);
            if (!"done".equals(status.state())) {
                LOGGER.debug(IT, "Sendvid video '{}' not ready yet (state={}, queue={})", videoId, status.state(), status.placeInQueue());
                if (status.placeInQueue() > MAX_QUEUE)
                    throw new PlatformException(NAME, "Video '" + videoId + "' is in queue position " + status.placeInQueue() + " (> " + MAX_QUEUE + "), too far to wait");
                this.waitForReady(statusUrl, videoId);
            }

            try (final NetRequest req = new NetRequest(uri).accept("text/html").send()) {
                if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for video '" + videoId + "' returned HTTP " + req.statusCode());

                final String html = req.text();

                final Matcher sourceMatcher = PATTERN_SOURCE.matcher(html);
                if (!sourceMatcher.find())
                    throw new PlatformException(NAME, "video_source not found in page for video '" + videoId + "' (removed or markup changed): " + uri);

                final URI videoUri = DataTool.uri(sourceMatcher.group(1));
                if (videoUri == null)
                    throw new PlatformException(NAME, "video_source '" + sourceMatcher.group(1) + "' of video '" + videoId + "' is not a web URL");

                final Matcher posterMatcher = PATTERN_POSTER.matcher(html);
                final URI thumbnailUri = posterMatcher.find() ? DataTool.uri(posterMatcher.group(1)) : null;
                if (thumbnailUri == null) LOGGER.warn(IT, "Sendvid video '{}' has no usable video_poster (thumbnail)", videoId);

                // PREFER THE PAGE'S video_duration; FALL BACK TO THE STATUS API VALUE
                final Matcher durationMatcher = PATTERN_DURATION.matcher(html);
                final double seconds = durationMatcher.find() ? DataTool.parseDouble(durationMatcher.group(1), status.duration()) : status.duration();
                final Metadata metadata = new Metadata(null, null, null, (long) Math.max(0d, seconds * 1_000d), null);

                // THE SIGNED CDN URL CARRIES ITS OWN EXPIRY (validto EPOCH SECONDS); HONOR IT FOR CACHE INVALIDATION
                final Instant expires = DataTool.expiry(videoUri);
                LOGGER.debug(IT, "Sendvid resolved video '{}' (expires={})", videoId, expires);
                final var entry = new Source(MediaType.VIDEO, thumbnailUri, metadata,
                        HttpHeaders.defaults(uri),
                        List.of(new Quality(videoUri, 0, 0)),
                        null, null);
                return new Resolution(expires, entry);
            }
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void waitForReady(final String statusUrl, final String videoId) throws PlatformException {
        for (int i = 0; i < MAX_POLLS; i++) {
            // THE SLEEP RESTORES THE INTERRUPT FLAG ITSELF; A CANCELLED RESOLUTION STOPS POLLING AT ONCE
            if (!ThreadTool.sleep(POLL_DELAY_MS)) throw new PlatformException(NAME, "Interrupted while waiting for video '" + videoId + "'");
            final StatusResponse status = WebRequests.fetchJson(NAME, statusUrl, StatusResponse.class);
            LOGGER.debug(IT, "Sendvid {} status: {} ({}%)", videoId, status.state(), status.progress());
            if ("done".equals(status.state())) return;
            if (status.placeInQueue() > MAX_QUEUE)
                throw new PlatformException(NAME, "Video '" + videoId + "' queue position grew to " + status.placeInQueue());
        }
        throw new PlatformException(NAME, "Video '" + videoId + "' did not become ready after " + MAX_POLLS * POLL_DELAY_MS / 1_000L + " seconds");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    private record StatusResponse(int progress, @SerializedName("place_in_queue") int placeInQueue, String state, double duration) {}
}
