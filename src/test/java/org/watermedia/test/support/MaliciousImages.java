package org.watermedia.test.support;

import org.watermedia.api.util.PixelFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.test.support.ImageBytes.deflate;
import static org.watermedia.test.support.ImageBytes.ihdr;
import static org.watermedia.test.support.ImageBytes.pngChunk;
import static org.watermedia.test.support.ImageBytes.riff;
import static org.watermedia.test.support.ImageBytes.vp8x;
import static org.watermedia.test.support.ImageBytes.webpChunk;

/** Generator of the hostile image corpus under {@code src/test/resources/pentest}; {@code PentestTest} reads the committed output. */
public final class MaliciousImages {

    private MaliciousImages() {}

    public static void main(final String[] args) throws IOException {
        // EACH FILE SITS NEXT TO ITS VULNERABILITY CLASS, THE CVE IT MIRRORS AND THE GUARD THAT REFUSES IT. PAYLOADS STAY
        // SMALL: EVERY BOMB DECLARES ITS COST IN HEADER FIELDS (DIMENSIONS, FRAME COUNTS, EXPANSION RATIOS), NOT IN BULK
        final Path root = Fixtures.PENTEST_DIR;
        gif(root.resolve("gif"));
        png(root.resolve("png"));
        jpeg(root.resolve("jpeg"));
        netpbm(root.resolve("netpbm"));
        dds(root.resolve("dds"));
        webp(root.resolve("webp"));
        svg(root.resolve("svg"));
        System.out.println("Corpus written to " + root.toAbsolutePath());
    }

    // ==========================================================================
    // NETPBM
    // ==========================================================================
    // THE PLAIN VARIANTS P1..P3 SHARE THE HEADER PARSER AND THE PIXEL CAP WITH P4..P7, SO THESE FIXTURES COVER THEM TOO
    private static void netpbm(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // 15 ASCII BYTES DECLARING 16384x16384: EACH AXIS PASSES, BUT Limits.fits REFUSES THE PRODUCT BEFORE THE 1 GiB
        // DIRECT BUFFER IS RESERVED. THE TRAILING NEWLINE ENDS THE HEIGHT TOKEN SO THE HEADER PARSES
        write(dir, "direct-memory-bomb.pam", ascii("P4\n16384 16384\n"));

        // DEPTH 65535 WOULD SIZE A 2 GiB ROW FROM 69 BYTES (CVE-2017-2581's RASTER ARITHMETIC); NetpbmReader.Header BINDS DEPTH
        // TO THE TUPLE TYPE, SO RGB WITH ANY DEPTH BUT 3 IS REFUSED BEFORE A ROW IS SIZED. HEIGHT 1 DODGES SIZE HEURISTICS
        write(dir, "pam-depth-bomb.pam", ascii(
                "P7\nWIDTH 16384\nHEIGHT 1\nDEPTH 65535\nMAXVAL 65535\nTUPLTYPE RGB\nENDHDR\n"));

        // TRUNCATED HEADERS AND RASTER: EACH END OF INPUT MUST SURFACE AS CodecException, NEVER A RAW EOFException
        write(dir, "truncated-header.pam", ascii("P5"));
        write(dir, "truncated-pam-header.pam", ascii("P7"));
        write(dir, "truncated-raster.pam", ascii("P5\n1 1 255\n"));

        // A 60 KB TUPLTYPE OF CARRIAGE RETURNS AND FORGED LOG PREFIXES, AIMED AT THE MESSAGE THE PLAYER LOGS;
        // NetpbmReader.Header CAPS THE VALUE AT 256 CHARACTERS AND BLANKS EVERY NON-PRINTABLE BYTE
        write(dir, "tupltype-log-injection.pam", ascii(
                "P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nMAXVAL 1\nTUPLTYPE "
                        + "\rX [12:00:00] [Render thread/INFO]: forged log line ".repeat(1_200)
                        + "\nENDHDR\n"));

        // A P7 HEADER THAT NEVER REACHES ENDHDR; THE HEADER SCAN IS CAPPED AT 64 KiB AND REFUSES THE MISSING
        // TERMINATOR AT END OF INPUT INSTEAD OF BUFFERING THE WHOLE BODY
        write(dir, "unbounded-header.pam", ascii("P7\n#" + "Z".repeat(40_000) + "\n"));

        // A COMMENT BETWEEN DIGITS: THE TOKENIZER ENDS A TOKEN AT '#', SO "1#x\n6384" READS AS 1x6384 LIKE EVERY
        // REFERENCE DECODER AND NEVER AS 16384, WHICH WOULD LET AN UPSTREAM SIZE CHECK SEE DIFFERENT DIMENSIONS
        write(dir, "comment-token-merge.pam", ascii("P5\n1#x\n6384 100 255\n"));

        // VERTICAL TABS AS SEPARATORS: THE HEADER PRE-SCANNER AND THE TOKEN PARSER SHARE NetpbmReader.whitespace,
        // SO BOTH AGREE THE RASTER 0x10..0x13 STARTS RIGHT AFTER THE MAXVAL TOKEN
        write(dir, "whitespace-desync.pam", new ImageBytes()
                .ascii("P5").u8(0x0B).ascii("2").u8(0x0B).ascii("2").u8(0x0B).ascii("255").u8(0x0A)
                .raw(0x10, 0x11, 0x12, 0x13).u8(0x0A)
                .bytes());
    }

    // ==========================================================================
    // DDS / BC
    // ==========================================================================
    // THESE FIXTURES EXERCISE DDS STRUCTURE AND BLOCK-SIZE BOUNDS WITHOUT A GPU
    private static void dds(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // BLOCK COUNT TRUSTED FROM THE HEADER (OpenImageIO CVE-2026-7582, PILLOW CVE-2025-48379): 65536x65536 BC1 BLOCKS
        // TOTAL EXACTLY 2^31, WHICH WRAPS NEGATIVE IN int MATH; THE DIMENSION CAP REFUSES IT BEFORE ANY SIZE MATH
        write(dir, "blockcount-overflow-negative.dds", ddsFile(65536, 65536, PixelFormat.BC1, 1, 1));

        // THE SAME ARITHMETIC LANDING ON ZERO: 65535x65535 BC7 GIVES 2^32, A HUGE TEXTURE BACKED BY NO BLOCKS; THE
        // DIMENSION CAP REFUSES IT
        write(dir, "blockcount-overflow-zero.dds", ddsFile(65535, 65535, PixelFormat.BC7, 1, 1));

        // ARRAY-SIZE AMPLIFICATION: 10000 DECLARED SLICES COST 8 FOOTER BYTES EACH BUT TWO DirectByteBuffer OBJECTS;
        // THE DIMENSION AND arraySize CAPS REFUSE IT
        write(dir, "arraysize-amplification.dds", ddsFile(65535, 65535, PixelFormat.BC7, 10_000, 10_000));

        // STRUCTURAL FIELDS A READER MUST CHECK: dwSize 0, 15 MIP LEVELS, TEXTURE3D AND THE CUBEMAP FLAG (SIX FACES PER
        // SLICE WOULD SEND GARBAGE BLOCKS TO THE GPU AS VALID BCn); THE HEADER CHECK REFUSES THE ZERO dwSize FIRST
        final byte[] header = DdsFixture.write(64, 64, PixelFormat.BC7, 1);
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0).putInt(28, 15).putInt(132, 4).putInt(136, 4);
        // 64x64 BC7 IS 16x16 BLOCKS OF 16 BYTES, SO THE 4096-BYTE PAYLOAD REALLY IS THERE
        write(dir, "unvalidated-structure.dds", new ImageBytes().raw(header).fill(0, 4_096).raw(DdsFixture.writeFooter(0L)).bytes());
    }

    // DDS + DX10 HEADER FOLLOWED BY THE WMTC FRAME FOOTER, WITH THE DIMENSIONS, FORMAT AND ARRAY SIZE EACH ATTACK PICKS
    private static byte[] ddsFile(final int width, final int height, final PixelFormat format, final int arraySize, final int frameCount) {
        return new ImageBytes().raw(DdsFixture.write(width, height, format, arraySize)).raw(DdsFixture.writeFooter(new long[frameCount])).bytes();
    }

    // ==========================================================================
    // WEBP
    // ==========================================================================
    private static void webp(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // A RUN OF 2000 BARE </rdf:li> TERMINATORS BEFORE ONE REAL 20000-CHARACTER ELEMENT, SHAPED TO MAKE A RESCANNING
        // XMP PARSER RETAIN THE SAME BODY ONCE PER TERMINATOR (CWE-1333); THE SCAN ADVANCES PAST EVERY CONSUMED ELEMENT
        final int terminators = 2_000;
        final int bodyLength = 20_000;
        // THE TRAILING VP8L MAKES THE FILE DECODABLE, SO A REFUSAL CANNOT HIDE WHAT THE XMP PARSE COST
        write(dir, "xmp-quadratic-bomb.webp", riff(webpChunk("VP8X", vp8x(0x00, 1, 1)),
                webpChunk("XMP ", ascii("<dc:title>" + "</rdf:li>".repeat(terminators)
                        + "<rdf:li>" + "Z".repeat(bodyLength) + "</rdf:li></dc:title>")),
                webpChunk("VP8L", new ImageBytes().u8(0x2F).le32(0).raw(vp8lSingleSymbolGroup()).bytes())));

        // AN ANIMATED 8192x8192 VP8X WITH NO FRAME: THE PIXEL CAP ADMITS IT, AND THE 256 MiB COMPOSITING CANVAS IS ONLY
        // ALLOCATED FOR A FRAME, SO THE FILE IS REFUSED FOR CARRYING NO PICTURE WITHOUT COSTING ANY MEMORY
        write(dir, "canvas-at-cap-bomb.webp", riff(webpChunk("VP8X", vp8x(0x02, 8_192, 8_192))));

        // AN 8192x8192 VP8L WHOSE FIVE ONE-SYMBOL CODES COST ZERO BITS PER PIXEL (RFC 9649), 67 MILLION PIXELS FROM NO
        // INPUT; THE DECODER CAPS A ZERO-BIT CONSTANT FILL AT 2^24 PIXELS, AS libwebp STOPS AT eos_
        write(dir, "vp8l-zero-bit-fill.webp", riff(webpChunk("VP8L", new ImageBytes()
                .u8(0x2F)                       // VP8L SIGNATURE
                .le32(0x07FFDFFF)               // 14-BIT WIDTH-1 = 8191, 14-BIT HEIGHT-1 = 8191, ALPHA 0, VERSION 0
                .raw(vp8lSingleSymbolGroup())
                .bytes())));

        // AN 8192x8192 KEY FRAME WITH A 2-BYTE PARTITION 0, ~608 MB OF PLANES FROM 52 BYTES IF THE BOOL DECODER READ
        // IMPLICIT ZEROS (CVE-2018-25009/25010/25012/25014); ITS EOF FLAG REFUSES THE DRY PARTITION BEFORE ANY PLANE
        write(dir, "vp8-booldecoder-eof.webp", riff(
                webpChunk("VP8X", vp8x(0x10, 8_192, 8_192)), // ALPHA FLAG FORCES THE BGRA PATH
                webpChunk("VP8 ", new ImageBytes()
                        .raw(0x40, 0x00, 0x00)      // FRAME TAG: KEY FRAME, PARTITION-0 SIZE 2
                        .raw(0x9D, 0x01, 0x2A)      // VP8 START CODE
                        .le16(0x2000).le16(0x2000)  // 8192 x 8192
                        .fill(0x00, 2)              // PARTITION 0
                        .fill(0x00, 2)              // TOKEN PARTITION
                        .bytes())));

        // A 1x1 IMAGE WHOSE ENTROPY IMAGE ENCODES green | (red << 8) = 4096 HUFFMAN GROUPS (~13 MB OF TABLES FROM ~10 KB);
        // A 1x1 ENTROPY IMAGE CAN NAME ONE GROUP, SO THE GROUP GATE REFUSES THE COUNT BEFORE ANY TABLE IS BUILT
        write(dir, "vp8l-huffman-group-bomb.webp", riff(webpChunk("VP8L", new ImageBytes()
                .u8(0x2F)
                .le32(0)                            // 1x1 IMAGE: ALL THE COST IS IN THE GROUP TABLES
                .raw(vp8lGroupBomb())
                .bytes())));

        // TRUNCATED PREAMBLE, CHUNK HEADER AND CHUNK BODIES: THE CHUNK WALK REPORTS EACH AS CodecException
        write(dir, "truncated-preamble.webp", new ImageBytes().ascii("RIFF").le32(4).ascii("WEBP").bytes());
        write(dir, "trailing-partial-chunk.webp", new ImageBytes()
                .raw(riff(webpChunk("VP8X", vp8x(0x00, 1, 1))))
                .ascii("AAAA").bytes());
        write(dir, "chunk-size-past-eof.webp", new ImageBytes()
                .ascii("RIFF").le32(21).ascii("WEBP")
                .ascii("VP8L").le32(65535).u8(0x2F).le32(0)
                .bytes());
        write(dir, "anmf-size-past-eof.webp", new ImageBytes()
                .ascii("RIFF").le32(46).ascii("WEBP")
                .raw(webpChunk("VP8X", vp8x(0x02, 16, 16)))
                .raw(webpChunk("ANIM", new byte[6]))
                .ascii("ANMF").le32(65535).fill(0x00, 4)
                .bytes());
        // AN UNKNOWN TOP-LEVEL FOURCC DECLARING SIZE 0xFFFFFFF8, A BACKWARDS SKIP FOR A SIGNED READER; THE CHUNK WALK READS
        // IT UNSIGNED AS A BODY PAST THE BUFFER AND REFUSES IT AS TRUNCATION
        write(dir, "negative-chunk-size.webp", new ImageBytes()
                .ascii("RIFF").le32(30).ascii("WEBP")
                .raw(webpChunk("VP8X", vp8x(0x00, 1, 1)))
                .ascii("JUNK").le32(-8)
                .bytes());
    }

    // FIVE ONE-SYMBOL PREFIX CODES (GREEN, RED, BLUE, ALPHA, DISTANCE) PRECEDED BY THE THREE
    // STREAM FLAGS. EACH TABLE IS isSimple=1, numSymbols-1=0, is8Bits=0, symbol=0 -> FOUR BITS
    private static byte[] vp8lSingleSymbolGroup() {
        final ImageBytes.Bits bits = new ImageBytes.Bits();
        bits.put(0, 1).put(0, 1).put(0, 1); // NO TRANSFORM, NO COLOR CACHE, NO META HUFFMAN
        for (int table = 0; table < 5; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        return bits.bytes();
    }

    // META-HUFFMAN STREAM WHOSE 1x1 ENTROPY IMAGE ENCODES THE GROUP COUNT IN ITS RED AND GREEN
    // CHANNELS, FOLLOWED BY THAT MANY MINIMAL FIVE-TABLE GROUPS
    private static byte[] vp8lGroupBomb() {
        final int green = 0xFF, red = 0x0F; // 4096 GROUPS
        final ImageBytes.Bits bits = new ImageBytes.Bits();
        bits.put(0, 1).put(0, 1).put(1, 1).put(0, 3); // NO TRANSFORM, NO CACHE, META HUFFMAN, metaBits 2
        bits.put(0, 1);                               // ENTROPY IMAGE: NO SUB COLOR CACHE
        bits.put(1, 1).put(0, 1).put(1, 1).put(green, 8); // GREEN: SIMPLE, 1 SYMBOL, 8-BIT
        bits.put(1, 1).put(0, 1).put(1, 1).put(red, 8);   // RED:   SIMPLE, 1 SYMBOL, 8-BIT
        for (int table = 0; table < 3; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        final int groups = (green | (red << 8)) + 1;
        for (int g = 0; g < groups; g++) {
            for (int table = 0; table < 5; table++) bits.put(1, 1).put(0, 1).put(0, 1).put(0, 1);
        }
        return bits.fill(0x00, 64).bytes(); // SLACK SO THE FEASIBILITY GATE SEES ENOUGH BITS
    }

    // ==========================================================================
    // SVG
    // ==========================================================================
    // SVG PAYLOADS ARE TEXT: THEIR COST COMES FROM XML ENTITY EXPANSION AND DEGENERATE FLOATING-POINT GEOMETRY, NOT FROM
    // DECLARED SIZES. EACH COUNT SITS FAR ABOVE ANY SANE LIMIT BUT WELL UNDER THE MEASURED WORST CASE, SPARING CI A GIGABYTE
    private static void svg(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // INTERNAL-SUBSET ENTITIES EXPANDING TO 300,000 <g> NODES (BILLION LAUGHS, librsvg CVE-2019-20446); SvgParser COUNTS
        // ELEMENTS AGAINST ITS OWN BUDGET, AND THE SUBSET STAYS UNDER THE 4096-BYTE SNIFF WINDOW SO THE FILE STILL READS AS SVG
        write(dir, "entity-markup-bomb.svg", ascii(
                "<?xml version=\"1.0\"?><!DOCTYPE svg [\n"
                        + entity("e0", "<g a='012345'/>".repeat(100))
                        + entity("e1", "&e0;".repeat(100))
                        + entity("e2", "&e1;".repeat(30))
                        + "]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">&e2;</svg>"));

        // ENTITIES EXPANDING INSIDE ONE d ATTRIBUTE TO 500,000 SEGMENTS, WHICH NO JDK LIMIT BOUNDS ON 17 AND 21;
        // THE PATH SEGMENT CAP REFUSES THEM BEFORE THE PATH IS COPIED TOWARDS THE EDGE TABLE
        write(dir, "attribute-entity-path-bomb.svg", ascii(
                "<?xml version='1.0'?><!DOCTYPE svg [\n"
                        + entity("d0", "l1 1".repeat(500))
                        + entity("d1", "&d0;".repeat(100))
                        + entity("d2", "&d1;".repeat(10))
                        + "]><svg xmlns='http://www.w3.org/2000/svg' width='512' height='512'"
                        + " viewBox='0 0 512 512'><path d=\"M0 0&d2;\"/></svg>"));

        // stroke-width="1e999" ON A ZIGZAG: AN INFINITE HALF-WIDTH WOULD APPEND AN INFINITE DISC PER JOIN AND FLATTEN
        // EVERY CUBIC TO FULL DEPTH (cairo CVE-2019-6461); SvgParser REFUSES THE NON-FINITE LENGTH
        final StringBuilder zigzag = new StringBuilder();
        for (int i = 0; i < 8; i++) zigzag.append(i == 0 ? "" : " ").append(i % 2).append(',').append(i);
        write(dir, "stroke-disc-flatten-bomb.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<polyline fill=\"none\" stroke=\"#000\" stroke-width=\"1e999\" points=\""
                        + zigzag + "\"/></svg>"));

        // 2000 SLIVERS AT STRICTLY DESCENDING X, THE WORST ORDER FOR AN ACTIVE-EDGE SORT PER SCANLINE (cairo CVE-2019-6462);
        // SvgRasterizer CAPS THE CROSSINGS ONE FILL MAY WALK. EQUAL KEYS WOULD DEFUSE IT, SO IT CANNOT BE ENTITY-AMPLIFIED
        final StringBuilder edges = new StringBuilder();
        final int edgeCount = 2_000;
        for (int i = 0; i < edgeCount; i++) {
            edges.append("M").append(511 - i * 511.0 / edgeCount).append(" 0h0.01v511h-0.01z");
        }
        write(dir, "scanline-quadratic.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<path d=\"" + edges + "\"/></svg>"));

        // SUBPATH 1 REACHES 1e999 SO 0 * Infinity TURNS A SPAN BOUND INTO NaN NEXT TO SUBPATH 2's EDGE NEAR x = 1e9, A PAIR
        // NO NaN-BLIND CLAMP CATCHES BEFORE INDEXING THE COVERAGE ARRAY; PathParser REFUSES THE INFINITE COORDINATE
        write(dir, "nan-span-oob.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\" viewBox=\"0 0 512 512\">"
                        + "<path d=\"M0,0.125L1e999,8Z M1000000000,0L1000000100,20L1000000050,0.125Z\"/></svg>"));

        // 30,000 ENTITY-AMPLIFIED GRADIENT STOPS, EACH WALKED PER COVERED PIXEL; STOPS NEED NO UNIQUE id, SO A FEW KB BUY
        // SECONDS PER FILL. SvgDocument CAPS A GRADIENT AT 256 STOPS
        write(dir, "gradient-stop-scan.svg", ascii(
                "<?xml version='1.0'?><!DOCTYPE svg [\n"
                        + entity("s0", "<stop stop-color='#123456'/>".repeat(60))
                        + entity("s1", "&s0;".repeat(100))
                        + entity("s2", "&s1;".repeat(5))
                        + "]><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"512\" height=\"512\""
                        + " viewBox=\"0 0 512 512\"><defs><linearGradient id=\"g\">&s2;</linearGradient></defs>"
                        + "<rect width=\"512\" height=\"512\" fill=\"url(#g)\"/></svg>"));

        // A 2000-LINK ACYCLIC xlink:href GRADIENT CHAIN, QUADRATIC TO RESOLVE AND ~46 BYTES PER LINK SINCE EACH NEEDS ITS
        // OWN id; SvgDocument CAPS A CHAIN AT 16 LINKS ON TOP OF ITS CYCLE GUARD
        final StringBuilder chain = new StringBuilder(
                "<linearGradient id=\"g0\"><stop stop-color=\"#fff\"/></linearGradient>");
        for (int i = 1; i < 2_000; i++) {
            chain.append("<linearGradient id=\"g").append(i).append("\" xlink:href=\"#g").append(i - 1).append("\"/>");
        }
        write(dir, "gradient-href-chain.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                        + " width=\"16\" height=\"16\"><defs>" + chain + "</defs></svg>"));

        // rgb)( WITH THE CLOSER BEFORE THE OPENER, AS A FILL AND AS A STOP COLOUR; SvgColor REQUIRES THE CLOSER AFTER THE
        // OPENER, SO BOTH ARE INVALID COLOURS INSTEAD OF A substring WITH begin > end
        write(dir, "color-paren-order-fill.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">"
                        + "<rect width=\"8\" height=\"8\" fill=\"rgb)(\"/></svg>"));
        write(dir, "color-paren-order-stop.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\"><defs>"
                        + "<linearGradient id=\"g\"><stop stop-color=\"rgb)(\"/></linearGradient></defs>"
                        + "<rect width=\"8\" height=\"8\" fill=\"url(#g)\"/></svg>"));

        // AN UNDEFINED ENTITY, THE TERMINAL OBSERVABLE OF EVERY JDK ENTITY-LIMIT TRIP; SvgParser WRAPS EVERY
        // XMLStreamException IN A CodecException INSTEAD OF A BARE IOException
        write(dir, "undefined-entity.svg", ascii(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"8\" height=\"8\">&nope;</svg>"));
    }

    private static String entity(final String name, final String body) {
        return "<!ENTITY " + name + " \"" + body + "\">\n";
    }

    // ==========================================================================
    // PNG / APNG
    // ==========================================================================
    private static void png(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // EVERY FIXTURE CARRIES VALID CRCs: failOnCorruptedData DEFAULTS TO TRUE, SO A BAD CRC WOULD ABORT THE READ BEFORE
        // THE TARGETED CODE PATH IS EVER REACHED

        // A zTXt WHOSE zlib HEADER (0x78,0x20: FDICT SET, CHECKSUM 31*992) SETS FDICT: INFLATE ANSWERS Z_NEED_DICT WITHOUT
        // CONSUMING OR PRODUCING, SO A LOOP ON needsInput() ALONE SPINS; THE INFLATER CHECKS needsDictionary() AND SKIPS IT
        write(dir, "ztxt-fdict-hang.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("zTXt", new ImageBytes().ascii("A").u8(0).u8(0)
                        .raw(0x78, 0x20).be32(1).fill(0x00, 4).bytes()),
                idatGray1x1()));

        // THE SAME STALLED INFLATE REACHED THROUGH iTXt
        write(dir, "itxt-fdict-hang.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("iTXt", new ImageBytes().ascii("A").u8(0).u8(1).u8(0).u8(0).u8(0)
                        .raw(0x78, 0x20).be32(1).fill(0x00, 4).bytes()),
                idatGray1x1()));

        // 16384x16384 RGBA FROM A 45-BYTE IHDR: EACH AXIS PASSES, BUT Limits.fits REFUSES THE PRODUCT BEFORE ANY CANVAS
        // EXISTS, THE DEFENCE libpng SHIPS AS png_set_user_limits
        write(dir, "canvas-pixel-bomb.png", ImageBytes.png(ihdr(16_384, 16_384, 8, 6)));

        // 8192x8192 16-BIT TRUECOLOR DECLARES A 384 MB SCANLINE BUFFER BEHIND AN 8-BYTE IDAT (libpng CVE-2011-3026's
        // SHAPE); THE FIRST INFLATE BUFFER IS SIZED FROM THE COMPRESSED BYTES AND THE SHORT IMAGE DATA IS REFUSED
        write(dir, "inflate-preallocation.png", ImageBytes.png(
                ihdr(8_192, 8_192, 16, 2),
                pngChunk("IDAT", new ImageBytes().raw(0x78, 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01).bytes())));

        // A sPLT ENDING RIGHT AFTER ITS NAME, WITHOUT THE SAMPLE DEPTH BYTE (libpng CVE-2015-8540: A MISSING RESIDUAL-LENGTH
        // CHECK); SPLT CHECKS THE RESIDUAL LENGTH AND THE MALFORMED ANCILLARY CHUNK IS SKIPPED
        write(dir, "splt-truncated-sampledepth.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("sPLT", new ImageBytes().ascii("A").u8(0).bytes()),
                idatGray1x1()));

        // acTL num_plays 0xFFFFFFFB, UNSIGNED IN THE SPEC AND NEGATIVE AS AN int; scan() MAPS EVERY NON-POSITIVE COUNT TO
        // REPEAT_FOREVER INSTEAD OF HANDING ImageData A PLAY COUNT ITS CONSTRUCTOR REJECTS
        write(dir, "actl-negative-loopcount.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("acTL", new ImageBytes().be32(2).be32(-5).bytes()),
                pngChunk("fcTL", fctl(0)),
                idatGray1x1()));

        // A WELL-FORMED 2-BYTE tRNS ON AN RGBA IMAGE, WHICH THE SPEC FORBIDS; THE ANCILLARY CHUNK IS SKIPPED INSTEAD OF
        // FAILING THE DECODE WITH AN IllegalArgumentException
        write(dir, "trns-on-rgba.png", ImageBytes.png(
                ihdr(1, 1, 8, 6),
                pngChunk("tRNS", new ImageBytes().fill(0x00, 2).bytes()),
                pngChunk("IDAT", deflate(new byte[] { 0, 0, 0, 0, 0 }, 9))));

        // A 4-BYTE PLTE; THE PALETTE PARSER REFUSES A LENGTH THAT IS NOT A MULTIPLE OF 3 AS CodecException
        write(dir, "plte-not-multiple-of-3.png", ImageBytes.png(
                ihdr(1, 1, 8, 3),
                pngChunk("PLTE", new ImageBytes().fill(0x00, 4).bytes())));

        // AN 80-BYTE tEXt KEYWORD, ONE PAST THE SPEC LIMIT; THE MALFORMED ANCILLARY CHUNK IS SKIPPED
        write(dir, "text-keyword-overlong.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("tEXt", new ImageBytes().fill('A', 80).u8(0).ascii("v").bytes()),
                idatGray1x1()));

        // A CHUNK LENGTH OF 0x80000000, NEGATIVE AS AN int; PngChunk.read REFUSES IT AS CodecException. EXACTLY 8 TRAILING
        // BYTES LET THE READ REACH THE LENGTH CHECK INSTEAD OF AN EARLIER END OF INPUT
        write(dir, "negative-chunk-length.png", new ImageBytes()
                .raw(ImageBytes.PNG_SIGNATURE)
                .raw(ihdr(1, 1, 8, 0))
                .raw(0x80, 0x00, 0x00, 0x00).fill(0x00, 4)
                .bytes());

        // A STRUCTURALLY VALID PNG WITH NO IDAT; THE READER REFUSES THE MISSING IMAGE DATA AS CodecException
        write(dir, "no-idat.png", ImageBytes.png(ihdr(1, 1, 8, 0)));

        // 4 MiB OF ONE BYTE DEFLATED TO A FEW KB INSIDE zTXt; THE TEXT INFLATE CAP STOPS IT AND THE CHUNK IS SKIPPED
        write(dir, "ztxt-zlib-bomb.png", ImageBytes.png(
                ihdr(1, 1, 8, 0),
                pngChunk("zTXt", new ImageBytes().ascii("A").u8(0).u8(0)
                        .raw(deflate(new ImageBytes().fill('A', 4 * 1_024 * 1_024).bytes(), 9)).bytes()),
                idatGray1x1()));

        // gAMA 0x80000000 WOULD DRIVE THE GAMMA TABLE TO INFINITY AND SATURATE INTO THE ALPHA BYTE OF A TRANSPARENT PIXEL
        // (CVE-2018-13785's SHAPE); GAMA REFUSES THE OUT-OF-RANGE VALUE AND EVERY CORRECTED CHANNEL IS CLAMPED
        write(dir, "gama-alpha-kill.png", ImageBytes.png(
                ihdr(1, 1, 8, 6),
                pngChunk("gAMA", new ImageBytes().be32(0x80000000).bytes()),
                pngChunk("IDAT", deflate(new byte[] { 0, 0, 0, 0, 0 }, 9))));

        // acTL DECLARES 2 FRAMES WHILE THE STREAM CARRIES 500, EACH A FULL-CANVAS COPY IN readAll(); scan() KEEPS THE
        // SMALLER OF acTL AND THE fcTL COUNT AND THE DECODER REFUSES THE FIRST SURPLUS FRAME
        final ImageBytes apng = new ImageBytes()
                .raw(ImageBytes.PNG_SIGNATURE)
                .raw(ihdr(2_048, 2_048, 1, 0))
                .raw(pngChunk("acTL", new ImageBytes().be32(2).be32(0).bytes()))
                .raw(pngChunk("IDAT", deflate(new byte[2_048 * 257], 9)));
        for (int i = 0; i < 500; i++) {
            apng.raw(pngChunk("fcTL", fctl(i * 2)));
            apng.raw(pngChunk("fdAT", new ImageBytes().be32(i * 2 + 1).raw(deflate(new byte[] { 0, 0 }, 9)).bytes()));
        }
        write(dir, "apng-framecount-underdeclared.png", apng.raw(pngChunk("IEND", new byte[0])).bytes());
    }

    // A 1x1 FRAME AT THE ORIGIN WITH A 1/100 s DELAY
    private static byte[] fctl(final int seq) {
        return new ImageBytes().be32(seq).be32(1).be32(1).be32(0).be32(0)
                .be16(1).be16(100).u8(0).u8(0).bytes();
    }

    // 1x1 GREYSCALE-8 IMAGE DATA: ONE FILTER BYTE PLUS ONE SAMPLE BYTE
    private static byte[] idatGray1x1() {
        return pngChunk("IDAT", deflate(new byte[] { 0, 0 }, 9));
    }

    // ==========================================================================
    // JPEG
    // ==========================================================================
    private static void jpeg(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // A 23-BYTE 16384x16384 THREE-COMPONENT SOF WITH NO TABLES OR DATA, 3 GiB OF COEFFICIENT PLANES;
        // Limits.fits REFUSES THE FRAME BEFORE ANY PLANE IS ALLOCATED
        write(dir, "sof-dimension-bomb.jpg", new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(segment(0xC0, new ImageBytes().u8(8).be16(16_384).be16(16_384).u8(3)
                        .raw(1, 0x11, 0).raw(2, 0x11, 0).raw(3, 0x11, 0).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // 500 AC REFINEMENT SCANS WITH NO FIRST PASS: ONE 15-BIT EOB SYMBOL BUYS 16384 BLOCKS OF 63 ITERATIONS, AND 4096x4096
        // IS 16 x 16384 BLOCKS, SO 30 ZERO BYTES PER SCAN; A REFINEMENT WITHOUT ITS FIRST PASS IS REFUSED AND MAX_SCANS BOUNDS IT
        final ImageBytes eob = new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new ImageBytes().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC4, new ImageBytes().u8(0x00).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC4, new ImageBytes().u8(0x10).u8(1).fill(0x00, 15).u8(0xE0).bytes()))
                .raw(segment(0xC2, new ImageBytes().u8(8).be16(4_096).be16(4_096).u8(1).raw(1, 0x11, 0).bytes()));
        for (int i = 0; i < 500; i++) {
            // Ss=1, Se=63, Ah=1, Al=0 -> A REFINEMENT SCAN THAT NO FIRST-PASS SCAN EVER PRECEDED
            eob.raw(segment(0xDA, new ImageBytes().u8(1).raw(1, 0x00).u8(1).u8(63).u8(0x10).bytes()));
            eob.fill(0x00, 30);
        }
        write(dir, "progressive-eobrun-blowup.jpg", eob.raw(0xFF, 0xD9).bytes());

        // 200 SOF SEGMENTS, EACH RE-ALLOCATING AND ZEROING 48 MiB OF COEFFICIENTS WHILE PEAK MEMORY STAYS FLAT; A SECOND
        // FRAME HEADER IS REFUSED ON SIGHT, AS libjpeg ANSWERS IT WITH JERR_SOF_DUPLICATE
        final ImageBytes sofs = new ImageBytes().raw(0xFF, 0xD8);
        for (int i = 0; i < 200; i++) {
            sofs.raw(segment(0xC0, new ImageBytes().u8(8).be16(2_048).be16(2_048).u8(3)
                    .raw(1, 0x11, 0).raw(2, 0x11, 0).raw(3, 0x11, 0).bytes()));
        }
        write(dir, "repeated-sof-churn.jpg", sofs.raw(0xFF, 0xD9).bytes());

        // AN SOF DECLARING Lf=17 AND CARRYING 3 BYTES, THE COMMONEST TRUNCATION SHAPE; THE SEGMENT READER REFUSES IT AS
        // CodecException, SO A RETRY-DRIVEN PIPELINE NEVER MISTAKES IT FOR A STREAM FAILURE
        write(dir, "truncated-segment.jpg", new ImageBytes()
                .raw(0xFF, 0xD8, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x01).bytes());

        // A DHT WITH Lh=3 WHOSE 16 COUNT BYTES WOULD SWALLOW THE EOI MARKER (CVE-2013-6629's get_dht); TABLE DATA IS READ
        // FROM A SLICE LIMITED TO THE DECLARED SEGMENT, SO THE OVERREAD IS A TRUNCATION
        write(dir, "dht-segment-overread.jpg", new ImageBytes()
                .raw(0xFF, 0xD8, 0xFF, 0xC4, 0x00, 0x03, 0x00, 0xFF, 0xD9).bytes());

        // AN SOF DECLARING Lf=8 FOR THREE COMPONENTS, WHOSE GEOMETRY WOULD COME FROM BYTES PAST THE SEGMENT; THE FRAME
        // PARSER REFUSES A LENGTH THAT CONTRADICTS Nf
        write(dir, "sof-length-mismatch.jpg", new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(0xFF, 0xC0, 0x00, 0x08, 0x08, 0x00, 0x10, 0x00, 0x10, 0x03)
                .raw(0x01, 0x44, 0x00)
                .fill(0x00, 16)
                .raw(0xFF, 0xD9)
                .bytes());

        // A COMPLETE BASELINE HEADER SET FOLLOWED BY NO ENTROPY BYTES; THE HUFFMAN DECODER MEETS THE EOI AT ONCE AND
        // REFUSES THE TRUNCATED SCAN AS CodecException
        write(dir, "truncated-entropy.jpg", new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new ImageBytes().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC4, new ImageBytes().u8(0x00).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC4, new ImageBytes().u8(0x10).u8(1).fill(0x00, 15).u8(0x00).bytes()))
                .raw(segment(0xC0, new ImageBytes().u8(8).be16(16).be16(16).u8(1).raw(1, 0x11, 0).bytes()))
                .raw(segment(0xDA, new ImageBytes().u8(1).raw(1, 0x00).u8(0).u8(63).u8(0x00).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // CHROMA SAMPLED TALLER THAN LUMA (CVE-2018-14498 / CVE-2017-15232 SHAPE); COLOUR CONVERSION RESAMPLES EVERY
        // COMPONENT ONTO THE MAX SAMPLING GRID WITH CLAMPED INDEXES, SO NO PLANE IS READ OUT OF BOUNDS
        write(dir, "chroma-taller-than-luma.jpg", new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new ImageBytes().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC0, new ImageBytes().u8(8).be16(64).be16(64).u8(3)
                        .raw(1, 0x11, 0).raw(2, 0x12, 1).raw(3, 0x12, 1).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());

        // AN SOF1 (EXTENDED SEQUENTIAL HUFFMAN) FRAME, BIT-IDENTICAL TO BASELINE AT 8 BITS, WITH NO DHT; THE READER TAKES IT
        // AS ITS FRAME HEADER, SO THE SCAN IS REFUSED FOR ITS MISSING HUFFMAN TABLE, NEVER FOR A MISSING FRAME HEADER
        write(dir, "sof1-silently-skipped.jpg", new ImageBytes()
                .raw(0xFF, 0xD8)
                .raw(segment(0xDB, new ImageBytes().u8(0).fill(0x01, 64).bytes()))
                .raw(segment(0xC1, new ImageBytes().u8(8).be16(16).be16(16).u8(1).raw(1, 0x11, 0).bytes()))
                .raw(segment(0xDA, new ImageBytes().u8(1).raw(1, 0x00).u8(0).u8(63).u8(0x00).bytes()))
                .raw(0xFF, 0xD9)
                .bytes());
    }

    // JPEG SEGMENT: MARKER, THEN A BIG-ENDIAN LENGTH THAT COUNTS ITSELF, THEN THE PAYLOAD
    private static byte[] segment(final int marker, final byte[] payload) {
        return new ImageBytes().raw(0xFF, marker).be16(payload.length + 2).raw(payload).bytes();
    }

    // ==========================================================================
    // GIF
    // ==========================================================================
    private static void gif(final Path dir) throws IOException {
        Files.createDirectories(dir);

        // 13 BYTES DECLARING A 16384x16384 LOGICAL SCREEN, 2 GiB OF CANVAS THE FILE NEVER CARRIES (giflib CVE-2019-15133
        // CLASS); Limits.fits REFUSES THE PRODUCT BEFORE THE CANVAS EXISTS
        write(dir, "canvas-allocation-bomb.gif", new ImageBytes()
                .ascii("GIF89a")
                .le16(16_384).le16(16_384)
                .u8(0x00)   // PACKED: NO GLOBAL COLOR TABLE
                .u8(0x00)   // BACKGROUND COLOR INDEX
                .u8(0x00)   // PIXEL ASPECT RATIO
                .bytes());

        // A 4096x4096 SCREEN WITH 64 TWELVE-BYTE FRAMES, EACH A 64 MiB CANVAS COPY IN readAll(), SO 788 BYTES REQUEST
        // 4 GiB (THE libgd / ImageMagick FRAME-COUNT DoS); ImageReader.MAX_DECODED_BYTES REFUSES THE NINTH FRAME
        final ImageBytes frames = new ImageBytes()
                .ascii("GIF89a")
                .le16(4_096).le16(4_096)
                .u8(0x80)   // PACKED: GLOBAL COLOR TABLE, SIZE BITS 0 -> 2 ENTRIES
                .u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF); // GCT: BLACK, WHITE
        for (int i = 0; i < 64; i++) frames.raw(minimalGifFrame());
        write(dir, "frame-count-bomb.gif", frames.u8(0x3B).bytes());

        // A 2-ENTRY GLOBAL COLOUR TABLE CUT AFTER 3 OF ITS 6 BYTES; THE READER REPORTS THE TRUNCATION AS CodecException,
        // NOT AS A RAW EOFException INDISTINGUISHABLE FROM A SOCKET FAILURE
        write(dir, "truncated-color-table.gif", new ImageBytes()
                .ascii("GIF89a")
                .le16(1).le16(1)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00)
                .bytes());

        // A 5-BYTE LZW SUB-BLOCK WITH NO TERMINATOR, THE COMMONEST MALFORMED GIF IN THE WILD; THE SUB-BLOCK READER
        // REFUSES THE MISSING TERMINATOR AS CodecException
        write(dir, "unterminated-subblock.gif", new ImageBytes()
                .ascii("GIF89a")
                .le16(1).le16(1)
                .u8(0x80).u8(0x00).u8(0x00)                 // GCT SO THE FRAME REACHES THE LZW READER
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02)   // LZW MINIMUM CODE SIZE
                .u8(0x05).raw(0xAA, 0xAA, 0xAA, 0xAA, 0xAA) // 5-BYTE SUB-BLOCK, NO TERMINATOR
                .bytes());

        // FRAME 1 SEEDS DICTIONARY ENTRIES 6..8 AND FRAME 2 OPENS WITH CODE 6 (giflib CVE-2018-11490); EVERY FRAME STARTS A
        // FRESH DICTIONARY, SO CODE 6 IS REFUSED, AND FRAME 2 DECODING TO GREEN INSTEAD OF BLACK WOULD PROVE A STALE READ
        write(dir, "lzw-stale-dictionary.gif", new ImageBytes()
                .ascii("GIF89a")
                .le16(2).le16(2)
                .u8(0x81).u8(0x00).u8(0x00) // PACKED: GCT, SIZE BITS 1 -> 4 ENTRIES
                .raw(0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0xFF)
                // FRAME 1: CODES CLEAR(4)@3b, 1@3b, 2@3b, 3@3b, 1@4b -> 0x168C LSB-FIRST
                .u8(0x2C).le16(0).le16(0).le16(2).le16(2).u8(0x00)
                .u8(0x02).u8(0x02).raw(0x8C, 0x16).u8(0x00)
                // FRAME 2: CODES 6@3b, EOI(5)@3b -> 0x2E. CODE 6 == available WITH oldCode == -1
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02).u8(0x01).raw(0x2E).u8(0x00)
                .u8(0x3B)
                .bytes());

        // A GRAPHIC CONTROL EXTENSION DECLARING BLOCK SIZE 5 WHERE THE SPEC MANDATES 4; scan() AND THE DECODER BOTH PARSE
        // IT WITH GraphicExtension.read, WHICH REFUSES IT, SO THE TWO WALKS CANNOT DISAGREE ON THE FRAME COUNT
        final ImageBytes gce = new ImageBytes()
                .ascii("GIF89a")
                .le16(64).le16(64)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .raw(0x21, 0xF9, 0x05, 0x00, 0x0A, 0x00, 0x00, 0x00); // BLOCK SIZE 5, MUST BE 4
        for (int i = 0; i < 4; i++) gce.raw(minimalGifFrame());
        write(dir, "gce-blocksize-divergence.gif", gce.u8(0x3B).bytes());

        // A FOREVER LOOP WITH 5000 EMPTY APPLICATION EXTENSIONS PAST THE FIRST FRAME, AIMED AT A READER THAT RE-PARSES
        // METADATA ON EVERY REPLAY; METADATA IS READ ONCE AT CONSTRUCTION, CAPPED, AND NEVER RE-READ BY reset()
        final ImageBytes meta = new ImageBytes()
                .ascii("GIF89a")
                .le16(64).le16(64)
                .u8(0x80).u8(0x00).u8(0x00)
                .raw(0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF)
                .raw(0x21, 0xFF, 0x0B).ascii("NETSCAPE2.0").raw(0x03, 0x01, 0x00, 0x00, 0x00)
                .raw(minimalGifFrame());
        for (int i = 0; i < 5_000; i++) meta.raw(0x21, 0xFF, 0x00, 0x00); // EMPTY APP EXTENSION
        for (int i = 0; i < 256; i++) meta.raw(minimalGifFrame());
        write(dir, "metadata-accumulation.gif", meta.u8(0x3B).bytes());
    }

    // SMALLEST LEGAL FRAME: A 1x1 IMAGE DESCRIPTOR WITH AN EMPTY LZW SUB-BLOCK CHAIN. TWELVE
    // INPUT BYTES THAT STILL COST A FULL CANVAS COPY IN readAll()
    private static byte[] minimalGifFrame() {
        return new ImageBytes()
                .u8(0x2C).le16(0).le16(0).le16(1).le16(1).u8(0x00)
                .u8(0x02)   // LZW MINIMUM CODE SIZE
                .u8(0x00)   // SUB-BLOCK TERMINATOR: ZERO LZW BYTES
                .bytes();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static void write(final Path dir, final String name, final byte[] data) throws IOException {
        Files.write(dir.resolve(name), data);
        System.out.printf("  %-40s %,10d bytes%n", dir.getFileName() + "/" + name, data.length);
    }

    private static byte[] ascii(final String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
