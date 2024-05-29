package me.srrapero720.watermedia.tests.bootstrap;

import me.srrapero720.watermedia.WaterMedia;
import me.srrapero720.watermedia.loaders.ILoader;
import org.junit.jupiter.api.Test;

public class BootstrapTests {

    @Test
    public void testBootstrap() throws Exception {
        WaterMedia instance = WaterMedia.prepare(ILoader.DEFAULT);
        instance.start();
    }
}
