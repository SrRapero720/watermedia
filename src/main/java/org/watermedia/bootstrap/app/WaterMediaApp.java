package org.watermedia.bootstrap.app;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.screen.*;
import org.watermedia.bootstrap.app.screen.Screen.Keybind;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.tools.ThreadTool;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Entry point of the standalone app: window, render loop, engine hot swap, screens and the home actions, which hand
 * the log upload and cache cleanup to their dialogs.
 */
public class WaterMediaApp {
    private static final Marker IT = MarkerManager.getMarker(WaterMediaApp.class.getSimpleName());
    private static final List<Keybind> KEYS_ERROR = List.of(new Keybind("ENTER/ESC", "Close"));
    private static final List<Keybind> KEYS_EXIT = List.of(new Keybind("ENTER", "Exit"), new Keybind("ESC", "Cancel"));
    private static final AppContext ctx = new AppContext();
    private static final ScreenManager screens = new ScreenManager();

    // CLEARED BY THE RENDER THREAD ON EXIT OR A FAILED SWAP, READ BY THE GLOW-PULSE THREAD
    private static volatile boolean running = true;
    // WRITTEN BY THE GLFW ICONIFY CALLBACK, READ BY THE GLOW-PULSE THREAD
    private static volatile boolean iconified;
    private static boolean maximized;
    // PENDING HOT-SWAP TARGET (null = NONE), CONSUMED AT THE TOP OF THE MAIN LOOP, NEVER MID-FRAME OR IN A CALLBACK
    private static volatile RenderSystem.Engine swapTarget;
    // A FIELD BECAUSE THE SWAP REBUILDS IT FOR THE NEW WINDOW
    private static FrameLimiter frameLimiter;
    // CRT BAND STEP THE LAST FRAME SHOWED; A COMPOSITE WAITS UNTIL THE BAND MOVES PAST IT
    private static long bandStep = -1L;
    // LAST CURSOR POSITION A HOVER DISPATCH WAS ISSUED FOR; NaN SO THE FIRST FRAME ALWAYS DISPATCHES
    private static double lastMoveX = Double.NaN;
    private static double lastMoveY = Double.NaN;

    // WINDOW SHELL: THE WHOLE WINDOW RENDERS THROUGH THIS RETAINED TREE
    private static RootScreen root;
    private static Dialog errorDialog;
    private static Parent errorLines;
    private static String errorShownMsg;
    private static Dialog exitDialog;

    static {
        // LWJGL'S DEFAULT 64KB MemoryStack OVERFLOWS WHILE VULKAN ENUMERATES HUNDREDS OF DEVICE EXTENSIONS;
        // IT MUST GROW BEFORE THE FIRST stackPush
        Configuration.STACK_SIZE.set(1_024);
        initLogging();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void start(final Runnable task) {
        // PHASE 1: SHOW THE WINDOW ASAP; THE APP SPEC REGISTERS FIRST SO ITS LOAD ON THE WATERCONFIG IO POOL
        // OVERLAPS THE SLOWER WINDOW AND ENGINE BOOT
        AppConfig.register();
        initWindow();
        ctx.text = new TextRenderer();
        ctx.text.margin(6);
        // THE UI SCALE NEEDS THE WINDOW'S MONITOR AND THE TEXT RENDERER; THE WINDOW IS STILL HIDDEN, SO THE
        // FIRST LOADING FRAME ALREADY USES THE PREFERENCES
        AppConfig.applyBoot(ctx);
        ctx.assets.load(ctx);
        initShell();
        glfwShowWindow(ctx.windowHandle);

        // PHASE 2: LOADING SCREEN, APP AUDIO, THEN WaterMedia.start() IN THE BACKGROUND; THE SCREEN POLLS THE
        // LOAD STATE EACH FRAME FOR ITS BOOT BAR
        final LoadingScreen loadingScreen = new LoadingScreen(ctx.text, ctx);
        loadingScreen.onEnter();
        root.mount(loadingScreen);
        renderLoadingFrame();
        ctx.sound.open();
        runLoadingPhase();

        // PHASE 3: EVERYTHING THAT NEEDS WATERMEDIA LOADED
        ctx.catalog.load();
        initScreens();
        task.run();
        mainLoop();
        cleanup();
    }

    public static void log(final String message) {
        WaterMedia.LOGGER.info(IT, message);
    }

    /** Applies {@link AppConfig#scale}; 0 recomputes the factor from the window's monitor. */
    public static void reapplyUiScale() {
        final float factor = AppConfig.scaleFactor(AppConfig.scale);
        final float s = factor == 0f ? autoUiScale() : factor;
        // THE TEXT ATLAS REGENERATES LAZILY AT THE NEW PHYSICAL SIZE AND THE NEXT FRAME RELAYOUTS THE TREE
        ctx.uiScale = s;
        RenderSystem.uiScale(s);
        if (ctx.text != null) ctx.text.setUiScale(s);
        ctx.requestRender();
    }

    /** Queues a live render engine hot swap for the next main-loop iteration; safe to call from callbacks. */
    public static void requestEngineSwap(final RenderSystem.Engine target) {
        // WITHOUT A VULKAN LOADER THE SWAP WOULD TEAR THE WINDOW DOWN ONLY TO REBUILD IT ON OPENGL
        if (target == RenderSystem.Engine.VULKAN && !RenderSystem.vulkanAvailable()) {
            WaterMedia.LOGGER.warn(IT, "Vulkan is unavailable on this system; staying on OpenGL");
            ctx.showError("Vulkan unavailable", "Vulkan is not available on this system.\nThe app keeps running on OpenGL.", null);
            return;
        }
        swapTarget = target;
        ctx.requestRender();
    }

    // ==========================================================================
    // INITIALIZATION
    // ==========================================================================
    private static void runLoadingPhase() {
        // THE LOADER'S FAILURE; loader.join() PUBLISHES THE WRITE, SO IT NEEDS NO VOLATILE
        final Throwable[] failure = new Throwable[1];
        final Thread loader = ThreadTool.thread("WaterMediaApp-Init", () -> {
            try {
                // PERSISTENT DATA GOES IN THE WORKING DIRECTORY NEXT TO config/ AND logs/; A null TEMP KEEPS THE
                // MEDIA CACHE IN THE SYSTEM TEMP DIR
                WaterMedia.start("WaterMediaApp", null, Path.of("").toAbsolutePath(), true);
            } catch (final Throwable t) {
                failure[0] = t;
            }
        });

        RenderSystem.configureFrameState();

        final FrameLimiter loadingLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        while (loader.isAlive() && running && !glfwWindowShouldClose(ctx.windowHandle)) {
            loadingLimiter.syncBeforeFrame();
            renderLoadingFrame();
        }

        try {
            loader.join();
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        ctx.backendsLoading = false;
        renderLoadingFrame();

        final Throwable t = failure[0];
        if (t != null) {
            if (t instanceof final RuntimeException re) throw re;
            if (t instanceof final Error err) throw err;
            throw new RuntimeException(t);
        }
    }

    private static void renderLoadingFrame() {
        RenderSystem.beginFrame();
        syncShell();
        // PASS LOGICAL DIMS: THE UI TREE LIVES IN LOGICAL PX AND SCALES TO PHYSICAL THROUGH THE ORTHO;
        // THE PHYSICAL VIEWPORT/SWAPCHAIN STAYS FULL-SIZE (OWNED BY THE BACKEND)
        root.render(ctx.logicalWidth(), ctx.logicalHeight(), false);
        RenderSystem.present();
        glfwPollEvents();
        // INPUT RUNS DURING BOOT TOO, SO THE TITLEBAR AND THE EXIT DIALOG WORK WHILE LOADING
        handleFrameInput();
    }

    private static void initWindow() {
        GLFWErrorCallback.create((code, description) ->
                WaterMedia.LOGGER.error(IT, "GLFW error 0x{}: {}", Integer.toHexString(code), GLFWErrorCallback.getDescription(description))).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        // THE LAUNCHER FORWARDS THE SAVED ENGINE; A LAUNCH WITHOUT IT READS THE SPEC, WHOSE LOAD MAY STILL BE RUNNING
        final String forwarded = System.getProperty(AppBootstrap.ENGINE_PROP);
        if (forwarded == null) AppConfig.await();
        RenderSystem.chooseEngine(forwarded == null ? AppConfig.engine
                : forwarded.equalsIgnoreCase("vulkan") ? RenderSystem.Engine.VULKAN : RenderSystem.Engine.OPENGL);
        openWindow();
    }

    // A FAILED VULKAN ATTACH LEAVES THE ENGINE ON OPENGL, WHICH NEEDS A WINDOW WITH A GL CONTEXT, SO THE WINDOW IS
    // REBUILT ONCE FOR IT
    private static void openWindow() {
        createWindow();
        if (RenderSystem.attach(ctx.windowHandle)) return;
        destroyWindow();
        createWindow();
        RenderSystem.attach(ctx.windowHandle);
    }

    private static void destroyWindow() {
        WinFrame.uninstall();
        glfwFreeCallbacks(ctx.windowHandle);
        glfwDestroyWindow(ctx.windowHandle);
        ctx.windowHandle = NULL;
    }

    private static void createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);
        RenderSystem.applyWindowHints();

        ctx.windowHandle = glfwCreateWindow(1_280, 720, AppContext.APP_NAME, NULL, NULL);
        if (ctx.windowHandle == NULL) throw new RuntimeException("Failed to create the GLFW window");
        maximized = true;
        ctx.windowMaximized = true;

        installCallbacks();
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer width = stack.mallocInt(1);
            final IntBuffer height = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, width, height);
            ctx.windowWidth = width.get(0);
            ctx.windowHeight = height.get(0);
        }
        // WINDOWS ONLY: SUBCLASS THE WIN32 WINDOW SO THE BORDERLESS FRAME GETS NATIVE SNAP/RESIZE/CAPTION BEHAVIOR;
        // EVERY WINDOW REBUILD PASSES THROUGH HERE, SO THE HOT-SWAP RE-INSTALLS TOO
        WinFrame.install(ctx.windowHandle, ctx);
    }

    private static void installCallbacks() {
        glfwSetKeyCallback(ctx.windowHandle, WaterMediaApp::handleKeyInput);
        glfwSetCharCallback(ctx.windowHandle, WaterMediaApp::handleCharInput);
        glfwSetCursorPosCallback(ctx.windowHandle, (w, x, y) -> {
            // RAW = PHYSICAL CURSOR (TITLEBAR WINDOW DRAG); mouseX/Y = LOGICAL (THE UI TREE LIVES IN
            // LOGICAL PX, SO EVERY HIT-TEST DIVIDES BY THE UI SCALE EXACTLY ONCE, HERE)
            ctx.mouseRawX = x;
            ctx.mouseRawY = y;
            final float scale = ctx.uiScale;
            ctx.mouseX = x / scale;
            ctx.mouseY = y / scale;
            ctx.requestRender();
        });
        glfwSetMouseButtonCallback(ctx.windowHandle, (w, button, action, mods) -> {
            if (button != GLFW_MOUSE_BUTTON_LEFT) return;
            ctx.requestRender();
            if (action == GLFW_PRESS) {
                ctx.mouseDown = true;
                ctx.mousePressed = true;
            } else if (action == GLFW_RELEASE) {
                ctx.mouseDown = false;
                ctx.mouseClicked = true;
            }
        });
        glfwSetScrollCallback(ctx.windowHandle, (w, xOffset, yOffset) -> {
            // THE TREE RESOLVES THE TARGET TOPMOST-FIRST; A VISIBLE GLOBAL DIALOG SWALLOWS THE SCROLL
            root.scroll(ctx.mouseX, ctx.mouseY, yOffset);
            ctx.requestRender();
        });
        glfwSetWindowSizeCallback(ctx.windowHandle, (w, width, height) -> {
            ctx.windowWidth = width;
            ctx.windowHeight = height;
            RenderSystem.viewport(width, height);
            ctx.requestRender();
        });
        glfwSetWindowIconifyCallback(ctx.windowHandle, (w, minimized) -> {
            iconified = minimized;
            ctx.requestRender();
        });
        glfwSetWindowMaximizeCallback(ctx.windowHandle, (w, isMaximized) -> {
            maximized = isMaximized;
            ctx.windowMaximized = maximized;
            // WINDOWS: A RESTORE RECLAMPS A FLOATING WINDOW LARGER THAN THE WORK AREA (E.G. AFTER AN RDP MONITOR
            // SHRINK); DEFERRED BECAUSE THIS CALLBACK RUNS INSIDE THE WIN32 MESSAGE DISPATCH
            if (!isMaximized && WinFrame.ACTIVE) ctx.execute(() -> WinFrame.reclamp(ctx));
            ctx.requestRender();
        });
    }

    // ==========================================================================
    // GLOBAL UI SCALE
    // ==========================================================================
    // AUTO FACTOR: THE LARGER OF THE MONITOR CONTENT SCALE AND height/1080, SNAPPED LIKE A SAVED FACTOR; THE
    // RESOLUTION TERM CATCHES HIGH-RES PANELS THE OS REPORTS AT 100%
    private static float autoUiScale() {
        final float contentScale;
        try (final MemoryStack stack = stackPush()) {
            final FloatBuffer sx = stack.mallocFloat(1);
            final FloatBuffer sy = stack.mallocFloat(1);
            glfwGetWindowContentScale(ctx.windowHandle, sx, sy);
            contentScale = Math.max(sx.get(0), sy.get(0));
        }
        final GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        final float heuristic = vidmode != null ? vidmode.height() / 1_080f : 1f;
        return AppConfig.scaleFactor(Math.max(contentScale, heuristic));
    }

    // ==========================================================================
    // WINDOW SHELL
    // ==========================================================================
    // ROOT TREE, GLOBAL DIALOGS AND THE NAVIGATION BINDINGS THE SHELL NEEDS
    private static void initShell() {
        root = new RootScreen(ctx, ctx.text, WaterMediaApp::footerKeybinds);
        root.titleBar().close(() -> {
            ctx.exitConfirm = true;
            ctx.requestRender();
        });

        // GLOBAL ERROR DIALOG: CONTENT AND VISIBILITY BOUND TO ctx.error EVERY FRAME IN syncShell
        errorLines = Parent.column().spacing(6);
        errorDialog = new Dialog()
                .accent(AppTheme.RED)
                .title("ERROR")
                .content(errorLines)
                .actions(new Button("OK").subText("ENTER").icon("check").accent(AppTheme.RED)
                        .onClick(b -> ctx.clearError()))
                .onDismiss(ctx::clearError)
                .onPrimary(ctx::clearError)
                .panelWidth(600)
                .visible(false);

        // EXIT-CONFIRM DIALOG, BOUND TO ctx.exitConfirm; CONFIRMING ENDS THE MAIN LOOP
        exitDialog = new Dialog()
                .accent(AppTheme.RED)
                .title("EXIT WATERMEDIA")
                .content(Parent.column().spacing(8)
                        .add(new Text("CONFIRM EXIT").bold(true).scale(AppTheme.TEXT_BUTTON))
                        .add(new Text("Press ENTER to close the app or ESC to return.")
                                .scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT)))
                .actions(
                        new Button("CANCEL").subText("ESC").icon("x").accent(AppTheme.TEXT_SOFT)
                                .onClick(b -> dismissExit()),
                        new Button("EXIT").subText("ENTER").icon("exit").accent(AppTheme.RED)
                                .onClick(b -> running = false))
                .onDismiss(WaterMediaApp::dismissExit)
                .onPrimary(() -> running = false)
                .panelWidth(520)
                .visible(false);
        root.overlay().add(errorDialog).add(exitDialog);

        screens.root(root);
        // THE PLAYER OWNS A CUSTOM WINDOW TITLE; EVERY OTHER SCREEN RESTORES THE DEFAULT
        screens.onNavigate((previous, next) -> root.titleBar().title("player".equals(screens.currentName())
                ? "WATERMeDIA - Now Playing" : AppContext.APP_NAME));
    }

    private static void dismissExit() {
        ctx.exitConfirm = false;
        ctx.requestRender();
    }

    // FOOTER SHORTCUTS: A VISIBLE GLOBAL DIALOG OVERRIDES THE ACTIVE SCREEN'S HINTS
    private static List<Keybind> footerKeybinds() {
        if (ctx.hasError()) return KEYS_ERROR;
        if (ctx.exitConfirm) return KEYS_EXIT;
        return screens.currentKeybinds();
    }

    // PER-FRAME SHELL BINDINGS: GLOBAL DIALOG VISIBILITY AND CONTENT
    private static void syncShell() {
        final boolean hasError = ctx.hasError();
        if (hasError) {
            errorDialog.title(ctx.error.title == null ? "ERROR" : ctx.error.title.toUpperCase());
            final String message = ctx.error.message == null ? "" : ctx.error.message;
            if (!message.equals(errorShownMsg)) {
                errorShownMsg = message;
                errorLines.clear();
                for (final String line: message.split("\n"))
                    errorLines.add(new Text(line).scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT));
            }
        }
        errorDialog.visible(hasError);
        exitDialog.visible(ctx.exitConfirm);
    }

    private static void initScreens() {
        screens.register("home", new HomeScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("mrl", new MRLSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("regions", new RegionSelectorScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("player", new PlayerScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("multimedia", new OpenMultimediaScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.register("settings", new SettingsScreen(ctx.text, ctx, WaterMediaApp::navigateAction));
        screens.navigate("home");
    }

    private static void navigateAction(final HomeScreen.Action action) {
        ctx.requestRender();
        if (action == null) {
            screens.backToHome();
            return;
        }

        switch (action) {
            case EXIT -> ctx.exitConfirm = true;
            case BACK -> screens.backToHome();
            case MRL_SELECTOR -> screens.navigate("mrl");
            case REGION_SELECTOR -> screens.navigate("regions");
            case UPLOAD_LOGS -> ctx.upload.step();
            case CLEANUP -> ctx.cleanup.step();
            case SETTINGS -> {
                if (WaterMedia.LOGGER.isDebugEnabled()) screens.navigate("settings");
            }
            case OPEN_MULTIMEDIA, PLAYER -> {
                if (!CodecsAPI.ffmpegLoaded()) {
                    ctx.showError("Feature Unavailable",
                            "FFmpeg is not loaded.\nMedia playback is not available.\n\nCheck the alerts for more information.",
                            null);
                    return;
                }
                screens.navigate(action == HomeScreen.Action.PLAYER ? "player" : "multimedia");
            }
        }
    }

    // ==========================================================================
    // MAIN LOOP
    // ==========================================================================
    // THE LOADING PHASE ALREADY CONFIGURED THE FRAME STATE
    private static void mainLoop() {
        frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
        ctx.requestRender();
        // THE GLOW HEARTBEAT MOVES ABOUT 1% EVERY 250 ms, SO 4 TREE REPAINTS A SECOND (CACHE REBUILDS UNDER A MOVING CRT
        // BAND) KEEP IT SMOOTH ON ON-DEMAND SCREENS; A MINIMIZED WINDOW SHOWS NOTHING AND IS LEFT IDLE
        ThreadTool.thread("WaterMedia-GlowPulse", () -> {
            while (running) {
                if (!iconified) ctx.requestRender();
                ThreadTool.sleep(250);
            }
        });
        while (running && !glfwWindowShouldClose(ctx.windowHandle)) {
            // ENGINE HOT-SWAP RUNS AT THE TOP OF THE LOOP ON THE RENDER THREAD, NEVER MID-FRAME
            if (swapTarget != null) performEngineSwap();
            final boolean continuous = root.continuous();
            final boolean band = ctx.crt && root.live() && !iconified;
            // ONLY A VISIBLE CRT BAND COMPOSITES, SO WITHOUT ONE THE TWO WINDOW-SIZED LAYERS GO BACK TO THE GPU
            if (!band) RenderSystem.releaseCache();
            // VIDEO AND POINTER DRAGS PACE TO THE REFRESH RATE AND POLL INPUT AFTER THE SLEEP, SO A SLIDER SHOWS THE CURSOR
            // AS OF RENDER TIME; A STILL TREE UNDER A MOVING BAND WAKES FOR INPUT OR THE BAND'S NEXT STEP
            if (continuous || root.dragging()) {
                frameLimiter.syncBeforeFrame();
                glfwPollEvents();
            } else if (ctx.renderRequested()) {
                glfwPollEvents();
            } else if (band) {
                glfwWaitEventsTimeout((AppContext.CRT_STEP_MS - System.currentTimeMillis() % AppContext.CRT_STEP_MS) / 1_000d);
            } else {
                glfwWaitEventsTimeout(frameLimiter.idleTimeoutSeconds());
            }

            handleFrameInput();
            ctx.processExecutor();
            AppConfig.poll(ctx);

            // ON-DEMAND FRAMES REPAINT THE TREE ONLY WHEN SOMETHING ASKED FOR ONE; A STILL DRAG-HOLD FALLS THROUGH
            // HERE TOO AND SKIPS THE REDUNDANT REPAINT UNTIL THE POINTER MOVES AGAIN
            final long step = System.currentTimeMillis() / AppContext.CRT_STEP_MS;
            final FramePlan plan = FramePlan.of(continuous, ctx.consumeRenderRequest(), ctx.crt, root.live(), !iconified,
                    RenderSystem.cacheValid(), step != bandStep);
            if (plan == FramePlan.IDLE) continue;
            bandStep = step;

            RenderSystem.beginFrame();
            // A COMPOSITE COSTS A CLEAR, THE BASE LAYER QUAD, TWO GRADIENTS PER VISIBLE CRT BAND AND THE TOP LAYER QUAD
            // WHEN A DIALOG, HUD OR PREVIEW RAISED; LOGICAL DIMS, AS THE TREE SCALES TO PHYSICAL THROUGH THE ORTHO
            final FramePlan frame = plan.settle(RenderSystem.cacheValid());
            if (frame == FramePlan.COMPOSITE) root.composite(ctx.logicalWidth(), ctx.logicalHeight());
            else {
                syncShell();
                root.render(ctx.logicalWidth(), ctx.logicalHeight(), frame != FramePlan.DIRECT);
            }
            RenderSystem.present();
        }

        ctx.media.releasePlayer();
    }

    // REBUILDS THE WINDOW AND ENGINE IN PLACE, KEEPING THE JVM, THE PLAYER API, THE ctx EXECUTOR AND THE GLOW
    // PULSE; RUNS ONLY FROM THE TOP OF mainLoop()
    private static void performEngineSwap() {
        final RenderSystem.Engine target = swapTarget;
        swapTarget = null;
        if (target == null || target == RenderSystem.engineKind()) return;

        // CAPTURE THE CURRENT WINDOW PLACEMENT SO THE REBUILT WINDOW LANDS EXACTLY WHERE IT WAS
        final boolean wasMaximized = maximized;
        int px = 0, py = 0, pw = ctx.windowWidth, ph = ctx.windowHeight;
        if (!wasMaximized) {
            try (final MemoryStack stack = stackPush()) {
                final IntBuffer bx = stack.mallocInt(1);
                final IntBuffer by = stack.mallocInt(1);
                final IntBuffer bw = stack.mallocInt(1);
                final IntBuffer bh = stack.mallocInt(1);
                glfwGetWindowPos(ctx.windowHandle, bx, by);
                glfwGetWindowSize(ctx.windowHandle, bw, bh);
                px = bx.get(0);
                py = by.get(0);
                pw = bw.get(0);
                ph = bh.get(0);
            }
        }

        WaterMedia.LOGGER.info(IT, "Hot-swapping render engine {} -> {}", RenderSystem.engineName(), target);
        try {
            // 1) DRAIN ALL MEDIA IN THE SAME ORDER AS cleanup(): RELEASE PLAYERS/THUMBNAILS, THEN JOIN THE
            //    BACKGROUND RELEASE QUEUE SO NOTHING TOUCHES THE DEVICE AFTER IT IS DESTROYED
            screens.releaseMediaAll();
            ctx.media.releasePlayer();
            ctx.awaitPlayerRelease();

            // 2) DROP GPU CACHES BOUND TO THE OUTGOING CONTEXT; THEY REBUILD LAZILY (TEXT) OR ARE RECREATED BELOW
            ctx.text.reset();
            ctx.assets.dispose();

            // 3) TEAR DOWN THE OUTGOING ENGINE AND ITS WINDOW (MIRRORS cleanup() MINUS glfwTerminate)
            RenderSystem.cleanup();
            destroyWindow();

            // 4) SELECT THE TARGET ENGINE AND REBUILD THE WINDOW WITH ITS HINTS (MIRRORS initWindow())
            rebuildWindow(target, wasMaximized, px, py, pw, ph);

            // A FAILED VULKAN ATTACH RUNS THIS LAUNCH ON OPENGL; THE SAVED PREFERENCE KEEPS WHAT THE USER PICKED
            if (target == RenderSystem.Engine.VULKAN && RenderSystem.engineKind() != RenderSystem.Engine.VULKAN)
                ctx.showError("Vulkan unavailable",
                        "Vulkan could not be initialized on this system.\nThe app is running on OpenGL.", null);
            WaterMedia.LOGGER.info(IT, "Render engine hot-swap complete: {}", RenderSystem.engineName());
        } catch (final Throwable t) {
            WaterMedia.LOGGER.error(IT, "Render engine hot-swap failed; attempting OpenGL recovery", t);
            // LAST RESORT: FORCE OPENGL AND REBUILD FROM WHATEVER THE SWAP LEFT; A ZEROED HANDLE MEANS THE SWAP ALREADY
            // DESTROYED THE WINDOW
            try {
                if (ctx.windowHandle != NULL) destroyWindow();
                rebuildWindow(RenderSystem.Engine.OPENGL, wasMaximized, px, py, pw, ph);
                ctx.showError("Engine switch failed",
                        "The render engine could not be switched.\nThe app recovered on OpenGL.", null);
            } catch (final Throwable recovery) {
                WaterMedia.LOGGER.error(IT, "OpenGL recovery after a failed hot-swap also failed; shutting down", recovery);
                running = false; // LET THE MAIN LOOP EXIT SO cleanup() TEARS DOWN CLEANLY
            }
        } finally {
            ctx.requestRender();
        }
    }

    // createWindow() ALWAYS BUILDS MAXIMIZED, SO A WINDOWED PLACEMENT IS RESTORED HERE; THE SIZE IS READ BACK
    // BECAUSE THE RESIZE CALLBACK MAY NOT HAVE FIRED YET
    private static void rebuildWindow(final RenderSystem.Engine engine, final boolean wasMaximized, final int px, final int py,
                                      final int pw, final int ph) {
        RenderSystem.chooseEngine(engine);
        openWindow();
        if (!wasMaximized) {
            glfwRestoreWindow(ctx.windowHandle);
            glfwSetWindowSize(ctx.windowHandle, pw, ph);
            glfwSetWindowPos(ctx.windowHandle, px, py);
            maximized = false;
            ctx.windowMaximized = false;
        }
        try (final MemoryStack stack = stackPush()) {
            final IntBuffer width = stack.mallocInt(1);
            final IntBuffer height = stack.mallocInt(1);
            glfwGetWindowSize(ctx.windowHandle, width, height);
            ctx.windowWidth = width.get(0);
            ctx.windowHeight = height.get(0);
        }
        RenderSystem.viewport(ctx.windowWidth, ctx.windowHeight);

        // RE-APPLY THE UI SCALE (MIRRORS BOOT), RE-CREATE THE ASSET TEXTURES AND WINDOW ICON, SHOW, RE-PACE THE LIMITER
        reapplyUiScale();
        ctx.assets.load(ctx);
        glfwShowWindow(ctx.windowHandle);
        frameLimiter = FrameLimiter.forWindow(ctx.windowHandle);
    }

    // ==========================================================================
    // INPUT HANDLING
    // ==========================================================================
    private static void handleFrameInput() {
        if (ctx.mousePressed) {
            ctx.mousePressed = false;
            // GLOBAL DIALOGS ARE TRULY MODAL: A PRESS MUST NOT START A DRAG ON CONTENT BENEATH THE SCRIM
            if (!ctx.hasError() && !ctx.exitConfirm) root.press(ctx.mouseX, ctx.mouseY);
        }
        // HOVER DISPATCHES ONLY ON REAL CURSOR MOVEMENT, SO A PARKED CURSOR NEVER OVERRIDES KEYBOARD NAVIGATION
        if (ctx.mouseX != lastMoveX || ctx.mouseY != lastMoveY) {
            lastMoveX = ctx.mouseX;
            lastMoveY = ctx.mouseY;
            root.hover(ctx.mouseX, ctx.mouseY);
            root.drag(ctx.mouseX, ctx.mouseY);
        }
        if (ctx.mouseClicked) {
            ctx.mouseClicked = false;
            // RELEASE ENDS ANY CAPTURE FIRST; THE SHELL SUPPRESSES THE CLICK WHEN IT ENDED A REAL DRAG
            root.release(ctx.mouseX, ctx.mouseY);
            root.click(ctx.mouseX, ctx.mouseY);
        }
    }

    private static void handleKeyInput(final long window, final int key, final int scancode, final int action, final int mods) {
        ctx.requestRender();
        ctx.ctrlDown = (mods & GLFW_MOD_CONTROL) != 0
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_LEFT_CONTROL) == GLFW_PRESS
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_RIGHT_CONTROL) == GLFW_PRESS;
        ctx.altDown = (mods & GLFW_MOD_ALT) != 0
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_LEFT_ALT) == GLFW_PRESS
                || glfwGetKey(ctx.windowHandle, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS;

        // CRT TOGGLE, EXCEPT IN A FOCUSED TEXT FIELD WHERE THE C IS TYPED TEXT
        if (action == GLFW_RELEASE && key == GLFW_KEY_C && !root.textInputFocused()) {
            ctx.crt = !ctx.crt;
            AppConfig.crt(ctx.crt);
            ctx.requestRender();
            return;
        }

        // THE TREE RESOLVES PRIORITY TOPMOST-FIRST; A VISIBLE GLOBAL DIALOG CONSUMES ESC/ENTER ITSELF
        root.key(key, action);
    }

    private static void handleCharInput(final long window, final int codepoint) {
        ctx.requestRender();
        root.character(codepoint);
    }

    // ==========================================================================
    // CLEANUP
    // ==========================================================================
    private static void cleanup() {
        // FINISH ANY BACKGROUND PLAYER RELEASE BEFORE TEARING DOWN THE RENDER DEVICE, OR A VULKAN
        // RELEASE COULD DESTROY IMAGES ON AN ALREADY-DESTROYED DEVICE
        ctx.awaitPlayerRelease();
        RenderSystem.cleanup();
        destroyWindow();
        glfwTerminate();
        final GLFWErrorCallback errorCallback = glfwSetErrorCallback(null);
        if (errorCallback != null) errorCallback.close();
        System.exit(0);
    }

    // ==========================================================================
    // LOGGING
    // ==========================================================================
    private static void initLogging() {
        final String filename = "logs/watermedia-app.log";
        final File logfile = new File(filename);
        // NO ':' OR SPACES IN THE STAMP: WINDOWS REJECTS THEM IN FILENAMES AND THE ROTATION WOULD FAIL
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        final boolean rotationFailed = logfile.exists() && !logfile.renameTo(new File("logs/watermedia-app-" + stamp + ".log"));

        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.WARN);

        final AppenderComponentBuilder console = builder.newAppender("Console", "Console")
                .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        console.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "%highlight{[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n}"));

        final AppenderComponentBuilder file = builder.newAppender("File", "File")
                .addAttribute("fileName", filename)
                .addAttribute("append", true);
        file.add(builder.newLayout("PatternLayout")
                .addAttribute("pattern", "[%d{HH:mm:ss}] [%t/%level] [%logger/%marker]: %msg%n"));

        builder.add(console);
        builder.add(file);
        builder.add(builder.newRootLogger(Level.DEBUG)
                .add(builder.newAppenderRef("Console"))
                .add(builder.newAppenderRef("File")));

        Configurator.initialize(builder.build());
        if (rotationFailed) WaterMedia.LOGGER.warn(IT, "Could not rotate {}; continuing in append mode", filename);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // HOW ONE MAIN-LOOP ITERATION PAINTS THE WINDOW
    enum FramePlan {
        IDLE, // NOTHING CHANGED AND NOTHING MOVES: NO FRAME
        DIRECT, // THE TREE DRAWS STRAIGHT INTO THE WINDOW, LIVE PAINTERS INLINE
        CACHE, // THE TREE DRAWS INTO THE CACHE LAYERS, WHICH THEN COMPOSITE WITH THE LIVE PAINTERS
        COMPOSITE; // ONLY THE CACHE LAYERS AND THE LIVE PAINTERS DRAW; THE TREE IS NOT TOUCHED

        // AN ANIMATING SCREEN REPAINTS WHOLE EVERY FRAME, A CHANGE REPAINTS THE TREE (INTO THE CACHE WHILE A CRT BAND MOVES),
        // AND A STILL TREE UNDER A MOVING BAND COMPOSITES ONCE PER BAND STEP, REBUILDING THE CACHE WHEN THE BACKEND DROPPED IT
        static FramePlan of(final boolean continuous, final boolean requested, final boolean crt, final boolean live,
                            final boolean visible, final boolean cacheValid, final boolean stepped) {
            if (continuous) return DIRECT;
            final boolean moving = crt && live;
            if (requested) return moving ? CACHE : DIRECT;
            if (!moving || !visible) return IDLE;
            if (!cacheValid) return CACHE;
            return stepped ? COMPOSITE : IDLE;
        }

        // RE-CHECKED AFTER beginFrame(): A SWAPCHAIN REBUILD THERE TAKES THE LAYERS, SO THAT COMPOSITE REPAINTS THE TREE
        FramePlan settle(final boolean cacheValid) {
            return this == COMPOSITE && !cacheValid ? CACHE : this;
        }
    }

    // PACES FRAMES TO THE MONITOR REFRESH RATE FOR DRIVERS THAT IGNORE THE SWAP INTERVAL
    private static final class FrameLimiter {
        private static final int DEFAULT_REFRESH_HZ = 60;
        private static final int MIN_REFRESH_HZ = 30;
        private static final int MAX_REFRESH_HZ = 360;

        private final int refreshRate;
        private final long frameNanos;
        private long nextFrameNanos;

        private FrameLimiter(final int refreshRate) {
            this.refreshRate = MathUtil.clamp(refreshRate, MIN_REFRESH_HZ, MAX_REFRESH_HZ);
            this.frameNanos = 1_000_000_000L / this.refreshRate;
        }

        static FrameLimiter forWindow(final long windowHandle) {
            long monitor = glfwGetWindowMonitor(windowHandle);
            if (monitor == 0L) monitor = glfwGetPrimaryMonitor();
            final GLFWVidMode mode = monitor != 0L ? glfwGetVideoMode(monitor) : null;
            final int hz = mode != null && mode.refreshRate() > 0 ? mode.refreshRate() : DEFAULT_REFRESH_HZ;
            return new FrameLimiter(hz);
        }

        void syncBeforeFrame() {
            final long now = System.nanoTime();
            if (this.nextFrameNanos == 0L) this.nextFrameNanos = now;
            // A FRAME LATE BY MORE THAN ONE PERIOD RESTARTS THE SCHEDULE INSTEAD OF BURSTING TO CATCH UP
            if (now < this.nextFrameNanos) LockSupport.parkNanos(this.nextFrameNanos - now);
            else if (now - this.nextFrameNanos > this.frameNanos) this.nextFrameNanos = now;
            this.nextFrameNanos += this.frameNanos;
        }

        double idleTimeoutSeconds() {
            return MathUtil.clamp(1.0 / this.refreshRate, 0.01, 0.25);
        }
    }
}
