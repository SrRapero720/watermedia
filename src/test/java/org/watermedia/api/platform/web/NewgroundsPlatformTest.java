package org.watermedia.api.platform.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.MediaType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Newgrounds handler offline: the URL claims, the movie, audio and art page parsers with the mature
 * gate, and the NG Guard proof-of-work solver with its argon2id and BLAKE2b primitives against published vectors
 * and a puzzle the live guard accepted.
 */
@DisplayName("Newgrounds platform (offline)")
public class NewgroundsPlatformTest {
    private static final String MOVIE_HTML = """
            <html><head><title>Metal Gear Awesome</title>
            <meta property="og:title" content="Metal Gear &amp; Co&#039;s Awesome" />
            <meta property="og:description" content="Metal Gear is awesome &lt;3" />
            <meta property="og:image" content="https://picon.ngfiles.com/297000/flash_297383_card.png?f1601084239" />
            </head><body>
            <h2 class="rated-t" itemprop="name">Metal Gear Awesome</h2>
            <meta itemprop="datePublished" content="2006-02-23T02:54:52-05:00">
            <div class="item-details-main"><h4><a href="https://egoraptor.newgrounds.com">Egoraptor</a></h4></div>
            </body></html>
            """;
    private static final String MOVIE_JSON = """
            {"id": 297383, "title": "Metal Gear Awesome", "author": "Egoraptor", "sources": {
              "1080p": [{"type": "video\\/mp4", "src": "https:\\/\\/uploads.ungrounded.net\\/alternate\\/65000\\/65289_alternate_865.1080p.mp4?1360102222"}],
              "720p": [{"type": "application\\/x-shockwave-flash", "src": "https:\\/\\/uploads.ungrounded.net\\/alternate\\/65000\\/65289.swf"},
                       {"type": "video\\/mp4", "src": "https:\\/\\/uploads.ungrounded.net\\/alternate\\/65000\\/65289_alternate_865.720p.mp4?1360102221"}],
              "360p": [{"type": "video\\/mp4", "src": "https:\\/\\/uploads.ungrounded.net\\/alternate\\/65000\\/65289_alternate_865.360p.mp4?1360102221"}]}}
            """;
    private static final String AUDIO_HTML = """
            <html><head><title>B7 - BusMode</title>
            <meta property="og:title" content="B7 - BusMode" />
            <meta property="og:image" content="https://aicon.ngfiles.com/549/549479.png?f1378892949" />
            </head><body>
            <meta itemprop="datePublished" content="2013-09-11T05:49:05-04:00">
            <script>NgAudioPlayer.fromListenPage({ 'generic_id': 549479, 'type_id': 3, 'url': "https:\\/\\/audio.ngfiles.com\\/549000\\/549479_B7---BusMode.mp3?f1378892946", 'version': 1378892946, 'duration': 143, 'loop': false, 'images': {"condensed":{"completed":{"url":"\\/\\/img.ngfiles.com\\/x.png"}}}});</script>
            <div class="item-details"><div class="item-details-main">
              <h4>
                <a href="https://burn7.newgrounds.com">Burn7</a>
              </h4></div><div class="item-details-meta"><div class="role"><em>Artist</em></div></div></div>
            </body></html>
            """;
    private static final String ART_HTML = """
            <html><head><meta property="og:title" content="Geistober 2026" /></head><body>
            <h2 class="rated-e" itemprop="name">Geistober 2026</h2>
            <div class="item-details-main"><h4><a href="https://luwano.newgrounds.com">Luwano</a></h4></div>
            <script>(() => { let imageData = [
              {"id": 3877908, "icon": "https:\\/\\/art.ngfiles.com\\/thumbnails\\/8242000\\/8242720\\/8242720_3877908_full.webp", "suitability": "e",
               "image": "https:\\/\\/art.ngfiles.com\\/images\\/8242000\\/8242720_3877908_luwano.jpg?f1793481278",
               "medium_image": "https:\\/\\/art.ngfiles.com\\/medium_views\\/8242000\\/8242720_3877908_luwano.webp?f1793481466", "width": 2648, "height": 2648, "animated": false},
              {"id": 3877901, "image": "https:\\/\\/art.ngfiles.com\\/images\\/8242000\\/8242720_3877901_luwano.jpg?f1793481273", "width": 2975, "height": 2977, "animated": true}
            ]; gallery(imageData); })();</script>
            </body></html>
            """;

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
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claims")
    class Claims {
        private final NewgroundsPlatform platform = new NewgroundsPlatform();

        @Test
        @DisplayName("Foreign hosts and lookalikes are disclaimed with null")
        void testForeignHosts() throws PlatformException {
            assertNull(this.platform.resolve(URI.create("https://example.com/portal/view/297383")));
            assertNull(this.platform.resolve(URI.create("https://notnewgrounds.com/portal/view/297383")));
            assertNull(this.platform.resolve(URI.create("https://uploads.ungrounded.net/alternate/65000/65289_alternate_865.360p.mp4")));
        }

        @Test
        @DisplayName("Unsupported shapes of the site fail without network and are not mature blocks")
        void testUnsupportedShapes() {
            for (final String url: List.of("https://www.newgrounds.com/", "https://newgrounds.com/games/view/1", "https://burn7.newgrounds.com/audio",
                    "https://www.newgrounds.com/art/view/luwano", "https://www.newgrounds.com/portal/view/abc")) {
                final PlatformException thrown = assertThrows(PlatformException.class, () -> this.platform.resolve(URI.create(url)), url);
                assertFalse(thrown instanceof MatureContentException, url);
                assertTrue(thrown.getMessage().startsWith(NewgroundsPlatform.NAME + ": Unsupported URL"), thrown.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Movies")
    class Movies {

        @Test
        @DisplayName("One MP4 per tier with the tier height, and the page's decoded metadata")
        void testMovieParsing() throws PlatformException {
            final Source source = NewgroundsPlatform.parseMovie(MOVIE_HTML, MOVIE_JSON, "297383");
            assertEquals(MediaType.VIDEO, source.type());
            assertEquals(3, source.qualities().size());
            assertEquals(1080, source.qualities().get(0).height());
            assertEquals(720, source.qualities().get(1).height());
            assertEquals("https://uploads.ungrounded.net/alternate/65000/65289_alternate_865.720p.mp4?1360102221", source.qualities().get(1).uri().toString(), "The Flash file of the tier is skipped");
            assertEquals(360, source.qualities().get(2).height());
            assertEquals("Metal Gear & Co's Awesome", source.metadata().title());
            assertEquals("Metal Gear is awesome <3", source.metadata().description());
            assertEquals("Egoraptor", source.metadata().author());
            assertEquals(Instant.parse("2006-02-23T07:54:52Z"), source.metadata().postedAt());
            assertEquals("https://picon.ngfiles.com/297000/flash_297383_card.png?f1601084239", source.thumbnail().toString());
            assertTrue(source.headers().has("User-Agent"), "FFmpeg gets the default headers");
        }

        @Test
        @DisplayName("Behind the adult login wall the JSON's own title and author serve")
        void testWalledMovieUsesJsonMetadata() throws PlatformException {
            WaterMediaConfig.platforms.allowMatureContent = true;
            final Source source = NewgroundsPlatform.parseMovie("", MOVIE_JSON, "297383");
            assertEquals("Metal Gear Awesome", source.metadata().title());
            assertEquals("Egoraptor", source.metadata().author());
            assertNull(source.thumbnail());
        }

        @Test
        @DisplayName("The site's error message and a Flash-only movie fail with a PlatformException")
        void testMovieFailures() {
            final PlatformException missing = assertThrows(PlatformException.class,
                    () -> NewgroundsPlatform.parseMovie(MOVIE_HTML, "{\"error\": {\"code\": 404, \"msg\": \"The submission you are looking for could not be found.\"}}", "1"));
            assertTrue(missing.getMessage().contains("could not be found"), missing.getMessage());
            final PlatformException flash = assertThrows(PlatformException.class,
                    () -> NewgroundsPlatform.parseMovie(MOVIE_HTML, "{\"id\": 1, \"title\": \"x\", \"sources\": {}}", "1"));
            assertTrue(flash.getMessage().contains("Flash"), flash.getMessage());
        }

        @Test
        @DisplayName("Mature and Adult ratings block while the gate is off and pass once it is on")
        void testMatureGate() throws PlatformException {
            final String mature = MOVIE_HTML.replace("rated-t", "rated-m");
            final String adult = MOVIE_HTML.replace("rated-t", "rated-a");
            assertThrows(MatureContentException.class, () -> NewgroundsPlatform.parseMovie(mature, MOVIE_JSON, "297383"));
            assertThrows(MatureContentException.class, () -> NewgroundsPlatform.parseMovie(adult, MOVIE_JSON, "297383"));
            assertEquals(MediaType.VIDEO, NewgroundsPlatform.parseMovie(MOVIE_HTML, MOVIE_JSON, "297383").type(), "Teen passes with the gate off");
            WaterMediaConfig.platforms.allowMatureContent = true;
            assertEquals(MediaType.VIDEO, NewgroundsPlatform.parseMovie(adult, MOVIE_JSON, "297383").type());
        }
    }

    @Nested
    @DisplayName("Audio")
    class Audio {

        @Test
        @DisplayName("The player's file, duration and the page's artist")
        void testAudioParsing() throws PlatformException {
            final Source source = NewgroundsPlatform.parseAudio(AUDIO_HTML, "549479");
            assertEquals(MediaType.AUDIO, source.type());
            assertEquals(1, source.qualities().size());
            assertEquals("https://audio.ngfiles.com/549000/549479_B7---BusMode.mp3?f1378892946", source.qualities().get(0).uri().toString());
            assertEquals(143_000L, source.metadata().durationMs());
            assertEquals("B7 - BusMode", source.metadata().title());
            assertEquals("Burn7", source.metadata().author());
            assertEquals(Instant.parse("2013-09-11T09:49:05Z"), source.metadata().postedAt());
            assertEquals("https://aicon.ngfiles.com/549/549479.png?f1378892949", source.thumbnail().toString());
        }

        @Test
        @DisplayName("The former embedController boot still yields the file")
        void testLegacyPlayer() throws PlatformException {
            final String legacy = AUDIO_HTML.replace("NgAudioPlayer.fromListenPage({ 'generic_id': 549479, 'type_id': 3, 'url':", "embedController([{\"url\":");
            assertEquals("https://audio.ngfiles.com/549000/549479_B7---BusMode.mp3?f1378892946", NewgroundsPlatform.parseAudio(legacy, "549479").qualities().get(0).uri().toString());
        }

        @Test
        @DisplayName("A page without the player fails with a PlatformException")
        void testNoPlayer() {
            final PlatformException thrown = assertThrows(PlatformException.class, () -> NewgroundsPlatform.parseAudio("<html><body>nothing</body></html>", "1"));
            assertFalse(thrown instanceof MatureContentException);
            assertTrue(thrown.getMessage().contains("no audio file"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Art")
    class Art {

        @Test
        @DisplayName("One image source per gallery piece with its size and preview")
        void testArtParsing() throws PlatformException {
            final List<Source> sources = NewgroundsPlatform.parseArt(ART_HTML, "Art luwano/geistober-2026");
            assertEquals(2, sources.size());
            assertEquals(MediaType.IMAGE, sources.get(0).type());
            assertEquals("https://art.ngfiles.com/images/8242000/8242720_3877908_luwano.jpg?f1793481278", sources.get(0).qualities().get(0).uri().toString());
            assertEquals(2648, sources.get(0).qualities().get(0).width());
            assertEquals("https://art.ngfiles.com/medium_views/8242000/8242720_3877908_luwano.webp?f1793481466", sources.get(0).thumbnail().toString());
            assertEquals(2977, sources.get(1).qualities().get(0).height());
            assertNull(sources.get(1).thumbnail(), "A piece without previews has no thumbnail");
            assertEquals("Luwano", sources.get(1).metadata().author());
        }

        @Test
        @DisplayName("A page without the gallery script fails with a PlatformException")
        void testNoImages() {
            final PlatformException thrown = assertThrows(PlatformException.class, () -> NewgroundsPlatform.parseArt("<html><body></body></html>", "Art x/y"));
            assertTrue(thrown.getMessage().contains("no images"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("NG Guard solver")
    class Guard {

        @Test
        @DisplayName("BLAKE2b matches the RFC 7693 vectors")
        void testBlake2b() {
            assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
                    HexFormat.of().formatHex(NewgroundsPlatform.blake2b(64, "abc".getBytes(StandardCharsets.US_ASCII))));
            assertEquals("786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
                    HexFormat.of().formatHex(NewgroundsPlatform.blake2b(64, new byte[0])));
        }

        @Test
        @DisplayName("Argon2id matches the reference implementation's vectors, one and two lanes")
        void testArgon2id() throws PlatformException {
            final byte[] password = "password".getBytes(StandardCharsets.US_ASCII);
            final byte[] salt = "somesalt".getBytes(StandardCharsets.US_ASCII);
            assertEquals("9dfeb910e80bad0311fee20f9c0e2b12c17987b4cac90c2ef54d5b3021c68bfe",
                    HexFormat.of().formatHex(new NewgroundsPlatform.Argon2id(256, 2, 1, 32, salt).hash(password, password.length)));
            assertEquals("6d093c501fd5999645e0ea3bf620d7b8be7fd2db59c20d9fff9539da2bf57037",
                    HexFormat.of().formatHex(new NewgroundsPlatform.Argon2id(256, 2, 2, 32, salt).hash(password, password.length)));
        }

        @Test
        @DisplayName("A puzzle the live guard accepted on 2026-11-01 solves to the same nonce")
        void testCapturedPuzzle() throws PlatformException {
            final byte[] payload = "1793538360467|eb1a1096".getBytes(StandardCharsets.US_ASCII);
            assertEquals(355L, NewgroundsPlatform.solve(payload, 8, new NewgroundsPlatform.Argon2id(4_096, 1, 1, 32, new byte[8])));
        }

        @Test
        @DisplayName("The SHA-256 variant finds a digest with the asked leading zero bits")
        void testSha256Puzzle() throws Exception {
            final byte[] payload = "1793538360467|eb1a1096".getBytes(StandardCharsets.US_ASCII);
            final long nonce = NewgroundsPlatform.solve(payload, 12, null);
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(("1793538360467|eb1a1096:" + nonce).getBytes(StandardCharsets.US_ASCII));
            assertEquals(0, digest[0]);
            assertEquals(0, digest[1] & 0xF0, "12 leading zero bits");
            for (long earlier = 0; earlier < nonce; earlier++) {
                final byte[] other = MessageDigest.getInstance("SHA-256").digest(("1793538360467|eb1a1096:" + earlier).getBytes(StandardCharsets.US_ASCII));
                assertFalse(other[0] == 0 && (other[1] & 0xF0) == 0, "The first fitting nonce is reported");
            }
        }

        @Test
        @DisplayName("Puzzle parameters beyond the budget are refused before allocating")
        void testUnreasonablePuzzle() {
            assertThrows(PlatformException.class, () -> new NewgroundsPlatform.Argon2id(1_048_576, 1, 1, 32, new byte[8]));
            assertThrows(PlatformException.class, () -> new NewgroundsPlatform.Argon2id(4_096, 0, 1, 32, new byte[8]));
            assertThrows(PlatformException.class, () -> new NewgroundsPlatform.Argon2id(4_096, 1, 64, 32, new byte[8]));
        }
    }
}
