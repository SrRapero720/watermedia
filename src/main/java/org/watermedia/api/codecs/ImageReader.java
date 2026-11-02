package org.watermedia.api.codecs;

import org.watermedia.api.util.PixelFormat;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * ByteBuffer-backed, frame-by-frame image decoder.
 *
 * <p>An {@code ImageReader} is constructed by {@link CodecsAPI#reader(ByteBuffer)} once the
 * format has been identified from the leading magic bytes. The {@code ByteBuffer} passed to a
 * concrete subclass is positioned <strong>past the format header</strong>: the reader assumes
 * the format is correct and starts parsing the body directly. Headers are never re-validated.
 *
 * <p>The reader pulls from the underlying buffer only the bytes it needs to produce the next
 * frame. After {@link #next()} returns, the decoded frame is available as a direct
 * {@link ByteBuffer} laid out in {@link #pixelFormat()}; {@link ReaderFilter}s added through
 * {@link #addFilter(ReaderFilter)} transform it first, so {@link #frameWidth()} and
 * {@link #frameHeight()} carry the geometry of what {@code next()} actually returned.
 * The frame buffer is owned by the reader and reused across calls; a caller that needs to retain
 * a frame copies it out before the next {@code next()}. The source buffer is shared, not copied:
 * its contents must not change until the reader is closed.
 *
 * <p>A reader is used from one thread at a time. {@link #close()} is the only method safe to call
 * from another thread: it is idempotent, and {@link #hasNext()}, {@link #next()}, {@link #reset()}
 * and {@link #readAll()} then throw {@link IllegalStateException} while the descriptors stay readable.
 */
public abstract class ImageReader implements Closeable {
    /**
     * Aggregate ceiling on the pixel bytes {@link #readAll()} will retain across every frame of one
     * image, and the last line of defence against a container that declares far more animation than
     * it carries. Sized at roughly twice the largest legitimate sample measured (an ordinary
     * 32-frame animated GIF already decodes to 261 MiB), because a budget tight enough to be
     * interesting is also tight enough to reject real content.
     */
    public static final long MAX_DECODED_BYTES = 512L << 20;

    protected final ByteBuffer data;
    protected final PixelFormat requestedFormat;
    // THE METADATA EVERY READER FILLS; metadata() HANDS OUT FROZEN COPIES SO A CONSUMER NEVER SEES IT CHANGE
    protected final ImageMetadata metadata = new ImageMetadata();
    // ORDERED SO A POST-PROCESSOR CHAIN RUNS IN INSERTION ORDER (E.G. DOWNSCALE THEN RECOLOR)
    protected final List<ReaderFilter> filters = new ArrayList<>(2);
    protected long delayMs;
    // OUTPUT OF THE LATEST next() AFTER THE FILTER CHAIN; fresh MARKS A FILTER-ALLOCATED BUFFER THE CALLER MAY KEEP
    private ByteBuffer frame;
    private int frameWidth, frameHeight;
    private boolean fresh;
    private ByteBuffer converted; // REUSED OUTPUT OF THE FORMAT CONVERSION PASS
    private volatile boolean closed;

    protected ImageReader(final ByteBuffer data) { this(data, null); }

    protected ImageReader(final ByteBuffer data, final PixelFormat requestedFormat) {
        if (requestedFormat != null && requestedFormat != PixelFormat.BGRA && requestedFormat != PixelFormat.RGBA && requestedFormat != PixelFormat.GRAY)
            throw new IllegalArgumentException("Readers deliver BGRA, RGBA or GRAY, not " + requestedFormat);
        this.data = data.slice();
        this.requestedFormat = requestedFormat;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Format short name (e.g. {@code "PNG"}, {@code "GIF"}). */
    public String name() {
        // FORMAT NAMES ARE UPPERCASE ("PNG", "WEBP") WHATEVER THE CLASS CASING IS
        final String simpleName = this.getClass().getSimpleName();
        final String format = simpleName.endsWith("Reader")
                ? simpleName.substring(0, simpleName.length() - "Reader".length())
                : simpleName;
        return format.toUpperCase(Locale.ROOT);
    }

    /** Image width in pixels. Available immediately after construction. */
    public abstract int width();
    /** Image height in pixels. Available immediately after construction. */
    public abstract int height();

    /** Width of the frame returned by the latest {@link #next()}, after filters; {@link #width()} before the first. */
    public int frameWidth() { return this.frame == null ? this.width() : this.frameWidth; }
    /** Height of the frame returned by the latest {@link #next()}, after filters; {@link #height()} before the first. */
    public int frameHeight() { return this.frame == null ? this.height() : this.frameHeight; }

    /**
     * Pixel layout of the single, tightly packed buffer returned by {@link #next()}: the layout
     * requested at construction ({@link PixelFormat#BGRA}, {@link PixelFormat#RGBA} or
     * {@link PixelFormat#GRAY}, converted by the reader when it is not its own), otherwise the
     * reader's own layout, which is BGRA for every built-in reader except a grayscale JPEG, which
     * stays GRAY.
     */
    public final PixelFormat pixelFormat() {
        return this.requestedFormat != null ? this.requestedFormat : this.decodedFormat();
    }

    /**
     * Pre-generated animation summary computed during construction. Animated formats walk the
     * container chunk structure (without decoding pixel data) to populate this; static formats
     * return {@link ImageData.Scan#EMPTY}.
     */
    public abstract ImageData.Scan scan();

    /** Total plays of an animation: {@link ImageData#REPEAT_FOREVER} (0) to loop forever, otherwise the count; a static image reports 1. */
    public int repeat() { return this.scan().repeat(); }
    /** Total frame count; one for a static image. */
    public int frameCount() { return this.scan().frameCount(); }
    /** Total playback duration in milliseconds. Static images return {@code 0}. */
    public long duration() { return this.scan().duration(); }
    /** Per-frame delays in milliseconds, as a copy the caller owns. Static images return a single {@code 0}. */
    public long[] delays() { return this.scan().delays(); }
    /** Delay in milliseconds of the frame the latest {@link #next()} returned; {@code 0} before the first frame. */
    public long delay() { return this.delayMs; }

    /**
     * Average FPS when both duration and frame count are known. Animated image formats may be
     * variable-frame-rate, so callers that need exact timing must use {@link #delays()}.
     */
    public float averageFps() {
        final long duration = this.duration();
        final int frames = this.frameCount();
        return duration > 0L && frames > 1 ? (frames * 1_000f) / duration : 0f;
    }

    /** True when per-frame delays may differ; a single frame never does. */
    public boolean variableFrameRate() {
        return this.frameCount() > 1;
    }

    /** Normalized image metadata as a frozen snapshot, or {@link ImageMetadata#EMPTY} while nothing was read. */
    public final ImageMetadata metadata() {
        return this.metadata.empty() ? ImageMetadata.EMPTY : this.metadata.frozen();
    }

    /**
     * Appends a filter to the chain run on every frame produced by {@link #next()} and {@link #readAll()}.
     * @throws IllegalArgumentException when {@code filter} is null
     */
    public void addFilter(final ReaderFilter filter) {
        if (filter == null) throw new IllegalArgumentException("Filter cannot be null");
        this.filters.add(filter);
    }

    /**
     * Decodes every available frame and returns a detached {@link ImageData} instance.
     * Frames the reader would reuse are copied, frames a filter allocated are retained as they are,
     * and the resulting geometry is the last filter's output.
     * @throws IllegalStateException when the reader is closed
     */
    public ImageData readAll() throws IOException {
        this.checkOpen();
        final List<ByteBuffer> frames = new ArrayList<>();
        final List<Long> frameDelays = new ArrayList<>();
        long retained = 0L;
        while (this.hasNext()) {
            final ByteBuffer decoded = this.next();
            // BACKSTOP AGAINST THE ANIMATION BOMB: FRAME COST IS FIXED AT CANVAS SIZE WHILE FRAME COUNT COMES FROM
            // THE CONTAINER. CHARGED BEFORE THE COPY, SO THE FRAME THAT BREAKS THE BUDGET IS NEVER RETAINED
            retained += decoded.remaining();
            if (retained > MAX_DECODED_BYTES)
                throw new CodecException("Decoded animation exceeds budget: " + retained
                        + " bytes over " + frames.size() + " frames (max " + MAX_DECODED_BYTES + ")");
            // A REUSED READER FRAME IS COPIED OUT; A FILTER-ALLOCATED ONE IS KEPT AS IT IS
            frames.add(this.fresh ? decoded : ByteBuffer.allocateDirect(decoded.remaining()).order(decoded.order()).put(decoded.duplicate()).flip());
            frameDelays.add(this.delayMs);
        }
        if (frames.isEmpty()) throw new CodecException("No frames decoded by " + this.name());
        final long[] delayArray = new long[frameDelays.size()];
        for (int i = 0; i < delayArray.length; i++) delayArray[i] = frameDelays.get(i);
        return new ImageData(frames.toArray(ByteBuffer[]::new), this.pixelFormat(), this.frameWidth(), this.frameHeight(),
                delayArray, this.repeat());
    }

    /**
     * Rewinds the reader back to the first frame without re-parsing or re-buffering the source.
     * <p>
     * Animated readers that support rewinding reposition their internal cursor at the first
     * frame and clear any inter-frame state (canvas, disposal, counters) so the next
     * {@link #next()} call decodes frame 0 again. Metadata accessors ({@link #scan()},
     * {@link #width()}, {@link #delays()}, ...) are unaffected.
     *
     * @return {@code true} when the reader was rewound, {@code false} when rewinding is not
     *         supported by this implementation
     * @throws IllegalStateException when the reader is closed
     */
    public final boolean reset() {
        this.checkOpen();
        if (!this.rewind()) return false;
        this.frame = null;
        this.delayMs = 0L;
        return true;
    }

    /**
     * Returns whether another decoded frame can be produced. Implementations may validate and
     * skip non-frame container records while answering this.
     * @throws IllegalStateException when the reader is closed
     */
    public final boolean hasNext() throws IOException {
        this.checkOpen();
        return this.hasMore();
    }

    /**
     * Decodes the next frame, converts it to {@link #pixelFormat()} when a layout was requested and
     * runs it through the filter chain.
     *
     * @return the frame buffer, positioned at zero and ready to read
     * @throws NoSuchElementException when no frame remains
     * @throws IOException on I/O error or malformed bitstream
     * @throws IllegalStateException when the reader is closed
     */
    public final ByteBuffer next() throws IOException {
        this.checkOpen();
        if (!this.hasMore()) throw new NoSuchElementException("No more " + this.name() + " frames");
        final ByteBuffer decoded;
        final ByteBuffer delivered;
        try {
            decoded = this.decode();
            delivered = this.requestedFormat == null ? decoded : this.convert(decoded, this.decodedFormat(), this.requestedFormat);
        } catch (final RuntimeException failure) {
            // A CONCURRENT close() DROPS THE BUFFERS UNDER THE DECODER: THAT IS A STATE ERROR, NOT A CODEC FAULT
            if (this.closed) throw new IllegalStateException(this.name() + " reader was closed while decoding", failure);
            throw failure;
        }
        ReaderFilter.Frame f = new ReaderFilter.Frame(delivered, this.width(), this.height(), this.pixelFormat());
        // EACH FILTER CONSUMES THE PREVIOUS OUTPUT'S GEOMETRY; A NO-OP CHAIN HANDS BACK THE READER'S REUSED BUFFER
        for (final ReaderFilter filter: this.filters) f = filter.compute(f);
        this.fresh = f.buffer() != decoded && f.buffer() != this.converted;
        this.frameWidth = f.width();
        this.frameHeight = f.height();
        return this.frame = f.buffer();
    }

    @Override
    public final void close() {
        // THE TRANSITION IS ATOMIC SO TWO CLOSERS NEVER BOTH REACH dispose(); A next() IN FLIGHT FAILS AS A STATE ERROR
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
        }
        this.frame = null;
        this.converted = null;
        this.dispose();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // LAYOUT OF THE BUFFER decode() RETURNS; A READER THAT HONOURS requestedFormat ITSELF REPORTS IT HERE
    protected abstract PixelFormat decodedFormat();

    // REPOSITIONS AT THE FIRST FRAME AND CLEARS THE INTER-FRAME STATE; FALSE WHEN THE READER CANNOT REWIND
    protected boolean rewind() {
        return false;
    }

    // ANSWERS hasNext(): MAY VALIDATE AND SKIP NON-FRAME CONTAINER RECORDS ON THE WAY TO THE NEXT FRAME
    protected abstract boolean hasMore() throws IOException;

    /** Decodes the next frame (the base class verified one remains) into a reused direct buffer in {@link #decodedFormat()}, positioned at zero. */
    protected abstract ByteBuffer decode() throws IOException;

    // RELEASES WHAT THE READER HOLDS BEYOND ITS BUFFERS; RUNS ONCE, ON THE CLOSING THREAD
    protected void dispose() {}

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void checkOpen() {
        if (this.closed) throw new IllegalStateException(this.name() + " reader is closed");
    }

    // THE CONVERSION PASS BETWEEN THE READER'S LAYOUT AND THE REQUESTED ONE, ALWAYS INTO A REUSED BUFFER: A DECODER MAY
    // HAND BACK THE SAME BYTES ON REPLAY, SO SWIZZLING IN PLACE WOULD FLIP THEM TWICE. GRAY EXPANDS, COLOUR REDUCES TO BT.601 LUMA
    private ByteBuffer convert(final ByteBuffer decoded, final PixelFormat from, final PixelFormat to) {
        if (from == to) return decoded;
        final int pixels = this.width() * this.height();
        final int bytes = to == PixelFormat.GRAY ? pixels : pixels * 4;
        ByteBuffer out = this.converted;
        if (out == null || out.capacity() < bytes) this.converted = out = ByteBuffer.allocateDirect(bytes).order(decoded.order());
        out.clear().limit(bytes);
        if (from != PixelFormat.GRAY && to != PixelFormat.GRAY) {
            for (int o = 0; o < bytes; o += 4)
                out.put(o, decoded.get(o + 2)).put(o + 1, decoded.get(o + 1)).put(o + 2, decoded.get(o)).put(o + 3, decoded.get(o + 3));
        } else if (from == PixelFormat.GRAY) {
            for (int i = 0, o = 0; i < pixels; i++, o += 4) {
                final byte luma = decoded.get(i);
                out.put(o, luma).put(o + 1, luma).put(o + 2, luma).put(o + 3, (byte) 0xFF);
            }
        } else {
            final int red = from == PixelFormat.BGRA ? 2 : 0;
            final int blue = from == PixelFormat.BGRA ? 0 : 2;
            for (int i = 0, o = 0; i < pixels; i++, o += 4) {
                final int r = decoded.get(o + red) & 0xFF;
                final int g = decoded.get(o + 1) & 0xFF;
                final int b = decoded.get(o + blue) & 0xFF;
                out.put(i, (byte) ((77 * r + 150 * g + 29 * b + 128) >> 8));
            }
        }
        return out;
    }
}
