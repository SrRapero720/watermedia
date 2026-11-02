package org.watermedia.api.player;

import org.watermedia.api.codecs.decoders.FFDecoder;
import org.watermedia.api.player.MediaPlayer.Status;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

/**
 * Single source of truth for time, status, seek requests, and inter-thread coordination.
 * Uses a reentrant lock plus condition for thread synchronization.
 * <p>
 * The clock is PTS-drift based (like ffplay):
 * <ul>
 *   <li>{@code time = ptsDrift + now - elapsed * (1 - speed)} when playing (speed-corrected)</li>
 *   <li>{@code time = pts} when not playing (frozen)</li>
 * </ul>
 * Status is volatile for lock-free reads; all mutations happen under the lock. It is the {@link FFDecoder.Timeline}
 * the FFmpeg decoder follows.
 */
final class MasterClock implements FFDecoder.Timeline {
    // AUDIO PTS DRIFT FROM THE CLOCK, IN SECONDS, PAST WHICH update() RECALIBRATES ptsDrift
    private static final double DRIFT_RECALIBRATION_SEC = 0.050;

    // LOCK + CONDITION
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = this.lock.newCondition();

    // CLOCK STATE: VOLATILE FOR LOCK-FREE READS
    private volatile double pts;
    private volatile double ptsDrift;
    private volatile double lastUpdated;
    private volatile double speed = 1.0;
    private volatile Status status = Status.WAITING;
    private volatile int serial;

    // SEEK STATE: ACCESSED UNDER LOCK
    private volatile FFDecoder.Seek pendingSeek;
    private volatile boolean pauseIntent;

    // PIPELINE STATE
    private volatile boolean demuxFinished;

    // STATUS LISTENER: TRANSITIONS ARE QUEUED UNDER THE LOCK AND DELIVERED AFTER IT IS RELEASED, IN ORDER
    // AND BY ONE THREAD AT A TIME (draining), SO A LISTENER RUNS UNDER NO LOCK AND MAY RE-ENTER THE CLOCK
    private volatile BiConsumer<Status, Status> listener;
    private final ArrayDeque<Status[]> events = new ArrayDeque<>();
    private boolean draining;

    // FRAME TIMING
    private volatile double frameDurationSec = 1.0 / 30.0;
    private volatile float fpsValue = 30.0f;
    private volatile long skipThresholdMs = 165;

    // ==========================================================================
    // PUBLIC API: CLOCK
    // ==========================================================================

    /** Returns the clock time in seconds, lock-free: speed-corrected {@code ptsDrift + now} while playing, else the frozen last PTS. */
    public double time() {
        final Status s = this.status;
        if (s != Status.PLAYING) return this.pts; // FROZEN IN ALL NON-PLAYING STATES
        final double now = wallclock();
        final double elapsed = now - this.lastUpdated;
        return this.ptsDrift + now - elapsed * (1.0 - this.speed);
    }

    /** Returns the clock time in milliseconds. */
    public long timeMs() {
        return (long) (this.time() * 1_000.0);
    }

    /** Updates the clock from a decoded frame of the current serial (audio, or video while it drives the clock); the first one while buffering resolves the pause intent. */
    public void update(final double ptsSec, final int serial) {
        this.lock.lock();
        try {
            // NO BACKWARD GUARD: THE SERIAL REJECTS STALE FRAMES
            if (serial != this.serial) return;
            final double now = wallclock();

            // ALWAYS UPDATE PTS (FOR DISPLAY AND BUFFERING TRANSITION)
            this.pts = ptsSec;

            // RECALIBRATE ONLY ON REAL DESYNC (SEEK, AUDIO GAP): AUDIO CONSUMED LATE BEHIND SLOW VIDEO WORK WOULD OTHERWISE
            // DRAG THE CLOCK BELOW WALL-CLOCK RATE. time() IS SPEED-CORRECTED, SO THE CHECK HOLDS AT ANY SPEED
            final double drift = ptsSec - this.time();
            if (this.status == Status.BUFFERING || Math.abs(drift) > DRIFT_RECALIBRATION_SEC) {
                this.ptsDrift = ptsSec - now;
                this.lastUpdated = now;
            }

            // IF BUFFERING → FIRST VALID UPDATE → TRANSITION TO PLAYING/PAUSED
            if (this.status == Status.BUFFERING) {
                this.status = this.pauseIntent ? Status.PAUSED : Status.PLAYING;
                this.events.addLast(new Status[] { Status.BUFFERING, this.status });
                this.condition.signalAll();
            }
        } finally {
            this.lock.unlock();
        }
        this.drain();
    }

    /** Updates the clock from a PTS in milliseconds; see {@link #update(double, int)}. */
    public void updateMs(final long ptsMs, final int serial) {
        this.update(ptsMs / 1_000.0, serial);
    }

    /** Sets the playback speed, recalibrating the clock so the current position stays continuous. */
    public void speed(final double speed) {
        this.lock.lock();
        try {
            final double current = this.time();
            this.speed = speed;
            final double now = wallclock();
            this.pts = current;
            this.ptsDrift = current - now;
            this.lastUpdated = now;
        } finally {
            this.lock.unlock();
        }
    }

    /** Returns the playback speed multiplier (1.0 = normal). */
    public double speed() { return this.speed; }

    // ==========================================================================
    // PUBLIC API: STATUS
    // ==========================================================================

    /** Registers the listener of every status transition, delivered in order outside the lock. */
    public void listener(final BiConsumer<Status, Status> listener) {
        this.listener = listener;
    }

    /** Returns the current status, lock-free. */
    public Status status() { return this.status; }

    /** Applies a transition {@link Status#allows(Status)} permits; true when it applied (see {@link #exchange(Status)}). */
    public boolean transition(final Status newStatus) {
        return this.exchange(newStatus) != null;
    }

    /** Applies a transition {@link Status#allows(Status)} permits and returns the status it left, or null when rejected. */
    public Status exchange(final Status newStatus) {
        // THE CLOCK FREEZES OR RECALIBRATES AROUND PLAYING AND EVERY WAITER WAKES; THE RETURNED STATUS LETS THE CALLER PUBLISH
        // THE EXACT PAIR INSTEAD OF A STATUS READ BEFORE OR AFTER THE SWAP. start(), reload(), requestSeek(), update(),
        // halt() AND reset() OWN THE MOVES THE ENUM LEAVES OUT
        final Status current;
        this.lock.lock();
        try {
            current = this.status;
            if (!current.allows(newStatus)) return null; // ALSO THE NO-OP: NO STATUS ALLOWS ITSELF

            // ADJUST CLOCK WHEN LEAVING PLAYING: FREEZE PTS
            if (current == Status.PLAYING) this.pts = this.time();

            // ADJUST CLOCK WHEN ENTERING PLAYING: RECALIBRATE DRIFT
            if (newStatus == Status.PLAYING) {
                final double now = wallclock();
                this.ptsDrift = this.pts - now;
                this.lastUpdated = now;
            }
            // STOPPED MEANS "BACK AT THE BEGINNING" FOR EVERY PLAYER
            if (newStatus == Status.STOPPED) this.pts = 0;

            this.status = newStatus;
            this.events.addLast(new Status[] { current, newStatus });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return current;
    }

    /** Forces the terminal stopped state from any status for a release and returns the status it left, or null when already stopped. */
    public Status halt() {
        // THE TRANSITION IS QUEUED, NOT DELIVERED: THE CALLER RUNS drain() ONCE IT HOLDS NO LOCK OF ITS OWN
        final Status current;
        this.lock.lock();
        try {
            current = this.status;
            if (current == Status.STOPPED) return null;
            this.pts = 0;
            this.pendingSeek = null;
            this.status = Status.STOPPED;
            this.events.addLast(new Status[] { current, Status.STOPPED });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        return current;
    }

    /** Takes a running clock back to loading for a new source, whatever control raced it, keeping the pause intent; false once the run ended, stopped or failed. */
    public boolean reload() {
        final Status current;
        this.lock.lock();
        try {
            current = this.status;
            if (current != Status.PLAYING && current != Status.PAUSED && current != Status.BUFFERING) return false;
            if (current == Status.PLAYING) this.pts = this.time();
            // THE NEXT SOURCE STARTS UNREAD: AN END THE PREVIOUS ONE REPORTED IS NOT ITS END
            this.demuxFinished = false;
            this.status = Status.LOADING;
            this.events.addLast(new Status[] { current, Status.LOADING });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return true;
    }

    // STARTS THE RUN FROM LOADING AT TIME 0, PLAYING OR PAUSED AS THE LATCHED INTENT SAYS, OR BUFFERING AT A SEEK REQUESTED
    // WHILE LOADING
    @Override
    public void start() {
        this.lock.lock();
        try {
            final double now = wallclock();
            final FFDecoder.Seek pending = this.pendingSeek;
            final Status prev = this.status;
            // A RUN THAT WAS STOPPED OR FAILED WHILE OPENING MUST NOT COME BACK TO LIFE
            if (prev != Status.LOADING && prev != Status.WAITING) return;
            this.pts = pending != null ? pending.targetMs() / 1_000.0 : 0;
            this.ptsDrift = this.pts - now;
            this.lastUpdated = now;
            this.status = pending != null ? Status.BUFFERING : this.pauseIntent ? Status.PAUSED : Status.PLAYING;
            if (prev != this.status) this.events.addLast(new Status[] { prev, this.status });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
    }

    // ==========================================================================
    // PUBLIC API: SEEK AND PAUSE
    // ==========================================================================

    /** Requests a seek, freezing the clock at the target in buffering; false without a pipeline to serve it (waiting, ended, stopped, error). */
    public boolean requestSeek(final long targetMs, final boolean precise) {
        this.lock.lock();
        try {
            // WHILE LOADING THE REQUEST WAITS FOR start() AND THE STATUS STAYS LOADING
            final Status current = this.status;
            if (current == Status.WAITING || current == Status.ENDED || current == Status.STOPPED || current == Status.ERROR)
                return false;
            this.pendingSeek = new FFDecoder.Seek(targetMs, precise);
            this.pts = targetMs / 1_000.0; // FREEZE CLOCK AT TARGET
            // INVALIDATE ALL CURRENT FRAMES, SO update() NEVER TURNS BUFFERING INTO PLAYING WITH STALE AUDIO
            this.serial++;
            if (current != Status.LOADING && current != Status.BUFFERING) {
                this.status = Status.BUFFERING;
                this.events.addLast(new Status[] { current, Status.BUFFERING });
            }
            this.demuxFinished = false; // CLEAR EOF FLAG FOR SEEK AFTER EOF
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return true;
    }

    @Override
    public FFDecoder.Seek consumeSeek() {
        this.lock.lock();
        try {
            final FFDecoder.Seek req = this.pendingSeek;
            this.pendingSeek = null;
            return req;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public boolean seekPending() { return this.pendingSeek != null; }

    /** Pauses or resumes, latching the intent that resolves buffering; true when the state, or the intent while loading, changed. */
    public boolean paused(final boolean paused) {
        final boolean changed;
        this.lock.lock();
        try {
            // NO RUN YET: THERE IS NOTHING TO LATCH INTO (reset() WOULD WIPE IT ANYWAY)
            if (this.status == Status.WAITING) return false;
            final boolean was = this.pauseIntent;
            this.pauseIntent = paused;
            // WHILE LOADING ONLY THE INTENT CHANGES AND start() APPLIES IT
            if (this.status == Status.LOADING) return was != paused;
            if (paused) {
                // PAUSE IS ALWAYS ALLOWED (BUFFERING→PAUSED, PLAYING→PAUSED)
                changed = this.transition(Status.PAUSED);
            } else {
                // RESUME: IF WE WERE PAUSED DURING BUFFERING, GO BACK TO BUFFERING
                // (NOT PLAYING): THE SEEK HASN'T RESOLVED YET.
                // update() WILL TRANSITION BUFFERING→PLAYING WHEN DATA ARRIVES.
                changed = this.status == Status.PAUSED && this.pendingSeek != null
                        ? this.transition(Status.BUFFERING) : this.transition(Status.PLAYING);
            }
        } finally {
            this.lock.unlock();
        }
        this.drain(); // THE NESTED TRANSITION QUEUED ITS EVENT WHILE THE LOCK WAS HELD
        return changed;
    }

    /** Whether the latched pause intent resolves buffering into paused rather than playing. */
    public boolean pauseRequested() { return this.pauseIntent; }

    // PLAYBACK IS HELD PAUSED BY REQUEST, SO THE DEMUXER PARKS
    @Override
    public boolean holding() { return this.pauseIntent && this.status == Status.PAUSED; }

    // ==========================================================================
    // PUBLIC API: THREAD COORDINATION
    // ==========================================================================

    // BLOCKS UNTIL THE STATUS CHANGES OR A NEW SEEK ARRIVES (timeoutMs 0 = INDEFINITE)
    @Override
    public void awaitChange(final long timeoutMs) throws InterruptedException {
        this.lock.lock();
        try {
            final Status initial = this.status;
            final FFDecoder.Seek initialSeek = this.pendingSeek;
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (this.status == initial && this.pendingSeek == initialSeek) {
                final long remaining = timeoutMs > 0 ? TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) : Long.MAX_VALUE;
                if (remaining <= 0) break;
                // KEEP A COARSE SAFETY CAP: consumeSeek() CLEARS pendingSeek WITHOUT SIGNALLING,
                // SO A PURE-SIGNAL WAIT COULD MISS THAT ONE TRANSITION.
                this.condition.await(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
            }
        } finally {
            this.lock.unlock();
        }
    }

    /** Blocks until the status leaves {@code current} or {@code timeoutMs} elapses (0 = indefinite) and returns the status it found. */
    public Status awaitNotStatus(final Status current, final long timeoutMs) throws InterruptedException {
        this.lock.lock();
        try {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (this.status == current) {
                final long remaining = timeoutMs > 0 ? TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) : Long.MAX_VALUE;
                if (remaining <= 0) break;
                // EVERY STATUS MUTATION SIGNALS THIS CONDITION, SO AWAIT THE FULL REMAINDER, NO POLLING CAP
                this.condition.await(remaining, TimeUnit.MILLISECONDS);
            }
            return this.status;
        } finally {
            this.lock.unlock();
        }
    }

    // ==========================================================================
    // PUBLIC API: PIPELINE AND FRAME TIMING
    // ==========================================================================

    /** Resets the pipeline state for a new run; the playback speed is a player setting and survives. */
    public void reset() {
        this.lock.lock();
        try {
            final double now = wallclock();
            final Status prev = this.status;
            this.pts = 0;
            this.ptsDrift = -now;
            this.lastUpdated = now;
            this.status = Status.WAITING;
            this.serial = 0;
            this.pendingSeek = null;
            this.pauseIntent = false;
            this.demuxFinished = false;
            if (prev != Status.WAITING) this.events.addLast(new Status[] { prev, Status.WAITING });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
    }

    @Override
    public void signalDemuxFinished() {
        this.lock.lock();
        try {
            this.demuxFinished = true;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    // THE DEMUX RESUMED AFTER EOF (E.G. AFTER A SEEK)
    @Override
    public void signalDemuxResumed() {
        this.lock.lock();
        try {
            this.demuxFinished = false;
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
    }

    /** Whether the demuxer reached end-of-stream, lock-free. */
    public boolean demuxFinished() { return this.demuxFinished; }

    /** Returns the current serial, lock-free. */
    public int serial() { return this.serial; }

    // ADOPTS THE DECODER'S PACKET QUEUE SERIAL AFTER A SEEK FLUSH, SO update() ACCEPTS POST-SEEK FRAMES AGAIN (A SERIAL MATCH
    // IS REQUIRED FOR THE BUFFERING TO PLAYING TRANSITION)
    @Override
    public void serial(final int serial) {
        this.lock.lock();
        try {
            this.serial = serial;
        } finally {
            this.lock.unlock();
        }
    }

    /** Sets the stream rate (at least 1 fps) when the video stream opens, with its frame duration and skip threshold. */
    public void fps(final float fps) {
        this.fpsValue = Math.max(fps, 1.0f);
        this.frameDurationSec = 1.0 / this.fpsValue;
        // SKIP THRESHOLD: ~5 FRAMES WORTH
        this.skipThresholdMs = (long) (this.frameDurationSec * 5.0 * 1_000.0);
    }

    /** Returns the stream rate in frames per second. */
    public float fps() { return this.fpsValue; }
    /** Returns the frame duration in milliseconds. */
    public long frameDurationMs() { return (long) (this.frameDurationSec * 1_000.0); }
    @Override
    public double frameDurationSec() { return this.frameDurationSec; }
    /** Returns the lateness in milliseconds (~5 frames) past which a video frame is skipped. */
    public long skipThresholdMs() { return this.skipThresholdMs; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // DELIVERS QUEUED TRANSITIONS OUTSIDE THE LOCK: A MUTATOR HANDS ITS EVENT TO A RUNNING DRAINER, A NESTED ONE TO ITS
    // OUTERMOST CALL, AND halt() TO ITS CALLER, SO THE LISTENER NEVER RUNS UNDER THE LOCK
    void drain() {
        if (this.lock.isHeldByCurrentThread()) return;
        while (true) {
            final Status[] event;
            this.lock.lock();
            try {
                if (this.draining || this.events.isEmpty()) return;
                event = this.events.pollFirst();
                this.draining = true;
            } finally {
                this.lock.unlock();
            }
            try {
                final BiConsumer<Status, Status> l = this.listener;
                if (l != null) l.accept(event[0], event[1]);
            } finally {
                this.lock.lock();
                try {
                    this.draining = false;
                } finally {
                    this.lock.unlock();
                }
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // WALLCLOCK IN SECONDS WITH NANOSECOND PRECISION
    private static double wallclock() {
        return System.nanoTime() / 1_000_000_000.0;
    }
}
