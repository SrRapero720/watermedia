package org.watermedia.api.codecs.readers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.ImageBytes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.watermedia.test.support.ImageBytes.vp8x;
import static org.watermedia.test.support.ImageBytes.webpChunk;

/**
 * Verifies crafted and corrupted WebP files: every malformed input surfaces as a clean
 * {@link CodecException} (or completes harmlessly), never as an unchecked exception, an
 * {@link OutOfMemoryError} or a hang, while valid features the decoder lacks surface as
 * {@link UnsupportedFormatException}.
 */
@DisplayName("WebP malformed inputs")
public class WebpMalformedTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A SQUARE ANIMATED VP8X CANVAS WITH ITS ANIM CHUNK, THEN ONE ANMF CHUNK REPEATED; THE CHUNKS ONLY, NO RIFF PREAMBLE
    private static byte[] animation(final byte[] frame, final int size, final int count) {
        final ImageBytes out = new ImageBytes().raw(webpChunk("VP8X", vp8x(0x02, size, size))).raw(webpChunk("ANIM", new byte[6]));
        for (int i = 0; i < count; i++) out.raw(frame);
        return out.bytes();
    }

    // 16-BYTE ANMF HEADER FOR A FRAME AT THE ORIGIN: MINUS-ONE 24-BIT SIZES, A 10 MS DURATION AND BLEND WITHOUT DISPOSAL
    private static byte[] anmfHeader(final int width, final int height) {
        return new ImageBytes().le24(0).le24(0).le24(width - 1).le24(height - 1).le24(10).u8(0).bytes();
    }

    // CHUNK STREAM OF A REAL FIXTURE (EVERYTHING PAST THE 12-BYTE RIFF????WEBP PREAMBLE)
    private static byte[] fixtureChunks(final Path path) {
        final byte[] file = Fixtures.readAll(path);
        return Arrays.copyOfRange(file, 12, file.length);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Geometry")
    class Geometry {

        @Test
        @DisplayName("VP8X canvas mismatching VP8 bitstream dims is rejected")
        void vp8CanvasMismatch() {
            // A VP8X CANVAS ONE PIXEL LARGER THAN THE SIMPLE VP8 KEY FRAME IT WRAPS: THE DECODER COMPARES THE CONTAINER SIZE
            // WITH THE FRAME HEADER'S AND FAILS AS A CODEC ERROR, NEVER AS AN INDEX OR ARGUMENT FAULT
            final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSY_DIR.resolve("0.webp"));
            // THE KEY FRAME SIZES ARE BODY BYTES 6..9, 14 BITS EACH, PAST THE 8-BYTE "VP8 " CHUNK HEADER
            final int w = ((stream[8 + 6] & 0xFF) | ((stream[8 + 7] & 0xFF) << 8)) & 0x3FFF;
            final int h = ((stream[8 + 8] & 0xFF) | ((stream[8 + 9] & 0xFF) << 8)) & 0x3FFF;
            final byte[] forged = new ImageBytes().raw(webpChunk("VP8X", vp8x(0x00, w + 1, h + 1))).raw(stream).bytes();

            final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
            assertThrows(CodecException.class, reader::next, "Mismatched canvas must fail cleanly");
        }

        @Test
        @DisplayName("VP8X canvas mismatching VP8L bitstream dims is rejected")
        void vp8lCanvasMismatch() {
            // THE SAME MISMATCH ON THE LOSSLESS PATH: THE VP8L HEADER SIZE MUST MATCH THE CONTAINER BEFORE ANY BUFFER IS SIZED
            final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSLESS);
            // THE VP8L SIZES ARE BODY BYTES 1..4, 14 BITS EACH, MINUS-ONE CODED
            final int bits = (stream[8 + 1] & 0xFF) | ((stream[8 + 2] & 0xFF) << 8) | ((stream[8 + 3] & 0xFF) << 16) | ((stream[8 + 4] & 0xFF) << 24);
            final int w = (bits & 0x3FFF) + 1;
            final int h = ((bits >> 14) & 0x3FFF) + 1;
            final byte[] forged = new ImageBytes().raw(webpChunk("VP8X", vp8x(0x00, w + 1, h + 1))).raw(stream).bytes();

            final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
            assertThrows(CodecException.class, reader::next, "Mismatched canvas must fail cleanly");
        }

        @Test
        @DisplayName("ANMF frame outside the canvas is rejected")
        void anmfFrameOutsideCanvas() {
            // A 32x32 FRAME ON A 16x16 CANVAS: THE FRAME RECTANGLE MUST LIE INSIDE THE CANVAS BEFORE IT IS DECODED
            final byte[] forged = animation(webpChunk("ANMF", anmfHeader(32, 32)), 16, 1);
            final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(forged)));
            assertThrows(CodecException.class, reader::next, "Out-of-canvas frame must fail cleanly");
        }

        @Test
        @DisplayName("Canvas above the total-pixel cap is rejected at construction")
        void canvasPixelBomb() {
            // 16384x16384 PASSES EACH AXIS, BUT Limits.fits REFUSES THE PRODUCT BEFORE ~2 GiB OF CANVAS AND OUTPUT EXIST
            final byte[] forged = webpChunk("VP8X", vp8x(0x02, 16_384, 16_384));
            assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(forged)), "Pixel bomb must fail before allocating");
        }
    }

    @Nested
    @DisplayName("Chunk structure")
    class ChunkStructure {

        @Test
        @Timeout(10)
        @DisplayName("ANMF with a sub-chunk size of 0xFFFFFFF8 neither hangs nor crashes")
        void anmfNegativeSubChunkSize() {
            // SUB-CHUNK SIZES ARE READ UNSIGNED, SO 0xFFFFFFF8 RUNS PAST THE ANMF BODY AND ENDS THE SUB-CHUNK WALK;
            // THE FRAME IS THEN REFUSED FOR CARRYING NO BITSTREAM
            final byte[] anmf = new ImageBytes().raw(anmfHeader(16, 16)).ascii("VP8 ").le32(-8).bytes();
            final WebPReader reader = assertDoesNotThrow(() -> new WebPReader(ByteBuffer.wrap(animation(webpChunk("ANMF", anmf), 16, 1))));
            assertThrows(CodecException.class, reader::next, "An oversized sub-chunk must fail cleanly");
        }

        @Test
        @DisplayName("Frames are capped per pass and reset() starts a new pass")
        void frameCap() throws IOException {
            // A 1x1 VP8L FRAME: NO TRANSFORM, NO CACHE, ONE GROUP OF FIVE ONE-SYMBOL CODES, SO THE PIXEL COSTS NO BITS
            final byte[] vp8l = { 0x2F, 0, 0, 0, 0, (byte) 0x88, (byte) 0x88, 0x08 };
            final byte[] frame = webpChunk("ANMF", new ImageBytes().raw(anmfHeader(1, 1)).raw(webpChunk("VP8L", vp8l)).bytes());
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 1, 4_096)))) {
                assertEquals(4_096, reader.frameCount());
                assertEquals(4_096, reader.readAll().frames().length);
                assertTrue(reader.reset());
                assertEquals(4_096, reader.readAll().frames().length);
            }
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 1, 4_097)))) {
                assertTrue(assertThrows(CodecException.class, reader::readAll).getMessage().contains("frame count"));
            }
        }
    }

    @Nested
    @DisplayName("Unsupported features")
    class Unsupported {

        @Test
        @DisplayName("A VP8 interframe is unsupported, not malformed")
        void vp8Interframe() {
            // FRAME TAG BIT 0 SET MARKS AN INTERFRAME; THE REST IS A WELL-FORMED 1x1 KEY FRAME HEADER
            final byte[] body = { 0x01, 0, 0, (byte) 0x9D, 0x01, 0x2A, 1, 0, 1, 0 };
            assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8 ", body))));
        }

        @Test
        @DisplayName("A VP8L bitstream of an unknown version is unsupported, not malformed")
        void vp8lVersion() {
            // THE TOP THREE BITS OF THE 32-BIT SIZE FIELD CARRY THE VERSION: 0x20 IN ITS LAST BYTE IS VERSION 1
            final byte[] body = { 0x2F, 0, 0, 0, 0x20 };
            assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8L", body))));
            assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8L", new byte[] { 0x2E, 0, 0, 0, 0 }))));
        }
    }
}
