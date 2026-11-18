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

/** Verifies {@link MediaQuality}: the bucket of a size by its smallest known side and the closest available quality to a preferred one. */
@DisplayName("MediaQuality")
public class MediaQualityTest {

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("of(width, height)")
    class OfDimensions {

        @Test
        @DisplayName("1920x1080 maps to HIGH")
        void fullHdIsHigh() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(1920, 1080));
        }

        @Test
        @DisplayName("3840x2160 maps to HIGHEST")
        void uhdIsHighest() {
            assertSame(MediaQuality.HIGHEST, MediaQuality.of(3840, 2160));
        }

        @Test
        @DisplayName("640x360 maps to LOW")
        void nhdIsLow() {
            assertSame(MediaQuality.LOW, MediaQuality.of(640, 360));
        }

        @Test
        @DisplayName("a size without a known side, 0x0 or -1x-1, maps to UNKNOWN")
        void unknownSizes() {
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
        @DisplayName("a negative width counts as unknown, so the height decides")
        void negativeWidthFallsBackToHeight() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(-5, 1080));
        }

        @Test
        @DisplayName("a zero height counts as unknown, so the width decides")
        void zeroHeightFallsBackToWidth() {
            assertSame(MediaQuality.MEDIUM, MediaQuality.of(720, 0));
        }

        @Test
        @DisplayName("portrait uses the smaller side")
        void portraitUsesSmallerSide() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(1080, 1920));
        }

        @Test
        @DisplayName("of(resolution) buckets a single side: 1080 is HIGH and 500 is LOWISH")
        void singleSide() {
            assertSame(MediaQuality.HIGH, MediaQuality.of(1080));
            assertSame(MediaQuality.LOWISH, MediaQuality.of(500));
        }
    }

    @Nested
    @DisplayName("closest(available, preferred)")
    class Closest {

        @Test
        @DisplayName("returns the preferred quality when it is available")
        void preferredAvailable() {
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.LOWISH, MediaQuality.HIGH, MediaQuality.MEDIUM);
            assertSame(MediaQuality.HIGH, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("a missing preferred quality picks the nearest available one, the lower on a tie")
        void picksClosestNeighbour() {
            // PREFERRED HIGH: MEDIUM AND HIGHER ARE BOTH ONE STEP AWAY
            final Set<MediaQuality> available = EnumSet.of(MediaQuality.MEDIUM, MediaQuality.HIGHER);
            assertSame(MediaQuality.MEDIUM, MediaQuality.closest(available, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("the only available quality is picked however far it sits from the preferred one")
        void farthestNeighbour() {
            assertSame(MediaQuality.LOWEST, MediaQuality.closest(EnumSet.of(MediaQuality.LOWEST), MediaQuality.ULTRA_8K));
        }

        @Test
        @DisplayName("an empty set returns null")
        void emptySetReturnsNull() {
            assertNull(MediaQuality.closest(EnumSet.noneOf(MediaQuality.class), MediaQuality.HIGH));
        }

        @Test
        @DisplayName("a null set returns null")
        void nullSetReturnsNull() {
            assertNull(MediaQuality.closest(null, MediaQuality.HIGH));
        }

        @Test
        @DisplayName("a null preferred quality is rejected")
        void nullPreferredRejected() {
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> MediaQuality.closest(EnumSet.of(MediaQuality.MEDIUM), null));
            assertEquals("(wm_err:0410) Failed to pick the quality due to null preferred quality", failure.getMessage());
        }
    }
}
