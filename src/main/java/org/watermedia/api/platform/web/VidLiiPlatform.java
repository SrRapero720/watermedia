package org.watermedia.api.platform.web;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves VidLii watch pages to the source of their {@code <video>} element. */
public final class VidLiiPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(VidLiiPlatform.class.getSimpleName());
    public static final String NAME = "VidLii";
    private static final String[] HOSTS = { "vidlii.com", "www.vidlii.com" };
    private static final String ORIGIN = "https://www.vidlii.com";
    private static final Pattern VIDEO_SRC_PATTERN = Pattern.compile("<video\\s+[^>]*src=\"([^\"]+)\"");

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        if (uri.getPath() == null || uri.getPath().length() < 2) throw PlatformException.unsupported(NAME, uri);

        try (final NetRequest req = new NetRequest(uri).accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for " + uri + " returned HTTP " + req.statusCode());
            final String html = req.text();

            final Matcher matcher = VIDEO_SRC_PATTERN.matcher(html);
            if (!matcher.find())
                throw new PlatformException(NAME, "<video> source not found in page (removed, private, or markup changed): " + uri);

            // VIDLII EMITS A ROOT-RELATIVE PATH, BUT TOLERATE AN ABSOLUTE URL IN CASE THE MARKUP CHANGES
            final String videoPath = matcher.group(1);
            final URI videoUri = DataTool.uri(videoPath.startsWith("http") ? videoPath : ORIGIN + videoPath);
            if (videoUri == null) throw new PlatformException(NAME, "<video> source '" + videoPath + "' is malformed: " + uri);
            LOGGER.debug(IT, "VidLii resolved video source {} for {}", videoUri, uri);

            final var entry = new Source(MediaType.VIDEO, null, null,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(videoUri, 0, 0)),
                    null, null);
            return new Resolution(null, entry);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
