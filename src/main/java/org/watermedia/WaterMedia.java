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

    // ENVIRONMENT: WRITTEN ONCE BY start() BEFORE THE VOLATILE started FLAG, WHICH PUBLISHES THEM;
    // EVERY READER CHECKS THE FLAG FIRST, SO ANY THREAD THAT SEES IT SET SEES THEM TOO
    private static String name;
    private static Path tmp;
    private static Path cwd;
    private static boolean clientSide;
    private static int steps;
    private static volatile boolean started;

    // LOAD PROGRESS: THE APP SPLASH POLLS IT FROM ITS RENDER THREAD WHILE start() HOLDS THE CLASS LOCK, SO ONLY VOLATILE READS FIT
    private static volatile String loading;
    private static volatile int done;

    private WaterMedia() {}

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
        if (started) throw new IllegalStateException("WaterMedia has already started");

        // THE MODULES IN LOAD ORDER: CONFIGURATION, NATIVES, THEN THE APIS THAT USE THEM; EACH ONE REFUSES A SECOND
        // CONSTRUCTION AND DECIDES BY ITSELF WHETHER THIS SIDE RUNS IT
        final List<Module> modules = new ArrayList<>(5);
        modules.add(new WaterMediaConfig());
        // BINARIES IS OPTIONAL: ITS INSTALL IS CHECKED WITH THE CLASS LOADER'S getResource(), WHICH SEES OTHER JARS WITHOUT LINKING THE CLASS
        if (WaterMedia.class.getClassLoader().getResource("org/watermedia/binaries/WaterMediaBinaries.class") != null) modules.add(new WaterMediaBinaries());
        modules.add(new NetworkAPI());
        modules.add(new CodecsAPI());
        modules.add(new PlayerAPI());

        // PUBLISHED BEFORE THE FIRST MODULE STARTS, SO THE STATIC ACCESSORS ALREADY WORK FOR THE MODULES
        WaterMedia.name = name;
        WaterMedia.tmp = (tmp == null ? DEFAULT_TMP : tmp).toAbsolutePath().normalize();
        WaterMedia.cwd = (cwd == null ? DEFAULT_CWD : cwd).toAbsolutePath().normalize();
        WaterMedia.clientSide = clientSide;
        steps = modules.size();
        started = true;
        LOGGER.info(IT, "Starting {} {} for {} ({})", NAME, VERSION, name, clientSide ? "client" : "server");
        LOGGER.info(IT, "Working directory: {}", WaterMedia.cwd);
        LOGGER.info(IT, "Temporary directory: {}", WaterMedia.tmp);

        try {
            for (final Module module: modules) {
                loading = module.getClass().getSimpleName();
                LOGGER.info(IT, "Loading {} ({}/{})", loading, done + 1, steps);
                module.start(WaterMedia.name, WaterMedia.tmp, WaterMedia.cwd, WaterMedia.clientSide);
                done++; // SINGLE WRITER: ONLY start() TOUCHES IT, UNDER THE CLASS LOCK
            }
        } catch (final Exception | LinkageError failure) {
            final String module = loading;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.error(IT, "{} did not load: module {} failed", NAME, module, failure);
            throw new IllegalStateException("WaterMedia module " + module + " failed", failure);
        } finally {
            loading = null;
        }
        LOGGER.info(IT, "{} loaded {} module(s)", NAME, done);
    }

    /** True once every module loaded; a part a module lives without (FFmpeg, the file server) does not change it. */
    public static boolean loaded() { return started && done == steps; }
    /** Returns the name of the module being loaded, or null outside a load; loading screens pair it with {@link #step()} and {@link #steps()}. */
    public static String loading() { return loading; }
    /** Returns how many modules finished loading so far. */
    public static int step() { return done; }
    /** Returns how many modules this process loads; zero before {@link #start}. */
    public static int steps() { return started ? steps : 0; }

    /** Prefixes a resource path with this library's identifier, as {@code watermedia:path}. */
    @Deprecated
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

    /** Returns the environment name given to {@link #start}; throws before it. */
    public static String name() { requireStarted(); return name; }
    /** Returns the process's absolute working directory; throws before {@link #start}. */
    public static Path cwd() { requireStarted(); return cwd; }
    /** Returns the process's absolute temporary directory; throws before {@link #start}. */
    public static Path tmp() { requireStarted(); return tmp; }
    /** Whether WaterMedia runs on a client; throws before {@link #start}. */
    public static boolean clientSide() { requireStarted(); return clientSide; }

    /** Rejects a client-only operation of {@code type} before {@link #start} or on a server; engines, players and client-only tools call it from their constructors. */
    public static void checkIsClientSideOrThrow(final Class<?> type) {
        if (!clientSide()) throw new IllegalStateException("Called " + type.getSimpleName() + " in a server environment");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE ONE FAILURE EVERY ACCESSOR GIVES BEFORE start(); ITS VOLATILE READ MUST COME BEFORE THE ENVIRONMENT READ
    private static void requireStarted() {
        if (!started) throw new IllegalStateException(NAME + " has not started");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /**
     * One unit of WaterMedia's load: an API or a companion module that {@link WaterMedia#start} creates and starts once
     * per process. Each subclass knows by itself whether it is loaded: its constructor flips its own flag on the first
     * call and throws on the second. It decides whether this side needs it and handles the parts it can live without;
     * a failure that escapes {@link #start} ends the load.
     */
    public abstract static class Module {
        /** Starts the module, once, on the thread that called {@link WaterMedia#start}, with the environment it received. */
        protected abstract void start(final String name, final Path tmp, final Path cwd, final boolean clientSide) throws Exception;
    }
}
