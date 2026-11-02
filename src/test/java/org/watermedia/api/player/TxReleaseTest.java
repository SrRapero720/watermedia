package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that releasing a {@link TxMediaPlayer} never waits for a queued preparation or a held download. */
@DisplayName("TxMediaPlayer release")
public class TxReleaseTest {

    private static final long RELEASE_RETURN_MS = 2_000L;
    private static final long WAIT_MS = 3_000L;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll static void boot() { MediaBootstrap.client(); }

    @Test
    @DisplayName("Releasing a preparation still queued behind a busy pool returns at once and frees the graphics")
    void cancellingQueuedPreparationDoesNotWaitForANonexistentWorker() throws Exception {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC));
        assertTrue(mrl.await(WAIT_MS));
        // THE SHARED PREPARATION POOL HAS NO PUBLIC HANDLE, AND FILLING EVERY WORKER IS THE PRECONDITION ITSELF; A RENAMED
        // FIELD FAILS HERE WITH NoSuchFieldException INSTEAD OF SILENTLY TESTING NOTHING
        final var field = TxMediaPlayer.class.getDeclaredField("SINGLE_FRAME_POOL");
        field.setAccessible(true);
        final ThreadPoolExecutor pool = (ThreadPoolExecutor) field.get(null);
        final CountDownLatch entered = new CountDownLatch(pool.getCorePoolSize());
        final CountDownLatch resume = new CountDownLatch(1);
        final List<Future<?>> blockers = new ArrayList<>();
        final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
        try {
            for (int i = 0; i < pool.getCorePoolSize(); i++) {
                blockers.add(pool.submit(() -> {
                    entered.countDown();
                    try { resume.await(); }
                    catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(player.start());
            final long before = System.nanoTime();
            player.release();
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
            assertTrue(elapsedMs < RELEASE_RETURN_MS, "release() must not wait for a worker that never started, it took " + elapsedMs + " ms");
            // THE ENGINE IS FREED ONLY ONCE NO PREPARATION OF THE PLAYER IS RUNNING
            assertTrue(gfx.released());
            assertFalse(player.start());
        } finally {
            resume.countDown();
            for (final Future<?> blocker: blockers) blocker.get(WAIT_MS, TimeUnit.MILLISECONDS);
            player.release();
        }
        // THE QUEUED PREPARATION RAN AFTER THE BLOCKERS LEFT AND FOUND THE PLAYER RELEASED
        assertEquals(0, gfx.uploadCount());
        assertNull(gfx.format());
    }

    @Test
    @DisplayName("A preparation waiting on a held download releases at once and frees the graphics")
    void heldDownloadDoesNotStallRelease() throws Exception {
        final byte[] image = "P6\n1 1\n255\nRGB".getBytes(StandardCharsets.US_ASCII);
        final int previousTimeout = WaterMediaConfig.network.timeout;
        WaterMediaConfig.network.timeout = 20_000;
        try (final Origin origin = Origin.of(image, "image/x-portable-pixmap")) {
            final MRL mrl = PlayerAPI.mrl(origin.uri(".ppm"));
            assertTrue(mrl.await(WAIT_MS));
            assertEquals(MRL.Status.LOADED, mrl.status());
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine();
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
            try {
                origin.hold();
                assertTrue(player.start());
                assertTrue(origin.awaitEntered(WAIT_MS));
                // THE SOCKET READ RUNS ON A CACHE WORKER; THE RELEASE MUST FIND THE PREPARATION WAITING FOR IT
                assertTrue(PlayerWait.awaitCondition(() -> Thread.getAllStackTraces().entrySet().stream()
                        .anyMatch(thread -> thread.getKey().getName().startsWith("TxPlayer-SingleFrame") && Arrays.stream(thread.getValue())
                                .anyMatch(frame -> frame.getClassName().equals(NetworkCache.class.getName()))), WAIT_MS),
                        "The preparation did not wait for its download");
                final long before = System.nanoTime();
                player.release();
                final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
                assertTrue(elapsedMs < RELEASE_RETURN_MS, "release() waited " + elapsedMs + " ms for the held download");
                assertTrue(gfx.released(), "the preparation left with the release");
                assertEquals(0, gfx.uploadCount());
                assertFalse(player.start());
            } finally {
                origin.release();
                player.release();
            }
        } finally {
            WaterMediaConfig.network.timeout = previousTimeout;
        }
    }
}
