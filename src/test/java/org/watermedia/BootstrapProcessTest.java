package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies the one-shot WaterMedia load in fresh JVMs: the modules each side walks with and without the client
 * libraries or the Binaries classes, a failed native install, a file server that cannot bind, the installed natives,
 * the native TLS bundle and a load that stays final whatever state it left.
 */
@DisplayName("WaterMedia load")
public class BootstrapProcessTest {
    @TempDir Path directory;

    @Test @DisplayName("a server without the client libraries walks every module and provisions nothing") void serverWalksEveryModuleWithoutClientLibraries() throws Exception { runProbe(BootstrapProbe.class, this.directory, "server", true); }
    @Test @DisplayName("a server without the Binaries classes walks the other four modules") void serverWithoutBinariesWalksTheOtherModules() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "server-no-binaries", false); }
    @Test @DisplayName("a client without the Binaries classes disables video and still decodes images") void clientWithoutBinariesKeepsImagesAvailable() throws Exception { runProbe(WithoutBinariesProbe.class, this.directory, "client-no-binaries", false); }
    @Test @DisplayName("a client with FFmpeg disabled publishes no native state and an interrupted MRL wait keeps its load") void clientWithDisabledFFmpegPublishesNoNativeState() throws Exception { runProbe(BootstrapProbe.class, this.directory, "client", false); }
    @Test @DisplayName("enabled natives publish their verified installation and the executable paths") void enabledNativesPublishTheirInstallation() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native", false); }
    @Test @DisplayName("a failed native install ends the load before the modules behind it") void failedNativeInstallEndsTheLoad() throws Exception { runProbe(BootstrapProbe.class, this.directory, "native-failure", false); }
    @Test @DisplayName("a file server that cannot bind its port leaves the load complete and uploads working") void occupiedServerPortKeepsTheLoadComplete() throws Exception { runProbe(BootstrapProbe.class, this.directory, "optional-failure", false); }
    @Test @DisplayName("the native TLS bundle holds the truststore's authorities and keeps verification on") void nativeAuthoritiesFollowTheTruststore() throws Exception { runProbe(BootstrapProbe.class, this.directory, "tls", false); }
    @Test @DisplayName("an empty truststore disables natives without failing the load") void emptyTruststoreDisablesNativesWithoutFailingTheLoad() throws Exception { runProbe(BootstrapProbe.class, this.directory, "tls-empty", false); }
}
