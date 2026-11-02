package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

/** Verifies the loaded services in fresh JVMs: search isolation, upload queueing and the MRL cache sweep. */
@DisplayName("Loaded services")
public class ServiceLifecycleProcessTest {
    @TempDir Path directory;

    @Test @DisplayName("searches stay isolated across cancellation and registry changes") void searchesRemainIsolatedAcrossCancellationAndRegistryChanges() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "platform", false); }
    @Test @DisplayName("uploads queue behind the workers and fail on their own") void uploadsQueueBehindTheWorkersAndFailOnTheirOwn() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "uploads", false); }
    @Test @DisplayName("an evicted MRL reload returns a canonical handle") void evictedMrlReloadReturnsACanonicalHandle() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "evicted", false); }
    @Test @DisplayName("a failed probe cannot complete before the other probes finish") void failedProbeCannotCompleteBeforeOtherProbesFinish() throws Exception { runProbe(ServiceLifecycleProbe.class, this.directory, "failed-probe", false); }
}
