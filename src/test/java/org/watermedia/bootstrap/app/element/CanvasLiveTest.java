package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.render.RenderEngine;
import org.watermedia.test.support.RecordingBackend;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link Canvas} handles live painters over a recording render engine: inline in a direct pass, deferred
 * to every composite in a cached one and dropped by the next pass, split by {@link Canvas#raise()} only in a cached pass
 * that already met one, and repainted with the clip and fade they met.
 */
@DisplayName("Canvas live painters")
public class CanvasLiveTest {

    private RecordingBackend backend;
    private RenderEngine engine;
    private Canvas canvas;
    private List<String> calls;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeEach
    void setup() {
        this.backend = new RecordingBackend();
        this.engine = this.backend.install();
        this.canvas = new Canvas(null).viewport(200, 100);
        this.calls = new ArrayList<>();
    }

    @AfterEach
    void teardown() {
        RecordingBackend.uninstall();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Consumer<Canvas> painter(final String name) {
        return c -> this.calls.add(name);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Passes")
    class Passes {

        @Test
        @DisplayName("a direct pass paints a painter inline; a cached one defers it to every composite")
        void inlineOrDeferred() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            canvas.beginTree(false);
            canvas.live(CanvasLiveTest.this.painter("band"));
            assertEquals(List.of("band"), CanvasLiveTest.this.calls, "a direct pass paints the band at once");

            canvas.beginTree(true);
            canvas.live(CanvasLiveTest.this.painter("band"));
            assertEquals(List.of("band"), CanvasLiveTest.this.calls, "a cached pass must not paint the band into the layer");
            assertTrue(canvas.hasLive(), "the cached band needs a composite");
            canvas.paintLive(false);
            canvas.paintLive(false);
            assertEquals(List.of("band", "band", "band"), CanvasLiveTest.this.calls, "each composite paints the band again");
        }

        @Test
        @DisplayName("a new pass drops the painters of the last one, so a pass without painters needs no composite")
        void newPassDropsPainters() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            canvas.beginTree(true);
            canvas.live(CanvasLiveTest.this.painter("band"));
            assertTrue(canvas.hasLive(), "the first pass met the band");
            canvas.beginTree(true);
            assertFalse(canvas.hasLive(), "the band of the last pass is gone");
        }
    }

    @Nested
    @DisplayName("Raise")
    class Raise {

        @Test
        @DisplayName("a raise opens the top layer once and splits the painters met before it from those met after it")
        void splits() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            CanvasLiveTest.this.engine.beginCache(0f, 0f, 0f, 1f);
            canvas.beginTree(true);
            canvas.live(CanvasLiveTest.this.painter("preview"));
            canvas.raise();
            canvas.live(CanvasLiveTest.this.painter("dialog"));
            canvas.raise();
            CanvasLiveTest.this.engine.endCache();
            assertEquals(List.of(0, 1), CanvasLiveTest.this.backend.begun, "the base layer, then the top one once");

            canvas.paintLive(false);
            assertEquals(List.of("preview"), CanvasLiveTest.this.calls, "the base composite paints what came before the raise");
            canvas.paintLive(true);
            assertEquals(List.of("preview", "dialog"), CanvasLiveTest.this.calls, "the top composite paints what came after it");
        }

        @Test
        @DisplayName("a raise before any painter, or in a direct pass, splits nothing and opens no layer")
        void nothingToSplit() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            // THE ENGINE CACHES THROUGH BOTH PASSES, SO A RAISE THAT SHOULD DO NOTHING WOULD STILL OPEN THE TOP LAYER
            CanvasLiveTest.this.engine.beginCache(0f, 0f, 0f, 1f);
            canvas.beginTree(true);
            canvas.raise();
            canvas.live(CanvasLiveTest.this.painter("band"));
            canvas.paintLive(true);
            assertEquals(List.of(), CanvasLiveTest.this.calls, "nothing was drawn over a band yet");
            canvas.paintLive(false);
            assertEquals(List.of("band"), CanvasLiveTest.this.calls, "the base composite paints the band");

            canvas.beginTree(false);
            canvas.live(CanvasLiveTest.this.painter("inline"));
            canvas.raise();
            assertEquals(List.of("band", "inline"), CanvasLiveTest.this.calls, "a direct pass paints the painter at once");
            assertEquals(List.of(0), CanvasLiveTest.this.backend.begun, "only the base layer opened");
        }
    }

    @Nested
    @DisplayName("Repetition")
    class Repetition {

        @Test
        @DisplayName("every composite repaints a painter under the clip and fade it met, then restores the canvas")
        void clipAndFade() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            final RecordingBackend backend = CanvasLiveTest.this.backend;
            canvas.beginTree(true);
            canvas.pushAlpha(0.5f);
            canvas.pushClip(10, 20, 30, 40);
            canvas.pushClip(0, 0, 25, 100);
            canvas.live(c -> c.fill(0, 0, 5, 5, Color.WHITE));
            canvas.popClip();
            canvas.popClip();
            canvas.popAlpha();

            // FLOAT 7 OF A VERTEX IS ITS ALPHA: HALF OF THE 255 OF WHITE ROUNDS TO 128
            for (int composite = 0; composite < 2; composite++) {
                backend.clips.clear();
                final int clears = backend.clipClears;
                canvas.paintLive(false);
                assertEquals(1, backend.clips.size(), "one clip per composite");
                assertArrayEquals(new int[] { 10, 20, 15, 40 }, backend.clips.get(0), "the nested clip, intersected");
                assertEquals(128 / 255f, backend.lastVertices[7], 1e-6f, "the painter keeps its half fade");
                assertEquals(clears + 1, backend.clipClears, "the clip of the painter ends with it");
            }

            backend.clips.clear();
            canvas.pushClip(0, 0, 200, 100);
            canvas.fill(0, 0, 1, 1, Color.WHITE);
            canvas.popClip();
            assertArrayEquals(new int[] { 0, 0, 200, 100 }, backend.clips.get(0), "a later clip is not cut by the painter's");
            assertEquals(1f, backend.lastVertices[7], "the fade ends with the painter");
        }
    }
}
