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
import java.util.Map;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Resolves Dropbox share links to their direct download through the CDN. */
public final class DropboxPlatform implements Platform {
    private static final Marker IT = MarkerManager.getMarker(DropboxPlatform.class.getSimpleName());
    public static final String NAME = "Dropbox";
    private static final String[] HOSTS = { "dropbox.com", "www.dropbox.com" };
    // SHARE LINKS: /s/<id>/<name>, /sh/<id>/<key>/<name> AND /scl/fi/<id>/<name>?rlkey=...; FOLDERS AND APP PAGES ARE NOT PLAYABLE
    private static final String[] SHARE_PREFIXES = { "/s/", "/sh/", "/scl/fi/" };
    // A WHOLE dl PARAMETER OF THE RAW QUERY, WITH OR WITHOUT A VALUE
    private static final Pattern DL_PARAM = Pattern.compile("(^|&)dl(?:=[^&]*)?(?=&|$)");

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!DataTool.equalsAnyIgnoreCase(uri.getHost(), HOSTS)) return null;
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!DataTool.startsWith(path, SHARE_PREFIXES)) throw PlatformException.unsupported(NAME, uri);

        // dl=0 OPENS THE PREVIEW PAGE WHILE dl=1 AND raw=1 SERVE THE FILE THROUGH THE CDN; ANY OTHER dl VALUE IS
        // REWRITTEN TO dl=1 AND A SHARE LINK WITHOUT ONE GETS dl=1 APPENDED, THE REST OF THE RAW QUERY KEPT AS IS
        final Map<String, String> params = NetRequest.query(uri);
        final String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        final String rebuilt = "1".equals(params.get("dl")) || "1".equals(params.get("raw")) ? query
                : params.containsKey("dl") ? DL_PARAM.matcher(query).replaceAll("$1dl=1")
                : query.isEmpty() ? "dl=1" : query + "&dl=1";
        final URI directUri = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path + "?" + rebuilt);

        try (final NetRequest req = new NetRequest(directUri).send()) {
            if (req.statusCode() != 200)
                throw new PlatformException(NAME, "Direct download for " + directUri + " returned HTTP " + req.statusCode());

            final MediaType type = MediaType.of(req.contentType());
            LOGGER.debug(IT, "Dropbox resolved {} (content-type {}) for {}", directUri, req.contentType(), uri);

            final var entry = new Source(type, null, null,
                    HttpHeaders.defaults(uri),
                    List.of(new Quality(directUri, 0, 0)),
                    null, null);
            return new Resolution(null, entry);
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(NAME, "Failed to resolve " + uri, e);
        }
    }
}
