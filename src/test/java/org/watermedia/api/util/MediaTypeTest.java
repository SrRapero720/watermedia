package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies {@link MediaType} classification: {@code of} by the essence of a MIME type (primary types, subtitle
 * subtypes, the {@code application/*} streaming and SubRip types, parameters, whitespace, null and empty text) and
 * {@code ofExtension} by the extensions of a file name, scanned right to left.
 */
@DisplayName("MediaType")
public class MediaTypeTest {

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("of(mime)")
    class OfMime {

        // AN EMPTY CELL IS NULL AND '' IS THE EMPTY STRING
        @ParameterizedTest(name = "{displayName}: {0} is {1}")
        @DisplayName("of classifies a MIME type by its primary type and its subtitle subtypes, in any case")
        @CsvSource({
                "image/png,        IMAGE",
                "video/mp4,        VIDEO",
                "VIDEO/MP4,        VIDEO",
                "audio/mp3,        AUDIO",
                "text/vtt,         SUBTITLES",
                "text/srt,         SUBTITLES",
                "text/x-subtitle,  SUBTITLES",
                "text/html,        UNKNOWN",
                "application/json, UNKNOWN",
                ",                 UNKNOWN",
                "'',               UNKNOWN"
        })
        void classifies(final String mime, final MediaType expected) {
            assertSame(expected, MediaType.of(mime));
        }

        @Test
        @DisplayName("parameters and surrounding whitespace are ignored")
        void parametersIgnored() {
            assertSame(MediaType.SUBTITLES, MediaType.of("text/vtt; charset=utf-8"));
            assertSame(MediaType.VIDEO, MediaType.of("application/vnd.apple.mpegurl;charset=UTF-8"));
            assertSame(MediaType.SUBTITLES, MediaType.of("  application/x-subrip  "));
            assertSame(MediaType.IMAGE, MediaType.of(" image / png ; q=1"));
            assertSame(MediaType.UNKNOWN, MediaType.of(" ; charset=utf-8"));
        }
    }

    @Nested
    @DisplayName("ofExtension(path)")
    class OfExtension {

        @ParameterizedTest(name = "{displayName}: {0} is {1}")
        @DisplayName("ofExtension classifies the last media extension of the file name")
        @CsvSource({
                "/clips/thumbnail.webp, IMAGE",
                "video.mp4,             VIDEO",
                "clip.mp4.download,     VIDEO",
                "file.bin,              UNKNOWN",
                "noextension,           UNKNOWN",
                ",                      UNKNOWN"
        })
        void classifies(final String path, final MediaType expected) {
            assertSame(expected, MediaType.ofExtension(path));
        }

        @Test
        @DisplayName("stacked media extensions resolve to the rightmost one")
        void extensionStacked() {
            assertSame(MediaType.VIDEO, MediaType.ofExtension("/v/video.m3u8.mp4"));
            // TWO KINDS STACKED: A LEFT-TO-RIGHT SCAN WOULD PICK THE FIRST ONE
            assertSame(MediaType.VIDEO, MediaType.ofExtension("cover.png.mp4"));
            assertSame(MediaType.IMAGE, MediaType.ofExtension("clip.mp4.png"));
        }

        @Test
        @DisplayName("dots in parent directories never count as extensions")
        void extensionDirectoryDots() {
            assertSame(MediaType.IMAGE, MediaType.ofExtension("/cdn.v1.2/path/image.png"));
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension("/cdn.v1.2/path/noext"));
            // WITHOUT THE CUT AT THE LAST SLASH, THE DIRECTORY WOULD LEND ITS mp4 PIECE
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension("/media.mp4.d/noext"));
        }
    }
}
