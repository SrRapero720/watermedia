package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.PlayerState;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ServerMediaPlayer}, the headless wall clock players follow: its controls and revision, the held, finite
 * and live timelines, the ticker's end and loop wrap, snapshots, the status listener and release.
 */
@DisplayName("ServerMediaPlayer")
public class ServerMediaPlayerTest {

    private static final long TIMEOUT_MS = 2_000L; // BOUND OF EVERY WAIT: FORTY CYCLES OF THE SHARED 50 ms TICKER

    private final List<ServerMediaPlayer> clocks = new ArrayList<>();

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @AfterEach
    void releaseClocks() {
        // A FAILED ASSERTION MUST NOT LEAVE A CLOCK ON THE SHARED TICKER FOR THE REST OF THE JVM
        for (final ServerMediaPlayer clock: this.clocks) clock.release();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private ServerMediaPlayer clock() {
        final ServerMediaPlayer clock = new ServerMediaPlayer();
        this.clocks.add(clock);
        return clock;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Controls")
    class Controls {

        @Test
        @DisplayName("start() plays and time advances with the wall clock")
        void startAdvancesTime() throws InterruptedException {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(100_000L);
            // THE SEGMENT STARTS INSIDE [before, started] AND IS READ INSIDE [reading, after], SO THE BOUNDS HOLD WHATEVER THE
            // SCHEDULER DOES
            final long before = System.nanoTime();
            assertTrue(clock.start());
            final long started = System.nanoTime();
            assertEquals(Status.PLAYING, clock.status());

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = clock.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum, "time() must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("pause() freezes time and resume() continues it")
        void pauseFreezesTime() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(100_000L);
            assertTrue(clock.start());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() > 0L, TIMEOUT_MS), "the clock must start counting");
            assertTrue(clock.pause());
            final long frozen = clock.time();
            assertFalse(PlayerWait.awaitCondition(() -> clock.time() != frozen, 100L), "time must not move while paused");
            assertTrue(clock.resume());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() > frozen, TIMEOUT_MS), "time must advance again after resume()");
        }

        @Test
        @DisplayName("speed(2) runs the clock at twice the wall clock rate")
        void speedScalesTheClock() throws InterruptedException {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(100_000L);
            assertTrue(clock.startPaused());
            assertTrue(clock.speed(2.0f));
            final long before = System.nanoTime();
            assertTrue(clock.resume());
            final long started = System.nanoTime();

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = clock.time();
            final long after = System.nanoTime();
            final long minimum = 2 * TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = 2 * TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum, "time() at 2x must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("speed() closes the elapsed segment at the outgoing rate instead of rescaling it")
        void speedChangeKeepsTheElapsedTime() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(100_000L);
            assertTrue(clock.start());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() >= 100L, TIMEOUT_MS), "the clock must count 100 ms first");
            final long opened = System.nanoTime();
            final long before = clock.time();
            assertTrue(clock.speed(4f));
            final long after = clock.time();
            final long closed = System.nanoTime();
            // ONLY THE BRACKETED WINDOW MAY PASS BETWEEN THE READS, AT THE NEW RATE AT MOST, PLUS ONE MILLISECOND OF TRUNCATION
            assertTrue(after >= before && after - before <= 4L * TimeUnit.NANOSECONDS.toMillis(closed - opened) + 1L,
                    "a speed change must not jump the clock: " + before + " -> " + after);
        }

        @Test
        @DisplayName("revision() moves on every clock mutation, never on a refused control, volume or mute")
        void revisionMovesOnMutations() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            // THE COUNTER STARTS FROM A CLOCK-SEEDED VALUE AND MAY WRAP, SO ONLY A CHANGE IS ASSERTED, NEVER AN ORDER
            final int initial = clock.revision();
            clock.syncDuration(10_000L);
            final int synced = clock.revision();
            assertNotEquals(initial, synced, "syncDuration() must bump the revision");

            assertFalse(clock.pause(), "a clock that never started cannot pause");
            assertFalse(clock.stop(), "a clock that never started cannot stop");
            assertEquals(synced, clock.revision(), "refused controls must not bump the revision");

            assertTrue(clock.start());
            final int started = clock.revision();
            assertNotEquals(synced, started, "start() must bump the revision");
            assertTrue(clock.pause());
            final int paused = clock.revision();
            assertNotEquals(started, paused, "pause() must bump the revision");
            assertTrue(clock.seek(5_000L));
            final int seeked = clock.revision();
            assertNotEquals(paused, seeked, "seek() must bump the revision");

            // VOLUME AND MUTE ARE LOCAL TO EVERY PLAYER, SO THERE IS NOTHING TO SEND
            clock.volume(40);
            clock.mute(true);
            assertEquals(seeked, clock.revision(), "volume and mute must not bump the revision");
        }

        @Test
        @DisplayName("seek() lands inside [0, duration] and a skip near the end of the long range saturates")
        void seekClampsAndSaturates() {
            final ServerMediaPlayer finite = ServerMediaPlayerTest.this.clock();
            finite.syncDuration(10_000L);
            assertTrue(finite.startPaused());
            assertTrue(finite.seek(5_000L));
            assertEquals(5_000L, finite.time(), "a paused seek sets the position");
            assertTrue(finite.seek(15_000L));
            assertEquals(10_000L, finite.time(), "a seek past the end lands on the duration");
            assertTrue(finite.seek(-5L));
            assertEquals(0L, finite.time(), "a seek before the start lands on zero");

            // THE LONGEST TIMELINE: THE RUNNING SEGMENT AND A SKIP PAST Long.MAX_VALUE MUST SATURATE INSTEAD OF WRAPPING NEGATIVE
            final ServerMediaPlayer longest = ServerMediaPlayerTest.this.clock();
            longest.syncDuration(Long.MAX_VALUE);
            assertTrue(longest.start());
            assertTrue(longest.seek(Long.MAX_VALUE - 10L));
            final long near = longest.time();
            assertTrue(near >= Long.MAX_VALUE - 10L, "the running segment must not wrap negative, was " + near);
            assertTrue(longest.skipTime(100L));
            assertEquals(Long.MAX_VALUE, longest.time(), "the skip saturates at the end of the range");
            assertNotNull(longest.snapshot(), "a saturated clock still snapshots");
        }

        @Test
        @DisplayName("seek() on an ended or stopped clock lands PAUSED at the target")
        void seekAfterEndOrStopPauses() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(150L);
            assertTrue(clock.start());
            assertTrue(PlayerWait.awaitStatus(clock, TIMEOUT_MS, Status.ENDED), "a 150 ms clock must end");

            assertTrue(clock.seek(50L));
            assertEquals(Status.PAUSED, clock.status());
            assertEquals(50L, clock.time());

            // STOPPED SCRUBS THE SAME WAY
            assertTrue(clock.stop());
            assertTrue(clock.seek(80L));
            assertEquals(Status.PAUSED, clock.status());
            assertEquals(80L, clock.time());
        }

        @Test
        @DisplayName("resume() after a seek on an ended clock ticks it to its next end")
        void resumeAfterEndTicksAgain() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(150L);
            assertTrue(clock.start());
            assertTrue(PlayerWait.awaitStatus(clock, TIMEOUT_MS, Status.ENDED), "a 150 ms clock must end");

            // THE ENDED CLOCK LEFT THE TICKER: A RESUME MUST REGISTER IT AGAIN OR IT NEVER ENDS AGAIN
            assertTrue(clock.seek(50L));
            assertTrue(clock.resume());
            assertTrue(PlayerWait.awaitStatus(clock, TIMEOUT_MS, Status.ENDED), "a resumed clock must reach ENDED again");
            assertEquals(150L, clock.time());
        }
    }

    @Nested
    @DisplayName("Timeline")
    class Timeline {

        @Test
        @DisplayName("syncDuration() keeps the first report and ignores divergent or invalid ones")
        void durationFirstWins() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(10_000L);
            clock.syncDuration(99_000L);
            assertEquals(10_000L, clock.duration());
            clock.syncDuration(0L);
            clock.syncDuration(-5L);
            assertEquals(10_000L, clock.duration(), "zero and negative reports never touch the synced value");
        }

        @Test
        @DisplayName("seek() is refused until a client reports the duration and on a live session")
        void seekNeedsATimeline() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            assertTrue(clock.start());
            assertFalse(clock.live(), "an unknown duration is not a live session");
            assertFalse(clock.canSeek());
            assertFalse(clock.seek(5_000L), "no timeline to move before the duration is known");
            assertFalse(clock.skipTime(1_000L));
            assertEquals(0L, clock.time());

            clock.syncDuration(60_000L);
            assertTrue(clock.canSeek());
            assertTrue(clock.canSpeed());
            assertTrue(clock.seek(5_000L));

            clock.live(true);
            assertTrue(clock.live());
            assertFalse(clock.canSeek());
            assertFalse(clock.canSpeed(), "a live source locks speed changes");
            final int revision = clock.revision();
            assertFalse(clock.seek(10_000L), "a live session has no position to seek");
            assertFalse(clock.seekQuick(10_000L));
            assertFalse(clock.forward());
            assertEquals(revision, clock.revision(), "a refused seek is not broadcast");

            // THE FLAG IS EXPLICIT BOTH WAYS ON THE AUTHORITY
            clock.live(false);
            assertFalse(clock.live());
            assertTrue(clock.canSeek());
            assertTrue(clock.canSpeed());
        }

        @Test
        @DisplayName("A clock without a timeline holds at zero and starts counting when the duration latches")
        void clockHoldsUntilTheDuration() throws InterruptedException {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            assertTrue(clock.start());
            assertFalse(PlayerWait.awaitCondition(() -> clock.time() != 0L, 200L), "a clock without a timeline must hold still");
            assertEquals(Status.PLAYING, clock.status());

            final long before = System.nanoTime();
            clock.syncDuration(100_000L);
            final long started = System.nanoTime();
            Thread.sleep(100L);
            assertEquals(Status.PLAYING, clock.status());
            final long reading = System.nanoTime();
            final long t = clock.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time since the duration latch must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("A live clock runs without a duration")
        void liveClockRuns() throws InterruptedException {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            assertTrue(clock.start());
            final long before = System.nanoTime();
            clock.live(true);
            final long started = System.nanoTime();
            Thread.sleep(120L);
            final long reading = System.nanoTime();
            final long t = clock.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "a live session clock must advance within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("live(false) keeps the time a live clock counted and holds it there")
        void liveEndingKeepsTheElapsedTime() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            assertTrue(clock.start());
            clock.live(true);
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() >= 100L, TIMEOUT_MS), "the live clock must count 100 ms first");
            final long counted = clock.time();
            clock.live(false);
            final long held = clock.time();
            assertTrue(held >= counted, "the live run must survive the flag, was " + held + " after " + counted);
            // WITHOUT A DURATION THE CLOCK HOLDS AGAIN, AT THAT POSITION
            assertFalse(PlayerWait.awaitCondition(() -> clock.time() != held, 200L), "a held clock stays at " + held);
            assertEquals(Status.PLAYING, clock.status());
        }

        @Test
        @DisplayName("A live session never ends nor wraps, even with a latched duration")
        void liveIgnoresLatchedDuration() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            // MIXED-VARIANT SESSION: A VOD CLIENT LATCHED A DURATION, A STREAM CLIENT LATCHED LIVE
            clock.syncDuration(150L);
            clock.live(true);
            assertTrue(clock.start());

            assertFalse(PlayerWait.awaitStatus(clock, 400L, Status.ENDED), "a live clock must never END at the phantom duration");
            assertEquals(Status.PLAYING, clock.status());
            assertFalse(clock.canSeek(), "a live session is not seekable, matching FFMediaPlayer");
            final long t = clock.time();
            assertTrue(t > 150L, "a live clock must run past the phantom duration, was " + t);
        }

        @Test
        @DisplayName("A clock that turns live near the end of the long range saturates instead of wrapping negative")
        void liveClockSaturates() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            // A LIVE TIMELINE IGNORES THE DURATION, SO NOTHING CLAMPS THE RUNNING SEGMENT BUT THE SATURATION; PAUSED
            // UNTIL IT IS LIVE, SO THE TICKER CANNOT END THE CLOCK AT THE DURATION FIRST
            clock.syncDuration(Long.MAX_VALUE);
            assertTrue(clock.startPaused());
            assertTrue(clock.seek(Long.MAX_VALUE - 10L));
            clock.live(true);
            assertTrue(clock.resume());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() == Long.MAX_VALUE, TIMEOUT_MS),
                    "the live segment outgrows the 10 ms left and saturates");
            assertEquals(Status.PLAYING, clock.status(), "a live clock never ends");
            final PlayerState snapshot = clock.snapshot();
            assertEquals(Long.MAX_VALUE, snapshot.time());
            assertEquals(snapshot, PlayerState.read(snapshot.toBytes()), "the saturated snapshot still crosses the wire");
        }
    }

    @Nested
    @DisplayName("Ticker")
    class Ticker {

        @Test
        @DisplayName("A clock without repeat ends at the duration and tells the listener")
        void endsAtTheDuration() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            final StatusCapture capture = new StatusCapture();
            clock.onStatus(capture);
            clock.syncDuration(150L);
            clock.repeat(false);
            assertTrue(clock.start());

            // THE TICKER SETS ENDED UNDER THE MONITOR AND DELIVERS THE EVENT AFTER IT: THE LISTENER IS THE SIGNAL TO WAIT FOR
            assertTrue(PlayerWait.awaitCondition(() -> capture.to == Status.ENDED, TIMEOUT_MS), "the listener must hear ENDED");
            assertEquals(Status.PLAYING, capture.from);
            assertEquals(Status.ENDED, clock.status());
            assertEquals(150L, clock.time(), "the terminal time is the duration");
        }

        @Test
        @DisplayName("Repeat wraps without ending, moves the revision and never overruns the duration")
        void repeatWrapsWithoutEnding() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            final StatusCapture capture = new StatusCapture();
            clock.onStatus(capture);
            clock.syncDuration(120L);
            clock.repeat(true);
            assertTrue(clock.start());
            final int revision = clock.revision();

            // A WRAP REBASES THE TIMELINE AND BUMPS THE REVISION SO THE HOST RESENDS THE SNAPSHOT
            assertTrue(PlayerWait.awaitCondition(() -> clock.revision() != revision, TIMEOUT_MS), "a loop wrap must bump the revision");
            // 400 ms SPANS SEVERAL 120 ms LOOPS
            assertFalse(PlayerWait.awaitStatus(clock, 400L, Status.ENDED), "a looping clock never ends");
            assertEquals(Status.PLAYING, clock.status());
            assertFalse(clock.ended());
            final long t = clock.time();
            assertTrue(t < 120L, "a looping time() must wrap under the duration, was " + t);
            assertEquals(Status.PLAYING, capture.to, "no terminal transition is published while looping, only the start");
        }
    }

    @Nested
    @DisplayName("Snapshots and listeners")
    class Snapshots {

        @Test
        @DisplayName("snapshot() captures the whole session state, paused and running")
        void snapshotCapturesTheSession() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(60_000L);
            clock.repeat(true);
            assertTrue(clock.startPaused());
            assertTrue(clock.speed(2.0f));
            assertTrue(clock.seek(30_000L));

            final PlayerState snapshot = clock.snapshot();
            assertEquals(clock.revision(), snapshot.revision());
            assertEquals(Status.PAUSED, snapshot.status());
            assertEquals(30_000L, snapshot.time());
            assertEquals(60_000L, snapshot.duration());
            assertEquals(2.0f, snapshot.speed());
            assertTrue(snapshot.repeat());
            assertFalse(snapshot.live());

            // THE LIVE FLAG IS SESSION STATE TOO
            clock.live(true);
            assertTrue(clock.snapshot().live());
            clock.live(false);

            // WITHOUT LOOP WRAPS, THE RUNNING SNAPSHOT MUST FALL BETWEEN THE SURROUNDING READS
            clock.repeat(false);
            assertTrue(clock.resume());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() > snapshot.time(), TIMEOUT_MS), "the resumed clock must advance");
            final long before = clock.time();
            final PlayerState playing = clock.snapshot();
            final long after = clock.time();
            assertEquals(Status.PLAYING, playing.status());
            assertFalse(playing.repeat());
            assertTrue(playing.time() > snapshot.time(), "a running snapshot must advance after resume()");
            assertTrue(playing.time() >= before && playing.time() <= after,
                    "the snapshot must be within [" + before + ", " + after + "], was " + playing.time());
            assertEquals(30_000L, snapshot.time(), "an earlier snapshot must remain immutable");
        }

        @Test
        @DisplayName("Every transition the controls cause reaches the listener, in order")
        void listenerHearsEveryTransition() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            final List<Status> seen = new CopyOnWriteArrayList<>();
            clock.onStatus((p, prev, next) -> seen.add(next));
            clock.syncDuration(60_000L);
            clock.start();
            clock.pause();
            clock.resume();
            clock.stop();
            assertEquals(List.of(Status.PLAYING, Status.PAUSED, Status.PLAYING, Status.STOPPED), seen);
        }

        @Test
        @DisplayName("release() lands STOPPED with a new revision, so its last snapshot can still be sent")
        void releaseLandsStopped() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            final StatusCapture capture = new StatusCapture();
            clock.onStatus(capture);
            clock.syncDuration(60_000L);
            assertTrue(clock.start());
            assertTrue(PlayerWait.awaitCondition(() -> clock.time() > 0L, TIMEOUT_MS), "the clock must run before the release");
            final int revision = clock.revision();

            clock.release();
            assertEquals(Status.STOPPED, clock.status());
            assertEquals(revision + 1, clock.revision(), "the release is one more mutation to send");
            assertEquals(Status.PLAYING, capture.from);
            assertEquals(Status.STOPPED, capture.to, "the listener hears the release");
            final PlayerState last = clock.snapshot();
            assertEquals(Status.STOPPED, last.status());
            assertEquals(clock.revision(), last.revision());
            assertTrue(last.time() > 0L && last.time() == clock.time(), "the position freezes where the release caught it");
            assertFalse(clock.canPlay());
            assertFalse(clock.start(), "a released clock never runs again");
            clock.release();
            assertEquals(revision + 1, clock.revision(), "a second release is a no-op");
        }

        @Test
        @DisplayName("A released clock refuses every control and keeps its last snapshot")
        void releasedClockIsInert() {
            final ServerMediaPlayer clock = ServerMediaPlayerTest.this.clock();
            clock.syncDuration(60_000L);
            assertTrue(clock.startPaused());
            assertTrue(clock.seek(10_000L));
            clock.release();
            final PlayerState last = clock.snapshot();
            assertFalse(clock.seek(20_000L), "a released clock cannot be scrubbed");
            assertFalse(clock.pause(false), "a released clock cannot resume");
            assertFalse(clock.togglePlay());
            assertFalse(clock.speed(2f));
            assertFalse(clock.repeat(true));
            clock.syncTime(30_000L);
            clock.live(true);
            assertEquals(last, clock.snapshot(), "nothing moves a released clock");
        }
    }

    // CAPTURES THE LAST STATUS TRANSITION FROM THE SHARED TICKER THREAD; volatile FOR VISIBILITY
    private static final class StatusCapture implements MediaPlayer.StatusListener {
        private volatile Status from;
        private volatile Status to;
        @Override public void onStatus(final MediaPlayer player, final Status prev, final Status next) { this.from = prev; this.to = next; }
    }
}
