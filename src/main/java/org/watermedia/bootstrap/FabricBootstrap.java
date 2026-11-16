package org.watermedia.bootstrap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.watermedia.WaterMedia;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.tools.DataTool;

public class FabricBootstrap implements ModInitializer {
    private static final String NAME = "Fabric";

    @Override
    public void onInitialize() {
        final FabricLoader loader = FabricLoader.getInstance();
        DefensiveCheck.run(loader.getGameDir());
        final boolean client = loader.getEnvironmentType() == EnvType.CLIENT;
        // FABRIC CANNOT SCOPE A DEPENDENCY TO ONE SIDE; THE CONSTANTS INLINE, SO SERVERS NEVER LOAD THE BINARIES CLASS
        if (client && !loader.isModLoaded(WaterMediaBinaries.ID))
            throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_FABRIC_BINARIES_MISSING) + "Failed to start " + WaterMedia.NAME + ", " + WaterMediaBinaries.NAME + " is required on Minecraft clients");
        try {
            WaterMedia.start(NAME, null, loader.getGameDir(), client);
        } catch (final Exception e) {
            throw new RuntimeException(DataTool.errCode(WaterMedia.ERR_FABRIC_START) + "Failed to start " + WaterMedia.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
