package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.Status;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the {@link MediaPlayer.PlayerState} wire form: byte-exact round trips from any buffer and rejections that consume no byte. */
@DisplayName("PlayerState snapshot")
public class PlayerStateTest {

    // BIG-ENDIAN WIRE OFFSETS, AFTER THE LEADING VERSION BYTE
    private static final int REVISION_OFFSET = 1;
    private static final int STATUS_OFFSET = 5;
    private static final int TIME_OFFSET = 6;
    private static final int DURATION_OFFSET = 14;
    private static final int SPEED_OFFSET = 22;
    private static final int FLAGS_OFFSET = 26;

    // A HOST FRAME AROUND THE SNAPSHOT: A LEADING CHANNEL ID AND A TRAILING ROUTING FIELD
    private static final int HEADER_BYTES = Integer.BYTES;
    private static final int CHANNEL = 99;
    private static final long TRAILER = 0xCAFE_BABEL;

    private static final MediaPlayer.PlayerState SAMPLE = new MediaPlayer.PlayerState(1, Status.PLAYING, 0L, 1_000L, 1f, false, false); // VALID, CORRUPTED FIELD BY FIELD

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE ENCODED SNAPSHOT AS A PLAIN ARRAY, FREE TO CORRUPT
    private static byte[] bytes(final MediaPlayer.PlayerState playerState) {
        final byte[] wire = new byte[MediaPlayer.PlayerState.BYTES];
        playerState.toBytes().get(wire);
        return wire;
    }

    private static void roundTrip(final MediaPlayer.PlayerState playerState) {
        final ByteBuffer wire = playerState.toBytes();
        assertEquals(MediaPlayer.PlayerState.BYTES, wire.remaining(), "unexpected payload size for " + playerState);
        assertEquals(playerState, MediaPlayer.PlayerState.read(wire));
        assertEquals(0, wire.remaining(), "the decode consumes the whole payload");
    }

    private static void rejected(final ByteBuffer payload) {
        final int position = payload.position();
        assertThrows(IllegalArgumentException.class, () -> MediaPlayer.PlayerState.read(payload));
        assertEquals(position, payload.position(), "a rejected payload must not consume bytes");
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Round trips")
    class RoundTrips {

        @Test
        @DisplayName("Every status and flag combination round-trips")
        void everyStatusAndFlag() {
            for (final Status status: Status.values()) {
                for (int flags = 0; flags < 4; flags++)
                    roundTrip(new MediaPlayer.PlayerState(1_234, status, 987_654L, 3_600_000L, 1.75f, (flags & 1) != 0, (flags & 2) != 0));
            }
        }

        @Test
        @DisplayName("Extreme values survive the codec")
        void extremeValues() {
            roundTrip(new MediaPlayer.PlayerState(Integer.MAX_VALUE, Status.PLAYING, Long.MAX_VALUE, Long.MAX_VALUE, 4f, true, true));
            roundTrip(new MediaPlayer.PlayerState(Integer.MIN_VALUE, Status.WAITING, 0L, 0L, Float.MIN_VALUE, false, false));
            roundTrip(new MediaPlayer.PlayerState(-1, Status.ERROR, Long.MAX_VALUE, 0L, 0.01f, true, false));
        }

        @Test
        @DisplayName("The encoded form is big-endian with every field at a fixed offset")
        void wireLayout() {
            final MediaPlayer.PlayerState playerState = new MediaPlayer.PlayerState(0x0A0B_0C0D, Status.PAUSED, 0x0102_0304_0506_0708L, 0x1112_1314_1516_1718L, 1.5f, true, false);
            final ByteBuffer wire = playerState.toBytes();
            assertEquals(0, wire.position());
            assertEquals(MediaPlayer.PlayerState.BYTES, wire.remaining());
            assertEquals(ByteOrder.BIG_ENDIAN, wire.order());
            assertEquals(0x0A0B_0C0D, wire.getInt(REVISION_OFFSET));
            assertEquals(Status.PAUSED.ordinal(), wire.get(STATUS_OFFSET));
            assertEquals(0x0102_0304_0506_0708L, wire.getLong(TIME_OFFSET));
            assertEquals(0x1112_1314_1516_1718L, wire.getLong(DURATION_OFFSET));
            assertEquals(1.5f, wire.getFloat(SPEED_OFFSET));
            assertEquals(0b01, wire.get(FLAGS_OFFSET), "repeat is the low flag bit");
            assertEquals(0b10, new MediaPlayer.PlayerState(1, Status.PLAYING, 0L, 0L, 1f, false, true).toBytes().get(FLAGS_OFFSET), "live is the next one");
        }

        @Test
        @DisplayName("Decodes from little-endian heap, direct and read-only buffers and leaves their byte order alone")
        void anyBufferAnyOrder() {
            // BYTE-ASYMMETRIC VALUES: A DECODE THAT HONOURED THE CALLER'S ORDER WOULD READ EVERY FIELD SWAPPED
            final MediaPlayer.PlayerState playerState = new MediaPlayer.PlayerState(0x0A0B_0C0D, Status.PAUSED, 0x0102_0304L, 0x0506_0708L, 1.5f, true, false);
            final byte[] wire = bytes(playerState);
            final List<ByteBuffer> sources = List.of(
                    ByteBuffer.wrap(wire.clone()).order(ByteOrder.LITTLE_ENDIAN),
                    ByteBuffer.allocateDirect(MediaPlayer.PlayerState.BYTES).order(ByteOrder.LITTLE_ENDIAN).put(wire).flip(),
                    ByteBuffer.wrap(wire.clone()).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN));
            for (final ByteBuffer source: sources) {
                final String kind = source.getClass().getSimpleName();
                assertEquals(playerState, MediaPlayer.PlayerState.read(source), kind);
                assertEquals(0, source.remaining(), kind + ": the decode consumes exactly the snapshot");
                assertEquals(ByteOrder.LITTLE_ENDIAN, source.order(), kind + ": the caller's byte order survives the decode");
            }
        }

        @Test
        @DisplayName("Snapshots inside a larger frame decode one after another and leave the host's trailing bytes")
        void embeddedInFrame() {
            // HOSTS WRAP SNAPSHOTS IN THEIR OWN FRAME (CHANNEL, BLOCK POSITION); EACH DECODE TAKES ONLY ITS OWN BYTES
            final MediaPlayer.PlayerState first = new MediaPlayer.PlayerState(7, Status.PLAYING, 42_000L, 60_000L, 1f, false, false);
            final MediaPlayer.PlayerState second = new MediaPlayer.PlayerState(8, Status.PAUSED, 43_000L, 60_000L, 2f, true, false);
            final ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + 2 * MediaPlayer.PlayerState.BYTES + Long.BYTES)
                    .putInt(CHANNEL).put(first.toBytes()).put(second.toBytes()).putLong(TRAILER).flip();
            assertEquals(CHANNEL, frame.getInt());
            assertEquals(first, MediaPlayer.PlayerState.read(frame));
            assertEquals(HEADER_BYTES + MediaPlayer.PlayerState.BYTES, frame.position(), "the position lands right after the snapshot");
            assertEquals(second, MediaPlayer.PlayerState.read(frame));
            assertEquals(Long.BYTES, frame.remaining());
            assertEquals(TRAILER, frame.getLong(), "the host's trailing field survives the decode");
        }
    }

    @Nested
    @DisplayName("Rejections")
    class Rejections {

        @Test
        @DisplayName("Null and truncated payloads are rejected")
        void nullAndTruncated() {
            assertThrows(IllegalArgumentException.class, () -> MediaPlayer.PlayerState.read(null));
            rejected(ByteBuffer.allocate(0));
            rejected(ByteBuffer.wrap(bytes(SAMPLE), 0, MediaPlayer.PlayerState.BYTES - 1));
            // WHAT COUNTS IS A WHOLE SNAPSHOT PAST THE POSITION, NOT THE CAPACITY
            rejected(ByteBuffer.allocate(HEADER_BYTES + MediaPlayer.PlayerState.BYTES).position(HEADER_BYTES + 1));
        }

        @Test
        @DisplayName("A payload of another version is rejected, inside a frame too")
        void otherVersion() {
            final byte[] wire = bytes(SAMPLE);
            wire[0]++;
            rejected(ByteBuffer.wrap(wire));
            final ByteBuffer frame = ByteBuffer.allocate(HEADER_BYTES + MediaPlayer.PlayerState.BYTES).putInt(CHANNEL).put(wire).flip();
            assertEquals(CHANNEL, frame.getInt());
            rejected(frame);
        }

        @Test
        @DisplayName("An out-of-range status ordinal is rejected")
        void invalidStatus() {
            for (final int ordinal: new int[] { Status.values().length, 200, 255 }) {
                final byte[] wire = bytes(SAMPLE);
                wire[STATUS_OFFSET] = (byte) ordinal;
                rejected(ByteBuffer.wrap(wire));
            }
        }

        @Test
        @DisplayName("NaN, infinite and out-of-range speeds are rejected")
        void invalidSpeed() {
            for (final float speed: new float[] { Float.NaN, Float.POSITIVE_INFINITY, 0f, -0f, -1f, Math.nextUp(4f), 4.5f })
                rejected(ByteBuffer.wrap(bytes(SAMPLE)).putFloat(SPEED_OFFSET, speed));
        }

        @Test
        @DisplayName("Negative times and durations are rejected")
        void negativeTimes() {
            for (final int offset: new int[] { TIME_OFFSET, DURATION_OFFSET }) {
                rejected(ByteBuffer.wrap(bytes(SAMPLE)).putLong(offset, -1L));
                rejected(ByteBuffer.wrap(bytes(SAMPLE)).putLong(offset, Long.MIN_VALUE));
            }
        }

        @Test
        @DisplayName("The constructor rejects a null status, negative times and out-of-range speeds")
        void constructorValidation() {
            assertThrows(IllegalArgumentException.class, () -> new MediaPlayer.PlayerState(1, null, 0L, 0L, 1f, false, false));
            assertThrows(IllegalArgumentException.class, () -> new MediaPlayer.PlayerState(1, Status.PLAYING, -1L, 0L, 1f, false, false));
            assertThrows(IllegalArgumentException.class, () -> new MediaPlayer.PlayerState(1, Status.PLAYING, 0L, -1L, 1f, false, false));
            for (final float speed: new float[] { 0f, -1f, Math.nextUp(4f), Float.NaN, Float.POSITIVE_INFINITY })
                assertThrows(IllegalArgumentException.class, () -> new MediaPlayer.PlayerState(1, Status.PLAYING, 0L, 0L, speed, false, false), "speed " + speed);
        }
    }
}
