package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ThreadTool}: one name counter shared by thread, factory and workerFactory, daemon threads at a
 * clamped priority, and the owned-worker mark that covers worker tasks, nested runs included, and no other thread.
 */
@DisplayName("ThreadTool")
public class ThreadToolTest {

    private static final long TIMEOUT_MS = 2_000L;

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Naming")
    class Naming {

        @Test
        @DisplayName("thread, factory and workerFactory share one counter per name and start daemon threads at normal priority")
        void sharedCounter() throws InterruptedException {
            // THE COUNTER LIVES AS LONG AS THE JVM, SO A NAME OF ITS OWN STARTS AT 0 ON ANY RUN
            final String name = "ThreadToolTest-shared-" + System.nanoTime();
            final Thread started = ThreadTool.thread(name, () -> {});
            final List<String> names = List.of(started.getName(),
                    ThreadTool.factory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName(),
                    ThreadTool.workerFactory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName(),
                    ThreadTool.factory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName());
            started.join(TIMEOUT_MS);
            assertEquals(List.of(name + "-0", name + "-1", name + "-2", name + "-3"), names);
            assertTrue(started.isDaemon(), "thread starts a daemon");
            assertEquals(Thread.NORM_PRIORITY, started.getPriority());
        }

        @Test
        @DisplayName("factory clamps the priority into the thread range")
        void factoryClampsPriority() {
            assertEquals(Thread.MIN_PRIORITY, ThreadTool.factory("ThreadToolTest-low", Thread.MIN_PRIORITY - 5).newThread(() -> {}).getPriority());
            assertEquals(Thread.MAX_PRIORITY, ThreadTool.factory("ThreadToolTest-high", Thread.MAX_PRIORITY + 1).newThread(() -> {}).getPriority());
        }
    }

    @Nested
    @DisplayName("Worker mark")
    class WorkerMark {

        @Test
        @DisplayName("the worker mark covers worker tasks, nested runs included, without marking unrelated threads")
        void markCoversWorkerTasksOnly() throws Exception {
            assertFalse(ThreadTool.workerThread(), "the test thread carries no mark");
            final ThreadFactory factory = ThreadTool.workerFactory("ThreadToolTest-owned", Thread.NORM_PRIORITY);
            final FutureTask<Boolean> task = new FutureTask<>(ThreadTool::workerThread);
            final Thread worker = factory.newThread(task);
            worker.start();
            assertTrue(task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), "a worker thread carries the mark");
            worker.join(TIMEOUT_MS);
            assertFalse(worker.isAlive(), "the worker ended");
            assertFalse(ThreadTool.workerThread(), "the mark never leaks to the starting thread");

            // run() EXECUTES ON THIS THREAD: THE MARK HOLDS INSIDE, SURVIVES A NESTED RUN AND IS GONE AFTERWARDS
            factory.newThread(() -> {
                assertTrue(ThreadTool.workerThread(), "inside run()");
                factory.newThread(() -> assertTrue(ThreadTool.workerThread(), "inside a nested run()")).run();
                assertTrue(ThreadTool.workerThread(), "after a nested run()");
            }).run();
            assertFalse(ThreadTool.workerThread(), "after run()");

            final FutureTask<Boolean> ordinary = new FutureTask<>(ThreadTool::workerThread);
            final Thread host = ThreadTool.factory("ThreadToolTest-host", Thread.NORM_PRIORITY).newThread(ordinary);
            host.start();
            assertFalse(ordinary.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), "a factory thread carries no mark");
            host.join(TIMEOUT_MS);
            final FutureTask<Boolean> plain = new FutureTask<>(ThreadTool::workerThread);
            final Thread started = ThreadTool.thread("ThreadToolTest-plain", plain);
            assertFalse(plain.get(TIMEOUT_MS, TimeUnit.MILLISECONDS), "a started thread carries no mark");
            started.join(TIMEOUT_MS);
        }
    }
}
