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
 * blocked frame producer or packet consumer, a reset serves a frame queue again, and a packet queue takes every packet
 * without waiting, measures what it holds and passes the end of the input in line with its packets.
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
    @DisplayName("A packet queue takes every packet without waiting, weighs and measures what it holds within bounds, passes the end of the input in line, and an abort wakes its consumer with nothing")
    void measuresEndMarksAndAbort() throws Exception {
        final PacketQueue queue = new PacketQueue();
        final AVPacket packet = avcodec.av_packet_alloc();
        assertNotNull(packet, "the packet must allocate");
        Thread worker = null;
        try {
            // NO CAP: THE READER ASKS holds() BEFORE IT READS MORE, SO A PUT NEVER WAITS NOR FAILS. IT TAKES THE REFERENCE AND
            // LEAVES THE CALLER'S PACKET BLANK FOR THE NEXT READ
            assertTrue(queue.put(read(packet, 1), 1, 1_000L, 100_000L), "an empty queue takes the packet");
            assertEquals(0, packet.size(), "the queue took the reference of the packet");
            assertTrue(queue.put(read(packet, 2), 1, 1_100L, 100_000L), "a queue with packets takes one more");
            final long weight = 2 * (8 + PacketQueue.ENTRY_BYTES);
            assertEquals(weight, queue.byteSize(), "every packet weighs its payload and the overhead of its entry");
            assertEquals(200L, queue.durationMs(), "the media queued is the sum of the packet durations");
            assertEquals(1_100L, queue.lastMs());
            assertTrue(queue.holds(weight, Integer.MAX_VALUE, Long.MAX_VALUE), "the bytes alone are enough");
            assertTrue(queue.holds(Long.MAX_VALUE, 1, 200L), "200 ms in more than one packet are enough");
            assertFalse(queue.holds(Long.MAX_VALUE, 2, 200L), "two packets are not more than two");
            assertFalse(queue.holds(weight + 1, 1, 201L), "neither the bytes nor the media are there");

            // THE END MARK CLOSES THE QUEUE BEHIND ITS PACKETS AND COMES OUT LAST, WITH ITS GENERATION
            queue.finish(1);
            assertTrue(queue.closed(), "nothing comes behind the end mark");
            assertFalse(queue.empty(), "the packets before the mark still wait");
            for (final long pts: new long[] { 1, 2 }) {
                final PacketQueue.Entry entry = queue.get();
                try {
                    assertNotNull(entry, "the queued packet must come out");
                    assertEquals(pts, entry.packet().pts(), "the packets come out in order");
                    assertEquals(1, entry.generation(), "every packet carries the generation it was read in");
                } finally { avcodec.av_packet_free(entry.packet()); }
            }
            assertTrue(queue.empty(), "an end mark is no packet");
            final PacketQueue.Entry end = queue.get();
            assertNotNull(end, "the end mark must come out");
            assertNull(end.packet(), "the end mark carries no packet");
            assertEquals(1, end.generation());
            assertFalse(queue.closed(), "the mark left the queue");

            // WITHOUT DURATIONS THE MEDIA QUEUED IS THE DISTANCE BETWEEN THE FIRST AND THE LAST PACKET, AT LEAST 1 MS WHILE ONE WAITS
            assertTrue(queue.put(read(packet, 3), 2, Long.MIN_VALUE, 0L));
            assertEquals(1L, queue.durationMs(), "a packet that says nothing still counts");
            queue.flush();
            assertTrue(queue.put(read(packet, 4), 2, 5_000L, 0L));
            assertTrue(queue.put(read(packet, 5), 2, 5_500L, 0L));
            assertEquals(500L, queue.durationMs());
            queue.flush();

            // A FLOOD OF EMPTY PACKETS STILL WEIGHS, AND A BOGUS DURATION COUNTS FOR NO MORE THAN THE LIMIT OF ONE PACKET
            for (int i = 0; i < 4; i++) {
                avcodec.av_packet_unref(packet);
                assertEquals(0, avcodec.av_new_packet(packet, 0), "an empty packet must allocate");
                assertTrue(queue.put(packet, 2, Long.MIN_VALUE, 0L));
            }
            assertEquals(4 * PacketQueue.ENTRY_BYTES, queue.byteSize(), "empty packets reach the byte cap of their reader too");
            queue.flush();
            assertTrue(queue.put(read(packet, 6), 2, 0L, Long.MAX_VALUE));
            assertEquals(PacketQueue.PACKET_LIMIT_US / 1_000L, queue.durationMs(), "a packet never counts for more than its limit");
            queue.flush();
            assertTrue(queue.empty(), "a flush drops every packet");
            assertEquals(0L, queue.byteSize());
            assertEquals(0L, queue.durationMs());
            assertEquals(Long.MIN_VALUE, queue.lastMs());

            // A BLOCKED CONSUMER: AN ABORT WAKES IT WITH NOTHING, AND THE QUEUE REFUSES EVERY PUT FROM THEN ON
            final FutureTask<PacketQueue.Entry> consumer = new FutureTask<>(queue::get);
            worker = new Thread(consumer, "PacketQueue-Test-Consumer");
            worker.start();
            final Thread waiting = worker;
            assertTrue(PlayerWait.awaitCondition(() -> waiting.getState() == Thread.State.WAITING, WAIT_MS), "the consumer must block on the empty queue");
            queue.abort();
            assertNull(consumer.get(WAIT_MS, TimeUnit.MILLISECONDS), "an aborted queue hands out nothing");
            worker.join(WAIT_MS);
            assertFalse(queue.put(read(packet, 7), 3, 0L, 0L), "an aborted queue refuses the packet");
            assertEquals(8, packet.size(), "a refused packet stays with its caller");
            queue.finish(3);
            assertFalse(queue.closed(), "an aborted queue takes no end mark");
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

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // FILLS packet AS A READ WOULD: 8 FRESH BYTES WITH THEIR OWN REFERENCE, STAMPED WITH pts
    private static AVPacket read(final AVPacket packet, final long pts) {
        avcodec.av_packet_unref(packet);
        assertEquals(0, avcodec.av_new_packet(packet, 8), "the packet must take 8 bytes");
        packet.pts(pts);
        return packet;
    }
}
