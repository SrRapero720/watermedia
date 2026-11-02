package org.watermedia.api.codecs;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decoded image container, animated or static.
 *
 * @param frames   decoded frames, each a single tightly packed buffer laid out in {@code format}
 * @param format   pixel layout of every frame
 * @param width    frame width; {@link ImageReader#readAll()} reports the last frame's width after filters
 * @param height   frame height; {@link ImageReader#readAll()} reports the last frame's height after filters
 * @param delays   per-frame delay in milliseconds; {@code 0} advances to the next frame immediately
 * @param duration single-cycle playback time, the sum of every entry in {@code delays}; use
 *                 {@link #duration(boolean)} for the repeat-aware value
 * @param repeat   total plays: {@link #REPEAT_FOREVER} to loop forever, otherwise the number of
 *                 plays the container declares, {@code 1} for a static image
 */
public record ImageData(ByteBuffer[] frames, PixelFormat format, int width, int height, long[] delays, long duration, int repeat) {
    public static final int REPEAT_FOREVER = 0;

    public ImageData {
        if (frames == null || frames.length == 0) throw new IllegalArgumentException("At least one image is required");
        if (format == null) throw new IllegalArgumentException("Pixel format cannot be null");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Width and height must be positive");
        if (delays == null || delays.length != frames.length) throw new IllegalArgumentException("Delay array must match the number of frames");
        for (final long delay: delays) if (delay < 0) throw new IllegalArgumentException("Delays must not be negative");
        if (duration < 0) throw new IllegalArgumentException("Total duration must not be negative");
        if (repeat < REPEAT_FOREVER) throw new IllegalArgumentException("Repeat must be REPEAT_FOREVER (0) or the number of plays");
    }

    /** Creates image data whose single-cycle duration is the sum of {@code delays}. */
    public ImageData(final ByteBuffer[] frames, final PixelFormat format, final int width, final int height, final long[] delays, final int repeat) {
        this(frames, format, width, height, delays, sum(delays), repeat);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Per-frame delays in milliseconds, as a copy the caller owns. */
    @Override
    public long[] delays() {
        return this.delays.clone();
    }

    @Override
    public long duration() {
        return this.duration(false);
    }

    /** Repeat-aware total playback time: one cycle multiplied by the play count (one cycle for a forever loop). */
    public long duration(final boolean repeatAware) {
        return this.duration * (repeatAware ? Math.max(1, this.repeat) : 1);
    }

    @Override
    public String toString() {
        return "Image{" +
                "frames=" + Arrays.toString(this.frames) +
                ", format=" + this.format +
                ", width=" + this.width +
                ", height=" + this.height +
                ", delays=" + Arrays.toString(this.delays) +
                ", duration=" + this.duration +
                ", repeat=" + this.repeat +
                '}';
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // SUM OF THE DELAYS; A NULL ARRAY IS REJECTED BY THE CONSTRUCTOR CHECK THAT FOLLOWS
    private static long sum(final long[] delays) {
        if (delays == null) return 0L;
        long total = 0L;
        for (final long delay: delays) total += delay;
        return total;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /**
     * Lightweight scan result produced by an {@link ImageReader} at construction time.
     * Carries the animation shape (frame count, per-frame delays, total duration, repeat count)
     * without holding any decoded pixel data.
     */
    public record Scan(int frameCount, long[] delays, long duration, int repeat) {
        public static final Scan EMPTY = new Scan(1, new long[] { 0L }, 0L, 1);

        public Scan {
            if (delays == null || delays.length != frameCount) throw new IllegalArgumentException("Delay array must match the frame count");
            for (final long delay: delays) if (delay < 0) throw new IllegalArgumentException("Delays must not be negative");
            if (duration < 0) throw new IllegalArgumentException("Total duration must not be negative");
            if (repeat < REPEAT_FOREVER) throw new IllegalArgumentException("Repeat must be REPEAT_FOREVER (0) or the number of plays");
        }

        /** Creates a scan of one frame per delay, summing the duration. */
        public static Scan of(final long[] delays, final int repeat) {
            if (delays == null) throw new IllegalArgumentException("Delays are required");
            return new Scan(delays.length, delays, sum(delays), repeat);
        }

        /** Per-frame delays in milliseconds, as a copy the caller owns. */
        @Override
        public long[] delays() {
            return this.delays.clone();
        }
    }
}
