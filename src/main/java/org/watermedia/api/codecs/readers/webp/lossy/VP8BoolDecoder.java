package org.watermedia.api.codecs.readers.webp.lossy;

import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.readers.webp.lossy.VP8LossyDecoder.IT;

// RFC6386 SECTION 7: BOOLEAN ENTROPY DECODER
// MAINTAINS 'VALUE' AND 'RANGE' TO TRACK CURRENT POSITION IN ARITHMETIC CODING INTERVAL
// KEY CONSTRAINTS: 128 <= RANGE <= 255, VALUE CONTAINS AT LEAST 8 SIGNIFICANT BITS
final class VP8BoolDecoder {
    // BYTES THE DECODER MAY FABRICATE PAST ITS PARTITION END BEFORE THE STREAM COUNTS AS TRUNCATED: THE RENORMALIZER
    // RUNS UP TO TWO BYTES AHEAD AND ENCODERS FLUSH A SHORT ZERO TAIL, SO A FEW PHANTOM BYTES ARE NORMAL AT THE END
    // OF A VALID PARTITION, BUT A FRAME'S WORTH OF THEM IS NOT
    private static final int MAX_PAD_BYTES = 16;

    private final ByteBuffer buf;
    private int range;    // ALWAYS 128-255 PER RFC6386 7.2
    private int val;      // CURRENT CODED VALUE
    private int bitCnt;   // NUMBER OF BITS SHIFTED OUT, MAX 7
    private int padBytes; // IMPLICIT ZERO BYTES CONSUMED PAST THE PARTITION END
    private boolean eof;  // SET ONCE padBytes EXCEEDS THE TOLERATED FLUSH TAIL

    // RFC6386 SECTION 7.3: DECODER INITIALIZATION
    // READS FIRST TWO BYTES TO INITIALIZE VALUE, SETS RANGE = 255
    VP8BoolDecoder(final ByteBuffer buffer) throws CodecException {
        this.buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        if (this.buf.remaining() < 2) throw new CodecException("VP8 bool decoder buffer too small");

        // VALUE = FIRST 2 INPUT BYTES (BIG-ENDIAN ORDER)
        this.val = (this.buf.get() & 0xFF) << 8;
        this.val |= (this.buf.get() & 0xFF);
        this.range = 255;
        this.bitCnt = 0;

        LOGGER.trace(IT, "Init: val=0x{}, range={}", Integer.toHexString(this.val), this.range);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // RFC6386 SECTION 7.3, READ_BOOL: prob IS P(FALSE) * 256, AND THE BIT IS VALUE >= SPLIT << 8
    // WITH SPLIT = 1 + (((RANGE - 1) * PROB) >> 8) (EQ 7.1)
    boolean readBool(final int prob) {
        final int split = 1 + (((this.range - 1) * prob) >> 8);
        final int bigSplit = split << 8;

        int r;
        int v = this.val;
        final boolean bit;
        if (v >= bigSplit) {
            bit = true;
            r = this.range - split;
            v -= bigSplit;
        } else {
            bit = false;
            r = split;
        }

        // RENORMALIZE IN ONE BATCHED SHIFT: shift IS THE LEFT SHIFTS THAT BRING range BACK TO >= 128
        if (r < 128) {
            final int shift = Integer.numberOfLeadingZeros(r) - 24;
            r <<= shift;
            v <<= shift;
            int bc = this.bitCnt + shift;
            // bc IS AT MOST 7 + 7 = 14, SO ONE BYTE REFILL IS ENOUGH
            if (bc >= 8) {
                bc -= 8;
                if (this.buf.hasRemaining()) {
                    v |= (this.buf.get() & 0xFF) << bc;
                } else if (++this.padBytes > MAX_PAD_BYTES) {
                    // NOTHING LEFT TO READ: EVERY FURTHER BIT IS INVENTED, NOT DECODED. WITHOUT THIS
                    // FLAG A 2-BYTE PARTITION "DECODES" A FULL 64-MEGAPIXEL FRAME OUT OF ZEROS
                    // (libwebp CVE-2018-25009 / 25010 / 25012 / 25014)
                    this.eof = true;
                }
            }
            this.bitCnt = bc;
        }
        this.range = r;
        this.val = v;
        return bit;
    }

    // RFC6386 SECTION 7.3: 50% PROBABILITY BOOL (FLAG)
    boolean readBool() {
        return this.readBool(128);
    }

    // RFC6386 SECTION 8: N-BIT UNSIGNED LITERAL L(N), MSB FIRST
    int readLit(final int n) {
        int r = 0;
        for (int i = 0; i < n; i++) r = (r << 1) | (this.readBool() ? 1 : 0);
        return r;
    }

    // RFC6386 SECTION 8, SIGNED LITERAL: MAGNITUDE FIRST, THEN THE SIGN BIT (1 = NEGATIVE)
    int readSigned(final int n) {
        final int mag = this.readLit(n);
        return this.readBool() ? -mag : mag;
    }

    // TRUE ONCE THE DECODER HAS RUN PAST ITS PARTITION BY MORE THAN A FLUSH TAIL
    boolean eof() {
        return this.eof;
    }
}
