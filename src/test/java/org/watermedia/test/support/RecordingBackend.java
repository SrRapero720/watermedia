package org.watermedia.test.support;

import org.joml.Matrix4f;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.bootstrap.app.render.RenderBackend;
import org.watermedia.bootstrap.app.render.RenderEngine;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** GPU-less {@link RenderBackend} recording the render engine's layer, clip and draw calls; it can stand behind {@link RenderSystem}. */
public final class RecordingBackend implements RenderBackend {
    public final List<Integer> begun = new ArrayList<>();
    public final List<Integer> bound = new ArrayList<>();
    public final List<Integer> textures = new ArrayList<>();
    // EVERY clip(x, y, width, height) IN PHYSICAL PIXELS, IN CALL ORDER
    public final List<int[]> clips = new ArrayList<>();
    public boolean accept = true;
    public boolean flipped;
    public int epoch;
    public int ended;
    public int releases;
    public int draws;
    public int clipClears;
    public int lastVertexCount;
    public boolean lastTextured;
    public float[] lastVertices = new float[0];
    public float[] lastClear = new float[0];

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Puts a fresh {@link RenderEngine} over this backend behind {@link RenderSystem} and returns it. */
    public RenderEngine install() {
        final RenderEngine engine = new RenderEngine(this);
        engine(engine);
        return engine;
    }

    /** Leaves {@link RenderSystem} without an engine, as before the app starts one. */
    public static void uninstall() {
        engine(null);
    }

    @Override public boolean beginLayer(final int index, final float r, final float g, final float b, final float a) {
        this.begun.add(index);
        this.lastClear = new float[] {r, g, b, a};
        return this.accept;
    }
    @Override public void endLayer() { this.ended++; }
    @Override public boolean bindLayer(final int index) {
        this.bound.add(index);
        return this.begun.contains(index);
    }
    @Override public boolean layerFlipped() { return this.flipped; }
    @Override public int layerEpoch() { return this.epoch; }
    @Override public void releaseLayers() {
        this.releases++;
        this.epoch++;
    }
    @Override public void init() {}
    @Override public void cleanup() {}
    @Override public void configureFrameState() {}
    @Override public void clear(final float r, final float g, final float b, final float a) {}
    @Override public void viewport(final int width, final int height) {}
    @Override public TextureHandle createTexture(final int width, final int height, final ByteBuffer rgba) { return new TextureHandle(1, width, height); }
    @Override public void deleteTexture(final TextureHandle texture) {}
    @Override public void bindTexture(final int textureId) { this.textures.add(textureId); }
    @Override public void useProjection(final Matrix4f projection) {}
    @Override public void draw(final DrawMode mode, final float[] vertices, final int vertexCount, final boolean textured) {
        this.draws++;
        this.lastVertexCount = vertexCount;
        this.lastTextured = textured;
        this.lastVertices = Arrays.copyOf(vertices, vertexCount * 8);
    }
    @Override public void lineWidth(final float width) {}
    @Override public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        this.clips.add(new int[] {x, y, width, height});
    }
    @Override public void clearClip() { this.clipClears++; }
    @Override public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) { return () -> null; }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // RenderSystem KEEPS ITS ENGINE PRIVATE TO THE RENDER THREAD THAT BUILDS IT
    private static void engine(final RenderEngine engine) {
        try {
            final Field field = RenderSystem.class.getDeclaredField("engine");
            field.setAccessible(true);
            field.set(null, engine);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("RenderSystem has no engine field to stand in for", e);
        }
    }
}
