package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies which channels of a Twitch search become hits, offline: only the live ones, which carry a stream. */
@DisplayName("Twitch platform (offline)")
public class TwitchPlatformTest {

    @Nested
    @DisplayName("Search hits")
    class SearchHits {
        // THE searchFor SHAPE THE INLINE GQL QUERY ASKS FOR; AN OFFLINE CHANNEL ANSWERS stream: null
        private static final String DATA = """
                {"searchFor":{"channels":{"edges":[
                  {"item":{"login":"minecraft","displayName":"Minecraft","profileImageURL":"https://cdn.twitch.tv/m.png","broadcastSettings":{"title":"Minecraft LIVE"},"stream":null}},
                  {"item":{"login":"nooreax","displayName":"Nooreax","profileImageURL":"https://cdn.twitch.tv/n.png","broadcastSettings":{"title":"CRAFTATTACK"},"stream":{"previewImageURL":"https://cdn.twitch.tv/live_nooreax-{width}x{height}.jpg"}}},
                  {"item":{"displayName":"NoLogin","stream":{"previewImageURL":"https://cdn.twitch.tv/x.jpg"}}},
                  {"item":{"login":"fresh","displayName":"Fresh","profileImageURL":"https://cdn.twitch.tv/f.png","broadcastSettings":{},"stream":{}}},
                  {"item":null}
                ]}}}""";

        @Test
        @DisplayName("Only live channels with a login become hits, titled by stream or display name")
        void testOnlyLiveChannelsAreHits() {
            final List<PlatformResult> hits = new TwitchPlatform().hits(JSONTool.parse(DATA, JsonObject.class), 10);
            assertEquals(List.of("CRAFTATTACK", "Fresh"), hits.stream().map(PlatformResult::title).toList());
            assertEquals(URI.create("https://www.twitch.tv/nooreax"), hits.get(0).uri());
            assertTrue(hits.get(0).thumbnail().toString().startsWith("https://cdn.twitch.tv/live_nooreax-"), "a live channel shows its stream preview");
            assertEquals(URI.create("https://cdn.twitch.tv/f.png"), hits.get(1).thumbnail(), "a stream without a preview falls back to the profile picture");
        }

        @Test
        @DisplayName("The limit counts kept hits, not the skipped offline channels")
        void testLimitCountsKeptHits() {
            final List<PlatformResult> hits = new TwitchPlatform().hits(JSONTool.parse(DATA, JsonObject.class), 1);
            assertEquals(List.of("CRAFTATTACK"), hits.stream().map(PlatformResult::title).toList());
        }

        @Test
        @DisplayName("A response without channels yields no hits")
        void testNoChannels() {
            assertTrue(new TwitchPlatform().hits(JSONTool.parse("{\"searchFor\":{}}", JsonObject.class), 2).isEmpty());
        }
    }
}
