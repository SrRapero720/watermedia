package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.global.avcodec;

import java.util.ArrayDeque;

/**
 * Thread-safe queue of AVPackets between the reader of an input and the decode thread of one of its streams.
 *
 * <p>Unlike {@link FrameQueue} (a fixed ring buffer), packets go in an {@link ArrayDeque}: they are small, their
 * count varies widely and taking a packet's reference is cheap, so no slot is pre-allocated. Every packet carries the
 * generation of the seek it was read after, and the decode thread drops the ones a newer seek left behind. The queue
 * always takes a packet: its reader asks {@link #holds(long, int, long)} before it reads more, so a full queue never
 * blocks whoever feeds it. The end of the input travels in line, as an entry without a packet.
 */
final class PacketQueue {
    // EVERY ENTRY WEIGHS ITS PAYLOAD PLUS THE NATIVE AND JAVA OBJECTS AROUND IT, SO A FLOOD OF EMPTY PACKETS STILL REACHES THE BYTE CAP
    // OF ITS READER. NO PACKET COUNTS FOR MORE THAN 10 s, SO A BOGUS DURATION NEVER PASSES FOR A FULL QUEUE
    static final long ENTRY_BYTES = 256L;
    static final long PACKET_LIMIT_US = 10_000_000L;

    private final ArrayDeque<Entry> packets = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile boolean aborted;
    private long totalBytes;
    private long totalUs;
    private int count; // PACKETS, WITHOUT THE END MARKS

    /**
     * Enqueues the packet without ever waiting, taking its reference: the caller's packet is left blank, ready for the
     * next read. {@code tsMs} is where the packet sits in its stream ({@code Long.MIN_VALUE} when unknown) and
     * {@code durationUs} how long it plays. Returns false, the packet untouched, when the queue was aborted or out of memory.
     */
    public boolean put(final AVPacket packet, final int generation, final long tsMs, final long durationUs) {
        synchronized (this.lock) {
            if (this.aborted) return false;
            // A MOVE INSTEAD OF A CLONE: av_read_frame HANDS OUT REFERENCE-COUNTED PACKETS, SO NOTHING IS COPIED
            final AVPacket owned = avcodec.av_packet_alloc();
            if (owned == null) return false;
            avcodec.av_packet_move_ref(owned, packet);
            final long duration = Math.max(0L, Math.min(durationUs, PACKET_LIMIT_US));
            this.packets.addLast(new Entry(owned, generation, tsMs, duration));
            this.totalBytes += owned.size() + ENTRY_BYTES;
            this.totalUs += duration;
            this.count++;
            this.lock.notifyAll();
        }
        return true;
    }

    /** Marks the end of the input for {@code generation}: the decode thread drains its decoder when the mark comes out. */
    public void finish(final int generation) {
        synchronized (this.lock) {
            if (this.aborted) return;
            this.packets.addLast(new Entry(null, generation, Long.MIN_VALUE, 0L));
            this.lock.notifyAll();
        }
    }

    /**
     * Dequeues the next entry, blocking while empty. Its packet belongs to the caller, who frees it with
     * {@code av_packet_free}; an entry without one is the end of the input. Returns null when the queue was aborted
     * or the wait was interrupted.
     */
    public Entry get() {
        synchronized (this.lock) {
            while (this.packets.isEmpty() && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;

            final Entry entry = this.packets.pollFirst();
            if (entry.packet() != null) {
                this.totalBytes -= entry.packet().size() + ENTRY_BYTES;
                this.totalUs -= entry.durationUs();
                this.count--;
            }
            return entry;
        }
    }

    /** Discards every pending entry, as the reader does when it serves a seek. */
    public void flush() {
        synchronized (this.lock) {
            Entry entry;
            while ((entry = this.packets.pollFirst()) != null)
                if (entry.packet() != null) avcodec.av_packet_free(entry.packet());
            this.totalBytes = 0;
            this.totalUs = 0;
            this.count = 0;
        }
    }

    /** Returns whether the queue has enough to stop reading: {@code bytes} queued, or {@code ms} of media in more than {@code packets} packets. */
    public boolean holds(final long bytes, final int packets, final long ms) {
        synchronized (this.lock) {
            return this.totalBytes >= bytes || this.count > packets && this.durationMs() >= ms;
        }
    }

    /** Returns whether no packet is queued. */
    public boolean empty() {
        synchronized (this.lock) { return this.count == 0; }
    }

    /** Returns whether the end of the input closes the queue: nothing more comes behind what is queued until a seek. */
    public boolean closed() {
        synchronized (this.lock) {
            final Entry last = this.packets.peekLast();
            return last != null && last.packet() == null;
        }
    }

    /** Returns the bytes the queued packets weigh: their payloads plus a fixed overhead per packet. */
    public long byteSize() {
        synchronized (this.lock) { return this.totalBytes; }
    }

    /**
     * Returns the media queued in milliseconds: the durations of its packets, else the distance between the first and
     * the last one when they carry none, and at least 1 while a packet is queued.
     */
    public long durationMs() {
        synchronized (this.lock) {
            if (this.count == 0) return 0L;
            if (this.totalUs > 0) return Math.max(1L, this.totalUs / 1_000L);
            // PACKETS ALWAYS COME BEFORE THE END MARK OF THEIR GENERATION, SO THE HEAD IS ONE
            final long first = this.packets.peekFirst().tsMs();
            final long last = this.lastMs();
            return first == Long.MIN_VALUE || last == Long.MIN_VALUE ? 1L : Math.max(1L, last - first);
        }
    }

    /** Returns where the last queued packet sits in its stream in milliseconds, or {@code Long.MIN_VALUE} when it does not say or nothing is queued. */
    public long lastMs() {
        synchronized (this.lock) {
            // AT MOST ONE END MARK CLOSES THE QUEUE
            final var entries = this.packets.descendingIterator();
            while (entries.hasNext()) {
                final Entry entry = entries.next();
                if (entry.packet() != null) return entry.tsMs();
            }
            return Long.MIN_VALUE;
        }
    }

    /** Unblocks the reader for good: puts get false and gets null, and pending packets stay owned until {@link #free()}. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** Frees the pending packets at final cleanup, the counterpart of {@link FrameQueue#free()}. */
    public void free() {
        this.flush();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // THE PACKET IS OWNED: MOVED IN BY put() AND FREED BY WHOEVER TAKES THE ENTRY OUT. NULL MARKS THE END OF THE INPUT
    record Entry(AVPacket packet, int generation, long tsMs, long durationUs) {}
}
