package org.watermedia.api.codecs;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Post-processor applied to every frame an {@link ImageReader} returns from {@code next()} and
 * {@code readAll()}. A filter takes a frame with its geometry and returns a transformed one (rescaled,
 * recolored, ...), so filters chain: each consumes the previous output's properties. Return {@code src}
 * untouched for a no-op or a buffer allocated in this call; never rewrite {@code src} in place, because
 * readers hand the same buffer out again after {@code reset()} and {@code readAll()} keeps every buffer a
 * filter returns.
 */
public interface ReaderFilter {
    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Transforms one frame. The returned buffer must be positioned at {@code 0} and ready to read.
     * Return {@code src} unchanged when the transform is a no-op for this frame.
     */
    Frame compute(final Frame src);

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** A decoded frame paired with the geometry a filter needs to read and rewrite it. */
    record Frame(ByteBuffer buffer, int width, int height, PixelFormat format) {}
}
