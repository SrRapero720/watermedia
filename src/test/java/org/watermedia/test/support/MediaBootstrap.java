package org.watermedia.test.support;

import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared client bootstrap and child-JVM probes; required native jobs fail instead of silently skipping missing binaries. */
public final class MediaBootstrap {
    private static final long PROBE_TIMEOUT_MS = 45_000L;
    private static final long KILL_TIMEOUT_MS = 5_000L;
    private static boolean attempted;
    private static boolean ffmpeg;
    private static Throwable failure;

    private MediaBootstrap() {}

    /** Starts the shared client bootstrap once per test JVM, failing with its cause when it cannot start. */
    public static void client() { ffmpegAvailable(); }

    /** Returns whether the shared client bootstrap loaded FFmpeg; with {@code watermedia.test.requireNatives} a missing FFmpeg fails. */
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

    /** Runs a lifecycle scenario in a fresh JVM with isolated files and optional client bindings; every failure carries the probe's log. */
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
        final boolean exited;
        try {
            exited = process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            // A HUNG PROBE DIES BEFORE ITS LOG IS READ, SO THE LOG IS COMPLETE AND NO LONGER WRITTEN
            if (process.isAlive()) process.destroyForcibly().waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        // LENIENT DECODING: A STRAY BYTE OUTSIDE UTF-8 MUST NOT HIDE THE LOG
        final String log = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        final String probe = type.getSimpleName() + " " + scenario;
        assertTrue(exited, probe + " timed out after " + PROBE_TIMEOUT_MS + " ms:\n" + log);
        assertEquals(0, process.exitValue(), probe + " failed:\n" + log);
        assertTrue(log.contains("PROBE_OK " + scenario), probe + " ended without PROBE_OK:\n" + log);
    }
}
