package org.watermedia.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies the standalone launcher catalog: build versions, SHA-256 pins for every supported platform and the coded
 * failure of a missing catalog; nested libraries shared with the loader metadata that a failed extraction never leaves
 * behind; and downloads that only replace the cache when complete and verified.
 */
@DisplayName("AppBootstrap dependencies")
public class AppBootstrapDependenciesTest {
    private static final String PIN = "[0-9a-f]{64}";

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Properties catalog() throws IOException {
        final Properties catalog = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("/bootstrap.properties")) {
            assertNotNull(source, "The bootstrap must carry its processed catalog");
            catalog.load(source);
        }
        return catalog;
    }

    private static void expect(final List<?> dependencies, final Properties versions, final String artifact,
                               final String key, final String classifier, final boolean optional) throws Exception {
        final String version = versions.getProperty(key + "_version");
        final String name = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        for (final Object dependency: dependencies) {
            if (!name.equals(value(dependency, "name"))) continue;
            assertTrue(dependency.getClass().isRecord(), "a dependency is a record");
            assertEquals(optional, value(dependency, "optional"), name + " optional");
            final String group = artifact.startsWith("log4j") ? "org/apache/logging/log4j"
                    : artifact.startsWith("lwjgl") ? "org/lwjgl"
                    : artifact.equals("gson") ? "com/google/code/gson" : "org/joml";
            assertEquals(URI.create("https://repo1.maven.org/maven2/" + group + "/" + artifact + "/" + version + "/" + name), value(dependency, "source"));
            return;
        }
        fail("Missing dependency: " + name);
    }

    private static Object value(final Object dependency, final String name) throws Exception {
        final Method method = dependency.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(dependency);
    }

    private static void download(final URI source, final Path destination, final String sha256) throws Exception {
        final Method download = Arrays.stream(AppBootstrap.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("download")).findFirst().orElseThrow();
        download.setAccessible(true);
        download.invoke(null, source, destination, sha256, null);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Catalog")
    class Catalog {
        @Test
        @DisplayName("Versions and nested jars match the build and the jarjar metadata")
        void matchesTheBuild() throws Exception {
            final Properties build = new Properties();
            try (final InputStream source = Files.newInputStream(Path.of("gradle.properties"))) { build.load(source); }
            final Properties catalog = catalog();
            for (final String key: List.of("log4j_version", "gson_version", "lwjgl_version", "joml_version")) {
                assertNotNull(build.getProperty(key), key);
                assertEquals(build.getProperty(key), catalog.getProperty(key), key);
            }
            final List<String> paths;
            try (final var reader = new InputStreamReader(AppBootstrap.class.getResourceAsStream("/META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8)) {
                paths = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars").asList().stream()
                        .map(jar -> jar.getAsJsonObject().get("path").getAsString()).toList();
            }
            assertEquals(paths, Arrays.stream(catalog.getProperty("nested").split(",")).map(name -> "META-INF/jarjar/" + name).toList(),
                    "the catalog nests the jars of the jarjar metadata");
        }

        @Test
        @DisplayName("Every supported platform resolves pinned dependencies without downloaded libraries")
        void pinsEveryPlatform() throws Exception {
            final String previousOs = System.getProperty("os.name");
            final String previousArch = System.getProperty("os.arch");
            final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            final URL resources = AppBootstrap.class.getResource("/bootstrap.properties").toURI().resolve(".").toURL();
            final Properties versions = catalog();
            final Set<String> names = new HashSet<>();
            // A FRESH LOADER RESOLVES THE CATALOG WITHOUT ANY LIBRARY OF THE TEST CLASSPATH; os.name AND os.arch PICK THE PLATFORM
            try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes, resources }, ClassLoader.getPlatformClassLoader())) {
                final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
                final Method resolve = bootstrap.getDeclaredMethod("dependencies", Properties.class);
                resolve.setAccessible(true);
                for (final String[] platform: List.of(
                        new String[] { "Windows 10", "amd64", "windows" },
                        new String[] { "Windows 11", "aarch64", "windows-arm64" },
                        new String[] { "Linux", "amd64", "linux" },
                        new String[] { "Linux", "aarch64", "linux-arm64" },
                        new String[] { "Mac OS X", "amd64", "macos" },
                        new String[] { "Mac OS X", "aarch64", "macos-arm64" })) {
                    System.setProperty("os.name", platform[0]);
                    System.setProperty("os.arch", platform[1]);
                    final List<?> dependencies = (List<?>) resolve.invoke(null, versions);
                    // 4 BASE LIBRARIES, 5 LWJGL MODULES WITH THEIR NATIVES, VULKAN AND SHADERC WITH ITS NATIVES; MACOS ADDS THE VULKAN NATIVES
                    assertEquals(platform[2].startsWith("macos") ? 18 : 17, dependencies.size(), "dependencies of " + platform[2]);
                    expect(dependencies, versions, "log4j-api", "log4j", null, false);
                    expect(dependencies, versions, "log4j-core", "log4j", null, false);
                    expect(dependencies, versions, "gson", "gson", null, false);
                    expect(dependencies, versions, "joml", "joml", null, false);
                    for (final String artifact: List.of("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal")) {
                        expect(dependencies, versions, artifact, "lwjgl", null, false);
                        expect(dependencies, versions, artifact, "lwjgl", "natives-" + platform[2], false);
                    }
                    expect(dependencies, versions, "lwjgl-vulkan", "lwjgl", null, true);
                    expect(dependencies, versions, "lwjgl-shaderc", "lwjgl", null, true);
                    expect(dependencies, versions, "lwjgl-shaderc", "lwjgl", "natives-" + platform[2], true);
                    if (platform[2].startsWith("macos"))
                        expect(dependencies, versions, "lwjgl-vulkan", "lwjgl", "natives-" + platform[2], true);
                    for (final Object dependency: dependencies) {
                        final String name = value(dependency, "name").toString();
                        assertFalse(name.startsWith("waterconfig-"), "WaterConfig is nested, never downloaded");
                        assertTrue(String.valueOf(versions.getProperty(name)).matches(PIN), "Missing SHA-256 pin for " + name);
                        names.add(name);
                    }
                }
            } finally {
                System.setProperty("os.name", previousOs);
                System.setProperty("os.arch", previousArch);
            }
            assertEquals(versions.stringPropertyNames().stream().filter(key -> key.endsWith(".jar")).collect(Collectors.toSet()), names,
                    "The catalog must pin exactly the libraries the launcher can download");
        }

        @Test
        @DisplayName("Pins match the same libraries on the test classpath")
        void pinsMatchTheBuildArtifacts() throws Exception {
            final Properties catalog = catalog();
            final Set<String> matched = new HashSet<>();
            for (final String entry: System.getProperty("watermedia.test.classpath", System.getProperty("java.class.path")).split(File.pathSeparator)) {
                final Path path = Path.of(entry);
                final String pin = path.getFileName() == null ? null : catalog.getProperty(path.getFileName().toString());
                if (pin == null) continue;
                assertEquals(pin, IOTool.sha256(path), path.toString());
                matched.add(path.getFileName().toString());
            }
            for (final String name: List.of("log4j-api-" + catalog.getProperty("log4j_version"), "gson-" + catalog.getProperty("gson_version"),
                    "lwjgl-" + catalog.getProperty("lwjgl_version")))
                assertTrue(matched.contains(name + ".jar"), name + ".jar is on the test classpath");
        }

        @Test
        @DisplayName("A missing catalog fails with its coded I/O error")
        void missingCatalogFails() throws Exception {
            final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            // THE CLASSES WITHOUT THE RESOURCES: THE LOADER FINDS NO bootstrap.properties
            try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes }, ClassLoader.getPlatformClassLoader())) {
                final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
                final Method versions = bootstrap.getDeclaredMethod("versions");
                versions.setAccessible(true);
                final var failure = assertThrows(InvocationTargetException.class, () -> versions.invoke(null));
                assertInstanceOf(IOException.class, failure.getCause());
                assertEquals("(wm_err:8002) Failed to read bootstrap.properties due to missing file", failure.getCause().getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Nested libraries")
    class NestedLibraries {
        @Test
        @DisplayName("Bundled WaterConfig loads without external libraries and shares the loader metadata")
        void bundledConfigLoads(@TempDir final Path directory) throws Exception {
            final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            final URL resources = AppBootstrap.class.getResource("/bootstrap.properties").toURI().resolve(".").toURL();
            final JsonObject metadata;
            try (final var reader = new InputStreamReader(AppBootstrap.class.getResourceAsStream("/META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8)) {
                metadata = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars").get(0).getAsJsonObject();
            }
            final String resource = "/" + metadata.get("path").getAsString();
            final String version = metadata.getAsJsonObject("version").get("artifactVersion").getAsString();
            final Properties build = new Properties();
            try (final InputStream source = Files.newInputStream(Path.of("gradle.properties"))) { build.load(source); }
            assertEquals(build.getProperty("waterconfig_range"), metadata.getAsJsonObject("version").get("range").getAsString());
            try (final var reader = new InputStreamReader(AppBootstrap.class.getResourceAsStream("/fabric.mod.json"), StandardCharsets.UTF_8)) {
                final List<String> files = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("jars").asList().stream()
                        .map(JsonElement::getAsJsonObject).map(jar -> jar.get("file").getAsString()).toList();
                assertEquals(List.of(resource.substring(1)), files);
            }
            try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes, resources }, ClassLoader.getPlatformClassLoader())) {
                final Class<?> bootstrap = Class.forName(AppBootstrap.class.getName(), true, loader);
                final Method extract = bootstrap.getDeclaredMethod("extract", String.class, Path.class);
                extract.setAccessible(true);
                final Path first = (Path) extract.invoke(null, resource, directory);
                final Path second = (Path) extract.invoke(null, resource, directory);
                assertNotEquals(first, second, "Concurrent applications must not replace each other's open jars");
                assertEquals(-1, Files.mismatch(first, second));
                try (final InputStream input = loader.getResourceAsStream(resource.substring(1))) {
                    assertNotNull(input, resource + " is bundled");
                    assertArrayEquals(input.readAllBytes(), Files.readAllBytes(first));
                }
                try (final JarFile jar = new JarFile(first.toFile())) {
                    assertEquals("GAMELIBRARY", jar.getManifest().getMainAttributes().getValue("FMLModType"));
                    assertEquals("waterconfig", jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
                    assertNotNull(jar.getEntry("META-INF/services/me.srrapero720.waterconfig.api.ICodec"), "the ICodec services");
                    assertNotNull(jar.getEntry("META-INF/services/me.srrapero720.waterconfig.api.formats.IFormatCodec"), "the IFormatCodec services");
                    try (final var reader = new InputStreamReader(jar.getInputStream(jar.getJarEntry("fabric.mod.json")), StandardCharsets.UTF_8)) {
                        final JsonObject fabric = JsonParser.parseReader(reader).getAsJsonObject();
                        assertEquals("com_github_srrapero720_waterconfig", fabric.get("id").getAsString());
                        assertEquals(version, fabric.get("version").getAsString());
                    }
                }
                // THE EXTRACTED JAR ALONE DEFINES WATERCONFIG: IT NEEDS NO LIBRARY OUTSIDE ITSELF
                try (final URLClassLoader config = new URLClassLoader(new URL[] { first.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
                    assertSame(config, Class.forName("me.srrapero720.waterconfig.WaterConfig", true, config).getClassLoader());
                }
            }
        }

        @Test
        @DisplayName("A missing or unreadable bundled library fails and leaves no extracted file")
        void failedExtractionsLeaveNoFiles(@TempDir final Path directory) throws Exception {
            final Method extract = AppBootstrap.class.getDeclaredMethod("extract", String.class, Path.class);
            extract.setAccessible(true);
            final var missing = assertThrows(InvocationTargetException.class,
                    () -> extract.invoke(null, "/META-INF/jarjar/missing.jar", directory));
            assertInstanceOf(IOException.class, missing.getCause());
            assertEquals("(wm_err:8005) Failed to extract the bundled library due to missing resource: /META-INF/jarjar/missing.jar",
                    missing.getCause().getMessage());
            // THE STREAM OF broken.jar FAILS AFTER ITS FIRST CHUNK, SO THE COPY BREAKS ONCE THE TEMPORARY JAR EXISTS
            final URL classes = AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            try (final URLClassLoader loader = new URLClassLoader(new URL[] { classes }, ClassLoader.getPlatformClassLoader()) {
                @Override
                public InputStream getResourceAsStream(final String name) {
                    if (!name.equals("META-INF/jarjar/broken.jar")) return super.getResourceAsStream(name);
                    return new InputStream() {
                        private boolean sent;
                        @Override public int read() throws IOException { throw new IOException("Broken bundled library"); }
                        @Override public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                            if (this.sent) throw new IOException("Broken bundled library");
                            this.sent = true;
                            return Math.min(length, 16);
                        }
                    };
                }
            }) {
                final Method broken = Class.forName(AppBootstrap.class.getName(), true, loader).getDeclaredMethod("extract", String.class, Path.class);
                broken.setAccessible(true);
                final var failure = assertThrows(InvocationTargetException.class,
                        () -> broken.invoke(null, "/META-INF/jarjar/broken.jar", directory));
                assertInstanceOf(IOException.class, failure.getCause());
                assertEquals("Broken bundled library", failure.getCause().getMessage());
            }
            try (final var files = Files.list(directory)) { assertEquals(List.of(), files.toList(), "no extracted file is left"); }
        }
    }

    @Nested
    @DisplayName("Downloads")
    class Downloads {
        private static final byte[] PAYLOAD = "complete dependency".getBytes(StandardCharsets.UTF_8);
        private static final String PAYLOAD_PIN = HexFormat.of().formatHex(DataTool.sha256(PAYLOAD));

        @Test
        @DisplayName("Verified downloads replace the cache with or without a content length")
        void verifiedDownloadsReplaceTheCache(@TempDir final Path directory) throws Exception {
            for (final boolean chunked: List.of(false, true)) {
                final Path destination = Files.writeString(directory.resolve("library.jar"), "previous");
                try (final LocalHttp server = LocalHttp.start("/library.jar", exchange -> {
                    try (exchange) {
                        exchange.sendResponseHeaders(200, chunked ? 0 : PAYLOAD.length);
                        exchange.getResponseBody().write(PAYLOAD);
                    }
                })) {
                    download(server.uri("/library.jar"), destination, PAYLOAD_PIN);
                }
                assertArrayEquals(PAYLOAD, Files.readAllBytes(destination));
                try (final var files = Files.list(directory)) { assertEquals(List.of(destination), files.toList()); }
            }
        }

        @Test
        @DisplayName("Tampered downloads keep the cache and remove partial files")
        void tamperedDownloadsAreRejected(@TempDir final Path directory) throws Exception {
            final Path destination = Files.writeString(directory.resolve("library.jar"), "previous");
            try (final LocalHttp server = LocalHttp.start("/library.jar", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, PAYLOAD.length);
                    exchange.getResponseBody().write(PAYLOAD);
                }
            })) {
                final String pin = HexFormat.of().formatHex(DataTool.sha256("expected dependency".getBytes(StandardCharsets.UTF_8)));
                final var failure = assertThrows(InvocationTargetException.class, () -> download(server.uri("/library.jar"), destination, pin));
                assertInstanceOf(IOException.class, failure.getCause());
                // THE DIGEST JUDGES THE RANDOMLY NAMED PARTIAL FILE, library.jar.<random>.part
                assertTrue(failure.getCause().getMessage().startsWith("(wm_err:0149) Failed to verify the file due to SHA-256 mismatch: library.jar."),
                        failure.getCause().getMessage());
            }
            assertEquals("previous", Files.readString(destination));
            try (final var files = Files.list(directory)) { assertEquals(List.of(destination), files.toList()); }
        }

        @Test
        @DisplayName("Incomplete downloads fail on their length, keep the cache and remove partial files")
        void incompleteDownloadsKeepTheCache(@TempDir final Path directory) throws Exception {
            final Path destination = Files.writeString(directory.resolve("library.jar"), "previous");
            // 3 BYTES OF THE 100 THE HEADERS ANNOUNCE, THEN THE CONNECTION CLOSES
            try (final LocalHttp server = LocalHttp.start("/library.jar", exchange -> {
                try {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(new byte[] { 1, 2, 3 });
                } finally {
                    exchange.close();
                }
            })) {
                final var failure = assertThrows(InvocationTargetException.class, () -> download(server.uri("/library.jar"), destination, PAYLOAD_PIN));
                assertInstanceOf(IOException.class, failure.getCause());
                assertEquals("(wm_err:8008) Failed to download library.jar due to incomplete data: 3 of 100 bytes", failure.getCause().getMessage());
            }
            assertEquals("previous", Files.readString(destination));
            try (final var files = Files.list(directory)) { assertEquals(List.of(destination), files.toList()); }
        }

        @Test
        @DisplayName("Rejected downloads create no cache entries")
        void rejectedDownloadsCreateNothing(@TempDir final Path directory) throws Exception {
            try (final LocalHttp server = LocalHttp.start("/missing.jar", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(404, -1); }
            })) {
                final var failure = assertThrows(InvocationTargetException.class,
                        () -> download(server.uri("/missing.jar"), directory.resolve("missing.jar"), PAYLOAD_PIN));
                assertInstanceOf(IOException.class, failure.getCause());
            }
            try (final var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
    }
}
