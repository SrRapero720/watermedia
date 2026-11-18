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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.watermedia.test.support.ImageBytes.vp8x;
import static org.watermedia.test.support.ImageBytes.webpChunk;

/**
 * Verifies crafted and corrupted WebP files: canvas and frame geometry that disagrees with the bitstream, an oversized
 * ANMF sub-chunk, the per-pass frame cap and the pixel cap all fail as a clean, named {@link CodecException}, never as
 * an unchecked exception, an {@link OutOfMemoryError} or a hang, while valid features the decoder lacks surface as
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
        @DisplayName("A VP8X canvas larger than its VP8 key frame fails the decode as a size mismatch")
        void vp8CanvasMismatch() throws IOException {
            // A VP8X CANVAS ONE PIXEL LARGER THAN THE SIMPLE VP8 KEY FRAME IT WRAPS: THE DECODER COMPARES THE CONTAINER SIZE
            // WITH THE FRAME HEADER'S BEFORE SIZING ANY PLANE
            final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSY_DIR.resolve("0.webp"));
            // THE KEY FRAME SIZES ARE BODY BYTES 6..9, 14 BITS EACH, PAST THE 8-BYTE "VP8 " CHUNK HEADER
            final int w = ((stream[8 + 6] & 0xFF) | ((stream[8 + 7] & 0xFF) << 8)) & 0x3FFF;
            final int h = ((stream[8 + 8] & 0xFF) | ((stream[8 + 9] & 0xFF) << 8)) & 0x3FFF;
            final byte[] forged = new ImageBytes().raw(webpChunk("VP8X", vp8x(0x00, w + 1, h + 1))).raw(stream).bytes();
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(forged))) {
                assertEquals("(wm_err:5202) Failed to decode the VP8 frame due to size mismatch with the container: frame is " + w + "x" + h
                        + ", container declares " + (w + 1) + "x" + (h + 1), assertThrows(CodecException.class, reader::next).getMessage());
            }
        }

        @Test
        @DisplayName("A VP8X canvas larger than its VP8L bitstream fails the decode as a size mismatch")
        void vp8lCanvasMismatch() throws IOException {
            // THE SAME MISMATCH ON THE LOSSLESS PATH: THE VP8L HEADER SIZE MUST MATCH THE CONTAINER BEFORE ANY BUFFER IS SIZED
            final byte[] stream = fixtureChunks(Fixtures.WEBP_LOSSLESS);
            // THE VP8L SIZES ARE BODY BYTES 1..4, 14 BITS EACH, MINUS-ONE CODED
            final int bits = (stream[8 + 1] & 0xFF) | ((stream[8 + 2] & 0xFF) << 8) | ((stream[8 + 3] & 0xFF) << 16) | ((stream[8 + 4] & 0xFF) << 24);
            final int w = (bits & 0x3FFF) + 1;
            final int h = ((bits >> 14) & 0x3FFF) + 1;
            final byte[] forged = new ImageBytes().raw(webpChunk("VP8X", vp8x(0x00, w + 1, h + 1))).raw(stream).bytes();
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(forged))) {
                assertEquals("(wm_err:5016) Failed to read the VP8L bitstream due to size mismatch with the container: bitstream is " + w + "x" + h
                        + ", container declares " + (w + 1) + "x" + (h + 1), assertThrows(CodecException.class, reader::next).getMessage());
            }
        }

        @Test
        @DisplayName("An ANMF frame outside the canvas fails before its bitstream is looked for")
        void anmfFrameOutsideCanvas() throws IOException {
            // A 32x32 FRAME ON A 16x16 CANVAS, AND NO SUB-CHUNK: ONLY THE BOUNDS CHECK NAMES THE CANVAS
            final byte[] forged = animation(webpChunk("ANMF", anmfHeader(32, 32)), 16, 1);
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(forged))) {
                assertEquals("(wm_err:5009) Failed to read ANMF due to frame outside canvas: 32x32 at (0,0) on 16x16",
                        assertThrows(CodecException.class, reader::next).getMessage());
            }
        }

        @Test
        @DisplayName("A canvas above the total-pixel cap is refused at construction")
        void canvasPixelBomb() {
            // 16384x16384 PASSES EACH AXIS, BUT Limits.fits REFUSES THE PRODUCT BEFORE ABOUT 2 GiB OF CANVAS AND OUTPUT EXIST
            final byte[] forged = webpChunk("VP8X", vp8x(0x02, 16_384, 16_384));
            assertEquals("(wm_err:5005) Failed to read the WEBP header due to invalid dimensions: 16384x16384 (max 16384 per axis, 67108864 pixels)",
                    assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(forged))).getMessage());
        }
    }

    @Nested
    @DisplayName("Chunk structure")
    class ChunkStructure {

        @Test
        @Timeout(10)
        @DisplayName("An ANMF sub-chunk size of 0xFFFFFFF8 ends the sub-chunk walk and the frame fails for lack of a bitstream")
        void anmfNegativeSubChunkSize() throws IOException {
            // SUB-CHUNK SIZES ARE READ UNSIGNED, SO 0xFFFFFFF8 RUNS PAST THE ANMF BODY AND ENDS THE WALK INSTEAD OF LOOPING
            final byte[] anmf = new ImageBytes().raw(anmfHeader(16, 16)).ascii("VP8 ").le32(-8).bytes();
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(webpChunk("ANMF", anmf), 16, 1)))) {
                assertEquals("(wm_err:5010) Failed to read ANMF due to missing VP8/VP8L sub-chunk", assertThrows(CodecException.class, reader::next).getMessage());
            }
        }

        @Test
        @DisplayName("Frames are capped at 4096 per pass and reset() starts a new pass")
        void frameCap() throws IOException {
            // A 1x1 VP8L FRAME: NO TRANSFORM, NO CACHE, ONE GROUP OF FIVE ONE-SYMBOL CODES, SO THE PIXEL COSTS NO BITS
            final byte[] vp8l = { 0x2F, 0, 0, 0, 0, (byte) 0x88, (byte) 0x88, 0x08 };
            final byte[] frame = webpChunk("ANMF", new ImageBytes().raw(anmfHeader(1, 1)).raw(webpChunk("VP8L", vp8l)).bytes());
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 1, 4_096)))) {
                assertEquals(4_096, reader.frameCount(), "frames scanned");
                assertEquals(4_096, reader.readAll().frames().length, "frames of the first pass");
                assertTrue(reader.reset(), "an animation rewinds");
                assertEquals(4_096, reader.readAll().frames().length, "frames of the second pass");
            }
            try (final WebPReader reader = new WebPReader(ByteBuffer.wrap(animation(frame, 1, 4_097)))) {
                assertEquals("(wm_err:5007) Failed to read the WEBP animation due to frame count over the limit: 4097 (max 4096)",
                        assertThrows(CodecException.class, reader::readAll).getMessage());
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
            assertEquals("(wm_err:5001) Failed to read the VP8 chunk due to unsupported interframes, only key frames are supported",
                    assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8 ", body)))).getMessage());
        }

        @Test
        @DisplayName("A VP8L bitstream of an unknown version is unsupported, while a wrong signature is malformed")
        void vp8lVersion() {
            // THE TOP THREE BITS OF THE 32-BIT SIZE FIELD CARRY THE VERSION: 0x20 IN ITS LAST BYTE IS VERSION 1
            final byte[] version = { 0x2F, 0, 0, 0, 0x20 };
            assertEquals("(wm_err:5015) Failed to read the VP8L header due to unsupported version: 1",
                    assertThrows(UnsupportedFormatException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8L", version)))).getMessage());
            final byte[] signature = { 0x2E, 0, 0, 0, 0 };
            final CodecException e = assertThrows(CodecException.class, () -> new WebPReader(ByteBuffer.wrap(webpChunk("VP8L", signature))));
            assertFalse(e instanceof UnsupportedFormatException, "malformed input reported as unsupported: " + e.getMessage());
            assertEquals("(wm_err:5014) Failed to read the VP8L header due to invalid signature", e.getMessage());
        }
    }
}
