package org.watermedia.api.codecs.common.png;

import org.watermedia.api.codecs.CodecException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * tIME (image last-modification time) chunk: the UTC time of the last image modification.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#11tIME">PNG Specification - tIME</a>
 */
public record TIME(int year, int month, int day, int hour, int minute, int second) implements IChunk {
    public static final int SIGNATURE = 0x74_49_4D_45; // "tIME"
    public static final int LENGTH = 7;

    /** Reads a complete tIME chunk (length, type, data and CRC) from the buffer. */
    public static TIME read(final ByteBuffer buffer) throws IOException {
        return convert(PngChunk.read(buffer, SIGNATURE));
    }

    /** Converts a generic chunk to tIME. */
    public static TIME convert(final PngChunk chunk) throws CodecException {
        if (chunk.type() != SIGNATURE) throw new IllegalArgumentException("Invalid chunk type for tIME: 0x" + Integer.toHexString(chunk.type()));

        final byte[] data = chunk.data();
        if (data.length != LENGTH) throw new CodecException("tIME data must be 7 bytes, got " + data.length);

        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return new TIME(
                buffer.getShort() & 0xFFFF, // YEAR (2 BYTES)
                buffer.get() & 0xFF,        // MONTH (1 BYTE, 1-12)
                buffer.get() & 0xFF,        // DAY (1 BYTE, 1-31)
                buffer.get() & 0xFF,        // HOUR (1 BYTE, 0-23)
                buffer.get() & 0xFF,        // MINUTE (1 BYTE, 0-59)
                buffer.get() & 0xFF         // SECOND (1 BYTE, 0-60, 60 FOR LEAP SECOND)
        );
    }

    /** Returns the modification time as a LocalDateTime, every field clamped to its valid range. */
    public LocalDateTime toLocalDateTime() {
        // CLAMP EVERY FIELD: THE CHUNK CARRIES RAW BYTES, AND LocalDateTime.of THROWS DateTimeException
        // ON ANYTHING OUT OF RANGE (LEAP SECOND 60, MONTH 0, DAY 31 IN FEBRUARY, ...)
        final int m = Math.min(Math.max(this.month, 1), 12);
        final int d = Math.min(Math.max(this.day, 1), YearMonth.of(this.year, m).lengthOfMonth());
        return LocalDateTime.of(this.year, m, d, Math.min(this.hour, 23), Math.min(this.minute, 59), Math.min(this.second, 59));
    }

    /** Returns the modification time as a UTC ZonedDateTime. */
    public ZonedDateTime toZonedDateTime() {
        return this.toLocalDateTime().atZone(ZoneOffset.UTC);
    }

    /** Returns the clamped modification time as an ISO 8601 UTC string. */
    public String toISO8601() {
        return this.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
    }

    @Override
    public byte[] toBytes() {
        final ByteBuffer buf = ByteBuffer.allocate(LENGTH).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) this.year);
        buf.put((byte) this.month);
        buf.put((byte) this.day);
        buf.put((byte) this.hour);
        buf.put((byte) this.minute);
        buf.put((byte) this.second);
        return buf.array();
    }

    @Override
    public PngChunk toChunk() {
        return PngChunk.create(SIGNATURE, this.toBytes());
    }
}
