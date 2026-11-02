package org.watermedia;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.ExtendedLoggerWrapper;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.bootstrap.WaterMediaModule;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Loads WaterMedia's modules once for the life of the process and holds the environment they run in. */
public final class WaterMedia {
    private static final Marker IT = MarkerManager.getMarker(WaterMedia.class.getSimpleName());

    // IDENTITY
    public static final String ID = "watermedia";
    public static final String NAME = "WaterMedia";
    public static final String VERSION = IOTool.jarVersion();
    public static final String USER_AGENT = "WaterMedia/" + VERSION;
    public static final String BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + (152 + Math.max(0L, (ChronoUnit.DAYS.between(LocalDate.of(2026, 9, 8), LocalDate.now()) / 14))) + ".0.0.0 Safari/537.36";
    public static final Logger LOGGER = logger(ID);

    // DEFAULT PATHS
    private static final Path DEFAULT_TMP = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve(ID);
    private static final Path DEFAULT_CWD = Path.of("").toAbsolutePath();

    // THE INSTANCE OF THIS PROCESS: CREATED BY start(), NEVER REPLACED AND NEVER RELEASED
    private static volatile WaterMedia instance;

    // ENVIRONMENT
    public final String name;
    public final Path tmp;
    public final Path cwd;
    public final boolean clientSide;

    // LOAD STATE
    // THE APP SPLASH POLLS THESE FROM ITS RENDER THREAD WHILE THE HOST THREAD WALKS THE MODULES
    private final List<WaterMediaModule> modules;
    private volatile WaterMediaModule loading;
    private volatile int done;
    private volatile boolean loaded;

    private WaterMedia(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        this.name = name;
        this.tmp = (tmp == null ? DEFAULT_TMP : tmp).toAbsolutePath().normalize();
        this.cwd = (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize();
        this.clientSide = clientSide;

        // THE MODULES IN LOAD ORDER: CONFIGURATION, NATIVES, THEN THE APIS THAT USE THEM; EACH ONE DECIDES BY ITSELF WHETHER THIS SIDE RUNS IT.
        final List<WaterMediaModule> modules = new ArrayList<>(5);
        modules.add(new WaterMediaConfig.Module(this));
        // BINARIES IS OPTIONAL: ITS INSTALL IS CHECKED WITH THE CLASS LOADER'S getResource(), WHICH SEES OTHER JARS WITHOUT LINKING THE CLASS
        if (WaterMedia.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") != null) modules.add(new WaterMediaBinaries(this));
        modules.add(new NetworkAPI.Module(this));
        modules.add(new CodecsAPI.Module(this));
        modules.add(new PlayerAPI.Module(this));
        this.modules = List.copyOf(modules);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Loads every module, once for the life of the process; nothing is ever unloaded. Each module decides by
     * itself whether this side needs it and handles the parts it can live without (the file server, FFmpeg);
     * a failure that escapes a module ends the load.
     *
     * @throws IllegalStateException when WaterMedia already started, when called from an owned worker thread,
     *         or when a module fails, with that failure as the cause
     */
    public static synchronized void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) {
        if (ThreadTool.workerThread()) throw new IllegalStateException("Start WaterMedia from a host thread, not a managed worker");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Environment name cannot be empty");
        if (instance != null) throw new IllegalStateException("WaterMedia has already started");

        // PUBLISHED BEFORE THE FIRST MODULE STARTS, SO THE STATIC ACCESSORS ALREADY WORK FOR THE MODULES
        final WaterMedia watermedia = new WaterMedia(name, tmp, cwd, clientSide);
        instance = watermedia;
        LOGGER.info(IT, "Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        LOGGER.info(IT, "Working directory: {}", watermedia.cwd);
        LOGGER.info(IT, "Temporary directory: {}", watermedia.tmp);

        try {
            for (final WaterMediaModule module: watermedia.modules) {
                watermedia.loading = module;
                LOGGER.info(IT, "Loading {} ({}/{})", module.name(), watermedia.done + 1, watermedia.modules.size());
                module.start();
                watermedia.done++;
            }
            watermedia.loaded = true;
        } catch (final Exception | LinkageError failure) {
            final String module = watermedia.loading.name();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.error(IT, "{} did not load: module {} failed", NAME, module, failure);
            throw new IllegalStateException("WaterMedia module " + module + " failed", failure);
        } finally {
            watermedia.loading = null;
        }
        LOGGER.info(IT, "{} loaded {} module(s)", NAME, watermedia.done);
    }

    /** True once every module loaded; a part a module lives without (FFmpeg, the file server) does not change it. */
    public static boolean loaded() {
        final WaterMedia watermedia = instance;
        return watermedia != null && watermedia.loaded;
    }

    /** Returns the name of the module being loaded, or null outside a load; loading screens pair it with {@link #step()} and {@link #steps()}. */
    public static String loading() {
        final WaterMedia watermedia = instance;
        final WaterMediaModule module = watermedia == null ? null : watermedia.loading;
        return module == null ? null : module.name();
    }

    /** Returns how many modules finished loading so far. */
    public static int step() {
        final WaterMedia watermedia = instance;
        return watermedia == null ? 0 : watermedia.done;
    }

    /** Returns how many modules this process loads; zero before {@link #start}. */
    public static int steps() {
        final WaterMedia watermedia = instance;
        return watermedia == null ? 0 : watermedia.modules.size();
    }

    /** Prefixes a resource path with this library's identifier, as {@code watermedia:path}. */
    public static String toId(final String path) { return ID + ":" + path; }

    /** Creates a project logger that redacts secrets from messages, parameters and traces before appenders see them. */
    public static Logger logger(final String name) {
        final ExtendedLogger logger = LogManager.getContext(false).getLogger(name);
        return new ExtendedLoggerWrapper(logger, name, logger.getMessageFactory()) {
            @Override
            public void logMessage(final String caller, final Level level, final Marker marker, final Message message, final Throwable failure) {
                // 1. THE FORMATTED TEXT, REDACTED AS A WHOLE
                final String originalText = message == null ? "null" : message.getFormattedMessage();
                String text = DataTool.redact(originalText);
                boolean changed = message == null || !Objects.equals(text, originalText);

                // 2. THE PARAMETERS: AN APPENDER MAY FORMAT THEM AGAIN, SO A SECRET IN ONE OF THEM ALSO REPLACES THE MESSAGE
                final Object[] parameters = message == null ? null : message.getParameters();
                if (parameters != null) {
                    for (final Object parameter: parameters) {
                        final String value = ParameterizedMessage.deepToString(parameter);
                        changed |= !Objects.equals(value, DataTool.redact(value));
                    }
                }

                // 3. THE THROWABLES: THE EXPLICIT ONE AND THE ONE EMBEDDED IN THE MESSAGE, EACH PRINTED AND CHECKED ONCE
                final Throwable embedded = message == null ? null : message.getThrowable();
                Throwable cause = failure != null ? failure : embedded;
                for (final Throwable thrown: new Throwable[] { cause, embedded == cause ? null : embedded }) {
                    if (thrown == null) continue;
                    final StringWriter trace = new StringWriter();
                    thrown.printStackTrace(new PrintWriter(trace));
                    final String safe = DataTool.redact(trace.toString());
                    if (safe.contentEquals(trace.getBuffer())) continue;
                    // AN UNSAFE TRACE BECOMES TEXT: TYPES, FRAMES AND CAUSES STAY READABLE, THE THROWABLE NEVER REACHES APPENDERS
                    text += System.lineSeparator() + safe;
                    if (thrown == cause) cause = null;
                    changed = true;
                }
                super.logMessage(caller, level, marker, changed ? new SimpleMessage(text) : message, cause);
            }
        };
    }

    /** Returns the process's absolute working directory; throws before {@link #start}. */
    public static Path cwd() { return instance().cwd; }
    /** Returns the process's absolute temporary directory; throws before {@link #start}. */
    public static Path tmp() { return instance().tmp; }
    /** Whether WaterMedia runs on a client; throws before {@link #start}. */
    public static boolean clientSide() { return instance().clientSide; }

    /** Rejects a client-only operation of {@code type} before {@link #start} or on a server; engines, players and client-only tools call it from their constructors. */
    public static void checkIsClientSideOrThrow(final Class<?> type) {
        if (!instance().clientSide) throw new IllegalStateException("Called " + type.getSimpleName() + " in a server environment");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE INSTANCE, OR THE ONE FAILURE EVERY ACCESSOR GIVES BEFORE start()
    private static WaterMedia instance() {
        final WaterMedia watermedia = instance;
        if (watermedia == null) throw new IllegalStateException(NAME + " has not started");
        return watermedia;
    }
}
