package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;

import java.nio.file.Path;

public interface FabricLoader {
    static FabricLoader getInstance() {
        throw new UnsupportedOperationException("Stub!");
    }

    boolean isModLoaded(final String id);
    EnvType getEnvironmentType();
    Path getGameDir();
}
