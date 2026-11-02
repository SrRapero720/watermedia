package org.watermedia.api.codecs;

import org.watermedia.api.codecs.common.dds.DdsHeader;
import org.watermedia.api.util.PixelFormat;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reader for the BC (BC7/BC3/BC1) texture-compression codec stored in a DDS
 * container. Unlike the pixel-decoding {@link ImageReader} readers, BC is
 * sampled by the GPU, so this reader yields the <em>compressed</em> blocks of each frame (in the
 * file's own BC layout) for a consumer's direct upload; there is no software decode.
 *
 * <p>Reading blocks requires no native encoder. WaterMedia's built-in graphics engines currently
 * decline BC textures; an external GPU consumer must support the layout named by {@link #pixelFormat()}.
 * A WaterMedia animation footer is optional; trailing bytes that do not start with its magic are ignored
 * and leave every texture array slice with zero display delay.
 */
public final class BcReader implements Closeable {
    private final int width;
    private final int height;
    private final PixelFormat format;
    private final ByteBuffer[] frames;
    private final long[] delays;
    private final long duration;
    private int cursor;
    private boolean closed;

    /**
     * Parses a complete BC-in-DDS file from {@code file.position()}.
     *
     * @throws UnsupportedFormatException when the DDS is valid but not a single-level 2D BC1, BC3 or BC7 texture array
     * @throws CodecException             when the container or its animation footer is malformed, truncated or
     *                                    exceeds the memory budget
     */
    public BcReader(final ByteBuffer file) throws CodecException {
        final DdsHeader.Info info = DdsHeader.read(file);
        this.width = info.width();
        this.height = info.height();
        this.format = info.format();

        // RANGE BEFORE TRUNCATION: THE RANGE CHECK IS WHAT KEEPS THE TRUNCATION MATH AND THE int CASTS BELOW SOUND
        final long frameBytes = info.frameBytes();
        final long texLen = (long) info.arraySize() * frameBytes;
        if (texLen > ImageReader.MAX_DECODED_BYTES) throw new CodecException("BC texture too large: " + texLen + " bytes");
        final long need = (long) DdsHeader.BYTES + texLen;
        if (file.remaining() < need) throw new CodecException("Truncated BC texture: need " + need + " bytes");

        final int frameLen = (int) frameBytes;
        final int base = file.position();
        final int texStart = base + DdsHeader.BYTES;

        this.delays = DdsHeader.readFooter(file.duplicate().position(texStart + (int) texLen), info.arraySize());
        long total = 0L;
        for (final long delay: this.delays) {
            if (delay < 0 || delay > Long.MAX_VALUE - total) throw new CodecException("Invalid BC frame delays");
            total += delay;
        }
        this.duration = total;

        // ONE DIRECT COPY OF EVERY SLICE, VIEWED PER FRAME: THE VIEWS STAY DIRECT, SO AN ENGINE UPLOADS THEM WITHOUT ANOTHER COPY
        final ByteBuffer tex = ByteBuffer.allocateDirect((int) texLen).order(ByteOrder.nativeOrder());
        final ByteBuffer region = file.duplicate();
        region.position(texStart).limit(texStart + (int) texLen);
        tex.put(region);
        tex.flip();

        this.frames = new ByteBuffer[info.arraySize()];
        for (int i = 0; i < this.frames.length; i++) {
            final ByteBuffer view = tex.duplicate().order(tex.order());
            view.position(i * frameLen).limit((i + 1) * frameLen);
            this.frames[i] = view.slice().order(tex.order());
        }
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns the encoded frame width in pixels. */
    public int width() { return this.width; }
    /** Returns the encoded frame height in pixels. */
    public int height() { return this.height; }
    /** Returns the number of texture-array slices, including already consumed frames. */
    public int frameCount() { return this.frames.length; }

    /** Returns the BC layout of the stored texture, the format to check with {@code GFXEngine.supports(PixelFormat)} before upload. */
    public PixelFormat pixelFormat() { return this.format; }

    /** Per-frame delays in milliseconds (defensive copy). */
    public long[] delays() { return this.delays.clone(); }

    /** Total playback duration in milliseconds. */
    public long duration() { return this.duration; }

    /**
     * The compressed block buffers for every frame, in order, for a one-shot array upload.
     * Only the array is copied: buffer contents, positions and limits are shared with {@link #next()}.
     * Duplicate a buffer to change its position independently; do not free the shared storage.
     */
    public ByteBuffer[] blocks() { return this.frames.clone(); }

    /** Returns whether next() can advance to another compressed frame; close() exhausts the cursor. */
    public boolean hasNext() { return this.cursor < this.frames.length; }

    /** The next frame's compressed blocks paired with its delay, or {@code null} at the end. */
    public Block next() {
        if (this.cursor >= this.frames.length) return null;
        final int i = this.cursor++;
        return new Block(this.frames[i], this.delays[i]);
    }

    /** Rewinds to the first frame; a closed reader throws {@link IllegalStateException}. */
    public void reset() {
        if (this.closed) throw new IllegalStateException("BC reader is closed");
        this.cursor = 0;
    }

    @Override
    public void close() {
        // BACKED BY AN IN-MEMORY DIRECT BUFFER: CLOSING ONLY EXHAUSTS THE CURSOR FOR GOOD
        this.closed = true;
        this.cursor = this.frames.length;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** One frame's compressed blocks and its display delay in milliseconds. */
    public record Block(ByteBuffer blocks, long delayMs) {}
}
