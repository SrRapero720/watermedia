package org.watermedia.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.Manifest;

/** Platform detection, bounded reads, atomic installs, digests and bundled resources. */
public final class IOTool {
    public static final int BUFFER_SIZE = 64 * 1_024;
    private static final int MAX_PREALLOCATED_BYTES = 4 * 1_024 * 1_024; // FIRST BUFFER readLimited SIZES FROM A DECLARED LENGTH
    private static final String SHADERS = "assets/watermedia/shaders/"; // THE BUNDLED GLSL, INCLUDES RESOLVE AGAINST IT

    private IOTool() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    // NORMALISED OS TOKEN ("windows"/"macos"/"linux"), OR null IF UNSUPPORTED; platformClassifier NAMES THE ffmpeg
    // ZIPS WHILE platform() RESOLVES THE PER-OS/ARCH ASSETS OF DOWNLOADED NATIVE BINARIES
    public static String os() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        return null;
    }

    /** Stable operating-system and CPU identifier, or unsupported when either is unknown. */
    public static String platform() {
        final String system = os();
        final String cpu = arch();
        return system == null || cpu == null ? "unsupported" : system + "-" + cpu;
    }

    public static String platformClassifier() {
        final String system = os();
        final String cpu = arch();
        return system == null || cpu == null ? "unsupported" : system + (cpu.equals("aarch64") ? "-arm64" : "");
    }

    /** Whether {@code uri} uses the http or https scheme, in any case. */
    public static boolean web(final URI uri) {
        final String scheme = uri == null ? null : uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** Closes {@code closeable} when non-null, swallowing any failure. */
    public static void closeQuietly(final AutoCloseable closeable) {
        if (closeable == null) return;
        try { closeable.close(); }
        catch (final Exception ignored) {}
    }

    /** Reads {@code in} to its end, failing past {@code maxBytes}; {@code expectedBytes} (or -1) only sizes the first buffer, up to 4 MiB, and a positive {@code timeoutMs} bounds the whole read. */
    public static byte[] readLimited(final InputStream in, final long maxBytes, final long expectedBytes, final long timeoutMs) throws IOException {
        // A HOSTILE Content-Length MUST NOT DRIVE THE ALLOCATION: THE BUFFER STARTS BOUNDED AND GROWS WITH REAL BYTES
        final long initial = Math.min(expectedBytes > 0L ? expectedBytes : BUFFER_SIZE, Math.min(maxBytes, MAX_PREALLOCATED_BYTES));
        final ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0L, initial));
        final byte[] buffer = new byte[BUFFER_SIZE];
        final long start = System.nanoTime();
        final long timeoutNs = timeoutMs > 0L ? timeoutMs * 1_000_000L : 0L;
        long total = 0L;
        while (true) {
            // SOCKET READS ARE NOT INTERRUPTIBLE, SO A CANCELLED CALLER (A STOPPED PLAYER) IS HONOURED BETWEEN CHUNKS
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Read cancelled after " + total + " bytes");
            // EACH READ IS CAPPED BY THE SOCKET TIMEOUT, BUT A TRICKLING SOURCE NEEDS A TOTAL ONE
            if (timeoutNs > 0L && System.nanoTime() - start > timeoutNs)
                throw new IOException("Failed to read input: exceeded the " + timeoutMs + " ms deadline after " + total + " bytes");
            final int read = in.read(buffer);
            if (read < 0) break;
            total += read;
            if (total > maxBytes) throw new IOException("Failed to read input: exceeds limit (" + total + " > " + maxBytes + " bytes)");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Publishes an installed direct child through an atomically replaced pointer. */
    public static void publishGeneration(final Path directory, final Path installation) throws IOException {
        final Path base = directory.toAbsolutePath().normalize();
        final Path child = installation.toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || child.getFileName() == null)
            throw new IOException("Installation storage is missing or is a symbolic link: " + base);
        final String name = child.getFileName().toString();
        if (!base.equals(child.getParent()) || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installation is not a direct, regular child of " + base);
        final Path pointer = Files.createTempFile(base, ".current-", ".tmp");
        try {
            Files.writeString(pointer, name, StandardCharsets.UTF_8);
            Files.move(pointer, base.resolve("current"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pointer);
        }
    }

    /** Resolves a direct installed generation without following a pointer or target symlink. */
    public static Path currentGeneration(final Path directory) throws IOException {
        final Path base = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null;
        final Path pointer = base.resolve("current");
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) return null;
        final String name = Files.readString(pointer, StandardCharsets.UTF_8);
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) return null;
        final Path child = base.resolve(name);
        return Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) ? child : null;
    }

    /** Deletes a tree without traversing symbolic links. */
    public static void deleteTree(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (final var paths = Files.walk(directory)) {
            for (final Path path: paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    /** Requires a regular executable file and sets its owner execute bit outside Windows. */
    public static void makeExecutable(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Executable is missing or is a symbolic link: " + file);
        if (!"windows".equals(os()) && !file.toFile().setExecutable(true, true))
            throw new IOException("Cannot grant executable permission: " + file);
    }

    // LOWERCASE HEX SHA-256 OF file; SHA-256 IS MANDATED BY THE JVM SPEC, SO ABSENCE MEANS A BROKEN RUNTIME
    public static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Validates and normalizes a SHA-256 digest from a manifest or release index. */
    public static String requireSha256(final String value) throws IOException {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw new IOException("Missing or invalid SHA-256 digest");
        return value.toLowerCase(Locale.ROOT);
    }

    /** Verifies a regular file without following links or modifying it after a mismatch. */
    public static void verifySha256(final Path file, final String expected) throws IOException {
        final String digest = requireSha256(expected);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !sha256(file).equals(digest))
            throw new IOException("File SHA-256 mismatch: " + file.getFileName());
    }

    /** Returns the Implementation-Version of the jar that ships WaterMedia, or "unknown" outside a jar. */
    public static String jarVersion() { return jarVersion(IOTool.class); }

    /** Returns the Implementation-Version of the jar that loaded {@code anchor}, or "unknown" outside a jar. */
    public static String jarVersion(final Class<?> anchor) {
        // THE MANIFEST OF THE JAR THAT LOADED THE CLASS, NOT THE FIRST ONE ON THE CLASSPATH (E.G. LOG4J'S);
        // A CLASSES-DIR RUN (IDE) SHIPS NO MANIFEST AND REPORTS AN UNKNOWN VERSION RATHER THAN A FAKE ONE
        final String cls = anchor.getName().replace('.', '/') + ".class";
        final URL url = anchor.getClassLoader().getResource(cls);
        if (url != null) {
            final String base = url.toString();
            try (final var in = URI.create(base.substring(0, base.length() - cls.length()) + "META-INF/MANIFEST.MF").toURL().openStream()) {
                final String version = new Manifest(in).getMainAttributes().getValue("Implementation-Version");
                if (version != null) return version;
            } catch (final Exception ignored) {}
        }
        return "unknown";
    }

    /** Reads a bundled UTF-8 resource; a missing one throws {@link FileNotFoundException}. */
    public static String jarRead(final String path) throws IOException {
        try (final InputStream in = jarOpenFile(path)) {
            if (in == null) throw new FileNotFoundException("Bundled resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Opens a bundled resource by its path inside the jar, or null when there is none. */
    public static InputStream jarOpenFile(final String name) {
        // A CLASS LOOKUP REACHES THE MODULE'S OWN PACKAGES ON A MODULE-AWARE LOADER (FORGE), WHERE THE CLASS LOADER'S DOES NOT
        return IOTool.class.getResourceAsStream(name.startsWith("/") ? name : "/" + name);
    }

    /** Reads a bundled shader by its path under the shaders folder, splicing each {@code #include "file"} line with that file of the same folder. */
    public static String shader(final String name) throws IOException {
        final StringBuilder source = new StringBuilder();
        for (final String line: jarRead(SHADERS + name).lines().toList()) {
            final String directive = line.strip();
            if (!directive.startsWith("#include")) {
                source.append(line).append('\n');
                continue;
            }
            final int open = directive.indexOf('"');
            final int close = directive.lastIndexOf('"');
            if (open < 0 || close <= open) throw new IOException("Malformed include in shader " + name + ": " + directive);
            source.append(shader(directive.substring(open + 1, close)));
        }
        return source.toString();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // NORMALISED ARCH TOKEN ("x86_64"/"aarch64"), OR null IF UNSUPPORTED
    private static String arch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }
}
