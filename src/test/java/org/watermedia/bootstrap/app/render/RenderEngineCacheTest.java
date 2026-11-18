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
 * the top layer opens and composites, when the layers go back to the GPU, that a composite is one full-window quad per
 * layer and that the texture bound before it binds again after it. No GPU.
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
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            assertFalse(engine.cacheValid(), "nothing was cached yet");
            assertTrue(engine.beginCache(0f, 0f, 0f, 1f), "the backend opens the base layer");
            assertFalse(engine.cacheValid(), "a pass in progress is not a cache");
            engine.endCache();
            assertTrue(engine.cacheValid(), "the finished pass is the cache");
            RenderEngineCacheTest.this.backend.epoch++;
            assertFalse(engine.cacheValid(), "a resize or swapchain rebuild drops the cache");
        }

        @Test
        @DisplayName("a frame that cannot open a layer leaves the cache invalid, without a top layer or an end")
        void failedLayer() {
            final RecordingBackend backend = RenderEngineCacheTest.this.backend;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            backend.accept = false;
            assertFalse(engine.beginCache(0f, 0f, 0f, 1f), "the backend refused the base layer");
            engine.raiseCache();
            engine.endCache();
            assertFalse(engine.cacheValid(), "a failed pass is no cache");
            assertEquals(List.of(0), backend.begun, "a failed pass never opens the top layer");
            assertEquals(0, backend.ended, "a failed pass ends no layer");
        }

        @Test
        @DisplayName("a direct pass drops a cache that was valid")
        void directPassDrops() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.dropCache();
            assertFalse(engine.cacheValid(), "the layers no longer hold the tree");
        }

        @Test
        @DisplayName("releasing frees the layers and invalidates the cache until a new pass fills them")
        void release() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.releaseCache();
            assertEquals(1, RenderEngineCacheTest.this.backend.releases);
            assertFalse(engine.cacheValid(), "released layers hold nothing");
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
            final RecordingBackend backend = RenderEngineCacheTest.this.backend;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0.1f, 0.2f, 0.3f, 1f);
            engine.fill(0f, 0f, 10f, 10f);
            engine.raiseCache();
            assertEquals(1, backend.draws, "the base fill flushes before the layer switch");
            engine.raiseCache();
            engine.endCache();
            assertEquals(List.of(0, 1), backend.begun);
            assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, backend.lastClear, "the top layer clears to transparent");
            assertEquals(1, backend.ended);
        }

        @Test
        @DisplayName("only a pass that raised composites the top layer")
        void topOnlyWhenRaised() {
            final RecordingBackend backend = RenderEngineCacheTest.this.backend;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.raiseCache();
            engine.endCache();
            engine.drawCache(true);
            assertEquals(List.of(1), backend.bound);

            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.drawCache(true);
            engine.drawCache(false);
            assertEquals(List.of(1, 0), backend.bound, "an unraised pass draws only the base layer");
        }
    }

    @Nested
    @DisplayName("Composite")
    class Composite {

        @Test
        @DisplayName("a layer composites as one textured quad over the whole window, unclipped")
        void oneQuad() {
            final RecordingBackend backend = RenderEngineCacheTest.this.backend;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            final int clears = backend.clipClears;
            engine.drawCache(false);
            assertEquals(1, backend.draws);
            assertEquals(6, backend.lastVertexCount);
            assertTrue(backend.lastTextured, "the quad samples the layer");
            assertEquals(clears + 1, backend.clipClears, "a clip left by a painter must not cut the layer");
            // FIRST VERTEX: TOP-LEFT CORNER WITH THE TOP ROW OF A TOP-DOWN LAYER; THIRD: BOTTOM-RIGHT WITH ITS LAST ROW
            final float[] v = backend.lastVertices;
            assertArrayEquals(new float[] { 0f, 0f, 0f, 0f }, Arrays.copyOfRange(v, 0, 4));
            assertArrayEquals(new float[] { 100f, 50f, 1f, 1f }, Arrays.copyOfRange(v, 16, 20));
        }

        @Test
        @DisplayName("a bottom-up layer composites with its rows flipped")
        void flipped() {
            final RecordingBackend backend = RenderEngineCacheTest.this.backend;
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            backend.flipped = true;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            engine.drawCache(false);
            final float[] v = backend.lastVertices;
            assertArrayEquals(new float[] { 0f, 0f, 0f, 1f }, Arrays.copyOfRange(v, 0, 4));
            assertArrayEquals(new float[] { 100f, 50f, 1f, 0f }, Arrays.copyOfRange(v, 16, 20));
        }

        @Test
        @DisplayName("the texture bound before the composite is bound again for the next textured draw")
        void rebindsAfterComposite() {
            final RenderEngine engine = RenderEngineCacheTest.this.engine;
            engine.beginCache(0f, 0f, 0f, 1f);
            engine.endCache();
            // BOUND AFTER THE PASS: beginCache() ITSELF FORGETS THE BOUND TEXTURE, SO ONLY THE COMPOSITE IS LEFT TO FORGET IT
            engine.bindTexture(7);
            engine.drawCache(false);
            engine.bindTexture(7);
            assertEquals(List.of(7, 7), RenderEngineCacheTest.this.backend.textures, "the composite bound its layer over texture 7");
        }
    }
}
