package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.decoders.FFDecoder.AudioChoice;
import org.watermedia.api.codecs.decoders.FFDecoder.AudioStream;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL;
import static org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_DEFAULT;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_4POINT0;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_QUAD;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFDecoder}: the configured probe policy reaches the main input and a separate audio track through the
 * shared input options, an abort cuts every blocked open short however many decoders are alive without passing for the
 * end of the input, and the audio an {@link AudioChoice} asks for is the stream that decodes, reported with every other
 * audio stream of the opened inputs while the streams nobody decodes are discarded.
 */
@DisplayName("FFDecoder")
public class FFDecoderTest {
    private static final long OPEN_TIMEOUT_MS = 15_000L;
    private static final long ABORT_MS = 5_000L; // HALF THE 10 s I/O TIMEOUT AN OPEN THE ABORT MISSED WAITS FOR
    private static final int RATE_HZ = 8_000;
    private static final int SECONDS = 3;
    // SAMPLE VALUES OF THE SEPARATE TRACKS, APART FROM EVERY Fixtures.level OF THE MAIN INPUT
    private static final short GERMAN = 7_000;
    private static final short FRENCH = 8_000;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeEach
    void natives() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static FFDecoder decoder(final URI uri, final Recorder host) {
        return new FFDecoder("FFThread-Test", uri, new HttpHeaders(), true, null, new Linear(true), host);
    }

    private static void assertProbePolicy(final AVFormatContext context, final String what) {
        assertEquals(19_000_000, context.max_analyze_duration(), what + " analyzes for the configured duration");
        assertEquals(23L * 1_024 * 1_024, context.probesize(), what + " probes the configured size");
    }

    // WHETHER THE NAMED THREAD IS INSIDE FFMPEG'S PACKET READ
    private static boolean reading(final String name) {
        for (final Map.Entry<Thread, StackTraceElement[]> entry: Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().equals(name)) continue;
            for (final StackTraceElement frame: entry.getValue())
                if (frame.getMethodName().equals("av_read_frame")) return true;
        }
        return false;
    }

    // OPENS AN AUDIO-ONLY DECODER OF choice ON A MONO S16 ENGINE, WHICH TAKES THE FIXTURES' PCM AS IT IS, AND RETURNS IT ONCE
    // THE FIRST FRAME DECODED; THE CALLER CLOSES IT
    private static FFDecoder decode(final URI uri, final AudioChoice choice) throws Exception {
        final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 1));
        final FFDecoder decoder = new FFDecoder("FFThread-Audio", uri, new HttpHeaders(), false, choice, new Linear(false), host);
        decoder.start();
        assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
        assertNull(host.failure, "the input must open without a failure");
        assertTrue(PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null, OPEN_TIMEOUT_MS), "the audio must decode");
        return decoder;
    }

    // THE FIRST DECODED SAMPLE, WHICH NAMES THE STREAM IT CAME FROM
    private static short sample(final FFDecoder decoder) {
        return decoder.audioFrames().peek().frame.data(0).limit(2).asBuffer().order(ByteOrder.nativeOrder()).getShort(0);
    }

    // THREE TAGGED STREAMS, THE SPANISH ONE FLAGGED DEFAULT; THE FRENCH ONE CARRIES THE BIBLIOGRAPHIC CODE
    private static URI dubbed(final Path directory) throws Exception {
        final Path file = directory.resolve("dubbed.mka");
        Fixtures.dubbed(file, RATE_HZ,SECONDS, 1, "eng", "spa", "fre");
        return file.toUri();
    }

    // THE SEPARATE TRACKS OF A SOURCE: GERMAN FIRST (THE PLATFORM'S ORIGINAL), THEN FRENCH; WAV CARRIES NO TAGS OF ITS OWN
    private static List<Track> tracks(final Path directory) throws Exception {
        final Path german = directory.resolve("german.wav");
        final Path french = directory.resolve("french.wav");
        Fixtures.wav(german, RATE_HZ,1, Fixtures.tone(GERMAN, RATE_HZ *SECONDS));
        Fixtures.wav(french, RATE_HZ,1, Fixtures.tone(FRENCH, RATE_HZ *SECONDS));
        return List.of(new Track("Deutsch", "de", german.toUri()), new Track("Français", "fr-CA", french.toUri()));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Input options")
    class InputOptions {
        private long analysis;
        private int probe;

        @BeforeEach
        void configure() {
            this.analysis = WaterMediaConfig.codecs.ffmpeg.analyzeDuration;
            this.probe = WaterMediaConfig.codecs.ffmpeg.probeSize;
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = 19_000;
            WaterMediaConfig.codecs.ffmpeg.probeSize = 23;
        }

        @AfterEach
        void restore() {
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = this.analysis;
            WaterMediaConfig.codecs.ffmpeg.probeSize = this.probe;
        }

        @Test
        @DisplayName("The main input opens with the configured probe policy and the shared input options carry it")
        void mainInputAppliesTheConfiguredProbePolicy() throws Exception {
            final Recorder host = new Recorder(null);
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), host);
            final AVDictionary options = new AVDictionary();
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the main input must open");
                assertNull(host.failure, "the main input must open without a failure");
                // THE DEMUXER PARKS ON THE HELD TIMELINE RIGHT AFTER THE OPEN, SO ITS CONTEXT IS ONLY READ HERE
                assertProbePolicy(decoder.formatContext, "the main input");

                // BUILDING THE SHARED OPTIONS ONLY READS THE DECODER'S HEADERS
                decoder.applyInputOptions(options);
                assertEquals("19000000", av_dict_get(options, "analyzeduration", null, 0).value().getString(), "microseconds");
                assertEquals(String.valueOf(23L * 1_024 * 1_024), av_dict_get(options, "probesize", null, 0).value().getString(), "bytes");
            } finally {
                av_dict_free(options);
                decoder.close();
            }
        }

        @Test
        @DisplayName("A separate audio track opens with the same probe policy")
        void separateTrackAppliesTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
            final Path audio = directory.resolve("track.wav");
            Fixtures.wav(audio, 48_000, 1, new byte[9_600]);
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), new Recorder(null));
            try {
                // THE DECODER NEVER STARTS, SO NO DEMUX THREAD RACES THE TRACK FIELDS THE OPEN WRITES
                assertTrue(decoder.initAudioTrack(new Track("Track", "en", audio.toUri()), -1, null), "the WAV track must open");
                assertProbePolicy(decoder.trackFormatContext, "the audio track");
            } finally {
                decoder.close();
            }
        }
    }

    @Nested
    @DisplayName("Abort")
    class Abort {
        @Test
        @DisplayName("An abort cuts every open blocked on a held origin short, with more decoders alive than JavaCPP binds callbacks for")
        void abortCutsEveryBlockedOpenShort() throws Exception {
            // JAVACPP BINDS AT MOST 10 LIVE INSTANCES OF ONE CALLBACK CLASS
            final int count = 12;
            final List<Recorder> hosts = new ArrayList<>();
            final List<FFDecoder> decoders = new ArrayList<>();
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.MP4_H264), "video/mp4")) {
                origin.hold();
                try {
                    for (int i = 0; i < count; i++) {
                        final Recorder host = new Recorder(null);
                        final FFDecoder decoder = decoder(origin.uri(".mp4"), host);
                        hosts.add(host);
                        decoders.add(decoder);
                        decoder.start();
                    }
                    assertTrue(PlayerWait.awaitCondition(() -> origin.requests() >= count, OPEN_TIMEOUT_MS), "every open must reach the held origin");

                    final long before = System.nanoTime();
                    for (final FFDecoder decoder: decoders) decoder.stop();
                    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                    assertTrue(elapsedMs < ABORT_MS, "the aborts must cut the opens short, they took " + elapsedMs + " ms");
                    for (final Recorder host: hosts) {
                        assertEquals(1L, host.opened.getCount(), "an aborted open never reports the input");
                        assertNull(host.failure, "an aborted open is not a failure");
                    }
                } finally {
                    origin.release();
                    for (final FFDecoder decoder: decoders) decoder.close();
                }
            }
        }

        @Test
        @DisplayName("An abort that cuts a read short never reports the end of the input to the timeline")
        void abortedReadIsNotTheEnd() throws Exception {
            final byte[] webm = Fixtures.webm(96, 64, 180, 30);
            // HALF THE FILE ON A CONNECTION THAT STAYS OPEN: THE DEMUXER WAITS INSIDE av_read_frame FOR THE REST
            try (final Origin origin = Origin.endless(Arrays.copyOf(webm, webm.length / 2), "video/webm")) {
                final Recorder host = new Recorder(null);
                final Linear timeline = new Linear(false);
                final FFDecoder decoder = new FFDecoder("FFThread-Read", origin.uri(".webm"), new HttpHeaders(), true, null, timeline, host);
                try {
                    decoder.start();
                    assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the partial input must open");
                    assertNull(host.failure, "the partial input must open without a failure");
                    assertTrue(PlayerWait.awaitCondition(() -> reading("FFThread-Read-demux"), OPEN_TIMEOUT_MS), "the demuxer must wait for the rest of the input");
                    decoder.stop();
                    assertFalse(timeline.finished, "an aborted read is the end of the decoder, not of the input");
                } finally {
                    decoder.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Audio streams of one input")
    class EmbeddedAudio {
        @Test
        @DisplayName("Without a preference the default stream decodes, every audio stream is reported and the others are discarded")
        void defaultStreamWithoutPreference(@TempDir final Path directory) throws Exception {
            final FFDecoder decoder = decode(dubbed(directory), new AudioChoice(List.of(), AudioChoice.AUTO, -1, null));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"), decoder.audioStream());
                assertEquals(List.of(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", "eng"), new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"),
                        new AudioStream(AudioChoice.MAIN, 2, "Audio 2", "fre")), decoder.audioStreams());
                assertEquals(Fixtures.level(1), sample(decoder), "the default stream decodes, not the first");
                assertEquals(AVDISCARD_ALL, decoder.formatContext.streams(0).discard(), "an undecoded stream is discarded");
                assertEquals(AVDISCARD_DEFAULT, decoder.formatContext.streams(1).discard(), "the decoded stream is read");
                assertEquals(AVDISCARD_ALL, decoder.formatContext.streams(2).discard(), "an undecoded stream is discarded");
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("The preferred language outranks the default, a BCP 47 tag meeting the bibliographic code the container carries")
        void preferredLanguageOutranksTheDefault(@TempDir final Path directory) throws Exception {
            final FFDecoder decoder = decode(dubbed(directory), new AudioChoice(List.of(), AudioChoice.AUTO, -1, "fr-CA"));
            try {
                assertEquals(2, decoder.audioStream().stream());
                assertEquals(Fixtures.level(2), sample(decoder));
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("A picked stream decodes while it carries the picked language; once it moved, the language finds it again")
        void pickedStreamDecodes(@TempDir final Path directory) throws Exception {
            final URI uri = dubbed(directory);
            final FFDecoder picked = decode(uri, new AudioChoice(List.of(), AudioChoice.MAIN, 0, "eng"));
            try {
                assertEquals(0, picked.audioStream().stream());
                assertEquals(Fixtures.level(0), sample(picked), "the pick outranks the default");
            } finally {
                picked.close();
            }
            final FFDecoder moved = decode(uri, new AudioChoice(List.of(), AudioChoice.MAIN, 0, "fre"));
            try {
                assertEquals(2, moved.audioStream().stream(), "stream 0 is no longer French, so the language finds the French one");
                assertEquals(Fixtures.level(2), sample(moved));
            } finally {
                moved.close();
            }
        }

        @Test
        @DisplayName("An HLS master's audio renditions decode by BCP 47 language, named after their NAME and flagged by DEFAULT")
        void hlsRenditions(@TempDir final Path directory) throws Exception {
            // A VARIANT WITH ITS OWN AUDIO AND TWO RENDITIONS OF ITS GROUP, EACH A ONE-SEGMENT PLAYLIST OF ITS OWN LEVEL
            final String[][] media = { { "main", "1000" }, { "english", "2000" }, { "spanish", "3000" } };
            for (final String[] entry: media) {
                Fixtures.segment(directory.resolve(entry[0] + ".mkv"), RATE_HZ,SECONDS, Short.parseShort(entry[1]));
                Files.writeString(directory.resolve(entry[0] + ".m3u8"), "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SECONDS
                        + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:" + SECONDS + ".0,\n" + entry[0] + ".mkv\n#EXT-X-ENDLIST\n", StandardCharsets.UTF_8);
            }
            final Path master = directory.resolve("master.m3u8");
            Files.writeString(master, """
                    #EXTM3U
                    #EXT-X-VERSION:3
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="en",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="english.m3u8"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="es-419",NAME="Español",DEFAULT=NO,AUTOSELECT=YES,URI="spanish.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=256000,AUDIO="aud"
                    main.m3u8
                    """, StandardCharsets.UTF_8);

            final FFDecoder english = decode(master.toUri(), new AudioChoice(List.of(), AudioChoice.AUTO, -1, null));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, english.audioStream().stream(), "English", "en"), english.audioStream(), "DEFAULT=YES plays");
                assertEquals((short) 2_000, sample(english));
                assertEquals(3, english.audioStreams().size(), "the variant's audio and both renditions");
            } finally {
                english.close();
            }
            final FFDecoder spanish = decode(master.toUri(), new AudioChoice(List.of(), AudioChoice.AUTO, -1, "es-MX"));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, spanish.audioStream().stream(), "Español", "es-419"), spanish.audioStream());
                assertEquals((short) 3_000, sample(spanish));
                for (final AudioStream other: spanish.audioStreams())
                    if (other.stream() != spanish.audioStream().stream())
                        assertEquals(AVDISCARD_ALL, spanish.formatContext.streams(other.stream()).discard(), "the other renditions are discarded");
            } finally {
                spanish.close();
            }
        }

        @Test
        @DisplayName("An input reopened after a failed seek keeps the stream that played instead of the first one")
        void reopenKeepsThePlayingStream(@TempDir final Path directory) throws Exception {
            final FFDecoder decoder = new FFDecoder("FFThread-Reopen", dubbed(directory), new HttpHeaders(), false,
                    new AudioChoice(List.of(), AudioChoice.AUTO, -1, "fr"), new Linear(true), new Recorder(new FFDecoder.Pcm(SampleType.S16, 1)));
            try {
                // NEVER STARTED: THE OPEN AND THE REOPEN RUN ON THIS THREAD, SO NO DEMUX OR DECODE THREAD TOUCHES THE CONTEXTS
                final Method init = FFDecoder.class.getDeclaredMethod("init");
                final Method reopen = FFDecoder.class.getDeclaredMethod("reopenFormat");
                init.setAccessible(true);
                reopen.setAccessible(true);
                assertTrue((boolean) init.invoke(decoder), "the input must open");
                assertEquals(2, decoder.audioStream().stream());
                assertTrue((boolean) reopen.invoke(decoder), "the input must reopen");
                assertEquals(new AudioStream(AudioChoice.MAIN, 2, "Audio 2", "fre"), decoder.audioStream());
                assertEquals(AVDISCARD_ALL, decoder.formatContext.streams(0).discard(), "the reopened input discards again");
                assertEquals(AVDISCARD_DEFAULT, decoder.formatContext.streams(2).discard());
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("Without a match nor a default flag the first stream decodes, and an untagged stream reports no language")
        void firstStreamWithoutMatchOrDefault(@TempDir final Path directory) throws Exception {
            final Path file = directory.resolve("plain.mka");
            Fixtures.dubbed(file, RATE_HZ,SECONDS, -1, null, "eng");
            final FFDecoder decoder = decode(file.toUri(), new AudioChoice(List.of(), AudioChoice.AUTO, -1, "ja"));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", null), decoder.audioStream());
                assertEquals(Fixtures.level(0), sample(decoder));
            } finally {
                decoder.close();
            }
        }
    }

    @Nested
    @DisplayName("Separate audio tracks")
    class SeparateTracks {
        @Test
        @DisplayName("The separate track of the preferred language plays over the main input's audio, else the first track")
        void trackOfThePreferredLanguage(@TempDir final Path directory) throws Exception {
            final URI main = dubbed(directory);
            final List<Track> tracks = tracks(directory);

            final FFDecoder french = decode(main, new AudioChoice(tracks, AudioChoice.AUTO, -1, "fr"));
            try {
                // THE WAV TRACK CARRIES NO TAGS, SO ITS STREAM TAKES THE TRACK'S NAME AND LANGUAGE
                assertEquals(new AudioStream(1, 0, "Français", "fr-CA"), french.audioStream());
                assertEquals(FRENCH, sample(french));
                assertEquals(4, french.audioStreams().size(), "the main input's three streams and the opened track's one");
                assertEquals(new AudioStream(1, 0, "Français", "fr-CA"), french.audioStreams().get(3));
            } finally {
                french.close();
            }
            for (final String language: new String[] { null, "ja" }) {
                final FFDecoder first = decode(main, new AudioChoice(tracks, AudioChoice.AUTO, -1, language));
                try {
                    assertEquals(new AudioStream(0, 0, "Deutsch", "de"), first.audioStream(), "the platform's original plays for " + language);
                    assertEquals(GERMAN, sample(first));
                } finally {
                    first.close();
                }
            }
        }

        @Test
        @DisplayName("A pick of the main input plays its stream without opening any separate track")
        void pickOfTheMainInput(@TempDir final Path directory) throws Exception {
            final FFDecoder decoder = decode(dubbed(directory), new AudioChoice(tracks(directory), AudioChoice.MAIN, 0, "eng"));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", "eng"), decoder.audioStream());
                assertEquals(Fixtures.level(0), sample(decoder));
                assertNull(decoder.trackFormatContext, "no separate track opens");
                assertEquals(3, decoder.audioStreams().size(), "only the main input's streams are known");
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("A separate track that fails to open falls back to the main input's own choice")
        void brokenTrackFallsBackToTheMainInput(@TempDir final Path directory) throws Exception {
            final List<Track> tracks = List.of(new Track("Gone", "de", directory.resolve("missing.wav").toUri()));
            final FFDecoder decoder = decode(dubbed(directory), new AudioChoice(tracks, AudioChoice.AUTO, -1, null));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"), decoder.audioStream());
                assertEquals(Fixtures.level(1), sample(decoder));
            } finally {
                decoder.close();
            }
        }
    }

    @Nested
    @DisplayName("Output channel order")
    class OutputOrder {
        @Test
        @DisplayName("Four channels leave in OpenAL's quad order, so a 4.0 center plays from the front instead of the back left")
        void fourChannelsLeaveAsQuad(@TempDir final Path directory) throws Exception {
            // 4.0 IS FL FR FC BC: ONLY THE CENTER CARRIES SOUND
            final ByteBuffer pcm = ByteBuffer.allocate(RATE_HZ *SECONDS * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < RATE_HZ *SECONDS; i++) pcm.putShort((short) 0).putShort((short) 0).putShort(GERMAN).putShort((short) 0);
            final Path file = directory.resolve("center.wav");
            Fixtures.wav(file, RATE_HZ,4, AV_CH_LAYOUT_4POINT0, pcm.array());
            final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 4));
            final FFDecoder decoder = new FFDecoder("FFThread-Quad", file.toUri(), new HttpHeaders(), false,
                    new AudioChoice(List.of(), AudioChoice.AUTO, -1, null), new Linear(false), host);
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
                assertNull(host.failure, "the input must open without a failure");
                assertTrue(PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null, OPEN_TIMEOUT_MS), "the audio must decode");
                final AVFrame frame = decoder.audioFrames().peek().frame;
                assertEquals(AV_CH_LAYOUT_QUAD, frame.ch_layout().u_mask());
                final ByteBuffer out = frame.data(0).limit(8L * frame.nb_samples()).asBuffer().order(ByteOrder.nativeOrder());
                final int at = frame.nb_samples() / 2 * 8;
                assertTrue(out.getShort(at) > 0 && out.getShort(at + 2) > 0, "the center reaches the front pair");
                assertEquals(0, out.getShort(at + 4), "nothing plays from the back left");
                assertEquals(0, out.getShort(at + 6), "nothing plays from the back right");
            } finally {
                decoder.close();
            }
        }
    }

    @Nested
    @DisplayName("Language match")
    class LanguageMatch {
        @Test
        @DisplayName("The same tag scores 2 across case, BCP 47 and the ISO 639-2 codes containers carry")
        void sameTag() {
            assertEquals(2, FFDecoder.match("es-MX", "ES-mx"));
            assertEquals(2, FFDecoder.match("en", "eng"));
            assertEquals(2, FFDecoder.match("spa", "es"));
            assertEquals(2, FFDecoder.match("en_US", "en-US"));
            assertEquals(2, FFDecoder.match("en", "en,fr"), "an HLS pair counts by its first language");
        }

        @Test
        @DisplayName("Every bibliographic code meets its two-letter tag and its terminologic twin")
        void bibliographicCodes() {
            final String[][] pairs = {
                    { "sq", "alb", "sqi" }, { "hy", "arm", "hye" }, { "eu", "baq", "eus" }, { "my", "bur", "mya" }, { "zh", "chi", "zho" },
                    { "cs", "cze", "ces" }, { "nl", "dut", "nld" }, { "fr", "fre", "fra" }, { "ka", "geo", "kat" }, { "de", "ger", "deu" },
                    { "el", "gre", "ell" }, { "is", "ice", "isl" }, { "mk", "mac", "mkd" }, { "mi", "mao", "mri" }, { "ms", "may", "msa" },
                    { "fa", "per", "fas" }, { "ro", "rum", "ron" }, { "sk", "slo", "slk" }, { "bo", "tib", "bod" }, { "cy", "wel", "cym" }
            };
            for (final String[] pair: pairs) {
                assertEquals(2, FFDecoder.match(pair[0], pair[1]), pair[0] + " meets " + pair[1]);
                assertEquals(2, FFDecoder.match(pair[2], pair[1]), pair[2] + " meets " + pair[1]);
            }
        }

        @Test
        @DisplayName("The same primary language scores 1, a preference for es-MX taking every Spanish")
        void samePrimaryLanguage() {
            for (final String spanish: new String[] { "es-419", "es", "es-US", "spa" })
                assertEquals(1, FFDecoder.match("es-MX", spanish), spanish);
            assertEquals(1, FFDecoder.match("en", "en-US"));
            assertEquals(1, FFDecoder.match("zh-Hans", "chi"));
        }

        @Test
        @DisplayName("Another language, an undetermined one or a missing side scores 0")
        void noMatch() {
            assertEquals(0, FFDecoder.match("es", "en"));
            assertEquals(0, FFDecoder.match("fr", "en,fr"));
            assertEquals(0, FFDecoder.match("es", "und"));
            assertEquals(0, FFDecoder.match(null, "en"));
            assertEquals(0, FFDecoder.match("es", null));
            assertEquals(0, FFDecoder.match("", "en"));
        }
    }

    // A TIMELINE WITHOUT SEEKS THAT RECORDS WHETHER THE DEMUXER REPORTED THE END OF THE INPUT;
    // A HELD ONE STAYS PAUSED, SO THE DEMUXER PARKS ONCE THE INPUT IS OPEN
    private static final class Linear implements FFDecoder.Timeline {
        private final boolean held;
        private volatile boolean finished;

        private Linear(final boolean held) { this.held = held; }

        @Override public FFDecoder.Seek consumeSeek() { return null; }
        @Override public boolean seekPending() { return false; }
        @Override public boolean holding() { return this.held; }
        @Override public void awaitChange(final long timeoutMs) throws InterruptedException { Thread.sleep(timeoutMs > 0 ? timeoutMs : 50); }
        @Override public void serial(final int serial) {}
        @Override public void start() {}
        @Override public void signalDemuxResumed() {}
        @Override public void signalDemuxFinished() { this.finished = true; }
        @Override public double frameDurationSec() { return 1.0 / 30.0; }
    }

    // RECORDS THE OPEN OR ITS FAILURE AND TAKES THE AUDIO AS pcm (null DROPS IT); NOTHING IS CACHED, SO EVERY INPUT OPENS WHERE IT LIVES
    private static final class Recorder implements FFDecoder.Host {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final FFDecoder.Pcm pcm;
        private volatile Throwable failure;

        private Recorder(final FFDecoder.Pcm pcm) { this.pcm = pcm; }

        @Override public Path cached(final URI uri) { return null; }
        @Override public FFDecoder.Device vulkan(final int codecId) { return null; }
        @Override public FFDecoder.Pcm audio(final SampleType type, final int channels, final int rate) { return this.pcm; }
        @Override public void duration(final long ms) {}
        @Override public void opened(final FFDecoder decoder) { this.opened.countDown(); }

        @Override
        public void failed(final Throwable cause) {
            this.failure = cause;
            this.opened.countDown();
        }
    }
}
