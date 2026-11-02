package org.watermedia.api.codecs.readers;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.common.Limits;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.tools.DataTool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Streaming Netpbm reader: plain ASCII PBM/PGM/PPM ({@code P1}..{@code P3}), their binary variants
 * ({@code P4}..{@code P6}) and PAM ({@code P7}) with the BLACKANDWHITE, GRAYSCALE and RGB tuple types,
 * each with or without alpha.
 *
 * <p>Receives a {@link ByteBuffer} positioned after the 2-byte {@code P<digit>} magic, plus the
 * digit value passed as a constructor argument by {@code CodecsAPI}. Parses the rest of the
 * text header (whitespace, width, height, maxval, plus TUPLTYPE/ENDHDR for P7), then in
 * {@link #next()} reads the raster lazily and decodes into BGRA.
 */
public final class NetpbmReader extends ImageReader {
    // HEADER CEILINGS: P1..P6 CARRY FOUR NUMERIC TOKENS AND P7 A HANDFUL OF KEY-VALUE LINES, SO A HEADER THAT NEVER
    // TERMINATES (A P7 WITHOUT ENDHDR, AN ALL-COMMENT P4/P5/P6) IS REFUSED INSTEAD OF BUFFERING THE WHOLE BODY
    private static final int MAX_PLAIN_HEADER_BYTES = 4_096;
    private static final int MAX_PAM_HEADER_BYTES = 65_536;
    // A PLAIN SAMPLE IS AT MOST MAXVAL (65535), SO FIVE DIGITS; LONGER RUNS ARE MALFORMED, NOT BIG NUMBERS
    private static final int MAX_SAMPLE_DIGITS = 5;
    private static final byte[] ENDHDR = "ENDHDR".getBytes(StandardCharsets.US_ASCII);

    private final Type type;
    private final Header header;
    private final ByteBuffer directOut;
    private final int rasterStart;
    private boolean delivered;

    public NetpbmReader(final ByteBuffer data, final int version) throws IOException { this(data, version, null); }

    /** Parses the header that follows the {@code P<version>} magic; a version outside 1 to 7 is an {@link IllegalArgumentException}. */
    public NetpbmReader(final ByteBuffer data, final int version, final PixelFormat requestedFormat) throws IOException {
        super(data, requestedFormat);
        if (version < 1 || version > 7) throw new IllegalArgumentException("Netpbm version must be 1 to 7, not " + version);
        // RE-SYNTHESIZE "P<digit>" SO Header.read SEES THE WHOLE HEADER; readHeaderBytes CAPS THE BUFFERING
        this.header = Header.read(ByteBuffer.wrap(readHeaderBytes(this.data, version)));
        this.type = Type.values()[this.header.version() - 1];

        // THE PIXEL BUDGET IS WHAT THE VERY NEXT STATEMENT RESERVES: A PER-AXIS CAP ALONE LETS A 15-BYTE
        // HEADER RESERVE 1 GiB OF DIRECT MEMORY BEFORE ONE RASTER BYTE IS READ
        if (!Limits.fits(this.header.width(), this.header.height()))
            throw new CodecException("Netpbm dimensions too big: " + this.header.width() + "x" + this.header.height()
                    + " (max " + Limits.MAX_DIM + " per axis, " + Limits.MAX_PIXELS + " pixels)");
        this.directOut = ByteBuffer.allocateDirect(this.header.width() * this.header.height() * 4).order(ByteOrder.LITTLE_ENDIAN);
        // SNAPSHOT THE RASTER START SO reset() CAN REPLAY WITHOUT RE-PARSING THE HEADER
        this.rasterStart = this.data.position();
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    @Override public int width() { return this.header.width(); }
    @Override public int height() { return this.header.height(); }
    @Override public ImageData.Scan scan() { return ImageData.Scan.EMPTY; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override protected PixelFormat decodedFormat() { return PixelFormat.BGRA; }

    @Override
    protected boolean hasMore() {
        return !this.delivered;
    }

    @Override
    protected ByteBuffer decode() throws IOException {
        this.delivered = true;
        this.directOut.clear();
        final int pixels = this.header.width() * this.header.height();
        final int maxVal = this.header.maxVal() != null ? this.header.maxVal() : 1;
        switch (this.type) {
            // PLAIN RASTERS ARE DECIMAL TOKENS; A PLAIN BITMAP BIT IS ONE CHARACTER THAT NEEDS NO SEPARATOR
            case PBM_PLAIN -> {
                for (int i = 0; i < pixels; i++) {
                    final int v = this.plainSample(true) == 1 ? 0 : 255;
                    this.directOut.putInt(0xFF000000 | (v << 16) | (v << 8) | v);
                }
            }
            case PGM_PLAIN -> {
                for (int i = 0; i < pixels; i++) {
                    final int g = Math.min(255, this.plainSample(false) * 255 / maxVal);
                    this.directOut.putInt(0xFF000000 | (g << 16) | (g << 8) | g);
                }
            }
            case PPM_PLAIN -> {
                for (int i = 0; i < pixels; i++) {
                    final int r = Math.min(255, this.plainSample(false) * 255 / maxVal);
                    final int g = Math.min(255, this.plainSample(false) * 255 / maxVal);
                    final int b = Math.min(255, this.plainSample(false) * 255 / maxVal);
                    this.directOut.putInt(0xFF000000 | (r << 16) | (g << 8) | b);
                }
            }
            case PBM -> {
                final int rowBytes = (this.header.width() + 7) >> 3;
                int row = this.raster(rowBytes);
                for (int y = 0; y < this.header.height(); y++, row += rowBytes) {
                    for (int x = 0; x < this.header.width(); x++) {
                        final int v = ((this.data.get(row + (x >> 3)) >> (7 - (x & 7))) & 1) == 1 ? 0 : 255;
                        this.directOut.putInt(0xFF000000 | (v << 16) | (v << 8) | v);
                    }
                }
            }
            case PGM -> this.decodeGrayscale(1, maxVal, false);
            case PPM -> this.decodeColor(3, maxVal, false);
            case PAM -> {
                // THE HEADER ALREADY BOUND DEPTH AND MAXVAL TO A KNOWN TUPLE TYPE, SO EACH DECODER TRUSTS ITS SAMPLE COUNT
                final int depth = this.header.depth();
                switch (this.header.tuplType()) {
                    case "BLACKANDWHITE", "BLACKANDWHITE_ALPHA" -> {
                        // MAXVAL 1 MEANS ONE BYTE PER SAMPLE; ANY NONZERO SAMPLE IS WHITE OR OPAQUE
                        final boolean hasAlpha = depth == 2;
                        int offset = this.raster((long) this.header.width() * depth);
                        for (int i = 0; i < pixels; i++, offset += depth) {
                            final int v = this.data.get(offset) == 0 ? 0 : 255;
                            final int a = hasAlpha && this.data.get(offset + 1) == 0 ? 0 : 255;
                            this.directOut.putInt((a << 24) | (v << 16) | (v << 8) | v);
                        }
                    }
                    case "GRAYSCALE" -> this.decodeGrayscale(depth, maxVal, false);
                    case "GRAYSCALE_ALPHA" -> this.decodeGrayscale(depth, maxVal, true);
                    case "RGB" -> this.decodeColor(depth, maxVal, false);
                    case "RGB_ALPHA" -> this.decodeColor(depth, maxVal, true);
                }
            }
        }
        this.directOut.flip();
        return this.directOut;
    }

    @Override
    protected boolean rewind() {
        this.data.position(this.rasterStart);
        this.delivered = false;
        return true;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void decodeGrayscale(final int depth, final int maxVal, final boolean hasAlpha) throws CodecException {
        final int bytes = maxVal < 256 ? 1 : 2;
        int offset = this.raster((long) this.header.width() * depth * bytes);
        for (int i = this.header.width() * this.header.height(); i > 0; i--, offset += depth * bytes) {
            final int sample = this.sample(offset, bytes);
            final int as = hasAlpha ? this.sample(offset + bytes, bytes) : maxVal;
            // CLAMP: A MALFORMED SAMPLE ABOVE maxVal WOULD RESCALE PAST 8 BITS AND CORRUPT ADJACENT CHANNELS
            final int gray = Math.min(255, (sample * 255) / maxVal);
            final int a = Math.min(255, (as * 255) / maxVal);
            this.directOut.putInt((a << 24) | (gray << 16) | (gray << 8) | gray);
        }
    }

    private void decodeColor(final int depth, final int maxVal, final boolean hasAlpha) throws CodecException {
        final int bytes = maxVal < 256 ? 1 : 2;
        int offset = this.raster((long) this.header.width() * depth * bytes);
        for (int i = this.header.width() * this.header.height(); i > 0; i--, offset += depth * bytes) {
            // CLAMP: A MALFORMED SAMPLE ABOVE maxVal WOULD RESCALE PAST 8 BITS AND BLEED INTO ADJACENT CHANNELS
            final int r = Math.min(255, (this.sample(offset, bytes) * 255) / maxVal);
            final int g = Math.min(255, (this.sample(offset + bytes, bytes) * 255) / maxVal);
            final int b = Math.min(255, (this.sample(offset + bytes * 2, bytes) * 255) / maxVal);
            final int a = hasAlpha ? Math.min(255, (this.sample(offset + bytes * 3, bytes) * 255) / maxVal) : 255;
            this.directOut.putInt((a << 24) | (r << 16) | (g << 8) | b);
        }
    }

    // PROVES THE WHOLE BINARY RASTER IS PRESENT BEFORE A PIXEL IS WRITTEN AND CONSUMES IT, RETURNING WHERE IT STARTS;
    // THE ROW SIZE DERIVES FROM HEADER FIELDS, SO A 69-BYTE FILE CAN DECLARE GIGABYTES OF IT
    private int raster(final long rowBytes) throws CodecException {
        final long need = rowBytes * this.header.height();
        if (need > this.data.remaining())
            throw new CodecException("Truncated Netpbm raster: need " + need + " bytes, have " + this.data.remaining());
        final int start = this.data.position();
        this.data.position(start + (int) need);
        return start;
    }

    // ONE BINARY SAMPLE: A BYTE, OR A BIG-ENDIAN SHORT WHEN MAXVAL EXCEEDS 255
    private int sample(final int offset, final int bytes) {
        return bytes == 1 ? this.data.get(offset) & 0xFF : DataTool.u16(this.data, offset, ByteOrder.BIG_ENDIAN);
    }

    // NEXT SAMPLE OF A PLAIN RASTER: WHITESPACE AND '#' COMMENTS MAY APPEAR ANYWHERE, A BITMAP BIT IS A SINGLE
    // '0' OR '1' CHARACTER AND EVERY OTHER SAMPLE IS A DECIMAL RUN ENDED BY WHITESPACE, A COMMENT OR EOF
    private int plainSample(final boolean bit) throws CodecException {
        int c;
        while (true) {
            c = readUnsignedOrEnd(this.data);
            if (c == '#') {
                while (c >= 0 && c != '\n') c = readUnsignedOrEnd(this.data);
                continue;
            }
            if (c < 0) throw new CodecException("Truncated Netpbm plain raster");
            if (!whitespace(c)) break;
        }
        if (bit) {
            if (c != '0' && c != '1') throw new CodecException("Invalid plain bitmap bit: " + (char) c);
            return c - '0';
        }
        int value = 0;
        int digits = 0;
        while (c >= '0' && c <= '9') {
            if (++digits > MAX_SAMPLE_DIGITS) throw new CodecException("Plain Netpbm sample too long");
            value = value * 10 + (c - '0');
            c = readUnsignedOrEnd(this.data);
        }
        if (digits == 0) throw new CodecException("Invalid plain Netpbm sample: " + (char) c);
        // THE TERMINATOR IS CONSUMED UNLESS IT OPENS A COMMENT, WHICH THE NEXT SAMPLE SKIPS WHOLE
        if (c == '#') this.data.position(this.data.position() - 1);
        return value;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static int readUnsignedOrEnd(final ByteBuffer in) {
        return in.hasRemaining() ? in.get() & 0xFF : -1;
    }

    // NETPBM HEADER WHITESPACE (C isspace()); THE HEADER PRE-SCANNER, THE TOKEN PARSER AND THE PLAIN RASTER SHARE IT
    // SO ALL THREE AGREE ON WHERE A TOKEN ENDS
    private static boolean whitespace(final int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0x0C;
    }

    // RE-EMITS "P<digit>" THEN DRAINS THE ASCII HEADER FROM THE STREAM UNTIL THE RASTER BEGINS. THE BOUNDARY
    // DEPENDS ON THE VERSION: P1..P6 END AFTER THE LAST NUMERIC TOKEN'S TRAILING WHITESPACE BYTE (CONSUMED),
    // P7 ENDS AFTER THE "ENDHDR\n" LINE
    private static byte[] readHeaderBytes(final ByteBuffer in, final int version) throws CodecException {
        final int maxHeader = version == 7 ? MAX_PAM_HEADER_BYTES : MAX_PLAIN_HEADER_BYTES;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write('P');
        out.write('0' + version);

        if (version == 7) {
            // READ UNTIL A LINE STARTING WITH "ENDHDR", THEN THROUGH ITS TERMINATOR
            int sentIdx = 0;
            boolean endhdrSeen = false;
            int last = -1;
            while (true) {
                final int b = readUnsignedOrEnd(in);
                if (b < 0) throw new CodecException("Truncated PAM header");
                if (out.size() >= maxHeader) throw new CodecException("Netpbm header exceeds " + maxHeader + " bytes");
                out.write(b);
                if (endhdrSeen) {
                    if (b == '\n') return out.toByteArray();
                    continue;
                }
                if (last == '\n' || last == -1) {
                    sentIdx = b == ENDHDR[0] ? 1 : 0;
                } else if (sentIdx > 0 && sentIdx < ENDHDR.length && b == ENDHDR[sentIdx]) {
                    sentIdx++;
                    if (sentIdx == ENDHDR.length) endhdrSeen = true;
                } else {
                    sentIdx = 0;
                }
                last = b;
            }
        }

        // P1..P6: "Pn WSP width WSP height [WSP maxval] WSP" WITH '#' COMMENTS TO '\n'; BITMAPS (P1/P4) CARRY 3 TOKENS.
        // THE TOKEN RULES MUST MATCH Header.nextToken EXACTLY: THIS SCAN DECIDES WHERE THE RASTER STARTS AND
        // THAT ONE WHAT THE DIMENSIONS ARE, SO A DISAGREEMENT DESYNCS THEM
        final int expectedTokens = (version == 1 || version == 4) ? 3 : 4;
        int tokens = 1; // ALREADY WROTE "Pn"
        boolean inComment = false;
        boolean inToken = false;
        while (tokens < expectedTokens || inToken || inComment) {
            final int b = readUnsignedOrEnd(in);
            if (b < 0) throw new CodecException("Truncated Netpbm header");
            if (out.size() >= maxHeader) throw new CodecException("Netpbm header exceeds " + maxHeader + " bytes");
            out.write(b);
            if (inComment) {
                if (b != '\n') continue;
                // FALL THROUGH ON THE TERMINATOR: A COMMENT'S NEWLINE IS ALSO WHITESPACE
                inComment = false;
            } else if (b == '#') {
                // '#' ENDS THE CURRENT TOKEN INSTEAD OF MERELY OPENING A COMMENT
                inComment = true;
                if (inToken) { tokens++; inToken = false; }
                continue;
            }
            if (whitespace(b)) {
                if (inToken) { tokens++; inToken = false; }
            } else {
                inToken = true;
            }
        }
        return out.toByteArray();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // NETPBM VARIANTS IN MAGIC-NUMBER ORDER: PLAIN ASCII P1..P3, THEN BINARY P4..P7. THE ORDINAL IS THE MAGIC DIGIT
    // MINUS ONE, SO NEVER REORDER
    private enum Type {
        PBM_PLAIN,
        PGM_PLAIN,
        PPM_PLAIN,
        PBM,
        PGM,
        PPM,
        PAM
    }

    // PARSED HEADER OF ANY VARIANT; depth AND maxVal ARE NULL WHEN THE VARIANT DOES NOT DECLARE THEM, AND tuplType
    // FOLLOWS THE PAM SPEC SPELLING
    private record Header(int version, int width, int height, Integer depth, Integer maxVal, String tuplType) {
        // TUPLTYPE IS THE ONLY FREE-FORM HEADER VALUE AND IT REACHES EXCEPTION MESSAGES (AND THE LOG) VERBATIM.
        // A REAL PAM HEADER IS UNDER 200 BYTES IN TOTAL, SO THIS IS ALREADY GENEROUS
        private static final int MAX_TUPLTYPE_CHARS = 256;

        // ======================================================================
        // INTERNAL API
        // ======================================================================
        // PARSES AND VALIDATES AN ASCII HEADER STARTING AT ITS "Pn" TOKEN: A PAM TUPLE TYPE OUTSIDE THE SIX THE READER
        // DECODES IS AN UnsupportedFormatException, A MALFORMED HEADER OR AN OUT-OF-RANGE VALUE A CodecException
        static Header read(final ByteBuffer data) throws CodecException {
            int version = 0;
            int width = 0;
            int height = 0;
            Integer depth = null;
            Integer maxVal = null;
            String tuplType = "";
            boolean tuplTypeSeen = false;
            int tokenIndex = 0;

            label:
            while (data.hasRemaining()) {
                final String token = nextToken(data);
                if (token.isEmpty()) break;
                tokenIndex++;

                if (tokenIndex == 1 && token.startsWith("P")) {
                    version = parseInt(token.substring(1));
                    continue;
                }

                switch (version) {
                    case 1, 4 -> {
                        switch (tokenIndex) {
                            case 2 -> width = parseInt(token);
                            case 3 -> {
                                height = parseInt(token);
                                break label;
                            }
                        }
                    }
                    case 2, 3, 5, 6 -> {
                        switch (tokenIndex) {
                            case 2 -> width = parseInt(token);
                            case 3 -> height = parseInt(token);
                            case 4 -> {
                                maxVal = parseInt(token);
                                break label;
                            }
                        }
                    }
                    case 7 -> {
                        // PAM: KEY-VALUE PAIRS UNTIL ENDHDR
                        switch (token) {
                            case "WIDTH" -> width = nextTokenInt(data);
                            case "HEIGHT" -> height = nextTokenInt(data);
                            case "DEPTH" -> depth = nextTokenInt(data);
                            case "MAXVAL" -> maxVal = nextTokenInt(data);
                            case "TUPLTYPE" -> {
                                // A REPEATED TUPLTYPE IS REJECTED: CONCATENATING THEM WOULD LET ONE FILE GROW AN UNBOUNDED STRING
                                if (tuplTypeSeen) throw new CodecException("Duplicate PAM TUPLTYPE");
                                tuplTypeSeen = true;
                                tuplType = readRestOfLine(data).trim();
                            }
                            case "ENDHDR" -> {
                                break label;
                            }
                        }
                    }
                    default -> throw new CodecException("Unknown Netpbm version: " + version);
                }
            }

            if (width <= 0) throw new CodecException("Invalid WIDTH: " + width + ". Must be greater than 0.");
            if (height <= 0) throw new CodecException("Invalid HEIGHT: " + height + ". Must be greater than 0.");

            // EVERYTHING BUT A BITMAP REQUIRES MAXVAL; A TRUNCATED HEADER LEAVES IT NULL AND NPEs THE DECODER UNDER A BLANKET CATCH
            if (version != 1 && version != 4 && maxVal == null)
                throw new CodecException("Missing MAXVAL for P" + version);
            if (maxVal != null && (maxVal <= 0 || maxVal > 65_535))
                throw new CodecException("Invalid MAXVAL: " + maxVal + ". Must be between 1 and 65535.");

            if (version == 7) {
                // THE TUPLE TYPE BELOW BOUNDS DEPTH TO 1..4; A MISSING OR NON-POSITIVE ONE IS MALFORMED WHATEVER THE TYPE
                if (depth == null || depth <= 0)
                    throw new CodecException("Invalid DEPTH: " + depth + ". Must be greater than 0.");

                // DEPTH SIZES EVERY ROW AND IS THE ONE RASTER DIMENSION THE PIXEL CAP DOES NOT COVER, SO IT IS BOUND TO
                // THE TUPLE TYPE: "DEPTH 65535 / TUPLTYPE RGB" IS A 2 GiB ROW DESCRIBED BY 69 BYTES. ANY OTHER TUPLE TYPE
                // (OR NONE) IS VALID PAM WITH NO SAMPLE LAYOUT THE READER KNOWS, SO IT IS REFUSED BEFORE A RASTER IS SIZED
                final int expectedDepth = switch (tuplType) {
                    case "BLACKANDWHITE", "GRAYSCALE" -> 1;
                    case "BLACKANDWHITE_ALPHA", "GRAYSCALE_ALPHA" -> 2;
                    case "RGB" -> 3;
                    case "RGB_ALPHA" -> 4;
                    default -> throw new UnsupportedFormatException("Unsupported PAM tuple type: " + (tuplType.isEmpty() ? "(none)" : tuplType));
                };
                if (depth != expectedDepth)
                    throw new CodecException("PAM DEPTH " + depth + " does not match TUPLTYPE " + tuplType);
                if (tuplType.startsWith("BLACKANDWHITE") && maxVal != 1)
                    throw new CodecException("PAM " + tuplType + " requires MAXVAL 1, not " + maxVal);
            }

            return new Header(version, width, height, depth, maxVal, tuplType);
        }

        // ======================================================================
        // UTILITY
        // ======================================================================
        private static int nextTokenInt(final ByteBuffer buf) throws CodecException {
            final String valueToken = nextToken(buf);
            if (valueToken.isBlank()) throw new CodecException("Expected integer token but found blank");
            return parseInt(valueToken);
        }

        private static int parseInt(final String string) throws CodecException {
            try {
                return Integer.parseInt(string);
            } catch (final NumberFormatException e) {
                throw new CodecException(e);
            }
        }

        private static String nextToken(final ByteBuffer buf) {
            final StringBuilder sb = new StringBuilder();
            while (buf.hasRemaining()) {
                // & 0xFF: A BARE CAST SIGN-EXTENDS 0x80.. INTO U+FF80.., CORRUPTING THE TOKEN AND DOUBLING ITS FOOTPRINT
                final char c = (char) (buf.get() & 0xFF);
                if (c == '#') {
                    // A COMMENT ENDS THE CURRENT TOKEN AND RUNS TO END OF LINE, SO "1#x\n6384" STAYS THE TWO
                    // TOKENS EVERY REFERENCE DECODER READS INSTEAD OF MERGING INTO "16384"
                    while (buf.hasRemaining() && buf.get() != '\n') { /* SKIP THE COMMENT BODY */ }
                    if (!sb.isEmpty()) break;
                    continue;
                }
                if (whitespace(c)) {
                    if (!sb.isEmpty()) break;
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        // READS A TAG VALUE UP TO ITS END OF LINE. THE VALUE IS ATTACKER-CHOSEN AND IS INTERPOLATED INTO
        // MESSAGES THE PLAYER LOGS VERBATIM, SO IT IS BOUNDED AND EVERYTHING OUTSIDE PRINTABLE ASCII
        // BECOMES A SPACE: A RAW CR/LF WOULD LET AN IMAGE FORGE WHOLE LINES INTO latest.log
        private static String readRestOfLine(final ByteBuffer buf) throws CodecException {
            final StringBuilder sb = new StringBuilder();
            while (buf.hasRemaining()) {
                final char c = (char) (buf.get() & 0xFF);
                if (c == '\n') break;
                if (sb.length() >= MAX_TUPLTYPE_CHARS) throw new CodecException("PAM TUPLTYPE too long");
                sb.append(c >= 0x20 && c < 0x7F ? c : ' ');
            }
            return sb.toString();
        }
    }
}
