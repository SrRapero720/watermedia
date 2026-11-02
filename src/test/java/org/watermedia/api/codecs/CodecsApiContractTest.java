package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link CodecsAPI} contract: format detection, header consumption on the caller's buffer, the
 * overloads and their argument checks, the requested layout, the iterator and close contract every
 * {@link ImageReader} shares, empty metadata and codec availability.
 */
@DisplayName("CodecsAPI contract")
public class CodecsApiContractTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // HEAP COPY OF A FRAME, SO IT SURVIVES THE READER REUSING ITS BUFFER
    private static byte[] copy(final ByteBuffer frame) {
        final byte[] out = new byte[frame.remaining()];
        frame.duplicate().get(out);
        return out;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Format detection")
    class FormatDetection {

        @Test
        @DisplayName("Unknown bytes are an UnsupportedFormatException")
        void unknownBytes() {
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decode(new byte[] { 0x00, 0x11, 0x22, 0x33, 0x44 }));
        }

        @Test
        @DisplayName("Readers expose their format name without consuming past the header")
        void consumesOnlyTheHeader() throws Exception {
            record Fixture(String name, Path path, int headerBytes) {}
            for (final Fixture fixture: List.of(
                    new Fixture("PNG", Fixtures.PNG_ANIMATED, 8),
                    new Fixture("GIF", Fixtures.GIF_ANIMATED, 6),
                    new Fixture("WEBP", Fixtures.WEBP_LOSSLESS, 12),
                    new Fixture("NETPBM", Fixtures.NETPBM_PPM, 2),
                    new Fixture("JPEG", Fixtures.JPEG_BASELINE, 2))) {
                final ByteBuffer data = ByteBuffer.wrap(Fixtures.readAll(fixture.path()));
                try (final ImageReader reader = CodecsAPI.reader(data)) {
                    assertEquals(fixture.name(), reader.name());
                    assertEquals(fixture.headerBytes(), data.position(), fixture.name() + " should leave caller buffer after the format header");
                    assertTrue(reader.hasNext(), fixture.name() + " fixture should expose a frame");
                }
            }
        }

        @Test
        @DisplayName("A reader that refuses its body leaves the buffer position untouched")
        void refusedBodyRestoresPosition() {
            // A PNG SIGNATURE FOLLOWED BY GARBAGE: THE HEADER MATCHES, THE BODY DOES NOT
            final ByteBuffer source = ByteBuffer.wrap(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4 });
            assertThrows(IOException.class, () -> CodecsAPI.reader(source));
            assertEquals(0, source.position());
        }
    }

    @Nested
    @DisplayName("Reader contract")
    class ReaderContract {

        @Test
        @DisplayName("readAll() returns ImageData consistent with the reader")
        void readAllMatchesReader() throws Exception {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_ANIMATED)))) {
                final ImageData image = reader.readAll();
                assertEquals(reader.width(), image.width());
                assertEquals(reader.height(), image.height());
                assertEquals(reader.frameCount(), image.frames().length);
            }
        }

        @Test
        @DisplayName("decode accepts buffers and streams, sniff accepts buffers, and null arguments are rejected")
        void overloadsAndArguments() throws Exception {
            final byte[] bytes = Fixtures.readAll(Fixtures.PNG_STATIC);
            final ImageData fromArray = CodecsAPI.decode(bytes);
            final ImageData fromBuffer = CodecsAPI.decode(ByteBuffer.wrap(bytes));
            final ImageData fromStream = CodecsAPI.decode(new ByteArrayInputStream(bytes));
            assertEquals(fromArray.frames().length, fromBuffer.frames().length);
            assertEquals(fromArray.width(), fromStream.width());
            assertEquals(fromArray.format(), fromStream.format());

            final ByteBuffer probe = ByteBuffer.wrap(bytes);
            assertEquals(MediaType.IMAGE, CodecsAPI.sniff(probe));
            assertEquals(0, probe.position(), "sniffing a buffer leaves its position alone");

            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.reader(null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.decode((byte[]) null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.decode((InputStream) null));
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.sniff((ByteBuffer) null));
            final ByteBuffer source = ByteBuffer.wrap(bytes);
            assertThrows(IllegalArgumentException.class, () -> CodecsAPI.reader(source, PixelFormat.YUV420P));
            assertEquals(0, source.position(), "a refused layout leaves the buffer where it was");
        }

        @Test
        @DisplayName("A requested RGBA layout swaps red and blue on every pixel and survives reset() on every reader")
        void requestedLayoutSurvivesReset() throws Exception {
            for (final Path fixture: List.of(Fixtures.PNG_STATIC, Fixtures.GIF_ANIMATED, Fixtures.WEBP_LOSSLESS, Fixtures.JPEG_BASELINE, Fixtures.NETPBM_PPM)) {
                final byte[] bytes = Fixtures.readAll(fixture);
                final byte[] swapped;
                try (final ImageReader reference = CodecsAPI.reader(ByteBuffer.wrap(bytes), PixelFormat.BGRA)) {
                    swapped = copy(reference.next());
                }
                for (int i = 0; i < swapped.length; i += 4) {
                    final byte blue = swapped[i];
                    swapped[i] = swapped[i + 2];
                    swapped[i + 2] = blue;
                }
                try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(bytes), PixelFormat.RGBA)) {
                    assertEquals(PixelFormat.RGBA, reader.pixelFormat(), fixture.toString());
                    final byte[] first = copy(reader.next());
                    assertEquals(ByteBuffer.wrap(swapped), ByteBuffer.wrap(first), fixture + " must deliver the BGRA frame with red and blue swapped");
                    assertTrue(reader.reset(), fixture.toString());
                    assertEquals(ByteBuffer.wrap(first), ByteBuffer.wrap(copy(reader.next())), fixture + " must deliver the same RGBA bytes after reset()");
                }
            }
        }

        @Test
        @DisplayName("next() past the last frame throws NoSuchElementException, and a closed reader throws IllegalStateException but keeps its descriptors")
        void endAndClose() throws IOException {
            final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_STATIC)));
            assertEquals(0L, reader.delay(), "no frame delivered yet");
            reader.next();
            assertFalse(reader.hasNext());
            assertThrows(NoSuchElementException.class, reader::next);
            reader.close();
            reader.close();
            assertThrows(IllegalStateException.class, reader::hasNext);
            assertThrows(IllegalStateException.class, reader::next);
            assertThrows(IllegalStateException.class, reader::reset);
            assertThrows(IllegalStateException.class, reader::readAll);
            assertEquals(64, reader.width(), "descriptors stay readable after close");
        }
    }

    @Nested
    @DisplayName("Metadata and availability")
    class MetadataAndAvailability {

        @Test
        @DisplayName("Empty metadata: scalar fields null, collection fields empty (not null)")
        void emptyMetadataFields() throws Exception {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.JPEG_BASELINE)))) {
                final ImageMetadata metadata = reader.metadata();
                assertNull(metadata.title());
                assertTrue(metadata.authors().isEmpty());
                assertTrue(metadata.values().isEmpty());
            }
        }

        @Test
        @DisplayName("Decode-only containers list no codecs, image containers carry no sound and Ogg carries only sound")
        void availability() {
            for (final Container container: List.of(Container.WEBP, Container.JPEG, Container.NETPBM, Container.SVG))
                assertTrue(container.video().isEmpty() && container.audio().isEmpty(), container.name());
            assertTrue(Container.PNG.audio().isEmpty() && Container.GIF.audio().isEmpty());
            assertTrue(Container.OGG.video().isEmpty() && !Container.OGG.audio().isEmpty());
        }
    }
}
