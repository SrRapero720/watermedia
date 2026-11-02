package org.watermedia.bootstrap.app.screen;

import org.lwjgl.system.MemoryStack;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.WinFrame;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.IconButton;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwGetMonitorWorkarea;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwIconifyWindow;
import static org.lwjgl.glfw.GLFW.glfwMaximizeWindow;
import static org.lwjgl.glfw.GLFW.glfwRestoreWindow;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * The window titlebar: app icon, title and the minimize / maximize / close buttons. On Windows the empty band is
 * native caption space ({@link WinFrame}); elsewhere it moves the window through GLFW with top-edge snap-assist.
 */
public final class TitleBar extends Element<TitleBar> {
    /** Fixed titlebar height in logical pixels. */
    public static final int H = 28;
    /** Width of one window button in logical pixels; the button zone spans three of these. */
    public static final int BUTTON_W = 38;
    private static final int SNAP_PX = 3;   // TOP-EDGE SNAP THRESHOLD (PHYSICAL PX)
    private static final int DRAG_SLOP = 4; // GLOBAL CURSOR TRAVEL BEFORE THE GESTURE COUNTS AS A REAL DRAG

    private String title = AppContext.APP_NAME;
    private Runnable minimize;
    private Runnable maximize;
    private Runnable close;
    private int dragX;
    private int dragY;
    // SNAP-ASSIST STATE: GLOBAL (PHYSICAL SCREEN) GRAB POINT AND WHETHER THIS GESTURE REALLY MOVED
    private double pressGX;
    private double pressGY;
    private boolean dragMoved;

    public TitleBar() {
        this.width = MAX_PARENT;
        this.height = H;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets the window title text. */
    public TitleBar title(final String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    /** Sets the minimize action; unset falls back to {@code glfwIconifyWindow}. */
    public TitleBar minimize(final Runnable action) {
        this.minimize = action;
        return this;
    }

    /** Sets the maximize/restore action; unset falls back to the GLFW maximize toggle. */
    public TitleBar maximize(final Runnable action) {
        this.maximize = action;
        return this;
    }

    /** Sets the close action; unset does nothing (the app wires its shutdown here). */
    public TitleBar close(final Runnable action) {
        this.close = action;
        return this;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        final int base = this.left + this.measuredWidth - BUTTON_W * 3;
        if (mx >= base) {
            switch ((int) ((mx - base) / BUTTON_W)) {
                case 0 -> {
                    if (this.minimize != null) this.minimize.run();
                    else if (this.ctx != null && this.ctx.windowHandle != 0L) glfwIconifyWindow(this.ctx.windowHandle);
                }
                case 1 -> {
                    // DEFAULT TOGGLE: THE GLFW MAXIMIZE CALLBACK KEEPS ctx.windowMaximized (AND THE APP) IN SYNC
                    if (this.maximize != null) this.maximize.run();
                    else if (this.ctx != null && this.ctx.windowHandle != 0L) {
                        if (this.ctx.windowMaximized) glfwRestoreWindow(this.ctx.windowHandle);
                        else glfwMaximizeWindow(this.ctx.windowHandle);
                    }
                }
                default -> {
                    if (this.close != null) this.close.run();
                }
            }
        }
        return true; // THE BAR ALWAYS SWALLOWS ITS CLICKS SO THEY NEVER FALL THROUGH TO THE CONTENT
    }

    @Override
    public void onDrag(final double mx, final double my) {
        // WINDOW POSITIONS ARE PHYSICAL, SO THE MOVE USES THE RAW CURSOR DELTA FROM THE GRAB POINT
        // INSTEAD OF THE LOGICAL mx/my
        if (this.ctx == null || this.ctx.windowHandle == 0L) return;
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer wx = stack.mallocInt(1);
            final IntBuffer wy = stack.mallocInt(1);
            glfwGetWindowPos(this.ctx.windowHandle, wx, wy);
            // SNAP-ASSIST ARMS ONLY AFTER THE GLOBAL CURSOR TRAVELS PAST THE SLOP, SO A RESTORE-FROM-MAXIMIZED
            // PRESS THAT NEVER MOVED STAYS UNARMED
            if (Math.abs(wx.get(0) + this.ctx.mouseRawX - this.pressGX)
                    + Math.abs(wy.get(0) + this.ctx.mouseRawY - this.pressGY) > DRAG_SLOP)
                this.dragMoved = true;
            glfwSetWindowPos(this.ctx.windowHandle,
                    wx.get(0) + (int) this.ctx.mouseRawX - this.dragX,
                    wy.get(0) + (int) this.ctx.mouseRawY - this.dragY);
        }
    }

    @Override
    public void onRelease(final double mx, final double my) {
        // SNAP-ASSIST: RELEASING A REAL DRAG ON THE TOP EDGE OF THE WORK AREA MAXIMIZES THE WINDOW; dragMoved
        // KEEPS A RESTORE PRESS, WHICH STARTS AT THE TOP, FROM RE-MAXIMIZING WHEN IT NEVER MOVED
        if (!this.dragMoved) return;
        this.dragMoved = false;
        if (this.ctx == null || this.ctx.windowHandle == 0L || this.ctx.windowMaximized) return;
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer px = stack.mallocInt(1);
            final IntBuffer py = stack.mallocInt(1);
            glfwGetWindowPos(this.ctx.windowHandle, px, py);
            final double gx = px.get(0) + this.ctx.mouseRawX;
            final double gy = py.get(0) + this.ctx.mouseRawY;
            // MONITOR UNDER THE GRAB POINT (FULL BOUNDS, AS THE WORK AREA CAN START BELOW A TOP TASKBAR),
            // FALLING BACK TO THE PRIMARY WHEN THE POINT LANDS ON NO MONITOR
            final long monitor = WinFrame.monitorAt(gx, gy, px, py);
            if (monitor == 0L) return;
            glfwGetMonitorWorkarea(monitor, px, py, stack.mallocInt(1), stack.mallocInt(1));
            // THE GLFW MAXIMIZE CALLBACK SYNCS ctx.windowMaximized AND THE VIEWPORT/APP STATE
            if (gy <= py.get(0) + SNAP_PX) glfwMaximizeWindow(this.ctx.windowHandle);
        }
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || this.ctx.text == null) return;
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        canvas.gradientV(x, y, w, H,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 1f);
        canvas.lineH(x, y + H - 1, w, AppTheme.STROKE_BRIGHT, 1f);
        // THE ICON TEXTURE ID IS READ EVERY DRAW BECAUSE IT CHANGES ACROSS RELOADS
        if (this.ctx.assets.iconId > 0) canvas.image(this.ctx.assets.iconId, x + 8, y + 6, 16, 16, null);
        canvas.text(this.title, x + 32,
                y + Math.max(0, (H - this.ctx.text.glyphHeight(AppTheme.TEXT_SUBTITLE)) / 2),
                AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE, false);
        for (int i = 0; i < 3; i++) {
            final int bx = x + w - BUTTON_W * (3 - i);
            final boolean hover = this.ctx.mouseY >= y && this.ctx.mouseY <= y + H
                    && this.ctx.mouseX >= bx && this.ctx.mouseX <= bx + BUTTON_W;
            final Color color = i == 2
                    ? (hover ? AppTheme.RED : AppTheme.alpha(AppTheme.RED, 190))
                    : (hover ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT);
            final String icon = i == 0 ? "minimize" : i == 1 ? (this.ctx.windowMaximized ? "restore" : "maximize") : "x";
            // BARE GLYPH WITHOUT A BOX: ONLY ITS COLOR REACTS TO HOVER
            IconButton.render(canvas, bx, y, BUTTON_W, H, icon, 12, color, color, false, hover, true, false);
        }
    }

    @Override
    protected boolean onPress(final double mx, final double my) {
        // ON WINDOWS THE EMPTY BAND IS NATIVE HTCAPTION (WinFrame) AND THE OS OWNS DRAG, SNAP AND RESTORE;
        // A PRESS THAT SLIPS THROUGH BY EDGE ROUNDING MUST NOT START A MANUAL DRAG
        if (WinFrame.ACTIVE) return false;
        // ONLY THE EMPTY BAND STARTS A WINDOW DRAG; THE BUTTON ZONE KEEPS PLAIN CLICKS
        if (mx >= this.left + this.measuredWidth - BUTTON_W * 3) return false;
        // REMEMBER THE GLOBAL GRAB POINT (PHYSICAL SCREEN COORDS) BEFORE ANY RESTORE MOVES THE WINDOW,
        // SO onDrag CAN TELL A REAL WINDOW DRAG FROM PRESS JITTER (SNAP-ASSIST GUARD)
        this.dragMoved = false;
        if (this.ctx != null && this.ctx.windowHandle != 0L) {
            try (final MemoryStack stack = stackPush()) {
                final IntBuffer wx = stack.mallocInt(1);
                final IntBuffer wy = stack.mallocInt(1);
                glfwGetWindowPos(this.ctx.windowHandle, wx, wy);
                this.pressGX = wx.get(0) + this.ctx.mouseRawX;
                this.pressGY = wy.get(0) + this.ctx.mouseRawY;
            }
        }
        if (!this.restoreForDrag()) {
            // THE GRAB POINT IS PHYSICAL: WINDOW POSITION AND glfwSetWindowPos ARE IN PHYSICAL SCREEN
            // COORDS, WHILE mx/my ARRIVE IN LOGICAL PX (DIVIDED BY THE UI SCALE)
            this.dragX = (int) this.ctx.mouseRawX;
            this.dragY = (int) this.ctx.mouseRawY;
        }
        return true;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // DRAGGING A MAXIMIZED WINDOW FIRST RESTORES IT AND RE-ANCHORS THE GRAB POINT SO THE CURSOR KEEPS
    // ITS RELATIVE POSITION OVER THE SMALLER TITLEBAR
    private boolean restoreForDrag() {
        if (this.ctx == null || !this.ctx.windowMaximized || this.ctx.windowHandle == 0L) return false;
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer winX = stack.mallocInt(1);
            final IntBuffer winY = stack.mallocInt(1);
            glfwGetWindowPos(this.ctx.windowHandle, winX, winY);

            // ALL PHYSICAL SCREEN COORDS: WINDOW POS/SIZE AND THE RAW CURSOR SHARE ONE SPACE
            final double globalCursorX = winX.get(0) + this.ctx.mouseRawX;
            final double globalCursorY = winY.get(0) + this.ctx.mouseRawY;
            final double xRatio = this.ctx.windowWidth <= 0 ? 0.5d
                    : MathUtil.clamp(this.ctx.mouseRawX / this.ctx.windowWidth, 0d, 1d);

            glfwRestoreWindow(this.ctx.windowHandle);
            this.ctx.windowMaximized = false;

            final IntBuffer restoredW = stack.mallocInt(1);
            final IntBuffer restoredH = stack.mallocInt(1);
            glfwGetWindowSize(this.ctx.windowHandle, restoredW, restoredH);
            final int newW = restoredW.get(0);
            final int newH = restoredH.get(0);
            this.ctx.windowWidth = newW;
            this.ctx.windowHeight = newH;
            RenderSystem.viewport(newW, newH);

            this.dragX = Math.max(0, Math.min(newW - 1, (int) Math.round(newW * xRatio)));
            this.dragY = (int) Math.max(0, this.ctx.mouseRawY);
            glfwSetWindowPos(this.ctx.windowHandle,
                    (int) Math.round(globalCursorX - this.dragX),
                    (int) Math.round(globalCursorY - this.dragY));
            this.ctx.requestRender();
            return true;
        }
    }
}
