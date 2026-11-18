package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the headless capture engine honors the {@link GFXEngine} contract, from refused layouts to an idempotent release, and keeps its own copy of a preloaded frame set. */
@DisplayName("HeadlessGFXEngine")
public class HeadlessGFXEngineTest {

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        // ENGINE CONSTRUCTION IS CLIENT-GATED BY THE GFXEngine BASE
        MediaBootstrap.client();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Frames flip the sentinel texture; release zeroes it, refuses formats, drops uploads and may run twice")
        void releaseLeavesTheEngineInert() {
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            assertEquals(0L, gfx.texture(), "no frame yet");
            assertTrue(gfx.format(PixelFormat.BGRA, 2, 2, 8));
            gfx.upload(new ByteBuffer[] { ByteBuffer.allocateDirect(16) }, new int[] { 0 });
            assertNotEquals(0L, gfx.texture(), "a captured frame shows the sentinel texture");
            assertEquals(1L, gfx.uploadCount());

            gfx.release();
            assertTrue(gfx.released());
            assertEquals(0L, gfx.texture(), "a released engine exposes no texture");
            assertFalse(gfx.format(PixelFormat.BGRA, 2, 2, 8), "a released engine refuses formats");
            gfx.upload(new ByteBuffer[] { ByteBuffer.allocateDirect(16) }, new int[] { 0 });
            assertEquals(1L, gfx.uploadCount(), "uploads are dropped after release");
            gfx.release(); // A SECOND RELEASE IS A NO-OP
            assertTrue(gfx.released());
        }

        @Test
        @DisplayName("Empty plane sets, null or heap planes and missing strides are caller bugs that never reach the backend")
        void malformedPlaneSets() {
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            assertTrue(gfx.format(PixelFormat.BGRA, 2, 2, 8));
            final ByteBuffer plane = ByteBuffer.allocateDirect(16);
            assertThrows(IllegalArgumentException.class, () -> gfx.upload(null, new int[] { 0 }), "no plane array");
            assertThrows(IllegalArgumentException.class, () -> gfx.upload(new ByteBuffer[0], new int[0]), "an empty plane set");
            assertThrows(IllegalArgumentException.class, () -> gfx.upload(new ByteBuffer[] { null }, new int[] { 0 }), "a null plane");
            assertThrows(IllegalArgumentException.class, () -> gfx.upload(new ByteBuffer[] { ByteBuffer.allocate(16) }, new int[] { 0 }), "a heap plane");
            assertThrows(IllegalArgumentException.class, () -> gfx.upload(new ByteBuffer[] { plane }, new int[0]), "a missing stride");
            assertEquals(0L, gfx.uploadCount(), "no malformed set reached the backend");
            assertEquals(0L, gfx.texture(), "no frame was captured");
        }

        @Test
        @DisplayName("Empty sizes, odd depths and compressed layouts are refused before reaching the backend")
        void refusedLayouts() {
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            assertFalse(gfx.format(PixelFormat.BGRA, 0, 2, 8), "an empty size");
            assertFalse(gfx.format(PixelFormat.BGRA, 2, 2, 9), "a 9-bit depth");
            assertFalse(gfx.format(PixelFormat.BC7, 2, 2, 8), "a compressed layout");
            assertNull(gfx.format(), "a refused layout leaves the engine unconfigured");
            assertTrue(gfx.format(PixelFormat.GRAY, 3, 3, 16), "a raw 16-bit layout is accepted");
            assertEquals(16, gfx.bits());
        }
    }

    @Nested
    @DisplayName("Preloaded frame sets")
    class Preload {

        @Test
        @DisplayName("preload(frames) follows the advertised capability and never aliases the producer's array")
        void preloadGating() {
            assertFalse(PlayerAPI.headlessEngine().preload(new ByteBuffer[] { ByteBuffer.allocateDirect(4) }),
                    "an engine without the capability refuses frame sets");
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine(true);
            final ByteBuffer first = ByteBuffer.allocateDirect(4);
            final ByteBuffer[] frames = { first };
            assertFalse(gfx.preload(new ByteBuffer[0]), "an empty frame set is refused");
            assertFalse(gfx.preload(null), "a missing frame set is refused");
            assertTrue(gfx.preload(frames));
            assertNotEquals(0L, gfx.texture(), "an accepted frame set shows the sentinel texture");

            // THE PRODUCER REUSING ITS ARRAY AFTERWARDS MUST NOT CHANGE THE SET THE ENGINE KEPT
            frames[0] = ByteBuffer.allocateDirect(4);
            assertSame(first, gfx.preloadedFrames()[0], "the engine stored its own copy of the array");
            assertNotSame(gfx.preloadedFrames(), gfx.preloadedFrames(), "every read hands out a fresh copy");
        }
    }
}
