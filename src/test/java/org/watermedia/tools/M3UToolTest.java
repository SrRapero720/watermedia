package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.NetRequest;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.LogCapture;
import org.watermedia.tools.M3UTool.Channel;
import org.watermedia.tools.M3UTool.Iptv;
import org.watermedia.tools.M3UTool.Master;
import org.watermedia.tools.M3UTool.Media;
import org.watermedia.tools.M3UTool.Rendition;
import org.watermedia.tools.M3UTool.Segment;
import org.watermedia.tools.M3UTool.Variant;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link M3UTool}: the coded errors of a blank, header-less or shapeless body; the classification of HLS
 * master, HLS media and IPTV playlists with their fields; entries resolved against the source, with the local,
 * archive and {@code water:} entries a playlist may not name skipped and logged once; and the headers of fetch.
 */
@DisplayName("M3UTool")
public class M3UToolTest {

    // A REMOTE PLAYLIST KEEPS ONLY ENTRIES ON STREAMING SCHEMES, SO EVERY SOURCE LIVES ON A WEB ORIGIN
    private static final String BASE = "https://cdn.test/";

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Errors")
    class Errors {

        @Test
        @DisplayName("A null, empty or blank body throws the coded empty body error")
        void blankBodyRejected() {
            for (final String content: new String[] { null, "", "   \n\t  " }) {
                final IOException error = assertThrows(IOException.class, () -> M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
                assertEquals("(wm_err:0171) Failed to parse the playlist from " + BASE + "test.m3u8 due to null or blank body", error.getMessage(),
                        "body [" + content + "]");
            }
        }

        @Test
        @DisplayName("A body without the #EXTM3U header throws the coded error with a one-line excerpt")
        void missingHeaderRejected() {
            final String content = """
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;
            final IOException error = assertThrows(IOException.class, () -> M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            // THE EXCERPT IS THE FIRST 32 CHARACTERS WITH THE LINE BREAKS TURNED INTO SPACES
            assertEquals("(wm_err:0172) Failed to parse the playlist from " + BASE + "test.m3u8 due to missing #EXTM3U header, body starts with "
                    + "'#EXT-X-VERSION:3 #EXT-X-TARGETDU'", error.getMessage());
        }

        @Test
        @DisplayName("A header with no master, media or IPTV tag throws the coded unknown shape error")
        void unknownShapeRejected() {
            final String content = """
                #EXTM3U
                # Just some comments, no actual content
                """;
            final IOException error = assertThrows(IOException.class, () -> M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals("(wm_err:0173) Failed to parse the playlist from " + BASE + "test.m3u8 due to unknown shape, it has #EXTM3U but none of "
                    + "#EXT-X-STREAM-INF (master), #EXT-X-* (media) or #EXTINF (iptv)", error.getMessage());
        }
    }

    @Nested
    @DisplayName("Master playlists")
    class MasterPlaylists {

        @Test
        @DisplayName("A STREAM-INF ladder parses as a master with its source, kind, default version 1 and every variant")
        void basicMaster() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=854x480
                480p.m3u8
                """;
            final Master master = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "master.m3u8")));
            assertEquals(URI.create(BASE + "master.m3u8"), master.source());
            assertEquals("master", master.kind());
            assertEquals(1, master.version(), "no #EXT-X-VERSION tag reads as version 1");
            assertEquals(2, master.variants().size());
        }

        @Test
        @DisplayName("A master reads its #EXT-X-VERSION")
        void masterVersion() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:6
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;
            assertEquals(6, assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).version());
        }

        @Test
        @DisplayName("A variant reads its bandwidth and resolution and resolves its URI against the source")
        void variantBandwidthAndResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=9014525,RESOLUTION=1920x1080
                1080p.m3u8
                """;
            final Master master = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals(1, master.variants().size());
            final Variant variant = master.variants().get(0);
            assertEquals(9_014_525L, variant.bandwidth());
            assertEquals(1920, variant.width());
            assertEquals(1080, variant.height());
            assertEquals(URI.create(BASE + "1080p.m3u8"), variant.uri());
        }

        @Test
        @DisplayName("A variant reads its quoted codecs and its frame rate")
        void variantCodecsAndFrameRate() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",FRAME-RATE=60.000
                stream.m3u8
                """;
            final Variant variant = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).variants().get(0);
            assertEquals("avc1.64002A,mp4a.40.2", variant.codecs());
            assertEquals(60.0, variant.fps(), 0.001);
        }

        @Test
        @DisplayName("#EXT-X-MEDIA renditions keep their type, group, name, language, URI and DEFAULT and AUTOSELECT flags")
        void mediaRenditions() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="audio_en.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,URI="audio_es.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="audio"
                video.m3u8
                """;
            final Master master = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals(2, master.renditions().size());

            final Rendition en = master.renditions().get(0);
            assertEquals("AUDIO", en.type());
            assertEquals("audio", en.groupId());
            assertEquals("English", en.name());
            assertEquals("en", en.language());
            assertEquals(URI.create(BASE + "audio_en.m3u8"), en.uri());
            assertTrue(en.preferred(), "DEFAULT=YES");
            assertTrue(en.autoSelect(), "AUTOSELECT=YES");

            final Rendition es = master.renditions().get(1);
            assertEquals("Spanish", es.name());
            assertEquals("es", es.language());
            assertFalse(es.preferred(), "DEFAULT=NO");
            assertTrue(es.autoSelect(), "AUTOSELECT=YES");
        }

        @Test
        @DisplayName("A variant names its VIDEO group and takes the name of that group's rendition")
        void videoGroupName() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60 (uri)",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,VIDEO="chunked"
                chunked.m3u8
                """;
            final Variant variant = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).variants().get(0);
            assertEquals("chunked", variant.videoGroup());
            assertEquals("1080p60 (uri)", variant.name());
        }
    }

    @Nested
    @DisplayName("Media playlists")
    class MediaPlaylists {

        @Test
        @DisplayName("A segment list parses as media with its source, kind, version, target duration and segments, neither live nor VOD after ENDLIST")
        void basicMedia() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                #EXT-X-ENDLIST
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "playlist.m3u8")));
            assertEquals(URI.create(BASE + "playlist.m3u8"), media.source());
            assertEquals("media", media.kind());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(2, media.segments().size());
            assertFalse(media.live(), "#EXT-X-ENDLIST ends the playlist");
            assertFalse(media.vod(), "no #EXT-X-PLAYLIST-TYPE:VOD");
        }

        @Test
        @DisplayName("A segment reads its #EXTINF duration and title, empty when nothing follows the comma")
        void segmentDurationAndTitle() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:10
                #EXTINF:4.167,live segment
                segment1.ts
                #EXTINF:4.166,
                segment2.ts
                #EXT-X-ENDLIST
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));

            final Segment seg1 = media.segments().get(0);
            assertEquals(4.167, seg1.duration(), 0.001);
            assertEquals("live segment", seg1.title());
            assertEquals(URI.create(BASE + "segment1.ts"), seg1.uri());

            final Segment seg2 = media.segments().get(1);
            assertEquals(4.166, seg2.duration(), 0.001);
            assertEquals("", seg2.title());
        }

        @Test
        @DisplayName("#EXT-X-PROGRAM-DATE-TIME dates only the segment that follows it")
        void programDateTime() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:56:19.451Z
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                segment2.ts
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals("2025-12-16T10:56:19.451Z", media.segments().get(0).dateTime());
            assertNull(media.segments().get(1).dateTime());
        }

        @Test
        @DisplayName("#EXT-X-PLAYLIST-TYPE:VOD with ENDLIST reads as VOD and not live")
        void vodStream() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXTINF:4.0,
                segment1.ts
                #EXT-X-ENDLIST
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertTrue(media.vod());
            assertFalse(media.live());
        }

        @Test
        @DisplayName("An #EXTINF without a comma still reads its duration")
        void extinfWithoutComma() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.5
                segment.ts
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals(1, media.segments().size());
            assertEquals(4.5, media.segments().get(0).duration(), 0.001);
        }
    }

    @Nested
    @DisplayName("IPTV playlists")
    class IptvPlaylists {

        // CHANNEL TWO CARRIES NO tvg-id, SO A MISSING ATTRIBUTE READS AS NULL
        private static final String IPTV_SAMPLE = """
                #EXTM3U x-tvg-url="https://epg.example/epg.xml.gz"
                #EXTINF:-1 tvg-name="Channel One" tvg-logo="https://logo.example/one.png" tvg-id="One.us" tvg-country="US" group-title="USA",Channel One
                https://stream.example/one/playlist.m3u8
                #EXTINF:-1 tvg-name="Channel Two" tvg-logo="https://logo.example/two.png" group-title="USA",Channel Two
                https://stream.example/two/playlist.m3u8
                """;

        @Test
        @DisplayName("A flat #EXTINF list is classified as IPTV and its channels carry their tvg-* attributes")
        void channelsCarryTvgAttributes() throws IOException {
            final List<Channel> channels = assertInstanceOf(Iptv.class, M3UTool.parse(IPTV_SAMPLE, URI.create(BASE + "iptv.m3u"))).channels();
            assertEquals(2, channels.size());

            final Channel first = channels.get(0);
            assertEquals("Channel One", first.title());
            assertEquals(URI.create("https://stream.example/one/playlist.m3u8"), first.uri());
            assertEquals("https://logo.example/one.png", first.tvgLogo());
            assertEquals("One.us", first.tvgId());
            assertEquals("US", first.tvgCountry());
            assertEquals("USA", first.tvgGroup());

            final Channel second = channels.get(1);
            assertEquals("Channel Two", second.title());
            assertNull(second.tvgId());
            assertEquals("USA", second.tvgGroup());
        }

        @Test
        @DisplayName("A bare URL line inside an IPTV list becomes a channel titled by its URL")
        void bareUrlLine() throws IOException {
            final String src = """
                #EXTM3U
                #EXTINF:-1 tvg-name="X",X
                https://x.example/x
                https://bare.example/y
                """;
            final List<Channel> channels = assertInstanceOf(Iptv.class, M3UTool.parse(src, URI.create(BASE + "iptv.m3u"))).channels();
            assertEquals(2, channels.size());
            final Channel bare = channels.get(1);
            assertEquals(URI.create("https://bare.example/y"), bare.uri());
            assertEquals("https://bare.example/y", bare.title());
        }

        @Test
        @DisplayName("#KODIPROP and #EXTVLCOPT hint lines are skipped")
        void skipsKodiAndVlcOptions() throws IOException {
            final String src = """
                #EXTM3U
                #EXTINF:-1 tvg-name="X",X
                #KODIPROP:inputstream.adaptive.license_type=clearkey
                #EXTVLCOPT:http-user-agent=foo
                https://x.example/x
                """;
            final List<Channel> channels = assertInstanceOf(Iptv.class, M3UTool.parse(src, URI.create(BASE + "iptv.m3u"))).channels();
            assertEquals(1, channels.size());
            assertEquals(URI.create("https://x.example/x"), channels.get(0).uri());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Whitespace before the #EXTM3U header is tolerated")
        void whitespaceAroundHeader() throws IOException {
            final String content = """
                   #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment.ts
                """;
            assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
        }

        @Test
        @DisplayName("A STREAM-INF without a URI after it adds no variant")
        void streamInfWithoutUri() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                """;
            assertEquals(0, assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).variants().size());
        }

        @Test
        @DisplayName("Relative segment URLs resolve against the source, parent steps included")
        void relativeSegmentResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segments/segment1.ts
                #EXTINF:4.0,
                ../other/segment2.ts
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create("https://cdn.example.com/live/stream/playlist.m3u8")));
            assertEquals(URI.create("https://cdn.example.com/live/stream/segments/segment1.ts"), media.segments().get(0).uri());
            assertEquals(URI.create("https://cdn.example.com/live/other/segment2.ts"), media.segments().get(1).uri());
        }

        @Test
        @DisplayName("A variant without RESOLUTION reads as 0x0")
        void missingResolution() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000
                stream.m3u8
                """;
            final Variant variant = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).variants().get(0);
            assertEquals(0, variant.width());
            assertEquals(0, variant.height());
        }

        @Test
        @DisplayName("A missing, negative, NaN, infinite or oversized FRAME-RATE is unknown, which reads as zero")
        void unknownFrameRate() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080
                missing.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080,FRAME-RATE=-5
                negative.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080,FRAME-RATE=NaN
                nan.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080,FRAME-RATE=Infinity
                infinite.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080,FRAME-RATE=1e40
                oversized.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080,FRAME-RATE=25
                known.m3u8
                """;
            final Master master = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            // A DEFAULT ABOVE ZERO WOULD LET A TAG-LESS VARIANT OUTRANK A SIBLING THAT DECLARED ITS REAL RATE, AND A VALUE
            // PAST THE FLOAT RANGE WOULD REACH THE QUALITY RECORDS AS INFINITY
            for (int i = 0; i < 5; i++) assertEquals(0.0, master.variants().get(i).fps(), 0.0, master.variants().get(i).uri().toString());
            assertEquals(25.0, master.variants().get(5).fps(), 0.001);
        }

        @Test
        @DisplayName("Each PROGRAM-DATE-TIME tag dates the segment after it")
        void multipleProgramDateTimes() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:00:00.000Z
                #EXTINF:4.0,
                segment1.ts
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:00:04.000Z
                #EXTINF:4.0,
                segment2.ts
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8")));
            assertEquals("2025-12-16T10:00:00.000Z", media.segments().get(0).dateTime());
            assertEquals("2025-12-16T10:00:04.000Z", media.segments().get(1).dateTime());
        }

        @Test
        @DisplayName("A variant names its AUDIO group")
        void audioGroup() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Stereo"
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="stereo"
                stream.m3u8
                """;
            assertEquals("stereo", assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "test.m3u8"))).variants().get(0).audioGroup());
        }

        @Test
        @DisplayName("A real-world master with session data keeps every rendition and the ladder in declaration order")
        void realWorldMaster() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-SESSION-DATA:DATA-ID="NODE",VALUE="cloudfront.hls.live-video.net"
                #EXT-X-SESSION-DATA:DATA-ID="BROADCAST-ID",VALUE="315425928548"
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="chunked",NAME="1080p60",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=9014525,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked",FRAME-RATE=60.000
                https://example.com/chunked.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="720p60",NAME="720p60",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60",FRAME-RATE=60.000
                https://example.com/720p60.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="480p30",NAME="480p",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=1427999,RESOLUTION=852x480,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="480p30",FRAME-RATE=30.000
                https://example.com/480p30.m3u8
                #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="360p30",NAME="360p",AUTOSELECT=YES,DEFAULT=YES
                #EXT-X-STREAM-INF:BANDWIDTH=630000,RESOLUTION=640x360,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="360p30",FRAME-RATE=30.000
                https://example.com/360p30.m3u8
                """;
            final Master master = assertInstanceOf(Master.class, M3UTool.parse(content, URI.create(BASE + "master.m3u8")));
            assertEquals(4, master.renditions().size());
            assertEquals(4, master.variants().size());
            assertEquals(9_014_525L, master.variants().get(0).bandwidth());
            assertEquals(URI.create("https://example.com/chunked.m3u8"), master.variants().get(0).uri());
            assertEquals(630_000L, master.variants().get(3).bandwidth());
            assertEquals(URI.create("https://example.com/360p30.m3u8"), master.variants().get(3).uri());
        }

        @Test
        @DisplayName("A real-world live media playlist numbers its segments from MEDIA-SEQUENCE and sums their durations")
        void realWorldMedia() throws IOException {
            final String content = """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:1074
                #EXT-X-PROGRAM-DATE-TIME:2025-12-16T10:56:19.451Z
                #EXTINF:4.167,live
                https://cdn.example.com/segment1.ts
                #EXTINF:4.166,live
                https://cdn.example.com/segment2.ts
                #EXTINF:4.167,live
                https://cdn.example.com/segment3.ts
                """;
            final Media media = assertInstanceOf(Media.class, M3UTool.parse(content, URI.create(BASE + "playlist.m3u8")));
            assertEquals(URI.create(BASE + "playlist.m3u8"), media.source());
            assertEquals(3, media.version());
            assertEquals(6.0, media.targetDuration(), 0.001);
            assertEquals(1074L, media.sequence());
            assertTrue(media.live(), "no #EXT-X-ENDLIST keeps the playlist live");
            assertFalse(media.vod());
            assertEquals(12.5, media.totalDuration(), 0.001);
            assertEquals(3, media.segments().size());

            final Segment seg1 = media.segments().get(0);
            assertEquals(URI.create("https://cdn.example.com/segment1.ts"), seg1.uri());
            assertEquals(4.167, seg1.duration(), 0.001);
            assertEquals("live", seg1.title());
            assertEquals(1074L, seg1.sequence());
            assertEquals("2025-12-16T10:56:19.451Z", seg1.dateTime());
            assertEquals(1076L, media.segments().get(2).sequence());
        }
    }

    @Nested
    @DisplayName("Remote entries and fetch")
    class RemoteEntries {

        @Test
        @DisplayName("A remote playlist keeps only streaming entries, a local one keeps its files too, and neither keeps archive or water entries")
        void skipsLocalEntries() throws IOException {
            final String iptv = """
                #EXTM3U
                #EXTINF:-1,Local
                file:///etc/passwd
                #EXTINF:-1,Archive
                jar:file:///tmp/x.zip!/y
                #EXTINF:-1,Water
                water://local/x
                #EXTINF:-1,Stream
                rtsp://cam.example/live
                #EXTINF:-1,Web
                HTTPS://x.example/x
                """;
            final List<Channel> channels = assertInstanceOf(Iptv.class, M3UTool.parse(iptv, URI.create(BASE + "iptv.m3u"))).channels();
            assertEquals(List.of("Stream", "Web"), channels.stream().map(Channel::title).toList(), "remote IPTV list");

            final String media = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:4.0,
                segment1.ts
                #EXTINF:4.0,
                file:///C:/secret.ts
                """;
            final List<Segment> segments = assertInstanceOf(Media.class, M3UTool.parse(media, URI.create(BASE + "live.m3u8"))).segments();
            assertEquals(List.of(URI.create(BASE + "segment1.ts")), segments.stream().map(Segment::uri).toList(), "remote media playlist");

            // A LOCAL PLAYLIST MAY LIST LOCAL FILES, RELATIVE OR ABSOLUTE
            final List<Segment> local = assertInstanceOf(Media.class, M3UTool.parse(media, URI.create("file:///C:/videos/live.m3u8"))).segments();
            assertEquals(List.of(URI.create("file:///C:/videos/segment1.ts"), URI.create("file:///C:/secret.ts")),
                    local.stream().map(Segment::uri).toList(), "local media playlist");
            final List<Channel> localChannels = assertInstanceOf(Iptv.class, M3UTool.parse(iptv, URI.create("file:///C:/videos/iptv.m3u"))).channels();
            assertEquals(List.of("Local", "Stream", "Web"), localChannels.stream().map(Channel::title).toList(), "local IPTV list");
        }

        @Test
        @DisplayName("Each playlist logs only its first skipped entry")
        void logsFirstSkipPerPlaylist() throws IOException {
            final String iptv = "#EXTM3U\n#EXTINF:-1,Local\nfile:///etc/passwd\n#EXTINF:-1,Archive\njar:file:///tmp/x.zip!/y\n#EXTINF:-1,Web\nhttps://x.example/x\n";
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                M3UTool.parse(iptv, URI.create(BASE + "first.m3u"));
                M3UTool.parse(iptv, URI.create(BASE + "second.m3u"));
                assertEquals(2, capture.events().stream().filter(event -> event.getMessage().getFormattedMessage().startsWith("Skipping")).count(),
                        "one skip line per playlist");
            }
        }

        @Test
        @DisplayName("fetch sends the caller's User-Agent and Accept, and WaterMedia's own without headers")
        void fetchSendsHeaders() throws IOException {
            final List<String> received = new CopyOnWriteArrayList<>();
            try (final LocalHttp server = LocalHttp.start("/live.m3u8", exchange -> {
                received.add(exchange.getRequestHeaders().getFirst("User-Agent") + "|" + exchange.getRequestHeaders().getFirst("Accept"));
                LocalHttp.respond(exchange, "application/vnd.apple.mpegurl",
                        "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:4.0,\nsegment1.ts\n".getBytes(StandardCharsets.UTF_8), 0);
            })) {
                final HttpHeaders headers = new HttpHeaders().set("User-Agent", "custom-agent").set("Accept", "application/vnd.apple.mpegurl");
                assertInstanceOf(Media.class, M3UTool.fetch(server.uri("/live.m3u8"), headers));
                assertInstanceOf(Media.class, M3UTool.fetch(server.uri("/live.m3u8")));
                assertEquals(List.of("custom-agent|application/vnd.apple.mpegurl", NetRequest.UserAgent.WATERMEDIA.value() + "|*/*"), received);
            }
        }
    }
}
