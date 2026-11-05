package org.watermedia.test.support;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/** Builds hand-packed image streams: an append-only byte builder plus the PNG, zlib and RIFF/WebP chunk writers the codec tests share. */
public final class ImageBytes {
    /** The 8-byte PNG file signature. */
    public static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Appends one byte. */
    public ImageBytes u8(final int v) { this.out.write(v); return this; }
    /** Appends a little-endian 16-bit value. */
    public ImageBytes le16(final int v) { return this.raw(v, v >> 8); }
    /** Appends a big-endian 16-bit value. */
    public ImageBytes be16(final int v) { return this.raw(v >> 8, v); }
    /** Appends a little-endian 24-bit value. */
    public ImageBytes le24(final int v) { return this.raw(v, v >> 8, v >> 16); }
    /** Appends a little-endian 32-bit value. */
    public ImageBytes le32(final int v) { return this.raw(v, v >> 8, v >> 16, v >> 24); }
    /** Appends a big-endian 32-bit value. */
    public ImageBytes be32(final int v) { return this.raw(v >> 24, v >> 16, v >> 8, v); }
    /** Appends the US-ASCII bytes of a string. */
    public ImageBytes ascii(final String s) { return this.raw(s.getBytes(StandardCharsets.US_ASCII)); }

    /** Appends {@code count} copies of one byte value. */
    public ImageBytes fill(final int value, final int count) {
        for (int i = 0; i < count; i++) this.out.write(value);
        return this;
    }

    /** Appends the low byte of every value, so call sites write {@code 0xFF} without a cast. */
    public ImageBytes raw(final int... values) {
        for (final int v: values) this.out.write(v);
        return this;
    }

    /** Appends a byte array. */
    public ImageBytes raw(final byte[] bytes) { this.out.writeBytes(bytes); return this; }

    /** Returns the bytes written so far. */
    public byte[] bytes() { return this.out.toByteArray(); }

    /** Returns a PNG chunk: big-endian length, type, payload and the CRC-32 over type and payload. */
    public static byte[] pngChunk(final String type, final byte[] payload) {
        final byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(payload);
        return new ImageBytes().be32(payload.length).raw(t).raw(payload).be32((int) crc.getValue()).bytes();
    }

    /** Returns a PNG file: the signature, the given chunks and a closing IEND. */
    public static byte[] png(final byte[]... chunks) {
        final ImageBytes out = new ImageBytes().raw(PNG_SIGNATURE);
        for (final byte[] chunk: chunks) out.raw(chunk);
        return out.raw(pngChunk("IEND", new byte[0])).bytes();
    }

    /** Returns an IHDR chunk with compression, filter and interlace all zero. */
    public static byte[] ihdr(final int width, final int height, final int depth, final int colorType) {
        return pngChunk("IHDR", new ImageBytes().be32(width).be32(height).u8(depth).u8(colorType).raw(0, 0, 0).bytes());
    }

    /** Returns the zlib stream of {@code raw} at the given compression level. */
    public static byte[] deflate(final byte[] raw, final int level) {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(raw);
            deflater.finish();
            final ImageBytes out = new ImageBytes();
            final byte[] buffer = new byte[8_192];
            while (!deflater.finished()) out.out.write(buffer, 0, deflater.deflate(buffer));
            return out.bytes();
        } finally {
            deflater.end();
        }
    }

    /** Returns a RIFF/WEBP file whose size field covers the given chunks. */
    public static byte[] riff(final byte[]... chunks) {
        final ImageBytes body = new ImageBytes().ascii("WEBP");
        for (final byte[] chunk: chunks) body.raw(chunk);
        final byte[] bytes = body.bytes();
        return new ImageBytes().ascii("RIFF").le32(bytes.length).raw(bytes).bytes();
    }

    /** Returns a RIFF chunk: FourCC, little-endian size, payload and the pad byte an odd payload needs. */
    public static byte[] webpChunk(final String fourCC, final byte[] payload) {
        final ImageBytes out = new ImageBytes().ascii(fourCC).le32(payload.length).raw(payload);
        if ((payload.length & 1) == 1) out.u8(0);
        return out.bytes();
    }

    /** Returns a VP8X payload: flags, three reserved bytes and the minus-one 24-bit canvas size. */
    public static byte[] vp8x(final int flags, final int width, final int height) {
        return new ImageBytes().u8(flags).fill(0, 3).le24(width - 1).le24(height - 1).bytes();
    }

    /** LSB-first bit writer, the packing VP8L reads every field in. */
    public static final class Bits {
        private final ImageBytes out = new ImageBytes();
        private int acc;
        private int count;

        /** Appends the low {@code bits} bits of {@code value}, least significant first. */
        public Bits put(final int value, final int bits) {
            for (int i = 0; i < bits; i++) {
                this.acc |= ((value >>> i) & 1) << this.count;
                if (++this.count == 8) {
                    this.out.u8(this.acc);
                    this.acc = 0;
                    this.count = 0;
                }
            }
            return this;
        }

        /** Appends {@code count} whole bytes of {@code value}. */
        public Bits fill(final int value, final int count) {
            for (int i = 0; i < count; i++) this.put(value, 8);
            return this;
        }

        /** Returns the bits written so far, the last partial byte zero-padded. */
        public byte[] bytes() {
            if (this.count > 0) this.put(0, 8 - this.count);
            return this.out.bytes();
        }
    }
}
