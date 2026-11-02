package org.watermedia.bootstrap.app;

import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.util.MediaQuality;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

/** What the screens picked to play, from a test group down to one source and quality, and the player that plays it. */
public final class MediaSession {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppContext ctx;
    public final LinkedHashMap<String, MRL> groupMRLs = new LinkedHashMap<>();
    public MediaCatalog.URIGroup selectedGroup;
    public MRL selectedMRL;
    public String selectedMRLName = "";
    public MRL.Source[] availableSources;
    public MRL.Source selectedSource;
    public MediaQuality[] availableQualities;
    public MediaQuality selectedQuality = MediaQuality.HIGHER;
    // INDEX OF selectedSource IN availableSources
    public int selectedSourceIndex;
    public MediaPlayer player;

    MediaSession(final AppContext ctx) {
        this.ctx = ctx;
    }

    /** Takes the sources of {@link #selectedMRL} and selects the first; returns {@code false} when it has none. */
    public boolean selectSources() {
        this.availableSources = this.selectedMRL.sources().toArray(MRL.Source[]::new);
        if (this.availableSources.length == 0) return false;
        this.selectedSourceIndex = 0;
        this.selectedSource = this.availableSources[0];
        return true;
    }

    /** Forgets the selected group and the links resolved for it. */
    public void clearGroupState() {
        this.selectedGroup = null;
        this.groupMRLs.clear();
    }

    /** Stops the player at once and releases it off the render thread. */
    public void releasePlayer() {
        final MediaPlayer p = this.player;
        if (p == null) return;
        // RENDER-THREAD-ONLY FIELD, SO IT CLEARS BEFORE THE BACKGROUND TEARDOWN
        this.player = null;
        p.stop();
        // release() CLOSES FFMPEG, JOINS THE DECODE THREADS AND DRAINS THE GPU FOR HUNDREDS OF MS, SO IT
        // RUNS OFF THE RENDER THREAD TO KEEP THE UI RESPONSIVE WHEN LEAVING THE PLAYER
        this.ctx.releaseAsync("WaterMedia-PlayerRelease", p::release);
    }

    /** Formats elapsed media time as {@code HH:mm:ss}, wrapping at 24 hours. */
    public static String formatTime(final long ms) {
        return LocalTime.ofSecondOfDay(Math.max(0L, ms) / 1_000L % 86_400L).format(TIME_FORMAT);
    }
}
