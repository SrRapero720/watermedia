package org.watermedia.api.codecs.readers.webp.lossy;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.MathUtil;
import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.readers.webp.lossy.VP8Tables.*;

/** RFC 6386 VP8 key-frame decoder of lossy WebP, into packed ARGB or a BGRA buffer. */
public final class VP8LossyDecoder {
    static final Marker IT = MarkerManager.getMarker(VP8LossyDecoder.class.getSimpleName());
    private static final byte[] TOP_BORDER_16 = fillBorder(16, 127);
    private static final byte[] LEFT_BORDER_16 = fillBorder(16, 129);
    private static final byte[] TOP_BORDER_8 = fillBorder(8, 127);
    private static final byte[] LEFT_BORDER_8 = fillBorder(8, 129);
    private static final byte[] LEFT_BORDER_4 = fillBorder(4, 129);

    private VP8LossyDecoder() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Converts an opaque VP8 key frame into a new direct BGRA buffer; {@code warned} latches the repair notice. */
    public static ByteBuffer decodeToBgra(final ByteBuffer data, final int expW, final int expH, final boolean[] warned) throws CodecException {
        return DataTool.bgraToBuffer(decode(data, expW, expH, warned));
    }

    /** Decodes an opaque VP8 key frame of the declared size into packed ARGB pixels; {@code warned} latches the repair notice. */
    public static int[] decode(final ByteBuffer data, final int expW, final int expH, final boolean[] warned) throws CodecException {
        final ByteBuffer buf = data.slice().order(ByteOrder.LITTLE_ENDIAN);

        // RFC6386 SECTION 9.1: PARSE UNCOMPRESSED FRAME HEADER
        final FrameInfo frm = parseFrameHeader(buf);
        if (!frm.keyFrame) throw new UnsupportedFormatException("VP8 interframes are not supported, only key frames");
        if (frm.w <= 0 || frm.h <= 0)
            throw new CodecException("Invalid VP8 frame size: " + frm.w + "x" + frm.h);
        // THE CONTAINER (SIMPLE HEADER, VP8X CANVAS OR ANMF FRAME) AND THE BITSTREAM MUST AGREE:
        // CALLERS SIZE THEIR OUTPUT BUFFERS FROM THE CONTAINER, SO A MISMATCH WOULD OVERRUN THEM
        if (frm.w != expW || frm.h != expH)
            throw new CodecException("VP8 frame is " + frm.w + "x" + frm.h + ", container declares " + expW + "x" + expH);

        // PARTITION 0 IS THE COMPRESSED HEADER. p0Sz COMES FROM THE FRAME TAG AND CAN OVERRUN THE CHUNK
        if (frm.hdrSz + frm.p0Sz > buf.limit()) {
            if (!WaterMediaConfig.codecs.vp8.brokenTokens)
                throw new CodecException("Broken VP8 partition 0: declared " + frm.p0Sz + " bytes, chunk has " + (buf.limit() - frm.hdrSz));
            repaired(warned, "Broken VP8 partition 0: declared {} bytes, only {} available; clamping", frm.p0Sz, buf.limit() - frm.hdrSz);
            frm.p0Sz = buf.limit() - frm.hdrSz;
        }
        final ByteBuffer p0 = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        p0.position(frm.hdrSz);
        p0.limit(frm.hdrSz + frm.p0Sz);
        final VP8BoolDecoder hdrBr = new VP8BoolDecoder(p0.slice().order(ByteOrder.LITTLE_ENDIAN));

        // RFC6386 SECTION 9.2-9.11: PARSE COMPRESSED HEADER
        final State st = parseCompressedHeader(hdrBr, frm);
        // THE COMPRESSED HEADER IS FIXED-SHAPE (OVER A THOUSAND BOOLS), SO A PARTITION 0 THAT RAN
        // OUT WHILE PARSING IT MEANS EVERY FIELD BELOW WAS INVENTED. FAIL HERE, BEFORE THE
        // FRAME-SIZED PLANES ARE ALLOCATED FROM THOSE FIELDS
        if (hdrBr.eof())
            throw new CodecException("VP8 bitstream truncated: bool decoder ran past partition 0 end");

        // RFC6386 SECTION 9.5: TOKEN PARTITION SIZES AND DECODERS
        buf.position(frm.hdrSz + frm.p0Sz);
        final VP8BoolDecoder[] tokBr = readTokenParts(buf, st.numParts, warned);

        // ALLOCATE YUV PLANES
        final int mbW = (frm.w + 15) >> 4;
        final int mbH = (frm.h + 15) >> 4;
        final int yStr = mbW * 16;
        final int uvStr = mbW * 8;

        final byte[] yPln = new byte[yStr * mbH * 16];
        final byte[] uPln = new byte[uvStr * mbH * 8];
        final byte[] vPln = new byte[uvStr * mbH * 8];
        Arrays.fill(yPln, (byte) 128);
        Arrays.fill(uPln, (byte) 128);
        Arrays.fill(vPln, (byte) 128);

        // ALLOCATE PER-MB INFO FOR LOOP FILTER
        st.mbSeg = new int[mbW * mbH];
        st.mbIsI4x4 = new int[mbW * mbH];
        st.mbFInner = new int[mbW * mbH];

        // RFC6386 SECTIONS 11-14: DECODE ALL MACROBLOCKS
        decodeMBs(hdrBr, tokBr, st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH);

        LOGGER.debug(IT, "Frame: {}x{} = {}x{} MBs, {} parts, loopLvl={}, skipProb={}", frm.w, frm.h, mbW, mbH, st.numParts, st.loopLvl, st.skipProb);

        // RFC6386 SECTION 15: APPLY LOOP FILTER
        applyLoopFilter(st, yPln, uPln, vPln, yStr, uvStr, mbW, mbH, frm.keyFrame);

        // VP8 IS BT.601 LIMITED RANGE; IT IS CONVERTED HERE BECAUSE PICTURE DECODERS HAND OUT BGRA OR GRAY,
        // NEVER PLANAR YUV, SO EVERY ENGINE SHOWS THE SAME COLORS
        return DataTool.yuvToBgra(yPln, uPln, vPln, frm.w, frm.h, yStr, uvStr);
    }

    // ==========================================================================
    // RFC6386 SECTION 9: FRAME HEADER AND PARTITIONS
    // ==========================================================================
    // RFC6386 SECTION 9.5: ONE BOOL DECODER PER TOKEN PARTITION. FILE SIZES CAN BE BROKEN (OVERLONG, TRUNCATED TABLE,
    // EMPTY PARTITION): STRICT MODE FAILS, codecs.vp8.brokenTokens CLAMPS THEM AND STANDS IN THE PREVIOUS PARTITION OR
    // ZEROED DATA FOR AN UNUSABLE ONE, SO THE MACROBLOCK LOOP STILL RUNS BEST-EFFORT
    private static VP8BoolDecoder[] readTokenParts(final ByteBuffer buf, final int numParts, final boolean[] warned) throws CodecException {
        final boolean repair = WaterMediaConfig.codecs.vp8.brokenTokens;

        // SIZE TABLE: 3 BYTES PER PARTITION EXCEPT THE LAST ONE (IMPLICIT REMAINDER)
        final int tableLen = (numParts - 1) * 3;
        if (buf.remaining() < tableLen) {
            if (!repair)
                throw new CodecException("Truncated VP8 partition size table: " + buf.remaining() + " of " + tableLen + " bytes");
            repaired(warned, "Truncated VP8 partition size table ({} of {} bytes); missing sizes assumed 0", buf.remaining(), tableLen);
        }
        final int[] partSz = new int[numParts];
        for (int i = 0; i < numParts - 1; i++)
            partSz[i] = buf.remaining() >= 3
                    ? (buf.get() & 0xFF) | ((buf.get() & 0xFF) << 8) | ((buf.get() & 0xFF) << 16)
                    : 0;

        // VALIDATE DECLARED SIZES AGAINST THE BYTES LEFT; THE LAST PARTITION TAKES THE REMAINDER
        int avail = buf.remaining();
        for (int i = 0; i < numParts - 1; i++) {
            if (partSz[i] > avail) {
                if (!repair)
                    throw new CodecException("Broken VP8 token partition " + i + ": declared " + partSz[i] + " bytes, only " + avail + " available");
                repaired(warned, "Broken VP8 token partition {}: declared {} bytes, only {} available; clamping", i, partSz[i], avail);
                partSz[i] = avail;
            }
            avail -= partSz[i];
        }
        partSz[numParts - 1] = avail;

        final VP8BoolDecoder[] tokBr = new VP8BoolDecoder[numParts];
        int pos = buf.position();
        ByteBuffer prev = null;
        for (int i = 0; i < numParts; i++) {
            ByteBuffer pb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            pb.position(pos);
            pb.limit(pos + partSz[i]);
            pos += partSz[i];
            // THE BOOL DECODER NEEDS AT LEAST 2 BYTES; REPAIR BY DUPLICATING THE PREVIOUS
            // PARTITION BYTES OR, WHEN THERE IS NO PREVIOUS ONE, BY FAKING ZEROED DATA
            if (partSz[i] < 2) {
                if (!repair)
                    throw new CodecException("Broken VP8 token partition " + i + ": only " + partSz[i] + " bytes");
                repaired(warned, "Empty VP8 token partition {}; {}", i, prev != null ? "duplicating previous partition" : "faking zeroed data");
                pb = prev != null ? prev.duplicate() : ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
            }
            prev = pb;
            tokBr[i] = new VP8BoolDecoder(pb.slice().order(ByteOrder.LITTLE_ENDIAN));
        }
        return tokBr;
    }

    // ONE NOTICE PER READER: AN ANIMATED WEBP WITH BROKEN PARTITIONS WOULD OTHERWISE WARN ON EVERY FRAME
    private static void repaired(final boolean[] warned, final String pattern, final Object... args) {
        if (warned[0]) return;
        warned[0] = true;
        LOGGER.warn(IT, pattern, args);
    }

    // RFC6386 SECTION 9.1: FRAME HEADER, UNCOMPRESSED DATA CHUNK
    private static FrameInfo parseFrameHeader(final ByteBuffer buf) throws CodecException {
        if (buf.remaining() < 10) throw new CodecException("VP8 frame too small");

        final FrameInfo f = new FrameInfo();
        final int b0 = buf.get() & 0xFF;
        final int b1 = buf.get() & 0xFF;
        final int b2 = buf.get() & 0xFF;

        f.keyFrame = (b0 & 1) == 0;
        f.p0Sz = ((b0 >> 5) & 7) | (b1 << 3) | (b2 << 11);

        if (f.keyFrame) {
            // KEY FRAME START CODE: 0x9D 0x01 0x2A
            if ((buf.get() & 0xFF) != 0x9D || (buf.get() & 0xFF) != 0x01 || (buf.get() & 0xFF) != 0x2A)
                throw new CodecException("Invalid VP8 start code");
            f.w = buf.getShort() & 0x3FFF;
            f.h = buf.getShort() & 0x3FFF;
            f.hdrSz = 10;
        } else {
            f.hdrSz = 3;
        }
        return f;
    }

    // RFC6386 SECTIONS 9.2-9.11: COMPRESSED HEADER
    private static State parseCompressedHeader(final VP8BoolDecoder br, final FrameInfo frm) {
        final State st = new State();

        // RFC6386 SECTION 9.2: COLOR SPACE AND PIXEL TYPE (KEY FRAMES)
        if (frm.keyFrame) {
            br.readBool();
            br.readBool();
        }

        // RFC6386 SECTION 9.3: SEGMENT-BASED ADJUSTMENTS
        st.segEnabled = br.readBool();
        if (st.segEnabled) {
            st.segUpdMap = br.readBool();
            if (br.readBool()) {
                st.segAbsMode = br.readBool();
                for (int i = 0; i < 4; i++) if (br.readBool()) st.segQuant[i] = br.readSigned(7);
                for (int i = 0; i < 4; i++) if (br.readBool()) st.segLoop[i] = br.readSigned(6);
            }
            if (st.segUpdMap)
                for (int i = 0; i < 3; i++) if (br.readBool()) st.segProbs[i] = br.readLit(8);
        }

        // RFC6386 SECTION 9.4: LOOP FILTER TYPE AND LEVELS
        st.filterSimple = br.readBool();
        st.loopLvl = br.readLit(6);
        st.sharpLvl = br.readLit(3);
        st.useLfDelta = br.readBool();
        if (st.useLfDelta && br.readBool()) {
            for (int i = 0; i < 4; i++) if (br.readBool()) st.refLfDelta[i] = br.readSigned(6);
            for (int i = 0; i < 4; i++) if (br.readBool()) st.modeLfDelta[i] = br.readSigned(6);
        }

        // RFC6386 SECTION 9.5: TOKEN PARTITION COUNT
        st.numParts = 1 << br.readLit(2);

        // RFC6386 SECTION 9.6: DEQUANTIZATION INDICES
        final int yacIdx = br.readLit(7);
        final int ydcD = br.readBool() ? br.readSigned(4) : 0;
        final int y2dcD = br.readBool() ? br.readSigned(4) : 0;
        final int y2acD = br.readBool() ? br.readSigned(4) : 0;
        final int uvdcD = br.readBool() ? br.readSigned(4) : 0;
        final int uvacD = br.readBool() ? br.readSigned(4) : 0;

        // CALCULATE QUANTIZERS FOR EACH SEGMENT
        for (int seg = 0; seg < 4; seg++) {
            int base = yacIdx;
            if (st.segEnabled && st.segAbsMode) base = st.segQuant[seg];
            else if (st.segEnabled) base += st.segQuant[seg];

            st.segQ[seg][0] = DC_QUANT[MathUtil.clamp(base + ydcD, 0, 127)];
            st.segQ[seg][1] = AC_QUANT[MathUtil.clamp(base, 0, 127)];
            st.segQ[seg][2] = DC_QUANT[MathUtil.clamp(base + y2dcD, 0, 127)] * 2;
            st.segQ[seg][3] = Math.max(8, AC_QUANT[MathUtil.clamp(base + y2acD, 0, 127)] * 155 / 100);
            st.segQ[seg][4] = DC_QUANT[MathUtil.clamp(base + uvdcD, 0, 117)];
            st.segQ[seg][5] = AC_QUANT[MathUtil.clamp(base + uvacD, 0, 127)];
        }

        // RFC6386 SECTION 13.5: INITIALIZE COEFFICIENT PROBABILITIES
        st.coeffProbs = new int[4][8][3][11];
        for (int t = 0; t < 4; t++)
            for (int b = 0; b < 8; b++)
                for (int c = 0; c < 3; c++)
                    System.arraycopy(DEFAULT_COEFF_PROBS[t][b][c], 0, st.coeffProbs[t][b][c], 0, 11);

        // RFC6386 SECTION 9.11: KEY FRAME REFRESH_ENTROPY_PROBS
        if (frm.keyFrame) br.readBool();

        // RFC6386 SECTION 9.9/13.4: DCT COEFFICIENT PROBABILITY UPDATE
        for (int t = 0; t < 4; t++)
            for (int b = 0; b < 8; b++)
                for (int c = 0; c < 3; c++)
                    for (int p = 0; p < 11; p++)
                        if (br.readBool(COEFF_UPDATE_PROBS[t][b][c][p]))
                            st.coeffProbs[t][b][c][p] = br.readLit(8);

        // RFC6386 SECTION 11.1: MB_SKIP_COEFF
        st.useSkip = br.readBool();
        if (st.useSkip) st.skipProb = br.readLit(8);

        return st;
    }

    // ==========================================================================
    // RFC6386 SECTIONS 11-14: MACROBLOCK DECODING
    // ==========================================================================
    private static void decodeMBs(final VP8BoolDecoder hdrBr, final VP8BoolDecoder[] tokBr, final State st,
                                  final byte[] yPln, final byte[] uPln, final byte[] vPln,
                                  final int yStr, final int uvStr, final int mbW, final int mbH) throws CodecException {
        // NON-ZERO COEFFICIENT TRACKING FOR CONTEXT
        final int[] topNzY = new int[mbW * 4];
        final int[] topNzU = new int[mbW * 2];
        final int[] topNzV = new int[mbW * 2];
        final int[] topNzDc = new int[mbW];
        final short[] coeffs = new short[16];
        final short[] y2dcScratch = new short[16];
        final int[] whtTmp = new int[16];
        final int[] dctTmp = new int[16];
        final byte[] aboveY = new byte[16];
        final byte[] leftY = new byte[16];
        final byte[] aboveU = new byte[8];
        final byte[] leftU = new byte[8];
        final byte[] aboveV = new byte[8];
        final byte[] leftV = new byte[8];
        final byte[] subAbove = new byte[8];
        final byte[] subLeft = new byte[4];

        // RFC6386 SECTION 11.3: SUBBLOCK MODE CONTEXT
        final SubMode[] topSubModes = new SubMode[mbW * 4];
        final SubMode[] leftSubModes = new SubMode[4];
        Arrays.fill(topSubModes, SubMode.B_DC);
        // SCRATCH FOR THE CURRENT I4X4 MB'S 16 SUBMODES; FULLY REWRITTEN PER I4X4 MB, UNUSED OTHERWISE
        final SubMode[] subModes = new SubMode[16];

        for (int mbY = 0; mbY < mbH; mbY++) {
            final VP8BoolDecoder tbr = tokBr[mbY % st.numParts];
            int leftNzY = 0, leftNzU = 0, leftNzV = 0, leftNzDc = 0;
            Arrays.fill(leftSubModes, SubMode.B_DC);

            for (int mbX = 0; mbX < mbW; mbX++) {
                // RFC6386 SECTION 10: SEGMENT ID
                int seg = 0;
                if (st.segEnabled && st.segUpdMap) {
                    if (!hdrBr.readBool(st.segProbs[0])) seg = hdrBr.readBool(st.segProbs[1]) ? 1 : 0;
                    else seg = hdrBr.readBool(st.segProbs[2]) ? 3 : 2;
                }

                final int y1Dc = st.segQ[seg][0];
                final int y1Ac = st.segQ[seg][1];
                final int y2Dc = st.segQ[seg][2];
                final int y2Ac = st.segQ[seg][3];
                final int uvDc = st.segQ[seg][4];
                final int uvAc = st.segQ[seg][5];

                // RFC6386 SECTION 11.1-11.2: SKIP AND LUMA MODE
                final boolean skip = st.useSkip && hdrBr.readBool(st.skipProb);
                final boolean isI4x4 = !hdrBr.readBool(145);

                final MBMode yMode;
                if (isI4x4) {
                    // RFC6386 SECTION 11.2-11.3: I4X4 SUBBLOCK MODES
                    yMode = null;
                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final SubMode above = (j > 0) ? subModes[(j - 1) * 4 + i] : topSubModes[mbX * 4 + i];
                            final SubMode left = (i > 0) ? subModes[j * 4 + i - 1] : leftSubModes[j];
                            subModes[j * 4 + i] = readSubMode(hdrBr, above, left);
                        }

                    // UPDATE SUBBLOCK MODE CONTEXT
                    System.arraycopy(subModes, 12, topSubModes, mbX * 4, 4);
                    for (int j = 0; j < 4; j++) leftSubModes[j] = subModes[j * 4 + 3];
                } else {
                    // RFC6386 SECTION 11.2: 16X16 LUMA MODE TREE
                    final int modeIdx;
                    if (!hdrBr.readBool(156)) modeIdx = hdrBr.readBool(163) ? 1 : 0;
                    else modeIdx = hdrBr.readBool(128) ? 3 : 2;
                    yMode = MBMode.VALUES[modeIdx];

                    // UPDATE SUBBLOCK MODE CONTEXT
                    final SubMode derived = SubMode.fromMBMode(yMode);
                    for (int i = 0; i < 4; i++) topSubModes[mbX * 4 + i] = derived;
                    Arrays.fill(leftSubModes, derived);
                }

                // RFC6386 SECTION 11.4: CHROMA MODE
                final int uvModeIdx;
                if (!hdrBr.readBool(142)) uvModeIdx = 0;
                else if (!hdrBr.readBool(114)) uvModeIdx = 1;
                else uvModeIdx = hdrBr.readBool(183) ? 3 : 2;
                final MBMode uvMode = MBMode.VALUES[uvModeIdx];

                // STORE PER-MB INFO FOR LOOP FILTER (mbFInner IS SET AFTER RESIDUAL DECODING)
                final int mbIdx = mbY * mbW + mbX;
                st.mbSeg[mbIdx] = seg;
                st.mbIsI4x4[mbIdx] = isI4x4 ? 1 : 0;

                final int yOff = mbY * 16 * yStr + mbX * 16;
                final int uvOff = mbY * 8 * uvStr + mbX * 8;

                // GET PREDICTION CONTEXT PIXELS
                // LIBWEBP BORDER VALUES: 127 FOR TOP (mbY=0), 129 FOR LEFT (mbX=0)
                final boolean hasAbove = mbY > 0, hasLeft = mbX > 0;
                if (hasAbove) getAbove(yPln, yOff, yStr, 16, aboveY);
                else System.arraycopy(TOP_BORDER_16, 0, aboveY, 0, 16);
                if (hasLeft) getLeft(yPln, yOff, yStr, 16, leftY);
                else System.arraycopy(LEFT_BORDER_16, 0, leftY, 0, 16);
                final int tlY = hasAbove ? (hasLeft ? (yPln[yOff - yStr - 1] & 0xFF) : 129) : 127;

                if (hasAbove) getAbove(uPln, uvOff, uvStr, 8, aboveU);
                else System.arraycopy(TOP_BORDER_8, 0, aboveU, 0, 8);
                if (hasLeft) getLeft(uPln, uvOff, uvStr, 8, leftU);
                else System.arraycopy(LEFT_BORDER_8, 0, leftU, 0, 8);
                final int tlU = hasAbove ? (hasLeft ? (uPln[uvOff - uvStr - 1] & 0xFF) : 129) : 127;

                if (hasAbove) getAbove(vPln, uvOff, uvStr, 8, aboveV);
                else System.arraycopy(TOP_BORDER_8, 0, aboveV, 0, 8);
                if (hasLeft) getLeft(vPln, uvOff, uvStr, 8, leftV);
                else System.arraycopy(LEFT_BORDER_8, 0, leftV, 0, 8);
                final int tlV = hasAbove ? (hasLeft ? (vPln[uvOff - uvStr - 1] & 0xFF) : 129) : 127;

                // RFC6386 SECTION 12: INTRAFRAME PREDICTION
                if (!isI4x4) {
                    predictBlock(yMode, yPln, yOff, yStr, 16, aboveY, leftY, tlY, hasAbove, hasLeft);
                } else if (skip) {
                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final int sOff = yOff + j * 4 * yStr + i * 4;
                            final boolean rightEdge = (i == 3) && (mbX == mbW - 1);
                            if (mbY > 0 || j > 0) getAbove8Sub(yPln, sOff, yStr, rightEdge, mbY, i, yOff, subAbove);
                            else System.arraycopy(TOP_BORDER_8, 0, subAbove, 0, 8);
                            if (mbX > 0 || i > 0) getLeft(yPln, sOff, yStr, 4, subLeft);
                            else System.arraycopy(LEFT_BORDER_4, 0, subLeft, 0, 4);
                            final int sTl = subTl(mbX, mbY, i, j, yPln, sOff, yStr);
                            predict4(subModes[j * 4 + i], yPln, sOff, yStr, subAbove, subLeft, sTl);
                        }
                }

                predictBlock(uvMode, uPln, uvOff, uvStr, 8, aboveU, leftU, tlU, hasAbove, hasLeft);
                predictBlock(uvMode, vPln, uvOff, uvStr, 8, aboveV, leftV, tlV, hasAbove, hasLeft);

                // RFC6386 SECTION 13: DCT COEFFICIENT DECODING
                // TRACK WHETHER ANY COEFFICIENT DECODED NON-ZERO; LIBWEBP DERIVES THE LOOP-FILTER
                // INNER-EDGE FLAG FROM ACTUAL NZ, NOT THE SKIP FLAG (f_inner |= !effectiveSkip)
                boolean nzAny = false;
                if (!skip) {
                    boolean hasY2dc = false;

                    if (!isI4x4) {
                        final int ctx = leftNzDc + topNzDc[mbX];
                        Arrays.fill(coeffs, (short) 0);
                        final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 1, 0, y2Dc, y2Ac, ctx);
                        leftNzDc = nz > 0 ? 1 : 0;
                        topNzDc[mbX] = leftNzDc;
                        nzAny |= nz > 0;
                        if (nz > 0) {
                            inverseWHT(coeffs, y2dcScratch, whtTmp);
                            hasY2dc = true;
                        }
                    }

                    final int firstCoef = isI4x4 ? 0 : 1;
                    final int yType = isI4x4 ? 3 : 0;

                    for (int j = 0; j < 4; j++)
                        for (int i = 0; i < 4; i++) {
                            final int sOff = yOff + j * 4 * yStr + i * 4;

                            if (isI4x4) {
                                final boolean rightEdge = (i == 3) && (mbX == mbW - 1);
                                if (mbY > 0 || j > 0) getAbove8Sub(yPln, sOff, yStr, rightEdge, mbY, i, yOff, subAbove);
                                else System.arraycopy(TOP_BORDER_8, 0, subAbove, 0, 8);
                                if (mbX > 0 || i > 0) getLeft(yPln, sOff, yStr, 4, subLeft);
                                else System.arraycopy(LEFT_BORDER_4, 0, subLeft, 0, 4);
                                final int sTl = subTl(mbX, mbY, i, j, yPln, sOff, yStr);
                                predict4(subModes[j * 4 + i], yPln, sOff, yStr, subAbove, subLeft, sTl);
                            }

                            final int ctx = ((leftNzY >> j) & 1) + topNzY[mbX * 4 + i];
                            Arrays.fill(coeffs, (short) 0);
                            if (hasY2dc) coeffs[0] = y2dcScratch[j * 4 + i];
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, yType, firstCoef, y1Dc, y1Ac, ctx);
                            if (nz > 0 || (hasY2dc && y2dcScratch[j * 4 + i] != 0))
                                inverseDCT(coeffs, yPln, sOff, yStr, dctTmp);
                            leftNzY = (leftNzY & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzY[mbX * 4 + i] = nz > 0 ? 1 : 0;
                            nzAny |= nz > 0;
                        }

                    for (int j = 0; j < 2; j++)
                        for (int i = 0; i < 2; i++) {
                            final int ctx = ((leftNzU >> j) & 1) + topNzU[mbX * 2 + i];
                            Arrays.fill(coeffs, (short) 0);
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 2, 0, uvDc, uvAc, ctx);
                            if (nz > 0) inverseDCT(coeffs, uPln, uvOff + j * 4 * uvStr + i * 4, uvStr, dctTmp);
                            leftNzU = (leftNzU & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzU[mbX * 2 + i] = nz > 0 ? 1 : 0;
                            nzAny |= nz > 0;
                        }

                    for (int j = 0; j < 2; j++)
                        for (int i = 0; i < 2; i++) {
                            final int ctx = ((leftNzV >> j) & 1) + topNzV[mbX * 2 + i];
                            Arrays.fill(coeffs, (short) 0);
                            final int nz = decodeCoeffs(tbr, coeffs, st.coeffProbs, 2, 0, uvDc, uvAc, ctx);
                            if (nz > 0) inverseDCT(coeffs, vPln, uvOff + j * 4 * uvStr + i * 4, uvStr, dctTmp);
                            leftNzV = (leftNzV & ~(1 << j)) | ((nz > 0 ? 1 : 0) << j);
                            topNzV[mbX * 2 + i] = nz > 0 ? 1 : 0;
                            nzAny |= nz > 0;
                        }
                } else {
                    leftNzY = 0;
                    for (int i = 0; i < 4; i++) topNzY[mbX * 4 + i] = 0;
                    leftNzU = 0;
                    for (int i = 0; i < 2; i++) topNzU[mbX * 2 + i] = 0;
                    leftNzV = 0;
                    for (int i = 0; i < 2; i++) topNzV[mbX * 2 + i] = 0;
                    if (!isI4x4) {
                        leftNzDc = 0;
                        topNzDc[mbX] = 0;
                    }
                }

                // INNER-EDGE FILTERING APPLIES TO I4X4 MBS OR MBS THAT ACTUALLY CARRY COEFFICIENTS
                st.mbFInner[mbIdx] = (isI4x4 || nzAny) ? 1 : 0;

                // PER-MACROBLOCK EXHAUSTION CHECK (WHAT libwebp DOES): ONCE EITHER PARTITION IS
                // SPENT THE REMAINING GRID WOULD BE SYNTHESISED FROM IMPLICIT ZERO BYTES, WHICH IS
                // HOW A 2-BYTE TOKEN PARTITION "DECODES" A 64-MEGAPIXEL FRAME
                if (hdrBr.eof() || tbr.eof())
                    throw new CodecException("VP8 bitstream truncated: bool decoder ran past partition end at macroblock "
                            + mbX + "," + mbY + " of " + mbW + "x" + mbH);
            }
        }
    }

    // RFC6386 SECTION 13: COEFFICIENT DECODING; type IS 0=Y_AFTER_Y2, 1=Y2, 2=UV OR 3=Y_I4X4
    private static int decodeCoeffs(final VP8BoolDecoder br, final short[] coeffs, final int[][][][] probs,
                                    final int type, final int first, final int dcQ, final int acQ, int ctx) {
        int nz = 0;
        boolean prevZero = false;

        for (int n = first; n < COEFF_BANDS.length; n++) {
            final int band = COEFF_BANDS[n];
            final int[] p = probs[type][band][Math.min(ctx, 2)];

            // AFTER A ZERO TOKEN THE EOB BRANCH IS SKIPPED: ONLY THE ZERO/NON-ZERO DECISION IS CODED
            if (!prevZero && !br.readBool(p[0])) break;
            final int token = br.readBool(p[1]) ? decodeTokenVal(br, p) : 0;
            prevZero = token == 0;
            if (token != 0) {
                final int sign = br.readBool(128) ? -1 : 1;
                coeffs[ZIGZAG[n]] = (short) (token * (n == 0 ? dcQ : acQ) * sign);
                nz++;
            }
            // THE NEXT CONTEXT: 0 AFTER A ZERO TOKEN, 1 AFTER A ONE, 2 AFTER ANYTHING LARGER
            ctx = Math.min(token, 2);
        }
        return nz;
    }

    private static int decodeTokenVal(final VP8BoolDecoder br, final int[] p) {
        if (!br.readBool(p[2])) return 1;
        if (!br.readBool(p[3])) {
            if (!br.readBool(p[4])) return 2;
            return br.readBool(p[5]) ? 4 : 3;
        }
        if (!br.readBool(p[6])) {
            if (!br.readBool(p[7])) return 5 + readExtra(br, PCAT1);
            return 7 + readExtra(br, PCAT2);
        }
        final int b1 = br.readBool(p[8]) ? 1 : 0;
        final int b0 = br.readBool(p[9 + b1]) ? 1 : 0;
        final int cat = 2 * b1 + b0;
        return CATEGORY_BASE[cat + 2] + readExtra(br, CATEGORY_PROBS[cat + 2]);
    }

    private static int readExtra(final VP8BoolDecoder br, final int[] probs) {
        int v = 0;
        for (final int prob: probs) {
            if (prob == 0) break;
            v = (v << 1) | (br.readBool(prob) ? 1 : 0);
        }
        return v;
    }

    // RFC6386 SECTION 11.2: B-MODE TREE
    private static SubMode readSubMode(final VP8BoolDecoder br, final SubMode above, final SubMode left) {
        final int[] p = KF_BMODE_PROB[above.ordinal()][left.ordinal()];
        if (!br.readBool(p[0])) return SubMode.B_DC;
        if (!br.readBool(p[1])) return SubMode.B_TM;
        if (!br.readBool(p[2])) return SubMode.B_VE;
        if (!br.readBool(p[3])) {
            if (!br.readBool(p[4])) return SubMode.B_HE;
            return br.readBool(p[5]) ? SubMode.B_VR : SubMode.B_RD;
        }
        if (!br.readBool(p[6])) return SubMode.B_LD;
        if (!br.readBool(p[7])) return SubMode.B_VL;
        return br.readBool(p[8]) ? SubMode.B_HU : SubMode.B_HD;
    }

    // RFC6386 SECTION 14: DCT/WHT INVERSION
    private static void inverseWHT(final short[] in, final short[] out, final int[] t) {
        for (int i = 0; i < 4; i++) {
            final int a1 = in[i] + in[12 + i];
            final int b1 = in[4 + i] + in[8 + i];
            final int c1 = in[4 + i] - in[8 + i];
            final int d1 = in[i] - in[12 + i];
            t[i] = a1 + b1;
            t[4 + i] = c1 + d1;
            t[8 + i] = a1 - b1;
            t[12 + i] = d1 - c1;
        }

        for (int i = 0; i < 4; i++) {
            final int a1 = t[i * 4] + t[i * 4 + 3];
            final int b1 = t[i * 4 + 1] + t[i * 4 + 2];
            final int c1 = t[i * 4 + 1] - t[i * 4 + 2];
            final int d1 = t[i * 4] - t[i * 4 + 3];
            final int a2 = a1 + b1;
            final int b2 = c1 + d1;
            final int c2 = a1 - b1;
            final int d2 = d1 - c1;
            out[i * 4] = (short) ((a2 + 3) >> 3);
            out[i * 4 + 1] = (short) ((b2 + 3) >> 3);
            out[i * 4 + 2] = (short) ((c2 + 3) >> 3);
            out[i * 4 + 3] = (short) ((d2 + 3) >> 3);
        }
    }

    private static void inverseDCT(final short[] c, final byte[] dst, final int off, final int str, final int[] t) {
        for (int i = 0; i < 4; i++) {
            final int c0 = c[i], c1 = c[4 + i], c2 = c[8 + i], c3 = c[12 + i];
            final int a = c0 + c2;
            final int b = c0 - c2;
            int tmp1 = (c1 * SINPI8SQRT2) >> 16;
            int tmp2 = c3 + ((c3 * COSPI8SQRT2MINUS1) >> 16);
            final int tt = tmp1 - tmp2;
            tmp1 = c1 + ((c1 * COSPI8SQRT2MINUS1) >> 16);
            tmp2 = (c3 * SINPI8SQRT2) >> 16;
            final int d = tmp1 + tmp2;
            t[i] = a + d;
            t[12 + i] = a - d;
            t[4 + i] = b + tt;
            t[8 + i] = b - tt;
        }

        for (int i = 0; i < 4; i++) {
            final int t0 = t[i * 4], t1 = t[i * 4 + 1], t2 = t[i * 4 + 2], t3 = t[i * 4 + 3];
            final int a = t0 + t2;
            final int b = t0 - t2;
            int tmp1 = (t1 * SINPI8SQRT2) >> 16;
            int tmp2 = t3 + ((t3 * COSPI8SQRT2MINUS1) >> 16);
            final int tt = tmp1 - tmp2;
            tmp1 = t1 + ((t1 * COSPI8SQRT2MINUS1) >> 16);
            tmp2 = (t3 * SINPI8SQRT2) >> 16;
            final int d = tmp1 + tmp2;
            dst[off + i * str] = (byte) MathUtil.clip255((dst[off + i * str] & 0xFF) + ((a + d + 4) >> 3));
            dst[off + i * str + 3] = (byte) MathUtil.clip255((dst[off + i * str + 3] & 0xFF) + ((a - d + 4) >> 3));
            dst[off + i * str + 1] = (byte) MathUtil.clip255((dst[off + i * str + 1] & 0xFF) + ((b + tt + 4) >> 3));
            dst[off + i * str + 2] = (byte) MathUtil.clip255((dst[off + i * str + 2] & 0xFF) + ((b - tt + 4) >> 3));
        }
    }

    // ==========================================================================
    // RFC6386 SECTION 12: INTRAFRAME PREDICTION
    // ==========================================================================
    // A BORDER ROW OR COLUMN OF sz BYTES; LIBWEBP USES 127 FOR THE TOP ROW (mbY=0) AND 129 FOR THE LEFT COLUMN (mbX=0)
    private static byte[] fillBorder(final int sz, final int val) {
        final byte[] border = new byte[sz];
        Arrays.fill(border, (byte) val);
        return border;
    }

    // COMPUTE TOP-LEFT PIXEL FOR I4X4 SUBBLOCK, MATCHING LIBWEBP BORDER INIT
    private static int subTl(final int mbX, final int mbY, final int i, final int j, final byte[] pln, final int sOff, final int str) {
        final boolean hasLeft = (mbX > 0 || i > 0);
        final boolean hasAbove = (mbY > 0 || j > 0);
        if (hasLeft && hasAbove) return pln[sOff - str - 1] & 0xFF;
        if (!hasAbove) return 127;  // TOP BORDER (mbY=0, j=0)
        return 129;                 // LEFT BORDER (mbX=0, i=0)
    }

    private static void getAbove(final byte[] pln, final int off, final int str, final int sz, final byte[] out) {
        System.arraycopy(pln, off - str, out, 0, sz);
    }

    private static void getAbove8Sub(final byte[] pln, final int off, final int str, final boolean rightEdgeMB, final int mbY, final int sbCol, final int yOff, final byte[] out) {
        System.arraycopy(pln, off - str, out, 0, 4);

        if (sbCol == 3) {
            if (mbY == 0) {
                out[4] = out[5] = out[6] = out[7] = 127;
            } else if (!rightEdgeMB) {
                System.arraycopy(pln, yOff - str + 16, out, 4, 4);
            } else {
                final byte val = pln[yOff - str + 15];
                out[4] = out[5] = out[6] = out[7] = val;
            }
        } else {
            System.arraycopy(pln, off - str + 4, out, 4, 4);
        }
    }

    private static void getLeft(final byte[] pln, final int off, final int str, final int sz, final byte[] out) {
        for (int i = 0; i < sz; i++) out[i] = pln[off + i * str - 1];
    }

    // PREDICTS A WHOLE sz x sz BLOCK: THE 16X16 LUMA OR AN 8X8 CHROMA PLANE OF THE MACROBLOCK
    private static void predictBlock(final MBMode mode, final byte[] dst, final int off, final int str, final int sz,
                                     final byte[] above, final byte[] left, final int tl,
                                     final boolean hasAbove, final boolean hasLeft) {
        switch (mode) {
            case DC -> {
                // LIBWEBP CheckMode: DC USES ONLY REAL NEIGHBORS, NOT BORDER VALUES
                int sum = 0, cnt = 0;
                if (hasAbove) {
                    for (int i = 0; i < sz; i++) sum += above[i] & 0xFF;
                    cnt += sz;
                }
                if (hasLeft) {
                    for (int i = 0; i < sz; i++) sum += left[i] & 0xFF;
                    cnt += sz;
                }
                fill(dst, off, str, sz, (byte) (cnt > 0 ? (sum + cnt / 2) / cnt : 128));
            }
            case V -> {
                for (int y = 0; y < sz; y++) System.arraycopy(above, 0, dst, off + y * str, sz);
            }
            case H -> {
                for (int y = 0; y < sz; y++) {
                    final byte v = left[y];
                    for (int x = 0; x < sz; x++) dst[off + y * str + x] = v;
                }
            }
            case TM -> {
                for (int y = 0; y < sz; y++)
                    for (int x = 0; x < sz; x++)
                        dst[off + y * str + x] = (byte) MathUtil.clip255((left[y] & 0xFF) + (above[x] & 0xFF) - tl);
            }
        }
    }

    private static void predict4(final SubMode mode, final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        switch (mode) {
            case B_DC -> predDC4(dst, off, str, above, left);
            case B_TM -> predTM4(dst, off, str, above, left, tl);
            case B_VE -> predVE4(dst, off, str, above, tl);
            case B_HE -> predHE4(dst, off, str, left, tl);
            case B_LD -> predLD4(dst, off, str, above);
            case B_RD -> predRD4(dst, off, str, above, left, tl);
            case B_VR -> predVR4(dst, off, str, above, left, tl);
            case B_VL -> predVL4(dst, off, str, above);
            case B_HD -> predHD4(dst, off, str, above, left, tl);
            case B_HU -> predHU4(dst, off, str, left);
        }
    }

    private static void predDC4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left) {
        int sum = 0, cnt = 0;
        if (above != null) {
            for (int i = 0; i < 4; i++) sum += above[i] & 0xFF;
            cnt += 4;
        }
        if (left != null) {
            for (int i = 0; i < 4; i++) sum += left[i] & 0xFF;
            cnt += 4;
        }
        fill(dst, off, str, 4, (byte) (cnt > 0 ? (sum + cnt / 2) / cnt : 128));
    }

    private static void predTM4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int av = above != null ? (above[x] & 0xFF) : 128;
                final int lv = left != null ? (left[y] & 0xFF) : 128;
                dst[off + y * str + x] = (byte) MathUtil.clip255(av + lv - tl);
            }
    }

    private static void predVE4(final byte[] dst, final int off, final int str, final byte[] above, final int tl) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final byte s0 = (byte) avg3(tl, above[0] & 0xFF, above[1] & 0xFF);
        final byte s1 = (byte) avg3(above[0] & 0xFF, above[1] & 0xFF, above[2] & 0xFF);
        final byte s2 = (byte) avg3(above[1] & 0xFF, above[2] & 0xFF, above[3] & 0xFF);
        final byte s3 = (byte) avg3(above[2] & 0xFF, above[3] & 0xFF, above.length > 4 ? (above[4] & 0xFF) : (above[3] & 0xFF));
        for (int y = 0; y < 4; y++) {
            final int row = off + y * str;
            dst[row] = s0;
            dst[row + 1] = s1;
            dst[row + 2] = s2;
            dst[row + 3] = s3;
        }
    }

    private static void predHE4(final byte[] dst, final int off, final int str, final byte[] left, final int tl) {
        if (left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final byte s0 = (byte) avg3(tl, left[0] & 0xFF, left[1] & 0xFF);
        final byte s1 = (byte) avg3(left[0] & 0xFF, left[1] & 0xFF, left[2] & 0xFF);
        final byte s2 = (byte) avg3(left[1] & 0xFF, left[2] & 0xFF, left[3] & 0xFF);
        final byte s3 = (byte) avg3(left[2] & 0xFF, left[3] & 0xFF, left[3] & 0xFF);
        for (int x = 0; x < 4; x++) dst[off + x] = s0;
        for (int x = 0; x < 4; x++) dst[off + str + x] = s1;
        for (int x = 0; x < 4; x++) dst[off + 2 * str + x] = s2;
        for (int x = 0; x < 4; x++) dst[off + 3 * str + x] = s3;
    }

    private static void predLD4(final byte[] dst, final int off, final int str, final byte[] above) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        // THE DIAGONAL READS UP TO THREE SAMPLES PAST THE CORNER, CLAMPED TO THE LAST ABOVE SAMPLE
        final int last = above.length - 1;
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int idx = x + y;
                dst[off + y * str + x] = (byte) avg3(above[Math.min(idx, last)] & 0xFF,
                        above[Math.min(idx + 1, last)] & 0xFF, above[Math.min(idx + 2, last)] & 0xFF);
            }
    }

    private static void predRD4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF;
        final int l1 = left[1] & 0xFF;
        final int l2 = left[2] & 0xFF;
        final int l3 = left[3] & 0xFF;
        final int a0 = above[0] & 0xFF;
        final int a1 = above[1] & 0xFF;
        final int a2 = above[2] & 0xFF;
        final int a3 = above[3] & 0xFF;
        for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
                final int idx = 3 - y + x;
                dst[off + y * str + x] = (byte) avg3(
                        rdSample(idx, l0, l1, l2, l3, tl, a0, a1, a2, a3),
                        rdSample(idx + 1, l0, l1, l2, l3, tl, a0, a1, a2, a3),
                        rdSample(Math.min(idx + 2, 8), l0, l1, l2, l3, tl, a0, a1, a2, a3)
                );
            }
    }

    private static void predVR4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF;
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF, a3 = above[3] & 0xFF;
        dst[off] = (byte) avg2(tl, a0);
        dst[off + 1] = (byte) avg2(a0, a1);
        dst[off + 2] = (byte) avg2(a1, a2);
        dst[off + 3] = (byte) avg2(a2, a3);
        dst[off + str] = (byte) avg3(l0, tl, a0);
        dst[off + str + 1] = (byte) avg3(tl, a0, a1);
        dst[off + str + 2] = (byte) avg3(a0, a1, a2);
        dst[off + str + 3] = (byte) avg3(a1, a2, a3);
        dst[off + 2 * str] = (byte) avg3(l1, l0, tl);
        dst[off + 2 * str + 1] = (byte) avg2(tl, a0);
        dst[off + 2 * str + 2] = (byte) avg2(a0, a1);
        dst[off + 2 * str + 3] = (byte) avg2(a1, a2);
        dst[off + 3 * str] = (byte) avg3(l2, l1, l0);
        dst[off + 3 * str + 1] = (byte) avg3(l0, tl, a0);
        dst[off + 3 * str + 2] = (byte) avg3(tl, a0, a1);
        dst[off + 3 * str + 3] = (byte) avg3(a0, a1, a2);
    }

    private static void predVL4(final byte[] dst, final int off, final int str, final byte[] above) {
        if (above == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF, a3 = above[3] & 0xFF;
        final int a4 = above.length > 4 ? (above[4] & 0xFF) : a3;
        final int a5 = above.length > 5 ? (above[5] & 0xFF) : a4;
        final int a6 = above.length > 6 ? (above[6] & 0xFF) : a5;
        final int a7 = above.length > 7 ? (above[7] & 0xFF) : a6;
        dst[off] = (byte) avg2(a0, a1);
        dst[off + 1] = (byte) avg2(a1, a2);
        dst[off + 2] = (byte) avg2(a2, a3);
        dst[off + 3] = (byte) avg2(a3, a4);
        dst[off + str] = (byte) avg3(a0, a1, a2);
        dst[off + str + 1] = (byte) avg3(a1, a2, a3);
        dst[off + str + 2] = (byte) avg3(a2, a3, a4);
        dst[off + str + 3] = (byte) avg3(a3, a4, a5);
        dst[off + 2 * str] = (byte) avg2(a1, a2);
        dst[off + 2 * str + 1] = (byte) avg2(a2, a3);
        dst[off + 2 * str + 2] = (byte) avg2(a3, a4);
        dst[off + 2 * str + 3] = (byte) avg3(a4, a5, a6);
        dst[off + 3 * str] = (byte) avg3(a1, a2, a3);
        dst[off + 3 * str + 1] = (byte) avg3(a2, a3, a4);
        dst[off + 3 * str + 2] = (byte) avg3(a3, a4, a5);
        dst[off + 3 * str + 3] = (byte) avg3(a5, a6, a7);
    }

    private static void predHD4(final byte[] dst, final int off, final int str, final byte[] above, final byte[] left, final int tl) {
        if (above == null || left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF, l3 = left[3] & 0xFF;
        final int a0 = above[0] & 0xFF, a1 = above[1] & 0xFF, a2 = above[2] & 0xFF;
        dst[off] = (byte) avg2(tl, l0);
        dst[off + 1] = (byte) avg3(a0, tl, l0);
        dst[off + 2] = (byte) avg3(tl, a0, a1);
        dst[off + 3] = (byte) avg3(a0, a1, a2);
        dst[off + str] = (byte) avg2(l0, l1);
        dst[off + str + 1] = (byte) avg3(tl, l0, l1);
        dst[off + str + 2] = (byte) avg2(tl, l0);
        dst[off + str + 3] = (byte) avg3(a0, tl, l0);
        dst[off + 2 * str] = (byte) avg2(l1, l2);
        dst[off + 2 * str + 1] = (byte) avg3(l0, l1, l2);
        dst[off + 2 * str + 2] = (byte) avg2(l0, l1);
        dst[off + 2 * str + 3] = (byte) avg3(tl, l0, l1);
        dst[off + 3 * str] = (byte) avg2(l2, l3);
        dst[off + 3 * str + 1] = (byte) avg3(l1, l2, l3);
        dst[off + 3 * str + 2] = (byte) avg2(l1, l2);
        dst[off + 3 * str + 3] = (byte) avg3(l0, l1, l2);
    }

    private static void predHU4(final byte[] dst, final int off, final int str, final byte[] left) {
        if (left == null) {
            fill(dst, off, str, 4, (byte) 128);
            return;
        }
        final int l0 = left[0] & 0xFF, l1 = left[1] & 0xFF, l2 = left[2] & 0xFF, l3 = left[3] & 0xFF;
        dst[off] = (byte) avg2(l0, l1);
        dst[off + 1] = (byte) avg3(l0, l1, l2);
        dst[off + 2] = (byte) avg2(l1, l2);
        dst[off + 3] = (byte) avg3(l1, l2, l3);
        dst[off + str] = (byte) avg2(l1, l2);
        dst[off + str + 1] = (byte) avg3(l1, l2, l3);
        dst[off + str + 2] = (byte) avg2(l2, l3);
        dst[off + str + 3] = (byte) avg3(l2, l3, l3);
        dst[off + 2 * str] = (byte) avg2(l2, l3);
        dst[off + 2 * str + 1] = (byte) avg3(l2, l3, l3);
        dst[off + 2 * str + 2] = (byte) l3;
        dst[off + 2 * str + 3] = (byte) l3;
        dst[off + 3 * str] = (byte) l3;
        dst[off + 3 * str + 1] = (byte) l3;
        dst[off + 3 * str + 2] = (byte) l3;
        dst[off + 3 * str + 3] = (byte) l3;
    }

    private static void fill(final byte[] dst, final int off, final int str, final int sz, final byte v) {
        for (int y = 0; y < sz; y++)
            for (int x = 0; x < sz; x++)
                dst[off + y * str + x] = v;
    }

    private static int avg2(final int a, final int b) {
        return (a + b + 1) >> 1;
    }

    private static int avg3(final int a, final int b, final int c) {
        return (a + 2 * b + c + 2) >> 2;
    }

    private static int rdSample(final int idx, final int l0, final int l1, final int l2, final int l3,
                                final int tl, final int a0, final int a1, final int a2, final int a3) {
        return switch (idx) {
            case 0 -> l3;
            case 1 -> l2;
            case 2 -> l1;
            case 3 -> l0;
            case 4 -> tl;
            case 5 -> a0;
            case 6 -> a1;
            case 7 -> a2;
            default -> a3;
        };
    }

    // ==========================================================================
    // RFC6386 SECTION 15: LOOP FILTER
    // ==========================================================================
    private static void applyLoopFilter(final State st, final byte[] yPln, final byte[] uPln, final byte[] vPln,
                                        final int yStr, final int uvStr, final int mbW, final int mbH, final boolean keyFrame) {
        for (int mbY = 0; mbY < mbH; mbY++) {
            for (int mbX = 0; mbX < mbW; mbX++) {
                final int mbIdx = mbY * mbW + mbX;
                final int seg = st.mbSeg[mbIdx];
                final int isI4x4 = st.mbIsI4x4[mbIdx];
                final boolean fInner = st.mbFInner[mbIdx] != 0;

                // COMPUTE PER-SEGMENT FILTER LEVEL (MATCHING LIBWEBP PrecomputeFilterStrengths)
                int level;
                if (st.segEnabled) {
                    level = st.segLoop[seg];
                    if (!st.segAbsMode) level += st.loopLvl;
                } else {
                    level = st.loopLvl;
                }
                if (st.useLfDelta) {
                    level += st.refLfDelta[0]; // refLfDelta[0] FOR INTRA FRAME
                    if (isI4x4 != 0) level += st.modeLfDelta[0];
                }
                level = Math.max(0, Math.min(63, level));
                if (level == 0) continue;

                int iLim = level;
                final int shrp = st.sharpLvl;
                if (shrp > 0) {
                    iLim >>= (shrp > 4) ? 2 : 1;
                    if (iLim > 9 - shrp) iLim = 9 - shrp;
                }
                if (iLim < 1) iLim = 1;

                final int limit = 2 * level + iLim;
                final int mbLim = limit + 4;
                final int hevThr = keyFrame ? ((level >= 40) ? 2 : (level >= 15) ? 1 : 0)
                        : ((level >= 40) ? 3 : (level >= 20) ? 2 : (level >= 15) ? 1 : 0);

                final int yOff = mbY * 16 * yStr + mbX * 16;
                final int uvOff = mbY * 8 * uvStr + mbX * 8;

                if (st.filterSimple) {
                    if (mbX > 0) filterSimpleV(yPln, yOff, yStr, mbLim);
                    if (fInner) {
                        filterSimpleV(yPln, yOff + 4, yStr, limit);
                        filterSimpleV(yPln, yOff + 8, yStr, limit);
                        filterSimpleV(yPln, yOff + 12, yStr, limit);
                    }
                    if (mbY > 0) filterSimpleH(yPln, yOff, yStr, mbLim);
                    if (fInner) {
                        filterSimpleH(yPln, yOff + 4 * yStr, yStr, limit);
                        filterSimpleH(yPln, yOff + 8 * yStr, yStr, limit);
                        filterSimpleH(yPln, yOff + 12 * yStr, yStr, limit);
                    }
                } else {
                    if (mbX > 0) filterMBV(yPln, yOff, yStr, 16, iLim, mbLim, hevThr);
                    if (fInner) {
                        filterSubV(yPln, yOff + 4, yStr, 16, iLim, limit, hevThr);
                        filterSubV(yPln, yOff + 8, yStr, 16, iLim, limit, hevThr);
                        filterSubV(yPln, yOff + 12, yStr, 16, iLim, limit, hevThr);
                    }
                    if (mbY > 0) filterMBH(yPln, yOff, yStr, 16, iLim, mbLim, hevThr);
                    if (fInner) {
                        filterSubH(yPln, yOff + 4 * yStr, yStr, 16, iLim, limit, hevThr);
                        filterSubH(yPln, yOff + 8 * yStr, yStr, 16, iLim, limit, hevThr);
                        filterSubH(yPln, yOff + 12 * yStr, yStr, 16, iLim, limit, hevThr);
                    }

                    if (mbX > 0) filterMBV(uPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubV(uPln, uvOff + 4, uvStr, 8, iLim, limit, hevThr);
                    if (mbY > 0) filterMBH(uPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubH(uPln, uvOff + 4 * uvStr, uvStr, 8, iLim, limit, hevThr);

                    if (mbX > 0) filterMBV(vPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubV(vPln, uvOff + 4, uvStr, 8, iLim, limit, hevThr);
                    if (mbY > 0) filterMBH(vPln, uvOff, uvStr, 8, iLim, mbLim, hevThr);
                    if (fInner) filterSubH(vPln, uvOff + 4 * uvStr, uvStr, 8, iLim, limit, hevThr);
                }
            }
        }
    }

    private static void filterSimpleV(final byte[] p, final int off, final int str, final int lim) {
        for (int i = 0; i < 16; i++) {
            final int idx = off + i * str;
            final int p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF;
            if ((Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= lim) {
                final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - 1] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
            }
        }
    }

    private static void filterSimpleH(final byte[] p, final int off, final int str, final int lim) {
        for (int i = 0; i < 16; i++) {
            final int idx = off + i;
            final int p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF;
            if ((Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= lim) {
                final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - str] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
            }
        }
    }

    private static boolean filterYes(final int iLim, final int eLim, final int p3, final int p2, final int p1, final int p0, final int q0, final int q1, final int q2, final int q3) {
        return (Math.abs(p0 - q0) * 2 + Math.abs(p1 - q1) / 2) <= eLim
                && Math.abs(p3 - p2) <= iLim && Math.abs(p2 - p1) <= iLim && Math.abs(p1 - p0) <= iLim
                && Math.abs(q3 - q2) <= iLim && Math.abs(q2 - q1) <= iLim && Math.abs(q1 - q0) <= iLim;
    }

    private static boolean hev(final int thr, final int p1, final int p0, final int q0, final int q1) {
        return Math.abs(p1 - p0) > thr || Math.abs(q1 - q0) > thr;
    }

    private static int clampS8(final int v) {
        return v < -128 ? -128 : (Math.min(v, 127));
    }

    private static void filterSubV(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i * str;
            final int p3 = p[idx - 4] & 0xFF, p2 = p[idx - 3] & 0xFF, p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF, q2 = p[idx + 2] & 0xFF, q3 = p[idx + 3] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                final boolean hv = hev(hevThr, p1, p0, q0, q1);
                final int a = (hv ? clampS8(p1 - q1) : 0) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - 1] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
                if (!hv) {
                    final int a3 = (a1 + 1) >> 1;
                    p[idx - 2] = (byte) MathUtil.clip255(p1 + a3);
                    p[idx + 1] = (byte) MathUtil.clip255(q1 - a3);
                }
            }
        }
    }

    private static void filterSubH(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i;
            final int p3 = p[idx - 4 * str] & 0xFF, p2 = p[idx - 3 * str] & 0xFF, p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF, q2 = p[idx + 2 * str] & 0xFF, q3 = p[idx + 3 * str] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                final boolean hv = hev(hevThr, p1, p0, q0, q1);
                final int a = (hv ? clampS8(p1 - q1) : 0) + 3 * (q0 - p0);
                final int a1 = clampS8((a + 4) >> 3);
                final int a2 = clampS8((a + 3) >> 3);
                p[idx - str] = (byte) MathUtil.clip255(p0 + a2);
                p[idx] = (byte) MathUtil.clip255(q0 - a1);
                if (!hv) {
                    final int a3 = (a1 + 1) >> 1;
                    p[idx - 2 * str] = (byte) MathUtil.clip255(p1 + a3);
                    p[idx + str] = (byte) MathUtil.clip255(q1 - a3);
                }
            }
        }
    }

    private static void filterMBV(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i * str;
            final int p3 = p[idx - 4] & 0xFF, p2 = p[idx - 3] & 0xFF, p1 = p[idx - 2] & 0xFF, p0 = p[idx - 1] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + 1] & 0xFF, q2 = p[idx + 2] & 0xFF, q3 = p[idx + 3] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                if (!hev(hevThr, p1, p0, q0, q1)) {
                    final int p0s = p0 - 128, p1s = p1 - 128, q0s = q0 - 128, q1s = q1 - 128;
                    final int w = clampS8(clampS8(p1s - q1s) + 3 * (q0s - p0s));
                    int a = clampS8((27 * w + 63) >> 7);
                    p[idx - 1] = (byte) MathUtil.clip255((p0s + a) + 128);
                    p[idx] = (byte) MathUtil.clip255((q0s - a) + 128);
                    a = clampS8((18 * w + 63) >> 7);
                    p[idx - 2] = (byte) MathUtil.clip255((p1s + a) + 128);
                    p[idx + 1] = (byte) MathUtil.clip255((q1s - a) + 128);
                    a = clampS8((9 * w + 63) >> 7);
                    p[idx - 3] = (byte) MathUtil.clip255((p2 - 128 + a) + 128);
                    p[idx + 2] = (byte) MathUtil.clip255((q2 - 128 - a) + 128);
                } else {
                    final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                    final int a1 = clampS8((a + 4) >> 3);
                    final int a2 = clampS8((a + 3) >> 3);
                    p[idx - 1] = (byte) MathUtil.clip255(p0 + a2);
                    p[idx] = (byte) MathUtil.clip255(q0 - a1);
                }
            }
        }
    }

    private static void filterMBH(final byte[] p, final int off, final int str, final int len, final int iLim, final int eLim, final int hevThr) {
        for (int i = 0; i < len; i++) {
            final int idx = off + i;
            final int p3 = p[idx - 4 * str] & 0xFF, p2 = p[idx - 3 * str] & 0xFF, p1 = p[idx - 2 * str] & 0xFF, p0 = p[idx - str] & 0xFF;
            final int q0 = p[idx] & 0xFF, q1 = p[idx + str] & 0xFF, q2 = p[idx + 2 * str] & 0xFF, q3 = p[idx + 3 * str] & 0xFF;
            if (filterYes(iLim, eLim, p3, p2, p1, p0, q0, q1, q2, q3)) {
                if (!hev(hevThr, p1, p0, q0, q1)) {
                    final int p0s = p0 - 128, p1s = p1 - 128, q0s = q0 - 128, q1s = q1 - 128;
                    final int w = clampS8(clampS8(p1s - q1s) + 3 * (q0s - p0s));
                    int a = clampS8((27 * w + 63) >> 7);
                    p[idx - str] = (byte) MathUtil.clip255((p0s + a) + 128);
                    p[idx] = (byte) MathUtil.clip255((q0s - a) + 128);
                    a = clampS8((18 * w + 63) >> 7);
                    p[idx - 2 * str] = (byte) MathUtil.clip255((p1s + a) + 128);
                    p[idx + str] = (byte) MathUtil.clip255((q1s - a) + 128);
                    a = clampS8((9 * w + 63) >> 7);
                    p[idx - 3 * str] = (byte) MathUtil.clip255((p2 - 128 + a) + 128);
                    p[idx + 2 * str] = (byte) MathUtil.clip255((q2 - 128 - a) + 128);
                } else {
                    final int a = clampS8(p1 - q1) + 3 * (q0 - p0);
                    final int a1 = clampS8((a + 4) >> 3);
                    final int a2 = clampS8((a + 3) >> 3);
                    p[idx - str] = (byte) MathUtil.clip255(p0 + a2);
                    p[idx] = (byte) MathUtil.clip255(q0 - a1);
                }
            }
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // RFC6386 SECTION 12.1: 16X16 LUMA AND 8X8 CHROMA PREDICTION MODES
    enum MBMode {
        DC, V, H, TM;

        static final MBMode[] VALUES = values();
    }

    // RFC6386 SECTION 12.3: 4X4 SUBBLOCK MODES
    enum SubMode {
        B_DC, B_TM, B_VE, B_HE, B_LD, B_RD, B_VR, B_VL, B_HD, B_HU;

        static SubMode fromMBMode(final MBMode mode) {
            return switch (mode) {
                case DC -> B_DC;
                case V -> B_VE;
                case H -> B_HE;
                case TM -> B_TM;
            };
        }
    }

    // UNCOMPRESSED FRAME TAG AND KEY FRAME HEADER; p0Sz IS CLAMPED IN PLACE WHEN A BROKEN PARTITION 0 IS REPAIRED
    private static final class FrameInfo {
        boolean keyFrame;
        int p0Sz, w, h, hdrSz;
    }

    // MUTABLE DECODE STATE OF ONE FRAME: THE COMPRESSED HEADER FIELDS AND THE PER-MACROBLOCK LOOP FILTER INPUTS
    private static final class State {
        int[][] segQ = new int[4][6];
        int[][][][] coeffProbs;
        boolean segEnabled, segUpdMap, segAbsMode, useSkip;
        int[] segQuant = new int[4];
        int[] segLoop = new int[4];
        int[] segProbs = {255, 255, 255};
        int skipProb, numParts;
        boolean filterSimple, useLfDelta;
        int loopLvl, sharpLvl;
        int[] refLfDelta = new int[4];
        int[] modeLfDelta = new int[4];
        // PER-MB INFO FOR LOOP FILTER (STORED DURING DECODE, USED DURING FILTER)
        int[] mbSeg, mbIsI4x4, mbFInner;
    }
}
