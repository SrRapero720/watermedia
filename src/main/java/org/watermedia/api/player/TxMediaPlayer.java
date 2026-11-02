package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.DownscaleFilter;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.IOTool;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Media player for static and animated images (PNG, APNG, GIF, WebP, NETPBM, JPEG).
 * <p>
 * Preparation runs on a shared single-frame pool. After the source is opened and metadata is
 * resolved, {@link #prepare(int)} dispatches to one of three playback modes implemented in
 * dedicated sections below:
 * <ul>
 *   <li><b>Mode 1, static image:</b> a single frame is uploaded once. No lifecycle thread is
 *       spawned and the texture remains live until {@link #stop()} or {@link #release()}.</li>
 *   <li><b>Mode 2, pre-uploaded frame textures:</b> when the animation's decoded RGBA frames
 *       fit under the configured VRAM budget <i>and</i> the engine reports
 *       {@link GFXEngine#preload()}, every frame is uploaded as its own texture.
 *       Playback is driven by a passive clock: no thread exists, the displayed frame is
 *       resolved from wall time on each {@link #texture()} call, so steady-state cost is zero
 *       uploads and zero wakeups.</li>
 *   <li><b>Mode 3, streaming decode with prefetch:</b> a streaming {@link ImageReader} is
 *       driven from the playback clock: each {@code next()} call decodes one frame on demand,
 *       like a video decoder. During the current frame's display window the loop pre-decodes
 *       upcoming frames into a bounded queue ("decode ahead instead of just sleeping"). If the
 *       queue empties because decoding fell behind the clock, playback drops into
 *       {@link Status#BUFFERING} and pre-decodes a handful of frames before resuming. Aggregate
 *       decode CPU across all players is bounded by a shared permit pool.</li>
 * </ul>
 * <p>
 * Seek / loop / step-backwards rewind the source ({@link ImageReader#reset()} when supported,
 * otherwise a full re-open) and decode forward to the target. Animated image formats are not
 * random-access because each frame's canvas depends on prior frames, exactly like video's GOP
 * semantics.
 * <p>
 * When {@link #scale(int, int)} or a {@link LodLevel} below MAX is active, decoded frames
 * are downscaled (area average) before upload. Streaming playback (Mode 3) picks up LOD
 * changes on the fly; static images and preloaded texture sets (Modes 1-2) apply the target
 * at preparation time.
 */
public final class TxMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(TxMediaPlayer.class.getSimpleName());

    // PREFETCH: HOLD UP TO PREFETCH_MAX PRE-DECODED FRAMES; REFILL PREFETCH_REFILL ON BUFFERING STALL.
    // PIXEL BUFFERS CYCLE THROUGH A POOL SO STEADY-STATE ALLOCATIONS ARE ZERO.
    // TOTAL LIVE MEMORY IS ROUGHLY PREFETCH AND IN-FLIGHT BUFFER COUNT TIMES THE FRAME BYTE SIZE.
    private static final int PREFETCH_MAX = 6;
    private static final int PREFETCH_REFILL = 3;
    private static final int POOL_SPARE = 2;
    private static final long PREFETCH_BUDGET_BYTES = 64L * 1_024 * 1_024;
    private static final long PREFETCH_SAFETY_MARGIN_MS = 2L;
    // ENGINES CONSUME OR DROP A SUBMITTED FRAME BEFORE THE SECOND SUBSEQUENT upload() RETURNS,
    // SO ONLY THE LAST TWO SUBMITTED BUFFERS MUST STAY UNTOUCHED.
    private static final int IN_FLIGHT_KEEP = 2;
    // MODE 2 HARD CAP: AVOIDS CREATING THOUSANDS OF GL TEXTURE OBJECTS FOR TINY LONG ANIMATIONS
    private static final int MAX_FRAME_TEXTURES = 256;
    private static final long PAUSE_WAIT_MS = 200L;
    private static final long REFILL_PERMIT_TIMEOUT_MS = 5_000L;
    // A TIMED-OUT RELEASE RETAINS ENGINE OWNERSHIP UNTIL THE HOST RETRIES AFTER I/O FINISHES.
    private static final long RELEASE_WAIT_TIMEOUT_MS = 5_000L;
    private static final long SKIP_STEP_MS = 1_000L;
    private static final ExecutorService SINGLE_FRAME_POOL = Executors.newFixedThreadPool(
            Math.max(1, ThreadTool.baseThreads()),
            ThreadTool.workerFactory("TxPlayer-SingleFrame", Thread.NORM_PRIORITY - 1));
    // ONE STREAMING DECODE THREAD PER PLAYING ANIMATION, OWNED LIKE THE POOL SO ITS CALLBACKS CANNOT RUN LIFECYCLE OPERATIONS
    private static final ThreadFactory STREAM_THREADS = ThreadTool.workerFactory("TxPlayer-Stream", Thread.NORM_PRIORITY - 1);
    // GLOBAL PERMITS BOUNDING AGGREGATE DECODE CPU ACROSS ALL TX PLAYERS: MANY ANIMATED
    // IMAGES THROTTLE EACH OTHER INSTEAD OF STARVING THE GAME'S OWN THREADS
    private static final Semaphore DECODE_PERMITS = new Semaphore(Math.max(2, ThreadTool.baseThreads()));
    // ACCEPT HEADER SHARED BY IMAGE REQUESTS AND THEIR CACHE KEYS.
    private static final String IMAGE_ACCEPT = "image/*,*/*";

    // METADATA (RESOLVED ON FIRST READER OPEN; DURATION MAY REMAIN UNKNOWN UNTIL EOF).
    // THE NATIVE FRAME SIZE LIVES IN MediaPlayer#sourceWidth / sourceHeight.
    private volatile boolean animated;
    private volatile boolean loaded;
    private volatile long knownDuration;       // 0 = UNKNOWN / STATIC. SET BY READER METADATA OR EOF.
    private volatile PixelFormat pixelFormat = PixelFormat.BGRA;

    // UPLOAD TARGET: SOURCE DIMENSIONS SHRUNK BY scale/LOD (SEE MediaPlayer#scaled).
    // WRITTEN ONLY BY THE PREPARE/LIFECYCLE THREAD THAT DECODES AND UPLOADS FRAMES.
    private int outWidth;
    private int outHeight;
    // SIZE THE ENGINE WAS LAST CONFIGURED WITH: GL APPLIES format() ON ITS RENDER THREAD, SO gfx.width() LAGS BEHIND
    private int uploadWidth;
    private int uploadHeight;
    private boolean refusedWarned; // A REFUSED SIZE IS LOGGED ONCE PER PLAYER

    // STATUS
    private volatile Status status = Status.WAITING;

    // STATIC-IMAGE DISPLAY CLOCK: A STILL ENDS AFTER displayTimeMs OF PLAYBACK SO STATUS-DRIVEN PLAYLISTS ADVANCE OFF ended()
    // (0 = SHOW FOREVER). WITH staticTimed SET THE STILL RIDES MODE 2'S PASSIVE clock (texTime()), knownDuration =
    // displayTimeMs AND NO FRAME-TEXTURE SWITCHING
    private volatile long displayTimeMs;
    private volatile boolean staticTimed;

    // LIFECYCLE THREAD (MODE 3 ONLY)
    private volatile Thread lifecycleThread;
    private volatile Future<?> lifecycleTask;
    private volatile Thread prepareThread; // POOL THREAD RUNNING prepare(), SO release() FROM ITS LISTENER FAILS FAST
    private volatile boolean released;
    private volatile boolean releaseDone; // SET ONCE THE ENGINE IS FREED, SO A RETRIED OR REPEATED release() IS A NO-OP
    private volatile boolean releasing; // A release() IS JOINING THE PIPELINE: STATUS DELIVERY WAITS FOR IT (SEE holdStatus)
    private volatile boolean playable; // THE SOURCE LOADED AT LEAST ONCE AND NOTHING FAILED SINCE (canPlay())
    private volatile long startSeekMs = -1L; // A RESTART FROM ENDED/STOPPED LANDS PAUSED HERE (-1 = FROM THE BEGINNING)
    private volatile ImageReader activeReader;
    private volatile int lifecycleSerial;

    // TRIGGERS (CALLER TO LIFECYCLE THREAD); signals WAKES THE LIFECYCLE OUT OF ITS WAITS AND GUARDS EVERY
    // status WRITE (TAKEN INSIDE clock WHEN THE PASSIVE CLOCK WRITES ONE, NEVER THE OTHER WAY AROUND)
    private final Object signals = new Object();
    private final Object releaseLock = new Object();
    // IN-FLIGHT PREPARE TASKS ON THE SHARED POOL (GUARDED BY signals). release() AWAITS ZERO,
    // BECAUSE Future.cancel() RETURNS IMMEDIATELY EVEN WHILE THE TASK STILL RUNS ON A POOL THREAD.
    private int prepareActive;
    private volatile boolean paused;
    private volatile boolean triggerStop;
    private volatile boolean triggerPause;
    private volatile boolean triggerResume;
    private volatile boolean triggerNextFrame;
    private volatile boolean triggerPrevFrame;
    private volatile long seekTarget = -1L;

    // PLAYBACK CLOCK (LIFECYCLE WRITES, CALLER READS)
    private volatile long time;
    private volatile long currentDelayMs;
    private volatile int currentFrameIndex = -1;

    // MODE 2 PASSIVE CLOCK: texTimeline[i] IS THE CUMULATIVE START TIME OF FRAME i. WHEN
    // NON-NULL, THE DISPLAYED FRAME IS RESOLVED FROM ELAPSED TIME ON EACH texture() CALL AND NO
    // LIFECYCLE THREAD EXISTS. clock GUARDS THE (clockBase, wallBase) PAIR.
    private final Object clock = new Object();
    private volatile long[] texTimeline;
    private volatile long[] texDelays;
    private long clockBase;     // MEDIA TIME AT wallBase
    private long wallBase;      // MONOTONIC nowMs() WHEN clockBase WAS TAKEN

    // PREFETCH STATE (ACCESSED ONLY BY THE LIFECYCLE THREAD IN STREAMING MODE)
    private final ArrayDeque<PrefetchedFrame> prefetchQueue = new ArrayDeque<>(PREFETCH_MAX);
    private int prefetchMax = PREFETCH_MAX;
    private int prefetchRefill = PREFETCH_REFILL;
    private int nextDecodedIndex;
    private boolean readerExhausted;

    // BUFFER POOL: DIRECT BYTE BUFFERS CYCLE THROUGH QUEUE, UPLOAD, AND POOL INSTEAD OF BEING
    // ALLOCATED PER FRAME. THE LAST IN_FLIGHT_KEEP UPLOADED BUFFERS ARE HELD BACK TO HONOR THE
    // ENGINE'S ASYNC CONSUMPTION CONTRACT.
    private final ArrayDeque<ByteBuffer> bufferPool = new ArrayDeque<>();
    private final ArrayDeque<ByteBuffer> inFlight = new ArrayDeque<>(IN_FLIGHT_KEEP + 1);
    private int bufferByteSize;

    TxMediaPlayer(final MRL mrl, final int sourceIndex, final GFXEngine gfxEngine) {
        super(mrl, sourceIndex, gfxEngine, null);
        this.arm();
    }

    // ==========================================================================
    // PUBLIC API: CONTROLS
    // ==========================================================================
    @Override
    public boolean pause(final boolean paused) {
        final Status status = this.status;
        if (!this.loaded) {
            // LATCHED WHILE LOADING, LIKE THE VIDEO PLAYER: APPLIED THE INSTANT THE FIRST FRAME GOES LIVE
            if (status != Status.LOADING && status != Status.BUFFERING) return false;
            final boolean was = this.triggerPause;
            this.triggerPause = paused;
            return was != paused;
        }
        if (!this.animated || this.texTimeline != null) {
            // A STILL OR THE PASSIVE CLOCK FLIPS BETWEEN PLAYING AND PAUSED IN PLACE; ENDED HAS NOTHING
            // TO RESUME AND A REPEATED CALL CHANGES NOTHING. THE CLOCK IS FROZEN OR REBASED ON THE FLIP
            synchronized (this.clock) {
                synchronized (this.signals) {
                    final Status prev = this.status;
                    if (paused ? prev != Status.PLAYING : prev != Status.PAUSED) return false;
                    this.paused = paused;
                    this.status = paused ? Status.PAUSED : Status.PLAYING;
                    this.queueStatus(prev, this.status);
                }
                final long now = nowMs();
                if (paused && (this.staticTimed || this.texTimeline != null)) this.clockBase += (long) ((now - this.wallBase) * this.speed());
                this.wallBase = now;
            }
            this.deliverStatus();
            return true;
        }
        // STREAMING: ONLY A LIVE LOOP TAKES THE TRIGGER; AN ENDED, FAILED OR IDLE PLAYER HAS NOTHING TO PAUSE
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle == null || !lifecycle.isAlive() || status == Status.ENDED || status == Status.ERROR || status == Status.STOPPED) return false;
        // THE EFFECTIVE STATE INCLUDES A TRIGGER THE LOOP HAS NOT CONSUMED YET
        final boolean effective = this.triggerPause || (this.paused && !this.triggerResume);
        if (paused == effective) return false;
        if (paused) { this.triggerPause = true; this.triggerResume = false; }
        else { this.triggerResume = true; this.triggerPause = false; }
        this.wake();
        return true;
    }

    @Override
    public boolean stop() {
        return this.teardown(false);
    }

    @Override
    public boolean togglePlay() {
        return switch (this.status()) {
            case ENDED, STOPPED -> this.launch(false, -1L);
            case WAITING, ERROR -> false;
            default -> this.pause(!this.paused());
        };
    }

    @Override
    public boolean seek(final long time) {
        if (!this.canSeek()) return false;
        long target = Math.max(0L, time);
        if (this.knownDuration > 0L) target = Math.min(target, this.knownDuration - 1L);
        final Status status = this.status();
        // A FINISHED OR STOPPED ANIMATION IS SCRUBBED INTO A PAUSED RESTART AT THE TARGET; THE PASSIVE
        // CLOCK STILL HOLDS ITS TEXTURES AFTER ENDED, SO IT JUST REWINDS AND PAUSES IN PLACE
        if (status == Status.STOPPED || (status == Status.ENDED && this.texTimeline == null)) return this.launch(true, target);
        if (!this.loaded) return false;
        if (this.texTimeline != null) {
            synchronized (this.clock) {
                synchronized (this.signals) {
                    if (this.status == Status.ENDED) {
                        this.paused = true;
                        this.status = Status.PAUSED;
                        this.queueStatus(Status.ENDED, Status.PAUSED);
                    }
                }
                this.clockBase = target;
                this.wallBase = nowMs();
            }
            this.deliverStatus();
            return true;
        }
        this.seekTarget = target;
        this.wake();
        return true;
    }

    @Override
    public boolean seekQuick(final long time) { return this.seek(time); }

    // IMAGES USE A FINER 1s STEP THAN THE 5s VIDEO DEFAULT
    @Override
    public boolean forward() { return this.skipTime(SKIP_STEP_MS); }

    @Override
    public boolean rewind() { return this.skipTime(-SKIP_STEP_MS); }

    @Override
    public boolean previousFrame() { return super.previousFrame() && this.stepFrame(-1); }

    @Override
    public boolean nextFrame() { return super.nextFrame() && this.stepFrame(1); }

    @Override
    public void release() {
        // FAIL FAST FROM THE PLAYER'S OWN THREADS: A LISTENER FIRED BY prepare() OR THE STREAMING LOOP
        // WOULD OTHERWISE WAIT FOR ITSELF
        final Thread t = this.lifecycleThread;
        if (Thread.currentThread() == this.prepareThread || (t != null && t == Thread.currentThread()))
            throw new IllegalStateException("Release the image player from its host thread, not from a playback callback");
        // ONE RELEASE AT A TIME: A CONCURRENT SECOND CALL WAITS AND THEN FINDS THE WORK DONE. STOPPED IS QUEUED UNDER THE LOCK
        // AND HEARD AFTER IT, SO A LISTENER CAN WAIT FOR A THREAD THAT CALLS INTO THIS PLAYER
        try {
            synchronized (this.releaseLock) {
                this.releasing = true;
                synchronized (this.signals) {
                    if (this.releaseDone) return;
                    this.released = true;
                    this.triggerStop = true;
                    this.lifecycleSerial++;
                }
                // OFF THE TICKER BEFORE THE JOINS, SO NO CORRECTION RUNS ON A DYING PLAYER
                this.disarm();
                this.texTimeline = null;
                this.texDelays = null;
                IOTool.closeQuietly(this.activeReader);
                final Future<?> task = this.lifecycleTask;
                if (task != null) task.cancel(true);
                if (t != null) t.interrupt();
                if (t != null) ThreadTool.join(t);
                // CANCELLING A FUTURE DOES NOT END A RUNNING TASK; KEEP ITS ENGINE ALIVE UNTIL IT EXITS.
                boolean interrupted = false;
                final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RELEASE_WAIT_TIMEOUT_MS);
                try {
                    synchronized (this.signals) {
                        while (this.prepareActive > 0) {
                            final long remaining = deadline - System.nanoTime();
                            if (remaining <= 0L)
                                throw new IllegalStateException("Image preparation has not stopped; release must be retried after I/O finishes");
                            try {
                                TimeUnit.NANOSECONDS.timedWait(this.signals, remaining);
                            } catch (final InterruptedException e) {
                                interrupted = true;
                            }
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
                // PREPARATION MAY HAVE HANDED OFF TO STREAMING WHILE RELEASE WAS WAITING FOR ITS EXIT.
                final Thread streaming = this.lifecycleThread;
                if (streaming != null && streaming != t && streaming != Thread.currentThread()) {
                    streaming.interrupt();
                    ThreadTool.join(streaming);
                }
                this.lifecycleThread = null;
                this.lifecycleTask = null;
                this.playable = false;
                this.releaseDone = true;
                synchronized (this.signals) {
                    final Status prev = this.status;
                    this.loaded = false;
                    this.paused = false;
                    this.status = Status.STOPPED;
                    this.clearBuffers();
                    this.queueStatus(prev, Status.STOPPED);
                }
                super.release();
            }
        } finally {
            this.releasing = false;
            this.deliverStatus();
        }
    }

    // ==========================================================================
    // PUBLIC API: STATE QUERIES
    // ==========================================================================
    @Override
    public Status status() {
        // TEXTURE MODE AND TIMED STATICS RESOLVE THE ENDED TRANSITION LAZILY FROM THE PASSIVE CLOCK
        if ((this.texTimeline != null || this.staticTimed) && this.status == Status.PLAYING && (this.staticTimed || !this.repeat())) this.texTime();
        return this.status;
    }

    @Override
    public long time() {
        return this.texTimeline != null || this.staticTimed ? this.texTime() : this.time;
    }

    @Override
    public long texture() {
        final long[] timeline = this.texTimeline;
        if (timeline != null) {
            // PASSIVE CLOCK: THE FRAME IS RESOLVED AT RENDER TIME
            final int frame = frameAt(timeline, this.texTime());
            if (frame != this.currentFrameIndex) {
                this.currentFrameIndex = frame;
                this.currentDelayMs = delayAt(this.texDelays, frame);
            }
            this.gfx.frame(frame);
        }
        return super.texture();
    }

    @Override
    public void displayTime(final long ms) {
        // STATIC-IMAGE DISPLAY DURATION; ms <= 0 SHOWS THE STILL UNTIL IT IS STOPPED
        this.displayTimeMs = ms;
        // (RE)ARM AT ONCE IF A STATIC IMAGE IS ALREADY LIVE; OTHERWISE showStatic ARMS IT AT LOAD.
        if (this.loaded && !this.animated && this.texTimeline == null) this.armStaticClock();
    }

    @Override
    public long displayTime() { return this.displayTimeMs; }

    @Override
    public float fps() {
        if (!this.loaded || this.currentDelayMs <= 0L) return 0f;
        return 1_000f / this.currentDelayMs;
    }

    @Override public boolean live() { return false; }
    @Override public boolean canSeek() { return this.playable && this.animated; }
    @Override public boolean canPlay() { return this.playable; }

    @Override
    public long duration() {
        return Math.max(0L, this.knownDuration);
    }

    @Override
    public boolean speed(final float speed) {
        // RESOLVE THE CALL BEFORE TAKING THE CLOCK: THE RENDER THREAD TAKES THIS SAME LOCK ON EVERY DRAW TO READ THE PASSIVE CLOCK
        final float old = this.speed();
        if (!super.speed(speed)) return false;
        synchronized (this.clock) {
            // CLOSE THE PASSIVE / STATIC CLOCK SEGMENT AT THE OUTGOING RATE: THE ELAPSED TIME RAN AT
            // `old`, SO SCALING IT BY THE NEW RATE WOULD JUMP THE MEDIA TIME
            if ((this.texTimeline != null || this.staticTimed) && this.status == Status.PLAYING) {
                final long now = nowMs();
                this.clockBase += (long) ((now - this.wallBase) * old);
                this.wallBase = now;
            }
        }
        // WAKE THE STREAMING LOOP SO A LIVE SPEED CHANGE RESCALES THE CURRENT FRAME DELAY (MODE 3)
        this.wake();
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // LAUNCHES A PREPARE PASS, TEARING DOWN THE ONE IN FLIGHT. THE PAUSE INTENT AND THE INITIAL SEEK
    // ARE SEEDED HERE ONLY, NEVER FROM A LEFTOVER TRIGGER OF A PRIOR SESSION
    @Override
    boolean launch(final boolean initialPause, final long seekMs) {
        if (this.released) return false;
        final Future<?> oldTask = this.lifecycleTask;
        if ((this.lifecycleThread != null && this.lifecycleThread.isAlive()) || (oldTask != null && !oldTask.isDone())) this.teardown(true);
        final Thread old = this.lifecycleThread;
        final int serial;
        // MINT THE SERIAL UNDER signals: TWO CONCURRENT start() CALLS (GAME THREAD + NETWORK HANDLER) MUST NOT
        // READ-MODIFY-WRITE THE SAME lifecycleSerial AND LET BOTH PREPARE PASSES BELIEVE THEY ARE CURRENT
        synchronized (this.signals) {
            if (this.released) return false;
            serial = this.lifecycleSerial + 1;
            this.lifecycleSerial = serial;
            this.exception = null; // A FAILING PASS RECORDS UNDER signals AGAINST ITS SERIAL, SO AN OLDER ONE NEVER LANDS HERE
            final Status prev = this.status;
            this.resetForStart(initialPause);
            this.startSeekMs = seekMs;
            this.queueStatus(prev, Status.WAITING);
        }
        // HEARD BEFORE THE PASS IS HANDED OFF, SO WAITING ALWAYS PRECEDES ITS OWN LOADING
        this.deliverStatus();
        synchronized (this.signals) {
            // A NEWER start()/stop() SUPERSEDED THIS ONE WHILE THE LISTENER RAN: IT OWNS THE PIPELINE NOW
            if (this.released || serial != this.lifecycleSerial) return !this.released;
            this.lifecycleTask = SINGLE_FRAME_POOL.submit(() -> {
                synchronized (this.signals) {
                    if (this.released || this.triggerStop || serial != this.lifecycleSerial || Thread.currentThread().isInterrupted()) return;
                    this.prepareActive++;
                    this.prepareThread = Thread.currentThread();
                }
                try {
                    if (old != null) ThreadTool.join(old);
                    if (oldTask != null && !oldTask.isDone()) oldTask.cancel(true);
                    if (this.released || this.triggerStop || serial != this.lifecycleSerial || Thread.currentThread().isInterrupted()) return;
                    this.prepare(serial);
                } finally {
                    synchronized (this.signals) {
                        this.prepareThread = null;
                        this.prepareActive--;
                        this.signals.notifyAll();
                    }
                }
            });
        }
        return true;
    }

    @Override
    boolean holdStatus() { return this.releasing; }

    // ==========================================================================
    // LIFECYCLE
    // ==========================================================================
    // CANCELS THE PIPELINE, DROPS THE TIMELINE AND REWINDS TO 0; SHARED BY stop() AND THE RESTART IN launch().
    // ANSWERS WHETHER A TRANSITION TO STOPPED HAPPENED
    private boolean teardown(final boolean restart) {
        final Status prev;
        final boolean quiet;
        synchronized (this.signals) {
            // A NEW SERIAL ORPHANS THE PREPARE PASS IN FLIGHT: ITS LATE STATUS WRITES ARE REJECTED.
            // A PLAYER THAT NEVER RAN STAYS WAITING: THERE IS NOTHING TO STOP
            this.lifecycleSerial++;
            this.triggerStop = true;
            prev = this.status;
            // A RESTART OF A RUN THAT ALREADY ENDED OR FAILED PUBLISHES NO STOPPED: ITS LISTENERS JUST MOVED ON
            quiet = restart && (prev == Status.ENDED || prev == Status.ERROR);
            if (prev != Status.WAITING && !quiet) {
                this.status = Status.STOPPED;
                this.queueStatus(prev, Status.STOPPED);
            }
        }
        this.texTimeline = null;
        this.texDelays = null;
        IOTool.closeQuietly(this.activeReader);
        final Future<?> task = this.lifecycleTask;
        if (task != null) task.cancel(true);
        final Thread t = this.lifecycleThread;
        if (t != null) t.interrupt();
        this.paused = false;
        this.loaded = false;
        this.time = 0L;
        synchronized (this.clock) {
            this.clockBase = 0L;
            this.wallBase = nowMs();
        }
        this.deliverStatus();
        return prev != Status.STOPPED && prev != Status.WAITING;
    }

    // PUBLISHES A STATUS FROM A PIPELINE PASS, REJECTED WHEN A LATER start()/stop() ORPHANED THAT PASS; QUEUED WITH THE
    // SERIAL CHECK, SO AN ORPHANED PASS CAN NEVER BE HEARD AFTER THE TRANSITION THAT ORPHANED IT
    private void publish(final Status next, final int serial) {
        synchronized (this.signals) {
            if (serial != this.lifecycleSerial) return;
            this.queueStatus(this.status, next);
            this.status = next;
        }
        this.deliverStatus();
    }

    // ENDS THE PASS OF serial IN ERROR. THE SERIAL CHECK, THE RECORD AND THE STATUS SHARE ONE signals SECTION WITH launch(), SO A
    // PASS A NEWER RUN ALREADY RETIRED CAN NEITHER LEAVE ITS FAILURE ON THAT RUN NOR FAIL IT
    private void fail(final Exception failure, final int serial) {
        synchronized (this.signals) {
            if (serial != this.lifecycleSerial) return;
            this.exception(failure);
            this.playable = false;
            this.loaded = false;
            this.queueStatus(this.status, Status.ERROR);
            this.status = Status.ERROR;
        }
        this.deliverStatus();
    }

    // ==========================================================================
    // MODE DISPATCH
    // ==========================================================================
    // OPENS THE SOURCE ONCE, READS METADATA AND DISPATCHES TO EXACTLY ONE OF THE THREE PLAYBACK MODES BELOW. MODE 3 HANDS
    // THE READER OFF TO A LIFECYCLE THREAD, OTHERWISE THE FINALLY BLOCK CLOSES IT (handedOff GUARDS A DOUBLE CLOSE)
    private void prepare(final int serial) {
        ImageReader reader = null;
        boolean handedOff = false;
        // A RESTART FROM ENDED/STOPPED RESUMES AT THE SCRUBBED POSITION ONCE THE FRAMES ARE LIVE
        final long startAt = this.startSeekMs;
        this.startSeekMs = -1L;
        try {
            this.publish(Status.LOADING, serial);

            reader = this.openSource();
            if (this.released || this.triggerStop || serial != this.lifecycleSerial || Thread.currentThread().isInterrupted()) return;
            this.sourceWidth = reader.width();
            this.sourceHeight = reader.height();
            if (this.sourceWidth <= 0 || this.sourceHeight <= 0)
                throw new IOException("Invalid image dimensions: " + this.sourceWidth + "x" + this.sourceHeight);
            this.resolveQuality(this.sourceWidth, this.sourceHeight);
            this.animated = reader.frameCount() > 1;
            this.knownDuration = Math.max(0L, reader.duration());
            this.pixelFormat = reader.pixelFormat();
            this.applyTarget();
            if (!this.gfx.format(this.pixelFormat, this.outWidth, this.outHeight))
                throw new IOException("Graphics engine refused " + this.pixelFormat + " " + this.outWidth + "x" + this.outHeight);
            this.uploadWidth = this.outWidth;
            this.uploadHeight = this.outHeight;

            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.publish(Status.STOPPED, serial);
                return;
            }

            // MODE 1: STATIC IMAGE, UPLOAD AND DONE, NO LIFECYCLE THREAD.
            if (!this.animated) {
                this.showStatic(reader, serial);
                return;
            }

            // MODE 2: PRE-UPLOADED FRAME TEXTURES WHEN THEY FIT THE VRAM BUDGET ON A CAPABLE ENGINE.
            if (this.shouldUseFrameTextures(reader)) {
                this.prepareTextures(reader, serial, startAt);
                return;
            }

            // MODE 3: STREAMING DECODE, HANDING THE READER OFF TO THE LIFECYCLE THREAD; THE LOOP CONSUMES
            // THE RESTART POSITION AS ITS FIRST SEEK
            if (startAt >= 0) this.seekTarget = startAt;
            final ImageReader streamingReader = reader;
            reader = null;
            handedOff = true;
            this.lifecycleThread = STREAM_THREADS.newThread(() -> this.playStreaming(streamingReader, serial));
            this.lifecycleThread.start();
        } catch (final Exception e) {
            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.publish(Status.STOPPED, serial);
            } else {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
                this.fail(e, serial);
            }
        } finally {
            if (!handedOff) {
                if (this.lifecycleSerial == serial) this.clearBuffers();
                IOTool.closeQuietly(reader);
                if (this.lifecycleSerial == serial) this.activeReader = null;
            }
            if (this.lifecycleSerial == serial) this.lifecycleTask = null;
        }
    }

    // FRAME STEPPING IS A PAUSED-ONLY CONTROL ON EVERY PLAYER: THE PASSIVE CLOCK STEPS IN PLACE, THE
    // STREAMING LOOP TAKES A TRIGGER, AND A STILL HAS NO OTHER FRAME TO SHOW
    private boolean stepFrame(final int direction) {
        if (!this.paused() || !this.animated || !this.loaded) return false;
        if (this.texTimeline != null) return this.stepTexture(direction);
        final Thread lifecycle = this.lifecycleThread;
        if (lifecycle == null || !lifecycle.isAlive() || (direction < 0 && this.currentFrameIndex <= 0)) return false;
        if (direction > 0) this.triggerNextFrame = true;
        else this.triggerPrevFrame = true;
        this.wake();
        return true;
    }

    // ==========================================================================
    // MODE 1: STATIC IMAGE
    // ==========================================================================
    // ONE FRAME IS UPLOADED ONCE AND THE READER IS MARKED EXHAUSTED. NO LIFECYCLE
    // THREAD IS NEEDED: THE GFX HANDLE REMAINS LIVE UNTIL STOP/RELEASE.
    private void showStatic(final ImageReader reader, final int serial) throws IOException {
        // ARM THE DISPLAY CLOCK BEFORE showFirstFrame MARKS loaded, SO knownDuration/staticTimed ARE
        // ALREADY CORRECT THE INSTANT A CALLER OBSERVES loaded (NO WINDOW REPORTING reader.duration()).
        this.armStaticClock();
        this.showFirstFrame(reader, serial);
        this.readerExhausted = true;
        LOGGER.debug(IT, "Loaded: {} ({}x{}, static, cache/threadless{})",
                this.source, this.sourceWidth, this.sourceHeight,
                this.staticTimed ? ", displayTime=" + this.displayTimeMs + "ms" : "");
    }

    // ARMS THE STATIC DISPLAY CLOCK WHEN displayTime(ms>0) WAS SET: THE STILL IMAGE RIDES THE
    // PASSIVE clock (knownDuration = displayTimeMs) AND texTime() TRANSITIONS IT TO ENDED.
    private void armStaticClock() {
        final long display = this.displayTimeMs;
        this.staticTimed = display > 0L;
        if (!this.staticTimed) {
            this.knownDuration = 0L; // UNLIMITED STATIC REPORTS NO DURATION
            return;
        }
        this.knownDuration = display;
        synchronized (this.clock) {
            this.clockBase = 0L;
            this.wallBase = nowMs();
        }
    }

    // ==========================================================================
    // MODE 2: PRE-UPLOADED FRAME TEXTURES + PASSIVE CLOCK
    // ==========================================================================
    // ALL FRAMES ARE DECODED UP FRONT AND UPLOADED AS PER-FRAME TEXTURES. NO THREAD EXISTS:
    // texture() RESOLVES THE DISPLAYED FRAME FROM ELAPSED TIME ON EVERY CALL, SO PLAYBACK COSTS
    // ZERO UPLOADS, ZERO WAKEUPS, AND HAS NO SLEEP DRIFT.

    // ELIGIBLE WHEN THE FRAME SET AT THE UPLOAD TARGET FITS THE CONFIGURED VRAM BUDGET, THE FRAME
    // COUNT IS KNOWN AND SANE, AND THE GFX BACKEND SUPPORTS THE PATH.
    private boolean shouldUseFrameTextures(final ImageReader reader) {
        final int frames = reader.frameCount();
        if (frames <= 1 || frames > MAX_FRAME_TEXTURES || !this.gfx.preload()) return false;
        final long budget = DataTool.megabytes(Math.max(0L, WaterMediaConfig.player.tx.texturesBudget));
        // RGBA8 PER FRAME AT THE UPLOAD TARGET REGARDLESS OF THE SOURCE LAYOUT; prepareTextures DECODES THE SET THROUGH
        // A DownscaleFilter, SO ITS MEMORY PEAK FOLLOWS THE SAME TARGET AND ONE GATE COVERS VRAM AND DECODE ALIKE.
        return (long) frames * this.outWidth * this.outHeight * 4L <= budget;
    }

    private void prepareTextures(final ImageReader reader, final int serial, final long startAt) throws IOException {
        this.publish(Status.BUFFERING, serial);
        this.clearBuffers();

        // SHRINK WHILE DECODING: THE RETAINED SET, ITS PEAK MEMORY AND THE BULK UPLOAD FOLLOW THE TARGET SIZE
        if (this.outWidth != this.sourceWidth || this.outHeight != this.sourceHeight)
            reader.addFilter(new DownscaleFilter(this.outWidth, this.outHeight));
        // DECODE EVERY FRAME. IF readAll() YIELDS ONLY ONE FRAME (E.G. APNG WITH A SINGLE FCTL)
        // FALL BACK TO STATIC SEMANTICS RATHER THAN BUILDING A CLOCK FOR NOTHING.
        final ImageData data = reader.readAll();
        // A TRUNCATED/CORRUPT ANIMATION CAN DECODE TO ZERO FRAMES EVEN THOUGH frameCount() REPORTED >1;
        // FAIL CLEANLY INSTEAD OF INDEXING frames()[0] (AIOOBE SWALLOWED AS A VAGUE "Lifecycle error").
        if (data.frames().length == 0) throw new IOException("No frames decoded from: " + this.source);
        if (data.frames().length == 1) {
            // ALREADY AT THE UPLOAD SIZE: POOL IT WITHOUT THE RESCALE copyFrame WOULD APPLY
            final ByteBuffer single = this.borrowBuffer();
            single.put(data.frames()[0].duplicate()).flip();
            this.uploadBuffer(single, this.outWidth, this.outHeight);
            this.currentFrameIndex = 0;
            this.currentDelayMs = 0L;
            this.time = 0L;
            this.animated = false;
            this.readerExhausted = true;
            this.armStaticClock();
            this.loaded = true;
            this.playable = true;
            this.resolveInitialStatus(serial);
            return;
        }

        if (!this.gfx.preload(data.frames()))
            throw new IOException("GFXEngine rejected preloaded frame textures for: " + this.source);
        this.gfx.frame(0);

        // BUILD THE CUMULATIVE TIMELINE THAT DRIVES THE PASSIVE CLOCK
        final long[] delays = data.delays();
        final long[] timeline = new long[data.frames().length];
        long total = 0L;
        for (int i = 0; i < timeline.length; i++) {
            timeline[i] = total;
            total += Math.max(1L, delayAt(delays, i));
        }
        this.knownDuration = total;
        this.currentFrameIndex = 0;
        this.currentDelayMs = delayAt(delays, 0);
        this.nextDecodedIndex = timeline.length;
        this.readerExhausted = true;
        synchronized (this.clock) {
            this.clockBase = startAt >= 0 ? Math.min(startAt, total - 1L) : 0L;
            this.wallBase = nowMs();
        }
        this.texDelays = delays;
        this.texTimeline = timeline;
        this.loaded = true;
        this.playable = true;
        this.resolveInitialStatus(serial);

        LOGGER.debug(IT, "Loaded: {} ({}x{}, {} frame textures, passive clock, duration={}ms)",
                this.source, this.sourceWidth, this.sourceHeight, timeline.length, this.knownDuration);
    }

    // RESOLVES THE PASSIVE-CLOCK MEDIA TIME, FOLDING LOOP WRAPS AND THE ENDED TRANSITION.
    // SHARED BY MODE 2 FRAME TEXTURES AND THE TIMED-STATIC DISPLAY CLOCK.
    private long texTime() {
        final long result;
        synchronized (this.clock) {
            final long duration = Math.max(1L, this.knownDuration);
            long t = this.clockBase;
            if (this.status == Status.PLAYING) t += (long) ((nowMs() - this.wallBase) * this.speed());
            if (t < duration) return t;
            // A TIMED STILL NEVER LOOPS: ITS WINDOW ENDS THE DISPLAY REGARDLESS OF repeat()
            if (this.repeat() && !this.staticTimed) {
                t %= duration;
                this.clockBase = t;
                this.wallBase = nowMs();
                return t;
            }
            this.clockBase = duration;
            this.wallBase = nowMs();
            synchronized (this.signals) {
                if (this.status == Status.PLAYING) {
                    this.status = Status.ENDED;
                    this.queueStatus(Status.PLAYING, Status.ENDED);
                }
            }
            result = duration;
        }
        // HEARD OUTSIDE THE clock LOCK SO A LISTENER RE-ENTERING THE PLAYER CANNOT DEADLOCK
        this.deliverStatus();
        return result;
    }

    private boolean stepTexture(final int direction) {
        final long[] timeline = this.texTimeline;
        if (timeline == null) return false;
        synchronized (this.clock) {
            final int idx = frameAt(timeline, Math.min(this.clockBase, this.knownDuration - 1L));
            final int target = MathUtil.clamp(idx + direction, 0, timeline.length - 1);
            if (target == idx) return false; // ALREADY AT THE FIRST/LAST FRAME, NOTHING TO STEP TO
            this.clockBase = timeline[target];
            this.wallBase = nowMs();
            this.currentFrameIndex = target;
            this.currentDelayMs = delayAt(this.texDelays, target);
        }
        return true;
    }

    // ==========================================================================
    // MODE 3: STREAMING DECODE WITH PREFETCH
    // ==========================================================================
    // EACH next() DECODES ONE FRAME ON DEMAND; WHILE A FRAME IS DISPLAYED THE LOOP DECODES AHEAD INTO A BOUNDED QUEUE, AND
    // AN EMPTY QUEUE ENTERS BUFFERING, REFILLS A SMALL BATCH AND RESUMES. SEEK, LOOP AND STEP-BACKWARDS REWIND THE SOURCE
    // (reset() WHEN SUPPORTED) AND DECODE FORWARD, BECAUSE ANIMATED FRAMES DEPEND ON PRIOR ONES

    // WRAPS playStream(...) WITH FIRST-FRAME PUBLISH AND READER LIFECYCLE.
    private void playStreaming(ImageReader reader, final int serial) {
        try {
            this.showFirstFrame(reader, serial);
            LOGGER.debug(IT, "Loaded: {} ({}x{}, animated, duration={}ms, prefetch<={})",
                    this.source, this.sourceWidth, this.sourceHeight, this.knownDuration, this.prefetchMax);
            reader = this.playStream(reader, serial);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            if (this.triggerStop) this.publish(Status.STOPPED, serial);
        } catch (final Exception e) {
            if (this.triggerStop || Thread.currentThread().isInterrupted()) {
                this.publish(Status.STOPPED, serial);
            } else {
                LOGGER.error(IT, "Lifecycle error: {}", this.source, e);
                this.fail(e, serial);
            }
        } finally {
            if (this.lifecycleSerial == serial) this.clearBuffers();
            IOTool.closeQuietly(reader);
            if (this.lifecycleSerial == serial) this.activeReader = null;
        }
    }

    // STREAMING PLAYBACK LOOP. DRIVES THE ANIMATION CLOCK, PREFETCHES AHEAD,
    // AND ABSORBS PAUSE / SEEK / STEP TRIGGERS WITHOUT LEAKING DECODE WORK.
    private ImageReader playStream(ImageReader reader, final int serial) throws InterruptedException, IOException {
        while (!Thread.currentThread().isInterrupted()) {
            // HOT scale/LOD CHANGES: FRAMES DECODED FROM NOW ON SCALE TO THE NEW TARGET, FRAMES ALREADY QUEUED KEEP
            // THEIR SIZE AND DRAIN FIRST (uploadBuffer RECONFIGURES THE ENGINE WHEN THE SIZE FLIPS)
            this.applyTarget();

            // STOP
            if (this.triggerStop) {
                this.triggerStop = false;
                this.publish(Status.STOPPED, serial);
                return reader;
            }

            // PAUSE / RESUME. STRAY TRIGGERS ARE CLEARED SO SIGNAL WAITS NEVER SPIN.
            if (this.triggerPause && !this.paused) {
                this.paused = true;
                this.triggerPause = false;
                this.publish(Status.PAUSED, serial);
                this.bufferPool.clear(); // TRIM SPARE BUFFERS WHILE PAUSED; QUEUE AND IN-FLIGHT STAY
            }
            if (this.triggerResume) {
                this.triggerResume = false;
                if (this.paused) {
                    this.paused = false;
                    this.publish(Status.PLAYING, serial);
                }
            }
            if (this.triggerPause && this.paused) this.triggerPause = false;
            if (!this.paused) {
                this.triggerNextFrame = false;
                this.triggerPrevFrame = false;
            }

            // SEEK BY REWINDING AND DECODING FORWARD TO THE TARGET, THEN DROP THE PREFETCH QUEUE.
            final long target = this.seekTarget;
            if (target >= 0) {
                this.seekTarget = -1L;
                this.clearPrefetch();
                reader = this.reopen(reader);
                final ByteBuffer frame = this.seekReaderToTime(reader, target);
                if (frame != null) this.uploadFrame(frame);
                this.nextDecodedIndex = this.currentFrameIndex + 1;
                this.readerExhausted = false;
                if (this.paused) { this.awaitSignal(PAUSE_WAIT_MS); continue; }
            }

            // PAUSED FRAME STEPPING: THE CLOCK IS STOPPED AND PREFETCH IS INACTIVE.
            if (this.paused) {
                if (this.triggerNextFrame) {
                    this.triggerNextFrame = false;
                    this.stepForward(reader);
                }
                if (this.triggerPrevFrame) {
                    this.triggerPrevFrame = false;
                    final int targetIdx = this.currentFrameIndex - 1;
                    if (targetIdx >= 0) {
                        this.clearPrefetch();
                        reader = this.reopen(reader);
                        final ByteBuffer frame = this.seekReaderToFrame(reader, targetIdx);
                        if (frame != null) this.uploadFrame(frame);
                        this.nextDecodedIndex = this.currentFrameIndex + 1;
                        this.readerExhausted = false;
                    }
                }
                this.awaitSignal(PAUSE_WAIT_MS);
                continue;
            }

            // DISPLAY WINDOW: DECODE AHEAD, THEN WAIT OUT THE REMAINDER. THE DEADLINE IS RECOMPUTED
            // FROM THE LIVE speed EACH ITERATION SO A speed(float) CHANGE (WHICH CALLS wake()) RESCALES
            // THIS FRAME INSTEAD OF ONLY TAKING EFFECT NEXT FRAME. SEEK/PAUSE/STOP ALSO SIGNAL THE WAIT.
            final long frameStart = nowMs();
            this.fillQueueUntil(reader, frameStart + Math.max(1L, (long) (this.currentDelayMs / this.speed())));
            while (!Thread.currentThread().isInterrupted() && !this.signaled()) {
                final long remaining = frameStart + Math.max(1L, (long) (this.currentDelayMs / this.speed())) - nowMs();
                if (remaining <= 0L) break;
                this.awaitSignal(remaining);
            }
            if (this.signaled()) continue; // RE-ENTER WITHOUT ADVANCING THE CLOCK

            // ADVANCE BY POPPING A QUEUED FRAME OR STALLING TO BUFFERING AND REFILLING.
            this.time += this.currentDelayMs;
            PrefetchedFrame next = this.prefetchQueue.pollFirst();
            if (next == null && !this.readerExhausted) {
                // DECODING FELL BEHIND, SO SWITCH TO BUFFERING, REFILL, THEN RESUME.
                this.publish(Status.BUFFERING, serial);
                if (ThreadTool.tryAcquire(DECODE_PERMITS, REFILL_PERMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        while (this.prefetchQueue.size() < this.prefetchRefill && !this.readerExhausted) {
                            if (!this.queueNextFrame(reader)) break;
                        }
                    } finally {
                        DECODE_PERMITS.release();
                    }
                }
                this.publish(this.paused ? Status.PAUSED : Status.PLAYING, serial);
                next = this.prefetchQueue.pollFirst();
            }

            if (next != null) {
                this.uploadBuffer(next.pixels, next.width, next.height);
                this.currentDelayMs = next.delay;
                this.currentFrameIndex = next.index;
                continue;
            }

            // NO FRAME AVAILABLE MEANS EOF.
            if (this.knownDuration <= 0L) this.knownDuration = this.time;
            if (this.repeat()) {
                this.clearPrefetch();
                reader = this.reopen(reader);
                if (reader.hasNext()) {
                    this.uploadFrame(reader.next());
                    this.time = 0L;
                    this.currentFrameIndex = 0;
                    this.currentDelayMs = reader.delay();
                    this.nextDecodedIndex = 1;
                    this.readerExhausted = false;
                    continue;
                }
            }
            this.publish(Status.ENDED, serial);
            return reader;
        }
        if (this.triggerStop) {
            this.triggerStop = false;
            this.publish(Status.STOPPED, serial);
        }
        return reader;
    }

    // DECODES AHEAD UNTIL THE DEADLINE OR QUEUE LIMIT, LEAVING A SMALL SAFETY MARGIN.
    // THE SHARED PERMIT POOL BOUNDS HOW MANY PLAYERS DECODE CONCURRENTLY.
    private void fillQueueUntil(final ImageReader reader, final long deadlineMs) throws IOException {
        if (this.readerExhausted || this.prefetchQueue.size() >= this.prefetchMax) return;
        final long budget = deadlineMs - nowMs() - PREFETCH_SAFETY_MARGIN_MS;
        if (budget <= 0L) return;
        if (!ThreadTool.tryAcquire(DECODE_PERMITS, budget, TimeUnit.MILLISECONDS)) return;
        try {
            while (this.prefetchQueue.size() < this.prefetchMax && !this.readerExhausted) {
                if (deadlineMs - nowMs() <= PREFETCH_SAFETY_MARGIN_MS) break;
                if (!this.queueNextFrame(reader)) break;
            }
        } finally {
            DECODE_PERMITS.release();
        }
    }

    // DECODES ONE FRAME INTO THE PREFETCH QUEUE AND RETURNS FALSE AT EOF.
    private boolean queueNextFrame(final ImageReader reader) throws IOException {
        if (this.readerExhausted) return false;
        if (!reader.hasNext()) {
            this.readerExhausted = true;
            return false;
        }
        final int idx = this.nextDecodedIndex++;
        final ByteBuffer frame = reader.next();
        final long delay = reader.delay();
        this.prefetchQueue.offerLast(new PrefetchedFrame(this.copyFrame(frame), delay, idx, this.outWidth, this.outHeight));
        return true;
    }

    // PAUSED STEP-FORWARD PREFERS A QUEUED FRAME, THEN DECODES DIRECTLY IF NEEDED.
    private void stepForward(final ImageReader reader) throws IOException {
        final PrefetchedFrame queued = this.prefetchQueue.pollFirst();
        if (queued != null) {
            this.uploadBuffer(queued.pixels, queued.width, queued.height);
            this.time += this.currentDelayMs;
            this.currentDelayMs = queued.delay;
            this.currentFrameIndex = queued.index;
            return;
        }
        if (this.readerExhausted) return;
        if (reader.hasNext()) {
            final int idx = this.nextDecodedIndex++;
            this.uploadFrame(reader.next());
            this.time += this.currentDelayMs;
            this.currentDelayMs = reader.delay();
            this.currentFrameIndex = idx;
        } else {
            this.readerExhausted = true;
        }
    }

    // ==========================================================================
    // SHARED: SOURCE LIFECYCLE
    // ==========================================================================
    private ImageReader openSource() throws IOException {
        final URI uri = this.source.uri(this.quality);
        super.quality(this.source.qualityOf(uri));
        final long maxBytes = DataTool.megabytes(Math.max(1L, WaterMediaConfig.codecs.maxImageSourceSize));
        try {
            final NetworkCache.CachedBytes sourceBytes = NetworkCache.read(uri, this.source.headers(), IMAGE_ACCEPT, maxBytes,
                    WaterMediaConfig.player.tx.cache);
            if (this.released || this.triggerStop || Thread.currentThread().isInterrupted())
                throw new IOException("Image loading was cancelled");
            // MRL ALREADY TYPED THE SOURCE (CONTENT-TYPE OR, FOR AMBIGUOUS MIMES, A BYTE SNIFF) AND THE DECODER VALIDATES THE
            // BYTES, SO THE SERVER'S CONTENT-TYPE IS IGNORED: IT WOULD REJECT IMAGES SERVED AS application/octet-stream OR text/xml
            final ByteBuffer bytes = ByteBuffer.wrap(sourceBytes.bytes());
            // DECODERS DELIVER BGRA EXCEPT GRAYSCALE JPEG, WHICH STAYS ONE LUMA BYTE PER PIXEL FOR THE GPU ENGINES; AN ENGINE THAT
            // DECLINES GRAY (SOFTWARE AWT/JavaFX) GETS BGRA FROM THE DECODER, LIKE FFMediaPlayer'S sws FALLBACK
            final ImageReader reader = this.gfx != null && !this.gfx.supports(PixelFormat.GRAY)
                    ? CodecsAPI.reader(bytes, PixelFormat.BGRA)
                    : CodecsAPI.reader(bytes);
            this.activeReader = reader;
            return reader;
        } catch (final Throwable t) {
            if (t instanceof final IOException io) throw io;
            if (t instanceof final RuntimeException re) throw re;
            throw new IOException("Failed to open image source: " + uri, t);
        }
    }

    // REWINDS THE READER IN PLACE WHEN THE CODEC SUPPORTS IT; OTHERWISE REOPENS THE SOURCE.
    // RESET AVOIDS RE-READING THE CACHED SOURCE FROM DISK ON EVERY LOOP ITERATION.
    private ImageReader reopen(final ImageReader reader) throws IOException {
        if (reader.reset()) return reader;
        reader.close();
        return this.openSource();
    }

    // DECODES, UPLOADS, AND PUBLISHES THE FIRST FRAME. SHARED BETWEEN STATIC AND
    // STREAMING MODES; TEXTURE MODE NEVER CALLS IT BECAUSE IT BULK-UPLOADS UPFRONT.
    private void showFirstFrame(final ImageReader reader, final int serial) throws IOException {
        this.publish(Status.BUFFERING, serial);
        this.clearBuffers();

        if (!reader.hasNext()) throw new IOException("No frames decoded from: " + this.source);
        final ByteBuffer first = this.copyFrame(reader.next());
        this.uploadBuffer(first, this.outWidth, this.outHeight);
        this.currentFrameIndex = 0;
        this.currentDelayMs = reader.delay();
        this.time = 0L;
        this.loaded = true;
        this.playable = true;
        this.nextDecodedIndex = 1;
        this.readerExhausted = false;
        this.resolveInitialStatus(serial);
    }

    private ByteBuffer seekReaderToTime(final ImageReader reader, final long targetMs) throws IOException {
        if (!reader.hasNext()) {
            this.currentFrameIndex = 0;
            this.time = 0L;
            this.currentDelayMs = 0L;
            return null;
        }
        ByteBuffer frame = reader.next();
        long accum = 0L;
        int idx = 0;
        long delay = reader.delay();
        while (accum + delay <= targetMs) {
            if (!reader.hasNext()) break;
            frame = reader.next();
            accum += delay;
            idx++;
            delay = reader.delay();
        }
        this.currentFrameIndex = idx;
        this.time = accum;
        this.currentDelayMs = delay;
        return frame;
    }

    private ByteBuffer seekReaderToFrame(final ImageReader reader, final int targetIdx) throws IOException {
        if (!reader.hasNext()) {
            this.currentFrameIndex = 0;
            this.time = 0L;
            this.currentDelayMs = 0L;
            return null;
        }
        ByteBuffer frame = reader.next();
        long accum = 0L;
        int idx = 0;
        long delay = reader.delay();
        while (idx < targetIdx) {
            if (!reader.hasNext()) break;
            frame = reader.next();
            accum += delay;
            idx++;
            delay = reader.delay();
        }
        this.currentFrameIndex = idx;
        this.time = accum;
        this.currentDelayMs = delay;
        return frame;
    }

    // RESOLVES THE PLAYBACK STATUS AFTER THE FIRST FRAME GOES LIVE, RESPECTING
    // A PENDING PAUSE TRIGGER LEFT BY startPaused().
    private void resolveInitialStatus(final int serial) {
        if (this.triggerPause || this.paused) {
            this.paused = true;
            this.triggerPause = false;
            this.publish(Status.PAUSED, serial);
        } else {
            this.publish(Status.PLAYING, serial);
        }
    }

    // ==========================================================================
    // SHARED: SIGNALS (CALLER TO LIFECYCLE THREAD)
    // ==========================================================================
    private boolean signaled() {
        return this.triggerStop || this.triggerPause || this.triggerResume
                || this.triggerNextFrame || this.triggerPrevFrame || this.seekTarget >= 0L;
    }

    // WAITS UP TO timeoutMs UNLESS A TRIGGER IS ALREADY PENDING. wake() INTERRUPTS THE WAIT
    // SO CONTROLS REACT IMMEDIATELY EVEN DURING LONG FRAME DELAYS.
    private void awaitSignal(final long timeoutMs) {
        synchronized (this.signals) {
            if (this.signaled()) return;
            try {
                this.signals.wait(Math.max(1L, timeoutMs));
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake() {
        synchronized (this.signals) {
            this.signals.notifyAll();
        }
    }

    // ==========================================================================
    // SHARED: BUFFER POOL & GPU UPLOAD
    // ==========================================================================
    // BORROWS A FRAME-SIZED DIRECT BUFFER, REUSING A POOLED BUFFER WHEN POSSIBLE.
    private ByteBuffer borrowBuffer() {
        final ByteBuffer pooled = this.bufferPool.pollFirst();
        if (pooled != null && pooled.capacity() == this.bufferByteSize) {
            pooled.clear();
            return pooled;
        }
        return ByteBuffer.allocateDirect(this.bufferByteSize).order(ByteOrder.nativeOrder());
    }

    // RETURNS A BUFFER TO THE POOL WITHOUT KEEPING MORE SPARES THAN THE PREFETCH BUDGET NEEDS.
    private void returnBuffer(final ByteBuffer buffer) {
        if (buffer == null || buffer.capacity() != this.bufferByteSize) return;
        if (this.bufferPool.size() >= this.prefetchMax + POOL_SPARE) return;
        buffer.clear();
        this.bufferPool.offerLast(buffer);
    }

    // PUSHES A BUFFER TO THE GPU AND HOLDS BACK THE LAST IN_FLIGHT_KEEP SUBMISSIONS. IMAGE READERS
    // DELIVER ONE TIGHTLY PACKED PLANE, SO THE UPLOAD IS ALWAYS A SINGLE-PLANE SET
    private void uploadBuffer(final ByteBuffer buffer, final int w, final int h) {
        // SIZE FLIP (HOT scale/LOD CHANGE): RECONFIGURE THE ENGINE FOR THIS FRAME; A REFUSED SIZE DROPS THE FRAME
        if (this.uploadWidth != w || this.uploadHeight != h) {
            if (!this.gfx.format(this.pixelFormat, w, h)) {
                if (!this.refusedWarned) LOGGER.error(IT, "Graphics engine refused {} {}x{}; frame dropped", this.pixelFormat, w, h);
                this.refusedWarned = true;
                this.returnBuffer(buffer);
                return;
            }
            this.uploadWidth = w;
            this.uploadHeight = h;
        }
        this.gfx.upload(new ByteBuffer[]{buffer}, new int[]{0});
        this.inFlight.offerLast(buffer);
        while (this.inFlight.size() > IN_FLIGHT_KEEP) {
            this.returnBuffer(this.inFlight.pollFirst());
        }
    }

    // COPIES THE REUSED READER BUFFER BEFORE UPLOAD BECAUSE RENDER WORK MAY RUN ASYNCHRONOUSLY.
    private void uploadFrame(final ByteBuffer frame) {
        this.uploadBuffer(this.copyFrame(frame), this.outWidth, this.outHeight);
    }

    // COPIES A FRAME INTO A BORROWED POOL BUFFER (DOWNSCALING WHEN A scale/LOD
    // TARGET IS ACTIVE) AND LEAVES IT FLIPPED FOR READING.
    private ByteBuffer copyFrame(final ByteBuffer src) {
        final ByteBuffer dst = this.borrowBuffer();
        if (this.outWidth != this.sourceWidth || this.outHeight != this.sourceHeight) {
            DataTool.scaleArea(src, this.sourceWidth, this.sourceHeight, dst, this.outWidth, this.outHeight, this.pixelFormat);
        } else {
            final int savedPos = src.position();
            dst.put(src);
            src.position(savedPos);
        }
        dst.flip();
        return dst;
    }

    // RESOLVES THE UPLOAD TARGET FROM scale/LOD AND REBUILDS THE BUFFER BUDGET ON CHANGE; UNSCALABLE FORMATS UPLOAD AT
    // SOURCE SIZE. CALLED ONLY FROM THE PREPARE/LIFECYCLE THREAD SO outWidth/outHeight NEVER TEAR AGAINST A DECODE
    private void applyTarget() throws IOException {
        int w = this.sourceWidth;
        int h = this.sourceHeight;
        if (DataTool.scalable(this.pixelFormat)) {
            w = scaled(this.sourceWidth, this.scaleWidth, this.lod.percent());
            h = scaled(this.sourceHeight, this.scaleHeight, this.lod.percent());
        }
        if (w == this.outWidth && h == this.outHeight) return;
        final long byteSize = this.pixelFormat.frameBytes(w, h, 8);
        if (byteSize > Integer.MAX_VALUE) throw new IOException("Image dimensions exceed upload buffer limit: " + w + "x" + h);
        this.outWidth = w;
        this.outHeight = h;
        this.bufferByteSize = (int) byteSize;
        // CAP THE PREFETCH QUEUE SO DIRECT BUFFER MEMORY STAYS UNDER THE BUDGET
        final long bytesPerFrame = Math.max(1L, this.bufferByteSize);
        final int cap = (int) MathUtil.clamp(PREFETCH_BUDGET_BYTES / bytesPerFrame, 1L, PREFETCH_MAX);
        this.prefetchMax = cap;
        this.prefetchRefill = Math.min(PREFETCH_REFILL, cap);
    }

    // DRAINS THE PREFETCH QUEUE AND RETURNS EACH QUEUED BUFFER TO THE POOL.
    private void clearPrefetch() {
        for (final PrefetchedFrame f: this.prefetchQueue) this.returnBuffer(f.pixels);
        this.prefetchQueue.clear();
    }

    // ==========================================================================
    // SHARED: STATE RESET
    // ==========================================================================
    private void resetForStart(final boolean initialPause) {
        this.triggerStop = false;
        this.triggerPause = initialPause;
        this.triggerResume = false;
        this.triggerNextFrame = false;
        this.triggerPrevFrame = false;
        this.seekTarget = -1L;
        this.paused = false;
        this.loaded = false;
        this.animated = false;
        this.status = Status.WAITING;
        this.time = 0L;
        this.knownDuration = 0L;
        this.currentDelayMs = 0L;
        this.currentFrameIndex = -1;
        this.nextDecodedIndex = 0;
        this.readerExhausted = false;
        // FORCE A TARGET RECOMPUTE: THE NEW SOURCE MAY REUSE DIMENSIONS WITH A
        // DIFFERENT PIXEL FORMAT, WHICH CHANGES THE BUFFER BYTE SIZE
        this.outWidth = 0;
        this.outHeight = 0;
        this.texTimeline = null;
        this.texDelays = null;
        // staticTimed IS RE-DERIVED AT LOAD (showStatic); displayTimeMs IS USER CONFIG AND PERSISTS
        this.staticTimed = false;
        this.lifecycleThread = null;
        this.activeReader = null;
    }

    // DRAINS THE PREFETCH QUEUE, THE SPARE POOL, AND THE IN-FLIGHT BUFFERS: EVERYTHING IS DISCARDED.
    private void clearBuffers() {
        this.prefetchQueue.clear();
        this.bufferPool.clear();
        this.inFlight.clear();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // FINDS THE FRAME WHOSE DISPLAY WINDOW CONTAINS time IN A CUMULATIVE TIMELINE.
    private static int frameAt(final long[] timeline, final long time) {
        int idx = Arrays.binarySearch(timeline, time);
        if (idx < 0) idx = -idx - 2;
        return MathUtil.clamp(idx, 0, timeline.length - 1);
    }

    // DELAY OF FRAME index, CLAMPED TO THE LAST KNOWN DELAY; 0 WITHOUT DELAYS
    private static long delayAt(final long[] delays, final int index) {
        if (delays == null || delays.length == 0) return 0L;
        if (index < delays.length) return delays[Math.max(0, index)];
        return delays[delays.length - 1];
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // SINGLE PRE-DECODED FRAME HELD IN THE PREFETCH QUEUE. CARRIES ITS OWN UPLOAD
    // DIMENSIONS SO FRAMES QUEUED BEFORE A HOT scale/LOD CHANGE DRAIN CORRECTLY.
    private record PrefetchedFrame(ByteBuffer pixels, long delay, int index, int width, int height) {}
}
