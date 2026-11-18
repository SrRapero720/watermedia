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
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.internal.WaterPlatform;

import java.net.URI;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Verifies the claim half of {@link Platform#resolve(URI)} offline for every built-in handler but Newgrounds, whose
 * claims {@code NewgroundsPlatformTest} covers: a foreign URI is disclaimed with {@code null}, and an unsupported shape
 * of the handler's own host fails with a {@link PlatformException} that names the handler and is not a mature block,
 * both before any network access. Also the TikTok video id reader and the mature gate of the mature-only PornHub handler.
 */
@DisplayName("Platform URI claim (offline)")
public class PlatformClaimTest {
    // EVERY CASE RUNS WITH THE MATURE GATE OFF, SO A MATURE BLOCK WOULD SHOW AS ONE; THE PROCESS-WIDE SWITCH IS PUT BACK
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

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // ONE CASE OF THE TABLES: THE HANDLER SHOWS BY ITS SIMPLE NAME IN THE CASE LABEL
    private static Arguments row(final Class<? extends Platform> type, final String uri) {
        return arguments(named(type.getSimpleName(), type), uri);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claims")
    class Claims {

        static Stream<Arguments> foreignUris() {
            return Stream.of(
                    // WaterPlatform: THE water:// SCHEME IS THE ONLY SIGNAL
                    row(WaterPlatform.class, "https://foo/"),
                    row(WaterPlatform.class, "file:///tmp/x.png"),

                    // Bluesky: HOST WHITELIST OR AN at:// POST URI
                    row(BlueskyPlatform.class, "https://twitter.com/i/status/1"),

                    // BiliBili: HOST WHITELIST
                    row(BiliBiliPlatform.class, "https://youtube.com/watch?v=x"),

                    // DTube: HOST IS d.tube OR www.d.tube
                    row(DTubePlatform.class, "https://example.com/"),

                    // Drive: HOST IS drive.google.com
                    row(DrivePlatform.class, "https://docs.google.com/file/d/ID/view"),

                    // Dropbox: HOST IS dropbox.com OR www.dropbox.com
                    row(DropboxPlatform.class, "https://example.com/s/abc?dl=0"),

                    // Imgur: HOST IS imgur.com IN ANY CASE
                    row(ImgurPlatform.class, "https://i.imgur.com/abc.png"),
                    row(ImgurPlatform.class, "https://example.com/"),

                    // Kick: HOST IS kick.com OR www.kick.com
                    row(KickPlatform.class, "https://notkick.com/channelname"),
                    row(KickPlatform.class, "https://twitch.tv/x"),

                    // Lightshot: HOST IS prnt.sc IN ANY CASE
                    row(LightshotPlatform.class, "https://light.shot/abc"),

                    // Medal: HOST medal.tv OR www.medal.tv
                    row(MedalPlatform.class, "https://example.com/games/gta-v/clips/abc"),

                    // MediaFire: HOST IS mediafire.com OR www.mediafire.com
                    row(MediaFirePlatform.class, "https://notmediafire.com/file/abc/x.zip"),

                    // Odysee: HOST odysee.com OR www.odysee.com
                    row(OdyseePlatform.class, "https://example.com/"),

                    // PornHub: HOST IS pornhub.com OR *.pornhub.com
                    row(PornHubPlatform.class, "https://example.com/view_video.php?viewkey=abc"),
                    // LOOKALIKE HOST: A DOT-UNAWARE SUFFIX MATCH WOULD WRONGLY CLAIM THIS AND THROW MatureContentException
                    row(PornHubPlatform.class, "https://notpornhub.com/view_video.php?viewkey=abc"),

                    // Sendvid: HOST sendvid.com OR www.sendvid.com
                    row(SendvidPlatform.class, "https://example.com/"),

                    // Streamable: HOST IS streamable.com OR www.streamable.com
                    row(StreamablePlatform.class, "https://streamable.co/abc123"),
                    row(StreamablePlatform.class, "https://example.com/"),

                    // TikTok: HOST WHITELIST
                    row(TikTokPlatform.class, "https://example.com/"),

                    // Twitch: HOST WHITELIST
                    row(TwitchPlatform.class, "https://example.com/"),

                    // Twitter: HOST WHITELIST
                    row(TwitterPlatform.class, "https://example.com/user/status/1"),

                    // VidLii: HOST vidlii.com OR www.vidlii.com
                    row(VidLiiPlatform.class, "https://example.com/"),

                    // yt-dlp / YouTube: THE HOST-SUFFIX MATCH USES A DOT BOUNDARY, SO LOOKALIKES ARE FOREIGN
                    row(YtDlpPlatform.class, "https://example.com/"),
                    row(YtDlpPlatform.class, "https://xinstagr.am/p/abc"),
                    row(YouTubePlatform.class, "https://example.com/"),
                    row(YouTubePlatform.class, "https://notyoutube.com/watch?v=abc"));
        }

        static Stream<Arguments> unsupportedUris() {
            return Stream.of(
                    // WaterPlatform: AN UNKNOWN OR MISSING HOST OF ITS OWN SCHEME
                    row(WaterPlatform.class, "water://unknown/x"),
                    row(WaterPlatform.class, "water:///x"),

                    // Bluesky: NEEDS /profile/<handle>/post/<id> OR AN at:// POST PATTERN
                    row(BlueskyPlatform.class, "https://bsky.app/about"),
                    row(BlueskyPlatform.class, "at://did:plc:abc/app.bsky.feed.like/xyz"),

                    // BiliBili: NO BVID, NO LIVE ROOM
                    row(BiliBiliPlatform.class, "https://www.bilibili.com/"),
                    row(BiliBiliPlatform.class, "https://live.bilibili.com/"),

                    // DTube, Lightshot, VidLii, Odysee, Sendvid: A BARE HOST NAMES NO PAGE
                    row(DTubePlatform.class, "https://d.tube"),
                    row(LightshotPlatform.class, "https://prnt.sc"),
                    row(VidLiiPlatform.class, "https://www.vidlii.com"),
                    row(OdyseePlatform.class, "https://odysee.com/"),
                    row(SendvidPlatform.class, "https://sendvid.com/"),

                    // Drive: /file/d/<id>, /uc?id=<id> OR /open?id=<id>
                    row(DrivePlatform.class, "https://drive.google.com/drive/folders/X"),
                    row(DrivePlatform.class, "https://drive.google.com/file/d/"),
                    row(DrivePlatform.class, "https://drive.google.com/uc?export=download"),

                    // Dropbox: ONLY SHARE LINKS (/s/, /sh/, /scl/fi/) NAME A FILE
                    row(DropboxPlatform.class, "https://www.dropbox.com/home"),
                    row(DropboxPlatform.class, "https://www.dropbox.com/scl/fo/abc/folder"),

                    // Imgur: A MEDIA PATH IS REQUIRED
                    row(ImgurPlatform.class, "https://imgur.com/"),

                    // Kick: A BARE HOST OR A SETTINGS PAGE NAMES NO CHANNEL
                    row(KickPlatform.class, "https://kick.com"),
                    row(KickPlatform.class, "https://kick.com/"),
                    row(KickPlatform.class, "https://kick.com/settings/profile"),

                    // Medal: ONLY CLIP PAGES CARRY AN ID
                    row(MedalPlatform.class, "https://medal.tv/users/xxlynds"),
                    row(MedalPlatform.class, "https://medal.tv/games/gta-v"),

                    // MediaFire: ONLY /file/
                    row(MediaFirePlatform.class, "https://www.mediafire.com/folder/abc"),

                    // PornHub: A viewkey IS REQUIRED, AND THE SHAPE CHECK RUNS BEFORE THE MATURE GATE
                    row(PornHubPlatform.class, "https://www.pornhub.com/embed/abc"),

                    // Streamable: /<id>, /e/<id>, /o/<id> OR /s/<id>/<slug>
                    row(StreamablePlatform.class, "https://streamable.com/"),
                    row(StreamablePlatform.class, "https://streamable.com/a/b/c/d"),

                    // TikTok: A VIDEO OR EMBED ID IS REQUIRED (NO SHORT LINK, SO NO NETWORK)
                    row(TikTokPlatform.class, "https://www.tiktok.com/@user"),

                    // Twitch: CHANNEL, VOD, CLIP OR PLAYER SHAPES ONLY
                    row(TwitchPlatform.class, "https://www.twitch.tv/"),
                    row(TwitchPlatform.class, "https://player.twitch.tv/?foo=bar"),
                    row(TwitchPlatform.class, "https://www.twitch.tv/a/b/c"),

                    // Twitter: A NUMERIC /status/<id> SEGMENT IS REQUIRED
                    row(TwitterPlatform.class, "https://twitter.com/user"),
                    row(TwitterPlatform.class, "https://twitter.com/user/status/abc"),

                    // yt-dlp / YouTube: A BARE HOST MUST NOT SPAWN THE BINARY
                    row(YtDlpPlatform.class, "https://soundcloud.com"),
                    row(YouTubePlatform.class, "https://youtube.com"));
        }

        @ParameterizedTest(name = "{0} resolve({1}) == null")
        @MethodSource("foreignUris")
        @DisplayName("A foreign URI is disclaimed with null before any network access")
        void foreignUriYieldsNull(final Class<? extends Platform> type, final String link) throws Exception {
            final Platform platform = type.getDeclaredConstructor().newInstance();
            assertNull(platform.resolve(URI.create(link)), () -> type.getSimpleName() + ".resolve(" + link + ") must return null for a foreign URI");
        }

        @ParameterizedTest(name = "{0} resolve({1}) throws PlatformException")
        @MethodSource("unsupportedUris")
        @DisplayName("An unsupported shape of a claimed host fails with a PlatformException naming the handler, not a mature block")
        void unsupportedUriThrows(final Class<? extends Platform> type, final String link) throws Exception {
            final Platform platform = type.getDeclaredConstructor().newInstance();
            final URI uri = URI.create(link);
            final PlatformException thrown = assertThrows(PlatformException.class, () -> platform.resolve(uri),
                    () -> type.getSimpleName() + ".resolve(" + link + ") must refuse an unsupported shape of its own host");
            assertFalse(thrown instanceof MatureContentException, "An unsupported shape is not a mature block");
            assertTrue(thrown.getMessage().startsWith(platform.name() + ": "), () -> "The message names the handler: " + thrown.getMessage());
        }

        @Test
        @DisplayName("TikTok reads the video id of canonical and embed links, and none of a profile")
        void tikTokReadsVideoIds() {
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
        @DisplayName("The mature-only PornHub handler refuses a video page before any fetch while mature content is off")
        void matureOnlyPlatformRefusesBeforeFetch() {
            final PornHubPlatform platform = new PornHubPlatform();
            assertThrows(MatureContentException.class, () -> platform.resolve(URI.create("https://www.pornhub.com/view_video.php?viewkey=abc")),
                    "Mature-only platform must throw when mature content is disabled, before any fetch");
        }
    }
}
