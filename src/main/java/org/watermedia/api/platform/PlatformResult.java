package org.watermedia.api.platform;

import org.watermedia.api.player.MRL;

import java.net.URI;

/**
 * One hit of {@link Platform#search(String, int)}: what a result list draws plus the raw page URI, which goes through
 * {@link MRL} or {@link PlatformAPI#fetch(URI)} like any other link only once the user picks it.
 *
 * @param platform  the platform that produced this hit, never null
 * @param title     human-readable title (video, stream or gallery name)
 * @param thumbnail preview image URI, or {@code null} when the platform exposes none
 * @param uri       the raw page URI to resolve later (e.g. {@code https://www.youtube.com/watch?v=...}), never null
 */
public record PlatformResult(Platform platform, String title, URI thumbnail, URI uri) {
    public PlatformResult {
        if (platform == null || uri == null) throw new IllegalArgumentException("PlatformResult requires a platform and a URI");
    }
}
