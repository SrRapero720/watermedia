package org.watermedia.bootstrap.app;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.RECT;
import org.lwjgl.system.windows.WindowProc;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.screen.TitleBar;
import org.watermedia.tools.IOTool;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.GLFW_ICONIFIED;
import static org.lwjgl.glfw.GLFW.GLFW_MAXIMIZED;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorPos;
import static org.lwjgl.glfw.GLFW.glfwGetMonitorWorkarea;
import static org.lwjgl.glfw.GLFW.glfwGetMonitors;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutInt;
import static org.lwjgl.system.windows.User32.*;

/**
 * Native Windows frame for the borderless GLFW window: subclasses its window procedure to restore Aero Snap, edge
 * resizing, the caption menu and the DWM animations while the app draws its own titlebar; a no-op elsewhere.
 */
public final class WinFrame {
    /** Whether the native Windows frame integration applies on this platform. */
    public static final boolean ACTIVE = "windows".equals(IOTool.os());
    private static final int WM_DPICHANGED = 0x02E0; // NOT EXPOSED BY LWJGL'S User32 BINDING

    private static AppContext ctx;
    private static long hwnd;
    private static long prevProc; // ORIGINAL (GLFW) WNDPROC; EVERYTHING UNHANDLED CHAINS HERE
    private static WindowProc proc;

    private WinFrame() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Subclasses the Win32 window of a GLFW window, replacing any previous install; call it after every window rebuild. */
    public static void install(final long window, final AppContext context) {
        if (!ACTIVE || window == 0L) return;
        uninstall();
        ctx = context;
        hwnd = glfwGetWin32Window(window);
        // SUBCLASS FIRST: THE SWP_FRAMECHANGED BELOW SENDS WM_NCCALCSIZE, AND OUR HANDLER MUST ANSWER
        // IT; THE DEFAULT ONE WOULD INSET THE CLIENT AREA BY THE NEW CAPTION/FRAME UNTIL A LATER RESIZE
        prevProc = GetWindowLongPtr(null, hwnd, GWL_WNDPROC);
        proc = WindowProc.create(WinFrame::wndProc);
        SetWindowLongPtr(null, hwnd, GWL_WNDPROC, proc.address());
        // FRAME STYLES: WS_THICKFRAME ENABLES EDGE RESIZE + SNAP, WS_CAPTION/BOXES ENABLE SNAP LAYOUTS,
        // WIN+ARROWS AND THE DWM MIN/MAX ANIMATIONS; THE VISUAL FRAME IS REMOVED IN WM_NCCALCSIZE
        final long style = GetWindowLongPtr(null, hwnd, GWL_STYLE);
        SetWindowLongPtr(null, hwnd, GWL_STYLE,
                style | WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU);
        // APPLY THE NEW FRAME IN PLACE: NO MOVE, NO RESIZE, NO Z-ORDER CHANGE
        SetWindowPos(null, hwnd, 0L, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER);
    }

    /** Restores the original window procedure; call it before destroying the subclassed window. */
    public static void uninstall() {
        if (proc == null) return;
        if (hwnd != 0L) SetWindowLongPtr(null, hwnd, GWL_WNDPROC, prevProc);
        proc.free();
        proc = null;
        hwnd = 0L;
        prevProc = 0L;
        ctx = null;
    }

    /** Clamps a floating window that outgrew its monitor's work area, as after an RDP session; call it on the main thread. */
    public static void reclamp(final AppContext ctx) {
        if (!ACTIVE || ctx == null || ctx.windowHandle == 0L) return;
        final long window = ctx.windowHandle;
        if (glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE
                || glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE) return;
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pa = stack.mallocInt(1);
            final IntBuffer pb = stack.mallocInt(1);
            final IntBuffer pc = stack.mallocInt(1);
            final IntBuffer pd = stack.mallocInt(1);
            glfwGetWindowPos(window, pa, pb);
            glfwGetWindowSize(window, pc, pd);
            final int x = pa.get(0), y = pb.get(0), w = pc.get(0), h = pd.get(0);
            if (w <= 0 || h <= 0) return;

            // MONITOR HOSTING THE WINDOW CENTER (FULL BOUNDS, AS THE WORK AREA CAN START BELOW A TOP
            // TASKBAR), FALLING BACK TO THE PRIMARY WHEN THE CENTER LANDS ON NO MONITOR
            final long monitor = monitorAt(x + w / 2.0, y + h / 2.0, pa, pb);
            if (monitor == 0L) return;

            glfwGetMonitorWorkarea(monitor, pa, pb, pc, pd);
            final int wx = pa.get(0), wy = pb.get(0), ww = pc.get(0), wh = pd.get(0);
            if (ww <= 0 || wh <= 0) return;
            final int nw = Math.min(w, ww);
            final int nh = Math.min(h, wh);
            final int nx = MathUtil.clamp(x, wx, wx + ww - nw);
            final int ny = MathUtil.clamp(y, wy, wy + wh - nh);
            if (nw == w && nh == h && nx == x && ny == y) return;
            // THE GLFW SIZE CALLBACK RE-SYNCS ctx.windowWidth/Height AND THE ENGINE VIEWPORT
            glfwSetWindowSize(window, nw, nh);
            glfwSetWindowPos(window, nx, ny);
            ctx.requestRender();
        }
    }

    /** Returns the monitor whose full bounds contain the point, else the primary; the caller lends two scratch buffers. */
    public static long monitorAt(final double cx, final double cy, final IntBuffer pa, final IntBuffer pb) {
        long monitor = 0L;
        final PointerBuffer monitors = glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit() && monitor == 0L; i++) {
                final long m = monitors.get(i);
                glfwGetMonitorPos(m, pa, pb);
                final GLFWVidMode mode = glfwGetVideoMode(m);
                if (mode != null && cx >= pa.get(0) && cx < pa.get(0) + mode.width()
                        && cy >= pb.get(0) && cy < pb.get(0) + mode.height())
                    monitor = m;
            }
        }
        return monitor != 0L ? monitor : glfwGetPrimaryMonitor();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // CLAMPS THE PROPOSED WM_NCCALCSIZE RECT (FIRST RECT AT lParam) TO ITS MONITOR'S WORK AREA, REMOVING THE
    // MAXIMIZED FRAME OVERHANG WITHOUT SHRINKING A RECT THAT FITS
    private static void clampZoomed(final long rectPtr) {
        final int l = memGetInt(rectPtr);
        final int t = memGetInt(rectPtr + 4);
        final int r = memGetInt(rectPtr + 8);
        final int b = memGetInt(rectPtr + 12);
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer pa = stack.mallocInt(1);
            final IntBuffer pb = stack.mallocInt(1);
            final IntBuffer pc = stack.mallocInt(1);
            final IntBuffer pd = stack.mallocInt(1);
            final long monitor = monitorAt((l + r) / 2.0, (t + b) / 2.0, pa, pb);
            if (monitor == 0L) return;
            glfwGetMonitorWorkarea(monitor, pa, pb, pc, pd);
            if (pc.get(0) <= 0 || pd.get(0) <= 0) return;
            final int wl = pa.get(0);
            final int wt = pb.get(0);
            final int wr = wl + pc.get(0);
            final int wb = wt + pd.get(0);
            final int nl = Math.max(l, wl);
            final int nt = Math.max(t, wt);
            final int nr = Math.min(r, wr);
            final int nb = Math.min(b, wb);
            if (nl >= nr || nt >= nb) return;
            memPutInt(rectPtr, nl);
            memPutInt(rectPtr + 4, nt);
            memPutInt(rectPtr + 8, nr);
            memPutInt(rectPtr + 12, nb);
        }
    }

    // SUBCLASSED WNDPROC: RUNS ON THE GLFW MAIN THREAD (INSIDE THE MESSAGE PUMP); EVERYTHING NOT
    // HANDLED HERE CHAINS TO THE ORIGINAL GLFW PROC SO ALL GLFW CALLBACKS KEEP FIRING
    private static long wndProc(final long hwnd, final int msg, final long wParam, final long lParam) {
        switch (msg) {
            case WM_NCCALCSIZE -> {
                // THE CLIENT AREA FILLS THE WINDOW, SO THE OS DRAWS NO FRAME; MAXIMIZED, WINDOWS OVERHANGS THE
                // MONITOR BY THE FRAME THICKNESS, SO THE RECT IS INTERSECTED WITH THE WORK AREA
                if (IsZoomed(hwnd)) clampZoomed(lParam);
                return 0L;
            }
            case WM_NCHITTEST -> {
                return hitTest(hwnd, lParam);
            }
            case WM_DISPLAYCHANGE, WM_DPICHANGED -> {
                // MONITOR LAYOUT OR DPI CHANGED (RDP RECONNECT, DISPLAY SWITCH); DEFERRED TO THE MAIN-LOOP
                // EXECUTOR BECAUSE GLFW CALLS CANNOT RE-ENTER MID-DISPATCH
                final AppContext c = ctx;
                if (c != null) c.execute(() -> reclamp(c));
            }
        }
        return nCallWindowProc(prevProc, hwnd, msg, wParam, lParam);
    }

    // lParam HOLDS SIGNED 16-BIT SCREEN COORDS; CLIENT == WINDOW RECT, SO SUBTRACTING GetWindowRect GIVES CLIENT
    // COORDS, ALL IN PHYSICAL PX
    private static long hitTest(final long hwnd, final long lParam) {
        try (final MemoryStack stack = stackPush()) {
            final RECT r = RECT.malloc(stack);
            if (!GetWindowRect(null, hwnd, r)) return HTCLIENT;
            final int gx = (short) (lParam & 0xFFFFL);
            final int gy = (short) ((lParam >>> 16) & 0xFFFFL);
            final int cx = gx - r.left();
            final int cy = gy - r.top();
            final int cw = r.right() - r.left();
            final int ch = r.bottom() - r.top();

            // RESIZE BORDERS, FLOATING ONLY; CORNERS GET A DOUBLE-SIZED BAND FOR A COMFORTABLE GRIP;
            // THE TOP BAND WINS OVER THE TITLEBAR: RESIZING FROM THE TOP EDGE IS THE STANDARD BEHAVIOR
            if (!IsZoomed(hwnd)) {
                final int fx = GetSystemMetrics(SM_CXFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
                final int fy = GetSystemMetrics(SM_CYFRAME) + GetSystemMetrics(SM_CXPADDEDBORDER);
                if (cy < fy) return cx < fx * 2 ? HTTOPLEFT : cx >= cw - fx * 2 ? HTTOPRIGHT : HTTOP;
                if (cy >= ch - fy) return cx < fx * 2 ? HTBOTTOMLEFT : cx >= cw - fx * 2 ? HTBOTTOMRIGHT : HTBOTTOM;
                if (cx < fx) return cy < fy * 2 ? HTTOPLEFT : cy >= ch - fy * 2 ? HTBOTTOMLEFT : HTLEFT;
                if (cx >= cw - fx) return cy < fy * 2 ? HTTOPRIGHT : cy >= ch - fy * 2 ? HTBOTTOMRIGHT : HTRIGHT;
            }

            // THE TITLEBAR BAND MINUS ITS THREE BUTTONS IS THE NATIVE CAPTION (DRAG, SNAP, SYSTEM MENU); THE
            // BUTTONS STAY CLIENT SO THEIR IN-APP HANDLERS KEEP WORKING
            final AppContext c = ctx;
            final float scale = c == null ? 1f : c.uiScale;
            if (cx >= 0 && cy >= 0 && cy < Math.round(TitleBar.H * scale)
                    && cx < cw - Math.round(TitleBar.BUTTON_W * 3 * scale))
                return HTCAPTION;
            return HTCLIENT;
        }
    }
}
