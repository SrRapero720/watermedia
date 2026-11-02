package org.watermedia.api.codecs.readers;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.common.Limits;
import org.watermedia.api.codecs.common.Tiff;
import org.watermedia.api.codecs.common.Xmp;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * ByteBuffer-backed JPEG reader.
 *
 * <p>The reader receives a buffer positioned immediately after the SOI marker ({@code FF D8}).
 * It parses JPEG interchange streams directly and decodes 8-bit Huffman-coded baseline and
 * progressive images with one (grayscale) or three (color) components. Color images are delivered
 * as {@link PixelFormat#BGRA}, converted on the CPU with the full-range BT.601 matrix JPEG uses (or
 * copied as is when JFIF, Adobe APP14 or the component ids declare RGB samples); grayscale images
 * stay one luma byte per pixel ({@link PixelFormat#GRAY}) unless BGRA output is requested. The frame
 * comes out already rotated into its EXIF orientation, and comments, EXIF, XMP and ICC segments
 * reach {@link #metadata()}. Valid streams this decoder does not implement (lossless, hierarchical
 * or arithmetic-coded processes, 12-bit samples, CMYK or other component counts) are refused with
 * {@link UnsupportedFormatException}; malformed ones with {@link CodecException}.
 */
public final class JpegReader extends ImageReader {
    private static final Marker IT = MarkerManager.getMarker(JpegReader.class.getSimpleName());

    // DECODABLE FRAME TYPES. SOF1 IS BIT-IDENTICAL TO SOF0 FOR 8-BIT HUFFMAN DATA, SO IT SHARES readFrame()
    private static final int SOF0 = 0xC0;
    private static final int SOF1 = 0xC1;
    private static final int SOF2 = 0xC2;
    // FRAME TYPES THIS DECODER DOES NOT IMPLEMENT. NAMED SO parse() CAN REJECT THEM EXPLICITLY: THE WHOLE
    // 0xC0..0xCF BLOCK CARRIES A SEGMENT LENGTH, SO THE GENERIC SKIP PATH WOULD SWALLOW THEM AS PADDING
    private static final int SOF3 = 0xC3;
    private static final int SOF5 = 0xC5;
    private static final int SOF6 = 0xC6;
    private static final int SOF7 = 0xC7;
    private static final int SOF9 = 0xC9;
    private static final int SOF10 = 0xCA;
    private static final int SOF11 = 0xCB;
    private static final int SOF13 = 0xCD;
    private static final int SOF14 = 0xCE;
    private static final int SOF15 = 0xCF;
    private static final int DHT = 0xC4;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int DQT = 0xDB;
    private static final int DRI = 0xDD;
    private static final int COM = 0xFE;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP2 = 0xE2;
    private static final int APP14 = 0xEE;
    // APPn PAYLOAD SIGNATURES
    private static final byte[] JFIF_SIGNATURE = { 'J', 'F', 'I', 'F', 0 };
    private static final byte[] EXIF_SIGNATURE = { 'E', 'x', 'i', 'f', 0, 0 };
    private static final byte[] XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ICC_SIGNATURE = { 'I', 'C', 'C', '_', 'P', 'R', 'O', 'F', 'I', 'L', 'E', 0 };
    private static final byte[] ADOBE_SIGNATURE = { 'A', 'd', 'o', 'b', 'e' };
    // METADATA IS RETAINED FOR THE READER'S LIFETIME, SO THE COMMENT COUNT AND THE ASSEMBLED ICC PROFILE ARE CAPPED;
    // WHAT FALLS PAST EITHER CAP IS SKIPPED, NEVER A DECODE FAILURE
    private static final int MAX_COMMENTS = 64;
    private static final int MAX_ICC_BYTES = 8 << 20;
    // WORKING-SET CAP FOR ONE FRAME (COEFFICIENTS + SAMPLE PLANES + OUTPUT): MAX_PIXELS ALONE STILL ALLOWS 12 BYTES OF
    // COEFFICIENTS PER PIXEL ON 4:4:4. ABSOLUTE ON PURPOSE: ImageReader.MAX_DECODED_BYTES TRACKS WHAT REAL ANIMATIONS
    // RETAIN, WHICH HAS NO BEARING ON ONE FRAME'S DECODE COST
    private static final long MAX_FRAME_BYTES = 512L << 20;
    // SCAN CEILING (libjpeg-turbo USES THE SAME 100). EVERY SCAN REWALKS THE WHOLE COEFFICIENT GRID,
    // SO AN UNBOUNDED SOS CHAIN IS A CPU BOMB NO MATTER HOW LITTLE ENTROPY DATA EACH SCAN CARRIES.
    private static final int MAX_SCANS = 100;
    // POINT-TRANSFORM CEILING: Al ABOVE THIS SHIFTS COEFFICIENTS OUT OF ANY MEANINGFUL RANGE (libjpeg AGREES)
    private static final int MAX_APPROXIMATION = 13;

    private static final int[] ZIGZAG = {
            0, 1, 8, 16, 9, 2, 3, 10,
            17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34,
            27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36,
            29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
    };

    // INTEGER IDCT CONSTANTS (libjpeg "islow" STYLE, 13-BIT FIXED POINT)
    private static final int CONST_BITS = 13;
    private static final int PASS1_BITS = 2;
    private static final int PASS1_SHIFT = CONST_BITS - PASS1_BITS;
    private static final int PASS1_ROUND = 1 << (PASS1_SHIFT - 1);
    private static final int PASS2_SHIFT = CONST_BITS + PASS1_BITS + 3;
    private static final int PASS2_ROUND = 1 << (PASS2_SHIFT - 1);
    private static final int FIX_0_298631336 = 2446;
    private static final int FIX_0_390180644 = 3196;
    private static final int FIX_0_541196100 = 4433;
    private static final int FIX_0_765366865 = 6270;
    private static final int FIX_0_899976223 = 7373;
    private static final int FIX_1_175875602 = 9633;
    private static final int FIX_1_501321110 = 12299;
    private static final int FIX_1_847759065 = 15137;
    private static final int FIX_1_961570560 = 16069;
    private static final int FIX_2_053119869 = 16819;
    private static final int FIX_2_562915447 = 20995;
    private static final int FIX_3_072711026 = 25172;

    // PRECOMPUTED YCbCr -> RGB CONTRIBUTIONS (BT.601, libjpeg jdcolor.c LAYOUT, ABSORB -128 OFFSET).
    // R/B TABLES STORE THE FINAL PER-PIXEL INTEGER CONTRIBUTION; G TABLES STORE 16-BIT FIXED-POINT,
    // SUMMED THEN SHIFTED. ROUNDING BIAS LIVES IN CB_TO_G SO THE SUM ROUNDS-TO-NEAREST.
    private static final int[] CR_TO_R = new int[256];
    private static final int[] CB_TO_B = new int[256];
    private static final int[] CR_TO_G = new int[256];
    private static final int[] CB_TO_G = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            final int v = i - 128;
            CR_TO_R[i] = (int) Math.round(1.402 * v);
            CB_TO_B[i] = (int) Math.round(1.772 * v);
            CR_TO_G[i] = (int) Math.round(-0.71414 * v * 65536.0);
            CB_TO_G[i] = (int) Math.round(-0.34414 * v * 65536.0) + (1 << 15);
        }
    }

    private final int[][] quantTables = new int[4][];
    private final HuffmanTable[][] huffmanTables = new HuffmanTable[2][4];

    // COLOR MODEL EVIDENCE AND THE METADATA SEGMENTS, COLLECTED WHILE PARSING AND PUBLISHED ONCE THE FRAME IS BUILT
    private boolean jfif;
    private int adobeTransform = -1;
    private byte[] exif;
    private byte[] xmp;
    private byte[][] icc;
    private int iccBytes;
    private int comments;

    private Component[] components;
    private int width;
    private int height;
    private int maxH;
    private int maxV;
    private int mcusX;
    private int mcusY;
    private int restartInterval;
    private boolean progressive;
    private PixelFormat outputFormat;
    private ByteBuffer directOut;
    private boolean delivered;
    private int eobRun;
    private int scans;

    public JpegReader(final ByteBuffer data, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        this.parse();
        if (this.comments > MAX_COMMENTS)
            LOGGER.warn(IT, "Skipped {} JPEG comments past the cap of {}", this.comments - MAX_COMMENTS, MAX_COMMENTS);
        this.buildFrame();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override public int width() { return this.width; }
    @Override public int height() { return this.height; }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override protected PixelFormat decodedFormat() { return this.outputFormat; }

    @Override
    protected boolean hasMore() {
        return !this.delivered;
    }

    @Override
    protected ByteBuffer decode() throws IOException {
        this.delivered = true;
        this.directOut.position(0).limit(this.directOut.capacity());
        return this.directOut;
    }

    @Override
    protected boolean rewind() {
        // FULLY DECODED INTO directOut AT CONSTRUCTION; REPLAYING IS JUST CLEARING THE DELIVERY FLAG
        this.delivered = false;
        return true;
    }

    @Override
    protected void dispose() {
        // THE DECODED FRAME LIVES OFF-HEAP (UP TO width*height*4 BYTES) AND ONLY THE Cleaner CAN FREE IT; DROPPING
        // THE REFERENCE RELEASES IT AS SOON AS THE CALLER IS DONE INSTEAD OF WHEN THE WHOLE READER BECOMES UNREACHABLE
        this.directOut = null;
    }

    // ==========================================================================
    // MARKER PARSING
    // ==========================================================================
    private void parse() throws IOException {
        int marker = -1;
        while (this.data.hasRemaining()) {
            if (marker < 0) marker = readMarker(this.data);
            switch (marker) {
                case SOI -> throw new CodecException("Nested JPEG SOI marker");
                case EOI -> {
                    return;
                }
                case SOF0, SOF1, SOF2 -> this.readFrame(marker);
                case SOF3, SOF5, SOF6, SOF7, SOF9, SOF10, SOF11, SOF13, SOF14, SOF15 -> {
                    // NAME THE CODING PROCESS INSTEAD OF SKIPPING THE SEGMENT: SKIPPING LEAVES THE DECODE
                    // WITHOUT A FRAME HEADER AND IT DIES LATER ON THE MISLEADING "scan before frame header"
                    final String process = switch (marker) {
                        case SOF3 -> "lossless";
                        case SOF5, SOF6, SOF7 -> "hierarchical";
                        case SOF9, SOF10, SOF11 -> "arithmetic coding";
                        default -> "hierarchical arithmetic coding";
                    };
                    throw new UnsupportedFormatException("Unsupported JPEG frame type FF" + hex(marker) + " (" + process + ")");
                }
                case DQT -> this.readQuantTables();
                case DHT -> this.readHuffmanTables();
                case DRI -> {
                    final ByteBuffer segment = this.segment();
                    if (segment.remaining() != 2) throw new CodecException("Invalid JPEG DRI segment length " + (segment.limit() + 2));
                    this.restartInterval = readU16(segment);
                }
                case SOS -> {
                    if (++this.scans > MAX_SCANS) throw new CodecException("Too many JPEG scans (max " + MAX_SCANS + ")");
                    final Scan scan = this.readScanHeader();
                    final EntropyReader entropy = new EntropyReader(this.data);
                    this.decodeScan(scan, entropy);
                    // A SCAN THAT READ NO ENTROPY BYTE CANNOT HAVE MADE PROGRESS; IT ONLY REPLAYS WORK OVER
                    // THE COEFFICIENT GRID FOR FREE, SO IT IS MALFORMED RATHER THAN MERELY EMPTY
                    if (entropy.consumed == 0) throw new CodecException("JPEG scan consumed no entropy data");
                    marker = entropy.finishScan();
                    continue;
                }
                case COM -> {
                    // METADATA NEVER FAILS A DECODE: COMMENTS PAST THE CAP ARE SKIPPED, NOT REJECTED. NO continue HERE:
                    // IT WOULD SKIP THE MARKER RESET BELOW AND PARSE THE NEXT MARKER'S BYTES AS ANOTHER COMMENT
                    final ByteBuffer segment = this.segment();
                    if (++this.comments <= MAX_COMMENTS) {
                        final byte[] text = new byte[segment.remaining()];
                        segment.get(text);
                        this.metadata.comment(new String(text, StandardCharsets.ISO_8859_1));
                    }
                }
                default -> {
                    // A SEGMENT WITHOUT A CONSUMER IS ONLY SKIPPED, SO ITS BODY IS DROPPED ON PURPOSE
                    if (isApp(marker)) this.readApp(marker);
                    else if (hasSegmentLength(marker)) this.segment();
                    else if (!isRestart(marker)) throw new CodecException("Unknown JPEG marker FF" + hex(marker));
                }
            }
            marker = -1;
        }
    }

    // APPn SEGMENTS CARRY THE METADATA AND THE TWO FLAGS THAT DECIDE THE COLOR MODEL (JFIF, ADOBE TRANSFORM);
    // ANYTHING ELSE IS SKIPPED. ONLY THE FIRST EXIF AND XMP PACKETS COUNT, LATER DUPLICATES ARE IGNORED
    private void readApp(final int marker) throws CodecException {
        final ByteBuffer segment = this.segment();
        switch (marker) {
            case APP0 -> this.jfif |= DataTool.startsWith(segment, 0, JFIF_SIGNATURE);
            case APP1 -> {
                if (DataTool.startsWith(segment, 0, EXIF_SIGNATURE) && this.exif == null) this.exif = tail(segment, EXIF_SIGNATURE.length);
                else if (DataTool.startsWith(segment, 0, XMP_SIGNATURE) && this.xmp == null) this.xmp = tail(segment, XMP_SIGNATURE.length);
            }
            case APP2 -> {
                // "ICC_PROFILE\0", A 1-BASED SEQUENCE NUMBER, THE SLICE COUNT, THEN A SLICE OF THE PROFILE. MALFORMED,
                // INCONSISTENT OR OVERSIZED PROFILES ARE DROPPED BECAUSE THE PIXELS DO NOT DEPEND ON THEM
                if (this.iccBytes > MAX_ICC_BYTES || !DataTool.startsWith(segment, 0, ICC_SIGNATURE)
                        || segment.remaining() < ICC_SIGNATURE.length + 2) return;
                final int seq = segment.get(ICC_SIGNATURE.length) & 0xFF;
                final int count = segment.get(ICC_SIGNATURE.length + 1) & 0xFF;
                if (seq < 1 || seq > count) return;
                if (this.icc == null) this.icc = new byte[count][];
                if (count != this.icc.length || this.icc[seq - 1] != null) return;
                final byte[] slice = tail(segment, ICC_SIGNATURE.length + 2);
                this.iccBytes += slice.length;
                if (this.iccBytes > MAX_ICC_BYTES) {
                    this.icc = null;
                    return;
                }
                this.icc[seq - 1] = slice;
            }
            case APP14 -> {
                // "Adobe", VERSION, TWO FLAG WORDS, THEN THE TRANSFORM: 0 = NONE (RGB), 1 = YCbCr, 2 = YCCK
                if (DataTool.startsWith(segment, 0, ADOBE_SIGNATURE) && segment.remaining() >= 12)
                    this.adobeTransform = segment.get(11) & 0xFF;
            }
            default -> { }
        }
    }

    private void readFrame(final int marker) throws CodecException {
        // T.81 ALLOWS EXACTLY ONE FRAME HEADER. A REPEATED SOF DROPS THE PREVIOUS COEFFICIENT ARRAYS AND
        // ALLOCATES A FRESH ZEROED SET, SO PEAK MEMORY STAYS FLAT WHILE THE JVM IS FORCED TO ALLOCATE AND
        // ZERO TENS OF GIGABYTES FROM A FEW KILOBYTES OF 19-BYTE HEADERS
        if (this.components != null) throw new CodecException("Duplicate JPEG frame header");

        final ByteBuffer segment = this.segment();
        if (segment.remaining() < 6) throw new CodecException("Truncated JPEG frame header");
        // T.81 BASELINE (SOF0) IS 8-BIT ONLY; EXTENDED AND PROGRESSIVE ALSO ALLOW 12 BITS, VALID BUT NOT IMPLEMENTED
        final int precision = readU8(segment);
        if (precision == 12 && marker != SOF0) throw new UnsupportedFormatException("Unsupported JPEG sample precision: 12 bits");
        if (precision != 8) throw new CodecException("Invalid JPEG sample precision for frame type FF" + hex(marker) + ": " + precision + " bits");

        this.height = readU16(segment);
        this.width = readU16(segment);
        if (this.height == 0 && this.width > 0) throw new UnsupportedFormatException("Unsupported JPEG height defined by a DNL marker");
        // 16-BIT SOF AXES OTHERWISE OVERFLOW THE COEFFICIENT, SAMPLE AND BGRA INT MATH: A 23-BYTE HEADER
        // DECLARING 16384x16384x3 ASKS FOR 1 GiB OF COEFFICIENTS PER COMPONENT
        if (!Limits.fits(this.width, this.height))
            throw new CodecException("Invalid JPEG dimensions: " + this.width + "x" + this.height
                    + " (max " + Limits.MAX_DIM + " per axis, " + Limits.MAX_PIXELS + " pixels)");

        // ONE COMPONENT IS GRAYSCALE AND THREE ARE YCbCr OR RGB; TWO, FOUR (CMYK, YCCK) OR MORE ARE VALID BUT NOT IMPLEMENTED
        final int count = readU8(segment);
        if (count == 0) throw new CodecException("JPEG frame header declares no components");
        if (count != 1 && count != 3)
            throw new UnsupportedFormatException("Unsupported JPEG component count: " + count + (count == 4 ? " (CMYK or YCCK)" : ""));
        // Lf MUST BE EXACTLY 8 + 3*Nf. WITHOUT THIS THE COMPONENT TRIPLETS BELOW ARE HAPPILY READ FROM
        // WHATEVER FOLLOWS THE SEGMENT (CVE-2013-6629 SHAPE); THE SLICE LIMIT TURNS THAT INTO A HARD ERROR
        if (segment.remaining() != count * 3)
            throw new CodecException("Malformed JPEG frame header length " + (segment.limit() + 2)
                    + " (expected " + (8 + count * 3) + " for " + count + " components)");

        this.progressive = marker == SOF2;
        this.components = new Component[count];
        this.maxH = 1;
        this.maxV = 1;
        for (int i = 0; i < count; i++) {
            final int id = readU8(segment);
            final int sampling = readU8(segment);
            final int h = sampling >>> 4;
            final int v = sampling & 0x0F;
            final int table = readU8(segment);
            // T.81 SAMPLING FACTORS ARE 1..4 PER AXIS AND THE RESAMPLER HANDLES EVERY SUCH LAYOUT, SO ANY OTHER IS MALFORMED
            if (h <= 0 || v <= 0 || h > 4 || v > 4) throw new CodecException("Invalid JPEG sampling factor " + h + "x" + v);
            if (table >= this.quantTables.length) throw new CodecException("Invalid JPEG quantization table " + table);
            this.components[i] = new Component(id, h, v, table);
            this.maxH = Math.max(this.maxH, h);
            this.maxV = Math.max(this.maxV, v);
        }

        this.mcusX = ceilDiv(this.width, this.maxH * 8);
        this.mcusY = ceilDiv(this.height, this.maxV * 8);
        // OUTPUT BUFFER AT ITS BGRA WORST CASE; THE PLANAR LAYOUTS ARE STRICTLY CHEAPER
        long frameBytes = (long) this.width * this.height * 4L;
        for (final Component component: this.components) {
            // MCU-PADDED GRID: USED FOR STORAGE STRIDE AND INTERLEAVED (MCU) SCAN ITERATION
            component.blocksX = this.mcusX * component.h;
            component.blocksY = this.mcusY * component.v;
            // ACTUAL COMPONENT GRID: USED FOR NON-INTERLEAVED (SINGLE-COMPONENT) SCAN ITERATION.
            // PADDING BLOCKS PAST THESE BOUNDS ARE NOT CODED IN NON-INTERLEAVED SCANS (T.81 A.2.2).
            component.widthInBlocks = ceilDiv(this.width * component.h, this.maxH * 8);
            component.heightInBlocks = ceilDiv(this.height * component.v, this.maxV * 8);
            // 4 BYTES PER COEFFICIENT (int[]) PLUS 1 BYTE PER IDCT SAMPLE (byte[], ALLOCATED IN buildComponent)
            frameBytes += (long) component.blocksX * component.blocksY * 64L * 5L;
        }
        // SUM THE WHOLE FRAME IN long BEFORE THE FIRST ALLOCATION: PER-AXIS AND PER-PIXEL CAPS BOUND ONE
        // COMPONENT, NOT THE SUM OVER COMPONENTS, AND EVERY int PRODUCT BELOW IS ONLY SAFE ONCE THIS PASSES
        if (frameBytes > MAX_FRAME_BYTES)
            throw new CodecException("JPEG frame needs " + frameBytes + " bytes to decode (max " + MAX_FRAME_BYTES + ")");
        for (final Component component: this.components) {
            component.coefficients = new int[component.blocksX * component.blocksY * 64];
        }
    }

    private void readQuantTables() throws CodecException {
        final ByteBuffer segment = this.segment();
        while (segment.hasRemaining()) {
            final int info = readU8(segment);
            final int precision = info >>> 4;
            final int table = info & 0x0F;
            if (table >= this.quantTables.length) throw new CodecException("Invalid JPEG quantization table " + table);
            final int bytes = precision == 0 ? 64 : precision == 1 ? 128 : -1;
            if (bytes < 0) throw new CodecException("Invalid JPEG quantization table precision " + precision);
            if (segment.remaining() < bytes)
                throw new CodecException("Truncated JPEG quantization table " + table + ": " + bytes
                        + " bytes declared, " + segment.remaining() + " left in segment");

            final int[] values = new int[64];
            for (int i = 0; i < 64; i++) {
                values[ZIGZAG[i]] = precision == 0 ? readU8(segment) : readU16(segment);
            }
            this.quantTables[table] = values;
        }
    }

    private void readHuffmanTables() throws CodecException {
        final ByteBuffer segment = this.segment();
        while (segment.hasRemaining()) {
            final int info = readU8(segment);
            final int type = info >>> 4;
            final int table = info & 0x0F;
            if (type > 1 || table >= 4) throw new CodecException("Invalid JPEG Huffman table");
            // THE 16 CODE-LENGTH COUNTS MUST FIT IN THIS SEGMENT. READ FROM THE PARENT BUFFER A THREE-BYTE
            // DHT WOULD ADOPT THE FOLLOWING MARKER AS TABLE DATA INSTEAD OF FAILING (CVE-2013-6629 SHAPE)
            if (segment.remaining() < 16)
                throw new CodecException("Truncated JPEG Huffman table: 16 code counts needed, "
                        + segment.remaining() + " left in segment");

            final int[] counts = new int[17];
            int symbols = 0;
            for (int i = 1; i <= 16; i++) {
                counts[i] = readU8(segment);
                symbols += counts[i];
            }
            if (segment.remaining() < symbols)
                throw new CodecException("Truncated JPEG Huffman table: " + symbols + " symbols declared, "
                        + segment.remaining() + " left in segment");
            final int[] values = new int[symbols];
            for (int i = 0; i < symbols; i++) values[i] = readU8(segment);
            this.huffmanTables[type][table] = new HuffmanTable(counts, values);
        }
    }

    private Scan readScanHeader() throws CodecException {
        if (this.components == null) throw new CodecException("JPEG scan before frame header");
        final ByteBuffer segment = this.segment();
        if (!segment.hasRemaining()) throw new CodecException("Truncated JPEG scan header");
        final int count = readU8(segment);
        if (count <= 0 || count > this.components.length) throw new CodecException("Invalid JPEG scan component count");
        // Ls MUST BE EXACTLY 6 + 2*Ns: Ns COMPONENT PAIRS PLUS Ss, Se AND THE APPROXIMATION BYTE
        if (segment.remaining() != count * 2 + 3)
            throw new CodecException("Malformed JPEG scan header length " + (segment.limit() + 2)
                    + " (expected " + (6 + count * 2) + " for " + count + " components)");

        final Component[] scanComponents = new Component[count];
        for (int i = 0; i < count; i++) {
            final Component component = this.component(readU8(segment));
            final int tables = readU8(segment);
            component.dcTable = tables >>> 4;
            component.acTable = tables & 0x0F;
            // TABLE DESTINATION SELECTORS ARE 4-BIT FIELDS BUT ONLY 0..3 ARE VALID; huffmanTables IS [2][4],
            // SO AN UNCHECKED 4..15 SELECTOR INDEXES OUT OF BOUNDS (AIOOBE ESCAPING THE IOException CONTRACT)
            if (component.dcTable > 3 || component.acTable > 3)
                throw new CodecException("Invalid JPEG scan table selector " + component.dcTable + "/" + component.acTable);
            scanComponents[i] = component;
        }
        final int ss = readU8(segment);
        final int se = readU8(segment);
        final int approximation = readU8(segment);
        return new Scan(scanComponents, ss, se, approximation >>> 4, approximation & 0x0F);
    }

    private Component component(final int id) throws CodecException {
        for (final Component component: this.components) {
            if (component.id == id) return component;
        }
        throw new CodecException("JPEG scan references unknown component " + id);
    }

    // READS THE SEGMENT LENGTH AND RETURNS A VIEW BOUNDED TO ITS BODY, LEAVING THE PARENT BUFFER POSITIONED
    // ON THE NEXT MARKER. EVERY SUB-READ IS THEN CONFINED TO ITS OWN SEGMENT: A SHORT DECLARED LENGTH FAILS
    // INSIDE THE SLICE INSTEAD OF QUIETLY CONSUMING THE BYTES OF WHATEVER FOLLOWS.
    private ByteBuffer segment() throws CodecException {
        final int length = readU16(this.data);
        if (length < 2) throw new CodecException("Invalid JPEG segment length " + length);
        final int body = length - 2;
        if (this.data.remaining() < body)
            throw new CodecException("Truncated JPEG segment: " + body + " bytes declared, "
                    + this.data.remaining() + " available");
        final ByteBuffer segment = this.data.slice(this.data.position(), body);
        this.data.position(this.data.position() + body);
        return segment;
    }

    // ==========================================================================
    // SCAN DECODING
    // ==========================================================================
    private void decodeScan(final Scan scan, final EntropyReader entropy) throws IOException {
        this.eobRun = 0;
        if (!this.progressive) {
            this.decodeSequentialScan(scan, entropy);
            return;
        }

        final boolean dcBand = scan.ss == 0 && scan.se == 0;
        if (!dcBand) {
            if (scan.components.length != 1) throw new CodecException("Progressive AC scan must contain one component");
            if (scan.ss < 1 || scan.se > 63 || scan.ss > scan.se) throw new CodecException("Invalid progressive AC range");
        }
        // SUCCESSIVE APPROXIMATION (T.81 G.1.1.1.2): A REFINEMENT SCAN LOWERS THE POINT TRANSFORM BY EXACTLY ONE BIT
        if (scan.al > MAX_APPROXIMATION || (scan.ah != 0 && scan.ah != scan.al + 1))
            throw new CodecException("Invalid JPEG successive approximation Ah=" + scan.ah + " Al=" + scan.al);

        // SCAN-ORDERING GATE: approx[k] IS THE POINT TRANSFORM COEFFICIENT k WAS LAST CODED AT (-1 NEVER). A LONE REFINEMENT
        // SCAN WOULD REFINE AN ALL-ZERO ARRAY, refineNonZero() WALKING 63 POSITIONS PER BLOCK FOR NO BITS (ONE 15-BIT EOB
        // BUYS 16384 BLOCKS OF WORK); THE GATE ALSO BOUNDS EFFORT TO ONE VISIT PER COEFFICIENT AND APPROXIMATION LEVEL
        for (final Component component: scan.components) {
            for (int k = scan.ss; k <= scan.se; k++) {
                if (scan.ah == 0) {
                    if (component.approx[k] >= 0)
                        throw new CodecException("Duplicate JPEG first pass on component " + component.id + " coefficient " + k);
                } else if (component.approx[k] != scan.ah) {
                    throw new CodecException("JPEG refinement scan on component " + component.id + " coefficient " + k
                            + " has no preceding pass at Al=" + scan.ah);
                }
                component.approx[k] = scan.al;
            }
        }

        if (dcBand) {
            if (scan.ah == 0) this.decodeDcFirst(scan, entropy);
            else this.decodeDcRefine(scan, entropy);
            return;
        }
        if (scan.ah == 0) this.decodeAcFirst(scan, entropy);
        else this.decodeAcRefine(scan, entropy);
    }

    private void decodeSequentialScan(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> this.decodeSequentialBlock(component, entropy, block));
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> this.decodeSequentialBlock(component, entropy, block));
    }

    private void decodeDcFirst(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> this.decodeDcFirstBlock(component, entropy, block, scan.al));
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> this.decodeDcFirstBlock(component, entropy, block, scan.al));
    }

    private void decodeDcRefine(final Scan scan, final EntropyReader entropy) throws IOException {
        if (scan.components.length == 1) {
            final Component component = scan.components[0];
            this.forEachBlock(component, entropy, block -> component.coefficients[block] |= entropy.readBit() << scan.al);
            return;
        }
        this.forEachMcu(scan, entropy, (component, block) -> component.coefficients[block] |= entropy.readBit() << scan.al);
    }

    private void decodeAcFirst(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        final Component component = scan.components[0];
        this.forEachBlock(component, entropy, block -> this.decodeAcFirstBlock(component, entropy, block, scan.ss, scan.se, scan.al));
    }

    private void decodeAcRefine(final Scan scan, final EntropyReader entropy) throws IOException {
        this.validateTables(scan);
        final Component component = scan.components[0];
        this.forEachBlock(component, entropy, block -> this.decodeAcRefineBlock(component, entropy, block, scan.ss, scan.se, scan.al));
    }

    private void decodeSequentialBlock(final Component component, final EntropyReader entropy, final int block) throws IOException {
        this.decodeDcFirstBlock(component, entropy, block, 0);
        this.decodeAcFirstBlock(component, entropy, block, 1, 63, 0);
    }

    private void decodeDcFirstBlock(final Component component, final EntropyReader entropy, final int block, final int shift) throws IOException {
        final HuffmanTable table = this.huffmanTables[0][component.dcTable];
        final int bits = table.decode(entropy);
        // DC MAGNITUDE CATEGORY (SSSS) IS 0..15; A CORRUPT TABLE CAN YIELD ANY BYTE, SO REJECT OUT-OF-RANGE
        // INSTEAD OF FEEDING A BOGUS BIT COUNT INTO receiveExtend
        if (bits > 15) throw new CodecException("Invalid JPEG DC magnitude category " + bits);
        component.dc += entropy.receiveExtend(bits);
        component.coefficients[block] = component.dc << shift;
    }

    private void decodeAcFirstBlock(final Component component, final EntropyReader entropy, final int block,
                                    final int start, final int end, final int shift) throws IOException {
        if (this.eobRun > 0) {
            this.eobRun--;
            return;
        }

        final HuffmanTable table = this.huffmanTables[1][component.acTable];
        int k = start;
        while (k <= end) {
            final int symbol;
            try {
                symbol = table.decode(entropy);
            } catch (final CodecException e) {
                throw new CodecException("Invalid JPEG AC Huffman code for component " +
                        component.id + " block " + (block / 64) + " range " + start + "-" + end +
                        " shift " + shift, e);
            }
            final int run = symbol >>> 4;
            final int bits = symbol & 0x0F;
            if (bits == 0) {
                if (run == 15) {
                    k += 16;
                    continue;
                }
                this.eobRun = (1 << run) + entropy.readBits(run) - 1;
                return;
            }
            k += run;
            if (k > end) throw new CodecException("JPEG AC run exceeds spectral selection");
            component.coefficients[block + ZIGZAG[k]] = entropy.receiveExtend(bits) << shift;
            k++;
        }
    }

    private void decodeAcRefineBlock(final Component component, final EntropyReader entropy, final int block,
                                     final int start, final int end, final int shift) throws IOException {
        final int bit = 1 << shift;
        if (this.eobRun > 0) {
            this.refineNonZero(component.coefficients, block, start, end, bit, entropy);
            this.eobRun--;
            return;
        }

        final HuffmanTable table = this.huffmanTables[1][component.acTable];
        int k = start;
        while (k <= end) {
            final int symbol;
            try {
                symbol = table.decode(entropy);
            } catch (final CodecException e) {
                throw new CodecException("Invalid JPEG AC refinement Huffman code for component " +
                        component.id + " block " + (block / 64) + " range " + start + "-" + end +
                        " shift " + shift, e);
            }
            int run = symbol >>> 4;
            final int bits = symbol & 0x0F;
            final int value;
            if (bits == 0) {
                if (run < 15) {
                    this.eobRun = (1 << run) + entropy.readBits(run);
                    this.refineNonZero(component.coefficients, block, k, end, bit, entropy);
                    this.eobRun--;
                    return;
                }
                value = 0;
                run = 15;
            } else if (bits == 1) {
                value = entropy.readBit() == 1 ? bit : -bit;
            } else {
                throw new CodecException("Invalid JPEG progressive refinement symbol");
            }

            while (k <= end) {
                final int index = block + ZIGZAG[k];
                final int coefficient = component.coefficients[index];
                if (coefficient != 0) {
                    this.refineCoefficient(component.coefficients, index, bit, entropy);
                } else {
                    if (run == 0) break;
                    run--;
                }
                k++;
            }
            if (value != 0) {
                if (k > end) throw new CodecException("JPEG refinement run exceeds spectral selection");
                component.coefficients[block + ZIGZAG[k]] = value;
            }
            k++;
        }
    }

    private void refineNonZero(final int[] coefficients, final int block, final int start,
                               final int end, final int bit, final EntropyReader entropy) throws IOException {
        for (int k = start; k <= end; k++) {
            final int index = block + ZIGZAG[k];
            if (coefficients[index] != 0) this.refineCoefficient(coefficients, index, bit, entropy);
        }
    }

    private void refineCoefficient(final int[] coefficients, final int index, final int bit,
                                   final EntropyReader entropy) throws IOException {
        final int value = coefficients[index];
        final int correction = entropy.readBit();
        if ((Math.abs(value) & bit) == 0 && correction == 1) coefficients[index] += value > 0 ? bit : -bit;
    }

    private void forEachMcu(final Scan scan, final EntropyReader entropy, final BlockConsumer consumer) throws IOException {
        int restartCount = 0;
        int mcu = 0;
        final int total = this.mcusX * this.mcusY;
        for (int my = 0; my < this.mcusY; my++) {
            for (int mx = 0; mx < this.mcusX; mx++) {
                for (final Component component: scan.components) {
                    for (int y = 0; y < component.v; y++) {
                        for (int x = 0; x < component.h; x++) {
                            final int blockX = mx * component.h + x;
                            final int blockY = my * component.v + y;
                            consumer.accept(component, component.blockOffset(blockX, blockY));
                        }
                    }
                }
                mcu++;
                restartCount = this.advanceRestart(entropy, restartCount, mcu < total);
            }
        }
    }

    // NON-INTERLEAVED ITERATION: WALKS THE COMPONENT'S OWN BLOCK GRID (NOT THE MCU-PADDED ONE).
    // blockOffset() STILL USES THE MCU-PADDED blocksX STRIDE SO COEFFICIENTS LAND IN THE SHARED BUFFER.
    private void forEachBlock(final Component component, final EntropyReader entropy, final SingleBlockConsumer consumer) throws IOException {
        int restartCount = 0;
        int blockIndex = 0;
        final int total = component.widthInBlocks * component.heightInBlocks;
        for (int y = 0; y < component.heightInBlocks; y++) {
            for (int x = 0; x < component.widthInBlocks; x++) {
                consumer.accept(component.blockOffset(x, y));
                blockIndex++;
                restartCount = this.advanceRestart(entropy, restartCount, blockIndex < total);
            }
        }
    }

    private int advanceRestart(final EntropyReader entropy, final int count, final boolean moreData) throws IOException {
        if (this.restartInterval <= 0 || !moreData) return count;
        final int next = count + 1;
        if (next < this.restartInterval) return next;
        entropy.consumeRestart();
        for (final Component component: this.components) component.dc = 0;
        this.eobRun = 0;
        return 0;
    }

    private void validateTables(final Scan scan) throws CodecException {
        for (final Component component: scan.components) {
            if (this.quantTables[component.quantTable] == null)
                throw new CodecException("Missing JPEG quantization table " + component.quantTable);
            if (this.huffmanTables[0][component.dcTable] == null)
                throw new CodecException("Missing JPEG DC Huffman table " + component.dcTable);
            if ((scan.se > 0 || !this.progressive) && this.huffmanTables[1][component.acTable] == null)
                throw new CodecException("Missing JPEG AC Huffman table " + component.acTable);
        }
    }

    // ==========================================================================
    // FRAME OUTPUT
    // ==========================================================================
    private void buildFrame() throws IOException {
        if (this.components == null) throw new CodecException("JPEG frame header not found");
        for (final Component component: this.components) this.buildComponent(component);

        // GRAYSCALE STAYS ONE FULL-RANGE LUMA BYTE PER PIXEL UNLESS BGRA WAS ASKED FOR; COLOR (FULL-RANGE BT.601) IS
        // CONVERTED HERE BECAUSE PICTURE DECODERS HAND OUT BGRA OR GRAY, NEVER PLANAR YUV
        final boolean gray = this.components.length == 1;
        this.outputFormat = gray && this.requestedFormat != PixelFormat.BGRA ? PixelFormat.GRAY : PixelFormat.BGRA;
        this.directOut = ByteBuffer.allocateDirect(this.width * this.height * (this.outputFormat == PixelFormat.GRAY ? 1 : 4))
                .order(ByteOrder.LITTLE_ENDIAN);
        if (gray) {
            final byte[] samples = this.components[0].samples;
            final int stride = this.components[0].blocksX * 8;
            if (this.outputFormat == PixelFormat.GRAY) {
                // ONE BULK COPY WHEN THE MCU GRID ADDS NO RIGHT PADDING, OTHERWISE ONE PER ROW
                if (stride == this.width) this.directOut.put(samples, 0, this.width * this.height);
                else for (int y = 0; y < this.height; y++) this.directOut.put(samples, y * stride, this.width);
            } else {
                for (int y = 0; y < this.height; y++) {
                    for (int x = 0; x < this.width; x++) {
                        final int g = samples[y * stride + x] & 0xFF;
                        this.directOut.putInt(0xFF000000 | (g << 16) | (g << 8) | g);
                    }
                }
            }
        } else {
            // libjpeg RULE: JFIF MEANS YCbCr; OTHERWISE THE ADOBE TRANSFORM DECIDES (0 = NONE, SO RGB); OTHERWISE
            // COMPONENT IDS 'R', 'G', 'B' MEAN RGB AND ANYTHING ELSE YCbCr
            final boolean rgb = !this.jfif && (this.adobeTransform == 0 || (this.adobeTransform < 0
                    && this.components[0].id == 'R' && this.components[1].id == 'G' && this.components[2].id == 'B'));
            this.writeColorBgra(rgb);
        }
        this.directOut.flip();

        if (this.exif != null) {
            this.metadata.put(CodecsAPI.METAKEY_EXIF, this.exif);
            final int orientation = Tiff.orientation(this.exif);
            this.metadata.put(CodecsAPI.METAKEY_ORIENTATION, orientation);
            if (orientation != 1) {
                final ByteBuffer turned = ByteBuffer.allocateDirect(this.directOut.capacity()).order(ByteOrder.LITTLE_ENDIAN);
                DataTool.orient(this.directOut, turned, this.width, this.height, this.outputFormat == PixelFormat.GRAY ? 1 : 4, orientation);
                final boolean swap = orientation >= 5;
                final int ow = swap ? this.height : this.width, oh = swap ? this.width : this.height;
                this.directOut = turned;
                this.width = ow;
                this.height = oh;
            }
        }
        if (this.xmp != null) {
            this.metadata.put(CodecsAPI.METAKEY_XMP, this.xmp);
            Xmp.read(this.metadata, this.xmp);
        }
        if (this.icc != null) {
            // THE PROFILE IS PUBLISHED ONLY WHEN EVERY SLICE ARRIVED, JOINED IN SEQUENCE ORDER
            final ByteBuffer profile = ByteBuffer.allocate(this.iccBytes);
            boolean complete = true;
            for (final byte[] slice: this.icc) {
                if (slice == null) { complete = false; break; }
                profile.put(slice);
            }
            if (complete) this.metadata.put(CodecsAPI.METAKEY_ICC_PROFILE, profile.array());
        }
    }

    private void buildComponent(final Component component) throws CodecException {
        final int[] quant = this.quantTables[component.quantTable];
        if (quant == null) throw new CodecException("Missing JPEG quantization table " + component.quantTable);

        final int sampleWidth = component.blocksX * 8;
        final int sampleHeight = component.blocksY * 8;
        component.samples = new byte[sampleWidth * sampleHeight];
        final int[] tmp = new int[64];
        for (int by = 0; by < component.blocksY; by++) {
            for (int bx = 0; bx < component.blocksX; bx++) {
                final int block = component.blockOffset(bx, by);
                final int dst = by * 8 * sampleWidth + bx * 8;
                idct(component.coefficients, block, quant, tmp, component.samples, dst, sampleWidth);
            }
        }
    }

    private void writeColorBgra(final boolean rgb) {
        final Component yc = this.components[0];
        final Component cbc = this.components[1];
        final Component crc = this.components[2];
        final byte[] ySrc = yc.samples;
        final byte[] cbSrc = cbc.samples;
        final byte[] crSrc = crc.samples;
        final int yStride = yc.blocksX * 8;
        final int cbStride = cbc.blocksX * 8;
        final int crStride = crc.blocksX * 8;

        // PRECOMPUTE PER-DESTINATION-COLUMN SAMPLE INDICES (REMOVES MUL/DIV/MIN PER PIXEL). EVERY COMPONENT, LUMA
        // INCLUDED, IS RESAMPLED ONTO THE MAX SAMPLING GRID WITH CLAMPED INDEXES, SO ANY T.81 LAYOUT (4:4:4, 4:2:2,
        // 4:2:0, 4:1:1, 4:4:0 OR CHROMA SAMPLED ABOVE LUMA) STAYS IN BOUNDS AT THE COST OF ONE TABLE LOOKUP.
        final int[] yX = new int[this.width];
        final int[] cbX = new int[this.width];
        final int[] crX = new int[this.width];
        final int ySampleW = yc.blocksX * 8;
        final int cbSampleW = cbc.blocksX * 8;
        final int crSampleW = crc.blocksX * 8;
        for (int x = 0; x < this.width; x++) {
            yX[x] = Math.min(ySampleW - 1, (x * yc.h) / this.maxH);
            cbX[x] = Math.min(cbSampleW - 1, (x * cbc.h) / this.maxH);
            crX[x] = Math.min(crSampleW - 1, (x * crc.h) / this.maxH);
        }

        final ByteBuffer out = this.directOut;
        final int ySampleH = yc.blocksY * 8;
        final int cbSampleH = cbc.blocksY * 8;
        final int crSampleH = crc.blocksY * 8;
        for (int y = 0; y < this.height; y++) {
            final int yRow = Math.min(ySampleH - 1, (y * yc.v) / this.maxV) * yStride;
            final int cbRow = Math.min(cbSampleH - 1, (y * cbc.v) / this.maxV) * cbStride;
            final int crRow = Math.min(crSampleH - 1, (y * crc.v) / this.maxV) * crStride;
            for (int x = 0; x < this.width; x++) {
                final int c0 = ySrc[yRow + yX[x]] & 0xFF;
                final int c1 = cbSrc[cbRow + cbX[x]] & 0xFF;
                final int c2 = crSrc[crRow + crX[x]] & 0xFF;
                final int r, g, b;
                if (rgb) {
                    r = c0;
                    g = c1;
                    b = c2;
                } else {
                    r = MathUtil.clip255(c0 + CR_TO_R[c2]);
                    g = MathUtil.clip255(c0 + ((CB_TO_G[c1] + CR_TO_G[c2]) >> 16));
                    b = MathUtil.clip255(c0 + CB_TO_B[c1]);
                }
                out.putInt(0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // INTEGER IDCT (libjpeg "islow"): ROW PASS THEN COLUMN PASS WITH INLINED DEQUANTIZATION.
    // ROW PASS WRITES TO 64-INT SCRATCH; COLUMN PASS LEVEL-SHIFTS BY 128, CLAMPS, AND WRITES BYTES DIRECTLY.
    private static void idct(final int[] coef, final int block, final int[] quant,
                             final int[] tmp, final byte[] dst, final int dstOff, final int dstStride) {
        // FAST PATH: ENTIRE BLOCK IS DC ONLY (FLAT 8x8)
        int ac = 1;
        while (ac < 64 && coef[block + ac] == 0) ac++;
        if (ac == 64) {
            final int dc = MathUtil.clip255(((coef[block] * quant[0] + 4) >> 3) + 128);
            final byte b = (byte) dc;
            for (int y = 0; y < 8; y++) {
                final int row = dstOff + y * dstStride;
                dst[row]     = b; dst[row + 1] = b; dst[row + 2] = b; dst[row + 3] = b;
                dst[row + 4] = b; dst[row + 5] = b; dst[row + 6] = b; dst[row + 7] = b;
            }
            return;
        }

        // ROW PASS
        for (int row = 0; row < 8; row++) {
            final int b = block + row * 8;
            final int q = row * 8;

            // FAST ROW PATH: ALL AC IN ROW ARE ZERO -> CONSTANT ROW
            if (coef[b + 1] == 0 && coef[b + 2] == 0 && coef[b + 3] == 0 &&
                coef[b + 4] == 0 && coef[b + 5] == 0 && coef[b + 6] == 0 && coef[b + 7] == 0) {
                final int dcval = (coef[b] * quant[q]) << PASS1_BITS;
                final int o = row * 8;
                tmp[o]     = dcval; tmp[o + 1] = dcval; tmp[o + 2] = dcval; tmp[o + 3] = dcval;
                tmp[o + 4] = dcval; tmp[o + 5] = dcval; tmp[o + 6] = dcval; tmp[o + 7] = dcval;
                continue;
            }

            int z2 = coef[b + 2] * quant[q + 2];
            int z3 = coef[b + 6] * quant[q + 6];
            int z1 = (z2 + z3) * FIX_0_541196100;
            int tmp2 = z1 + z3 * (-FIX_1_847759065);
            int tmp3 = z1 + z2 * FIX_0_765366865;

            z2 = coef[b] * quant[q];
            z3 = coef[b + 4] * quant[q + 4];
            int tmp0 = (z2 + z3) << CONST_BITS;
            int tmp1 = (z2 - z3) << CONST_BITS;

            final int tmp10 = tmp0 + tmp3;
            final int tmp13 = tmp0 - tmp3;
            final int tmp11 = tmp1 + tmp2;
            final int tmp12 = tmp1 - tmp2;

            tmp0 = coef[b + 7] * quant[q + 7];
            tmp1 = coef[b + 5] * quant[q + 5];
            tmp2 = coef[b + 3] * quant[q + 3];
            tmp3 = coef[b + 1] * quant[q + 1];

            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            int z4 = tmp1 + tmp3;
            final int z5 = (z3 + z4) * FIX_1_175875602;

            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;

            z3 += z5;
            z4 += z5;

            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            final int o = row * 8;
            tmp[o]     = (tmp10 + tmp3 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 7] = (tmp10 - tmp3 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 1] = (tmp11 + tmp2 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 6] = (tmp11 - tmp2 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 2] = (tmp12 + tmp1 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 5] = (tmp12 - tmp1 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 3] = (tmp13 + tmp0 + PASS1_ROUND) >> PASS1_SHIFT;
            tmp[o + 4] = (tmp13 - tmp0 + PASS1_ROUND) >> PASS1_SHIFT;
        }

        // COLUMN PASS: WRITES INTO dst[] APPLYING LEVEL SHIFT +128 AND CLAMP TO 0..255
        for (int col = 0; col < 8; col++) {
            int z2 = tmp[2 * 8 + col];
            int z3 = tmp[6 * 8 + col];
            int z1 = (z2 + z3) * FIX_0_541196100;
            int tmp2 = z1 + z3 * (-FIX_1_847759065);
            int tmp3 = z1 + z2 * FIX_0_765366865;

            z2 = tmp[col];
            z3 = tmp[4 * 8 + col];
            int tmp0 = (z2 + z3) << CONST_BITS;
            int tmp1 = (z2 - z3) << CONST_BITS;

            final int tmp10 = tmp0 + tmp3;
            final int tmp13 = tmp0 - tmp3;
            final int tmp11 = tmp1 + tmp2;
            final int tmp12 = tmp1 - tmp2;

            tmp0 = tmp[7 * 8 + col];
            tmp1 = tmp[5 * 8 + col];
            tmp2 = tmp[3 * 8 + col];
            tmp3 = tmp[8 + col];

            z1 = tmp0 + tmp3;
            z2 = tmp1 + tmp2;
            z3 = tmp0 + tmp2;
            int z4 = tmp1 + tmp3;
            final int z5 = (z3 + z4) * FIX_1_175875602;

            tmp0 *= FIX_0_298631336;
            tmp1 *= FIX_2_053119869;
            tmp2 *= FIX_3_072711026;
            tmp3 *= FIX_1_501321110;
            z1 *= -FIX_0_899976223;
            z2 *= -FIX_2_562915447;
            z3 *= -FIX_1_961570560;
            z4 *= -FIX_0_390180644;

            z3 += z5;
            z4 += z5;

            tmp0 += z1 + z3;
            tmp1 += z2 + z4;
            tmp2 += z2 + z3;
            tmp3 += z1 + z4;

            final int base = dstOff + col;
            dst[base]                 = (byte) MathUtil.clip255(((tmp10 + tmp3 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 7] = (byte) MathUtil.clip255(((tmp10 - tmp3 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride]     = (byte) MathUtil.clip255(((tmp11 + tmp2 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 6] = (byte) MathUtil.clip255(((tmp11 - tmp2 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 2] = (byte) MathUtil.clip255(((tmp12 + tmp1 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 5] = (byte) MathUtil.clip255(((tmp12 - tmp1 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 3] = (byte) MathUtil.clip255(((tmp13 + tmp0 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
            dst[base + dstStride * 4] = (byte) MathUtil.clip255(((tmp13 - tmp0 + PASS2_ROUND) >> PASS2_SHIFT) + 128);
        }
    }

    private static int readMarker(final ByteBuffer input) throws CodecException {
        while (input.hasRemaining()) {
            if (readU8(input) != 0xFF) continue;
            int marker;
            do {
                if (!input.hasRemaining()) throw new CodecException("Truncated JPEG marker");
                marker = readU8(input);
            } while (marker == 0xFF);
            if (marker == 0x00) continue;
            return marker;
        }
        throw new CodecException("JPEG marker not found");
    }

    private static boolean hasSegmentLength(final int marker) {
        return marker >= 0xC0 && marker <= 0xFE && marker != SOI && marker != EOI && !isRestart(marker);
    }

    private static boolean isApp(final int marker) {
        return marker >= 0xE0 && marker <= 0xEF;
    }

    private static boolean isRestart(final int marker) {
        return marker >= 0xD0 && marker <= 0xD7;
    }

    private static int readU8(final ByteBuffer input) throws CodecException {
        if (!input.hasRemaining()) throw new CodecException("Unexpected JPEG EOF");
        return input.get() & 0xFF;
    }

    private static int readU16(final ByteBuffer input) throws CodecException {
        return (readU8(input) << 8) | readU8(input);
    }

    private static int ceilDiv(final int value, final int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static String hex(final int value) {
        return String.format("%02X", value);
    }

    // COPIES THE SEGMENT BODY THAT FOLLOWS A SIGNATURE
    private static byte[] tail(final ByteBuffer segment, final int from) {
        final byte[] bytes = new byte[segment.remaining() - from];
        segment.get(from, bytes);
        return bytes;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @FunctionalInterface
    private interface BlockConsumer {
        void accept(Component component, int block) throws IOException;
    }

    @FunctionalInterface
    private interface SingleBlockConsumer {
        void accept(int block) throws IOException;
    }

    private record Scan(Component[] components, int ss, int se, int ah, int al) {}

    private static final class Component {
        final int id;
        final int h;
        final int v;
        final int quantTable;
        int dcTable;
        int acTable;
        int blocksX;
        int blocksY;
        int widthInBlocks;
        int heightInBlocks;
        int dc;
        int[] coefficients;
        byte[] samples;
        // POINT TRANSFORM EACH COEFFICIENT WAS LAST CODED AT, -1 UNTIL A FIRST PASS COVERS IT. DRIVES THE
        // PROGRESSIVE SCAN-ORDERING GATE IN decodeScan()
        final int[] approx = new int[64];

        Component(final int id, final int h, final int v, final int quantTable) {
            this.id = id;
            this.h = h;
            this.v = v;
            this.quantTable = quantTable;
            Arrays.fill(this.approx, -1);
        }

        int blockOffset(final int x, final int y) {
            return (y * this.blocksX + x) * 64;
        }
    }

    private static final class HuffmanTable {
        private static final int LUT_BITS = 8;
        private static final int LUT_SIZE = 1 << LUT_BITS;

        private final int[] maxCode = new int[18];
        private final int[] valueOffset = new int[17];
        private final int[] values;
        // PER-PREFIX FAST LOOKUP: ENTRY 0 = NO MATCH (CODE LONGER THAN LUT_BITS),
        // OTHERWISE (LENGTH << 8) | SYMBOL WITH LENGTH IN 1..LUT_BITS AND SYMBOL IN 0..255
        private final int[] lut = new int[LUT_SIZE];

        HuffmanTable(final int[] counts, final int[] values) throws CodecException {
            this.values = values;
            Arrays.fill(this.maxCode, -1);
            int code = 0;
            int index = 0;
            for (int length = 1; length <= 16; length++) {
                final int count = counts[length];
                if (count > 0) {
                    // REJECT AN OVERSUBSCRIBED (NON-CANONICAL) CODE: MORE CODES AT THIS LENGTH THAN THE
                    // CODE SPACE ALLOWS OVERFLOWS THE LUT PREFIX ((code+n) << (LUT_BITS-length)) PAST 255
                    // AND THE values[] INDEXING: AN AIOOBE THAT WOULD ESCAPE THE IOException CONTRACT
                    if (code + count > (1 << length)) throw new CodecException("Oversubscribed JPEG Huffman table");
                    this.maxCode[length] = code + count - 1;
                    this.valueOffset[length] = index - code;
                    // FILL LOOKUP TABLE FOR CODES THAT FIT IN LUT_BITS
                    if (length <= LUT_BITS) {
                        for (int n = 0; n < count; n++) {
                            final int sym = values[index + n] & 0xFF;
                            final int entry = (length << 8) | sym;
                            final int prefix = (code + n) << (LUT_BITS - length);
                            final int suffixes = 1 << (LUT_BITS - length);
                            for (int s = 0; s < suffixes; s++) this.lut[prefix | s] = entry;
                        }
                    }
                    index += count;
                    code += count;
                }
                code <<= 1;
            }
            this.maxCode[17] = Integer.MAX_VALUE;
        }

        int decode(final EntropyReader r) throws CodecException {
            if (r.bitCount < LUT_BITS) r.refill();
            if (r.bitCount >= LUT_BITS) {
                final int peek = (int) (r.bitBuf >>> (r.bitCount - LUT_BITS)) & (LUT_SIZE - 1);
                final int entry = this.lut[peek];
                if (entry != 0) {
                    r.bitCount -= entry >>> 8;
                    return entry & 0xFF;
                }
            }
            // SLOW PATH: WALK BIT-BY-BIT (FOR CODES > LUT_BITS OR LOW BUFFER STATE NEAR EOF)
            int code = 0;
            for (int length = 1; length <= 16; length++) {
                code = (code << 1) | r.readBit();
                if (code <= this.maxCode[length]) return this.values[this.valueOffset[length] + code];
            }
            throw new CodecException("Invalid JPEG Huffman code");
        }
    }

    private static final class EntropyReader {
        private final ByteBuffer input;
        long bitBuf;
        int bitCount;
        // ENTROPY BYTES FED INTO bitBuf; A SCAN THAT ENDS WITH ZERO OF THEM MADE NO PROGRESS AT ALL
        int consumed;
        private int pendingMarker = -1;

        EntropyReader(final ByteBuffer input) {
            this.input = input;
        }

        // FAST REFILL: ACCUMULATES UP TO 32 BITS INTO bitBuf. STOPS AT TRUNCATION OR EMBEDDED MARKER.
        void refill() throws CodecException {
            while (this.bitCount <= 24) {
                if (this.pendingMarker >= 0 || !this.input.hasRemaining()) return;
                final int v = this.input.get() & 0xFF;
                if (v == 0xFF) {
                    int marker;
                    do {
                        if (!this.input.hasRemaining()) throw new CodecException("Truncated JPEG entropy stream");
                        marker = this.input.get() & 0xFF;
                    } while (marker == 0xFF);
                    if (marker != 0x00) {
                        this.pendingMarker = marker;
                        return;
                    }
                    // STUFFED 0xFF00 -> EMIT REAL 0xFF
                    this.bitBuf = (this.bitBuf << 8) | 0xFF;
                } else {
                    this.bitBuf = (this.bitBuf << 8) | v;
                }
                this.bitCount += 8;
                this.consumed++;
            }
        }

        int readBit() throws CodecException {
            if (this.bitCount == 0) {
                this.refill();
                if (this.bitCount == 0) throw new CodecException("Unexpected JPEG marker FF" + hex(this.pendingMarker) + " in entropy stream");
            }
            this.bitCount--;
            return (int) (this.bitBuf >>> this.bitCount) & 1;
        }

        int readBits(final int count) throws CodecException {
            if (count == 0) return 0;
            if (this.bitCount < count) {
                this.refill();
                if (this.bitCount < count) throw new CodecException("Unexpected JPEG marker FF" + hex(this.pendingMarker) + " in entropy stream");
            }
            this.bitCount -= count;
            return (int) (this.bitBuf >>> this.bitCount) & ((1 << count) - 1);
        }

        int receiveExtend(final int count) throws CodecException {
            if (count == 0) return 0;
            final int value = this.readBits(count);
            final int threshold = 1 << (count - 1);
            return value < threshold ? value - ((1 << count) - 1) : value;
        }

        int finishScan() throws CodecException {
            this.bitCount = 0;
            this.bitBuf = 0L;
            if (this.pendingMarker >= 0) {
                final int marker = this.pendingMarker;
                this.pendingMarker = -1;
                return marker;
            }
            while (this.input.hasRemaining()) {
                final int value = readU8(this.input);
                if (value != 0xFF) continue;
                int marker;
                do {
                    if (!this.input.hasRemaining()) return EOI;
                    marker = readU8(this.input);
                } while (marker == 0xFF);
                if (marker == 0x00 || isRestart(marker)) continue;
                return marker;
            }
            return EOI;
        }

        void consumeRestart() throws CodecException {
            this.bitCount = 0;
            this.bitBuf = 0L;
            int marker = -1;
            if (this.pendingMarker >= 0) {
                marker = this.pendingMarker;
                this.pendingMarker = -1;
            } else {
                while (this.input.hasRemaining()) {
                    final int value = readU8(this.input);
                    if (value != 0xFF) continue;
                    do {
                        if (!this.input.hasRemaining()) throw new CodecException("Truncated JPEG restart marker");
                        marker = readU8(this.input);
                    } while (marker == 0xFF);
                    break;
                }
            }
            if (!isRestart(marker)) throw new CodecException("Expected JPEG restart marker");
        }
    }
}
