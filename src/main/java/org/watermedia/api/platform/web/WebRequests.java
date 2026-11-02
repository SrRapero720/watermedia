package org.watermedia.api.platform.web;

import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.M3UTool;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Host, request and playlist helpers shared by the web platform handlers. */
final class WebRequests {
    private WebRequests() {}

    // CASE-INSENSITIVE HOST-SUFFIX MATCH WITH A DOT BOUNDARY: host EQUALS OR ENDS WITH ".<suffix>", SO SUBDOMAINS
    // (www./m.) MATCH BUT LOOKALIKES LIKE "notyoutube.com" DO NOT; A NULL HOST (file:// URIs) NEVER MATCHES
    static boolean hostMatches(final String host, final String... suffixes) {
        if (host == null) return false;
        final String h = host.toLowerCase(Locale.ROOT);
        for (final String suffix: suffixes)
            if (h.equals(suffix) || h.endsWith("." + suffix)) return true;
        return false;
    }

    // GETS uri AS JSON; A MALFORMED uri FAILS LIKE ANY OTHER REQUEST, SO resolve() NEVER LEAKS IllegalArgumentException
    static <T> T fetchJson(final String platform, final String uri, final Class<T> type) throws PlatformException {
        final URI target;
        try { target = URI.create(uri); }
        catch (final IllegalArgumentException malformed) { throw new PlatformException(platform, "Malformed API URL " + uri, malformed); }
        return fetchJson(platform, new NetRequest(target).accept(NetRequest.ACCEPT_JSON), type);
    }

    // SENDS AN UNSENT request AND BINDS ITS JSON BODY; A NON-200 STATUS, A NON-JSON OR EMPTY BODY AND ANY
    // TRANSPORT OR PARSE FAILURE BECOME A PlatformException NAMING platform AND THE REQUESTED URI, KEEPING THE CAUSE
    static <T> T fetchJson(final String platform, final NetRequest request, final Class<T> type) throws PlatformException {
        final URI uri = request.uri();
        try (final NetRequest req = request.send()) {
            if (req.statusCode() != 200)
                throw new PlatformException(platform, "API " + uri + " returned HTTP " + req.statusCode());
            final T data = req.json(type);
            if (data == null)
                throw new PlatformException(platform, "API " + uri + " returned an empty or non-JSON body");
            return data;
        } catch (final PlatformException e) {
            throw e;
        } catch (final IOException | RuntimeException e) {
            throw new PlatformException(platform, "Failed to fetch " + uri, e);
        }
    }

    // FOLLOWS A SHORT LINK'S REDIRECTS WITH A BROWSER UA (SHORTENERS FILTER BOTS) AND RETURNS WHERE IT LANDED
    static URI follow(final URI link) throws IOException {
        try (final NetRequest req = new NetRequest(link).userAgent(NetRequest.UserAgent.GENERIC).send()) {
            return req.uri();
        }
    }

    // BEST-EFFORT HLS LADDER OF playlist, FETCHED WITH THE HEADERS ITS SOURCE CARRIES; NEVER THROWS
    static List<Quality> hls(final URI playlist, final HttpHeaders headers) {
        return qualities(playlist, M3UTool.qualities(playlist, headers));
    }

    // RENDITIONS WITHOUT A RESOLUTION (AUDIO-ONLY OR UNTAGGED) ARE DROPPED; A LADDER LEFT EMPTY FALLS BACK TO THE
    // PLAYLIST ITSELF SO FFmpeg PICKS THE VARIANT
    static List<Quality> qualities(final URI playlist, final List<M3UTool.Variant> variants) {
        final List<Quality> out = new ArrayList<>(variants.size());
        for (final M3UTool.Variant v: variants)
            if (v.width() > 0 || v.height() > 0) out.add(new Quality(v.uri(), v.width(), v.height(), (float) v.fps()));
        if (out.isEmpty()) out.add(new Quality(playlist, 0, 0));
        return out;
    }
}
