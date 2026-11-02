package org.watermedia.api.player;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat.Type;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritablePixelFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.YuvFrames;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that {@link JFXEngine} lands frames premultiplied in its {@code PixelBuffer} image; skips without a JavaFX toolkit. */
@DisplayName("JFXEngine")
public class JFXEngineTest {

    private static final long FX_TIMEOUT_MS = 5_000L;

    @BeforeAll
    static void boot() throws InterruptedException {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE GFXEngine BASE
        MediaBootstrap.client();
        // PixelBuffer IMAGES AND Platform.runLater NEED A STARTED TOOLKIT; ANOTHER CLASS MAY HAVE STARTED IT ALREADY
        final CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (final IllegalStateException running) {
            started.countDown();
        } catch (final RuntimeException | Error unavailable) {
            Assumptions.abort("JavaFX toolkit unavailable: " + unavailable);
        }
        Assumptions.assumeTrue(started.await(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS), "JavaFX toolkit did not start");
        Platform.setImplicitExit(false);
    }

    @Test
    @DisplayName("A BGRA frame lands premultiplied in the image without touching the caller's buffer, and release drops the image")
    void bgraPremultiplied() throws Exception {
        final JFXEngine engine = PlayerAPI.jfxEngine(null);
        try {
            assertTrue(engine.format(PixelFormat.BGRA, 2, 1, 8));
            // PIXEL 0 IS OPAQUE; PIXEL 1 (A=128) PREMULTIPLIES TO ROUND(C * 128 / 255)
            final ByteBuffer frame = YuvFrames.direct(10, 20, 30, 255, 40, 50, 60, 128);
            final byte[] premultiplied = { 10, 20, 30, (byte) 255, 20, 25, 30, (byte) 128 };
            engine.upload(new ByteBuffer[] { frame }, new int[] { 0 });

            final Image image = engine.image();
            assertEquals(2, image.getWidth());
            assertEquals(1, image.getHeight());
            assertEquals(Type.BYTE_BGRA_PRE, image.getPixelReader().getPixelFormat().getType(), "PixelBuffer only takes premultiplied BGRA");
            assertArrayEquals(premultiplied, pixels(image));
            assertEquals(0, frame.position(), "the frame keeps its position");
            assertEquals(40, frame.get(4), "the caller's frame is never premultiplied in place");

            engine.upload(new ByteBuffer[] { frame }, new int[] { 0 });
            assertArrayEquals(premultiplied, pixels(image), "a repeated frame is premultiplied once");

            engine.release();
            assertNull(engine.image(), "release drops the PixelBuffer image");
        } finally {
            engine.release();
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // READS THE IMAGE AS PREMULTIPLIED BGRA ON THE FX THREAD; runLater IS FIFO, SO EVERY updateBuffer POSTED BY present() RAN FIRST
    private static byte[] pixels(final Image image) throws Exception {
        final FutureTask<byte[]> read = new FutureTask<>(() -> {
            final int width = (int) image.getWidth();
            final int height = (int) image.getHeight();
            final byte[] out = new byte[width * height * 4];
            final PixelReader reader = image.getPixelReader();
            reader.getPixels(0, 0, width, height, WritablePixelFormat.getByteBgraPreInstance(), out, 0, width * 4);
            return out;
        });
        Platform.runLater(read);
        return read.get(FX_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
}
