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
 * Verifies the WebP decoder: lossless and lossy stills against {@code dwebp} PAM references, reset() replays of the
 * animated fixtures and one still per codec, EXIF orientation, and the container rules (metadata, loop count, still
 * animations, the RIFF bound and an unpadded final chunk).
 *
 * <p>Every still decodes through the no-arg overload into {@link PixelFormat#BGRA}, the native layout of every WebP
 * shape. Lossless stills match their references exactly. VP8 decoding itself is bit-exact (RFC 6386), so lossy stills
 * drift only in the YUV to RGB step: {@code dwebp} interpolates the 4:2:0 chroma, while the decoder repeats each
 * chroma sample over its 2x2 block. Animated fixtures have no {@code dwebp} reference and are covered by the replay.
 */
@DisplayName("WebP decoder")
public class WebpTest {
    // LOSSY BOUNDS, A LITTLE ABOVE THE WORST FIXTURE TODAY: MAX DIFF 95 AND MEAN 2.89 (0.webp),
    // 8.76% OF VALUES OFF BY MORE THAN 10 (5.webp)
    private static final int LOSSY_SMALL_DIFF = 10;
    private static final int LOSSY_MAX_DIFF = 100;
    private static final double LOSSY_MAX_MEAN_DIFF = 3.5;
    private static final double LOSSY_MAX_PERCENT = 10.0;

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // DECODES A STILL WITH THE NO-ARG OVERLOAD, CHECKS IT IS NATIVE TIGHTLY PACKED BGRA AND DIFFS IT AGAINST raw/<name>.pam
    private static PixelDiff diff(final Path imageFile, final Path dir, final int smallDiff) throws IOException {
        final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile));
        assertEquals(PixelFormat.BGRA, decoded.format(), imageFile + " decodes natively to BGRA");
        assertEquals(decoded.width() * decoded.height() * 4, decoded.frames()[0].remaining(), imageFile + " frame bytes");
        final Path pamPath = dir.resolve("raw").resolve(imageFile.getFileName().toString().replace(".webp", ".pam"));
        assertTrue(Files.exists(pamPath), "reference " + pamPath.toAbsolutePath() + " is missing: generate it with dwebp -pam");
        final PamImage reference = PamImage.read(pamPath);
        assertEquals(reference.width(), decoded.width(), imageFile + " width");
        assertEquals(reference.height(), decoded.height(), imageFile + " height");
        return reference.diff(decoded.frames()[0], smallDiff);
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
        assertTrue(Files.exists(dir), "fixture folder " + dir + " exists");
        try (final Stream<Path> entries = Files.list(dir)) {
            final List<Path> images = entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".webp")).sorted().toList();
            assertFalse(images.isEmpty(), "no WebP fixtures under " + dir);
            return images;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list the WebP fixtures in " + dir, e);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Fixture files")
    class Corpus {

        @TestFactory
        @DisplayName("Every lossless still equals its dwebp reference")
        Iterable<DynamicTest> lossless() {
            final List<DynamicTest> tests = new ArrayList<>();
            for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSLESS_DIR)) {
                final String name = imageFile.getFileName().toString();
                tests.add(dynamicTest("LOSSLESS data match [" + name + "]", () -> {
                    final PixelDiff diff = diff(imageFile, Fixtures.WEBP_LOSSLESS_DIR, 0);
                    assertEquals(0, diff.maxDiff(), String.format(Locale.ROOT, "%s differs from its reference: max diff %d, %.2f%% of values differ",
                            name, diff.maxDiff(), diff.percentAboveSmall()));
                }));
            }
            return tests;
        }

        @TestFactory
        @DisplayName("Every lossy still stays within the max, mean and share bounds of its dwebp reference")
        Iterable<DynamicTest> lossy() {
            final List<DynamicTest> tests = new ArrayList<>();
            for (final Path imageFile: listWebp(Fixtures.WEBP_LOSSY_DIR)) {
                final String name = imageFile.getFileName().toString();
                tests.add(dynamicTest("LOSSY similarity [" + name + "]", () -> {
                    final PixelDiff diff = diff(imageFile, Fixtures.WEBP_LOSSY_DIR, LOSSY_SMALL_DIFF);
                    final String summary = String.format(Locale.ROOT, "%s: max diff %d (limit %d), mean %.3f (limit %.1f), %.3f%% of values above %d (limit %.1f%%)",
                            name, diff.maxDiff(), LOSSY_MAX_DIFF, diff.meanDiff(), LOSSY_MAX_MEAN_DIFF, diff.percentAboveSmall(), LOSSY_SMALL_DIFF, LOSSY_MAX_PERCENT);
                    assertTrue(diff.maxDiff() <= LOSSY_MAX_DIFF, summary);
                    assertTrue(diff.meanDiff() <= LOSSY_MAX_MEAN_DIFF, summary);
                    assertTrue(diff.percentAboveSmall() <= LOSSY_MAX_PERCENT, summary);
                }));
            }
            return tests;
        }

        @TestFactory
        @DisplayName("Every animated fixture and one still per codec replay byte for byte through reset(), as many frames as scanned")
        Iterable<DynamicTest> resetReplays() {
            final List<DynamicTest> tests = new ArrayList<>();
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
    }

    @Nested
    @DisplayName("Orientation")
    class Orientation {

        @Test
        @DisplayName("EXIF orientation 6 rotates static and animated pictures 90 degrees clockwise and swaps their size")
        void orientationRotates() throws IOException {
            final byte[] exif = ImageBytes.exif(6);
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
                    // OUTPUT PIXEL (x, y) COMES FROM SOURCE PIXEL (y, h - 1 - x)
                    final int[] source = PamImage.argb(upright, f);
                    final int[] expected = new int[w * h];
                    for (int y = 0; y < w; y++)
                        for (int x = 0; x < h; x++) expected[y * h + x] = source[(h - 1 - x) * w + y];
                    assertArrayEquals(expected, PamImage.argb(turned, f), fixture + " frame " + f);
                }
            }
        }
    }

    @Nested
    @DisplayName("Container and metadata")
    class Container {

        @Test
        @DisplayName("Metadata after the frames is known at construction and survives reset() replays unchanged")
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
                    assertTrue(reader.reset(), "pass " + pass + " rewinds");
                }
                final ImageMetadata after = reader.metadata();
                assertEquals(List.of("WaterMedia"), after.authors());
                assertEquals(List.of("animated"), after.comments());
                assertEquals(before.values().keySet(), after.values().keySet(), "metadata keys");
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
                    assertFalse(reader.variableFrameRate(), "a still has one frame rate");
                    reader.next();
                    assertEquals(0L, reader.delay(), "the streamed delay agrees with the scan");
                    assertFalse(reader.hasNext(), "a still has one frame");
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
            assertTrue(expected.frames().length > 1, "the fixture is animated");
            assertEquals(expected.frames().length, actual.frames().length, "frames");
            for (int f = 0; f < expected.frames().length; f++) assertEquals(expected.frames()[f], actual.frames()[f], "frame " + f);
        }

        @Test
        @DisplayName("A final odd-sized chunk may end the file without its padding byte")
        void unpaddedFinalChunk() throws IOException {
            // 13 BYTES: ODD, SO A PAD BYTE WOULD FOLLOW
            final byte[] xmp = "<x:xmpmeta/> ".getBytes(StandardCharsets.UTF_8);
            final byte[] tagged = append(Fixtures.readAll(Fixtures.WEBP_ANIMATED), FourCC.XMP, xmp, false);
            final ImageData image = CodecsAPI.decode(tagged);
            assertTrue(image.frames().length > 1, "the fixture is animated");
            // A READER BUILT DIRECTLY OVER THE CHUNKS SEES THE SAME BUFFER CodecsAPI HANDS IT
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(tagged, 12, tagged.length - 12))) {
                assertArrayEquals(xmp, (byte[]) reader.metadata().value(CodecsAPI.METAKEY_XMP));
                assertEquals(image.frames().length, reader.readAll().frames().length, "frames");
            }
        }
    }
}
