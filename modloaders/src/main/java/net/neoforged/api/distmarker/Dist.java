package net.neoforged.api.distmarker;

public enum Dist {
    CLIENT,
    DEDICATED_SERVER;

    public boolean isClient() {
        throw new UnsupportedOperationException("Stub!");
    }
}
