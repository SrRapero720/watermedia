package org.watermedia.bootstrap.app;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the standalone app runs: the type of place it was loaded from and the home that holds its {@code config/} and
 * {@code logs/}. The launcher resolves it to start the app in that home, and the app resolves the same answer there.
 */
public record AppEnvironment(Type type, Path home, Path app) {
    /** Environment of this process, from its working directory and the jar or classes directory it was loaded from. */
    public static final AppEnvironment CURRENT = of(Path.of("").toAbsolutePath(),
            Path.of(URI.create(AppEnvironment.class.getProtectionDomain().getCodeSource().getLocation().toString())));

    /** Resolves the environment of an app loaded from {@code app}, a jar or a classes directory, by a process started in {@code cwd}. */
    public static AppEnvironment of(final Path cwd, final Path app) {
        if (Files.isDirectory(app)) return new AppEnvironment(Type.DEVELOPMENT, cwd, app);
        final Path folder = app.getParent();
        // THE INSTANCE ROOT HOLDS THE config/ THE GAME READS, SO THE SETTINGS OF THE APP EDIT THAT ONE
        if (folder.getParent() != null && folder.getFileName().toString().equalsIgnoreCase("mods")) return new AppEnvironment(Type.MINECRAFT, folder.getParent(), app);
        // A SHORTCUT OR A SEARCH LAUNCH LEAVES THE PROCESS IN A READ-ONLY DIRECTORY (system32), WHERE THE JAR'S FOLDER TAKES OVER
        return new AppEnvironment(Type.STANDALONE, Files.isWritable(cwd) || !Files.isWritable(folder) ? cwd : folder, app);
    }

    /** The type of place the app was loaded from, which decides its home whatever directory the process started in. */
    public enum Type {
        /** A jar inside the {@code mods} folder of a Minecraft instance: the home is the root of that instance. */
        MINECRAFT,
        /** Classes loaded from a directory by an IDE or a test: the home is the working directory. */
        DEVELOPMENT,
        /** A jar anywhere else: the home is the working directory, or the folder of the jar when that one is read-only. */
        STANDALONE
    }
}
