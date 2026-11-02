package org.watermedia.bootstrap;

import org.watermedia.bootstrap.app.AppConfig;
import org.watermedia.bootstrap.app.WaterMediaApp;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.tools.IOTool;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Prepares the standalone application's libraries and supervises its JVM. */
public final class AppBootstrap {
    public static final int RELAUNCH_EXIT = 42;
    /** System property that carries the render engine, {@code opengl} or {@code vulkan}, from the launcher to the app. */
    public static final String ENGINE_PROP = "watermedia.engine";
    private static final String APP_FLAG = "watermedia.app";
    private static final String MAVEN = "https://repo1.maven.org/maven2/";
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1_024 * 1_024; // NO PINNED LIBRARY COMES CLOSE
    // SHARED BY EVERY USER ON LINUX (/tmp): PINS REJECT PLANTED JARS, BUT THE FOLDER OWNER COULD SWAP A VERIFIED JAR
    // BEFORE THE CHILD JVM OPENS IT; A PER-USER CACHE DIRECTORY WOULD CLOSE THAT WINDOW
    private static final Path LIBS_DIR = Path.of(System.getProperty("java.io.tmpdir"), "watermedia", "libs");
    // THE APP'S WATERCONFIG WRITES config/ INSIDE THE WORKING DIRECTORY THIS JVM PASSES ON; THE AppConfig KEYS ARE
    // COMPILE-TIME CONSTANTS, SO READING THEM HERE NEVER LOADS WATERCONFIG
    private static final Path PREFERENCES = Path.of("config", AppConfig.ID + ".properties");
    private BootstrapWindow window;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void main(final String... args) {
        final AppBootstrap bootstrap = new AppBootstrap();
        final boolean application = System.getProperty(APP_FLAG) != null;
        while (true) {
            try {
                if (application) {
                    WaterMediaApp.start(() -> {
                        WaterMediaApp.log("Searching for extensions...");
                        ServiceLoader.load(Extension.class).forEach(extension -> {
                            WaterMediaApp.log("Loading extension: " + extension.name());
                            extension.load();
                        });
                    });
                    return;
                }
                final Set<Path> jars = bootstrap.prepare();
                if (bootstrap.window != null) {
                    EventQueue.invokeAndWait(bootstrap.window::dispose);
                    bootstrap.window = null;
                }
                final int code = relaunch(jars, args, bootstrap.engine());
                if (code != RELAUNCH_EXIT) System.exit(code);
            } catch (final Throwable failure) {
                failure.printStackTrace();
                if (GraphicsEnvironment.isHeadless()) System.exit(1);
                try {
                    bootstrap.window().error(failure);
                    // A FAILED APP MAY HAVE PARTIALLY INITIALIZED NATIVES; RETRY IN A FRESH JVM
                    if (application) System.exit(RELAUNCH_EXIT);
                } catch (final Exception displayFailure) {
                    displayFailure.printStackTrace();
                    System.exit(1);
                }
            }
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private Set<Path> prepare() throws Exception {
        final Set<Path> jars = new LinkedHashSet<>();
        // OUR RESOURCES MUST WIN OVER IDENTICALLY NAMED RESOURCES IN EXTENSION JARS
        jars.add(Path.of(AppBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        Files.createDirectories(LIBS_DIR);
        final Properties versions = versions();
        final String nested = versions.getProperty("nested");
        if (nested == null) throw new IOException("Missing nested libraries in bootstrap.properties");
        for (final String name: nested.split(",")) {
            this.log("Extracting " + name);
            jars.add(extract("/META-INF/jarjar/" + name, LIBS_DIR));
        }
        for (final Dependency dependency: dependencies(versions)) {
            final Path jar = LIBS_DIR.resolve(dependency.name());
            final String sha256 = versions.getProperty(dependency.name());
            if (sha256 == null) throw new IOException("Missing SHA-256 pin for " + dependency.name());
            // THE SHARED CACHE IS UNTRUSTED: A JAR JOINS THE CLASSPATH ONLY WHILE IT MATCHES ITS BUILD PIN
            if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !IOTool.sha256(jar).equals(sha256)) {
                if (!GraphicsEnvironment.isHeadless()) this.window();
                this.log((Files.exists(jar, LinkOption.NOFOLLOW_LINKS) ? "Replacing unverified " : "Downloading ") + dependency.name());
                try {
                    download(dependency.source(), jar, sha256, this.window);
                } catch (final IOException failure) {
                    if (!dependency.optional()) throw failure;
                    this.log("Optional library unavailable: " + dependency.name() + " (" + failure.getMessage() + ")");
                    continue;
                }
            }
            jars.add(jar);
        }
        final File[] files = Path.of("").toAbsolutePath().toFile().listFiles();
        boolean binaries = AppBootstrap.class.getResource("/org/watermedia/binaries/WaterMediaBinaries.class") != null;
        if (files != null) {
            for (final File file: files) {
                final String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && name.endsWith(".jar") && (name.startsWith("watermedia_") || name.startsWith("wm_")
                        || name.startsWith("waterm_") || name.startsWith("wmedia_"))) {
                    jars.add(file.toPath());
                    binaries |= name.startsWith("watermedia_binaries");
                    this.log("Extension: " + file.getName());
                }
            }
        }
        if (!binaries) this.log("WaterMedia Binaries is not installed; video decoding is unavailable.");
        return jars;
    }

    // THE -D OVERRIDE WINS FOR ONE LAUNCH, THEN THE SAVED ENGINE, THEN VULKAN; THE FILE IS ONLY READ, THE APP OWNS IT
    private String engine() {
        final String override = engine(System.getProperty(ENGINE_PROP));
        if (override != null) return override;
        final Properties preferences = new Properties();
        try (final InputStream input = Files.newInputStream(PREFERENCES)) { preferences.load(input); }
        catch (final NoSuchFileException absent) {} // FIRST LAUNCH
        catch (final IOException | IllegalArgumentException failure) { this.log("Cannot read the app preferences: " + failure.getMessage()); }
        final String saved = engine(preferences.getProperty(AppConfig.ENGINE));
        return saved != null ? saved : "vulkan";
    }

    private static String engine(final String value) {
        if (value == null) return null;
        final String name = value.trim().toLowerCase(Locale.ROOT);
        return name.equals("opengl") || name.equals("vulkan") ? name : null;
    }

    private BootstrapWindow window() throws Exception {
        if (this.window == null) EventQueue.invokeAndWait(() -> this.window = new BootstrapWindow());
        return this.window;
    }

    private void log(final String message) {
        System.out.println(message);
        if (this.window != null) {
            final BootstrapWindow window = this.window;
            EventQueue.invokeLater(() -> window.details.append(message + "\n"));
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Properties versions() throws IOException {
        final Properties versions = new Properties();
        try (final InputStream source = AppBootstrap.class.getResourceAsStream("/bootstrap.properties")) {
            if (source == null) throw new IOException("Missing bootstrap.properties");
            versions.load(source);
        }
        return versions;
    }

    private static List<Dependency> dependencies(final Properties versions) throws IOException {
        final String platform = IOTool.platformClassifier();
        if (platform.equals("unsupported")) throw new IOException("Unsupported operating system or CPU architecture");
        final String natives = "natives-" + platform;
        final String lwjgl = version(versions, "lwjgl");
        final List<Dependency> dependencies = new ArrayList<>();
        for (final String artifact: List.of("log4j-api", "log4j-core"))
            dependencies.add(new Dependency("org/apache/logging/log4j", artifact, version(versions, "log4j"), null, false));
        dependencies.add(new Dependency("com/google/code/gson", "gson", version(versions, "gson"), null, false));
        dependencies.add(new Dependency("org/joml", "joml", version(versions, "joml"), null, false));
        for (final String artifact: List.of("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-stb", "lwjgl-openal")) {
            dependencies.add(new Dependency("org/lwjgl", artifact, lwjgl, null, false));
            dependencies.add(new Dependency("org/lwjgl", artifact, lwjgl, natives, false));
        }
        // KEEP VULKAN AVAILABLE FOR RUNTIME ENGINE SWITCHES, EVEN WHEN STARTING WITH OPENGL
        for (final String artifact: List.of("lwjgl-vulkan", "lwjgl-shaderc")) {
            dependencies.add(new Dependency("org/lwjgl", artifact, lwjgl, null, true));
            if (artifact.equals("lwjgl-shaderc") || platform.startsWith("macos"))
                dependencies.add(new Dependency("org/lwjgl", artifact, lwjgl, natives, true));
        }
        return dependencies;
    }

    private static String version(final Properties versions, final String name) throws IOException {
        final String value = versions.getProperty(name + "_version");
        if (value == null || !value.matches("[0-9][A-Za-z0-9_.-]*"))
            throw new IOException("Invalid bootstrap dependency version: " + name);
        return value;
    }

    // THE DIRECTORY IS A PARAMETER SO THE TESTS EXTRACT INTO THEIR OWN TEMPORARY FOLDER
    private static Path extract(final String resource, final Path directory) throws IOException {
        try (final InputStream input = AppBootstrap.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing bundled library: " + resource);
            // EACH LAUNCH OWNS ITS FILE UNTIL THE CHILD JVM EXITS, INCLUDING ON WINDOWS
            final String name = resource.substring(resource.lastIndexOf('/') + 1);
            final Path jar = Files.createTempFile(directory, name + ".", ".jar");
            jar.toFile().deleteOnExit();
            try {
                Files.copy(input, jar, StandardCopyOption.REPLACE_EXISTING);
                return jar;
            } catch (final IOException failure) {
                try { Files.deleteIfExists(jar); }
                catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private static void download(final URI source, final Path destination, final String sha256, final BootstrapWindow window) throws IOException {
        final String name = destination.getFileName().toString();
        if (window != null) window.progress("Downloading " + name, -1);
        final URLConnection connection = source.toURL().openConnection();
        connection.setRequestProperty("User-Agent", "WaterMedia");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        final long total = connection.getContentLengthLong();
        // THE SHA-256 ONLY JUDGES A FINISHED FILE, SO A HOSTILE MIRROR COULD FILL THE DISK FIRST: THE BODY STOPS AT ITS
        // ANNOUNCED LENGTH OR THE CAP. THIS RUNS BEFORE LOG4J AND GSON EXIST, SO IT CANNOT USE NetRequest
        if (total > MAX_DOWNLOAD_BYTES) throw new IOException(name + " announces " + total + " bytes, above the " + MAX_DOWNLOAD_BYTES + "-byte cap");
        final long limit = total >= 0 ? total : MAX_DOWNLOAD_BYTES;
        // UNIQUE PARTIAL FILES PREVENT CONCURRENT LAUNCHERS FROM OVERWRITING EACH OTHER'S TRANSFERS
        final Path partial = Files.createTempFile(destination.getParent(), name + ".", ".part");
        long received = 0;
        try {
            try (final InputStream input = connection.getInputStream();
                 final OutputStream output = new BufferedOutputStream(Files.newOutputStream(partial))) {
                final byte[] buffer = new byte[IOTool.BUFFER_SIZE];
                long lastUpdate = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    received += count;
                    if (received > limit) throw new IOException("Download of " + name + " exceeds " + limit + " bytes");
                    output.write(buffer, 0, count);
                    final long now = System.nanoTime();
                    if (window != null && now - lastUpdate >= 50_000_000L) {
                        lastUpdate = now;
                        window.progress(name, total > 0 ? (int) (received * 100 / total) : -1);
                    }
                }
            }
            if (total >= 0 && received != total)
                throw new IOException("Incomplete download of " + name + ": " + received + " of " + total + " bytes");
            IOTool.verifySha256(partial, sha256);
            IOTool.move(partial, destination);
        } catch (final IOException failure) {
            try { Files.deleteIfExists(partial); }
            catch (final IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (window != null) window.progress(name, 100);
    }

    private static int relaunch(final Set<Path> jars, final String[] args, final String engine) throws Exception {
        final Set<String> classpath = new LinkedHashSet<>();
        jars.forEach(jar -> classpath.add(jar.toAbsolutePath().toString()));
        classpath.addAll(Arrays.asList(System.getProperty("java.class.path", "").split(File.pathSeparator)));
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent()) {
            if (!(loader instanceof final URLClassLoader urls)) continue;
            for (final URL url: urls.getURLs())
                if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
        }
        // THE APP LOADS THE LWJGL AND FFMPEG NATIVES FROM THE CLASSPATH AND LWJGL READS MEMORY THROUGH sun.misc.Unsafe;
        // JAVA 24+ WARNS ON BOTH WITHOUT THESE FLAGS, AND THE UNSAFE ONE DOES NOT EXIST BEFORE JAVA 23
        final List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED"));
        if (Runtime.version().feature() >= 23) command.add("--sun-misc-unsafe-memory-access=allow");
        command.addAll(List.of("-D" + APP_FLAG + "=true", "-D" + ENGINE_PROP + "=" + engine, "-Dlog4j2.StatusLogger.level=WARN",
                "-cp", String.join(File.pathSeparator, classpath), AppBootstrap.class.getName()));
        command.addAll(Arrays.asList(args));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    public interface Extension {
        default String name() { return this.getClass().getSimpleName(); }
        void load();
    }

    private record Dependency(String group, String artifact, String version, String classifier, boolean optional) {
        String name() {
            return this.artifact + "-" + this.version + (this.classifier == null ? "" : "-" + this.classifier) + ".jar";
        }

        URI source() {
            return URI.create(MAVEN + this.group + "/" + this.artifact + "/" + this.version + "/" + this.name());
        }
    }

    // ALL COMPONENT ACCESS RUNS ON THE EDT; DOWNLOADS AND CHILD PROCESS WAITS STAY ON THE MAIN THREAD
    private static final class BootstrapWindow extends JFrame {
        private final JTextArea details = new JTextArea();
        private final JProgressBar progress = new JProgressBar();
        private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        private int exitCode;

        private BootstrapWindow() {
            super("WATERMeDIA: App Bootstrap");
            final JPanel content = new JPanel(new BorderLayout(12, 12));
            content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
            content.setBackground(AppTheme.BG_0);
            this.setContentPane(content);
            final JLabel banner = new JLabel("WATERMeDIA", SwingConstants.CENTER);
            banner.setForeground(AppTheme.NEON);
            banner.setFont(banner.getFont().deriveFont(Font.BOLD, 28f));
            for (final String resource: List.of("icon.png", "assets/watermedia/textures/banner.png")) {
                try (final InputStream input = IOTool.jarOpenFile(resource)) {
                    final Image image = input == null ? null : ImageIO.read(input);
                    if (image == null) continue;
                    if (resource.equals("icon.png")) this.setIconImage(image);
                    else {
                        final double scale = Math.min(800.0 / image.getWidth(null), 100.0 / image.getHeight(null));
                        banner.setIcon(new ImageIcon(image.getScaledInstance((int) (image.getWidth(null) * scale),
                                (int) (image.getHeight(null) * scale), Image.SCALE_SMOOTH)));
                        banner.setText(null);
                    }
                } catch (final IOException ignored) {}
            }
            content.add(banner, BorderLayout.NORTH);
            this.details.setEditable(false);
            this.details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            this.details.setBackground(AppTheme.BG_1);
            this.details.setForeground(AppTheme.TEXT);
            this.details.setCaretColor(AppTheme.TEXT);
            content.add(new JScrollPane(this.details), BorderLayout.CENTER);
            this.progress.setStringPainted(true);
            this.progress.setForeground(AppTheme.NEON);
            this.progress.setBackground(AppTheme.BG_2);
            this.actions.setOpaque(false);
            final JButton copy = new JButton("Copy details");
            copy.addActionListener(event -> {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(this.details.getText()), null);
                } catch (final IllegalStateException failure) {
                    this.progress.setString("Clipboard unavailable; select the text and copy it manually.");
                }
            });
            final JButton close = new JButton("Close");
            close.addActionListener(event -> System.exit(this.exitCode));
            final JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
            controls.setOpaque(false);
            controls.add(copy);
            controls.add(close);
            buttons.add(controls, BorderLayout.WEST);
            buttons.add(this.actions, BorderLayout.EAST);
            final JPanel footer = new JPanel(new BorderLayout(0, 8));
            footer.setOpaque(false);
            footer.add(this.progress, BorderLayout.NORTH);
            footer.add(buttons, BorderLayout.SOUTH);
            content.add(footer, BorderLayout.SOUTH);
            this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            this.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent event) { System.exit(BootstrapWindow.this.exitCode); }
            });
            this.setMinimumSize(new Dimension(640, 360));
            this.setSize(900, 520);
            this.setLocationRelativeTo(null);
            this.setVisible(true);
        }

        private void progress(final String message, final int percent) {
            EventQueue.invokeLater(() -> {
                this.progress.setIndeterminate(percent < 0);
                this.progress.setValue(Math.max(0, percent));
                this.progress.setString(message + (percent < 0 ? "" : " - " + percent + "%"));
            });
        }

        // SHOWS THE TRACE AND BLOCKS UNTIL RELAUNCH IS CLICKED; CLOSE EXITS WITH CODE 1 INSTEAD
        private void error(final Throwable failure) throws Exception {
            final StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            final CompletableFuture<Void> relaunch = new CompletableFuture<>();
            EventQueue.invokeAndWait(() -> {
                this.exitCode = 1;
                this.setTitle("WATERMeDIA: Startup failed");
                this.details.setForeground(AppTheme.RED);
                final int start = this.details.getDocument().getLength();
                this.details.append("\n" + trace);
                this.details.setCaretPosition(start);
                this.progress.setIndeterminate(false);
                this.progress.setValue(0);
                this.progress.setString("Startup failed. Copy the details or relaunch to try again.");
                final JButton button = new JButton("Relaunch");
                button.addActionListener(event -> relaunch.complete(null));
                this.actions.add(button);
                this.actions.revalidate();
                this.toFront();
            });
            relaunch.get();
            EventQueue.invokeAndWait(() -> {
                this.actions.removeAll();
                this.actions.revalidate();
                this.actions.repaint();
                this.exitCode = 0;
                this.setTitle("WATERMeDIA: App Bootstrap");
                this.details.setForeground(AppTheme.TEXT);
                this.details.setText("");
            });
        }
    }
}
