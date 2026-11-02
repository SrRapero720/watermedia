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
 * to every composite in a cached one, split by {@link Canvas#raise()}, and repainted with the clip and fade they met.
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
            assertEquals(List.of("band"), CanvasLiveTest.this.calls);

            canvas.beginTree(true);
            canvas.live(CanvasLiveTest.this.painter("band"));
            assertEquals(List.of("band"), CanvasLiveTest.this.calls, "a cached pass must not paint the band into the layer");
            assertTrue(canvas.hasLive());
            canvas.paintLive(false);
            canvas.paintLive(false);
            assertEquals(List.of("band", "band", "band"), CanvasLiveTest.this.calls);
        }

        @Test
        @DisplayName("a pass without painters needs no composite")
        void noPainters() {
            CanvasLiveTest.this.canvas.beginTree(true);
            assertFalse(CanvasLiveTest.this.canvas.hasLive());
        }
    }

    @Nested
    @DisplayName("Raise")
    class Raise {

        @Test
        @DisplayName("a raise opens the top layer once and splits the painters met before it from those met after it")
        void splits() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            canvas.beginTree(true);
            CanvasLiveTest.this.engine.beginCache(0f, 0f, 0f, 1f);
            canvas.live(CanvasLiveTest.this.painter("preview"));
            canvas.raise();
            canvas.live(CanvasLiveTest.this.painter("dialog"));
            canvas.raise();
            CanvasLiveTest.this.engine.endCache();
            assertEquals(List.of(0, 1), CanvasLiveTest.this.backend.begun);

            canvas.paintLive(false);
            assertEquals(List.of("preview"), CanvasLiveTest.this.calls);
            canvas.paintLive(true);
            assertEquals(List.of("preview", "dialog"), CanvasLiveTest.this.calls);
        }

        @Test
        @DisplayName("a raise before any painter, or in a direct pass, splits nothing")
        void nothingToSplit() {
            final Canvas canvas = CanvasLiveTest.this.canvas;
            canvas.beginTree(true);
            canvas.raise();
            canvas.live(CanvasLiveTest.this.painter("band"));
            canvas.paintLive(true);
            assertEquals(List.of(), CanvasLiveTest.this.calls, "nothing was drawn over a band yet");
            canvas.paintLive(false);
            assertEquals(List.of("band"), CanvasLiveTest.this.calls);

            canvas.beginTree(false);
            canvas.live(CanvasLiveTest.this.painter("inline"));
            canvas.raise();
            assertEquals(List.of("band", "inline"), CanvasLiveTest.this.calls);
            assertTrue(CanvasLiveTest.this.backend.begun.isEmpty(), "a direct pass opens no layer");
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

            for (int composite = 0; composite < 2; composite++) {
                backend.clips.clear();
                canvas.paintLive(false);
                assertEquals(1, backend.clips.size());
                assertArrayEquals(new int[] { 10, 20, 15, 40 }, backend.clips.get(0), "the nested clip, intersected");
                assertEquals(128 / 255f, backend.lastVertices[7], 1e-6f, "the painter keeps its half fade");
            }

            final int clears = backend.clipClears;
            canvas.fill(0, 0, 1, 1, Color.WHITE);
            CanvasLiveTest.this.engine.clearClip();
            assertEquals(1f, backend.lastVertices[7], "the fade ends with the painter");
            assertEquals(1, backend.clips.size(), "the clip ends with the painter");
            assertEquals(clears + 1, backend.clipClears);
        }
    }
}
