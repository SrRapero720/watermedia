package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.watermedia.api.player.GFXEngine;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Low-level renderer contract implemented by the OpenGL and Vulkan backends. */
public interface RenderBackend {

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    void init();
    void cleanup();
    void configureFrameState();
    void clear(float r, float g, float b, float a);
    void viewport(int width, int height);
    TextureHandle createTexture(int width, int height, ByteBuffer rgba);
    void deleteTexture(TextureHandle texture);
    void bindTexture(int textureId);
    void useProjection(Matrix4f projection);
    void draw(DrawMode mode, float[] vertices, int vertexCount, boolean textured);

    /** Returns whether {@link #softRect} draws in one pass; otherwise the engine stacks translucent fills. */
    default boolean supportsSoftRect() {
        return false;
    }

    /** Fills a rounded rectangle whose alpha fades to zero over {@code softness} pixels outside its edge. */
    default void softRect(final float x, final float y, final float w, final float h, final float radius,
                          final float r, final float g, final float b, final float a, final float softness) {
    }

    void lineWidth(float width);
    void clip(int x, int y, int width, int height, int canvasHeight);
    void clearClip();

    /** Builds the media engine of this backend ({@code GLEngine} or {@code VKEngine}); only OpenGL uses the thread and executor. */
    Supplier<GFXEngine> mediaEngineSupplier(Thread renderThread, Executor renderExecutor);

    /** Begins a frame; Vulkan acquires the next swapchain image here. */
    default void beginFrame() {
    }

    /** Ends and presents the frame. */
    default void present() {
    }

    /** Binds a player's {@code texture()} handle for the next blit; 64-bit to fit a {@code VkImageView}, 0 without a frame. */
    default void bindMediaTexture(final long handle) {
        this.bindTexture((int) handle);
    }

    /** Redirects drawing into offscreen layer {@code index} (0 or 1), cleared to the color; false when nothing draws this frame. */
    default boolean beginLayer(final int index, final float r, final float g, final float b, final float a) {
        return false;
    }

    /** Ends the open layer, so the next clear or draw targets the window again. */
    default void endLayer() {
    }

    /** Binds layer {@code index} for the next textured draw, sampled as premultiplied color; false when it holds nothing. */
    default boolean bindLayer(final int index) {
        return false;
    }

    /** Returns whether layer rows run bottom-up, as OpenGL textures do. */
    default boolean layerFlipped() {
        return false;
    }

    /** Returns a counter that moves whenever the layers lose their contents, such as on a resize. */
    default int layerEpoch() {
        return 0;
    }

    /** Frees the layers between frames, moving {@link #layerEpoch()}; the next {@link #beginLayer} builds them again. */
    default void releaseLayers() {
    }

    /** Returns the GPU or renderer name, captured at init so any thread can read it. */
    default String deviceName() {
        return "Unknown";
    }

    /** Returns the highest API version as {@code "major.minor"}, captured at init; empty when unknown. */
    default String deviceVersion() {
        return "";
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Texture handle owned by the active render backend. */
    record TextureHandle(int id, int width, int height) {
        public static final TextureHandle NONE = new TextureHandle(-1, 0, 0);
    }

    /** Backend-neutral primitive mode of the UI render engine. */
    enum DrawMode {
        TRIANGLES,
        TRIANGLE_FAN,
        LINES,
        LINE_LOOP
    }
}
