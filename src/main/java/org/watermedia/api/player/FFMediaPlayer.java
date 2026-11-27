package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVHWFramesContext;
import org.bytedeco.ffmpeg.avutil.AVPixFmtDescriptor;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.decoders.FFDecoder;
import org.watermedia.api.codecs.decoders.FrameQueue;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.FFTool;
import org.watermedia.tools.M3UTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.watermedia.WaterMedia.LOGGER;

/**
 * MediaPlayer implementation over the FFmpeg {@link FFDecoder}.
 * <p>A lifecycle thread opens one decoder per run and consumes the frames its demux, video
 * and audio threads produce, pacing them on the {@link MasterClock}. HLS inputs add a
 * short-lived probe thread that classifies the playlist as live or VOD while playback
 * already runs.
 * <p>Video frames are uploaded to GFXEngine as native YUV planes whenever the pixel
 * format is directly supported (YUV420P, NV12, etc.), avoiding CPU-side sws_scale.
 * For unsupported formats, sws_scale to BGRA is used as a fallback.
 * <p>When {@link #scale(int, int)} or a {@link LodLevel} below MAX is active, frames
 * are downscaled with sws_scale before upload, in the native pixel format when
 * supported as sws output, otherwise as BGRA. The target is resolved per frame, so
 * LOD changes apply on the fly without disturbing playback.
 * <p>With a {@link VKEngine} whose device decodes video, frames stay on the GPU: libplacebo
 * turns them into SDR RGBA there when the device runs it, and the engine scales them.
 */
public final class FFMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(FFMediaPlayer.class.getSimpleName());
    // PIPELINE THREADS ARE OWNED WORKERS (THEIR CALLBACKS CANNOT RUN LIFECYCLE OPERATIONS) NAMED FFThread-<PLAYER>-<ROLE>
    private static final ThreadFactory THREADS = ThreadTool.workerFactory("FFThread", Thread.NORM_PRIORITY);

    // A/V SYNC THRESHOLDS (SECONDS)
    private static final double AV_SYNC_TOO_EARLY_SEC = 0.040;
    // AUDIO FEEDS THE SFX ENGINE EAGERLY: ITS BUFFER POOL IS THE BACKPRESSURE (upload() FALSE WHEN FULL) AND ITS QUEUE DEPTH
    // ABSORBS GAME HITCHES. THIS LEAD ONLY CAPS BOGUS PTS JUMPS AND MUST STAY WELL BELOW FFDecoder.PTS_DISCONTINUITY_SEC
    private static final double AUDIO_MAX_LEAD_SEC = 0.5;

    // PERFORMANCE MONITORING: THE CLOCK RATE IS SAMPLED AND CHECKED EVERY INTERVAL; SLOW CONSUMPTION ITERATIONS ARE
    // COUNTED AND REPORTED ONCE WHEN THE RUN ENDS
    private static final long PERF_CHECK_INTERVAL_MS = 2_000;
    private static final double PERF_RATE_WARN_THRESHOLD = 0.90;
    private static final String SLOW_REPORT = "Slow consumption iterations: {} in {}ms (worst={}ms, audio={}ms, video={}ms, frame={}ms)";

    // BUFFERING: HOW LONG A STREAM MAY HAVE NOTHING TO PLAY OR THE PICTURE MAY TRAIL ITS DECODER BEFORE BOTH WAIT, AND THE MEDIA EVERY
    // STREAM QUEUES BEFORE A STARVED ONE RESUMES, DOUBLED WHEN IT STARVES AGAIN WITHIN THE WINDOW
    private static final long STARVED_MS = 300;
    private static final long BEHIND_MS = 3_000;
    private static final long CUSHION_MS = 1_000;
    private static final long CUSHION_WINDOW_MS = 30_000;
    // HOW FAR PAST THE TARGET OF ITS SEEK THE SESSION OF A FOLLOWER MAY BE FOR THE LANDING TO FOLLOW IT, AND HOW FAR AHEAD A SEEK IS
    // SERVED BY DROPPING WHAT WAS READ (VP9 AND AV1 DECODE EVERY FRAME THEY DROP)
    private static final long GUIDE_RANGE_MS = 10_000;
    private static final long CATCH_UP_MS = 3_000;
    // THE SOUND A REPEAT MAY CUT WHEN IT FLUSHES THE ENGINE
    private static final long TAIL_MS = 50;
    // BUFFER SETS OF THE ROTATING UPLOAD POOL (SEE planePool)
    private static final int PLANE_POOL_SETS = 4;
    // A RESUME AFTER THIS LONG ON HOLD READS AN HLS INPUT AGAIN (SEE pause)
    private static final long REREAD_HOLD_MS = 10_000L;

    private static int players; // GUARDED BY THREADS

    // DECODER: ONE PER RUN (A QUALITY SWITCH OPENS ANOTHER), null BETWEEN RUNS
    private volatile FFDecoder decoder;
    private final FFDecoder.Host host = new Callbacks();

    // CONVERSION: FORMAT CONVERSION AND/OR scale/LOD DOWNSCALE
    private SwsContext swsContext;
    // THE POOL OF THE LAST DECODED FRAME AND ITS SOFTWARE LAYOUT (CONSUME THREAD ONLY), MAPPED ONCE PER POOL
    private long cachedFramesCtx;
    private int cachedFramesFormat = AV_PIX_FMT_NONE;
    private int swsInputFormat = AV_PIX_FMT_NONE;
    private int swsInputWidth;
    private int swsInputHeight;
    private Colorimetry swsColor; // COLORIMETRY THE CURRENT CONTEXT CONVERTS TO BGRA WITH, null UNTIL SET

    // THREADS: lifecycleLock SERIALIZES start()/stop()/release() AGAINST EACH OTHER; IT IS NEVER
    // HELD ACROSS A JOIN, SO A LIFECYCLE PUBLISHING ITS LAST STATUS CANNOT DEADLOCK A RELEASE
    private final String group;
    private final Object lifecycleLock = new Object();
    private final Object releaseLock = new Object();
    private volatile Thread lifecycleThread;

    // STATUS + SYNCHRONIZATION
    private final MasterClock clock = new MasterClock();
    private volatile boolean switchRequest = false; // A QUALITY OR AUDIO TRACK CHANGE THE CONSUMPTION LOOP APPLIES WITH A NEW DECODER
    private volatile boolean released = false; // ONCE release() RUNS THE ENGINES ARE FREED: REJECT NEW start()s
    private volatile boolean releaseDone; // SET ONCE THE ENGINES ARE FREED, SO A REPEATED release() IS A NO-OP
    private volatile boolean releasing; // A release() IS JOINING THE PIPELINE: STATUS DELIVERY WAITS FOR IT (SEE holdStatus)
    private volatile boolean playable; // THE SOURCE OPENED AT LEAST ONCE AND NOTHING FAILED SINCE (canPlay())
    private volatile float fps; // 0 UNTIL THE VIDEO STREAM REPORTS A PLAUSIBLE RATE; AUDIO-ONLY STAYS 0
    private volatile long displayTimeMs; // HOW LONG A SINGLE-FRAME SOURCE STAYS UP BEFORE ENDED (0 = FOREVER)
    private volatile int runSerial; // BUMPED PER lifecycle() RUN SO ASYNC PROBES CANNOT WRITE INTO A LATER RUN
    private volatile long seekTargetMs; // WHERE THE SEEK STAMPED IN seekNanos WAS SENT, FOR ITS LANDING REPORT
    private volatile Boolean hlsLiveSource;

    // NATIVE-FREE SNAPSHOTS FOR PUBLIC GETTERS: duration()/live()/canPlay() RUN ON THE GAME/RENDER THREAD WHILE THE
    // LIFECYCLE THREAD MAY BE CLOSING THE DECODER, AND READING ITS NATIVE STRUCTS THERE IS A USE-AFTER-FREE (JVM CRASH)
    private volatile boolean opened;
    private volatile long mediaDurationMs = NO_DURATION;

    // AUDIO TRACKS: THE LIST HOSTS READ (IMMUTABLE, REPLACED WHOLE ON EACH OPEN BY THE DECODER'S DEMUX THREAD), THE TRACK THE
    // CURRENT OR LAST RUN DECODES AND THE HOST'S PICK, WHICH SURVIVES QUALITY SWITCHES AND RESTARTS (null LETS THE DECODER CHOOSE)
    private volatile List<AudioTrack> audioTracks;
    private volatile AudioTrack audioTrack;
    private volatile AudioTrack audioPick;

    // SWS DESTINATION FRAME (LAZY-INIT, ONLY WHEN sws_scale IS NEEDED)
    private AVFrame scaledFrame;
    // A FRAME DECODED ON THE ENGINE'S DEVICE IN A LAYOUT IT CANNOT SAMPLE, BROUGHT DOWN TO RAM (LAZY-INIT)
    private AVFrame downloadedFrame;

    // ROTATING PLANE POOL FOR UPLOADS: FrameQueue RECYCLES FRAME DATA AFTER next() AND THE RENDER THREAD MAY RUN A QUEUED
    // UPLOAD AFTER SEVERAL LATER FRAMES (~1ms APART), SO ONE BUFFER SET WOULD TEAR MID-READ; THE SETS COVER THE REALISTIC
    // BURST DEPTH (POST-HITCH CATCH-UP ≈ skipThreshold)
    private final ByteBuffer[][] planePool = new ByteBuffer[PLANE_POOL_SETS][];
    private int planePoolIdx;
    // BASE-ADDRESS ALIGNMENT THE GFXENGINE WANTS FOR ZERO-COPY HOST IMPORT (-1 = NOT YET QUERIED, 0 = NONE).
    // WHEN > 0 THE POOLED BUFFERS ARE PAGE-ALIGNED VIA MemoryUtil AND MUST BE FREED EXPLICITLY.
    private int planeAlign = -1;

    // STATS
    private long totalSkippedFrames = 0;
    private long totalRenderedFrames = 0;

    // FORMAT CHANGE DETECTION (RENDER THREAD ONLY)
    private int lastFrameWidth;
    private int lastFrameHeight;
    private PixelFormat lastFormat;
    private int lastBitsPerComponent;
    private Colorimetry lastColor;
    // TAGS, FORMAT AND SIZE OF THE LAST MAPPED FRAME: THE COLOR MAPPING RE-RUNS ONLY WHEN ONE OF THEM CHANGES
    private long colorTags = -1L;
    private int colorWidth;
    private int colorHeight;
    private Colorimetry frameColor;
    private boolean uploadRefused; // THE ENGINE REFUSED THE LAST LAYOUT: FRAMES DROP UNTIL IT CHANGES
    // FFTool.Pixels CACHE (CONSUME THREAD ONLY): THE STREAM PIXEL FORMAT IS STABLE, SO MAP ONCE PER
    // FORMAT CHANGE INSTEAD OF ALLOCATING A MAPPING EVERY FRAME IN THE UPLOAD HOT PATH.
    private int cachedUploadFmt = Integer.MIN_VALUE;
    private FFTool.Pixels cachedUploadMapping;

    // WHAT THE RUN PLAYS (LIFECYCLE THREAD ONLY): THE DECODER GENERATION AND THE CLOCK EPOCH, WHETHER THEIR FRAMES LANDED (THE CLOCK RUNS
    // FROM FRAMES EVERY STREAM HAS) AND THE FLOOR SENT TO THE DECODER, BELOW WHICH NOTHING PLAYS
    private int generation;
    private int epoch;
    private boolean settled;
    private boolean fromStart; // THE LANDING PLAYS FROM THE BEGINNING OF THE MEDIA
    private long floorMs = Long.MIN_VALUE;
    // THE MEDIA A REBUFFER WAITS FOR, AND WHERE THE FOLLOWER SAYS THE SESSION IS WHILE A SEEK FLIES
    private volatile long cushionMs;
    private volatile long guideMs = Long.MIN_VALUE;

    // PERFORMANCE COUNTERS
    private long perfLastCheckMs;
    private long perfLastClockMs;
    private double perfLastSpeed = 1.0;
    private int perfAudioUploads;
    private int perfVideoRenders;

    // ANOMALY LATCHES: EACH REPEATABLE ANOMALY IS LOGGED ONCE PER PLAYER
    private boolean rateWarned;
    private boolean refusedWarned;
    private boolean decodedWarned;
    private boolean starvationWarned;
    private boolean behindWarned;

    // NOTHING IS OPENED UNTIL start(); THE HEAVY FFMPEG SETUP RUNS ON THE DECODER'S THREADS SPAWNED THEN.
    // A NULL gfx MEANS AUDIO-ONLY AND A NULL sfx VIDEO-ONLY PLAYBACK
    FFMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfx, final SFXEngine sfx) {
        super(mrl, sourceIndex, gfx, sfx);
        synchronized (THREADS) {
            this.group = "FFThread-" + players++;
        }
        // EVERY SEPARATE TRACK IS A PROVISIONAL ENTRY UNTIL A RUN OPENS IT; WITHOUT AN AUDIO ENGINE THERE IS NOTHING TO PICK
        final List<AudioTrack> provisional = new ArrayList<>();
        final List<Track> tracks = sfx == null ? List.of() : this.source.audioTracks();
        for (int i = 0; i < tracks.size(); i++) provisional.add(new AudioTrack(i, -1, tracks.get(i).name(), tracks.get(i).lang()));
        this.audioTracks = List.copyOf(provisional);
        // EVERY CLOCK TRANSITION (INCLUDING THE ONES THE PIPELINE MAKES INTERNALLY) REACHES THE LISTENERS, HELD WHILE A RELEASE
        // JOINS THE PIPELINE (SEE holdStatus)
        this.clock.listener((previous, current) -> {
            this.queueStatus(previous, current);
            this.deliverStatus();
        });
        this.arm();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public void release() {
        // FAIL FAST FROM THE PLAYER'S OWN THREADS BEFORE TOUCHING ANY STATE, SO THE HOST CAN RETRY LATER
        final Thread caller = Thread.currentThread();
        final FFDecoder decoder = this.decoder;
        if (caller == this.lifecycleThread || decoder != null && decoder.owns(caller))
            throw new IllegalStateException(DataTool.errCode(WaterMedia.ERR_FFPLAYER_RELEASE_CALLBACK) + "Failed to release the player from a playback callback, release it from its host thread");
        // ONE RELEASE AT A TIME: A CONCURRENT SECOND CALL WAITS AND THEN FINDS THE WORK DONE. STOPPED AND WHATEVER THE PIPELINE
        // PUBLISHED WHILE IT WAS JOINED ARE HEARD AFTER THE LOCK, SO A LISTENER CAN WAIT FOR A THREAD THAT CALLS INTO THIS PLAYER
        try {
            synchronized (this.releaseLock) {
                this.releasing = true;
                this.disarm();
                final Thread lifecycle;
                synchronized (this.lifecycleLock) {
                    if (this.releaseDone) return;
                    LOGGER.debug(IT, "Release requested");
                    this.released = true; // GATE OUT ANY LATER start(): THE ENGINES ARE ABOUT TO BE FREED
                    // TEAR DOWN DIRECTLY RATHER THAN THROUGH stop(): THE RELEASE PUBLISHES ITS OWN STOPPED ONCE THE PIPELINE IS JOINED
                    this.abort();
                    lifecycle = this.lifecycleThread;
                }
                // WAIT FOR THE PIPELINE TO EXIT BEFORE FREEING ENGINES: THE CONSUMPTION LOOP KEEPS TOUCHING sfx/gfx
                // AND NATIVE CONTEXTS UNTIL IT FINISHES; abort() CUTS THE DECODER'S NATIVE I/O SHORT
                if (lifecycle != null) ThreadTool.join(lifecycle);
                // RETIRE EACH GPU IMPORT BEFORE FREEING ITS HOST BUFFER, WHILE THE GRAPHICS ENGINE IS LIVE.
                // AUDIO ADAPTER CLEANUP MAY THROW, SO THE DECODED PLANE POOL MUST ALREADY BE RELEASED.
                this.freePlanePool();
                this.playable = false;
                this.clock.halt(); // EVERY PLAYER LANDS STOPPED ON RELEASE, EVEN FROM ERROR
                this.releaseDone = true;
                super.release();
            }
        } finally {
            this.releasing = false;
            this.clock.drain();
            this.deliverStatus();
        }
    }

    @Override
    public boolean pause(final boolean paused) {
        // THE ORIGIN CUTS A SEGMENT DOWNLOAD LEFT IDLE AND FFMPEG SKIPS ITS REST: A LONG HOLD RESUMES BY READING AGAIN
        final FFDecoder decoder = this.decoder;
        final long heldMs = this.clock.heldMs();
        final boolean reread = !paused && heldMs >= REREAD_HOLD_MS && decoder != null && decoder.hls() && this.canSeek()
                && !decoder.ended() && this.clock.reread();
        final boolean changed = reread || this.clock.paused(paused);
        // THE AUDIO ENGINE FOLLOWS, OR OPENAL PLAYS ITS QUEUE INTO AN UNDERRUN AND RESUMES FROM A STOPPED SOURCE (AUDIBLE
        // GAP). ONLY ON A REAL TRANSITION: play() ON A TERMINAL STATE (ENDED/STOPPED) WOULD REPLAY THE RESIDUAL QUEUE
        if (changed && this.sfx != null) {
            if (paused) this.sfx.pause();
            // A CLOCK THAT STILL BUFFERS (SEEK, REREAD) STAYS QUIET: ITS LANDING STARTS THE ENGINE
            else if (this.clock.status() == Status.PLAYING) this.sfx.play();
        }
        if (reread) LOGGER.debug(IT, "Resume after a {}ms hold reads the HLS input again from {}ms", heldMs, this.time());
        else if (changed) LOGGER.debug(IT, "Pause -> {}", paused);
        return changed;
    }

    @Override
    public boolean stop() {
        synchronized (this.lifecycleLock) {
            final Thread lifecycle = this.lifecycleThread;
            if (lifecycle != null && lifecycle.isAlive() && !lifecycle.isInterrupted()) return this.abort();
        }
        // AN ENDED OR FAILED PIPELINE IS ALREADY GONE: stop() ONLY MOVES THE STATUS AND REWINDS THE CLOCK.
        // OUTSIDE THE LOCK BECAUSE THE TRANSITION NOTIFIES THE LISTENER; A RESTART RACING IT RE-CHECKS LOADING
        final Status status = this.clock.status();
        return (status == Status.ENDED || status == Status.ERROR) && this.clock.transition(Status.STOPPED);
    }

    @Override
    public boolean togglePlay() {
        return switch (this.clock.status()) {
            case ENDED, STOPPED -> this.launch(false, -1L);
            case WAITING, ERROR -> false;
            default -> this.pause(!this.clock.pauseRequested());
        };
    }

    @Override
    public boolean seek(final long timeMs) {
        return this.requestClampedSeek(timeMs, true, false);
    }

    @Override
    public boolean seekQuick(final long timeMs) {
        return this.requestClampedSeek(timeMs, false, false);
    }

    // FRAME STEPPING IS A PAUSED-ONLY CONTROL ON EVERY PLAYER
    @Override
    public boolean previousFrame() {
        if (!super.previousFrame() || !this.paused() || !this.canSeek()) return false;
        return this.clock.requestSeek(Math.max(0, this.time() - this.clock.frameDurationMs()), true, false);
    }

    @Override
    public boolean nextFrame() {
        if (!super.nextFrame() || !this.paused() || !this.canSeek()) return false;
        final long duration = this.duration();
        final long target = this.time() + this.clock.frameDurationMs();
        // THE NEXT FRAME IS ALMOST ALWAYS READ ALREADY, SO THE STEP DROPS UP TO IT INSTEAD OF ASKING THE INPUT AGAIN
        return this.clock.requestSeek(duration > 0 ? Math.min(target, duration) : target, true, true);
    }

    @Override
    public float fps() { return this.fps; }

    @Override
    public Status status() { return this.clock.status(); }

    @Override
    public void displayTime(final long ms) { this.displayTimeMs = ms; }

    @Override
    public long displayTime() { return this.displayTimeMs; }

    @Override
    public boolean live() {
        final Boolean hls = this.hlsLiveSource;
        if (hls != null) return hls;
        return this.opened && this.mediaDurationMs == NO_DURATION;
    }

    @Override
    public boolean canSeek() { return this.playable && !this.live(); }

    @Override
    public boolean canPlay() { return this.playable; }

    @Override
    public long duration() { return this.live() ? NO_DURATION : this.mediaDurationMs; }

    @Override
    public int buffered() {
        // A PIPELINE THAT IS PLAYING OR PAUSED IS KEEPING UP, AND ONE THAT READ ITS INPUT TO THE END HAS NOTHING LEFT TO WAIT FOR.
        // LOADING AND BUFFERING REPORT THE STREAM WITH THE LEAST QUEUED, AGAINST THE MEDIA THE PLAYER WAITS FOR
        final Status status = this.clock.status();
        final FFDecoder decoder = this.decoder;
        if (decoder == null) return 0;
        if (this.opened && (status == Status.PLAYING || status == Status.PAUSED || decoder.ended())) return 100;
        final long wanted = Math.max(CUSHION_MS, this.cushionMs);
        return (int) (Math.min(decoder.buffered(), wanted) * 100L / wanted);
    }

    @Override
    public void quality(final MediaQuality quality) {
        super.quality(quality);
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle != null && lifecycle.isAlive() && !lifecycle.isInterrupted()) {
            this.switchRequest = true;
            LOGGER.debug(IT, "Quality switch requested -> {}", quality);
        }
    }

    @Override
    public List<AudioTrack> audioTracks() { return this.audioTracks; }

    @Override
    public AudioTrack audioTrack() { return this.audioTrack; }

    @Override
    public boolean audioTrack(final AudioTrack track) {
        if (track == null) throw new IllegalArgumentException(DataTool.errCode(WaterMedia.ERR_FFPLAYER_NULL_TRACK) + "Failed to select the audio track due to null track");
        if (this.sfx == null || this.released) return false;
        // ONLY AN ENTRY OF THE LIST COUNTS, FOUND BY ORIGIN AND STREAM; A PROVISIONAL PICK IS THE STREAM ITS ORIGIN PLAYS ONCE OPEN
        AudioTrack entry = null;
        for (final AudioTrack listed: this.audioTracks)
            if (listed.track() == track.track() && listed.stream() == track.stream()) entry = listed;
        final AudioTrack pick = this.audioPick;
        final AudioTrack playing = this.audioTrack;
        final AudioTrack current = pick == null || pick.provisional() && playing != null && playing.track() == pick.track() ? playing : pick;
        if (entry == null || current != null && current.track() == entry.track() && current.stream() == entry.stream()) return false;
        this.audioPick = entry;
        // A RUNNING PIPELINE SWITCHES ON ITS LOOP; ANY OTHER PLAYS THE PICK FROM ITS NEXT START
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle != null && lifecycle.isAlive() && !lifecycle.isInterrupted()) this.switchRequest = true;
        LOGGER.debug(IT, "Audio track switch requested -> {}", entry);
        return true;
    }

    @Override
    public long time() { return this.clock.timeMs(); }

    @Override
    public boolean speed(final float speed) {
        if (super.speed(speed)) {
            this.clock.speed(speed);
            LOGGER.debug(IT, "Speed -> {}x", speed);
            return true;
        }
        return false;
    }

    /** Whether the active video pipeline decodes on the GPU (a hardware decoder is in use). */
    public boolean hwAccel() {
        final FFDecoder decoder = this.decoder;
        return decoder != null && decoder.hardware();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // LAUNCHES A LIFECYCLE RUN, ABORTING THE ONE IN FLIGHT. THE PAUSE INTENT AND THE INITIAL SEEK TRAVEL
    // WITH THE RUN (THE CLOCK IS RESET BY IT); THE THREAD STARTS UNDER THE LOCK SO release() ALWAYS JOINS IT
    @Override
    boolean launch(final boolean paused, final long seekMs) {
        synchronized (this.lifecycleLock) {
            // release() FREES gfx/sfx: REBUILDING THE PIPELINE ON DEAD ENGINES WOULD CRASH ON UPLOAD
            if (this.released) {
                LOGGER.debug(IT, "start() ignored: player already released");
                return false;
            }
            LOGGER.debug(IT, "Start requested (quality={}, paused={}, at={}ms)", this.quality, paused, seekMs);
            this.exception = null;
            final Thread oldThread = this.lifecycleThread;
            // A RUN THAT ALREADY ENDED OR FAILED IS ONLY TEARING DOWN: THE NEW RUN JOINS IT INSTEAD OF ABORTING IT, SO NO
            // STRAY STOPPED CONSUMES THE LISTENERS A TERMINAL CALLBACK JUST REGISTERED
            final Status current = this.clock.status();
            if (oldThread != null && oldThread.isAlive() && !oldThread.isInterrupted() && current != Status.ENDED && current != Status.ERROR)
                this.abort();
            final Thread thread = this.thread("lifecycle", () -> {
                // join RETURNS false WHEN THIS THREAD IS INTERRUPTED WHILE WAITING: THE RESTART WAS
                // DROPPED, SO LEAVE A CLEAN TERMINAL STATE INSTEAD OF THE PREVIOUS RUN'S STALE STATUS
                if (oldThread != null && !ThreadTool.join(oldThread)) {
                    LOGGER.warn(IT, "Restart aborted: interrupted while joining the previous lifecycle");
                    this.publishTransition(Status.STOPPED);
                    return;
                }
                // THE PREVIOUS RUN MAY HAVE RECORDED A FAILURE WHILE IT TORE DOWN; IT IS GONE NOW, SO ITS FAILURE IS TOO
                this.exception = null;
                this.lifecycle(paused, seekMs);
            });
            this.lifecycleThread = thread;
            thread.start();
        }
        return true;
    }

    @Override
    boolean holdStatus() { return this.releasing; }

    // BEHIND THE SESSION, WHAT WAS ALREADY READ MAY REACH IT: THE CONSUMPTION LOOP DROPS UP TO THERE, OR SEEKS WHEN IT DOES NOT REACH
    @Override
    boolean catchUp(final long ms) { return this.requestClampedSeek(ms, false, true); }

    // READ BY THE CONSUMPTION LOOP WHILE A SEEK HAS NOT LANDED
    @Override
    void guide(final long ms) { this.guideMs = ms; }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // FREES THE ROTATING PLANE POOL. PAGE-ALIGNED BUFFERS (memAlignedAlloc, FOR ZERO-COPY VULKAN IMPORT) ARE
    // NOT GC-MANAGED AND MUST BE FREED EXPLICITLY; allocateDirect SETS ARE LEFT TO THE GC.
    private void freePlanePool() {
        if (this.planeAlign > 0) {
            for (final ByteBuffer[] set: this.planePool) {
                if (set == null) continue;
                for (int i = 0; i < set.length; i++) {
                    if (set[i] != null) {
                        this.gfx.release(set[i]); // DRAINS GPU READS AND DROPS THE IMPORT BEFORE FREEING HOST MEMORY
                        MemoryUtil.memAlignedFree(set[i]);
                    }
                    set[i] = null;
                }
            }
        }
        Arrays.fill(this.planePool, null);
        this.planePoolIdx = 0;
        this.planeAlign = -1;
    }

    // INTERRUPTS THE PIPELINE AND ABORTS BLOCKING NATIVE I/O. SHARED BY stop(), THE RESTART PATH IN launch() AND release()
    private boolean abort() {
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle != null) {
            LOGGER.debug(IT, "Stop requested (status={}, clock={}ms)", this.clock.status(), this.clock.timeMs());
            final FFDecoder decoder = this.decoder;
            if (decoder != null) decoder.abort(); // ABORT BLOCKING NATIVE I/O (OPEN/READ) ASAP
            lifecycle.interrupt();
            return true;
        }
        return false;
    }

    // CLAMP ONLY AGAINST A KNOWN DURATION: duration() RETURNS NO_DURATION (0)
    // FOR UNPROBED/UNKNOWN MEDIA, AND CLAMPING AGAINST THAT SENDS EVERY SEEK TO 0.
    private boolean requestClampedSeek(final long timeMs, final boolean precise, final boolean ahead) {
        if (!this.canSeek()) {
            LOGGER.debug(IT, "Seek to {}ms ignored: source is not seekable", timeMs);
            return false;
        }
        final long duration = this.duration();
        final long target = Math.max(0, duration > 0 ? Math.min(timeMs, duration) : timeMs);
        LOGGER.debug(IT, "Seek requested -> {}ms (precise={}, clamped from {}ms)", target, precise, timeMs);
        // A FINISHED OR STOPPED PIPELINE IS GONE: SCRUBBING IT MEANS A PAUSED RESTART AT THE TARGET
        final Status status = this.clock.status();
        if (status == Status.ENDED || status == Status.STOPPED) return this.launch(true, target);
        if (!this.clock.requestSeek(target, precise, ahead)) return false;
        // STAMPED ONCE THE CLOCK BUFFERS: THE FOLLOWER TIMES THE LANDING AND THE CONSUMPTION LOOP REPORTS IT
        this.seekTargetMs = target;
        this.seekNanos = System.nanoTime();
        return true;
    }

    // TERMINAL TRANSITION: THE CLOCK NOTIFIES THE LISTENER ITSELF (SEE THE CONSTRUCTOR); A FAILURE
    // ALSO ENDS THE MEDIA'S PLAYABLE LIFE UNTIL THE NEXT start()
    private void publishTransition(final Status next) {
        if (next == Status.ERROR) this.playable = false;
        this.clock.transition(next);
    }

    // COPIES THE FRAME PLANES INTO A POOLED BUFFER SET AND UPLOADS IT: GLEngine MAY RUN THE UPLOAD LATER ON THE RENDER THREAD,
    // BUT FrameQueue.next() RECYCLES THE FRAME DATA. STRIDES ARE IN BYTES (FFMPEG LINESIZE CONVENTION)
    private void uploadNativePlanes(final AVFrame frame, final PixelFormat cs, final int height) {
        if (this.gfx == null) return;
        // QUERY THE ENGINE'S BUFFER ALIGNMENT ONCE: NON-ZERO ASKS FOR PAGE-ALIGNED POOLS SO A ZERO-COPY ENGINE CAN IMPORT THE HOST POINTER
        if (this.planeAlign < 0) this.planeAlign = this.gfx.alignment();

        // GRAB THE NEXT BUFFER SET FROM THE ROTATING POOL (SEE planePool), ALLOCATING ON FIRST USE
        ByteBuffer[] planes = this.planePool[this.planePoolIdx];
        if (planes == null) {
            planes = new ByteBuffer[4];
            this.planePool[this.planePoolIdx] = planes;
        }
        this.planePoolIdx = (this.planePoolIdx + 1) % PLANE_POOL_SETS;
        // EACH PLANE COPIES ITS linesize TIMES ITS ROWS; PixelFormat ROUNDS SUBSAMPLED ROWS UP LIKE FFMPEG ALLOCATES THEM
        final ByteBuffer[] bufs = new ByteBuffer[cs.planes()];
        final int[] strides = new int[bufs.length];
        for (int i = 0; i < bufs.length; i++) {
            strides[i] = frame.linesize(i);
            final long size = (long) strides[i] * cs.planeHeight(i, height);
            planes[i] = this.ensurePlane(planes[i], size);
            // DIRECT NATIVE-TO-NATIVE COPY, NO WRAPPER ByteBuffer PER PLANE PER FRAME; CLEARED FIRST SO memAddress IS THE BASE
            final BytePointer src = frame.data(i);
            planes[i].clear();
            MemoryUtil.memCopy(src.address() + src.position(), MemoryUtil.memAddress(planes[i]), size);
            planes[i].limit((int) size);
            bufs[i] = planes[i];
        }
        this.gfx.upload(bufs, strides);
    }

    private ByteBuffer ensurePlane(final ByteBuffer buf, final long needed) {
        if (buf != null && buf.capacity() >= needed) return buf;
        if (this.planeAlign > 0) {
            // PAGE-ALIGNED DIRECT BUFFER SO A VULKAN ENGINE CAN IMPORT THE HOST POINTER (VK_EXT_external_memory_host).
            // ROUND THE SIZE UP TO THE ALIGNMENT: THE IMPORT REQUIRES BOTH BASE AND SIZE TO BE MULTIPLES OF IT.
            if (buf != null) {
                // DROP THE ENGINE'S IMPORT OF THE OLD POINTER FIRST: FREEING HOST MEMORY UNDER A
                // LIVE IMPORT (OR RECYCLING ITS ADDRESS) CRASHES THE DRIVER ON THE LATER vkFreeMemory.
                this.gfx.release(buf);
                MemoryUtil.memAlignedFree(buf);
            }
            final long rounded = (needed + this.planeAlign - 1) / this.planeAlign * this.planeAlign;
            return MemoryUtil.memAlignedAlloc(this.planeAlign, (int) rounded);
        }
        return ByteBuffer.allocateDirect((int) needed);
    }

    // LAZY-INIT SWS RESOURCES FOR FORMAT CONVERSION AND/OR DOWNSCALE. RETURNS FALSE IF INIT FAILS.
    private boolean ensureScaler(final int srcWidth, final int srcHeight, final int srcFormat,
                                 final int dstWidth, final int dstHeight, final int dstFormat, final Colorimetry color) {
        // (RE)ALLOCATE THE DESTINATION FRAME ON FIRST USE OR A TARGET CHANGE, AND TRACK IT: AFTER A REALLOC THE scaledFrame
        // CHECKS BELOW ALWAYS MATCH, SO AN UNTRACKED ONE KEEPS A SWS CONTEXT FOR THE OLD TARGET (HEAP CORRUPTION IN sws_scale)
        boolean frameRecreated = false;
        if (this.scaledFrame == null
                || this.scaledFrame.width() != dstWidth
                || this.scaledFrame.height() != dstHeight
                || this.scaledFrame.format() != dstFormat) {
            if (this.scaledFrame != null) avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = avutil.av_frame_alloc();
            this.scaledFrame.format(dstFormat);
            this.scaledFrame.width(dstWidth);
            this.scaledFrame.height(dstHeight);
            if (avutil.av_frame_get_buffer(this.scaledFrame, 32) < 0) {
                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SCALED_ALLOC) + "Failed to allocate scaled frame buffer");
                avutil.av_frame_free(this.scaledFrame);
                this.scaledFrame = null;
                return false;
            }
            frameRecreated = true;
        }

        // (RE)CREATE SWS CONTEXT ON SOURCE FORMAT/DIMENSION CHANGE
        if (this.swsContext == null || this.swsInputFormat != srcFormat
                || this.swsInputWidth != srcWidth || this.swsInputHeight != srcHeight || frameRecreated)
            this.recreateSwsContext(srcWidth, srcHeight, srcFormat, dstWidth, dstHeight, dstFormat);

        // BGRA OUTPUT NEEDS THE SOURCE MATRIX AND RANGE, OR SWS CONVERTS EVERY YUV FRAME AS BT.601; IT CANNOT TONE MAP, SO
        // HDR REACHES SOFTWARE ENGINES UNMAPPED. YUV OUTPUT KEEPS ITS SAMPLES AND THE ENGINE CONVERTS THEM
        if (this.swsContext != null && dstFormat == AV_PIX_FMT_BGRA && !color.equals(this.swsColor)) {
            final IntPointer coefficients = swscale.sws_getCoefficients(switch (color.matrix()) {
                case BT601 -> swscale.SWS_CS_ITU601;
                case BT709 -> swscale.SWS_CS_ITU709;
                case BT2020 -> swscale.SWS_CS_BT2020;
            });
            final int details = swscale.sws_setColorspaceDetails(this.swsContext, coefficients, color.full() ? 1 : 0, coefficients, 1, 0, 1 << 16, 1 << 16);
            if (details < 0) LOGGER.warn(IT, "The scaler refused {} (code {}) and keeps its default colorimetry", color, details);
            this.swsColor = color;
        }
        return this.swsContext != null;
    }

    // EAGER AUDIO UPLOADS WHILE THE CLOCK RUNS, LIFECYCLE LOOP ONLY (A SECOND CONSUMER RACES THE PIPELINE). THE ENGINE
    // POOL IS THE BACKPRESSURE AND THE CLOCK TRACKS THE AUDIBLE POSITION; A JUST-IN-TIME GATE
    // UNDERRUNS ON EVERY HICCUP; AUDIO_MAX_LEAD_SEC ONLY CAPS RUNAWAY PTS JUMPS.
    private boolean drainAudio(final FFDecoder decoder) {
        if (this.sfx == null || !decoder.audio()) return false;
        final FrameQueue frames = decoder.audioFrames();
        boolean didWork = false;
        while (true) {
            final FrameQueue.Slot slot = frames.peek();
            if (slot == null) break;

            // LEFT BEHIND BY A SEEK, OR ENDS BEFORE THE FLOOR
            if (slot.generation != this.generation || slot.ptsMs + slot.durationMs <= this.floorMs) {
                frames.next();
                didWork = true;
                continue;
            }
            if (slot.ptsMs / 1_000.0 - this.clock.time() > AUDIO_MAX_LEAD_SEC) break;

            final long framePtsMs = slot.ptsMs;
            final long frameDurationMs = slot.durationMs;

            // THE DECODER QUEUES INTERLEAVED PCM IN THE LAYOUT THE ENGINE WAS FORMATTED WITH
            final int bytesPerSample = av_get_bytes_per_sample(slot.frame.format());
            final int dataSize = slot.frame.nb_samples() * slot.frame.ch_layout().nb_channels() * bytesPerSample;
            final ByteBuffer audioData = slot.frame.data(0)
                    .limit(dataSize)
                    .asBuffer()
                    .clear();

            // ENGINE FULL: ITS BUFFER POOL IS THE BACKPRESSURE; STOP UNTIL IT FREES SLOTS
            if (!this.sfx.upload(audioData)) {
                // RE-ASSERT PLAY EVEN ON FAILURE: A STALE MAINTENANCE pause() RACING A RESUME WOULD
                // OTHERWISE DEADLOCK: A PAUSED ENGINE NEVER FREES BUFFERS, SO UPLOADS FAIL FOREVER
                if (!this.clock.pauseRequested()) this.sfx.play();
                break;
            }

            // DON'T START THE SOURCE WHEN A PAUSE IS REQUESTED: AN UPLOAD THAT RACES IT WOULD LEAK A FEW MS OF AUDIO
            if (!this.clock.pauseRequested()) this.sfx.play();
            frames.next();
            this.perfAudioUploads++;

            // CLOCK = AUDIBLE POSITION: THE FRAME PTS ALONE WOULD RUN AHEAD BY THE ENGINE'S QUEUE
            // DEPTH, SO SUBTRACT THE PENDING (LATENCY-COMPENSATED) AUDIO FROM THIS FRAME'S END.
            this.clock.update((framePtsMs + frameDurationMs - this.sfx.pendingMs()) / 1_000.0, this.epoch);
            didWork = true;
        }
        return didWork;
    }

    // LIFECYCLE (SUPERVISOR THREAD). paused LATCHES THE startPaused() INTENT; seekMs >= 0 IS THE POSITION A
    // RESTART FROM ENDED/STOPPED RESUMES AT (PAUSED), -1 STARTS FROM THE BEGINNING
    private void lifecycle(final boolean paused, final long seekMs) {
        final long started = System.nanoTime();
        long slowCount = 0;
        long slowWorstMs = 0;
        long slowAudioMs = 0;
        long slowVideoMs = 0;
        long slowFrameMs = 0;
        try {
            this.runSerial++;
            this.clock.reset();
            // A stop() THAT RACED THIS RESTART ALREADY MOVED THE FRESH CLOCK TO STOPPED: THE USER WINS
            if (!this.clock.transition(Status.LOADING)) return;
            // THE PAUSE INTENT AND THE RESTART POSITION LATCH INTO LOADING: THE DECODER OPENS AT THE POSITION AND THE LANDING
            // RESOLVES INTO PAUSED
            if (paused) this.clock.paused(true);
            if (seekMs >= 0) this.clock.requestSeek(seekMs, true, false);
            this.totalSkippedFrames = 0;
            this.totalRenderedFrames = 0;
            double renderDebtSec = 0; // ADAPTIVE FRAME DROPPING: CONVERT+UPLOAD TIME OWED TO THE FRAME BUDGET
            this.switchRequest = false; // THE QUALITY AND AUDIO PICK ARE ALREADY THE LATEST; A STALE SWITCH WOULD REBUILD THE FRESH PIPELINE
            // BUFFERING DETECTION: SINCE WHEN A STREAM HAS NOTHING TO PLAY, SINCE WHEN THE PICTURE TRAILS ITS DECODER, THE LAST REBUFFER
            long starvedStartMs = 0;
            long behindStartMs = 0;
            long lastRebufferMs = Long.MIN_VALUE / 4; // "NEVER", FAR ENOUGH FROM ANY MONOTONIC STAMP
            long playMs = 0; // WHERE THE CLOCK LAST WAS ONCE LANDED, PLAYING OR PAUSED
            long landStamp = this.seekNanos; // THE SEEK WHOSE LANDING WAS ALREADY REPORTED
            this.hlsLiveSource = null;
            this.opened = false;
            this.fps = 0f;
            this.mediaDurationMs = NO_DURATION;

            // THE DECODER OPENS THE INPUT, STARTS THE CLOCK AND ITS DECODE THREADS; LOADING LEAVES ONCE IT OPENED OR FAILED
            this.open();
            if (this.clock.awaitNotStatus(Status.LOADING, 0) == Status.ERROR) return;

            // CONSUMPTION LOOP
            while (!Thread.currentThread().isInterrupted()) {
                final Status current = this.clock.status();

                if (this.switchRequest) {
                    this.switchRequest = false;
                    this.switchSource();
                    if (this.clock.status() == Status.ERROR) break;
                    continue;
                }

                final FFDecoder decoder = this.decoder;
                final FrameQueue videoFrames = decoder.videoFrames();
                final FrameQueue audioFrames = decoder.audioFrames();
                final boolean videoOn = this.gfx != null && decoder.video();
                final boolean audioOn = this.sfx != null && decoder.audio();
                if (this.settled && (current == Status.PLAYING || current == Status.PAUSED)) playMs = this.clock.timeMs();

                // SEEKS: A NEW GENERATION OF THE DECODER, OR ONLY A FLOOR WHEN A STEP OR A CATCH-UP STAYS INSIDE WHAT WAS ALREADY READ. THE
                // SOUND OF THE OLD POSITION STOPS AT ONCE AND NOTHING PLAYS UNTIL EVERY STREAM HAS A FRAME OF THE NEW ONE
                final MasterClock.Seek seek = this.clock.consumeSeek();
                if (seek != null) {
                    if (seek.ahead() && this.settled && seek.targetMs() > playMs && seek.targetMs() - playMs <= CATCH_UP_MS && decoder.covers(seek.targetMs())) {
                        this.floorMs = seek.targetMs();
                        decoder.floor(this.generation, this.floorMs);
                    } else {
                        this.generation = decoder.seek(seek.targetMs(), seek.precise());
                        this.floorMs = seek.precise() ? seek.targetMs() : Long.MIN_VALUE;
                    }
                    this.epoch = seek.epoch();
                    this.settled = false;
                    this.fromStart = seek.targetMs() == 0;
                    this.cushionMs = 0;
                    this.guideMs = Long.MIN_VALUE;
                    if (this.sfx != null) this.sfx.flush();
                    continue;
                }

                // PAUSED BY REQUEST AND LANDED, THE READERS PARK. A PAUSE THAT CAME BEFORE THE LANDING STILL READS UP TO ITS FRAMES
                decoder.hold(this.settled && this.clock.holding());

                // READ BEFORE THE HEADS: A DECODER PUSHES ITS LAST FRAMES BEFORE IT SAYS IT IS DONE, SO NONE OF THEM IS LEFT BEHIND
                final boolean ended = decoder.ended();

                // WHAT A SEEK OR THE FLOOR LEFT BEHIND NEVER PLAYS, NOR COUNTS AS SOMETHING TO PLAY
                FrameQueue.Slot videoHead;
                while ((videoHead = videoFrames.peek()) != null
                        && (videoHead.generation != this.generation || videoHead.ptsMs + videoHead.durationMs <= this.floorMs)) {
                    if (videoHead.generation == this.generation) this.totalSkippedFrames++;
                    videoFrames.next();
                }
                FrameQueue.Slot audioHead;
                while ((audioHead = audioFrames.peek()) != null
                        && (audioHead.generation != this.generation || audioHead.ptsMs + audioHead.durationMs <= this.floorMs))
                    audioFrames.next();

                if (ended && videoHead == null && audioHead == null) {
                    // A VIDEO STREAM THAT DRAINED WITHOUT DECODING A FRAME IS A DECODE FAILURE, E.G. AN AV1 STREAM THE SELECTED DECODER
                    // CANNOT HANDLE: ENDED WOULD LOOK LIKE AN INSTANT 0-LENGTH PLAYBACK AND repeat() WOULD RETRY IT FOREVER
                    if (decoder.video() && decoder.videoDecoded() == 0) {
                        LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_NO_FRAMES_LOG) + "Failed to decode video for {} due to no frames emitted, failing instead of reporting ENDED", decoder.uri());
                        this.exception(new IOException(DataTool.errCode(WaterMedia.ERR_FFPLAYER_NO_FRAMES) + "Failed to decode video due to no frames emitted, the stream is unsupported or broken"));
                        this.publishTransition(Status.ERROR);
                        break;
                    }
                    // A STILL (ONE VIDEO FRAME, NO AUDIO) BEHAVES LIKE THE IMAGE PLAYER'S: SHOWN UNTIL displayTime
                    // ENDS IT, FOREVER WHEN UNTIMED, NEVER LOOPED (REPLAYING THE PIPELINE FOR THE SAME PIXELS BUYS NOTHING)
                    if (!decoder.audio() && decoder.videoDecoded() <= 1) {
                        this.hlsLiveSource = false; // A FULLY DRAINED FINITE SOURCE IS NEVER LIVE
                        final long shown = this.displayTimeMs;
                        if (shown > 0) {
                            this.mediaDurationMs = shown; // THE TIMED STILL EXPOSES ITS DISPLAY WINDOW AS DURATION
                            if (this.clock.timeMs() >= shown) {
                                this.publishTransition(Status.ENDED);
                                break;
                            }
                        }
                        this.clock.awaitChange(shown > 0 ? MathUtil.clamp(shown - this.clock.timeMs(), 1L, 500L) : 500L);
                        continue;
                    }
                    if (this.repeat()) {
                        // ALL DATA CONSUMED: SEEK TO THE BEGINNING AFTER THE FULL DRAIN, NOT AT THE END OF THE INPUT, SO NO PACKET IS LOST.
                        // THE SEEK FLUSHES THE ENGINE, SO IT WAITS UNTIL THE LAST SOUND WAS HEARD
                        if (audioOn && this.sfx.pendingMs() > TAIL_MS) {
                            this.clock.awaitChange(TAIL_MS);
                            continue;
                        }
                        this.clock.requestSeek(0, false, false);
                        continue;
                    }
                    this.publishTransition(Status.ENDED);
                    break;
                }

                if (current == Status.BUFFERING && decoder.demuxDied()) {
                    LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_DEMUX_DEAD_LOG) + "Failed to keep buffering due to a dead demux thread, setting ERROR");
                    this.exception(new IOException(DataTool.errCode(WaterMedia.ERR_FFPLAYER_DEMUX_DEAD) + "Failed to keep buffering due to a dead demux thread"));
                    this.publishTransition(Status.ERROR);
                    break;
                }

                if (current == Status.PAUSED && this.settled) {
                    // KEEP THE ENGINE PAUSED EVEN WHEN PAUSED WAS REACHED BY A LANDING AND NOT THROUGH pause(true)
                    if (this.sfx != null) this.sfx.pause();
                    this.clock.awaitChange(50);
                    continue;
                }

                // STOPPED, ERROR AND EVERY OTHER STATUS LEFT HERE END THE LOOP
                if (current != Status.PLAYING && current != Status.BUFFERING && current != Status.PAUSED) break;
                // A CLOCK THAT RUNS WITHOUT A LANDING HAS NO FRAMES UNDER IT: IT BUFFERS UNTIL IT DOES
                if (current == Status.PLAYING && !this.settled) {
                    this.clock.transition(Status.BUFFERING);
                    continue;
                }

                // LANDING: A START, A SEEK OR A REBUFFER ENDS WHEN EVERY STREAM THAT STILL DELIVERS HAS A FRAME, AND THE CLOCK STARTS
                // WHERE THE LAST OF THEM STARTS, SO SOUND AND PICTURE COME BACK TOGETHER
                boolean landing = false;
                long landMs = 0;
                long landVideoMs = -1;
                long landAudioMs = -1;
                if (!this.settled) {
                    // THE FOLLOWER SAYS WHERE THE SESSION IS WHILE ITS SEEK FLIES: THE LANDING GOES THERE, NOT WHERE THE SEEK WAS SENT
                    final long guide = this.guideMs;
                    if (guide > this.floorMs && guide >= this.seekTargetMs && guide - this.seekTargetMs <= GUIDE_RANGE_MS) {
                        this.floorMs = guide;
                        decoder.floor(this.generation, guide);
                        continue;
                    }
                    // A REBUFFER ALSO WAITS FOR ITS CUSHION OF QUEUED MEDIA
                    if (videoOn && decoder.videoComing() && (videoHead == null || !decoder.videoHolds(this.cushionMs))
                            || audioOn && decoder.audioComing() && (audioHead == null || !decoder.audioHolds(this.cushionMs))) {
                        Thread.sleep(1);
                        continue;
                    }
                    // WHAT A STREAM HAS BEFORE THE START OF THE OTHER IS DROPPED, HERE AND IN ITS DECODER, AND THE WAIT GOES ON. THE SOUND A
                    // REBUFFER LEFT IN THE ENGINE PLAYS FIRST, SO THE SOUND STARTS WHERE IT IS AUDIBLE (A SEEK FLUSHED THE ENGINE)
                    final long videoStart = videoHead != null ? videoHead.ptsMs : Long.MIN_VALUE;
                    final long audioStart = audioHead != null ? audioHead.ptsMs - (audioOn ? this.sfx.pendingMs() : 0L) : Long.MIN_VALUE;
                    // FROM THE BEGINNING NOTHING IS DROPPED: A STREAM THAT STARTS LATE (A DELAYED AUDIO TRACK) JOINS WHEN IT STARTS, AND
                    // THE CLOCK NEVER STARTS BEFORE 0 FOR THE PRE-ROLL OF A CODEC
                    final long start = this.fromStart && videoHead != null && audioHead != null ? Math.max(0L, Math.min(videoStart, audioStart)) : Math.max(videoStart, audioStart);
                    if (videoHead != null && videoHead.ptsMs + videoHead.durationMs <= start || audioHead != null && audioHead.ptsMs + audioHead.durationMs <= start) {
                        this.floorMs = start;
                        decoder.floor(this.generation, start);
                        continue;
                    }
                    landing = true;
                    landMs = start != Long.MIN_VALUE ? start : this.clock.timeMs();
                    if (videoHead != null) landVideoMs = videoHead.ptsMs;
                    if (audioHead != null) landAudioMs = audioHead.ptsMs;
                }

                boolean didWork = false;

                // PLAYBACK RATE MONITOR
                final long nowMs = nowMs();
                if (current == Status.PLAYING && nowMs - this.perfLastCheckMs > PERF_CHECK_INTERVAL_MS && this.perfLastClockMs > 0) {
                    final long wallElapsed = nowMs - this.perfLastCheckMs;
                    final long clockElapsed = this.clock.timeMs() - this.perfLastClockMs;
                    final double speed = this.clock.speed();
                    // THE CLOCK ADVANCES AT speed TIMES WALL TIME BY DESIGN, SO THE DEVIATION IS MEASURED AGAINST THAT;
                    // A SPEED CHANGE INSIDE THE WINDOW MAKES THE SAMPLE MEANINGLESS, SO IT IS SKIPPED
                    final double rate = clockElapsed / (wallElapsed * speed);
                    if (!this.rateWarned && speed == this.perfLastSpeed && (rate < PERF_RATE_WARN_THRESHOLD || rate > (2.0 - PERF_RATE_WARN_THRESHOLD))) {
                        this.rateWarned = true;
                        LOGGER.warn(IT, "Playback rate {} (clock {}ms over {}ms at {}x, vQ={}, aQ={}, aUploads={}, vRenders={})",
                                String.format("%.3f", rate), clockElapsed, wallElapsed, speed,
                                videoFrames.remaining(), audioFrames.remaining(), this.perfAudioUploads, this.perfVideoRenders);
                    }
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                    this.perfLastSpeed = speed;
                } else if (this.perfLastCheckMs == 0 || current != Status.PLAYING) {
                    this.perfLastCheckMs = nowMs;
                    this.perfLastClockMs = this.clock.timeMs();
                    this.perfLastSpeed = this.clock.speed();
                    this.perfAudioUploads = 0;
                    this.perfVideoRenders = 0;
                }

                final long iterStart = System.nanoTime();

                // AUDIO: FILL THE ENGINE QUEUE EAGERLY (SEE drainAudio). NOTHING SOUNDS BEFORE THE LANDING
                if (!landing) didWork |= this.drainAudio(decoder);
                final long afterAudio = System.nanoTime();

                // VIDEO: ONE FRAME PER ITERATION (INLINED POLL: A/V SYNC, ADAPTIVE DROP, THEN
                // UPLOAD VIA NATIVE PLANES OR sws_scale). A SECOND CONSUMER ON THIS QUEUE WOULD RACE.
                if (videoOn) {
                    while (true) {
                        final FrameQueue.Slot slot = videoFrames.peek();
                        if (slot == null) break;

                        // LEFT BEHIND BY A SEEK, OR ENDS BEFORE THE FLOOR
                        if (slot.generation != this.generation || slot.ptsMs + slot.durationMs <= this.floorMs) {
                            videoFrames.next();
                            continue;
                        }

                        // A/V SYNC, BYPASSED BY THE LANDING: ITS FRAME IS THE ONE THE CLOCK IS ABOUT TO START ON, AND IT GOES UP EVEN
                        // WHEN THE LANDING RESOLVES INTO PAUSED, SO A SEEK ON A PAUSED PLAYER SHOWS ITS TARGET
                        final double clockSec = this.clock.time();
                        final double frameSec = slot.ptsMs / 1_000.0;
                        final double diff = frameSec - clockSec;

                        if (!landing && diff > AV_SYNC_TOO_EARLY_SEC) break;
                        if (!landing && diff < -(this.clock.skipThresholdMs() / 1_000.0)) {
                            // LATE: THE DECODER DROPS WHAT PLAYS BEFORE THE CLOCK ITSELF, BEFORE IT LEAVES THE GPU OR TAKES A SLOT, INSTEAD
                            // OF HANDING EVERY LATE FRAME OVER TO BE SKIPPED HERE
                            this.floorMs = Math.max(this.floorMs, (long) (clockSec * 1_000.0));
                            decoder.floor(this.generation, this.floorMs);
                            videoFrames.next();
                            this.totalSkippedFrames++;
                            continue;
                        }

                        // ADAPTIVE FRAME DROP
                        final double frameDurSec = this.clock.frameDurationSec();
                        if (!landing && renderDebtSec >= frameDurSec) {
                            final FrameQueue.Slot nextSlot = videoFrames.peekNext();
                            if (nextSlot != null) {
                                renderDebtSec -= frameDurSec;
                                videoFrames.next();
                                this.totalSkippedFrames++;
                                continue;
                            }
                        }

                        // scale/LOD MAY REQUIRE A DOWNSCALE BEFORE UPLOAD; THE TARGET IS RESOLVED PER FRAME, SO HOT LOD
                        // CHANGES APPLY WITHOUT TOUCHING THE DECODE PIPELINE OR THE CLOCK. A FRAME DECODED FOR THE VULKAN
                        // ENGINE CARRIES THE LAYOUT OF ITS POOL AND IS SAMPLED ON THE GPU, SCALED BY THE ENGINE
                        final boolean decoded = slot.format == AV_PIX_FMT_VULKAN;
                        AVFrame uploadFrame = slot.frame;
                        int layout = slot.format;
                        if (decoded) {
                            final Pointer pool = slot.frame.hw_frames_ctx().data();
                            if (pool.address() != this.cachedFramesCtx) {
                                this.cachedFramesCtx = pool.address();
                                this.cachedFramesFormat = new AVHWFramesContext(pool).sw_format();
                            }
                            layout = this.cachedFramesFormat;
                        }
                        if (layout != this.cachedUploadFmt) {
                            this.cachedUploadFmt = layout;
                            this.cachedUploadMapping = FFTool.pixels(layout);
                        }
                        FFTool.Pixels mapping = this.cachedUploadMapping;
                        // ENGINE CAN'T TAKE THIS PLANAR FORMAT DIRECTLY (E.G. VULKAN WITHOUT GPU YUV CONVERSION):
                        // DROP TO null SO THE BLOCK BELOW CONVERTS TO BGRA VIA sws BEFORE UPLOAD
                        if (mapping != null && !this.gfx.supports(mapping.format(), mapping.bits())) mapping = null;
                        // A POOL LAYOUT THE ENGINE CANNOT SAMPLE, OR A TWIN'S POOL ITS DEVICE CANNOT IMPORT, COMES DOWN TO RAM, COLOR TAGS
                        // INCLUDED, AND FOLLOWS THE SOFTWARE PATH
                        boolean sampled = decoded;
                        if (decoded && (mapping == null || this.gfx instanceof final VKEngine vk && !vk.samples(slot.frame))) {
                            if (this.downloadedFrame == null) this.downloadedFrame = avutil.av_frame_alloc();
                            if (av_hwframe_transfer_data(this.downloadedFrame, slot.frame, 0) < 0) {
                                if (!this.decodedWarned) LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_GPU_FRAMES) + "Failed to read GPU decoded frames in {} due to frames that can neither be sampled nor downloaded, frames are dropped",
                                        FFTool.string(av_get_pix_fmt_name(layout), layout));
                                this.decodedWarned = true;
                                videoFrames.next();
                                this.totalSkippedFrames++;
                                break;
                            }
                            this.downloadedFrame.colorspace(slot.frame.colorspace()).color_range(slot.frame.color_range())
                                    .chroma_location(slot.frame.chroma_location()).color_trc(slot.frame.color_trc())
                                    .color_primaries(slot.frame.color_primaries());
                            uploadFrame = this.downloadedFrame;
                            layout = uploadFrame.format();
                            mapping = FFTool.pixels(layout);
                            if (mapping != null && !this.gfx.supports(mapping.format(), mapping.bits())) mapping = null;
                            sampled = false;
                        }
                        final int targetW = scaled(slot.width, this.scaleWidth, this.lod.percent());
                        final int targetH = scaled(slot.height, this.scaleHeight, this.lod.percent());

                        int uploadW = slot.width;
                        int uploadH = slot.height;
                        // THE TAGS RARELY CHANGE: PACKED WITH THE FORMAT (12 BITS EACH, 4 FOR RANGE AND SITING) THEY GATE THE REMAP
                        final long tags = (layout & 0xFFFL) | (uploadFrame.colorspace() & 0xFFFL) << 12
                                | (uploadFrame.color_range() & 0xFL) << 24 | (uploadFrame.chroma_location() & 0xFL) << 28
                                | (uploadFrame.color_trc() & 0xFFFL) << 32 | (uploadFrame.color_primaries() & 0xFFFL) << 44;
                        if (tags != this.colorTags || slot.width != this.colorWidth || slot.height != this.colorHeight) {
                            this.frameColor = colorimetry(layout, slot.width, slot.height, uploadFrame.colorspace(),
                                    uploadFrame.color_range(), uploadFrame.chroma_location(), uploadFrame.color_trc(), uploadFrame.color_primaries());
                            this.colorTags = tags;
                            this.colorWidth = slot.width;
                            this.colorHeight = slot.height;
                        }
                        final Colorimetry color = this.frameColor;

                        final long workStart = System.nanoTime();
                        if (sampled) {
                            // THE ENGINE SAMPLES THE POOL IMAGE IN PLACE AND SCALES TO THE TARGET AS IT CONVERTS
                            uploadW = targetW;
                            uploadH = targetH;
                        } else if (mapping == null || targetW != slot.width || targetH != slot.height) {
                            // SWS PATH: DOWNSCALE AND/OR CONVERT, KEEPING THE NATIVE FORMAT WHEN SWS CAN WRITE IT (NO BGRA
                            // EXPANSION, FEWER UPLOADED BYTES); UNSUPPORTED OUTPUTS CONVERT TO BGRA INSTEAD
                            final int dstFormat = mapping != null && swscale.sws_isSupportedOutput(layout) != 0
                                    ? layout : AV_PIX_FMT_BGRA;
                            if (this.ensureScaler(slot.width, slot.height, layout, targetW, targetH, dstFormat, color)) {
                                final int result = swscale.sws_scale(
                                        this.swsContext,
                                        uploadFrame.data(),
                                        uploadFrame.linesize(),
                                        0,
                                        slot.height,
                                        this.scaledFrame.data(),
                                        this.scaledFrame.linesize()
                                );

                                if (result <= 0) {
                                    LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SWS_SCALE) + "Failed to scale the frame with sws_scale: result={}, fmt={}, size={}x{} -> {}x{}",
                                            result, slot.format, slot.width, slot.height, targetW, targetH);
                                    // COUNT AS SKIPPED: THE FRAME WAS DECODED BUT DROPPED HERE, SO THE
                                    // ZERO-FRAME → ERROR CHECK MUST NOT MISTAKE THIS FOR A DEAD DECODER
                                    videoFrames.next();
                                    this.totalSkippedFrames++;
                                    break;
                                }

                                uploadFrame = this.scaledFrame;
                                uploadW = targetW;
                                uploadH = targetH;
                                if (dstFormat == AV_PIX_FMT_BGRA) mapping = FFTool.BGRA;
                            } else if (mapping == null) {
                                // NO CONVERTER AND NO NATIVE UPLOAD: THE FRAME IS UNUSABLE.
                                // COUNT AS SKIPPED SO THE ZERO-FRAME → ERROR CHECK STAYS ACCURATE.
                                videoFrames.next();
                                this.totalSkippedFrames++;
                                break;
                            }
                            // SCALER UNAVAILABLE BUT THE FORMAT UPLOADS NATIVELY: DEGRADE TO SOURCE SIZE
                        }

                        // FORMAT CHANGE DETECTION: RECONFIGURE GFXEngine; A REFUSED LAYOUT DROPS FRAMES UNTIL IT CHANGES. RGB
                        // LAYOUTS UPLOAD AS THEY ARE, SO ONLY YUV AND GRAY RECONFIGURE ON A COLOR CHANGE
                        final boolean tinted = mapping.format() != PixelFormat.BGRA && mapping.format() != PixelFormat.RGBA
                                && mapping.format() != PixelFormat.RGB;
                        if (mapping.format() != this.lastFormat || mapping.bits() != this.lastBitsPerComponent
                                || uploadW != this.lastFrameWidth || uploadH != this.lastFrameHeight || tinted && !color.equals(this.lastColor)) {
                            this.uploadRefused = !this.gfx.format(mapping.format(), uploadW, uploadH, mapping.bits(), color);
                            this.lastFormat = mapping.format();
                            this.lastBitsPerComponent = mapping.bits();
                            this.lastFrameWidth = uploadW;
                            this.lastFrameHeight = uploadH;
                            this.lastColor = color;
                            if (!this.uploadRefused)
                                LOGGER.debug(IT, "GFX format: {} {}x{} {}bit {} (source={}x{})",
                                        mapping.format(), uploadW, uploadH, mapping.bits(), color, slot.width, slot.height);
                            else if (!this.refusedWarned) {
                                this.refusedWarned = true;
                                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_GFX_REFUSED) + "Failed to upload the frame, the graphics engine refused {} {}x{} {}bit and frames are dropped until the layout changes",
                                        mapping.format(), uploadW, uploadH, mapping.bits());
                            }
                        }
                        // A REFUSED LAYOUT SKIPS ONLY THE UPLOAD: THE FRAME IS STILL CONSUMED AND THE CLOCK ADVANCES, SO
                        // PLAYBACK AND LOOPING KEEP THEIR PACE (COUNTED AS SKIPPED FOR THE ZERO-FRAME → ERROR CHECK)
                        if (this.uploadRefused) this.totalSkippedFrames++;
                        else if (sampled && this.gfx instanceof final VKEngine vk) vk.upload(slot.frame);
                        else this.uploadNativePlanes(uploadFrame, mapping.format(), uploadH);

                        // TRACK RENDER DEBT (CONVERT+UPLOAD TIME VS FRAME BUDGET)
                        final double workSec = (System.nanoTime() - workStart) / 1_000_000_000.0;
                        if (workSec > frameDurSec) renderDebtSec += (workSec - frameDurSec);
                        else renderDebtSec = Math.max(0, renderDebtSec - (frameDurSec - workSec));

                        // CONSUME (frameSec ALREADY HOLDS THE TIMING; slot IS RECYCLED BY next())
                        videoFrames.next();
                        if (!this.uploadRefused) {
                            this.totalRenderedFrames++;
                            this.perfVideoRenders++;
                        }

                        // CLOCK UPDATE: THE PICTURE DRIVES THE CLOCK ONLY WITHOUT SOUND
                        if (!audioOn && !landing) this.clock.update(frameSec, this.epoch);

                        didWork = true;
                        break;
                    }
                }
                final long afterVideo = System.nanoTime();

                if (landing) {
                    // A LANDING A NEWER SEEK BEAT IS LEFT FOR THE NEXT PASS TO SERVE. THE STAMP IS READ FIRST: A SEEK STAMPED AFTER THE
                    // LANDING NEVER TAKES ITS REPORT, AND ONE STAMPED BEFORE ALREADY MOVED THE EPOCH, SO THE LANDING FAILS
                    final long stamp = this.seekNanos;
                    final long target = this.seekTargetMs;
                    if (this.clock.land(landMs / 1_000.0, this.epoch)) {
                        this.settled = true;
                        starvedStartMs = 0;
                        behindStartMs = 0;
                        // THE LANDING MOVED THE CLOCK, SO THE RATE WINDOW STARTS THERE
                        this.perfLastCheckMs = nowMs();
                        this.perfLastClockMs = landMs;
                        if (audioOn && !this.clock.pauseRequested()) this.sfx.play();
                        // WHERE EACH STREAM STARTED AGAINST THE TARGET, AND HOW LONG THE SEEK TOOK TO GET THERE
                        if (stamp != landStamp) {
                            landStamp = stamp;
                            LOGGER.debug(IT, "Seek to {}ms landed at {}ms after {}ms (picture at {}ms, sound at {}ms, -1 without one)",
                                    target, landMs, (System.nanoTime() - stamp) / 1_000_000L, landVideoMs, landAudioMs);
                        }
                    }
                    continue;
                }

                // AUDIO AGAIN AFTER VIDEO (REFILL WHAT THE ENGINE CONSUMED DURING VIDEO UPLOAD)
                didWork |= this.drainAudio(decoder);
                final long afterWork = System.nanoTime();

                // PERFORMANCE MONITOR
                final long audioMs = (afterAudio - iterStart + afterWork - afterVideo) / 1_000_000;
                final long videoMs = (afterVideo - afterAudio) / 1_000_000;
                final long totalMs = (afterWork - iterStart) / 1_000_000;
                if (totalMs > this.clock.frameDurationMs() && didWork) {
                    slowCount++;
                    if (totalMs > slowWorstMs) {
                        slowWorstMs = totalMs;
                        slowAudioMs = audioMs;
                        slowVideoMs = videoMs;
                        slowFrameMs = this.clock.frameDurationMs();
                    }
                }

                // BUFFERING, DECIDED BY THE STREAM THAT HAS THE LEAST. A STREAM WITH NOTHING TO PLAY NOR TO DECODE WHILE MORE IS DUE WAITS
                // FOR THE NETWORK, AND A PICTURE WITH PACKETS BUT NO FRAME TRAILS ITS DECODER. EITHER WAY BOTH STREAMS WAIT, SO THE SOUND
                // NEVER RUNS ON OVER A FROZEN PICTURE, AND NEVER THROUGH A SEEK: THE PIPELINE STILL READS
                final long checkMs = nowMs();
                final boolean starved = videoOn && videoFrames.empty() && decoder.videoBuffered() == 0 && decoder.videoComing()
                        || audioOn && audioFrames.empty() && decoder.audioBuffered() == 0 && decoder.audioComing();
                final boolean behind = videoOn && videoFrames.empty() && decoder.videoBuffered() > 0;
                starvedStartMs = !starved ? 0 : starvedStartMs == 0 ? checkMs : starvedStartMs;
                behindStartMs = !behind ? 0 : behindStartMs == 0 ? checkMs : behindStartMs;
                if (starved && checkMs - starvedStartMs > STARVED_MS || behind && checkMs - behindStartMs > BEHIND_MS) {
                    if (starved && !this.starvationWarned) LOGGER.warn(IT, "Starvation: BUFFERING (clock={}ms)", this.clock.timeMs());
                    if (!starved && !this.behindWarned) LOGGER.warn(IT, "The picture fell {}ms behind its decoder: BUFFERING (clock={}ms)", BEHIND_MS, this.clock.timeMs());
                    this.starvationWarned |= starved;
                    this.behindWarned |= !starved;
                    // A STARVED STREAM COMES BACK WITH A CUSHION, TWICE AS LARGE WHEN IT STARVED AGAIN SOON. A PICTURE THAT TRAILS COMES
                    // BACK AS SOON AS IT REACHES THE CLOCK, WHICH STAYS WHERE IT STOPPED
                    this.cushionMs = !starved ? 0 : checkMs - lastRebufferMs < CUSHION_WINDOW_MS ? CUSHION_MS * 2 : CUSHION_MS;
                    lastRebufferMs = checkMs;
                    starvedStartMs = 0;
                    behindStartMs = 0;
                    if (this.clock.transition(Status.BUFFERING)) {
                        this.settled = false;
                        this.fromStart = false;
                        this.floorMs = Math.max(this.floorMs, this.clock.timeMs());
                        decoder.floor(this.generation, this.floorMs);
                        if (this.sfx != null) this.sfx.pause();
                    }
                    continue;
                }

                if (!didWork) Thread.sleep(1);
            }

            LOGGER.debug(IT, "Consumption loop exited (R: {}, S: {}, clock: {}ms, status: {})",
                    this.totalRenderedFrames, this.totalSkippedFrames, this.clock.timeMs(), this.clock.status());

            this.stopDecoder();

            // AN INTERRUPTED EXIT (stop()/RESTART RACING THE LOOP) MUST STILL LAND ON A TERMINAL STATE: LEAVING
            // PLAYING/BUFFERING BEHIND WOULD REPORT A LIVE STATUS, AND A stop() DURING THE ENDED TEARDOWN MUST WIN
            final Status finalStatus = this.clock.status();
            final boolean interrupted = Thread.currentThread().isInterrupted();
            if (finalStatus != Status.ERROR && finalStatus != Status.STOPPED && (finalStatus != Status.ENDED || interrupted))
                this.publishTransition(interrupted ? Status.STOPPED : Status.ENDED);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            this.stopDecoder();
            this.publishTransition(Status.STOPPED);
        } catch (final Throwable e) {
            LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_LIFECYCLE) + "Failed to run the lifecycle for URI {}", this.source.uri(this.quality), e);
            this.exception(e);
            this.stopDecoder();
            this.publishTransition(Status.ERROR);
        } finally {
            if (slowCount > 0)
                LOGGER.warn(IT, SLOW_REPORT, slowCount, (System.nanoTime() - started) / 1_000_000, slowWorstMs, slowAudioMs, slowVideoMs, slowFrameMs);
            // THE FIELD KEEPS THE DECODER UNTIL ITS THREADS ARE JOINED, SO release() STILL REJECTS A LISTENER RUNNING ON ONE OF THEM
            final FFDecoder decoder = this.decoder;
            if (decoder != null) decoder.close();
            this.decoder = null;
            this.cleanup();
        }
    }

    // OPENS THE DECODER OF THE CURRENT QUALITY AND AUDIO: THE HOST'S PICK, WHOSE LANGUAGE FINDS ITS STREAM AGAIN WHEN THE INDEX
    // MOVED, ELSE THE DECODER'S CHOICE BY THE PREFERRED LANGUAGE AMONG THE SEPARATE TRACKS, OR THE MAIN INPUT'S STREAMS WITHOUT ANY
    private void open() {
        final URI uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));
        LOGGER.debug(IT, "Resolved quality {}", this.quality);
        final AudioTrack pick = this.audioPick;
        final FFDecoder.AudioChoice audio = this.sfx == null ? null : pick == null
                ? new FFDecoder.AudioChoice(this.source.audioTracks(), FFDecoder.AudioChoice.AUTO, -1, this.audioLanguage())
                : new FFDecoder.AudioChoice(this.source.audioTracks(), pick.track(), pick.stream(), pick.lang() != null ? pick.lang() : this.audioLanguage());
        final FFDecoder decoder = new FFDecoder(this.group, uri, this.source.headers(), this.gfx != null, audio, this.host);
        // THE POSITION ASKED FOR SO FAR IS WHERE THE DECODER OPENS, SO IT NEVER READS FROM THE BEGINNING FIRST. A SEEK THAT COMES
        // WHILE IT OPENS STAYS PENDING FOR THE CONSUMPTION LOOP
        final MasterClock.Seek initial = this.clock.consumeSeek();
        this.generation = initial == null ? decoder.generation() : decoder.seek(initial.targetMs(), initial.precise());
        this.epoch = initial == null ? this.clock.epoch() : initial.epoch();
        this.floorMs = initial != null && initial.precise() ? initial.targetMs() : Long.MIN_VALUE;
        this.settled = false;
        this.fromStart = initial == null || initial.targetMs() == 0;
        this.cushionMs = 0;
        this.guideMs = Long.MIN_VALUE;
        this.decoder = decoder; // PUBLISHED BEFORE IT RUNS, SO abort() ALWAYS REACHES ITS NATIVE I/O
        decoder.start();
    }

    // JOINS THE DECODER'S THREADS BEFORE THE RUN PUBLISHES ITS LAST STATUS
    private void stopDecoder() {
        final FFDecoder decoder = this.decoder;
        if (decoder != null) decoder.stop();
    }

    // SOURCE SWITCH (QUALITY OR AUDIO TRACK): A NEW DECODER RESUMES WHERE THE OLD ONE WAS; LIVE STREAMS START AT THE LIVE EDGE
    // INSTEAD, WHERE A SEEK FAILS AND FEEDS BACK
    private void switchSource() throws InterruptedException {
        final long currentMs = this.clock.timeMs();
        final boolean live = this.live();

        LOGGER.info(IT, "Switching to quality {} and audio {} at {}ms (live={})", this.quality, this.audioPick, currentMs, live);

        // THE OLD DECODER STAYS IN THE FIELD UNTIL open() REPLACES IT, AS IN THE LIFECYCLE'S TEARDOWN
        this.clock.transition(Status.BUFFERING);
        final FFDecoder old = this.decoder;
        if (old != null) old.close();
        this.cleanup();

        this.totalSkippedFrames = 0;
        this.totalRenderedFrames = 0;
        if (this.sfx != null) this.sfx.flush();

        // A PAUSE OR RESUME THAT RACED THE TEARDOWN STILL RELOADS, ONLY A TERMINAL STATUS ENDS THE SWITCH. THE PAUSE INTENT STAYS
        // LATCHED, SO THE LANDING AT THE NEW QUALITY RESOLVES INTO PAUSED WHEN IT WAS
        if (!this.clock.reload()) return;
        if (!live) this.clock.requestSeek(currentMs, true, false);
        this.open();
        if (this.clock.awaitNotStatus(Status.LOADING, 0) == Status.ERROR) return;

        LOGGER.info(IT, "Switched to quality {} and audio {}", this.quality, this.audioTrack);
    }

    // SWS CONTEXT (FORMAT CONVERSION AND/OR DOWNSCALE)
    private void recreateSwsContext(final int srcWidth, final int srcHeight, final int srcFormat,
                                    final int dstWidth, final int dstHeight, final int dstFormat) {
        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        this.swsColor = null;

        // SWS_AREA AVERAGES THE WHOLE SOURCE BLOCK PER OUTPUT PIXEL, THE RIGHT
        // QUALITY/COST TRADE FOR STRONG LOD DOWNSCALES; PURE FORMAT CONVERSIONS
        // KEEP THE CHEAPER FAST BILINEAR.
        final int flags = dstWidth != srcWidth || dstHeight != srcHeight
                ? swscale.SWS_AREA : swscale.SWS_FAST_BILINEAR;
        this.swsContext = swscale.sws_getContext(
                srcWidth, srcHeight, srcFormat,
                dstWidth, dstHeight, dstFormat,
                flags, null, null, (double[]) null
        );

        final String srcName = FFTool.string(av_get_pix_fmt_name(srcFormat), srcFormat);
        final String dstName = FFTool.string(av_get_pix_fmt_name(dstFormat), dstFormat);

        if (this.swsContext == null) {
            LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SWS_CONTEXT) + "Failed to create SwsContext: {} ({}x{}) -> {} ({}x{})",
                    srcName, srcWidth, srcHeight, dstName, dstWidth, dstHeight);
        } else {
            this.swsInputFormat = srcFormat;
            this.swsInputWidth = srcWidth;
            this.swsInputHeight = srcHeight;
            LOGGER.debug(IT, "SwsContext: {} ({}x{}) -> {} ({}x{})",
                    srcName, srcWidth, srcHeight, dstName, dstWidth, dstHeight);
        }
    }

    // FREES THE CONVERSION AND UPLOAD STATE OF A RUN; THE DECODER FREES ITS OWN
    private void cleanup() {
        this.cachedFramesCtx = 0L;
        this.cachedFramesFormat = AV_PIX_FMT_NONE;

        if (this.swsContext != null) {
            swscale.sws_freeContext(this.swsContext);
            this.swsContext = null;
        }
        this.swsColor = null;

        if (this.scaledFrame != null) {
            avutil.av_frame_free(this.scaledFrame);
            this.scaledFrame = null;
        }
        if (this.downloadedFrame != null) {
            avutil.av_frame_free(this.downloadedFrame);
            this.downloadedFrame = null;
        }

        // FREE THE PLANE POOL ON EVERY TEARDOWN SO A STOPPED-BUT-KEPT PLAYER RETAINS NO FRAME MEMORY (UP TO 4 FRAME SETS); SAFE
        // FOR VULKAN, WHOSE release(buffer) DROPS EACH HOST IMPORT (DRAINING GPU READS) FIRST. release() REPEATS IT IDEMPOTENTLY
        this.freePlanePool();
        this.lastFormat = null;
        this.lastColor = null;
        this.colorTags = -1L;
        this.uploadRefused = false;
        this.lastBitsPerComponent = 0;

        this.opened = false;
        this.fps = 0f;
        this.swsInputFormat = AV_PIX_FMT_NONE;
        this.swsInputWidth = 0;
        this.swsInputHeight = 0;
    }

    // AN UNSTARTED PIPELINE THREAD OF THIS PLAYER'S GROUP, SO A CALLER CAN PUBLISH IT IN ITS FIELD BEFORE IT RUNS
    private Thread thread(final String role, final Runnable task) {
        final Thread thread = THREADS.newThread(task);
        thread.setName(this.group + "-" + role);
        return thread;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // MAPS THE COLOR TAGS OF A FRAME OR STREAM; WHAT IT LEAVES UNSPECIFIED FALLS BACK TO Colorimetry.guess. GRAY OF ANY DEPTH
    // (ALPHA INCLUDED) AND THE LEGACY YUVJ LAYOUTS DEFAULT TO FULL RANGE, AS SWSCALE TREATS THEM
    private static Colorimetry colorimetry(final int avFormat, final int width, final int height, final int space,
                                           final int range, final int location, final int transfer, final int primaries) {
        final AVPixFmtDescriptor desc = av_pix_fmt_desc_get(avFormat);
        final boolean gray = !FFTool.isNull(desc) && desc.nb_components() > 0 && desc.nb_components() <= 2
                && (desc.flags() & (AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_PAL | AV_PIX_FMT_FLAG_HWACCEL)) == 0;
        final Colorimetry guess = Colorimetry.guess(gray ? PixelFormat.GRAY : null, width, height);
        final Colorimetry.Matrix matrix = switch (space) {
            case AVCOL_SPC_BT709, AVCOL_SPC_SMPTE240M -> Colorimetry.Matrix.BT709;
            case AVCOL_SPC_BT470BG, AVCOL_SPC_SMPTE170M, AVCOL_SPC_FCC -> Colorimetry.Matrix.BT601;
            case AVCOL_SPC_BT2020_NCL, AVCOL_SPC_BT2020_CL -> Colorimetry.Matrix.BT2020;
            default -> guess.matrix();
        };
        final boolean jpeg = avFormat == AV_PIX_FMT_YUVJ420P || avFormat == AV_PIX_FMT_YUVJ422P || avFormat == AV_PIX_FMT_YUVJ444P
                || avFormat == AV_PIX_FMT_YUVJ411P || avFormat == AV_PIX_FMT_YUVJ440P;
        final boolean full = range == AVCOL_RANGE_JPEG || range != AVCOL_RANGE_MPEG && (jpeg || guess.full());
        final boolean tagged = location != AVCHROMA_LOC_UNSPECIFIED;
        final boolean cositedX = tagged
                ? location == AVCHROMA_LOC_LEFT || location == AVCHROMA_LOC_TOPLEFT || location == AVCHROMA_LOC_BOTTOMLEFT
                : guess.cositedX();
        final boolean cositedY = tagged ? location == AVCHROMA_LOC_TOPLEFT || location == AVCHROMA_LOC_TOP : guess.cositedY();
        final Colorimetry.Transfer curve = transfer == AVCOL_TRC_SMPTE2084 ? Colorimetry.Transfer.PQ
                : transfer == AVCOL_TRC_ARIB_STD_B67 ? Colorimetry.Transfer.HLG : Colorimetry.Transfer.SDR;
        final boolean wide = primaries == AVCOL_PRI_BT2020 || primaries == AVCOL_PRI_UNSPECIFIED && matrix == Colorimetry.Matrix.BT2020;
        return new Colorimetry(matrix, full, cositedX, cositedY, curve, wide);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // DECODER CALLBACKS, THE PLAYER SIDE OF THE DECODER: THE NETWORK CACHE, THE VULKAN ENGINE'S DEVICE, THE AUDIO ENGINE'S FORMAT AND THE OPEN
    // AND FAILURE REPORTS. EVERY CALL RUNS ON THE DECODER'S DEMUX THREAD
    private final class Callbacks implements FFDecoder.Host {
        @Override
        public Path cached(final URI uri) throws IOException {
            // PLAYLISTS ARE NEVER CACHED BY EXTENSION (THE CACHE ALSO REJECTS THEM BY CONTENT TYPE, BUT ONLY
            // AFTER DOWNLOADING); THE SCHEME AND CONFIG GATES LIVE INSIDE NetworkCache.readFile
            final String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return null;
            final NetworkCache.CachedFile cached = NetworkCache.readFile(
                    uri,
                    FFMediaPlayer.this.source.headers(),
                    "video/*,audio/*,application/octet-stream,*/*",
                    DataTool.megabytes(Math.max(1L, WaterMediaConfig.codecs.ffmpeg.cacheMaxResponse)),
                    WaterMediaConfig.codecs.ffmpeg.cache);
            if (cached == null) return null;
            LOGGER.debug(IT, "{} FFMediaPlayer cache entry for {}", cached.cached() ? "Using" : "Stored", uri);
            return cached.path();
        }

        @Override
        public FFDecoder.Device vulkan(final int codecId) {
            if (!(FFMediaPlayer.this.gfx instanceof final VKEngine vk)) return null;
            final AVBufferRef device = vk.hwDevice(codecId);
            return device == null ? null : new FFDecoder.Device(device, vk.placebo());
        }

        @Override
        public FFDecoder.Pcm audio(final SampleType type, final int channels, final int rate) {
            final SFXEngine sfx = FFMediaPlayer.this.sfx;
            // CHANNELS: THE EXACT COUNT WHEN SUPPORTED, ELSE THE CLOSEST; AN UNKNOWN LAYOUT (0 CHANNELS) NEGOTIATES AS THE
            // STEREO THE DECODER DEFAULTS IT TO. TYPE: KEPT WHEN SUPPORTED THERE, ELSE S16, ELSE THE ENGINE'S FIRST
            final SFXEngine.ChannelSupport exact = sfx.channelSupport(channels);
            final SFXEngine.ChannelSupport entry = exact != null ? exact : sfx.closestChannelSupport(channels > 0 ? channels : 2);
            if (entry == null) {
                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SFX_EMPTY_TABLE) + "Failed to pick the audio format due to an empty channel-support table in the SFX engine");
                return null;
            }
            final SampleType out = entry.supports(type) ? type : entry.supports(SampleType.S16) ? SampleType.S16
                    : Arrays.stream(sfx.supportedTypes()).filter(entry::supports).findFirst().orElse(null);
            if (out == null) {
                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SFX_NO_TYPES) + "Failed to pick the audio format due to no supported types in the SFX engine for {} channels", entry.channels());
                return null;
            }
            if (!sfx.format(out, entry.channels(), rate)) {
                LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_FFPLAYER_SFX_REJECTED) + "Failed to set the audio format, the SFX engine rejected it: type={}, ch={}, rate={}", out, entry.channels(), rate);
                return null;
            }
            return new FFDecoder.Pcm(out, entry.channels());
        }

        @Override
        public void duration(final long ms) {
            FFMediaPlayer.this.mediaDurationMs = ms;
        }

        @Override
        public void opened(final FFDecoder decoder) {
            final FFMediaPlayer player = FFMediaPlayer.this;
            if (decoder.hls()) {
                // ASYNC ON PURPOSE: THE PROBE COSTS 1-2 HTTP ROUNDTRIPS AND ONLY REFINES live(), WHICH THE DURATION HEURISTIC
                // ANSWERS MEANWHILE (HLS VOD PLAYLISTS CARRY A DURATION). IT USES THE SOURCE'S HEADERS (AUTHENTICATED PLAYLISTS
                // ARE COMMON) AND WRITES ONLY INTO THE RUN THAT STARTED IT; A FAILED PROBE LEAVES THE HEURISTIC IN CHARGE
                final int run = player.runSerial;
                final URI uri = decoder.uri();
                final Thread probe = player.thread("hls-probe", () -> {
                    try {
                        M3UTool.Playlist hlsResult = M3UTool.fetch(uri, player.source.headers());
                        // MASTER PLAYLIST: FOLLOW THE FIRST VARIANT (URL ALREADY ABSOLUTE) DOWN TO A REAL MEDIA PLAYLIST
                        if (hlsResult instanceof final M3UTool.Master master && !master.variants().isEmpty())
                            hlsResult = M3UTool.fetch(master.variants().get(0).uri(), player.source.headers());
                        if (run != player.runSerial) return;
                        if (hlsResult instanceof final M3UTool.Media media) {
                            player.hlsLiveSource = media.live();
                            LOGGER.debug(IT, "HLS probe: live={}, vod={}, totalDuration={}s", media.live(), media.vod(), media.totalDuration());
                        } else {
                            LOGGER.warn(IT, "HLS probe: inconclusive ({}), keeping the duration heuristic", hlsResult.kind());
                        }
                    } catch (final IOException e) {
                        LOGGER.warn(IT, "HLS probe failed, keeping the duration heuristic", e);
                    }
                });
                probe.start();
            }

            if (decoder.video()) {
                // THE CLOCK KEEPS ITS PACING DEFAULT WITHOUT A PLAUSIBLE RATE; fps() STAYS 0 BECAUSE THE RATE IS GENUINELY UNKNOWN
                final float fps = decoder.fps();
                if (fps > 0f) {
                    player.clock.fps(fps);
                    player.fps = fps;
                }
                LOGGER.debug(IT, "Video timing: {}fps, frame duration: {}ms, skip threshold: {}ms",
                        player.clock.fps(), player.clock.frameDurationMs(), player.clock.skipThresholdMs());

                final AVCodecContext ctx = decoder.videoContext();
                final int w = ctx.width();
                final int h = ctx.height();
                player.sourceWidth = w;
                player.sourceHeight = h;
                player.resolveQuality(w, h);

                final FFTool.Pixels initialMapping = FFTool.pixels(ctx.pix_fmt());
                if (initialMapping != null) {
                    // PRE-CONFIGURE THE ENGINE WITH THE UPLOAD TARGET (scale/LOD APPLIED) SO
                    // THE FIRST FRAME DOESN'T PAY AN EXTRA RECONFIGURATION
                    final int targetW = scaled(w, player.scaleWidth, player.lod.percent());
                    final int targetH = scaled(h, player.scaleHeight, player.lod.percent());
                    final Colorimetry color = colorimetry(ctx.pix_fmt(), w, h, ctx.colorspace(), ctx.color_range(),
                            ctx.chroma_sample_location(), ctx.color_trc(), ctx.color_primaries());
                    player.uploadRefused = !player.gfx.format(initialMapping.format(), targetW, targetH, initialMapping.bits(), color);
                    player.lastFormat = initialMapping.format();
                    player.lastBitsPerComponent = initialMapping.bits();
                    player.lastFrameWidth = targetW;
                    player.lastFrameHeight = targetH;
                    player.lastColor = color;
                }
            }

            // AUDIO TRACKS: THE STREAMS THIS DECODER FOUND REPLACE THEIR ORIGIN'S ENTRIES, THE MAIN INPUT'S ALWAYS (A QUALITY SWITCH
            // MAY CHANGE THEM); EVERY OTHER ORIGIN KEEPS WHAT AN EARLIER RUN FOUND OR ITS PROVISIONAL ENTRY. RUNS NEVER OVERLAP, AND
            // EACH DEMUX THREAD STARTS AFTER THE PREVIOUS ONE WAS JOINED, SO THESE WRITES NEVER RACE
            if (player.sfx != null) {
                final List<FFDecoder.AudioStream> found = decoder.audioStreams();
                final List<AudioTrack> previous = player.audioTracks;
                final List<AudioTrack> next = new ArrayList<>();
                for (int origin = AudioTrack.MAIN; origin < player.source.audioTracks().size(); origin++) {
                    final int size = next.size();
                    for (final FFDecoder.AudioStream stream: found)
                        if (stream.track() == origin) next.add(new AudioTrack(origin, stream.stream(), stream.name(), stream.lang()));
                    if (origin == AudioTrack.MAIN || next.size() > size) continue;
                    for (final AudioTrack kept: previous)
                        if (kept.track() == origin) next.add(kept);
                }
                final FFDecoder.AudioStream playing = decoder.audioStream();
                player.audioTracks = List.copyOf(next);
                player.audioTrack = playing == null ? null : new AudioTrack(playing.track(), playing.stream(), playing.name(), playing.lang());
                LOGGER.debug(IT, "Audio track {} of {}", player.audioTrack, player.audioTracks);
            }

            player.opened = true;
            player.playable = true;
            LOGGER.info(IT, "FFMediaPlayer started - video: {} (hw: {}{}), audio: {}", decoder.video(), decoder.hardware(),
                    !decoder.vulkan() ? "" : decoder.filters() ? ", frames stay on the GPU through libplacebo" : ", frames stay on the GPU", decoder.audio());
            // LOADING ENDS HERE: THE CLOCK BUFFERS UNTIL THE CONSUMPTION LOOP LANDS IT ON THE FIRST FRAMES
            player.clock.start();
        }

        @Override
        public void failed(final Throwable cause) {
            FFMediaPlayer.this.exception(cause);
            FFMediaPlayer.this.publishTransition(Status.ERROR);
        }
    }
}
