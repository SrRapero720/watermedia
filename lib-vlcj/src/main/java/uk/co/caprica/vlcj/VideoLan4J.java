package uk.co.caprica.vlcj;

import uk.co.caprica.vlcj.factory.discovery.provider.CustomDirectoryProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public class VideoLan4J {
    public static final Logger LOGGER = LogManager.getLogger("VideoLan4J");
    public static void init(Path customDir) {
        CustomDirectoryProvider.init(customDir);
    }

    public static void native$checkClassLoader(ClassLoader classLoader) {
        Thread t = Thread.currentThread();
        if (t.getContextClassLoader() == null) t.setContextClassLoader(classLoader);
    }
}