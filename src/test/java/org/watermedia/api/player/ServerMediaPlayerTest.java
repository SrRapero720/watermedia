package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link ServerMediaPlayer}, the headless wall clock whose snapshots players follow: a regression desyncs them all. */
@DisplayName("ServerMediaPlayer")
public class ServerMediaPlayerTest {

    // TICK IS 50ms; GIVE THE SHARED TICKER A FEW CYCLES OF SLACK BEFORE ASSERTING A TRANSITION
    private static final long ENDED_TIMEOUT_MS = 2_000L;

    private ServerMediaPlayer player;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @AfterEach
    void tearDown() {
        // A FAILED ASSERTION MUST NOT LEAVE THE CLOCK ON THE SHARED TICKER FOR THE REST OF THE JVM
        if (this.player != null) this.player.release();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private ServerMediaPlayer solo() {
        this.player = new ServerMediaPlayer();
        return this.player;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Controls")
    class Controls {

        // ======================================================================
        // INTERNAL API
        // ======================================================================

        @Test
        @DisplayName("start() plays and time advances with the wall clock")
        void testStartAdvancesTime() throws InterruptedException {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(100_000L);
            // BRACKET BOTH CALLS SO SCHEDULER DELAYS COUNT AS ELAPSED TIME
            final long before = System.nanoTime();
            assertTrue(player.start());
            final long started = System.nanoTime();
            assertEquals(Status.PLAYING, player.status());

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time() must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("pause() freezes time, resume() continues it")
        void testPauseFreezesTime() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(100_000L);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > 0L, ENDED_TIMEOUT_MS));
            assertTrue(player.pause());
            final long frozen = player.time();
            assertFalse(PlayerWait.awaitCondition(() -> player.time() != frozen, 100L), "time must not move while paused");
            assertTrue(player.resume());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > frozen, ENDED_TIMEOUT_MS), "time must resume advancing after resume()");
        }

        @Test
        @DisplayName("seek() sets the reported position")
        void testSeekSetsPosition() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(100_000L);
            player.startPaused();
            assertTrue(player.seek(30_000L));
            // PAUSED: time() REPORTS THE ACCUMULATED POSITION VERBATIM
            assertEquals(30_000L, player.time());
        }

        @Test
        @DisplayName("speed(2x) roughly doubles the clock rate")
        void testSpeedScalesClock() throws InterruptedException {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(100_000L);
            assertTrue(player.startPaused());
            assertTrue(player.speed(2.0f));
            final long before = System.nanoTime();
            assertTrue(player.resume());
            final long started = System.nanoTime();

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = 2 * TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = 2 * TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time() at 2x must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("speed() closes the elapsed segment at the outgoing rate instead of rescaling it")
        void speedChangeKeepsTheElapsedTime() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(100_000L);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() >= 100L, ENDED_TIMEOUT_MS));
            final long opened = System.nanoTime();
            final long before = player.time();
            assertTrue(player.speed(4f));
            final long after = player.time();
            final long closed = System.nanoTime();
            // ONLY THE BRACKETED WINDOW MAY PASS BETWEEN THE READS, AT THE NEW RATE AT MOST
            assertTrue(after >= before && after - before <= 4L * TimeUnit.NANOSECONDS.toMillis(closed - opened) + 1L,
                    "a speed change must not jump the clock: " + before + " -> " + after);
        }

        @Test
        @DisplayName("revision bumps on successful mutations and stays put on failed ones")
        void testRevisionBumps() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            final int r0 = player.revision();
            player.syncDuration(10_000L);
            assertTrue(player.revision() > r0, "syncDuration must bump the revision");

            int r = player.revision();
            assertFalse(player.pause());
            assertFalse(player.stop());
            assertEquals(r, player.revision(), "failed operations must not bump the revision");

            player.start();
            assertTrue(player.revision() > r);
            r = player.revision();
            assertTrue(player.pause());
            assertTrue(player.revision() > r);
            r = player.revision();
            assertTrue(player.seek(5_000L));
            assertTrue(player.revision() > r);

            // VOLUME AND MUTE ARE LOCAL TO EVERY PLAYER, SO THERE IS NOTHING TO SEND
            r = player.revision();
            player.volume(40);
            player.mute(true);
            assertEquals(r, player.revision(), "volume and mute must not bump the revision");
        }

        @Test
        @DisplayName("seek() clamps to the known duration and saturates instead of overflowing")
        void testSeekClampsAndSaturates() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(Long.MAX_VALUE);
            player.start();
            assertTrue(player.seek(-5L));
            assertEquals(0L, player.time());
            // THE RUNNING SEGMENT MUST NOT WRAP NEGATIVE NEAR THE END OF THE LONG RANGE
            assertTrue(player.seek(Long.MAX_VALUE - 10L));
            assertTrue(player.time() >= Long.MAX_VALUE - 10L);
            assertTrue(player.skipTime(100L));
            assertEquals(Long.MAX_VALUE, player.time());
            assertNotNull(player.snapshot(), "a saturated clock still snapshots");
        }

        @Test
        @DisplayName("seek() on a finished or stopped clock lands PAUSED at the position")
        void testSeekAfterEndedPauses() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(150L);
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED));

            assertTrue(player.seek(50L));
            assertEquals(Status.PAUSED, player.status());
            assertEquals(50L, player.time());

            // STOPPED SCRUBS THE SAME WAY
            assertTrue(player.stop());
            assertTrue(player.seek(80L));
            assertEquals(Status.PAUSED, player.status());
            assertEquals(80L, player.time());
        }

        @Test
        @DisplayName("resume after a post-ENDED seek keeps the clock ticking to its next end")
        void testResumeAfterEndedTicksAgain() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(150L);
            player.start();
            assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED));

            // SCRUB BACK AND RESUME: THE SOLO CLOCK MUST RE-REGISTER ON THE TICKER OR IT NEVER ENDS AGAIN
            assertTrue(player.seek(50L));
            assertTrue(player.resume());
            assertTrue(PlayerWait.awaitStatus(player, ENDED_TIMEOUT_MS, Status.ENDED),
                    "a resumed clock must reach ENDED again");
            assertEquals(150L, player.time());
        }
    }

    @Nested
    @DisplayName("Timeline")
    class Timeline {

        @Test
        @DisplayName("syncDuration is first-wins; divergent or invalid reports are ignored")
        void testDurationFirstWins() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(10_000L);
            player.syncDuration(99_000L);
            assertEquals(10_000L, player.duration());
            // ZERO/NEGATIVE REPORTS NEVER TOUCH THE SYNCED VALUE
            player.syncDuration(0L);
            player.syncDuration(-5L);
            assertEquals(10_000L, player.duration());
        }

        @Test
        @DisplayName("seek() is refused until a client reports the duration and on a live session")
        void testSeekNeedsATimeline() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            assertTrue(player.start());
            assertFalse(player.live(), "an unknown duration is not a live session");
            assertFalse(player.canSeek());
            assertFalse(player.seek(5_000L), "no timeline to move before the duration is known");
            assertFalse(player.skipTime(1_000L));
            assertEquals(0L, player.time());

            player.syncDuration(60_000L);
            assertTrue(player.canSeek());
            assertTrue(player.canSpeed());
            assertTrue(player.seek(5_000L));

            player.live(true);
            assertTrue(player.live());
            assertFalse(player.canSeek());
            assertFalse(player.canSpeed(), "a live source locks speed changes");
            final int revision = player.revision();
            assertFalse(player.seek(10_000L), "a live session has no position to seek");
            assertFalse(player.seekQuick(10_000L));
            assertFalse(player.forward());
            assertEquals(revision, player.revision(), "a refused seek is not broadcast");

            // THE FLAG IS EXPLICIT BOTH WAYS ON THE AUTHORITY
            player.live(false);
            assertFalse(player.live());
            assertTrue(player.canSeek());
            assertTrue(player.canSpeed());
        }

        @Test
        @DisplayName("a clock without a timeline holds at zero and starts counting when the duration latches")
        void testHeldClockWithoutTimeline() throws InterruptedException {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            assertTrue(player.start());

            // AN UNKNOWN NON-LIVE TIMELINE MUST STAY FROZEN UNTIL ITS DURATION ARRIVES
            assertFalse(PlayerWait.awaitCondition(() -> player.time() != 0L, 200L), "a clock without a timeline must hold still");
            assertEquals(Status.PLAYING, player.status());

            final long before = System.nanoTime();
            player.syncDuration(100_000L);
            final long started = System.nanoTime();
            Thread.sleep(100L);
            assertEquals(Status.PLAYING, player.status());
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "time since the duration latch must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("a live clock runs even without a duration")
        void testLiveClockRuns() throws InterruptedException {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            assertTrue(player.start());
            final long before = System.nanoTime();
            player.live(true); // A LIVE TIMELINE IS OPEN-ENDED BUT REAL: SESSION TIME COUNTS UP
            final long started = System.nanoTime();
            Thread.sleep(120L);
            final long reading = System.nanoTime();
            final long t = player.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum,
                    "a live session clock must advance within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("live(false) keeps the time a live clock counted and holds it there")
        void liveEndingKeepsTheElapsedTime() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            assertTrue(player.start());
            player.live(true);
            assertTrue(PlayerWait.awaitCondition(() -> player.time() >= 100L, ENDED_TIMEOUT_MS));
            final long counted = player.time();
            player.live(false);
            final long held = player.time();
            assertTrue(held >= counted, "the live run must survive the flag, was " + held + " after " + counted);
            // WITHOUT A DURATION THE CLOCK HOLDS AGAIN, AT THAT POSITION
            assertFalse(PlayerWait.awaitCondition(() -> player.time() != held, 200L), "a held clock stays at " + held);
            assertEquals(Status.PLAYING, player.status());
        }

        @Test
        @DisplayName("a live session never ends nor wraps, even with a latched duration")
        void testLiveIgnoresLatchedDuration() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            // MIXED-VARIANT SESSION: A VOD CLIENT LATCHED A DURATION, A STREAM CLIENT LATCHED LIVE
            player.syncDuration(150L);
            player.live(true);
            player.start();

            assertFalse(PlayerWait.awaitStatus(player, 400L, Status.ENDED), "a live clock must never END at the phantom duration");
            assertEquals(Status.PLAYING, player.status());
            assertFalse(player.canSeek(), "a live session is not seekable, matching FFMediaPlayer");
            assertTrue(player.time() > 150L, "a live clock must run past the phantom duration, was " + player.time());
        }

        @Test
        @DisplayName("A clock that turns live near the end of the long range saturates instead of wrapping negative")
        void testLiveClockSaturates() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            // A LIVE TIMELINE IGNORES THE DURATION, SO NOTHING CLAMPS THE RUNNING SEGMENT BUT THE SATURATION; PAUSED
            // UNTIL IT IS LIVE, SO THE TICKER CANNOT END THE CLOCK AT THE DURATION FIRST
            player.syncDuration(Long.MAX_VALUE);
            assertTrue(player.startPaused());
            assertTrue(player.seek(Long.MAX_VALUE - 10L));
            player.live(true);
            assertTrue(player.resume());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() == Long.MAX_VALUE, ENDED_TIMEOUT_MS),
                    "the live segment outgrows the 10ms left and saturates");
            assertEquals(Status.PLAYING, player.status(), "a live clock never ends");
            final MediaPlayer.PlayerState snapshot = player.snapshot();
            assertEquals(Long.MAX_VALUE, snapshot.time());
            assertEquals(snapshot, MediaPlayer.PlayerState.read(snapshot.toBytes()), "the saturated snapshot still crosses the wire");
        }
    }

    @Nested
    @DisplayName("Ticker")
    class Ticker {

        @Test
        @DisplayName("Non-repeat playback transitions to ENDED and notifies the status listener")
        void testEndedTransitionAndListener() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            final StatusCapture cap = new StatusCapture();
            player.onStatus(cap);
            player.syncDuration(150L);
            player.repeat(false);
            player.start();

            // THE TICKER SETS ENDED UNDER THE MONITOR AND DELIVERS THE EVENT AFTER IT: THE LISTENER IS THE SIGNAL TO WAIT FOR
            assertTrue(PlayerWait.awaitCondition(() -> cap.to == Status.ENDED, ENDED_TIMEOUT_MS));
            assertEquals(Status.PLAYING, cap.from);
            assertEquals(Status.ENDED, player.status());
            // TERMINAL TIME IS CLAMPED TO THE DURATION
            assertEquals(150L, player.time());
        }

        @Test
        @DisplayName("Repeat wraps without ENDING, bumps the revision and never overruns the duration")
        void testRepeatWrapsWithoutEnding() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            final StatusCapture cap = new StatusCapture();
            player.onStatus(cap);
            player.syncDuration(120L);
            player.repeat(true);
            player.start();
            final int revision = player.revision();

            // A WRAP REBASES THE TIMELINE AND BUMPS THE REVISION SO THE HOST RESENDS THE SNAPSHOT
            assertTrue(PlayerWait.awaitCondition(() -> player.revision() > revision, ENDED_TIMEOUT_MS), "loop wraps must bump the revision");
            // ACROSS SEVERAL LOOP PERIODS THE CLOCK STAYS PLAYING AND WRAPS WITHIN [0, duration)
            assertFalse(PlayerWait.awaitStatus(player, 400L, Status.ENDED));
            assertEquals(Status.PLAYING, player.status());
            assertFalse(player.ended());
            assertTrue(player.time() < 120L, "repeat time() must wrap under the duration, was " + player.time());
            assertEquals(Status.PLAYING, cap.to, "no terminal transition is published while looping, only the start");
        }
    }

    @Nested
    @DisplayName("Snapshots and listeners")
    class Snapshots {

        @Test
        @DisplayName("snapshot() captures the full authoritative state")
        void testSnapshot() {
            final ServerMediaPlayer source = ServerMediaPlayerTest.this.solo();
            source.syncDuration(60_000L);
            source.repeat(true);
            assertTrue(source.startPaused());
            assertTrue(source.speed(2.0f));
            assertTrue(source.seek(30_000L));

            final MediaPlayer.PlayerState snapshot = source.snapshot();
            assertEquals(source.revision(), snapshot.revision());
            assertEquals(Status.PAUSED, snapshot.status());
            assertEquals(30_000L, snapshot.time());
            assertEquals(60_000L, snapshot.duration());
            assertEquals(2.0f, snapshot.speed());
            assertTrue(snapshot.repeat());
            assertFalse(snapshot.live());

            // THE LIVE FLAG IS SESSION STATE TOO
            source.live(true);
            assertTrue(source.snapshot().live());
            source.live(false);

            // WITHOUT LOOP WRAPS, THE RUNNING SNAPSHOT MUST FALL BETWEEN THE SURROUNDING READS
            source.repeat(false);
            assertTrue(source.resume());
            assertTrue(PlayerWait.awaitCondition(() -> source.time() > snapshot.time(), ENDED_TIMEOUT_MS));
            final long before = source.time();
            final MediaPlayer.PlayerState playing = source.snapshot();
            final long after = source.time();
            assertEquals(Status.PLAYING, playing.status());
            assertFalse(playing.repeat());
            assertTrue(playing.time() > snapshot.time(), "a running snapshot must advance after resume()");
            assertTrue(playing.time() >= before && playing.time() <= after,
                    "the snapshot must be within [" + before + ", " + after + "], was " + playing.time());
            assertEquals(30_000L, snapshot.time(), "an earlier snapshot must remain immutable");
        }

        @Test
        @DisplayName("Every transition reaches the listener, including the ones controls cause")
        void testListenerHearsEveryTransition() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            final List<Status> seen = new CopyOnWriteArrayList<>();
            player.onStatus((p, prev, next) -> seen.add(next));
            player.syncDuration(60_000L);
            player.start();
            player.pause();
            player.resume();
            player.stop();
            assertEquals(List.of(Status.PLAYING, Status.PAUSED, Status.PLAYING, Status.STOPPED), seen);
        }

        @Test
        @DisplayName("release() lands STOPPED with a new revision, so its last snapshot can still be sent")
        void testReleaseLandsStopped() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            final StatusCapture cap = new StatusCapture();
            player.onStatus(cap);
            player.syncDuration(60_000L);
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitCondition(() -> player.time() > 0L, ENDED_TIMEOUT_MS), "the clock must run before the release");
            final int revision = player.revision();

            player.release();
            assertEquals(Status.STOPPED, player.status());
            assertEquals(revision + 1, player.revision(), "the release is one more mutation to send");
            assertEquals(Status.PLAYING, cap.from);
            assertEquals(Status.STOPPED, cap.to, "the listener hears the release");
            final MediaPlayer.PlayerState last = player.snapshot();
            assertEquals(Status.STOPPED, last.status());
            assertEquals(player.revision(), last.revision());
            assertTrue(last.time() > 0L && last.time() == player.time(), "the position freezes where the release caught it");
            assertFalse(player.canPlay());
            assertFalse(player.start(), "a released clock never runs again");
            player.release();
            assertEquals(revision + 1, player.revision(), "a second release is a no-op");
        }

        @Test
        @DisplayName("A released clock refuses every control and keeps its last snapshot")
        void testReleasedClockIsInert() {
            final ServerMediaPlayer player = ServerMediaPlayerTest.this.solo();
            player.syncDuration(60_000L);
            assertTrue(player.startPaused());
            assertTrue(player.seek(10_000L));
            player.release();
            final MediaPlayer.PlayerState last = player.snapshot();
            assertFalse(player.seek(20_000L), "a released clock cannot be scrubbed");
            assertFalse(player.pause(false), "a released clock cannot resume");
            assertFalse(player.togglePlay());
            assertFalse(player.speed(2f));
            assertFalse(player.repeat(true));
            player.syncTime(30_000L);
            player.live(true);
            assertEquals(last, player.snapshot(), "nothing moves a released clock");
        }
    }

    // CAPTURES THE LAST STATUS TRANSITION FROM THE SHARED TICKER THREAD; volatile FOR VISIBILITY
    private static final class StatusCapture implements MediaPlayer.StatusListener {
        private volatile Status from;
        private volatile Status to;
        @Override public void onStatus(final MediaPlayer player, final Status prev, final Status next) { this.from = prev; this.to = next; }
    }
}
