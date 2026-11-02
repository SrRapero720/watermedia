package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;

import java.util.ArrayDeque;

/**
 * Thread-safe queue of AVPackets between the demux thread and the decode threads.
 *
 * <p>Unlike {@link FrameQueue} (a fixed ring buffer), packets go in an {@link ArrayDeque}: they are small, their
 * count varies widely and {@code av_packet_clone} is cheap, so no slot is pre-allocated. Each {@link #flush()}
 * increments the serial, and a decode thread drops packets of an older serial after a seek. There is no count
 * limit, only a byte limit ({@code maxBytes}): {@link #put(AVPacket)} blocks above it until the decoder catches
 * up, which keeps network streams with large buffers from running out of memory.
 */
final class PacketQueue {
    private final ArrayDeque<Entry> packets = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile boolean aborted;
    private volatile boolean finished; // EOF: DRAIN REMAINING, THEN RETURN NULL
    private volatile int serial;
    private long totalBytes;
    private final long maxBytes;

    /** Creates a queue whose {@link #put(AVPacket)} blocks once {@code maxBytes} are queued; {@code Long.MAX_VALUE} lifts the limit. */
    public PacketQueue(final long maxBytes) {
        if (maxBytes <= 0) throw new IllegalArgumentException("Packet queue byte limit must be positive");
        this.maxBytes = maxBytes;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Enqueues a clone of the packet, blocking while the byte limit is reached (backpressure to the demux thread); the
     * caller keeps its packet. Returns false when the queue was aborted or finished, the wait was interrupted or the clone failed.
     */
    public boolean put(final AVPacket packet) {
        synchronized (this.lock) {
            while (this.totalBytes >= this.maxBytes && !this.aborted && !this.finished) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (this.aborted || this.finished) return false;

            // CLONE ONLY ONCE THE ENTRY WILL ACTUALLY BE ENQUEUED, SO A REJECTION ALLOCATES NOTHING NATIVE
            final AVPacket clone = avcodec.av_packet_clone(packet);
            if (clone == null) return false;
            this.packets.addLast(new Entry(clone, this.serial));
            this.totalBytes += clone.size();
            this.lock.notifyAll();
        }
        return true;
    }

    /**
     * Non-blocking put. Returns false immediately when the queue is full, aborted or finished, or the clone fails.
     * The caller's original packet is not consumed on failure.
     */
    public boolean tryPut(final AVPacket packet) {
        synchronized (this.lock) {
            // A DEMUX THREAD POLLING A SATURATED QUEUE MUST NOT PAY A NATIVE CLONE+FREE PER ATTEMPT
            if (this.totalBytes >= this.maxBytes || this.aborted || this.finished) return false;
            final AVPacket clone = avcodec.av_packet_clone(packet);
            if (clone == null) return false;
            this.packets.addLast(new Entry(clone, this.serial));
            this.totalBytes += clone.size();
            this.lock.notifyAll();
        }
        return true;
    }

    /**
     * Dequeues a packet the caller owns and frees with {@code av_packet_free}, blocking while empty; {@code serialOut[0]}
     * receives its serial. Returns null when the queue was aborted, finished and drained, or the wait was interrupted.
     */
    public AVPacket get(final int[] serialOut) {
        synchronized (this.lock) {
            while (this.packets.isEmpty() && !this.aborted && !this.finished) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            // FINISHED + EMPTY: EOF, NO MORE PACKETS COMING
            if (this.packets.isEmpty()) return null;

            final Entry entry = this.packets.pollFirst();
            this.totalBytes -= entry.packet().size();
            serialOut[0] = entry.serial();
            this.lock.notifyAll();
            return entry.packet();
        }
    }

    /** Discards every packet and increments the serial, so decode threads drop packets of the old one without decoding. */
    public void flush() {
        synchronized (this.lock) {
            while (!this.packets.isEmpty()) avcodec.av_packet_free(this.packets.pollFirst().packet());
            this.totalBytes = 0;
            this.serial++;
            this.lock.notifyAll();
        }
    }

    /** Current serial. Changes with each {@link #flush()}. */
    public int serial() { return this.serial; }

    /**
     * Returns whether a {@code null} from {@link #get(int[])} means a clean end of stream ({@link #finish()}) rather
     * than an {@link #abort()} teardown; decode threads drain the decoder's buffered frames only at a clean end.
     */
    public boolean endOfFile() { return this.finished && !this.aborted; }

    /** Total bytes enqueued. */
    public long byteSize() {
        synchronized (this.lock) { return this.totalBytes; }
    }

    /** Signals EOF: {@link #get(int[])} drains the pending packets, then returns null; unlike {@link #abort()} nothing is discarded. */
    public void finish() {
        synchronized (this.lock) {
            this.finished = true;
            this.lock.notifyAll();
        }
    }

    /** Unblocks readers and writers, which then get false or null until {@link #reset()}; pending packets stay owned until flush, reset or free. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** Flushes the queue and clears the abort and finish flags so it can be reused (e.g. on a quality switch). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
            this.finished = false;
        }
    }

    /** Frees the pending packet clones at final cleanup, the counterpart of {@link FrameQueue#free()}; the serial stays. */
    public void free() {
        synchronized (this.lock) {
            while (!this.packets.isEmpty()) avcodec.av_packet_free(this.packets.pollFirst().packet());
            this.totalBytes = 0;
            this.lock.notifyAll();
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // THE PACKET IS OWNED: CLONED IN put() AND FREED WHEN THE ENTRY LEAVES THE QUEUE
    private record Entry(AVPacket packet, int serial) {}
}
