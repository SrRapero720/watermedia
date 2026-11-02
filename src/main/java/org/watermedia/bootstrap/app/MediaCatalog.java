package org.watermedia.bootstrap.app;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The media the screens offer: the bundled test groups, the IPTV channels and the links the user added this run. */
public final class MediaCatalog {
    private static final Marker IT = MarkerManager.getMarker(MediaCatalog.class.getSimpleName());

    public URIGroup[] uriGroups;
    public IptvChannel[] iptvChannels = new IptvChannel[0];
    public final List<TestURI> customTests = new ArrayList<>();

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads the bundled groups and channels; without DEBUG logging the debug-only links are left out. */
    public void load() {
        try { this.uriGroups = JSONTool.parse(IOTool.jarRead("assets/watermedia/catalog/uris.json"), URIGroup[].class); }
        catch (final IOException e) { throw new IllegalStateException("Cannot read the bundled uris.json", e); }
        try {
            final IptvCatalog catalog = JSONTool.parse(IOTool.jarRead("assets/watermedia/catalog/iptv.json"), IptvCatalog.class);
            this.iptvChannels = catalog == null || catalog.channels() == null ? new IptvChannel[0] : catalog.channels();
        } catch (final IOException | RuntimeException e) {
            this.iptvChannels = new IptvChannel[0];
            WaterMedia.LOGGER.warn(IT, "Failed to load IPTV catalog", e);
        }
        if (WaterMedia.LOGGER.isDebugEnabled()) return;
        for (int i = 0; i < this.uriGroups.length; i++) {
            final URIGroup group = this.uriGroups[i];
            final TestURI[] filtered = Arrays.stream(group.uris()).filter(u -> !u.debug()).toArray(TestURI[]::new);
            if (filtered.length != group.uris().length) this.uriGroups[i] = new URIGroup(group.name(), filtered);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    public record TestURI(String name, String uri, boolean debug) {}
    public record URIGroup(String name, TestURI[] uris) {}
    public record IptvChannel(String name, String url, String logo, String region, String group, String tvgId, String source) {}
    record IptvCatalog(String generatedAt, IptvChannel[] channels) {}
}
