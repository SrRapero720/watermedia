package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.platform.internal.WaterPlatform;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Base of every playback unit, carrying the shared control surface (volume, mute, speed,
 * repeat, LOD/scaling) and the session follower.
 * <p>
 * A player fed the {@link ServerMediaPlayer#snapshot() snapshots} of a clock through {@link #sync(PlayerState)}
 * follows that clock: it mirrors its status, repeat and speed and keeps its own position within
 * {@link #tolerance()} of {@link #authorityTime()}. How the snapshots travel from the clock to the
 * player is the host's business; the library owns the time math and the correction.
 */
public abstract sealed class MediaPlayer permits ServerMediaPlayer, FFMediaPlayer, TxMediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(MediaPlayer.class.getSimpleName());
    /** Sentinel for unknown or unset video dimensions. */
    public static final int NO_SIZE = 0;
    /** Sentinel for a missing GPU texture handle. */
    public static final long NO_TEXTURE = 0L;
    /** Sentinel for a missing audio source handle. */
    public static final int NO_SOURCE = 0;
    /** Sentinel for an unknown media duration. */
    public static final long NO_DURATION = 0L;

    // FOLLOWER CORRECTION CONSTANTS
    private static final long SEEK_COOLDOWN_NS = 1_000_000_000L; // 1s OF PLAYBACK AFTER A LANDING BEFORE THE NEXT SEEK
    private static final long LATENCY_MAX_MS = 5_000L;           // THE FURTHEST A CORRECTION AIMS AHEAD OF THE SESSION
    private static final long CHAIN_NS = 10_000_000_000L;        // CORRECTIONS CLOSER THAN 10s FOLLOW FROM EACH OTHER
    private static final int CHAIN_WARN_SEEKS = 3;               // THAT MANY IN A ROW ARE AN ANOMALY
    private static final long SKIP_STEP_MS = 5_000L;             // forward()/rewind() DISTANCE FOR VIDEO AND MUSIC

    // SITE LISTS: A PLAIN IPv4 IS FOUR DECIMALS WITHOUT LEADING ZEROS. A DISGUISED HOST HIDES WHERE IT LEADS: IT KEEPS A MARK THE
    // PARSERS READ DIFFERENTLY (@, %, ?), IT IS THE IPv6 ALIAS OF WINDOWS, OR ITS LAST LABEL IS A NUMBER, WHICH NO DOMAIN
    // HAS, SO IT IS AN ADDRESS WRITTEN AS ONE NUMBER, IN HEX OR SHORTENED (2130706433, 0x7f.1, 127.1)
    private static final Pattern IPV4 = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern DISGUISED = Pattern.compile(".*[@%?].*|.*\\.ipv6-literal\\.net|(?:.*\\.)?(?:\\d+|0x[0-9a-f]*)");
    private static final Pattern SHARE = Pattern.compile("[/\\\\]{2,}([^/\\\\]*)"); // THE SERVER OF A WINDOWS SHARE PATH (//server/share)

    // SHARED 50 MS TICKER: DRIVES SERVER CLOCKS AND FOLLOWER CORRECTIONS. ONE DAEMON THREAD TICKS EVERY REGISTERED
    // PLAYER, SO ALL CORRECTION STATE IS SINGLE-THREADED AND OFF THE GAME THREAD
    private static final long TICK_MS = 50;
    private static final Set<MediaPlayer> TICKING = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService TICKER = Executors.newSingleThreadScheduledExecutor(
            ThreadTool.workerFactory("MediaSync", Thread.NORM_PRIORITY - 2)
    );

    // MONOTONIC MILLISECONDS FOR PACING, DEADLINES AND PASSIVE CLOCKS: A WALL-CLOCK ADJUSTMENT MUST
    // NEVER MOVE A PLAYBACK POSITION. ANCHORED AT CLASS LOAD SO VALUES START NEAR ZERO AND GROW
    private static final long EPOCH_NS = System.nanoTime();

    static {
        TICKER.scheduleAtFixedRate(MediaPlayer::tickAll, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    // BASIC PROPERTIES
    final MRL mrl;
    final int sourceIndex;
    final MRL.Source source;
    final GFXEngine gfx;
    final SFXEngine sfx;
    // WRITTEN BY THE CALLER AND BY THE PIPELINE THREADS WHEN THEY RESOLVE THE REAL RENDITION
    volatile MediaQuality quality = WaterMediaConfig.player.defaultQuality;
    // BCP 47 TAG THE AUDIO PICK PREFERS AT EACH OPEN; null FOR NONE (NEVER THE SYSTEM LOCALE)
    private volatile String audioLanguage;

    // AUDIO PROPERTIES
    private volatile boolean repeat;
    private volatile float volume = 1f;
    private volatile float speed = 1.0f;
    private volatile boolean muted = false;

    // SESSION SYNC: heard IS THE LAST SNAPSHOT THIS PLAYER WAS FED WITH ITS ARRIVAL STAMP, AGED INTO A LIVE POSITION BY
    // authorityTime(). armed GATES THE SHARED TICKER OFF A HALF-CONSTRUCTED INSTANCE; EVERY CONCRETE CTOR CALLS arm() LAST
    private volatile boolean armed;
    private final Object tickLock = new Object();
    private volatile Heard heard; // NULL UNTIL THE FIRST SNAPSHOT

    // FOLLOWER TUNABLE: HOW FAR PLAYBACK MAY DRIFT BEFORE IT IS PULLED BACK
    private volatile long toleranceMs = 1_000;

    // WHEN THE LAST SEEK WAS TAKEN, BY THE HOST OR BY THE FOLLOWER: STAMPED BY THE PLAYERS WHOSE SEEKS TAKE TIME TO LAND, SO THE
    // TICKER TIMES THE LANDING
    volatile long seekNanos;

    // FOLLOWER CORRECTION STATE: TOUCHED ONLY FROM THE SINGLE TICKER THREAD
    private boolean tickWarned; // A FAILING TICK IS LOGGED ONCE PER PLAYER
    private boolean chainWarned; // CORRECTIONS THAT KEEP MISSING ARE LOGGED ONCE PER PLAYER
    private boolean startIssued;
    private boolean flying;      // THE PIPELINE WAS SEEN BUFFERING AND HAS NOT PLAYED AGAIN YET
    private long landedNanos;    // WHEN IT LAST PLAYED AGAIN, OR WHEN THE FOLLOWER LAST ASKED FOR A SEEK
    private long correctedNanos; // WHEN THE FOLLOWER LAST ASKED FOR A SEEK
    private long seekSeen;       // THE seekNanos STAMP WHOSE LANDING IS ALREADY TIMED
    private long latencyMs;      // HOW LONG THE LAST SEEK TOOK TO LAND
    private int corrections;     // SEEKS THE FOLLOWER ASKED FOR IN A ROW

    // STATUS LISTENERS, CONSUMED BY A TERMINAL TRANSITION; TRANSITIONS QUEUE UP AND ONE THREAD DRAINS THEM IN ORDER, SO
    // A CONTROL ISSUED FROM A CALLBACK NEVER DELIVERS ITS TRANSITION AHEAD OF THE REPORTED ONE. GUARDED BY statusEvents
    private final List<StatusListener> statusListeners = new ArrayList<>();
    private final ArrayDeque<Transition> statusEvents = new ArrayDeque<>();
    private boolean statusDraining;

    // FAILURE BEHIND THE CURRENT ERROR STATE: SET THROUGH exception(Throwable), CLEARED BY EVERY launch() WHERE IT RETIRES THE
    // PREVIOUS RUN, SO A LATE FAILURE OF THAT RUN NEVER LANDS ON THE NEW ONE
    volatile Throwable exception;

    // VIDEO UPLOAD SCALING, WRITTEN BY THE CALLER AND READ BY THE PLAYBACK THREADS THROUGH scaled(native, scale, lod.percent()):
    // THE SCALE CAPS EACH AXIS (NO_SIZE = NO CAP, NEVER UPSCALES) AND lod SHRINKS IT FURTHER BY A PERCENTAGE
    volatile int scaleWidth = NO_SIZE;
    volatile int scaleHeight = NO_SIZE;
    volatile LodLevel lod = LodLevel.MAX;

    // NATIVE SOURCE FRAME SIZE BEFORE ANY SCALING: THE SUBCLASS SETS IT WHEN IT LEARNS THE DECODED DIMENSIONS,
    // CALLERS THAT NEED THE UNCAPPED RESOLUTION READ IT (e.g. A UI CLAMPING A CUSTOM scale() TO THE SOURCE)
    volatile int sourceWidth = NO_SIZE;
    volatile int sourceHeight = NO_SIZE;

    // MEDIA PLAYERS DECODE AND RENDER, SO THEY ARE CLIENT-ONLY; THE HEADLESS CLOCK BELOW IS THE SERVER-SIDE PLAYER
    MediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx) {
        WaterMedia.checkIsClientSideOrThrow(this.getClass());
        if (mrl == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_MRL) + "Failed to create the MediaPlayer due to null MRL");
        // THE SITE LISTS MAY HAVE CHANGED SINCE THE MRL RESOLVED, SO THE LINK IS JUDGED AGAIN BEFORE IT PLAYS
        try {
            checkSite(mrl.uri());
        } catch (final BlacklistException | WhitelistException refusal) {
            mrl.block(refusal);
            throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_PLAYER_SITE_BLOCKED) + "Failed to create the MediaPlayer due to a blocked site: " + mrl.uri(), refusal);
        }
        final MRL.Source resolved = mrl.source(sourceIndex);
        if (resolved == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_SOURCE_UNAVAILABLE) + "Failed to create the MediaPlayer due to unavailable source: index " + sourceIndex);
        if (gfx == null && sfx == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_ENGINES) + "Failed to create the MediaPlayer due to null GFX and SFX engines");
        // A MISSING VIDEO SINK IS WORTH A WARNING; MISSING AUDIO IS THE NORMAL SHAPE OF EVERY IMAGE PLAYER
        if (gfx == null)
            LOGGER.warn(IT, "GFXEngine is null: there will be no video output");
        if (sfx == null)
            LOGGER.debug(IT, "SFXEngine is null: there will be no audio output");

        this.mrl = mrl;
        this.sourceIndex = sourceIndex;
        this.source = resolved;
        this.gfx = gfx;
        this.sfx = sfx;
    }

    // HEADLESS CONSTRUCTOR FOR ServerMediaPlayer, WHICH ONLY TRACKS TIME PROGRESSION
    MediaPlayer() {
        this.mrl = null;
        this.sourceIndex = -1;
        this.source = null;
        this.gfx = null;
        this.sfx = null;
    }

    // ==========================================================================
    // PUBLIC API: SESSION SYNC
    // ==========================================================================

    /**
     * Feeds a clock snapshot into this player, which follows it from then on: every 50 ms the snapshot's
     * status is mirrored (start, pause, stop), its repeat and speed are adopted and playback is pulled
     * back with {@link #seekQuick(long)} once it drifts past {@link #tolerance()}. The latest snapshot
     * fed always wins, so feed them in the order the clock produced them. Safe to call from any thread.
     * A control called on a following player applies at once and is overridden by the next tick that
     * disagrees with it: drive the session through its {@link ServerMediaPlayer} instead.
     * @param snapshot a {@link ServerMediaPlayer#snapshot()}, received through the host's own transport
     * @throws IllegalArgumentException when {@code snapshot} is null
     */
    public final void sync(final PlayerState snapshot) {
        if (snapshot == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_SNAPSHOT) + "Failed to apply the snapshot due to null snapshot");
        synchronized (this.tickLock) {
            if (!this.armed) return;
            this.heard = new Heard(snapshot, System.nanoTime());
            TICKING.add(this);
        }
    }

    /**
     * Feeds an encoded clock snapshot, decoded with {@link PlayerState#read(ByteBuffer)} on the calling thread;
     * see {@link #sync(PlayerState)}.
     * @param payload the snapshot bytes, consumed
     * @throws IllegalArgumentException if the payload is malformed
     */
    public final void sync(final ByteBuffer payload) {
        this.sync(PlayerState.read(payload));
    }

    /** Whether this player follows a clock, which it does from the first {@link #sync(PlayerState)} until {@link #release()}. */
    public final boolean following() { return this.heard != null; }

    /**
     * The last snapshot fed to this player. It is a point in the past: use
     * {@link #authorityTime()} for where playback should be right now.
     * @return the latest snapshot, or {@code null} before the first one or after {@link #release()}
     */
    public final PlayerState authority() {
        final Heard heard = this.heard;
        return heard == null ? null : heard.snapshot();
    }

    /**
     * Where the session says playback should be right now. A running session is extrapolated
     * from the last snapshot at its own rate and folded into the media timeline, so a loop
     * wraps instead of overrunning; a stopped one reports its frozen position. A live session
     * is never folded: its timeline is open-ended.
     * @return the authoritative position in milliseconds, or 0 before the first snapshot
     */
    public final long authorityTime() {
        final Heard heard = this.heard;
        return heard == null ? 0 : authorityTime(heard, 0L);
    }

    /**
     * Signed drift between the session and this player in milliseconds; positive means the
     * session is ahead. Zero before the first snapshot. On repeating media the drift is computed
     * on the circular timeline, taking the short way around the wrap.
     * @return the current drift in milliseconds
     */
    public final long drift() {
        final Heard heard = this.heard;
        return heard == null ? 0 : this.drift(heard);
    }

    /**
     * Sets how far a follower may drift from the session before it is pulled back with a
     * {@link #seekQuick(long)}. Correction is deliberately not millimetric: the alternative,
     * trimming the playback rate to converge smoothly, is perceived as the video randomly
     * running slow, which is a worse experience than an occasional jump. No effect until the
     * player follows a clock.
     * @param ms drift tolerance in milliseconds, {@code > 0}
     */
    public final void tolerance(final long ms) {
        if (ms <= 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_TOLERANCE_RANGE) + "Failed to set the tolerance due to non-positive value");
        this.toleranceMs = ms;
    }

    /** Returns the follower drift tolerance in milliseconds. */
    public final long tolerance() { return this.toleranceMs; }

    // ==========================================================================
    // PUBLIC API: CONTROL SURFACE
    // ==========================================================================

    /**
     * Selects the quality to use for this player; if that quality is unavailable, the source
     * resolves it to the closest available one. A playing video or music backend switches its
     * source on the playback loop while preserving the current timestamp; a picture player keeps
     * showing the frames it decoded and applies the selection on its next {@link #start()}.
     * @param quality the new quality to use
     * @throws IllegalArgumentException if {@code quality} is {@code null}
     */
    public void quality(final MediaQuality quality) {
        if (quality == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_QUALITY) + "Failed to set the quality due to null quality");
        this.quality = quality;
    }

    /** Returns the selected quality, possibly {@link MediaQuality#UNKNOWN} until the source dimensions are known. */
    public MediaQuality quality() { return this.quality; }

    /**
     * Sets the language this player prefers when it picks its audio, as a BCP 47 tag such as {@code "es-MX"} or
     * {@code "en"}. The same tag wins first, then the same primary language ({@code "es-MX"} takes {@code "es-419"},
     * {@code "es"} or the {@code "spa"} a container carries), then the source's original or default audio, then the first.
     * It applies from the next start or source switch, never switching by itself, and an {@link #audioTrack(AudioTrack)}
     * pick outranks it.
     * @param language the preferred language tag, or null (or blank) for no preference, the default
     */
    public final void audioLanguage(final String language) {
        this.audioLanguage = language == null || language.isBlank() ? null : language.strip();
    }

    /** Returns the preferred audio language set by {@link #audioLanguage(String)}, or null when none. */
    public final String audioLanguage() { return this.audioLanguage; }

    /**
     * Lists the audio this player can switch to: one entry per audio stream FFmpeg found in each input a run opened, the main
     * input's first, then those of each separate track of {@link MRL.Source#audioTracks()} in order. A separate track no run
     * opened yet is a single {@link AudioTrack#provisional() provisional} entry built from the track itself, so the list never
     * opens every track up front; once a run opens it, its real streams take that place for the life of the player. The main
     * input's entries are refreshed on every open, since a quality switch may change them. Empty on players without an
     * audio engine and on image and server players.
     * @return an immutable snapshot, safe to read from any thread
     */
    public List<AudioTrack> audioTracks() { return List.of(); }

    /** Returns the entry of {@link #audioTracks()} the current or last run decodes, or null before the first open and when it decodes no audio. */
    public AudioTrack audioTrack() { return null; }

    /**
     * Switches the audio to an entry of {@link #audioTracks()}, matched by its {@link AudioTrack#track() origin} and
     * {@link AudioTrack#stream() stream}. A running player rebuilds its pipeline at the current time (a live stream restarts
     * at its edge) and any other plays it from its next start. The pick outranks {@link #audioLanguage(String)} and survives
     * quality switches and restarts.
     * @param track the entry to play
     * @return true if the pick changed, false when the entry is unknown or already picked, or the player has no audio
     *         engine, cannot switch audio or was released
     * @throws IllegalArgumentException if {@code track} is null
     */
    public boolean audioTrack(final AudioTrack track) {
        if (track == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_TRACK) + "Failed to set the audio track due to null track");
        return false;
    }

    /** The media reference this player renders. */
    public MRL mrl() { return this.mrl; }
    /** Index of the {@link MRL.Source} within {@link #mrl()} this player renders. */
    public int sourceIndex() { return this.sourceIndex; }
    /** The resolved source within {@link #mrl()} this player renders. */
    public MRL.Source source() { return this.source; }

    /** Whether this player has video output. */
    public boolean video() { return this.gfx != null; }
    /** Whether this player has audio output. */
    public boolean audio() { return this.sfx != null; }

    /** Returns the uploaded video width in pixels, after any {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale, or {@link #NO_SIZE}. */
    public final int width() { return this.gfx == null ? NO_SIZE : this.gfx.width(); }
    /** Returns the uploaded video height in pixels, after any {@link #scale(int, int)} / {@link #lod(LodLevel)} downscale, or {@link #NO_SIZE}. */
    public final int height() { return this.gfx == null ? NO_SIZE : this.gfx.height(); }

    /** Returns the native source width in pixels, before any downscale, or {@link #NO_SIZE} until known. */
    public final int sourceWidth() { return this.sourceWidth; }
    /** Returns the native source height in pixels, before any downscale, or {@link #NO_SIZE} until known. */
    public final int sourceHeight() { return this.sourceHeight; }

    /**
     * Caps the dimensions of the video frames uploaded to the GPU.
     * Each axis is capped independently to the requested maximum: a frame larger than the
     * cap on either axis is downscaled by the player before upload, and frames are never
     * upscaled. Pass dimensions matching the source aspect ratio to avoid distortion.
     * This trades a small CPU scaling cost for less data uploaded to the GPU and smaller
     * textures.
     * <p>
     * The cap is further reduced by the current {@link LodLevel}. Changes apply on the fly
     * to the next uploaded frame without interrupting playback; already uploaded frames
     * (static images, preloaded animations) keep their size until the next {@link #start()}.
     * @param width maximum upload width in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} for no limit
     * @param height maximum upload height in pixels, or {@link MediaPlayer#NO_SIZE NO_SIZE} for no limit
     * @throws IllegalArgumentException if width or height is negative
     */
    public void scale(final int width, final int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NEGATIVE_SIZE) + "Failed to set the dimensions due to negative size");
        this.scaleWidth = width;
        this.scaleHeight = height;
    }

    /** Returns the maximum upload width set by {@link #scale(int, int)}, or {@link #NO_SIZE} when unlimited. */
    public int scaleWidth() { return this.scaleWidth; }
    /** Returns the maximum upload height set by {@link #scale(int, int)}, or {@link #NO_SIZE} when unlimited. */
    public int scaleHeight() { return this.scaleHeight; }

    /**
     * Sets the level of detail for video uploads.
     * Each level shrinks the effective maximum video dimensions to a percentage of their
     * value (see {@link LodLevel}), so distant or less important media decodes, scales and
     * uploads fewer pixels. The change is applied on the fly to the next uploaded frame
     * without interrupting playback.
     * @param lod the new level of detail
     * @throws IllegalArgumentException if lod is null
     * @see #scale(int, int)
     */
    public void lod(final LodLevel lod) {
        if (lod == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_LOD) + "Failed to set the LOD level due to null level");
        this.lod = lod;
    }

    /** Returns the current level of detail, {@link LodLevel#MAX MAX} until changed. */
    public LodLevel lod() { return this.lod; }

    /**
     * Returns the GPU handle of the final RGBA frame texture.
     * <p>
     * The handle always refers to a resolved RGBA texture. Frames decoded in planar or packed
     * layouts (YUV, YUVA, NV12/NV21, YUYV/UYVY, GRAY) are converted to RGBA on the GPU by the
     * backing engine before this handle is valid; direct formats (BGRA, RGBA, RGB) are exposed as
     * is. The concrete handle type depends on the engine: an OpenGL texture name for the GL engine,
     * or a {@code VkImageView} handle for the Vulkan engine.
     * <p>
     * <b>Pipeline caution.</b> Read this fresh on every draw and sample it within (or right after)
     * the engine's render cycle on the render thread. The handle may be reallocated on a resolution
     * or LOD change, and using it earlier, from an unrelated render stage, or caching it across
     * frames can sample a stale, half-converted, or freshly allocated (undefined contents) texture.
     * @return the RGBA texture handle, or {@link MediaPlayer#NO_TEXTURE NO_TEXTURE} if no frame has been produced yet
     * @see GFXEngine#texture()
     */
    public long texture() { return this.gfx == null ? NO_TEXTURE : this.gfx.texture(); }

    /** Returns the audio source handle of the {@link SFXEngine} (an OpenAL source id, 0 on Java Sound), or {@link #NO_SOURCE} without audio; it stays listener-relative and unattenuated unless {@link #spatialAudio(SFXEngine.SpatialAudio)} positions it. */
    public int audioSource() { return this.sfx != null ? this.sfx.source() : NO_SOURCE; }

    /** Whether the audio engine plays through positional mono sinks, see {@link #sinks()}. */
    public boolean spatial() { return this.sfx != null && this.sfx.spatial(); }

    /** Returns the sinks of a spatial OpenAL engine, the primary first (it owns {@link #audioSource()}); empty without one or after release. */
    public List<ALEngine.Sink> sinks() { return this.sfx instanceof final ALEngine al ? al.sinks() : List.of(); }

    /**
     * Adds a sink playing {@code mix} of this player's audio, in step with the others, on the host's sound executor
     * with the engine's OpenAL context current; see {@link ALEngine#addSink(ALEngine.Mix)}.
     * @return the new sink, or null without a spatial OpenAL engine or after release
     * @throws IllegalArgumentException when {@code mix} is null
     */
    public ALEngine.Sink addSink(final ALEngine.Mix mix) {
        if (mix == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_SINK_NULL_MIX) + "Failed to create the sink due to null mix");
        return this.sfx instanceof final ALEngine al ? al.addSink(mix) : null;
    }

    /** Last applied spatial state, or null when no spatial environment is active. */
    public SFXEngine.SpatialAudio spatialAudio() { return this.sfx != null ? this.sfx.spatialAudio() : null; }

    /**
     * Applies position and effects on the host's sound executor with the source's OpenAL context current.
     * Passing null restores dry listener-relative playback. Spatial controls are local to this listener.
     * @return false if the engine does not support spatial audio or has been released
     */
    public boolean spatialAudio(final SFXEngine.SpatialAudio audio) { return this.sfx != null && this.sfx.spatialAudio(audio); }

    /**
     * Moves to the previous frame of the video. Refused on a following player: stepping frames
     * against a synced timeline is meaningless.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean previousFrame() { return this.heard == null; }

    /**
     * Moves to the next frame of the video. Refused on a following player: stepping frames
     * against a synced timeline is meaningless.
     * @return true if the operation was successful, false otherwise.
     */
    public boolean nextFrame() { return this.heard == null; }

    /**
     * Sets the volume of the audio playback, clamped between 0 and 100.
     * Volume and {@link #mute(boolean)} are independent controls: changing the volume
     * never alters the mute state, and audio stays silent while muted. Volume is always local:
     * a followed clock never dictates it.
     * @param volume the desired volume level (0-100)
     */
    public void volume(final int volume) {
        this.volume = MathUtil.clamp(volume, 0, 100) / 100f;
        // MUTE IS AN INDEPENDENT CONTROL: APPLY THE GAIN THROUGH THE MUTE GATE, DON'T FLIP IT HERE
        if (this.sfx != null) this.sfx.volume(this.muted ? 0.0f : this.volume);
    }

    /** Returns the volume as a percentage (0-100); muting never changes it. */
    public int volume() { return Math.round(this.volume * 100); }

    /**
     * Mutes or unmutes the audio playback. Muting silences the audio engine without changing
     * {@link #volume()}, and unmuting plays at that volume again.
     * @param mute true to mute the audio, false to unmute
     */
    public void mute(final boolean mute) {
        this.muted = mute;
        if (this.sfx != null) this.sfx.volume(mute ? 0.0f : this.volume);
    }

    /** Whether the audio is muted; see {@link #mute(boolean)}. */
    public boolean mute() { return this.muted; }

    // ==========================================================================
    // PUBLIC API: SESSION CONTROLS
    // ==========================================================================
    // EVERY BOOLEAN CONTROL ANSWERS ONE QUESTION: DID THIS CALL CHANGE THIS PLAYER'S OWN STATE? false COVERS A CALL
    // REFUSED, ALREADY SATISFIED OR MADE AFTER release()

    /**
     * Starts media playback from the beginning, recovering from any previous state
     * (stopped, ended or error). An already-playing player restarts. Non-blocking.
     * @return true if a run was launched, false when refused or released
     */
    public final boolean start() {
        return this.launch(false, -1L);
    }

    /**
     * Starts media playback like {@link #start()} but landing in a paused state, also clearing a
     * previous failure. Not equivalent to {@code start()} followed by {@link #pause()}: those two
     * calls race the pipeline setup, so the pause intent is latched before playback begins. Non-blocking.
     * @return true if a run was launched, false when refused or released
     * @see MediaPlayer#start()
     */
    public final boolean startPaused() {
        return this.launch(true, -1L);
    }

    /**
     * Resumes media playback from the current position.
     * @see #pause(boolean)
     * @return true if the player left the paused state, false otherwise
     */
    public boolean resume() { return this.pause(false); }

    /**
     * Pauses media playback at the current position.
     * @see #pause(boolean)
     * @return true if the player entered the paused state, false otherwise
     */
    public boolean pause() { return this.pause(true); }

    /**
     * Pauses or resumes media playback. A pause while loading is latched and applied when the
     * pipeline opens; an ended, stopped, waiting or failed player has nothing to pause or resume
     * and answers false (use {@link #start()} or {@link #togglePlay()} to play it again).
     * @param paused true to pause the media playback, false to resume
     * @return true if the state changed, false when already there or impossible
     */
    public abstract boolean pause(boolean paused);

    /**
     * Stops media playback, tearing the pipeline down and resetting the position to the beginning.
     * Every player, a failed one included, lands in {@link Status#STOPPED} with {@link #time()} at 0.
     * Non-blocking.
     * @return true if a transition to stopped happened (or was scheduled on the playback thread),
     *         false when the player was already stopped or never started
     */
    public abstract boolean stop();

    /**
     * Toggles playback: a playing (or buffering) player pauses, a paused one resumes, and an ended
     * or stopped one starts again from the beginning like {@link #start()}.
     * @return true if the state changed, false when there is nothing to toggle
     */
    public abstract boolean togglePlay();

    /**
     * Seeks to a specific time in the media, clamped to the known duration. Seeking an ended or
     * stopped player restarts it paused at the target; a waiting, failed or non-seekable one
     * answers false.
     * @param time the time to seek to, in milliseconds.
     * @return true if the seek was taken, false when impossible
     */
    public abstract boolean seek(long time);

    /** Returns the playback time in milliseconds: 0 before the first frame and after {@link #stop()}, the duration once ended. */
    public abstract long time();

    /**
     * Skips forward or backward by a specific time in the media. A positive value skips forward,
     * a negative one backward; the sum saturates instead of overflowing.
     * @param time the time to skip, in milliseconds.
     * @return true if the seek was taken, false otherwise (see {@link #seek(long)})
     */
    public boolean skipTime(final long time) {
        final long now = this.time();
        final long target = (time > 0 && now > Long.MAX_VALUE - time) ? Long.MAX_VALUE : Math.max(0L, now + time);
        return this.seek(target);
    }

    /**
     * Quickly seeks to a specific time in the media without precise accuracy.
     * This method is useful for fast seeking operations where exact frame accuracy is not required.
     * @param time the time to seek to, in milliseconds.
     * @return true if the seek was taken, false otherwise (see {@link #seek(long)})
     */
    public abstract boolean seekQuick(long time);

    /**
     * Skips forward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the seek was taken, false otherwise (see {@link #seek(long)})
     */
    public boolean forward() { return this.skipTime(SKIP_STEP_MS); }

    /**
     * Skips backward 5 seconds in the media. Players with a finer natural step
     * (e.g. animated images) may override the distance.
     * @return true if the seek was taken, false otherwise (see {@link #seek(long)})
     */
    public boolean rewind() { return this.skipTime(-SKIP_STEP_MS); }

    /** Frame rate of the video stream (a float because 29.97 exists), or 0 until known or without video. */
    public abstract float fps();

    /** Returns the playback speed factor; 1.0 is normal speed. */
    public float speed() { return this.speed; }

    /**
     * Sets the playback speed factor, within (0.0, 4.0]; 1.0 is normal speed.
     * @param speed the desired playback speed.
     * @return true if the speed applies, false when the value is out of range, the player cannot
     *         change speed ({@link #canSpeed()}), the audio engine refused the new rate or the player was released.
     */
    public boolean speed(final float speed) {
        // NaN FAILS BOTH COMPARISONS, WHICH IS THE POINT: IT MUST NEVER REACH THE CLOCKS
        if (!(speed > 0f && speed <= 4.0f) || !this.armed) return false;
        // RE-REQUESTING THE CURRENT SPEED IS A SUCCESSFUL NO-OP (e.g. 1.0x ON SETUP)
        if (speed == this.speed) return true;
        // THE AUDIO ENGINE APPLIES THE RATE FIRST; THE CLOCKS FOLLOW ONLY A SPEED IT ACCEPTED
        if (!this.canSpeed() || (this.sfx != null && !this.sfx.speed(speed))) return false;
        this.speed = speed;
        return true;
    }

    /** Returns the media duration in milliseconds, or {@link #NO_DURATION} when unknown or not applicable (e.g. a live stream). */
    public abstract long duration();

    /** Whether playback repeats when it reaches the end of the media. */
    public boolean repeat() { return this.repeat; }

    /**
     * Sets whether the media should repeat playback when it reaches the end. A timed still image
     * (see {@link #displayTime(long)}) never loops.
     * @param repeat true to enable repeat playback, false to disable
     * @return true if the setting applied, false after {@link #release()}
     */
    public boolean repeat(final boolean repeat) {
        if (!this.armed) return false;
        this.repeat = repeat;
        return true;
    }

    /** Returns the current status of the media player. */
    public abstract Status status();

    /**
     * Adds a status listener; adding one twice keeps it once. Listeners hear every real transition,
     * including the ones a restart, {@link #stop()} and {@link #release()} cause, so playlist
     * consumers advance without polling {@link #status()}; the transition into
     * {@link Status#STOPPED}, {@link Status#ENDED} or {@link Status#ERROR} consumes them, so a
     * consumer registers once per playback and, when it restarts the player from that terminal
     * callback, registers again from the callback itself. Transitions are delivered one at a time
     * and in the order the statuses changed, on whichever thread drove the first of them: a playback
     * thread, the shared sync ticker, or the caller of {@link #status()}, {@link #time()} or
     * {@link #texture()} on image players whose clock resolves the end lazily. A listener never runs
     * under a lock of the player; {@link #release()} delivers its transitions once it finishes and
     * the engines are freed. Keep it short, never block, and never call {@link #release()} from it.
     * @param listener the listener to add
     * @throws IllegalArgumentException when {@code listener} is null
     */
    public void onStatus(final StatusListener listener) {
        if (listener == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_NULL_LISTENER) + "Failed to add the listener due to null listener");
        synchronized (this.statusEvents) {
            if (!this.statusListeners.contains(listener)) this.statusListeners.add(listener);
        }
    }

    /**
     * The failure behind the current {@link Status#ERROR ERROR} state, useful to surface the real
     * cause (bad URL, unsupported codec, network failure) instead of a bare error flag.
     * @return the last playback failure, or {@code null} when none occurred since the current run started (through
     *         {@link #start()}, {@link #startPaused()}, or a {@link #togglePlay()} or {@link #seek(long)} restart)
     */
    public final Throwable exception() { return this.exception; }

    /**
     * Sets how long a still image should remain displayed before the player transitions to
     * {@link Status#ENDED}; a timed still ignores {@link #repeat()}. Applies to image players and
     * to single-frame sources that fall into the video backend; a server clock has no media and
     * ignores it. A value {@code <= 0} shows the still until stopped.
     * @param ms display duration in milliseconds
     */
    public void displayTime(final long ms) {}

    /** Returns the display duration set by {@link #displayTime(long)} in milliseconds, or 0 when unlimited or not applicable. */
    public long displayTime() { return 0; }

    /** Whether the player is {@link Status#WAITING WAITING}. */
    public boolean waiting() { return this.status() == Status.WAITING; }
    /** Whether the player is {@link Status#LOADING LOADING}. */
    public boolean loading() { return this.status() == Status.LOADING; }
    /** Whether the player is {@link Status#BUFFERING BUFFERING}. */
    public boolean buffering() { return this.status() == Status.BUFFERING; }
    /** Whether the player is {@link Status#PAUSED PAUSED}. */
    public boolean paused() { return this.status() == Status.PAUSED; }
    /** Whether the player is {@link Status#PLAYING PLAYING}. */
    public boolean playing() { return this.status() == Status.PLAYING; }
    /** Whether the player is {@link Status#STOPPED STOPPED}. */
    public boolean stopped() { return this.status() == Status.STOPPED; }
    /** Whether the player is {@link Status#ENDED ENDED}. */
    public boolean ended() { return this.status() == Status.ENDED; }
    /** Whether the player is {@link Status#ERROR ERROR}. */
    public boolean error() { return this.status() == Status.ERROR; }

    /**
     * Best-effort fill of the playback read-ahead buffers as a percentage (0-100).
     * Complements {@link #buffering()}: while the player loads or buffers this reports how full
     * its pipeline is, so a UI can show real progress instead of an indeterminate spinner; a
     * pipeline that is playing or paused is keeping up and reports 100. Players without a
     * streaming pipeline (images, server clocks) report 100 whenever they {@link #canPlay()}.
     * @return the buffered percentage (0-100)
     */
    public int buffered() { return this.canPlay() ? 100 : 0; }

    /** Whether the source is a live stream (no seeking, open-ended duration); false until the source is open and says so. */
    public abstract boolean live();

    /** Whether the player can seek: false until the source is open, on live streams and on still images. */
    public abstract boolean canSeek();

    /** Whether the media is playable: true from the first successful load, through ended and stopped, until it fails or is released. */
    public abstract boolean canPlay();

    /** Whether {@link #speed(float)} can take effect: never on live streams nor over audio stuck at 1.0×; a pure query for any thread. */
    public boolean canSpeed() {
        // SCALING THE TIMELINE AGAINST AUDIO THAT CANNOT CHANGE RATE WOULD DESYNC THE PLAYBACK CLOCK
        return !this.live() && (this.sfx == null || this.sfx.canSpeed());
    }

    /**
     * Releases all resources associated with the media player, including the GPU and audio
     * resources held by the backing engines; every player stops following its clock and lands in
     * {@link Status#STOPPED}. Idempotent: a second call is a no-op.
     * <p>Implementations may block the calling thread while their internal decode/IO threads
     * finish, so never call it from a status listener or a playback callback. After calling this
     * method, the media player should not be used again.
     */
    public void release() {
        this.disarm();
        Throwable failure = null;
        // ATTEMPT EVERY OWNED RESOURCE EVEN WHEN A HOST CALLBACK OR ANOTHER ENGINE FAILS.
        final Runnable[] cleanup = {
                () -> { if (this.gfx != null) this.gfx.release(); },
                () -> { if (this.sfx != null) this.sfx.release(); }
        };
        for (final Runnable step: cleanup) {
            try {
                step.run();
            } catch (final RuntimeException | Error problem) {
                failure = mergeFailure(failure, problem);
            }
        }
        if (failure instanceof final Error error) throw error;
        if (failure instanceof final RuntimeException error) throw error;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // PUBLISHES THIS FULLY-CONSTRUCTED PLAYER TO THE TICKER MACHINERY. MUST BE THE LAST STATEMENT OF EVERY CONCRETE
    // CONSTRUCTOR, NEVER CALLED FROM THIS BASE CLASS, WHERE SUBCLASS FIELDS WOULD STILL BE UNINITIALIZED WHEN THE TICKER FIRES
    final void arm() {
        synchronized (this.tickLock) {
            if (this.armed) return;
            this.armed = true;
            // BACKDATE THE COOLDOWN SO THE FIRST CORRECTION IS NEVER BLOCKED (nanoTime ORIGIN IS ARBITRARY)
            this.landedNanos = System.nanoTime() - SEEK_COOLDOWN_NS;
        }
    }

    // REGISTERS/DEREGISTERS A PLAYER ON THE SHARED TICKER. A FOLLOWING PLAYER IGNORES DEREGISTRATION (IT TICKS UNTIL
    // release()), SO A CLOCK'S STOP/START CYCLES NEVER SILENCE THE CORRECTION
    void ticking(final boolean on) {
        synchronized (this.tickLock) {
            if (on && this.armed) TICKING.add(this);
            else if (this.heard == null) TICKING.remove(this);
        }
    }

    // FOLLOWER HOOKS, CALLED ON THE TICKER: catchUp BRINGS A PLAYER THAT FELL BEHIND THE SESSION TO ms, AND guide SAYS WHERE THE SESSION
    // IS WHILE A SEEK OF THE PLAYER IS STILL IN FLIGHT. A PLAYER THAT CAN DROP WHAT IT ALREADY READ OVERRIDES BOTH
    boolean catchUp(final long ms) { return this.seekQuick(ms); }
    void guide(final long ms) {}

    // SHARED-TICKER CALLBACK. THE BASE RUNS THE FOLLOWER ENGINE; ServerMediaPlayer LAYERS ITS CLOCK MAINTENANCE ON TOP
    void tick() {
        // ONE READ FOR THE WHOLE PASS: A RELEASE OR A NEWER SNAPSHOT LANDING MID-PASS NEVER MIXES INTO IT
        final Heard heard = this.heard;
        if (this.armed && heard != null) this.follow(heard);
    }

    // RESOLVES A STILL-UNKNOWN QUALITY ONCE THE DECODED SIZE IS KNOWN, RELABELING THE URI IN THE SHARED MRL (OR FINDING IT ALREADY
    // RELABELED BY AN EARLIER RUN); WHEN ANOTHER URI HOLDS THAT QUALITY OR THE MRL RELOADED, IT STAYS UNKNOWN
    final void resolveQuality(final int width, final int height) {
        if (this.quality != MediaQuality.UNKNOWN) return;
        final MediaQuality real = MediaQuality.of(width, height);
        final URI uri = this.source.uri(MediaQuality.UNKNOWN);
        if (!this.mrl.moveQuality(this.sourceIndex, uri, real)) return;
        LOGGER.info(IT, "Moved URI {} from quality {} to {}", uri, MediaQuality.UNKNOWN, real);
        this.quality = real;
    }

    // LAUNCHES A LOCAL RUN, PAUSED OR PLAYING, FROM THE BEGINNING (seekMs < 0) OR AT seekMs, AND CLEARS exception WHERE IT
    // RETIRES THE PREVIOUS RUN; START CONTROLS AND RESTARTS FROM ENDED/STOPPED CALL IT. FALSE WHEN RELEASED
    abstract boolean launch(final boolean paused, final long seekMs);

    // RECORDS THE FAILURE BEHIND AN ERROR TRANSITION. FIRST FAILURE OF A RUN WINS; LATER ONES
    // ARE USUALLY CASCADE NOISE OF THE ROOT CAUSE. EVERY launch() CLEARS IT FOR THE NEXT RUN.
    final void exception(final Throwable t) {
        if (this.exception == null) this.exception = t;
    }

    // QUEUES A REAL TRANSITION WITHOUT DELIVERING IT. CALL IT UNDER THE LOCK THAT WROTE THE STATUS, SO TWO WRITERS QUEUE IN THE
    // ORDER THEY WROTE, THEN deliverStatus() ONCE NO PLAYER LOCK IS HELD
    final void queueStatus(final Status prev, final Status next) {
        if (prev == next) return;
        synchronized (this.statusEvents) {
            this.statusEvents.add(new Transition(prev, next));
        }
    }

    // DELIVERS THE QUEUE IN ORDER UNLESS ANOTHER THREAD ALREADY DRAINS IT. A TERMINAL TRANSITION CONSUMES THE LISTENERS
    // BEFORE FIRING, SO ONE ADDED FROM ITS CALLBACK SURVIVES; FAILURES ARE SWALLOWED
    final void deliverStatus() {
        synchronized (this.statusEvents) {
            if (this.statusDraining || this.statusEvents.isEmpty() || this.holdStatus()) return;
            this.statusDraining = true;
        }
        while (true) {
            final Transition transition;
            final StatusListener[] listeners;
            synchronized (this.statusEvents) {
                transition = this.holdStatus() ? null : this.statusEvents.poll();
                if (transition == null) {
                    this.statusDraining = false;
                    return;
                }
                listeners = this.statusListeners.toArray(new StatusListener[0]);
                if (transition.terminal()) this.statusListeners.clear();
            }
            for (final StatusListener listener: listeners) {
                try {
                    listener.onStatus(this, transition.previous(), transition.current());
                } catch (final Throwable t) {
                    LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_PLAYER_LISTENER_LOG) + "Failed to run the status listener", t);
                }
            }
        }
    }

    // TRUE WHILE A RELEASE JOINS THE PIPELINE: NO THREAD IT WAITS FOR MAY RUN A LISTENER (ONE BLOCKED ON THE RELEASING THREAD WOULD
    // STALL THE JOIN), SO TRANSITIONS STAY QUEUED AND THE RELEASE DELIVERS THEM WHEN IT FINISHES
    boolean holdStatus() { return false; }

    // LEAVES THE TICKER AND THE SESSION BEFORE A RELEASE PUBLISHES STOPPED, SO NO CORRECTION RUNS ON A DYING PLAYER
    final void disarm() {
        synchronized (this.tickLock) {
            this.armed = false;
            this.heard = null;
            TICKING.remove(this);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private long drift(final Heard heard) {
        final PlayerState target = heard.snapshot();
        long drift = authorityTime(heard, 0L) - this.time();
        final long d = target.duration();
        // LOOPING TIMELINES ARE CIRCULAR: MAP INTO [-d/2, d/2) SO THE WRAP NEVER FAKES A HUGE DRIFT
        if (target.repeat() && d > 0 && !target.live()) drift = Math.floorMod(drift + d / 2, d) - d / 2;
        return drift;
    }

    // FOLLOWER ENGINE, RUNS ON THE SHARED TICKER: MIRRORS THE SESSION ONTO THIS PLAYER AND PULLS PLAYBACK BACK WHEN IT
    // DRIFTS PAST THE TOLERANCE
    private void follow(final Heard heard) {
        final PlayerState authority = heard.snapshot();
        final Status target = authority.status();
        Status current = this.status();
        final long now = System.nanoTime();

        final boolean dormant = current == Status.WAITING || current == Status.STOPPED || current == Status.ENDED;
        if (!dormant) this.startIssued = false;

        // DISCRETE STATE MIRROR: THE SESSION DICTATES START/PAUSE/STOP TRANSITIONS. ERROR PLAYERS ARE LEFT ALONE: RETRY
        // POLICY BELONGS TO THE OWNER. A BUFFERING SESSION HOLDS THIS PLAYER LIKE A PAUSE
        switch (target) {
            case PLAYING -> {
                if (dormant) {
                    // START ONCE PER DORMANCY: FF PIPELINES PUBLISH STATUS ASYNC, SO RE-CALLING start() EVERY TICK WOULD
                    // RESTART THE LIFECYCLE FOREVER. NON-REPEAT MEDIA WITH LESS THAN THE TOLERANCE LEFT IS SKIPPED: THE
                    // SESSION ENDS BEFORE IT FINISHES LOADING
                    final boolean go;
                    // AN ENDED PLAYER RESTARTS ONLY WHILE THE SESSION SITS BEFORE ITS OWN MEDIA'S END: THE SESSION DURATION
                    // MAY DIVERGE (BAD LATCH, MIXED VARIANTS) AND WOULD REPLAY IT FOREVER
                    if (current == Status.ENDED) {
                        final long end = this.duration() > 0 ? this.duration() : authority.duration();
                        go = end > 0 && authorityTime(heard, 0L) < Math.max(end - this.toleranceMs, this.toleranceMs);
                    } else {
                        go = authority.repeat() || authority.duration() <= 0
                                || authority.duration() - authorityTime(heard, 0L) > this.toleranceMs;
                    }
                    if (!this.startIssued && go) {
                        this.start();
                        this.startIssued = true;
                    }
                    return;
                }
                if (current == Status.PAUSED) this.resume();
            }
            case PAUSED, BUFFERING -> {
                if (dormant) {
                    if (!this.startIssued) {
                        this.startPaused();
                        this.startIssued = true;
                    }
                    return;
                }
                if (current == Status.PLAYING) this.pause();
            }
            case STOPPED -> {
                // ENDED IS ALREADY DORMANT AND ITS PIPELINE IS GONE: stop() THERE CANNOT TRANSITION
                // ANYTHING AND WOULD RE-FIRE EVERY TICK FOREVER
                if (current != Status.STOPPED && current != Status.WAITING && current != Status.ENDED) this.stop();
                return;
            }
            // ENDED: LET THE PLAYER REACH ITS OWN END NATURALLY; WAITING/OTHERS: NOTHING TO FOLLOW YET
            default -> { return; }
        }

        // REPEAT AND RATE ARE SESSION STATE; NO TIME AUTHORITY ON LIVE SOURCES, AND NO CORRECTION
        // WHILE THE LOCAL PIPELINE IS STILL CATCHING UP
        if (this.repeat() != authority.repeat()) this.repeat(authority.repeat());
        if (this.speed() != authority.speed()) this.speed(authority.speed());
        if (authority.live() || authority.duration() <= 0) return;
        // THE STAMP IS READ BEFORE THE STATUS: A SEEK TAKEN IN BETWEEN SHOWS UP AS BUFFERING, NEVER AS A LANDING
        final long seek = this.seekNanos;
        current = this.status();
        // A SEEK TAKEN WHILE LOADING LANDS WITH THE OPEN, WHICH SAYS NOTHING ABOUT HOW LONG THE NEXT ONE TAKES
        if (current == Status.LOADING) this.seekSeen = seek;
        if (current == Status.BUFFERING) {
            this.flying = true;
            // A SEEK STILL IN FLIGHT LANDS WHERE THE SESSION IS BY THEN, NOT WHERE IT WAS WHEN THE SEEK LEFT
            if (seek != this.seekSeen && target == Status.PLAYING) this.guide(authorityTime(heard, 0L));
        }
        if (current != Status.PLAYING && current != Status.PAUSED) return;

        // LANDING: THE PIPELINE PLAYS AGAIN AFTER A SEEK OR A REBUFFER. THE COOLDOWN COUNTS FROM HERE, NOT FROM THE REQUEST, AND
        // THE TIME A SEEK TOOK IS HOW FAR BEHIND THE SESSION IT LANDED
        if (this.flying || seek != this.seekSeen) {
            if (seek != this.seekSeen) {
                this.latencyMs = Math.max(0L, Math.min((now - seek) / 1_000_000L, LATENCY_MAX_MS));
                this.seekSeen = seek;
                LOGGER.debug(IT, "Sync: seek landed in {}ms, {}ms away from the session", this.latencyMs, this.drift(heard));
            }
            this.flying = false;
            this.landedNanos = now;
        }

        // ONE CORRECTION, ONE THRESHOLD: PAST THE TOLERANCE, JUMP TO THE SESSION. NO RATE TRIMMING: IT READS AS THE VIDEO RANDOMLY
        // RUNNING SLOW, AND A CLIENT ONLY FALLS BEHIND A BARE CLOCK. BEHIND IT, THE PLAYER CATCHES UP (guide KEEPS THE LANDING ON THE
        // SESSION). AHEAD OF IT, ONLY A SEEK GOES BACK, AIMED AT WHERE THE SESSION WILL BE WHEN IT LANDS
        final long drift = this.drift(heard);
        if (Math.abs(drift) <= this.toleranceMs || now - this.landedNanos < SEEK_COOLDOWN_NS) return;
        this.landedNanos = now;
        if (!(drift > 0 ? this.catchUp(authorityTime(heard, 0L)) : this.seekQuick(authorityTime(heard, this.latencyMs)))) return;

        // A CORRECTION THIS SOON AFTER THE LAST ONE MEANS THAT ONE MISSED
        this.corrections = now - this.correctedNanos < CHAIN_NS ? this.corrections + 1 : 1;
        this.correctedNanos = now;
        if (this.corrections == CHAIN_WARN_SEEKS && !this.chainWarned) {
            this.chainWarned = true;
            LOGGER.warn(IT, "Sync keeps correcting {}: {} seeks in a row missed the {}ms tolerance, the last one took {}ms and left a drift of {}ms",
                    this.mrl != null ? this.mrl.uri() : this.getClass().getSimpleName(), this.corrections - 1, this.toleranceMs, this.latencyMs, drift);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // SHARED-TICKER PASS OVER EVERY REGISTERED PLAYER; A FAILING TICK IS LOGGED ONCE PER PLAYER
    private static void tickAll() {
        for (final MediaPlayer player: TICKING) {
            try {
                if (player.armed) player.tick();
            } catch (final Throwable t) {
                if (player.tickWarned) continue;
                player.tickWarned = true;
                // A SERVER CLOCK CARRIES NO MRL
                final Object subject = player.mrl != null ? player.mrl.uri() : player.getClass().getSimpleName();
                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_PLAYER_SYNC_TICK_LOG) + "Failed to run the sync tick for {}", subject, t);
            }
        }
    }

    // THE SNAPSHOT KEPT AGEING SINCE IT LANDED AND WILL FOR aheadMs MORE; A LIVE OR UNKNOWN TIMELINE RUNS FREE, A FINITE ONE WRAPS
    // OR CLAMPS
    private static long authorityTime(final Heard heard, final long aheadMs) {
        final PlayerState target = heard.snapshot();
        if (target.status() != Status.PLAYING) return target.time();
        final long elapsed = (System.nanoTime() - heard.nanos()) / 1_000_000L + aheadMs;
        final long run = (long) (elapsed * target.speed());
        // SATURATE LIKE THE AUTHORITY: A LIVE POSITION NEAR Long.MAX_VALUE MUST NOT WRAP NEGATIVE
        final long t = run > Long.MAX_VALUE - target.time() ? Long.MAX_VALUE : target.time() + run;
        final long d = target.duration();
        if (d <= 0 || target.live()) return t;
        return target.repeat() ? t % d : Math.min(t, d);
    }

    // COMBINES CLEANUP FAILURES: THE FIRST CAUSE KEEPS THE REST AS SUPPRESSED, EXCEPT THAT AN Error OUTRANKS AN EXCEPTION AND A
    // VirtualMachineError OUTRANKS EVERYTHING, SO A FATAL VM ERROR NEVER HIDES BEHIND AN ORDINARY ONE
    static Throwable mergeFailure(final Throwable previous, final Throwable next) {
        if (previous == null || previous == next) return next;
        final boolean previousFatal = previous instanceof VirtualMachineError;
        final boolean nextFatal = next instanceof VirtualMachineError;
        if ((nextFatal && !previousFatal) || (next instanceof Error && !(previous instanceof Error))) {
            next.addSuppressed(previous);
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    // MONOTONIC MILLISECONDS SINCE CLASS LOAD (SEE EPOCH_NS)
    static long nowMs() { return (System.nanoTime() - EPOCH_NS) / 1_000_000L; }

    // LOD SIZING: CAPS nativeSize AT max (0 = NO CAP, NEVER UPSCALING), THEN KEEPS percent OF IT. AN UNREDUCED SIZE STAYS
    // EXACT (ODD SOURCES INCLUDED); A REDUCED ONE IS FORCED EVEN AND AT LEAST 2 SO CHROMA-SUBSAMPLED PLANES STAY ALIGNED
    static int scaled(final int nativeSize, final int max, final int percent) {
        if (nativeSize <= 0) return nativeSize;
        int size = max > 0 ? Math.min(nativeSize, max) : nativeSize;
        if (percent < 100) size = (int) Math.round(size * (percent / 100.0));
        if (size >= nativeSize) return nativeSize;
        return Math.max(2, size & ~1);
    }

    // ==========================================================================
    // UTILITY: SITE LISTS
    // ==========================================================================

    // JUDGES A LINK AGAINST player.blacklist AND player.whitelist BY THE HOST IT WAS WRITTEN WITH. THE HOSTS A PLATFORM, A
    // PLAYLIST OR A REDIRECT LEADS TO ARE NEVER JUDGED: YOUTUBE PLAYS FROM GOOGLE DOMAINS AND MOST SITES FROM A CDN
    static void checkSite(final URI uri) throws BlacklistException, WhitelistException {
        // ONE READ OF EACH LIST: THE CONFIG CAN BE RELOADED WHILE A LINK IS JUDGED
        final WaterMediaConfig.Player.Whitelist whitelist = WaterMediaConfig.player.whitelist;
        final WaterMediaConfig.Player.Blacklist blacklist = WaterMediaConfig.player.blacklist;
        final String[] allowed = whitelist.sites;
        final String[] forbidden = blacklist.sites;
        final boolean trustLocal = whitelist.trustLocal;

        // AN ENABLED LIST WITH NOTHING TO JUDGE BY STAYS OFF
        final boolean allowing = whitelist.enable && (allowed.length > 0 || trustLocal);
        final boolean forbidding = blacklist.enable && forbidden.length > 0;
        if (!allowing && !forbidding) return;

        // jar: WRAPS THE LINK JAVA REALLY FETCHES, WHICH ENDS AT THE FIRST "!/" (jar:https://site.com/pack.jar!/clip.mp4): THAT
        // INNER LINK IS THE ONE JUDGED
        URI link = uri;
        while ("jar".equalsIgnoreCase(link.getScheme())) {
            final String inner = link.getRawSchemeSpecificPart();
            final int entry = inner.indexOf("!/");
            link = URI.create(entry < 0 ? inner : inner.substring(0, entry));
        }

        // water:// NAMES A STORE INSTEAD OF A SITE: global ALWAYS PLAYS, local AND remote BELONG TO WHOEVER RUNS THE PACK
        final boolean water = NetworkAPI.PROTOCOL_WATER.equalsIgnoreCase(link.getScheme());
        if (water && WaterPlatform.HOST_GLOBAL.equalsIgnoreCase(link.getHost())) return;
        final String host = water ? null : host(link);

        // NO LIST CAN NAME A HOST THAT HIDES WHERE IT LEADS, SO THE BLACKLIST FORBIDS IT AND THE WHITELIST NEVER ALLOWS IT
        final boolean disguised = host != null && DISGUISED.matcher(host).matches() && !IPV4.matcher(host).matches();

        // THE BLACKLIST WINS OVER THE WHITELIST
        if (forbidding && host != null && (disguised || listed(host, forbidden)))
            throw new BlacklistException(DataTool.errCode(WaterMedia.ERR_PLAYER_SITE_FORBIDDEN) + "Failed to resolve " + uri + ", the site is forbidden");
        if (!allowing) return;

        // A FILE WITHOUT A HOST LIVES ON THIS MACHINE; ONE WITH A HOST IS A NETWORK SHARE, JUDGED LIKE ANY OTHER SITE
        final boolean local = water || host == null && "file".equalsIgnoreCase(link.getScheme()) || !disguised && local(host);
        if (trustLocal && local) return;
        if (host != null && !disguised && listed(host, allowed)) return;
        throw new WhitelistException(DataTool.errCode(WaterMedia.ERR_PLAYER_SITE_NOT_ALLOWED) + "Failed to resolve " + uri + ", the site is not allowed");
    }

    // THE HOST OF A LINK IN THE ONE FORM THE SITE LISTS COMPARE: LOWERCASE ASCII WITHOUT PORT, USER, BRACKETS OR TRAILING
    // DOT, AND IPv6 WRITTEN IN FULL; null ONLY WHEN THE LINK WRITES NO HOST AT ALL
    private static String host(final URI uri) {
        String host = uri.getHost();
        if (host == null && uri.getRawAuthority() != null) {
            // java.net.URI GIVES NO HOST TO NAMES IT REJECTS BUT THE NETWORK STILL REACHES (UNDERSCORES, UNICODE, 127.1). ONLY
            // THE FIRST USER MARK IS CUT: A SECOND ONE OR AN ESCAPE STAYS IN THE HOST, WHICH LEAVES IT DISGUISED
            host = uri.getRawAuthority();
            host = host.substring(host.indexOf('@') + 1);
            final int port = host.lastIndexOf(':');
            if (port >= 0 && !host.endsWith("]")) host = host.substring(0, port);
        }
        if ("file".equalsIgnoreCase(uri.getScheme()) && (host == null || host.isEmpty() || host.equalsIgnoreCase("localhost") || host.equals("~"))) {
            // JAVA READS THESE HOSTS AS NO HOST AND WINDOWS OPENS A PATH THAT STARTS WITH TWO SEPARATORS AS A NETWORK SHARE
            // (file:////server/share): ITS SERVER IS THE HOST
            final String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
            final Matcher share = SHARE.matcher(path == null ? "" : path);
            if (share.lookingAt()) host = share.group(1);
        }
        if (host == null) return null;
        host = host.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        // A HOST THAT WAS WRITTEN BUT NAMES NOTHING (AN EMPTY SERVER, THE DEVICE PREFIX \\.\) HIDES WHERE THE LINK LEADS
        if (host.isEmpty()) return "?";

        if (host.indexOf(':') >= 0) {
            // BETWEEN BRACKETS InetAddress READS AN IPv6 LITERAL OR FAILS, NEVER ASKS THE DNS; IT ALSO UNWRAPS ::ffff:127.0.0.1
            final int zone = host.indexOf('%');
            try { return InetAddress.getByName("[" + (zone < 0 ? host : host.substring(0, zone)) + "]").getHostAddress(); }
            catch (final UnknownHostException e) { return host; }
        }
        // A UNICODE NAME RESOLVES AS ITS ASCII FORM, WHERE FULLWIDTH LETTERS AND DOTS FOLD INTO PLAIN ONES
        try { return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED); }
        catch (final IllegalArgumentException e) { return host; }
    }

    // WHETHER A LIST NAMES host: A SITE COVERS ITS SUBDOMAINS, AN ADDRESS ONLY ITSELF
    private static boolean listed(final String host, final String[] sites) {
        final boolean address = host.indexOf(':') >= 0 || IPV4.matcher(host).matches();
        for (final String site: sites) {
            // AN ENTRY IS READ LIKE A LINK, SO A PASTED https://site.com/page, site.com:8080 OR *.site.com STILL NAMES ITS SITE;
            // TWO COLONS WITHOUT BRACKETS CAN ONLY BE A BARE IPv6 (::1)
            String entry = site.strip();
            while (entry.startsWith("*.") || entry.startsWith(".")) entry = entry.substring(entry.indexOf('.') + 1);
            if (!entry.contains("://")) entry = "x://" + (entry.indexOf(':') != entry.lastIndexOf(':') && !entry.startsWith("[") ? "[" + entry + "]" : entry);
            final String name;
            try { name = host(URI.create(entry)); }
            catch (final IllegalArgumentException e) { continue; }
            if (host.equals(name) || !address && name != null && host.endsWith("." + name)) return true;
        }
        return false;
    }

    // WHETHER host IS THIS MACHINE OR ITS OWN NETWORK: localhost, mDNS NAMES (.local) AND LOOPBACK, PRIVATE OR LINK-LOCAL ADDRESSES
    private static boolean local(final String host) {
        if (host == null) return false;
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) return true;
        final boolean ipv6 = host.indexOf(':') >= 0;
        if (!ipv6 && !IPV4.matcher(host).matches()) return false;
        try {
            // BOTH SHAPES ARE LITERALS BY NOW, WHICH InetAddress PARSES WITHOUT ASKING THE DNS
            final InetAddress address = InetAddress.getByName(ipv6 ? "[" + host + "]" : host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC; // fc00::/7, THE PRIVATE RANGE OF IPv6
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** Hears a real status transition of a player; see {@link #onStatus(StatusListener)} for the threading and lifetime rules. */
    @FunctionalInterface
    public interface StatusListener {
        void onStatus(MediaPlayer player, Status previous, Status current);
    }

    /** Lifecycle states of a media player; see {@link MediaPlayer#status()}. */
    public enum Status {
        /** The media player is waiting for resources or conditions to start loading the media. */
        WAITING,
        /** The media player is in the process of loading the media. */
        LOADING,
        /** The media player is buffering data to ensure smooth playback. */
        BUFFERING,
        /** The media player is currently playing the media. */
        PLAYING,
        /** The media player is paused and can be resumed. */
        PAUSED,
        /** The media player is stopped and can be started from the beginning. */
        STOPPED,
        /** The media player has reached the end of the media. */
        ENDED,
        /** The media player has encountered an error and cannot continue playback. */
        ERROR;

        private static final Status[] VALUES = values();

        /** Returns the status with this ordinal; throws {@link IllegalArgumentException} when it is out of range. */
        public static Status of(final int value) {
            if (value < 0 || value >= VALUES.length) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_STATUS_ORDINAL) + "Failed to read the status due to invalid ordinal: " + value);
            return VALUES[value];
        }

        /** Whether a pause, resume, buffering, end, stop or failure can move this status straight to {@code next}; starting, seeking and restarting take their own paths. */
        public boolean allows(final Status next) {
            return switch (this) {
                // A stop() OR AN ABORTED RESTART CAN LAND BEFORE PLAYBACK BEGINS; LOADING LEAVES ONLY THROUGH A START OR A
                // TERMINAL STATE, SO LEAVING IT MEANS THE PIPELINE OPENED
                case WAITING -> next == LOADING || next == STOPPED;
                case LOADING -> next == STOPPED || next == ERROR;
                case BUFFERING -> next == PLAYING || next == PAUSED || next == ENDED || next == STOPPED || next == ERROR;
                case PLAYING -> next == PAUSED || next == BUFFERING || next == ENDED || next == STOPPED || next == ERROR;
                // A PIPELINE FULLY DRAINED WHILE PAUSED STILL REACHES ITS END
                case PAUSED -> next == PLAYING || next == BUFFERING || next == ENDED || next == STOPPED || next == ERROR;
                // ENDED HAS NO PIPELINE LEFT (A SEEK THERE IS A RESTART) AND ONLY stop()/release() LEAVE A FAILURE
                case ENDED, ERROR -> next == STOPPED;
                case STOPPED -> false; // TERMINAL UNTIL A RESTART
            };
        }
    }

    /**
     * Level of detail applied to video uploads, to tie media resolution to the viewer distance: each level keeps a
     * percentage of the maximum video dimensions ({@link #scale(int, int)} when set, else the source size).
     * Playback always starts at {@link #MAX}; see {@link MediaPlayer#lod(LodLevel)}.
     */
    public enum LodLevel {
        /** Full resolution, no LOD reduction. */
        MAX(100),
        /** Close range: 75% of the maximum dimensions. */
        CLOSE(75),
        /** Near range: 50% of the maximum dimensions. */
        NEAR(50),
        /** Far range: 25% of the maximum dimensions. */
        FAR(25),
        /** Barely visible: 10% of the maximum dimensions. */
        FAR_AWAY(10);

        private final int percent;

        LodLevel(final int percent) { this.percent = percent; }

        /** Returns the percentage (1-100) of the maximum video dimensions this level keeps. */
        public int percent() { return this.percent; }
    }

    /**
     * One audio a player can play, as {@link MediaPlayer#audioTracks()} lists it.
     *
     * @param track  where the audio comes from: {@link #MAIN} for the main input, else the index of the separate track in
     *               {@link MRL.Source#audioTracks()}
     * @param stream the FFmpeg stream index inside that input, or -1 while no run opened it ({@link #provisional()})
     * @param name   display name from the stream's title or the separate track, or null when neither gives one
     * @param lang   language tag from the stream (BCP 47, or the ISO 639-2 code a container carries) or the separate track,
     *               or null when unknown
     */
    public record AudioTrack(int track, int stream, String name, String lang) {
        /** Origin of the audio streams the main input carries. */
        public static final int MAIN = -1;

        /** Whether this entry stands for a separate track no run opened yet, whose streams are still unknown. */
        public boolean provisional() { return this.stream < 0; }
    }

    /**
     * Snapshot of a {@link ServerMediaPlayer}: everything a following player needs, taken with
     * {@link ServerMediaPlayer#snapshot()} on one side and fed to {@link MediaPlayer#sync(PlayerState)} on the other.
     * {@link #toBytes()} and {@link #read(ByteBuffer)} give it a fixed {@value #BYTES}-byte big-endian form for
     * hosts whose transport carries raw bytes.
     *
     * @param revision clock mutation counter at capture time, for the host to tell snapshots apart
     * @param status   playback status at capture time
     * @param time     playback position in milliseconds at capture time
     * @param duration total media duration in milliseconds, or 0 when unknown
     * @param speed    playback speed factor, in (0, 4]
     * @param repeat   whether playback loops at the end of the media
     * @param live     whether the media source is a live stream
     */
    public record PlayerState(int revision, Status status, long time, long duration, float speed, boolean repeat, boolean live) {

        /** Exact size in bytes of the binary form produced by {@link #toBytes()}. */
        public static final int BYTES = 27;

        private static final byte VERSION = 3; // BUMP ON ANY LAYOUT CHANGE, A Status REORDER INCLUDED (THE STATUS BYTE IS ITS ORDINAL)
        private static final int REPEAT_BIT = 0b01;
        private static final int LIVE_BIT = 0b10;

        public PlayerState {
            if (status == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_STATE_NULL_STATUS) + "Failed to create the player state due to null status");
            if (time < 0 || duration < 0) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_STATE_NEGATIVE_TIME) + "Failed to create the player state due to negative time or duration");
            // NaN FAILS BOTH COMPARISONS: THIS ALSO SHIELDS AGAINST CORRUPTED FLOAT PAYLOADS
            if (!(speed > 0f && speed <= 4f)) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_STATE_SPEED) + "Failed to create the player state due to invalid speed: " + speed + " (expected in (0, 4])");
        }

        /** Encodes this snapshot into its fixed binary form, a new heap buffer positioned at zero and ready to transmit. */
        public ByteBuffer toBytes() {
            final ByteBuffer buf = ByteBuffer.allocate(BYTES); // BIG-ENDIAN BY DEFAULT
            buf.put(VERSION);
            buf.putInt(this.revision);
            buf.put((byte) this.status.ordinal());
            buf.putLong(this.time);
            buf.putLong(this.duration);
            buf.putFloat(this.speed);
            buf.put((byte) ((this.repeat ? REPEAT_BIT : 0) | (this.live ? LIVE_BIT : 0)));
            return buf.flip();
        }

        /**
         * Decodes a snapshot produced by {@link #toBytes()}. Exactly {@value #BYTES} bytes are consumed and anything after
         * them is left in the buffer, so a snapshot may travel inside a larger frame carrying the host's own routing fields;
         * the buffer's byte order is irrelevant (the wire is big-endian) and a rejected payload leaves the position untouched.
         * @param buf a payload produced by {@link #toBytes()}
         * @return the decoded snapshot
         * @throws IllegalArgumentException if the payload is null, truncated, of another version or holds out-of-range values
         */
        public static PlayerState read(final ByteBuffer buf) {
            if (buf == null || buf.remaining() < BYTES) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_SNAPSHOT_PAYLOAD) + "Failed to read the snapshot due to null or truncated payload: " + BYTES + " bytes needed");
            // DECODE FROM A BIG-ENDIAN VIEW SO THE CALLER'S BYTE ORDER NEVER MATTERS, AND ADVANCE THE CALLER'S POSITION ONLY
            // ONCE THE WHOLE SNAPSHOT PARSED
            final ByteBuffer view = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
            final byte version = view.get();
            if (version != VERSION) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_PLAYER_SNAPSHOT_VERSION) + "Failed to read the snapshot due to unsupported version: " + version);
            final int revision = view.getInt();
            final Status status = Status.of(view.get() & 0xFF);
            final long time = view.getLong();
            final long duration = view.getLong();
            final float speed = view.getFloat();
            final byte flags = view.get();
            final PlayerState playerState = new PlayerState(revision, status, time, duration, speed, (flags & REPEAT_BIT) != 0, (flags & LIVE_BIT) != 0);
            buf.position(view.position());
            return playerState;
        }
    }

    // THE LAST SNAPSHOT FED AND WHEN IT ARRIVED, PUBLISHED TOGETHER SO A READER NEVER AGES ONE WITH THE OTHER'S STAMP
    private record Heard(PlayerState snapshot, long nanos) {}

    // A STATUS TRANSITION WAITING FOR DELIVERY; THE TERMINAL ONES CONSUME THE LISTENERS
    private record Transition(Status previous, Status current) {
        boolean terminal() { return this.current == Status.STOPPED || this.current == Status.ENDED || this.current == Status.ERROR; }
    }
}
