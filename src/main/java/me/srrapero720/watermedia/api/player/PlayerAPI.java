package me.srrapero720.watermedia.api.player;

import me.srrapero720.watermedia.OperativeSystem;
import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.api.WaterMediaAPI;
import me.srrapero720.watermedia.api.player.vlc.SimplePlayer;
import me.srrapero720.watermedia.core.tools.IOTool;
import me.srrapero720.watermedia.core.tools.JarTool;
import me.srrapero720.watermedia.loaders.ILoader;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.file.PathUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import uk.co.caprica.vlcj.VideoLan4J;
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class PlayerAPI extends WaterMediaAPI {
    private static final NativeDiscovery DISCOVERY = new NativeDiscovery();
    private static final Marker IT = MarkerManager.getMarker(PlayerAPI.class.getSimpleName());
    private static final String VIDEOLAN_BIN_ASSET = "videolan/" + OperativeSystem.getFile();
    private static final String VIDEOLAN_VER_ASSET = "videolan/version.cfg";

    private static final Map<String, String> ARGVARS = new HashMap<>();

    private static MediaPlayerFactory DEFAULT_FACTORY;
    private static MediaPlayerFactory DEFAULT_SONG_FACTORY;

    /**
     * Check if PlayerAPI and/or VLC is loaded and ready to be used.
     * Some modules cannot be loaded in some OS, in that case WATERMeDIA can address it and
     * keep still working
     * @return if PlayerAPI and/or VLC was loaded
     */
    public static boolean isReady() {
        return NativeDiscovery.alreadyFound;
    }

    /**
     * Returns default WATERMeDIA's MediaPlayer factory instance
     * uses DirectSound by default, witch provides an individual volume for each player
     * by default uses on video output "mem"
     * @return default factory
     */
    public static MediaPlayerFactory getFactory() {
        return DEFAULT_FACTORY;
    }

    /**
     * Returns default WATERMeDIA's MediaPlayer factory instance
     * uses DirectSound and No video output by default, witch provides an individual volume for each player
     * by default uses on video output "none"
     * @return default factory
     */
    public static MediaPlayerFactory getFactorySoundOnly() {
        return DEFAULT_SONG_FACTORY;
    }

    /**
     * Use your own VLCArgs at your own risk
     * By default this method makes a ReleaseHook to release factory on process shutdown
     * Suggestion: Use the same VLC arguments for logging but with another filename
     * Example: <pre> "--logfile", "logs/vlc/mymod-latest.log",</pre>
     * check <a href="https://wiki.videolan.org/VLC_command-line_help/">VideoLAN wiki</a>
     * @param vlcArgs arguments to make another VLC instance
     * @return a PlayerFactory to create custom VLC players. {@link SimplePlayer} can accept factory for new instances
     */
    public static MediaPlayerFactory customFactory(String[] vlcArgs) {
        MediaPlayerFactory factory = null;
        if (DISCOVERY.discover()) {
            factory = new MediaPlayerFactory(DISCOVERY, vlcArgs);
            Runtime.getRuntime().addShutdownHook(new Thread(factory::release));
            LOGGER.info(IT, "Created new VLC instance from '{}' with args: '{}'", DISCOVERY.discoveredPath(), Arrays.toString(vlcArgs));
        } else {
            LOGGER.fatal(IT, "Missing VLC - Cannot create MediaPlayerFactory instance");
        }

        return factory;
    }

    // LOADING
    private final Path dir;
    private final Path logs;

    private final File binOutput;
    private final File cfgOutput;

    private boolean extract;
    public PlayerAPI() {
        super();
        ILoader bootstrap = WaterMedia.getLoader();
        this.dir = bootstrap.tempDir().resolve("videolan");
        this.logs = bootstrap.tempDir().resolve("logs/videolan.log");

        this.binOutput = bootstrap.tempDir().resolve(VIDEOLAN_BIN_ASSET).toFile();
        this.cfgOutput = bootstrap.tempDir().resolve(VIDEOLAN_VER_ASSET).toFile();
    }

    @Override
    public Priority priority() {
        return Priority.HIGH;
    }

    @Override
    public boolean prepare(ILoader bootCore) throws Exception {
        String versionInJar = JarTool.readString(VIDEOLAN_VER_ASSET);
        String versionInFile = IOTool.readString(cfgOutput.toPath());
        boolean wrapped = OperativeSystem.isWrapped();
        boolean versionMatch = versionInFile != null && versionInFile.equalsIgnoreCase(versionInJar);
        extract = wrapped && !versionMatch;

        LOGGER.info(IT, "Running in {}, binaries are {}", OperativeSystem.OS, wrapped ? "wrapped" : "not wrapped");
        if (!extract) {
            String reason = "unknown";
            if (!wrapped) reason = "binaries are not wrapped";
            if (versionMatch) reason = "extracted version match with wrapped version";
            LOGGER.warn(IT, "VLC binaries extraction skipped. Reason: {}", reason);
        } else {
            if (binOutput.getParentFile().exists()) {
                LOGGER.warn(IT, "Detected an old installation, cleaning up...");
                FileUtils.deleteDirectory(binOutput.getParentFile());
                LOGGER.warn(IT, "Cleaning up successfully");
            }
        }

        return true;
    }

    @Override
    public void start(ILoader bootCore) throws Exception {
        if (extract) {
            LOGGER.info(IT, "Extracting VideoLAN binaries...");
            if ((!binOutput.exists() && JarTool.copyAsset(VIDEOLAN_BIN_ASSET, binOutput.toPath())) || binOutput.exists()) {
                IOTool.un7zip(IT, binOutput.toPath());
                binOutput.delete();

                JarTool.copyAsset(VIDEOLAN_VER_ASSET, cfgOutput.toPath());

                LOGGER.info(IT, "VideoLAN binaries extracted successfully");
            } else {
                LOGGER.error(IT, "Failed to extract VideoLAN binaries");
            }
        }

        // LOGGER INIT
        LOGGER.info(IT, "Processing VideoLAN log files...");
        if (Files.exists(logs)) {
            Path parent = logs.getParent();
            try {
                PathUtils.deleteDirectory(parent);
            } catch (IOException e) {
                LOGGER.warn(IT, "Failed to delete VLC logs directory", e);
            }
        }

        // VLCJ INIT
        VideoLan4J.init(dir.toAbsolutePath());

        // VLC INIT, this need to be soft-crashed because api and game can still work without VLC
        try {
            String[] args = JarTool.readArrayAndParse("videolan/arguments.json", ARGVARS);
            String[] args2 = ArrayUtils.addAll(args, RuntimeUtil.isWindows() ? "--aout=directsound" : "--aout={mmdevice,waveout}");
            DEFAULT_FACTORY = customFactory(args2);
            DEFAULT_SONG_FACTORY = customFactory(ArrayUtils.addAll(args, "--vout=none"));
        } catch (Exception e) {
            LOGGER.error(IT, "Failed to load VLC", e);
        }
    }

    @Override
    public void release() {
        DEFAULT_FACTORY.release();
    }
}