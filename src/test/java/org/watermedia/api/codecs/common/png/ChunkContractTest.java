package org.watermedia.api.codecs.common.png;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecException;
import org.watermedia.test.support.ImageBytes;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the PNG {@link IChunk} contract: {@code toChunk()} wraps exactly the {@code toBytes()} data with a
 * valid CRC, every typed {@code read(ByteBuffer)} parses the written envelope through the same {@code convert},
 * and {@link PngChunk#calculateCRC()} / {@link PngChunk#corrupted()} match the reference CRC-32 vectors that
 * drive PNG {@code failOnCorruptedData}.
 */
@DisplayName("PNG chunk contract")
public class ChunkContractTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static PngChunk assertContract(final IChunk value, final int type) throws IOException {
        final byte[] data = value.toBytes();
        final PngChunk chunk = value.toChunk();
        assertEquals(type, chunk.type());
        assertEquals(data.length, chunk.length());
        assertArrayEquals(data, chunk.data());
        assertFalse(chunk.corrupted());

        final PngChunk decoded = PngChunk.read(envelope(chunk));
        assertEquals(chunk.length(), decoded.length());
        assertEquals(chunk.type(), decoded.type());
        assertArrayEquals(chunk.data(), decoded.data());
        assertEquals(chunk.crc(), decoded.crc());
        return chunk;
    }

    private static ByteBuffer envelope(final IChunk value) {
        return envelope(value.toChunk());
    }

    private static ByteBuffer envelope(final int type, final byte[] data) {
        return envelope(PngChunk.create(type, data));
    }

    private static ByteBuffer envelope(final PngChunk chunk) {
        final ByteBuffer buffer = ByteBuffer.allocate(chunk.length() + 12);
        chunk.write(buffer);
        return buffer.flip();
    }

    private static byte[] latin1(final String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Serialization")
    class Serialization {

        @Test
        @DisplayName("Serializes an empty IEND chunk")
        void serializesEmptyChunk() throws IOException {
            assertEquals(0, assertContract(new IEND(), IEND.SIGNATURE).length());
        }

        @Test
        @DisplayName("Serializes frame data behind its sequence number")
        void serializesFrameData() throws IOException {
            final PngChunk frameData = assertContract(new FDAT(7, new byte[] { 9, 10 }), FDAT.SIGNATURE);
            assertArrayEquals(new byte[] { 0, 0, 0, 7, 9, 10 }, frameData.data());
        }

        @Test
        @DisplayName("Serializes IDAT data without changing its payload")
        void serializesDataChunk() throws IOException {
            final byte[] data = { 0, 1, (byte) 0x80, (byte) 0xFF };
            final IDAT idat = new IDAT(data);
            final byte[] payload = idat.toBytes();
            assertArrayEquals(data, payload);
            payload[0] = 7;
            assertArrayEquals(data, idat.toBytes());
            assertArrayEquals(data, assertContract(idat, IDAT.SIGNATURE).data());
        }
    }

    @Nested
    @DisplayName("Typed envelope reads")
    class TypedReads {

        @Test
        @DisplayName("Reads fixed-layout chunks back from their envelope")
        void readsFixedLayoutChunks() throws IOException {
            final IHDR ihdr = new IHDR(16, 9, 8, 6, 0, 0, 0);
            assertEquals(ihdr, IHDR.read(envelope(ihdr)));
            final ACTL actl = new ACTL(3, 0);
            assertEquals(actl, ACTL.read(envelope(actl)));
            final FCTL fctl = new FCTL(1, 16, 9, 0, 0, (short) 1, (short) 10, FCTL.DISPOSE_OP_PREVIOUS, FCTL.BLEND_OP_OVER);
            assertEquals(fctl, FCTL.read(envelope(fctl)));
            final CHRM chrm = new CHRM(31_270, 32_900, 64_000, 33_000, 30_000, 60_000, 15_000, 6_000);
            assertEquals(chrm, CHRM.read(envelope(chrm)));
            final CICP cicp = new CICP(CICP.PRIMARIES_BT2020, CICP.TRANSFER_PQ, 0, 1);
            assertEquals(cicp, CICP.read(envelope(cicp)));
            final CLLI clli = new CLLI(10_000_000L, 4_000_000L);
            assertEquals(clli, CLLI.read(envelope(clli)));
            final MDCV mdcv = new MDCV(34_000, 16_000, 13_250, 34_500, 7_500, 3_000, 15_635, 16_450, 10_000_000L, 1L);
            assertEquals(mdcv, MDCV.read(envelope(mdcv)));
            final GAMA gama = new GAMA(45_455);
            assertEquals(gama, GAMA.read(envelope(gama)));
            final PHYS phys = new PHYS(2_835L, 2_835L, PHYS.UNIT_METER);
            assertEquals(phys, PHYS.read(envelope(phys)));
            final SRGB srgb = new SRGB(SRGB.SATURATION);
            assertEquals(srgb, SRGB.read(envelope(srgb)));
            final TIME time = new TIME(2026, 10, 18, 12, 30, 59);
            assertEquals(time, TIME.read(envelope(time)));
        }

        @Test
        @DisplayName("Forwards the color type and the palette size to convert")
        void forwardsConvertArguments() throws IOException {
            final BKGD gray = new BKGD(0x1234);
            assertEquals(gray, BKGD.read(envelope(gray), 0));
            final BKGD rgb = new BKGD(1, 2, 3);
            assertEquals(rgb, BKGD.read(envelope(rgb), 6));
            final SBIT sbit = SBIT.truecolorAlpha(5, 6, 5, 8);
            assertEquals(sbit, SBIT.read(envelope(sbit), 6));
            final TRNS trns = new TRNS(new byte[] { 0, (byte) 0x80, (byte) 0xFF });
            assertArrayEquals(trns.alphaPerPalette(), TRNS.read(envelope(trns), 3).alphaPerPalette());
            final HIST hist = new HIST(new int[] { 1, 65_535, 300 });
            assertArrayEquals(hist.frequencies(), HIST.read(envelope(hist), 3).frequencies());

            // SAME BYTES UNDER ANOTHER COLOR TYPE OR PALETTE: THE LENGTH RULE FOLLOWS THE FORWARDED ARGUMENT
            assertThrows(CodecException.class, () -> BKGD.read(envelope(gray), 2));
            assertThrows(CodecException.class, () -> HIST.read(envelope(hist), 4));
        }

        @Test
        @DisplayName("Reads variable-length text, palette and profile chunks")
        void readsVariableLengthChunks() throws IOException {
            final TEXT text = new TEXT(TEXT.KEYWORD_TITLE, "WaterMedia");
            assertEquals(text, TEXT.read(envelope(text)));

            final ZTXT ztxt = ZTXT.read(envelope(new ZTXT(TEXT.KEYWORD_COMMENT, 0, ImageBytes.deflate(latin1("compressed"), Deflater.DEFAULT_COMPRESSION))));
            assertEquals(TEXT.KEYWORD_COMMENT, ztxt.keyword());
            assertEquals("compressed", ztxt.text());

            final String translated = "Título";
            final ITXT itxt = ITXT.read(envelope(new ITXT(TEXT.KEYWORD_TITLE, false, 0, "es-MX", translated, "Hola".getBytes(StandardCharsets.UTF_8))));
            assertEquals(TEXT.KEYWORD_TITLE, itxt.keyword());
            assertEquals("es-MX", itxt.languageTag());
            assertEquals(translated, itxt.translatedKeyword());
            assertEquals("Hola", itxt.text());

            final ICCP iccp = ICCP.read(envelope(new ICCP("sRGB", 0, ImageBytes.deflate(latin1("profile"), Deflater.DEFAULT_COMPRESSION))));
            assertEquals("sRGB", iccp.profileName());
            assertArrayEquals(latin1("profile"), iccp.profile());

            final PLTE plte = new PLTE(new int[] { 0xFF_00_00, 0x00_FF_00, 0x00_00_FF });
            assertArrayEquals(plte.colors(), PLTE.read(envelope(plte)).colors());

            final SPLT.SPLTEntry entry = new SPLT.SPLTEntry(1, 2, 3, 255, 7);
            final SPLT splt = SPLT.read(envelope(new SPLT("web", 8, new SPLT.SPLTEntry[] { entry })));
            assertEquals("web", splt.paletteName());
            assertEquals(1, splt.size());
            assertEquals(entry, splt.entry(0));

            final EXIF exif = new EXIF(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
            assertArrayEquals(exif.data(), EXIF.read(envelope(exif)).data());
        }

        @Test
        @DisplayName("Consumes the CRC and leaves the buffer at the next chunk")
        void leavesBufferAtNextChunk() throws IOException {
            final GAMA gama = new GAMA(45_455);
            final SRGB srgb = new SRGB(SRGB.PERCEPTUAL);
            final ByteBuffer stream = ByteBuffer.allocate(GAMA.LENGTH + SRGB.LENGTH + 24);
            gama.toChunk().write(stream);
            srgb.toChunk().write(stream);
            stream.flip();
            assertEquals(gama, GAMA.read(stream));
            assertEquals(srgb, SRGB.read(stream));
            assertFalse(stream.hasRemaining());
        }
    }

    @Nested
    @DisplayName("Shared validation")
    class SharedValidation {

        @Test
        @DisplayName("Rejects empty keywords and names")
        void rejectsEmptyNames() {
            assertThrows(CodecException.class, () -> TEXT.read(envelope(TEXT.SIGNATURE, latin1("\0value"))));
            assertThrows(CodecException.class, () -> ZTXT.read(envelope(ZTXT.SIGNATURE, latin1("\0\0x"))));
            assertThrows(CodecException.class, () -> ITXT.read(envelope(ITXT.SIGNATURE, latin1("\0\0\0\0\0text"))));
            assertThrows(CodecException.class, () -> ICCP.read(envelope(ICCP.SIGNATURE, latin1("\0\0x"))));
            assertThrows(CodecException.class, () -> SPLT.read(envelope(SPLT.SIGNATURE, latin1("\0\b"))));
        }

        @Test
        @DisplayName("Rejects an unknown zTXt or iCCP compression method")
        void rejectsUnknownCompressionMethod() {
            assertThrows(CodecException.class, () -> ZTXT.read(envelope(ZTXT.SIGNATURE, latin1("Comment\0\1x"))));
            assertThrows(CodecException.class, () -> ICCP.read(envelope(ICCP.SIGNATURE, latin1("sRGB\0\1x"))));
        }

        @Test
        @DisplayName("Requires the iTXt language tag and translated keyword terminators")
        void requiresItxtTerminators() {
            assertThrows(CodecException.class, () -> ITXT.read(envelope(ITXT.SIGNATURE, latin1("Title\0\0\0en"))));
            assertThrows(CodecException.class, () -> ITXT.read(envelope(ITXT.SIGNATURE, latin1("Title\0\0\0en\0kw"))));
        }

        @Test
        @DisplayName("Rejects a hIST with more than 256 entries")
        void rejectsOversizedHistogram() {
            assertThrows(CodecException.class, () -> HIST.read(envelope(HIST.SIGNATURE, new byte[HIST.MAX_LENGTH + 2]), 257));
        }

        @Test
        @DisplayName("Rejects fixed-layout chunks with a wrong length or value")
        void rejectsWrongFixedLayout() {
            assertThrows(CodecException.class, () -> IHDR.read(envelope(IHDR.SIGNATURE, new byte[IHDR.LENGTH - 1])));
            assertThrows(CodecException.class, () -> GAMA.read(envelope(GAMA.SIGNATURE, new byte[GAMA.LENGTH])));
            assertThrows(CodecException.class, () -> FCTL.read(envelope(new FCTL(1, 1, 1, 0, 0, (short) 0, (short) 0, (byte) 0, (byte) 2))));
        }

        @Test
        @DisplayName("Fails malformed envelopes before parsing the data")
        void failsMalformedEnvelopes() {
            final ByteBuffer truncated = envelope(new SRGB(SRGB.PERCEPTUAL));
            // DROPS THE CRC FIELD
            truncated.limit(truncated.limit() - 4);
            assertThrows(EOFException.class, () -> SRGB.read(truncated));

            // AN UNSIGNED LENGTH ABOVE 2^31 READS BACK NEGATIVE
            final ByteBuffer oversized = ByteBuffer.wrap(new byte[] { (byte) 0x80, 0, 0, 0, 's', 'R', 'G', 'B' });
            assertThrows(CodecException.class, () -> SRGB.read(oversized));
        }

        @Test
        @DisplayName("Rejects a wrong next chunk on the wire as a codec error")
        void rejectsWrongWireType() {
            final TEXT text = new TEXT(TEXT.KEYWORD_TITLE, "value");
            final CodecException failure = assertThrows(CodecException.class, () -> ZTXT.read(envelope(text)));
            assertEquals("Expected zTXt chunk, got tEXt", failure.getMessage());
            assertThrows(CodecException.class, () -> ITXT.read(envelope(text)));
            assertThrows(CodecException.class, () -> ICCP.read(envelope(text)));
            assertThrows(CodecException.class, () -> SRGB.read(envelope(text)));
            assertThrows(CodecException.class, () -> BKGD.read(envelope(text), 0));
            assertThrows(CodecException.class, () -> HIST.read(envelope(text), 1));
        }

        @Test
        @DisplayName("Rejects a wrong chunk type passed to convert as a caller bug")
        void rejectsWrongType() {
            final PngChunk text = new TEXT(TEXT.KEYWORD_TITLE, "value").toChunk();
            assertThrows(IllegalArgumentException.class, () -> ZTXT.convert(text));
            assertThrows(IllegalArgumentException.class, () -> ITXT.convert(text));
            assertThrows(IllegalArgumentException.class, () -> ICCP.convert(text));
            assertThrows(IllegalArgumentException.class, () -> SRGB.convert(text));
        }
    }

    @Nested
    @DisplayName("CRC")
    class Crc {

        @Test
        @DisplayName("Matches the canonical IEND CRC-32 vector")
        void iendReferenceVector() {
            final PngChunk iend = new PngChunk(0, IEND.SIGNATURE, new byte[0], 0xAE426082);
            assertEquals(0xAE426082, iend.calculateCRC(), "IEND CRC must equal the canonical 0xAE426082");
            assertFalse(iend.corrupted(), "a chunk carrying its correct CRC must not be flagged corrupted");
        }

        @Test
        @DisplayName("Matches the JDK CRC-32 over type + data")
        void matchesJdkCrc() {
            final byte[] data = { 1, 2, 3, 4, 5, (byte) 0xFF, 0, (byte) 0x80 };
            final CRC32 ref = new CRC32();
            // CRC IS COMPUTED OVER THE 4 TYPE BYTES (BIG-ENDIAN) FOLLOWED BY THE DATA
            ref.update(new byte[] { 'I', 'D', 'A', 'T' });
            ref.update(data);
            final int expected = (int) ref.getValue();
            final PngChunk good = new PngChunk(data.length, IDAT.SIGNATURE, data, expected);
            assertEquals(expected, good.calculateCRC());
            assertFalse(good.corrupted());
            assertTrue(new PngChunk(data.length, IDAT.SIGNATURE, data, expected ^ 0x1).corrupted(), "a chunk whose stored CRC is wrong must be flagged corrupted");
        }

        @Test
        @DisplayName("Envelope reads keep the wire CRC and leave its policy to the caller")
        void wireCrc() throws IOException {
            final TEXT text = new TEXT(TEXT.KEYWORD_TITLE, "WaterMedia");
            assertFalse(PngChunk.read(envelope(text)).corrupted());
            final ByteBuffer flipped = envelope(text);
            flipped.put(flipped.limit() - 1, (byte) (flipped.get(flipped.limit() - 1) ^ 0x01));
            assertTrue(PngChunk.read(flipped.duplicate()).corrupted(), "a flipped wire CRC is flagged corrupted");
            // PngReader GATES THE CRC THROUGH failOnCorruptedData, SO THE TYPED PARSER MUST NOT DECIDE IT
            assertEquals(text, TEXT.read(flipped));
        }
    }
}
