package org.watermedia.bootstrap.app.render;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.bootstrap.app.render.opengl.OpenGLRenderBackend;
import org.watermedia.bootstrap.app.render.vulkan.VulkanRenderBackend;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;

/**
 * Static facade of the active UI render engine; it also picks OpenGL or Vulkan, applies the window hints, attaches
 * to the window and supplies the media engine, so the rest of the app never branches on the backend.
 */
public final class RenderSystem {
    private static final Marker IT = MarkerManager.getMarker(RenderSystem.class.getSimpleName());

    // A GL DEBUG CONTEXT ADDS PER-CALL DRIVER VALIDATION OVERHEAD: OFF BY DEFAULT, OPT IN WITH -Dwatermedia.gldebug=true
    public static final boolean GL_DEBUG = Boolean.getBoolean("watermedia.gldebug");

    // READ OFF THE RENDER THREAD (deviceName(), engineKind()) AND REPLACED ON EVERY HOT SWAP
    private static volatile Engine kind = Engine.OPENGL;
    private static volatile RenderEngine engine;
    // PHYSICAL PX PER LOGICAL PX, REMEMBERED HERE SO ENGINES ATTACHED LATER INHERIT IT
    private static volatile float uiScale = 1f;

    private RenderSystem() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Selects the engine for the next window, downgrading Vulkan to OpenGL when no Vulkan loader is present. */
    public static void chooseEngine(final Engine requested) {
        final boolean missing = requested == Engine.VULKAN && !glfwVulkanSupported();
        if (missing) WaterMedia.LOGGER.warn(IT, "Vulkan is unavailable on this system; running on OpenGL");
        kind = requested == Engine.VULKAN && !missing ? Engine.VULKAN : Engine.OPENGL;
    }

    /** Applies the GLFW window hints the chosen engine needs. Call right after {@code glfwDefaultWindowHints()}. */
    public static void applyWindowHints() {
        if (kind == Engine.VULKAN) {
            // NO GL CONTEXT: THE VULKAN BACKEND PRESENTS THROUGH ITS OWN SURFACE AND SWAPCHAIN
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        } else {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GL_DEBUG ? GLFW_TRUE : GLFW_FALSE);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        }
    }

    /** Builds the chosen engine on the window; returns false when Vulkan failed and the window must be rebuilt for OpenGL. */
    public static boolean attach(final long window) {
        if (kind == Engine.VULKAN) {
            VulkanRenderBackend vk = null;
            try {
                vk = new VulkanRenderBackend(window);
                engine = new RenderEngine(vk);
                engine.uiScale(uiScale);
                engine.init();
                engine.configureFrameState();
                WaterMedia.LOGGER.info(IT, "Render engine: Vulkan");
                return true;
            } catch (final Throwable t) {
                WaterMedia.LOGGER.warn(IT, "Vulkan engine init failed; running on OpenGL", t);
                // THE VkSurfaceKHR MUST DIE BEFORE THE CALLER REBUILDS THE WINDOW, OR THE DRIVER CAN CRASH AND
                // THE DEVICE AND INSTANCE LEAK
                if (vk != null)
                    try { vk.cleanup(); }
                    catch (final Throwable ct) { WaterMedia.LOGGER.error(IT, "Vulkan backend cleanup after failed init also failed", ct); }
                engine = null;
                kind = Engine.OPENGL;
                return false;
            }
        }
        engine = new RenderEngine(new OpenGLRenderBackend(window));
        engine.uiScale(uiScale);
        // init() MAKES THE GL CONTEXT CURRENT ITSELF, SO RenderBackend.init() SUFFICES FOR BOTH BACKENDS
        engine.init();
        engine.configureFrameState();
        WaterMedia.LOGGER.info(IT, "Render engine: OpenGL");
        return true;
    }

    /** The active engine kind. */
    public static Engine engineKind() { return kind; }

    /** Display name of the active engine ("Vulkan"/"OpenGL"). */
    public static String engineName() { return kind == Engine.VULKAN ? "Vulkan" : "OpenGL"; }

    /** Whether Vulkan is the active engine. */
    public static boolean vulkanActive() { return kind == Engine.VULKAN; }

    /** Whether a Vulkan loader is present (the engine is active, or Vulkan is selectable on this machine). */
    public static boolean vulkanAvailable() { return kind == Engine.VULKAN || glfwVulkanSupported(); }

    /** Name of the active GPU/renderer, captured at engine init (safe to read off the render thread). */
    public static String deviceName() { return engine != null ? engine.deviceName() : "Unknown"; }

    /** Short active-engine label with its max API version, e.g. {@code "GL 4.6"} or {@code "VK 1.3"}. */
    public static String engineVersionLabel() {
        final String prefix = kind == Engine.VULKAN ? "VK" : "GL";
        final String v = engine != null ? engine.deviceVersion() : "";
        return v == null || v.isBlank() ? prefix : prefix + " " + v;
    }

    /** Builds the media graphics engine ({@code GLEngine}/{@code VKEngine}) for the active backend. */
    public static Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return engine.mediaEngineSupplier(renderThread, renderExecutor);
    }

    /** Sets the UI scale on the active engine and remembers it for later ones; the text renderer needs its own call. */
    public static void uiScale(final float scale) {
        uiScale = scale > 0f ? scale : 1f;
        if (engine != null) engine.uiScale(uiScale);
    }

    /** The global UI scale (physical pixels per logical pixel). */
    public static float uiScale() {
        return uiScale;
    }

    public static void cleanup() {
        final RenderEngine current = engine;
        // WINDOW-CREATION MESSAGES BETWEEN A TEARDOWN AND THE NEXT attach() (HOT SWAP) MUST NOT REACH A DEAD
        // BACKEND WHOSE CONTEXT DIED WITH THE PREVIOUS WINDOW
        engine = null;
        if (current != null) current.cleanup();
    }

    /** Returns whether the active engine's tree cache still holds the last cached pass. */
    public static boolean cacheValid() {
        final RenderEngine current = engine;
        return current != null && current.cacheValid();
    }

    /** Frees the tree cache layers between frames while nothing composites; the next cached pass builds them again. */
    public static void releaseCache() {
        final RenderEngine current = engine;
        if (current != null) current.releaseCache();
    }

    public static void viewport(final int width, final int height) {
        // NULL-SAFE: WinFrame'S SWP_FRAMECHANGED FIRES THE SIZE CALLBACK DURING WINDOW CREATION, BEFORE attach();
        // GL TAKES ITS FIRST VIEWPORT FROM THE FRAMEBUFFER AND THE SWAP PATH CALLS viewport() AGAIN
        final RenderEngine current = engine;
        if (current != null) current.viewport(width, height);
    }

    public static boolean beginCache(final float r, final float g, final float b, final float a) { return engine.beginCache(r, g, b, a); }
    public static void raiseCache() { engine.raiseCache(); }
    public static void endCache() { engine.endCache(); }
    public static void dropCache() { engine.dropCache(); }
    public static void drawCache(final boolean top) { engine.drawCache(top); }
    public static void configureFrameState() { engine.configureFrameState(); }
    public static void clear(final float r, final float g, final float b, final float a) { engine.clear(r, g, b, a); }
    public static int createTexture(final int width, final int height, final ByteBuffer rgba) { return engine.createTexture(width, height, rgba); }
    public static void deleteTexture(final int textureId) { engine.deleteTexture(textureId); }
    public static void setupOrtho(final int width, final int height) { engine.setupOrtho(width, height); }
    public static void color(final Color c) { engine.color(c); }
    public static void color(final float r, final float g, final float b, final float a) { engine.color(r, g, b, a); }
    public static void color(final float r, final float g, final float b) { engine.color(r, g, b); }
    public static void bindTexture(final int textureId) { engine.bindTexture(textureId); }
    public static void bindMediaTexture(final long handle) { engine.bindMediaTexture(handle); }
    public static void beginFrame() { engine.beginFrame(); }
    public static void present() { engine.present(); }
    public static void clip(final int x, final int y, final int width, final int height, final int canvasHeight) { engine.clip(x, y, width, height, canvasHeight); }
    public static void clearClip() { engine.clearClip(); }
    public static void lineWidth(final float width) { engine.lineWidth(width); }
    public static void fill(final float x, final float y, final float w, final float h) { engine.fill(x, y, w, h); }
    public static void fill(final float x, final float y, final float w, final float h, final Color c) { engine.fill(x, y, w, h, c); }
    public static void fill(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a) { engine.fill(x, y, w, h, r, g, b, a); }
    public static void fillGradientH(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientH(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillGradientV(final float x, final float y, final float w, final float h, final float r1, final float g1, final float b1, final float a1, final float r2, final float g2, final float b2, final float a2) { engine.fillGradientV(x, y, w, h, r1, g1, b1, a1, r2, g2, b2, a2); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius) { engine.fillRounded(x, y, w, h, radius); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a) { engine.fillRounded(x, y, w, h, radius, r, g, b, a); }
    public static void fillRounded(final float x, final float y, final float w, final float h, final float radius, final Color c) { engine.fillRounded(x, y, w, h, radius, c); }
    public static void rect(final float x, final float y, final float w, final float h, final Color c, final float lineWidth) { engine.rect(x, y, w, h, c, lineWidth); }
    public static void rect(final float x, final float y, final float w, final float h, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rect(x, y, w, h, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final float r, final float g, final float b, final float a, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, r, g, b, a, lineWidth); }
    public static void rectRounded(final float x, final float y, final float w, final float h, final float radius, final Color c, final float lineWidth) { engine.rectRounded(x, y, w, h, radius, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length) { engine.lineH(x, y, length); }
    public static void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) { engine.lineH(x, y, length, c, lineWidth); }
    public static void lineH(final float x, final float y, final float length, final float r, final float g, final float b, final float a, final float lineWidth) { engine.lineH(x, y, length, r, g, b, a, lineWidth); }
    public static void line(final float x1, final float y1, final float x2, final float y2) { engine.line(x1, y1, x2, y2); }
    public static void blit(final float x, final float y, final float w, final float h) { engine.blit(x, y, w, h); }
    public static void blit(final float x, final float y, final float w, final float h, final float u0, final float v0, final float u1, final float v1) { engine.blit(x, y, w, h, u0, v0, u1, v1); }
    public static void glowRect(final float x, final float y, final float w, final float h, final float radius, final Color glow, final float alpha) { engine.glowRect(x, y, w, h, radius, glow, alpha); }
    public static void shadowRect(final float x, final float y, final float w, final float h, final float radius, final float alpha) { engine.shadowRect(x, y, w, h, radius, alpha); }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** The render backends the app can run on. */
    public enum Engine { OPENGL, VULKAN }
}
