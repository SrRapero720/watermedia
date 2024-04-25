package me.srrapero720.watermedia.tests.easing;

import me.srrapero720.watermedia.api.math.MathAPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MathEasingTest {

    private static final double DELTA = 1e-5;
    private static final int START = 0;
    private static final int END = 100;

    @Test
    public void testEaseIn() {
        assertEquals(START, MathAPI.easeIn(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeIn(START, END, 1), DELTA);
        assertEquals(25, MathAPI.easeIn(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseOut() {
        assertEquals(START, MathAPI.easeOut(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeOut(START, END, 1), DELTA);
        assertEquals(75, MathAPI.easeOut(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInOut() {
        assertEquals(START, MathAPI.easeInOut(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInOut(START, END, 1), DELTA);
        assertEquals((END - START) / 2f, MathAPI.easeInOut(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseOutIn() {
        assertEquals(START, MathAPI.easeOutIn(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeOutIn(START, END, 1), DELTA);
        assertEquals(37.5, MathAPI.easeOutIn(START, END, 0.25), DELTA);
        assertEquals(50, MathAPI.easeOutIn(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInCircle() {
        assertEquals(START, MathAPI.easeInCircle(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInCircle(START, END, 1), DELTA);
        double expected = START + (END - START) * (1 - Math.sqrt(1 - 0.25));
        assertEquals(expected, MathAPI.easeInCircle(START, END, 0.5), DELTA);
    }

    @Test
    public void testEasyEase() {
        assertEquals(START, MathAPI.easyEase(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easyEase(START, END, 1.0), DELTA);
        double expectedMid = START + (END - START) * 0.5;
        assertEquals(expectedMid, MathAPI.easyEase(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInSine() {
        assertEquals(0, MathAPI.easeInSine(START, END, 0), DELTA);
        assertEquals(100, MathAPI.easeInSine(START, END, 1), DELTA);
        double expectedMid = 100 * (1 - MathAPI.cos((float) (Math.PI / 4)));
        assertEquals(expectedMid, MathAPI.easeInSine(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInCubic() {
        assertEquals(START, MathAPI.easeInCubic(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInCubic(START, END, 1.0), DELTA);
        double expectedMid = START + (END - START) * Math.pow(0.5, 3);
        assertEquals(expectedMid, MathAPI.easeInCubic(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInQuint() {
        assertEquals(START, MathAPI.easeInQuint(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInQuint(START, END, 1.0), DELTA);
        double expectedMid = START + (END - START) * Math.pow(0.5, 5);
        assertEquals(expectedMid, MathAPI.easeInQuint(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInElastic() {
        assertEquals(START, MathAPI.easeInElastic(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInElastic(START, END, 1.0), DELTA);

        double t = 0.5;
        double c4 = (2 * Math.PI) / 3;
        double expectedMid = START - Math.pow(2, 10 * t - 10) * MathAPI.sin((float) ((t * 10 - 10.75) * c4)) * (END - START);
        assertEquals(expectedMid, MathAPI.easeInElastic(START, END, t), DELTA);
    }

    @Test
    public void testEaseOutSine() {
        assertEquals(0, MathAPI.easeOutSine(START, END, 0), DELTA);
        assertEquals(100, MathAPI.easeOutSine(START, END, 1), DELTA);
        double expectedMid = 100 * MathAPI.sin((float) (Math.PI / 4));
        assertEquals(expectedMid, MathAPI.easeOutSine(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseOutCubic() {
        assertEquals(START, MathAPI.easeOutCubic(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutCubic(START, END, 1.0), DELTA);

        double expectedMid = START + (END - START) * (1 - Math.pow(1 - 0.5, 3));
        assertEquals(expectedMid, MathAPI.easeOutCubic(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseOutQuint() {
        assertEquals(START, MathAPI.easeOutQuint(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutQuint(START, END, 1.0), DELTA);

        double expectedMid = START + (END - START) * (1 - Math.pow(1 - 0.5, 5));
        assertEquals(expectedMid, MathAPI.easeOutQuint(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseOutCircle() {
        assertEquals(START, MathAPI.easeOutCircle(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutCircle(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * Math.sqrt(1 - Math.pow(t - 1, 2));
        assertEquals(expectedMid, MathAPI.easeOutCircle(START, END, t), DELTA);
    }

    @Test
    public void testEaseOutElastic() {
        assertEquals(START, MathAPI.easeOutElastic(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutElastic(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedValue = (END - START) * (Math.pow(2, -10 * t) * MathAPI.sin((float) ((t * 10 - 0.75) * ((2 * Math.PI) / 3))) + 1) + START;
        assertEquals(expectedValue, MathAPI.easeOutElastic(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutSine() {
        assertEquals(START, MathAPI.easeInOutSine(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInOutSine(START, END, 1), DELTA);
        assertEquals((END - START) / 2f, MathAPI.easeInOutSine(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInOutCubic() {
        assertEquals(START, MathAPI.easeInOutCubic(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutCubic(START, END, 1.0), DELTA);

        double expectedMid = START + (END - START) * (4 * Math.pow(0.5, 3));
        assertEquals(expectedMid, MathAPI.easeInOutCubic(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInOutQuint() {
        assertEquals(START, MathAPI.easeInOutQuint(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutQuint(START, END, 1.0), DELTA);

        double expectedMid = START + (END - START) * (1 - Math.pow(-2 * 0.5 + 2, 5) / 2);
        assertEquals(expectedMid, MathAPI.easeInOutQuint(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInOutCircle() {
        assertEquals(START, MathAPI.easeInOutCircle(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutCircle(START, END, 1.0), DELTA);

        double expectedMid = START + (END - START) * 0.5;
        assertEquals(expectedMid, MathAPI.easeInOutCircle(START, END, 0.5), DELTA);
    }

    @Test
    public void testEaseInOutElastic() {
        assertEquals(START, MathAPI.easeInOutElastic(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInOutElastic(START, END, 1), DELTA);
    }

    @Test
    public void testEaseInQuad() {
        assertEquals(START, MathAPI.easeInQuad(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInQuad(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (t * t);
        assertEquals(expectedMid, MathAPI.easeInQuad(START, END, t), DELTA);
    }

    @Test
    public void testEaseInQuart() {
        assertEquals(START, MathAPI.easeInQuart(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInQuart(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * Math.pow(t, 4);
        assertEquals(expectedMid, MathAPI.easeInQuart(START, END, t), DELTA);
    }

    @Test
    public void testEaseInExpo() {
        assertEquals(START, MathAPI.easeInExpo(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInExpo(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * Math.pow(2, 10 * t - 10);
        assertEquals(expectedMid, MathAPI.easeInExpo(START, END, t), DELTA);
    }

    @Test
    public void testEaseInBack() {
        assertEquals(START, MathAPI.easeInBack(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInBack(START, END, 1.0), DELTA);

        double t = 0.5;
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        double expectedMid = START + (END - START) * (c3 * t * t * t - c1 * t * t);
        assertEquals(expectedMid, MathAPI.easeInBack(START, END, t), DELTA);
    }

        @Test
    public void testEaseInBounce() {
        assertEquals(START, MathAPI.easeInBounce(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInBounce(START, END, 1), DELTA);
    }

    @Test
    public void testEaseOutBounce() {
        assertEquals(START, MathAPI.easeOutBounce(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeOutBounce(START, END, 1), DELTA);
    }

    @Test
    public void testEaseOutQuad() {
        assertEquals(START, MathAPI.easeOutQuad(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutQuad(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (1 - Math.pow(1 - t, 2));
        assertEquals(expectedMid, MathAPI.easeOutQuad(START, END, t), DELTA);
    }

    @Test
    public void testEaseOutQuart() {
        assertEquals(START, MathAPI.easeOutQuart(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutQuart(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (1 - Math.pow(1 - t, 4));
        assertEquals(expectedMid, MathAPI.easeOutQuart(START, END, t), DELTA);
    }

    @Test
    public void testEaseOutExpo() {
        assertEquals(START, MathAPI.easeOutExpo(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutExpo(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (1 - Math.pow(2, -10 * t));
        assertEquals(expectedMid, MathAPI.easeOutExpo(START, END, t), DELTA);
    }

    @Test
    public void testEaseOutBack() {
        assertEquals(START, MathAPI.easeOutBack(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeOutBack(START, END, 1.0), DELTA);

        double t = 0.5;
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        double adjustedT = t - 1;
        double expectedMid = START + (END - START) * (1 + c3 * Math.pow(adjustedT, 3) + c1 * Math.pow(adjustedT, 2));
        assertEquals(expectedMid, MathAPI.easeOutBack(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutQuad() {
        assertEquals(START, MathAPI.easeInOutQuad(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutQuad(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (1 - Math.pow(-2 * t + 2, 2) / 2);
        assertEquals(expectedMid, MathAPI.easeInOutQuad(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutQuart() {
        assertEquals(START, MathAPI.easeInOutQuart(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutQuart(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (1 - Math.pow(-2 * t + 2, 4) / 2);
        assertEquals(expectedMid, MathAPI.easeInOutQuart(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutExpo() {
        assertEquals(START, MathAPI.easeInOutExpo(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutExpo(START, END, 1.0), DELTA);

        double t = 0.5;
        double expectedMid = START + (END - START) * (Math.pow(2, 20 * t - 10) / 2);
        assertEquals(expectedMid, MathAPI.easeInOutExpo(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutBack() {
        assertEquals(START, MathAPI.easeInOutBack(START, END, 0.0), DELTA);
        assertEquals(END, MathAPI.easeInOutBack(START, END, 1.0), DELTA);

        double t = 0.5;
        final double c1 = 1.70158;
        final double c2 = c1 * 1.525;
        double expectedMid = START + (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2 * (END - START);
        assertEquals(expectedMid, MathAPI.easeInOutBack(START, END, t), DELTA);
    }

    @Test
    public void testEaseInOutBounce() {
        assertEquals(START, MathAPI.easeInOutBounce(START, END, 0), DELTA);
        assertEquals(END, MathAPI.easeInOutBounce(START, END, 1), DELTA);
        assertEquals((END - START) / 2F, MathAPI.easeInOutBounce(START, END, 0.5), DELTA);
    }
}
