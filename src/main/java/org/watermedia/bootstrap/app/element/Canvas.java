package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drawing surface for {@link Element#onDraw}: a facade over {@link RenderSystem} and {@link TextRenderer} in top-left
 * window pixels. {@link #pushAlpha} scales the opacity of every following draw, so a container fades its whole subtree,
 * and {@link #live} draws what changes every frame over a tree cached by the render engine.
 */
public final class Canvas {
    private static final int CLIP_LEVELS = 16;

    private final TextRenderer text;
    // GLOBAL ALPHA MULTIPLIER STACK, MULTIPLICATIVE AND NESTABLE; 16 LEVELS IS FAR BEYOND ANY REAL TREE
    private final float[] alphaStack = new float[16];
    private int alphaDepth;
    // LEFT, TOP, RIGHT AND BOTTOM OF EACH PUSHED CLIP, ALREADY INTERSECTED WITH ITS PARENT
    private final int[] clipStack = new int[CLIP_LEVELS * 4];
    private int clipDepth;
    private float alpha = 1f;
    private int windowWidth;
    private int windowHeight;
    // LIVE PAINTERS OF THE LAST TREE PASS, IN DRAW ORDER; A CACHED PASS DEFERS THEM AND raisedAt SPLITS THOSE UNDER
    // THE TOP LAYER FROM THOSE OVER IT (-1 = NOTHING RAISED)
    private final List<Live> live = new ArrayList<>();
    private boolean deferring;
    private int raisedAt = -1;

    public Canvas(final TextRenderer text) {
        this.text = text;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Starts a tree pass; a cached one defers the live painters to {@link #paintLive} instead of drawing them. */
    public void beginTree(final boolean cached) {
        this.live.clear();
        this.deferring = cached;
        this.raisedAt = -1;
    }

    /** Returns whether the last tree pass met a live painter, which needs a repaint every frame. */
    public boolean hasLive() {
        return !this.live.isEmpty();
    }

    /**
     * Draws something that changes every frame: inline in a direct pass, over the cached tree in every composite. A
     * composite paints it over everything its layer holds, so whatever must stay above it, even when drawn later by the
     * same element, has to come after a {@link #raise()}.
     */
    public void live(final Consumer<Canvas> painter) {
        final boolean clipped = this.clipDepth > 0;
        final int at = Math.min(this.clipDepth, CLIP_LEVELS) * 4 - 4;
        this.live.add(new Live(painter, clipped, clipped ? this.clipStack[at] : 0, clipped ? this.clipStack[at + 1] : 0,
                clipped ? this.clipStack[at + 2] : 0, clipped ? this.clipStack[at + 3] : 0, this.alpha));
        if (!this.deferring) painter.accept(this);
    }

    /** Marks the rest of the pass as drawn over the live painters met so far, such as a dialog or a HUD. */
    public void raise() {
        if (!this.deferring || this.raisedAt >= 0 || this.live.isEmpty()) return;
        this.raisedAt = this.live.size();
        RenderSystem.raiseCache();
    }

    /** Repaints the deferred live painters met before the raise, or after it, with their clip and fade. */
    public void paintLive(final boolean raised) {
        final int split = this.raisedAt < 0 ? this.live.size() : this.raisedAt;
        final int from = raised ? split : 0;
        final int to = raised ? this.live.size() : split;
        for (int i = from; i < to; i++) {
            final Live entry = this.live.get(i);
            this.pushAlpha(entry.alpha());
            if (entry.clipped()) this.pushClip(entry.left(), entry.top(), entry.right() - entry.left(), entry.bottom() - entry.top());
            entry.painter().accept(this);
            if (entry.clipped()) this.popClip();
            this.popAlpha();
        }
    }

    /** Pushes an alpha multiplier for following draws; nested factors multiply and each needs a {@link #popAlpha()}. */
    public void pushAlpha(final float factor) {
        // PAST CAPACITY THE FACTOR STILL MULTIPLIES BUT IS NOT RECORDED, SO AN UNBALANCED PUSH NEVER WRITES OUT OF BOUNDS
        if (this.alphaDepth < this.alphaStack.length) this.alphaStack[this.alphaDepth] = this.alpha;
        this.alphaDepth++;
        this.alpha *= MathUtil.clamp(factor, 0f, 1f);
    }

    /** Restores the alpha multiplier active before the matching {@link #pushAlpha(float)}. */
    public void popAlpha() {
        if (this.alphaDepth > 0) {
            this.alphaDepth--;
            if (this.alphaDepth < this.alphaStack.length) this.alpha = this.alphaStack[this.alphaDepth];
        }
    }

    public Canvas viewport(final int width, final int height) {
        this.windowWidth = width;
        this.windowHeight = height;
        // EVERY FRAME STARTS HERE, SO A DRAW THAT THREW MID-FRAME CANNOT LEAK ITS CLIP OR FADE INTO THE NEXT ONE
        this.clipDepth = 0;
        this.alphaDepth = 0;
        this.alpha = 1f;
        return this;
    }

    public TextRenderer text() { return this.text; }
    public int windowWidth() { return this.windowWidth; }
    public int windowHeight() { return this.windowHeight; }

    /** Fills a square-cornered rectangle. */
    public void fill(final float x, final float y, final float w, final float h, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, 0f, this.mul(color));
    }

    /** Fills a rectangle from raw RGBA components, for overlays whose alpha is not a theme color. */
    public void fill(final float x, final float y, final float w, final float h,
                     final float r, final float g, final float b, final float a) {
        RenderSystem.fill(x, y, w, h, r, g, b, a * this.alpha);
    }

    public void fillRound(final float x, final float y, final float w, final float h, final float radius, final Color color) {
        if (color == null) return;
        RenderSystem.fillRounded(x, y, w, h, radius, this.mul(color));
    }

    public void stroke(final float x, final float y, final float w, final float h, final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rect(x, y, w, h, this.mul(color), lineWidth);
    }

    public void strokeRound(final float x, final float y, final float w, final float h, final float radius,
                            final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.rectRounded(x, y, w, h, radius, this.mul(color), lineWidth);
    }

    public void glow(final float x, final float y, final float w, final float h, final float radius,
                     final Color color, final float alpha) {
        if (color == null || alpha <= 0f) return;
        RenderSystem.glowRect(x, y, w, h, radius, color, alpha * this.alpha);
    }

    public void shadow(final float x, final float y, final float w, final float h, final float radius, final float alpha) {
        if (alpha <= 0f) return;
        RenderSystem.shadowRect(x, y, w, h, radius, alpha * this.alpha);
    }

    public void gradientV(final float x, final float y, final float w, final float h, final Color top, final Color bottom) {
        RenderSystem.fillGradientV(x, y, w, h,
                top.getRed() / 255f, top.getGreen() / 255f, top.getBlue() / 255f, top.getAlpha() / 255f * this.alpha,
                bottom.getRed() / 255f, bottom.getGreen() / 255f, bottom.getBlue() / 255f, bottom.getAlpha() / 255f * this.alpha);
    }

    /** Paints a vertical gradient from raw RGBA stops. */
    public void gradientV(final float x, final float y, final float w, final float h,
                          final float r1, final float g1, final float b1, final float a1,
                          final float r2, final float g2, final float b2, final float a2) {
        RenderSystem.fillGradientV(x, y, w, h, r1, g1, b1, a1 * this.alpha, r2, g2, b2, a2 * this.alpha);
    }

    public void gradientH(final float x, final float y, final float w, final float h, final Color left, final Color right) {
        RenderSystem.fillGradientH(x, y, w, h,
                left.getRed() / 255f, left.getGreen() / 255f, left.getBlue() / 255f, left.getAlpha() / 255f * this.alpha,
                right.getRed() / 255f, right.getGreen() / 255f, right.getBlue() / 255f, right.getAlpha() / 255f * this.alpha);
    }

    /** Paints a horizontal gradient from raw RGBA stops. */
    public void gradientH(final float x, final float y, final float w, final float h,
                          final float r1, final float g1, final float b1, final float a1,
                          final float r2, final float g2, final float b2, final float a2) {
        RenderSystem.fillGradientH(x, y, w, h, r1, g1, b1, a1 * this.alpha, r2, g2, b2, a2 * this.alpha);
    }

    /** Draws a straight line between two points at the given width. */
    public void line(final float x1, final float y1, final float x2, final float y2, final Color color, final float lineWidth) {
        RenderSystem.color(this.mul(color));
        RenderSystem.lineWidth(lineWidth);
        RenderSystem.line(x1, y1, x2, y2);
    }

    /** Draws a horizontal rule of the given length and weight. */
    public void lineH(final float x, final float y, final float length, final Color color, final float lineWidth) {
        if (color == null || lineWidth <= 0f) return;
        RenderSystem.lineH(x, y, length, this.mul(color), lineWidth);
    }

    /** Blits a texture with an optional tint; a null tint draws it untinted. */
    public void image(final int textureId, final float x, final float y, final float w, final float h, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) RenderSystem.color(1f, 1f, 1f, this.alpha);
        else RenderSystem.color(this.mul(tint));
        RenderSystem.blit(x, y, w, h);
    }

    /** Blits a UV sub-region of a texture, for cover-fit cropping without distortion. */
    public void image(final int textureId, final float x, final float y, final float w, final float h,
                      final float u0, final float v0, final float u1, final float v1, final Color tint) {
        if (textureId <= 0) return;
        RenderSystem.bindTexture(textureId);
        if (tint == null) RenderSystem.color(1f, 1f, 1f, this.alpha);
        else RenderSystem.color(this.mul(tint));
        RenderSystem.blit(x, y, w, h, u0, v0, u1, v1);
    }

    /** Blits a player frame by its 64-bit handle (GL texture name or Vulkan {@code VkImageView}) through the media path. */
    public void mediaImage(final long handle, final float x, final float y, final float w, final float h) {
        if (handle == 0L) return;
        RenderSystem.bindMediaTexture(handle);
        RenderSystem.color(1f, 1f, 1f, this.alpha);
        RenderSystem.blit(x, y, w, h);
    }

    /** Blits a UV sub-region of a player frame, for cover-fit cropping without distortion. */
    public void mediaImage(final long handle, final float x, final float y, final float w, final float h,
                           final float u0, final float v0, final float u1, final float v1) {
        if (handle == 0L) return;
        RenderSystem.bindMediaTexture(handle);
        RenderSystem.color(1f, 1f, 1f, this.alpha);
        RenderSystem.blit(x, y, w, h, u0, v0, u1, v1);
    }

    /** Draws the glowing amber cube that accents the corners of panels, frames and dialogs. */
    public void cube(final int x, final int y, final int size) {
        this.glow(x, y, size, size, 0f, AppTheme.AMBER, 0.32f);
        this.fill(x, y, size, size, AppTheme.AMBER);
    }

    /** Draws a pixel icon with the alpha multiplier applied. */
    public void icon(final String name, final int x, final int y, final int size, final Color color) {
        PixelIcon.draw(name, x, y, size, this.mul(color));
    }

    public void text(final String value, final float x, final float y, final Color color, final float scale, final boolean bold) {
        if (value == null || value.isEmpty()) return;
        // THE TEXT RENDERER RECEIVES THE ALREADY-MULTIPLIED COLOR AND SETS IT ONCE FOR THE WHOLE RUN
        final Color c = this.mul(color);
        if (bold) this.text.renderBold(value, x, y, c, scale);
        else this.text.render(value, x, y, c, scale);
    }

    public int textWidth(final String value, final float scale, final boolean bold) {
        return bold ? this.text.widthBold(value, scale) : this.text.width(value, scale);
    }

    public int textHeight(final float scale, final boolean bold) {
        return bold ? this.text.glyphHeightBold(scale) : this.text.glyphHeight(scale);
    }

    /** Draws a letter-spaced run glyph by glyph with extra logical pixels between characters. */
    public void textSpaced(final String value, final float x, final float y, final Color color,
                           final float scale, final boolean bold, final int letterSpacing) {
        if (value == null || value.isEmpty()) return;
        this.text.renderSpaced(value, x, y, this.mul(color), scale, bold, letterSpacing);
    }

    /** Returns the width of a letter-spaced run as {@link #textSpaced} draws it. */
    public int textSpacedWidth(final String value, final float scale, final boolean bold, final int letterSpacing) {
        return this.text.spacedWidth(value, scale, bold, letterSpacing);
    }

    /** Clips every following draw to the rectangle intersected with the active clip; balance it with {@link #popClip}. */
    public void pushClip(final int x, final int y, final int w, final int h) {
        // A SATURATED STACK INTERSECTS WITH ITS DEEPEST RECORDED CLIP, LIKE THE ALPHA STACK; AN EMPTY ONE WITH NOTHING
        final int parent = Math.min(this.clipDepth, CLIP_LEVELS) * 4 - 4;
        final boolean nested = this.clipDepth > 0;
        final int left = nested ? Math.max(x, this.clipStack[parent]) : x;
        final int top = nested ? Math.max(y, this.clipStack[parent + 1]) : y;
        final int right = Math.max(left, nested ? Math.min(x + Math.max(0, w), this.clipStack[parent + 2]) : x + Math.max(0, w));
        final int bottom = Math.max(top, nested ? Math.min(y + Math.max(0, h), this.clipStack[parent + 3]) : y + Math.max(0, h));
        if (this.clipDepth < CLIP_LEVELS) {
            final int at = this.clipDepth * 4;
            this.clipStack[at] = left;
            this.clipStack[at + 1] = top;
            this.clipStack[at + 2] = right;
            this.clipStack[at + 3] = bottom;
        }
        this.clipDepth++;
        RenderSystem.clip(left, top, right - left, bottom - top, this.windowHeight);
    }

    /** Restores the clip active before the matching {@link #pushClip}, or disables clipping at the outermost level. */
    public void popClip() {
        if (this.clipDepth == 0) return;
        this.clipDepth--;
        if (this.clipDepth == 0) {
            RenderSystem.clearClip();
            return;
        }
        final int at = Math.min(this.clipDepth, CLIP_LEVELS) * 4 - 4;
        RenderSystem.clip(this.clipStack[at], this.clipStack[at + 1], this.clipStack[at + 2] - this.clipStack[at],
                this.clipStack[at + 3] - this.clipStack[at + 1], this.windowHeight);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Color mul(final Color c) {
        // THE GLOBAL ALPHA MULTIPLIER; IDENTITY (NO ALLOCATION) WHEN NO FADE IS ACTIVE
        if (c == null || this.alpha >= 1f) return c;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() * this.alpha));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // A PAINTER WITH THE CLIP AND FADE ACTIVE WHERE THE TREE REACHED IT
    private record Live(Consumer<Canvas> painter, boolean clipped, int left, int top, int right, int bottom, float alpha) {}
}
