package org.watermedia.bootstrap.app.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.RecordingBackend;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the CPU side of {@link RenderEngine} against a {@link RecordingBackend}: fills of one mode coalesce into one
 * draw, an unchanged ortho size or line width neither flushes nor reaches the backend again, and a logical clip rect
 * reaches the backend in physical pixels rounded by its edges. No GPU.
 */
@DisplayName("RenderEngine batching")
public class RenderEngineBatchTest {

    private RecordingBackend backend;
    private RenderEngine engine;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeEach
    void setup() {
        this.backend = new RecordingBackend();
        this.engine = new RenderEngine(this.backend);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Batching")
    class Batching {

        @Test
        @DisplayName("fills of one mode wait for the flush and reach the backend as one draw")
        void fillsCoalesce() {
            final RecordingBackend backend = RenderEngineBatchTest.this.backend;
            final RenderEngine engine = RenderEngineBatchTest.this.engine;
            engine.fill(0f, 0f, 10f, 10f);
            engine.fill(10f, 10f, 10f, 10f);
            assertEquals(0, backend.draws, "batched fills do not draw before the flush");
            engine.flush();
            assertEquals(1, backend.draws, "two fills of one mode flush as one draw");
            assertEquals(12, backend.lastVertexCount, "two 6-vertex quads batch to 12 vertices");
        }

        @Test
        @DisplayName("an unchanged ortho size uploads no second projection, a new size uploads once")
        void orthoDedupe() {
            final RecordingBackend backend = RenderEngineBatchTest.this.backend;
            final RenderEngine engine = RenderEngineBatchTest.this.engine;
            engine.setupOrtho(100, 200);
            engine.setupOrtho(100, 200);
            assertEquals(1, backend.projections, "an unchanged size does not upload again");
            engine.setupOrtho(300, 400);
            assertEquals(2, backend.projections, "a new size uploads once");
        }

        @Test
        @DisplayName("lines of an unchanged width share one batch, a new width flushes it once")
        void lineWidthDedupe() {
            final RecordingBackend backend = RenderEngineBatchTest.this.backend;
            final RenderEngine engine = RenderEngineBatchTest.this.engine;
            engine.lineH(0f, 0f, 10f, Color.WHITE, 1f);
            engine.lineH(0f, 5f, 10f, Color.WHITE, 1f);
            assertEquals(List.of(), backend.lineWidths, "the backend already starts at 1 px");
            assertEquals(0, backend.draws, "equal widths do not break the batch");
            engine.lineH(0f, 9f, 10f, Color.WHITE, 2f);
            assertEquals(List.of(2f), backend.lineWidths, "the new width reaches the backend once");
            assertEquals(1, backend.draws, "the 1 px lines flush once before the width changes");
        }
    }

    @Nested
    @DisplayName("Clip rects")
    class ClipRects {

        @Test
        @DisplayName("a clip rounds its edges, not its size, by the UI scale")
        void clipRounding() {
            final RecordingBackend backend = RenderEngineBatchTest.this.backend;
            // A FRACTIONAL SCALE TELLS THE TWO ROUNDINGS APART: ROUNDING THE 1 PX SIZE WOULD GIVE 2, THE FAR EDGE GIVES 3 - 2 = 1
            RenderEngineBatchTest.this.engine.uiScale(1.5f);
            RenderEngineBatchTest.this.engine.clip(1, 1, 1, 1, 100);
            assertEquals(1, backend.clips.size());
            assertArrayEquals(new int[] { 2, 2, 1, 1 }, backend.clips.get(0), "x, y, width and height in physical pixels");
            assertEquals(150, backend.lastCanvasHeight, "the canvas height scales too");
        }
    }
}
