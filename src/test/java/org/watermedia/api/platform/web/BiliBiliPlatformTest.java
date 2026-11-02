package org.watermedia.api.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.HttpHeaders;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the offline half of {@link BiliBiliPlatform}: which URIs it claims or refuses without touching the
 * network, and the {@code Cookie} its API calls carry, where the fetched {@code buvid3}/{@code buvid4} device
 * fingerprint completes the user's session cookie instead of replacing it. The API calls themselves are
 * exercised by the manual platform harness.
 */
@DisplayName("BiliBili platform (offline)")
public class BiliBiliPlatformTest {
    private static final String FINGERPRINT = "buvid3=1E89B18A-7FBC-CC1B-368C-23357A901D0D30789infoc; buvid4=165E753D-F9FF-10B8-DB77-9FE95C44872B30789-026110120-e21OmBqK6Dt+Ule0JaNFRw==";
    private static final String SESSION = "SESSDATA=abc123; bili_jct=def456; DedeUserID=789";

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claims")
    class Claims {

        @ParameterizedTest(name = "resolve({0}) == null")
        @ValueSource(strings = {
                "https://youtube.com/watch?v=BV1Zt4y1C7s6",
                "https://notbilibili.com/video/BV1Zt4y1C7s6",
                "https://bilibili.tv/video/BV1Zt4y1C7s6"
        })
        @DisplayName("A foreign host is disclaimed with null")
        void testForeignHostYieldsNull(final String uri) throws Exception {
            assertNull(new BiliBiliPlatform().resolve(URI.create(uri)));
        }

        @ParameterizedTest(name = "resolve({0}) throws PlatformException")
        @ValueSource(strings = {
                "https://www.bilibili.com/",
                "https://www.bilibili.com/video/",
                "https://m.bilibili.com/video/",
                "https://www.bilibili.com/bangumi/play/",
                "https://live.bilibili.com/",
                "https://live.bilibili.com/p/eden/area-tags"
        })
        @DisplayName("An own-host URL without a BV id, an ep/ss id or a room number is refused before any request")
        void testUnsupportedShapeThrows(final String uri) {
            final PlatformException thrown = assertThrows(PlatformException.class, () -> new BiliBiliPlatform().resolve(URI.create(uri)));
            assertFalse(thrown instanceof MatureContentException, "An unsupported shape is not a mature block");
            assertTrue(thrown.getMessage().startsWith(BiliBiliPlatform.NAME + ": Unsupported URL"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Cookie")
    class Cookie {

        @Test
        @DisplayName("Without a user cookie the API call carries the fingerprint alone")
        void testFingerprintAlone() {
            assertEquals(FINGERPRINT, BiliBiliPlatform.cookie("", FINGERPRINT));
            assertEquals(FINGERPRINT, BiliBiliPlatform.cookie(null, FINGERPRINT));
        }

        @Test
        @DisplayName("A user session cookie follows the fingerprint instead of replacing it")
        void testSessionFollowsFingerprint() {
            assertEquals(FINGERPRINT + "; " + SESSION, BiliBiliPlatform.cookie(SESSION, FINGERPRINT));
        }

        @Test
        @DisplayName("A user cookie that already carries buvid3 is sent verbatim")
        void testUserFingerprintWins() {
            final String own = "buvid3=OWN-infoc; " + SESSION;
            assertSame(own, BiliBiliPlatform.cookie(own, FINGERPRINT));
        }

        @Test
        @DisplayName("The merged value is a single valid Cookie header")
        void testHeaderValue() {
            final HttpHeaders headers = new HttpHeaders().set("Cookie", BiliBiliPlatform.cookie(SESSION, FINGERPRINT));
            assertEquals(1, headers.count());
            assertTrue(headers.value("Cookie").startsWith("buvid3="), "The fingerprint leads the cookie");
            assertTrue(headers.value("Cookie").endsWith(SESSION), "The user session closes the cookie");
        }
    }
}
