package me.srrapero720.watermedia.loaders;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.tools.exceptions.IllegalTLauncherException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.io.File;
import java.nio.file.Path;

public class FavricLoader implements PreLaunchEntrypoint, ILoader {
    private static final Marker IT = MarkerManager.getMarker("FabricLoader");

    private final Path tempPath = new File(System.getProperty("java.io.tmpdir")).toPath().toAbsolutePath().resolve("watermedia");
    private final Path processPath = FabricLoader.getInstance().getGameDir();

    @Override
    public void onPreLaunch() {
        try {
            if (tlcheck()) throw new IllegalTLauncherException();

            if (clientSide()) WaterMedia.prepare(this).start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to run " + WaterMedia.NAME + " for FABRIC: " + e.getMessage(), e);
        }
    }

    @Override public String name() { return "Fabric"; }
    @Override public Path processDir() { return processPath; }
    @Override public Path tempDir() { return tempPath; }
    @Override public boolean developerMode() { return FabricLoader.getInstance().isDevelopmentEnvironment(); }
    @Override public boolean clientSide() { return FabricLoader.getInstance().getEnvironmentType().equals(EnvType.CLIENT); }

    @Override
    public boolean tlcheck() {
        // FOLDER VALIDATION - Avoid ATLauncher and SKLauncher (for some reason)
        String f = new File("").toPath().toAbsolutePath().toString().toLowerCase();
        boolean tlauncher = f.contains("tlauncher");
        boolean atlauncher = f.contains("atlauncher");
        boolean sklauncher = f.contains("skcraftlauncher");

        // TLSKINCAPE VALIDATION
        boolean tlskincape = FabricLoader.getInstance().isModLoaded("tlskincape") || FabricLoader.getInstance().isModLoaded("tlauncher_custom_cape_skin");

        return tlskincape || (tlauncher && !atlauncher && !sklauncher);
    }
}
