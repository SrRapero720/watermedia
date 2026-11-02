package org.watermedia.api.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the offline half of {@link KickPlatform}: which page shapes it reads as a VOD or a clip, which search
 * channels become hits, and which shapes of its own host it refuses before any network access. Live, VOD and clip
 * resolutions need the Kick API and are covered by the manual platform check instead.
 */
@DisplayName("Kick platform (offline)")
public class KickPlatformTest {
    static final String VOD = "c4a80015-c8ac-47a9-a980-ffa277755103";
    static final String CLIP = "clip_01M6B6P65W5DRXR2PE3M7GSZC0";

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("VOD ids")
    class VodIds {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/video/" + VOD,
                "https://kick.com/xqc/videos/" + VOD,
                "https://www.kick.com/xqc/videos/" + VOD + "/",
                "https://kick.com/Video/" + VOD + "?t=120",
                "https://kick.com/xqc/Videos/" + VOD + "#comments"
        })
        @DisplayName("Both the legacy /video/<uuid> and the current /<channel>/videos/<uuid> pages yield the uuid")
        void testBothVodShapesYieldTheUuid(final String page) {
            assertEquals(VOD, KickPlatform.vodId(URI.create(page)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc",
                "https://kick.com/xqc/videos",
                "https://kick.com/xqc/video/" + VOD,
                "https://kick.com/video",
                "https://kick.com/videos/" + VOD,
                "https://kick.com/xqc/videos/" + VOD + "/extra",
                "https://kick.com/xqc/clips/" + CLIP,
                "https://kick.com/"
        })
        @DisplayName("A channel page, a VOD listing or any other shape yields no uuid")
        void testOtherShapesYieldNoUuid(final String page) {
            assertNull(KickPlatform.vodId(URI.create(page)));
        }
    }

    @Nested
    @DisplayName("Clip ids")
    class ClipIds {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc/clips/" + CLIP,
                "https://kick.com/xqc?clip=" + CLIP,
                "https://kick.com/xqc/videos/" + VOD + "?clip=" + CLIP,
                "https://www.kick.com/xqc/Clips/" + CLIP + "/"
        })
        @DisplayName("Both the /<channel>/clips/clip_... path and the ?clip=clip_... query yield the clip id")
        void testBothClipShapesYieldTheClipId(final String page) {
            assertEquals(CLIP, KickPlatform.clipId(URI.create(page)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/xqc/clips",
                "https://kick.com/xqc/clips/latest",
                "https://kick.com/xqc?clip=latest",
                "https://kick.com/xqc/videos/" + VOD
        })
        @DisplayName("A clip listing or an id without the clip_ prefix yields no clip id")
        void testOtherShapesYieldNoClipId(final String page) {
            assertNull(KickPlatform.clipId(URI.create(page)));
        }
    }

    @Nested
    @DisplayName("Search hits")
    class SearchHits {
        // THE LIVE PAYLOAD'S SHAPE: camelCase isLive AND user, snake_case is_banned
        private static final String PAYLOAD = """
                {"channels":[
                  {"slug":"offline","is_banned":false,"isLive":false,"user":{"username":"Offline","profilePic":"https://files.kick.com/a.webp"}},
                  {"slug":"banned","is_banned":true,"isLive":true,"user":{"username":"Banned"}},
                  {"slug":"first","is_banned":false,"isLive":true,"user":{"username":"First","profilePic":"https://files.kick.com/b.webp"}},
                  {"is_banned":false,"isLive":true,"user":{"username":"NoSlug"}},
                  {"slug":"second","is_banned":false,"isLive":true},
                  {"slug":"third","is_banned":false,"isLive":true,"user":{"username":"Third"}}
                ],"categories":[],"livestreams":{"tags":[]}}""";

        @Test
        @DisplayName("Only live, unbanned channels with a slug become hits, labelled by username or slug")
        void testOnlyLiveChannelsAreHits() {
            final List<PlatformResult> hits = new KickPlatform().hits(JSONTool.parse(PAYLOAD, KickPlatform.SearchResponse.class), 10);
            assertEquals(List.of("First", "second", "Third"), hits.stream().map(PlatformResult::title).toList());
            assertEquals(URI.create("https://kick.com/first"), hits.get(0).uri());
            assertEquals(URI.create("https://files.kick.com/b.webp"), hits.get(0).thumbnail());
            assertNull(hits.get(1).thumbnail(), "a channel without a user has no avatar");
        }

        @Test
        @DisplayName("The limit counts kept hits, not the skipped offline and banned channels")
        void testLimitCountsKeptHits() {
            final List<PlatformResult> hits = new KickPlatform().hits(JSONTool.parse(PAYLOAD, KickPlatform.SearchResponse.class), 2);
            assertEquals(List.of("First", "second"), hits.stream().map(PlatformResult::title).toList());
        }

        @Test
        @DisplayName("A payload without channels yields no hits")
        void testNoChannels() {
            assertTrue(new KickPlatform().hits(JSONTool.parse("{\"categories\":[]}", KickPlatform.SearchResponse.class), 2).isEmpty());
        }
    }

    @Nested
    @DisplayName("Claims")
    class Claims {

        @Test
        @DisplayName("A foreign host is disclaimed with null before any network access")
        void testForeignHostYieldsNull() throws PlatformException {
            final KickPlatform platform = new KickPlatform();
            assertNull(platform.resolve(URI.create("https://notkick.com/xqc/videos/" + VOD)));
            assertNull(platform.resolve(URI.create("https://clips.kick.com/clips/9c/" + CLIP + "/playlist.m3u8")));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://kick.com/",
                "https://kick.com/xqc/videos",
                "https://kick.com/xqc/clips",
                "https://kick.com/xqc/video/" + VOD,
                "https://kick.com/xqc/videos/" + VOD + "/extra",
                "https://kick.com/a/b/c/d"
        })
        @DisplayName("A listing or an unknown shape of kick.com is refused without network and is not a mature block")
        void testUnsupportedShapeThrows(final String page) {
            final KickPlatform platform = new KickPlatform();
            final URI uri = URI.create(page);
            final PlatformException thrown = assertThrows(PlatformException.class, () -> platform.resolve(uri));
            assertFalse(thrown instanceof MatureContentException, "An unsupported shape is not a mature block");
            assertTrue(thrown.getMessage().startsWith(KickPlatform.NAME + ": Unsupported URL: "), thrown.getMessage());
        }
    }
}
