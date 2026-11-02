package org.watermedia.api.player;

import java.nio.ByteBuffer;

/**
 * Headless {@link GFXEngine} that records frames into memory instead of a GPU.
 * <p>
 * There is no OpenGL or Vulkan context: uploads are captured, not rendered. It lets a
 * {@link MediaPlayer} run its full decode/upload pipeline where no display is available (CI,
 * headless validation, image processing) and lets callers introspect what the pipeline pushed
 * ({@link #uploadCount()}, {@link #lastUpload()}, {@link #format()}, {@link #activeFrame()}). It
 * needs no GPU but, like every engine and media player, it only exists on a client.
 * <p>
 * {@link #texture()} returns a non-zero sentinel once the first frame is captured (0 before),
 * matching the base contract; there is no real texture to bind. Every mutable field is
 * {@code volatile} because the player's lifecycle thread writes them while another thread may read them.
 */
public final class HeadlessGFXEngine extends GFXEngine {
    private final boolean preload;
    private volatile long uploadCount;
    private volatile int activeFrame;
    private volatile ByteBuffer[] preloaded;
    private volatile ByteBuffer lastUpload;

    HeadlessGFXEngine() {
        this(false);
    }

    HeadlessGFXEngine(final boolean preload) {
        this.preload = preload;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public boolean preload() { return this.preload; }

    /** Number of {@code upload} calls received so far. */
    public long uploadCount() { return this.uploadCount; }
    /** Index of the preloaded frame the player last selected. */
    public int activeFrame() { return this.activeFrame; }
    /** Y (or sole) plane of the most recent {@code upload}, or null. */
    public ByteBuffer lastUpload() { return this.lastUpload; }

    /** Copy of the frame set the player last preloaded, or null. */
    public ByteBuffer[] preloadedFrames() {
        final ByteBuffer[] frames = this.preloaded;
        return frames == null ? null : frames.clone();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    boolean preload(final ByteBuffer[] frames) {
        // HONOR THE ADVERTISED CAPABILITY LIKE A REAL BACKEND; CLONE ON STORE SO THE PRODUCER'S ARRAY IS NEVER ALIASED
        if (!this.preload || this.released || frames == null || frames.length == 0) return false;
        this.preloaded = frames.clone();
        this.framed = true;
        return true;
    }

    @Override
    void frame(final int index) {
        this.activeFrame = index;
    }

    @Override
    void push(final ByteBuffer[] planes, final int[] strides) {
        this.lastUpload = planes[0];
        this.uploadCount++;
        this.framed = true;
    }

    @Override
    void destroy() {
        // NOTHING NATIVE TO FREE: THE CAPTURED BUFFERS BELONG TO THE PRODUCER
    }
}
