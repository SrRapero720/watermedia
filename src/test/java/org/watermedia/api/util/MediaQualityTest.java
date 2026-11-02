package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies {@link MediaQuality} classification from dimensions and the closest-available lookup. */
@DisplayName("MediaQuality")
public class MediaQualityTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("of(width, height)")
    class OfDimensionsTests {

        @Test
        @DisplayName("1920x1080 maps to HIGH")
        void hd1080() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(1920, 1080));
        }

        @Test
        @DisplayName("3840x2160 maps to HIGHEST")
        void uhd2160() {
            assertSame(MediaQuality.HIGHEST, MediaQuality.of(3840, 2160));
        }

        @Test
        @DisplayName("640x360 maps to LOW")
        void sd360() {
            assertSame(MediaQuality.LOW, MediaQuality.of(640, 360));
        }

        @Test
        @DisplayName("0x0 maps to UNKNOWN")
        void allZero() {
            assertSame(MediaQuality.UNKNOWN, MediaQuality.of(0, 0));
            assertSame(MediaQuality.UNKNOWN, MediaQuality.of(-1, -1));
        }

        @Test
        @DisplayName("known sizes under the first threshold map to LOWEST, and 144 and 240 climb the first two steps")
        void tinySizesAreLowest() {
            assertSame(MediaQuality.LOWEST, MediaQuality.of(1, 1));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(256, 143));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(0, 64));
            assertSame(MediaQuality.LOWEST, MediaQuality.of(256, 144));
            assertSame(MediaQuality.LOWER, MediaQuality.of(426, 240));
        }

        @Test
        @DisplayName("negative width is treated as missing dimension")
        void negativeWidthFallsBackToHeight() {
            // WIDTH <= 0 SO HEIGHT IS USED DIRECTLY
            assertSame(MediaQuality.HIGH, MediaQuality.of(-5, 1080));
        }

        @Test
        @DisplayName("zero height falls back to width")
        void zeroHeightFallsBackToWidth() {
            assertSame(MediaQuality.MEDIUM, MediaQuality.of(720, 0));
        }

        @Test
        @DisplayName("portrait uses the smaller dimension")
        void portraitUsesSmallerSide() {
            // THE SMALLER SIDE, 1080, DECIDES: HIGH
            assertSame(MediaQuality.HIGH, MediaQuality.of(1080, 1920));
        }

        @Test
        @DisplayName("of(int) delegates to of(int, int) with same width/height")
        void singleResolution() {
            assertEquals(MediaQuality.of(1080, 1080), MediaQuality.of(1080));
        }
    }

    @Nested
    @DisplayName("closest(set, preferred)")
    class ClosestTests {

        @Test
        @DisplayName("returns preferred when available")
        void exactMatch() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.LOWISH, MediaQuality.HIGH, MediaQuality.MEDIUM);
            assertSame(MediaQuality.HIGH, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("returns closest available when preferred is missing")
        void picksClosestNeighbour() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.MEDIUM, MediaQuality.HIGHER);
            // PREFERRED HIGH: MEDIUM AND HIGHER ARE BOTH ONE STEP AWAY, AND A TIE BREAKS TOWARD THE LOWER QUALITY
            assertSame(MediaQuality.MEDIUM, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("empty set returns null")
        void emptyReturnsNull() {
            assertNull(MediaQuality.closest(EnumSet.noneOf(MediaQuality.class), MediaQuality.HIGH));
        }

        @Test
        @DisplayName("null set returns null")
        void nullReturnsNull() {
            assertNull(MediaQuality.closest(null, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("null preferred quality is rejected")
        void nullPreferredRejected() {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MediaQuality.closest(EnumSet.of(MediaQuality.MEDIUM), null));
            assertEquals("Preferred quality cannot be null", failure.getMessage());
        }

        @Test
        @DisplayName("the only available quality is picked however far it sits from the preferred one")
        void farthestNeighbour() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.LOWEST);
            assertSame(MediaQuality.LOWEST, MediaQuality.closest(available, MediaQuality.ULTRA_8K));
        }
    }
}
