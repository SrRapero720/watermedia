package net.neoforged.fml.loading;

import net.neoforged.api.distmarker.Dist;

// MIRRORS THE INSTANCE-BASED FMLLoader OF FANCYMODLOADER 10 (NEOFORGE 21.11+ AND 26.x); FML <= 9
// (UP TO MC 1.21.1 ON FML 4.x) ONLY EXPOSES THE STATIC getDist(), WHICH THE BOOTSTRAP REACHES VIA REFLECTION
public class FMLLoader {
    public static FMLLoader getCurrent() {
        throw new UnsupportedOperationException("Stub!");
    }

    public Dist getDist() {
        throw new UnsupportedOperationException("Stub!");
    }
}
