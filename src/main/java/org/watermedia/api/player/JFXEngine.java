package org.watermedia.api.player;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * {@link SWEngine} that renders frames into a JavaFX {@link Image}.
 * <p>
 * The engine's direct BGRA buffer backs a {@link PixelBuffer} with no copy, so a decoded frame is
 * visible to JavaFX the moment {@link PixelBuffer#updateBuffer} runs. Bind {@link #image()} to an
 * {@code ImageView}; re-read it after {@code onFrame} because a resolution change replaces the image.
 * <p>
 * Requires a running JavaFX toolkit: {@code updateBuffer} is marshalled to the Application Thread via
 * {@link Platform#runLater}. {@link PixelBuffer} only accepts premultiplied BGRA
 * ({@link PixelFormat#getByteBgraPreInstance()}), so each frame is premultiplied before it is published.
 */
public final class JFXEngine extends SWEngine {
    private static final Marker IT = MarkerManager.getMarker(JFXEngine.class.getSimpleName());
    // BOTH ARE REPLACED ON THE DECODE THREAD AND READ FROM THE FX THREAD (repaint), HENCE volatile
    private volatile WritableImage image;
    private volatile PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile boolean loggedPresent;

    JFXEngine(final Runnable onFrame) {
        super(onFrame);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** The current frame image; changes on a resolution change, so re-read it after each frame. */
    public Image image() {
        return this.image;
    }

    /** Re-marks the current frame dirty; software Prism repaints only on a fresh {@code updateBuffer}, so pump it for a still image. */
    public void repaint() {
        final PixelBuffer<ByteBuffer> pb = this.pixelBuffer;
        if (pb != null) Platform.runLater(() -> pb.updateBuffer(b -> null));
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    void allocSurface(final int width, final int height, final ByteBuffer bgra) {
        // A FAILURE PROPAGATES TO SWEngine, WHICH REFUSES THE FORMAT AND LEAVES NO SURFACE BEHIND
        final PixelBuffer<ByteBuffer> buffer = new PixelBuffer<>(width, height, bgra, PixelFormat.getByteBgraPreInstance());
        this.image = new WritableImage(buffer);
        this.pixelBuffer = buffer;
    }

    @Override
    void present() {
        final PixelBuffer<ByteBuffer> pb = this.pixelBuffer;
        final ByteBuffer bgra = this.bgra;
        if (pb == null || bgra == null) return;
        DataTool.premultiply(bgra.duplicate().clear());
        if (!this.loggedPresent) {
            this.loggedPresent = true;
            LOGGER.debug(IT, "First frame presented ({}x{})", this.width, this.height);
        }
        // updateBuffer MUST RUN ON THE FX THREAD; null MARKS THE WHOLE BUFFER DIRTY. THE NEXT DECODE-THREAD
        // WRITE INTO bgra MAY RACE PRISM'S READ, ACCEPTED: OPAQUE VIDEO FRAMES TEAR AT WORST ONE PULSE
        Platform.runLater(() -> pb.updateBuffer(b -> null));
    }

    @Override
    void disposeSurface() {
        this.image = null;
        this.pixelBuffer = null;
    }
}
