package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that headless mirrors follow a {@link ServerMediaPlayer} clock end to end over an in-test byte transport. */
@DisplayName("Follower sync")
public class FollowerPlayerStateTest {

    // NETWORK
    private static final long PERIOD_MS = 50L;          // POLLS THE CLOCK AS OFTEN AS THE SHARED TICKER RUNS
    private static final long RESEND_NS = 500_000_000L; // HEARTBEAT THAT REACHES MIRRORS JOINING A QUIET SESSION

    // WAITS AND TOLERANCES
    private static final long AWAIT_MS = 4_000L;
    private static final long SETTLE_MS = 300L;    // SEVERAL TICKS, SO A "NOTHING MOVED" ASSERTION MEANS SOMETHING
    private static final long TOLERANCE_MS = 200L; // TIGHTER THAN THE 1s DEFAULT SO ASSERTIONS STAY STRICT
    private static final long ALIGN_MS = 350L;     // TOLERANCE PLUS ONE NETWORK PERIOD AND ONE TICK OF SLACK

    private final List<ServerMediaPlayer> players = new ArrayList<>();
    private Network network;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @AfterEach
    void tearDown() throws InterruptedException {
        final Network network = this.network;
        if (network != null) network.close();
        // NOTHING MAY STAY ON THE SHARED TICKER FOR THE REST OF THE JVM
        for (final ServerMediaPlayer player: this.players) player.release();
        if (network != null && network.failure != null) throw new AssertionError("The network thread failed", network.failure);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // A CLOCK FOR A SESSION OF THE GIVEN LENGTH, WITH THE NETWORK THAT CARRIES ITS SNAPSHOTS
    private ServerMediaPlayer clock(final long durationMs) {
        final ServerMediaPlayer clock = this.player();
        clock.syncDuration(durationMs);
        this.network = new Network(clock);
        return clock;
    }

    // A HEADLESS MIRROR THE NETWORK REACHES, WITH A TOLERANCE TIGHT ENOUGH FOR STRICT ASSERTIONS
    private ServerMediaPlayer mirror() {
        final ServerMediaPlayer mirror = this.player();
        mirror.tolerance(TOLERANCE_MS);
        this.network.join(mirror);
        return mirror;
    }

    private ServerMediaPlayer player() {
        final ServerMediaPlayer player = PlayerAPI.serverPlayer();
        this.players.add(player);
        return player;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void await(final String what, final BooleanSupplier condition) {
        assertTrue(PlayerWait.awaitCondition(condition, AWAIT_MS), "timed out waiting for: " + what);
    }

    // TRUE WHEN THE MIRROR'S POSITION FALLS WITHIN slackMs OF THE CLOCK READ ON BOTH SIDES OF IT
    private static boolean aligned(final MediaPlayer clock, final MediaPlayer mirror, final long slackMs) {
        final long before = clock.time();
        final long position = mirror.time();
        final long after = clock.time();
        return position >= before - slackMs && position <= after + slackMs;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Mirror")
    class Mirror {

        @Test
        @DisplayName("Mirrors follow the clock's start, pause and stop")
        void mirrorsFollowStartPauseStop() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer a = FollowerPlayerStateTest.this.mirror();
            final ServerMediaPlayer b = FollowerPlayerStateTest.this.mirror();
            await("both mirrors to adopt the session", () -> a.duration() == 60_000L && b.duration() == 60_000L);
            assertEquals(Status.WAITING, a.status(), "an idle clock starts nobody");
            assertEquals(Status.WAITING, b.status());

            assertTrue(clock.start());
            await("both to play", () -> a.playing() && b.playing());
            await("their clocks to run", () -> a.time() > 100L && b.time() > 100L);

            assertTrue(clock.pause());
            await("both to pause", () -> a.paused() && b.paused());
            await("the paused mirrors to align", () -> aligned(clock, a, TOLERANCE_MS) && aligned(clock, b, TOLERANCE_MS));
            final long frozenA = a.time();
            final long frozenB = b.time();
            ThreadTool.sleep(SETTLE_MS);
            assertEquals(frozenA, a.time(), "a paused mirror must not advance");
            assertEquals(frozenB, b.time(), "a paused mirror must not advance");

            assertTrue(clock.stop());
            await("both to stop", () -> a.stopped() && b.stopped());
            assertEquals(0L, a.time());
            assertEquals(0L, b.time());
        }

        @Test
        @DisplayName("A mirror joining mid-playback is reached by the resend and catches up with a seek")
        void lateMirrorCatchesUp() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(300_000L);
            final ServerMediaPlayer early = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            assertTrue(clock.seek(120_000L));
            await("the early mirror to catch up", () -> early.playing() && aligned(clock, early, ALIGN_MS));

            // THE SESSION IS QUIET NOW: ONLY THE PERIODIC RESEND CAN REACH THE LATE MIRROR
            final int revision = clock.revision();
            final ServerMediaPlayer late = FollowerPlayerStateTest.this.mirror();
            await("the late mirror to play", late::playing);
            await("it to catch up with a seek", () -> aligned(clock, late, ALIGN_MS));
            assertEquals(revision, clock.revision(), "no clock mutation served the late mirror");
        }

        @Test
        @DisplayName("Mirrors adopt the clock's speed and repeat and keep pace at that rate")
        void mirrorsAdoptSpeedAndRepeat() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer a = FollowerPlayerStateTest.this.mirror();
            final ServerMediaPlayer b = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            await("both to play", () -> a.playing() && b.playing());

            assertTrue(clock.speed(2f));
            assertTrue(clock.repeat(true));
            await("both to adopt the rate and the loop", () -> a.speed() == 2f && b.speed() == 2f && a.repeat() && b.repeat());
            await("both to keep pace", () -> aligned(clock, a, ALIGN_MS) && aligned(clock, b, ALIGN_MS));
        }
    }

    @Nested
    @DisplayName("Local controls")
    class LocalControls {

        @Test
        @DisplayName("A local pause applies at once and the next tick resumes the mirror")
        void localPauseIsUndone() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer mirror = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            await("the mirror to play", mirror::playing);

            assertTrue(mirror.pause(), "a control on a following player applies at once");
            await("the next tick to resume it", mirror::playing);
            await("it to realign", () -> aligned(clock, mirror, ALIGN_MS));
        }

        @Test
        @DisplayName("A local stop is undone: the mirror starts again and catches up")
        void localStopIsUndone() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer mirror = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            assertTrue(clock.seek(30_000L));
            await("the mirror to catch up", () -> mirror.playing() && aligned(clock, mirror, ALIGN_MS));

            assertTrue(mirror.stop());
            await("the next tick to start it again", mirror::playing);
            await("it to catch up again", () -> aligned(clock, mirror, ALIGN_MS));
        }

        @Test
        @DisplayName("A local seek, speed and repeat are overridden by the clock's")
        void localSeekSpeedRepeatAreUndone() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer mirror = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            await("the mirror to play", mirror::playing);

            assertTrue(mirror.seek(40_000L));
            assertTrue(mirror.speed(2f));
            assertTrue(mirror.repeat(true));
            await("the clock's speed and repeat to win", () -> mirror.speed() == 1f && !mirror.repeat());
            await("the drift correction to pull it back", () -> aligned(clock, mirror, ALIGN_MS));
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("A mirror follows from the first snapshot until it is released")
        void followingSpansFirstSnapshotToRelease() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer mirror = FollowerPlayerStateTest.this.player();
            assertFalse(mirror.following(), "nothing heard from the clock yet");
            assertNull(mirror.authority());
            assertEquals(0L, mirror.authorityTime());
            assertEquals(0L, mirror.drift());

            FollowerPlayerStateTest.this.network.join(mirror);
            await("the first snapshot", mirror::following);
            assertEquals(clock.revision(), mirror.authority().revision());
            assertEquals(60_000L, mirror.authority().duration());

            mirror.release();
            assertFalse(mirror.following(), "a released mirror follows nothing");
            assertNull(mirror.authority());
            assertEquals(0L, mirror.authorityTime());
            // A SNAPSHOT STILL IN FLIGHT NEVER REVIVES IT
            mirror.sync(clock.snapshot().toBytes());
            assertFalse(mirror.following());
            assertFalse(mirror.start(), "a released mirror never runs again");
        }

        @Test
        @DisplayName("Releasing the clock bumps its revision to a STOPPED snapshot that stops every mirror")
        void releasedClockStopsMirrors() {
            final ServerMediaPlayer clock = FollowerPlayerStateTest.this.clock(60_000L);
            final ServerMediaPlayer a = FollowerPlayerStateTest.this.mirror();
            final ServerMediaPlayer b = FollowerPlayerStateTest.this.mirror();
            assertTrue(clock.start());
            await("both to play", () -> a.playing() && b.playing());

            final int revision = clock.revision();
            clock.release();
            assertNotEquals(revision, clock.revision(), "the release is a mutation the host must send");
            final MediaPlayer.PlayerState last = clock.snapshot();
            assertEquals(Status.STOPPED, last.status());
            assertEquals(clock.revision(), last.revision());
            await("both to stop", () -> a.stopped() && b.stopped());
            assertTrue(a.following() && b.following(), "the mirrors keep following the stopped session");
        }
    }

    // IN-MEMORY TRANSPORT ON ITS OWN DAEMON THREAD: EVERY PERIOD IT SENDS THE CLOCK'S SNAPSHOT AS BYTES WHEN THE REVISION
    // MOVED OR THE RESEND IS DUE, SO NOTHING IS EVER APPLIED ON THE TEST'S STACK
    private static final class Network {
        private final ScheduledExecutorService net = Executors.newSingleThreadScheduledExecutor(
                ThreadTool.factory("FollowerPlayerStateTest-net", Thread.NORM_PRIORITY));
        private final List<ServerMediaPlayer> mirrors = new CopyOnWriteArrayList<>();
        private final ServerMediaPlayer clock;
        private volatile Throwable failure;
        // TOUCHED ONLY FROM THE NETWORK THREAD
        private int sent;
        private long sentNanos;

        Network(final ServerMediaPlayer clock) {
            this.clock = clock;
            this.sentNanos = System.nanoTime() - RESEND_NS; // THE FIRST POLL ALWAYS SENDS
            this.net.scheduleAtFixedRate(this::poll, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
        }

        void join(final ServerMediaPlayer mirror) {
            this.mirrors.add(mirror);
        }

        void close() throws InterruptedException {
            this.net.shutdownNow();
            this.net.awaitTermination(1L, TimeUnit.SECONDS);
        }

        private void poll() {
            try {
                final long now = System.nanoTime();
                if (this.clock.revision() == this.sent && now - this.sentNanos < RESEND_NS) return;
                final MediaPlayer.PlayerState snapshot = this.clock.snapshot();
                final byte[] wire = new byte[MediaPlayer.PlayerState.BYTES];
                snapshot.toBytes().get(wire);
                // EVERY MIRROR DECODES ITS OWN PACKET, AS EACH CLIENT WOULD
                for (final ServerMediaPlayer mirror: this.mirrors) mirror.sync(ByteBuffer.wrap(wire.clone()));
                this.sent = snapshot.revision();
                this.sentNanos = now;
            } catch (final Throwable t) {
                // A THROWING TASK WOULD SILENTLY CANCEL THE SCHEDULE; KEEP THE FIRST FAILURE FOR THE TEARDOWN
                if (this.failure == null) this.failure = t;
            }
        }
    }
}
