package org.watermedia.api.codecs.encoders;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.PointerPointer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.player.HeadlessGFXEngine;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.SampleType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFEncoder} through {@link CodecsAPI#encoder}: track and argument checks, then audio and video
 * encodes of every FFmpeg container probed back with libavformat, odd sizes, resampling, quality and playback
 * through {@code PlayerAPI}. Native cases skip when the FFmpeg binaries are absent.
 */
@DisplayName("FFEncoder")
public class FFEncoderTest {
    private static final Path DIRECTORY = Path.of("build", "test-ff-encoder");
    private static final int WIDTH = 128, HEIGHT = 96, FPS = 30, RATE_HZ = 48_000, SECONDS = 1;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // FEEDS SECONDS OF A MOVING GRADIENT AND A 440 HZ TONE, INTERLEAVING AUDIO AFTER EVERY FRAME LIKE A CAPTURE WOULD
    private static void encode(final Encoder encoder, final Encoder.Video video, final Encoder.Audio audio) throws IOException {
        try (encoder) {
            final ByteBuffer frame = video == null ? null : ByteBuffer.allocateDirect((int) video.frameBytes());
            final int frames = SECONDS * FPS;
            final int rate = audio == null ? 0 : audio.sampleRate();
            long sent = 0;
            for (int f = 0; f < frames; f++) {
                if (video != null) {
                    frame.clear();
                    for (int i = 0; frame.hasRemaining(); i++) frame.put((byte) (i * 7 + f * 11));
                    frame.flip();
                    encoder.video(frame, f * 1_000_000L / FPS);
                }
                if (audio != null) {
                    final long until = (long) rate * (f + 1) / FPS;
                    encoder.audio(tone(audio, sent, (int) (until - sent)));
                    sent = until;
                }
            }
        }
    }

    private static ByteBuffer tone(final Encoder.Audio audio, final long start, final int samples) {
        final ByteBuffer pcm = ByteBuffer.allocate(samples * audio.frameBytes()).order(ByteOrder.LITTLE_ENDIAN);
        for (int s = 0; s < samples; s++) {
            final double value = 0.25 * Math.sin(2 * Math.PI * 440 * (start + s) / audio.sampleRate());
            for (int c = 0; c < audio.channels(); c++) {
                if (audio.type() == SampleType.FLT) pcm.putFloat((float) value);
                else pcm.putShort((short) (value * Short.MAX_VALUE));
            }
        }
        return pcm.flip();
    }

    // READS THE STREAMS, THEIR PACKETS AND WHERE EACH ONE ENDS (LAST PTS PLUS DURATION) BACK WITH LIBAVFORMAT
    private static Probe probe(final Path file) throws IOException {
        final AVFormatContext context = new AVFormatContext(null);
        final int opened = avformat.avformat_open_input(context, file.toAbsolutePath().toString(), null, null);
        if (opened < 0) throw new IOException("avformat_open_input failed: " + opened);
        final AVPacket packet = avcodec.av_packet_alloc();
        try {
            if (avformat.avformat_find_stream_info(context, (PointerPointer<?>) null) < 0) throw new IOException("avformat_find_stream_info failed");
            final int streams = context.nb_streams();
            final int[] packets = new int[streams];
            final long[] ends = new long[streams];
            while (avformat.av_read_frame(context, packet) >= 0) {
                final int index = packet.stream_index();
                final AVRational base = context.streams(index).time_base();
                packets[index]++;
                ends[index] = Math.max(ends[index], (packet.pts() + packet.duration()) * 1_000_000L * base.num() / base.den());
                avcodec.av_packet_unref(packet);
            }
            final List<Track> tracks = new ArrayList<>();
            for (int i = 0; i < streams; i++) {
                final AVCodecParameters parameters = context.streams(i).codecpar();
                tracks.add(new Track(parameters.codec_type(), parameters.width(), parameters.height(),
                        parameters.sample_rate(), parameters.ch_layout().nb_channels(), packets[i], ends[i]));
            }
            return new Probe(context.duration(), tracks);
        } finally {
            avcodec.av_packet_free(packet);
            avformat.avformat_close_input(context);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("Tracks validate their own ranges and refuse block-compressed frames")
        void tracks() {
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BC7, 30));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 0, 16, PixelFormat.BGRA, 30));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, 0));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, 30, 101, 0));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, 1_000, 2, SampleType.S16));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, RATE_HZ, 9, SampleType.S16));
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, RATE_HZ, 2, SampleType.S16, -1));
        }

        @Test
        @DisplayName("A codec the container cannot carry, a decode-only container and an empty encode are refused before the file exists")
        void containers() throws IOException {
            Files.createDirectories(DIRECTORY);
            final Path file = DIRECTORY.resolve("refused.mp4");
            Files.deleteIfExists(file);
            final Encoder.Video h264 = new Encoder.Video(VideoCodec.H264, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.WEBM, file, h264, null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.OGG, file, h264, null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.WEBP, file, h264, null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MP4, file, null, null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MP4, file, h264,
                    new Encoder.Audio(AudioCodec.PCM, RATE_HZ, 2, SampleType.S16)));
            assertFalse(Files.exists(file), "a refused encode must not create its target");
        }

        @Test
        @DisplayName("Wrong frames, timestamps that do not increase, split samples and use after close are refused")
        void misuse() throws IOException {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            final Encoder encoder = CodecsAPI.encoder(Container.WEBM, new ByteArrayOutputStream(),
                    new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, FPS), new Encoder.Audio(AudioCodec.OPUS, RATE_HZ, 2, SampleType.S16));
            final ByteBuffer frame = ByteBuffer.allocateDirect(16 * 16 * 4);
            try (encoder) {
                assertThrows(IllegalArgumentException.class, () -> encoder.video(ByteBuffer.allocateDirect(16 * 16 * 4 - 1), 0));
                assertThrows(IllegalArgumentException.class, () -> encoder.video(null, 0));
                assertThrows(IllegalArgumentException.class, () -> encoder.video(frame.duplicate(), -1));
                encoder.video(frame.duplicate(), 0);
                assertThrows(IllegalArgumentException.class, () -> encoder.video(frame.duplicate(), 0));
                assertThrows(IllegalArgumentException.class, () -> encoder.audio(ByteBuffer.allocate(3)));
                encoder.audio(ByteBuffer.allocate(0));
            }
            assertThrows(IllegalStateException.class, () -> encoder.video(frame.duplicate(), 1_000_000));
            assertThrows(IllegalStateException.class, () -> encoder.audio(ByteBuffer.allocate(4)));

            try (final Encoder silent = CodecsAPI.encoder(Container.WEBM, new ByteArrayOutputStream(),
                    new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, FPS), null)) {
                assertThrows(IllegalStateException.class, () -> silent.audio(ByteBuffer.allocate(4)));
                silent.video(frame.duplicate(), 0);
            }
        }

        @Test
        @DisplayName("A codec that cannot take the track's channel count is refused with the counts it takes")
        void channels() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MKV,
                    new ByteArrayOutputStream(), null, new Encoder.Audio(AudioCodec.MP3, RATE_HZ, 6, SampleType.S16)));
            assertTrue(refused.getMessage().contains("MP3"), refused.getMessage());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Round trip")
    class RoundTrip {

        @BeforeAll
        void natives() throws IOException {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            Files.createDirectories(DIRECTORY);
        }

        @ParameterizedTest(name = "{0} {1} {2}")
        @DisplayName("Every container writes its video and audio tracks with the codecs, size and length asked for")
        @CsvSource({
                "WEBM, VP9, OPUS", "WEBM, VP8, OPUS", "WEBM, AV1, ",
                "MP4, H264, AAC", "MP4, HEVC, MP3", "MP4, AV1, OPUS", "MP4, VP9, FLAC",
                "MKV, VP9, FLAC", "MKV, H264, PCM", "MKV, VP8, MP3",
                "MOV, PRORES, PCM", "MOV, H264, AAC",
                "OGG, , OPUS", "OGG, , FLAC"
        })
        void containers(final Container container, final VideoCodec videoCodec, final AudioCodec audioCodec) throws IOException {
            final Path file = DIRECTORY.resolve(container + "-" + videoCodec + "-" + audioCodec + "." + container.name().toLowerCase());
            final Encoder.Video video = videoCodec == null ? null : new Encoder.Video(videoCodec, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            final Encoder.Audio audio = audioCodec == null ? null : new Encoder.Audio(audioCodec, RATE_HZ, 2, SampleType.S16);
            encode(CodecsAPI.encoder(container, file, video, audio), video, audio);

            final Probe probe = probe(file);
            assertEquals((video != null ? 1 : 0) + (audio != null ? 1 : 0), probe.tracks().size(), "one stream per track");
            assertTrue(Math.abs(probe.durationUs() - SECONDS * 1_000_000L) <= 150_000L, "length " + probe.durationUs() + " us");
            if (video != null) {
                final Track picture = probe.track(avutil.AVMEDIA_TYPE_VIDEO);
                assertNotNull(picture, "video stream");
                assertEquals(WIDTH, picture.width());
                assertEquals(HEIGHT, picture.height());
                // EVERY FRAME COMES BACK AND THE LAST ONE LASTS 1 / fps, SO THE TRACK ENDS AT EXACTLY ONE SECOND
                assertEquals(SECONDS * FPS, picture.packets(), "frames read back");
                assertTrue(Math.abs(picture.endUs() - SECONDS * 1_000_000L) <= 1_000L, "video ends at " + picture.endUs() + " us");
            }
            if (audio != null) {
                final Track sound = probe.track(avutil.AVMEDIA_TYPE_AUDIO);
                assertNotNull(sound, "audio stream");
                assertEquals(2, sound.channels());
                assertEquals(RATE_HZ, sound.sampleRate());
            }
        }

        @Test
        @DisplayName("An odd size grows by a pixel for 4:2:0 codecs, and Opus resamples 44.1 kHz mono to 48 kHz")
        void conversions() throws IOException {
            final Path file = DIRECTORY.resolve("odd.webm");
            final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, 97, 63, PixelFormat.RGB, FPS);
            final Encoder.Audio audio = new Encoder.Audio(AudioCodec.OPUS, 44_100, 1, SampleType.FLT);
            encode(CodecsAPI.encoder(Container.WEBM, file, video, audio), video, audio);
            final Probe probe = probe(file);
            final Track picture = probe.track(avutil.AVMEDIA_TYPE_VIDEO);
            assertEquals(98, picture.width());
            assertEquals(64, picture.height());
            final Track sound = probe.track(avutil.AVMEDIA_TYPE_AUDIO);
            assertEquals(48_000, sound.sampleRate());
            assertEquals(1, sound.channels());
        }

        @Test
        @DisplayName("A file target appears only once finished, and an encode closed without data writes nothing")
        void target() throws IOException {
            final Path file = DIRECTORY.resolve("target.mkv");
            final Path part = DIRECTORY.resolve("target.mkv.part");
            Files.deleteIfExists(file);
            final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            final Encoder encoder = CodecsAPI.encoder(Container.MKV, file, video, null);
            try (encoder) {
                encoder.video(ByteBuffer.allocateDirect((int) video.frameBytes()), 0);
                assertFalse(Files.exists(file), "the target waits for the finished file");
                assertTrue(Files.exists(part), "the encode runs next to the target");
            }
            assertTrue(Files.size(file) > 0);
            assertFalse(Files.exists(part), "the finished file replaces the target");

            final Path empty = DIRECTORY.resolve("empty.mkv");
            Files.deleteIfExists(empty);
            assertThrows(IllegalStateException.class, CodecsAPI.encoder(Container.MKV, empty, video, null)::close);
            assertFalse(Files.exists(empty));
            assertFalse(Files.exists(DIRECTORY.resolve("empty.mkv.part")));
        }

        @Test
        @DisplayName("A stream target gets the whole WebM, a sniffable Matroska file")
        void stream() throws IOException {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, WIDTH, HEIGHT, PixelFormat.YUV420P, FPS);
            encode(CodecsAPI.encoder(Container.WEBM, out, video, null), video, null);
            final byte[] webm = out.toByteArray();
            assertArrayEquals(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 }, Arrays.copyOf(webm, 4));
            assertEquals(MediaType.VIDEO, CodecsAPI.sniff(ByteBuffer.wrap(webm)));
        }

        @Test
        @DisplayName("A lower quality writes a smaller file")
        void quality() throws IOException {
            final long[] sizes = new long[2];
            for (final int quality: new int[] { 10, 90 }) {
                final Path file = DIRECTORY.resolve("quality-" + quality + ".webm");
                final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, WIDTH, HEIGHT, PixelFormat.BGRA, FPS, quality, 0);
                encode(CodecsAPI.encoder(Container.WEBM, file, video, null), video, null);
                sizes[quality == 10 ? 0 : 1] = Files.size(file);
            }
            assertTrue(sizes[0] < sizes[1], "quality 10 wrote " + sizes[0] + " bytes, quality 90 wrote " + sizes[1]);
        }

        @Test
        @DisplayName("An MP4 with H.264 and AAC plays back through PlayerAPI with its length and size")
        void plays() throws Exception {
            final Path file = DIRECTORY.resolve("playback.mp4");
            final Encoder.Video video = new Encoder.Video(VideoCodec.H264, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            final Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, RATE_HZ, 2, SampleType.S16);
            encode(CodecsAPI.encoder(Container.MP4, file, video, audio), video, audio);

            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(file));
            assertTrue(mrl.await(3_000L));
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            final MediaPlayer player = PlayerAPI.player(mrl, () -> gfx, () -> null);
            try {
                player.start();
                assertTrue(PlayerWait.awaitLoaded(player, 15_000L));
                assertTrue(PlayerWait.awaitStatus(player, 15_000L, Status.PLAYING, Status.BUFFERING, Status.PAUSED, Status.ENDED));
                assertTrue(Math.abs(player.duration() - SECONDS * 1_000L) <= 150L, "duration " + player.duration() + " ms");
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, 8_000L), "decoded frames reach the engine");
                assertEquals(WIDTH, player.sourceWidth());
                assertEquals(HEIGHT, player.sourceHeight());
            } finally {
                player.stop();
                player.release();
            }
        }
    }

    private record Track(int type, int width, int height, int sampleRate, int channels, int packets, long endUs) {}

    private record Probe(long durationUs, List<Track> tracks) {
        Track track(final int type) {
            for (final Track track: this.tracks) if (track.type() == type) return track;
            return null;
        }
    }
}
