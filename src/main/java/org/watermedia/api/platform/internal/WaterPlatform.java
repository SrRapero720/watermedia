package org.watermedia.api.platform.internal;

import org.watermedia.WaterMedia;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.*;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.HttpHeaders;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Platform of the {@code water://} scheme: local files resolved against the working directory, the configured remote file server and the global server. */
public final class WaterPlatform implements Platform {
    public static final String NAME = "WaterMedia Internal";
    public static final String HOST_LOCAL = "local";
    public static final String HOST_REMOTE = "remote";
    public static final String HOST_GLOBAL = "global";

    public static final String GLOBAL_SERVER = "https://watermedia.srrapero720.me/";

    @Override
    public String name() { return NAME; }

    @Override
    public Resolution resolve(final URI uri) throws PlatformException {
        if (!NetworkAPI.PROTOCOL_WATER.equalsIgnoreCase(uri.getScheme())) return null;

        final URI resolved;
        try {
            resolved = new URI(location(uri));
        } catch (final URISyntaxException | IllegalStateException | IllegalArgumentException e) {
            // A BAD remoteUrl OR A REJECTED PATH
            throw new PlatformException(NAME, "Cannot resolve " + uri + " (" + e.getMessage() + ")", e);
        }
        // THE REFERER FOLLOWS THE RESOLVED ORIGIN; NO SERVER KNOWS THE water:// ADDRESS
        final Source entry = new Source(MediaType.UNKNOWN, null, null, HttpHeaders.defaults(resolved),
                List.of(new Quality(resolved, 0, 0)), null, null);
        return new Resolution(null, entry);
    }

    /**
     * Resolves a {@code water://local/}, {@code water://remote/}, or {@code water://global/}
     * address to its file or HTTP URL. Local paths are resolved against {@link WaterMedia#cwd()} with the
     * same reach as a {@code file://} address, so a parent segment leaves that directory; remote and global
     * paths keep their raw encoding, so a space or a percent sign survives the hop.
     *
     * @param u water address to resolve
     * @return the resolved URL text
     * @throws PlatformException if the address has no supported host
     * @throws IllegalStateException if the remote base URL is not configured or WaterMedia has not started
     */
    public static String location(final URI u) throws PlatformException {
        final String host = u.getHost();
        if (host == null) throw new PlatformException(NAME, "water:// URL requires a host (local, remote, global)");
        final String raw = u.getRawPath() == null ? "" : u.getRawPath();

        return switch (host.toLowerCase(Locale.ROOT)) {
            case HOST_LOCAL -> {
                String path = u.getPath() == null ? "" : u.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                final Path file = WaterMedia.cwd().resolve(path);
                yield file.toUri().toString();
            }
            // NetworkAPI.remoteUrl() VALIDATES THE CONFIG UP FRONT AND STRIPS THE TRAILING SLASH
            case HOST_REMOTE -> NetworkAPI.remoteUrl() + raw;
            case HOST_GLOBAL -> GLOBAL_SERVER + (raw.startsWith("/") ? raw.substring(1) : raw);
            default -> throw new PlatformException(NAME, "Unknown water:// host: " + host + " (expected: local, remote, global)");
        };
    }
}
