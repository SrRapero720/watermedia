package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.readers.WebPReader.FourCC;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.FrameReplay;
import org.watermedia.test.support.ImageBytes;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the WebP decoder, consolidating lossless, lossy, animated and container coverage.
 *
 * <p>Lossless fixtures are compared pixel-perfectly against {@code dwebp}-generated PAMs. Lossy fixtures use relaxed
 * similarity bounds because VP8 implementations legitimately differ in DCT rounding, quantization and loop-filter
 * behavior. Both decode through the no-arg overload and assert {@link PixelFormat#BGRA}, the native layout of every
 * WebP shape; animated fixtures are covered by the reset() replay, which {@code dwebp} has no reference for.
 */
@DisplayName("WebP decoder")
public class WebpTest {

    // LOSSY VP8 DECODE BOUNDS: DECODER VARIANTS ARE NORMAL
    private static final int LOSSY_MAX_DIFF_TOLERANCE = 100;
    private static final double LOSSY_MAX_DIFF_PERCENTAGE = 30.0;
    private static final int LOSSY_SMALL_THRESHOLD = 10;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @TestFactory
    @DisplayName("Lossless pixel match")
    Iterable<DynamicTest> testLossless() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSLESS_DIR)) {
            final String name = imageFile.getFileName().toString();
            tests.add(dynamicTest("LOSSLESS data match [" + name + "]", () -> {
                final PixelDiff diff = diff(imageFile, Fixtures.WEBP_LOSSLESS_DIR, 0);
                assertEquals(0, diff.maxDiff(), String.format(Locale.ROOT, "Pixel data mismatch for %s - max diff: %d, %.2f%% of values differ",
                        name, diff.maxDiff(), diff.percentAboveSmall()));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Lossy similarity")
    Iterable<DynamicTest> testLossy() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSY_DIR)) {
            final String name = imageFile.getFileName().toString();
            tests.add(dynamicTest("LOSSY similarity [" + name + "]", () -> {
                final PixelDiff diff = diff(imageFile, Fixtures.WEBP_LOSSY_DIR, LOSSY_SMALL_THRESHOLD);
                assertTrue(diff.maxDiff() <= LOSSY_MAX_DIFF_TOLERANCE, String.format(Locale.ROOT,
                        "Lossy %s has extreme pixel difference - max: %d (limit: %d), mean: %.2f, %.2f%% above threshold %d",
                        name, diff.maxDiff(), LOSSY_MAX_DIFF_TOLERANCE, diff.meanDiff(), diff.percentAboveSmall(), LOSSY_SMALL_THRESHOLD));
                assertTrue(diff.percentAboveSmall() <= LOSSY_MAX_DIFF_PERCENTAGE, String.format(Locale.ROOT,
                        "Lossy %s has too many differing pixels - %.2f%% above threshold %d (limit: %.1f%%), max diff: %d, mean diff: %.2f",
                        name, diff.percentAboveSmall(), LOSSY_SMALL_THRESHOLD, LOSSY_MAX_DIFF_PERCENTAGE, diff.maxDiff(), diff.meanDiff()));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        // EVERY ANIMATED FIXTURE PLUS ONE STATIC OF EACH CODEC: reset() MUST REPLAY ALL SHAPES
        final List<Path> files = new ArrayList<>(listWebp(Fixtures.WEBP_ANIMATED_DIR));
        files.add(Fixtures.WEBP_LOSSLESS);
        files.add(Fixtures.WEBP_LOSSY_DIR.resolve("1.webp"));
        for (final Path imageFile: files) {
            final String name = imageFile.getParent().getFileName() + "/" + imageFile.getFileName();
            tests.add(dynamicTest("RESET replay [" + name + "]", () -> {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(imageFile)))) {
                    final List<byte[]> first = FrameReplay.verify(reader, name);
                    assertEquals(reader.frameCount(), first.size(), "scan() and the decoder disagree on " + name);
                }
            }));
        }
        return tests;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // DECODES A STILL WITH THE NO-ARG OVERLOAD, CHECKS IT IS NATIVE TIGHTLY PACKED BGRA AND DIFFS IT AGAINST raw/<name>.pam
    private static PixelDiff diff(final Path imageFile, final Path dir, final int smallDiff) throws IOException {
        final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile));
        assertEquals(PixelFormat.BGRA, decoded.format(), imageFile + " decodes natively to BGRA");
        assertEquals(decoded.width() * decoded.height() * 4, decoded.frames()[0].remaining(), imageFile + " frame size");
        final Path pamPath = dir.resolve("raw").resolve(imageFile.getFileName().toString().replace(".webp", ".pam"));
        assertTrue(Files.exists(pamPath), "Reference PAM file not found: " + pamPath.toAbsolutePath() + ". Run dwebp to generate reference data");
        final PamImage reference = PamImage.read(pamPath);
        final byte[] actualRgba = PamImage.bgraToRgba(decoded.frames()[0], decoded.width(), decoded.height());
        assertEquals(reference.pixels().length, actualRgba.length, "Pixel data size mismatch for " + imageFile);
        return PixelDiff.compare(reference.pixels(), actualRgba, smallDiff);
    }

    // APPENDS ONE CHUNK TO AN EXTENDED FILE AND RECOUNTS THE RIFF SIZE; WITHOUT pad AN ODD BODY ENDS THE FILE UNPADDED
    private static byte[] append(final byte[] webp, final int fourCC, final byte[] body, final boolean pad) {
        final ByteBuffer out = ByteBuffer.allocate(webp.length + 8 + body.length + (pad ? body.length & 1 : 0)).order(ByteOrder.LITTLE_ENDIAN);
        out.put(webp).putInt(fourCC).putInt(body.length).put(body);
        out.putInt(4, out.capacity() - 8);
        return out.array();
    }

    // OFFSET OF THE FIRST CHUNK HEADER WITH THE GIVEN FOURCC, WALKING THE CHUNK LIST
    private static int indexOf(final byte[] webp, final int fourCC) {
        final ByteBuffer in = ByteBuffer.wrap(webp).order(ByteOrder.LITTLE_ENDIAN);
        int at = 12;
        while (in.getInt(at) != fourCC) at += 8 + ((in.getInt(at + 4) + 1) & ~1);
        return at;
    }

    // APPENDS AN EXIF CHUNK: A SIMPLE FILE IS FIRST WRAPPED INTO A VP8X CONTAINER, AN EXTENDED ONE JUST GAINS THE EXIF FLAG
    private static byte[] withExif(final byte[] webp, final byte[] exif, final int width, final int height) {
        final boolean extended = webp[12] == 'V' && webp[13] == 'P' && webp[14] == '8' && webp[15] == 'X';
        final byte[] chunks = Arrays.copyOfRange(webp, 12, webp.length);
        // THE VP8X FLAGS BYTE FOLLOWS ITS 8-BYTE CHUNK HEADER
        if (extended) chunks[8] |= 0x08;
        final byte[] tag = ImageBytes.webpChunk("EXIF", exif);
        return extended ? ImageBytes.riff(chunks, tag) : ImageBytes.riff(ImageBytes.webpChunk("VP8X", ImageBytes.vp8x(0x08, width, height)), chunks, tag);
    }

    private static List<Path> listWebp(final Path dir) {
        assertTrue(Files.exists(dir), "Test folder does not exist: " + dir);
        try (final Stream<Path> entries = Files.list(dir)) {
            final List<Path> images = entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".webp")).sorted().toList();
            assertFalse(images.isEmpty(), "Test folder is empty: " + dir);
            return images;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate WebP fixtures in " + dir, e);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Orientation")
    class Orientation {

        @Test
        @DisplayName("EXIF orientation 6 rotates static and animated pictures 90 degrees clockwise and swaps their size")
        void orientationRotates() throws IOException {
            // A LITTLE-ENDIAN TIFF WHOSE IFD0 HOLDS ONE ENTRY: ORIENTATION (0x0112), SHORT, COUNT 1, VALUE 6
            final byte[] exif = { 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0 };
            for (final Path fixture: List.of(Fixtures.WEBP_LOSSLESS, Fixtures.WEBP_ANIMATED)) {
                final byte[] plain = Fixtures.readAll(fixture);
                final ImageData upright = CodecsAPI.decode(plain, PixelFormat.BGRA);
                final int w = upright.width(), h = upright.height();
                final byte[] tagged = withExif(plain, exif, w, h);
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(tagged))) {
                    assertEquals(6, reader.metadata().value(CodecsAPI.METAKEY_ORIENTATION), fixture + " orientation key");
                    assertArrayEquals(exif, (byte[]) reader.metadata().value(CodecsAPI.METAKEY_EXIF), fixture + " EXIF payload");
                }
                final ImageData turned = CodecsAPI.decode(tagged, PixelFormat.BGRA);
                assertEquals(h, turned.width(), fixture + " rotated width");
                assertEquals(w, turned.height(), fixture + " rotated height");
                assertEquals(upright.frames().length, turned.frames().length, fixture + " frames");
                for (int f = 0; f < upright.frames().length; f++) {
                    final IntBuffer a = upright.frames()[f].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                    final IntBuffer b = turned.frames()[f].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                    // OUTPUT PIXEL (x, y) COMES FROM SOURCE PIXEL (y, h - 1 - x)
                    for (final int[] p: new int[][] { { 0, 0 }, { h - 1, 0 }, { 0, w - 1 }, { h / 2, w / 3 }, { h - 1, w - 1 } }) {
                        final int x = p[0], y = p[1];
                        assertEquals(a.get((h - 1 - x) * w + y), b.get(y * h + x), fixture + " frame " + f + " pixel " + x + "," + y);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Container and metadata")
    class Container {

        @Test
        @DisplayName("Metadata is parsed once and survives reset() replays unchanged")
        void metadataStableAcrossReplays() throws IOException {
            final byte[] xmp = ("<x:xmpmeta><rdf:RDF><rdf:Description><dc:creator><rdf:Seq><rdf:li>WaterMedia</rdf:li></rdf:Seq>"
                    + "</dc:creator><xmp:Label>animated</xmp:Label></rdf:Description></rdf:RDF></x:xmpmeta>").getBytes(StandardCharsets.UTF_8);
            final byte[] icc = { 1, 2, 3 };
            final byte[] tagged = append(append(Fixtures.readAll(Fixtures.WEBP_ANIMATED), FourCC.XMP, xmp, true), FourCC.ICCP, icc, true);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(tagged))) {
                final ImageMetadata before = reader.metadata();
                assertEquals(List.of("WaterMedia"), before.authors(), "metadata after the frames is known at construction");
                for (int pass = 0; pass < 3; pass++) {
                    while (reader.hasNext()) reader.next();
                    assertTrue(reader.reset());
                }
                final ImageMetadata after = reader.metadata();
                assertEquals(List.of("WaterMedia"), after.authors());
                assertEquals(List.of("animated"), after.comments());
                assertEquals(before.values().keySet(), after.values().keySet());
                assertArrayEquals(xmp, (byte[]) after.value(CodecsAPI.METAKEY_XMP));
                assertArrayEquals(icc, (byte[]) after.value(CodecsAPI.METAKEY_ICC_PROFILE));
            }
        }

        @Test
        @DisplayName("The ANIM loop count maps straight to the play count and reaches the metadata")
        void loopCount() throws IOException {
            final byte[] plain = Fixtures.readAll(Fixtures.WEBP_ANIMATED);
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(plain))) {
                assertEquals(0, reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
                assertEquals(ImageData.REPEAT_FOREVER, reader.repeat());
            }
            // ANIM BODY: BACKGROUND COLOR (4), THEN THE LITTLE-ENDIAN LOOP COUNT
            final int anim = indexOf(plain, FourCC.ANIM);
            final byte[] thrice = plain.clone();
            thrice[anim + 12] = 3;
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(thrice))) {
                assertEquals(3, reader.metadata().value(CodecsAPI.METAKEY_LOOP_COUNT));
                assertEquals(3, reader.repeat());
            }
        }

        @Test
        @DisplayName("A single frame is a still image whatever its duration or loop count declare")
        void singleFrameIsStill() throws IOException {
            // THE ANIMATED FIXTURE CUT AFTER ITS FIRST ANMF (70 MS, LOOPING FOREVER) NEXT TO A SIMPLE LOSSLESS STILL
            final byte[] plain = Fixtures.readAll(Fixtures.WEBP_ANIMATED);
            final int anmf = indexOf(plain, FourCC.ANMF);
            final int size = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getInt(anmf + 4);
            final byte[] single = Arrays.copyOf(plain, anmf + 8 + ((size + 1) & ~1));
            ByteBuffer.wrap(single).order(ByteOrder.LITTLE_ENDIAN).putInt(4, single.length - 8);
            for (final byte[] still: List.of(single, Fixtures.readAll(Fixtures.WEBP_LOSSLESS))) {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(still))) {
                    assertArrayEquals(new long[] { 0L }, reader.delays());
                    assertEquals(0L, reader.duration());
                    assertEquals(1, reader.repeat());
                    assertFalse(reader.variableFrameRate());
                    reader.next();
                    assertEquals(0L, reader.delay());
                    assertFalse(reader.hasNext());
                }
            }
        }

        @Test
        @DisplayName("Bytes past the RIFF size are not chunks")
        void trailingBytesIgnored() throws IOException {
            // AN EXTENDED FILE WALKS EVERY CHUNK TO ITS END, SO ONLY THE RIFF BOUND KEEPS THE JUNK OUT OF THE WALK
            final byte[] plain = Fixtures.readAll(Fixtures.WEBP_ANIMATED);
            final byte[] junk = Arrays.copyOf(plain, plain.length + 7);
            Arrays.fill(junk, plain.length, junk.length, (byte) 0x5A);
            final ImageData expected = CodecsAPI.decode(plain);
            final ImageData actual = CodecsAPI.decode(junk);
            assertTrue(expected.frames().length > 1);
            assertEquals(expected.frames().length, actual.frames().length);
            for (int f = 0; f < expected.frames().length; f++) assertEquals(expected.frames()[f], actual.frames()[f], "frame " + f);
        }

        @Test
        @DisplayName("A final odd-sized chunk may end the file without its padding byte")
        void unpaddedFinalChunk() throws IOException {
            // 13 BYTES: ODD, SO A PAD BYTE WOULD FOLLOW
            final byte[] xmp = "<x:xmpmeta/> ".getBytes(StandardCharsets.UTF_8);
            final byte[] tagged = append(Fixtures.readAll(Fixtures.WEBP_ANIMATED), FourCC.XMP, xmp, false);
            final ImageData image = CodecsAPI.decode(tagged);
            assertTrue(image.frames().length > 1);
            // A READER BUILT DIRECTLY OVER THE CHUNKS SEES THE SAME BUFFER CodecsAPI HANDS IT
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(tagged, 12, tagged.length - 12))) {
                assertArrayEquals(xmp, (byte[]) reader.metadata().value(CodecsAPI.METAKEY_XMP));
                assertEquals(image.frames().length, reader.readAll().frames().length);
            }
        }
    }
}
