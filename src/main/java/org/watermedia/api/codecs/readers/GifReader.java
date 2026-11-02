package org.watermedia.api.codecs.readers;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.common.Canvas;
import org.watermedia.api.codecs.common.Limits;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Streaming GIF87a / GIF89a reader.
 *
 * <p>Receives a {@link ByteBuffer} positioned immediately after the 6-byte signature
 * ({@code GIF87a} or {@code GIF89a}); the signature is consumed by {@code CodecsAPI}. The
 * constructor reads the Logical Screen Descriptor and the optional Global Color Table, then walks
 * the block stream once to collect the frame delays, the loop count, comments and application
 * extensions. Each {@link #next()} parses one Image Descriptor + LZW data block and composes it
 * onto the canvas.
 */
public final class GifReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(GifReader.class.getSimpleName());

    // SECURITY CAPS
    // A FRAME COSTS A FULL-CANVAS COMPOSITE AND COPY REGARDLESS OF ITS DESCRIPTOR SIZE, YET IS ONLY
    // 12 FILE BYTES. BOUNDED PER PASS: reset() STARTS A NEW PASS, SO LOOPING PLAYBACK IS UNAFFECTED
    private static final int MAX_FRAMES = 4_096;
    // COMMENTS AND APPLICATION EXTENSIONS LIVE AS LONG AS THE READER: RECORDS PAST EITHER CAP ARE SKIPPED
    private static final int MAX_METADATA_RECORDS = 1_024;
    private static final int MAX_METADATA_BYTES = 8 << 20;
    private static final int MAX_SUBBLOCK_BYTES = Integer.MAX_VALUE - 8; // JAVA ARRAY-SIZE SAFE UPPER BOUND

    // BLOCK INTRODUCERS AND EXTENSION LABELS
    private static final int IMAGE_SEPARATOR = 0x2C;
    private static final int EXTENSION_INTRODUCER = 0x21;
    private static final int TRAILER = 0x3B;
    private static final int APPLICATION_EXTENSION_LABEL = 0xFF;
    private static final int COMMENT_EXTENSION_LABEL = 0xFE;
    private static final int APPLICATION_HEADER_BYTES = 11;
    // LOOPING APPLICATION EXTENSIONS (NETSCAPE AND ITS ADOBE TWIN): SUB-ID 1, THEN A LITTLE-ENDIAN LOOP COUNT
    private static final String NETSCAPE_LOOP = "NETSCAPE2.0";
    private static final String ANIMEXTS_LOOP = "ANIMEXTS1.0";

    // LZW
    private static final int MIN_LZW_CODE_SIZE = 2;
    private static final int MAX_LZW_CODE_SIZE = 8;
    private static final int MAX_STACK_SIZE = 4_096;
    private static final int STACK_BUFFER_SIZE = 4_097;

    // INTERLACED RENDERING
    private static final int[] PASS_STARTS = {0, 4, 2, 1};
    private static final int[] PASS_INCREMENTS = {8, 8, 4, 2};

    private static final int DELAY_UNIT_MS = 10; // GCE DELAYS COUNT CENTISECONDS
    private static final long BROWSER_DELAY_MS = 100L;
    private static final int OPAQUE_BLACK = 0xFF000000;

    // PARSED HEADER STATE
    private final ScreenDescriptor lsd;
    private final ColorTable globalColorTable;
    private final int backgroundColor;
    private final int bodyStart;           // FIRST BLOCK AFTER THE GLOBAL COLOR TABLE; reset() REPLAYS FROM HERE
    private final ImageData.Scan scan;

    // CANVAS STATE
    private final int[] canvas;
    private int[] restoreFrame;            // CANVAS SAVED FOR DISPOSAL METHOD 3
    private final ByteBuffer directOut;    // BGRA OUTPUT BUFFER (REUSED)
    private final IntBuffer directOutInts;

    // STREAMING STATE
    private boolean done;
    private boolean nextReady;             // AN IMAGE SEPARATOR WAS CONSUMED AND ITS FRAME AWAITS decode()
    private int frames;                    // FRAMES YIELDED IN THE CURRENT PASS
    private byte[] subBlockBuffer = new byte[4_096];
    private byte[] lzwIndexScratch = new byte[0];
    private final short[] lzwPrefix = new short[MAX_STACK_SIZE];
    private final byte[] lzwSuffix = new byte[MAX_STACK_SIZE];
    private final byte[] lzwPixelStack = new byte[STACK_BUFFER_SIZE];

    // FRAME CONTROL BETWEEN CONSECUTIVE FRAMES
    private GraphicExtension gce;          // APPLIES TO THE NEXT IMAGE ONLY
    private GraphicExtension previousGce;
    private ImageDescriptor previousId;

    public GifReader(final ByteBuffer data) throws CodecException { this(data, null); }

    public GifReader(final ByteBuffer data, final PixelFormat requestedFormat) throws CodecException {
        super(data, requestedFormat);
        this.lsd = ScreenDescriptor.read(this.data);
        // CAP THE CANVAS BEFORE ANYTHING IS ALLOCATED: A 7-BYTE SCREEN DESCRIPTOR DECLARING 16K x 16K BUYS 1 GiB OF
        // CANVAS, 1 GiB OF DIRECT BUFFER AND 1 GiB OF DISPOSAL-3 SNAPSHOT BEFORE ANY FRAME BYTE IS PARSED
        if (!Limits.fits(this.lsd.width(), this.lsd.height()))
            throw new CodecException("GIF canvas too big: " + this.lsd.width() + "x" + this.lsd.height()
                    + " (max " + Limits.MAX_DIM + " per axis, " + Limits.MAX_PIXELS + " pixels)");
        this.globalColorTable = this.lsd.globalColorTableFlag() ? ColorTable.read(this.lsd.globalColorCount(), this.data) : null;
        this.backgroundColor = this.globalColorTable != null && this.lsd.backgroundColorIndex() < this.globalColorTable.size()
                ? this.globalColorTable.colors()[this.lsd.backgroundColorIndex()] : OPAQUE_BLACK;
        this.bodyStart = this.data.position();
        this.scan = this.scan(this.data.duplicate());

        this.canvas = new int[this.lsd.width() * this.lsd.height()];
        this.directOut = ByteBuffer.allocateDirect(this.canvas.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        this.directOutInts = this.directOut.asIntBuffer();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override public int width() { return this.lsd.width(); }
    @Override public int height() { return this.lsd.height(); }
    @Override public ImageData.Scan scan() { return this.scan; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override protected PixelFormat decodedFormat() { return PixelFormat.BGRA; }

    @Override
    protected boolean hasMore() throws CodecException {
        while (!this.nextReady && !this.done) {
            final int b = this.data.hasRemaining() ? this.data.get() & 0xFF : TRAILER;
            if (b == TRAILER) {
                this.done = true;
            } else if (b == IMAGE_SEPARATOR) {
                // COUNTED ONCE PER FRAME: nextReady ABSORBS REPEATED hasNext() CALLS
                if (++this.frames > MAX_FRAMES)
                    throw new CodecException("GIF frame count exceeds limit: " + this.frames + " (max " + MAX_FRAMES + ")");
                this.nextReady = true;
            } else if (b == EXTENSION_INTRODUCER) {
                // THE GRAPHIC CONTROL EXTENSION DRIVES THE NEXT FRAME; scan() ALREADY READ EVERY OTHER EXTENSION
                final int label = this.data.hasRemaining() ? this.data.get() & 0xFF : -1;
                if (label == GraphicExtension.GCE_LABEL) this.gce = GraphicExtension.read(this.data);
                else skipBlocks(this.data);
            }
            // ANY OTHER BYTE IS SKIPPED, EXACTLY AS scan() SKIPS IT
        }
        return this.nextReady;
    }

    @Override
    protected ByteBuffer decode() throws CodecException {
        this.nextReady = false;
        final ImageDescriptor id = ImageDescriptor.read(this.data);
        // A DESCRIPTOR MAY HANG PAST THE CANVAS (REAL ENCODERS DO IT): THE RASTER DECODES AT ITS OWN SIZE AND
        // blitRow CLIPS IT, SO ONLY ITS AREA NEEDS A BUDGET. 16-BIT AXES MULTIPLY TO 4 GIGAPIXELS OF LZW SCRATCH
        if ((long) id.width() * id.height() > Limits.MAX_PIXELS)
            throw new CodecException("GIF frame too big: " + id.width() + "x" + id.height() + " (max " + Limits.MAX_PIXELS + " pixels)");
        final ColorTable colorTable = id.localColorTableFlag() ? ColorTable.read(id.localColorCount(), this.data) : this.globalColorTable;
        if (colorTable == null) throw new CodecException("GIF frame has no color table");

        // LZW: 1 BYTE MIN CODE SIZE + SUB-BLOCKS
        if (!this.data.hasRemaining()) throw new CodecException("EOF before LZW min code size");
        final int lzwMinCodeSize = this.data.get() & 0xFF;
        if (lzwMinCodeSize < MIN_LZW_CODE_SIZE || lzwMinCodeSize > MAX_LZW_CODE_SIZE)
            throw new CodecException("Invalid LZW minimum code size: " + lzwMinCodeSize);
        final int lzwDataLength = this.readSubBlocks();

        // ONLY THE ROWS THAT LAND ON THE CANVAS ARE DECODED: LZW IS SEQUENTIAL, SO THE STREAM STOPS AFTER THE LAST
        // VISIBLE ROW (IN PASS ORDER FOR INTERLACED FRAMES) AND A FRAME HANGING BELOW THE CANVAS COSTS NOTHING
        final int visibleRows = Math.max(0, Math.min(this.lsd.height() - id.top(), id.height()));
        int needed = visibleRows;
        if (id.interlacedFlag()) {
            int count = 0;
            needed = 0;
            for (int pass = 0; pass < PASS_STARTS.length; pass++) {
                for (int y = PASS_STARTS[pass]; y < id.height(); y += PASS_INCREMENTS[pass]) {
                    count++;
                    if (y < visibleRows) needed = count;
                }
            }
        }
        needed *= id.width();
        final byte[] indices = this.decompress(lzwMinCodeSize, this.subBlockBuffer, lzwDataLength, needed);

        if (this.previousId == null) {
            Arrays.fill(this.canvas, this.gce != null && this.gce.transparentColorFlag() ? 0 : this.backgroundColor);
        } else {
            // DISPOSE THE PREVIOUS FRAME'S RECTANGLE: 2 RESTORES THE BACKGROUND (TRANSPARENT WHEN THAT FRAME WAS),
            // 3 THE CANVAS SAVED BEFORE IT WAS DRAWN
            final ImageDescriptor p = this.previousId;
            final int disposal = this.previousGce != null ? this.previousGce.disposalMethod() : 0;
            if (disposal == 2)
                Canvas.fill(this.canvas, this.lsd.width(), this.lsd.height(), p.left(), p.top(), p.width(), p.height(),
                        this.previousGce.transparentColorFlag() ? 0 : this.backgroundColor);
            else if (disposal == 3 && this.restoreFrame != null)
                Canvas.restore(this.canvas, this.restoreFrame, this.lsd.width(), this.lsd.height(), p.left(), p.top(), p.width(), p.height());
        }
        if (this.gce != null && this.gce.disposalMethod() == 3) {
            if (this.restoreFrame == null) this.restoreFrame = new int[this.canvas.length];
            System.arraycopy(this.canvas, 0, this.restoreFrame, 0, this.canvas.length);
        }

        this.renderImage(indices, needed, id, colorTable);
        // A SINGLE-FRAME GIF IS A STILL IMAGE: ITS GCE DELAY TIMES NOTHING
        this.delayMs = this.scan.frameCount() > 1 ? delayOf(this.gce != null ? this.gce.delayTime() : 0) : 0L;
        this.previousGce = this.gce;
        this.previousId = id;
        this.gce = null;
        Canvas.write(this.canvas, this.canvas.length, this.directOutInts, this.directOut);
        return this.directOut;
    }

    @Override
    protected boolean rewind() {
        // THE CANVAS AND restoreFrame NEED NO CLEARING: FRAME 0 FULLY REFILLS THE CANVAS AND DISPOSAL-3
        // SNAPSHOTS ARE ALWAYS SAVED BEFORE BEING RESTORED WITHIN THE SAME PASS
        this.data.position(this.bodyStart);
        this.done = false;
        this.nextReady = false;
        this.gce = null;
        this.previousGce = null;
        this.previousId = null;
        this.frames = 0; // THE FRAME CAP BOUNDS ONE PASS, NOT THE LIFETIME OF A FOREVER-LOOPING READER
        return true;
    }

    // ==========================================================================
    // FRAME DECODE
    // ==========================================================================
    private void renderImage(final byte[] indexes, final int pixelCount, final ImageDescriptor id, final ColorTable colorTable) {
        final int transparentIndex = this.gce != null && this.gce.transparentColorFlag() ? this.gce.transparentColorIndex() : -1;
        final int[] canvas = this.canvas;
        final int[] colors = colorTable.colors();
        final int colorLen = colors.length;
        final int canvasWidth = this.lsd.width();
        final int canvasHeight = this.lsd.height();
        final int idLeft = id.left();
        final int idTop = id.top();
        final int idWidth = id.width();
        final int idHeight = id.height();

        // FAST PATH: THE IMAGE DESCRIPTOR COVERS THE WHOLE CANVAS EXACTLY, SO NO PIXEL NEEDS CLIPPING
        if (!id.interlacedFlag() && idLeft == 0 && idTop == 0 && idWidth == canvasWidth && idHeight == canvasHeight) {
            if (transparentIndex < 0) {
                for (int i = 0; i < pixelCount; i++) {
                    final int ci = indexes[i] & 0xFF;
                    if (ci < colorLen) canvas[i] = colors[ci];
                }
            } else {
                for (int i = 0; i < pixelCount; i++) {
                    final int ci = indexes[i] & 0xFF;
                    if (ci == transparentIndex) continue;
                    if (ci < colorLen) canvas[i] = colors[ci];
                }
            }
            return;
        }

        // GENERAL PATH: HOIST THE CLIP RECT ONCE INSTEAD OF PER-PIXEL CHECKS
        final int clipXStart = Math.max(0, idLeft);
        final int clipYStart = Math.max(0, idTop);
        final int clipXEnd = Math.min(canvasWidth, idLeft + idWidth);
        final int clipYEnd = Math.min(canvasHeight, idTop + idHeight);
        final boolean rowFullyVisible = (idLeft >= 0) && (idLeft + idWidth <= canvasWidth);

        int srcIdx = 0;
        if (id.interlacedFlag()) {
            for (int pass = 0; pass < PASS_STARTS.length; pass++) {
                final int passStart = PASS_STARTS[pass];
                final int passInc = PASS_INCREMENTS[pass];
                for (int y = passStart; y < idHeight; y += passInc) {
                    srcIdx = blitRow(indexes, srcIdx, idWidth, idLeft, idTop + y,
                            canvas, canvasWidth, clipXStart, clipXEnd, clipYStart, clipYEnd,
                            rowFullyVisible, colors, colorLen, transparentIndex);
                    if (srcIdx >= pixelCount) return;
                }
            }
        } else {
            for (int y = 0; y < idHeight; y++) {
                srcIdx = blitRow(indexes, srcIdx, idWidth, idLeft, idTop + y,
                        canvas, canvasWidth, clipXStart, clipXEnd, clipYStart, clipYEnd,
                        rowFullyVisible, colors, colorLen, transparentIndex);
                if (srcIdx >= pixelCount) return;
            }
        }
    }

    private byte[] decompress(final int lzwMinCodeSize, final byte[] data,
                              final int dataLength, final int expectedSize) throws CodecException {
        final int clearCode = 1 << lzwMinCodeSize;
        final int endOfInfoCode = clearCode + 1;
        if (this.lzwIndexScratch.length < expectedSize) this.lzwIndexScratch = new byte[expectedSize];
        final byte[] output = this.lzwIndexScratch;
        final short[] prefix = this.lzwPrefix;
        final byte[] suffix = this.lzwSuffix;
        final byte[] pixelStack = this.lzwPixelStack;

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        int codeSize = lzwMinCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clearCode + 2;

        int datum = 0;
        int bits = 0;
        int oldCode = -1;
        int first = 0;
        int top = 0;
        int pi = 0;
        int dataPos = 0;
        int pixelsLeft = expectedSize;

        outer:
        while (pixelsLeft > 0) {
            // DRAIN THE PENDING STACK FIRST, THE MOST COMMON HOT PATH
            while (top > 0) {
                output[pi++] = pixelStack[--top];
                if (--pixelsLeft == 0) break outer;
            }

            // READ ENOUGH BITS FOR THE NEXT CODE
            while (bits < codeSize) {
                if (dataPos >= dataLength) break outer;
                datum += (data[dataPos++] & 0xFF) << bits;
                bits += 8;
            }
            int code = datum & codeMask;
            datum >>>= codeSize;
            bits -= codeSize;

            if (code > available || code == endOfInfoCode) break;
            if (code == clearCode) {
                codeSize = lzwMinCodeSize + 1;
                codeMask = (1 << codeSize) - 1;
                available = clearCode + 2;
                oldCode = -1;
                continue;
            }
            if (oldCode == -1) {
                // THE FIRST CODE AFTER A CLEAR MUST BE A LITERAL. code == available (clearCode + 2) PASSES THE
                // BOUNDS CHECK ABOVE AND WOULD READ A DICTIONARY SLOT THIS FRAME NEVER DEFINED; THE TABLE IS
                // LONG-LIVED, SO THAT SLOT STILL HOLDS THE PREVIOUS FRAME'S DATA. REJECT INSTEAD OF LEAKING IT
                if (code >= clearCode) throw new CodecException("LZW first code references undefined entry: " + code);
                pixelStack[top++] = suffix[code];
                oldCode = code;
                first = code;
                continue;
            }
            final int inCode = code;
            if (code == available) {
                pixelStack[top++] = (byte) first;
                code = oldCode;
            }
            while (code > clearCode) {
                if (top >= MAX_STACK_SIZE) throw new CodecException("LZW stack overflow");
                pixelStack[top++] = suffix[code];
                code = prefix[code];
            }
            first = suffix[code] & 0xFF;
            pixelStack[top++] = (byte) first;
            if (available < MAX_STACK_SIZE) {
                prefix[available] = (short) oldCode;
                suffix[available] = (byte) first;
                available++;
                if (((available & codeMask) == 0) && (available < MAX_STACK_SIZE)) {
                    codeSize++;
                    codeMask += available;
                }
            }
            oldCode = inCode;
        }
        // TRUNCATED LZW DATA: ZERO-FILL THE UNWRITTEN TAIL SO MISSING PIXELS DECODE AS INDEX 0 INSTEAD
        // OF STALE INDICES LEFT IN THE REUSED SCRATCH BY AN EARLIER (POSSIBLY LARGER) FRAME
        if (pi < expectedSize) Arrays.fill(output, pi, expectedSize, (byte) 0);
        return output;
    }

    // CONCATENATES THE IMAGE DATA SUB-BLOCKS INTO subBlockBuffer; UNLIKE METADATA, A BROKEN CHAIN HERE FAILS THE FRAME
    private int readSubBlocks() throws CodecException {
        int total = 0;
        while (true) {
            if (!this.data.hasRemaining()) throw new CodecException("EOF in GIF sub-block chain");
            final int size = this.data.get() & 0xFF;
            if (size == 0) return total;
            if (this.data.remaining() < size)
                throw new CodecException("Truncated GIF sub-block: " + size + " bytes declared, " + this.data.remaining() + " available");
            final int need = total + size;
            if (need < 0 || need > MAX_SUBBLOCK_BYTES) throw new CodecException("GIF sub-block data too large: " + need);
            if (this.subBlockBuffer.length < need) {
                // DOUBLING CAN OVERFLOW PAST Integer.MAX_VALUE INTO A NEGATIVE SIZE; FALL BACK TO THE EXACT NEED
                int next = this.subBlockBuffer.length;
                while (next < need) {
                    next <<= 1;
                    if (next < 0 || next > MAX_SUBBLOCK_BYTES) { next = need; break; }
                }
                this.subBlockBuffer = Arrays.copyOf(this.subBlockBuffer, next);
            }
            this.data.get(this.subBlockBuffer, total, size);
            total = need;
        }
    }

    // ==========================================================================
    // STREAM SCAN
    // ==========================================================================
    // WALKS THE BLOCK STREAM ONCE WITHOUT DECODING: FRAME DELAYS, THE LOOP COUNT, COMMENTS AND APPLICATION EXTENSIONS.
    // IT SKIPS EXACTLY WHAT hasMore() SKIPS, SO BOTH AGREE ON THE FRAME COUNT; A MALFORMED BLOCK ENDS THE WALK
    private ImageData.Scan scan(final ByteBuffer buffer) {
        final List<Long> delays = new ArrayList<>();
        final Map<String, List<byte[]>> extensions = new LinkedHashMap<>();
        long delay = BROWSER_DELAY_MS; // A FRAME WITHOUT A GCE PLAYS AT THE BROWSER FLOOR, LIKE delayOf(0)
        int loops = -1;
        int records = 0;
        int bytes = 0;
        int dropped = 0;
        try {
            // ONE FRAME PAST THE CAP IS ENOUGH: THE DECODER REFUSES THAT FRAME ANYWAY
            while (buffer.hasRemaining() && delays.size() <= MAX_FRAMES) {
                final int introducer = buffer.get() & 0xFF;
                if (introducer == TRAILER) break;
                if (introducer == IMAGE_SEPARATOR) {
                    final ImageDescriptor id = ImageDescriptor.read(buffer);
                    // THE LOCAL COLOR TABLE AND THE LZW MINIMUM CODE SIZE PRECEDE THE IMAGE DATA SUB-BLOCKS
                    final int skip = (id.localColorTableFlag() ? id.localColorCount() * 3 : 0) + 1;
                    if (buffer.remaining() < skip) break;
                    buffer.position(buffer.position() + skip);
                    skipBlocks(buffer);
                    delays.add(delay);
                    delay = BROWSER_DELAY_MS;
                } else if (introducer == EXTENSION_INTRODUCER && buffer.hasRemaining()) {
                    final int label = buffer.get() & 0xFF;
                    if (label == GraphicExtension.GCE_LABEL) {
                        delay = delayOf(GraphicExtension.read(buffer).delayTime());
                        continue;
                    }
                    final int start = buffer.position();
                    final int total = skipBlocks(buffer);
                    if (total < 0) break;
                    if (label != COMMENT_EXTENSION_LABEL && label != APPLICATION_EXTENSION_LABEL) continue;
                    // AN APPLICATION EXTENSION OPENS WITH ITS 11-BYTE HEADER BLOCK (8-BYTE IDENTIFIER + 3-BYTE
                    // AUTHENTICATION CODE); ONE WITHOUT IT NAMES NOTHING AND IS SKIPPED LIKE AN UNKNOWN EXTENSION
                    final int header = label == APPLICATION_EXTENSION_LABEL ? buffer.get(start) & 0xFF : 0;
                    if (label == APPLICATION_EXTENSION_LABEL && header != APPLICATION_HEADER_BYTES) continue;
                    final int payloadStart = start + (header == 0 ? 0 : 1 + header);
                    final String identifier = new String(blocks(buffer, start, header), StandardCharsets.ISO_8859_1);
                    if (loops < 0 && (identifier.equals(NETSCAPE_LOOP) || identifier.equals(ANIMEXTS_LOOP))
                            && total - header >= 3 && (buffer.get(payloadStart) & 0xFF) >= 3 && buffer.get(payloadStart + 1) == 1)
                        loops = DataTool.u16(buffer, payloadStart + 2, ByteOrder.LITTLE_ENDIAN);
                    if (records >= MAX_METADATA_RECORDS || total - header > MAX_METADATA_BYTES - bytes) {
                        dropped++;
                        continue;
                    }
                    records++;
                    bytes += total - header;
                    final byte[] payload = blocks(buffer, payloadStart, total - header);
                    if (label == COMMENT_EXTENSION_LABEL) this.metadata.comment(new String(payload, StandardCharsets.ISO_8859_1));
                    else extensions.computeIfAbsent(identifier, key -> new ArrayList<>()).add(payload);
                }
                // ANY OTHER BYTE IS SKIPPED, EXACTLY AS hasMore() SKIPS IT
            }
        } catch (final CodecException malformed) {
            // THE FRAMES BEFORE THE DAMAGE STILL COUNT; THE DECODER REPORTS THE DAMAGE WHEN IT GETS THERE
        }

        if (dropped > 0)
            LOGGER.warn(IT, "Skipped {} GIF metadata records past the cap of {} records or {} bytes", dropped, MAX_METADATA_RECORDS, MAX_METADATA_BYTES);
        if (loops >= 0) this.metadata.put(CodecsAPI.METAKEY_LOOP_COUNT, loops);
        extensions.replaceAll((identifier, payloads) -> List.copyOf(payloads));
        this.metadata.put(CodecsAPI.GIF_METAKEY_APPLICATION_EXTENSION, Collections.unmodifiableMap(extensions));
        LOGGER.debug(IT, "GIF scanned: {} frames, loop count {}, {} metadata records", delays.size(), loops, records);

        // ONE FRAME IS A STILL IMAGE WHATEVER ITS GCE OR LOOPING EXTENSION DECLARE
        if (delays.size() <= 1) return ImageData.Scan.EMPTY;
        final long[] delayArray = new long[delays.size()];
        for (int i = 0; i < delayArray.length; i++) delayArray[i] = delays.get(i);
        // NETSCAPE COUNTS EXTRA LOOPS AFTER THE FIRST PLAY WHILE THE API COUNTS PLAYS; 0 LOOPS FOREVER IN BOTH
        return ImageData.Scan.of(delayArray, loops < 0 ? 1 : loops == 0 ? ImageData.REPEAT_FOREVER : loops + 1);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // BROWSERS STRETCH GCE DELAYS OF 10 MS OR LESS (INCLUDING A MISSING GCE) TO 100 MS; GIFS ARE AUTHORED AGAINST THAT RULE
    private static long delayOf(final int centiseconds) {
        final long ms = (long) centiseconds * DELAY_UNIT_MS;
        return ms <= DELAY_UNIT_MS ? BROWSER_DELAY_MS : ms;
    }

    // PAINTS ONE DESCRIPTOR ROW ONTO THE CANVAS AND RETURNS THE INDEX OF THE NEXT ROW; A ROW OUTSIDE THE CLIP IS
    // ONLY SKIPPED AND A TRANSPARENT INDEX LEAVES THE CANVAS PIXEL UNTOUCHED
    private static int blitRow(final byte[] indexes, final int start, final int idWidth,
                               final int idLeft, final int canvasY,
                               final int[] canvas, final int canvasWidth,
                               final int clipXStart, final int clipXEnd,
                               final int clipYStart, final int clipYEnd,
                               final boolean rowFullyVisible,
                               final int[] colors, final int colorLen,
                               final int transparentIndex) {
        if (canvasY < clipYStart || canvasY >= clipYEnd) return start + idWidth;
        int srcIdx = start;
        final int rowOff = canvasY * canvasWidth;
        if (rowFullyVisible) {
            if (transparentIndex < 0) {
                for (int x = 0; x < idWidth; x++) {
                    final int ci = indexes[srcIdx++] & 0xFF;
                    if (ci < colorLen) canvas[rowOff + idLeft + x] = colors[ci];
                }
            } else {
                for (int x = 0; x < idWidth; x++) {
                    final int ci = indexes[srcIdx++] & 0xFF;
                    if (ci == transparentIndex) continue;
                    if (ci < colorLen) canvas[rowOff + idLeft + x] = colors[ci];
                }
            }
            return srcIdx;
        }
        // PARTIAL HORIZONTAL CLIP: SKIP LEADING/TRAILING PIXELS THAT FALL OUTSIDE THE CANVAS
        for (int x = 0; x < idWidth; x++) {
            final int canvasX = idLeft + x;
            if (canvasX < clipXStart || canvasX >= clipXEnd) { srcIdx++; continue; }
            final int ci = indexes[srcIdx++] & 0xFF;
            if (ci == transparentIndex) continue;
            if (ci < colorLen) canvas[rowOff + canvasX] = colors[ci];
        }
        return srcIdx;
    }

    // WALKS THE SUB-BLOCK CHAIN AT THE BUFFER POSITION PAST ITS TERMINATOR AND RETURNS ITS PAYLOAD SIZE, OR -1
    // WHEN THE CHAIN RUNS PAST THE BUFFER, LEAVING THE POSITION AT THE LIMIT
    private static int skipBlocks(final ByteBuffer buffer) {
        int total = 0;
        while (buffer.hasRemaining()) {
            final int size = buffer.get() & 0xFF;
            if (size == 0) return total;
            if (buffer.remaining() < size) break;
            buffer.position(buffer.position() + size);
            total += size;
        }
        buffer.position(buffer.limit());
        return -1;
    }

    // COPIES total PAYLOAD BYTES OF AN ALREADY WALKED SUB-BLOCK CHAIN STARTING AT from, DROPPING THE SIZE BYTES
    private static byte[] blocks(final ByteBuffer buffer, final int from, final int total) {
        final byte[] payload = new byte[total];
        for (int p = from, o = 0; o < total; ) {
            final int size = Math.min(buffer.get(p) & 0xFF, total - o);
            buffer.get(p + 1, payload, o, size);
            o += size;
            p += size + 1;
        }
        return payload;
    }
}
