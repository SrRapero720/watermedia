package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/** Verifies {@link IOTool}'s platform identification, verified native files, bounded stream reads and resource helpers. */
@DisplayName("IOTool")
public class IOToolTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Platform")
    class Platform {

        @Test
        @DisplayName("os() maps Darwin and Mac OS X to macos and leaves unsupported kernels unclassified")
        void nativeOperatingSystemsDoNotMisclassifyDarwinOrUnsupportedKernels() {
            final String previous = System.getProperty("os.name");
            try {
                for (final String name: new String[] { "Darwin", "Mac OS X" }) {
                    System.setProperty("os.name", name);
                    assertEquals("macos", IOTool.os());
                }
                System.setProperty("os.name", "Windows 10");
                assertEquals("windows", IOTool.os());
                System.setProperty("os.name", "Linux");
                assertEquals("linux", IOTool.os());
                for (final String name: new String[] { "AIX", "FreeBSD", "Unix", "unknown" }) {
                    System.setProperty("os.name", name);
                    assertNull(IOTool.os());
                }
            } finally {
                if (previous == null) System.clearProperty("os.name");
                else System.setProperty("os.name", previous);
            }
        }
    }

    @Nested
    @DisplayName("Native files")
    class NativeFiles {

        @Test
        @DisplayName("hash verification accepts any case and keeps a mismatched file for its owner")
        void hashVerificationKeepsMismatchedFilesForTheirOwner(@TempDir final Path directory) throws IOException {
            final Path file = Files.writeString(directory.resolve("binary"), "original");
            final String hash = IOTool.sha256(file);
            IOTool.verifySha256(file, hash.toUpperCase(Locale.ROOT));
            assertThrows(IOException.class, () -> IOTool.verifySha256(file, "0".repeat(64)));
            assertEquals("original", Files.readString(file));
            assertThrows(IOException.class, () -> IOTool.verifySha256(file, "invalid"));
        }

        @Test
        @DisplayName("native file checks reject symbolic links")
        void nativeFileChecksRejectSymbolicLinks(@TempDir final Path directory) throws IOException {
            final Path file = Files.writeString(directory.resolve("binary"), "original");
            final Path link = directory.resolve("linked");
            try { Files.createSymbolicLink(link, file); }
            catch (final FileSystemException | UnsupportedOperationException | SecurityException unavailable) {
                abort("Symbolic links unavailable: " + unavailable.getMessage());
            }
            assertThrows(IOException.class, () -> IOTool.verifySha256(link, IOTool.sha256(file)));
            assertThrows(IOException.class, () -> IOTool.makeExecutable(link));
            assertEquals("original", Files.readString(file));
        }

        @Test
        @DisplayName("generation pointers stay inside the storage directory")
        void generationPointersStayInsideTheStorageDirectory(@TempDir final Path directory) throws IOException {
            final Path generation = Files.createDirectory(directory.resolve("fresh"));
            IOTool.publishGeneration(directory, generation);
            assertEquals(generation, IOTool.currentGeneration(directory));
            Files.writeString(directory.resolve("current"), "../outside");
            assertNull(IOTool.currentGeneration(directory));
            final Path outside = Files.createDirectory(directory.getParent().resolve("outside-" + System.nanoTime()));
            try {
                assertThrows(IOException.class, () -> IOTool.publishGeneration(directory, outside));
            } finally {
                Files.delete(outside);
            }
        }
    }

    @Nested
    @DisplayName("Streams")
    class Streams {

        @Test
        @DisplayName("readLimited returns the body when under the cap and throws once it exceeds the cap")
        void readLimitedCap() throws IOException {
            final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 100, -1L, 0L));
            assertThrows(IOException.class, () -> IOTool.readLimited(new ByteArrayInputStream(new byte[100]), 10, -1L, 0L));
        }

        @Test
        @DisplayName("readLimited never preallocates a hostile declared length")
        void readLimitedBoundsPreallocation() throws IOException {
            final byte[] data = "tiny".getBytes(StandardCharsets.UTF_8);
            // A 2 GiB DECLARATION WOULD EXHAUST THE TEST HEAP IF IT SIZED THE FIRST BUFFER
            assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), Long.MAX_VALUE, Integer.MAX_VALUE, 0L));
            assertArrayEquals(data, IOTool.readLimited(new ByteArrayInputStream(data), 16, Long.MAX_VALUE, 0L));
        }

        @Test
        @DisplayName("readLimited stops a slow stream at its total deadline")
        void readLimitedDeadline() throws IOException {
            // EACH READ RETURNS ONE BYTE AFTER 20 MS: NEVER STUCK, BUT 200 BYTES WOULD TAKE FOUR SECONDS
            final class Trickle extends InputStream {
                private int left = 200;

                @Override
                public int read() throws IOException {
                    if (this.left-- <= 0) return -1;
                    try { Thread.sleep(20); }
                    catch (final InterruptedException e) { throw new InterruptedIOException(); }
                    return 'x';
                }

                @Override
                public int read(final byte[] b, final int off, final int len) throws IOException {
                    final int value = this.read();
                    if (value < 0) return -1;
                    b[off] = (byte) value;
                    return 1;
                }
            }
            try (final Trickle slow = new Trickle()) {
                final long start = System.nanoTime();
                final IOException failure = assertThrows(IOException.class, () -> IOTool.readLimited(slow, 1_024, -1L, 300L));
                final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                assertTrue(failure.getMessage().contains("deadline"), failure.getMessage());
                assertTrue(elapsedMs >= 300L, "Stopped before the deadline: " + elapsedMs + " ms");
                assertTrue(slow.left > 0, "The whole stream was read instead of stopping at the deadline");
            }
        }

        @Test
        @DisplayName("closeQuietly closes, swallows failures and accepts null")
        void closeQuietly() {
            final boolean[] closed = new boolean[1];
            IOTool.closeQuietly(() -> closed[0] = true);
            assertTrue(closed[0]);
            IOTool.closeQuietly(() -> { throw new IOException("ignored"); });
            IOTool.closeQuietly(null);
        }
    }

    @Nested
    @DisplayName("Resources")
    class Resources {

        @Test
        @DisplayName("jarRead throws for a missing resource and web accepts only http and https")
        void jarReadAndWeb() throws IOException {
            assertThrows(FileNotFoundException.class, () -> IOTool.jarRead("missing/" + System.nanoTime() + ".json"));
            assertFalse(IOTool.jarRead("org/watermedia/tools/IOTool.class").isEmpty());
            assertTrue(IOTool.web(URI.create("http://a.test/")));
            assertTrue(IOTool.web(URI.create("HTTPS://a.test/")));
            assertFalse(IOTool.web(URI.create("file:///x")));
            assertFalse(IOTool.web(URI.create("jar:file:///x.zip!/y")));
            assertFalse(IOTool.web(URI.create("relative")));
            assertFalse(IOTool.web(null));
        }

        @Test
        @DisplayName("shader splices the colorimetry include into every conversion shader and reports a missing file")
        void shaderIncludes() throws IOException {
            for (final String name: List.of("opengl/gray.frag", "opengl/nv.frag", "opengl/yuv3.frag", "opengl/yuva.frag", "opengl/yuyv.frag", "vulkan/convert.comp")) {
                final String source = IOTool.shader(name);
                assertTrue(source.startsWith("#version "), name + " must open with its version directive");
                assertTrue(source.contains("vec3 ycbcr(float y, float cb, float cr, vec4 range, vec4 m)") && source.contains("vec3 display(vec3 rgb, vec4 d)"),
                        name + " must carry the colorimetry functions");
                assertFalse(source.contains("#include"), name + " must leave no include behind");
            }
            // A SHADER WITHOUT INCLUDES COMES BACK LINE FOR LINE
            assertEquals(IOTool.jarRead("assets/watermedia/shaders/vulkan/ycbcr.comp").lines().toList(), IOTool.shader("vulkan/ycbcr.comp").lines().toList());
            for (final String api: List.of("opengl", "vulkan"))
                for (final String stage: List.of("ui.vert", "ui.frag", "sdf.vert", "sdf.frag"))
                    assertTrue(IOTool.shader(api + "/" + stage).startsWith("#version "), api + "/" + stage + " must be bundled");
            assertThrows(FileNotFoundException.class, () -> IOTool.shader("opengl/missing.frag"));
        }

        @Test
        @DisplayName("jarVersion reads the manifest of the jar that loaded the anchor class")
        void jarVersionPerAnchor() {
            assertEquals("unknown", IOTool.jarVersion(IOToolTest.class), "a classes-dir run ships no manifest");
            assertNotEquals("unknown", IOTool.jarVersion(Test.class), "a class from a jar reports that jar's version");
        }
    }
}
