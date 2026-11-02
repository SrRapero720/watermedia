package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the intrinsic data of {@link PixelFormat}: only the BCn layouts are compressed, and the
 * plane geometry rounds subsampled planes up, scales texels with the sample depth and sums every
 * plane into the frame size.
 */
@DisplayName("PixelFormat")
public class PixelFormatTest {
    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Test
    @DisplayName("Only BCn formats are compressed, with 8 or 16 block bytes")
    void testCompressed() {
        for (final PixelFormat fmt: PixelFormat.values()) {
            if (fmt.name().startsWith("BC")) {
                assertTrue(fmt.compressed(), fmt + " must be compressed");
                assertEquals(fmt == PixelFormat.BC1 ? 8 : 16, fmt.blockBytes(), fmt + " block bytes");
            } else {
                assertFalse(fmt.compressed(), fmt + " must not be compressed");
                assertEquals(0, fmt.blockBytes(), fmt + " must have no block bytes");
            }
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Plane geometry")
    class GeometryTests {

        @Test
        @DisplayName("Subsampled planes round up, so odd sizes keep their last chroma column and row")
        void roundsSubsampledPlanesUp() {
            assertEquals(853, PixelFormat.YUV420P.planeWidth(0, 853));
            assertEquals(481, PixelFormat.YUV420P.planeHeight(0, 481));
            for (final int plane: new int[] { 1, 2 }) {
                assertEquals(427, PixelFormat.YUV420P.planeWidth(plane, 853));
                assertEquals(241, PixelFormat.YUV420P.planeHeight(plane, 481));
                assertEquals(427, PixelFormat.YUV422P.planeWidth(plane, 853));
                assertEquals(481, PixelFormat.YUV422P.planeHeight(plane, 481));
                assertEquals(853, PixelFormat.YUV444P.planeWidth(plane, 853));
            }
            assertEquals(853, PixelFormat.YUVA420P.planeWidth(3, 853), "alpha stays at full size");
            assertEquals(481, PixelFormat.YUVA420P.planeHeight(3, 481));
            assertEquals(427, PixelFormat.NV12.planeWidth(1, 853));
            assertEquals(241, PixelFormat.NV21.planeHeight(1, 481));
            assertEquals(427, PixelFormat.YUYV.planeWidth(0, 853), "one texel per two-pixel group");
            assertEquals(481, PixelFormat.UYVY.planeHeight(0, 481));
            assertEquals(16, PixelFormat.BC7.planeWidth(0, 61), "one texel per 4x4 block");
            assertEquals(8, PixelFormat.BC1.planeHeight(0, 30));
            assertEquals(853, PixelFormat.RGB.planeWidth(0, 853));
        }

        @Test
        @DisplayName("Texel bytes scale with the sample depth and compressed formats count one block")
        void texelBytesFollowDepth() {
            assertEquals(1, PixelFormat.GRAY.texelBytes(0, 8));
            assertEquals(2, PixelFormat.GRAY.texelBytes(0, 12));
            assertEquals(2, PixelFormat.NV12.texelBytes(1, 8));
            assertEquals(4, PixelFormat.NV12.texelBytes(1, 10));
            assertEquals(8, PixelFormat.NV12.texelBytes(1, 32));
            assertEquals(2, PixelFormat.YUV420P.texelBytes(2, 16));
            assertEquals(3, PixelFormat.RGB.texelBytes(0, 8));
            assertEquals(6, PixelFormat.RGB.texelBytes(0, 16));
            assertEquals(4, PixelFormat.BGRA.texelBytes(0, 8));
            assertEquals(4, PixelFormat.YUYV.texelBytes(0, 8));
            assertEquals(8, PixelFormat.BC1.texelBytes(0, 16));
            assertEquals(16, PixelFormat.BC7.texelBytes(0, 8));
        }

        @Test
        @DisplayName("frameBytes sums every tightly packed plane")
        void frameBytesSumsPlanes() {
            assertEquals(16 + 4 + 4, PixelFormat.YUV420P.frameBytes(4, 4, 8));
            assertEquals(853L * 480 + 2L * 427 * 240, PixelFormat.YUV420P.frameBytes(853, 480, 8));
            assertEquals(853L * 481 + 427L * 2 * 241, PixelFormat.NV12.frameBytes(853, 481, 8));
            assertEquals((4 + 1 + 1 + 4) * 2, PixelFormat.YUVA420P.frameBytes(2, 2, 10));
            assertEquals(2 * 4, PixelFormat.YUYV.frameBytes(3, 1, 8), "an odd width still pays its last group");
            assertEquals(16 * 8 * 8, PixelFormat.BC1.frameBytes(61, 30, 8));
            assertEquals(4L * 1_920 * 1_080, PixelFormat.BGRA.frameBytes(1_920, 1_080, 8));
            assertEquals(1L << 31, PixelFormat.BC1.frameBytes(65_536, 65_536, 8), "block math never wraps");
        }

        @Test
        @DisplayName("Missing planes, negative sizes, impossible depths and sizes past a long are rejected")
        void rejectsInvalidArguments() {
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.BGRA.planeWidth(1, 4));
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.NV12.planeHeight(2, 4));
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.YUV420P.texelBytes(-1, 8));
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.YUV420P.planeWidth(0, -5));
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.GRAY.texelBytes(0, 64));
            assertThrows(IllegalArgumentException.class, () -> PixelFormat.GRAY.frameBytes(4, 4, 0));
            assertThrows(ArithmeticException.class, () -> PixelFormat.RGBA.frameBytes(Integer.MAX_VALUE, Integer.MAX_VALUE, 32));
        }
    }
}
