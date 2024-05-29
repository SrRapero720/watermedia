package me.srrapero720.watermedia.loaders;

import java.io.File;
import java.nio.file.Path;

/**
 * Required interface to boot instance of {@link me.srrapero720.watermedia.WaterMedia WaterMedia}
 * some instances was pre-made for Minecraft mod loaders like MinecraftForge, NeoForge, Fabric and Quilt.
 */
public interface ILoader {
    /**
     * Name should be without spaces or number and with capital letters
     * @return loader name
     */
    String name();

    /**
     * Tmp path is normally java.io.tmp directory; you can set up your custom TMP dir,
     * Just in case you need it (for cleaning or something else)
     * @return Path instance to tmp directory
     */
    Path tempDir();

    /**
     * Absolute path to current process directory
     * normally is enough just use `new File("")` but is better do not assume is always it
     * @return Path instance to process directory
     */
    Path processDir();

    /**
     * Aggressive trigger for TLauncher
     * this was used by internal usages of WATERMeDIA.
     * Please DO NOT bypass
     * @return if the current environment is using TLauncher
     */
    boolean tlcheck();

    /**
     * Check if the current environment was a client
     * this is important to prevent run on clients and load any client-side class like GL11
     * @return if was client-side
     */
    boolean clientSide();

    /**
     * Checks if the current environment was a development environment
     * We cannot consider offline-mode as a dev env because *cracked users*<br>
     * Is necessary search any special trigger like forge
     * @return if was a development env
     */
    boolean developerMode();

    /**
     * Default {@link ILoader} instance to be used in non-minecraft instances
     * @see ILoader
     */
    ILoader DEFAULT = new ILoader() {
        private final Path tmpPath = new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath().resolve("watermedia");
        private final Path processPath = new File("build/data").toPath().toAbsolutePath();

        @Override
        public String name() {
            return "Default";
        }

        @Override
        public Path tempDir() {
            return tmpPath;
        }

        @Override
        public Path processDir() {
            return processPath;
        }

        @Override
        public boolean tlcheck() {
            return false;
        }

        @Override
        public boolean clientSide() {
            return true;
        }

        @Override
        public boolean developerMode() {
            return false;
        }
    };
}