package org.watermedia.api.codecs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_INFO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.av_log;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

/**
 * Verifies that native FFmpeg log lines reach the WaterMedia logger under the FFmpeg marker once complete, with their
 * severity mapped and their secrets redacted, from any number of threads at once and from FFmpeg's own decoder threads,
 * and that a JVM which exits while those threads still log ends cleanly.
 */
@DisplayName("FFmpeg native log routing")
public class FFmpegLogRoutingTest {
    private static final long ROUTE_TIMEOUT_MS = 5_000L;
    private static final long PLAY_TIMEOUT_MS = 15_000L;
    private static final int THREADS = 8;
    private static final int LINES = 250;
    private static final String MARKER = "FFmpeg";

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // CHILD-JVM PROBE: A DECODER WHOSE THREADS LOG EVERY FRAME, AND AN EXIT IN THE MIDDLE OF IT
    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].equals("exit")) throw new IllegalArgumentException("Unknown FFmpeg log probe");
        if (!MediaBootstrap.ffmpegAvailable()) throw new AssertionError("FFmpeg did not load");
        WaterMediaConfig.codecs.ffmpeg.hardwareAccel = false;
        final MediaPlayer player = play(Path.of("broken.mkv").toAbsolutePath());
        // THE LOG IS FLOWING FROM THE DECODER WHEN THE JVM GOES DOWN
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            if (!PlayerWait.awaitCondition(() -> routed(capture, event -> event.getLevel() == Level.ERROR).size() > 3, PLAY_TIMEOUT_MS))
                throw new AssertionError("The broken video logged nothing, status " + player.status());
        }
        System.out.println("PROBE_OK exit");
        System.out.flush();
        System.exit(0);
    }

    @BeforeAll
    static void boot() {
        MediaBootstrap.client();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE EVENTS THE FFmpeg MARKER CARRIES THAT MATCH filter
    private static List<LogEvent> routed(final LogCapture capture, final Predicate<LogEvent> filter) {
        final List<LogEvent> events = new ArrayList<>();
        for (final LogEvent event: capture.events())
            if (event.getMarker() != null && event.getMarker().getName().equals(MARKER) && filter.test(event)) events.add(event);
        return events;
    }

    // AN H.264 CLIP WHOSE BYTES PAST ITS HEADERS ARE GARBLED EVERY FEW KIB, SO ITS DECODER THREADS REPORT ERRORS ON EVERY FRAME
    private static Path broken(final Path directory) throws Exception {
        final Path file = directory.resolve("broken.mkv");
        Fixtures.clip(file, Container.MKV, VideoCodec.H264, null, 320, 240, 25, 20);
        final byte[] bytes = Files.readAllBytes(file);
        for (int i = 4_096; i < bytes.length - 512; i += 2_048) for (int j = 0; j < 48; j++) bytes[i + j] ^= (byte) 0x5A;
        Files.write(file, bytes);
        return file;
    }

    // A VIDEO-ONLY PLAYER ON THE FILE, ALREADY STARTED
    private static MediaPlayer play(final Path file) {
        final MRL mrl = PlayerAPI.mrl(file.toUri());
        if (!mrl.await(PLAY_TIMEOUT_MS) || mrl.status() != MRL.Status.LOADED) throw new AssertionError("The broken video did not resolve: " + mrl.status());
        final MediaPlayer player = PlayerAPI.player(mrl, 0, PlayerAPI::headlessEngine, () -> null);
        player.start();
        return player;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Lines")
    class Lines {
        @BeforeEach
        void natives() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        }

        @Test
        @DisplayName("Fragments join into one redacted line per severity under the FFmpeg marker, and INFO lines go to DEBUG")
        void routesRedactedLines() {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                // FORMAT STRINGS WITHOUT CONVERSIONS, SO THE VARIADIC CALL NEEDS NO ARGUMENTS
                av_log(null, AV_LOG_WARNING, "Opening 'https://user:secret@example.test/video?id=7' ");
                av_log(null, AV_LOG_WARNING, "for reading\n");
                av_log(null, AV_LOG_ERROR, "Native failure\n");
                av_log(null, AV_LOG_INFO, "Native detail\n");
                // ONLY THESE LINES: A DECODER LEFT BY ANOTHER CASE MAY STILL LOG
                final Predicate<LogEvent> ours = event -> event.getMessage().getFormattedMessage().matches("Opening 'https://user.*|Native failure|Native detail");
                assertTrue(PlayerWait.awaitCondition(() -> routed(capture, ours).size() >= 3, ROUTE_TIMEOUT_MS), () -> "Routed events: " + routed(capture, event -> true));
                final List<LogEvent> events = routed(capture, ours);
                assertEquals(3, events.size(), () -> "Routed events: " + events);
                final String warning = events.get(0).getMessage().getFormattedMessage();
                assertEquals(Level.WARN, events.get(0).getLevel());
                assertTrue(warning.contains("Opening 'https://user:********@example.test/video?id=7'"), warning);
                assertTrue(warning.endsWith("for reading"), warning);
                assertFalse(warning.contains("secret"), warning);
                assertEquals(Level.ERROR, events.get(1).getLevel());
                assertEquals("Native failure", events.get(1).getMessage().getFormattedMessage());
                assertEquals(Level.DEBUG, events.get(2).getLevel(), "FFmpeg's INFO lines name every URL it opens, so they stay out of the main log");
                assertEquals("Native detail", events.get(2).getMessage().getFormattedMessage());
            }
        }

        @Test
        @DisplayName("Lines built in fragments by many threads at once each arrive whole")
        void concurrentLinesArriveWhole() throws Exception {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                final List<Thread> threads = new ArrayList<>();
                for (int t = 0; t < THREADS; t++) {
                    final String name = "thread" + t + " ";
                    final Thread thread = new Thread(() -> {
                        // THREE FRAGMENTS PER LINE, AS FFMPEG BUILDS ITS LINES; A CALL WITHOUT CONVERSIONS NEEDS NO ARGUMENTS
                        for (int i = 0; i < LINES; i++) {
                            av_log(null, AV_LOG_WARNING, "concurrent ");
                            av_log(null, AV_LOG_WARNING, name);
                            av_log(null, AV_LOG_WARNING, "line\n");
                        }
                    }, "FFmpegLogRoutingTest-" + t);
                    threads.add(thread);
                    thread.start();
                }
                for (final Thread thread: threads) thread.join(ROUTE_TIMEOUT_MS);
                final Predicate<LogEvent> concurrent = event -> event.getMessage().getFormattedMessage().startsWith("concurrent ");
                assertTrue(PlayerWait.awaitCondition(() -> routed(capture, concurrent).size() >= THREADS * LINES, ROUTE_TIMEOUT_MS),
                        () -> routed(capture, concurrent).size() + " of " + THREADS * LINES + " lines arrived");
                for (final LogEvent event: routed(capture, concurrent))
                    assertTrue(event.getMessage().getFormattedMessage().matches("concurrent thread\\d+ line"), () -> "A line arrived mixed: " + event.getMessage().getFormattedMessage());
            }
        }
    }

    @Nested
    @DisplayName("Decoder threads")
    class DecoderThreads {
        private boolean hardware; // THE PROCESS-WIDE SWITCH AS THE CASE FOUND IT

        @BeforeEach
        void configure() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            // SOFTWARE DECODING RUNS ON FFMPEG'S OWN FRAME THREADS, WHICH ARE THE ONES THAT LOG
            this.hardware = WaterMediaConfig.codecs.ffmpeg.hardwareAccel;
            WaterMediaConfig.codecs.ffmpeg.hardwareAccel = false;
        }

        @AfterEach
        void restore() {
            WaterMediaConfig.codecs.ffmpeg.hardwareAccel = this.hardware;
        }

        @Test
        @DisplayName("A broken video decoded on FFmpeg's own threads logs its errors through the routing")
        void decoderThreadsLog(@TempDir final Path directory) throws Exception {
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                final MediaPlayer player = play(broken(directory));
                try {
                    assertTrue(PlayerWait.awaitCondition(() -> routed(capture, event -> event.getLevel() == Level.ERROR).size() > 3, PLAY_TIMEOUT_MS),
                            () -> "The broken video must log its decoding errors, status " + player.status());
                } finally {
                    player.release();
                }
            }
        }

        @Test
        @DisplayName("A JVM that exits while FFmpeg's decoder threads still log ends cleanly")
        void exitWhileLogging(@TempDir final Path directory) throws Exception {
            broken(directory);
            runProbe(FFmpegLogRoutingTest.class, directory, "exit", false);
            try (final var files = Files.list(directory)) {
                assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith("hs_err")), "The exit must leave no JVM crash report");
            }
        }
    }
}
