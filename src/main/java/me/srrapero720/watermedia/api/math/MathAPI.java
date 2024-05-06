package me.srrapero720.watermedia.api.math;

import me.srrapero720.watermedia.api.WaterMediaAPI;
import me.srrapero720.watermedia.loaders.ILoader;

public class MathAPI extends WaterMediaAPI {

    private static final int SIN_SIZE = 65536;
    private static final float[] SIN = new float[SIN_SIZE];

    static {
        for (int i = 0; i < SIN_SIZE; i++) {
            SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / SIN_SIZE);
        }
    }

    /**
     * 1 seconds in Minecraft equals 20 ticks
     * 20x50 equals 1000ms (1 sec)
     *
     * @param ticks Minecraft Tick count
     * @return ticks converted to MS
     * @deprecated <p>Tick type was changed from int to long following the time counting standard.</p>
     * Use instead {@link MathAPI#tickToMs(long)}
     */
    @Deprecated
    public static long tickToMs(int ticks) { return ticks * 50L; }

    /**
     * 1 seconds in Minecraft equals 20 ticks
     * 20x50 equals 1000ms (1 sec)
     *
     * @param ticks Minecraft Tick count
     * @return ticks converted to MS
     */
    public static long tickToMs(long ticks) { return ticks * 50L; }

    /**
     * 1 seconds in Minecraft equals 20 ticks
     * 20x50 equals 1000ms (1 sec)
     *
     * @param partialTicks Minecraft Partial tick count
     * @return ticks converted to MS
     */
    public static long tickToMs(float partialTicks) { return (long) (partialTicks * 50L); }

    /**
     * 1000ms (1 sec) equals 20 ms in Minecraft
     * 1000/50 equals 20 Ticks (1 sec)
     *
     * @param ms Time in milliseconds
     * @return Milliseconds converted to Ticks
     */
    public static int msToTick(long ms) { return (int) (ms / 50); }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleTempo(long start, long end, long time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");

        long duration = end - start; // start acts like a margin
        long realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        long result = realTime / duration;
        if (realTime > duration) result %= duration;
        return result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleTempo(double start, double end, double time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        double duration = end - start; // start acts like a margin
        double realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        double result = realTime / duration;
        if (realTime > duration) result %= duration;
        return result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be reset to zero
     * ej start: 0, end: 10, time: 15 - Result will be 0.5d
     * Method helper to calculate time in ticks (20t = 1s)
     * @param startTick initial point to calculate scale, cannot be negative
     * @param endTick final point to calculate scale. cannot be negative
     * @param timeTick current time between values
     * @return scaled time
     */
    public static double scaleTempoTick(int startTick, int endTick, int timeTick) {
        return scaleTempo(MathAPI.tickToMs(startTick), MathAPI.tickToMs(endTick), MathAPI.tickToMs(timeTick));
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleDesTempo(long start, long end, long time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        long duration = end - start; // start acts like a margin
        long realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        return (double) realTime / duration;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * @param start initial point to calculate scale, cannot be negative
     * @param end final point to calculate scale. cannot be negative
     * @param time current time between values
     * @return scaled time
     */
    public static double scaleDesTempo(double start, double end, double time) {
        if (start < 0 || end < 0 || time < 0) throw new IllegalArgumentException("Invalid negative value");
        double duration = end - start; // start acts like a margin
        double realTime = time - start;

        if (realTime == 0 || duration == 0) return 0; // not ArithmeticException

        double result = realTime / duration;
        return (Double.isNaN(result)) ? 0 : result;
    }

    /**
     * Returns a precise scale of a start time and end time.
     * If time is out of range, it will be over-scaled
     * ej start: 0, end: 10, time: 15 - Result will be 1.5d
     * Method helper to calculate time in ticks (20t = 1s)
     * @param startTick initial point to calculate scale, cannot be negative
     * @param endTick final point to calculate scale. cannot be negative
     * @param timeTick current time between values
     * @return scaled time
     */
    public static double scaleDesTempoTick(int startTick, int endTick, int timeTick) {
        return scaleTempo(MathAPI.tickToMs(startTick), MathAPI.tickToMs(endTick), MathAPI.tickToMs(timeTick));
    }

    /**
     * Returns the floor modulus of the {@code long} arguments.
     * <p>
     * The floor modulus is {@code r = x - (floorDiv(x, y) * y)},
     * has the same sign as the divisor {@code y} or is zero, and
     * is in the range of {@code -abs(y) < r < +abs(y)}.
     *
     * <p>
     * The relationship between {@code floorDiv} and {@code floorMod} is such that:
     * <ul>
     *   <li>{@code floorDiv(x, y) * y + floorMod(x, y) == x}</li>
     * </ul>
     * <p>
     *
     *     Method doesn't throw exceptions when X and Y is ZERO.
     *     Instead, returns ZERO by default
     *
     * @param x the dividend
     * @param y the divisor
     * @return the floor modulus {@code x - (floorDiv(x, y) * y)}
     * @since 2.0.7
     */
    public static long floorMod(long x, long y) {
        if (x == 0 || y == 0) return 0;

        final long r = x % y;
        // if the signs are different and modulo not zero, adjust result
        if ((x ^ y) < 0 && r != 0) {
            return r + y;
        }
        return r;
    }

    public static int floorMod(int x, int y) {
        if (x == 0 || y == 0) return 0;

        final int r = x % y;
        // if the signs are different and modulo not zero, adjust result
        if ((x ^ y) < 0 && r != 0) {
            return r + y;
        }
        return r;
    }

    public static int floorMod(long x, int y) {
        // Result cannot overflow the range of int.
        return (int) floorMod(x, (long)y);
    }

    public static int floorMod(int x, long y) {
        // Result cannot overflow the range of int.
        return (int) floorMod((long) x, y);
    }

    public static short min(short a, short b) {
        return (a <= b) ? a : b;
    }

    public static byte min(byte a, byte b) {
        return (a <= b) ? a : b;
    }

    /**
     * Creates a hexadecimal color based on gave params
     * All values need to be in a range of 0 ~ 255
     * @param a Alpha
     * @param r Red
     * @param g Green
     * @param b Blue
     * @return HEX color
     */
    public static int argb(int a, int r, int g, int b) { return (a << 24) | (r << 16) | (g << 8) | b; }

    /**
     * Converts arguments into an ease-in value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in interpolation between start and end at time t.
     */
    public static double easeIn(double start, double end, double t) {
        return start + (end - start) * t * t;
    }

    /**
     * Converts arguments into an ease-out value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out interpolation between start and end at time t.
     */
    public static double easeOut(double start, double end, double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 2));
    }

    /**
     * Converts arguments into an ease-in-out value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out interpolation between start and end at time t.
     */
    public static double easeInOut(double start, double end, double t) {
        return t < 0.5 ? easeIn(start, end / 2, t * 2) : easeOut(start + (end / 2), end, (t - 0.5) * 2);
    }

    /**
     * Converts arguments into an ease-out-in value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-in interpolation between start and end at time t.
     */
    public static double easeOutIn(double start, double end, double t) {
        return t < 0.5 ? easeOut(start, end / 2, t * 2) : easeIn(start + (end / 2), end, (t - 0.5) * 2);
    }

    /**
     * Converts arguments into an ease-in-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-circle interpolation between start and end at time t.
     */
    public static double easeInCircle(double start, double end, double t) {
        return start + (end - start) * (1 - Math.sqrt(1 - t * t));
    }

    /**
     * Converts arguments into an easy-ease value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of easy-ease interpolation between start and end at time t.
     */
    public static double easyEase(double start, double end, double t) {
        return start + (end - start) * ((t < 0.5) ? 2 * t * t : -1 + 2 * t * (2 - t));
    }

    /**
     * Converts arguments into an ease-in-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-sine interpolation between start and end at time t.
     */
    public static double easeInSine(double start, double end, double t) {
        return (end - start) * (1 - cos((float) (t * Math.PI / 2))) + start;
    }

    /**
     * Converts arguments into an ease-in-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-cubic interpolation between start and end at time t.
     */
    public static double easeInCubic(double start, double end, double t) {
        return (end - start) * (t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quint interpolation between start and end at time t.
     */
    public static double easeInQuint(double start, double end, double t) {
        return (end - start) * (t * t * t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-elastic interpolation between start and end at time t.
     */
    public static double easeInElastic(double start, double end, double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        double c4 = (2 * Math.PI) / 3;
        return start - Math.pow(2, 10 * t - 10) * sin((float) ((t * 10 - 10.75) * c4)) * (end - start);
    }

    /**
     * Converts arguments into an ease-out-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-sine interpolation between start and end at time t.
     */
    public static double easeOutSine(double start, double end, double t) {
        return (end - start) * sin((float) (t * Math.PI / 2)) + start;
    }

    /**
     * Converts arguments into an ease-out-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-cubic interpolation between start and end at time t.
     */
    public static double easeOutCubic(double start, double end, double t) {
        return (end - start) * (1 - Math.pow(1 - t, 3)) + start;
    }

    /**
     * Converts arguments into an ease-out-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quint interpolation between start and end at time t.
     */
    public static double easeOutQuint(double start, double end, double t) {
        return (end - start) * (1 - Math.pow(1 - t, 5)) + start;
    }

    /**
     * Converts arguments into an ease-out-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-circle interpolation between start and end at time t.
     */
    public static double easeOutCircle(double start, double end, double t) {
        return (end - start) * Math.sqrt(1 - Math.pow(t - 1, 2)) + start;
    }

    /**
     * Converts arguments into an ease-out-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-elastic interpolation between start and end at time t.
     */
    public static double easeOutElastic(double start, double end, double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        return (end - start) * (Math.pow(2, -10 * t) * sin((float) ((t * 10 - 0.75) * ((2 * Math.PI) / 3))) + 1) + start;
    }

    /**
     * Converts arguments into an ease-in-out-sine value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-sine interpolation between start and end at time t.
     */
    public static double easeInOutSine(double start, double end, double t) {
        return (end - start) * (-(cos((float) (Math.PI * t)) - 1) / 2) + start;
    }

    /**
     * Converts arguments into an ease-in-out-cubic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-cubic interpolation between start and end at time t.
     */
    public static double easeInOutCubic(double start, double end, double t) {
        if (t < 0.5) {
            return (end - start) * (4 * t * t * t) + start;
        } else {
            return (end - start) * (1 - Math.pow(-2 * t + 2, 3) / 2) + start;
        }
    }

    /**
     * Converts arguments into an ease-in-out-quint value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-int-out-quint interpolation between start and end at time t.
     */
    public static double easeInOutQuint(double start, double end, double t) {
        if (t < 0.5) {
            return (end - start) * (16 * Math.pow(t, 5)) + start;
        } else {
            return (end - start) * (1 - Math.pow(-2 * t + 2, 5) / 2) + start;
        }
    }

    /**
     * Converts arguments into an ease-in-out-circle value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-circle interpolation between start and end at time t.
     */
    public static double easeInOutCircle(double start, double end, double t) {
        if (t < 0.5) {
            return start + (end - start) * (1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2;
        } else {
            return start + (end - start) * (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2;
        }
    }

    /**
     * Converts arguments into an ease-in-out-elastic value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-elastic interpolation between start and end at time t.
     */
    public static double easeInOutElastic(double start, double end, double t) {
        if (t == 0) {
            return start;
        } else if (t == 1) {
            return end;
        }
        double c5 = (2 * Math.PI) / 4.5;
        if (t < 0.5) {
            return start - (Math.pow(2, 20 * t - 10) * sin((float) ((20 * t - 11.125) * c5))) / 2;
        } else {
            return start + (Math.pow(2, -20 * t + 10) * sin((float) ((20 * t - 11.125) * c5))) / 2 + (end - start);
        }
    }

    /**
     * Converts arguments into an ease-in-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quad interpolation between start and end at time t.
     */
    public static double easeInQuad(double start, double end, double t) {
        return (end - start) * (t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-quart interpolation between start and end at time t.
     */
    public static double easeInQuart(double start, double end, double t) {
        return (end - start) * (t * t * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-expo interpolation between start and end at time t.
     */
    public static double easeInExpo(double start, double end, double t) {
        if (t == 0) {
            return start;
        }
        return (end - start) * (Math.pow(2, 10 * t - 10)) + start;
    }

    /**
     * Converts arguments into an ease-in-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-back interpolation between start and end at time t.
     */
    public static double easeInBack(double start, double end, double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        return (end - start) * (c3 * t * t * t - c1 * t * t) + start;
    }

    /**
     * Converts arguments into an ease-in-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-bounce interpolation between start and end at time t.
     */
    public static double easeInBounce(double start, double end, double t) {
        return start + (end - start) * (1 - easeOutBounce(0, 1, 1 - t));
    }

    /**
     * Converts arguments into an ease-out-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-bounce interpolation between start and end at time t.
     */
    public static double easeOutBounce(double start, double end, double t) {
        final double n1 = 7.5625;
        final double d1 = 2.75;
        double value;

        if (t < 1 / d1) {
            value = n1 * t * t;
        } else if (t < 2 / d1) {
            t -= 1.5 / d1;
            value = n1 * t * t + 0.75;
        } else if (t < 2.5 / d1) {
            t -= 2.25 / d1;
            value = n1 * t * t + 0.9375;
        } else {
            t -= 2.625 / d1;
            value = n1 * t * t + 0.984375;
        }

        return start + (end - start) * value;
    }

    /**
     * Converts arguments into an ease-out-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quad interpolation between start and end at time t.
     */
    public static double easeOutQuad(double start, double end, double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 2));
    }

    /**
     * Converts arguments into an ease-out-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-quart interpolation between start and end at time t.
     */
    public static double easeOutQuart(double start, double end, double t) {
        return start + (end - start) * (1 - Math.pow(1 - t, 4));
    }

    /**
     * Converts arguments into an ease-out-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-expo interpolation between start and end at time t.
     */
    public static double easeOutExpo(double start, double end, double t) {
        if (t == 1) {
            return end;
        }
        return start + (end - start) * (1 - Math.pow(2, -10 * t));
    }

    /**
     * Converts arguments into an ease-out-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-out-back interpolation between start and end at time t.
     */
    public static double easeOutBack(double start, double end, double t) {
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        double adjustedT = t - 1;
        return start + (end - start) * (1 + c3 * Math.pow(adjustedT, 3) + c1 * Math.pow(adjustedT, 2));
    }

    /**
     * Converts arguments into an ease-in-out-quad value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-quad interpolation between start and end at time t.
     */
    public static double easeInOutQuad(double start, double end, double t) {
        if (t < 0.5) {
            return start + (end - start) * (2 * t * t);
        } else {
            return start + (end - start) * (1 - Math.pow(-2 * t + 2, 2) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-quart value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-quart interpolation between start and end at time t.
     */
    public static double easeInOutQuart(double start, double end, double t) {
        if (t < 0.5) {
            return start + (end - start) * (8 * Math.pow(t, 4));
        } else {
            return start + (end - start) * (1 - Math.pow(-2 * t + 2, 4) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-expo value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-expo interpolation between start and end at time t.
     */
    public static double easeInOutExpo(double start, double end, double t) {
        if (t == 0) return start;
        if (t == 1) return end;

        if (t < 0.5) {
            return start + (end - start) * (Math.pow(2, 20 * t - 10) / 2);
        } else {
            return start + (end - start) * ((2 - Math.pow(2, -20 * t + 10)) / 2);
        }
    }

    /**
     * Converts arguments into an ease-in-out-back value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-back interpolation between start and end at time t.
     */
    public static double easeInOutBack(double start, double end, double t) {
        final double c1 = 1.70158;
        final double c2 = c1 * 1.525;

        if (t < 0.5) {
            return start + (Math.pow(2 * t, 2) * ((c2 + 1) * 2 * t - c2)) / 2 * (end - start);
        } else {
            return start + (Math.pow(2 * t - 2, 2) * ((c2 + 1) * (2 * t - 2) + c2) + 2) / 2 * (end - start);
        }
    }

    /**
     * Converts arguments into an ease-in-out-bounce value usable on animations.
     *
     * @param start The beginning of the result across time.
     * @param end The end of the result across time.
     * @param t Time from 0.0 to 1.0
     * @return The calculated result of ease-in-out-bounce interpolation between start and end at time t.
     */
    public static double easeInOutBounce(double start, double end, double t) {
        if (t < 0.5) {
            return start + (end - start) * (1 - easeOutBounce(0, 1, 1 - 2 * t)) / 2;
        } else {
            return start + (end - start) * (1 + easeOutBounce(0, 1, 2 * t - 1)) / 2;
        }
    }

    /**
     * Returns an approximate sine value for a given angle from a precomputed lookup table.
     *
     * @param pValue The angle in radians.
     * @return The approximate sine of the angle.
     */
    public static float sin(float pValue) {
        return SIN[(int)(pValue * 10430.378F) & 0xFFFF];
    }

    /**
     * Returns an approximate cosine value for a given angle from a precomputed lookup table.
     *
     * @param pValue The angle in radians.
     * @return The approximate cosine of the angle.
     */
    public static float cos(float pValue) {
        return SIN[(int)(pValue * 10430.378F + 16384.0F) & '\uffff'];
    }

    @Override
    public Priority priority() {
        return Priority.LOWEST;
    }

    @Override
    public boolean prepare(ILoader bootCore) throws Exception {
        return false;
    }

    @Override
    public void start(ILoader bootCore) throws Exception {

    }

    @Override
    public void release() {

    }
}