package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.ImageBytes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies that {@link CodecsAPI#sniff(InputStream)} classifies streams from their leading bytes: every decodable
 * on-disk image fixture, animated ones included, sniffs as an image, and synthetic minimal headers cover the image,
 * video, audio and subtitle signatures that have no fixture, plus unknown and empty input.
 */
@DisplayName("MediaType sniffing")
public class MediaTypeSniffTest {

    @TestFactory
    @DisplayName("On-disk fixtures")
    Iterable<DynamicTest> sniffsFixtures() {
        // THE ANIMATED PNG AND WEBP ROWS GUARD THAT AN ANIMATION SNIFFS AS AN IMAGE, NEVER AS VIDEO
        record FileCase(String label, Path path) {}
        final List<DynamicTest> tests = new ArrayList<>();
        for (final FileCase c: List.of(
                new FileCase("static PNG", Fixtures.PNG_STATIC),
                new FileCase("animated PNG", Fixtures.PNG_ANIMATED),
                new FileCase("progressive grayscale JPEG", Fixtures.JPEG_GRAY),
                new FileCase("animated GIF", Fixtures.GIF_ANIMATED),
                new FileCase("lossless WebP", Fixtures.WEBP_LOSSLESS),
                new FileCase("animated WebP", Fixtures.WEBP_ANIMATED),
                new FileCase("binary PPM", Fixtures.NETPBM_PPM)
        )) {
            tests.add(dynamicTest(c.label() + " sniffs as IMAGE", () ->
                    assertEquals(MediaType.IMAGE, sniff(Fixtures.readAll(c.path())), c.path().toString())));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Synthetic signatures")
    Iterable<DynamicTest> sniffsSignatures() {
        record ByteCase(String name, byte[] data, MediaType expected) {}
        final List<DynamicTest> tests = new ArrayList<>();
        for (final ByteCase c: List.of(
                // IMAGES
                new ByteCase("bmp", ascii("BM"), MediaType.IMAGE),
                new ByteCase("tiff-le", bytes(0x49, 0x49, 0x2A, 0x00), MediaType.IMAGE),
                new ByteCase("ico", bytes(0x00, 0x00, 0x01, 0x00), MediaType.IMAGE),
                new ByteCase("heif-mp4", ftyp("mif1"), MediaType.IMAGE),
                new ByteCase("avif", ftyp("avif"), MediaType.IMAGE),
                new ByteCase("qoi", ascii("qoif"), MediaType.IMAGE),
                new ByteCase("svg-plain", ascii("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"></svg>"), MediaType.IMAGE),
                new ByteCase("svg-xml-prolog", ascii("<?xml version=\"1.0\"?>\n<!-- c -->\n<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"), MediaType.IMAGE),
                // VIDEO
                new ByteCase("mp4", ftyp("isom"), MediaType.VIDEO),
                new ByteCase("mov", ftyp("qt  "), MediaType.VIDEO),
                new ByteCase("3gp", ftyp("3gp4"), MediaType.VIDEO),
                new ByteCase("mkv-webm", bytes(0x1A, 0x45, 0xDF, 0xA3), MediaType.VIDEO),
                new ByteCase("flv", ascii("FLV"), MediaType.VIDEO),
                new ByteCase("avi", riff("AVI "), MediaType.VIDEO),
                new ByteCase("mpeg-ps", bytes(0x00, 0x00, 0x01, 0xBA), MediaType.VIDEO),
                // MPEG-TS: THE 0x47 SYNC BYTE AT 0 AND AT 188
                new ByteCase("ts", new ImageBytes().u8(0x47).fill(0, 187).u8(0x47).bytes(), MediaType.VIDEO),
                new ByteCase("ogg-theora", ogg("theora"), MediaType.VIDEO),
                // AUDIO
                new ByteCase("mp3-id3", ascii("ID3"), MediaType.AUDIO),
                new ByteCase("mp3-sync", bytes(0xFF, 0xFB), MediaType.AUDIO),
                new ByteCase("aac-adts", bytes(0xFF, 0xF1), MediaType.AUDIO),
                new ByteCase("flac", ascii("fLaC"), MediaType.AUDIO),
                new ByteCase("wav", riff("WAVE"), MediaType.AUDIO),
                new ByteCase("m4a", ftyp("M4A "), MediaType.AUDIO),
                new ByteCase("ogg-vorbis", ogg("vorbis"), MediaType.AUDIO),
                new ByteCase("ac3", bytes(0x0B, 0x77, 0x00, 0x00), MediaType.AUDIO),
                // SUBTITLES
                new ByteCase("webvtt", ascii("WEBVTT\n"), MediaType.SUBTITLES),
                new ByteCase("ass", ascii("[Script Info]\n"), MediaType.SUBTITLES),
                // NEGATIVE
                new ByteCase("random", bytes(0xDE, 0xAD, 0xBE, 0xEF), MediaType.UNKNOWN),
                new ByteCase("empty", new byte[0], MediaType.UNKNOWN)
        )) {
            tests.add(dynamicTest(c.name() + " signature sniffs as " + c.expected(), () ->
                    assertEquals(c.expected(), sniff(c.data()))));
        }
        return tests;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static MediaType sniff(final byte[] data) throws Exception {
        try (final ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            return CodecsAPI.sniff(in);
        }
    }

    private static byte[] ascii(final String s) { return new ImageBytes().ascii(s).bytes(); }
    private static byte[] bytes(final int... v) { return new ImageBytes().raw(v).bytes(); }

    // ISO BMFF: [size][ftyp][major brand]
    private static byte[] ftyp(final String brand) {
        return new ImageBytes().be32(0x18).ascii("ftyp").ascii(brand).bytes();
    }

    // RIFF: ["RIFF"][size][form type]
    private static byte[] riff(final String form) {
        return new ImageBytes().ascii("RIFF").le32(0).ascii(form).bytes();
    }

    // OGG FIRST PAGE OF 40 BYTES WITH THE CODEC ID NAME AT 29, WITHIN THE PROBE WINDOW
    private static byte[] ogg(final String codec) {
        return new ImageBytes().ascii("OggS").fill(0, 25).ascii(codec).fill(0, 11 - codec.length()).bytes();
    }
}
