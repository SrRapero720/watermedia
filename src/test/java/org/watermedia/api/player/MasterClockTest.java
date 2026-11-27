package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MasterClock}: the state machine (the pause freeze, the latched pause, reload, stop and halt), the order and
 * lock of its listener, the seek lifecycle with its epochs and landings, the timed hold and its reread, and the speed.
 */
@DisplayName("MasterClock")
public class MasterClockTest {

    private static final long ADVANCE_MS = 20L; // WALL-CLOCK TIME THAT LETS PLAYING TIME DIVERGE FROM ZERO BEFORE A READ
    private static final long JOIN_MS = 2_000L;

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A CLOCK STARTED AND LANDED AT 0, AS A PLAYER LEAVES IT ONCE EVERY STREAM HAS ITS FIRST FRAME
    private static MasterClock playing() {
        final MasterClock clock = new MasterClock();
        clock.transition(Status.LOADING);
        clock.start();
        assertTrue(clock.land(0.0, clock.epoch()), "a started clock lands");
        assertEquals(Status.PLAYING, clock.status());
        return clock;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("State machine")
    class StateMachine {

        @Test
        @DisplayName("A start buffers at its position and only a landing plays")
        void startBuffersUntilItLands() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            clock.transition(Status.LOADING);
            clock.start();
            assertEquals(Status.BUFFERING, clock.status(), "the input is open but no frame came yet");
            assertTrue(clock.unsettled());
            Thread.sleep(ADVANCE_MS);
            assertEquals(0L, clock.timeMs(), "a buffering clock stands still");
            assertTrue(clock.land(0.5, clock.epoch()));
            assertEquals(Status.PLAYING, clock.status());
            assertFalse(clock.unsettled());
            assertTrue(clock.timeMs() >= 500L, "the clock runs from where it landed, not " + clock.timeMs() + " ms");
        }

        @Test
        @DisplayName("PLAYING to PAUSED freezes the PTS where playback reached")
        void transitionFromPlayingToPausedFreezesPts() throws InterruptedException {
            final MasterClock clock = playing();
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.transition(Status.PAUSED));
            assertEquals(Status.PAUSED, clock.status());

            final double frozen = clock.time();
            assertTrue(frozen >= ADVANCE_MS / 1_000.0, "the pause keeps the time reached before it, not " + frozen + " s");
            Thread.sleep(ADVANCE_MS);
            assertEquals(frozen, clock.time(), 1.0e-9, "a paused clock stands still");
        }

        @Test
        @DisplayName("A transition the state machine lacks is refused and leaves the status as it was")
        void illegalTransitionIsRejected() {
            final MasterClock clock = playing();
            assertFalse(clock.transition(Status.LOADING), "PLAYING never goes back to LOADING through a transition");
            assertEquals(Status.PLAYING, clock.status());
        }

        @Test
        @DisplayName("A pause while loading only latches the intent, and the landing applies it")
        void pauseWhileLoadingLatches() {
            final MasterClock clock = new MasterClock();
            assertFalse(clock.paused(true), "nothing to latch into before a run exists");
            assertFalse(clock.pauseRequested());
            clock.transition(Status.LOADING);
            assertTrue(clock.paused(true), "the first latch is a change");
            assertEquals(Status.LOADING, clock.status(), "LOADING is left only by start() or a terminal state");
            assertFalse(clock.paused(true), "re-latching the same intent changes nothing");
            assertTrue(clock.pauseRequested());
            clock.start();
            assertEquals(Status.BUFFERING, clock.status());
            assertTrue(clock.land(0.0, clock.epoch()));
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("A reload takes a running clock back to LOADING with its time and its pause intent, but never revives a finished run")
        void reloadFromAnyRunningStatus() throws InterruptedException {
            // A PAUSE OR A SEEK MAY RACE THE SWITCH, AND FFMediaPlayer.switchSource ITSELF RELOADS FROM BUFFERING
            for (final Status running: List.of(Status.PLAYING, Status.PAUSED, Status.BUFFERING)) {
                final MasterClock clock = playing();
                Thread.sleep(ADVANCE_MS);
                if (running == Status.PAUSED) assertTrue(clock.paused(true));
                if (running == Status.BUFFERING) assertTrue(clock.transition(Status.BUFFERING));

                assertTrue(clock.reload(), running + " has a run to reload");
                assertEquals(Status.LOADING, clock.status());
                assertTrue(clock.time() >= ADVANCE_MS / 1_000.0, running + ": the reload keeps the time reached, not " + clock.time() + " s");
                clock.start();
                assertEquals(Status.BUFFERING, clock.status(), running + ": the new source buffers until it lands");
                assertTrue(clock.land(clock.time(), clock.epoch()));
                assertEquals(running == Status.PAUSED ? Status.PAUSED : Status.PLAYING, clock.status(), running + ": the latched intent survives the reload");
            }

            // WAITING HAS NO RUN YET; ENDED, STOPPED AND ERROR FINISHED THEIRS
            for (final Status finished: List.of(Status.WAITING, Status.ENDED, Status.STOPPED, Status.ERROR)) {
                final MasterClock clock = finished == Status.WAITING ? new MasterClock() : playing();
                if (finished != Status.WAITING) assertTrue(clock.transition(finished));
                assertFalse(clock.reload(), finished + " has no run to reload");
                assertEquals(finished, clock.status());
            }
        }

        @Test
        @DisplayName("Entering STOPPED rewinds the clock, and a halt forces STOPPED even from ERROR")
        void stoppedRewindsAndHaltForces() {
            final MasterClock clock = playing();
            clock.requestSeek(5_000L, true, false);
            assertTrue(clock.transition(Status.STOPPED));
            assertEquals(0L, clock.timeMs());
            assertNull(clock.halt(), "already stopped");

            final MasterClock failed = playing();
            assertTrue(failed.transition(Status.ERROR));
            assertEquals(Status.ERROR, failed.halt());
            assertEquals(Status.STOPPED, failed.status());
            failed.start();
            assertEquals(Status.STOPPED, failed.status(), "start() never revives a stopped run");
        }

        @Test
        @DisplayName("A listener runs outside the clock lock, so another thread can seek while it runs")
        void listenerRunsOutsideTheLock() {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            final boolean[] blocked = new boolean[1];
            // THE LISTENER'S OWN THREAD COULD RE-ENTER A REENTRANT LOCK; ONLY A SECOND THREAD PROVES THE LOCK IS FREE
            clock.listener((prev, next) -> {
                seen.add(next);
                if (next != Status.PAUSED) return;
                final Thread seeker = new Thread(() -> clock.requestSeek(10L, true, false));
                seeker.start();
                try { seeker.join(JOIN_MS); }
                catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
                blocked[0] = seeker.isAlive();
            });
            clock.start();
            clock.land(0.0, clock.epoch());
            clock.paused(true);
            assertFalse(blocked[0], "a seek from another thread must complete while the listener runs");
            assertEquals(List.of(Status.BUFFERING, Status.PLAYING, Status.PAUSED, Status.BUFFERING), seen, "the seek of the second thread is heard after the pause");
        }

        @Test
        @DisplayName("The listener hears every transition in order, after it is published")
        void listenerOrder() {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            clock.listener((prev, next) -> {
                seen.add(next);
                assertEquals(next, clock.status(), "the status is published before the listener hears it");
            });
            clock.transition(Status.LOADING);
            clock.start();
            clock.land(0.0, clock.epoch());
            clock.paused(true);
            clock.requestSeek(100L, true, false);
            clock.land(0.1, clock.epoch());
            clock.transition(Status.ENDED);
            clock.reset();
            assertEquals(List.of(Status.LOADING, Status.BUFFERING, Status.PLAYING, Status.PAUSED, Status.BUFFERING, Status.PAUSED,
                    Status.ENDED, Status.WAITING), seen);
        }
    }

    @Nested
    @DisplayName("Seek lifecycle")
    class SeekLifecycle {

        @Test
        @DisplayName("requestSeek() queues the seek once, enters BUFFERING and opens a new epoch")
        void requestSeekQueuesBuffersAndOpensAnEpoch() {
            final MasterClock clock = playing();
            final int epoch = clock.epoch();

            assertTrue(clock.requestSeek(2_000L, true, true));
            assertEquals(Status.BUFFERING, clock.status());
            assertTrue(clock.unsettled());
            assertEquals(epoch + 1, clock.epoch(), "the frames of the old position belong to the old epoch");
            assertEquals(2_000L, clock.timeMs(), "the clock freezes at the target");

            final MasterClock.Seek seek = clock.consumeSeek();
            assertNotNull(seek, "the seek waits for the player");
            assertEquals(new MasterClock.Seek(2_000L, true, true, epoch + 1), seek);
            assertNull(clock.consumeSeek(), "the player takes the seek once");
        }

        @Test
        @DisplayName("A seek while loading stays LOADING, start() buffers at the target and the landing settles the latched pause")
        void seekWhileLoadingDeferredToStart() {
            final MasterClock clock = new MasterClock();
            clock.transition(Status.LOADING);
            assertTrue(clock.requestSeek(4_000L, true, false));
            assertEquals(Status.LOADING, clock.status());
            assertTrue(clock.paused(true));
            clock.start();
            assertEquals(Status.BUFFERING, clock.status());
            assertEquals(4_000L, clock.timeMs());
            assertTrue(clock.pauseRequested());
            assertTrue(clock.land(4.0, clock.epoch()));
            assertEquals(Status.PAUSED, clock.status());
        }

        @Test
        @DisplayName("A seek is refused where no pipeline can serve it: WAITING, ENDED, STOPPED and ERROR")
        void seekRejectedWithoutPipeline() {
            assertFalse(new MasterClock().requestSeek(1L, true, false), "WAITING has no pipeline yet");
            // A PLAYER RESTARTS FROM ENDED OR STOPPED INSTEAD, AND ONLY stop() OR release() LEAVE ERROR
            for (final Status finished: List.of(Status.ENDED, Status.STOPPED, Status.ERROR)) {
                final MasterClock clock = playing();
                assertTrue(clock.transition(finished));
                assertFalse(clock.requestSeek(1L, true, false), finished + " has no pipeline");
                assertEquals(finished, clock.status());
            }
        }

        @Test
        @DisplayName("A frame of an older epoch neither lands nor moves the clock, and no frame starts a buffering clock")
        void staleAndEarlyFramesAreIgnored() {
            final MasterClock clock = playing();
            clock.requestSeek(0L, false, false);
            final long target = clock.timeMs();
            assertFalse(clock.land(5.0, clock.epoch() - 1), "a frame of the old position never lands the seek");
            clock.update(5.0, clock.epoch() - 1);
            assertEquals(Status.BUFFERING, clock.status(), "a stale frame never promotes the clock to PLAYING");
            assertEquals(target, clock.timeMs(), "a stale frame never moves the clock");
            clock.update(5.0, clock.epoch());
            assertEquals(Status.BUFFERING, clock.status(), "only a landing leaves BUFFERING");
            assertEquals(target, clock.timeMs(), "a buffering clock stays at the target");
        }

        @Test
        @DisplayName("A clock paused before its seek landed resumes into BUFFERING, and a landing while paused moves it without playing")
        void pauseBeforeTheLanding() {
            final MasterClock clock = playing();
            clock.requestSeek(3_000L, true, false);
            assertTrue(clock.paused(true));
            assertEquals(Status.PAUSED, clock.status());
            assertTrue(clock.paused(false));
            assertEquals(Status.BUFFERING, clock.status(), "nothing landed yet, so the resume buffers instead of playing without frames");

            assertTrue(clock.paused(true));
            assertTrue(clock.land(3.2, clock.epoch()), "a paused clock takes the landing");
            assertEquals(Status.PAUSED, clock.status(), "the landing keeps the pause");
            assertEquals(3_200L, clock.timeMs(), "the clock moves where the streams landed");
            assertFalse(clock.unsettled());
            assertTrue(clock.paused(false));
            assertEquals(Status.PLAYING, clock.status(), "a landed clock resumes into PLAYING");
        }
    }

    @Nested
    @DisplayName("Hold")
    class Hold {

        @Test
        @DisplayName("A hold is timed from the moment the clock pauses, by request, as a seek lands or at a paused start, and ends with the pause")
        void holdTimedFromThePause() throws InterruptedException {
            final MasterClock clock = playing();
            assertEquals(0L, clock.heldMs(), "a playing clock holds nothing");

            // BRACKETED: THE HOLD LIES BETWEEN THE TIME SINCE THE PAUSE RETURNED AND THE TIME SINCE IT WAS CALLED
            final long called = System.nanoTime();
            assertTrue(clock.paused(true));
            final long returned = System.nanoTime();
            Thread.sleep(ADVANCE_MS);
            final long before = System.nanoTime();
            final long held = clock.heldMs();
            final long after = System.nanoTime();
            assertTrue(held >= TimeUnit.NANOSECONDS.toMillis(before - returned) && held <= TimeUnit.NANOSECONDS.toMillis(after - called),
                    "the hold must be timed from the pause, it reports " + held + " ms");

            // A SEEK WHILE PAUSED READS AGAIN, SO THE HOLD STARTS OVER WHEN IT LANDS PAUSED
            assertTrue(clock.requestSeek(1_000L, true, false));
            assertEquals(0L, clock.heldMs(), "a buffering clock holds nothing");
            final long landing = System.nanoTime();
            assertTrue(clock.land(1.0, clock.epoch()));
            assertEquals(Status.PAUSED, clock.status());
            assertTrue(clock.heldMs() <= TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - landing), "the hold restarts as the seek lands");
            assertTrue(clock.paused(false));
            assertEquals(0L, clock.heldMs(), "the resume ends the hold");

            // A PAUSED START HOLDS FROM ITS LANDING, NOT FROM THE LATCHED INTENT
            final MasterClock launched = new MasterClock();
            launched.transition(Status.LOADING);
            assertTrue(launched.paused(true));
            assertEquals(0L, launched.heldMs(), "a loading clock holds nothing yet");
            Thread.sleep(ADVANCE_MS);
            launched.start();
            assertFalse(launched.holding(), "a paused start holds only once it lands");
            final long starting = System.nanoTime();
            assertTrue(launched.land(0.0, launched.epoch()));
            assertTrue(launched.holding(), "a landed paused start holds");
            assertTrue(launched.heldMs() <= TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - starting), "the hold starts with the landing");
        }

        @Test
        @DisplayName("A reread resumes a holding clock straight into BUFFERING with a precise seek to the paused position, and refuses any other clock")
        void rereadSeeksThePausedPosition() throws InterruptedException {
            final MasterClock clock = new MasterClock();
            final List<Status> seen = new ArrayList<>();
            clock.listener((prev, next) -> seen.add(next));
            clock.start();
            assertTrue(clock.land(0.0, clock.epoch()));
            assertFalse(clock.reread(), "a playing clock is not holding");
            clock.requestSeek(3_000L, true, false);
            assertTrue(clock.land(3.0, clock.epoch()));
            Thread.sleep(ADVANCE_MS);
            assertTrue(clock.paused(true));
            final long paused = clock.timeMs();
            final int epoch = clock.epoch();
            seen.clear();

            assertTrue(clock.reread(), "a holding clock reads again");
            assertEquals(List.of(Status.BUFFERING), seen, "the reread never passes through PLAYING");
            assertFalse(clock.pauseRequested(), "the reread resumes");
            assertFalse(clock.holding());
            assertEquals(epoch + 1, clock.epoch(), "the frames queued before the hold are stale");
            assertEquals(paused, clock.timeMs(), 1.0, "the clock stays frozen at the paused position");
            final MasterClock.Seek seek = clock.consumeSeek();
            assertNotNull(seek, "the seek is pending before the player serves it");
            assertEquals(new MasterClock.Seek(paused, true, false, epoch + 1), seek);
            assertTrue(clock.land(paused / 1_000.0, seek.epoch()));
            assertEquals(Status.PLAYING, clock.status(), "the landing plays");
            assertFalse(clock.reread(), "only a holding clock reads again");

            final MasterClock loading = new MasterClock();
            loading.transition(Status.LOADING);
            assertTrue(loading.paused(true));
            assertFalse(loading.reread(), "a loading clock has no input to read again");
            assertTrue(loading.pauseRequested(), "a refused reread keeps the pause intent");
        }
    }

    @Nested
    @DisplayName("Speed")
    class Speed {

        @Test
        @DisplayName("A speed change keeps the time continuous and the clock running")
        void speedChangePreservesTimeLinearity() throws InterruptedException {
            final MasterClock clock = playing();
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
            assertTrue(clock.time() > afterChange, "the clock keeps advancing after the change");
        }

        @Test
        @DisplayName("reset() keeps the playback speed across runs")
        void resetKeepsSpeed() {
            final MasterClock clock = playing();
            clock.speed(2.0);
            clock.reset();
            assertEquals(Status.WAITING, clock.status());
            assertEquals(2.0, clock.speed(), 0.0, "speed is a player setting, not pipeline state");
        }
    }
}
