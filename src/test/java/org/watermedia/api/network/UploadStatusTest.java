package org.watermedia.api.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the terminal-state rules of {@link NetworkAPI.UploadStatus}: the first outcome wins and cancel ends a pending upload. */
@DisplayName("UploadStatus")
public class UploadStatusTest {

    @Nested
    @DisplayName("Terminal states")
    class TerminalStateTests {
        @Test
        @DisplayName("A failure wins against late completion and progress")
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
        @DisplayName("A completed upload cannot be changed by a late failure or cancel and names its remote address")
        void completedUploadCannotBeChangedByLateFailure() {
            final var status = new NetworkAPI.UploadStatus(100);
            status.complete("stored123");
            status.fail("stopped");
            status.cancel();
            status.uploadedBytes(0);
            assertTrue(status.completed());
            assertFalse(status.failed());
            assertEquals("stored123", status.id());
            assertEquals(URI.create("water://remote/stored123"), status.uri());
            assertEquals(100, status.uploadedBytes());
            assertNull(status.error());
        }

        @Test
        @DisplayName("cancel ends a pending upload as a failure and interrupts its worker")
        void cancelEndsPendingUpload() throws InterruptedException {
            final var status = new NetworkAPI.UploadStatus(100);
            final boolean[] interrupted = { false };
            final Thread worker = new Thread(() -> {
                status.worker = Thread.currentThread();
                try { Thread.sleep(5_000L); }
                catch (final InterruptedException e) { interrupted[0] = true; }
            });
            worker.start();
            while (status.worker == null) Thread.onSpinWait();
            status.cancel();
            worker.join(2_000L);
            assertTrue(interrupted[0], "the worker is interrupted out of its transfer");
            assertTrue(status.failed());
            assertEquals("Cancelled", status.error());
            assertNull(status.uri());
            status.complete("late");
            assertFalse(status.completed(), "a cancelled upload never completes");
        }
    }
}
