package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the {@link ImageData} contract: {@code duration()} is the transparent single-cycle sum
 * (equal to the stored component, so a rebuild from accessors does not compound), {@code duration(true)}
 * carries the play-count total, and a {@link ImageData.Scan} owns its delays.
 */
@DisplayName("ImageData")
public class ImageDataTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static ImageData single(final long duration, final int repeat) {
        final ByteBuffer[] frames = { ByteBuffer.allocate(4) };
        return new ImageData(frames, PixelFormat.BGRA, 1, 1, new long[] { duration }, duration, repeat);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Duration")
    class DurationTests {
        @Test
        @DisplayName("duration() is transparent and survives a round-trip")
        void testDurationTransparent() {
            final ImageData data = single(100L, 3);
            assertEquals(100L, data.duration(), "duration() must return the stored single-cycle sum");

            // REBUILDING FROM ACCESSORS MUST NOT COMPOUND duration BY repeat
            final ImageData copy = new ImageData(data.frames(), data.format(), data.width(), data.height(),
                    data.delays(), data.duration(), data.repeat());
            assertEquals(100L, copy.duration());
        }

        @Test
        @DisplayName("duration(true) multiplies by the play count with a one-cycle floor")
        void testDuration() {
            assertEquals(300L, single(100L, 3).duration(true), "three plays take three cycles");
            assertEquals(100L, single(100L, ImageData.REPEAT_FOREVER).duration(true), "loop-forever floors at one cycle");
            assertEquals(100L, single(100L, 1).duration(true), "a single play is one cycle");
        }

        @Test
        @DisplayName("Convenience constructor sums the per-frame delays")
        void testConvenienceCtorSumsDelays() {
            final ByteBuffer[] frames = { ByteBuffer.allocate(4), ByteBuffer.allocate(4) };
            final ImageData data = new ImageData(frames, PixelFormat.BGRA, 1, 1, new long[] { 40L, 60L }, 1);
            assertEquals(100L, data.duration(), "duration must be the sum of the per-frame delays");
        }
    }

    @Nested
    @DisplayName("Validation")
    class ValidationTests {
        @Test
        @DisplayName("A null format or a negative play count is rejected")
        void testRejectsBadFields() {
            final ByteBuffer[] frames = { ByteBuffer.allocate(4) };
            assertThrows(IllegalArgumentException.class, () -> new ImageData(frames, null, 1, 1, new long[] { 0L }, 1));
            assertThrows(IllegalArgumentException.class, () -> new ImageData(frames, PixelFormat.BGRA, 1, 1, new long[] { 0L }, -1));
        }
    }

    @Nested
    @DisplayName("Scan")
    class ScanTests {
        @Test
        @DisplayName("of() derives the frame count and duration, and delays() hands out copies")
        void testScanOf() {
            final long[] delays = { 40L, 60L };
            final ImageData.Scan scan = ImageData.Scan.of(delays, ImageData.REPEAT_FOREVER);
            assertEquals(2, scan.frameCount());
            assertEquals(100L, scan.duration());
            assertArrayEquals(delays, scan.delays());
            assertNotSame(scan.delays(), scan.delays(), "every call owns its copy");
        }

        @Test
        @DisplayName("A delay array that does not match the frame count is rejected")
        void testScanRejectsMismatch() {
            assertThrows(IllegalArgumentException.class, () -> new ImageData.Scan(2, new long[] { 10L }, 10L, 1));
        }
    }
}
