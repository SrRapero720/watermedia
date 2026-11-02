package org.watermedia.bootstrap;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/** Refuses to start under TLauncher, a Minecraft launcher distributed with malware, from any loader. */
public final class DefensiveCheck {
    private static final Marker IT = MarkerManager.getMarker(DefensiveCheck.class.getSimpleName());

    // TLAUNCHER SIGNATURES
    // THE LAUNCHER RUNS THE GAME IN ITS OWN JVM; WHAT REACHES IT IS THE SKIN MOD IT COPIES INTO mods/, ITS PATCHED
    // authlib/patchy/netty LIBRARIES UNDER org/tlauncher AND, ON OLDER SETUPS, THE JAVA RUNTIME OF ITS DATA FOLDER
    private static final String PACKAGE = "org.tlauncher.";
    private static final String[] MODS = { "tlskincape", "tlauncher_custom_cape_skin" };
    private static final String[] CLASSES = { "org.tlauncher.TLSkinCape", "org.tlauncher.MixinConnector", "org.tlauncher.forge.ForgeMod",
            "org.tlauncher.launch.Launch", "org.tlauncher.tweaker.Tweaker", "org.tlauncher.skin.cape.asm.TLSkinCapeHooks",
            "org.tlauncher.tlauncher.rmo.Bootstrapper", "org.tlauncher.tlauncher.rmo.TLauncher" };
    private static final String[] RESOURCES = { "mixins.tlskincape.json", "tlskincape-mixins.json", "tlskincape-refmap.json" };
    // ONE EXPRESSION FOR PATHS, OPTIONS AND NAMES, MATCHED ON FORWARD SLASHES: ITS EXECUTABLES, ITS GAME FILES, ITS MAVEN GROUP,
    // ITS SKIN MOD AND ITS RUNTIME STORE. PLAIN FOLDERS NAMED AFTER IT, ATLAUNCHER, SKCRAFTLAUNCHER AND LEGACY LAUNCHER
    // (ru.turikhay.tlauncher, .tlauncher/legacy) STAY OUT ON PURPOSE
    private static final Pattern SIGNATURE = Pattern.compile("(?i)(?<![\\w.-])(?:tlauncher(?:-[\\w.-]+)?\\.(?:exe|jar)|old-tlauncher\\.exe"
            + "|tlauncherprofiles\\.json|tlauncheradditional\\.json|tempoptifinestore-[\\d.]+\\.json|tlauncher_libraries|\\.?tlauncher/jvms"
            + "|org/tlauncher)(?![\\w.-])|(?<![\\w.])org\\.tlauncher[.:]|(?<![a-z0-9])tl_?skin_?cape");
    private static final String[] PATH_PROPERTIES = { "java.class.path", "java.library.path", "jdk.module.path" };
    private static final String COMMAND_PROPERTY = "sun.java.command"; // CARRIES THE ACCOUNT NAME AND TOKEN
    // LOADER ENTRY POINTS WHOSE CLASS LOADERS SEE THE APPLICATION CLASS PATH BEHIND THE MOD LAYERS
    private static final String[] LAUNCHERS = { "cpw.mods.modlauncher.Launcher", "cpw.mods.bootstraplauncher.BootstrapLauncher",
            "net.fabricmc.loader.impl.launch.knot.Knot" };
    // {CLASS, STATIC ACCESSOR, QUERY}; REFLECTION KEEPS THE LOADER API OUT OF THE MODULE GRAPH AND OF THE COMPILE STUBS
    private static final String[][] MOD_LISTS = { { "net.neoforged.fml.ModList", "get", "isLoaded" },
            { "net.minecraftforge.fml.ModList", "get", "isLoaded" }, { "net.fabricmc.loader.api.FabricLoader", "getInstance", "isModLoaded" } };
    // FILES IT LEAVES IN THE GAME DIRECTORY AND ITS RUNTIME STORE; THEY SURVIVE A SWITCH TO ANOTHER LAUNCHER, SO THEY ONLY WARN
    private static final String[] FOOTPRINTS = { "TlauncherProfiles.json", "TempOptifineStore-1.0.json", "TLauncher.exe", "Old-TLauncher.exe",
            "tlauncher_libraries", "logs/tlauncher", "libraries/org/tlauncher" };
    private static final String[] RUNTIME_STORES = { ".tlauncher/jvms", "Library/Application Support/tlauncher/jvms" }; // MACOS DROPS THE DOT
    private static final int PARENT_DEPTH = 5;
    private static final int EXCERPT_SIZE = 48;
    private static final int SHOWN_SIZE = 4;
    private static final String MESSAGE = WaterMedia.NAME + " does not run under TLauncher, a malicious launcher. Uninstall it and use the official "
            + "Minecraft Launcher, Prism Launcher, ATLauncher, Modrinth App or the CurseForge app. Detected: ";

    private DefensiveCheck() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Throws when this game process belongs to TLauncher; leftovers of a past install only warn. A null game directory is the working directory. */
    public static void run(final Path gameDir) {
        final List<String> evidence = new ArrayList<>();
        final ClassLoader own = DefensiveCheck.class.getClassLoader();
        // EVERY PROBE IS BEST EFFORT: A HOST THAT HIDES AN API MUST NEVER TURN THE CHECK ITSELF INTO A CRASH
        for (final String id: MODS) {
            for (final String[] api: MOD_LISTS) {
                try {
                    final Class<?> type = Class.forName(api[0], false, own);
                    final Object list = type.getMethod(api[1]).invoke(null);
                    if ((boolean) type.getMethod(api[2], String.class).invoke(list, id)) {
                        evidence.add("mod " + id);
                        break;
                    }
                } catch (final Throwable ignored) {}
            }
        }

        // THE MOD LAYER, THE HOST LAYERS BEHIND IT AND THE APPLICATION CLASS PATH EACH GET A LOOKUP WITHOUT INITIALIZING
        final Set<ClassLoader> loaders = new LinkedHashSet<>();
        for (final ClassLoader root: new ClassLoader[] { own, Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(), ClassLoader.getPlatformClassLoader() })
            for (ClassLoader loader = root; loader != null; loader = loader.getParent()) loaders.add(loader);
        for (final String name: LAUNCHERS) {
            try {
                for (ClassLoader loader = Class.forName(name, false, own).getClassLoader(); loader != null; loader = loader.getParent()) loaders.add(loader);
            } catch (final Throwable ignored) {}
        }
        for (final String name: CLASSES) {
            final String resource = name.replace('.', '/') + ".class";
            for (final ClassLoader loader: loaders) {
                boolean present = false;
                try { present = loader.getResource(resource) != null; } catch (final Throwable ignored) {}
                if (!present) {
                    try {
                        Class.forName(name, false, loader);
                        present = true;
                    } catch (final Throwable ignored) {}
                }
                if (!present) continue;
                evidence.add("class " + name + " in " + (loader.getName() != null ? loader.getName() : loader.getClass().getSimpleName()));
                break;
            }
        }
        for (final String name: RESOURCES) {
            for (final ClassLoader loader: loaders) {
                try {
                    if (loader.getResource(name) == null) continue;
                    evidence.add("resource " + name + " in " + (loader.getName() != null ? loader.getName() : loader.getClass().getSimpleName()));
                    break;
                } catch (final Throwable ignored) {}
            }
        }

        try {
            for (final Map.Entry<Thread, StackTraceElement[]> entry: Thread.getAllStackTraces().entrySet()) {
                final String thread = entry.getKey().getName();
                if (found(thread) != null) evidence.add("thread " + thread);
                for (final StackTraceElement frame: entry.getValue()) {
                    if (!frame.getClassName().startsWith(PACKAGE)) continue;
                    evidence.add("thread " + thread + " runs " + frame.getClassName());
                    break;
                }
            }
        } catch (final Throwable ignored) {}

        try {
            for (final String argument: ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                final String match = found(argument);
                if (match != null) evidence.add("jvm option " + match);
            }
        } catch (final Throwable ignored) {}

        try {
            for (final String key: System.getProperties().stringPropertyNames()) {
                final String value = System.getProperty(key);
                if (value == null || key.equals(COMMAND_PROPERTY)) continue;
                final boolean paths = List.of(PATH_PROPERTIES).contains(key);
                for (final String part: paths ? value.split(File.pathSeparator) : new String[] { value }) {
                    final String match = found(part);
                    if (match != null) evidence.add(key + " " + match);
                }
            }
        } catch (final Throwable ignored) {}

        // WINDOWS ONLY EXPOSES THE EXECUTABLE OF OTHER PROCESSES; LINUX AND MACOS ALSO EXPOSE THEIR ARGUMENTS
        try {
            Optional<ProcessHandle> parent = ProcessHandle.current().parent();
            for (int depth = 0; depth < PARENT_DEPTH && parent.isPresent(); depth++) {
                final ProcessHandle.Info info = parent.get().info();
                final String match = found(info.commandLine().or(info::command).orElse(null));
                if (match != null) evidence.add("parent process " + match);
                parent = parent.get().parent();
            }
        } catch (final Throwable ignored) {}

        if (!evidence.isEmpty()) {
            LOGGER.error(IT, "TLauncher detected: {}", evidence);
            final List<String> shown = evidence.subList(0, Math.min(SHOWN_SIZE, evidence.size()));
            throw new IllegalStateException(MESSAGE + String.join("; ", shown)
                    + (evidence.size() > shown.size() ? " (+" + (evidence.size() - shown.size()) + " more in the log)" : ""));
        }

        final List<String> footprints = new ArrayList<>();
        try {
            final Path directory = (gameDir == null ? Path.of("") : gameDir).toAbsolutePath().normalize();
            for (final String footprint: FOOTPRINTS) {
                final Path path = directory.resolve(footprint);
                if (Files.exists(path)) footprints.add(path.toString());
            }
            for (final String base: new String[] { System.getenv("APPDATA"), System.getProperty("user.home") }) {
                if (base == null) continue;
                for (final String store: RUNTIME_STORES) {
                    final Path path = Path.of(base, store);
                    if (Files.isDirectory(path)) footprints.add(path.toString());
                }
            }
        } catch (final Throwable ignored) {}
        if (footprints.isEmpty()) LOGGER.debug(IT, "No TLauncher signature found");
        else LOGGER.warn(IT, "TLauncher leftovers found but another launcher started the game; remove them: {}", footprints);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // RETURNS THE FIRST SIGNATURE IN THE TEXT WITH ITS SURROUNDING CONTEXT, OR NULL
    static String found(final String text) {
        if (text == null) return null;
        final Matcher matcher = SIGNATURE.matcher(text.replace('\\', '/'));
        if (!matcher.find()) return null;
        final int from = Math.max(0, matcher.start() - EXCERPT_SIZE), to = Math.min(text.length(), matcher.end() + EXCERPT_SIZE);
        return (from > 0 ? "..." : "") + text.substring(from, to) + (to < text.length() ? "..." : "");
    }
}
