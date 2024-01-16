package uk.co.caprica.vlcj;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.co.caprica.vlcj.factory.discovery.provider.CustomDirectoryProvider;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.function.Function;

public class VideoLan4J {
    public static final Logger LOGGER = LogManager.getLogger("VLCJ");
    private static final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    private static Function<Integer, ByteBuffer> byteBufferFunction = integer -> ByteBuffer.allocateDirect(integer).order(ByteOrder.nativeOrder());

    public static void init(Path customDir) { CustomDirectoryProvider.init(customDir); }

    public static void native$checkClassLoader() {
        Thread t = Thread.currentThread();
        if (t.getContextClassLoader() == null) t.setContextClassLoader(contextClassLoader);
    }

    public static void setByteBufferBuilder(Function<Integer, ByteBuffer> builder) {
        byteBufferFunction = builder;
    }

    public static ByteBuffer createByteBuffer(int size) {
        return byteBufferFunction.apply(size);
    }
}