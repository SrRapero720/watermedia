package org.watermedia.bootstrap.app;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.encoders.FFTranscoder;
import org.watermedia.api.player.MRL;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.MediaProbe;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link MediaDownloader}: the file names it makes out of titles, the rules that keep the picks valid for
 * the container and the source, the presets, the bitrates behind the sliders, the size it expects and a download
 * from the probes to the written file. Cases that read media skip when the FFmpeg binaries are absent.
 */
@DisplayName("Media downloader")
public class MediaDownloaderTest {
    private static final long WAIT_MS = 30_000L;

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Names")
    class Names {

        @Test
        @DisplayName("A title turns into one file name: no separators, no characters a file system refuses, no trailing dot")
        void fileNames() {
            assertEquals("a b c", MediaDownloader.fileName("a/b\\c"), "separators never make a path");
            assertEquals(".. .. etc passwd", MediaDownloader.fileName("../../etc/passwd"), "a climbing path stays one name");
            assertEquals("what is this", MediaDownloader.fileName("what: is* \"this\"?"), "refused characters turn into spaces, never doubled");
            assertEquals("tab line", MediaDownloader.fileName("tab\tline\n"), "control characters turn into spaces");
            assertEquals("ends with dots", MediaDownloader.fileName("ends with dots. . ."), "Windows drops trailing dots and spaces");
            assertEquals("Canción 日本語 🎵", MediaDownloader.fileName("Canción 日本語 🎵"), "letters of any script stay");
        }

        @Test
        @DisplayName("An empty title and a Windows device name get a name of their own")
        void reserved() {
            assertEquals("watermedia-download", MediaDownloader.fileName(""), "an empty title");
            assertEquals("watermedia-download", MediaDownloader.fileName(" ..// "), "a title of nothing but refused characters");
            assertEquals("watermedia-CON", MediaDownloader.fileName("CON"), "a device name");
            assertEquals("watermedia-nul.txt", MediaDownloader.fileName("nul.txt"), "a device name keeps being one with an extension");
            assertEquals("watermedia-COM0", MediaDownloader.fileName("COM0"), "port zero is a device too");
            assertEquals("watermedia-lpt¹", MediaDownloader.fileName("lpt¹"), "so are the superscript ports");
            assertEquals("watermedia-CONOUT$", MediaDownloader.fileName("CONOUT$"), "and the console streams");
            assertEquals("console", MediaDownloader.fileName("console"), "a word that only starts like a device");
            assertEquals("a b", MediaDownloader.fileName("a\uD800b"), "half a character pair has no place in a path");
        }

        @Test
        @DisplayName("A long title is cut without splitting a character in two")
        void limit() {
            final String name = MediaDownloader.fileName("🎵".repeat(200));
            assertTrue(name.length() <= 121, name.length() + " chars");
            assertEquals(name.codePointCount(0, name.length()) * 2, name.length(), "every pair stays whole");
        }

        @Test
        @DisplayName("Bitrates read as kbps under a megabit and as Mbps from it")
        void rates() {
            assertEquals("128 kbps", MediaDownloader.rate(128_000));
            assertEquals("1000 kbps", MediaDownloader.rate(999_600));
            assertEquals("4.20 Mbps", MediaDownloader.rate(4_200_000));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Dialog")
    class DialogFlow {
        private static final int WIDTH = 160, HEIGHT = 120, FPS = 30, SECONDS = 2;
        private Path muxed;    // H.264 + AAC
        private Path picture;  // VP9, NO SOUND
        private Path sound;    // OPUS, NO PICTURE

        @BeforeAll
        void clips(@TempDir final Path directory) throws IOException {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            this.muxed = directory.resolve("muxed.mp4");
            this.picture = directory.resolve("picture.webm");
            this.sound = directory.resolve("sound.ogg");
            Fixtures.clip(this.muxed, Container.MP4, VideoCodec.H264, AudioCodec.AAC, WIDTH, HEIGHT, FPS, SECONDS);
            Fixtures.clip(this.picture, Container.WEBM, VideoCodec.VP9, null, WIDTH, HEIGHT, FPS, SECONDS);
            Fixtures.clip(this.sound, Container.OGG, null, AudioCodec.OPUS, WIDTH, HEIGHT, FPS, SECONDS);
        }

        // A DIALOG OPENED ON A SOURCE OF THE GIVEN FILES, WITH ITS PROBES LANDED
        private MediaDownloader opened(final Loop loop, final Path folder, final Path video, final Path track) {
            final MediaDownloader downloader = new MediaDownloader(loop, folder);
            final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, new Metadata("A: title?", null, null, SECONDS * 1_000L, "An author"), new HttpHeaders(),
                    Map.of(MediaQuality.UNKNOWN, video.toUri()), track == null ? List.of() : List.of(new Track(null, null, track.toUri())), List.of());
            downloader.open(source, "A: title?", URI.create("https://www.youtube.com/watch?v=x"), null);
            assertTrue(downloader.visible, "the dialog shows");
            assertTrue(downloader.probing(), "the probes start with the dialog");
            assertFalse(downloader.ready(), "nothing starts before the probes land");
            assertTrue(loop.await(() -> !downloader.probing()), "the probes must land");
            assertNull(downloader.failure(), "the probes must read the files");
            return downloader;
        }

        @Test
        @DisplayName("A dialog opens on the source as it comes: both tracks copied into the first container that carries them")
        void opensOnTheSource(@TempDir final Path folder) {
            final MediaDownloader downloader = this.opened(new Loop(), folder, this.muxed, null);
            assertTrue(downloader.youtube, "a YouTube link");
            assertEquals(MediaDownloader.Preset.SOURCE, downloader.preset, "the source preset");
            assertEquals(Container.MP4, downloader.container, "H.264 and AAC fit MP4");
            assertNull(downloader.video, "the video is copied");
            assertNull(downloader.audio, "the audio is copied");
            assertEquals(VideoCodec.H264, downloader.picture().codec(), "the probed video");
            assertEquals(AudioCodec.AAC, downloader.sound().codec(), "the probed audio");
            assertTrue(downloader.ready(), "ready to download");

            // COPIED TRACKS WEIGH WHAT THE SOURCE WEIGHS
            assertEquals(downloader.videoMax(), downloader.videoBitrate(), "a copy keeps the source's video bitrate");
            assertEquals(downloader.audioMax(), downloader.audioBitrate(), "a copy keeps the source's audio bitrate");
            assertEquals((downloader.videoMax() + downloader.audioMax()) * downloader.durationMs() / 8_000L, downloader.estimate(), "bitrates over the length");
        }

        @Test
        @DisplayName("A separate audio track gives the sound, and the source preset falls to the container that carries both codecs")
        void separateTrack(@TempDir final Path folder) {
            final MediaDownloader downloader = this.opened(new Loop(), folder, this.picture, this.sound);
            assertEquals(VideoCodec.VP9, downloader.picture().codec(), "the video of the resolution");
            assertEquals(AudioCodec.OPUS, downloader.sound().codec(), "the audio of the track");
            assertEquals(Container.WEBM, downloader.container, "VP9 and Opus go to the container they were born in");
        }

        @Test
        @DisplayName("A container that cannot carry a pick moves it to the copy when it fits, else to its first codec")
        void containerRules(@TempDir final Path folder) {
            final MediaDownloader downloader = this.opened(new Loop(), folder, this.muxed, null);

            downloader.container = Container.WEBM;
            downloader.changed();
            assertNull(downloader.preset, "a pick leaves the preset");
            assertFalse(downloader.allowed((VideoCodec) null), "WebM cannot take H.264 as it comes");
            assertEquals(VideoCodec.VP9, downloader.video, "the most played codec it carries");
            assertEquals(AudioCodec.OPUS, downloader.audio, "its only audio codec");

            downloader.container = Container.MKV;
            downloader.video = null;
            downloader.audio = null;
            downloader.changed();
            assertNull(downloader.video, "Matroska takes any track as it comes");
            assertNull(downloader.audio, "Matroska takes any track as it comes");

            downloader.container = Container.MP3;
            downloader.changed();
            assertFalse(downloader.videoOn(), "MP3 carries no video");
            assertEquals(AudioCodec.MP3, downloader.audio, "AAC turns into MP3");
            assertTrue(downloader.ready(), "the sound alone downloads");

            downloader.container = Container.MP4;
            downloader.video = VideoCodec.VP9;
            downloader.fps = 60;
            downloader.changed();
            assertEquals(VideoCodec.VP9, downloader.video, "a codec the container carries stays");
            assertEquals(0, downloader.fps, "a limit above the source's 30 fps limits nothing");
            downloader.fps = 24;
            downloader.changed();
            assertEquals(24, downloader.fps, "a limit under it stays");
        }

        @Test
        @DisplayName("The presets pick the widest playback and the sound alone, and stay on until another pick")
        void presets(@TempDir final Path folder) {
            final MediaDownloader downloader = this.opened(new Loop(), folder, this.picture, this.sound);

            downloader.preset(MediaDownloader.Preset.COMPATIBILITY);
            assertEquals(Container.MP4, downloader.container, "MP4");
            assertEquals(VideoCodec.H264, downloader.video, "VP9 is encoded into H.264");
            assertEquals(AudioCodec.AAC, downloader.audio, "Opus is encoded into AAC");
            assertEquals(MediaDownloader.Preset.COMPATIBILITY, downloader.preset, "the preset stays on");

            downloader.preset(MediaDownloader.Preset.AUDIO);
            assertEquals(Container.OGG, downloader.container, "Ogg is the container of Opus");
            assertNull(downloader.audio, "the sound is copied");
            assertFalse(downloader.videoOn(), "no video");

            final MediaDownloader compatible = this.opened(new Loop(), folder, this.muxed, null);
            compatible.preset(MediaDownloader.Preset.COMPATIBILITY);
            assertNull(compatible.video, "H.264 is already what every device plays");
            assertNull(compatible.audio, "so is AAC");
        }

        @Test
        @DisplayName("A source without sound never stays on an audio container, and the same source opened again reads nothing twice")
        void silentSource(@TempDir final Path folder) {
            final Loop loop = new Loop();
            final MediaDownloader downloader = new MediaDownloader(loop, folder);
            final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, new HttpHeaders(), Map.of(MediaQuality.UNKNOWN, this.picture.toUri()), List.of(), List.of());
            downloader.open(source, "silent", null, null);
            // PICKED BEFORE THE PROBE LANDS, WHEN NOTHING SAYS YET THAT THE SOURCE HAS NO SOUND
            downloader.preset(MediaDownloader.Preset.AUDIO);
            assertTrue(loop.await(() -> !downloader.probing()), "the probe must land");
            assertNull(downloader.sound(), "no sound");
            assertEquals(MediaDownloader.Preset.SOURCE, downloader.preset, "the audio preset has nothing to keep");
            assertEquals(Container.WEBM, downloader.container, "the container of its video");
            assertTrue(downloader.ready(), "the video alone downloads");

            downloader.container = Container.MP3;
            downloader.changed();
            assertEquals(Container.WEBM, downloader.container, "an audio container has nothing to carry");
            assertTrue(downloader.ready(), "still ready");

            downloader.cancel();
            downloader.open(source, "silent", null, null);
            assertFalse(downloader.probing(), "the probe of the same source is kept");
            assertNotNull(downloader.info(), "and still answers");
        }

        @Test
        @DisplayName("A slider moves an encoded track between its floor and the source's bitrate, never above it")
        void bitrates(@TempDir final Path folder) {
            final MediaDownloader downloader = this.opened(new Loop(), folder, this.muxed, null);
            downloader.video = VideoCodec.HEVC;
            downloader.audio = AudioCodec.OPUS;
            downloader.changed();
            final long videoMax = downloader.videoMax();
            final long audioMax = downloader.audioMax();
            assertEquals(videoMax, downloader.videoBitrate(), "the slider starts at the source's bitrate");
            assertEquals(audioMax, downloader.audioBitrate(), "the slider starts at the source's bitrate");
            final long full = downloader.estimate();

            downloader.videoRate = 0f;
            downloader.audioRate = 0f;
            downloader.changed();
            assertTrue(downloader.videoBitrate() > 0 && downloader.videoBitrate() <= videoMax, "the floor: " + downloader.videoBitrate() + " of " + videoMax);
            assertTrue(downloader.audioBitrate() > 0 && downloader.audioBitrate() <= audioMax, "the floor: " + downloader.audioBitrate() + " of " + audioMax);
            assertTrue(downloader.estimate() <= full, "a lower bitrate never grows the file");

            downloader.videoRate = 0.5f;
            downloader.changed();
            assertTrue(downloader.videoBitrate() <= videoMax, "half way stays under the source");

            // FLAC HAS NO BITRATE TO PICK: ITS SIZE COMES FROM THE PCM IT PACKS
            downloader.container = Container.MKV;
            downloader.audio = AudioCodec.FLAC;
            downloader.changed();
            assertEquals(Math.round(48_000 * 2 * 16 * 0.6), downloader.audioBitrate(), "FLAC's usual share of 16-bit stereo at 48 kHz");
        }

        @Test
        @DisplayName("A download writes the file under a name made from the title, and a second one never writes over it")
        void downloads(@TempDir final Path folder) throws IOException {
            final Loop loop = new Loop();
            final MediaDownloader downloader = this.opened(loop, folder, this.picture, this.sound);
            downloader.container = Container.MKV;
            downloader.changed();
            downloader.start();
            assertTrue(downloader.working, "the download runs");
            assertFalse(downloader.ready(), "a running download starts no other");
            assertTrue(loop.await(() -> !downloader.working), "the download must end");
            assertNull(downloader.error, "the download must not fail");
            assertTrue(downloader.done, "done");
            assertEquals(1f, downloader.progress, "the whole media");
            assertEquals(folder.resolve("A title.mkv"), downloader.file, "the title without what a file name refuses");

            final MediaProbe written = MediaProbe.read(downloader.file);
            assertEquals(avcodec.AV_CODEC_ID_VP9, written.track(avutil.AVMEDIA_TYPE_VIDEO).codec(), "the copied video");
            assertEquals(avcodec.AV_CODEC_ID_OPUS, written.track(avutil.AVMEDIA_TYPE_AUDIO).codec(), "the copied audio of the track");
            assertEquals("A: title?", written.title(), "the title travels as a tag");

            // THE SAME PICKS AGAIN: A SECOND FILE, THE FIRST UNTOUCHED
            downloader.back();
            downloader.start();
            assertTrue(loop.await(() -> !downloader.working), "the download must end");
            assertEquals(folder.resolve("A title (1).mkv"), downloader.file, "a name no earlier download took");
            assertTrue(Files.exists(folder.resolve("A title.mkv")), "the first file stays");
        }

        @Test
        @DisplayName("A source without a title names its file after the last part of its link, and writes no title tag")
        void namedAfterTheLink(@TempDir final Path folder) throws IOException {
            final Loop loop = new Loop();
            final MediaDownloader downloader = new MediaDownloader(loop, folder);
            final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, new HttpHeaders(), Map.of(MediaQuality.UNKNOWN, this.muxed.toUri()), List.of(), List.of());
            downloader.open(source, " ", URI.create("https://example.org/videos/My%20clip.v2.mp4?x=1"), null);
            assertTrue(loop.await(() -> !downloader.probing()), "the probe must land");
            downloader.start();
            assertTrue(loop.await(() -> !downloader.working), "the download must end");
            assertEquals(folder.resolve("My clip.v2.mp4"), downloader.file, "the link's file name, less its extension, plus the container's");
            assertNull(MediaProbe.read(downloader.file).title(), "no title of the source, no tag");
        }

        @Test
        @DisplayName("The picks of the last download started come back on the next dialog, fitted to its source")
        void remembersTheLastPicks(@TempDir final Path folder) {
            final Loop loop = new Loop();
            final MediaDownloader downloader = this.opened(loop, folder, this.picture, this.sound);
            downloader.container = Container.WEBM;
            downloader.videoRate = 0.5f;
            downloader.audioRate = 0.25f;
            downloader.changed();
            downloader.effort = FFTranscoder.Effort.FAST;
            downloader.hardware = true;
            assertNull(downloader.video, "WebM takes VP9 as it comes");
            downloader.start();
            assertTrue(loop.await(() -> !downloader.working), "the download must end");
            assertTrue(downloader.done, "done");
            downloader.back();
            downloader.cancel();
            assertFalse(downloader.visible, "the dialog hides");

            // THE NEXT SOURCE IS H.264 AND AAC: THE CONTAINER AND THE OPTIONS COME BACK, AND THE COPIES WEBM REFUSES TURN INTO ITS CODECS
            final MRL.Source next = new MRL.Source(MediaType.VIDEO, null, null, new HttpHeaders(), Map.of(MediaQuality.UNKNOWN, this.muxed.toUri()), List.of(), List.of());
            downloader.open(next, "next", null, null);
            assertTrue(loop.await(() -> !downloader.probing()), "the probe must land");
            assertNull(downloader.preset, "no preset was on, so none comes back");
            assertEquals(Container.WEBM, downloader.container, "the container");
            assertEquals(FFTranscoder.Effort.FAST, downloader.effort, "the effort");
            assertTrue(downloader.hardware, "the encoder");
            assertEquals(0.5f, downloader.videoRate, "the video slider");
            assertEquals(0.25f, downloader.audioRate, "the audio slider");
            assertEquals(VideoCodec.VP9, downloader.video, "H.264 cannot be copied into WebM");
            assertEquals(AudioCodec.OPUS, downloader.audio, "AAC cannot be copied into WebM");

            // A DIALOG THAT NEVER STARTED A DOWNLOAD KEEPS OPENING ON THE SOURCE AS IT COMES
            final MediaDownloader fresh = this.opened(new Loop(), folder, this.muxed, null);
            fresh.container = Container.MKV;
            fresh.changed();
            fresh.cancel();
            fresh.open(next, "next", null, null);
            assertEquals(MediaDownloader.Preset.SOURCE, fresh.preset, "picks that started nothing are not kept");
        }

        @Test
        @DisplayName("A cancel stops a running download and leaves no file, and one without a download hides the dialog")
        void cancels(@TempDir final Path folder) throws IOException {
            final Loop loop = new Loop();
            final MediaDownloader downloader = this.opened(loop, folder, this.muxed, null);
            downloader.container = Container.WEBM;
            downloader.changed();
            downloader.start();
            downloader.cancel();
            assertTrue(downloader.visible, "a cancelled download keeps the dialog on its options");
            assertTrue(loop.await(() -> !downloader.working), "the download must end");
            assertFalse(downloader.done, "nothing was written");
            assertNull(downloader.error, "a cancel is no failure");
            try (final var files = Files.list(folder)) {
                assertEquals(0L, files.count(), "no file and no temporary one");
            }

            downloader.cancel();
            assertFalse(downloader.visible, "without a download the cancel hides the dialog");
        }

        @Test
        @DisplayName("A source that cannot be read says why and never becomes ready")
        void failedProbe(@TempDir final Path folder) {
            final Loop loop = new Loop();
            final MediaDownloader downloader = new MediaDownloader(loop, folder);
            final MRL.Source source = new MRL.Source(MediaType.VIDEO, null, null, new HttpHeaders(),
                    Map.of(MediaQuality.HIGH, folder.resolve("missing.mp4").toUri()), List.of(), List.of());
            downloader.open(source, null, null, MediaQuality.HIGHEST);
            assertEquals(MediaQuality.HIGH, downloader.quality, "the closest resolution the source has");
            assertFalse(downloader.youtube, "no link, no YouTube");
            assertTrue(loop.await(() -> !downloader.probing()), "the probe must end");
            assertNotNull(downloader.failure(), "the probe says why it failed");
            assertFalse(downloader.ready(), "nothing to download");
            downloader.start();
            assertFalse(downloader.working, "a start before ready does nothing");
        }
    }

    // THE RENDER THREAD OF A TEST: WORKERS QUEUE THEIR RESULTS AND THE TEST THREAD RUNS THEM WHILE IT WAITS
    private static final class Loop implements Executor {
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(final Runnable task) {
            this.tasks.add(task);
        }

        boolean await(final BooleanSupplier done) {
            return PlayerWait.awaitCondition(() -> {
                for (Runnable task = this.tasks.poll(); task != null; task = this.tasks.poll()) task.run();
                return done.getAsBoolean();
            }, WAIT_MS);
        }
    }
}
