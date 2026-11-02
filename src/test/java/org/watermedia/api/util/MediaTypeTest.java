package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies MIME-string to {@link MediaType} resolution, including subtitle subtypes
 * and null/empty fallbacks.
 */
@DisplayName("MediaType")
public class MediaTypeTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("of(mimeString)")
    class OfMimeTests {

        @Test
        @DisplayName("image/png -> IMAGE")
        void imagePng() {
            assertSame(MediaType.IMAGE, MediaType.of("image/png"));
        }

        @Test
        @DisplayName("video/mp4 -> VIDEO")
        void videoMp4() {
            assertSame(MediaType.VIDEO, MediaType.of("video/mp4"));
        }

        @Test
        @DisplayName("audio/mp3 -> AUDIO")
        void audioMp3() {
            assertSame(MediaType.AUDIO, MediaType.of("audio/mp3"));
        }

        @Test
        @DisplayName("text/vtt -> SUBTITLES")
        void textVtt() {
            assertSame(MediaType.SUBTITLES, MediaType.of("text/vtt"));
        }

        @Test
        @DisplayName("text/srt -> SUBTITLES")
        void textSrt() {
            assertSame(MediaType.SUBTITLES, MediaType.of("text/srt"));
        }

        @Test
        @DisplayName("text/x-subtitle -> SUBTITLES")
        void textSubtitleVariant() {
            assertSame(MediaType.SUBTITLES, MediaType.of("text/x-subtitle"));
        }

        @Test
        @DisplayName("text/html -> UNKNOWN")
        void textHtml() {
            assertSame(MediaType.UNKNOWN, MediaType.of("text/html"));
        }

        @Test
        @DisplayName("application/json -> UNKNOWN")
        void applicationJson() {
            assertSame(MediaType.UNKNOWN, MediaType.of("application/json"));
        }

        @Test
        @DisplayName("null -> UNKNOWN")
        void nullInput() {
            assertSame(MediaType.UNKNOWN, MediaType.of(null));
        }

        @Test
        @DisplayName("empty string -> UNKNOWN")
        void emptyInput() {
            assertSame(MediaType.UNKNOWN, MediaType.of(""));
        }

        @Test
        @DisplayName("uppercase primary type is normalized")
        void caseInsensitivePrimaryType() {
            assertSame(MediaType.VIDEO, MediaType.of("VIDEO/MP4"));
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
    class OfExtensionTests {

        @Test
        @DisplayName("single extension")
        void extensionSingle() {
            assertSame(MediaType.IMAGE, MediaType.ofExtension("/clips/thumbnail.webp"));
            assertSame(MediaType.VIDEO, MediaType.ofExtension("video.mp4"));
        }

        @Test
        @DisplayName("stacked extensions use the appended container")
        void extensionStacked() {
            assertSame(MediaType.VIDEO, MediaType.ofExtension("/v/video.m3u8.mp4"));
        }

        @Test
        @DisplayName("trailing non-media suffix falls back to the earlier media one")
        void extensionTrailingNonMedia() {
            assertSame(MediaType.VIDEO, MediaType.ofExtension("clip.mp4.download"));
        }

        @Test
        @DisplayName("dots in parent directories are ignored")
        void extensionDirectoryDots() {
            assertSame(MediaType.IMAGE, MediaType.ofExtension("/cdn.v1.2/path/image.png"));
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension("/cdn.v1.2/path/noext"));
        }

        @Test
        @DisplayName("no/unknown extension -> UNKNOWN")
        void extensionUnknown() {
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension("file.bin"));
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension("noextension"));
            assertSame(MediaType.UNKNOWN, MediaType.ofExtension(null));
        }
    }
}
