package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.tools.JSONTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.watermedia.tools.JSONTool.obj;
import static org.watermedia.tools.JSONTool.str;

/**
 * Verifies the Odysee handler offline against a loopback stand-in of the Odysee JSON-RPC proxy and live API:
 * odysee.com URL shapes map to lbry claim URIs, file streams resolve through {@code resolve} and {@code get} to the
 * player URL stamped with the odysee.com Referer the CDN requires, live claims go through their channel's live status,
 * reposts follow the shared claim, and the type, mature, paid and not-found gates fail with the contract's exceptions.
 */
@DisplayName("Odysee platform (offline)")
public class OdyseePlatformTest {
    private static final String VIDEO = "lbry://@IsaacElite10#8/2026-05-06-21-36-32#5";
    private static final String AUDIO = "lbry://@LBRYFoundation#0/Episode-1#e";
    private static final String LIVE = "lbry://@RT#fd/livestream_RT#d";
    private static final String ENDED = "lbry://@RT#fd/RTlivestream#8";
    private static final String OFFLINE = "lbry://@Quiet#1/stream#2";
    private static final String MATURE = "lbry://@Spicy#1/late-night#2";
    private static final String PAID = "lbry://@Shop#1/premiere#2";
    private static final String COLLECTION = "lbry://@Curator#1/best-of#2";
    private static final String REPOST = "lbry://@Fan#1/share#2";
    private static final String BINARY = "lbry://@Tools#1/installer#2";
    private static final String LIVE_CHANNEL = "fdd11cb3ab75f95efb7b3bc2d726aa13ac915b66";

    // CLAIMS THE STAND-IN resolve KNOWS, KEYED BY LBRY URI; THE SHAPES MIRROR REAL PROXY ANSWERS
    private static final Map<String, String> CLAIMS = Map.of(
            VIDEO, claim("5d2af9ea0959b3938b6e0fbd2eacc550430b169f", "stream", """
                    "title": "Project Gotham Racing 4", "description": "El ultimo juego de la saga", "release_time": "1778197589",
                    "stream_type": "video", "video": {"duration": 950, "width": 1920, "height": 1080},
                    "source": {"media_type": "video/mp4", "sd_hash": "b8e5ee73957a41f6"}, "thumbnail": {"url": "https://thumbs.odycdn.com/6a16.webp"}
                    """, "8e70225396441f70e692b66a4da905695d1f8338", "@IsaacElite10", "IsaacElite10"),
            AUDIO, claim("e7d93d772bd87e2b62d5ab993c1c3ced86ebb396", "stream", """
                    "title": "Community Podcast Episode 1", "release_time": "1591312421", "stream_type": "audio", "audio": {"duration": 2570},
                    "source": {"media_type": "audio/mpeg", "sd_hash": "34b6e10e769550a4"}, "tags": ["lbry", "podcast"]
                    """, "0ed629d2b9c601300cacf7eabe9da0be79010212", "@LBRYFoundation", "The LBRY Foundation"),
            LIVE, claim("d826937ad9bf3b7991eada5034c4612389583bc1", "stream", """
                    "title": "RT News | Livestream 24/7", "release_time": "1741331533", "thumbnail": {"url": "https://thumbs.odycdn.com/fb6a.webp"}
                    """, LIVE_CHANNEL, "@RT", "RT"),
            ENDED, claim("8c06ebe369b6ecf6ad383e4a32bfca34c0168d79", "stream", """
                    "title": "RT livestream", "release_time": "1659109392"
                    """, LIVE_CHANNEL, "@RT", "RT"),
            OFFLINE, claim("2222222222222222222222222222222222222222", "stream", """
                    "title": "Quiet stream"
                    """, "1111111111111111111111111111111111111111", "@Quiet", null),
            MATURE, claim("3333333333333333333333333333333333333333", "stream", """
                    "title": "Late night", "stream_type": "video", "video": {"duration": 60, "width": 1280, "height": 720},
                    "source": {"media_type": "video/mp4", "sd_hash": "aaaaaa"}, "tags": ["gaming", "Hentai"]
                    """, "4444444444444444444444444444444444444444", "@Spicy", "Spicy"),
            PAID, claim("5555555555555555555555555555555555555555", "stream", """
                    "title": "Premiere", "stream_type": "video", "video": {"duration": 60},
                    "source": {"media_type": "video/mp4", "sd_hash": "bbbbbb"}, "fee": {"amount": "1", "currency": "LBC", "address": "bV3u"}
                    """, "6666666666666666666666666666666666666666", "@Shop", "Shop"),
            COLLECTION, claim("8888888888888888888888888888888888888888", "collection", """
                    "title": "Best of", "claims": ["5d2af9ea0959b3938b6e0fbd2eacc550430b169f"]
                    """, "cccccccccccccccccccccccccccccccccccccccc", "@Curator", "Curator"),
            REPOST, "{\"claim_id\": \"7777777777777777777777777777777777777777\", \"value_type\": \"repost\", \"value\": {},"
                    + " \"reposted_claim\": " + claim("5d2af9ea0959b3938b6e0fbd2eacc550430b169f", "stream", """
                    "title": "Project Gotham Racing 4", "stream_type": "video", "video": {"duration": 950, "width": 1920, "height": 1080},
                    "source": {"media_type": "video/mp4", "sd_hash": "b8e5ee73957a41f6"}
                    """, "8e70225396441f70e692b66a4da905695d1f8338", "@IsaacElite10", "IsaacElite10") + "}",
            BINARY, claim("9999999999999999999999999999999999999999", "stream", """
                    "title": "Installer", "stream_type": "binary", "source": {"media_type": "application/octet-stream", "sd_hash": "cccccc"}
                    """, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "@Tools", "Tools"));

    // EVERY CALL THE STAND-IN SERVED: "resolve <uri>", "get <uri>", "live <channel>" OR "master"
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private LocalHttp http;
    private OdyseePlatform platform;
    private boolean previousMature;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeEach
    void start() {
        this.previousMature = WaterMediaConfig.platforms.allowMatureContent;
        WaterMediaConfig.platforms.allowMatureContent = false;
        this.http = LocalHttp.start("/", this::serve);
        this.platform = new OdyseePlatform(this.http.uri("/proxy").toString(), this.http.uri("/live?channel_claim_id=").toString());
    }

    @AfterEach
    void stop() {
        WaterMediaConfig.platforms.allowMatureContent = this.previousMature;
        this.http.close();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // THE STAND-IN: /proxy ANSWERS resolve AND get LIKE api.na-backend.odysee.com, /live LIKE api.odysee.live, /content THE HLS MASTER
    private void serve(final HttpExchange exchange) throws IOException {
        final String path = exchange.getRequestURI().getPath();
        final String query = exchange.getRequestURI().getQuery();
        if (path.equals("/proxy")) {
            final JsonObject body = JSONTool.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            final String method = str(body, "method");
            final JsonObject params = obj(body, "params");
            if ("resolve".equals(method)) {
                final String uri = params.getAsJsonArray("urls").get(0).getAsString();
                this.calls.add("resolve " + uri);
                final String claim = CLAIMS.getOrDefault(uri, "{\"error\": {\"name\": \"NOT_FOUND\", \"text\": \"Could not find claim at \\\"" + uri + "\\\".\"}}");
                LocalHttp.respond(exchange, "application/json; charset=utf-8", ("{\"jsonrpc\": \"2.0\", \"result\": {\"" + uri + "\": " + claim + "}, \"id\": 1}").getBytes(StandardCharsets.UTF_8), 0);
                return;
            }
            if ("get".equals(method)) {
                final String uri = str(params, "uri");
                this.calls.add("get " + uri);
                // ONLY A CLAIM WITH A FILE STREAMS; THE REAL PROXY ANSWERS THE SAME ERROR FOR REPOSTS AND LIVE CLAIMS.
                // A PERMANENT URL (lbry://name#<claim id>) NAMES THE FILE OF THE CLAIM IT ENDS WITH
                final JsonObject claim = JSONTool.parse(CLAIMS.getOrDefault(uri, "{}"), JsonObject.class);
                final String claimId = str(claim, "claim_id") != null ? str(claim, "claim_id") : uri.substring(uri.lastIndexOf('#') + 1);
                final String answer = obj(claim, "value", "source") == null && !uri.startsWith("lbry://name#")
                        ? "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32603, \"message\": \"stream doesn't have source data\"}, \"id\": 0}"
                        : "{\"jsonrpc\": \"2.0\", \"result\": {\"streaming_url\": \"" + this.http.uri("/v6/streams/" + claimId + "/b8e5ee.mp4") + "\"}, \"id\": 1}";
                LocalHttp.respond(exchange, "application/json; charset=utf-8", answer.getBytes(StandardCharsets.UTF_8), 0);
                return;
            }
            LocalHttp.respond(exchange, "application/json; charset=utf-8", "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": -32601, \"message\": \"Method not found\"}, \"id\": 0}".getBytes(StandardCharsets.UTF_8), 0);
            return;
        }
        if (path.equals("/live")) {
            final String channel = query.substring("channel_claim_id=".length());
            this.calls.add("live " + channel);
            final boolean live = LIVE_CHANNEL.equals(channel);
            final String data = live
                    ? "{\"Live\": true, \"Start\": \"2026-10-24T12:13:39Z\", \"VideoURL\": \"" + this.http.uri("/content/" + channel + "/master.m3u8") + "\", \"ViewerCount\": 93,"
                    + " \"ActiveClaim\": {\"ClaimID\": \"d826937ad9bf3b7991eada5034c4612389583bc1\", \"Protected\": false}}"
                    : "{\"Live\": false, \"Start\": \"\", \"VideoURL\": \"\", \"ActiveClaim\": null}";
            LocalHttp.respond(exchange, "application/json; charset=utf-8", ("{\"success\": true, \"error\": null, \"data\": " + data + "}").getBytes(StandardCharsets.UTF_8), 0);
            return;
        }
        if (path.endsWith("/master.m3u8")) {
            this.calls.add("master");
            final String master = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=30\n1080.m3u8\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1280x720,FRAME-RATE=30\n720.m3u8\n";
            LocalHttp.respond(exchange, "application/x-mpegURL", master.getBytes(StandardCharsets.UTF_8), 0);
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // ONE PROXY CLAIM AS THE REAL resolve ANSWERS IT: THE value BODY GIVEN PLUS ITS IDS, URLS AND SIGNING CHANNEL
    private static String claim(final String claimId, final String valueType, final String value, final String channelId, final String channelName, final String channelTitle) {
        final String channel = channelId == null ? "" : ", \"signing_channel\": {\"claim_id\": \"" + channelId + "\", \"name\": \"" + channelName + "\", \"value\": {"
                + (channelTitle == null ? "" : "\"title\": \"" + channelTitle + "\"") + "}}";
        return "{\"claim_id\": \"" + claimId + "\", \"permanent_url\": \"lbry://name#" + claimId + "\", \"value_type\": \"" + valueType + "\", \"timestamp\": 1700000000,"
                + " \"value\": {" + value + "}" + channel + "}";
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claim URIs")
    class ClaimUris {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(delimiter = '|', value = {
                "https://odysee.com/@IsaacElite10:8/2026-05-06-21-36-32:5                    | lbry://@IsaacElite10#8/2026-05-06-21-36-32#5",
                "https://odysee.com/@IsaacElite10:8/2026-05-06-21-36-32:5/?lid=abc&t=12      | lbry://@IsaacElite10#8/2026-05-06-21-36-32#5",
                "https://www.odysee.com/@gardeningincanada:b/plants-i-will-never-grow:e      | lbry://@gardeningincanada#b/plants-i-will-never-grow#e",
                "https://odysee.com/@IsaacElite10/2026-05-06-21-36-32                         | lbry://@IsaacElite10/2026-05-06-21-36-32",
                "https://odysee.com/Episode-1:e7d93d772bd87e2b62d5ab993c1c3ced86ebb396       | lbry://Episode-1#e7d93d772bd87e2b62d5ab993c1c3ced86ebb396",
                "https://odysee.com/$/embed/@IsaacElite10:8/2026-05-06-21-36-32:5            | lbry://@IsaacElite10#8/2026-05-06-21-36-32#5",
                "https://odysee.com/$/embed/Episode-1/e7d93d772bd87e2b62d5ab993c1c3ced86ebb396 | lbry://Episode-1#e7d93d772bd87e2b62d5ab993c1c3ced86ebb396",
                "https://odysee.com/$/download/Episode-1/e7d93d772bd87e2b62d5ab993c1c3ced86ebb396 | lbry://Episode-1#e7d93d772bd87e2b62d5ab993c1c3ced86ebb396",
                "https://odysee.com/@DTV:9/%D0%A1%D0%B0%D0%BC%D1%8B%D0%B9-%D0%BC%D1%83%D0%BB%D1%8C%D1%82:a | lbry://@DTV#9/Самый-мульт#a"
        })
        @DisplayName("Canonical, embed and download URLs map to the lbry claim URI")
        void testClaimUris(final String url, final String expected) {
            assertEquals(expected, OdyseePlatform.claimUri(URI.create(url)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://odysee.com/", "https://odysee.com/@IsaacElite10:8", "https://odysee.com/@IsaacElite10:8/",
                "https://odysee.com/$/playlist/ffef782f27486f0ac138bde8777f72ebdd0548c2", "https://odysee.com/$/search?q=minecraft",
                "https://odysee.com/$/embed/", "https://odysee.com/@ch:1/name:2/extra", "https://odysee.com/name/not-a-claim-id"
        })
        @DisplayName("Channels, playlists and other pages name no stream")
        void testNoClaim(final String url) {
            assertNull(OdyseePlatform.claimUri(URI.create(url)));
        }

        @Test
        @DisplayName("A channel URL is refused before any request")
        void testChannelUrlUnsupported() {
            final PlatformException e = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@IsaacElite10:8")));
            assertTrue(e.getMessage().startsWith("Odysee: Unsupported URL"), e.getMessage());
            assertTrue(OdyseePlatformTest.this.calls.isEmpty(), "No request may leave for an unsupported shape");
        }
    }

    @Nested
    @DisplayName("File streams")
    class FileStreams {

        @Test
        @DisplayName("A video resolves through resolve and get to the player URL with its size, metadata and Referer")
        void testVideo() throws Exception {
            final URI page = URI.create("https://odysee.com/@IsaacElite10:8/2026-05-06-21-36-32:5");
            final Resolution resolution = OdyseePlatformTest.this.platform.resolve(page);

            assertNull(resolution.expires(), "An unsigned player URL never expires");
            final Source source = resolution.sources().get(0);
            assertEquals(MediaType.VIDEO, source.type());
            assertEquals(URI.create("https://thumbs.odycdn.com/6a16.webp"), source.thumbnail());
            final Quality quality = source.qualities().get(0);
            assertEquals(OdyseePlatformTest.this.http.uri("/v6/streams/5d2af9ea0959b3938b6e0fbd2eacc550430b169f/b8e5ee.mp4"), quality.uri());
            assertEquals(1920, quality.width());
            assertEquals(1080, quality.height());
            assertEquals("Project Gotham Racing 4", source.metadata().title());
            assertEquals("El ultimo juego de la saga", source.metadata().description());
            assertEquals("IsaacElite10", source.metadata().author());
            assertEquals(950_000L, source.metadata().durationMs());
            assertEquals(Instant.ofEpochSecond(1_778_197_589L), source.metadata().postedAt());
            // THE PLAYER ORIGIN REFUSES REQUESTS WITHOUT AN odysee.com REFERER
            assertEquals("https://odysee.com/", source.headers().value("Referer"));
            assertEquals(List.of("resolve " + VIDEO, "get " + VIDEO), OdyseePlatformTest.this.calls);
        }

        @Test
        @DisplayName("An audio claim is an AUDIO source without dimensions")
        void testAudio() throws Exception {
            final Source source = OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@LBRYFoundation:0/Episode-1:e")).sources().get(0);
            assertEquals(MediaType.AUDIO, source.type());
            assertEquals(0, source.qualities().get(0).width());
            assertEquals(2_570_000L, source.metadata().durationMs());
            assertEquals("The LBRY Foundation", source.metadata().author());
        }

        @Test
        @DisplayName("A repost plays the shared claim and asks get for that claim's own URL")
        void testRepost() throws Exception {
            final Source source = OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Fan:1/share:2")).sources().get(0);
            assertEquals(OdyseePlatformTest.this.http.uri("/v6/streams/5d2af9ea0959b3938b6e0fbd2eacc550430b169f/b8e5ee.mp4"), source.qualities().get(0).uri());
            // get ON THE REPOST URL ITSELF ANSWERS "stream doesn't have source data"; THE SHARED CLAIM'S PERMANENT URL STREAMS
            assertEquals(List.of("resolve " + REPOST, "get lbry://name#5d2af9ea0959b3938b6e0fbd2eacc550430b169f"), OdyseePlatformTest.this.calls);
        }

        @Test
        @DisplayName("The embed URL shape resolves the same claim")
        void testEmbedShape() throws Exception {
            final Source source = OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/$/embed/@IsaacElite10:8/2026-05-06-21-36-32:5")).sources().get(0);
            assertEquals("Project Gotham Racing 4", source.metadata().title());
        }
    }

    @Nested
    @DisplayName("Live streams")
    class LiveStreams {

        @Test
        @DisplayName("A claim without a file resolves its channel's live HLS ladder with a TTL")
        void testLive() throws Exception {
            final long before = System.currentTimeMillis();
            final Resolution resolution = OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@RT:fd/livestream_RT:d"));

            assertNotNull(resolution.expires(), "A live playlist must be re-resolved");
            assertTrue(resolution.expires().toEpochMilli() > before, "The TTL lies in the future");
            final Source source = resolution.sources().get(0);
            assertEquals(MediaType.VIDEO, source.type());
            assertEquals(2, source.qualities().size(), "Both master renditions are reported");
            assertEquals(1080, source.qualities().get(0).height());
            assertEquals(0L, source.metadata().durationMs());
            assertEquals(Instant.parse("2026-10-24T12:13:39Z"), source.metadata().postedAt(), "The broadcast start replaces the claim's release time");
            assertEquals("RT", source.metadata().author());
            assertEquals(List.of("resolve " + LIVE, "live " + LIVE_CHANNEL, "master"), OdyseePlatformTest.this.calls);
        }

        @Test
        @DisplayName("A past livestream claim of a channel live on another claim has nothing to play")
        void testEnded() {
            final PlatformException e = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@RT:fd/RTlivestream:8")));
            assertTrue(e.getMessage().contains("has ended"), e.getMessage());
        }

        @Test
        @DisplayName("An offline channel fails instead of returning a dead source")
        void testOffline() {
            final PlatformException e = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Quiet:1/stream:2")));
            assertTrue(e.getMessage().contains("not live"), e.getMessage());
            assertFalse(OdyseePlatformTest.this.calls.contains("master"), "No playlist is fetched for an offline channel");
        }
    }

    @Nested
    @DisplayName("Gates")
    class Gates {

        @Test
        @DisplayName("A mature tag blocks the stream while mature content is off, before get")
        void testMatureBlocked() {
            final PlatformException e = assertThrows(MatureContentException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Spicy:1/late-night:2")));
            assertTrue(e.getMessage().contains("Hentai"), e.getMessage());
            assertEquals(List.of("resolve " + MATURE), OdyseePlatformTest.this.calls);
        }

        @Test
        @DisplayName("A mature tag is ignored while mature content is allowed")
        void testMatureAllowed() throws Exception {
            WaterMediaConfig.platforms.allowMatureContent = true;
            final Source source = OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Spicy:1/late-night:2")).sources().get(0);
            assertEquals(720, source.qualities().get(0).height());
        }

        @Test
        @DisplayName("Paid content fails with its price instead of a source the player cannot open")
        void testPaid() {
            final PlatformException e = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Shop:1/premiere:2")));
            assertFalse(e instanceof MatureContentException);
            assertTrue(e.getMessage().contains("paid content (1 LBC)"), e.getMessage());
        }

        @Test
        @DisplayName("An unknown claim, a non-media file and a collection fail with a named reason")
        void testNotPlayable() {
            final PlatformException unknown = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@IsaacElite10:8/anything:1")));
            assertTrue(unknown.getMessage().contains("Could not find claim"), unknown.getMessage());
            final PlatformException binary = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Tools:1/installer:2")));
            assertTrue(binary.getMessage().contains("'binary' file"), binary.getMessage());
            final PlatformException collection = assertThrows(PlatformException.class,
                    () -> OdyseePlatformTest.this.platform.resolve(URI.create("https://odysee.com/@Curator:1/best-of:2")));
            assertTrue(collection.getMessage().contains("is a collection, not a stream"), collection.getMessage());
            assertFalse(OdyseePlatformTest.this.calls.stream().anyMatch(c -> c.startsWith("get ")), "Nothing unplayable reaches get");
        }
    }
}
