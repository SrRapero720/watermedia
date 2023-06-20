package me.srrapero720.watermedia.core.videolan;


import me.lib720.caprica.vlcj.factory.MediaPlayerFactory;
import me.lib720.caprica.vlcj.factory.discovery.provider.CustomDirectoryProvider;
import me.srrapero720.watermedia.MediaUtil;
import me.srrapero720.watermedia.api.WaterMediaAPI;
import me.srrapero720.watermedia.api.external.ThreadUtil;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.util.zip.GZIPOutputStream;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

public class VLCManager {
    private static final Marker IT = MarkerFactory.getMarker("VLCManager");
    private static MediaPlayerFactory defaultFactory;
    public static MediaPlayerFactory getDefaultFactory() { return defaultFactory; }

    public static boolean init(Path rootPath) {
        if (defaultFactory != null) return true;

        // LOGGER INIT
        var vlcLogs = rootPath.resolve("logs/vlc");
        if (!Files.exists(vlcLogs.toAbsolutePath())) vlcLogs.toFile().mkdirs();
        else compressAndDeleteLogFile(vlcLogs.resolve("latest.log"));

        //INIT
        var vlcPath = rootPath.resolve("cache/vlc/");
        var config = vlcPath.resolve("version.cfg");
        var version = VLCResources.getVersion(config);
        CustomDirectoryProvider.init(vlcPath);

        // Check if we need to update binaries
        if (version == null || !version.equals(VLCResources.getVersion())) {
            // CLEAR
            LOGGER.warn(IT, "Running deletion for VLC Files");
            for (var binary : VLCResources.values()) binary.clear(rootPath.resolve("cache/vlc"));

            // EXTRACT
            LOGGER.warn(IT, "Running extraction for VLC Files");
            for (var binary : VLCResources.values()) binary.extract(rootPath.resolve("cache/vlc"));

            // SET LOCAL VERSION
            ThreadUtil.trySimple(() -> {
                if (!Files.exists(config.getParent())) Files.createDirectories(config.getParent());
                Files.writeString(config, VLCResources.getVersion());
            }, e -> LOGGER.error(IT, "Could not write to configuration file", e));
        } else LOGGER.warn(IT, "VLC detected and match with the wrapped version");

        defaultFactory = ThreadUtil.tryAndReturnNull(
                defaultVar -> WaterMediaAPI.newVLCPlayerFactory(MediaUtil.getArrayStringFromRes("vlc/command-line.json")), e -> LOGGER.error(IT, "Failed to load VLC", e)
        );

        return defaultFactory != null;
    }


    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void compressAndDeleteLogFile(Path logFilePath) {
        File logFile = logFilePath.toFile();
        if (!logFile.exists() || !logFile.isFile()) return;

        // Output for new gZIP
        var date = new Date(System.currentTimeMillis()).toLocalDate().toString();
        String compressedFilePath = logFile.getParent() + "/" + date + ".log.gz";

        int count = 0;
        while (new File(compressedFilePath).exists()) compressedFilePath = logFile.getParent() + "/" + date + "-" + (count++) + ".log.gz";

        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(new FileOutputStream(compressedFilePath)); InputStream inputStream = new FileInputStream(logFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) gzipOutputStream.write(buffer, 0, bytesRead);
        } catch (Exception e) {
            LOGGER.error(IT, "Failed to compress vlc.log");
        }
        logFile.delete();
    }
}