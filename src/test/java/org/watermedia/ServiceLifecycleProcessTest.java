package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies the loaded services in fresh JVMs: searches on owned workers that a new query discards and a registry change
 * clears, a failed search probe beside a slow one, uploads held to the four workers and the MRL cache sweep.
 */
@DisplayName("Loaded services")
public class ServiceLifecycleProcessTest {
    @TempDir Path directory;

    @Test @DisplayName("a new query discards the search in flight, held resolutions never gate searches and a registry change drops the cache") void searchesRemainIsolatedAcrossCancellationAndRegistryChanges() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "platform", false); }
    @Test @DisplayName("uploads run four at a time while the rest wait pending, and a bad remote fails the status") void uploadsQueueBehindTheWorkersAndFailOnTheirOwn() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "uploads", false); }
    @Test @DisplayName("an MRL forgotten by the cache sweep reloads into the canonical handle") void evictedMrlReloadReturnsACanonicalHandle() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "evicted", false); }
    @Test @DisplayName("a failed probe neither ends the search early nor loses the late results of the others") void failedProbeCannotCompleteBeforeOtherProbesFinish() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "failed-probe", false); }
}
