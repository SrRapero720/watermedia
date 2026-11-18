package org.watermedia.api.codecs.readers.webp.lossless;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.test.support.ImageBytes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the VP8L canonical {@link HuffmanTable}: single-symbol tables that consume no bits, one-bit two-symbol
 * tables, canonical builds from code lengths decoding LSB-first streams (an incomplete code included, whose unassigned
 * codes are refused), and the refusal of over-subscribed codes.
 */
@DisplayName("HuffmanTable")
public class HuffmanTableTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static BitReader reader(final int... bytes) {
        return new BitReader(ByteBuffer.wrap(new ImageBytes().raw(bytes).bytes()).order(ByteOrder.LITTLE_ENDIAN));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Simple tables")
    class Simple {

        @TestFactory
        @DisplayName("A single-symbol table returns its symbol without consuming bits")
        Iterable<DynamicTest> singleSymbol() {
            final List<DynamicTest> tests = new ArrayList<>();
            for (final int symbol: new int[] { 0, 255 }) {
                tests.add(dynamicTest("Single symbol [" + symbol + "]", () -> {
                    final HuffmanTable table = HuffmanTable.simple(1, symbol, 0);
                    final BitReader reader = reader(0xFF, 0xFF);
                    for (int i = 0; i < 10; i++) assertEquals(symbol, table.read(reader), "read " + i);
                    assertEquals(2, reader.remaining(), "no bit consumed");
                }));
            }
            return tests;
        }

        @TestFactory
        @DisplayName("A two-symbol table maps bit 0 to the first symbol and bit 1 to the second")
        Iterable<DynamicTest> twoSymbols() {
            final List<DynamicTest> tests = new ArrayList<>();
            // BYTE 0xAA = 10101010: LSB-FIRST READ ORDER YIELDS sym0, sym1, sym0, sym1...
            for (final int[] pair: new int[][] { { 0, 1 }, { 100, 200 } }) {
                tests.add(dynamicTest("Two symbols [" + pair[0] + ", " + pair[1] + "]", () -> {
                    final HuffmanTable table = HuffmanTable.simple(2, pair[0], pair[1]);
                    final BitReader reader = reader(0xAA);
                    for (int i = 0; i < 8; i++) assertEquals(pair[i & 1], table.read(reader), "bit " + i);
                }));
            }
            return tests;
        }
    }

    @Nested
    @DisplayName("Canonical build")
    class Canonical {

        @Test
        @DisplayName("build() assigns canonical codes that decode an LSB-first stream")
        void buildFromCodeLengths() throws CodecException {
            // LENGTHS 1, 2, 3, 3 GIVE THE CODES 0, 10, 110, 111; SENT MOST SIGNIFICANT BIT FIRST, SYMBOLS 0, 1, 2, 3
            // ARE THE BIT SEQUENCE 0 10 110 111, PACKED LSB-FIRST INTO 0xDA 0x01
            final HuffmanTable table = HuffmanTable.build(new int[] { 1, 2, 3, 3, 0, 0, 0, 0 }, 8);
            final BitReader reader = reader(0xDA, 0x01, 0x00, 0x00);
            for (int symbol = 0; symbol < 4; symbol++) assertEquals(symbol, table.read(reader), "symbol " + symbol);
            assertEquals(-1, table.symbol(), "a table of several codes reads bits");
        }

        @Test
        @DisplayName("All-zero code lengths collapse to symbol 0 and a single code to its symbol, both without reading bits")
        void edgeCaseCodeLengths() throws CodecException {
            assertEquals(0, HuffmanTable.build(new int[10], 10).symbol(), "no code at all is symbol 0");
            final int[] single = new int[10];
            single[5] = 1;
            final HuffmanTable table = HuffmanTable.build(single, 10);
            final BitReader reader = reader(0xFF);
            assertEquals(5, table.read(reader));
            assertEquals(1, reader.remaining(), "a single code costs no bits");
        }

        @Test
        @DisplayName("Two equal 2-bit codes decode from an incomplete code, whose unassigned codes are refused")
        void twoSymbol2BitCodes() throws CodecException {
            // TWO SYMBOLS WITH EQUAL 2-BIT CODES 00 AND 01, AN UNDER-FULL CODE REAL FILES EMIT (6.webp'S META GREEN TABLE)
            final int[] codeLengths = new int[280];
            codeLengths[0] = 2;
            codeLengths[1] = 2;
            final HuffmanTable table = HuffmanTable.build(codeLengths, 280);
            // 0x22 READ LSB-FIRST TWO BITS AT A TIME IS 0 1, 0 0, 0 1, 0 0: CODES 01, 00, 01, 00
            final BitReader reader = reader(0x22);
            for (final int symbol: new int[] { 1, 0, 1, 0 }) assertEquals(symbol, table.read(reader));
            // A LEADING 1 BIT STARTS THE CODES 10 AND 11, WHICH NO SYMBOL OWNS
            final CodecException e = assertThrows(CodecException.class, () -> table.read(reader(0x01, 0x00, 0x00, 0x00)));
            assertEquals("(wm_err:5161) Failed to read the huffman code due to invalid code (code=1)", e.getMessage());
        }

        @Test
        @DisplayName("Over-subscribed code lengths are rejected")
        void overSubscribedCodeLengthsRejected() {
            // THREE LENGTH-1 CODES OVER-SUBSCRIBE THE TREE (KRAFT SUM 1.5 > 1); BUILDING THEM WOULD
            // COLLIDE IN THE LOOKUP TABLE, SO build() MUST REJECT IT INSTEAD OF DECODING TO GARBAGE
            final CodecException e = assertThrows(CodecException.class, () -> HuffmanTable.build(new int[] { 1, 1, 1, 0, 0, 0, 0, 0 }, 8));
            assertEquals("(wm_err:5160) Failed to build the huffman table due to over-subscribed code at length 1", e.getMessage());
        }
    }
}
