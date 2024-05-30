package me.srrapero720.watermedia.api.config.support;

import me.srrapero720.watermedia.api.config.WaterConfig;
import uk.co.caprica.vlcj.discovery.provider.DiscoveryDirectoryProvider;

public class CustomDirectoryProvider implements DiscoveryDirectoryProvider {
    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public String[] directories() {
        return new String[] {WaterConfig.vlcInstallPath};
    }
}
