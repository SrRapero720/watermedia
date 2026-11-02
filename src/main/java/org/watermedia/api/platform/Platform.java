package org.watermedia.api.platform;

import org.watermedia.api.player.MRL;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.Track;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Contract for a platform handler. A single {@link #resolve(URI)} call both
 * decides whether the URI belongs to this platform and resolves it to a raw
 * {@link Resolution} (direct links, dimensions, metadata). {@link MRL.Source}
 * construction lives in {@link MRL}, not here.
 */
public interface Platform {

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Provides the human-readable platform name (e.g. "Kick", "BiliBili"). */
    String name();

    /**
     * Resolves the URI to one or more sources with their qualities and metadata. The contract has three outcomes:
     * <ul>
     *   <li>{@code null}: the URI does not belong to this platform (foreign host or scheme); the
     *       caller must keep probing other handlers.</li>
     *   <li>a thrown {@link PlatformException}: the URI belongs to this platform but cannot be
     *       resolved: a URL shape the handler does not support ({@link PlatformException#unsupported}),
     *       a parse or network failure, or mature content while {@code platforms.allowMatureContent}
     *       is off ({@link MatureContentException}).</li>
     *   <li>a {@link Resolution} instance: the URI was resolved successfully.</li>
     * </ul>
     * The call blocks on network I/O and may run on several loader threads at once, so an
     * implementation must be thread-safe. It translates every failure it meets, an interrupted wait
     * included, into a {@link PlatformException} instead of letting other exceptions escape.
     *
     * @param uri the URI to resolve
     * @return the sources with their qualities and expiration, or {@code null}
     *         when the URI is not handled by this platform
     * @throws PlatformException if the URI belongs to this platform but the lookup fails
     */
    Resolution resolve(URI uri) throws PlatformException;

    /**
     * Searches this platform for {@code query} and returns up to {@code limit} unresolved hits (see
     * {@link PlatformResult}); the default returns an empty list for platforms without a public search.
     * <p>
     * {@link PlatformAPI} probes every platform concurrently on a shared daemon pool, so an implementation must be
     * thread-safe, may block on network I/O and should bail out promptly once its thread is
     * {@link Thread#isInterrupted() interrupted} by a newer search. Its requests cap their connect and read timeouts
     * at {@link PlatformAPI#SEARCH_TIMEOUT_MS}, so one slow platform never holds a worker for long.
     *
     * @param query the user's search text (never {@code null} or blank)
     * @param limit the maximum number of results to return
     * @return up to {@code limit} hits, or an empty list when this platform has no match or no
     *         search support; never {@code null}
     * @throws PlatformException if this platform supports search but the lookup failed
     */
    default List<PlatformResult> search(final String query, final int limit) throws PlatformException {
        return List.of();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /**
     * Result of {@link #resolve(URI)}. May carry several {@link Source sources} when the URI is a
     * gallery, album or multi-stream resource.
     *
     * @param expires expiration instant for cached sources (null = never)
     * @param sources copied sources; may be empty but never null or contain nulls
     */
    record Resolution(Instant expires, List<Source> sources) {
        public Resolution {
            if (sources == null) throw new IllegalArgumentException("Resolution requires a source list");
            // THE LIST THE PLATFORM BUILT STAYS MUTABLE ON ITS SIDE: THE RECORD KEEPS ITS OWN COPY
            sources = List.copyOf(sources);
        }

        /** Creates the common single-source resolution. */
        public Resolution(final Instant expires, final Source source) {
            this(expires, List.of(source));
        }

        /** Returns the number of sources in this resolution. */
        public int sourceCount() {
            return this.sources.size();
        }
    }

    /**
     * A single media item carrying every available quality (different resolutions or bitrates)
     * plus the metadata needed to display it without downloading. Devs can read
     * {@link Quality#width()}, {@link Quality#height()} and {@link #metadata()} directly to pick a link.
     * Every source names its {@link MediaType} and lists at least one quality; the thumbnail, the
     * metadata and the track lists are optional.
     * <p>
     * Each of the {@code audioTracks} is a separate audio input, one per language, that a player plays instead of the
     * qualities' own audio. The platform lists them with the original (or default) audio first, which is what a player
     * without a language preference plays.
     */
    record Source(MediaType type, URI thumbnail, Metadata metadata, HttpHeaders headers, List<Quality> qualities,
                  List<Track> audioTracks, List<Track> subtitleTracks) {
        public Source {
            if (type == null) throw new IllegalArgumentException("Source requires a media type");
            if (qualities == null || qualities.isEmpty()) throw new IllegalArgumentException("Source constructed with no qualities");
            headers = new HttpHeaders(headers).freeze(); // SHARED WITH PLAYER THREADS ONCE RESOLVED: NEVER MUTABLE

            // THE LISTS THE PLATFORM BUILT STAY MUTABLE ON ITS SIDE: THE RECORD KEEPS ITS OWN COPIES
            qualities = List.copyOf(qualities);
            audioTracks = audioTracks == null ? List.of() : List.copyOf(audioTracks);
            subtitleTracks = subtitleTracks == null ? List.of() : List.copyOf(subtitleTracks);
        }
    }

    /**
     * One URL of a {@link Source}. The platform reports the {@code width}/{@code height} it knows for
     * the rendition; bucketing those dimensions into a quality level is {@link MRL}'s responsibility
     * and is not exposed to the platform layer.
     * <p>
     * If a platform has no resolution data, pass {@code 0}/{@code 0}. If only one of the dimensions
     * is known (typical for HLS rendition tags that only report height), pass {@code 0} for the other.
     * {@code fps} is the frame rate when the platform reports one (0 otherwise); it breaks ties
     * between renditions of the same size when {@link MRL} keeps one URL per quality bucket.
     * The URI is mandatory and no dimension may be negative.
     */
    record Quality(URI uri, int width, int height, float fps) {
        public Quality {
            if (uri == null) throw new IllegalArgumentException("Quality requires a URI");
            if (width < 0 || height < 0 || fps < 0f) throw new IllegalArgumentException("Quality dimensions cannot be negative");
        }

        /** Creates a quality whose frame rate is unknown. */
        public Quality(final URI uri, final int width, final int height) {
            this(uri, width, height, 0f);
        }
    }
}
