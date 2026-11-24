package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link FrameQueue} and {@link PacketQueue} lifecycles the FFmpeg pipeline relies on: an abort wakes a
 * blocked frame producer or packet consumer and a reset serves them again, a packet queue refuses a put without ever
 * waiting, a finish wakes its consumer with a clean end of file that an abort never reports, and a flush advances the serial.
 */
@DisplayName("FFmpeg pipeline queues")
public class QueueLifecycleTest {
    private static final long WAIT_MS = 2_000L;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
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
            assertNotNull(queue.peekWritable(), "an empty queue grants its slot");
            queue.push();
            final FutureTask<FrameQueue.Slot> producer = new FutureTask<>(queue::peekWritable);
            worker = new Thread(producer, "FrameQueue-Test-Producer");
            worker.start();
            final Thread waiting = worker;
            assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, WAIT_MS), "the producer must block on the full queue");
            queue.abort();
            assertNull(producer.get(WAIT_MS, TimeUnit.MILLISECONDS), "an aborted queue grants no slot");
            worker.join(WAIT_MS);
            // A RESET QUEUE SERVES WRITERS AND READERS AGAIN; AN ABORTED ONE WOULD ANSWER NULL TO BOTH
            queue.reset();
            assertNotNull(queue.peekWritable(), "a reset queue grants its slot again");
            queue.push();
            assertNotNull(queue.peek(), "a reset queue serves its reader again");
        } finally {
            queue.abort();
            if (worker != null) {
                worker.interrupt();
                worker.join(WAIT_MS);
                assertFalse(worker.isAlive(), "the producer thread must end");
            }
            queue.free();
        }
    }

    @Test
    @DisplayName("A full, aborted or finished packet queue refuses a put without waiting, abort and finish wake its consumer, only a finish reports the end of file, and a flush advances the serial")
    void refusalsWakeupsAndFlushSerial() throws Exception {
        // ONE 8-BYTE PACKET FILLS AN 8-BYTE QUEUE
        final PacketQueue queue = new PacketQueue(8);
        final AVPacket packet = avcodec.av_packet_alloc();
        assertNotNull(packet, "the packet must allocate");
        assertEquals(0, avcodec.av_new_packet(packet, 8), "the packet must take 8 bytes");
        Thread worker = null;
        try {
            // A FULL QUEUE REFUSES THE NEXT PACKET AT ONCE; AN ABORT, THEN A FINISH, REFUSE EVERY PUT UNTIL A RESET
            assertTrue(queue.tryPut(packet), "an empty queue takes the packet");
            assertFalse(queue.tryPut(packet), "a full queue refuses the packet");
            avcodec.av_packet_free(queue.get(new int[1]));
            for (final boolean finish: new boolean[] { false, true }) {
                if (finish) queue.finish();
                else queue.abort();
                assertFalse(queue.tryPut(packet), (finish ? "a finished" : "an aborted") + " queue refuses the packet");
                queue.reset();
            }

            // A FLUSH ADVANCES THE SERIAL, AND THE PACKETS PUT AFTER IT CARRY THE NEW ONE
            final int before = queue.serial();
            assertTrue(queue.tryPut(packet), "a reset queue takes the packet");
            queue.flush();
            assertEquals(before + 1, queue.serial(), "a flush advances the serial");
            packet.pts(123);
            assertTrue(queue.tryPut(packet), "a flushed queue takes the packet");
            final int[] serial = new int[1];
            final AVPacket current = queue.get(serial);
            try {
                assertNotNull(current, "the queued packet must come out");
                assertEquals(123, current.pts(), "the packet put after the flush comes out, not the flushed one");
                assertEquals(queue.serial(), serial[0], "the packet carries the serial after the flush");
            } finally { avcodec.av_packet_free(current); }

            // A BLOCKED CONSUMER: A FINISH WAKES IT WITH THE END OF FILE, AN ABORT WITH A TEARDOWN THAT NEVER PASSES FOR ONE
            for (final boolean finish: new boolean[] { true, false }) {
                final FutureTask<AVPacket> consumer = new FutureTask<>(() -> queue.get(new int[1]));
                worker = new Thread(consumer, "PacketQueue-Test-Consumer");
                worker.start();
                final Thread waiting = worker;
                assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, WAIT_MS), "the consumer must block on the empty queue");
                if (finish) queue.finish();
                else queue.abort();
                assertNull(consumer.get(WAIT_MS, TimeUnit.MILLISECONDS), (finish ? "a finished" : "an aborted") + " empty queue hands out no packet");
                worker.join(WAIT_MS);
                assertEquals(finish, queue.endOfFile(), finish ? "a finish is the end of file" : "an abort is no end of file");
                // THE DEMUXER FINISHES THE QUEUES IT LEAVES EVEN AFTER stop() ABORTED THEM
                queue.finish();
                assertEquals(finish, queue.endOfFile(), finish ? "a finish is the end of file" : "a finish after an abort is no end of file");
                queue.reset();
            }
        } finally {
            queue.abort();
            if (worker != null) {
                worker.interrupt();
                worker.join(WAIT_MS);
                assertFalse(worker.isAlive(), "the queue thread must end");
            }
            queue.free();
            avcodec.av_packet_free(packet);
        }
    }
}
