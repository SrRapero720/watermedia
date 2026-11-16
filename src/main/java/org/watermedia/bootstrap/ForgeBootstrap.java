package org.watermedia.bootstrap;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import org.watermedia.WaterMedia;
import org.watermedia.tools.DataTool;

@Mod(WaterMedia.ID)
public class ForgeBootstrap {
    private static final String NAME = "Forge";

    public ForgeBootstrap() {
        DefensiveCheck.run(null);
        try {
            WaterMedia.start(NAME, null, null, FMLLoader.getDist().isClient());
        } catch (final Exception e) {
            throw new RuntimeException(DataTool.errCode(WaterMedia.ERR_FORGE_START) + "Failed to start " + WaterMedia.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
