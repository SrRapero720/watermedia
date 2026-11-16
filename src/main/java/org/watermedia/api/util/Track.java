package org.watermedia.api.util;

import org.watermedia.WaterMedia;
import org.watermedia.api.player.MRL;
import org.watermedia.api.platform.Platform;
import org.watermedia.tools.DataTool;

import java.net.URI;

/**
 * A secondary track attached to a media source: an alternate audio stream or a subtitle file.
 * Shared by the platform layer ({@link Platform.Source}) and {@link MRL.Source}, whose audio
 * track lists put the original (or default) audio first.
 *
 * @param name display name for the track (e.g. "English"), or null when the platform gives none
 * @param lang BCP-47 language tag (e.g. "en", "es-419"), or null when unknown
 * @param uri  direct URL to the track stream/file, never null
 */
public record Track(String name, String lang, URI uri) {
    public Track {
        if (uri == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_TRACK_NULL_URI) + "Failed to create the track due to null URI");
    }
}
