package org.watermedia.api.player;

import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;

/**
 * Graphics engine abstraction for uploading decoded video frames to GPU textures.
 * <p>
 * WATERMeDIA creates the texture, uploads pixel data, and exposes a handle.
 * The developer binds that handle in their rendering pipeline.
 * <p>
 * Engines are created through {@link PlayerAPI} factory methods and are client-side only:
 * construction throws on a server-side environment, headless capture included. Backends are
 * OpenGL, Vulkan, software surfaces and headless capture. The owning {@link MediaPlayer} drives
 * formats and uploads from one producer thread; the public surface only queries the engine.
 * <p>
 * Every backend shares one lifecycle contract: {@link #release()} is idempotent, may run on any
 * thread but never concurrently with a format or upload, and leaves the engine inert
 * ({@link #texture()} returns 0, formats are refused and uploads are dropped). A backend failure
 * never throws from an upload: the backend logs it, drops the frame and keeps the last texture it
 * published. Software and headless backends expose a non-zero sentinel texture because they have
 * no GPU handle.
 */
public abstract sealed class GFXEngine permits VKEngine, GLEngine, HeadlessGFXEngine, SWEngine {
    // NON-ZERO HANDLE FOR BACKENDS WITHOUT A GPU TEXTURE (SOFTWARE SURFACES, HEADLESS CAPTURE): NEVER A VALID GL NAME
    static final long SENTINEL_TEXTURE = -1L;

    // CONFIGURED LAYOUT, WRITTEN BY THE PRODUCER (THE RENDER THREAD IN GL) AND READ FROM ANY HOST THREAD
    volatile PixelFormat format;
    volatile int width;
    volatile int height;
    volatile int bits = 8;
    volatile Colorimetry color;
    volatile boolean released;
    // A FRAME IS PRESENT ON A BACKEND WITHOUT A GPU HANDLE (SEE texture); GPU BACKENDS PUBLISH THEIR OWN HANDLE INSTEAD
    volatile boolean framed;

    // SINGLE ENFORCEMENT POINT: EVERY ENGINE CONSTRUCTION PASSES THROUGH HERE, HEADLESS CAPTURE INCLUDED
    GFXEngine() {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Frame width in pixels, or 0 if no format has been set. */
    public int width() { return this.width; }

    /** Frame height in pixels, or 0 if no format has been set. */
    public int height() { return this.height; }

    /** Pixel layout the engine is configured for, or null before the player configures the first format. */
    public PixelFormat format() { return this.format; }

    /** Sample precision in bits per component (8, 10, 12, 16, or 32 for float); 8 before the first format. */
    public int bits() { return this.bits; }

    /** Whether {@link #release()} already ran; a released engine refuses formats and drops uploads. */
    public final boolean released() { return this.released; }

    /** Returns the RGBA texture handle (a GL name, a {@code VkImageView} or a non-zero sentinel without a GPU), 0 before the first frame or after release. */
    public long texture() { return this.released || !this.framed ? 0L : SENTINEL_TEXTURE; }

    /** Whether the engine consumes 8-bit frames in {@code format} directly; see {@link #supports(PixelFormat, int)}. */
    public final boolean supports(final PixelFormat format) { return this.supports(format, 8); }

    /** Whether the engine consumes this layout at this sample precision directly; the producer pre-converts declined layouts, usually to 8-bit BGRA. */
    public boolean supports(final PixelFormat format, final int bits) {
        // RAW LAYOUTS AT 8, 10, 12, 16 OR 32 (FLOAT) BITS; COMPRESSED ONES FALL BACK TO THE DECODE PATH UNLESS A BACKEND OPTS IN
        return format != null && !format.compressed() && (bits == 8 || bits == 10 || bits == 12 || bits == 16 || bits == 32);
    }

    /** Whether this engine can keep a small animated image as one texture per frame instead of one upload per frame. */
    public boolean preload() { return false; }

    /** Releases all GPU resources; later calls are no-ops. Never call it concurrently with an upload or a format change. */
    public final void release() {
        // THE TEST-AND-SET IS ATOMIC SO TWO CONCURRENT RELEASES NEVER TEAR DOWN TWICE
        synchronized (this) {
            if (this.released) return;
            this.released = true;
        }
        this.destroy();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // THE OWNING PLAYER DRIVES EVERY MEMBER OF THIS SECTION FROM ONE PRODUCER THREAD

    // RECONFIGURES FOR AN 8-BIT FORMAT; SEE THE FIVE-ARGUMENT OVERLOAD
    final boolean format(final PixelFormat format, final int width, final int height) {
        return this.format(format, width, height, 8);
    }

    // RECONFIGURES FOR FRAMES WITH THE COLORIMETRY AN UNTAGGED FRAME OF THIS SIZE MOST LIKELY HAS
    final boolean format(final PixelFormat format, final int width, final int height, final int bits) {
        return this.format(format, width, height, bits, Colorimetry.guess(format, width, height));
    }

    // RESETS PLANE TEXTURES, UPLOAD BUFFERS AND BUFFERED FRAMES FOR A NEW LAYOUT; CALLED BEFORE THE FIRST UPLOAD AND ON EVERY
    // RESOLUTION, LAYOUT OR COLORIMETRY CHANGE. FALSE (RELEASED, EMPTY SIZE, UNSUPPORTED FORMAT/BITS) LEAVES THE ENGINE AS IT WAS
    final boolean format(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        if (this.released || width <= 0 || height <= 0 || color == null || !this.supports(format, bits)) return false;
        return this.apply(format, width, height, bits, color);
    }

    // APPLIES A LAYOUT THE BASE ALREADY ACCEPTED; FALSE WHEN THE BACKEND CANNOT BACK IT (E.G. A SURFACE TOO LARGE).
    // THE DEFAULT ONLY PUBLISHES THE FIELDS
    boolean apply(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        this.publish(format, width, height, bits, color);
        return true;
    }

    // PUBLISHES THE LAYOUT ONCE THE BACKEND APPLIED IT (GL DOES SO ON ITS RENDER THREAD, THE REST SYNCHRONOUSLY)
    final void publish(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        this.format = format;
        this.width = width;
        this.height = height;
        this.bits = bits;
        this.color = color;
    }

    // UPLOADS ONE FRAME AS ITS ORDERED PLANES (ONE PER PixelFormat.planes(), EACH OF minBytes; STRIDES IN BYTES, 0 = TIGHT), READING [position, limit)
    // UNMOVED UNTIL THE SECOND LATER upload RETURNS (CALLERS PASS FRESH ARRAYS). AN EMPTY SET, A NULL OR HEAP PLANE OR A MISSING STRIDE IS A CALLER BUG
    final void upload(final ByteBuffer[] planes, final int[] strides) {
        if (planes == null || planes.length == 0 || strides == null || strides.length < planes.length)
            throw new IllegalArgumentException("A frame needs at least one plane and a stride per plane");
        for (final ByteBuffer plane: planes)
            if (plane == null || !plane.isDirect()) throw new IllegalArgumentException("Plane buffers must be direct and non-null");
        if (this.released) return;
        this.push(planes, strides);
    }

    // BACKEND UPLOAD (SEE upload); IMPLEMENTATIONS LOG AND DROP ON FAILURE INSTEAD OF THROWING
    abstract void push(final ByteBuffer[] planes, final int[] strides);

    // OPTIONAL FAST PATH: ONE TEXTURE PER TIGHTLY PACKED FRAME OF THE SET, IN format() (RAW PIXELS OR BCn BLOCKS); THE BUFFERS
    // MUST OUTLIVE THE NEXT preload/format/release AND A PROGRESSIVE ENGINE CLAMPS frame() TO THE UPLOADED PREFIX. FALSE = UNSUPPORTED
    boolean preload(final ByteBuffer[] frames) { return false; }

    // SELECTS THE PRELOADED FRAME TEXTURE EXPOSED BY texture()
    void frame(final int index) {}

    // BASE-ADDRESS ALIGNMENT ZERO-COPY BACKENDS NEED FOR THE upload PLANES (E.G. minImportedHostPointerAlignment); THE
    // PRODUCER THEN ALLOCATES ALIGNED, PADDED BUFFERS. 0 = NO CONSTRAINT (STAGING COPY)
    int alignment() { return 0; }

    // A PLANE BUFFER IS ABOUT TO BE FREED: ZERO-COPY BACKENDS DROP ITS GPU IMPORT HERE, DRAINING PENDING READS FIRST,
    // SINCE DESTROYING AN IMPORT AFTER ITS HOST MEMORY IS GONE CRASHES THE DRIVER. NEVER CONCURRENT WITH upload
    void release(final ByteBuffer buffer) {}

    // BACKEND TEARDOWN, RUN EXACTLY ONCE AFTER released IS SET; GL DEFERS ITS GPU WORK TO THE RENDER THREAD
    abstract void destroy();

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // BYTES A PLANE OF rows ROWS OF rowBytes EACH NEEDS WHEN ITS ROWS START stride APART (0 = TIGHT): THE LAST ROW
    // CARRIES NO PADDING. -1 WHEN THE STRIDE CANNOT HOLD ONE ROW
    static long minBytes(final int stride, final long rowBytes, final int rows) {
        if (stride == 0) return rowBytes * rows;
        return stride < rowBytes ? -1L : (long) (rows - 1) * stride + rowBytes;
    }

    // SHADER FACTOR THAT STRETCHES 10 AND 12-BIT SAMPLES STORED IN 16-BIT TEXELS TO THE FULL [0, 1] RANGE
    static float bitScale(final int bits) {
        return switch (bits) {
            case 10 -> 65_535.0f / 1_023.0f;
            case 12 -> 65_535.0f / 4_095.0f;
            default -> 1.0f;
        };
    }
}
