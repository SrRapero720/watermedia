package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies that status listeners hear transitions in order under concurrent drivers and never run under a player lock. */
@DisplayName("Status delivery")
public class StatusDeliveryTest {

    private static final int ROUNDS = 50_000;
    private static final int TOGGLERS = 4;
    private static final long JOIN_MS = 2_000L;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A HOST THAT RELEASES PLAYERS FROM A BACKGROUND THREAD WHILE ITS STOPPED LISTENER WAITS FOR THAT THREAD DEADLOCKS WHEN
    // THE LISTENER RUNS UNDER THE PLAYER'S RELEASE LOCK; THE JOIN TIMES OUT INSTEAD OF HANGING THE TEST
    private static void assertReleaseIsLockFree(final Path media) {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(media));
        assertTrue(mrl.await(3_000L));
        final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
        final MediaPlayer player = PlayerAPI.player(mrl, () -> gfx, () -> null);
        final AtomicBoolean ran = new AtomicBoolean();
        final AtomicBoolean joined = new AtomicBoolean();
        // REGISTERED BEFORE start(): NEITHER FIXTURE ENDS, SO ONLY THE RELEASE'S STOPPED CONSUMES IT
        player.onStatus((p, previous, current) -> {
            if (current != Status.STOPPED) return;
            ran.set(true);
            final Thread other = new Thread(p::release, "second-release");
            other.start();
            try { other.join(JOIN_MS); }
            catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
            joined.set(!other.isAlive());
        });
        assertTrue(player.start());
        assertTrue(PlayerWait.awaitStatus(player, 15_000L, Status.PLAYING, Status.PAUSED));
        player.release();
        assertTrue(ran.get(), "the release published STOPPED to the listener");
        assertTrue(joined.get(), "the second release waited for the lock the first one held while the listener ran");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("Concurrent pauses and resumes are heard in the order the statuses changed")
        void transitionsArriveInOrder() throws Exception {
            final ServerMediaPlayer player = PlayerAPI.serverPlayer();
            final AtomicReference<String> broken = new AtomicReference<>();
            final AtomicReference<Status> last = new AtomicReference<>();
            final AtomicInteger heard = new AtomicInteger();
            player.onStatus((p, previous, current) -> {
                // EACH TRANSITION MUST START WHERE THE ONE HEARD BEFORE IT ENDED
                heard.incrementAndGet();
                final Status before = last.getAndSet(current);
                if (before != null && before != previous) broken.compareAndSet(null, previous + "->" + current + " heard after " + before);
            });
            try {
                assertTrue(player.start());
                final CountDownLatch go = new CountDownLatch(1);
                final Runnable toggler = () -> {
                    try { go.await(); }
                    catch (final InterruptedException e) { return; }
                    for (int i = 0; i < ROUNDS; i++) player.pause(i % 2 == 0);
                };
                final Thread[] togglers = new Thread[TOGGLERS];
                for (int i = 0; i < togglers.length; i++) {
                    togglers[i] = new Thread(toggler, "toggler-" + i);
                    togglers[i].start();
                }
                go.countDown();
                for (final Thread toggling: togglers) toggling.join();
                assertTrue(heard.get() > ROUNDS, "the togglers raced through real transitions: " + heard.get());
                assertNull(broken.get(), "out-of-order transition");
                assertEquals(player.status(), last.get(), "the last transition heard is the current status");
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Release outside the player locks")
    class LockFreeRelease {

        @Test
        @DisplayName("An image player's release lets a STOPPED listener wait for another thread releasing it")
        void imageReleaseListenerIsLockFree() {
            assertReleaseIsLockFree(Fixtures.PNG_STATIC);
        }

        @Test
        @DisplayName("A video player's release lets a STOPPED listener wait for another thread releasing it")
        void videoReleaseListenerIsLockFree() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping");
            assertReleaseIsLockFree(Fixtures.MP4_H264);
        }
    }
}
