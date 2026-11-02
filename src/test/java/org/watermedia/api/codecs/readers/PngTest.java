package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageMetadata;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.watermedia.test.support.ImageBytes.deflate;
import static org.watermedia.test.support.ImageBytes.ihdr;
import static org.watermedia.test.support.ImageBytes.png;
import static org.watermedia.test.support.ImageBytes.pngChunk;

/**
 * Verifies the PNG decoder against the fixtures and the reader contract with synthetic streams.
 *
 * <p>Every PNG fixture under {@code src/test/resources/png} decodes into tightly packed BGRA frames whose first frame
 * matches a pre-generated PAM reference under {@code png/raw} exactly (PNG is lossless), and replays byte for byte
 * through {@link ImageReader#reset()}.
 */
@DisplayName("PNG decoder")
public class PngTest {

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @TestFactory
    @DisplayName("Decode and pixel match")
    Iterable<DynamicTest> testPNG() {
        final List<DynamicTest> tests = new ArrayList<>();
        for (final Path imageFile: fixtures()) {
            final String name = imageFile.getFileName().toString();
            tests.add(dynamicTest("PNG decode and match [" + name + "]", () -> {
                final ImageData decoded = CodecsAPI.decode(Fixtures.readAll(imageFile));
                assertEquals(PixelFormat.BGRA, decoded.format(), name + " decodes natively to BGRA");
                for (int i = 0; i < decoded.frames().length; i++)
                    assertEquals(decoded.width() * decoded.height() * 4, decoded.frames()[i].remaining(), "Frame " + i + " size of " + name);

                final Path pamPath = Fixtures.PNG_DIR.resolve("raw").resolve(name.replace(".png", ".pam"));
                assertTrue(Files.exists(pamPath), "Reference PAM file not found: " + pamPath.toAbsolutePath()
                        + " - generate it with src/test/tools/GeneratePngReference.java");
                final PamImage reference = PamImage.read(pamPath);
                final byte[] actualRgba = PamImage.bgraToRgba(decoded.frames()[0], decoded.width(), decoded.height());
                assertEquals(reference.pixels().length, actualRgba.length, "Pixel data size mismatch for " + name);
                final PixelDiff diff = PixelDiff.compare(reference.pixels(), actualRgba, 0);
                assertEquals(0, diff.maxDiff(), String.format(Locale.ROOT, "Pixel data mismatch for %s - max diff: %d, %d values differ (%.2f%%)",
                        name, diff.maxDiff(), diff.valuesAboveSmall(), diff.percentAboveSmall()));
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("reset() replay")
    Iterable<DynamicTest> testPNGReset() {
        final List<DynamicTest> tests = new ArrayList<>();
        // COVERS BOTH STATIC PNG AND APNG: reset() MUST REPLAY EITHER ONE IDENTICALLY
        for (final Path imageFile: fixtures()) {
            final String name = imageFile.getFileName().toString();
            tests.add(dynamicTest("PNG reset replay [" + name + "]", () -> {
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(imageFile)))) {
                    FrameReplay.verify(reader, name);
                }
            }));
        }
        return tests;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // fcTL FOR A 1x1 FRAME AT THE ORIGIN WITH A 1/10 S DELAY, DISPOSE NONE AND BLEND SOURCE
    private static byte[] fctl(final int sequence) {
        return new ImageBytes().be32(sequence).be32(1).be32(1).be32(0).be32(0).be16(1).be16(10).u8(0).u8(0).bytes();
    }

    // fdAT: THE SEQUENCE NUMBER, THEN THE DEFLATED FILTERED SCANLINES
    private static byte[] fdat(final int sequence, final int... scanlines) {
        return pngChunk("fdAT", new ImageBytes().be32(sequence).raw(deflate(new ImageBytes().raw(scanlines).bytes(), 9)).bytes());
    }

    // IDAT CARRYING THE DEFLATED FILTERED SCANLINES
    private static byte[] idat(final int... scanlines) {
        return pngChunk("IDAT", deflate(new ImageBytes().raw(scanlines).bytes(), 9));
    }

    private static byte[] latin1(final String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static int[] pixels(final ImageData image, final int frame) {
        final int[] pixels = new int[image.width() * image.height()];
        image.frames()[frame].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
    }

    private static List<Path> fixtures() {
        try (final Stream<Path> entries = Files.list(Fixtures.PNG_DIR)) {
            final List<Path> images = entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
            assertFalse(images.isEmpty(), "Test folder is empty: " + Fixtures.PNG_DIR);
            return images;
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to enumerate PNG fixtures", e);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Animation")
    class Animation {

        @Test
        @DisplayName("An APNG default image ahead of the first fcTL is not a frame")
        void defaultImageIsNotAFrame() throws IOException {
            // 1x1 RGBA: A RED DEFAULT IMAGE, THEN TWO ANIMATION FRAMES, GREEN AND BLUE, OF 100 MS EACH
            final ImageData image = CodecsAPI.decode(png(ihdr(1, 1, 8, 6),
                    pngChunk("acTL", new ImageBytes().be32(2).be32(0).bytes()),
                    idat(0, 255, 0, 0, 255),
                    pngChunk("fcTL", fctl(0)), fdat(1, 0, 0, 255, 0, 255),
                    pngChunk("fcTL", fctl(2)), fdat(3, 0, 0, 0, 255, 255)));
            assertEquals(2, image.frames().length, "the default image is not part of the animation");
            assertArrayEquals(new long[] { 100L, 100L }, image.delays());
            assertEquals(ImageData.REPEAT_FOREVER, image.repeat());
            assertEquals(0xFF00FF00, pixels(image, 0)[0], "the first frame is the green animation frame, not the red default image");
            assertEquals(0xFF0000FF, pixels(image, 1)[0]);
        }

        @Test
        @DisplayName("A one-frame animation is a still image whatever its fcTL delay or loop count declare")
        void singleFrameIsStill() throws IOException {
            final byte[] bytes = png(ihdr(1, 1, 8, 6),
                    pngChunk("acTL", new ImageBytes().be32(1).be32(3).bytes()),
                    pngChunk("fcTL", fctl(0)), idat(0, 255, 0, 0, 255));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes))) {
                assertArrayEquals(new long[] { 0L }, reader.delays());
                assertEquals(0L, reader.duration());
                assertEquals(1, reader.repeat());
                reader.next();
                assertEquals(0L, reader.delay(), "the streamed delay agrees with the scan");
            }
            assertArrayEquals(new long[] { 0L }, CodecsAPI.decode(bytes).delays(), "readAll() agrees with the scan");
        }

        @Test
        @DisplayName("A one-frame animation still skips its default image and compressed text obeys a byte budget")
        void singleFrameDefaultImageAndTextBudget() throws IOException {
            // THREE zTXt CHUNKS OF 2 MiB EACH: THE 4 MiB TEXT BUDGET KEEPS TWO AND SKIPS THE THIRD WITHOUT FAILING
            final byte[] ztxt = new ImageBytes().ascii("Comment").u8(0).u8(0).raw(deflate(new byte[2 << 20], 9)).bytes();
            final byte[] bytes = png(ihdr(1, 1, 8, 6),
                    pngChunk("acTL", new ImageBytes().be32(1).be32(0).bytes()),
                    pngChunk("zTXt", ztxt), pngChunk("zTXt", ztxt), pngChunk("zTXt", ztxt),
                    idat(0, 255, 0, 0, 255),
                    pngChunk("fcTL", fctl(0)), fdat(1, 0, 0, 255, 0, 255));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes))) {
                assertEquals(1, reader.frameCount());
                final ImageData image = reader.readAll();
                assertEquals(1, image.frames().length, "the default image is not a frame even with one fcTL");
                assertEquals(0xFF00FF00, pixels(image, 0)[0]);
                final Map<?, ?> texts = (Map<?, ?>) reader.metadata().value(CodecsAPI.PNG_METAKEY_COMPRESSED_TEXT);
                assertEquals(2, ((List<?>) texts.get("Comment")).size(), "the third 2 MiB text exceeds the budget and is skipped");
            }
        }
    }

    @Nested
    @DisplayName("Metadata and chunks")
    class MetadataAndChunks {

        @Test
        @DisplayName("tEXt entries are normalized into the title and authors and kept in the generic map")
        void textNormalization() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(png(ihdr(1, 1, 8, 0),
                    pngChunk("tEXt", latin1("Title\0WaterMedia")), pngChunk("tEXt", latin1("Author\0J-RAP")), idat(0, 0))))) {
                final ImageMetadata metadata = reader.metadata();
                assertEquals("WaterMedia", metadata.title());
                assertEquals(List.of("J-RAP"), metadata.authors());
                assertNull(metadata.description());
                assertInstanceOf(Map.class, metadata.value(CodecsAPI.PNG_METAKEY_TEXT));
            }
        }

        @Test
        @DisplayName("Metadata met between frames is stored once, however many replays")
        void metadataIsStableAcrossReplays() throws IOException {
            final byte[] bytes = png(ihdr(1, 1, 8, 6),
                    pngChunk("acTL", new ImageBytes().be32(2).be32(1).bytes()),
                    pngChunk("fcTL", fctl(0)), idat(0, 255, 0, 0, 255),
                    pngChunk("tEXt", latin1("Comment\0between frames")),
                    pngChunk("fcTL", fctl(1)), fdat(2, 0, 0, 255, 0, 255));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes))) {
                final ImageMetadata early = reader.metadata();
                for (int pass = 0; pass < 3; pass++) {
                    while (reader.hasNext()) reader.next();
                    assertEquals(List.of("between frames"), reader.metadata().comments(), "pass " + pass);
                    assertTrue(reader.reset());
                }
                assertTrue(early.comments().isEmpty(), "a snapshot never changes under the consumer");
                assertEquals(1, reader.repeat(), "one declared play");
                assertThrows(UnsupportedOperationException.class, () -> reader.metadata().comment("x"), "snapshots are read-only");
            }
        }

        @Test
        @DisplayName("Malformed ancillary chunks are skipped and an unknown critical chunk is unsupported")
        void ancillaryPolicy() throws IOException {
            final byte[] bytes = png(ihdr(1, 1, 8, 6),
                    pngChunk("tEXt", new byte[] { 0, 'x' }), // EMPTY KEYWORD
                    pngChunk("gAMA", new byte[] { 0, 1, (byte) 0x86, (byte) 0xA0 }), // 100000 = GAMMA 1.0
                    pngChunk("tIME", new byte[] { 7, (byte) 0xE9, 10, 18, 12, 30, 45 }),
                    pngChunk("sRGB", new byte[] { 1 }),
                    idat(0, 255, 0, 0, 255));
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes))) {
                final ImageMetadata metadata = reader.metadata();
                assertNull(metadata.value(CodecsAPI.PNG_METAKEY_TEXT), "the malformed tEXt is skipped");
                assertEquals(1.0, metadata.value(CodecsAPI.PNG_METAKEY_GAMMA));
                assertEquals(LocalDateTime.of(2025, 10, 18, 12, 30, 45), metadata.value(CodecsAPI.PNG_METAKEY_LAST_MODIFIED_TIME));
                assertEquals(1, metadata.value(CodecsAPI.PNG_METAKEY_SRGB));
                assertTrue(reader.hasNext(), "a skipped chunk never fails the decode");
            }

            final ByteBuffer source = ByteBuffer.wrap(png(ihdr(1, 1, 8, 6), pngChunk("ZZZZ", new byte[] { 1 }), idat(0, 255, 0, 0, 255)));
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.reader(source));
            assertEquals(0, source.position(), "a refused body leaves the buffer where it was");
        }
    }

    @Nested
    @DisplayName("Pixels")
    class Pixels {

        @Test
        @DisplayName("useBKGDChunk presents the picture against its bKGD colour")
        void bkgdPresentation() throws IOException {
            // 2x1 RGBA: A HALF-COVERING RED PIXEL AND A FULLY TRANSPARENT GREEN ONE, OVER A BLUE bKGD
            final byte[] bytes = png(ihdr(2, 1, 8, 6), pngChunk("bKGD", new byte[] { 0, 0, 0, 0, 0, (byte) 255 }),
                    idat(0, 255, 0, 0, 128, 0, 255, 0, 0));
            final boolean previous = WaterMediaConfig.codecs.png.useBKGDChunk;
            try {
                WaterMediaConfig.codecs.png.useBKGDChunk = false;
                assertArrayEquals(new int[] { 0x80FF0000, 0x0000FF00 }, pixels(CodecsAPI.decode(bytes), 0), "transparency kept");
                WaterMediaConfig.codecs.png.useBKGDChunk = true;
                assertArrayEquals(new int[] { 0xFF80007F, 0xFF0000FF }, pixels(CodecsAPI.decode(bytes), 0), "flattened onto bKGD");
            } finally {
                WaterMediaConfig.codecs.png.useBKGDChunk = previous;
            }
        }

        @Test
        @DisplayName("A grayscale tRNS key matches the raw sample at 4 and 16 bits, before scaling to 8")
        void grayscaleTransparencyKey() throws IOException {
            // 4-BIT SAMPLES 0, 5, 10, 15 KEYED ON 5: ONLY THE SECOND PIXEL IS TRANSPARENT, ITS 8-BIT GRAY 85 NEVER COMPARED
            final byte[] four = png(ihdr(4, 1, 4, 0), pngChunk("tRNS", new byte[] { 0, 5 }), idat(0, 0x05, 0xAF));
            assertArrayEquals(new int[] { 0xFF000000, 0x00555555, 0xFFAAAAAA, 0xFFFFFFFF }, pixels(CodecsAPI.decode(four), 0));
            // 16-BIT SAMPLES 0x0012, 0x1200, 0xFFFF KEYED ON 0x0012: THE KEY IS THE FIRST PIXEL, WHILE THE SECOND ONLY
            // SHARES ITS 8-BIT SCALED VALUE 0x12 AND STAYS OPAQUE
            final byte[] sixteen = png(ihdr(3, 1, 16, 0), pngChunk("tRNS", new byte[] { 0x00, 0x12 }), idat(0, 0x00, 0x12, 0x12, 0x00, 0xFF, 0xFF));
            assertArrayEquals(new int[] { 0x00000000, 0xFF121212, 0xFFFFFFFF }, pixels(CodecsAPI.decode(sixteen), 0));
        }

        @Test
        @DisplayName("The requested RGBA and GRAY layouts are honoured")
        void requestedLayout() throws IOException {
            final byte[] bytes = png(ihdr(1, 1, 8, 6), idat(0, 255, 0, 0, 255));
            final ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);
            assertEquals(PixelFormat.RGBA, rgba.format());
            assertEquals(0xFF0000FF, pixels(rgba, 0)[0], "red lands in the first byte under RGBA");
            final ImageData gray = CodecsAPI.decode(bytes, PixelFormat.GRAY);
            assertEquals(PixelFormat.GRAY, gray.format());
            assertEquals(1, gray.frames()[0].remaining());
            assertEquals(77, gray.frames()[0].get(0) & 0xFF, "BT.601 luma of pure red");
        }
    }
}
