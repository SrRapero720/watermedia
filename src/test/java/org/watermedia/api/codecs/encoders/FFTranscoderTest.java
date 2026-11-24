package org.watermedia.api.codecs.encoders;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.encoders.FFTranscoder.Audio;
import org.watermedia.api.codecs.encoders.FFTranscoder.Effort;
import org.watermedia.api.codecs.encoders.FFTranscoder.Info;
import org.watermedia.api.codecs.encoders.FFTranscoder.Job;
import org.watermedia.api.codecs.encoders.FFTranscoder.Video;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.MediaProbe;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFTranscoder}: job checks before anything opens, the probe of a file and of an HLS playlist whose
 * bitrate it measures, tracks copied or encoded again into every type of container read back with libavformat, a
 * separate audio input, bitrate and frame rate limits, tags, progress, cancelling and failures that leave no file.
 * Native cases skip when the FFmpeg binaries are absent.
 */
@DisplayName("FFTranscoder")
public class FFTranscoderTest {
    private static final Path DIRECTORY = Path.of("build", "test-ff-transcoder");
    private static final int WIDTH = 160, HEIGHT = 120, FPS = 30, SECONDS = 3;
    private static final Video COPY_VIDEO = new Video(null, 0, 0, Effort.FAST, false);
    private static final Audio COPY_AUDIO = new Audio(null, 0);

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Job job(final Path input, final Path track, final Container container, final Path file, final Video video, final Audio audio) {
        return new Job(input.toAbsolutePath().toUri(), track == null ? null : track.toAbsolutePath().toUri(), new HttpHeaders(), container, file, video, audio, null, null);
    }

    private static MediaProbe run(final Job job) throws IOException {
        Files.deleteIfExists(job.file());
        assertTrue(new FFTranscoder(job, null).run(), "the run must write its file");
        assertFalse(Files.exists(job.file().resolveSibling(job.file().getFileName() + ".part")), "the finished file replaces its temporary one");
        return MediaProbe.read(job.file());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("A job without its parts, without a track, into a house-made container or with a codec the container cannot carry is refused before anything opens")
        void refused() {
            final Path file = DIRECTORY.resolve("refused.mp4");
            final URI input = Fixtures.fileUri(Fixtures.MP4_H264);
            final HttpHeaders headers = new HttpHeaders();
            final Video h264 = new Video(VideoCodec.H264, 500_000, 0, Effort.FAST, false);
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(null, null), "a null job");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(null, null, headers, Container.MP4, file, COPY_VIDEO, null, null, null), null), "a null input");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.MP4, null, COPY_VIDEO, null, null, null), null), "a null file");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.MP4, file, null, null, null, null), null), "no track");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.PNG, file, COPY_VIDEO, null, null, null), null), "PNG is house-made");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.WEBM, file, h264, null, null, null), null), "WebM cannot carry H.264");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.MP3, file, COPY_VIDEO, null, null, null), null), "MP3 carries no video");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.OGG, file, null, new Audio(AudioCodec.AAC, 0), null, null), null), "Ogg cannot carry AAC");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.MP4, file,
                    new Video(VideoCodec.H264, -1, 0, Effort.FAST, false), null, null, null), null), "a negative bitrate");
            assertThrows(IllegalArgumentException.class, () -> new FFTranscoder(new Job(input, null, headers, Container.MP4, file,
                    new Video(VideoCodec.H264, 0, 0, null, false), null, null, null), null), "a null effort");
            assertFalse(Files.exists(file), "a refused job must not create its target");
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Media")
    class Media {
        private Path source;   // H.264 + AAC
        private Path picture;  // VP9, NO SOUND
        private Path sound;    // AAC, NO PICTURE

        @BeforeAll
        void clips() throws IOException {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            Files.createDirectories(DIRECTORY);
            this.source = DIRECTORY.resolve("source.mp4");
            this.picture = DIRECTORY.resolve("picture.webm");
            this.sound = DIRECTORY.resolve("sound.m4a");
            Fixtures.clip(this.source, Container.MP4, VideoCodec.H264, AudioCodec.AAC, WIDTH, HEIGHT, FPS, SECONDS);
            Fixtures.clip(this.picture, Container.WEBM, VideoCodec.VP9, null, WIDTH, HEIGHT, FPS, SECONDS);
            Fixtures.clip(this.sound, Container.M4A, null, AudioCodec.AAC, WIDTH, HEIGHT, FPS, SECONDS);
        }

        @Test
        @DisplayName("A probe reads the length, the codecs, the picture and sound layout and the bitrates of a file")
        void probe() throws IOException {
            final Info info = FFTranscoder.probe(this.source.toAbsolutePath().toUri(), new HttpHeaders());
            assertTrue(Math.abs(info.durationMs() - SECONDS * 1_000L) <= 150L, "length " + info.durationMs() + " ms");
            assertNotNull(info.video(), "video track");
            assertEquals(VideoCodec.H264, info.video().codec(), "video codec");
            assertEquals("h264", info.video().name(), "video codec name");
            assertEquals(WIDTH, info.video().width(), "width");
            assertEquals(HEIGHT, info.video().height(), "height");
            assertEquals(FPS, info.video().fps(), 0.5f, "frame rate");
            assertNotNull(info.audio(), "audio track");
            assertEquals(AudioCodec.AAC, info.audio().codec(), "audio codec");
            assertEquals(48_000, info.audio().sampleRate(), "sample rate");
            assertEquals(2, info.audio().channels(), "channels");

            // BOTH BITRATES TOGETHER MUST ACCOUNT FOR THE FILE, LESS ITS CONTAINER OVERHEAD
            final long file = Files.size(this.source) * 8_000L / info.durationMs();
            assertTrue(info.video().bitrate() > 0 && info.audio().bitrate() > 0, "both tracks state a bitrate: " + info);
            final long tracks = info.video().bitrate() + info.audio().bitrate();
            assertTrue(tracks <= file * 11 / 10 && tracks >= file / 2, "the tracks add " + tracks + " bps of the file's " + file);

            final Info silent = FFTranscoder.probe(this.picture.toAbsolutePath().toUri(), new HttpHeaders());
            assertNull(silent.audio(), "a file without sound has no audio track");
            assertEquals(VideoCodec.VP9, silent.video().codec(), "its video codec");
            assertTrue(silent.video().bitrate() > 0, "a lone track takes the file's bitrate: " + silent);
            final Info blind = FFTranscoder.probe(this.sound.toAbsolutePath().toUri(), new HttpHeaders());
            assertNull(blind.video(), "a file without picture has no video track");
        }

        @Test
        @DisplayName("An HLS playlist, which states no bitrate, gets its own measured from the segments, and a codec no container encodes reports none")
        void probeMeasures(@TempDir final Path directory) throws IOException {
            final int seconds = 2, count = 6;
            final List<Path> segments = Fixtures.transportSegments(directory, 320, 240, 25, seconds, count, 4);
            final StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + seconds + "\n#EXT-X-MEDIA-SEQUENCE:0\n");
            long bytes = 0;
            for (final Path segment: segments) {
                bytes += Files.size(segment);
                playlist.append("#EXTINF:").append(seconds).append(".0,\n").append(segment.getFileName()).append('\n');
            }
            final Path media = directory.resolve("media.m3u8");
            Files.writeString(media, playlist.append("#EXT-X-ENDLIST\n"), StandardCharsets.UTF_8);

            final Info info = FFTranscoder.probe(media.toUri(), new HttpHeaders());
            assertTrue(Math.abs(info.durationMs() - seconds * count * 1_000L) <= 500L, "length " + info.durationMs() + " ms");
            assertNotNull(info.video(), "video track");
            assertNull(info.video().codec(), "MPEG-2 is no codec of ours");
            assertEquals("mpeg2video", info.video().name(), "its name still says what it is");
            // NOISE AT A FIXED QUANTIZER WEIGHS THE SAME EVERYWHERE, SO THE WINDOWS MUST LAND NEAR THE WHOLE
            final long real = bytes * 8L / (seconds * count);
            assertTrue(Math.abs(info.video().bitrate() - real) <= real / 4, "measured " + info.video().bitrate() + " bps of " + real);
        }

        @Test
        @DisplayName("Copied tracks change container and keep their codecs, every packet and their length")
        void copies() throws IOException {
            final MediaProbe original = MediaProbe.read(this.source);
            final MediaProbe copy = run(job(this.source, null, Container.MKV, DIRECTORY.resolve("copy.mkv"), COPY_VIDEO, COPY_AUDIO));
            assertEquals(2, copy.tracks().size(), "both tracks");
            final MediaProbe.Track video = copy.track(avutil.AVMEDIA_TYPE_VIDEO);
            final MediaProbe.Track audio = copy.track(avutil.AVMEDIA_TYPE_AUDIO);
            assertEquals(avcodec.AV_CODEC_ID_H264, video.codec(), "the video stays H.264");
            assertEquals(avcodec.AV_CODEC_ID_AAC, audio.codec(), "the audio stays AAC");
            assertEquals(original.track(avutil.AVMEDIA_TYPE_VIDEO).packets(), video.packets(), "every video packet");
            assertEquals(original.track(avutil.AVMEDIA_TYPE_AUDIO).packets(), audio.packets(), "every audio packet");
            assertEquals(WIDTH, video.width(), "width");
            assertTrue(Math.abs(copy.durationUs() - original.durationUs()) <= 50_000L, "length " + copy.durationUs() + " us of " + original.durationUs());
        }

        @Test
        @DisplayName("An audio container takes the sound alone, copied or encoded again")
        void audioOnly() throws IOException {
            final MediaProbe m4a = run(job(this.source, null, Container.M4A, DIRECTORY.resolve("audio.m4a"), null, COPY_AUDIO));
            assertEquals(1, m4a.tracks().size(), "the sound alone");
            assertEquals(avcodec.AV_CODEC_ID_AAC, m4a.tracks().get(0).codec(), "M4A keeps the AAC");

            final MediaProbe mp3 = run(job(this.source, null, Container.MP3, DIRECTORY.resolve("audio.mp3"), null, new Audio(AudioCodec.MP3, 128_000)));
            assertEquals(1, mp3.tracks().size(), "the sound alone");
            assertEquals(avcodec.AV_CODEC_ID_MP3, mp3.tracks().get(0).codec(), "MP3");
            assertTrue(Math.abs(mp3.durationUs() - SECONDS * 1_000_000L) <= 150_000L, "length " + mp3.durationUs() + " us");

            final MediaProbe ogg = run(job(this.source, null, Container.OGG, DIRECTORY.resolve("audio.ogg"), null, new Audio(AudioCodec.OPUS, 96_000)));
            assertEquals(avcodec.AV_CODEC_ID_OPUS, ogg.tracks().get(0).codec(), "Opus");
            assertEquals(2, ogg.tracks().get(0).channels(), "channels");
        }

        @Test
        @DisplayName("Both tracks encode again into other codecs, with the size, the frames and the length of the source")
        void recodes() throws IOException {
            final MediaProbe webm = run(job(this.source, null, Container.WEBM, DIRECTORY.resolve("recode.webm"),
                    new Video(VideoCodec.VP9, 300_000, 0, Effort.FAST, false), new Audio(AudioCodec.OPUS, 96_000)));
            final MediaProbe.Track video = webm.track(avutil.AVMEDIA_TYPE_VIDEO);
            final MediaProbe.Track audio = webm.track(avutil.AVMEDIA_TYPE_AUDIO);
            assertEquals(avcodec.AV_CODEC_ID_VP9, video.codec(), "VP9");
            assertEquals(avcodec.AV_CODEC_ID_OPUS, audio.codec(), "Opus");
            assertEquals(WIDTH, video.width(), "width");
            assertEquals(HEIGHT, video.height(), "height");
            assertEquals(SECONDS * FPS, video.packets(), "every frame");
            assertTrue(Math.abs(webm.durationUs() - SECONDS * 1_000_000L) <= 150_000L, "length " + webm.durationUs() + " us");
            assertTrue(Math.abs(audio.endUs() - SECONDS * 1_000_000L) <= 100_000L, "the sound ends at " + audio.endUs() + " us");
        }

        @Test
        @DisplayName("One track is copied while the other is encoded again")
        void mixes() throws IOException {
            final MediaProbe mixed = run(job(this.source, null, Container.MKV, DIRECTORY.resolve("mixed.mkv"), COPY_VIDEO, new Audio(AudioCodec.FLAC, 0)));
            assertEquals(avcodec.AV_CODEC_ID_H264, mixed.track(avutil.AVMEDIA_TYPE_VIDEO).codec(), "the video stays H.264");
            assertEquals(avcodec.AV_CODEC_ID_FLAC, mixed.track(avutil.AVMEDIA_TYPE_AUDIO).codec(), "the audio turns into FLAC");
        }

        @Test
        @DisplayName("A separate audio input joins the video of the main one in a single file")
        void separateTrack() throws IOException {
            final MediaProbe joined = run(job(this.picture, this.sound, Container.MKV, DIRECTORY.resolve("joined.mkv"), COPY_VIDEO, COPY_AUDIO));
            assertEquals(2, joined.tracks().size(), "both inputs");
            assertEquals(avcodec.AV_CODEC_ID_VP9, joined.track(avutil.AVMEDIA_TYPE_VIDEO).codec(), "the main input's video");
            assertEquals(avcodec.AV_CODEC_ID_AAC, joined.track(avutil.AVMEDIA_TYPE_AUDIO).codec(), "the track's audio");
            assertEquals(SECONDS * FPS, joined.track(avutil.AVMEDIA_TYPE_VIDEO).packets(), "every frame");

            // THE SOUND OF THE MAIN INPUT IS IGNORED FOR THE TRACK'S, AND AN AUDIO JOB NEVER OPENS THE MAIN ONE
            final MediaProbe replaced = run(job(this.source, this.sound, Container.MP4, DIRECTORY.resolve("replaced.mp4"),
                    new Video(VideoCodec.H264, 300_000, 0, Effort.FAST, false), new Audio(AudioCodec.AAC, 96_000)));
            assertEquals(2, replaced.tracks().size(), "one video and one audio");
            final MediaProbe alone = run(new Job(URI.create("file:///missing/never-opened.mp4"), this.sound.toAbsolutePath().toUri(), new HttpHeaders(),
                    Container.M4A, DIRECTORY.resolve("alone.m4a"), null, COPY_AUDIO, null, null));
            assertEquals(1, alone.tracks().size(), "the track alone");
        }

        @Test
        @DisplayName("A lower bitrate writes a smaller file")
        void bitrate() throws IOException {
            final long[] sizes = new long[2];
            for (int i = 0; i < 2; i++) {
                final Path file = DIRECTORY.resolve("bitrate-" + i + ".webm");
                run(job(this.source, null, Container.WEBM, file, new Video(VideoCodec.VP9, i == 0 ? 60_000 : 600_000, 0, Effort.FAST, false), null));
                sizes[i] = Files.size(file);
            }
            assertTrue(sizes[0] < sizes[1], "60 kbps wrote " + sizes[0] + " bytes, 600 kbps wrote " + sizes[1]);
        }

        @Test
        @DisplayName("A frame rate limit drops frames down to it, and one above the source's limits nothing")
        void frameRate() throws IOException {
            final MediaProbe half = run(job(this.source, null, Container.WEBM, DIRECTORY.resolve("fps-15.webm"),
                    new Video(VideoCodec.VP9, 300_000, 15, Effort.FAST, false), null));
            final int frames = half.track(avutil.AVMEDIA_TYPE_VIDEO).packets();
            assertTrue(Math.abs(frames - SECONDS * 15) <= 1, frames + " frames at 15 fps");
            assertTrue(Math.abs(half.durationUs() - SECONDS * 1_000_000L) <= 150_000L, "the length stays: " + half.durationUs() + " us");

            final MediaProbe whole = run(job(this.source, null, Container.WEBM, DIRECTORY.resolve("fps-60.webm"),
                    new Video(VideoCodec.VP9, 300_000, 60, Effort.FAST, false), null));
            assertEquals(SECONDS * FPS, whole.track(avutil.AVMEDIA_TYPE_VIDEO).packets(), "every frame of the 30 fps source");
        }

        @ParameterizedTest(name = "{0} in {1}")
        @DisplayName("Every codec opens on the first encoder the natives have for it, never on a slower one after a refusal")
        @CsvSource({ "H264, MP4", "HEVC, MP4", "AV1, MP4", "VP9, WEBM", "VP8, WEBM" })
        void preferredEncoder(final VideoCodec codec, final Container container) throws IOException {
            String preferred = null;
            for (final String name: FFEncoder.software(codec)) if (preferred == null && avcodec.avcodec_find_encoder_by_name(name) != null) preferred = name;
            final Path file = DIRECTORY.resolve("preferred-" + codec + "." + container.name().toLowerCase(Locale.ROOT));
            Files.deleteIfExists(file);
            final FFTranscoder transcoder = new FFTranscoder(job(this.source, null, container, file, new Video(codec, 300_000, 0, Effort.FAST, false), null), null);
            assertTrue(transcoder.run(), "the run must write its file");
            assertEquals(preferred, transcoder.encoder(), "the encoder that took the bitrate target");
        }

        @Test
        @DisplayName("The rotation of a phone video survives a copy and an encode")
        void rotation() throws IOException {
            final Path turned = DIRECTORY.resolve("turned.mp4");
            Fixtures.rotated(this.source, turned, 90);
            // THE MATRIX READS BACK WITH THE SIGN OF ITS OWN CONVENTION, SO THE FIXTURE'S READING IS THE REFERENCE
            final double turn = MediaProbe.read(turned).track(avutil.AVMEDIA_TYPE_VIDEO).rotation();
            assertEquals(90.0, Math.abs(turn), 0.5, "the fixture is turned a quarter");

            final MediaProbe copied = run(job(turned, null, Container.MP4, DIRECTORY.resolve("turned-copy.mp4"), COPY_VIDEO, null));
            assertEquals(turn, copied.track(avutil.AVMEDIA_TYPE_VIDEO).rotation(), 0.5, "a copy keeps the rotation");
            final MediaProbe encoded = run(job(turned, null, Container.MP4, DIRECTORY.resolve("turned-encode.mp4"),
                    new Video(VideoCodec.H264, 300_000, 0, Effort.FAST, false), null));
            assertEquals(turn, encoded.track(avutil.AVMEDIA_TYPE_VIDEO).rotation(), 0.5, "an encode keeps the rotation");
        }

        @Test
        @DisplayName("A hole in the sound stays a hole: silence fills it when the track is encoded again")
        void audioHole() throws IOException {
            // 4 S OF SOUND WHOSE TIMESTAMPS SKIP 2 S AFTER THE SECOND ONE, SO THE TRACK ENDS AT 6 S
            final Path holed = DIRECTORY.resolve("holed.mkv");
            Fixtures.holed(holed, 48_000, 4, 2, 2, Fixtures.level(0));
            final MediaProbe copied = run(job(holed, null, Container.MKV, DIRECTORY.resolve("holed-copy.mkv"), null, COPY_AUDIO));
            assertTrue(Math.abs(copied.tracks().get(0).endUs() - 6_000_000L) <= 100_000L, "a copy keeps the timestamps: " + copied.tracks().get(0).endUs() + " us");
            final MediaProbe encoded = run(job(holed, null, Container.MKV, DIRECTORY.resolve("holed-encode.mkv"), null, new Audio(AudioCodec.FLAC, 0)));
            assertTrue(Math.abs(encoded.tracks().get(0).endUs() - 6_000_000L) <= 200_000L, "the sound after the hole stays where it was: " + encoded.tracks().get(0).endUs() + " us");
        }

        @Test
        @DisplayName("A playlist whose segments restart their clock comes out as one continuous timeline, copied or encoded")
        void clockRestarts(@TempDir final Path directory) throws IOException {
            // THREE SEGMENTS OF 2 S, EACH COUNTING FROM ZERO AS AFTER AN HLS DISCONTINUITY
            final StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (int i = 0; i < 3; i++) {
                Fixtures.segment(directory.resolve("segment" + i + ".mkv"), 48_000, 2, Fixtures.level(i));
                playlist.append(i == 0 ? "" : "#EXT-X-DISCONTINUITY\n").append("#EXTINF:2.0,\nsegment").append(i).append(".mkv\n");
            }
            final Path media = directory.resolve("media.m3u8");
            Files.writeString(media, playlist.append("#EXT-X-ENDLIST\n"), StandardCharsets.UTF_8);

            // WHAT THE DEMUXER ITSELF GIVES IS THE REFERENCE: PACKETS OF 100 MS WHOSE TIMESTAMPS GO BACK TO ZERO, SO THEY END AT 2 S
            final MediaProbe.Track raw = MediaProbe.read(media).tracks().get(0);
            assertTrue(raw.packets() > 20 && raw.endUs() <= 2_000_000L, "the fixture restarts its clock: " + raw);
            final long length = raw.packets() * 100_000L;

            final MediaProbe copied = run(job(media, null, Container.MKV, DIRECTORY.resolve("restart-copy.mkv"), null, COPY_AUDIO));
            assertEquals(raw.packets(), copied.tracks().get(0).packets(), "every packet the playlist gives");
            assertTrue(Math.abs(copied.tracks().get(0).endUs() - length) <= 100_000L, "a copy ends at " + copied.tracks().get(0).endUs() + " us of " + length);
            final MediaProbe encoded = run(job(media, null, Container.MKV, DIRECTORY.resolve("restart-encode.mkv"), null, new Audio(AudioCodec.FLAC, 0)));
            assertTrue(Math.abs(encoded.tracks().get(0).endUs() - length) <= 200_000L, "an encode ends at " + encoded.tracks().get(0).endUs() + " us of " + length);
        }

        @Test
        @DisplayName("A GPU encoder that is missing or refuses the video falls back to the software one")
        void hardwareFallsBack() throws IOException {
            final Path file = DIRECTORY.resolve("hardware.mp4");
            Files.deleteIfExists(file);
            final FFTranscoder transcoder = new FFTranscoder(job(this.source, null, Container.MP4, file,
                    new Video(VideoCodec.H264, 300_000, 0, Effort.FAST, true), null), null);
            assertTrue(transcoder.run(), "the run must write its file whatever the machine");
            assertNotNull(transcoder.encoder(), "the run names its encoder");
            assertEquals(avcodec.AV_CODEC_ID_H264, MediaProbe.read(file).track(avutil.AVMEDIA_TYPE_VIDEO).codec(), "H.264 either way");
        }

        @Test
        @DisplayName("The title travels as a tag, the progress ends at 1 and its listener is called")
        void tagsAndProgress() throws IOException {
            final Path file = DIRECTORY.resolve("tagged.mkv");
            Files.deleteIfExists(file);
            final AtomicInteger calls = new AtomicInteger();
            final FFTranscoder transcoder = new FFTranscoder(new Job(this.source.toAbsolutePath().toUri(), null, new HttpHeaders(), Container.MKV, file,
                    COPY_VIDEO, COPY_AUDIO, "A title", "An author"), calls::incrementAndGet);
            assertEquals(0f, transcoder.progress(), "nothing written yet");
            assertTrue(transcoder.run(), "the run must write its file");
            assertEquals(1f, transcoder.progress(), "the whole media");
            assertTrue(calls.get() > 0, "the listener hears the progress");
            assertEquals("A title", MediaProbe.read(file).title(), "the title tag");
            assertThrows(IllegalStateException.class, transcoder::run, "a transcoder runs once");
        }

        @Test
        @DisplayName("A cancelled run returns false and leaves neither the file nor its temporary one")
        void cancels() throws IOException {
            final Path file = DIRECTORY.resolve("cancelled.webm");
            Files.deleteIfExists(file);
            final FFTranscoder[] running = new FFTranscoder[1];
            // THE FIRST PACKET WRITTEN CANCELS THE RUN FROM ITS OWN THREAD
            final FFTranscoder transcoder = new FFTranscoder(job(this.source, null, Container.WEBM, file,
                    new Video(VideoCodec.VP9, 300_000, 0, Effort.FAST, false), new Audio(AudioCodec.OPUS, 96_000)), () -> running[0].cancel());
            running[0] = transcoder;
            assertFalse(transcoder.run(), "a cancelled run writes nothing");
            assertFalse(Files.exists(file), "no file");
            assertFalse(Files.exists(DIRECTORY.resolve("cancelled.webm.part")), "no temporary file");

            final FFTranscoder early = new FFTranscoder(job(this.source, null, Container.MKV, file, COPY_VIDEO, COPY_AUDIO), null);
            early.cancel();
            assertFalse(early.run(), "a run cancelled before it starts writes nothing");
            assertFalse(Files.exists(file), "no file");
        }

        @Test
        @DisplayName("A missing input, a missing track and a codec the container refuses fail with their cause and leave no file")
        void failures() throws IOException {
            final Path file = DIRECTORY.resolve("failed.mp4");
            Files.deleteIfExists(file);
            final IOException missing = assertThrows(IOException.class, new FFTranscoder(job(DIRECTORY.resolve("missing.mp4"), null, Container.MP4, file, COPY_VIDEO, null), null)::run);
            assertTrue(missing.getMessage().startsWith("(wm_err:5239) Failed to open the media ("), missing.getMessage());
            final IOException silent = assertThrows(IOException.class, new FFTranscoder(job(this.picture, null, Container.MP4, file, COPY_VIDEO, COPY_AUDIO), null)::run);
            assertTrue(silent.getMessage().startsWith("(wm_err:5242) Failed to transcode due to no audio track in "), silent.getMessage());
            final IOException blind = assertThrows(IOException.class, new FFTranscoder(job(this.sound, null, Container.MP4, file, COPY_VIDEO, null), null)::run);
            assertTrue(blind.getMessage().startsWith("(wm_err:5241) Failed to transcode due to no video track in "), blind.getMessage());
            // VP9 COPIED INTO QUICKTIME: THE MUXER REFUSES ITS HEADER
            assertThrows(IOException.class, new FFTranscoder(job(this.picture, null, Container.MOV, file, COPY_VIDEO, null), null)::run, "MOV refuses VP9");
            assertFalse(Files.exists(file), "no file");
            assertFalse(Files.exists(DIRECTORY.resolve("failed.mp4.part")), "no temporary file");
            assertThrows(IOException.class, () -> FFTranscoder.probe(DIRECTORY.resolve("missing.mp4").toUri(), new HttpHeaders()), "a probe of nothing");
        }
    }
}
