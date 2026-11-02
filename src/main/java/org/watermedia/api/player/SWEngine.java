package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Software {@link GFXEngine} base for CPU render targets that consume a packed BGRA frame
 * instead of a GPU texture: JavaFX ({@link JFXEngine}) and AWT/Swing ({@link AWTEngine}).
 * <p>
 * It only accepts single-plane 8-bit {@code BGRA}/{@code RGBA}; every planar or packed-YUV format
 * is declined, so the frame producer pre-converts those to BGRA through the decoder's scaler. Each
 * frame is written into a reusable direct BGRA buffer that the concrete engine wraps into (or copies
 * to) its native surface. Because there is no GPU texture, {@link #texture()} returns a non-zero
 * sentinel once the first frame is uploaded (0 before), matching the base contract; the real output
 * is the surface the subclass exposes.
 * <p>
 * A frame arrives on the player's decode thread: {@code upload(ByteBuffer[], int[])} writes the buffer
 * and calls {@code present()} to publish it, then fires the optional {@code onFrame} callback so a
 * UI can repaint; a callback that throws is logged and never reaches the decoder. The surface is
 * reallocated on every {@code format(PixelFormat, int, int, int)} (resolution change), and a size
 * the backend cannot allocate refuses the format and leaves no surface.
 */
public abstract sealed class SWEngine extends GFXEngine permits JFXEngine, AWTEngine {
    private static final Marker IT = MarkerManager.getMarker(SWEngine.class.getSimpleName());

    // REUSABLE DIRECT BGRA SURFACE OF width*height*4 BYTES, REALLOCATED ON RESIZE, NULL WHILE NO LAYOUT IS BACKED
    volatile ByteBuffer bgra;
    // OPTIONAL "FRAME READY" HOOK, RUN ON THE UPLOAD THREAD AFTER EACH present()
    private final Runnable onFrame;
    // ANOMALY LATCHES: A SHORT BUFFER OR ROW AND A FAILING CALLBACK ARE LOGGED ONCE PER ENGINE
    private boolean shortWarned;
    private boolean strideWarned;
    private boolean callbackWarned;

    SWEngine(final Runnable onFrame) {
        this.onFrame = onFrame;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public boolean supports(final PixelFormat format, final int bits) {
        // ANYTHING ELSE (PLANAR/PACKED YUV, RGB, GRAY, BCn, DEEP COLOR) IS DECLINED SO THE PRODUCER CONVERTS IT TO BGRA
        return (format == PixelFormat.BGRA || format == PixelFormat.RGBA) && bits == 8;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    boolean apply(final PixelFormat format, final int width, final int height, final int bits, final Colorimetry color) {
        // DROP THE PREVIOUS SURFACE FIRST SO A FAILED ALLOCATION NEVER LEAVES A STALE IMAGE ON SCREEN
        this.framed = false;
        this.bgra = null;
        this.disposeSurface();
        final long bytes = (long) width * height * 4;
        if (bytes > Integer.MAX_VALUE) {
            LOGGER.warn(IT, "Surface {}x{} needs {} bytes, above {}; the format is refused", width, height, bytes, Integer.MAX_VALUE);
            return false;
        }
        final ByteBuffer surface;
        try {
            surface = ByteBuffer.allocateDirect((int) bytes).order(ByteOrder.nativeOrder());
            this.allocSurface(width, height, surface);
        } catch (final RuntimeException | OutOfMemoryError e) {
            this.disposeSurface();
            LOGGER.error(IT, "Failed to allocate the {}x{} surface", width, height, e);
            return false;
        }
        this.bgra = surface;
        this.publish(format, width, height, bits, color);
        return true;
    }

    @Override
    void push(final ByteBuffer[] planes, final int[] strides) {
        // ONLY SINGLE-PLANE BGRA/RGBA IS ACCEPTED (supports), SO THE FIRST PLANE IS THE WHOLE FRAME
        final ByteBuffer buffer = planes[0];
        final int stride = strides[0];
        final ByteBuffer dst = this.bgra;
        if (dst == null) return;
        final int w = this.width;
        final int h = this.height;
        final int rowBytes = w * 4;
        final int srcStride = stride == 0 ? rowBytes : stride;
        // WARN-DROP LIKE THE GPU ENGINES INSTEAD OF THROWING FROM THE DECODE THREAD ON A SHORT BUFFER OR ROW
        final long needed = minBytes(stride, rowBytes, h);
        if (needed < 0L) {
            if (!this.strideWarned) LOGGER.warn(IT, "Stride {} cannot hold a {}-byte row; frame dropped", stride, rowBytes);
            this.strideWarned = true;
            return;
        }
        if (buffer.remaining() < needed) {
            if (!this.shortWarned) LOGGER.warn(IT, "Frame buffer too small: {} < {}", buffer.remaining(), needed);
            this.shortWarned = true;
            return;
        }
        final ByteBuffer src = buffer.duplicate();
        final int base = src.position();

        dst.clear();
        if (this.format == PixelFormat.RGBA) {
            // SWIZZLE RGBA->BGRA WITH ONE 32-BIT OP PER PIXEL (SWAP R AND B). FORCING LE VIEWS MAKES
            // THE BYTE MATH HOST-AGNOSTIC: THE OUTPUT MEMORY IS ALWAYS B,G,R,A REGARDLESS OF ENDIANNESS
            final ByteBuffer s = src.order(ByteOrder.LITTLE_ENDIAN);
            final ByteBuffer d = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int row = 0; row < h; row++) {
                int o = base + row * srcStride;
                for (int x = 0; x < w; x++, o += 4) {
                    final int p = s.getInt(o); // 0xAABBGGRR
                    d.putInt((p & 0xFF00FF00) | ((p >>> 16) & 0xFF) | ((p & 0xFF) << 16));
                }
            }
        } else {
            // BGRA: COPY EACH ROW, DROPPING ANY STRIDE PADDING
            for (int row = 0; row < h; row++) {
                final int o = base + row * srcStride;
                src.limit(o + rowBytes).position(o);
                dst.put(src);
            }
        }

        this.framed = true;
        this.present();
        if (this.onFrame == null) return;
        // THE HOST CALLBACK RUNS ON THE DECODE THREAD: ITS FAILURES ARE REPORTED HERE, NEVER THROWN AT THE DECODER
        try { this.onFrame.run(); }
        catch (final RuntimeException e) {
            if (!this.callbackWarned) LOGGER.error(IT, "Frame callback failed", e);
            this.callbackWarned = true;
        }
    }

    @Override
    void destroy() {
        this.bgra = null;
        this.disposeSurface();
    }

    // CREATES THE NATIVE SURFACE FOR A NEW SIZE; jfx WRAPS bgra ZERO-COPY, awt KEEPS ITS OWN IMAGE. A THROW REFUSES THE FORMAT
    abstract void allocSurface(final int width, final int height, final ByteBuffer bgra);

    // PUBLISHES THE FRAME JUST WRITTEN INTO bgra (FX-THREAD updateBuffer / AWT PIXEL CONVERSION)
    abstract void present();

    // DROPS THE NATIVE SURFACE BEFORE A REALLOCATION AND ON release()
    abstract void disposeSurface();
}
