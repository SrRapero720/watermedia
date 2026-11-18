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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link WaterPlatform#location(URI)} mapping of the {@code local} and {@code remote} hosts, the share and
 * unknown-host refusals, and that {@link WaterPlatform#resolve(URI)} turns a {@code global} address into a
 * single-source resolution without any network access.
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
    class LocationTests {

        @Test
        @DisplayName("water://local resolves against the session working directory, folders included")
        void testLocalResolvesAgainstCwd() throws PlatformException {
            assertEquals(WaterMedia.cwd().resolve("foo.png").toUri().toString(), WaterPlatform.location(URI.create("water://local/foo.png")));
            assertEquals(WaterMedia.cwd().resolve("a/b.png").toUri().toString(), WaterPlatform.location(URI.create("water://local/a/b.png")));
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("A water://local path that resolves to a Windows share is refused with its code")
        void testLocalShareIsRefused() {
            final PlatformException failure = assertThrows(PlatformException.class,
                    () -> WaterPlatform.location(URI.create("water://local///server/share/x.mp4")));
            assertEquals("WaterMedia Internal: (wm_err:2043) Failed to resolve the water:// URL due to a network share path: water://local///server/share/x.mp4",
                    failure.getMessage());
        }

        @Test
        @EnabledOnOs(OS.WINDOWS)
        @DisplayName("Escaped, extra and device spellings of a share path are refused too")
        void testLocalShareSpellingsAreRefused() {
            // BACKSLASHES, ESCAPED SLASHES, EXTRA SLASHES AND THE \\.\UNC AND \\?\UNC DEVICE PREFIXES ALL NAME ANOTHER MACHINE
            for (final String link: new String[] { "water://local/%5C%5Cserver%5Cshare%5Cx.mp4", "water://local/%2F%2Fserver/share/x.mp4",
                    "water://local////server/share/x.mp4", "water://local/%5C%5C.%5CUNC%5Cserver%5Cshare%5Cx.mp4",
                    "water://local/%5C%5C%3F%5CUNC%5Cserver%5Cshare%5Cx.mp4" }) {
                final PlatformException failure = assertThrows(PlatformException.class, () -> WaterPlatform.location(URI.create(link)), link);
                assertTrue(String.valueOf(failure.getMessage()).contains("(wm_err:2043)"), link + " was refused for another reason: " + failure.getMessage());
            }
        }

        @Test
        @DisplayName("water://remote keeps the raw path against the configured remote URL")
        void testRemoteKeepsRawPath() throws PlatformException {
            assertEquals(NetworkAPI.remoteUrl() + "/a%20b", WaterPlatform.location(URI.create("water://remote/a%20b")),
                    "A percent-encoded path must reach the remote server unchanged");
        }

        @Test
        @DisplayName("An unknown host is refused with a PlatformException naming it")
        void testUnknownHostIsRefused() {
            final PlatformException failure = assertThrows(PlatformException.class,
                    () -> WaterPlatform.location(URI.create("water://unknown/x")));
            assertTrue(String.valueOf(failure.getMessage()).contains("unknown"),
                    "The message should name the unknown host, got: " + failure.getMessage());
        }
    }

    @Nested
    @DisplayName("resolve() contract")
    class ResolveTests {

        @Test
        @DisplayName("A valid water:// address resolves to one source pointing at the mapped URL")
        void testResolveResolvesValidUriToInstance() throws Exception {
            final Resolution data = new WaterPlatform().resolve(URI.create("water://global/x"));
            assertNotNull(data, "resolve() must return a Resolution for a valid water:// URI");
            assertEquals(1, data.sourceCount(), "Resolved water:// URI must produce exactly one source");
            assertEquals(WaterPlatform.GLOBAL_SERVER + "x",
                    data.sources().get(0).qualities().get(0).uri().toString(),
                    "Resolved variant must point at the mapped global URL");
        }
    }
}
