package org.watermedia.api.codecs.encoders;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
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
import org.watermedia.test.support.MediaProbe;
import org.watermedia.test.support.PlayerWait;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFEncoder} through {@link CodecsAPI#encoder}: track and argument checks, then audio and video
 * encodes of every FFmpeg container probed back with libavformat (codecs, size, frames and length), odd sizes,
 * resampling, file and stream targets, quality and playback through {@code PlayerAPI}. Native cases skip when the
 * FFmpeg binaries are absent.
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

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Arguments")
    class Arguments {

        @Test
        @DisplayName("Tracks validate their own ranges and refuse block-compressed frames")
        void tracks() {
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BC7, 30), "a block-compressed format is refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 0, 16, PixelFormat.BGRA, 30), "a zero width is refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, 0), "a zero frame rate is refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, 30, 101, 0), "a quality above 100 is refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, 1_000, 2, SampleType.S16), "a sample rate below 4 kHz is refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, RATE_HZ, 9, SampleType.S16), "nine channels are refused");
            assertThrows(IllegalArgumentException.class, () -> new Encoder.Audio(AudioCodec.OPUS, RATE_HZ, 2, SampleType.S16, -1), "a negative bitrate is refused");
        }

        @Test
        @DisplayName("A codec the container cannot carry, a decode-only container and an empty encode are refused before the file exists")
        void containers() throws IOException {
            Files.createDirectories(DIRECTORY);
            final Path file = DIRECTORY.resolve("refused.mp4");
            Files.deleteIfExists(file);
            final Encoder.Video h264 = new Encoder.Video(VideoCodec.H264, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.WEBM, file, h264, null), "WebM cannot carry H.264");
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.OGG, file, h264, null), "Ogg carries no video");
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.WEBP, file, h264, null), "WebP is only decoded");
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MP4, file, null, null), "an encode needs a track");
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MP4, file, h264,
                    new Encoder.Audio(AudioCodec.PCM, RATE_HZ, 2, SampleType.S16)), "MP4 cannot carry PCM");
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
                assertThrows(IllegalArgumentException.class, () -> encoder.video(ByteBuffer.allocateDirect(16 * 16 * 4 - 1), 0), "a frame one byte short is refused");
                assertThrows(IllegalArgumentException.class, () -> encoder.video(null, 0), "a null frame is refused");
                assertThrows(IllegalArgumentException.class, () -> encoder.video(frame.duplicate(), -1), "a negative timestamp is refused");
                encoder.video(frame.duplicate(), 0);
                assertThrows(IllegalArgumentException.class, () -> encoder.video(frame.duplicate(), 0), "a repeated timestamp is refused");
                assertThrows(IllegalArgumentException.class, () -> encoder.audio(ByteBuffer.allocate(3)), "3 bytes split a 4-byte sample frame");
                encoder.audio(ByteBuffer.allocate(0)); // NO SAMPLES ARE NO MISUSE
            }
            assertThrows(IllegalStateException.class, () -> encoder.video(frame.duplicate(), 1_000_000), "a closed encoder refuses frames");
            assertThrows(IllegalStateException.class, () -> encoder.audio(ByteBuffer.allocate(4)), "a closed encoder refuses samples");

            try (final Encoder silent = CodecsAPI.encoder(Container.WEBM, new ByteArrayOutputStream(),
                    new Encoder.Video(VideoCodec.VP9, 16, 16, PixelFormat.BGRA, FPS), null)) {
                assertThrows(IllegalStateException.class, () -> silent.audio(ByteBuffer.allocate(4)), "an encode without audio refuses samples");
                silent.video(frame.duplicate(), 0);
            }
        }

        @Test
        @DisplayName("A codec that cannot take the track's channel count is refused with the counts it takes")
        void channels() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> CodecsAPI.encoder(Container.MKV,
                    new ByteArrayOutputStream(), null, new Encoder.Audio(AudioCodec.MP3, RATE_HZ, 6, SampleType.S16)), "MP3 refuses six channels");
            assertEquals("(wm_err:4674) Failed to create the audio encoder due to unsupported channels, MP3 encodes 1, 2 channels, not 6", refused.getMessage());
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
                "OGG, , OPUS", "OGG, , FLAC", "MP3, , MP3", "M4A, , AAC"
        })
        void containers(final Container container, final VideoCodec videoCodec, final AudioCodec audioCodec) throws IOException {
            final Path file = DIRECTORY.resolve(container + "-" + videoCodec + "-" + audioCodec + "." + container.name().toLowerCase());
            final Encoder.Video video = videoCodec == null ? null : new Encoder.Video(videoCodec, WIDTH, HEIGHT, PixelFormat.BGRA, FPS);
            final Encoder.Audio audio = audioCodec == null ? null : new Encoder.Audio(audioCodec, RATE_HZ, 2, SampleType.S16);
            encode(CodecsAPI.encoder(container, file, video, audio), video, audio);

            final MediaProbe probe = MediaProbe.read(file);
            assertEquals((video != null ? 1 : 0) + (audio != null ? 1 : 0), probe.tracks().size(), "one stream per track");
            assertTrue(Math.abs(probe.durationUs() - SECONDS * 1_000_000L) <= 150_000L, "length " + probe.durationUs() + " us");
            if (video != null) {
                final MediaProbe.Track picture = probe.track(avutil.AVMEDIA_TYPE_VIDEO);
                assertNotNull(picture, "video stream");
                assertEquals(switch (videoCodec) {
                    case H264 -> avcodec.AV_CODEC_ID_H264;
                    case HEVC -> avcodec.AV_CODEC_ID_HEVC;
                    case AV1 -> avcodec.AV_CODEC_ID_AV1;
                    case VP8 -> avcodec.AV_CODEC_ID_VP8;
                    case VP9 -> avcodec.AV_CODEC_ID_VP9;
                    case PRORES -> avcodec.AV_CODEC_ID_PRORES;
                    case PNG, GIF -> avcodec.AV_CODEC_ID_NONE;
                }, picture.codec(), "the video stream is " + videoCodec);
                assertEquals(WIDTH, picture.width(), "video width");
                assertEquals(HEIGHT, picture.height(), "video height");
                // EVERY FRAME COMES BACK AND THE LAST ONE LASTS 1 / fps, SO THE TRACK ENDS AT EXACTLY ONE SECOND
                assertEquals(SECONDS * FPS, picture.packets(), "frames read back");
                assertTrue(Math.abs(picture.endUs() - SECONDS * 1_000_000L) <= 1_000L, "video ends at " + picture.endUs() + " us");
            }
            if (audio != null) {
                final MediaProbe.Track sound = probe.track(avutil.AVMEDIA_TYPE_AUDIO);
                assertNotNull(sound, "audio stream");
                assertEquals(switch (audioCodec) {
                    case PCM -> avcodec.AV_CODEC_ID_PCM_S16LE;
                    case OPUS -> avcodec.AV_CODEC_ID_OPUS;
                    case AAC -> avcodec.AV_CODEC_ID_AAC;
                    case MP3 -> avcodec.AV_CODEC_ID_MP3;
                    case FLAC -> avcodec.AV_CODEC_ID_FLAC;
                }, sound.codec(), "the audio stream is " + audioCodec);
                assertEquals(2, sound.channels(), "audio channels");
                assertEquals(RATE_HZ, sound.sampleRate(), "audio sample rate");
                // EVERY CONTAINER KEEPS THE PRIMING BEFORE ZERO, SO EVERY SAMPLE SENT ENDS THE TRACK AT ONE SECOND (LESS THE
                // MILLISECOND MATROSKA ROUNDS AWAY) AND ONLY THE PADDING OF A LAST FULL FRAME MAY RUN PAST IT. A BARE MP3 STREAM
                // HAS NO TIME BEFORE ZERO, SO ITS PRIMING (UNDER ONE FRAME) COUNTS TOO
                final long past = sound.frameUs() * (container == Container.MP3 ? 2 : 1);
                assertTrue(sound.endUs() >= SECONDS * 1_000_000L - 1_000L && sound.endUs() <= SECONDS * 1_000_000L + past,
                        "audio ends at " + sound.endUs() + " us, its frames last up to " + sound.frameUs() + " us");
            }
        }

        @Test
        @DisplayName("An odd size grows by a pixel for 4:2:0 codecs, and Opus resamples 44.1 kHz mono to 48 kHz")
        void conversions() throws IOException {
            final Path file = DIRECTORY.resolve("odd.webm");
            final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, 97, 63, PixelFormat.RGB, FPS);
            final Encoder.Audio audio = new Encoder.Audio(AudioCodec.OPUS, 44_100, 1, SampleType.FLT);
            encode(CodecsAPI.encoder(Container.WEBM, file, video, audio), video, audio);
            final MediaProbe probe = MediaProbe.read(file);
            final MediaProbe.Track picture = probe.track(avutil.AVMEDIA_TYPE_VIDEO);
            assertNotNull(picture, "video stream");
            assertEquals(98, picture.width(), "the odd width grows by a pixel");
            assertEquals(64, picture.height(), "the odd height grows by a pixel");
            final MediaProbe.Track sound = probe.track(avutil.AVMEDIA_TYPE_AUDIO);
            assertNotNull(sound, "audio stream");
            assertEquals(48_000, sound.sampleRate(), "Opus resamples to 48 kHz");
            assertEquals(1, sound.channels(), "the mono track stays mono");
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
            assertTrue(Files.size(file) > 0, "the finished file holds the encode");
            assertFalse(Files.exists(part), "the finished file replaces the target");

            final Path empty = DIRECTORY.resolve("empty.mkv");
            Files.deleteIfExists(empty);
            assertThrows(IllegalStateException.class, CodecsAPI.encoder(Container.MKV, empty, video, null)::close, "closing without data throws");
            assertFalse(Files.exists(empty), "an empty encode writes no target");
            assertFalse(Files.exists(DIRECTORY.resolve("empty.mkv.part")), "an empty encode leaves no temporary file");
        }

        @Test
        @DisplayName("A stream target gets the whole WebM, a sniffable Matroska file")
        void stream() throws IOException {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final Encoder.Video video = new Encoder.Video(VideoCodec.VP9, WIDTH, HEIGHT, PixelFormat.YUV420P, FPS);
            encode(CodecsAPI.encoder(Container.WEBM, out, video, null), video, null);
            final byte[] webm = out.toByteArray();
            assertArrayEquals(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 }, Arrays.copyOf(webm, 4), "the stream starts with the EBML header");
            assertEquals(MediaType.VIDEO, CodecsAPI.sniff(ByteBuffer.wrap(webm)), "the stream sniffs as video");
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
            assertTrue(mrl.await(3_000L), "the MRL of the file must load");
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            final MediaPlayer player = PlayerAPI.player(mrl, () -> gfx, () -> null);
            try {
                player.start();
                assertTrue(PlayerWait.awaitCondition(player::canPlay, 15_000L), "the player must open the file");
                assertTrue(PlayerWait.awaitStatus(player, 15_000L, Status.PLAYING, Status.BUFFERING, Status.PAUSED, Status.ENDED), "the player must start playing");
                assertTrue(Math.abs(player.duration() - SECONDS * 1_000L) <= 150L, "duration " + player.duration() + " ms");
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, 8_000L), "decoded frames reach the engine");
                assertEquals(WIDTH, player.sourceWidth(), "source width");
                assertEquals(HEIGHT, player.sourceHeight(), "source height");
            } finally {
                player.stop();
                player.release();
            }
        }
    }
}
