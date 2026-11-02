package org.watermedia.api.player;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.YuvFrames;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the {@link AWTEngine} software sink and the {@link SWEngine} logic it shares with JavaFX: byte order, strides and refusals. */
@DisplayName("AWTEngine")
public class AWTEngineTest {

    @BeforeAll
    static void boot() {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE GFXEngine BASE
        MediaBootstrap.client();
    }

    @Nested
    @DisplayName("Frame uploads")
    class Frames {

        @Test
        @DisplayName("BGRA bytes pack into ARGB pixels")
        void bgraPacking() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.BGRA, 2, 1, 8));
            // PIXEL 0: B=10 G=20 R=30 A=255 ; PIXEL 1: B=40 G=50 R=60 A=128
            engine.upload(new ByteBuffer[] { YuvFrames.direct(10, 20, 30, 255, 40, 50, 60, 128) }, new int[] { 0 });

            final BufferedImage img = engine.image();
            assertEquals(2, img.getWidth());
            assertEquals(1, img.getHeight());
            assertEquals(0xFF1E140A, img.getRGB(0, 0));
            assertEquals(0x803C3228, img.getRGB(1, 0));
        }

        @Test
        @DisplayName("RGBA is swizzled to the same ARGB result")
        void rgbaSwizzle() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.RGBA, 1, 1, 8));
            // R=30 G=20 B=10 A=255 -> 0xFF1E140A, SAME PIXEL AS THE BGRA CASE
            engine.upload(new ByteBuffer[] { YuvFrames.direct(30, 20, 10, 255) }, new int[] { 0 });
            assertEquals(0xFF1E140A, engine.image().getRGB(0, 0));
        }

        @Test
        @DisplayName("BGRA stride padding beyond w*4 is dropped")
        void bgraStridePadding() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.BGRA, 2, 2, 8));
            // 2x2 BGRA WITH A 12-BYTE STRIDE: 8 REAL BYTES PER ROW + 4 PADDING BYTES THE ENGINE MUST SKIP
            engine.upload(new ByteBuffer[] { YuvFrames.direct(
                    10, 20, 30, 255,  40, 50, 60, 255,   1, 2, 3, 4,   // ROW 0 + PAD
                    70, 80, 90, 255, 100, 110, 120, 255,  5, 6, 7, 8) }, // ROW 1 + PAD
                    new int[] { 12 });
            final BufferedImage img = engine.image();
            assertEquals(0xFF1E140A, img.getRGB(0, 0));
            assertEquals(0xFF3C3228, img.getRGB(1, 0));
            assertEquals(0xFF5A5046, img.getRGB(0, 1));
            assertEquals(0xFF786E64, img.getRGB(1, 1));
        }

        @Test
        @DisplayName("RGBA stride padding beyond w*4 is dropped and swizzled")
        void rgbaStridePadding() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.RGBA, 2, 1, 8));
            // 2x1 RGBA, 12-BYTE STRIDE (8 REAL + 4 PAD); R,G,B,A SWIZZLES TO THE SAME ARGB AS THE BGRA CASE
            engine.upload(new ByteBuffer[] { YuvFrames.direct(30, 20, 10, 255,  60, 50, 40, 255,  1, 2, 3, 4) }, new int[] { 12 });
            final BufferedImage img = engine.image();
            assertEquals(0xFF1E140A, img.getRGB(0, 0));
            assertEquals(0xFF3C3228, img.getRGB(1, 0));
        }

        @Test
        @DisplayName("A padded frame may end on its last tight row, and a stride shorter than a row drops the frame once logged")
        void lastRowAndShortStride() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.BGRA, 2, 2, 8));
            // 12-BYTE STRIDE, BUT THE BUFFER STOPS RIGHT AFTER THE 8 BYTES OF THE LAST ROW
            engine.upload(new ByteBuffer[] { YuvFrames.direct(
                    10, 20, 30, 255,  40, 50, 60, 255,  1, 2, 3, 4,
                    70, 80, 90, 255, 100, 110, 120, 255) }, new int[] { 12 });
            assertEquals(0xFF786E64, engine.image().getRGB(1, 1));
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                for (int frame = 0; frame < 2; frame++)
                    engine.upload(new ByteBuffer[] { YuvFrames.direct(new int[16]) }, new int[] { 4 });
                final List<LogEvent> events = sinkEvents(capture);
                assertEquals(1, events.size(), "the refused stride is logged once per engine");
                assertEquals(Level.WARN, events.get(0).getLevel());
            }
            assertEquals(0xFF786E64, engine.image().getRGB(1, 1), "the dropped frames left the last one in place");
        }

        @Test
        @DisplayName("A frame callback that throws is logged once per engine, never thrown at the decoder")
        void callbackFailuresAreIsolated() {
            final AWTEngine engine = PlayerAPI.awtEngine(() -> { throw new IllegalStateException("host repaint failed"); });
            assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                for (int frame = 0; frame < 3; frame++) engine.upload(new ByteBuffer[] { YuvFrames.direct(1, 2, 3, 4) }, new int[] { 0 });
                final List<LogEvent> events = sinkEvents(capture);
                assertEquals(1, events.size(), "a repeated failure is logged once per engine");
                assertEquals(Level.ERROR, events.get(0).getLevel());
            }
            assertNotEquals(0L, engine.texture(), "the frame was published before the callback failed");
        }
    }

    @Nested
    @DisplayName("Formats and lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("The frame callback runs after each published frame, and release drops the image")
        void callbackAndLifecycle() {
            final boolean[] fired = { false };
            final AWTEngine engine = PlayerAPI.awtEngine(() -> fired[0] = true);
            assertTrue(engine.format(PixelFormat.BGRA, 1, 1, 8));
            assertEquals(0L, engine.texture(), "no frame yet");

            engine.upload(new ByteBuffer[] { YuvFrames.direct(1, 2, 3, 4) }, new int[] { 0 });
            assertTrue(fired[0], "the callback runs after the frame is published");
            assertNotEquals(0L, engine.texture(), "a frame is present");

            engine.release();
            assertEquals(0L, engine.texture());
            assertNull(engine.image(), "release drops the surface");
        }

        @Test
        @DisplayName("A resolution change reallocates the image and waits for the next frame; a declined layout keeps the current one")
        void resizeReallocates() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.format(PixelFormat.BGRA, 2, 2, 8));
            final BufferedImage first = engine.image();
            engine.upload(new ByteBuffer[] { YuvFrames.direct(new int[16]) }, new int[] { 0 });
            assertNotEquals(0L, engine.texture());

            assertTrue(engine.format(PixelFormat.BGRA, 4, 3, 8));
            final BufferedImage second = engine.image();
            assertNotSame(first, second, "re-read image() after a resize");
            assertEquals(4, second.getWidth());
            assertEquals(3, second.getHeight());
            assertEquals(0L, engine.texture(), "the new surface holds no frame yet");

            // A LAYOUT THE ENGINE DECLINES NEVER REACHES THE BACKEND, SO THE CURRENT SURFACE AND ITS FRAME STAY ON SCREEN
            final int[] frame = new int[48];
            System.arraycopy(new int[] { 10, 20, 30, 255 }, 0, frame, 0, 4);
            engine.upload(new ByteBuffer[] { YuvFrames.direct(frame) }, new int[] { 0 });
            assertFalse(engine.format(PixelFormat.NV12, 4, 3, 8));
            assertSame(second, engine.image());
            assertEquals(0xFF1E140A, second.getRGB(0, 0));
            assertNotEquals(0L, engine.texture());
        }

        @Test
        @DisplayName("Sizes that cannot back one buffer are refused, leave no surface and drop frames")
        void invalidSizesDropFrames() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            // EMPTY SIZES ARE REFUSED BY THE BASE CONTRACT BEFORE ANY BACKEND WORK, SILENTLY
            for (final int[] size: new int[][] { { 0, 4 }, { 4, -1 } }) {
                try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                    assertFalse(engine.format(PixelFormat.BGRA, size[0], size[1], 8));
                    assertNull(engine.image());
                    assertTrue(sinkEvents(capture).isEmpty());
                }
                engine.upload(new ByteBuffer[] { YuvFrames.direct(1, 2, 3, 4) }, new int[] { 0 });
                assertEquals(0L, engine.texture());
            }
            // A SIZE NO SINGLE BUFFER CAN BACK IS REFUSED BY THE SOFTWARE SINK WITH A WARNING
            try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
                assertFalse(engine.format(PixelFormat.BGRA, 50_000, 50_000, 8));
                assertNull(engine.image());
                final List<LogEvent> events = sinkEvents(capture);
                assertEquals(1, events.size());
                assertEquals(Level.WARN, events.get(0).getLevel());
                assertTrue(events.get(0).getMessage().getFormattedMessage().contains("50000x50000"));
            }
            engine.upload(new ByteBuffer[] { YuvFrames.direct(1, 2, 3, 4) }, new int[] { 0 });
            assertEquals(0L, engine.texture());
        }

        @Test
        @DisplayName("Only 8-bit BGRA/RGBA are accepted; planar, RGB and deep color decline so the producer converts")
        void formatDeclines() {
            final AWTEngine engine = PlayerAPI.awtEngine(null);
            assertTrue(engine.supports(PixelFormat.BGRA));
            assertTrue(engine.supports(PixelFormat.RGBA));
            assertFalse(engine.supports(PixelFormat.RGB));
            assertFalse(engine.supports(PixelFormat.YUV420P));
            assertFalse(engine.supports(PixelFormat.RGBA, 16), "a software surface copies four bytes per pixel");
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE SOFTWARE SINK'S OWN EVENTS: THE CAPTURE SPANS THE WHOLE PROJECT LOGGER, WHICH OTHER THREADS MAY WRITE TO MEANWHILE
    private static List<LogEvent> sinkEvents(final LogCapture capture) {
        return capture.events().stream()
                .filter(event -> event.getMarker() != null && SWEngine.class.getSimpleName().equals(event.getMarker().getName()))
                .toList();
    }
}
