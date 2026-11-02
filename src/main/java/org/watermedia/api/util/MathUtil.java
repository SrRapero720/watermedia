package org.watermedia.api.util;

import java.util.Locale;

/**
 * Numeric helpers shared by the players, the decoders and the engines.
 * <p>
 * This API is safe to use even if watermedia isn't successfully loaded.
 */
public final class MathUtil {
    private MathUtil() {}

    /** Converts minutes to milliseconds, truncating anything below one millisecond. */
    public static long minutesToMs(final double min) { return (long) (min * 60_000.0); }

    /** Limits a value to the inclusive range between {@code min} and {@code max}; inverted bounds ({@code min > max}) always return {@code max}. */
    public static int clamp(final int value, final int min, final int max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Limits a value to the inclusive range between {@code min} and {@code max}; inverted bounds always return {@code max}. */
    public static long clamp(final long value, final long min, final long max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Limits a value to the inclusive range between {@code min} and {@code max}; inverted bounds always return {@code max}. */
    public static float clamp(final float value, final float min, final float max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Limits a value to the inclusive range between {@code min} and {@code max}; inverted bounds always return {@code max}. */
    public static double clamp(final double value, final double min, final double max) {
        return Math.min(Math.max(value, min), max);
    }

    /** Limits a value to the unsigned-byte range, 0 to 255. */
    public static int clip255(final int value) {
        return clamp(value, 0, 255);
    }

    /** Formats a byte count as a human-readable string with a dot decimal separator in every locale, scaling from B to KB, MB and GB at 1024 boundaries. */
    public static String displayBytes(final long bytes) {
        if (bytes < 1_024L) return bytes + " B";
        if (bytes < 1_024L * 1_024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0);
        if (bytes < 1_024L * 1_024 * 1_024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1_024.0 * 1_024 * 1_024));
    }
}
