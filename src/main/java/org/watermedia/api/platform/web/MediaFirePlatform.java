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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves MediaFire file pages to the direct download link their markup carries. */
public final class MediaFirePlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(MediaFirePlatform.class.getSimpleName());
    public static final String NAME = "MediaFire";
    private static final String[] HOSTS = { "mediafire.com", "www.mediafire.com" };
    private static final Pattern PATTERN_POPSOK = Pattern.compile("<a\\s+class=\"input\\s+popsok\"\\s+aria-label=\"Download\\s+file\"\\s+href=\"([^\"]+)\"[^>]*>");
    private static final Pattern PATTERN_DATA_SCRAMBLED = Pattern.compile("data-scrambled-url=\"([^\"]+)\"");
    // DIRECT LINKS ARE ISSUED PER VISIT AND STOP WORKING AFTER A WHILE; THE LIFETIME IS NOT PUBLISHED
    private static final Duration TTL = Duration.ofMinutes(30);

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        final String path = uri.getPath();
        if (path == null || !path.startsWith("/file/")) throw PlatformException.unsupported(NAME, uri);

        try (final NetRequest req = new NetRequest(uri).accept("text/html").send()) {
            if (req.statusCode() != 200) throw new PlatformException(NAME, "Page request for " + uri + " returned HTTP " + req.statusCode());

            final String html = req.text();
            final Matcher popsokMatcher = PATTERN_POPSOK.matcher(html);
            final Matcher dataScrambledMatcher = PATTERN_DATA_SCRAMBLED.matcher(html);

            // THE DIRECT LINK IS EITHER A PLAIN "popsok" ANCHOR OR, WHEN OBFUSCATED, A BASE64 "data-scrambled-url"
            // ATTRIBUTE; THE PLAIN ONE WINS
            final String link;
            if (popsokMatcher.find()) {
                link = popsokMatcher.group(1);
                LOGGER.debug(IT, "MediaFire resolved direct link via popsok anchor: {}", link);
            } else if (dataScrambledMatcher.find()) {
                link = new String(Base64.getDecoder().decode(dataScrambledMatcher.group(1)), StandardCharsets.UTF_8);
                LOGGER.debug(IT, "MediaFire resolved direct link via scrambled attribute: {}", link);
            } else {
                throw new PlatformException(NAME, "Download link not found in page (private, removed, or markup changed): " + uri);
            }
            final URI downloadUri = DataTool.uri(link);
            if (downloadUri == null) throw new PlatformException(NAME, "Download link '" + link + "' is not a web URL: " + uri);

            final var entry = new Source(MediaType.UNKNOWN, null, null,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(downloadUri, 0, 0)),
                    null, null);
            return new Resolution(Instant.now().plus(TTL), entry);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
