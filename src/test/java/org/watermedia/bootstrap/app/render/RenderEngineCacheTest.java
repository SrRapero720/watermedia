package org.watermedia.bootstrap.app.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.RecordingBackend;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RenderEngine}'s tree cache against a {@link RecordingBackend}: when a cached pass counts as valid, when
 * the top layer opens and composites, when the layers go back to the GPU, and that a composite is one full-window quad
 * per layer. No GPU.
 */
@DisplayName("RenderEngine tree cache")
public class RenderEngineCacheTest {

    private RecordingBackend backend;
    private RenderEngine engine;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeEach
    void setup() {
        this.backend = new RecordingBackend();
        this.engine = new RenderEngine(this.backend);
        this.engine.setupOrtho(100, 50);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Validity")
    class Validity {

        @Test
        @DisplayName("a finished pass stays valid until the backend drops its layers")
        void validUntilEpochMoves() {
            assertFalse(RenderEngineCacheTest.this.engine.cacheValid(), "nothing was cached yet");
            assertTrue(RenderEngineCacheTest.this.engine.beginCache(0f, 0f, 0f, 1f));
            assertFalse(RenderEngineCacheTest.this.engine.cacheValid(), "a pass in progress is not a cache");
            RenderEngineCacheTest.this.engine.endCache();
            assertTrue(RenderEngineCacheTest.this.engine.cacheValid());
            RenderEngineCacheTest.this.backend.epoch++;
            assertFalse(RenderEngineCacheTest.this.engine.cacheValid(), "a resize or swapchain rebuild drops the cache");
        }

        @Test
        @DisplayName("a frame that cannot open a layer leaves the cache invalid")
        void failedLayer() {
            RenderEngineCacheTest.this.backend.accept = false;
            assertFalse(RenderEngineCacheTest.this.engine.beginCache(0f, 0f, 0f, 1f));
            RenderEngineCacheTest.this.engine.raiseCache();
            RenderEngineCacheTest.this.engine.endCache();
            assertFalse(RenderEngineCacheTest.this.engine.cacheValid());
            assertEquals(List.of(0), RenderEngineCacheTest.this.backend.begun, "a failed pass never opens the top layer");
            assertEquals(0, RenderEngineCacheTest.this.backend.ended);
        }

        @Test
        @DisplayName("a direct pass drops a cache that was valid")
        void directPassDrops() {
            RenderEngineCacheTest.this.engine.beginCache(0f, 0f, 0f, 1f);
            RenderEngineCacheTest.this.engine.endCache();
            RenderEngineCacheTest.this.engine.dropCache();
            assertFalse(RenderEngineCacheTest.this.engine.cacheValid());
        }

        @Test
        @DisplayName("releasing frees the layers and invalidates the cache until a new pass fills them")
        void release() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.releaseCache();
            assertEquals(1, RenderEngineCacheTest.this.backend.releases);
            assertFalse(engine.cacheValid());
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            assertTrue(engine.cacheValid(), "a pass after the release caches again");
        }
    }

    @Nested
    @DisplayName("Layers")
    class Layers {

        @Test
        @DisplayName("raising opens the top layer once, transparent, and flushes the base first")
        void raiseOpensTopOnce() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0.1f, 0.2f, 0.3f, 1f);
            engine.fill(0f, 0f, 10f, 10f);
            engine.raiseCache();
            assertEquals(1, RenderEngineCacheTest.this.backend.draws, "the base fill flushes before the layer switch");
            engine.raiseCache();
            engine.endCache();
            assertEquals(List.of(0, 1), RenderEngineCacheTest.this.backend.begun);
            assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, RenderEngineCacheTest.this.backend.lastClear);
            assertEquals(1, RenderEngineCacheTest.this.backend.ended);
        }

        @Test
        @DisplayName("only a pass that raised composites the top layer")
        void topOnlyWhenRaised() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.raiseCache();
            engine.endCache();
            engine.drawCache(true);
            assertEquals(List.of(1), RenderEngineCacheTest.this.backend.bound);

            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.drawCache(true);
            engine.drawCache(false);
            assertEquals(List.of(1, 0), RenderEngineCacheTest.this.backend.bound, "an unraised pass draws only the base layer");
        }
    }

    @Nested
    @DisplayName("Composite")
    class Composite {

        @Test
        @DisplayName("a layer composites as one textured quad over the whole window, unclipped")
        void oneQuad() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            final int clears = RenderEngineCacheTest.this.backend.clipClears;
            engine.drawCache(false);
            assertEquals(1, RenderEngineCacheTest.this.backend.draws);
            assertEquals(6, RenderEngineCacheTest.this.backend.lastVertexCount);
            assertTrue(RenderEngineCacheTest.this.backend.lastTextured);
            assertEquals(clears + 1, RenderEngineCacheTest.this.backend.clipClears, "a clip left by a painter must not cut the layer");
            // FIRST VERTEX: TOP-LEFT CORNER WITH THE TOP ROW OF A TOP-DOWN LAYER; THIRD: BOTTOM-RIGHT WITH ITS LAST ROW
            final float[] v = RenderEngineCacheTest.this.backend.lastVertices;
            assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, Arrays.copyOfRange(v, 0, 4));
            assertArrayEquals(new float[] { 100f, 50f, 1f, 1f }, Arrays.copyOfRange(v, 16, 20));
        }

        @Test
        @DisplayName("a bottom-up layer composites with its rows flipped")
        void flipped() {
            RenderEngineCacheTest.this.backend.flipped = true;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.drawCache(false);
            final float[] v = RenderEngineCacheTest.this.backend.lastVertices;
            assertArrayEquals(new float[] { 0f, 0f, 0f, 1f }, Arrays.copyOfRange(v, 0, 4));
            assertArrayEquals(new float[] { 100f, 50f, 1f, 0f }, Arrays.copyOfRange(v, 16, 20));
        }

        @Test
        @DisplayName("the texture bound before the composite is bound again for the next textured draw")
        void rebindsAfterComposite() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.bindTexture(7);
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.drawCache(false);
            engine.bindTexture(7);
            assertEquals(List.of(7, 7), RenderEngineCacheTest.this.backend.textures);
        }
    }
}
