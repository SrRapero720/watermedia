package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * sPLT (suggested palette) chunk: a palette for display devices with a limited number of colors.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11sPLT">PNG Specification - sPLT</a>
 */
public record SPLT(String paletteName, int sampleDepth, SPLTEntry[] entries) implements IChunk {
    public static final int SIGNATURE = 0x73_50_4C_54; // "sPLT"

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Reads a complete sPLT chunk (length, type, data and CRC) from the buffer. */
    public static SPLT read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to sPLT. */
    public static SPLT convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for sPLT: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        // THE PALETTE NAME TAKES 1-79 BYTES AND ENDS AT A NUL
        int nullIndex = -1;
        for (int i = 0; i < Math.min(80, data.length); i++) {
            if (data[i] == 0) {
                nullIndex = i;
                break;
            }
        }
        if (nullIndex < 1) throw new CodecException("Invalid sPLT: missing or empty palette name");
        // NEED THE SAMPLE-DEPTH BYTE AFTER THE NAME NUL; A TRUNCATED PAYLOAD OTHERWISE READS PAST THE ARRAY
        if (data.length < nullIndex + 2) throw new CodecException("Truncated sPLT: missing sample depth");

        final String paletteName = new String(data, 0, nullIndex, StandardCharsets.ISO_8859_1);
        final int sampleDepth = data[nullIndex + 1] & 0xFF;
        if (sampleDepth != 8 && sampleDepth != 16) throw new CodecException("Invalid sPLT sample depth: " + sampleDepth);

        final int entrySize = (sampleDepth == 8) ? 6 : 10; // RGBA + FREQUENCY
        final int entryDataStart = nullIndex + 2;
        final int entryDataLength = data.length - entryDataStart;
        if (entryDataLength % entrySize != 0) throw new CodecException("Invalid sPLT entry data length");

        final int entryCount = entryDataLength / entrySize;
        final SPLTEntry[] entries = new SPLTEntry[entryCount];
        final ByteBuffer buffer = ByteBuffer.wrap(data, entryDataStart, entryDataLength).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < entryCount; i++) {
            final int red, green, blue, alpha;
            if (sampleDepth == 8) {
                red = buffer.get() & 0xFF;
                green = buffer.get() & 0xFF;
                blue = buffer.get() & 0xFF;
                alpha = buffer.get() & 0xFF;
            } else { // 16-BIT
                red = buffer.getShort() & 0xFFFF;
                green = buffer.getShort() & 0xFFFF;
                blue = buffer.getShort() & 0xFFFF;
                alpha = buffer.getShort() & 0xFFFF;
            }
            // THE FREQUENCY IS 16-BIT AT EITHER SAMPLE DEPTH
            final int frequency = buffer.getShort() & 0xFFFF;
            entries[i] = new SPLTEntry(red, green, blue, alpha, frequency);
        }
        return new SPLT(paletteName, sampleDepth, entries);
    }

    /** Returns the number of entries. */
    public int size() {
        return this.entries.length;
    }

    /** Returns the suggested-palette entry at an index. */
    public SPLTEntry entry(final int index) {
        return this.entries[index];
    }

    @Override
    public byte[] toBytes() {
        final byte[] nameBytes = this.paletteName.getBytes(StandardCharsets.ISO_8859_1);
        final int entrySize = (this.sampleDepth == 8) ? 6 : 10;
        final ByteBuffer buf = ByteBuffer.allocate(nameBytes.length + 1 + 1 + this.entries.length * entrySize).order(ByteOrder.BIG_ENDIAN);
        buf.put(nameBytes);
        buf.put((byte) 0);
        buf.put((byte) this.sampleDepth);
        for (final SPLTEntry entry: this.entries) {
            if (this.sampleDepth == 8) {
                buf.put((byte) entry.red());
                buf.put((byte) entry.green());
                buf.put((byte) entry.blue());
                buf.put((byte) entry.alpha());
            } else {
                buf.putShort((short) entry.red());
                buf.putShort((short) entry.green());
                buf.putShort((short) entry.blue());
                buf.putShort((short) entry.alpha());
            }
            buf.putShort((short) entry.frequency());
        }
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** A single entry in the suggested palette. */
    public record SPLTEntry(int red, int green, int blue, int alpha, int frequency) {
        /** Returns the entry as packed ARGB, scaled to 8 bits when the samples are 16-bit. */
        public int toARGB(final int sampleDepth) {
            if (sampleDepth == 8) return (this.alpha << 24) | (this.red << 16) | (this.green << 8) | this.blue;
            // 16-BIT SAMPLES KEEP THEIR HIGH BYTE
            return ((this.alpha >> 8) << 24) | ((this.red >> 8) << 16) | ((this.green >> 8) << 8) | (this.blue >> 8);
        }
    }
}
