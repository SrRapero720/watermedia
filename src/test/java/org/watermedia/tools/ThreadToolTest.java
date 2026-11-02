package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ThreadTool}'s naming (one counter per name across every creator), its daemon and
 * priority defaults, and the owned-worker mark of worker factories.
 */
@DisplayName("ThreadTool")
public class ThreadToolTest {

    @Nested
    @DisplayName("Naming")
    class NamingTests {

        @Test
        @DisplayName("thread, factory and workerFactory share one counter per name")
        void sharedCounter() throws InterruptedException {
            final String name = "ThreadToolTest-shared";
            final Set<String> names = new HashSet<>();
            final Thread started = ThreadTool.thread(name, () -> {});
            names.add(started.getName());
            names.add(ThreadTool.factory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName());
            names.add(ThreadTool.workerFactory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName());
            names.add(ThreadTool.factory(name, Thread.NORM_PRIORITY).newThread(() -> {}).getName());
            assertEquals(Set.of(name + "-0", name + "-1", name + "-2", name + "-3"), names);
            assertTrue(started.isDaemon());
            assertEquals(Thread.NORM_PRIORITY, started.getPriority());
            started.join(2_000);
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
    class WorkerTests {

        @Test
        @DisplayName("the worker mark covers callbacks without marking unrelated threads")
        void workerMarkerCoversCallbacksWithoutMarkingUnrelatedThreads() throws Exception {
            assertFalse(ThreadTool.workerThread());
            final var factory = ThreadTool.workerFactory("ThreadToolTest-owned", Thread.NORM_PRIORITY);
            final FutureTask<Boolean> task = new FutureTask<>(ThreadTool::workerThread);
            final Thread worker = factory.newThread(task);
            worker.start();
            assertTrue(task.get(2, TimeUnit.SECONDS));
            worker.join(2_000);
            assertFalse(worker.isAlive());
            assertFalse(ThreadTool.workerThread());
            factory.newThread(() -> {
                assertTrue(ThreadTool.workerThread());
                factory.newThread(() -> assertTrue(ThreadTool.workerThread())).run();
                assertTrue(ThreadTool.workerThread());
            }).run();
            assertFalse(ThreadTool.workerThread());
            final FutureTask<Boolean> ordinary = new FutureTask<>(ThreadTool::workerThread);
            final Thread host = ThreadTool.factory("ThreadToolTest-host", Thread.NORM_PRIORITY).newThread(ordinary);
            host.start();
            assertFalse(ordinary.get(2, TimeUnit.SECONDS));
            host.join(2_000);
            // ThreadTool.thread STARTS A HOST THREAD TOO
            final FutureTask<Boolean> plain = new FutureTask<>(ThreadTool::workerThread);
            final Thread started = ThreadTool.thread("ThreadToolTest-plain", plain);
            assertFalse(plain.get(2, TimeUnit.SECONDS));
            started.join(2_000);
        }
    }
}
