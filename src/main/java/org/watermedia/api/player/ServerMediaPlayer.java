package org.watermedia.api.player;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.util.MathUtil;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * A headless media player that acts as a wall clock for time synchronization.
 * <p>
 * This player produces no audio or video: it computes playback time from the system clock, so a server
 * can hold one authoritative position per media session and its clients align to it. Every successful
 * mutation bumps {@link #revision()}; a host polls it, sends {@link #snapshot()} through its own transport
 * whenever it moved, and each receiving player feeds the snapshot to {@link MediaPlayer#sync(PlayerState)}. The
 * clock knows nothing about transports, followers or permissions: who may drive it, how its snapshots
 * travel and where its position is persisted are the host's business.
 * <p>
 * One instance represents one media session: {@link #syncDuration(long)} is first-wins for the whole
 * lifetime, so switching media warrants a fresh instance. The clock holds at zero until a duration is
 * known or the session is marked {@link #live(boolean) live}, and {@link #release()} freezes the position
 * and leaves it {@link Status#STOPPED} with a new revision, so that last snapshot can still be persisted and sent.
 */
public final class ServerMediaPlayer extends MediaPlayer {
    private static final Marker IT = MarkerManager.getMarker(ServerMediaPlayer.class.getSimpleName());
    // DURATION REPORTS DIVERGING BEYOND THIS GAP GET LOGGED: LIKELY A BROKEN SOURCE VARIANT
    private static final long DURATION_DIVERGENCE_MS = 500;

    // TIME TRACKING: MUTATORS ARE synchronized (CALLER THREAD vs SHARED TICKER), READS STAY VOLATILE
    // LOCK-FREE. time() MAY PAIR accumulatedMs/segmentStartNanos ACROSS A CONCURRENT REBASE: THE GLITCH
    // IS SUB-TICK (<50ms), TRANSIENT AND ACCEPTED.
    private volatile long duration;
    private volatile boolean live;
    private volatile long accumulatedMs;      // TIME ACCUMULATED BEFORE THE CURRENT PLAY SEGMENT
    private volatile long segmentStartNanos;  // System.nanoTime() WHEN THE CURRENT SEGMENT BEGAN
    private volatile Status status = Status.WAITING;
    private volatile boolean released;
    // MUTATION COUNTER (AtomicInteger: ++ ON volatile ISN'T ATOMIC AND repeat() BUMPS UNLOCKED), SEEDED FROM
    // THE CLOCK SO A REPLACEMENT CLOCK NEVER REPEATS THE VALUES A HOST REMEMBERS FROM ITS PREDECESSOR
    private final AtomicInteger revision = new AtomicInteger((int) System.currentTimeMillis());
    private boolean divergentWarned; // A DIVERGENT DURATION IS LOGGED ONCE PER PLAYER, GUARDED BY THIS

    ServerMediaPlayer() {
        super();
        this.arm();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /**
     * Synchronizes the total media duration a client reported.
     * The first non-zero report wins for the player lifetime; later divergent reports are
     * ignored (the first one is logged), keeping the loop modulo stable across mixed-quality clients.
     * @param durationMs total duration in milliseconds (values {@code <= 0} are ignored)
     */
    public synchronized void syncDuration(final long durationMs) {
        if (durationMs <= 0 || this.released) return;
        if (this.duration > 0) {
            if (!this.divergentWarned && Math.abs(durationMs - this.duration) > DURATION_DIVERGENCE_MS) {
                this.divergentWarned = true;
                LOGGER.warn(IT, "Ignored divergent duration report: {}ms vs synced {}ms", durationMs, this.duration);
            }
            return;
        }
        this.duration = durationMs;
        // THE TIMELINE JUST BECAME KNOWN: A RUNNING SESSION STARTS COUNTING FROM THIS INSTANT,
        // SINCE THE CLOCK WAS HELD WHILE IT HAD NOTHING TO MEASURE AGAINST (SEE computeTime)
        if (this.status == Status.PLAYING && !this.live) this.segmentStartNanos = System.nanoTime();
        this.revision.incrementAndGet();
    }

    /** Sets the playback position in milliseconds (clamped to 0), say one restored from the host's storage after the start that would reset it; a playing clock keeps advancing from it. */
    public synchronized void syncTime(final long timeMs) {
        if (this.released) return;
        this.accumulatedMs = Math.max(0, timeMs);
        if (this.status == Status.PLAYING) this.segmentStartNanos = System.nanoTime();
        this.revision.incrementAndGet();
    }

    /** Marks the session live or not, as a client reported it: a live source locks speed changes and tells followers to never correct time. */
    public synchronized void live(final boolean live) {
        if (this.live == live || this.released) return;
        if (this.duration <= 0 && this.status == Status.PLAYING) {
            // SAME TIMELINE-BECAME-KNOWN REBASE AS syncDuration: A HELD CLOCK STARTS COUNTING NOW, AND A CLOCK THAT
            // RAN LIVE KEEPS WHAT IT COUNTED BEFORE IT HOLDS AGAIN
            if (!live) this.accumulatedMs = this.computeTime(this.speed());
            this.segmentStartNanos = System.nanoTime();
        }
        this.live = live;
        this.revision.incrementAndGet();
    }

    /** Returns the mutation counter, seeded from the clock and bumped by every state change, the loop wrap and {@link #release()}, so a host sends {@link #snapshot()} only when it moved. */
    public int revision() {
        return this.revision.get();
    }

    /** Captures the current state as an immutable snapshot, consistent under concurrent mutations. */
    public synchronized PlayerState snapshot() {
        return new PlayerState(this.revision.get(), this.status, this.time(), this.duration, this.speed(), this.repeat(), this.live);
    }

    // EVERY CONTROL QUEUES ITS TRANSITION UNDER THE LOCK, IN WRITE ORDER, AND THE LISTENER HEARS IT AFTER THE LOCK, SO NO
    // CALLBACK RUNS UNDER IT
    @Override
    public boolean pause(final boolean paused) {
        synchronized (this) {
            final Status prev = this.status;
            if (paused) {
                if (this.status != Status.PLAYING) return false;
                this.accumulatedMs = this.computeTime(this.speed());
                this.status = Status.PAUSED;
            } else {
                if (this.status != Status.PAUSED) return false;
                this.segmentStartNanos = System.nanoTime();
                this.status = Status.PLAYING;
                // THE CLOCK DEREGISTERS ON ENDED/STOP; RESUMING AFTER A SEEK MUST TICK AGAIN
                // OR IT NEVER WRAPS NOR ENDS FOR THE REST OF ITS LIFE
                this.ticking(true);
            }
            this.revision.incrementAndGet();
            this.queueStatus(prev, this.status);
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean stop() {
        synchronized (this) {
            if (this.status == Status.WAITING || this.status == Status.STOPPED) return false;
            final Status prev = this.status;
            this.accumulatedMs = 0;
            this.status = Status.STOPPED;
            this.revision.incrementAndGet();
            this.ticking(false);
            this.queueStatus(prev, Status.STOPPED);
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean togglePlay() {
        return switch (this.status) {
            case PLAYING -> this.pause(true);
            case PAUSED  -> this.pause(false);
            case STOPPED, ENDED -> this.start();
            default -> false;
        };
    }

    @Override
    public boolean seek(final long time) {
        synchronized (this) {
            if (this.released || this.status == Status.WAITING || !this.canSeek()) return false;
            final Status prev = this.status;
            // SCRUBBING A FINISHED/STOPPED CLOCK LANDS PAUSED AT THE POSITION INSTEAD OF STAYING DEAD
            if (this.status == Status.ENDED || this.status == Status.STOPPED) this.status = Status.PAUSED;
            this.syncTime(MathUtil.clamp(time, 0L, this.duration));
            this.queueStatus(prev, this.status);
        }
        this.deliverStatus();
        return true;
    }

    @Override
    public boolean seekQuick(final long time) {
        return this.seek(time);
    }

    @Override
    public long time() {
        // A NON-PLAYING CLOCK REPORTS ITS ACCUMULATED POSITION (0 UNTIL FIRST USE)
        if (this.status != Status.PLAYING) return this.accumulatedMs;
        final long t = this.computeTime(this.speed());
        final long d = this.duration;
        // CLAMP/MODULO AGAINST DURATION SO time() NEVER OVERRUNS BETWEEN 50ms TICKS; A LIVE
        // TIMELINE IS OPEN-ENDED AND RUNS FREE OF ANY MIXED-VARIANT DURATION LATCH
        return d > 0 && !this.live ? (this.repeat() ? t % d : Math.min(t, d)) : t;
    }

    @Override
    public boolean speed(final float speed) {
        final float old = this.speed();
        synchronized (this) {
            // THE ELAPSED SEGMENT RAN AT THE OUTGOING RATE: CLOSE IT BEFORE THE BASE PUBLISHES THE NEW ONE, SO NEITHER
            // THE TICKER NOR A SNAPSHOT RESCALES TIME THAT ALREADY PASSED (A REFUSED CHANGE JUST KEEPS THE OLD RATE)
            if (this.status == Status.PLAYING) {
                this.accumulatedMs = this.computeTime(old);
                this.segmentStartNanos = System.nanoTime();
            }
        }
        if (!super.speed(speed)) return false;
        if (old != speed) this.revision.incrementAndGet();
        return true;
    }

    @Override
    public boolean repeat(final boolean repeat) {
        final boolean old = this.repeat();
        if (!super.repeat(repeat)) return false;
        if (old != repeat) this.revision.incrementAndGet();
        return true;
    }

    @Override
    public float fps() {
        return 0;
    }

    @Override
    public long duration() {
        return this.duration;
    }

    @Override
    public Status status() {
        return this.status;
    }

    @Override
    public boolean live() {
        // ONLY A CLIENT REPORT OR THE HOST MARKS THE SESSION LIVE: AN UNKNOWN DURATION IS A SOURCE NOT OPEN YET
        return this.live;
    }

    @Override
    public boolean canSeek() {
        // NO TIMELINE TO MOVE UNTIL A CLIENT REPORTS THE DURATION, AND NONE ON A LIVE SESSION
        return this.duration > 0 && !this.live;
    }

    @Override
    public boolean canPlay() {
        return !this.released && this.status != Status.ERROR;
    }

    @Override
    public boolean previousFrame() {
        return false;
    }

    @Override
    public boolean nextFrame() {
        return false;
    }

    @Override
    public void release() {
        synchronized (this) {
            if (this.released) return;
            final Status prev = this.status;
            // THE POSITION FREEZES WHERE THE RELEASE CAUGHT IT, SO A HOST CAN STILL PERSIST IT FROM THE LAST SNAPSHOT
            if (this.status == Status.PLAYING) this.accumulatedMs = this.time();
            this.released = true;
            this.status = Status.STOPPED;
            this.revision.incrementAndGet();
            this.queueStatus(prev, Status.STOPPED);
        }
        this.disarm();
        this.deliverStatus();
        super.release();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    void tick() {
        // A CLOCK FED SNAPSHOTS MIRRORS THAT SESSION AND HAS NO MEDIA TO LEARN THE TIMELINE FROM, SO IT ADOPTS THE SESSION'S
        final PlayerState session = this.authority();
        if (session != null) {
            if (this.duration <= 0) this.syncDuration(session.duration());
            if (this.live != session.live()) this.live(session.live());
        }
        super.tick();
        this.update();
    }

    // THE MONITOR release() TAKES KEEPS A RELEASED CLOCK STOPPED
    @Override
    boolean launch(final boolean paused, final long seekMs) {
        synchronized (this) {
            if (this.released) return false;
            this.exception = null;
            final Status prev = this.status;
            this.accumulatedMs = Math.max(0L, seekMs);
            this.segmentStartNanos = System.nanoTime();
            this.status = paused ? Status.PAUSED : Status.PLAYING;
            this.revision.incrementAndGet();
            this.ticking(true);
            this.queueStatus(prev, this.status);
        }
        this.deliverStatus();
        return true;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // POSITION OF THE RUNNING SEGMENT AT THE GIVEN RATE. THE RATE IS EXPLICIT BECAUSE A SPEED CHANGE
    // MUST CLOSE THE SEGMENT AT THE OUTGOING VALUE BEFORE THE NEW ONE APPLIES
    private long computeTime(final float rate) {
        // NO TIMELINE, NO PROGRESSION: WITHOUT A DURATION (AND NOT LIVE) THE CLOCK CANNOT WRAP OR
        // END, SO RUNNING IT ACCUMULATES HOURS OF NONSENSE ON SESSIONS PLAYING UNWATCHED AND THE
        // EVENTUAL DURATION LATCH WOULD SNAP EVERY FOLLOWER TO A MEANINGLESS POSITION.
        if (this.duration <= 0 && !this.live) return this.accumulatedMs;
        final long elapsedNanos = System.nanoTime() - this.segmentStartNanos;
        final long run = (long) (TimeUnit.NANOSECONDS.toMillis(elapsedNanos) * rate);
        // SATURATE: A POSITION SEEKED NEAR Long.MAX_VALUE MUST NOT WRAP NEGATIVE AND BREAK THE SNAPSHOT
        return run > Long.MAX_VALUE - this.accumulatedMs ? Long.MAX_VALUE : this.accumulatedMs + run;
    }

    // 50ms CLOCK MAINTENANCE: LOOP WRAP AND THE ENDED TRANSITION, QUEUED UNDER THE MONITOR AND HEARD OUTSIDE IT
    private void update() {
        synchronized (this) {
            if (this.status != Status.PLAYING) return;

            // A LIVE TIMELINE HAS NO END: NEVER WRAP NOR END IT, EVEN WITH A DURATION LATCHED
            final long d = this.duration;
            if (d <= 0 || this.live) return;
            final long now = this.computeTime(this.speed());
            if (now < d) return;

            if (!this.repeat()) {
                this.accumulatedMs = d;
                this.status = Status.ENDED;
                this.revision.incrementAndGet();
                this.ticking(false);
                this.queueStatus(Status.PLAYING, Status.ENDED);
            } else {
                // CARRY THE OVERSHOOT ACROSS THE LOOP BOUNDARY: DISCARDING IT WOULD SLIP THE AUTHORITATIVE
                // CLOCK BY UP TO ONE TICK (50ms) PER LOOP, DESYNCING EVERY CLIENT THAT TRUSTS IT
                this.accumulatedMs = now % d;
                this.segmentStartNanos = System.nanoTime();
                // THE WRAP REBASES THE TIMELINE: BUMP SO THE HOST RESENDS WHERE CLIENTS DRIFT MOST
                this.revision.incrementAndGet();
            }
        }
        this.deliverStatus();
    }
}
