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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the headless capture engine honors the {@link GFXEngine} contract, from refused layouts to an idempotent release. */
@DisplayName("HeadlessGFXEngine")
public class HeadlessGFXEngineTest {

    @BeforeAll
    static void boot() {
        MediaBootstrap.client();
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("Frames flip the sentinel texture; release zeroes it and refuses formats")
        void releaseLeavesTheEngineInert() {
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            assertEquals(0L, gfx.texture());
            assertTrue(gfx.format(PixelFormat.BGRA, 2, 2, 8));
            gfx.upload(new ByteBuffer[] { ByteBuffer.allocateDirect(16) }, new int[] { 0 });
            assertNotEquals(0L, gfx.texture());
            assertEquals(1L, gfx.uploadCount());

            gfx.release();
            assertTrue(gfx.released());
            assertEquals(0L, gfx.texture());
            assertFalse(gfx.format(PixelFormat.BGRA, 2, 2, 8));
            gfx.upload(new ByteBuffer[] { ByteBuffer.allocateDirect(16) }, new int[] { 0 });
            assertEquals(1L, gfx.uploadCount(), "uploads are dropped after release");
            gfx.release(); // IDEMPOTENT
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
            assertEquals(0L, gfx.texture());
        }

        @Test
        @DisplayName("Empty sizes, odd depths and compressed layouts are refused before reaching the backend")
        void refusedLayouts() {
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            assertFalse(gfx.format(PixelFormat.BGRA, 0, 2, 8));
            assertFalse(gfx.format(PixelFormat.BGRA, 2, 2, 9));
            assertFalse(gfx.format(PixelFormat.BC7, 2, 2, 8));
            assertNull(gfx.format(), "a refused layout leaves the engine unconfigured");
            assertTrue(gfx.format(PixelFormat.GRAY, 3, 3, 16));
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
            final ByteBuffer[] frames = { ByteBuffer.allocateDirect(4) };
            assertFalse(gfx.preload(new ByteBuffer[0]));
            assertFalse(gfx.preload(null));
            assertTrue(gfx.preload(frames));
            assertNotSame(frames, gfx.preloadedFrames());
            assertNotSame(gfx.preloadedFrames(), gfx.preloadedFrames());
            assertNotEquals(0L, gfx.texture());
        }
    }
}
