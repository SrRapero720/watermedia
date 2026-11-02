package org.watermedia.api.codecs.readers.webp.lossless;

import org.watermedia.api.codecs.CodecException;

/** Canonical LSB-first VP8L prefix code: a 7-bit lookup resolves short codes, a per-length scan the longer ones. */
public final class HuffmanTable {
    private static final int LOOKUP_BITS = 7;
    private static final int LOOKUP_SIZE = 1 << LOOKUP_BITS;
    // SHARED EMPTY LONG-CODE TABLES. TABLES WHOSE CODES ALL FIT IN LOOKUP_BITS NEVER INDEX THEM,
    // SO ONE IMMUTABLE INSTANCE IS ENOUGH AND A MINIMALLY-ENCODED HUFFMAN GROUP STOPS COSTING
    // FIVE THROWAWAY ARRAYS
    private static final int[] NO_LONG = new int[0];
    private static final int[] NO_LONG_START = new int[2];

    // LOOKUP TABLE: LOW 8 BITS = LENGTH, HIGH 24 BITS = SYMBOL. NULL FOR SINGLE-SYMBOL TABLES,
    // WHICH RESOLVE WITHOUT READING BITS AND THEREFORE NEVER TOUCH IT
    private final int[] lookup;
    // LONG CODES (len > LOOKUP_BITS), PRE-REVERSED FOR THE LSB-FIRST COMPARE OF THE LINEAR SCAN
    private final int[] longCodes;
    private final int[] longSymbols;
    // PER-LENGTH START INDICES: LONG ENTRIES OF LENGTH len OCCUPY [longStart[len], longStart[len+1])
    private final int[] longStart;
    private final int longMaxLen;
    // SINGLE SYMBOL FLAG: WHEN SET, singleSymbolValue IS RETURNED WITHOUT READING BITS
    private final boolean isSingleSymbol;
    private final int singleSymbolValue;

    private HuffmanTable(final int[] lookup, final int[] longCodes, final int[] longSymbols, final int[] longStart, final int longMaxLen,
                         final boolean isSingleSymbol, final int singleSymbolValue) {
        this.lookup = lookup;
        this.longCodes = longCodes;
        this.longSymbols = longSymbols;
        this.longStart = longStart;
        this.longMaxLen = longMaxLen;
        this.isSingleSymbol = isSingleSymbol;
        this.singleSymbolValue = singleSymbolValue;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Builds a canonical LSB-first table from symbol lengths; rejects over-subscribed codes. */
    public static HuffmanTable build(final int[] codeLengths, final int alphabetSize) throws CodecException {
        // COUNT CODE LENGTHS AND FIND SINGLE SYMBOL IF PRESENT
        final int[] lengthCount = new int[16];
        int maxLen = 0;
        int symbolCount = 0;
        int singleSymbol = -1;
        for (int i = 0; i < codeLengths.length; i++) {
            if (codeLengths[i] > 0) {
                lengthCount[codeLengths[i]]++;
                if (codeLengths[i] > maxLen) maxLen = codeLengths[i];
                symbolCount++;
                singleSymbol = i;
            }
        }

        // RFC 9649: IF ONLY ONE SYMBOL HAS NON-ZERO CODE LENGTH, IT REQUIRES ZERO BITS
        if (symbolCount == 1) return singleSymbolTable(singleSymbol);
        // NO SYMBOL HAS A CODE: TREAT IT AS SINGLE SYMBOL 0
        if (symbolCount == 0) return singleSymbolTable(0);

        // REJECT OVER-SUBSCRIBED CODES: MORE CODES OF A LENGTH THAN THE TREE HAS ROOM FOR WOULD
        // COLLIDE IN THE LOOKUP TABLE AND DECODE TO GARBAGE. INCOMPLETE (UNDER-FULL) CODES ARE
        // LEFT VALID BECAUSE REAL VP8L FILES EMIT THEM (E.G. TWO LENGTH-2 CODES).
        int left = 1;
        for (int len = 1; len <= maxLen; len++) {
            left <<= 1;
            left -= lengthCount[len];
            if (left < 0) throw new CodecException("Over-subscribed huffman code at length " + len);
        }

        // COMPUTE FIRST CODE FOR EACH LENGTH
        final int[] nextCode = new int[16];
        int code = 0;
        for (int len = 1; len <= maxLen; len++) {
            code = (code + lengthCount[len - 1]) << 1;
            nextCode[len] = code;
        }

        // ASSIGN CODES TO SYMBOLS
        final int[] codes = new int[alphabetSize];
        final int[] lengths = new int[alphabetSize];
        for (int i = 0; i < codeLengths.length && i < alphabetSize; i++) {
            final int len = codeLengths[i];
            if (len > 0) {
                codes[i] = nextCode[len]++;
                lengths[i] = len;
            }
        }

        // CODES ARE STORED REVERSED BECAUSE BITS ARE READ LSB-FIRST: A SHORT CODE FILLS EVERY LOOKUP ENTRY
        // SHARING ITS PREFIX, A LONG ONE (len > LOOKUP_BITS) GOES TO THE COMPACT LONG-CODE TABLES
        final int[] lookup = new int[LOOKUP_SIZE];
        int longCount = 0;
        for (int sym = 0; sym < alphabetSize; sym++) if (lengths[sym] > LOOKUP_BITS) longCount++;
        final int[] longLens = new int[longCount];
        final int[] longCodes = new int[longCount];
        final int[] longSymbols = new int[longCount];
        int longIdx = 0;
        int longMaxLen = 0;

        for (int sym = 0; sym < alphabetSize; sym++) {
            final int len = lengths[sym];
            if (len == 0) continue;
            final int reversed = Integer.reverse(codes[sym]) >>> (32 - len); // THE LOW len BITS, MIRRORED
            if (len <= LOOKUP_BITS) {
                final int step = 1 << len;
                final int packed = (sym << 8) | len;
                for (int i = reversed; i < LOOKUP_SIZE; i += step) lookup[i] = packed;
            } else {
                longLens[longIdx] = len;
                longCodes[longIdx] = reversed;
                longSymbols[longIdx] = sym;
                longIdx++;
                if (len > longMaxLen) longMaxLen = len;
            }
        }

        // SORT THE THREE PARALLEL LONG ARRAYS TOGETHER BY ASCENDING LENGTH SO ENTRIES OF EACH
        // LENGTH ARE CONTIGUOUS; SMALL N, IN-PLACE INSERTION SORT KEEPING ALL THREE IN LOCKSTEP
        for (int a = 1; a < longCount; a++) {
            final int kLen = longLens[a];
            final int kCode = longCodes[a];
            final int kSym = longSymbols[a];
            int b = a - 1;
            while (b >= 0 && longLens[b] > kLen) {
                longLens[b + 1] = longLens[b];
                longCodes[b + 1] = longCodes[b];
                longSymbols[b + 1] = longSymbols[b];
                b--;
            }
            longLens[b + 1] = kLen;
            longCodes[b + 1] = kCode;
            longSymbols[b + 1] = kSym;
        }

        // PRECOMPUTE PER-LENGTH START INDICES: ENTRIES OF LENGTH len OCCUPY [longStart[len], longStart[len+1])
        final int[] longStart = new int[longMaxLen + 2];
        int startPos = 0;
        for (int len = 0; len <= longMaxLen + 1; len++) {
            longStart[len] = startPos;
            while (startPos < longCount && longLens[startPos] == len) startPos++;
        }

        return new HuffmanTable(lookup, longCodes, longSymbols, longStart, longMaxLen, false, 0);
    }

    /** Builds a one-symbol zero-bit table or a two-symbol one-bit table. */
    public static HuffmanTable simple(final int numSymbols, final int sym0, final int sym1) {
        if (numSymbols == 1) return singleSymbolTable(sym0);

        // TWO SYMBOLS, LENGTH 1 EACH. BIT 0 = sym0, BIT 1 = sym1
        final int[] lookup = new int[LOOKUP_SIZE];
        for (int i = 0; i < LOOKUP_SIZE; i++) lookup[i] = ((i & 1) == 0 ? (sym0 << 8) : (sym1 << 8)) | 1;
        return new HuffmanTable(lookup, NO_LONG, NO_LONG, NO_LONG_START, 0, false, 0);
    }

    /** Returns the symbol this table yields without consuming a bit, or {@code -1} when it reads bits. */
    public int symbol() {
        return this.isSingleSymbol ? this.singleSymbolValue : -1;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // DECODES THE NEXT SYMBOL FROM THE LSB-FIRST BITSTREAM, OR RETURNS THE SOLE SYMBOL WITHOUT CONSUMING BITS
    int read(final BitReader reader) throws CodecException {
        if (this.isSingleSymbol) return this.singleSymbolValue;

        final int idx = reader.peek(LOOKUP_BITS);
        if (reader.bitsAvail() >= LOOKUP_BITS) {
            // FAST PATH: PEEK LOOKUP_BITS, RESOLVE IN O(1) FROM THE PREFILLED LOOKUP TABLE
            final int entry = this.lookup[idx];
            final int len = entry & 0xFF;
            if (len > 0) {
                reader.consume(len);
                return entry >>> 8;
            }
            // LONG CODE: PAST THE 7 PEEKED BITS, EXTEND ONE BIT AT A TIME AND COMPARE AGAINST THE PRE-REVERSED CODES
            int code = idx;
            reader.consume(LOOKUP_BITS);
            for (int curLen = LOOKUP_BITS + 1; curLen <= this.longMaxLen; curLen++) {
                final int bit = reader.read(1);
                code |= bit << (curLen - 1);
                // SCAN ONLY THE CONTIGUOUS RANGE OF ENTRIES OF THIS LENGTH (LENGTH ALREADY MATCHES)
                for (int j = this.longStart[curLen]; j < this.longStart[curLen + 1]; j++)
                    if (this.longCodes[j] == code) return this.longSymbols[j];
            }
            throw new CodecException("Invalid huffman code (code=" + code + ")");
        }

        // SLOW SAFE PATH: NEAR EOF, READ BIT-BY-BIT WHILE PROPAGATING EOF EXCEPTIONS
        int code = 0;
        for (int curLen = 1; curLen <= 15; curLen++) {
            final int bit = reader.read(1);
            code |= bit << (curLen - 1);
            if (curLen <= LOOKUP_BITS) {
                final int entry = this.lookup[code];
                if ((entry & 0xFF) == curLen) return entry >>> 8;
            } else if (curLen <= this.longMaxLen) {
                // SCAN ONLY THE CONTIGUOUS RANGE OF ENTRIES OF THIS LENGTH (LENGTH ALREADY MATCHES)
                for (int j = this.longStart[curLen]; j < this.longStart[curLen + 1]; j++)
                    if (this.longCodes[j] == code) return this.longSymbols[j];
            }
        }
        throw new CodecException("Invalid huffman code (code=" + code + ")");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // A ONE-SYMBOL CODE COSTS ZERO BITS (RFC 9649), SO NO LOOKUP ARRAY IS BUILT AT ALL: ALLOCATING
    // ONE WOULD MAKE A 20-BIT HUFFMAN GROUP COST KILOBYTES OF LIVE TABLES NOBODY EVER READS
    private static HuffmanTable singleSymbolTable(final int symbol) {
        return new HuffmanTable(null, NO_LONG, NO_LONG, NO_LONG_START, 0, true, symbol);
    }
}
