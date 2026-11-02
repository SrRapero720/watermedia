package org.watermedia.api.util;

import org.watermedia.api.player.MRL;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Media types supported by {@link MRL.Source}. */
public enum MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    SUBTITLES,
    UNKNOWN;

    // EXTENSION TO MIME FOR EVERY FORMAT THE CODECS AND FFMPEG CONSUME; NetRequest.contentTypeOf ASKS IT BEFORE THE
    // JDK'S content-types.properties, WHICH MISSES MOST OF THEM
    private static final Map<String, String> MIMES = table(new String[] {
            // IMAGE
            "png",  "image/png",
            "apng", "image/apng",
            "jpg",  "image/jpeg",
            "jpeg", "image/jpeg",
            "jfif", "image/jpeg",
            "gif",  "image/gif",
            "webp", "image/webp",
            "svg",  "image/svg+xml",
            "pbm",  "image/x-portable-bitmap",
            "pgm",  "image/x-portable-graymap",
            "ppm",  "image/x-portable-pixmap",
            "pam",  "image/x-portable-arbitrarymap",
            "bmp",  "image/bmp",
            "tif",  "image/tiff",
            "tiff", "image/tiff",
            "ico",  "image/x-icon",
            "avif", "image/avif",
            "heic", "image/heic",
            "heif", "image/heif",

            // VIDEO
            "mp4",  "video/mp4",
            "m4v",  "video/x-m4v",
            "mkv",  "video/x-matroska",
            "webm", "video/webm",
            "mov",  "video/quicktime",
            "avi",  "video/x-msvideo",
            "wmv",  "video/x-ms-wmv",
            "flv",  "video/x-flv",
            "f4v",  "video/x-f4v",
            "ts",   "video/mp2t",
            "mts",  "video/mp2t",
            "m2ts", "video/mp2t",
            "mpg",  "video/mpeg",
            "mpeg", "video/mpeg",
            "3gp",  "video/3gpp",
            "3g2",  "video/3gpp2",
            "ogv",  "video/ogg",
            "asf",  "video/x-ms-asf",
            "vob",  "video/dvd",
            "rm",   "application/vnd.rn-realmedia",
            "rmvb", "application/vnd.rn-realmedia-vbr",
            "m3u",  "application/vnd.apple.mpegurl",
            "m3u8", "application/vnd.apple.mpegurl",
            "mpd",  "application/dash+xml",

            // AUDIO
            "mp3",  "audio/mpeg",
            "aac",  "audio/aac",
            "m4a",  "audio/mp4",
            "ogg",  "audio/ogg",
            "oga",  "audio/ogg",
            "opus", "audio/opus",
            "flac", "audio/flac",
            "wav",  "audio/wav",
            "weba", "audio/webm",
            "wma",  "audio/x-ms-wma",
            "ac3",  "audio/ac3",
            "dts",  "audio/vnd.dts",
            "amr",  "audio/amr",
            "aiff", "audio/aiff",
            "aif",  "audio/aiff",
            "au",   "audio/basic",

            // SUBTITLES
            "vtt",  "text/vtt",
            "srt",  "application/x-subrip",
            "ass",  "text/x-ssa",
            "ssa",  "text/x-ssa",
            "sub",  "text/x-microdvd",
    });

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Classifies a MIME type by its essence, ignoring parameters and whitespace, including the HLS, DASH, RealMedia and SubRip {@code application/*} types; {@link #UNKNOWN} otherwise. */
    public static MediaType of(final String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) return UNKNOWN;

        // ONLY THE type/subtype ESSENCE CLASSIFIES; PARAMETERS NEVER CHANGE THE MEDIA KIND
        final String essence = mimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        final int slash = essence.indexOf('/');
        final String subtype = slash < 0 ? "" : essence.substring(slash + 1).strip();
        return switch (slash < 0 ? essence : essence.substring(0, slash).strip()) {
            case "image" -> IMAGE;
            case "video" -> VIDEO;
            case "audio" -> AUDIO;
            case "text" -> (subtype.equals("vtt") || subtype.equals("srt") || subtype.equals("x-ssa")
                    || subtype.equals("x-microdvd") || subtype.contains("subtitle")) ? SUBTITLES : UNKNOWN;
            case "application" -> switch (subtype) {
                case "vnd.apple.mpegurl", "x-mpegurl", "dash+xml",
                     "vnd.rn-realmedia", "vnd.rn-realmedia-vbr" -> VIDEO;
                case "x-subrip" -> SUBTITLES;
                default -> UNKNOWN;
            };
            default -> UNKNOWN;
        };
    }

    /** Returns the MIME type of a bare file extension (no dot, any case), or null when unmapped. */
    public static String mime(final String extension) {
        return extension == null ? null : MIMES.get(extension.toLowerCase(Locale.ROOT));
    }

    /** Classifies a path or URL by its file name's extensions, scanned right to left, so {@code video.m3u8.mp4} and {@code clip.mp4.download} resolve; the fallback for vague server types. */
    public static MediaType ofExtension(final String path) {
        if (path == null) return UNKNOWN;
        final String name = path.substring(path.lastIndexOf('/') + 1);
        final String[] parts = name.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            final MediaType type = of(mime(parts[i]));
            if (type != UNKNOWN) return type;
        }
        return UNKNOWN;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // PAIRS EACH EXTENSION WITH ITS MIME TYPE FROM A FLAT TABLE
    private static Map<String, String> table(final String[] keyValue) {
        final Map<String, String> map = new HashMap<>(keyValue.length / 2);
        for (int i = 0; i < keyValue.length; i += 2) map.put(keyValue[i], keyValue[i + 1]);
        return Map.copyOf(map);
    }
}
