package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.screen.Screen.Keybind;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Hosts the window as one retained tree: the {@link Background}, a {@link TitleBar} / screen slot /
 * {@link KeybindsBar} column and a top layer for global dialogs, with pointer capture over all input.
 */
public final class RootScreen {
    private static final float CLEAR_R = 0.04f;
    private static final float CLEAR_G = 0.06f;
    private static final float CLEAR_B = 0.12f;

    private final AppContext ctx;
    private final Canvas canvas;
    private final ParentFrame tree;
    private final TitleBar titleBar;
    private final ParentFrame centerSlot;
    private final ParentFrame dialogs;
    private Element<?> captured; // VIEW THAT CLAIMED THE CURRENT PRESS AND RECEIVES DRAG/RELEASE
    private boolean wasDragging;
    private boolean dragMoved;
    private double pressX;
    private double pressY;

    public RootScreen(final AppContext ctx, final TextRenderer text, final Supplier<List<Keybind>> keybinds) {
        this.ctx = ctx;
        this.canvas = new Canvas(text);
        this.titleBar = new TitleBar();
        this.centerSlot = new ParentFrame().width(Element.MAX_PARENT).weight(1f);
        final Parent column = Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.titleBar)
                .add(this.centerSlot)
                .add(new KeybindsBar(keybinds));
        // GLOBAL DIALOGS LIVE IN THEIR OWN TOP LAYER, ABOVE THE SCREEN COLUMN
        this.dialogs = new ParentFrame().size(Element.MAX_PARENT, Element.MAX_PARENT);
        this.tree = new ParentFrame()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(new Background())
                .add(column)
                .add(this.dialogs);
        this.tree.attach(ctx);
    }

    // ==========================================================================
    // PUBLIC API: RENDER AND MOUNT
    // ==========================================================================

    /** Updates, measures, lays out and draws the tree in logical pixels; with {@code cache} it fills the engine's layers and composites them. */
    public void render(final int logicalW, final int logicalH, final boolean cache) {
        // BINDINGS FIRST: onUpdate HOOKS PULL LIVE STATE INTO THE TREE BEFORE IT IS MEASURED
        this.tree.update();
        RenderSystem.setupOrtho(logicalW, logicalH);
        this.tree.measure(logicalW, logicalH);
        this.tree.layout(0, 0);
        this.canvas.viewport(logicalW, logicalH);
        // A FRAME THAT CANNOT CACHE (MINIMIZED, NO LAYER SUPPORT) DRAWS STRAIGHT INTO THE WINDOW INSTEAD
        final boolean cached = cache && RenderSystem.beginCache(CLEAR_R, CLEAR_G, CLEAR_B, 1f);
        if (!cached) {
            RenderSystem.dropCache();
            RenderSystem.clear(CLEAR_R, CLEAR_G, CLEAR_B, 1f);
        }
        this.canvas.beginTree(cached);
        this.tree.draw(this.canvas);
        if (!cached) return;
        RenderSystem.endCache();
        this.composite(logicalW, logicalH);
    }

    /** Draws the cached tree back with the live painters between its layers, without touching the tree. */
    public void composite(final int logicalW, final int logicalH) {
        RenderSystem.setupOrtho(logicalW, logicalH);
        RenderSystem.clear(CLEAR_R, CLEAR_G, CLEAR_B, 1f);
        this.canvas.viewport(logicalW, logicalH);
        RenderSystem.drawCache(false);
        this.canvas.paintLive(false);
        RenderSystem.drawCache(true);
        this.canvas.paintLive(true);
    }

    /** Whether the last tree pass met a live painter, such as a CRT band, that moves every frame. */
    public boolean live() {
        return this.canvas.hasLive();
    }

    /** Mounts a screen element into the center slot, replacing whatever was there. */
    public void mount(final Element<?> screenContent) {
        this.releaseCapture();
        this.centerSlot.clear();
        if (screenContent != null) this.centerSlot.add(screenContent);
        this.ctx.requestRender();
    }

    /** Mounts a stacked pair into the center slot: {@code under} below, {@code top} above. */
    public void mountUnder(final Element<?> under, final Element<?> top) {
        this.releaseCapture();
        this.centerSlot.clear();
        if (under != null) this.centerSlot.add(under);
        if (top != null) this.centerSlot.add(top);
        this.ctx.requestRender();
    }

    /** The topmost layer hosting global dialogs. */
    public ParentFrame overlay() {
        return this.dialogs;
    }

    /** The window titlebar. */
    public TitleBar titleBar() {
        return this.titleBar;
    }

    /** Whether a text input anywhere in the tree has keyboard focus. */
    public boolean textInputFocused() {
        return this.tree.textInputActive();
    }

    /** Whether a mounted screen repaints its whole tree every frame (video, animated thumbnails, a caret). */
    public boolean continuous() {
        for (final Element<?> child: this.centerSlot.children()) {
            if (!child.visible()) continue;
            if (child instanceof final Screen screen && screen.continuous()) return true;
        }
        return false;
    }

    /** Whether a pointer capture (slider, window move, modal hold) is active; the main loop then paces frames so drags track the cursor. */
    public boolean dragging() {
        return this.captured != null;
    }

    // ==========================================================================
    // PUBLIC API: INPUT ENTRY POINTS
    // ==========================================================================

    // A PRESS CAPTURES THE POINTER AND A REAL DRAG SUPPRESSES THE NEXT CLICK

    public boolean press(final double mx, final double my) {
        this.captured = this.tree.dispatchPress(mx, my);
        this.dragMoved = false;
        this.pressX = mx;
        this.pressY = my;
        return this.captured != null;
    }

    public void drag(final double mx, final double my) {
        if (this.captured != null) {
            // A CAPTURE COUNTS AS A DRAG ONLY AFTER A FEW PIXELS OF TRAVEL, SO JITTER BETWEEN PRESS AND
            // RELEASE DOES NOT SUPPRESS THE CLICK (MODAL SCRIMS CAPTURE EVERY PRESS)
            if (Math.abs(mx - this.pressX) + Math.abs(my - this.pressY) > 3) this.dragMoved = true;
            this.captured.onDrag(mx, my);
        }
    }

    public void release(final double mx, final double my) {
        this.wasDragging = this.captured != null && this.dragMoved;
        if (this.captured != null) {
            this.captured.onRelease(mx, my);
            this.captured = null;
        }
        this.dragMoved = false;
    }

    public boolean click(final double mx, final double my) {
        // A CLICK THAT ENDS A REAL DRAG (WINDOW MOVE, SLIDER) IS DROPPED
        final boolean handled = !this.wasDragging && this.tree.dispatchClick(mx, my);
        this.wasDragging = false;
        return handled;
    }

    public void hover(final double mx, final double my) {
        this.tree.dispatchHover(mx, my);
    }

    public boolean scroll(final double mx, final double my, final double amount) {
        return this.tree.dispatchScroll(mx, my, amount);
    }

    public boolean key(final int key, final int action) {
        return this.tree.dispatchKey(key, action);
    }

    public boolean character(final int codepoint) {
        return this.tree.dispatchChar(codepoint);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void releaseCapture() {
        // A SLOT SWAP MUST NOT LEAVE A DETACHED ELEMENT HOLDING THE POINTER
        if (this.captured != null) {
            this.captured.onRelease(this.ctx.mouseX, this.ctx.mouseY);
            this.captured = null;
        }
        this.wasDragging = false;
    }
}
