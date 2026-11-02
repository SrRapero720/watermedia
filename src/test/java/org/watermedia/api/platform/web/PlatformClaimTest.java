package org.watermedia.api.platform.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformApiTest;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.platform.internal.WaterPlatformTest;

import java.net.URI;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Verifies the URI-claim half of {@link Platform#resolve(URI)} offline. A foreign URI must be
 * rejected with a {@code null} return before any network access, and a URI of a claimed host whose
 * shape the handler does not support must fail with a {@link PlatformException} that is not a mature
 * block, again without touching the network. These cases exercise host matching, suffix rules, path
 * prefixes and pattern matchers.
 * <p>
 * The success (instance) state is covered offline by {@link WaterPlatformTest}, the registry-level
 * wiring by {@link PlatformApiTest}, and the mature-content gate by the mature-only case below.
 */
@DisplayName("Platform URI claim (offline)")
public class PlatformClaimTest {

    // THE MATURE GATE IS CHECKED OFF, SO A MATURE-ONLY HANDLER REJECTS BEFORE ANY NETWORK ACCESS
    private boolean previousMature;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeEach
    void rememberConfig() {
        this.previousMature = WaterMediaConfig.platforms.allowMatureContent;
        WaterMediaConfig.platforms.allowMatureContent = false;
    }

    @AfterEach
    void restoreConfig() {
        WaterMediaConfig.platforms.allowMatureContent = this.previousMature;
    }

    static Stream<Arguments> foreignUris() {
        return Stream.of(
                // WaterPlatform: THE water:// SCHEME IS THE ONLY SIGNAL
                arguments(WaterPlatform.class, "https://foo/"),
                arguments(WaterPlatform.class, "file:///tmp/x.png"),

                // Bluesky: HOST WHITELIST OR AN at:// POST URI
                arguments(BlueskyPlatform.class, "https://twitter.com/i/status/1"),

                // BiliBili: HOST WHITELIST
                arguments(BiliBiliPlatform.class, "https://youtube.com/watch?v=x"),

                // DTube: HOST IS d.tube OR www.d.tube
                arguments(DTubePlatform.class, "https://example.com/"),

                // Drive: HOST IS drive.google.com
                arguments(DrivePlatform.class, "https://docs.google.com/file/d/ID/view"),

                // Dropbox: HOST IS dropbox.com OR www.dropbox.com
                arguments(DropboxPlatform.class, "https://example.com/s/abc?dl=0"),

                // Imgur: HOST equalsIgnoreCase imgur.com
                arguments(ImgurPlatform.class, "https://i.imgur.com/abc.png"),
                arguments(ImgurPlatform.class, "https://example.com/"),

                // Kick: HOST IS kick.com OR www.kick.com
                arguments(KickPlatform.class, "https://notkick.com/channelname"),
                arguments(KickPlatform.class, "https://twitch.tv/x"),

                // Lightshot: HOST equalsIgnoreCase prnt.sc
                arguments(LightshotPlatform.class, "https://light.shot/abc"),

                // Medal: HOST medal.tv OR www.medal.tv
                arguments(MedalPlatform.class, "https://example.com/games/gta-v/clips/abc"),

                // MediaFire: HOST IS mediafire.com OR www.mediafire.com
                arguments(MediaFirePlatform.class, "https://notmediafire.com/file/abc/x.zip"),

                // Odysee: HOST odysee.com OR www.odysee.com
                arguments(OdyseePlatform.class, "https://example.com/"),

                // PornHub: HOST IS pornhub.com OR *.pornhub.com
                arguments(PornHubPlatform.class, "https://example.com/view_video.php?viewkey=abc"),
                // LOOKALIKE HOST: A DOT-UNAWARE SUFFIX MATCH WOULD WRONGLY CLAIM THIS AND THROW MatureContentException
                arguments(PornHubPlatform.class, "https://notpornhub.com/view_video.php?viewkey=abc"),

                // Sendvid: HOST sendvid.com OR www.sendvid.com
                arguments(SendvidPlatform.class, "https://example.com/"),

                // Streamable: HOST IS streamable.com OR www.streamable.com
                arguments(StreamablePlatform.class, "https://streamable.co/abc123"),
                arguments(StreamablePlatform.class, "https://example.com/"),

                // TikTok: HOST WHITELIST
                arguments(TikTokPlatform.class, "https://example.com/"),

                // Twitch: HOST WHITELIST
                arguments(TwitchPlatform.class, "https://example.com/"),

                // Twitter: HOST WHITELIST
                arguments(TwitterPlatform.class, "https://example.com/user/status/1"),

                // VidLii: HOST vidlii.com OR www.vidlii.com
                arguments(VidLiiPlatform.class, "https://example.com/"),

                // yt-dlp / YouTube: THE HOST-SUFFIX MATCH USES A DOT BOUNDARY, SO LOOKALIKES ARE FOREIGN
                arguments(YtDlpPlatform.class, "https://example.com/"),
                arguments(YtDlpPlatform.class, "https://xinstagr.am/p/abc"),
                arguments(YouTubePlatform.class, "https://example.com/"),
                arguments(YouTubePlatform.class, "https://notyoutube.com/watch?v=abc")
        );
    }

    static Stream<Arguments> unsupportedUris() {
        return Stream.of(
                // WaterPlatform: AN UNKNOWN OR MISSING HOST OF ITS OWN SCHEME
                arguments(WaterPlatform.class, "water://unknown/x"),
                arguments(WaterPlatform.class, "water:///x"),

                // Bluesky: NEEDS /profile/<handle>/post/<id> OR AN at:// POST PATTERN
                arguments(BlueskyPlatform.class, "https://bsky.app/about"),
                arguments(BlueskyPlatform.class, "at://did:plc:abc/app.bsky.feed.like/xyz"),

                // BiliBili: NO BVID, NO LIVE ROOM
                arguments(BiliBiliPlatform.class, "https://www.bilibili.com/"),
                arguments(BiliBiliPlatform.class, "https://live.bilibili.com/"),

                // DTube, Lightshot, VidLii, Odysee, Sendvid: A BARE HOST NAMES NO PAGE
                arguments(DTubePlatform.class, "https://d.tube"),
                arguments(LightshotPlatform.class, "https://prnt.sc"),
                arguments(VidLiiPlatform.class, "https://www.vidlii.com"),
                arguments(OdyseePlatform.class, "https://odysee.com/"),
                arguments(SendvidPlatform.class, "https://sendvid.com/"),

                // Drive: /file/d/<id>, /uc?id=<id> OR /open?id=<id>
                arguments(DrivePlatform.class, "https://drive.google.com/drive/folders/X"),
                arguments(DrivePlatform.class, "https://drive.google.com/file/d/"),
                arguments(DrivePlatform.class, "https://drive.google.com/uc?export=download"),

                // Dropbox: ONLY SHARE LINKS (/s/, /sh/, /scl/fi/) NAME A FILE
                arguments(DropboxPlatform.class, "https://www.dropbox.com/home"),
                arguments(DropboxPlatform.class, "https://www.dropbox.com/scl/fo/abc/folder"),

                // Imgur: A MEDIA PATH IS REQUIRED
                arguments(ImgurPlatform.class, "https://imgur.com/"),

                // Kick: A BARE HOST OR A SETTINGS PAGE NAMES NO CHANNEL
                arguments(KickPlatform.class, "https://kick.com"),
                arguments(KickPlatform.class, "https://kick.com/"),
                arguments(KickPlatform.class, "https://kick.com/settings/profile"),

                // Medal: ONLY CLIP PAGES CARRY AN ID
                arguments(MedalPlatform.class, "https://medal.tv/users/xxlynds"),
                arguments(MedalPlatform.class, "https://medal.tv/games/gta-v"),

                // MediaFire: ONLY /file/
                arguments(MediaFirePlatform.class, "https://www.mediafire.com/folder/abc"),

                // PornHub: A viewkey IS REQUIRED, AND THE SHAPE CHECK RUNS BEFORE THE MATURE GATE
                arguments(PornHubPlatform.class, "https://www.pornhub.com/embed/abc"),

                // Streamable: /<id>, /e/<id>, /o/<id> OR /s/<id>/<slug>
                arguments(StreamablePlatform.class, "https://streamable.com/"),
                arguments(StreamablePlatform.class, "https://streamable.com/a/b/c/d"),

                // TikTok: A VIDEO OR EMBED ID IS REQUIRED (NO SHORT LINK, SO NO NETWORK)
                arguments(TikTokPlatform.class, "https://www.tiktok.com/@user"),

                // Twitch: CHANNEL, VOD, CLIP OR PLAYER SHAPES ONLY
                arguments(TwitchPlatform.class, "https://www.twitch.tv/"),
                arguments(TwitchPlatform.class, "https://player.twitch.tv/?foo=bar"),
                arguments(TwitchPlatform.class, "https://www.twitch.tv/a/b/c"),

                // Twitter: A NUMERIC /status/<id> SEGMENT IS REQUIRED
                arguments(TwitterPlatform.class, "https://twitter.com/user"),
                arguments(TwitterPlatform.class, "https://twitter.com/user/status/abc"),

                // yt-dlp / YouTube: A BARE HOST MUST NOT SPAWN THE BINARY
                arguments(YtDlpPlatform.class, "https://soundcloud.com"),
                arguments(YouTubePlatform.class, "https://youtube.com")
        );
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claims")
    class Claims {

        @ParameterizedTest(name = "{0} resolve({1}) == null")
        @MethodSource("org.watermedia.api.platform.web.PlatformClaimTest#foreignUris")
        @DisplayName("A foreign URI is disclaimed with null before any network access")
        void testForeignUriYieldsNull(final Class<? extends Platform> type, final String uriString) throws Exception {
            final Platform platform = type.getDeclaredConstructor().newInstance();
            final URI uri = URI.create(uriString);
            assertNull(platform.resolve(uri),
                    () -> type.getSimpleName() + ".resolve(" + uriString + ") must return null for a foreign URI");
        }

        @ParameterizedTest(name = "{0} resolve({1}) throws PlatformException")
        @MethodSource("org.watermedia.api.platform.web.PlatformClaimTest#unsupportedUris")
        @DisplayName("An unsupported shape of a claimed host fails with a PlatformException that is not a mature block")
        void testUnsupportedUriThrows(final Class<? extends Platform> type, final String uriString) throws Exception {
            final Platform platform = type.getDeclaredConstructor().newInstance();
            final URI uri = URI.create(uriString);
            final PlatformException thrown = assertThrows(PlatformException.class, () -> platform.resolve(uri),
                    () -> type.getSimpleName() + ".resolve(" + uriString + ") must refuse an unsupported shape of its own host");
            assertFalse(thrown instanceof MatureContentException, "An unsupported shape is not a mature block");
            assertTrue(thrown.getMessage().startsWith(platform.name() + ": "), "The message names the handler");
        }

        @Test
        @DisplayName("TikTok reads the video id of canonical and embed links")
        void testTikTokVideoIds() {
            assertEquals("7300000000000000001", TikTokPlatform.videoId("https://www.tiktok.com/@user/video/7300000000000000001"));
            assertEquals("7300000000000000002", TikTokPlatform.videoId("https://www.tiktok.com/embed/v2/7300000000000000002"));
            assertEquals("7300000000000000003", TikTokPlatform.videoId("https://www.tiktok.com/embed/7300000000000000003"));
            assertNull(TikTokPlatform.videoId("https://www.tiktok.com/@user"));
        }
    }

    @Nested
    @DisplayName("Mature gate")
    class MatureGate {

        @Test
        @DisplayName("Mature-only platform throws before any fetch when mature content is disabled")
        void testMatureContentDisabledThrowsBeforeFetch() {
            final PornHubPlatform platform = new PornHubPlatform();
            assertThrows(MatureContentException.class,
                    () -> platform.resolve(URI.create("https://www.pornhub.com/view_video.php?viewkey=abc")),
                    "Mature-only platform must throw when mature content is disabled, before any fetch");
        }
    }
}
