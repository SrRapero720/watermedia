package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.util.MediaType;
import org.watermedia.bootstrap.WaterMediaModule;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.watermedia.WaterMedia.LOGGER;

/** Player facade: resolves MRLs and creates players and engines. */
public final class PlayerAPI {
    private static final Marker IT = MarkerManager.getMarker(PlayerAPI.class.getSimpleName());
    private PlayerAPI() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /**
     * Gets or creates an MRL for the given URI string.
     * If cached and not expired, returns immediately; otherwise starts async loading.
     * <p>
     * A string naming an existing file is resolved through the filesystem; relative paths
     * resolve against the working directory WaterMedia started with (the same root as
     * {@code water://local}), never the launch directory. Anything else is parsed as a URI.
     * This method never throws on malformed or relative input: it returns a non-cached MRL born
     * in {@link MRL.Status#ERROR} carrying the failure as {@link MRL#exception()}, consistent
     * with every other resolution failure.
     *
     * @param uri the media URI or file path
     * @return the MRL instance (may still be loading, or in ERROR for malformed input)
     * @throws IllegalArgumentException when {@code uri} is null
     * @throws IllegalStateException before WaterMedia loads, and on dedicated servers
     */
    public static MRL mrl(final String uri) {
        if (uri == null) throw new IllegalArgumentException("URI cannot be null");
        try {
            final Path file = WaterMedia.cwd().resolve(uri);
            if (Files.isRegularFile(file)) return MRL.get(file.toAbsolutePath().normalize().toUri());
        } catch (final InvalidPathException ignored) {
            // URLS CARRY CHARACTERS A PATH CANNOT (":" ON WINDOWS): NOT A FILE
        }
        try {
            return MRL.get(URI.create(uri));
        } catch (final IllegalArgumentException e) {
            // MALFORMED INPUT USES THE SAME ERROR STATUS AS RESOLUTION FAILURES.
            return MRL.error(uri, e);
        }
    }

    /**
     * Gets or creates an MRL for the given URI.
     * If cached and not expired, returns immediately.
     * Otherwise, starts async loading via the platform API. A relative URI cannot be resolved
     * and yields a non-cached MRL born in {@link MRL.Status#ERROR}.
     *
     * @param uri the media URI
     * @return the MRL instance (may still be loading)
     * @throws IllegalArgumentException when {@code uri} is null
     * @throws IllegalStateException    before WaterMedia loads, and on dedicated servers
     */
    public static MRL mrl(final URI uri) {
        return MRL.get(uri);
    }

    /** Preloads several URIs in parallel (playlists, well-known URLs) and returns their MRLs in the same order. */
    public static MRL[] preload(final URI... uri) {
        return MRL.preload(uri);
    }

    /** Creates a {@link ServerMediaPlayer}, a clock without media or engines for either side; send its snapshots with your own transport and feed them to {@link MediaPlayer#sync(MediaPlayer.PlayerState)}. */
    public static ServerMediaPlayer serverPlayer() {
        return new ServerMediaPlayer();
    }

    /** Creates a media player for the first source of {@code mrl}, or {@code null}; see {@link #player(MRL, int, Supplier, Supplier)}. */
    public static MediaPlayer player(final MRL mrl, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        return player(mrl, 0, gfx, sfx);
    }

    /**
     * Creates a media player for the given MRL source, or {@code null} when no player can be built.
     * <p>
     * {@code null} covers two distinct situations, logged distinctly: the source isn't ready yet
     * (MRL still {@link MRL.Status#FETCHING}: retry next tick) or a permanent failure (failed MRL,
     * invalid index, missing backend, or a construction crash). Check {@link MRL#status()} to tell
     * them apart. {@link Error}s always propagate.
     *
     * @param mrl the resolved MRL
     * @param sourceIndex index of the source to play
     * @param gfx supplier of the video sink, invoked at most once
     * @param sfx supplier of the audio sink, invoked at most once
     * @return the player, or {@code null} when not ready or on failure
     * @throws IllegalArgumentException when {@code mrl}, {@code gfx} or {@code sfx} is null
     */
    public static MediaPlayer player(final MRL mrl, final int sourceIndex, final Supplier<GFXEngine> gfx, final Supplier<SFXEngine> sfx) {
        if (mrl == null || gfx == null || sfx == null) throw new IllegalArgumentException("The MRL and both engine suppliers are required");
        final MRL.Source source = mrl.source(sourceIndex);
        if (source == null) {
            // STILL FETCHING IS A NORMAL TRANSIENT STATE: DON'T SPAM WARNINGS FOR IT
            final MRL.Status status = mrl.status();
            if (status == MRL.Status.FETCHING) LOGGER.debug(IT, "Source {} not ready yet (still fetching): {}", sourceIndex, mrl.uri());
            else LOGGER.warn(IT, "Cannot create player: source {} unavailable (status {}) for {}", sourceIndex, status, mrl.uri());
            return null;
        }

        if (source.type() == MediaType.UNKNOWN) LOGGER.warn(IT, "Creating a media player for an unknown media type: {}", source);

        // MATERIALIZE ENGINES OUTSIDE THE PLAYER CTOR SO A THROW CAN'T LEAK GL/AL STATE ON RETRIES
        GFXEngine gfxEngine = null;
        SFXEngine sfxEngine = null;
        try {
            if (source.type() == MediaType.IMAGE) {
                LOGGER.debug(IT, "Creating TxMediaPlayer for image: {}", source);
                gfxEngine = gfx.get();
                return new TxMediaPlayer(mrl, sourceIndex, gfxEngine);
            }

            if (CodecsAPI.ffmpegLoaded()) {
                LOGGER.debug(IT, "Creating FFMediaPlayer for: {}", source);
                gfxEngine = gfx.get();
                sfxEngine = sfx.get();
                return new FFMediaPlayer(mrl, sourceIndex, gfxEngine, sfxEngine);
            }

            LOGGER.error(IT, "No media backend available for: {}", mrl.uri());
            return null;
        } catch (final Exception e) { // VM AND LINKAGE ERRORS MUST PROPAGATE.
            try { if (gfxEngine != null) gfxEngine.release(); }
            catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release GFX engine after construction failure", cleanup); }
            try { if (sfxEngine != null) sfxEngine.release(); }
            catch (final Exception cleanup) { LOGGER.warn(IT, "Failed to release SFX engine after construction failure", cleanup); }
            LOGGER.error(IT, "Player construction failed for: {}", mrl.uri(), e);
            return null;
        }
    }

    // ENGINE AND MEDIA PLAYER CONSTRUCTORS ENFORCE CLIENT ACCESS; ONLY ServerMediaPlayer EXISTS ON A DEDICATED SERVER.

    /**
     * Creates an OpenGL video engine bound to a render thread and the executor dispatching onto it.
     * <p>
     * {@code renderEx} must run its tasks on {@code renderThread} (the thread owning the GL
     * context) and be pumped every render frame. Passing both {@code null} builds a
     * no-thread-contract engine whose GL calls run synchronously on the calling thread, which
     * then must own the context.
     * @param renderThread thread owning the GL context, or null for the no-thread-contract mode
     * @param renderEx     executor dispatching onto {@code renderThread}; required when it is non-null
     */
    public static GLEngine glEngine(final Thread renderThread, final Executor renderEx) {
        return new GLEngine(renderThread, renderEx);
    }

    /** Creates a Vulkan video engine over the consumer's borrowed {@link VKContext}; every object it exposes must outlive the engine. */
    public static VKEngine vkEngine(final VKContext context) {
        return new VKEngine(context);
    }

    /**
     * Creates a JavaFX software video engine; bind its {@code image()} to an {@code ImageView}.
     * @param onFrame hook run on the upload thread after each frame is published, or null
     * @throws IllegalStateException when JavaFX 13 or newer is not on the class path
     */
    public static JFXEngine jfxEngine(final Runnable onFrame) {
        // JFXEngine LINKS JAVAFX TYPES WHEN IT LOADS AND NEEDS PixelBuffer (JAVAFX 13+), SO A MISSING TOOLKIT FAILS HERE
        try { Class.forName("javafx.scene.image.PixelBuffer", false, PlayerAPI.class.getClassLoader()); }
        catch (final ClassNotFoundException | LinkageError e) { throw new IllegalStateException("JavaFX 13 or newer is not on the class path", e); }
        return new JFXEngine(onFrame);
    }

    /** Creates an AWT/Swing software video engine; paint its {@code image()} from a component, {@code onFrame} (or null) runs after each frame. */
    public static AWTEngine awtEngine(final Runnable onFrame) {
        return new AWTEngine(onFrame);
    }

    /** Creates a headless capture engine that records uploads in memory, for tests and image processing without a GPU. */
    public static HeadlessGFXEngine headlessEngine() {
        return new HeadlessGFXEngine();
    }

    /** Creates a headless capture engine; {@code preload} makes it report frame-texture preloading support. */
    public static HeadlessGFXEngine headlessEngine(final boolean preload) {
        return new HeadlessGFXEngine(preload);
    }

    /** Creates an OpenAL audio engine with the default buffer pool ({@value ALEngine#DEFAULT_BUFFER_COUNT} buffers). */
    public static ALEngine alEngine() {
        return new ALEngine(ALEngine.DEFAULT_BUFFER_COUNT);
    }

    /** Creates an OpenAL audio engine with an explicit buffer pool depth. */
    public static ALEngine alEngine(final int buffers) {
        return new ALEngine(buffers);
    }

    /** Creates an OpenAL engine; spatial mode plays through positional mono sinks ({@link ALEngine#addSink}) and accepts host sound-thread updates. */
    public static ALEngine alEngine(final boolean spatial) {
        return new ALEngine(ALEngine.DEFAULT_BUFFER_COUNT, spatial);
    }

    /** Creates an OpenAL engine with explicit buffer depth (per sink) and optional spatial playback through sinks. */
    public static ALEngine alEngine(final int buffers, final boolean spatial) {
        return new ALEngine(buffers, spatial);
    }

    /** Creates a Java Sound audio engine with the default line depth ({@value JSEngine#DEFAULT_BUFFER_MS} ms). */
    public static JSEngine jsEngine() {
        return new JSEngine(JSEngine.DEFAULT_BUFFER_MS);
    }

    /** Creates a Java Sound audio engine with an explicit line buffer depth in milliseconds. */
    public static JSEngine jsEngine(final int bufferMs) {
        return new JSEngine(bufferMs);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** Internal module: media resolution and the network cache; only clients run it. */
    public static final class Module extends WaterMediaModule {
        public Module(final WaterMedia watermedia) { super(watermedia); }

        @Override
        public void start() {
            // A DEDICATED SERVER PLAYS NOTHING: THE RESOLVER AND THE CACHE STAY OFF
            if (!this.watermedia.clientSide) return;
            MRL.start();
            try {
                NetworkCache.start(this.watermedia.tmp.resolve("cache"));
            } catch (final Exception failure) { // PLAYBACK WORKS WITHOUT THE DISK CACHE
                LOGGER.error(IT, "The media cache did not start; playback continues without it", failure);
            }
        }
    }
}
