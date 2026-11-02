package org.watermedia.bootstrap.app;

import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.glfw.GLFW.glfwPostEmptyEvent;

/**
 * Window, input and dialog state shared by the screens, the render-thread task queue and the media teardown chain;
 * the catalog, the media session and the click sound hang from it.
 */
public final class AppContext implements Executor {
    public static final String APP_NAME = "WATERMeDIA: Multimedia API";
    public static final long CRT_STEP_MS = 24L; // THE CRT BAND MOVES 1 px PER STEP

    // WINDOW STATE
    public int windowWidth = 1_280;
    public int windowHeight = 720;
    public long windowHandle;
    public boolean windowMaximized;
    private final AtomicBoolean renderRequested = new AtomicBoolean(true);
    // GLOBAL UI SCALE (PHYSICAL PX PER LOGICAL PX)
    public volatile float uiScale = 1f;

    // CRT OVERLAY (SCANLINES AND A MOVING NEON BAND), TOGGLED BY THE C SHORTCUT AND THE SETTINGS ROW
    public volatile boolean crt = true;

    // MOUSE, IN LOGICAL PX
    public double mouseX;
    public double mouseY;
    // PHYSICAL CURSOR COORDS FOR THE TITLEBAR DRAG
    public volatile double mouseRawX;
    public volatile double mouseRawY;
    public boolean mouseDown;
    public boolean mousePressed;
    public boolean mouseClicked;
    public boolean ctrlDown;
    // WINDOWS REPORTS ALTGR AS CTRL+ALT, SO CTRL ONLY MAKES A SHORTCUT WITHOUT ALT
    public boolean altDown;

    public TextRenderer text;

    // RENDER-THREAD TASKS DRAINED BY processExecutor(); PRIVATE SO EVERY ENQUEUE GOES THROUGH execute(), WHICH
    // WAKES THE LOOP
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile Thread releaseThread;

    public final MediaCatalog catalog = new MediaCatalog();
    public final MediaSession media = new MediaSession(this);
    public final ClickSound sound = new ClickSound();

    // BANNER, ICON AND DUCK GPU TEXTURE HANDLES
    public final Assets assets = new Assets();
    public boolean backendsLoading = true;

    // UPLOAD LOGS DIALOG
    public final LogUploader upload = new LogUploader(this::requestRender);

    // CLEANUP CACHE DIALOG
    public final CacheCleaner cleanup = new CacheCleaner(this::requestRender);

    // GLOBAL ERROR DIALOG
    final ErrorState error = new ErrorState();

    // GLOBAL EXIT-CONFIRM DIALOG, RAISED BY THE TITLEBAR CLOSE AND THE EXIT ACTION
    public volatile boolean exitConfirm;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the window width in logical pixels, the coordinate space of the element tree. */
    public int logicalWidth() {
        return Math.round(this.windowWidth / this.uiScale);
    }

    /** Returns the window height in logical pixels, the coordinate space of the element tree. */
    public int logicalHeight() {
        return Math.round(this.windowHeight / this.uiScale);
    }

    @Override
    public void execute(final Runnable task) {
        if (task != null) {
            this.tasks.add(task);
            this.requestRender();
        }
    }

    public void requestRender() {
        // ONLY THE REQUEST THAT RAISES THE FLAG WAKES THE LOOP; A RAISED FLAG IS STILL UNCONSUMED, SO ITS FRAME
        // RUNS AFTER THIS CALLER'S CHANGES
        if (this.renderRequested.compareAndSet(false, true) && this.windowHandle != 0L) glfwPostEmptyEvent();
    }

    public boolean renderRequested() {
        return this.renderRequested.get();
    }

    public boolean consumeRenderRequest() {
        return this.renderRequested.getAndSet(false);
    }

    public boolean processExecutor() {
        boolean processed = false;
        while (!this.tasks.isEmpty()) {
            final Runnable task = this.tasks.poll();
            if (task != null) {
                task.run();
                processed = true;
            }
        }
        if (processed) this.requestRender();
        return processed;
    }

    /** Runs a blocking media teardown off the render thread, after any previous one; call it on the render thread. */
    public void releaseAsync(final String name, final Runnable release) {
        final Thread prev = this.releaseThread;
        // CHAINED BECAUSE awaitPlayerRelease() JOINS ONLY THE LATEST THREAD, WHICH THEN OUTLASTS EVERY EARLIER RELEASE
        this.releaseThread = ThreadTool.thread(name, () -> {
            if (prev != null) ThreadTool.join(prev);
            release.run();
        });
    }

    /** Blocks until every {@link #releaseAsync} finishes; call it on the render thread before destroying the render device. */
    public void awaitPlayerRelease() {
        final Thread t = this.releaseThread;
        if (t == null) return;
        // PUMPS THE EXECUTOR: A BACKGROUND OPENGL RELEASE QUEUES ITS TEXTURE DELETES ON THIS THREAD, WHICH HAS
        // LEFT THE LOOP
        while (t.isAlive()) {
            this.processExecutor();
            ThreadTool.sleep(2);
        }
        this.processExecutor();
    }

    /** Shows the global error dialog. */
    public void showError(final String title, final String message, final Runnable onClose) {
        this.error.show(title, message, onClose);
    }

    /** Returns whether the error dialog is active. */
    public boolean hasError() {
        return this.error.has();
    }

    /** Clears the error dialog and runs its close callback. */
    public void clearError() {
        this.error.clear();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // STATE OF THE GLOBAL ERROR DIALOG
    static final class ErrorState {
        // show() MAY RUN ON A WORKER WHILE syncShell() POLLS has() EVERY FRAME ON THE RENDER THREAD
        public volatile String title;
        public volatile String message;
        public volatile Runnable onClose;

        public void show(final String title, final String message, final Runnable onClose) {
            this.title = title;
            this.onClose = onClose;
            this.message = message; // WRITTEN LAST: has() GATES ON message, SO title/onClose PUBLISH FIRST
        }

        public boolean has() {
            return this.message != null;
        }

        public void clear() {
            final Runnable callback = this.onClose;
            this.title = null;
            this.message = null;
            this.onClose = null;
            if (callback != null) callback.run();
        }
    }
}
