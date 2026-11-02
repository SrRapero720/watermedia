package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the easing curves of {@link MathEases}: their end points, shapes and behaviour outside the unit range. */
@DisplayName("MathEases")
public class MathEasesTest {

    private static final double EPSILON = 1.0e-3;

    @Nested
    @DisplayName("Boundaries")
    class BoundaryTests {

        @Test
        @DisplayName("every curve starts at start and ends at end")
        void everyCurveHitsBothEnds() {
            for (final MathEases ease: MathEases.values()) {
                assertEquals(0.0, ease.apply(0.0, 10.0, 0.0), EPSILON, ease.name());
                assertEquals(10.0, ease.apply(0.0, 10.0, 1.0), EPSILON, ease.name());
                assertEquals(10.0, ease.apply(10.0, 30.0, 0.0), EPSILON, ease.name());
                assertEquals(30.0, ease.apply(10.0, 30.0, 1.0), EPSILON, ease.name());
            }
        }

        @Test
        @DisplayName("outside [0, 1] circle curves return NaN while elastic and bounce extrapolate")
        void outsideUnitRange() {
            assertTrue(Double.isNaN(MathEases.EASE_IN_CIRCLE.apply(0.0, 10.0, 1.5)));
            assertTrue(Double.isNaN(MathEases.EASE_OUT_CIRCLE.apply(0.0, 10.0, -0.5)));
            assertTrue(Double.isNaN(MathEases.EASE_IN_OUT_CIRCLE.apply(0.0, 10.0, 2.0)));
            assertTrue(Double.isFinite(MathEases.EASE_OUT_ELASTIC.apply(0.0, 10.0, 1.5)));
            assertTrue(Double.isFinite(MathEases.EASE_IN_OUT_BOUNCE.apply(0.0, 10.0, -0.5)));
        }
    }

    @Nested
    @DisplayName("Shapes")
    class ShapeTests {

        @Test
        @DisplayName("EASE_IN is quadratic and EASE_OUT its mirror")
        void quadraticShapes() {
            assertEquals(2.5, MathEases.EASE_IN.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(7.5, MathEases.EASE_OUT.apply(0.0, 10.0, 0.5), EPSILON);
        }

        @Test
        @DisplayName("in-out curves cross the midpoint half-way")
        void inOutMidpoints() {
            assertEquals(5.0, MathEases.EASE_IN_OUT.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_QUAD.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_SINE.apply(0.0, 10.0, 0.5), EPSILON);
            assertEquals(5.0, MathEases.EASE_IN_OUT_BOUNCE.apply(0.0, 10.0, 0.5), EPSILON);
        }

        @Test
        @DisplayName("composite curves honour a non-zero start and scale with the range")
        void compositeCurvesHonourRange() {
            assertEquals(11.25, MathEases.EASE_IN_OUT.apply(10.0, 20.0, 0.25), EPSILON);
            assertEquals(15.0, MathEases.EASE_IN_OUT.apply(10.0, 20.0, 0.5), EPSILON);
            assertEquals(13.75, MathEases.EASE_OUT_IN.apply(10.0, 20.0, 0.25), EPSILON);
            assertEquals(15.0, MathEases.EASE_OUT_IN.apply(10.0, 20.0, 0.5), EPSILON);
            for (final MathEases ease: new MathEases[] { MathEases.EASE_IN_OUT, MathEases.EASE_OUT_IN, MathEases.EASE_IN_OUT_ELASTIC }) {
                for (final double t: new double[] { 0.1, 0.25, 0.4, 0.6, 0.75, 0.9 }) {
                    // THE CURVE SCALES WITH THE RANGE AND SHIFTS WITH THE START
                    assertEquals(10.0 * ease.apply(0.0, 1.0, t), ease.apply(0.0, 10.0, t), EPSILON, ease.name() + " at " + t);
                    assertEquals(5.0 + ease.apply(0.0, 10.0, t), ease.apply(5.0, 15.0, t), EPSILON, ease.name() + " at " + t);
                }
            }
        }
    }
}
