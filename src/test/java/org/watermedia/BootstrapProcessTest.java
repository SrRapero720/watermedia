package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

/** Verifies the one-shot WaterMedia load in fresh JVMs: module counts, failures, natives and the TLS bundle. */
@DisplayName("WaterMedia load")
public class BootstrapProcessTest {
    @TempDir Path directory;

    @Test @DisplayName("a server loads only the shared modules") void serverBootWithoutClientBindings() throws Exception { runProbe(BootstrapProbe.class, this.directory, "server", true); }
    @Test @DisplayName("a server loads without the binaries classes") void serverBootWithoutBinariesClasses() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "server-no-binaries", false); }
    @Test @DisplayName("a client without binaries keeps images") void clientBootWithoutBinariesKeepsImagesAvailable() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "client-no-binaries", false); }
    @Test @DisplayName("a client with disabled natives keeps the load final") void clientBootWithDisabledNativesKeepsTheLoadFinal() throws Exception { runProbe(BootstrapProbe.class, this.directory, "client", false); }
    @Test @DisplayName("enabled natives publish their installation") void enabledNativesPublishTheirInstallation() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native", false); }
    @Test @DisplayName("installed binaries must initialize before other modules") void installedBinariesMustInitializeBeforeOtherModules() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native-failure", false); }
    @Test @DisplayName("an optional module failure completes the load") void optionalModuleFailureCompletesTheLoad() throws Exception { runProbe(BootstrapProbe.class, this.directory, "optional-failure", false); }
    @Test @DisplayName("native authorities follow the truststore") void nativeAuthoritiesFollowTheTruststore() throws Exception { runProbe(BootstrapProbe.class, this.directory, "tls", false); }
    @Test @DisplayName("an empty truststore disables natives without failing the load") void emptyTruststoreDisablesNativesWithoutFailingTheLoad() throws Exception { runProbe(BootstrapProbe.class, this.directory, "tls-empty", false); }
}
