package uk.co.caprica.vlcj;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.co.caprica.vlcj.factory.discovery.provider.CustomDirectoryProvider;

import java.nio.file.Path;

public class VideoLan4J {
    public static final Logger LOGGER = LogManager.getLogger("VLCJ");
    private static final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

    public static void init(Path customDir) {
        CustomDirectoryProvider.init(customDir);
    }

    public static void native$checkClassLoader() {
        Thread t = Thread.currentThread();
        if (t.getContextClassLoader() == null) t.setContextClassLoader(contextClassLoader);
    }
}