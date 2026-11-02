package org.watermedia;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.platform.PlatformAPI;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies client and server bootstrap without the optional binaries module. */
public final class WithoutBinariesProbe {
    private WithoutBinariesProbe() {}

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 1 || !("server-no-binaries".equals(arguments[0]) || "client-no-binaries".equals(arguments[0])))
            throw new IllegalArgumentException("Unknown bootstrap scenario");
        final boolean client = arguments[0].startsWith("client");
        final var classpath = new ArrayList<URL>();
        for (final String entry: System.getProperty("java.class.path").split(File.pathSeparator))
            classpath.add(Path.of(entry).toAbsolutePath().toUri().toURL());
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (final URLClassLoader isolated = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.watermedia.binaries.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
            @Override
            public URL getResource(final String name) {
                return name.startsWith("org/watermedia/binaries/") ? null : super.getResource(name);
            }
        }) {
            Thread.currentThread().setContextClassLoader(isolated);
            try {
                Class.forName("org.watermedia.binaries.WaterMediaBinaries", false, isolated);
                throw new AssertionError("Binaries remained visible to the isolated server");
            } catch (final ClassNotFoundException expected) {}
            final Class<?> config = Class.forName(WaterMediaConfig.class.getName(), true, isolated);
            final Object network = config.getField("network").get(null);
            // OFF IS THE FIRST CONSTANT OF THE ISOLATED Server ENUM
            final Object[] servers = Class.forName(WaterMediaConfig.Network.Server.class.getName(), true, isolated).getEnumConstants();
            network.getClass().getField("server").set(network, servers[0]);
            final Class<?> waterMedia = Class.forName(WaterMedia.class.getName(), true, isolated);
            final Path root = Path.of("").toAbsolutePath();
            waterMedia.getMethod("start", String.class, Path.class, Path.class, boolean.class)
                    .invoke(null, "WITHOUT_BINARIES", root.resolve("temp"), root, client);
            if (!(boolean) waterMedia.getMethod("loaded").invoke(null)) throw new AssertionError("Startup failed without binaries");
            // THE MISSING MODULE IS NEVER CREATED: BOTH SIDES WALK THE OTHER FOUR
            final int steps = (int) waterMedia.getMethod("steps").invoke(null);
            if (steps != 4 || (int) waterMedia.getMethod("step").invoke(null) != steps)
                throw new AssertionError("Module count did not match the installed set: " + steps);
            // THE PLATFORM REGISTRY MUST ENLIST WITHOUT LINKING THE yt-dlp HANDLERS
            final Class<?> platforms = Class.forName(PlatformAPI.class.getName(), true, isolated);
            if (((List<?>) platforms.getMethod("platforms").invoke(null)).isEmpty())
                throw new AssertionError("Built-in platforms were not enlisted without binaries");
            if (client) {
                final Class<?> codecs = Class.forName(CodecsAPI.class.getName(), true, isolated);
                if ((boolean) codecs.getMethod("ffmpegLoaded").invoke(null) || (boolean) codecs.getMethod("ffmpegError").invoke(null))
                    throw new AssertionError("Absent binaries must disable video without a native initialization error");
                final ByteBuffer image = ByteBuffer.wrap("P6\n1 1\n255\nABC".getBytes(StandardCharsets.US_ASCII));
                final Object reader = codecs.getMethod("reader", ByteBuffer.class).invoke(null, image);
                try (final AutoCloseable closeable = (AutoCloseable) reader) {
                    final Object decoded = reader.getClass().getMethod("readAll").invoke(reader);
                    if ((int) decoded.getClass().getMethod("width").invoke(decoded) != 1)
                        throw new AssertionError("Image decoding failed without binaries");
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        System.out.println("PROBE_OK " + arguments[0]);
    }
}
