package org.watermedia.api.util;

import java.util.Set;

/** Quality levels of media streams, each named by the smallest side its bucket starts at. */
public enum MediaQuality {
    /** Unknown size. */
    UNKNOWN(0),
    /** Any known size below {@link #LOWER}. */
    LOWEST(144),
    /** From 240 pixels up to {@link #LOW}. */
    LOWER(240),
    /** From 360 pixels up to {@link #LOWISH}. */
    LOW(360),
    /** From 480 pixels up to {@link #MEDIUM}. */
    LOWISH(480),
    /** From 720 pixels up to {@link #HIGH}. */
    MEDIUM(720),
    /** From 1080 pixels up to {@link #HIGHER}. */
    HIGH(1_080),
    /** From 1440 pixels up to {@link #HIGHEST}. */
    HIGHER(1_440),
    /** From 2160 pixels up to {@link #ULTRA_8K}. */
    HIGHEST(2_160),
    /** 4320 pixels and above. */
    ULTRA_8K(4_320);

    private static final MediaQuality[] VALUES = values();

    private final int height;

    MediaQuality(final int threshold) {
        this.height = threshold;
    }

    /** Returns the vertical resolution this level names, in pixels; zero for {@link #UNKNOWN}. */
    public int height() { return this.height; }

    /** Determines quality from a single dimension; see {@link #of(int, int)}. */
    public static MediaQuality of(final int resolution) {
        return of(resolution, resolution);
    }

    /** Determines quality from the smallest known side (1920x1080 is {@link #HIGH}); zero means unknown, and both unknown is {@link #UNKNOWN}. */
    public static MediaQuality of(final int width, final int height) {
        final int size;
        if (width <= 0 && height <= 0) return UNKNOWN;
        else if (width <= 0) size = height;
        else if (height <= 0) size = width;
        else size = Math.min(width, height);

        // UNKNOWN (INDEX 0) IS NO THRESHOLD: A KNOWN SIZE UNDER EVERY REAL ONE IS STILL THE LOWEST QUALITY
        for (int i = VALUES.length - 1; i > LOWEST.ordinal(); i--) {
            if (size >= VALUES[i].height) return VALUES[i];
        }
        return LOWEST;
    }

    /** Returns the available quality closest to {@code preferred}, the lower one on a tie, or null when none is available. */
    public static MediaQuality closest(final Set<MediaQuality> available, final MediaQuality preferred) {
        if (preferred == null) throw new IllegalArgumentException("Preferred quality cannot be null");
        if (available == null || available.isEmpty()) return null;

        // LINEAR MIN-DISTANCE SCAN; ASCENDING ORDER BREAKS TIES TOWARD THE LOWER QUALITY
        MediaQuality best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final MediaQuality quality: VALUES) {
            if (!available.contains(quality)) continue;
            final int distance = Math.abs(quality.ordinal() - preferred.ordinal());
            if (distance < bestDistance) {
                best = quality;
                bestDistance = distance;
            }
        }
        return best;
    }
}
