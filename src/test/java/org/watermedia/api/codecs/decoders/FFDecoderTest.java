package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.decoders.FFDecoder.AudioChoice;
import org.watermedia.api.codecs.decoders.FFDecoder.AudioStream;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.IdleOrigin;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL;
import static org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_DEFAULT;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_4POINT0;
import static org.bytedeco.ffmpeg.global.avutil.AV_CH_LAYOUT_QUAD;
import static org.bytedeco.ffmpeg.global.avutil.AV_OPT_SEARCH_CHILDREN;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_get;
import static org.bytedeco.ffmpeg.global.avutil.av_free;
import static org.bytedeco.ffmpeg.global.avutil.av_opt_get;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies {@link FFDecoder}: the shared input options (the configured probe policy and the I/O timeout on the main
 * input and a separate track, the reconnect policy of HLS and DASH segments, headers only when present, bounded
 * requests on YouTube's CDN alone, an RTMP input that connects instead of listening), HLS segment downloads that resume
 * after a reset or a silent connection, a refused segment asked for again, aborts that cut every blocked open and read
 * short without passing for the end of the input, the audio stream an {@link AudioChoice} decodes among the main
 * input's streams, HLS renditions and separate tracks while the others are discarded, a video that keeps coming beside
 * a separate track whose queue is full or whose download is paced, the quad order of four channels and the language match.
 */
@DisplayName("FFDecoder")
public class FFDecoderTest {
    private static final long OPEN_TIMEOUT_MS = 15_000L;
    private static final long ABORT_MS = 5_000L; // HALF THE 10 s I/O TIMEOUT AN OPEN THE ABORT MISSED WAITS FOR
    private static final long BLOCKED_MS = 200L; // A READ THAT HOLDS THE DEMUXER THIS LONG WAITS FOR DATA, NOT ON A BUFFERED PACKET
    private static final int RATE_HZ = 8_000;
    private static final int SECONDS = 3;
    // SAMPLE VALUES OF THE SEPARATE TRACKS, APART FROM EVERY Fixtures.level OF THE MAIN INPUT
    private static final short GERMAN = 7_000;
    private static final short FRENCH = 8_000;
    // IDLE CONNECTIONS: THREE 23 MB SEGMENTS (384 kHz MONO S16) ON AN ORIGIN THAT CUTS A CONNECTION IDLE FOR A SECOND
    private static final int SEGMENTS = 3;
    private static final int SEGMENT_RATE_HZ = 384_000;
    private static final int SEGMENT_SECONDS = 30;
    private static final long IDLE_MS = 1_000L;
    private static final long CUT_TIMEOUT_MS = 30_000L;
    private static final long DRAIN_TIMEOUT_MS = 90_000L; // EACH SILENT CONNECTION COSTS ITS READ THE 10 s I/O TIMEOUT
    private static final int REQUEST_BYTES = 10 * 1_024 * 1_024; // THE MOST A REQUEST ASKS OF YOUTUBE'S CDN
    // A VIDEO BESIDE A SEPARATE TRACK: AT 384 kHz THE TRACK OUTGROWS THE 8 MiB AUDIO PACKET QUEUE IN 11 s OF SOUND
    private static final int FPS = 30;
    private static final int FULL_SECONDS = 20;
    private static final int PACED_RATE_HZ = 48_000;
    private static final int PACED_SECONDS = 12;
    // A SEEK BESIDE A SEPARATE TRACK: THE TARGET SITS BETWEEN TWO CUES OF AN AUDIO-ONLY WEBM, WHICH CARRIES ONE PER CLUSTER
    private static final int SEEK_SECONDS = 20;
    private static final long SEEK_TARGET_MS = 12_500L;
    private static final long SEEK_SLACK_MS = 60L; // THREE OPUS PACKETS

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

    // THE PROBE POLICY THE CONTEXT OPENED WITH AND THE I/O TIMEOUT ITS CONNECTION TOOK
    private static void assertInputPolicy(final AVFormatContext context, final String what) {
        assertEquals(19_000_000, context.max_analyze_duration(), what + " analyzes for the configured duration");
        assertEquals(23L * 1_024 * 1_024, context.probesize(), what + " probes the configured size");
        final BytePointer timeout = new BytePointer((Pointer) null);
        try {
            assertTrue(av_opt_get(context.pb(), "rw_timeout", AV_OPT_SEARCH_CHILDREN, timeout) >= 0, what + " reads through a connection");
            assertEquals("10000000", timeout.getString(), what + " times out a stalled read");
        } finally {
            av_free(timeout);
        }
    }

    // PLAYS A THREE-SEGMENT HLS VOD FROM origin, HELD UNTIL IT CUT EVERY CONNECTION; EVERY FRAME OF EVERY SEGMENT MUST DECODE
    // AND EVERY DOWNLOAD CUT MID-BODY MUST BE ASKED FOR AGAIN
    private static void playsWholeAfterHold(final IdleOrigin origin, final Path directory) throws Exception {
        // A SEGMENT OUTGROWS THE SOCKET BUFFERS, SO THE CUT LANDS MID-DOWNLOAD; ITS SAMPLES CARRY Fixtures.level OF ITS INDEX
        final StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SEGMENT_SECONDS + "\n#EXT-X-MEDIA-SEQUENCE:0\n");
        for (int i = 0; i < SEGMENTS; i++) {
            final Path segment = directory.resolve("segment" + i + ".mkv");
            Fixtures.segment(segment, SEGMENT_RATE_HZ, SEGMENT_SECONDS, Fixtures.level(i));
            origin.serve("/segment" + i + ".mkv", "video/x-matroska", Files.readAllBytes(segment));
            playlist.append("#EXTINF:").append(SEGMENT_SECONDS).append(".0,\nsegment").append(i).append(".mkv\n");
        }
        origin.serve("/media.m3u8", "application/vnd.apple.mpegurl", playlist.append("#EXT-X-ENDLIST\n").toString().getBytes(StandardCharsets.UTF_8));

        final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 1));
        final Linear timeline = new Linear(true);
        final FFDecoder decoder = new FFDecoder("FFThread-Idle", origin.uri("/media.m3u8"), new HttpHeaders(), false,
                new AudioChoice(List.of(), AudioChoice.AUTO, -1, null), timeline, host);
        try {
            decoder.start();
            assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the playlist must open");
            assertNull(host.failure, "the playlist must open without a failure");
            // HELD, THE DEMUXER STOPS READING AND THE ORIGIN CUTS THE SEGMENT DOWNLOADS IT CANNOT FINISH
            assertTrue(PlayerWait.awaitCondition(() -> origin.connections() == 0, CUT_TIMEOUT_MS), "the origin must cut every idle connection");
            // ONLY A CUT IN THE MIDDLE OF A BODY NEEDS A RESUME: AN IDLE KEEP-ALIVE CUT PASSES WITH OR WITHOUT THE RECONNECT POLICY
            final List<String> stalled = new ArrayList<>();
            for (int i = 0; i < SEGMENTS; i++) if (origin.cuts("/segment" + i + ".mkv") > 0) stalled.add("/segment" + i + ".mkv");
            assertFalse(stalled.isEmpty(), "the origin must cut a segment download in the middle of its body");
            timeline.release();

            final int[] frames = framesPerSegment(decoder, timeline);
            final int[] whole = new int[SEGMENTS];
            Arrays.fill(whole, SEGMENT_SECONDS * 10); // Fixtures.segment WRITES 100 ms PACKETS, ONE FRAME EACH
            assertArrayEquals(whole, frames, "every packet of every segment must decode, frames per segment: " + Arrays.toString(frames));
            for (final String path: stalled)
                assertTrue(origin.requests(path) > origin.cuts(path), path + " must be asked for again after its cut, requests: " + origin.requests(path) + ", cuts: " + origin.cuts(path));
            assertEquals(0, origin.strays(), "no request may end its headers twice");
        } finally {
            decoder.close();
        }
    }

    // DRAINS THE DECODER TO THE END OF ITS INPUT AND RETURNS EVERY DECODED FRAME, COUNTED BY THE SEGMENT ITS SAMPLES NAME
    private static int[] framesPerSegment(final FFDecoder decoder, final Linear timeline) {
        final int[] frames = new int[SEGMENTS];
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_TIMEOUT_MS);
        while (!timeline.finished || decoder.decoding() || !decoder.audioFrames().empty()) {
            final boolean ready = PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null || timeline.finished && !decoder.decoding(),
                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            assertTrue(ready, "the input must play to its end, frames per segment so far: " + Arrays.toString(frames));
            if (decoder.audioFrames().peek() == null) continue;
            frames[sample(decoder) / Fixtures.level(0) - 1]++;
            decoder.audioFrames().next();
        }
        return frames;
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
    // THE FIRST FRAME DECODED; THE CALLER CLOSES IT, A FAILED OPEN CLOSES IT HERE
    private static FFDecoder decode(final URI uri, final AudioChoice choice) throws Exception {
        final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 1));
        final FFDecoder decoder = new FFDecoder("FFThread-Audio", uri, new HttpHeaders(), false, choice, new Linear(false), host);
        try {
            decoder.start();
            assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
            assertNull(host.failure, "the input must open without a failure");
            assertTrue(PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null, OPEN_TIMEOUT_MS), "the audio must decode");
            return decoder;
        } catch (final Throwable failure) {
            decoder.close();
            throw failure;
        }
    }

    // THE FIRST DECODED SAMPLE, WHICH NAMES THE STREAM IT CAME FROM
    private static short sample(final FFDecoder decoder) {
        return decoder.audioFrames().peek().frame.data(0).limit(2).asBuffer().order(ByteOrder.nativeOrder()).getShort(0);
    }

    // WRITES A VIDEO WITHOUT SOUND OF seconds AND OPENS IT BESIDE track ON A MONO S16 ENGINE, FOR THE CALLER TO CLOSE
    private static FFDecoder beside(final Path directory, final int seconds, final Track track) throws Exception {
        final Path video = directory.resolve("video.webm");
        Files.write(video, Fixtures.webm(64, 40, seconds * FPS, FPS));
        final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 1));
        final FFDecoder decoder = new FFDecoder("FFThread-Beside", video.toUri(), new HttpHeaders(), true,
                new AudioChoice(List.of(track), AudioChoice.AUTO, -1, null), new Linear(false), host);
        try {
            decoder.start();
            assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the video must open");
            assertNull(host.failure, "the video must open without a failure");
            assertEquals(new AudioStream(0, 0, track.name(), track.lang()), decoder.audioStream(), "the separate track plays");
            return decoder;
        } catch (final Throwable failure) {
            decoder.close();
            throw failure;
        }
    }

    // COUNTS THE VIDEO FRAMES IT TAKES FROM THE DECODER, NEVER ITS SOUND, UNTIL count CAME OUT OR timeoutMs PASSED
    private static int videoFrames(final FFDecoder decoder, final int count, final long timeoutMs) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int frames = 0;
        while (frames < count && PlayerWait.awaitCondition(() -> decoder.videoFrames().peek() != null, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))) {
            decoder.videoFrames().next();
            frames++;
        }
        return frames;
    }

    // THREE TAGGED STREAMS, THE SPANISH ONE FLAGGED DEFAULT; THE FRENCH ONE CARRIES THE BIBLIOGRAPHIC CODE
    private static URI dubbed(final Path directory) throws Exception {
        final Path file = directory.resolve("dubbed.mka");
        Fixtures.dubbed(file, RATE_HZ, SECONDS, 1, "eng", "spa", "fre");
        return file.toUri();
    }

    // THE SEPARATE TRACKS OF A SOURCE: GERMAN FIRST (THE PLATFORM'S ORIGINAL), THEN FRENCH; WAV CARRIES NO TAGS OF ITS OWN
    private static List<Track> tracks(final Path directory) throws Exception {
        final Path german = directory.resolve("german.wav");
        final Path french = directory.resolve("french.wav");
        Fixtures.wav(german, RATE_HZ, 1, Fixtures.tone(GERMAN, RATE_HZ * SECONDS));
        Fixtures.wav(french, RATE_HZ, 1, Fixtures.tone(FRENCH, RATE_HZ * SECONDS));
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
            // VALUES NO DEFAULT HAS, SO AN INPUT THAT IGNORES THE CONFIG FAILS
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = 19_000;
            WaterMediaConfig.codecs.ffmpeg.probeSize = 23;
        }

        @AfterEach
        void restore() {
            WaterMediaConfig.codecs.ffmpeg.analyzeDuration = this.analysis;
            WaterMediaConfig.codecs.ffmpeg.probeSize = this.probe;
        }

        @Test
        @DisplayName("The main input opens with the configured probe policy and the I/O timeout, and the shared input options carry the policy in FFmpeg's units")
        void mainInputAppliesTheConfiguredProbePolicy() throws Exception {
            final Recorder host = new Recorder(null);
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), host);
            final AVDictionary options = new AVDictionary();
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the main input must open");
                assertNull(host.failure, "the main input must open without a failure");
                // THE DEMUXER PARKS ON THE HELD TIMELINE RIGHT AFTER THE OPEN, SO ITS CONTEXT IS ONLY READ HERE
                assertInputPolicy(decoder.formatContext, "the main input");

                FFDecoder.inputOptions(options, decoder.uri(), new HttpHeaders());
                assertEquals("19000000", av_dict_get(options, "analyzeduration", null, 0).value().getString(), "the analysis duration goes in microseconds");
                assertEquals(String.valueOf(23L * 1_024 * 1_024), av_dict_get(options, "probesize", null, 0).value().getString(), "the probe size goes in bytes");
            } finally {
                av_dict_free(options);
                decoder.close();
            }
        }

        @Test
        @DisplayName("An input's connections get the 10 s I/O timeout, and HLS and DASH segments keep the TLS policy, retry a failed open and resume a cut download without restarting it at byte 0")
        void transportPolicy() {
            final AVDictionary options = new AVDictionary();
            try {
                FFDecoder.inputOptions(options, URI.create("https://example.org/media.m3u8"), new HttpHeaders());
                assertEquals("10000000", av_dict_get(options, "timeout", null, 0).value().getString(), "the input's own connections");
                assertEquals("10000000", av_dict_get(options, "rw_timeout", null, 0).value().getString(), "the only timeout HLS and DASH copy to their segments");
                assertEquals("2", av_dict_get(options, "seg_max_retry", null, 0).value().getString(), "an HLS segment that fails to open is asked for again");
                final String nested = av_dict_get(options, "protocol_opts", null, 0).value().getString();
                assertTrue(nested.contains("tls_verify=1"), "the TLS policy stays: " + nested);
                assertTrue(nested.endsWith(":reconnect=1:reconnect_delay_max=5"), "a cut segment download resumes at its offset: " + nested);
                assertFalse(nested.contains("reconnect_streamed"), "a segment served without ranges never restarts at byte 0: " + nested);
            } finally {
                av_dict_free(options);
            }
        }

        @Test
        @DisplayName("Only YouTube's CDN gets requests of at most 10 MiB, every other host one request for the whole file")
        void boundedRequestsOnlyOnYouTubeCdn() {
            final Map<String, Boolean> inputs = Map.of(
                    "https://rr3---sn-abc.googlevideo.com/videoplayback?id=1", true,
                    "https://GoogleVideo.com/videoplayback", true,
                    "https://example.org/video.mp4", false,
                    "https://notgooglevideo.com/videoplayback", false,
                    "https://googlevideo.com.example.org/videoplayback", false,
                    "http://127.0.0.1:8080/video.mp4", false,
                    Fixtures.fileUri(Fixtures.MP4_H264).toString(), false);
            for (final Map.Entry<String, Boolean> input: inputs.entrySet()) {
                final AVDictionary options = new AVDictionary();
                try {
                    FFDecoder.inputOptions(options, URI.create(input.getKey()), new HttpHeaders());
                    final AVDictionaryEntry size = av_dict_get(options, "request_size", null, 0);
                    if (input.getValue()) assertEquals(String.valueOf(REQUEST_BYTES), size == null ? null : size.value().getString(), input.getKey() + " asks for at most 10 MiB");
                    else assertNull(size, input.getKey() + " asks for the whole file");
                } finally {
                    av_dict_free(options);
                }
            }
        }

        @Test
        @DisplayName("The options of a YouTube CDN link read a file past 10 MiB to its end through requests of at most 10 MiB")
        void cdnOptionsReadInBoundedRequests(@TempDir final Path directory) throws Exception {
            final byte[] pcm = new byte[REQUEST_BYTES + REQUEST_BYTES / 4];
            final Path wav = directory.resolve("long.wav");
            Fixtures.wav(wav, 48_000, 2, pcm);
            final byte[] body = Files.readAllBytes(wav);
            final List<String> ranges = new CopyOnWriteArrayList<>();
            final ExecutorService workers = Executors.newCachedThreadPool();
            final AVDictionary options = new AVDictionary();
            final AVFormatContext context = avformat_alloc_context();
            final AVPacket packet = av_packet_alloc();
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
                ranges.add(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
                LocalHttp.respondRange(exchange, "audio/wav", body);
            }, workers)) {
                // THE OPTIONS OF A CDN LINK OPEN THE LOOPBACK FILE: FFMPEG ITSELF MUST SPLIT THE READ
                FFDecoder.inputOptions(options, URI.create("https://rr3---sn-abc.googlevideo.com/videoplayback"), new HttpHeaders());
                assertTrue(avformat_open_input(context, origin.uri("/long.wav").toString(), null, options) >= 0, "the file must open");
                long read = 0;
                while (av_read_frame(context, packet) >= 0) {
                    read += packet.size();
                    av_packet_unref(packet);
                }
                assertEquals(pcm.length, read, "every sample must be read");

                long last = -1;
                for (final String range: ranges) {
                    assertTrue(range.startsWith("bytes=") && !range.endsWith("-"), "every request must name its end: " + ranges);
                    final String[] bounds = range.substring("bytes=".length()).split("-", 2);
                    assertTrue(Long.parseLong(bounds[1]) - Long.parseLong(bounds[0]) < REQUEST_BYTES, "no request may ask for more than 10 MiB: " + ranges);
                    last = Math.max(last, Math.min(Long.parseLong(bounds[1]), body.length - 1L));
                }
                assertEquals(body.length - 1L, last, "the requests must reach the last byte: " + ranges);
            } finally {
                av_packet_free(packet);
                avformat_close_input(context);
                av_dict_free(options);
                workers.shutdownNow();
            }
        }

        @Test
        @DisplayName("Any other host gets open requests, so one of them can serve the whole file")
        void openRequestsElsewhere() throws Exception {
            final byte[] body = Fixtures.readAll(Fixtures.MP4_H264);
            final List<String> ranges = new CopyOnWriteArrayList<>();
            final ExecutorService workers = Executors.newCachedThreadPool();
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
                ranges.add(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
                LocalHttp.respondRange(exchange, "video/mp4", body);
            }, workers)) {
                final Recorder host = new Recorder(null);
                final FFDecoder decoder = decoder(origin.uri("/clip.mp4"), host);
                try {
                    decoder.start();
                    assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
                    assertNull(host.failure, "the input must open without a failure");
                    assertFalse(ranges.isEmpty(), "the input must reach the origin");
                    assertTrue(ranges.stream().allMatch(range -> range.equals("null") || range.endsWith("-")), "no request may name its end: " + ranges);
                } finally {
                    decoder.close();
                }
            } finally {
                workers.shutdownNow();
            }
        }

        @Test
        @DisplayName("An RTMP input never gets timeout, which it reads as the wait for an incoming connection, and keeps the I/O timeout")
        void rtmpNeverWaitsForAConnection() {
            for (final String scheme: List.of("rtmp", "rtmps", "rtmpt", "RTMPE", "rtsp", "https")) {
                final AVDictionary options = new AVDictionary();
                try {
                    FFDecoder.inputOptions(options, URI.create(scheme + "://example.org/live/stream"), new HttpHeaders());
                    final AVDictionaryEntry timeout = av_dict_get(options, "timeout", null, 0);
                    if (scheme.toLowerCase(Locale.ROOT).startsWith("rtmp")) assertNull(timeout, scheme + " takes no timeout");
                    else assertEquals("10000000", timeout == null ? null : timeout.value().getString(), scheme + " times out its connection");
                    assertEquals("10000000", av_dict_get(options, "rw_timeout", null, 0).value().getString(), scheme + " times out a stalled read");
                } finally {
                    av_dict_free(options);
                }
            }
        }

        @Test
        @DisplayName("An RTMP input connects to its server instead of waiting for one to connect")
        void rtmpConnectsToItsServer() throws Exception {
            try (final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                server.setSoTimeout((int) OPEN_TIMEOUT_MS);
                final FFDecoder decoder = decoder(URI.create("rtmp://127.0.0.1:" + server.getLocalPort() + "/live/stream"), new Recorder(null));
                try {
                    decoder.start();
                    // THE CONNECTION IS ENOUGH: AN INPUT THAT LISTENS NEVER MAKES ONE AND accept TIMES OUT
                    assertDoesNotThrow(server::accept, "the input must connect to its server").close();
                } finally {
                    decoder.close();
                }
            }
        }

        @Test
        @DisplayName("Headers reach FFmpeg only when there are any, so no request ends its headers twice")
        void headersOnlyWhenPresent() {
            final URI uri = Fixtures.fileUri(Fixtures.MP4_H264);
            final AVDictionary none = new AVDictionary();
            final AVDictionary some = new AVDictionary();
            try {
                FFDecoder.inputOptions(none, uri, new HttpHeaders());
                FFDecoder.inputOptions(some, uri, new HttpHeaders().set("Referer", "https://example.org/"));
                // AN EMPTY headers ENDS THE REQUEST HEAD EARLY
                assertNull(av_dict_get(none, "headers", null, 0), "an input without headers passes none");
                assertEquals("Referer: https://example.org/\r\n", av_dict_get(some, "headers", null, 0).value().getString(), "every header line ends in CRLF");
            } finally {
                av_dict_free(none);
                av_dict_free(some);
            }
        }

        @Test
        @DisplayName("A separate audio track opens with the same probe policy and I/O timeout")
        void separateTrackAppliesTheConfiguredProbePolicy(@TempDir final Path directory) throws Exception {
            final Path audio = directory.resolve("track.wav");
            Fixtures.wav(audio, 48_000, 1, new byte[9_600]);
            final FFDecoder decoder = decoder(Fixtures.fileUri(Fixtures.MP4_H264), new Recorder(null));
            try {
                // THE DECODER NEVER STARTS, SO NO DEMUX THREAD RACES THE TRACK FIELDS THE OPEN WRITES
                assertTrue(decoder.initAudioTrack(new Track("Track", "en", audio.toUri()), -1, null), "the WAV track must open");
                assertInputPolicy(decoder.trackFormatContext, "the audio track");
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
        void abortCutsEveryBlockedOpenShort() {
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
                    // A STOP THAT LANDS ON A QUICK READ OF BUFFERED DATA ENDS THE LOOP ON ITS INTERRUPT, WITH OR WITHOUT THE ABORT GUARD,
                    // SO EVERY POLL OF THE LAST BLOCKED_MS MUST FIND THE DEMUXER INSIDE THE READ; since IS ITS LAST POLL OUTSIDE ONE
                    final long[] since = { System.nanoTime() };
                    assertTrue(PlayerWait.awaitCondition(() -> {
                        final long now = System.nanoTime();
                        if (!reading("FFThread-Read-demux")) since[0] = now;
                        return now - since[0] >= TimeUnit.MILLISECONDS.toNanos(BLOCKED_MS);
                    }, OPEN_TIMEOUT_MS), "the demuxer must wait inside one read for the rest of the input");
                    decoder.stop();
                    assertFalse(timeline.finished, "an aborted read is the end of the decoder, not of the input");
                } finally {
                    decoder.close();
                }
            }
        }
    }

    @Nested
    @DisplayName("Idle connections")
    class IdleConnections {
        @Test
        @DisplayName("An HLS segment download the server reset while the demuxer was held resumes where it stopped instead of being skipped")
        void resetSegmentResumes(@TempDir final Path directory) throws Exception {
            try (final IdleOrigin origin = IdleOrigin.resetting(IDLE_MS)) {
                playsWholeAfterHold(origin, directory);
            }
        }

        @Test
        @DisplayName("An HLS segment download that went silent while the demuxer was held times out into a resume instead of blocking it")
        void silentSegmentResumes(@TempDir final Path directory) throws Exception {
            try (final IdleOrigin origin = IdleOrigin.silent(IDLE_MS)) {
                playsWholeAfterHold(origin, directory);
            }
        }
    }

    @Nested
    @DisplayName("Segment retries")
    class SegmentRetries {
        @Test
        @DisplayName("An HLS segment the server refused twice is asked for again instead of being skipped")
        void refusedSegmentIsAskedForAgain(@TempDir final Path directory) throws Exception {
            final Map<String, byte[]> bodies = new HashMap<>();
            final StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SEGMENT_SECONDS + "\n#EXT-X-MEDIA-SEQUENCE:0\n");
            for (int i = 0; i < SEGMENTS; i++) {
                final Path segment = directory.resolve("segment" + i + ".mkv");
                Fixtures.segment(segment, SEGMENT_RATE_HZ, SEGMENT_SECONDS, Fixtures.level(i));
                bodies.put("/segment" + i + ".mkv", Files.readAllBytes(segment));
                playlist.append("#EXTINF:").append(SEGMENT_SECONDS).append(".0,\nsegment").append(i).append(".mkv\n");
            }
            bodies.put("/media.m3u8", playlist.append("#EXT-X-ENDLIST\n").toString().getBytes(StandardCharsets.UTF_8));

            // ONLY THE FIRST SEGMENT IS NEVER FETCHED AHEAD, SO EACH 503 IS A FAILED OPEN; THE FETCH AHEAD NEEDS A SECOND THREAD
            final AtomicInteger asked = new AtomicInteger();
            final ExecutorService workers = Executors.newCachedThreadPool();
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
                final String path = exchange.getRequestURI().getPath();
                if (path.equals("/segment0.mkv") && asked.incrementAndGet() <= 2) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                LocalHttp.respondRange(exchange, path.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/x-matroska", bodies.get(path));
            }, workers)) {
                final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 1));
                final Linear timeline = new Linear(false);
                final FFDecoder decoder = new FFDecoder("FFThread-Retry", origin.uri("/media.m3u8"), new HttpHeaders(), false,
                        new AudioChoice(List.of(), AudioChoice.AUTO, -1, null), timeline, host);
                try {
                    decoder.start();
                    assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the playlist must open");
                    assertNull(host.failure, "the playlist must open without a failure");

                    final int[] frames = framesPerSegment(decoder, timeline);
                    final int[] whole = new int[SEGMENTS];
                    Arrays.fill(whole, SEGMENT_SECONDS * 10); // Fixtures.segment WRITES 100 ms PACKETS, ONE FRAME EACH
                    assertArrayEquals(whole, frames, "the refused segment must decode whole, frames per segment: " + Arrays.toString(frames));
                    assertEquals(3, asked.get(), "the refused segment must be asked for until it opens");
                } finally {
                    decoder.close();
                }
            } finally {
                workers.shutdownNow();
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
                assertEquals(new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"), decoder.audioStream(), "the Spanish default stream decodes");
                assertEquals(List.of(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", "eng"), new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"),
                        new AudioStream(AudioChoice.MAIN, 2, "Audio 2", "fre")), decoder.audioStreams(), "every audio stream is reported in order");
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
                assertEquals(2, decoder.audioStream().stream(), "the French stream decodes, not the Spanish default");
                assertEquals(Fixtures.level(2), sample(decoder), "the French stream's samples decode");
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
                assertEquals(0, picked.audioStream().stream(), "the picked English stream decodes");
                assertEquals(Fixtures.level(0), sample(picked), "the pick outranks the default");
            } finally {
                picked.close();
            }
            final FFDecoder moved = decode(uri, new AudioChoice(List.of(), AudioChoice.MAIN, 0, "fre"));
            try {
                assertEquals(2, moved.audioStream().stream(), "stream 0 is no longer French, so the language finds the French one");
                assertEquals(Fixtures.level(2), sample(moved), "the French stream's samples decode");
            } finally {
                moved.close();
            }
        }

        @Test
        @DisplayName("An HLS master's audio renditions decode by BCP 47 language, named after their NAME and flagged by DEFAULT")
        void hlsRenditions(@TempDir final Path directory) throws Exception {
            // A VARIANT WITH ITS OWN AUDIO AND TWO RENDITIONS OF ITS GROUP, EACH A ONE-SEGMENT PLAYLIST FILLED WITH Fixtures.level OF ITS INDEX
            final String[] media = { "main", "english", "spanish" };
            for (int i = 0; i < media.length; i++) {
                Fixtures.segment(directory.resolve(media[i] + ".mkv"), RATE_HZ, SECONDS, Fixtures.level(i));
                Files.writeString(directory.resolve(media[i] + ".m3u8"), "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SECONDS
                        + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:" + SECONDS + ".0,\n" + media[i] + ".mkv\n#EXT-X-ENDLIST\n", StandardCharsets.UTF_8);
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
                assertEquals(Fixtures.level(1), sample(english), "the English rendition's samples decode");
                assertEquals(3, english.audioStreams().size(), "the variant's audio and both renditions");
            } finally {
                english.close();
            }
            final FFDecoder spanish = decode(master.toUri(), new AudioChoice(List.of(), AudioChoice.AUTO, -1, "es-MX"));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, spanish.audioStream().stream(), "Español", "es-419"), spanish.audioStream(), "es-MX picks the es-419 rendition");
                assertEquals(Fixtures.level(2), sample(spanish), "the Spanish rendition's samples decode");
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
                assertEquals(2, decoder.audioStream().stream(), "the French stream plays before the reopen");
                assertTrue((boolean) reopen.invoke(decoder), "the input must reopen");
                assertEquals(new AudioStream(AudioChoice.MAIN, 2, "Audio 2", "fre"), decoder.audioStream(), "the French stream plays after the reopen");
                assertEquals(AVDISCARD_ALL, decoder.formatContext.streams(0).discard(), "the reopened input discards again");
                assertEquals(AVDISCARD_DEFAULT, decoder.formatContext.streams(2).discard(), "the reopened input reads the playing stream");
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("With neither a language match nor a default flag the first stream decodes, and an untagged stream reports no language")
        void firstStreamWithoutMatchOrDefault(@TempDir final Path directory) throws Exception {
            final Path file = directory.resolve("plain.mka");
            Fixtures.dubbed(file, RATE_HZ, SECONDS, -1, null, "eng");
            final FFDecoder decoder = decode(file.toUri(), new AudioChoice(List.of(), AudioChoice.AUTO, -1, "ja"));
            try {
                assertEquals(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", null), decoder.audioStream(), "the untagged first stream decodes");
                assertEquals(Fixtures.level(0), sample(decoder), "the first stream's samples decode");
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
                assertEquals(new AudioStream(1, 0, "Français", "fr-CA"), french.audioStream(), "the French track plays");
                assertEquals(FRENCH, sample(french), "the French track's samples decode");
                assertEquals(4, french.audioStreams().size(), "the main input's three streams and the opened track's one");
                assertEquals(new AudioStream(1, 0, "Français", "fr-CA"), french.audioStreams().get(3), "the opened track is reported last");
            } finally {
                french.close();
            }
            for (final String language: new String[] { null, "ja" }) {
                final FFDecoder first = decode(main, new AudioChoice(tracks, AudioChoice.AUTO, -1, language));
                try {
                    assertEquals(new AudioStream(0, 0, "Deutsch", "de"), first.audioStream(), "the platform's original plays for " + language);
                    assertEquals(GERMAN, sample(first), "the German track's samples decode for " + language);
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
                assertEquals(new AudioStream(AudioChoice.MAIN, 0, "Audio 0", "eng"), decoder.audioStream(), "the picked English stream plays");
                assertEquals(Fixtures.level(0), sample(decoder), "the English stream's samples decode");
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
                assertEquals(new AudioStream(AudioChoice.MAIN, 1, "Audio 1", "spa"), decoder.audioStream(), "the main input's default stream plays");
                assertEquals(Fixtures.level(1), sample(decoder), "the default stream's samples decode");
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("A separate track whose packet queue filled up never holds the video back")
        void fullTrackQueueKeepsTheVideoComing(@TempDir final Path directory) throws Exception {
            // NOBODY TAKES THE SOUND, SO ITS QUEUE FILLS AND STAYS FULL WHILE THE VIDEO STILL HAS HALF ITS FRAMES TO READ
            final Path track = directory.resolve("track.wav");
            Fixtures.wav(track, SEGMENT_RATE_HZ, 1, Fixtures.tone(GERMAN, SEGMENT_RATE_HZ * FULL_SECONDS));
            final FFDecoder decoder = beside(directory, FULL_SECONDS, new Track("Deutsch", "de", track.toUri()));
            try {
                assertEquals(FULL_SECONDS * FPS, videoFrames(decoder, FULL_SECONDS * FPS, OPEN_TIMEOUT_MS), "every video frame must decode beside a full audio queue");
            } finally {
                decoder.close();
            }
        }

        @Test
        @DisplayName("A separate track downloading at twice its bitrate, as YouTube paces one, never slows the video below its own speed")
        void pacedTrackKeepsTheVideoComing(@TempDir final Path directory) throws Exception {
            try (final IdleOrigin origin = IdleOrigin.resetting(CUT_TIMEOUT_MS)) {
                final Path track = directory.resolve("track.wav");
                Fixtures.wav(track, PACED_RATE_HZ, 1, Fixtures.tone(GERMAN, PACED_RATE_HZ * PACED_SECONDS));
                origin.stream("/track.wav", "audio/wav", Files.readAllBytes(track), PACED_RATE_HZ * 2L * 2L);
                final FFDecoder decoder = beside(directory, PACED_SECONDS, new Track("Deutsch", "de", origin.uri("/track.wav")));
                try {
                    // THE TRACK IS ONLY READ AS FAR AS THE VIDEO GOT, SO A DOWNLOAD AT TWICE ITS BITRATE FEEDS THE VIDEO AT TWICE ITS SPEED.
                    // THE FIRST SIXTH IS MEASURED, WHILE MOST OF THE TRACK IS STILL DOWNLOADING
                    final int measured = PACED_SECONDS / 6;
                    final long started = System.nanoTime();
                    final int frames = videoFrames(decoder, measured * FPS, measured * 1_000L);
                    final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertEquals(measured * FPS, frames, measured + " s of video must arrive before they would have played, took " + elapsedMs + " ms");
                } finally {
                    decoder.close();
                }
            }
        }

        @Test
        @DisplayName("A seek lands a separate WebM track on its target instead of on the cue before it")
        void seekLandsTheTrackOnItsTarget(@TempDir final Path directory) throws Exception {
            final Path main = directory.resolve("main.mkv");
            final Path track = directory.resolve("track.webm");
            Fixtures.segment(main, RATE_HZ, SEEK_SECONDS, Fixtures.level(0));
            Fixtures.clip(track, Container.WEBM, null, AudioCodec.OPUS, 0, 0, FPS, SEEK_SECONDS);

            // HELD FROM THE OPEN, NOTHING PAST THE HEADERS IS READ: THE CUES ARE ALL THE TRACK'S INDEX KNOWS WHEN THE SEEK COMES
            final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 2));
            final Linear timeline = new Linear(true);
            timeline.seek(SEEK_TARGET_MS);
            final FFDecoder decoder = new FFDecoder("FFThread-Seek", main.toUri(), new HttpHeaders(), false,
                    new AudioChoice(List.of(new Track("Deutsch", "de", track.toUri())), AudioChoice.AUTO, -1, null), timeline, host);
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
                assertNull(host.failure, "the input must open without a failure");
                assertTrue(PlayerWait.awaitCondition(() -> !timeline.seekPending(), OPEN_TIMEOUT_MS), "the seek must be served");
                timeline.release();
                assertTrue(PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null, OPEN_TIMEOUT_MS), "the track must decode after the seek");
                final long landed = decoder.audioFrames().peek().ptsMs;
                assertTrue(Math.abs(landed - SEEK_TARGET_MS) <= SEEK_SLACK_MS, "the track must land on " + SEEK_TARGET_MS + " ms, landed on " + landed + " ms");
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
            final ByteBuffer pcm = ByteBuffer.allocate(RATE_HZ * SECONDS * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < RATE_HZ * SECONDS; i++) pcm.putShort((short) 0).putShort((short) 0).putShort(GERMAN).putShort((short) 0);
            final Path file = directory.resolve("center.wav");
            Fixtures.wav(file, RATE_HZ, 4, AV_CH_LAYOUT_4POINT0, pcm.array());
            final Recorder host = new Recorder(new FFDecoder.Pcm(SampleType.S16, 4));
            final FFDecoder decoder = new FFDecoder("FFThread-Quad", file.toUri(), new HttpHeaders(), false,
                    new AudioChoice(List.of(), AudioChoice.AUTO, -1, null), new Linear(false), host);
            try {
                decoder.start();
                assertTrue(host.opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the input must open");
                assertNull(host.failure, "the input must open without a failure");
                assertTrue(PlayerWait.awaitCondition(() -> decoder.audioFrames().peek() != null, OPEN_TIMEOUT_MS), "the audio must decode");
                final AVFrame frame = decoder.audioFrames().peek().frame;
                assertEquals(AV_CH_LAYOUT_QUAD, frame.ch_layout().u_mask(), "the frames leave in the quad layout");
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
            assertEquals(2, FFDecoder.match("es-MX", "ES-mx"), "es-MX meets ES-mx");
            assertEquals(2, FFDecoder.match("en", "eng"), "en meets eng");
            assertEquals(2, FFDecoder.match("spa", "es"), "spa meets es");
            assertEquals(2, FFDecoder.match("en_US", "en-US"), "en_US meets en-US");
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
                assertEquals(1, FFDecoder.match("es-MX", spanish), "es-MX takes " + spanish);
            assertEquals(1, FFDecoder.match("en", "en-US"), "en takes en-US");
            assertEquals(1, FFDecoder.match("zh-Hans", "chi"), "zh-Hans takes chi");
        }

        @Test
        @DisplayName("Another language, an undetermined one or a missing side scores 0")
        void noMatch() {
            assertEquals(0, FFDecoder.match("es", "en"), "es misses en");
            assertEquals(0, FFDecoder.match("fr", "en,fr"), "an HLS pair counts by its first language");
            assertEquals(0, FFDecoder.match("es", "und"), "und names no language");
            assertEquals(0, FFDecoder.match(null, "en"), "no preference matches nothing");
            assertEquals(0, FFDecoder.match("es", null), "an untagged stream matches nothing");
            assertEquals(0, FFDecoder.match("", "en"), "an empty preference matches nothing");
        }
    }

    // A TIMELINE THAT RECORDS WHETHER THE DEMUXER REPORTED THE END OF THE INPUT AND HANDS OVER THE QUICK SEEK seek() LEAVES;
    // A HELD ONE STAYS PAUSED, SO THE DEMUXER PARKS ONCE THE INPUT IS OPEN, UNTIL release()
    private static final class Linear implements FFDecoder.Timeline {
        private volatile boolean held;
        private volatile boolean finished;
        private volatile FFDecoder.Seek seek;

        private Linear(final boolean held) { this.held = held; }

        private void release() { this.held = false; }
        private void seek(final long targetMs) { this.seek = new FFDecoder.Seek(targetMs, false); }

        // ONLY THE DEMUX THREAD TAKES IT
        @Override
        public FFDecoder.Seek consumeSeek() {
            final FFDecoder.Seek seek = this.seek;
            this.seek = null;
            return seek;
        }

        @Override public boolean seekPending() { return this.seek != null; }
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
