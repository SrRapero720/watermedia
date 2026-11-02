package org.watermedia.api.codecs.readers.webp.lossless;

import org.watermedia.api.codecs.CodecException;

import static org.watermedia.WaterMedia.LOGGER;
import static org.watermedia.api.codecs.readers.webp.lossless.VP8LosslessDecoder.IT;

// READS THE VP8L PREFIX CODES: SIMPLE (ONE OR TWO SYMBOLS) OR LENGTH-CODED TABLES, GROUPED FIVE PER META GROUP
final class HuffmanDecoder {
    // CODE LENGTH CODE ORDER (RFC 9649 SECTION 3.7.2.1.2)
    private static final int[] CODE_LENGTH_ORDER = {
            17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private HuffmanDecoder() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // READS THE GREEN, RED, BLUE, ALPHA AND DISTANCE TABLES OF ONE GROUP. GREEN HOLDS 256 LITERALS, 24 LENGTH
    // CODES AND THE COLOR CACHE; RED, BLUE AND ALPHA 256 SYMBOLS; DISTANCE 40
    static VP8LosslessDecoder.HuffmanGroup readGroup(final BitReader reader, final int colorCacheSize) throws CodecException {
        final HuffmanTable green = readTable(reader, 256 + 24 + colorCacheSize);
        final HuffmanTable red = readTable(reader, 256);
        final HuffmanTable blue = readTable(reader, 256);
        final HuffmanTable alpha = readTable(reader, 256);
        final HuffmanTable dist = readTable(reader, 40);
        return new VP8LosslessDecoder.HuffmanGroup(green, red, blue, alpha, dist);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // READS ONE SIMPLE OR LENGTH-CODED TABLE FOR THE DECLARED ALPHABET SIZE
    private static HuffmanTable readTable(final BitReader reader, final int alphabetSize) throws CodecException {
        final boolean isSimple = reader.readBool();
        LOGGER.trace(IT, "readTable: alphabetSize={}, isSimple={}", alphabetSize, isSimple);
        return isSimple ? readSimpleCode(reader, alphabetSize) : readNormalCode(reader, alphabetSize);
    }

    private static HuffmanTable readSimpleCode(final BitReader reader, final int alphabetSize) throws CodecException {
        final int numSymbols = reader.read(1) + 1;
        final int is8Bits = reader.read(1);

        final int sym0 = reader.read(1 + 7 * is8Bits);
        if (sym0 >= alphabetSize) throw new CodecException("Simple code symbol out of range: " + sym0);

        int sym1 = 0;
        if (numSymbols == 2) {
            sym1 = reader.read(8);
            if (sym1 >= alphabetSize) throw new CodecException("Simple code symbol out of range: " + sym1);
        }

        LOGGER.trace(IT, "readSimpleCode: alphabetSize={}, numSymbols={}, is8Bits={}, sym0={}, sym1={}",
                alphabetSize, numSymbols, is8Bits, sym0, sym1);

        return HuffmanTable.simple(numSymbols, sym0, sym1);
    }

    private static HuffmanTable readNormalCode(final BitReader reader, final int alphabetSize) throws CodecException {
        LOGGER.trace(IT, "readNormalCode: alphabetSize={}, remaining={}", alphabetSize, reader.remaining());

        // THE CODE LENGTH CODE: ITS OWN LENGTHS COME FIRST, IN CODE_LENGTH_ORDER
        final int numCodeLengthCodes = reader.read(4) + 4;
        LOGGER.trace(IT, "  numCodeLengthCodes={}", numCodeLengthCodes);
        final int[] codeLengthCodeLengths = new int[19];
        for (int i = 0; i < numCodeLengthCodes; i++) codeLengthCodeLengths[CODE_LENGTH_ORDER[i]] = reader.read(3);
        final HuffmanTable codeLengthTable = HuffmanTable.build(codeLengthCodeLengths, 19);

        final int maxSymbol;
        final boolean useLength = reader.readBool();
        if (useLength) {
            final int lengthNBits = reader.read(3);
            final int lengthBits = 2 + 2 * lengthNBits;
            maxSymbol = 2 + reader.read(lengthBits);
            LOGGER.trace(IT, "  lengthNBits={}, lengthBits={}, maxSymbol={}", lengthNBits, lengthBits, maxSymbol);
            if (maxSymbol > alphabetSize) {
                LOGGER.trace(IT, "  ERROR: maxSymbol={} > alphabetSize={}", maxSymbol, alphabetSize);
                throw new CodecException("Max symbol exceeds alphabet size");
            }
        } else {
            maxSymbol = alphabetSize;
            LOGGER.trace(IT, "  maxSymbol={} (default)", maxSymbol);
        }

        // maxSymbol COUNTS CODE-LENGTH TOKENS, NOT SYMBOL POSITIONS: A REPEAT CODE (16/17/18) CONSUMES ONE TOKEN
        // BUT FILLS SEVERAL SYMBOLS, AS IN libwebp ReadHuffmanCodeLengths
        final int[] codeLengths = new int[alphabetSize];
        int prevCodeLen = 8;
        int symbol = 0;
        int tokensRemaining = maxSymbol;

        while (symbol < alphabetSize && tokensRemaining > 0) {
            tokensRemaining--;
            final int code = codeLengthTable.read(reader);

            if (code < 16) {
                // LITERAL CODE LENGTH
                codeLengths[symbol++] = code;
                if (code != 0) prevCodeLen = code;
            } else if (code == 16) {
                // REPEAT PREVIOUS
                final int extraBits = reader.read(2);
                final int repeat = 3 + extraBits;
                if (symbol + repeat > alphabetSize)
                    throw new CodecException("Repeat code overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                for (int i = 0; i < repeat; i++) codeLengths[symbol++] = prevCodeLen;
            } else if (code == 17) {
                // REPEAT ZERO (SHORT)
                final int extraBits = reader.read(3);
                final int repeat = 3 + extraBits;
                if (symbol + repeat > alphabetSize)
                    throw new CodecException("Repeat zero overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                symbol += repeat;
            } else if (code == 18) {
                // REPEAT ZERO (LONG)
                final int extraBits = reader.read(7);
                final int repeat = 11 + extraBits;
                if (symbol + repeat > alphabetSize)
                    throw new CodecException("Repeat zero overflow: symbol=" + symbol + ", repeat=" + repeat + ", alphabetSize=" + alphabetSize);
                symbol += repeat;
            }
        }

        return HuffmanTable.build(codeLengths, alphabetSize);
    }
}
