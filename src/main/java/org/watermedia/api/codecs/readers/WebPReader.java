package org.watermedia.api.codecs.readers;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.common.Canvas;
import org.watermedia.api.codecs.common.Limits;
import org.watermedia.api.codecs.common.Tiff;
import org.watermedia.api.codecs.common.Xmp;
import org.watermedia.api.codecs.readers.webp.AlphaDecoder;
import org.watermedia.api.codecs.readers.webp.lossless.VP8LosslessDecoder;
import org.watermedia.api.codecs.readers.webp.lossy.VP8LossyDecoder;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * Streaming WebP reader over the bytes past the 12-byte {@code RIFF????WEBP} preamble (the last chunk may omit its pad
 * byte): simple lossy {@code VP8 } and lossless {@code VP8L} stills, extended {@code VP8X} stills, and {@code ANMF}
 * animations composited one frame per {@link #next()}. Frames are BGRA, already rotated into their EXIF orientation.
 */
public final class WebPReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(WebPReader.class.getSimpleName());
    private static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

    // SECURITY CAPS
    // AN ANMF FRAME COSTS A FULL-CANVAS COMPOSITE AND COPY WHATEVER ITS SIZE: BOUNDED PER PASS, AS IN GifReader
    private static final int MAX_FRAMES = 4_096;
    // METADATA LIVES AS LONG AS THE READER: A LARGER ICCP, EXIF OR XMP CHUNK IS SKIPPED, NOT REJECTED
    private static final int MAX_METADATA_BYTES = 8 << 20;

    // CONTAINER LAYOUT
    private static final int CHUNK_HEADER_BYTES = 8;
    private static final int VP8X_BYTES = 10;
    private static final int ANIM_BYTES = 6;
    private static final int ANMF_HEADER_BYTES = 16;
    private static final int VP8L_HEADER_BYTES = 5;
    private static final int VP8L_SIGNATURE = 0x2F;
    private static final int ANIMATION_FLAG = 0x02;
    private static final int ALPHA_FLAG = 0x10;
    private static final byte[] EXIF_PREFIX = { 'E', 'x', 'i', 'f', 0, 0 };

    private final int canvasWidth;
    private final int canvasHeight;
    private final boolean animated;
    private final int orientation;
    private final int bodyStart;           // FIRST CHUNK AFTER THE HEADER CHUNK; reset() REPLAYS ANIMATIONS FROM HERE
    private final ImageData.Scan scan;
    private final boolean[] repairWarned = new boolean[1]; // VP8 PARTITION REPAIRS ARE LOGGED ONCE PER READER

    // STILLS: THE BITSTREAM AND OPTIONAL ALPH CHUNKS, SLICED FROM THE SOURCE
    private ByteBuffer bitstream;
    private int bitstreamFourCC;
    private ByteBuffer alpha;
    private boolean delivered;

    // ANIMATIONS: COMPOSITING TARGETS, ALLOCATED ON THE FIRST FRAME SO A VP8X DECLARING A CANVAS BUT NO FRAME COSTS NOTHING
    private int[] canvas;
    private ByteBuffer directOut;
    private IntBuffer directOutInts;
    private ByteBuffer pendingFrame;       // BODY OF THE ANMF CHUNK hasMore() LOCATED
    private int frames;                    // FRAMES YIELDED IN THE CURRENT PASS
    private ByteBuffer oriented;           // EXIF-REMAPPED DELIVERY TARGET, ALLOCATED ON THE FIRST FRAME WHEN THE TAG IS NOT 1

    public WebPReader(final ByteBuffer data) throws IOException { this(data, null); }

    public WebPReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        this.data.order(LE);
        final Chunk header = chunk(this.data);
        final int width, height, flags;
        switch (header.fourCC()) {
            case FourCC.VP8 -> {
                this.bitstreamFourCC = FourCC.VP8;
                this.bitstream = this.data.slice(header.at(), header.size());
                // A WEBP STILL IS ONE VP8 KEY FRAME: TAG BIT 0 CLEAR, THE 9D 01 2A START CODE, THEN TWO 14-BIT SIZES
                if (header.size() < 10) throw new CodecException("VP8 chunk too small: " + header.size());
                if ((this.bitstream.get(0) & 1) != 0) throw new UnsupportedFormatException("VP8 interframes are not supported, only key frames");
                if ((this.bitstream.get(3) & 0xFF) != 0x9D || (this.bitstream.get(4) & 0xFF) != 0x01 || (this.bitstream.get(5) & 0xFF) != 0x2A)
                    throw new CodecException("Invalid VP8 start code");
                width = DataTool.u16(this.bitstream, 6, LE) & 0x3FFF;
                height = DataTool.u16(this.bitstream, 8, LE) & 0x3FFF;
                flags = 0;
            }
            case FourCC.VP8L -> {
                this.bitstreamFourCC = FourCC.VP8L;
                this.bitstream = this.data.slice(header.at(), header.size());
                final int bits = vp8lHeader(this.bitstream);
                width = (bits & 0x3FFF) + 1;
                height = ((bits >> 14) & 0x3FFF) + 1;
                flags = ((bits >>> 28) & 1) != 0 ? ALPHA_FLAG : 0;
            }
            case FourCC.VP8X -> {
                if (header.size() < VP8X_BYTES) throw new CodecException("VP8X chunk too small: " + header.size());
                flags = this.data.get(header.at()) & 0xFF;
                width = DataTool.u24(this.data, header.at() + 4, LE) + 1;
                height = DataTool.u24(this.data, header.at() + 7, LE) + 1;
            }
            default -> throw new CodecException("Unknown WebP first chunk: " + FourCC.fourCCString(header.fourCC()));
        }

        // CENTRAL SIZE GATE BEFORE ANY ALLOCATION: SIMPLE VP8 CAN DECLARE 0, AND THE 24-BIT VP8X AXES
        // MULTIPLY INTO GB-SCALE BUFFERS FROM A FEW-BYTE FILE
        if (!Limits.fits(width, height))
            throw new CodecException("Invalid WEBP dimensions: " + width + "x" + height
                    + " (max " + Limits.MAX_DIM + " per axis, " + Limits.MAX_PIXELS + " pixels)");
        this.canvasWidth = width;
        this.canvasHeight = height;
        this.animated = header.fourCC() == FourCC.VP8X && (flags & ANIMATION_FLAG) != 0;
        this.bodyStart = this.data.position();
        this.scan = header.fourCC() == FourCC.VP8X ? this.scan(this.data.duplicate().order(LE)) : ImageData.Scan.EMPTY;

        if (header.fourCC() == FourCC.VP8X && !this.animated) {
            // AN EXTENDED STILL: KEEP THE BITSTREAM AND ITS OPTIONAL ALPH; scan() ALREADY READ THE METADATA
            while (this.data.hasRemaining()) {
                final Chunk c = chunk(this.data);
                if (c.fourCC() == FourCC.VP8 || c.fourCC() == FourCC.VP8L) {
                    this.bitstreamFourCC = c.fourCC();
                    this.bitstream = this.data.slice(c.at(), c.size());
                } else if (c.fourCC() == FourCC.ALPH) {
                    this.alpha = this.data.slice(c.at(), c.size());
                }
            }
            if (this.bitstream == null) throw new CodecException("Truncated extended WEBP (no bitstream chunk)");
        }
        // ONLY VP8X CARRIES EXIF; THE ORIENTATION DECIDES width()/height() BEFORE ANY FRAME IS DELIVERED
        this.orientation = this.metadata.value(CodecsAPI.METAKEY_ORIENTATION) instanceof final Integer tag ? tag : 1;

        LOGGER.debug(IT, "WEBP opened: {}x{} codec={} animated={} alpha={} frames={} orientation={}",
                width, height, FourCC.fourCCString(this.bitstreamFourCC), this.animated, (flags & ALPHA_FLAG) != 0,
                this.scan.frameCount(), this.orientation);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override public int width() { return this.orientation >= 5 ? this.canvasHeight : this.canvasWidth; }
    @Override public int height() { return this.orientation >= 5 ? this.canvasWidth : this.canvasHeight; }
    @Override public ImageData.Scan scan() { return this.scan; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override protected PixelFormat decodedFormat() { return PixelFormat.BGRA; }

    @Override
    protected boolean hasMore() throws IOException {
        if (!this.animated) return !this.delivered;
        // LOCATE THE NEXT ANMF; ANIM, METADATA AND UNKNOWN CHUNKS WERE ALREADY READ BY scan()
        while (this.pendingFrame == null && this.data.hasRemaining()) {
            final Chunk c = chunk(this.data);
            if (c.fourCC() != FourCC.ANMF) continue;
            // COUNTED ONCE PER FRAME: pendingFrame ABSORBS REPEATED hasNext() CALLS
            if (++this.frames > MAX_FRAMES)
                throw new CodecException("WEBP frame count exceeds limit: " + this.frames + " (max " + MAX_FRAMES + ")");
            this.pendingFrame = this.data.slice(c.at(), c.size());
        }
        return this.pendingFrame != null;
    }

    @Override
    protected ByteBuffer decode() throws IOException {
        if (this.animated) {
            final ByteBuffer anmf = this.pendingFrame;
            this.pendingFrame = null;
            this.decodeAnimFrame(anmf);
            return this.orient(this.directOut);
        }
        this.delivered = true;
        LOGGER.debug(IT, "Decoding static {} frame {}x{} (alpha chunk={})",
                FourCC.fourCCString(this.bitstreamFourCC), this.canvasWidth, this.canvasHeight, this.alpha != null);
        final ByteBuffer frame;
        if (this.bitstreamFourCC == FourCC.VP8L) {
            frame = DataTool.bgraToBuffer(VP8LosslessDecoder.decode(vp8lBody(this.bitstream, this.canvasWidth, this.canvasHeight), this.canvasWidth, this.canvasHeight));
        } else {
            frame = VP8LossyDecoder.decodeToBgra(this.bitstream, this.canvasWidth, this.canvasHeight, this.repairWarned);
            if (this.alpha != null && this.alpha.hasRemaining())
                AlphaDecoder.applyAlpha(frame, AlphaDecoder.decode(this.alpha, this.canvasWidth, this.canvasHeight));
        }
        frame.position(0).limit(this.canvasWidth * this.canvasHeight * 4);
        return this.orient(frame);
    }

    @Override
    protected boolean rewind() {
        // STILLS KEEP THEIR BITSTREAM SLICED, SO ONLY THE DELIVERY FLAG MATTERS; THE ANIMATED CANVAS RESTARTS
        // FULLY TRANSPARENT (THE ANIM BACKGROUND COLOR IS ONLY A HINT AND IS NEVER APPLIED)
        this.data.position(this.bodyStart);
        this.delivered = false;
        this.pendingFrame = null;
        this.frames = 0; // THE FRAME CAP BOUNDS ONE PASS, NOT THE LIFETIME OF A FOREVER-LOOPING READER
        if (this.canvas != null) Arrays.fill(this.canvas, 0);
        return true;
    }

    // ==========================================================================
    // DECODE
    // ==========================================================================
    private void decodeAnimFrame(final ByteBuffer anmf) throws IOException {
        final int length = anmf.limit();
        if (length < ANMF_HEADER_BYTES) throw new CodecException("Truncated ANMF chunk");

        // 16-BYTE ANMF HEADER: HALVED X/Y OFFSETS, MINUS-ONE SIZES AND THE DURATION AS 24-BIT FIELDS, THEN THE FLAGS
        final int frameX = DataTool.u24(anmf, 0, LE) * 2;
        final int frameY = DataTool.u24(anmf, 3, LE) * 2;
        final int frameW = DataTool.u24(anmf, 6, LE) + 1;
        final int frameH = DataTool.u24(anmf, 9, LE) + 1;
        // SPEC: THE FRAME RECTANGLE MUST LIE FULLY INSIDE THE CANVAS. THIS ALSO BOUNDS THE
        // PER-FRAME PIXEL ALLOCATION BY THE ALREADY-VALIDATED CANVAS SIZE
        if (frameX + frameW > this.canvasWidth || frameY + frameH > this.canvasHeight)
            throw new CodecException("ANMF frame outside canvas: " + frameW + "x" + frameH + " at (" + frameX + "," + frameY
                    + ") on " + this.canvasWidth + "x" + this.canvasHeight);
        final int duration = DataTool.u24(anmf, 12, LE);
        final int flags = anmf.get(15) & 0xFF;
        final boolean blend = (flags & 0x02) == 0;
        final boolean dispose = (flags & 0x01) != 0;

        // SUB-CHUNKS: OPTIONAL ALPH, THEN VP8 OR VP8L. SIZES ARE UNSIGNED AND ATTACKER-CONTROLLED: ONE PAST THE BODY ENDS THE WALK
        ByteBuffer frameBits = null;
        ByteBuffer frameAlpha = null;
        int frameFourCC = 0;
        int off = ANMF_HEADER_BYTES;
        while (off + CHUNK_HEADER_BYTES <= length) {
            final int fourCC = (int) DataTool.u32(anmf, off, LE);
            final long size = DataTool.u32(anmf, off + 4, LE);
            final int body = off + CHUNK_HEADER_BYTES;
            if (size > length - body) break;
            if (fourCC == FourCC.VP8 || fourCC == FourCC.VP8L) {
                frameFourCC = fourCC;
                frameBits = anmf.slice(body, (int) size);
            } else if (fourCC == FourCC.ALPH) {
                frameAlpha = anmf.slice(body, (int) size);
            }
            off = body + (int) size + (int) (size & 1);
        }
        if (frameBits == null || !frameBits.hasRemaining()) throw new CodecException("ANMF without VP8/VP8L sub-chunk");

        if (this.canvas == null) {
            this.canvas = new int[this.canvasWidth * this.canvasHeight];
            this.directOut = ByteBuffer.allocateDirect(this.canvas.length * 4).order(LE);
            this.directOutInts = this.directOut.asIntBuffer();
        }
        LOGGER.debug(IT, "Decoding ANMF: codec={} pos=({},{}) size={}x{} duration={}ms blend={} dispose={} alpha={}",
                FourCC.fourCCString(frameFourCC), frameX, frameY, frameW, frameH, duration, blend, dispose, frameAlpha != null);

        final int[] pixels;
        if (frameFourCC == FourCC.VP8L) {
            pixels = VP8LosslessDecoder.decode(vp8lBody(frameBits, frameW, frameH), frameW, frameH);
        } else {
            pixels = VP8LossyDecoder.decode(frameBits, frameW, frameH, this.repairWarned);
            if (frameAlpha != null && frameAlpha.hasRemaining()) AlphaDecoder.applyAlpha(pixels, AlphaDecoder.decode(frameAlpha, frameW, frameH));
        }

        // COMPOSITE: THE RECTANGLE WAS PROVEN INSIDE THE CANVAS, SO NO ROW NEEDS CLIPPING
        for (int y = 0; y < frameH; y++) {
            final int src = y * frameW;
            final int dst = (frameY + y) * this.canvasWidth + frameX;
            if (!blend) {
                System.arraycopy(pixels, src, this.canvas, dst, frameW);
                continue;
            }
            for (int x = 0; x < frameW; x++) this.canvas[dst + x] = DataTool.blendOver(pixels[src + x], this.canvas[dst + x]);
        }
        Canvas.write(this.canvas, this.canvas.length, this.directOutInts, this.directOut);
        // AN ANIMATION OF ONE FRAME IS A STILL IMAGE: ITS ANMF DURATION TIMES NOTHING
        this.delayMs = this.scan.frameCount() > 1 ? duration : 0L;
        // DISPOSAL CLEARS THE RECTANGLE TO TRANSPARENT FOR THE NEXT FRAME
        if (dispose) Canvas.fill(this.canvas, this.canvasWidth, this.canvasHeight, frameX, frameY, frameW, frameH, 0);
    }

    // DELIVERS THE FRAME REMAPPED INTO ITS EXIF ORIENTATION; THE TARGET IS ALLOCATED ONCE AND REUSED PER FRAME
    private ByteBuffer orient(final ByteBuffer frame) {
        if (this.orientation == 1) return frame;
        if (this.oriented == null) this.oriented = ByteBuffer.allocateDirect(this.canvasWidth * this.canvasHeight * 4).order(LE);
        DataTool.orient(frame, this.oriented, this.canvasWidth, this.canvasHeight, 4, this.orientation);
        return this.oriented;
    }

    // ==========================================================================
    // CHUNK SCAN
    // ==========================================================================
    // WALKS THE CHUNKS AFTER VP8X ONCE: ANMF DURATIONS, THE ANIM LOOP COUNT AND THE ICCP, EXIF AND XMP METADATA, EACH
    // KEPT ONCE (THE FIRST CHUNK WINS). A MALFORMED CHUNK ENDS THE WALK AND IS LEFT FOR THE STRICT WALKS TO REPORT
    private ImageData.Scan scan(final ByteBuffer buffer) {
        final List<Long> delays = new ArrayList<>();
        int loops = -1;
        int dropped = 0;
        try {
            // ONE FRAME PAST THE CAP IS ENOUGH: THE DECODER REFUSES THAT FRAME ANYWAY
            while (buffer.hasRemaining() && delays.size() <= MAX_FRAMES) {
                final Chunk c = chunk(buffer);
                final String key = switch (c.fourCC()) {
                    case FourCC.ICCP -> CodecsAPI.METAKEY_ICC_PROFILE;
                    case FourCC.EXIF -> CodecsAPI.METAKEY_EXIF;
                    case FourCC.XMP -> CodecsAPI.METAKEY_XMP;
                    default -> null;
                };
                if (c.fourCC() == FourCC.ANMF) {
                    // EVERY ANMF COUNTS, EVEN A SHORT ONE THE DECODER WILL REFUSE, SO BOTH WALKS AGREE ON THE FRAME COUNT
                    delays.add(c.size() >= ANMF_HEADER_BYTES ? DataTool.u24(buffer, c.at() + 12, LE) : 0L);
                } else if (c.fourCC() == FourCC.ANIM && this.animated && loops < 0 && c.size() >= ANIM_BYTES) {
                    // THE SPEC IGNORES AN ANIM CHUNK WHEN THE VP8X ANIMATION FLAG IS CLEAR
                    loops = DataTool.u16(buffer, c.at() + 4, LE);
                } else if (key != null && this.metadata.value(key) == null) {
                    if (c.size() > MAX_METADATA_BYTES) {
                        dropped++;
                        continue;
                    }
                    // SOME WRITERS KEEP JPEG'S "Exif\0\0" PREFIX; THE PUBLISHED EXIF STARTS AT ITS TIFF HEADER
                    final int skip = key.equals(CodecsAPI.METAKEY_EXIF) && DataTool.startsWith(buffer, c.at(), EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
                    final byte[] payload = new byte[c.size() - skip];
                    buffer.get(c.at() + skip, payload);
                    this.metadata.put(key, payload);
                    if (key.equals(CodecsAPI.METAKEY_EXIF)) this.metadata.put(CodecsAPI.METAKEY_ORIENTATION, Tiff.orientation(payload));
                    else if (key.equals(CodecsAPI.METAKEY_XMP)) Xmp.read(this.metadata, payload);
                }
            }
        } catch (final CodecException malformed) {
            // THE CHUNKS BEFORE THE DAMAGE STILL COUNT; THE STRICT WALKS REPORT THE DAMAGE WHEN THEY GET THERE
        }

        if (dropped > 0) LOGGER.warn(IT, "Skipped {} WEBP metadata chunks larger than {} bytes", dropped, MAX_METADATA_BYTES);
        if (loops >= 0) this.metadata.put(CodecsAPI.METAKEY_LOOP_COUNT, loops);
        // ONE FRAME IS A STILL IMAGE WHATEVER ITS ANMF DURATION OR ANIM LOOP COUNT DECLARE
        if (!this.animated || delays.size() <= 1) return ImageData.Scan.EMPTY;
        final long[] delayArray = new long[delays.size()];
        for (int i = 0; i < delayArray.length; i++) delayArray[i] = delays.get(i);
        // THE ANIM LOOP COUNT ALREADY COUNTS PLAYS (0 LOOPS FOREVER); WITHOUT AN ANIM CHUNK THE ANIMATION PLAYS ONCE
        return ImageData.Scan.of(delayArray, loops < 0 ? 1 : loops);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // READS THE CHUNK HEADER AT THE POSITION AND MOVES PAST THE BODY AND ITS PAD BYTE, WHICH THE LAST CHUNK MAY
    // OMIT; A BODY RUNNING PAST THE BUFFER (THE RIFF SIZE BOUNDS IT) IS TRUNCATION
    private static Chunk chunk(final ByteBuffer in) throws CodecException {
        if (in.remaining() < CHUNK_HEADER_BYTES)
            throw new CodecException("Truncated WEBP chunk header: " + in.remaining() + " of " + CHUNK_HEADER_BYTES + " bytes");
        final int fourCC = in.getInt();
        final int size = in.getInt();
        if (size < 0 || size > in.remaining())
            throw new CodecException("Truncated WEBP chunk " + FourCC.fourCCString(fourCC) + ": declared "
                    + Integer.toUnsignedString(size) + " bytes, " + in.remaining() + " available");
        final int at = in.position();
        in.position(Math.min(at + size + (size & 1), in.limit()));
        return new Chunk(fourCC, at, size);
    }

    // VALIDATES THE 5-BYTE VP8L HEADER (SIGNATURE, VERSION 0) AND RETURNS ITS PACKED 14-BIT SIZES AND ALPHA HINT
    private static int vp8lHeader(final ByteBuffer bitstream) throws CodecException {
        if (bitstream.limit() < VP8L_HEADER_BYTES) throw new CodecException("VP8L bitstream too small: " + bitstream.limit());
        if ((bitstream.get(0) & 0xFF) != VP8L_SIGNATURE) throw new CodecException("Invalid VP8L signature");
        final int bits = (int) DataTool.u32(bitstream, 1, LE);
        if (bits >>> 29 != 0) throw new UnsupportedFormatException("Unsupported VP8L version: " + (bits >>> 29));
        return bits;
    }

    // HANDS BACK THE BITSTREAM PAST THE VP8L HEADER. OUTPUT BUFFERS ARE SIZED FROM THE CONTAINER WHILE THE HEADER
    // CARRIES ITS OWN SIZE, SO THE SPEC REQUIRES BOTH TO AGREE: A MISMATCH WOULD OVER/UNDERRUN THEM
    private static ByteBuffer vp8lBody(final ByteBuffer bitstream, final int w, final int h) throws CodecException {
        final int bits = vp8lHeader(bitstream);
        final int bw = (bits & 0x3FFF) + 1;
        final int bh = ((bits >> 14) & 0x3FFF) + 1;
        if (bw != w || bh != h) throw new CodecException("VP8L bitstream is " + bw + "x" + bh + ", container declares " + w + "x" + h);
        return bitstream.slice(VP8L_HEADER_BYTES, bitstream.limit() - VP8L_HEADER_BYTES);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // A RIFF CHUNK: ITS IDENTIFIER AND WHERE ITS BODY LIES IN THE SOURCE
    private record Chunk(int fourCC, int at, int size) {}

    // RIFF CHUNK IDENTIFIERS AS LITTLE-ENDIAN INTS, PLUS THEIR ASCII SPELLING
    static final class FourCC {
        static final int VP8 = 0x20385056; // 'VP8 '
        static final int VP8L = 0x4C385056; // 'VP8L'
        static final int VP8X = 0x58385056; // 'VP8X'
        static final int ANIM = 0x4D494E41; // 'ANIM'
        static final int ANMF = 0x464D4E41; // 'ANMF'
        static final int ALPH = 0x48504C41; // 'ALPH'
        static final int ICCP = 0x50434349; // 'ICCP'
        static final int EXIF = 0x46495845; // 'EXIF'
        static final int XMP = 0x20504D58; // 'XMP '

        private FourCC() {}

        // DECODES THE FOUR LITTLE-ENDIAN ASCII BYTES OF A CHUNK IDENTIFIER
        static String fourCCString(final int fourCC) {
            return new String(new char[] {
                    (char) (fourCC & 0xFF),
                    (char) ((fourCC >> 8) & 0xFF),
                    (char) ((fourCC >> 16) & 0xFF),
                    (char) ((fourCC >> 24) & 0xFF)
            });
        }
    }
}
