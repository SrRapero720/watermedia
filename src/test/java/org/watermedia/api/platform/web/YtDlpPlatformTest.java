package org.watermedia.api.platform.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Track;
import org.watermedia.tools.JSONTool;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how {@link YtDlpPlatform} turns yt-dlp's audio formats into a source offline: one audio track per language,
 * the original first by {@code language_preference}, named after the display name YouTube puts in {@code format_note};
 * a single best track when no format names its language; and the original language on an audio-only source. The JSON
 * mirrors yt-dlp 2026.10.26 output for a dubbed and a single-language YouTube video.
 */
@DisplayName("yt-dlp audio tracks (offline)")
public class YtDlpPlatformTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static Source extract(final String formats) throws PlatformException {
        final JsonObject info = JSONTool.parse("{\"webpage_url\":\"https://www.youtube.com/watch?v=test\",\"title\":\"Test\",\"formats\":[" + formats + "]}", JsonObject.class);
        return new YouTubePlatform().extract(info).sources().get(0);
    }

    // A VIDEO-ONLY FORMAT, SO THE SOURCE IS A VIDEO WHOSE AUDIO COMES FROM THE TRACKS
    private static String video(final String id, final int height) {
        return "{\"format_id\":\"" + id + "\",\"url\":\"https://cdn.test/" + id + "\",\"protocol\":\"https\",\"vcodec\":\"avc1\",\"acodec\":\"none\","
                + "\"width\":" + height * 16 / 9 + ",\"height\":" + height + ",\"http_headers\":{\"User-Agent\":\"yt-dlp\"}}";
    }

    // AN AUDIO-ONLY FORMAT; A NULL language OR preference IS LEFT OUT, A NEGATIVE abr TOO (HLS RENDITIONS REPORT NONE)
    private static String audio(final String id, final String language, final Integer preference, final String note, final double abr) {
        return "{\"format_id\":\"" + id + "\",\"url\":\"https://cdn.test/" + id + "\",\"protocol\":\"https\",\"vcodec\":\"none\",\"acodec\":\"opus\""
                + (language == null ? "" : ",\"language\":\"" + language + "\"")
                + (preference == null ? "" : ",\"language_preference\":" + preference)
                + ",\"format_note\":\"" + note + "\"" + (abr < 0 ? "" : ",\"abr\":" + abr + ",\"tbr\":" + abr) + "}";
    }

    private static URI url(final String id) { return URI.create("https://cdn.test/" + id); }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Video sources")
    class Video {

        @Test
        @DisplayName("A dubbed video keeps one track per language, the original first, then the default, the others by bitrate and the descriptive last")
        void oneTrackPerLanguage() throws PlatformException {
            final Source source = extract(String.join(",",
                    video("137", 1080), video("136", 720),
                    audio("251-0", "ml", -1, "Malayalam, medium", 122.78),
                    audio("233-3", "es", null, "Español - dubbed", -1),
                    audio("251-8", "es", -1, "Spanish, medium", 128.34),
                    audio("140-9", "es", -1, "Spanish, medium", 129.48),
                    audio("251-30", "en-desc", -10, "English descriptive, medium", 130.0),
                    audio("251-23", "en-US", 10, "English (US) original (default), medium", 125.30),
                    audio("140-23", "en-US", 10, "English (US) original (default), medium", 129.47),
                    audio("251-5", "fr", 5, "French (default), low", 60.0)));
            assertEquals(MediaType.VIDEO, source.type());
            assertEquals(List.of(
                    new Track("English (US) original", "en-US", url("140-23")),
                    new Track("French", "fr", url("251-5")),
                    new Track("Spanish", "es", url("140-9")),
                    new Track("Malayalam", "ml", url("251-0")),
                    new Track("English descriptive", "en-desc", url("251-30"))), source.audioTracks());
        }

        @Test
        @DisplayName("Without languages the single best audio stays the only track, unnamed, as a quality note names nothing")
        void singleLanguage() throws PlatformException {
            final Source source = extract(String.join(",",
                    video("137", 1080),
                    audio("233", null, null, "Default, low", -1),
                    audio("140", null, -1, "medium", 129.80),
                    audio("251-drc", null, -1, "medium, DRC", 107.43)));
            assertEquals(List.of(new Track(null, null, url("140"))), source.audioTracks());
        }

        @Test
        @DisplayName("Once formats name their language, one that names none is not a track of its own")
        void unnamedFormatJoinsNoTrack() throws PlatformException {
            final Source source = extract(String.join(",",
                    video("137", 1080),
                    audio("233", null, null, "Default, low", -1),
                    audio("140", "en", -1, "English, medium", 129.80)));
            assertEquals(List.of(new Track("English", "en", url("140"))), source.audioTracks());
        }
    }

    @Nested
    @DisplayName("Audio-only sources")
    class AudioOnly {

        @Test
        @DisplayName("An audio-only source plays the original language over a dub with a higher bitrate")
        void originalLanguage() throws PlatformException {
            final Source source = extract(String.join(",",
                    audio("140-9", "es", -1, "Spanish, medium", 140.0),
                    audio("140-23", "en-US", 10, "English (US) original (default), medium", 129.47)));
            assertEquals(MediaType.AUDIO, source.type());
            assertEquals(url("140-23"), source.qualities().get(0).uri());
            assertEquals(List.of(), source.audioTracks());
        }
    }
}
