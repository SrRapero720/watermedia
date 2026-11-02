package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.decoders.FFDecoder;
import org.watermedia.api.player.MediaPlayer.Status;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link MasterClock}: the state machine, seek lifecycle, serial invalidation and speed/time linearity. */
@DisplayName("MasterClock")
public class MasterClockTest {

    private static final long ADVANCE_MS = 20L; // WALL-CLOCK TIME THAT LETS PLAYING TIME DIVERGE FROM ZERO BEFORE A READ

    // ==========================================================================
    // NESTED TYPES: STATE MACHINE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("State Machine")
    class StateMachineTests {

        @Test
        @DisplayName("PLAYING to PAUSED freezes the PTS")
        void testTransitionFromPlayingToPausedFreezesPts() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            clock.start();
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.transition(Status.PAUSED));
            assertEquals(Status.PAUSED, clock.status());

            // TWO READS A SLEEP APART MATCH WHILE PAUSED
            final double frozen = clock.time();
            Thread.sleep(ADVANCE_MS);
            assertEquals(frozen, clock.time(), 1.0e-9);
        }

        @Test
        @DisplayName("Illegal transition is rejected")
        void testIllegalTransitionIsRejected() {
            final MasterClock clock = new MasterClock();
            clock.start();

            // PLAYING -> LOADING IS NOT IN THE STATE MACHINE
            assertFalse(clock.transition(Status.LOADING));
            assertEquals(Status.PLAYING, clock.status());
        }

        @Test
        @DisplayName("A pause while loading only latches the intent, and start() applies it")
        void testPauseWhileLoadingLatches() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.paused(true), "nothing to latch into before a run exists");
            assertFalse(clock.pauseRequested());
            clock.transition(Status.LOADING);
            assertTrue(clock.paused(true), "the first latch is a change");
            assertEquals(Status.LOADING, clock.status(), "LOADING is left only by start() or a terminal state");
            assertFalse(clock.paused(true), "re-latching the same intent changes nothing");
            assertTrue(clock.pauseRequested());
            clock.start();
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("A reload takes a running clock back to LOADING with its pause intent and a cleared end, but never revives a finished run")
        void testReloadFromAnyRunningStatus() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.reload(), "WAITING has no run to reload");
            clock.transition(Status.LOADING);
            clock.start();
            clock.paused(true);
            clock.signalDemuxFinished();
            assertTrue(clock.reload(), "a pause racing the switch still reloads");
            assertEquals(Status.LOADING, clock.status());
            assertFalse(clock.demuxFinished(), "the next source starts unread");
            clock.start();
            assertEquals(Status.PAUSED, clock.status(), "the latched intent survives the reload");
            assertTrue(clock.transition(Status.ERROR));
            assertFalse(clock.reload(), "a failed run stays failed");
            assertEquals(Status.ERROR, clock.status());
        }

        @Test
        @DisplayName("Entering STOPPED rewinds the clock and a halt forces it from any state")
        void testStoppedRewindsAndHaltForces() {
            final MasterClock clock = new MasterClock();
            clock.start();
            clock.requestSeek(5_000L, true);
            assertTrue(clock.transition(Status.STOPPED));
            assertEquals(0L, clock.timeMs());
            assertNull(clock.halt(), "already stopped");

            final MasterClock failed = new MasterClock();
            failed.start();
            assertTrue(failed.transition(Status.ERROR));
            assertEquals(Status.ERROR, failed.halt());
            assertEquals(Status.STOPPED, failed.status());
            failed.start();
            assertEquals(Status.STOPPED, failed.status(), "start() never revives a stopped run");
        }

        @Test
        @DisplayName("A listener runs outside the clock lock, so another thread can seek while it runs")
        void testListenerRunsOutsideTheLock() {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            final boolean[] blocked = new boolean[1];
            // THE LISTENER'S OWN THREAD COULD RE-ENTER A REENTRANT LOCK; ONLY A SECOND THREAD PROVES THE LOCK IS FREE
            clock.listener((prev, next) -> {
                seen.add(next);
                if (next != Status.PAUSED) return;
                final Thread seeker = new Thread(() -> clock.requestSeek(10L, true));
                seeker.start();
                try { seeker.join(2_000L); }
                catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
                blocked[0] = seeker.isAlive();
            });
            clock.start();
            clock.paused(true);
            assertFalse(blocked[0], "a seek from another thread must complete while the listener runs");
            assertEquals(List.of(Status.PLAYING, Status.PAUSED, Status.BUFFERING), seen);
        }

        @Test
        @DisplayName("The listener hears every transition in order, after it is published")
        void testListenerOrder() {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            clock.listener((prev, next) -> {
                seen.add(next);
                // THE STATUS IS ALREADY PUBLISHED WHEN THE LISTENER HEARS IT
                assertEquals(next, clock.status());
            });
            clock.transition(Status.LOADING);
            clock.start();
            clock.paused(true);
            clock.requestSeek(100L, true);
            clock.updateMs(100L, clock.serial());
            clock.transition(Status.ENDED);
            clock.reset();
            assertEquals(List.of(Status.LOADING, Status.PLAYING, Status.PAUSED, Status.BUFFERING, Status.PAUSED,
                    Status.ENDED, Status.WAITING), seen);
        }
    }

    // ==========================================================================
    // NESTED TYPES: SEEK LIFECYCLE TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Seek Lifecycle")
    class SeekTests {

        @Test
        @DisplayName("requestSeek enqueues, enters BUFFERING and bumps the serial")
        void testRequestSeekEnqueuesAndBufferingAndBumpsSerial() {
            final MasterClock clock = new MasterClock();
            clock.start();
            final int beforeSerial = clock.serial();

            assertTrue(clock.requestSeek(2_000L, true));
            assertEquals(Status.BUFFERING, clock.status());
            assertTrue(clock.serial() > beforeSerial);

            final FFDecoder.Seek req = clock.consumeSeek();
            assertNotNull(req);
            assertEquals(2_000L, req.targetMs());
            assertTrue(req.precise());

            // CONSUMING IS DESTRUCTIVE
            assertNull(clock.consumeSeek());
        }

        @Test
        @DisplayName("A seek while loading stays LOADING and start() lands BUFFERING at the target")
        void testSeekWhileLoadingDeferredToStart() {
            final MasterClock clock = new MasterClock();
            clock.transition(Status.LOADING);
            assertTrue(clock.requestSeek(4_000L, true));
            assertEquals(Status.LOADING, clock.status());
            assertTrue(clock.paused(true));
            clock.start();
            assertEquals(Status.BUFFERING, clock.status());
            assertEquals(4_000L, clock.timeMs());
            assertTrue(clock.pauseRequested());
            // THE FIRST FRAME AFTER THE SEEK SETTLES THE LATCHED PAUSE
            clock.updateMs(4_000L, clock.serial());
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("Seeks are rejected where no pipeline can serve them")
        void testSeekRejectedWithoutPipeline() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.requestSeek(1L, true), "WAITING");
            clock.start();
            assertTrue(clock.transition(Status.ENDED));
            assertFalse(clock.requestSeek(1L, true), "ENDED has no pipeline: the player restarts instead");
            assertEquals(Status.ENDED, clock.status());
        }

        @Test
        @DisplayName("Update with a stale serial is silently rejected")
        void testUpdateWithWrongSerialIsSilentlyRejected() {
            final MasterClock clock = new MasterClock();
            clock.start();
            // THE SEEK BUMPS THE SERIAL, SO THE PREVIOUS ONE IS STALE
            clock.requestSeek(0L, false);
            final int currentSerial = clock.serial();
            final long before = clock.timeMs();
            clock.update(5.0, currentSerial - 1);

            // NO PROMOTION TO PLAYING, AND THE TIME STAYS FROZEN AT THE SEEK TARGET
            assertEquals(Status.BUFFERING, clock.status());
            assertEquals(before, clock.timeMs());
        }
    }

    // ==========================================================================
    // NESTED TYPES: SPEED AND TIME LINEARITY TESTS
    // ==========================================================================

    @Nested
    @DisplayName("Speed and Time Linearity")
    class SpeedTests {

        @Test
        @DisplayName("Speed change preserves time linearity")
        void testSpeedChangePreservesTimeLinearity() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            clock.start();
            Thread.sleep(ADVANCE_MS);

            final long opened = System.nanoTime();
            final double beforeChange = clock.time();
            clock.speed(2.0);
            final double afterChange = clock.time();
            final long closed = System.nanoTime();

            // SPEED RECALIBRATES BUT MUST NOT INTRODUCE A DISCONTINUITY: ONLY THE BRACKETED WINDOW, AT 2x AT MOST, MAY PASS
            assertTrue(afterChange >= beforeChange, "time must not go backwards");
            assertTrue(afterChange - beforeChange <= 2.0 * (closed - opened) / 1_000_000_000.0,
                    "a speed change must not jump the clock: " + beforeChange + " -> " + afterChange);
            assertEquals(2.0, clock.speed(), 0.0);
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.time() > afterChange);
        }

        @Test
        @DisplayName("reset() keeps the playback speed across runs")
        void testResetKeepsSpeed() {
            final MasterClock clock = new MasterClock();
            clock.start();
            clock.speed(2.0);
            clock.reset();
            assertEquals(Status.WAITING, clock.status());
            assertEquals(2.0, clock.speed(), 0.0, "speed is a player setting, not pipeline state");
        }
    }
}
