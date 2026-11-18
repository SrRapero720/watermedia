package org.watermedia.api.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the terminal states of {@link NetworkAPI.UploadStatus}: the first outcome wins and freezes the progress, and cancel fails a pending upload and interrupts its worker. */
@DisplayName("UploadStatus")
public class UploadStatusTest {
    private static final long WAIT_MS = 2_000L;

    @Test
    @DisplayName("A failure wins against a late completion and freezes the progress")
    void failureWinsAgainstLateCompletionAndProgress() {
        final var status = new NetworkAPI.UploadStatus(100);
        status.uploadedBytes(20);
        status.speed(5);
        status.fail("stopped");
        status.uploadedBytes(80);
        status.speed(40);
        status.complete("late");
        assertTrue(status.failed());
        assertFalse(status.completed());
        assertEquals("stopped", status.error());
        assertNull(status.id());
        assertNull(status.uri());
        assertEquals(20, status.uploadedBytes());
        assertEquals(5, status.speed());
    }

    @Test
    @DisplayName("A completion wins against a late failure, cancel or progress and names its water://remote address")
    void completionWinsAgainstLateFailureAndCancel() {
        final var status = new NetworkAPI.UploadStatus(100);
        status.complete("stored123");
        status.fail("stopped");
        status.cancel();
        status.uploadedBytes(0);
        assertTrue(status.completed());
        assertFalse(status.failed());
        assertEquals("stored123", status.id());
        assertEquals(URI.create("water://remote/stored123"), status.uri());
        assertEquals(100, status.uploadedBytes(), "a completed upload counts every byte");
        assertNull(status.error());
    }

    @Test
    @DisplayName("cancel ends a pending upload as a failure that never completes and interrupts its worker")
    void cancelEndsPendingUpload() throws InterruptedException {
        final var status = new NetworkAPI.UploadStatus(100);
        final boolean[] interrupted = { false };
        // STANDS IN FOR THE UPLOAD WORKER: IT REGISTERS ITSELF AND BLOCKS LIKE A TRANSFER
        final Thread worker = new Thread(() -> {
            status.worker = Thread.currentThread();
            try { Thread.sleep(5_000L); }
            catch (final InterruptedException e) { interrupted[0] = true; }
        });
        worker.start();
        try {
            assertTrue(PlayerWait.awaitCondition(() -> status.worker != null, WAIT_MS), "the worker registered itself");
            status.cancel();
            worker.join(WAIT_MS);
            assertTrue(interrupted[0], "the worker is interrupted out of its transfer");
            assertTrue(status.failed());
            assertEquals("Cancelled", status.error());
            assertNull(status.uri());
            status.complete("late");
            assertFalse(status.completed(), "a cancelled upload never completes");
        } finally {
            worker.interrupt();
        }
    }
}
