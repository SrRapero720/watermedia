package org.watermedia.test.support;

import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared client bootstrap; required native jobs fail instead of silently skipping missing binaries. */
public final class MediaBootstrap {
    private static boolean attempted;
    private static boolean ffmpeg;
    private static Throwable failure;

    private MediaBootstrap() {}

    public static void client() { ffmpegAvailable(); }

    public static synchronized boolean ffmpegAvailable() {
        if (!attempted) {
            attempted = true;
            try {
                if (!WaterMedia.loaded()) {
                    final Path tmp = Files.createTempDirectory("wm-test");
                    WaterMedia.start("WMTEST", tmp, Path.of("").toAbsolutePath(), true);
                }
                ffmpeg = CodecsAPI.ffmpegLoaded();
            } catch (final Exception | LinkageError e) {
                failure = e;
            }
        }
        // EVERY LATER CALLER FAILS WITH THE BOOTSTRAP CAUSE INSTEAD OF A MISLEADING ERROR OF ITS OWN
        if (failure != null) throw new AssertionError("The client bootstrap failed", failure);
        if (!ffmpeg && Boolean.getBoolean("watermedia.test.requireNatives"))
            throw new AssertionError("Required FFmpeg natives did not load");
        return ffmpeg;
    }

    /** Runs a lifecycle scenario in a fresh JVM with isolated files and optional client bindings. */
    public static void runProbe(final Class<?> type, final Path directory, final String scenario, final boolean omitBindings) throws Exception {
        final String classpath = Arrays.stream(System.getProperty("watermedia.test.classpath", System.getProperty("java.class.path"))
                        .split(File.pathSeparator))
                .filter(path -> !omitBindings || !Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT)
                        .matches("(?:lwjgl|javacpp|ffmpeg|javafx|joml).*\\.jar"))
                .map(path -> Path.of(path).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        final Path output = directory.resolve(scenario + ".log");
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-cp", classpath, type.getName(), scenario)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Lifecycle probe timed out: " + type.getSimpleName());
            final String log = Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("PROBE_OK " + scenario), log);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
}
