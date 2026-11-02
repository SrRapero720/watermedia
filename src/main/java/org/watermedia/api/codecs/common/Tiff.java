package org.watermedia.api.codecs.common;

import org.watermedia.tools.DataTool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads the IFD0 fields WaterMedia needs out of a TIFF structure, which is what every EXIF payload carries. */
public final class Tiff {
    private static final int ORIENTATION_TAG = 0x0112;
    private static final byte[] EXIF_PREFIX = { 'E', 'x', 'i', 'f', 0, 0 };

    private Tiff() {}

    /** Returns the EXIF orientation (1-8) declared in IFD0, or 1 when the payload is absent, malformed or lacks the tag. */
    public static int orientation(final byte[] data) {
        if (data == null) return 1;
        // JPEG APP1 SEGMENTS AND SOME WEBP CHUNKS PREFIX THE TIFF STRUCTURE WITH "Exif\0\0"
        final int start = DataTool.startsWith(ByteBuffer.wrap(data), 0, EXIF_PREFIX) ? EXIF_PREFIX.length : 0;
        if (data.length - start < 8) return 1;
        final boolean little = data[start] == 'I' && data[start + 1] == 'I';
        if (!little && !(data[start] == 'M' && data[start + 1] == 'M')) return 1;
        final ByteBuffer b = ByteBuffer.wrap(data, start, data.length - start).slice()
                .order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if ((b.getShort(2) & 0xFFFF) != 42) return 1;
        // IFD0: AN ENTRY COUNT FOLLOWED BY 12-BYTE ENTRIES (TAG, TYPE, COUNT, VALUE); THE ORIENTATION IS ONE
        // SHORT STORED INLINE IN THE VALUE FIELD. EVERY OFFSET COMES FROM THE FILE, SO EACH ONE IS BOUNDS-CHECKED
        final long ifd = b.getInt(4) & 0xFFFF_FFFFL;
        if (ifd + 2 > b.limit()) return 1;
        final int entries = b.getShort((int) ifd) & 0xFFFF;
        for (int i = 0; i < entries; i++) {
            final long entry = ifd + 2 + i * 12L;
            if (entry + 12 > b.limit()) return 1;
            if ((b.getShort((int) entry) & 0xFFFF) != ORIENTATION_TAG) continue;
            final int type = b.getShort((int) entry + 2) & 0xFFFF;
            final int value = type == 3 ? b.getShort((int) entry + 8) & 0xFFFF : type == 4 ? b.getInt((int) entry + 8) : 0;
            return value >= 1 && value <= 8 ? value : 1;
        }
        return 1;
    }
}
