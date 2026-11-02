package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link FrameQueue} and {@link PacketQueue} lifecycles the FFmpeg pipeline relies on: invalid capacities
 * fail before native allocation, abort and finish wake every blocked producer or consumer, and a flush advances the serial.
 */
@DisplayName("FFmpeg pipeline queues")
public class QueueLifecycleTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Invalid capacities fail before any native slot is allocated")
        void invalidCapacitiesFailBeforeAllocatingNativeSlots() {
            assertThrows(IllegalArgumentException.class, () -> new FrameQueue(0));
            assertThrows(IllegalArgumentException.class, () -> new FrameQueue(-1));
            assertThrows(IllegalArgumentException.class, () -> new PacketQueue(0));
            assertThrows(IllegalArgumentException.class, () -> new PacketQueue(-1));
        }
    }

    @Nested
    @DisplayName("Blocked producers and consumers")
    class Wakeups {

        @BeforeEach
        void natives() {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
        }

        @Test
        @DisplayName("Aborting a full frame queue wakes its blocked producer and a reset serves readers again")
        void abortWakesFrameProducers() throws Exception {
            final FrameQueue queue = new FrameQueue(1);
            Thread worker = null;
            try {
                assertNotNull(queue.peekWritable());
                queue.push();
                final FutureTask<FrameQueue.Slot> producer = new FutureTask<>(queue::peekWritable);
                worker = new Thread(producer, "FrameQueue-Test-Producer");
                worker.start();
                final Thread waitingProducer = worker;
                assertTrue(PlayerWait.awaitCondition(() -> waitingProducer.getState() == Thread.State.WAITING, 2_000));
                queue.abort();
                assertNull(producer.get(2, TimeUnit.SECONDS));
                worker.join(2_000);
                // A RESET QUEUE SERVES WRITERS AND READERS AGAIN; AN ABORTED ONE WOULD ANSWER NULL TO BOTH
                queue.reset();
                assertNotNull(queue.peekWritable());
                queue.push();
                assertNotNull(queue.peek());
            } finally {
                queue.abort();
                if (worker != null) { worker.interrupt(); worker.join(2_000); assertFalse(worker.isAlive()); }
                queue.free();
            }
        }

        @Test
        @DisplayName("Abort and finish wake packet producers and consumers, and a flush advances the serial")
        void abortAndFinishWakePacketWaitersAndFlushAdvancesSerial() throws Exception {
            final PacketQueue queue = new PacketQueue(8);
            final AVPacket packet = avcodec.av_packet_alloc();
            assertNotNull(packet);
            assertEquals(0, avcodec.av_new_packet(packet, 8));
            Thread worker = null;
            try {
                assertTrue(queue.put(packet));
                for (final boolean finish: new boolean[] { false, true }) {
                    final FutureTask<Boolean> producer = new FutureTask<>(() -> queue.put(packet));
                    worker = new Thread(producer, "PacketQueue-Test-Producer");
                    worker.start();
                    final Thread waiting = worker;
                    assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, 2_000));
                    if (finish) queue.finish(); else queue.abort();
                    assertFalse(producer.get(2, TimeUnit.SECONDS));
                    assertFalse(queue.tryPut(packet));
                    worker.join(2_000);
                    queue.reset();
                    if (!finish) assertTrue(queue.put(packet));
                }
                final int before = queue.serial();
                assertTrue(queue.put(packet));
                queue.flush();
                assertEquals(before + 1, queue.serial());
                packet.pts(123);
                assertTrue(queue.put(packet));
                final int[] serial = new int[1];
                final AVPacket current = queue.get(serial);
                try {
                    assertNotNull(current);
                    assertEquals(123, current.pts());
                    assertEquals(queue.serial(), serial[0]);
                } finally { avcodec.av_packet_free(current); }
                final FutureTask<AVPacket> consumer = new FutureTask<>(() -> queue.get(new int[1]));
                worker = new Thread(consumer, "PacketQueue-Test-Consumer");
                worker.start();
                final Thread waiting = worker;
                assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, 2_000));
                queue.abort();
                assertNull(consumer.get(2, TimeUnit.SECONDS));
            } finally {
                queue.abort();
                if (worker != null) { worker.interrupt(); worker.join(2_000); assertFalse(worker.isAlive()); }
                queue.free();
                avcodec.av_packet_free(packet);
            }
        }
    }
}
