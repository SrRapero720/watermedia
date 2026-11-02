package org.watermedia.bootstrap.app;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.IConfigField;
import me.srrapero720.waterconfig.api.annotations.Comment;
import me.srrapero720.waterconfig.api.annotations.NumberConditions;
import me.srrapero720.waterconfig.api.annotations.Spec;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.ThreadTool;

/**
 * WaterConfig spec of the standalone app preferences, saved as {@code config/app.properties}. The launcher reads the
 * same file before WaterConfig exists, to relaunch the app on the saved engine.
 */
@Spec(value = AppConfig.ID, format = WaterConfig.FORMAT_PROPERTIES)
@Comment("WATERMeDIA standalone app preferences")
public class AppConfig {
    private static final Marker IT = MarkerManager.getMarker(AppConfig.class.getSimpleName());
    /** Spec identifier and base name of the {@code app.properties} file. */
    public static final String ID = "app";
    /** Key of {@link #engine}, shared with the launcher. */
    public static final String ENGINE = "engine";
    /** Key of {@link #scale}. */
    public static final String SCALE = "scale";
    /** Smallest interface scale factor. */
    public static final float SCALE_MIN = 1f;
    /** Largest interface scale factor. */
    public static final float SCALE_MAX = 3f;
    /** Distance between two interface scale factors. */
    public static final float SCALE_STEP = 0.25f;
    private static final long LOAD_TIMEOUT_MS = 2_000;

    @Spec.Field(value = ENGINE, control = Control.SEGMENTED)
    @Comment("Render engine. OpenGL runs instead for one launch when Vulkan is unavailable. Applies live")
    public static RenderSystem.Engine engine = RenderSystem.Engine.VULKAN;

    @Spec.Field(value = SCALE, control = Control.SEEKBAR)
    @NumberConditions(minFloat = 0f, maxFloat = SCALE_MAX)
    @Comment("Interface scale: 0 follows the monitor, otherwise the factor from 1 to 3 in 0.25 steps")
    public static float scale = 0f;

    @Spec.Field
    @Comment("CRT overlay with scanlines and a moving neon band. Keyboard shortcut: C")
    public static boolean crt = true;

    @Spec.Field
    @Comment("Tick sound on menu and settings navigation")
    public static boolean sound = true;

    // null WHEN REGISTRATION FAILED: THE APP THEN RUNS ON THE FIELD DEFAULTS
    private static volatile ConfigSpec spec;
    // ENGINE PREFERENCE THE LAST apply() SAW; ONLY A CHANGE OF THE PREFERENCE SWAPS, NEVER A FALLBACK OR AN OVERRIDE
    private static RenderSystem.Engine applied;
    // SET WHILE THE FILE IS STILL LOADING AFTER applyBoot(); THE LOAD OVERWRITES THE FIELDS AND DROPS DIRTY MARKS
    private static boolean late;
    private static Boolean crtBeforeLoad;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the registered spec, or {@code null} when registration failed. */
    public static ConfigSpec spec() {
        return spec;
    }

    /** Registers the spec; WaterConfig loads the file on its IO pool while the window boots. */
    public static void register() {
        try {
            WaterConfig.init();
            spec = WaterConfig.register(AppConfig.class);
        } catch (final Throwable t) {
            WaterMedia.LOGGER.warn(IT, "Failed to register the app config spec", t);
        }
    }

    /** Waits up to two seconds for the load started by {@link #register()} to finish or fail. */
    public static void await() {
        final ConfigSpec s = spec;
        if (s == null) return;
        final long deadline = System.currentTimeMillis() + LOAD_TIMEOUT_MS;
        while (!settled(s) && System.currentTimeMillis() < deadline) ThreadTool.sleep(5);
    }

    /** Waits for the load and applies every value before the first frame, without swapping the engine. */
    public static void applyBoot(final AppContext ctx) {
        await();
        final ConfigSpec s = spec;
        late = s != null && !settled(s);
        if (late) WaterMedia.LOGGER.warn(IT, "The app preferences are still loading; they apply once the load ends");
        applied = engine;
        apply(ctx);
    }

    /** Applies the values of a load that outlasted {@link #applyBoot}; the main loop calls it every iteration. */
    public static void poll(final AppContext ctx) {
        if (!late || !settled(spec)) return;
        late = false;
        // THE LOAD REPLACED crt, SO A SHORTCUT TOGGLE MADE BEFORE IT ENDED IS RESTORED AND SAVED AGAIN
        if (crtBeforeLoad != null) crt(crtBeforeLoad);
        crtBeforeLoad = null;
        applied = engine;
        apply(ctx);
    }

    /** Applies the current values to the running app; the settings screen calls it after every edit of this spec. */
    public static void apply(final AppContext ctx) {
        ctx.crt = crt;
        ctx.sound.enabled = sound;
        WaterMediaApp.reapplyUiScale();
        if (engine == applied) return;
        applied = engine;
        if (engine != RenderSystem.engineKind()) WaterMediaApp.requestEngineSwap(engine);
    }

    /** Saves the CRT overlay state toggled by the keyboard shortcut. */
    public static void crt(final boolean on) {
        crt = on;
        if (late) crtBeforeLoad = on;
        // THE WATERCONFIG WORKER SAVES A SPEC ONCE ONE OF ITS FIELDS IS MARKED DIRTY
        final ConfigSpec s = spec;
        if (s == null) return;
        final IConfigField<?, ?> field = s.findField("crt");
        if (field != null) s.markDirty(field);
    }

    /** Returns the factor {@code value} asks for: 0 for automatic, else snapped to {@link #SCALE_STEP} within the bounds. */
    public static float scaleFactor(final float value) {
        if (!(value > 0f)) return 0f;
        return MathUtil.clamp(Math.round(value / SCALE_STEP) * SCALE_STEP, SCALE_MIN, SCALE_MAX);
    }

    /** Returns every value the settings screen offers for {@link #scale}: 0 for automatic, then each factor in order. */
    public static double[] scales() {
        final int count = Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP) + 1;
        final double[] values = new double[count + 1];
        for (int i = 0; i < count; i++) values[i + 1] = SCALE_MIN + i * SCALE_STEP;
        return values;
    }

    /** Returns how a scale reads in the settings screen: {@code Auto} or the factor, such as {@code x1.25}. */
    public static String scaleLabel(final double value) {
        final float factor = scaleFactor((float) value);
        return factor == 0f ? "Auto" : "x" + factor;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static boolean settled(final ConfigSpec s) {
        return s == null || s.isLoaded() || s.status() == ConfigSpec.Status.FAILED;
    }
}
