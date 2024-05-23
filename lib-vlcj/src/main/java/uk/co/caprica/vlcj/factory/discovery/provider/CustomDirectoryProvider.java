package uk.co.caprica.vlcj.factory.discovery.provider;

import java.io.File;
import java.nio.file.Path;

public class CustomDirectoryProvider implements DiscoveryDirectoryProvider {
    private static Path rootPath = null;
    public static void init(Path ph) { rootPath = ph; }

    @Override
    public int priority() { return 5; }

    @Override
    public boolean supported() { return true; }

    @Override
    public String[] directories() {
        Path vlc = rootPath != null ? rootPath : new File("").toPath();
        return new String[]{vlc.toAbsolutePath().toString()};
    }
}