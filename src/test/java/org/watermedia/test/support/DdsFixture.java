package org.watermedia.test.support;

import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Builds DDS byte streams for container and BC reader tests. */
public final class DdsFixture {
    private static final int HEADER_BYTES = 148;
    // THE ON-DISK FOOTER MAGIC, WRITTEN AS BYTES SO THE TESTS PIN THE FILE FORMAT INDEPENDENTLY OF THE READER'S CONSTANT
    private static final byte[] FOOTER_MAGIC = "WMTC".getBytes(StandardCharsets.US_ASCII);

    private DdsFixture() {}

    public static byte[] write(final int width, final int height, final PixelFormat format, final int arraySize) {
        // DXGI_FORMAT_BC1_UNORM, DXGI_FORMAT_BC3_UNORM AND DXGI_FORMAT_BC7_UNORM
        final int dxgi = switch (format) {
            case BC1 -> 71;
            case BC3 -> 77;
            case BC7 -> 98;
            default -> throw new IllegalArgumentException("Unsupported BC fixture: " + format);
        };
        // WRITE THE DDS AND DX10 PREFIX IN LITTLE-ENDIAN CONTAINER ORDER; THE PITCH STAYS 0 BECAUSE READERS DERIVE FRAME
        // SIZES FROM THE BLOCK GRID, WHICH LETS THE PENTEST CORPUS DECLARE DIMENSIONS WHOSE BLOCK BYTES OVERFLOW AN int
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x20534444);
        header.putInt(124);
        header.putInt(0x1 | 0x2 | 0x4 | 0x1000 | 0x80000);
        header.putInt(height);
        header.putInt(width);
        header.putInt(0);
        header.putInt(0);
        header.putInt(1);
        header.position(header.position() + 44);
        header.putInt(32);
        header.putInt(0x4);
        header.putInt(0x30315844);
        header.position(header.position() + 20);
        header.putInt(0x1000);
        header.position(header.position() + 16);
        header.putInt(dxgi);
        header.putInt(3);
        header.putInt(0);
        header.putInt(arraySize);
        header.putInt(0);
        return header.array();
    }

    public static byte[] writeFooter(final long... delays) {
        final ByteBuffer footer = ByteBuffer.allocate(12 + delays.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        footer.put(FOOTER_MAGIC).putInt(1).putInt(delays.length);
        for (final long delay: delays) footer.putLong(delay);
        return footer.array();
    }
}
