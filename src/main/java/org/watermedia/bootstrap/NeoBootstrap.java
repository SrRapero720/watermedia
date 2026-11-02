package org.watermedia.bootstrap;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import org.watermedia.WaterMedia;

@Mod(WaterMedia.ID)
public class NeoBootstrap {
    private static final String NAME = "NeoForge";

    public NeoBootstrap() {
        DefensiveCheck.run(null);
        try {
            WaterMedia.start(NAME, null, null, clientSide());
        } catch (final Exception e) {
            throw new RuntimeException("Failed to start " + WaterMedia.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }

    // FML 10+ (NEOFORGE 21.11+ AND 26.x) EXPOSES getDist() ON THE getCurrent() INSTANCE; FML <= 9
    // (MC 1.21.1 SHIPS FML 4.x) ONLY HAS THE STATIC getDist(), REACHED THROUGH REFLECTION
    private static boolean clientSide() {
        try {
            return FMLLoader.getCurrent().getDist().isClient();
        } catch (final NoSuchMethodError e) {
            try {
                return ((Dist) FMLLoader.class.getMethod("getDist").invoke(null)).isClient();
            } catch (final ReflectiveOperationException ex) {
                throw new IllegalStateException("Cannot resolve NeoForge dist", ex);
            }
        }
    }
}
