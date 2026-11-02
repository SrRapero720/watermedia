package org.watermedia.api.codecs.readers.webp.lossless;

import org.watermedia.api.codecs.CodecException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** LSB-first bit reader over a VP8L bitstream: {@code read(2)} over the bits {@code [b0, b1]} returns {@code b0 | (b1 << 1)}. */
final class BitReader {
    private static final int MAX_BURST_BITS = 24;

    private final ByteBuffer buf;
    private long bitBuf;     // LSB-ALIGNED ACCUMULATOR, LOW bitsAvail BITS VALID
    private int bitsAvail;

    BitReader(final ByteBuffer buffer) {
        this.buf = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // PEEKS UP TO 24 BITS WITHOUT CONSUMING THEM; NEAR EOF THE MISSING HIGH BITS READ AS ZERO
    int peek(final int bits) throws CodecException {
        if (bits == 0) return 0;
        if (bits > MAX_BURST_BITS) throw new CodecException("Cannot peek more than " + MAX_BURST_BITS + " bits at once");
        final int available = this.ensureBits(bits);
        if (available == 0) return 0;
        final int actualBits = Math.min(bits, available);
        return (int) (this.bitBuf & ((1L << actualBits) - 1L));
    }

    // READS AND CONSUMES UP TO 24 BITS; RUNNING OUT OF DATA IS TRUNCATION
    int read(final int bits) throws CodecException {
        if (bits == 0) return 0;
        if (bits > MAX_BURST_BITS) throw new CodecException("Cannot read more than " + MAX_BURST_BITS + " bits at once");
        final int available = this.ensureBits(bits);
        if (available < bits) throw new CodecException("Unexpected end of data: needed " + bits + " bits, only " + available + " available");
        final int result = (int) (this.bitBuf & ((1L << bits) - 1L));
        this.bitBuf >>>= bits;
        this.bitsAvail -= bits;
        return result;
    }

    boolean readBool() throws CodecException {
        if (this.bitsAvail == 0) this.ensureBits(1);
        if (this.bitsAvail == 0) throw new CodecException("Unexpected end of data: needed 1 bit, only 0 available");
        final boolean result = (this.bitBuf & 1L) != 0L;
        this.bitBuf >>>= 1;
        this.bitsAvail -= 1;
        return result;
    }

    // DROPS BITS WITHOUT BOUNDS CHECKS: ONLY AFTER A peek() THAT RETURNED AT LEAST THAT MANY
    void consume(final int bits) {
        this.bitBuf >>>= bits;
        this.bitsAvail -= bits;
    }

    int bitsAvail() { return this.bitsAvail; }
    int remaining() { return this.buf.remaining(); } // UNREAD SOURCE BYTES, EXCLUDING THE ACCUMULATOR

    String snapshot() {
        return "bytes=" + this.buf.remaining() + ",bitsAvail=" + this.bitsAvail + ",bitBuf=0x" + Long.toHexString(this.bitBuf);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    // TOPS THE ACCUMULATOR UP TO AT LEAST bits WHEN THE SOURCE ALLOWS: 32-BIT REFILLS FIRST, THEN BYTE BY BYTE NEAR EOF.
    // RETURNS THE BITS NOW AVAILABLE, WHICH MAY BE FEWER THAN REQUESTED AT EOF
    private int ensureBits(final int bits) {
        if (this.bitsAvail >= bits) return this.bitsAvail;
        while (this.bitsAvail <= 32 && this.buf.remaining() >= 4) {
            final long word = this.buf.getInt() & 0xFFFF_FFFFL;
            this.bitBuf |= word << this.bitsAvail;
            this.bitsAvail += 32;
        }
        while (this.bitsAvail < bits && this.buf.hasRemaining()) {
            this.bitBuf |= ((long) (this.buf.get() & 0xFF)) << this.bitsAvail;
            this.bitsAvail += 8;
        }
        return this.bitsAvail;
    }
}
