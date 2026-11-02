package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the pure helpers in {@link MathUtil}: clamping, unit conversion and byte formatting. */
@DisplayName("MathUtil")
public class MathUtilTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("clamp / clip255")
    class ClampTests {

        @Test
        @DisplayName("clamp returns the value when inside range")
        void clampInside() {
            assertEquals(5, MathUtil.clamp(5, 0, 10));
        }

        @Test
        @DisplayName("clamp returns lower bound when below")
        void clampLower() {
            assertEquals(0, MathUtil.clamp(-3, 0, 10));
        }

        @Test
        @DisplayName("clamp returns upper bound when above")
        void clampUpper() {
            assertEquals(10, MathUtil.clamp(20, 0, 10));
        }

        @Test
        @DisplayName("clamp with inverted bounds always returns max")
        void clampInverted() {
            for (final int value: new int[] { Integer.MIN_VALUE, -5, 0, 5, 10, 20, Integer.MAX_VALUE }) {
                assertEquals(0, MathUtil.clamp(value, 10, 0), "value " + value);
            }
        }

        @Test
        @DisplayName("long clamp keeps values beyond the int range and returns max on inverted bounds")
        void clampLong() {
            final long big = Integer.MAX_VALUE * 4L;
            assertEquals(big, MathUtil.clamp(big, 0L, Long.MAX_VALUE), "inside, beyond the int range");
            assertEquals(0L, MathUtil.clamp(-big, 0L, Long.MAX_VALUE), "below");
            assertEquals(big, MathUtil.clamp(Long.MAX_VALUE, 0L, big), "above");
            assertEquals(0L, MathUtil.clamp(big, 10L, 0L), "inverted bounds return max");
        }

        @Test
        @DisplayName("clip255 caps at 255")
        void clip255Upper() {
            assertEquals(255, MathUtil.clip255(300));
        }

        @Test
        @DisplayName("clip255 floors at 0")
        void clip255Lower() {
            assertEquals(0, MathUtil.clip255(-1));
        }

        @Test
        @DisplayName("clamp works on floats and doubles with the same inverted-bounds rule")
        void clampFloating() {
            assertEquals(0.5f, MathUtil.clamp(0.5f, 0f, 1f));
            assertEquals(1f, MathUtil.clamp(7f, 0f, 1f));
            assertEquals(0d, MathUtil.clamp(-3d, 0d, 1d));
            assertEquals(1d, MathUtil.clamp(0.5d, 2d, 1d), "inverted bounds return max");
        }
    }

    @Nested
    @DisplayName("Unit conversion")
    class ConversionTests {

        @Test
        @DisplayName("minutesToMs keeps fractional minutes down to the millisecond")
        void minutesToMs() {
            assertEquals(60_000L, MathUtil.minutesToMs(1.0));
            assertEquals(60_030L, MathUtil.minutesToMs(1.0005));
            assertEquals(30_000L, MathUtil.minutesToMs(0.5));
        }
    }

    @Nested
    @DisplayName("Byte formatting")
    class ByteTests {

        @Test
        @DisplayName("displayBytes(0) == '0 B'")
        void displayBytesZero() {
            assertEquals("0 B", MathUtil.displayBytes(0L));
        }

        @Test
        @DisplayName("displayBytes scales to KB, MB and GB")
        void displayBytesUnits() {
            assertEquals("1.0 KB", MathUtil.displayBytes(1_024L));
            assertEquals("1.5 MB", MathUtil.displayBytes(1_024L * 1_024L * 3 / 2));
            assertEquals("2.0 GB", MathUtil.displayBytes(2L * 1_024L * 1_024L * 1_024L));
        }

        @Test
        @DisplayName("displayBytes keeps a dot separator under a comma locale")
        void displayBytesIgnoresLocale() {
            final Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("es-ES"));
                assertEquals("1.5 KB", MathUtil.displayBytes(1_536L));
            } finally {
                Locale.setDefault(previous);
            }
        }
    }
}
