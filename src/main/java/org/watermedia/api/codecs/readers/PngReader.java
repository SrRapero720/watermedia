package org.watermedia.api.codecs.readers;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.common.Canvas;
import org.watermedia.api.codecs.common.Xmp;
import org.watermedia.api.codecs.common.png.ACTL;
import org.watermedia.api.codecs.common.png.BKGD;
import org.watermedia.api.codecs.common.png.CHRM;
import org.watermedia.api.codecs.common.png.CICP;
import org.watermedia.api.codecs.common.png.CLLI;
import org.watermedia.api.codecs.common.png.ColorType;
import org.watermedia.api.codecs.common.png.EXIF;
import org.watermedia.api.codecs.common.png.FCTL;
import org.watermedia.api.codecs.common.png.FDAT;
import org.watermedia.api.codecs.common.png.GAMA;
import org.watermedia.api.codecs.common.png.HIST;
import org.watermedia.api.codecs.common.png.ICCP;
import org.watermedia.api.codecs.common.png.IDAT;
import org.watermedia.api.codecs.common.png.IEND;
import org.watermedia.api.codecs.common.png.IHDR;
import org.watermedia.api.codecs.common.png.ITXT;
import org.watermedia.api.codecs.common.png.MDCV;
import org.watermedia.api.codecs.common.png.PHYS;
import org.watermedia.api.codecs.common.png.PLTE;
import org.watermedia.api.codecs.common.png.PngChunk;
import org.watermedia.api.codecs.common.png.SBIT;
import org.watermedia.api.codecs.common.png.SPLT;
import org.watermedia.api.codecs.common.png.SRGB;
import org.watermedia.api.codecs.common.png.TEXT;
import org.watermedia.api.codecs.common.png.TIME;
import org.watermedia.api.codecs.common.png.TRNS;
import org.watermedia.api.codecs.common.png.ZTXT;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.common.Limits.MAX_DIM;
import static org.watermedia.api.codecs.common.Limits.MAX_PIXELS;
import static org.watermedia.api.codecs.common.png.BKGD.scaleTo8Bit;

/**
 * Streaming PNG / APNG reader following PNG Specification Third Edition (W3C Recommendation 24 June 2025).
 *
 * <p>Receives a {@link ByteBuffer} positioned immediately after the 8-byte PNG signature
 * (the signature is consumed by {@code CodecsAPI} before construction). The constructor reads
 * chunks until the first {@code IDAT} or {@code fcTL}, decoding header / palette / animation
 * control / metadata. Each {@link #next()} pulls exactly the chunks for the next frame.
 *
 * @see <a href="https://www.w3.org/TR/png-3/">PNG Specification Third Edition</a>
 */
public final class PngReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(PngReader.class.getSimpleName());

    // SECURITY CAPS
    private static final int MAX_ANCILLARY_CHUNKS = 64;
    private static final long MAX_ANCILLARY_BYTES = 4L << 20;
    private static final int MAX_TEXT_ENTRIES = 256;
    private static final long MAX_TEXT_BYTES = 4L << 20;
    private static final int MAX_INFLATED_IMAGE_BYTES = Integer.MAX_VALUE - 8; // JAVA ARRAY-SIZE SAFE UPPER BOUND
    private static final int MIN_INFLATE_BUFFER_BYTES = 64 * 1_024; // FLOOR FOR THE INPUT-DERIVED INFLATE BUFFER
    private static final int INFLATE_RATIO_GUESS = 32; // ASSUMED DEFLATE RATIO WHEN SIZING THAT BUFFER

    // THE iTXt KEYWORD THAT CARRIES AN XMP PACKET
    private static final String XMP_KEYWORD = "XML:com.adobe.xmp";

    // FILTER TYPES (FILTER METHOD 0)
    private static final int FILTER_NONE = 0;
    private static final int FILTER_SUB = 1;
    private static final int FILTER_UP = 2;
    private static final int FILTER_AVERAGE = 3;
    private static final int FILTER_PAETH = 4;

    // ADAM7 INTERLACING PASS PARAMETERS
    private static final int[] ADAM7_X_START = {0, 4, 0, 2, 0, 1, 0};
    private static final int[] ADAM7_Y_START = {0, 0, 4, 0, 2, 0, 1};
    private static final int[] ADAM7_X_STEP = {8, 8, 4, 4, 2, 2, 1};
    private static final int[] ADAM7_Y_STEP = {8, 8, 8, 4, 4, 2, 2};

    // GAMMA
    private static final float SRGB_GAMMA = 2.2f;
    private static final float DEFAULT_GAMMA = 2.2f;
    private static final float DISPLAY_GAMMA = 2.2f;

    // PARSED STATE
    private IHDR ihdr;
    private PLTE plte;
    private TRNS trns;
    private BKGD bkgd;
    private GAMA gamma;
    private SRGB srgb;
    private CICP cicp;
    private final Map<String, List<String>> texts = new HashMap<>();
    private final Map<String, List<String>> compressedTexts = new HashMap<>();
    private final Map<String, List<String>> internationalTexts = new HashMap<>();
    private final Map<String, List<byte[]>> ancillaryChunks = new HashMap<>();
    private final Map<String, int[]> suggestedPalettes = new HashMap<>();
    private final float[] gammaLUT;
    // METADATA BUDGETS AND THE ONE-TIME SKIP WARNING; metaMark IS THE FURTHEST POSITION WHOSE ANCILLARY CHUNKS WERE HANDLED
    private int metaMark;
    private int ancillaryRetained;
    private long ancillaryBytes;
    private int textEntries;
    private long textBytes;
    private boolean ancillaryWarned;
    private boolean fctlSeen;

    // STREAMING STATE
    private PngChunk pendingChunk; // NEXT CHUNK TO CONSUME AT START OF next()
    private boolean done; // TRUE ONCE IEND SEEN OR EOF REACHED
    private int animFrames; // fcTL-DRIVEN FRAMES EMITTED SO FAR, CHECKED AGAINST scan.frameCount()
    private final ImageData.Scan scan;

    // RESET SNAPSHOT: STREAM STATE RIGHT AFTER CONSTRUCTION (FRAME 0 BOUNDARY)
    private final int resetPos;
    private final PngChunk resetChunk;
    private final boolean resetDone;

    // CANVAS / OUTPUT (1-D ARGB INT BUFFERS, STRIDE = canvasWidth)
    private final int canvasWidth;
    private final int canvasHeight;
    private final int[] outputBuffer; // COMPOSITED CANVAS (ARGB)
    private int[] previousBuffer; // LAZILY SAVED CANVAS FOR DISPOSE_OP_PREVIOUS
    private int[] frameBuffer; // LAZILY ALLOCATED APNG FRAME DECODE TARGET
    private final ByteBuffer directOut; // BGRA RESULT, REUSED
    private final IntBuffer directOutInts;

    // ROW SCRATCH
    private byte[] passCurrentRow = new byte[0];
    private byte[] passPreviousRow = new byte[0];

    // INFLATE
    private Inflater inflater; // CREATED AT THE FIRST DECODE, SO A REJECTED STREAM NEVER ALLOCATES NATIVE STATE
    private byte[] decompressed = new byte[0]; // REUSED ACROSS FRAMES

    // PRECOMPUTED HOT-PATH INVARIANTS (COMPUTED IN THE CONSTRUCTOR)
    private final ColorType colorType;
    private final int depth;
    private final int bytesPerPixel;
    private int[] indexedARGB; // 256-ENTRY PALETTE LUT (ARGB WITH tRNS ALPHA BAKED IN)
    private int trnsR8 = -1, trnsG8 = -1, trnsB8 = -1, trnsGray8 = -1;

    public PngReader(final ByteBuffer data) throws IOException { this(data, null); }

    public PngReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        this.data.order(ByteOrder.BIG_ENDIAN);
        this.scan = this.scan(this.data.duplicate().order(ByteOrder.BIG_ENDIAN));

        this.parseUntilFirstFrame();
        if (this.ihdr == null) throw new CodecException("Missing IHDR chunk");
        // AN IDAT AHEAD OF THE FIRST fcTL IS THE DEFAULT IMAGE OF AN ANIMATION, NOT ONE OF ITS FRAMES
        if (this.fctlSeen && this.pendingChunk != null && this.pendingChunk.type() == IDAT.SIGNATURE) this.skipDefaultImage();
        // FAIL BEFORE THE CANVAS IS ALLOCATED: A STREAM THAT REACHES IEND OR EOF WITHOUT IDAT/fcTL HAS
        // NO FRAME TO DECODE, SO PAYING FOR THE BUFFERS FIRST WOULD ONLY BE A FREE ALLOCATION FOR AN ATTACKER
        if (this.pendingChunk == null) throw new CodecException("PNG stream contains no IDAT");

        // SNAPSHOT THE FRAME-0 BOUNDARY SO reset() CAN REPLAY WITHOUT RE-PARSING THE HEADER
        this.resetPos = this.data.position();
        this.resetChunk = this.pendingChunk;
        this.resetDone = this.done;
        this.metaMark = this.resetPos;

        this.gammaLUT = buildGammaLUT(this.gamma, this.srgb, this.cicp);
        this.canvasWidth = this.ihdr.width();
        this.canvasHeight = this.ihdr.height();
        this.colorType = ColorType.of(this.ihdr.colorType());
        this.depth = this.ihdr.depth();
        this.bytesPerPixel = this.ihdr.bytesPerPixel();

        final int pixelCount = this.canvasWidth * this.canvasHeight;
        this.outputBuffer = new int[pixelCount];
        this.directOut = ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.LITTLE_ENDIAN);
        this.directOutInts = this.directOut.asIntBuffer();

        // VALIDATE INDEXED bKGD AGAINST THE PALETTE BEFORE ANY background() LOOKUP (PLTE.color IS UNCHECKED)
        if (this.bkgd != null && this.bkgd.isIndexed()) {
            if (this.plte == null) throw new CodecException("Indexed bKGD without PLTE");
            if (this.bkgd.paletteIndex() >= this.plte.size())
                throw new CodecException("bKGD palette index out of range: " + this.bkgd.paletteIndex() + " (palette size " + this.plte.size() + ")");
        }

        // THE CANVAS STARTS AS THE bKGD COLOUR, OR TRANSPARENT FROM ITS ZERO INIT
        if (this.bkgd != null) Arrays.fill(this.outputBuffer, this.background());

        // tRNS KEYS SCALED TO 8 BITS FOR THE DEPTH-8 ROW WRITERS; INDEXED IMAGES BAKE tRNS INTO THE PALETTE LUT
        // AND THE ALPHA COLOUR TYPES IGNORE IT
        if (this.trns != null && this.colorType == ColorType.GREYSCALE) this.trnsGray8 = scaleTo8Bit(this.trns.gray(), this.depth);
        if (this.trns != null && this.colorType == ColorType.TRUECOLOR) {
            this.trnsR8 = scaleTo8Bit(this.trns.red(), this.depth);
            this.trnsG8 = scaleTo8Bit(this.trns.green(), this.depth);
            this.trnsB8 = scaleTo8Bit(this.trns.blue(), this.depth);
        }
        if (this.colorType == ColorType.INDEXED && this.plte != null) {
            final int[] palette = this.plte.colors();
            this.indexedARGB = new int[256];
            for (int i = 0; i < 256; i++) {
                final int rgb = (i < palette.length) ? palette[i] : 0;
                final int alpha = (this.trns != null) ? this.trns.alpha(i) : 255;
                this.indexedARGB[i] = (alpha << 24) | (rgb & 0x00FFFFFF);
            }
        }
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override public int width() { return this.ihdr.width(); }
    @Override public int height() { return this.ihdr.height(); }
    @Override public ImageData.Scan scan() { return this.scan; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override protected PixelFormat decodedFormat() { return PixelFormat.BGRA; }

    @Override
    protected boolean hasMore() {
        return !this.done && this.pendingChunk != null;
    }

    @Override
    protected ByteBuffer decode() throws IOException {
        FCTL fctl = null;
        final List<ChunkSlice> compressed = new ArrayList<>();

        // PROCESS THE INITIAL PENDING CHUNK
        PngChunk c = this.pendingChunk;
        this.pendingChunk = null;
        if (c.type() == FCTL.SIGNATURE) {
            fctl = FCTL.convert(c);
        } else if (c.type() == IDAT.SIGNATURE) {
            compressed.add(new ChunkSlice(c.data(), 0, c.data().length));
        } else {
            // UNREACHABLE IN A WELL-FORMED STREAM (parseUntilFirstFrame ONLY STOPS ON IDAT/fcTL), SO FAIL FAST
            this.done = true;
            throw new CodecException("Unexpected PNG frame boundary: " + c.typeName());
        }

        // READ CHUNKS UNTIL NEXT FRAME BOUNDARY (next fcTL) OR IEND / EOF
        while (true) {
            c = this.nextChunk();
            if (c == null) {
                this.done = true;
                break;
            }

            final int t = c.type();
            if (t == IDAT.SIGNATURE) {
                compressed.add(new ChunkSlice(c.data(), 0, c.data().length));
            } else if (t == FDAT.SIGNATURE) {
                final byte[] data = c.data();
                if (data.length < 4) throw new CodecException("Truncated fdAT chunk");
                compressed.add(new ChunkSlice(data, 4, data.length - 4));
            } else if (t == FCTL.SIGNATURE) {
                this.pendingChunk = c;
                break;
            } else if (t == IEND.SIGNATURE) {
                this.done = true;
                break;
            } else if (this.data.position() > this.metaMark) {
                // MID-STREAM METADATA OR UNKNOWN ANCILLARY; A REPLAY AFTER rewind() HANDLED EVERYTHING BEHIND THE MARK
                this.metaMark = this.data.position();
                this.handleAncillary(c);
            }
        }

        if (compressed.isEmpty()) {
            // A FRAME WITHOUT IDAT/fdAT IS MALFORMED: TERMINATE AND FAIL FAST INSTEAD OF SKIPPING IT
            this.done = true;
            throw new CodecException("PNG frame without compressed data");
        }

        // VALIDATE APNG FRAME GEOMETRY AGAINST THE CANVAS: UNCHECKED fcTL FIELDS OTHERWISE DRIVE
        // OUT-OF-BOUNDS WRITES IN decodeData / applyBlendOp / applyDispose
        if (fctl != null) {
            try {
                fctl.validate(this.canvasWidth, this.canvasHeight);
            } catch (final CodecException e) {
                this.done = true;
                throw e;
            }
            // THE FRAME COUNT WAS RECONCILED AGAINST THE fcTL RECORDS IN scan(); acTL num_frames ALONE IS
            // ATTACKER-CONTROLLED, AND A STREAM CARRYING MORE FRAMES THAN IT DECLARES IS AN ANIMATION BOMB.
            // ONLY fcTL-DRIVEN FRAMES COUNT: A LEADING IDAT DEFAULT IMAGE IS NOT PART OF THE ANIMATION.
            if (++this.animFrames > this.scan.frameCount()) {
                this.done = true;
                throw new CodecException("APNG declares " + this.scan.frameCount()
                        + " frames but the stream carries more");
            }
        }

        final IHDR frameIhdr = (fctl != null)
                ? new IHDR(fctl.width(), fctl.height(), this.ihdr.depth(), this.ihdr.colorType(),
                this.ihdr.compression(), this.ihdr.filter(), this.ihdr.interlace())
                : this.ihdr;

        // DECODE
        final int decompressedLen = this.inflate(compressed, frameIhdr);
        if (fctl != null && this.frameBuffer == null) this.frameBuffer = new int[this.outputBuffer.length];
        final int[] framePixels = (fctl != null) ? this.frameBuffer : this.outputBuffer;
        this.decodeData(this.decompressed, decompressedLen, frameIhdr, framePixels);

        if (this.gammaLUT != null)
            applyGammaCorrection(framePixels, frameIhdr.width(), frameIhdr.height(), this.canvasWidth, this.gammaLUT);
        // PRESENTING AGAINST bKGD: A FRAME BLENDED OVER THE ALREADY OPAQUE CANVAS NEEDS NO FLATTENING, WHILE A
        // STATIC PICTURE OR A SOURCE-BLENDED FRAME REPLACES THE CANVAS AND IS FLATTENED ONTO THE BACKGROUND FIRST
        if (this.bkgd != null && (fctl == null || (fctl.blend() & 0xFF) != FCTL.BLEND_OP_OVER))
            this.flattenAlpha(framePixels, frameIhdr.width(), frameIhdr.height(), this.canvasWidth);

        // APNG: SNAPSHOT FOR DISPOSE_OP_PREVIOUS, BLEND ONTO THE CANVAS, PRESENT IT, THEN DISPOSE THE FRAME RECTANGLE
        if (fctl != null) {
            if ((fctl.dispose() & 0xFF) == FCTL.DISPOSE_OP_PREVIOUS) {
                if (this.previousBuffer == null) this.previousBuffer = new int[this.outputBuffer.length];
                System.arraycopy(this.outputBuffer, 0, this.previousBuffer, 0, this.outputBuffer.length);
            }
            this.applyBlendOp(this.outputBuffer, framePixels, fctl);
        }
        // AN ANIMATION OF ONE FRAME IS A STILL IMAGE: ITS fcTL DELAY TIMES NOTHING
        this.delayMs = fctl != null && this.scan.frameCount() > 1 ? fctl.delayMs() : 0L;
        Canvas.write(this.outputBuffer, this.outputBuffer.length, this.directOutInts, this.directOut);
        if (fctl != null) this.applyDispose(fctl);
        return this.directOut;
    }

    @Override
    protected boolean rewind() {
        this.data.position(this.resetPos);
        this.pendingChunk = this.resetChunk;
        this.done = this.resetDone;
        this.animFrames = 0;
        // RE-INIT THE CANVAS EXACTLY AS THE CONSTRUCTOR LEAVES IT (BKGD COLOR OR TRANSPARENT);
        // frameBuffer AND previousBuffer NEED NO CLEARING BECAUSE EACH next() FULLY WRITES THE
        // REGIONS IT LATER READS
        Arrays.fill(this.outputBuffer, this.bkgd != null ? this.background() : 0);
        return true;
    }

    @Override
    protected void dispose() {
        if (this.inflater != null) this.inflater.end();
    }

    // ==========================================================================
    // CHUNKS
    // ==========================================================================
    // NEXT CHUNK, OR NULL AT EOF; A CRC MISMATCH FAILS THE DECODE WHEN codecs.png.failOnCorruptedData IS SET
    private PngChunk nextChunk() throws IOException {
        final PngChunk c;
        try { c = PngChunk.read(this.data); }
        catch (final EOFException eof) { return null; }
        if (WaterMediaConfig.codecs.png.failOnCorruptedData && c.corrupted())
            throw new CodecException("Chunk CRC mismatch on " + c.typeName() + ": data corrupted");
        return c;
    }

    // CONSUMES THE DEFAULT IMAGE'S IDAT CHUNKS UP TO THE FIRST fcTL, HANDLING THE METADATA IN BETWEEN
    private void skipDefaultImage() throws IOException {
        while (true) {
            final PngChunk c = this.nextChunk();
            if (c == null) {
                this.done = true;
                this.pendingChunk = null;
                return;
            }
            final int t = c.type();
            if (t == FCTL.SIGNATURE) {
                this.pendingChunk = c;
                return;
            }
            if (t == IEND.SIGNATURE) {
                this.done = true;
                this.pendingChunk = null;
                return;
            }
            if (t != IDAT.SIGNATURE) this.handleAncillary(c);
        }
    }

    // PARSE CHUNKS UNTIL FIRST IDAT/fcTL (POPULATES pendingChunk OR SETS done)
    private void parseUntilFirstFrame() throws IOException {
        while (true) {
            final PngChunk c = this.nextChunk();
            if (c == null) {
                this.done = true;
                return;
            }

            final int t = c.type();
            if (t == IHDR.SIGNATURE) {
                this.ihdr = IHDR.convert(c);
                this.validateIHDR(this.ihdr);
            } else if (t == PLTE.SIGNATURE) {
                this.plte = PLTE.convert(c);
            } else if (t == FCTL.SIGNATURE || t == IDAT.SIGNATURE) {
                this.pendingChunk = c;
                return;
            } else if (t == IEND.SIGNATURE) {
                this.done = true;
                return;
            } else {
                this.handleAncillary(c);
            }
        }
    }

    private void validateIHDR(final IHDR ihdr) throws CodecException {
        if (ihdr.width() <= 0 || ihdr.height() <= 0)
            throw new CodecException("Invalid image dimensions: " + ihdr.width() + "x" + ihdr.height());
        // CAP DIMENSIONS: 24-BIT-ISH RAW IHDR FIELDS OTHERWISE OVERFLOW width*height*4 INT MATH AND
        // ALLOW MULTI-GB ALLOCATIONS FROM A TINY HEADER
        if (ihdr.width() > MAX_DIM || ihdr.height() > MAX_DIM)
            throw new CodecException("PNG dimensions too big: " + ihdr.width() + "x" + ihdr.height() + " (max " + MAX_DIM + ")");
        // THE PER-AXIS CAP IS ONLY A CHEAP PRE-FILTER: 16384x16384 STILL MULTIPLIES OUT TO A 2 GB CANVAS
        // (int[] + DIRECT BUFFER + APNG FRAME/PREVIOUS BUFFERS), SO THE PRODUCT IS THE REAL GATE. long
        // MATH BECAUSE THE int PRODUCT WRAPS NEGATIVE WELL BEFORE THE LIMIT IS REACHED.
        if ((long) ihdr.width() * ihdr.height() > MAX_PIXELS)
            throw new CodecException("PNG canvas too big: " + ihdr.width() + "x" + ihdr.height()
                    + " (max " + MAX_PIXELS + " pixels)");
        // ColorType.of THROWS IllegalArgumentException ON AN UNKNOWN TYPE, BUT A BAD HEADER IS A CodecException
        final int colorTypeByte = ihdr.colorType();
        if (colorTypeByte < 0 || colorTypeByte > 6) throw new CodecException("Unknown color type: " + colorTypeByte);
        final ColorType colorType = ColorType.of(colorTypeByte);
        final int depth = ihdr.depth();
        switch (colorType) {
            case GREYSCALE -> {
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8 && depth != 16)
                    throw new CodecException("Invalid bit depth " + depth + " for greyscale");
            }
            case TRUECOLOR, GREYSCALE_ALPHA, TRUECOLOR_ALPHA -> {
                if (depth != 8 && depth != 16)
                    throw new CodecException("Invalid bit depth " + depth + " for color type " + colorType);
            }
            case INDEXED -> {
                if (depth != 1 && depth != 2 && depth != 4 && depth != 8)
                    throw new CodecException("Invalid bit depth " + depth + " for indexed-color");
            }
            case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException("Forbidden color type: " + ihdr.colorType());
        }
        if (ihdr.compression() != 0) throw new CodecException("Unknown compression method: " + ihdr.compression());
        if (ihdr.filter() != 0) throw new CodecException("Unknown filter method: " + ihdr.filter());
        if (ihdr.interlace() != 0 && ihdr.interlace() != 1) throw new CodecException("Unknown interlace method: " + ihdr.interlace());
    }

    // ANCILLARY CHUNKS NEVER FAIL A DECODE: A MALFORMED OR OVERSIZED ONE IS SKIPPED AND THE FIRST SKIP IS LOGGED ONCE PER READER.
    // AN UNKNOWN CRITICAL CHUNK IS A FEATURE THIS DECODER LACKS, NOT A BROKEN FILE
    private void handleAncillary(final PngChunk c) throws IOException {
        final int t = c.type();
        if (t == IHDR.SIGNATURE || t == PLTE.SIGNATURE || t == IDAT.SIGNATURE || t == IEND.SIGNATURE)
            throw new CodecException("Misplaced critical chunk: " + c.typeName());
        if (c.isCritical()) throw new UnsupportedFormatException("Unknown critical chunk: " + c.typeName());
        try { this.storeAncillary(c); }
        catch (final IOException | RuntimeException failure) {
            if (this.ancillaryWarned) return;
            this.ancillaryWarned = true;
            LOGGER.warn(IT, "Skipping malformed {} chunk; later malformed chunks are skipped silently", c.typeName(), failure);
        }
    }

    // PUBLISHES EVERY ANCILLARY VALUE AS A JDK TYPE, SO A HOST THAT CANNOT SEE THE CHUNK RECORDS STILL READS IT
    private void storeAncillary(final PngChunk c) throws IOException {
        final int t = c.type();
        if (t == TRNS.SIGNATURE) {
            this.trns = TRNS.convert(c, this.ihdr.colorType());
        } else if (t == BKGD.SIGNATURE) {
            if (WaterMediaConfig.codecs.png.useBKGDChunk) this.bkgd = BKGD.convert(c, this.ihdr.colorType());
        } else if (t == ACTL.SIGNATURE) {
            ACTL.convert(c); // VALIDATION ONLY: scan() ALREADY READ THE ANIMATION CONTROL
        } else if (t == GAMA.SIGNATURE) {
            this.gamma = GAMA.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_GAMMA, this.gamma.gamma() / 100_000.0);
        } else if (t == CHRM.SIGNATURE) {
            final CHRM chrm = CHRM.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_CHROMATICITIES, new double[] {
                    chrm.whiteX() / 100_000.0, chrm.whiteY() / 100_000.0, chrm.redX() / 100_000.0, chrm.redY() / 100_000.0,
                    chrm.greenX() / 100_000.0, chrm.greenY() / 100_000.0, chrm.blueX() / 100_000.0, chrm.blueY() / 100_000.0 });
        } else if (t == SRGB.SIGNATURE) {
            this.srgb = SRGB.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_SRGB, this.srgb.renderingIntent());
        } else if (t == ICCP.SIGNATURE) {
            this.metadata.put(CodecsAPI.METAKEY_ICC_PROFILE, ICCP.convert(c).profile());
        } else if (t == SBIT.SIGNATURE) {
            final SBIT sbit = SBIT.convert(c, this.ihdr.colorType());
            this.metadata.put(CodecsAPI.PNG_METAKEY_SIGNIFICANT_BITS, switch (this.ihdr.colorType()) {
                case 0 -> new int[] { sbit.gray() };
                case 4 -> new int[] { sbit.gray(), sbit.alpha() };
                case 6 -> new int[] { sbit.red(), sbit.green(), sbit.blue(), sbit.alpha() };
                default -> new int[] { sbit.red(), sbit.green(), sbit.blue() };
            });
        } else if (t == CICP.SIGNATURE) {
            this.cicp = CICP.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_CICP, new int[] {
                    this.cicp.colorPrimaries(), this.cicp.transferFunction(), this.cicp.matrixCoefficients(), this.cicp.videoFullRangeFlag() });
        } else if (t == MDCV.SIGNATURE) {
            // CHROMATICITIES COME IN UNITS OF 0.00002, LUMINANCE IN UNITS OF 0.0001 cd/m²
            final MDCV mdcv = MDCV.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_MASTERING_DISPLAY_COLOR_VOLUME, new double[] {
                    mdcv.redX() / 50_000.0, mdcv.redY() / 50_000.0, mdcv.greenX() / 50_000.0, mdcv.greenY() / 50_000.0,
                    mdcv.blueX() / 50_000.0, mdcv.blueY() / 50_000.0, mdcv.whiteX() / 50_000.0, mdcv.whiteY() / 50_000.0,
                    mdcv.maxLuminance() / 10_000.0, mdcv.minLuminance() / 10_000.0 });
        } else if (t == CLLI.SIGNATURE) {
            final CLLI clli = CLLI.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_CONTENT_LIGHT_LEVEL, new double[] {
                    clli.maxContentLightLevel() / 10_000.0, clli.maxFrameAverageLightLevel() / 10_000.0 });
        } else if (t == TEXT.SIGNATURE) {
            final TEXT text = TEXT.convert(c);
            this.storePngText(this.texts, CodecsAPI.PNG_METAKEY_TEXT, text.keyword(), text.text());
        } else if (t == ZTXT.SIGNATURE && this.textEntries < MAX_TEXT_ENTRIES) {
            // COMPRESSED TEXT INFLATES ONLY WITHIN THE REMAINING TEXT BUDGET; A BREACH IS SKIPPED LIKE ANY OTHER BAD CHUNK
            final ZTXT text = ZTXT.convert(c);
            this.storePngText(this.compressedTexts, CodecsAPI.PNG_METAKEY_COMPRESSED_TEXT, text.keyword(),
                    text.text((int) Math.min(Integer.MAX_VALUE, MAX_TEXT_BYTES - this.textBytes)));
        } else if (t == ITXT.SIGNATURE && this.textEntries < MAX_TEXT_ENTRIES) {
            final ITXT text = ITXT.convert(c);
            final String value = text.text((int) Math.min(Integer.MAX_VALUE, MAX_TEXT_BYTES - this.textBytes));
            if (XMP_KEYWORD.equals(text.keyword())) {
                // THE XMP PACKET TRAVELS AS iTXt: PUBLISHED RAW AND MINED FOR THE NORMALIZED FIELDS LIKE JPEG AND WEBP DO
                final byte[] packet = value.getBytes(StandardCharsets.UTF_8);
                this.textBytes += packet.length;
                this.metadata.put(CodecsAPI.METAKEY_XMP, packet);
                Xmp.read(this.metadata, packet);
            } else {
                this.storePngText(this.internationalTexts, CodecsAPI.PNG_METAKEY_INTERNATIONAL_TEXT, text.keyword(), value);
            }
        } else if (t == PHYS.SIGNATURE) {
            final PHYS phys = PHYS.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_PHYSICAL_PIXEL_DIMENSIONS, new long[] { phys.pixelsPerUnitX(), phys.pixelsPerUnitY(), phys.unit() });
        } else if (t == HIST.SIGNATURE) {
            if (this.plte == null) throw new CodecException("hIST before PLTE");
            this.metadata.put(CodecsAPI.PNG_METAKEY_HISTOGRAM, HIST.convert(c, this.plte.size()).frequencies());
        } else if (t == SPLT.SIGNATURE) {
            final SPLT splt = SPLT.convert(c);
            final int[] flat = new int[splt.entries().length * 5];
            for (int i = 0; i < splt.entries().length; i++) {
                final SPLT.SPLTEntry entry = splt.entries()[i];
                flat[i * 5] = entry.red();
                flat[i * 5 + 1] = entry.green();
                flat[i * 5 + 2] = entry.blue();
                flat[i * 5 + 3] = entry.alpha();
                flat[i * 5 + 4] = entry.frequency();
            }
            this.suggestedPalettes.put(splt.paletteName(), flat);
            this.metadata.put(CodecsAPI.PNG_METAKEY_SUGGESTED_PALETTES, Collections.unmodifiableMap(this.suggestedPalettes));
        } else if (t == EXIF.SIGNATURE) {
            this.metadata.put(CodecsAPI.METAKEY_EXIF, EXIF.convert(c).data());
        } else if (t == TIME.SIGNATURE) {
            final TIME time = TIME.convert(c);
            this.metadata.put(CodecsAPI.PNG_METAKEY_LAST_MODIFIED_TIME,
                    LocalDateTime.of(time.year(), time.month(), time.day(), time.hour(), time.minute(), time.second()));
        } else if (this.ancillaryRetained < MAX_ANCILLARY_CHUNKS && this.ancillaryBytes + c.data().length <= MAX_ANCILLARY_BYTES) {
            // UNKNOWN ANCILLARY CHUNKS ARE RETAINED FOR THE CONSUMER UP TO A BUDGET; THE VIEW IS UNMODIFIABLE
            this.ancillaryRetained++;
            this.ancillaryBytes += c.data().length;
            this.ancillaryChunks.computeIfAbsent(c.typeName(), ignored -> new ArrayList<>()).add(c.data().clone());
            this.metadata.put(CodecsAPI.PNG_METAKEY_ANCILLARY_CHUNK, Collections.unmodifiableMap(this.ancillaryChunks));
        }
    }

    private void storePngText(final Map<String, List<String>> target, final String key, final String keyword, final String value) {
        if (keyword == null || keyword.isBlank() || value == null || value.isBlank() || this.textEntries >= MAX_TEXT_ENTRIES
                || this.textBytes + value.length() > MAX_TEXT_BYTES) return;
        this.textEntries++;
        this.textBytes += value.length();
        target.computeIfAbsent(keyword, ignored -> new ArrayList<>()).add(value);
        // PUBLISH AN UNMODIFIABLE VIEW SO A CONSUMER CANNOT MUTATE THE READER'S INTERNAL TEXT MAP
        this.metadata.put(key, Collections.unmodifiableMap(target));
        switch (keyword) {
            case TEXT.KEYWORD_TITLE -> this.metadata.title(value);
            case TEXT.KEYWORD_AUTHOR -> this.metadata.author(value);
            case TEXT.KEYWORD_DESCRIPTION -> this.metadata.description(value);
            case TEXT.KEYWORD_COPYRIGHT -> this.metadata.copyright(value);
            case TEXT.KEYWORD_COMMENT -> this.metadata.comment(value);
            case TEXT.KEYWORD_CREATION_TIME -> this.metadata.creationTime(value);
            case TEXT.KEYWORD_SOFTWARE -> this.metadata.software(value);
            case TEXT.KEYWORD_SOURCE -> this.metadata.source(value);
        }
    }

    // ==========================================================================
    // PIXELS
    // ==========================================================================
    private void flattenAlpha(final int[] pixels, final int w, final int h, final int stride) {
        final int bgColor = this.background();
        final int bgR = (bgColor >> 16) & 0xFF;
        final int bgG = (bgColor >> 8) & 0xFF;
        final int bgB = bgColor & 0xFF;

        for (int y = 0; y < h; y++) {
            final int rowBase = y * stride;
            for (int x = 0; x < w; x++) {
                final int argb = pixels[rowBase + x];
                final int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 255) continue;
                if (alpha == 0) {
                    pixels[rowBase + x] = 0xFF000000 | (bgR << 16) | (bgG << 8) | bgB;
                    continue;
                }
                final int srcR = (argb >>> 16) & 0xFF;
                final int srcG = (argb >>> 8) & 0xFF;
                final int srcB = argb & 0xFF;
                final int outR = ((srcR * alpha) + (bgR * (255 - alpha))) / 255;
                final int outG = ((srcG * alpha) + (bgG * (255 - alpha))) / 255;
                final int outB = ((srcB * alpha) + (bgB * (255 - alpha))) / 255;
                pixels[rowBase + x] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }
    }

    // THE PRESENTATION BACKGROUND IS ALWAYS OPAQUE; THE CONSTRUCTOR GUARANTEES A PALETTE FOR AN INDEXED bKGD
    private int background() {
        return 0xFF000000 | (this.bkgd.isIndexed() ? this.plte.color(this.bkgd.paletteIndex()) : this.bkgd.toRGB8(this.depth));
    }

    // INFLATES THE COMPRESSED CHUNKS INTO decompressed AND RETURNS THE NUMBER OF VALID BYTES
    private int inflate(final List<ChunkSlice> compressed, final IHDR ihdr) throws IOException {
        if (this.inflater == null) this.inflater = new Inflater();
        final Inflater inflater = this.inflater;
        inflater.reset();
        // A WELL-FORMED PNG DECOMPRESSES TO EXACTLY THIS MANY FILTERED BYTES; ANYTHING PAST IT IS A
        // DECOMPRESSION BOMB. COMPUTED AS long SO 16K depth-16 FRAMES DON'T OVERFLOW THE int MULTIPLY.
        final long expectedLong = this.expectedInflatedBytes(ihdr);
        if (expectedLong > MAX_INFLATED_IMAGE_BYTES) throw new CodecException("PNG image data too large: " + expectedLong + " bytes");
        final int expected = (int) expectedLong;
        // SIZE THE FIRST BUFFER FROM THE COMPRESSED BYTES ACTUALLY PRESENT, NOT FROM THE HEADER: AN
        // 8-BYTE IDAT UNDER A 8192x8192 16-BIT IHDR OTHERWISE PINS A ~400 MB ARRAY IN `decompressed`.
        // THE GROWTH STEPS BELOW STILL EXPAND UP TO `expected` FOR GENUINELY LARGE IMAGES
        long compressedBytes = 0L;
        for (final ChunkSlice chunk: compressed) {
            if (chunk.length() > 0) compressedBytes += chunk.length();
        }
        final int initial = (int) Math.min(Math.max(32, expected),
                Math.max(MIN_INFLATE_BUFFER_BYTES, compressedBytes * INFLATE_RATIO_GUESS));
        byte[] output = this.decompressed;
        if (output.length < initial) {
            output = new byte[initial];
            this.decompressed = output;
        }
        int outputSize = 0;
        try {
            for (final ChunkSlice chunk: compressed) {
                if (chunk.length() <= 0) continue;
                inflater.setInput(chunk.data(), chunk.offset(), chunk.length());
                while (true) {
                    if (outputSize == output.length) {
                        output = Arrays.copyOf(output, output.length + Math.max(8_192, output.length >> 1));
                        this.decompressed = output;
                    }
                    final int len = inflater.inflate(output, outputSize, output.length - outputSize);
                    if (len > 0) {
                        outputSize += len;
                        if (outputSize > expected) throw new CodecException("Compressed image data exceeds expected size (" + expected + " bytes)");
                        continue;
                    }
                    if (inflater.finished()) return outputSize;
                    if (inflater.needsDictionary()) throw new CodecException("PNG uses unsupported compression dictionary");
                    if (inflater.needsInput()) break;
                    throw new CodecException("Invalid compressed data stream");
                }
            }
            while (true) {
                if (outputSize == output.length) {
                    output = Arrays.copyOf(output, output.length + Math.max(8_192, output.length >> 1));
                    this.decompressed = output;
                }
                final int len = inflater.inflate(output, outputSize, output.length - outputSize);
                if (len > 0) {
                    outputSize += len;
                    if (outputSize > expected) throw new CodecException("Compressed image data exceeds expected size (" + expected + " bytes)");
                    continue;
                }
                if (inflater.finished()) return outputSize;
                if (inflater.needsDictionary()) throw new CodecException("PNG uses unsupported compression dictionary");
                if (inflater.needsInput()) throw new CodecException("Incomplete compressed data");
                throw new CodecException("Invalid compressed data stream");
            }
        } catch (final DataFormatException e) {
            throw new CodecException("Invalid compressed data: " + e.getMessage(), e);
        }
    }

    private long expectedInflatedBytes(final IHDR ihdr) {
        if (ihdr.interlace() == 0) return (long) (this.scanlineBytes(ihdr.width()) + 1) * ihdr.height();
        long total = 0;
        for (int pass = 0; pass < 7; pass++) {
            final int pw = this.passDimension(ihdr.width(), ADAM7_X_START[pass], ADAM7_X_STEP[pass]);
            final int ph = this.passDimension(ihdr.height(), ADAM7_Y_START[pass], ADAM7_Y_STEP[pass]);
            if (pw > 0 && ph > 0) total += (long) (this.scanlineBytes(pw) + 1) * ph;
        }
        return total;
    }

    private void decodeData(final byte[] data, final int dataLength, final IHDR ihdr, final int[] pixels) throws IOException {
        final int width = ihdr.width();
        final int height = ihdr.height();
        final int stride = this.canvasWidth;

        if (ihdr.interlace() == 0) {
            this.decodePass(data, dataLength, 0, pixels, stride, 0, 0, 1, 1, width, height);
        } else {
            int dataOffset = 0;
            for (int pass = 0; pass < 7; pass++) {
                final int pw = this.passDimension(width, ADAM7_X_START[pass], ADAM7_X_STEP[pass]);
                final int ph = this.passDimension(height, ADAM7_Y_START[pass], ADAM7_Y_STEP[pass]);
                if (pw > 0 && ph > 0)
                    dataOffset = this.decodePass(data, dataLength, dataOffset, pixels, stride,
                            ADAM7_X_START[pass], ADAM7_Y_START[pass], ADAM7_X_STEP[pass], ADAM7_Y_STEP[pass], pw, ph);
            }
        }
    }

    private int passDimension(final int dim, final int start, final int step) {
        if (start >= dim) return 0;
        return (dim - start + step - 1) / step;
    }

    private int decodePass(final byte[] data, final int dataLength, final int dataOffset,
                           final int[] pixels, final int stride,
                           final int xStart, final int yStart, final int xStep, final int yStep,
                           final int passWidth, final int passHeight) throws IOException {
        final int scanlineBytes = this.scanlineBytes(passWidth);
        if (this.passCurrentRow.length < scanlineBytes) this.passCurrentRow = new byte[scanlineBytes];
        if (this.passPreviousRow.length < scanlineBytes) this.passPreviousRow = new byte[scanlineBytes];
        byte[] currentRow = this.passCurrentRow;
        byte[] previousRow = this.passPreviousRow;
        Arrays.fill(previousRow, 0, scanlineBytes, (byte) 0);
        int offset = dataOffset;
        final int imageWidth = this.ihdr.width();

        for (int passY = 0; passY < passHeight; passY++) {
            if (offset >= dataLength) throw new CodecException("Unexpected end of image data");
            final int filterType = data[offset++] & 0xFF;
            if (offset + scanlineBytes > dataLength) throw new CodecException("Unexpected end of image data");
            System.arraycopy(data, offset, currentRow, 0, scanlineBytes);
            offset += scanlineBytes;

            unfilterRow(currentRow, previousRow, scanlineBytes, filterType, this.bytesPerPixel);

            final int imageY = yStart + passY * yStep;
            final int rowBase = imageY * stride;
            this.writeRowPixels(currentRow, pixels, rowBase, xStart, xStep, passWidth, imageWidth);

            final byte[] tmp = previousRow;
            previousRow = currentRow;
            currentRow = tmp;
        }
        this.passCurrentRow = currentRow;
        this.passPreviousRow = previousRow;
        return offset;
    }

    private int scanlineBytes(final int width) {
        final int samples = switch (this.colorType) {
            case GREYSCALE, INDEXED, FORBIDDEN_1, FORBIDDEN_5 -> 1;
            case TRUECOLOR -> 3;
            case GREYSCALE_ALPHA -> 2;
            case TRUECOLOR_ALPHA -> 4;
        };
        final int bitsPerPixel = samples * this.depth;
        return (width * bitsPerPixel + 7) / 8;
    }

    // DISPATCHES TO A SPECIALISED ROW WRITER BASED ON THE PRECOMPUTED COLOUR TYPE AND DEPTH
    private void writeRowPixels(final byte[] row, final int[] pixels, final int rowBase,
                                final int xStart, final int xStep, final int passWidth, final int imageWidth) throws IOException {
        if (this.depth == 8) {
            switch (this.colorType) {
                case TRUECOLOR_ALPHA -> writeRow8TruecolorAlpha(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth);
                case TRUECOLOR -> writeRow8Truecolor(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth,
                        this.trnsR8, this.trnsG8, this.trnsB8);
                case INDEXED -> writeRow8Indexed(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth, this.indexedARGB);
                case GREYSCALE -> writeRow8Greyscale(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth, this.trnsGray8);
                case GREYSCALE_ALPHA -> writeRow8GreyscaleAlpha(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth);
                case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException("Forbidden color type: " + this.ihdr.colorType());
            }
            return;
        }
        this.writeRowGeneric(row, pixels, rowBase, xStart, xStep, passWidth, imageWidth);
    }

    // GENERIC FALLBACK FOR THE RARE DEPTHS 1, 2, 4 AND 16
    private void writeRowGeneric(final byte[] row, final int[] pixels, final int rowBase,
                                 final int xStart, final int xStep, final int passWidth, final int imageWidth) throws IOException {
        final int d = this.depth;
        int bitOffset = 0;

        for (int passX = 0; passX < passWidth; passX++) {
            final int x = xStart + passX * xStep;
            if (x >= imageWidth) break;

            pixels[rowBase + x] = switch (this.colorType) {
                // tRNS HOLDS RAW SAMPLES AT THE IMAGE DEPTH, SO THE KEY IS MATCHED BEFORE SCALING TO 8 BITS
                case GREYSCALE -> {
                    final int raw = extractSample(row, bitOffset, d); bitOffset += d;
                    final int gray = scaleTo8Bit(raw, d);
                    final int alpha = this.trns != null && raw == this.trns.gray() ? 0 : 255;
                    yield (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }
                case TRUECOLOR -> {
                    final int r = extractSample(row, bitOffset, d); bitOffset += d;
                    final int g = extractSample(row, bitOffset, d); bitOffset += d;
                    final int b = extractSample(row, bitOffset, d); bitOffset += d;
                    final int alpha = this.trns != null && r == this.trns.red() && g == this.trns.green() && b == this.trns.blue() ? 0 : 255;
                    yield (alpha << 24) | (scaleTo8Bit(r, d) << 16) | (scaleTo8Bit(g, d) << 8) | scaleTo8Bit(b, d);
                }
                case INDEXED -> {
                    final int index = extractSample(row, bitOffset, d); bitOffset += d;
                    if (this.indexedARGB == null) throw new CodecException("Indexed-color image without PLTE");
                    yield this.indexedARGB[index];
                }
                case GREYSCALE_ALPHA -> {
                    int gray = extractSample(row, bitOffset, d); bitOffset += d;
                    int alpha = extractSample(row, bitOffset, d); bitOffset += d;
                    gray = scaleTo8Bit(gray, d);
                    alpha = scaleTo8Bit(alpha, d);
                    yield (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                }
                case TRUECOLOR_ALPHA -> {
                    int r = extractSample(row, bitOffset, d); bitOffset += d;
                    int g = extractSample(row, bitOffset, d); bitOffset += d;
                    int b = extractSample(row, bitOffset, d); bitOffset += d;
                    int alpha = extractSample(row, bitOffset, d); bitOffset += d;
                    r = scaleTo8Bit(r, d); g = scaleTo8Bit(g, d); b = scaleTo8Bit(b, d);
                    alpha = scaleTo8Bit(alpha, d);
                    yield (alpha << 24) | (r << 16) | (g << 8) | b;
                }
                case FORBIDDEN_1, FORBIDDEN_5 -> throw new CodecException("Forbidden color type: " + this.ihdr.colorType());
            };
        }
    }

    // ==========================================================================
    // COMPOSITING
    // ==========================================================================
    // DISPOSES THE FRAME RECTANGLE FOR THE NEXT ONE: LEFT ALONE, FILLED WITH THE BACKGROUND OR RESTORED FROM THE SNAPSHOT
    private void applyDispose(final FCTL fctl) {
        switch (fctl.dispose() & 0xFF) {
            case FCTL.DISPOSE_OP_BACKGROUND -> Canvas.fill(this.outputBuffer, this.canvasWidth, this.canvasHeight, fctl.xOffset(), fctl.yOffset(),
                    fctl.width(), fctl.height(), this.bkgd != null ? this.background() : 0);
            case FCTL.DISPOSE_OP_PREVIOUS -> {
                if (this.previousBuffer != null)
                    Canvas.restore(this.outputBuffer, this.previousBuffer, this.canvasWidth, this.canvasHeight, fctl.xOffset(), fctl.yOffset(), fctl.width(), fctl.height());
            }
            default -> { }
        }
    }

    private void applyBlendOp(final int[] outputBuffer, final int[] framePixels, final FCTL fctl) {
        final int xOff = fctl.xOffset();
        final int yOff = fctl.yOffset();
        final int fw = Math.min(fctl.width(), this.canvasWidth - xOff);
        final int fh = Math.min(fctl.height(), this.canvasHeight - yOff);
        if (fw <= 0 || fh <= 0) return;
        final int stride = this.canvasWidth;

        // FCTL.validate ALREADY REJECTED ANY OTHER OP: OVER COMPOSITES, SOURCE REPLACES ALPHA INCLUDED
        final boolean over = (fctl.blend() & 0xFF) == FCTL.BLEND_OP_OVER;
        for (int y = 0; y < fh; y++) {
            final int srcBase = y * stride;
            final int dstBase = (yOff + y) * stride + xOff;
            if (!over) {
                System.arraycopy(framePixels, srcBase, outputBuffer, dstBase, fw);
                continue;
            }
            for (int x = 0; x < fw; x++)
                outputBuffer[dstBase + x] = DataTool.blendOver(framePixels[srcBase + x], outputBuffer[dstBase + x]);
        }
    }

    // ==========================================================================
    // STREAM SCAN
    // ==========================================================================
    // WALKS THE CHUNK LAYOUT WITHOUT CRC OR PIXEL WORK: acTL PLAYS AND THE fcTL DELAYS; SETS fctlSeen
    private ImageData.Scan scan(final ByteBuffer source) {
        final ByteBuffer buffer = source.slice().order(ByteOrder.BIG_ENDIAN);
        final List<Long> delays = new ArrayList<>();
        int declaredFrameCount = 0;
        int declaredRepeat = 1;

        while (buffer.remaining() >= 12) {
            final int length = buffer.getInt();
            final int type = buffer.getInt();
            // LONG MATH: length+4 OVERFLOWS int FOR length >= 0x7FFFFFFC, BYPASSING THE BOUNDS CHECK
            if (length < 0 || buffer.remaining() < (long) length + 4) break;

            final int dataPos = buffer.position();
            if (type == ACTL.SIGNATURE && length >= ACTL.LENGTH) {
                declaredFrameCount = buffer.getInt(dataPos);
                final int plays = buffer.getInt(dataPos + 4);
                // num_plays IS UNSIGNED IN THE APNG SPEC, SO A HIGH-BIT VALUE READS BACK NEGATIVE AND
                // ImageData'S COMPACT CONSTRUCTOR REJECTS IT. TREAT ANY NON-POSITIVE COUNT AS "LOOP FOREVER"
                declaredRepeat = (plays <= 0) ? ImageData.REPEAT_FOREVER : plays;
            } else if (type == FCTL.SIGNATURE && length == FCTL.LENGTH) {
                final int delayNum = ((buffer.get(dataPos + 20) & 0xFF) << 8) | (buffer.get(dataPos + 21) & 0xFF);
                int delayDen = ((buffer.get(dataPos + 22) & 0xFF) << 8) | (buffer.get(dataPos + 23) & 0xFF);
                if (delayDen == 0) delayDen = 100;
                delays.add(((long) delayNum * 1_000L) / delayDen);
            }

            buffer.position(Math.min(buffer.limit(), dataPos + length + 4));
            if (type == IEND.SIGNATURE) break;
        }

        // RECONCILE acTL num_frames WITH THE fcTL RECORDS ACTUALLY WALKED: THE DECLARED COUNT IS
        // ATTACKER-CONTROLLED, AND TRUSTING IT OVER THE STREAM LETS A FILE DECLARE TWO FRAMES WHILE
        // CARRYING FIVE HUNDRED, SO EVERY DOWNSTREAM MEMORY GATE COMPUTES FROM A LIE
        final int frameCount = (declaredFrameCount > 0 && !delays.isEmpty())
                ? Math.min(declaredFrameCount, delays.size())
                : delays.size();
        this.fctlSeen = !delays.isEmpty();
        if (frameCount <= 1) return ImageData.Scan.EMPTY;

        final long[] delayArray = new long[frameCount];
        long total = 0L;
        for (int i = 0; i < frameCount; i++) {
            delayArray[i] = delays.get(i);
            total += delayArray[i];
        }
        return new ImageData.Scan(frameCount, delayArray, total, declaredRepeat);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static float[] buildGammaLUT(final GAMA gamma, final SRGB srgb, final CICP cicp) {
        final float fileGamma;
        if (srgb != null) {
            fileGamma = SRGB_GAMMA;
        } else if (cicp != null) {
            if (cicp.isSRGB()) fileGamma = SRGB_GAMMA;
            else if (cicp.isHDR()) return null;
            else fileGamma = DEFAULT_GAMMA;
        } else if (gamma != null) {
            fileGamma = gamma.decodingExponent();
        } else {
            return null;
        }
        if (Math.abs(fileGamma - DISPLAY_GAMMA) < 0.01f) return null;

        final float[] lut = new float[256];
        final float exponent = fileGamma / DISPLAY_GAMMA;
        for (int i = 0; i < 256; i++) lut[i] = (float) Math.pow(i / 255.0, exponent);
        return lut;
    }

    private static void applyGammaCorrection(final int[] pixels, final int w, final int h, final int stride, final float[] lut) {
        for (int y = 0; y < h; y++) {
            final int rowBase = y * stride;
            for (int x = 0; x < w; x++) {
                final int argb = pixels[rowBase + x];
                final int a = (argb >>> 24) & 0xFF;
                final int r = (argb >>> 16) & 0xFF;
                final int g = (argb >>> 8) & 0xFF;
                final int b = argb & 0xFF;
                // CLAMP EACH CHANNEL: AN OUT-OF-RANGE LUT ENTRY (Math.round SATURATES Infinity TO
                // 0x7FFFFFFF) WOULD OTHERWISE SHIFT STRAIGHT OVER THE ALPHA BYTE AND TURN A FULLY
                // TRANSPARENT PIXEL OPAQUE: A SILENT MASK BYPASS, WITH NO EXCEPTION ANYWHERE
                final int cr = MathUtil.clip255(Math.round(lut[r] * 255));
                final int cg = MathUtil.clip255(Math.round(lut[g] * 255));
                final int cb = MathUtil.clip255(Math.round(lut[b] * 255));
                pixels[rowBase + x] = (a << 24) | (cr << 16) | (cg << 8) | cb;
            }
        }
    }

    private static void unfilterRow(final byte[] currentRow, final byte[] previousRow, final int length,
                                    final int filterType, final int bpp) throws IOException {
        switch (filterType) {
            case FILTER_NONE -> { }
            case FILTER_SUB -> {
                for (int i = bpp; i < length; i++) {
                    currentRow[i] = (byte) (currentRow[i] + currentRow[i - bpp]);
                }
            }
            case FILTER_UP -> {
                for (int i = 0; i < length; i++) {
                    currentRow[i] = (byte) (currentRow[i] + previousRow[i]);
                }
            }
            case FILTER_AVERAGE -> {
                for (int i = 0; i < bpp; i++) {
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + (b >> 1));
                }
                for (int i = bpp; i < length; i++) {
                    final int a = currentRow[i - bpp] & 0xFF;
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + ((a + b) >> 1));
                }
            }
            case FILTER_PAETH -> {
                // INITIAL bpp BYTES HAVE NO 'a' OR 'c' NEIGHBOUR (TREATED AS 0); REDUCES TO UP
                for (int i = 0; i < bpp; i++) {
                    final int b = previousRow[i] & 0xFF;
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + b);
                }
                for (int i = bpp; i < length; i++) {
                    final int a = currentRow[i - bpp] & 0xFF;
                    final int b = previousRow[i] & 0xFF;
                    final int c = previousRow[i - bpp] & 0xFF;
                    final int p = a + b - c;
                    final int pa = p > a ? p - a : a - p;
                    final int pb = p > b ? p - b : b - p;
                    final int pc = p > c ? p - c : c - p;
                    final int pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    currentRow[i] = (byte) ((currentRow[i] & 0xFF) + pr);
                }
            }
            default -> throw new CodecException("Unknown filter type: " + filterType);
        }
    }

    private static void writeRow8TruecolorAlpha(final byte[] row, final int[] pixels, final int rowBase,
                                                final int xStart, final int xStep, final int passWidth, final int imageWidth) {
        int src = 0;
        if (xStep == 1) {
            final int dstStart = rowBase + xStart;
            final int limit = Math.min(passWidth, imageWidth - xStart);
            for (int i = 0; i < limit; i++) {
                final int r = row[src] & 0xFF;
                final int g = row[src + 1] & 0xFF;
                final int b = row[src + 2] & 0xFF;
                final int a = row[src + 3] & 0xFF;
                src += 4;
                pixels[dstStart + i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        } else {
            int x = xStart;
            for (int i = 0; i < passWidth; i++) {
                if (x >= imageWidth) break;
                final int r = row[src] & 0xFF;
                final int g = row[src + 1] & 0xFF;
                final int b = row[src + 2] & 0xFF;
                final int a = row[src + 3] & 0xFF;
                src += 4;
                pixels[rowBase + x] = (a << 24) | (r << 16) | (g << 8) | b;
                x += xStep;
            }
        }
    }

    private static void writeRow8Truecolor(final byte[] row, final int[] pixels, final int rowBase,
                                           final int xStart, final int xStep, final int passWidth, final int imageWidth,
                                           final int trnsR, final int trnsG, final int trnsB) {
        final boolean hasTrns = trnsR >= 0;
        int src = 0;
        if (xStep == 1) {
            final int dstStart = rowBase + xStart;
            final int limit = Math.min(passWidth, imageWidth - xStart);
            for (int i = 0; i < limit; i++) {
                final int r = row[src] & 0xFF;
                final int g = row[src + 1] & 0xFF;
                final int b = row[src + 2] & 0xFF;
                src += 3;
                final int alpha = (hasTrns && r == trnsR && g == trnsG && b == trnsB) ? 0 : 255;
                pixels[dstStart + i] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        } else {
            int x = xStart;
            for (int i = 0; i < passWidth; i++) {
                if (x >= imageWidth) break;
                final int r = row[src] & 0xFF;
                final int g = row[src + 1] & 0xFF;
                final int b = row[src + 2] & 0xFF;
                src += 3;
                final int alpha = (hasTrns && r == trnsR && g == trnsG && b == trnsB) ? 0 : 255;
                pixels[rowBase + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
                x += xStep;
            }
        }
    }

    private static void writeRow8Indexed(final byte[] row, final int[] pixels, final int rowBase,
                                         final int xStart, final int xStep, final int passWidth, final int imageWidth,
                                         final int[] lut) throws IOException {
        if (lut == null) throw new CodecException("Indexed-color image without PLTE");
        if (xStep == 1) {
            final int dstStart = rowBase + xStart;
            final int limit = Math.min(passWidth, imageWidth - xStart);
            for (int i = 0; i < limit; i++) {
                pixels[dstStart + i] = lut[row[i] & 0xFF];
            }
        } else {
            int x = xStart;
            for (int i = 0; i < passWidth; i++) {
                if (x >= imageWidth) break;
                pixels[rowBase + x] = lut[row[i] & 0xFF];
                x += xStep;
            }
        }
    }

    private static void writeRow8Greyscale(final byte[] row, final int[] pixels, final int rowBase,
                                           final int xStart, final int xStep, final int passWidth, final int imageWidth,
                                           final int trnsGray) {
        final boolean hasTrns = trnsGray >= 0;
        if (xStep == 1) {
            final int dstStart = rowBase + xStart;
            final int limit = Math.min(passWidth, imageWidth - xStart);
            for (int i = 0; i < limit; i++) {
                final int gray = row[i] & 0xFF;
                final int alpha = (hasTrns && gray == trnsGray) ? 0 : 255;
                pixels[dstStart + i] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            }
        } else {
            int x = xStart;
            for (int i = 0; i < passWidth; i++) {
                if (x >= imageWidth) break;
                final int gray = row[i] & 0xFF;
                final int alpha = (hasTrns && gray == trnsGray) ? 0 : 255;
                pixels[rowBase + x] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                x += xStep;
            }
        }
    }

    private static void writeRow8GreyscaleAlpha(final byte[] row, final int[] pixels, final int rowBase,
                                                final int xStart, final int xStep, final int passWidth, final int imageWidth) {
        int src = 0;
        if (xStep == 1) {
            final int dstStart = rowBase + xStart;
            final int limit = Math.min(passWidth, imageWidth - xStart);
            for (int i = 0; i < limit; i++) {
                final int gray = row[src] & 0xFF;
                final int alpha = row[src + 1] & 0xFF;
                src += 2;
                pixels[dstStart + i] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
            }
        } else {
            int x = xStart;
            for (int i = 0; i < passWidth; i++) {
                if (x >= imageWidth) break;
                final int gray = row[src] & 0xFF;
                final int alpha = row[src + 1] & 0xFF;
                src += 2;
                pixels[rowBase + x] = (alpha << 24) | (gray << 16) | (gray << 8) | gray;
                x += xStep;
            }
        }
    }

    private static int extractSample(final byte[] row, final int bitOffset, final int depth) {
        final int byteOffset = bitOffset >> 3;
        if (depth >= 8) {
            if (depth == 8) return row[byteOffset] & 0xFF;
            return ((row[byteOffset] & 0xFF) << 8) | (row[byteOffset + 1] & 0xFF);
        }
        final int bitInByte = 8 - (bitOffset & 7) - depth;
        final int mask = (1 << depth) - 1;
        return (row[byteOffset] >> bitInByte) & mask;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // ONE COMPRESSED PAYLOAD: AN IDAT BODY, OR AN fdAT BODY PAST ITS SEQUENCE NUMBER
    private record ChunkSlice(byte[] data, int offset, int length) {}
}
