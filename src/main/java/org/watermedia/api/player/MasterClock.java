package org.watermedia.api.player;

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
 * Status is volatile for lock-free reads; all mutations happen under the lock. The clock never starts on its own: a
 * start, a seek or a rebuffer leaves it buffering, frozen where it will resume, until the player lands it on the
 * frames every stream has ({@link #land(double, int)}).
 */
final class MasterClock {
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

    // SEEK STATE, WRITTEN UNDER THE LOCK: epoch COUNTS THE SEEKS REQUESTED, SO A FRAME OF THE POSITION A NEWER ONE LEFT NEVER
    // MOVES THE CLOCK. unsettled SAYS A START, A SEEK OR A REBUFFER HAS NOT LANDED YET
    private volatile int epoch;
    private volatile Seek pendingSeek;
    private volatile boolean unsettled;
    private volatile boolean pauseIntent;
    private volatile long heldSinceNs; // WHEN THE CLOCK LAST ENTERED PAUSED

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

    /** Corrects the running clock with a frame of {@code epoch} (audio, or video while it drives the clock); a frame of an older seek and a clock that is not playing are left alone. */
    public void update(final double ptsSec, final int epoch) {
        this.lock.lock();
        try {
            // NO BACKWARD GUARD: THE EPOCH REJECTS STALE FRAMES
            if (epoch != this.epoch || this.status != Status.PLAYING) return;
            this.pts = ptsSec;

            // RECALIBRATE ONLY ON REAL DESYNC (AN AUDIO GAP): AUDIO CONSUMED LATE BEHIND SLOW VIDEO WORK WOULD OTHERWISE DRAG THE
            // CLOCK BELOW WALL-CLOCK RATE. time() IS SPEED-CORRECTED, SO THE CHECK HOLDS AT ANY SPEED
            if (Math.abs(ptsSec - this.time()) > DRIFT_RECALIBRATION_SEC) {
                final double now = wallclock();
                this.ptsDrift = ptsSec - now;
                this.lastUpdated = now;
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Lands the clock on {@code ptsSec}, where every stream of {@code epoch} has its first frame: buffering resolves
     * into playing, or into paused when a pause is latched, and a clock already paused just moves there. Returns false
     * when a newer seek was requested or the clock neither buffers nor holds.
     */
    public boolean land(final double ptsSec, final int epoch) {
        this.lock.lock();
        try {
            final Status current = this.status;
            if (epoch != this.epoch || current != Status.BUFFERING && current != Status.PAUSED) return false;
            final double now = wallclock();
            this.pts = ptsSec;
            this.ptsDrift = ptsSec - now;
            this.lastUpdated = now;
            this.unsettled = false;
            if (current == Status.BUFFERING) {
                this.status = this.pauseIntent ? Status.PAUSED : Status.PLAYING;
                if (this.pauseIntent) this.heldSinceNs = System.nanoTime();
                this.events.addLast(new Status[] { Status.BUFFERING, this.status });
            }
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return true;
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
        // THE EXACT PAIR INSTEAD OF A STATUS READ BEFORE OR AFTER THE SWAP. start(), reload(), requestSeek(), land(),
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
            if (newStatus == Status.PAUSED) this.heldSinceNs = System.nanoTime();
            // A REBUFFER LEAVES ONLY THROUGH land()
            if (newStatus == Status.BUFFERING) this.unsettled = true;

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
            this.status = Status.LOADING;
            this.events.addLast(new Status[] { current, Status.LOADING });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return true;
    }

    /** Leaves loading once the input is open: the clock buffers, frozen where a seek requested while loading sent it or at 0, until the player lands it. */
    public void start() {
        this.lock.lock();
        try {
            final Status prev = this.status;
            // A RUN THAT WAS STOPPED OR FAILED WHILE OPENING MUST NOT COME BACK TO LIFE
            if (prev != Status.LOADING && prev != Status.WAITING) return;
            final double now = wallclock();
            this.ptsDrift = this.pts - now;
            this.lastUpdated = now;
            this.unsettled = true;
            this.status = Status.BUFFERING;
            this.events.addLast(new Status[] { prev, Status.BUFFERING });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
    }

    // ==========================================================================
    // PUBLIC API: SEEK AND PAUSE
    // ==========================================================================

    /**
     * Requests a seek, freezing the clock at the target in buffering; false without a pipeline to serve it (waiting,
     * ended, stopped, error). An {@code ahead} one is a jump the player may serve from what it already read.
     */
    public boolean requestSeek(final long targetMs, final boolean precise, final boolean ahead) {
        this.lock.lock();
        try {
            // WHILE LOADING THE REQUEST WAITS FOR start() AND THE STATUS STAYS LOADING
            final Status current = this.status;
            if (current == Status.WAITING || current == Status.ENDED || current == Status.STOPPED || current == Status.ERROR)
                return false;
            // A NEW EPOCH: NO FRAME OF THE OLD POSITION MOVES OR LANDS THE CLOCK FROM HERE ON
            final int epoch = this.epoch + 1;
            this.epoch = epoch;
            this.pendingSeek = new Seek(targetMs, precise, ahead, epoch);
            this.pts = targetMs / 1_000.0; // FREEZE CLOCK AT TARGET
            this.unsettled = true;
            if (current != Status.LOADING && current != Status.BUFFERING) {
                this.status = Status.BUFFERING;
                this.events.addLast(new Status[] { current, Status.BUFFERING });
            }
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
        return true;
    }

    /** Takes the pending seek, or returns null when none is pending; the player serves it with its decoder. */
    public Seek consumeSeek() {
        this.lock.lock();
        try {
            final Seek req = this.pendingSeek;
            this.pendingSeek = null;
            return req;
        } finally {
            this.lock.unlock();
        }
    }

    /** Returns how many seeks were requested in this run, lock-free: the epoch a frame must carry to move the clock. */
    public int epoch() { return this.epoch; }

    /** Pauses or resumes, latching the intent that resolves buffering; true when the state, or the intent while loading or buffering, changed. */
    public boolean paused(final boolean paused) {
        final boolean changed;
        this.lock.lock();
        try {
            // NO RUN YET: THERE IS NOTHING TO LATCH INTO (reset() WOULD WIPE IT ANYWAY)
            if (this.status == Status.WAITING) return false;
            final boolean was = this.pauseIntent;
            this.pauseIntent = paused;
            // WHILE LOADING ONLY THE INTENT CHANGES AND THE LANDING APPLIES IT
            if (this.status == Status.LOADING) return was != paused;
            if (paused) {
                // PAUSE IS ALWAYS ALLOWED (BUFFERING→PAUSED, PLAYING→PAUSED)
                changed = this.transition(Status.PAUSED);
            } else if (this.status == Status.BUFFERING) {
                // NOTHING LANDED YET: THE LANDING RESOLVES INTO PLAYING NOW THAT THE INTENT IS GONE
                changed = was;
            } else {
                // A CLOCK PAUSED BEFORE ITS START, SEEK OR REBUFFER LANDED GOES BACK TO BUFFERING, NOT TO PLAYING WITHOUT FRAMES
                changed = this.status == Status.PAUSED && this.unsettled
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

    /** Returns whether playback is held paused by request. */
    public boolean holding() { return this.pauseIntent && this.status == Status.PAUSED; }

    /** Returns whether a start, a seek or a rebuffer has not landed yet. */
    public boolean unsettled() { return this.unsettled; }

    /** Returns how long the clock has been holding in milliseconds, or 0 when it is not holding. */
    public long heldMs() { return this.holding() ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.heldSinceNs) : 0L; }

    /** Resumes a holding clock into buffering through a precise seek to its frozen position; false when it was not holding. */
    public boolean reread() {
        this.lock.lock();
        try {
            // ONE STEP UNDER THE LOCK: THE PARKED READERS WAKE TO THE SEEK, NEVER TO A READ OF THE CUT CONNECTION
            if (!this.holding()) return false;
            this.pauseIntent = false;
            this.requestSeek(this.timeMs(), true, false);
        } finally {
            this.lock.unlock();
        }
        this.drain(); // THE NESTED SEEK QUEUED ITS EVENT WHILE THE LOCK WAS HELD
        return true;
    }

    // ==========================================================================
    // PUBLIC API: THREAD COORDINATION
    // ==========================================================================

    /** Blocks until the status changes or a new seek arrives, at most {@code timeoutMs} (0 waits for it). */
    public void awaitChange(final long timeoutMs) throws InterruptedException {
        this.lock.lock();
        try {
            final Status initial = this.status;
            final Seek initialSeek = this.pendingSeek;
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
            this.epoch = 0;
            this.pendingSeek = null;
            this.unsettled = false;
            this.pauseIntent = false;
            if (prev != Status.WAITING) this.events.addLast(new Status[] { prev, Status.WAITING });
            this.condition.signalAll();
        } finally {
            this.lock.unlock();
        }
        this.drain();
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
    /** Returns the frame duration in seconds. */
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

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // A SEEK TO targetMs REQUESTED AS epoch: FRAME-EXACT WHEN precise, AND SERVED FROM WHAT THE PLAYER ALREADY READ WHEN ahead AND IT REACHES
    record Seek(long targetMs, boolean precise, boolean ahead, int epoch) {}
}
