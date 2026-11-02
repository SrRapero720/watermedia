package org.watermedia.bootstrap.app;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.bootstrap.app.render.RenderSystem;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

/** Runs the launcher and the app spec against one {@code config/app.properties} in a fresh working directory. */
public final class AppConfigProbe {

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void main(final String[] arguments) throws Exception {
        final String scenario = arguments[0];
        final Field preferences = AppBootstrap.class.getDeclaredField("PREFERENCES");
        preferences.setAccessible(true);
        final Path file = ((Path) preferences.get(null)).toAbsolutePath();
        switch (scenario) {
            case "defaults" -> {
                expect("vulkan", launcherEngine(), "launcher engine without a file");
                WaterConfig.init();
                final ConfigSpec spec = WaterConfig.registerBlocking(AppConfig.class);
                if (!spec.path().equals(file)) throw new AssertionError("The launcher reads " + file + " but WaterConfig writes " + spec.path());
                if (AppConfig.engine != RenderSystem.Engine.VULKAN || AppConfig.scale != 0f || !AppConfig.crt || !AppConfig.sound)
                    throw new AssertionError("Unexpected defaults: " + AppConfig.engine + ", " + AppConfig.scale + ", " + AppConfig.crt + ", " + AppConfig.sound);
            }
            case "shared" -> {
                // THE SETTINGS SCREEN SAVES THROUGH THE SAME PACKAGE-PRIVATE METHOD
                WaterConfig.init();
                final ConfigSpec spec = WaterConfig.registerBlocking(AppConfig.class);
                AppConfig.engine = RenderSystem.Engine.OPENGL;
                AppConfig.scale = 1.25f;
                final Method save = ConfigSpec.class.getDeclaredMethod("save");
                save.setAccessible(true);
                save.invoke(spec);
                final Properties written = new Properties();
                try (final InputStream input = Files.newInputStream(file)) { written.load(input); }
                if (!written.stringPropertyNames().equals(Set.of(AppConfig.ENGINE, AppConfig.SCALE, "crt", "sound")))
                    throw new AssertionError("Unexpected keys: " + written.stringPropertyNames());
                expect("1.25", written.getProperty(AppConfig.SCALE), "saved scale");
                expect("opengl", launcherEngine(), "launcher engine after the spec saved OPENGL");
            }
            case "malformed" -> {
                Files.createDirectories(file.getParent());
                Files.writeString(file, AppConfig.ENGINE + "=OPENGL\\u12\n");
                expect("vulkan", launcherEngine(), "launcher engine with a malformed file");
            }
            case "override" -> {
                Files.createDirectories(file.getParent());
                Files.writeString(file, AppConfig.ENGINE + "=OPENGL\n");
                System.setProperty(AppBootstrap.ENGINE_PROP, "vulkan");
                expect("vulkan", launcherEngine(), "launcher engine with an override");
                expect(AppConfig.ENGINE + "=OPENGL\n", Files.readString(file), "file after the launcher read it");
            }
            default -> throw new AssertionError("Unknown scenario " + scenario);
        }
        System.out.println("PROBE_OK " + scenario);
        System.exit(0);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static String launcherEngine() throws Exception {
        final Method engine = AppBootstrap.class.getDeclaredMethod("engine");
        engine.setAccessible(true);
        return (String) engine.invoke(new AppBootstrap());
    }

    private static void expect(final String expected, final String actual, final String what) {
        if (!expected.equals(actual)) throw new AssertionError(what + ": expected " + expected + " but was " + actual);
    }
}
