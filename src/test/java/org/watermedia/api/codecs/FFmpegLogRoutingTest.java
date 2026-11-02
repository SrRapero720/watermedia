package org.watermedia.api.codecs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;

import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.av_log;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that native FFmpeg log lines reach the project logger once complete, with their
 * severity mapped and their secrets redacted like any other project log event.
 */
@DisplayName("FFmpeg native log routing")
public class FFmpegLogRoutingTest {

    @BeforeAll
    static void boot() {
        MediaBootstrap.client();
    }

    @Test
    @Disabled("Native log routing is commented out in CodecsAPI.startFFmpeg until macOS and Linux are validated")
    @DisplayName("Fragments join into one redacted line per severity")
    void routesRedactedLines() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            // FORMAT STRINGS WITHOUT CONVERSIONS, SO THE VARIADIC CALL NEEDS NO ARGUMENTS
            av_log(null, AV_LOG_WARNING, "Opening 'https://user:secret@example.test/video?id=7' ");
            av_log(null, AV_LOG_WARNING, "for reading\n");
            av_log(null, AV_LOG_ERROR, "Native failure\n");
            final List<LogEvent> events = capture.events().stream()
                    .filter(event -> event.getMessage().getFormattedMessage().startsWith("FFmpeg: ")).toList();
            assertEquals(2, events.size(), () -> "Routed events: " + events);
            final String warning = events.get(0).getMessage().getFormattedMessage();
            assertEquals(Level.WARN, events.get(0).getLevel());
            assertTrue(warning.contains("Opening 'https://user:********@example.test/video?id=7'"), warning);
            assertTrue(warning.endsWith("for reading"), warning);
            assertFalse(warning.contains("secret"), warning);
            assertEquals(Level.ERROR, events.get(1).getLevel());
            assertEquals("FFmpeg: Native failure", events.get(1).getMessage().getFormattedMessage());
        }
    }
}
