package org.watermedia.bootstrap.app.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.render.RenderBackend.DrawMode;
import org.watermedia.bootstrap.app.render.RenderBackend.TextureHandle;

import java.awt.Color;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Backend-agnostic 2D render engine of the app UI, batching vertices for the active backend. */
public final class RenderEngine {

    private static final int FLOATS_PER_VERTEX = 8;
    // SHARED UI VERTEX CAPACITY: THE GL BACKEND SIZES ITS STREAM VBO TO THE SAME VALUE; KEEP THEM IN SYNC
    private static final int MAX_VERTICES = 8_192;
    private static final int ARC_SEGMENTS = 10; // PER ROUNDED CORNER
    // SDF SOFT-RECT FALLOFF IN PIXELS; THE GLOW MATCHES THE 10 * 2.2 SPREAD OF THE STACKED-FILL FALLBACK IN
    // glowRect() AND THE SHADOW IS A SMALLER OFFSET HALO
    private static final float GLOW_SOFTNESS = 22f;
    private static final float SHADOW_SOFTNESS = 12f;
    private static final float SHADOW_DX = 2f;
    private static final float SHADOW_DY = 6f;
    private static final float GLOW_STRENGTH = 0.9f; // OVERALL GLOW FORCE
    private static final int LAYER_BASE = 0; // TREE CACHE UNDER THE LIVE PAINTERS
    private static final int LAYER_TOP = 1; // TREE CACHE OVER THE LIVE PAINTERS, TRANSPARENT WHERE NOTHING WAS DRAWN
    private static final float[] IDENTITY = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
    };

    private final RenderBackend backend;
    private final Matrix4f projection = new Matrix4f();
    private final Vector4f color = new Vector4f(1f, 1f, 1f, 1f);
    private final float[] vertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private final float[] batchVertices = new float[MAX_VERTICES * FLOATS_PER_VERTEX];
    private int boundTextureId = -1;
    private DrawMode batchMode;
    private boolean batchTextured;
    private int batchVertexCount;
    // ACTIVE PROJECTION: (-1,-1) IDENTITY, POSITIVE ORTHO SIZE, MIN_VALUE UNSET; A REPEATED setupOrtho() WITH THE
    // SAME SIZE SKIPS THE FLUSH AND THE RE-UPLOAD, SO THE BATCH KEEPS GROWING
    private int orthoW = Integer.MIN_VALUE;
    private int orthoH = Integer.MIN_VALUE;
    // PHYSICAL PX PER LOGICAL PX, APPLIED ONLY TO SCISSOR RECTS AND RASTERIZED LINE WIDTHS; GEOMETRY SCALES
    // THROUGH THE ORTHO AND THE VIEWPORT STAYS PHYSICAL
    private float uiScale = 1f;
    // BOTH BACKENDS START AT A 1px RASTERIZER LINE WIDTH
    private float physicalLineWidth = 1f;
    // GLOW HEARTBEAT SAMPLED ONCE PER FRAME; IT DRIFTS FAR TOO SLOWLY TO DIFFER BETWEEN GLOWS OF ONE FRAME
    private float pulse = glowPulse();
    // TREE CACHE: caching WHILE A PASS FILLS THE LAYERS, cacheReady ONCE ONE FINISHED AT cacheEpoch, topUsed WHEN IT
    // RAISED INTO THE TOP LAYER
    private boolean caching;
    private boolean cacheReady;
    private int cacheEpoch;
    private boolean topUsed;

    public RenderEngine(final RenderBackend backend) {
        this.backend = backend;
    }

    // ==========================================================================
    // PUBLIC API: RENDERING
    // ==========================================================================
    public void init() {
        this.backend.init();
        // START ON THE IDENTITY PROJECTION
        if (this.orthoW == -1 && this.orthoH == -1) return;
        this.flush();
        this.orthoW = -1;
        this.orthoH = -1;
        this.projection.set(IDENTITY);
        this.backend.useProjection(this.projection);
    }

    public void cleanup() {
        this.flush();
        this.backend.cleanup();
    }

    public void configureFrameState() {
        this.backend.configureFrameState();
    }

    public void clear(final float r, final float g, final float b, final float a) {
        this.flush();
        this.boundTextureId = -1;
        this.backend.clear(r, g, b, a);
    }

    public void viewport(final int width, final int height) {
        this.flush();
        this.backend.viewport(width, height);
    }

    public int createTexture(final int width, final int height, final ByteBuffer rgba) {
        this.flush();
        final int id = this.backend.createTexture(width, height, rgba).id();
        this.boundTextureId = -1;
        return id;
    }

    public void deleteTexture(final int textureId) {
        this.flush();
        // ONLY THE ID IS KNOWN AT THE APP SEAM; BOTH BACKENDS DELETE BY ID, SO ZERO EXTENTS ARE FINE
        this.backend.deleteTexture(new TextureHandle(textureId, 0, 0));
        this.boundTextureId = -1;
    }

    public void setupOrtho(final int width, final int height) {
        if (this.orthoW == width && this.orthoH == height) return;
        this.flush();
        this.orthoW = width;
        this.orthoH = height;
        this.projection.identity().ortho2D(0f, width, height, 0f);
        this.backend.useProjection(this.projection);
    }

    public void color(final Color c) {
        this.color.set(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
    }

    public void color(final float r, final float g, final float b, final float a) {
        this.color.set(r, g, b, a);
    }

    public void color(final float r, final float g, final float b) {
        this.color.set(r, g, b, 1f);
    }

    public void bindTexture(final int textureId) {
        if (this.boundTextureId == textureId) return;
        this.flush();
        this.boundTextureId = textureId;
        this.backend.bindTexture(textureId);
    }

    public void bindMediaTexture(final long handle) {
        this.flush();
        // SENTINEL: A MEDIA TEXTURE IS BOUND SO blit() EMITS TEXTURED VERTICES. A 64-BIT VULKAN
        // VkImageView DOES NOT FIT boundTextureId, AND MAX_VALUE NEVER COLLIDES WITH A REAL GL ID
        this.boundTextureId = handle != 0L ? Integer.MAX_VALUE : -1;
        this.backend.bindMediaTexture(handle);
    }

    public void beginFrame() {
        this.pulse = glowPulse();
        this.backend.beginFrame();
    }

    public void present() {
        this.flush();
        this.backend.present();
    }

    public Supplier<GFXEngine> mediaEngineSupplier(final Thread renderThread, final Executor renderExecutor) {
        return this.backend.mediaEngineSupplier(renderThread, renderExecutor);
    }

    public String deviceName() {
        return this.backend.deviceName();
    }

    public String deviceVersion() {
        return this.backend.deviceVersion();
    }

    /** Sets the UI scale that maps logical scissor rects and line widths to physical pixels. */
    public void uiScale(final float scale) {
        this.uiScale = scale > 0f ? scale : 1f;
    }

    /** Returns the UI scale (physical pixels per logical pixel). */
    public float uiScale() {
        return this.uiScale;
    }

    public void clip(final int x, final int y, final int width, final int height, final int canvasHeight) {
        this.flush();
        // LOGICAL TO PHYSICAL: ROUND THE EDGES (NOT THE SIZES) SO ADJACENT CLIPS STAY GAP-FREE AT ANY SCALE;
        // AT uiScale=1 EVERY ROUND IS AN EXACT IDENTITY. THE BACKENDS ONLY FLIP AND CLAMP PHYSICAL INTS
        final float s = this.uiScale;
        final int px = Math.round(x * s);
        final int py = Math.round(y * s);
        final int pw = Math.round((x + width) * s) - px;
        final int ph = Math.round((y + height) * s) - py;
        this.backend.clip(px, py, pw, ph, Math.round(canvasHeight * s));
    }

    public void clearClip() {
        this.flush();
        this.backend.clearClip();
    }

    public void lineWidth(final float width) {
        // RASTERIZER LINE WIDTH (glLineWidth, vkCmdSetLineWidth) IS PHYSICAL PIXELS; QUAD STROKES (strokeQuads) ARE
        // LOGICAL GEOMETRY AND ALREADY SCALE WITH THE ORTHO
        final float physical = width * this.uiScale;
        // THE BACKENDS KEEP THE LAST WIDTH (VULKAN RE-RECORDS IT PER FRAME), SO AN EQUAL WIDTH KEEPS THE BATCH
        if (physical == this.physicalLineWidth) return;
        this.flush();
        this.physicalLineWidth = physical;
        this.backend.lineWidth(physical);
    }

    public void flush() {
        if (this.batchVertexCount <= 0 || this.batchMode == null) return;
        this.backend.draw(this.batchMode, this.batchVertices, this.batchVertexCount, this.batchTextured);
        this.batchVertexCount = 0;
        this.batchMode = null;
    }

    public void fill(final float x, final float y, final float w, final float h) {
        final Vector4f c = this.color;
        this.put(0, x, y, 0f, 0f, c);
        this.put(1, x + w, y, 0f, 0f, c);
        this.put(2, x + w, y + h, 0f, 0f, c);
        this.put(3, x, y, 0f, 0f, c);
        this.put(4, x + w, y + h, 0f, 0f, c);
        this.put(5, x, y + h, 0f, 0f, c);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fill(final float x, final float y, final float w, final float h, final Color c) {
        this.color(c);
        this.fill(x, y, w, h);
    }

    public void fill(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        this.fill(x, y, w, h);
    }

    public void fillGradientH(final float x, final float y, final float w, final float h,
                              final float r1, final float g1, final float b1, final float a1,
                              final float r2, final float g2, final float b2, final float a2) {
        this.put(0, x, y, 0f, 0f, r1, g1, b1, a1);
        this.put(1, x, y + h, 0f, 0f, r1, g1, b1, a1);
        this.put(2, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        this.put(3, x, y, 0f, 0f, r1, g1, b1, a1);
        this.put(4, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        this.put(5, x + w, y, 0f, 0f, r2, g2, b2, a2);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fillGradientV(final float x, final float y, final float w, final float h,
                              final float r1, final float g1, final float b1, final float a1,
                              final float r2, final float g2, final float b2, final float a2) {
        this.put(0, x, y, 0f, 0f, r1, g1, b1, a1);
        this.put(1, x + w, y, 0f, 0f, r1, g1, b1, a1);
        this.put(2, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        this.put(3, x, y, 0f, 0f, r1, g1, b1, a1);
        this.put(4, x + w, y + h, 0f, 0f, r2, g2, b2, a2);
        this.put(5, x, y + h, 0f, 0f, r2, g2, b2, a2);
        this.draw(DrawMode.TRIANGLES, 6, false);
    }

    public void fillRounded(final float x, final float y, final float w, final float h, final float radius) {
        if (radius <= 0f) {
            this.fill(x, y, w, h);
            return;
        }
        final float rad = Math.min(radius, Math.min(w, h) / 2f);
        this.fill(x + rad, y, w - 2 * rad, h);
        this.fill(x, y + rad, rad, h - 2 * rad);
        this.fill(x + w - rad, y + rad, rad, h - 2 * rad);
        this.fillArc(x + rad, y + rad, rad, (float) Math.PI, (float) (Math.PI * 1.5));
        this.fillArc(x + w - rad, y + rad, rad, (float) (Math.PI * 1.5), (float) (Math.PI * 2));
        this.fillArc(x + w - rad, y + h - rad, rad, 0, (float) (Math.PI * 0.5));
        this.fillArc(x + rad, y + h - rad, rad, (float) (Math.PI * 0.5), (float) Math.PI);
    }

    public void fillRounded(final float x, final float y, final float w, final float h, final float radius,
                            final float r, final float g, final float b, final float a) {
        this.color(r, g, b, a);
        this.fillRounded(x, y, w, h, radius);
    }

    public void fillRounded(final float x, final float y, final float w, final float h,
                            final float radius, final Color c) {
        this.color(c);
        this.fillRounded(x, y, w, h, radius);
    }

    public void rect(final float x, final float y, final float w, final float h,
                     final Color c, final float lineWidth) {
        this.color(c);
        this.strokeQuads(x, y, w, h, lineWidth);
    }

    public void rect(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.strokeQuads(x, y, w, h, lineWidth);
    }

    public void rectRounded(final float x, final float y, final float w, final float h,
                            final float radius, final float lineWidth) {
        if (radius <= 0f) {
            this.strokeQuads(x, y, w, h, lineWidth);
            return;
        }
        final float rad = Math.min(radius, Math.min(w, h) / 2f);
        this.lineWidth(lineWidth);
        int idx = 0;
        idx = this.putArc(idx, x + w - rad, y + rad, rad, (float) (-Math.PI / 2), 0f);
        idx = this.putArc(idx, x + w - rad, y + h - rad, rad, 0f, (float) (Math.PI / 2));
        idx = this.putArc(idx, x + rad, y + h - rad, rad, (float) (Math.PI / 2), (float) Math.PI);
        idx = this.putArc(idx, x + rad, y + rad, rad, (float) Math.PI, (float) (Math.PI * 1.5));
        this.draw(DrawMode.LINE_LOOP, idx, false);
    }

    public void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                            final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.rectRounded(x, y, w, h, radius, lineWidth);
    }

    public void rectRounded(final float x, final float y, final float w, final float h, final float radius,
                            final Color c, final float lineWidth) {
        this.color(c);
        this.rectRounded(x, y, w, h, radius, lineWidth);
    }

    public void lineH(final float x, final float y, final float length) {
        this.line(x, y, x + length, y);
    }

    public void lineH(final float x, final float y, final float length, final Color c, final float lineWidth) {
        this.color(c);
        this.lineWidth(lineWidth);
        this.lineH(x, y, length);
    }

    public void lineH(final float x, final float y, final float length,
                      final float r, final float g, final float b, final float a, final float lineWidth) {
        this.color(r, g, b, a);
        this.lineWidth(lineWidth);
        this.lineH(x, y, length);
    }

    public void line(final float x1, final float y1, final float x2, final float y2) {
        this.put(0, x1, y1, 0f, 0f, this.color);
        this.put(1, x2, y2, 0f, 0f, this.color);
        this.draw(DrawMode.LINES, 2, false);
    }

    public void blit(final float x, final float y, final float w, final float h) {
        this.put(0, x, y, 0f, 0f, this.color);
        this.put(1, x + w, y, 1f, 0f, this.color);
        this.put(2, x + w, y + h, 1f, 1f, this.color);
        this.put(3, x, y, 0f, 0f, this.color);
        this.put(4, x + w, y + h, 1f, 1f, this.color);
        this.put(5, x, y + h, 0f, 1f, this.color);
        this.draw(DrawMode.TRIANGLES, 6, this.boundTextureId > 0);
    }

    public void blit(final float x, final float y, final float w, final float h,
                     final float u0, final float v0, final float u1, final float v1) {
        this.put(0, x, y, u0, v0, this.color);
        this.put(1, x + w, y, u1, v0, this.color);
        this.put(2, x + w, y + h, u1, v1, this.color);
        this.put(3, x, y, u0, v0, this.color);
        this.put(4, x + w, y + h, u1, v1, this.color);
        this.put(5, x, y + h, u0, v1, this.color);
        this.draw(DrawMode.TRIANGLES, 6, this.boundTextureId > 0);
    }

    public void glowRect(final float x, final float y, final float w, final float h,
                         final float radius, final Color glow, final float alpha) {
        final float r = glow.getRed() / 255f;
        final float g = glow.getGreen() / 255f;
        final float b = glow.getBlue() / 255f;
        final float a = alpha * this.pulse;
        if (this.backend.supportsSoftRect()) {
            // ONE SDF QUAD INSTEAD OF 10 STACKED FILLS: SAME HALO, A FRACTION OF THE OVERDRAW
            this.flush();
            this.backend.softRect(x, y, w, h, radius, r, g, b, a, GLOW_SOFTNESS);
            return;
        }
        for (int i = 10; i >= 1; i--) {
            final float spread = i * 2.2f;
            final float falloff = (float) Math.pow(i + 1f, 1.8f);
            if (radius <= 0f) this.fill(x - spread, y - spread, w + spread * 2f, h + spread * 2f, r, g, b, a / falloff);
            else this.fillRounded(x - spread, y - spread, w + spread * 2f, h + spread * 2f, radius + spread, r, g, b, a / falloff);
        }
    }

    public void shadowRect(final float x, final float y, final float w, final float h,
                           final float radius, final float alpha) {
        if (this.backend.supportsSoftRect()) {
            // ONE SDF QUAD (BLACK, OFFSET DOWN-RIGHT) INSTEAD OF 4 STACKED FILLS
            this.flush();
            this.backend.softRect(x + SHADOW_DX, y + SHADOW_DY, w, h, radius, 0f, 0f, 0f, alpha, SHADOW_SOFTNESS);
            return;
        }
        for (int i = 4; i >= 1; i--) {
            final float spread = i * 3f;
            if (radius <= 0f) this.fill(x + spread, y + spread, w, h, 0f, 0f, 0f, alpha / (i + 2f));
            else this.fillRounded(x + spread, y + spread, w, h, radius, 0f, 0f, 0f, alpha / (i + 2f));
        }
    }

    // ==========================================================================
    // PUBLIC API: TREE CACHE
    // ==========================================================================
    // THE TREE PAINTS INTO OFFSCREEN LAYERS ONLY WHEN IT CHANGES, AND EVERY OTHER FRAME DRAWS THE LAYERS
    // BACK WITH THE LIVE PAINTERS (THE CRT BAND) BETWEEN THEM

    /** Returns whether the layers still hold the last cached tree pass. */
    public boolean cacheValid() {
        return this.cacheReady && this.cacheEpoch == this.backend.layerEpoch();
    }

    /** Starts a cached tree pass in the base layer; false when this frame cannot cache, so the tree draws to the window. */
    public boolean beginCache(final float r, final float g, final float b, final float a) {
        this.flush();
        this.cacheReady = false;
        this.topUsed = false;
        this.caching = this.backend.beginLayer(LAYER_BASE, r, g, b, a);
        // BUILDING A LAYER MAY BIND ITS OWN TEXTURE, SO THE NEXT bindTexture() MUST REACH THE BACKEND
        this.boundTextureId = -1;
        return this.caching;
    }

    /** Sends the rest of the cached pass to the top layer, which composites over the live painters. */
    public void raiseCache() {
        if (!this.caching || this.topUsed) return;
        this.flush();
        // A TOP LAYER THAT CANNOT OPEN LEAVES THE REST IN THE BASE ONE, UNDER THE PAINTERS BUT STILL VISIBLE
        this.topUsed = this.backend.beginLayer(LAYER_TOP, 0f, 0f, 0f, 0f);
        this.boundTextureId = -1;
    }

    /** Ends the cached pass; the layers stand in for the tree until it changes or the backend drops them. */
    public void endCache() {
        if (!this.caching) return;
        this.flush();
        this.backend.endLayer();
        this.caching = false;
        this.cacheReady = true;
        this.cacheEpoch = this.backend.layerEpoch();
    }

    /** Forgets the layers after a pass that drew the tree straight into the window. */
    public void dropCache() {
        this.cacheReady = false;
    }

    /** Frees the layers between frames while nothing composites, such as with the CRT off or the window minimized. */
    public void releaseCache() {
        this.cacheReady = false;
        this.backend.releaseLayers();
    }

    /** Draws the base or the top layer over the whole window; the top one only when the last pass raised it. */
    public void drawCache(final boolean top) {
        if (top && !this.topUsed) return;
        this.clearClip();
        if (!this.backend.bindLayer(top ? LAYER_TOP : LAYER_BASE)) return;
        // THE SAME SENTINEL AS A MEDIA FRAME: blit() EMITS TEXTURED VERTICES AND THE NEXT bindTexture() REBINDS
        this.boundTextureId = Integer.MAX_VALUE;
        final boolean flipped = this.backend.layerFlipped();
        this.color.set(1f, 1f, 1f, 1f);
        this.blit(0f, 0f, this.orthoW, this.orthoH, 0f, flipped ? 1f : 0f, 1f, flipped ? 0f : 1f);
        this.flush();
        this.boundTextureId = -1;
    }

    // ==========================================================================
    // VERTEX EMISSION
    // ==========================================================================
    private void strokeQuads(final float x, final float y, final float w, final float h, final float lineWidth) {
        // FOUR EDGE QUADS CENTERED ON THE PATH, CORNERS COVERED ONCE SO A TRANSLUCENT BORDER IS NOT DOUBLED;
        // UNLIKE A LINE_LOOP THEY JOIN THE TRIANGLE BATCH WITHOUT A FLUSH OR A LINE-WIDTH CHANGE
        final float lw = Math.max(1f, lineWidth);
        final float half = lw / 2f;
        this.fill(x - half, y - half, lw, h + lw);
        this.fill(x + w - half, y - half, lw, h + lw);
        if (w > lw) {
            this.fill(x + half, y - half, w - lw, lw);
            this.fill(x + half, y + h - half, w - lw, lw);
        }
    }

    private void fillArc(final float cx, final float cy, final float radius, final float startAngle, final float endAngle) {
        this.put(0, cx, cy, 0f, 0f, this.color);
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            final float angle = startAngle + (endAngle - startAngle) * i / ARC_SEGMENTS;
            this.put(1 + i, cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius, 0f, 0f, this.color);
        }
        this.draw(DrawMode.TRIANGLE_FAN, 2 + ARC_SEGMENTS, false);
    }

    private int putArc(final int startIdx, final float cx, final float cy, final float radius, final float startAngle, final float endAngle) {
        int idx = startIdx;
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            final float angle = startAngle + (endAngle - startAngle) * i / ARC_SEGMENTS;
            this.put(idx++, cx + (float) Math.cos(angle) * radius, cy + (float) Math.sin(angle) * radius, 0f, 0f, this.color);
        }
        return idx;
    }

    private void put(final int index, final float x, final float y, final float u, final float v, final Vector4f c) {
        this.put(index, x, y, u, v, c.x, c.y, c.z, c.w);
    }

    private void put(final int index, final float x, final float y, final float u, final float v,
                     final float r, final float g, final float b, final float a) {
        // SCALAR COLOR OVERLOAD: GRADIENT AND FADE VERTICES EMIT WITHOUT A Vector4f PER CALL
        final int off = index * FLOATS_PER_VERTEX;
        this.vertices[off] = x;
        this.vertices[off + 1] = y;
        this.vertices[off + 2] = u;
        this.vertices[off + 3] = v;
        this.vertices[off + 4] = r;
        this.vertices[off + 5] = g;
        this.vertices[off + 6] = b;
        this.vertices[off + 7] = a;
    }

    private void draw(final DrawMode mode, final int count, final boolean textured) {
        if (count <= 0) return;
        // GUARD THE SCRATCH AND BATCH ARRAYS (AND THE GL VBO) AT THE SINGLE EMIT SEAM SO NO PRIMITIVE OVERFLOWS
        if (count > MAX_VERTICES) throw new IllegalArgumentException("UI draw exceeds vertex capacity: " + count + " > " + MAX_VERTICES);
        if (mode != DrawMode.TRIANGLES && mode != DrawMode.LINES) {
            this.flush();
            this.backend.draw(mode, this.vertices, count, textured);
            return;
        }

        if (this.batchMode != mode || this.batchTextured != textured || this.batchVertexCount + count > MAX_VERTICES) {
            this.flush();
            this.batchMode = mode;
            this.batchTextured = textured;
        }
        System.arraycopy(this.vertices, 0, this.batchVertices, this.batchVertexCount * FLOATS_PER_VERTEX, count * FLOATS_PER_VERTEX);
        this.batchVertexCount += count;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static float glowPulse() {
        // SLOW, NON-REPEATING HEARTBEAT OF THE GLOW FORCE: THREE INCOMMENSURATE SINES NEVER LOOP EXACTLY, SO THE
        // PULSE BREATHES OVER ~10-30 SECONDS. RESULT IN [0.74, 0.9]
        final double t = System.currentTimeMillis() * 0.001;
        final double wave = Math.sin(t * 0.45) + 0.6 * Math.sin(t * 0.19 + 1.3) + 0.35 * Math.sin(t * 0.83 + 2.7);
        final double n = MathUtil.clamp(wave * 0.256 + 0.5, 0.0, 1.0);
        return (float) (GLOW_STRENGTH * (0.82 + 0.18 * n));
    }
}
