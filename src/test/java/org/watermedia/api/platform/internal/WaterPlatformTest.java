package org.watermedia.api.platform.internal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.watermedia.WaterMedia;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.test.support.MediaBootstrap;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link WaterPlatform#location(URI)} mapping of the {@code local} and {@code remote} hosts, the coded
 * refusals of a Windows share and of an unknown host, and that {@link WaterPlatform#resolve(URI)} turns a
 * {@code global} address into a single-source resolution without any network access.
 */
@DisplayName("WaterPlatform")
public class WaterPlatformTest {

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // location(local) NEEDS WaterMedia.cwd(); THE SHARED CLIENT LOAD KEEPS THE TEST JVM A CLIENT FOR THE MEDIA TESTS
    @BeforeAll
    static void initWaterMedia() { MediaBootstrap.client(); }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("location mapping")
    class Location {

        @Test
        @DisplayName("water://local resolves against the session working directory, folders included")
        void localResolvesAgainstCwd() throws PlatformException {
            assertEquals(WaterMedia.cwd().resolve("foo.png").toUri().toString(), WaterPlatform.location(URI.create("water://local/foo.png")));
            assertEquals(WaterMedia.cwd().resolve("a/b.png").toUri().toString(), WaterPlatform.location(URI.create("water://local/a/b.png")));
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("A water://local path that resolves to a Windows share is refused with its code")
        void localShareIsRefused() {
            final PlatformException failure = assertThrows(PlatformException.class,
                    () -> WaterPlatform.location(URI.create("water://local///server/share/x.mp4")));
            assertEquals("WaterMedia Internal: (wm_err:2043) Failed to resolve the water:// URL due to a network share path: water://local///server/share/x.mp4",
                    failure.getMessage());
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("Escaped, extra and device spellings of a share path are refused with the same code")
        void localShareSpellingsAreRefused() {
            // BACKSLASHES, ESCAPED SLASHES, EXTRA SLASHES AND THE \\.\UNC AND \\?\UNC DEVICE PREFIXES ALL NAME ANOTHER MACHINE
            for (final String link: new String[] { "water://local/%5C%5Cserver%5Cshare%5Cx.mp4", "water://local/%2F%2Fserver/share/x.mp4",
                    "water://local////server/share/x.mp4", "water://local/%5C%5C.%5CUNC%5Cserver%5Cshare%5Cx.mp4",
                    "water://local/%5C%5C%3F%5CUNC%5Cserver%5Cshare%5Cx.mp4" }) {
                final PlatformException failure = assertThrows(PlatformException.class, () -> WaterPlatform.location(URI.create(link)), link);
                assertEquals("WaterMedia Internal: (wm_err:2043) Failed to resolve the water:// URL due to a network share path: " + link, failure.getMessage(), link);
            }
        }

        @Test
        @DisplayName("water://remote keeps the raw path against the configured remote URL")
        void remoteKeepsRawPath() throws PlatformException {
            assertEquals(NetworkAPI.remoteUrl() + "/a%20b", WaterPlatform.location(URI.create("water://remote/a%20b")),
                    "A percent-encoded path must reach the remote server unchanged");
        }

        @Test
        @DisplayName("An unknown host is refused with its code, naming the host")
        void unknownHostIsRefused() {
            final PlatformException failure = assertThrows(PlatformException.class, () -> WaterPlatform.location(URI.create("water://elsewhere/x")));
            assertEquals("WaterMedia Internal: (wm_err:2042) Failed to resolve the water:// URL due to unknown host: elsewhere (expected: local, remote, global)",
                    failure.getMessage());
        }
    }

    @Nested
    @DisplayName("resolve() contract")
    class Resolve {

        @Test
        @DisplayName("A water://global address resolves to one source pointing at the global server")
        void globalAddressResolvesToOneSource() throws Exception {
            final Resolution data = new WaterPlatform().resolve(URI.create("water://global/x"));
            assertNotNull(data, "resolve() must return a Resolution for a valid water:// URI");
            assertEquals(1, data.sourceCount(), "Resolved water:// URI must produce exactly one source");
            assertEquals(WaterPlatform.GLOBAL_SERVER + "x", data.sources().get(0).qualities().get(0).uri().toString(),
                    "Resolved variant must point at the mapped global URL");
        }
    }
}
