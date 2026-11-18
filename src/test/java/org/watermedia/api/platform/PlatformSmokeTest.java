package org.watermedia.api.platform;

import org.watermedia.WaterMedia;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.Track;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Manual network harness, not a JUnit test: resolves links through {@link PlatformAPI#fetch(URI)} on this machine's own
 * network and prints the sources that came back, so a link of any registered handler meets the live site: the API calls
 * the offline tests leave out (BiliBili, Kick, yt-dlp extraction) and sites that block datacenter IPs. Run its
 * {@code main} from the IDE with the links as arguments; with none it resolves a YouTube video and a SoundCloud track.
 * The DEBUG log of {@code PlatformAPI} names the handler that answered. Facebook and Instagram public items rot fast and
 * often need a logged-in session, so test those with a fresh public link.
 */
public final class PlatformSmokeTest {
    private static final String[] DEFAULTS = {
            "https://www.youtube.com/watch?v=GjEKrr5EjzA",
            "https://soundcloud.com/imaginedragons/believer",
    };
    private static final int URL_PRINT_LENGTH = 90;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    public static void main(final String[] args) {
        WaterMedia.start("TEST", null, null, true);
        System.out.println("Resolving through " + PlatformAPI.platforms().size() + " registered platforms");

        final String[] urls = args.length > 0 ? args : DEFAULTS;
        for (final String url: urls) resolve(url);
        if (args.length == 0) System.out.println("\nTip: test Facebook/Instagram by passing a fresh public link as an argument");
        System.exit(0);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // ONE LINK: ITS SOURCES WITH THEIR TYPE, METADATA, USER AGENT, QUALITIES AND TRACKS, OR WHY IT FAILED
    private static void resolve(final String url) {
        System.out.println("\n=== " + url + " ===");
        final long started = System.nanoTime();
        try {
            final Resolution data = PlatformAPI.fetch(URI.create(url));
            final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (data == null) {
                System.out.println("(no platform handled this URL, " + ms + " ms)");
                return;
            }
            System.out.println("sources=" + data.sourceCount() + " expires=" + data.expires() + " (" + ms + " ms)");
            for (final Source src: data.sources()) {
                final Metadata meta = src.metadata();
                System.out.println("  type=" + src.type() + " qualities=" + src.qualities().size()
                        + " | title=" + (meta == null ? "(none)" : meta.title()) + " | author=" + (meta == null ? "(none)" : meta.author()));
                System.out.println("  UA=" + src.headers().value("User-Agent"));
                for (final Quality q: src.qualities()) {
                    final String u = q.uri().toString();
                    System.out.println("  " + q.width() + "x" + q.height() + " -> " + u.substring(0, Math.min(URL_PRINT_LENGTH, u.length())));
                }
                for (final Track a: src.audioTracks()) {
                    final String u = a.uri().toString();
                    System.out.println("  audio " + a.lang() + " -> " + u.substring(0, Math.min(URL_PRINT_LENGTH, u.length())));
                }
                System.out.println("  subtitles=" + src.subtitleTracks().size());
            }
        } catch (final Exception e) {
            System.out.println("FAILED (" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms): " + e.getMessage());
        }
    }
}
