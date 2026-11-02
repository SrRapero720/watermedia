package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves D.tube video pages to the HLS ladder and metadata embedded in their script. */
public final class DTubePlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(DTubePlatform.class.getSimpleName());
    public static final String NAME = "D.tube";
    private static final String[] HOSTS = { "d.tube", "www.d.tube" };
    private static final String ORIGIN = "https://d.tube";
    private static final Pattern VIDEO_URL = Pattern.compile("video_url:\\s*\"([^\"]+)\"");
    private static final Pattern THUMBNAIL_URL = Pattern.compile("thumbnail_url:\\s*\"([^\"]+)\"");
    private static final Pattern TITLE = Pattern.compile("title:\\s*\"([^\"]+)\"");
    private static final Pattern DESCRIPTION = Pattern.compile("description:\\s*\"([^\"]*)\"");
    private static final Pattern DURATION = Pattern.compile("duration:\\s*(\\d+)");
    private static final Pattern CREATED_AT = Pattern.compile("created_at:\\s*\"([^\"]+)\"");
    private static final Pattern USERNAME = Pattern.compile("username:\\s*\"([^\"]+)\"");
    // THE CDN LINKS ARE SIGNED WITH A LIFETIME D.tube DOES NOT PUBLISH
    private static final Duration TTL = Duration.ofMinutes(30);

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        if (uri.getPath() == null || uri.getPath().length() < 2) throw PlatformException.unsupported(NAME, uri);

        // THE D.tube CDN DEMANDS A BROWSER UA AND THE d.tube ORIGIN AND REFERER
        final HttpHeaders headers = HttpHeaders.defaults(uri)
                .set("User-Agent", NetRequest.UserAgent.GENERIC.value())
                .set("Referer", ORIGIN + "/")
                .set("Origin", ORIGIN);
        try (final NetRequest req = new NetRequest(uri).accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for " + uri + " returned HTTP " + req.statusCode());
            final String html = req.text();

            final String videoUrl = extract(html, VIDEO_URL);
            if (videoUrl == null) throw new PlatformException(NAME, "No video_url found in page: " + uri);
            final URI hlsUri = DataTool.uri(videoUrl);
            if (hlsUri == null) throw new PlatformException(NAME, "The video_url '" + videoUrl + "' of " + uri + " is not a web URL");
            LOGGER.debug(IT, "D.tube extracted video_url {} from {}", hlsUri, uri);

            final String title = extract(html, TITLE);
            final String description = extract(html, DESCRIPTION);
            final URI thumbnail = DataTool.uri(extract(html, THUMBNAIL_URL));
            if (title == null) LOGGER.warn(IT, "D.tube page has no title for {}", uri);

            // METADATA IS BEST-EFFORT: A MALFORMED TIMESTAMP MUST NOT ABORT AN OTHERWISE PLAYABLE VIDEO
            Instant publishedAt = null;
            final String createdAt = extract(html, CREATED_AT);
            if (createdAt != null) {
                try { publishedAt = Instant.parse(createdAt); }
                catch (final DateTimeParseException e) { LOGGER.warn(IT, "D.tube unparseable created_at '{}' for {}", createdAt, uri, e); }
            }
            final long duration = DataTool.parseLong(extract(html, DURATION), 0L) * 1_000L;
            final Metadata metadata = new Metadata(title, description, publishedAt, duration, extract(html, USERNAME));
            // THE CDN CHECKS THE PLAYLIST REQUEST WITH THE SAME BROWSER HEADERS AS THE MEDIA ONE
            final List<Quality> variants = WebRequests.hls(hlsUri, headers);

            LOGGER.debug(IT, "D.tube resolved '{}' with {} variant(s) for {}", title, variants.size(), uri);
            final var entry = new Source(MediaType.VIDEO, thumbnail, metadata, headers, variants, null, null);
            return new Resolution(Instant.now().plus(TTL), entry);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static String extract(final String html, final Pattern pattern) {
        final Matcher m = pattern.matcher(html);
        return m.find() ? m.group(1) : null;
    }
}
