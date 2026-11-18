package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.PlayerState;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how a follower turns the last snapshot fed into the session position: ageing at the session rate, frozen
 * paused and stopped sessions, the latest snapshot winning, the BUFFERING hold, live sessions (the mirror's own clock, no
 * folding, saturation, the live flag and the duration) and the media timeline (loop wrap, end clamp, an ended follower).
 */
@DisplayName("Follower clock")
public class FollowerClockTest {

    private static final long TIMEOUT_MS = 2_000L; // BOUND OF EVERY WAIT: FORTY CYCLES OF THE SHARED 50 ms TICKER
    private static final long SETTLE_MS = 300L;    // SIX TICKS, SO A "NOTHING MOVED" ASSERTION MEANS SOMETHING

    private ServerMediaPlayer follower;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @AfterEach
    void releaseFollower() {
        if (this.follower != null) this.follower.release();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // A HEADLESS MIRROR THE TEST FEEDS SNAPSHOTS BY HAND
    private ServerMediaPlayer follower() {
        this.follower = PlayerAPI.serverPlayer();
        return this.follower;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Ageing")
    class Ageing {

        @Test
        @DisplayName("A playing snapshot ages into a live position at the session rate")
        void agesAtTheSessionRate() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            assertNull(follower.authority(), "nothing heard from the session yet");
            assertEquals(0L, follower.authorityTime());

            // THE SNAPSHOT LANDS INSIDE [before, received] AND IS AGED INSIDE [reading, after]
            final long before = System.nanoTime();
            follower.sync(new PlayerState(7, Status.PLAYING, 30_000L, 60_000L, 2f, false, false));
            final long received = System.nanoTime();
            assertEquals(7, follower.authority().revision());

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = follower.authorityTime();
            final long after = System.nanoTime();
            final long minimum = 30_000L + 2 * TimeUnit.NANOSECONDS.toMillis(reading - received);
            final long maximum = 30_000L + 2 * TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum, "the session at 2x must be within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("A paused or stopped session reports its frozen position")
        void frozenWhenNotPlaying() {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(1, Status.PAUSED, 5_000L, 60_000L, 1f, false, false));
            assertEquals(5_000L, follower.authorityTime());
            assertFalse(PlayerWait.awaitCondition(() -> follower.authorityTime() != 5_000L, 100L), "a paused session must not advance");

            // A RELEASED CLOCK SENDS STOPPED AT THE POSITION IT FROZE
            follower.sync(new PlayerState(2, Status.STOPPED, 7_000L, 60_000L, 1f, false, false));
            assertEquals(7_000L, follower.authorityTime());
            assertFalse(PlayerWait.awaitCondition(() -> follower.authorityTime() != 7_000L, 100L), "a stopped session must not advance");
        }

        @Test
        @DisplayName("The latest snapshot fed wins whatever its revision, and a resend restarts its ageing")
        void latestWins() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(5, Status.PAUSED, 30_000L, 60_000L, 1f, false, false));
            // ORDERING IS THE HOST TRANSPORT'S JOB: AN OLDER REVISION FED LATER STILL LANDS
            final long before = System.nanoTime();
            follower.sync(new PlayerState(4, Status.PLAYING, 500L, 60_000L, 1f, false, false));
            final long received = System.nanoTime();
            assertEquals(4, follower.authority().revision());
            assertEquals(Status.PLAYING, follower.authority().status());

            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long aged = follower.authorityTime();
            final long after = System.nanoTime();
            final long minimum = 500L + TimeUnit.NANOSECONDS.toMillis(reading - received);
            final long maximum = 500L + TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(aged >= minimum && aged <= maximum,
                    "the later snapshot must age from 500 within [" + minimum + ", " + maximum + "], was " + aged);

            // A RESEND OF THE SAME REVISION RESTAMPS ITS ARRIVAL, SO AGEING STARTS OVER FROM ITS TIME
            final long resent = System.nanoTime();
            follower.sync(new PlayerState(4, Status.PLAYING, 500L, 60_000L, 1f, false, false));
            final long t = follower.authorityTime();
            final long read = System.nanoTime();
            final long limit = 500L + TimeUnit.NANOSECONDS.toMillis(read - resent);
            assertTrue(t >= 500L && t <= limit, "the resend must age from [500, " + limit + "], was " + t);
        }
    }

    @Nested
    @DisplayName("Status mirror")
    class StatusMirror {

        @Test
        @DisplayName("A BUFFERING session holds the follower like a pause")
        void bufferingHolds() {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(1, Status.PLAYING, 1_000L, 60_000L, 1f, false, false));
            assertTrue(PlayerWait.awaitCondition(follower::playing, TIMEOUT_MS), "the follower must start with the session");

            follower.sync(new PlayerState(2, Status.BUFFERING, 1_200L, 60_000L, 1f, false, false));
            assertTrue(PlayerWait.awaitCondition(follower::paused, TIMEOUT_MS), "a buffering session must hold the follower");
            assertEquals(1_200L, follower.authorityTime(), "a buffering session does not advance");

            follower.sync(new PlayerState(3, Status.PLAYING, 1_200L, 60_000L, 1f, false, false));
            assertTrue(PlayerWait.awaitCondition(follower::playing, TIMEOUT_MS), "the follower must resume with the session");
        }
    }

    @Nested
    @DisplayName("Live sessions")
    class Live {

        @Test
        @DisplayName("A live session drives the mirror's own clock")
        void liveSessionRunsTheMirrorClock() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            // A LIVE SESSION HAS NO DURATION: THE MIRROR MUST ADOPT THE LIVE FLAG OR THE TIMELINE-HELD GUARD FREEZES ITS
            // OWN CLOCK AT ZERO FOREVER
            final long before = System.nanoTime();
            follower.sync(new PlayerState(1, Status.PLAYING, 0L, 0L, 1f, false, true));
            assertTrue(PlayerWait.awaitCondition(follower::playing, TIMEOUT_MS), "the mirror must start with the session");
            final long started = System.nanoTime();
            assertTrue(follower.live(), "the mirror must know the session is live");
            Thread.sleep(100L);
            final long reading = System.nanoTime();
            final long t = follower.time();
            final long after = System.nanoTime();
            final long minimum = TimeUnit.NANOSECONDS.toMillis(reading - started);
            final long maximum = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= minimum && t <= maximum, "a live mirror clock must advance within [" + minimum + ", " + maximum + "], was " + t);
        }

        @Test
        @DisplayName("A live session's position is never folded into a latched duration")
        void liveSessionAgesUnfolded() {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(1, Status.PLAYING, 100L, 150L, 1f, false, true));
            // A FOLDED POSITION NEVER EXCEEDS THE 150 ms DURATION
            assertTrue(PlayerWait.awaitCondition(() -> follower.authorityTime() > 150L, TIMEOUT_MS),
                    () -> "a live session must age past the phantom duration, was " + follower.authorityTime());
        }

        @Test
        @DisplayName("A live session snapshotted at the end of the long range ages without wrapping negative")
        void liveSessionSaturates() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            // A CLOCK SEEKED NEAR Long.MAX_VALUE BEFORE IT TURNED LIVE SNAPSHOTS THAT POSITION; AGEING IT MUST SATURATE TOO
            follower.sync(new PlayerState(1, Status.PLAYING, Long.MAX_VALUE - 10L, 0L, 1f, false, true));
            // A SLEEP, NOT A WAIT: PAST THE 10 ms LEFT AN UNSATURATED SUM HAS WRAPPED NEGATIVE, WHILE A POLL COULD LAND ON THE
            // ONE MILLISECOND IT EQUALS Long.MAX_VALUE
            Thread.sleep(50L);
            assertEquals(Long.MAX_VALUE, follower.authorityTime(), "the aged position must saturate");
            assertTrue(follower.drift() >= 0L, "a saturated session stays ahead of the follower");
        }

        @Test
        @DisplayName("A mirror follows the session's live flag both ways and adopts its duration once known")
        void liveFlagMirroredBothWays() {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(1, Status.PLAYING, 0L, 0L, 1f, false, true));
            assertTrue(PlayerWait.awaitCondition(follower::live, TIMEOUT_MS), "the mirror must turn live with the session");
            // THE SESSION LEARNS IT WAS A FINITE FILE AFTER ALL: THE MIRROR DROPS THE LIVE FLAG AND TAKES THE DURATION
            follower.sync(new PlayerState(2, Status.PLAYING, 0L, 60_000L, 1f, false, false));
            assertTrue(PlayerWait.awaitCondition(() -> !follower.live() && follower.duration() == 60_000L, TIMEOUT_MS),
                    "the mirror must follow the session back to a finite timeline");
        }
    }

    @Nested
    @DisplayName("Timeline folding")
    class Timeline {

        @Test
        @DisplayName("A looping session folds its aged position into the media length")
        void wrapsOnRepeat() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            final long before = System.nanoTime();
            follower.sync(new PlayerState(1, Status.PLAYING, 400L, 500L, 1f, true, false));
            final long received = System.nanoTime();
            Thread.sleep(250L);
            final long reading = System.nanoTime();
            final long t = follower.authorityTime();
            final long after = System.nanoTime();
            // UNFOLDED, THE SESSION SITS IN [low, high]; FOLDED INTO THE 500 ms MEDIA IT IS ONE OF THOSE POSITIONS MODULO 500,
            // HOWEVER MANY LOOPS A STALLED HOST LETS PASS
            final long low = 400L + TimeUnit.NANOSECONDS.toMillis(reading - received);
            final long high = 400L + TimeUnit.NANOSECONDS.toMillis(after - before);
            assertTrue(t >= 0L && t < 500L && Math.floorMod(t - low, 500L) <= high - low,
                    "the session must fold [" + low + ", " + high + "] into the 500 ms media, was " + t);
        }

        @Test
        @DisplayName("A session without repeat clamps its aged position at the end of the media")
        void clampsWithoutRepeat() throws InterruptedException {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            follower.sync(new PlayerState(1, Status.PLAYING, 400L, 500L, 1f, false, false));
            // A SLEEP, NOT A WAIT: WELL PAST THE 100 ms LEFT AN UNCLAMPED POSITION READS 650 OR MORE, WHILE A POLL COULD LAND
            // ON THE ONE MILLISECOND IT EQUALS 500
            Thread.sleep(250L);
            assertEquals(500L, follower.authorityTime(), "playback cannot run past the end of the media");
        }

        @Test
        @DisplayName("An ended follower is not replayed by a session already past its media, only by a rewind")
        void endedHoldsAgainstDivergentSession() {
            final ServerMediaPlayer follower = FollowerClockTest.this.follower();
            // THE FOLLOWER'S OWN MEDIA IS 500 ms; THE SESSION LATCHED A DIVERGENT 60 s TIMELINE AND SITS AT 5 s. RESTARTING
            // WOULD REPLAY THE MEDIA FOREVER UNTIL THE SESSION CATCHES UP
            follower.syncDuration(500L);
            // THE SESSION HEARTBEATS EVERY 50 ms WHILE THE DRIFT CORRECTION SEEKS THE FOLLOWER TOWARDS THE SESSION POSITION,
            // CLAMPED TO ITS OWN END; A CLOCK GOING BACKWARDS IS A RESTART
            final long[] last = new long[1];
            final long[] restarts = new long[1];
            assertTrue(PlayerWait.awaitCondition(() -> {
                follower.sync(new PlayerState(1, Status.PLAYING, 5_000L, 60_000L, 1f, false, false));
                ThreadTool.sleep(50L);
                final long t = follower.time();
                if (t < last[0]) restarts[0]++;
                last[0] = t;
                return follower.ended();
            }, TIMEOUT_MS), "the follower must reach its own end");
            assertEquals(0L, restarts[0], "no restarts while ending");

            // NEITHER THE AGEING SNAPSHOT NOR A FRESH ONE FURTHER AHEAD MAY REVIVE IT, NOT EVEN FOR ONE TICK
            assertFalse(PlayerWait.awaitCondition(() -> !follower.ended(), SETTLE_MS), "an ageing session past our media must not replay it");
            follower.sync(new PlayerState(1, Status.PLAYING, 8_000L, 60_000L, 1f, false, false));
            assertFalse(PlayerWait.awaitCondition(() -> !follower.ended(), SETTLE_MS), "a session past our media must not replay it");

            // A REWIND BELOW OUR MEDIA'S END IS A REAL REPLAY AND MUST GO THROUGH
            follower.sync(new PlayerState(2, Status.PLAYING, 100L, 60_000L, 1f, false, false));
            assertTrue(PlayerWait.awaitCondition(follower::playing, TIMEOUT_MS), "a rewound session must replay the ended media");
        }
    }
}
