package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.GFXEngine;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link RenderEngine}'s CPU-side batching against a recording {@link RenderBackend} stub:
 * flush coalescing, ortho-projection dedupe and the logical-to-physical clip rounding. No GPU.
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

    @Test
    @DisplayName("same-mode fills coalesce into a single draw on flush")
    void fillsCoalesce() {
        this.engine.fill(0f, 0f, 10f, 10f);
        this.engine.fill(10f, 10f, 10f, 10f);
        assertEquals(0, this.backend.draws, "batched fills must not draw before flush");
        this.engine.flush();
        assertEquals(1, this.backend.draws, "two same-mode fills flush as one draw");
        assertEquals(12, this.backend.lastVertexCount, "two 6-vertex quads batch to 12 vertices");
    }

    @Test
    @DisplayName("repeated setupOrtho with identical dims re-uploads the projection once")
    void orthoDedupe() {
        this.engine.setupOrtho(100, 200);
        this.engine.setupOrtho(100, 200);
        assertEquals(1, this.backend.projections, "identical ortho dims must not re-upload");
        this.engine.setupOrtho(300, 400);
        assertEquals(2, this.backend.projections, "changed ortho dims re-upload once");
    }

    @Test
    @DisplayName("clip rounds edges (not sizes) by the UI scale")
    void clipRounding() {
        // A FRACTIONAL SCALE TELLS THE TWO ROUNDINGS APART: ROUNDING THE 1 PX SIZE WOULD GIVE 2, THE FAR EDGE GIVES 3 - 2 = 1
        this.engine.uiScale(1.5f);
        this.engine.clip(1, 1, 1, 1, 100);
        assertEquals(2, this.backend.clipX);
        assertEquals(2, this.backend.clipY);
        assertEquals(1, this.backend.clipW);
        assertEquals(1, this.backend.clipH);
        assertEquals(150, this.backend.clipCanvas);
    }

    @Test
    @DisplayName("lines of an unchanged width share one batch, a new width flushes it once")
    void lineWidthDedupe() {
        this.engine.lineH(0f, 0f, 10f, Color.WHITE, 1f);
        this.engine.lineH(0f, 5f, 10f, Color.WHITE, 1f);
        assertEquals(0, this.backend.lineWidths, "the backend already starts at 1 px");
        assertEquals(0, this.backend.draws, "equal widths must not break the batch");
        this.engine.lineH(0f, 9f, 10f, Color.WHITE, 2f);
        assertEquals(1, this.backend.lineWidths);
        assertEquals(1, this.backend.draws, "the 1 px lines flush once before the width changes");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // RECORDS THE ENGINE'S BACKEND CALLS; EVERY GPU METHOD IS AN INERT NO-OP.
    private static final class RecordingBackend implements RenderBackend {
        int draws, projections, lastVertexCount, lineWidths;
        int clipX, clipY, clipW, clipH, clipCanvas;

        @Override public void init() {}
        @Override public void cleanup() {}
        @Override public void configureFrameState() {}
        @Override public void clear(final float r, final float g, final float b, final float a) {}
        @Override public void viewport(final int width, final int height) {}
        @Override public TextureHandle createTexture(final int width, final int height, final ByteBuffer rgba) { return new TextureHandle(1, width, height); }
        @Override public void deleteTexture(final TextureHandle texture) {}
        @Override public void bindTexture(final int textureId) {}
        @Override public void useProjection(final Matrix4f projection) { this.projections++; }
        @Override public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
            this.draws++;
            this.lastVertexCount = vertexCount;
        }
        @Override public void lineWidth(final float width) { this.lineWidths++; }
        @Override public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
            this.clipX = x;
            this.clipY = y;
            this.clipW = width;
            this.clipH = height;
            this.clipCanvas = canvasHeight;
        }
        @Override public void clearClip() {}
        @Override public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) { return () -> null; }
    }
}
