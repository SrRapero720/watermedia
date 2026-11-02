package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.global.avutil;

/**
 * Thread-safe ring buffer of decoded AVFrames, following the ffplay pattern of pre-allocated slots with explicit
 * ownership.
 *
 * <p>Exactly one writer (the decode thread) and one reader (the render or caller thread) synchronize on an internal
 * monitor with wait/notify. Each slot's AVFrame is allocated once in the constructor and reused indefinitely; the
 * pixel buffers inside it belong to the slot between {@link #push()} and {@link #next()}, and {@link #free()}
 * releases the structs at final cleanup. Video uses 3 slots (about 24 MB of 1080p BGRA, absorbing decode jitter),
 * audio 9 (about 144 KB of 48 kHz stereo S16, keeping latency low).
 */
public final class FrameQueue {
    private final Slot[] queue;
    private final int capacity;
    private int readIndex;
    private int writeIndex;
    private int size;
    private volatile boolean aborted;
    private final Object lock = new Object();

    /** Creates a ring of {@code capacity} pre-allocated slots, typically 3 for video or 9 for audio. */
    public FrameQueue(final int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Frame queue capacity must be positive");
        this.capacity = capacity;
        this.queue = new Slot[capacity];
        try {
            for (int i = 0; i < capacity; i++) this.queue[i] = new Slot();
        } catch (final RuntimeException | Error failure) {
            for (final Slot slot: this.queue) if (slot != null) slot.destroy();
            throw failure;
        }
    }

    // ==========================================================================
    // PUBLIC API: WRITER SIDE (DECODE THREAD)
    // ==========================================================================
    /**
     * Returns the next writable slot, blocking while the queue is full (intentional backpressure), or null when the
     * queue was aborted or the wait was interrupted. The writer moves the decoded frame in with
     * {@code av_frame_move_ref(slot.frame, decodedFrame)}, fills {@code ptsMs}, {@code serial} and the rest, then calls {@link #push()}.
     */
    public Slot peekWritable() {
        synchronized (this.lock) {
            while (this.size >= this.capacity && !this.aborted) {
                try {
                    this.lock.wait();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (this.aborted) return null;
            return this.queue[this.writeIndex];
        }
    }

    /** Commits the slot {@link #peekWritable()} granted, making the frame visible to the reader; throws {@code IllegalStateException} without one. */
    public void push() {
        synchronized (this.lock) {
            // A PUSH WITHOUT A GRANTED SLOT WOULD SILENTLY OVERWRITE UNREAD FRAMES, SO IT FAILS LOUD
            if (this.size >= this.capacity) throw new IllegalStateException("push() without a writable slot (queue full)");
            this.writeIndex = (this.writeIndex + 1) % this.capacity;
            this.size++;
            this.lock.notifyAll();
        }
    }

    // ==========================================================================
    // PUBLIC API: READER SIDE (RENDER OR CALLER THREAD)
    // ==========================================================================
    /** Returns the oldest frame without consuming it, or null at once when the queue is empty or aborted. */
    public Slot peek() {
        synchronized (this.lock) {
            if (this.size <= 0 || this.aborted) return null;
            return this.queue[this.readIndex];
        }
    }

    /** Returns the frame after the current one (for the inter-frame duration), or null when fewer than two are queued or the queue is aborted. */
    public Slot peekNext() {
        synchronized (this.lock) {
            if (this.size < 2 || this.aborted) return null;
            return this.queue[(this.readIndex + 1) % this.capacity];
        }
    }

    /** Consumes the current frame, returning its pixel buffers to the FFmpeg pool with {@code av_frame_unref}; the slot is invalid afterwards. */
    public void next() {
        synchronized (this.lock) {
            avutil.av_frame_unref(this.queue[this.readIndex].frame);
            this.readIndex = (this.readIndex + 1) % this.capacity;
            this.size--;
            this.lock.notifyAll();
        }
    }

    // ==========================================================================
    // PUBLIC API: CONTROL
    // ==========================================================================
    /** Frames available for reading. */
    public int remaining() {
        synchronized (this.lock) { return this.size; }
    }

    /** Returns true if the queue is empty. */
    public boolean empty() {
        synchronized (this.lock) { return this.size == 0; }
    }

    /**
     * Discards every pending frame with {@code av_frame_unref}, as after a seek. A writer between {@link #peekWritable()}
     * and {@link #push()} still commits into its own slot, which becomes the first readable frame and is then dropped
     * by the reader for its stale serial.
     */
    public void flush() {
        synchronized (this.lock) {
            for (int i = 0; i < this.size; i++) avutil.av_frame_unref(this.queue[(this.readIndex + i) % this.capacity].frame);
            // KEEP writeIndex: A WRITER HOLDING A SLOT (NEVER IN THE UNREF RANGE, SINCE THE QUEUE WAS NOT FULL) STILL PUSHES
            // INTO IT; JUMPING THE READ CURSOR THERE KEEPS THE RING CONSISTENT
            this.readIndex = this.writeIndex;
            this.size = 0;
            this.lock.notifyAll();
        }
    }

    /** Abort signal: unblocks {@link #peekWritable()} immediately; every peek answers null until {@link #reset()}. */
    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** Discards the pending frames and clears the abort flag so the queue can be reused (e.g. on a quality switch). */
    public void reset() {
        synchronized (this.lock) {
            this.flush();
            this.aborted = false;
        }
    }

    /** Releases the AVFrame structs at final player cleanup; the queue is not reusable afterwards. */
    public void free() {
        this.flush();
        for (int i = 0; i < this.capacity; i++) this.queue[i].destroy();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Wrapper for a slot with its AVFrame and presentation metadata. */
    public static final class Slot {
        /** Pre-allocated AVFrame. Pixel data is filled via {@code av_frame_move_ref}. */
        public final AVFrame frame;

        /** PTS in milliseconds. Set by the writer before {@link FrameQueue#push()}. */
        public long ptsMs;

        /** Estimated frame duration in milliseconds. */
        public long durationMs;

        /** Serial of the {@link PacketQueue} at decode time; the reader drops frames whose serial predates the last seek. */
        public int serial;

        // CACHED TO DETECT MID-STREAM FORMAT CHANGES WITHOUT ACCESSING THE AVFrame
        public int width;
        public int height;
        public int format;

        Slot() {
            this.frame = avutil.av_frame_alloc();
            if (this.frame == null) throw new OutOfMemoryError("av_frame_alloc failed");
        }

        void destroy() {
            avutil.av_frame_free(this.frame);
        }
    }
}
