package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.LodLevel;
import org.watermedia.api.player.MediaPlayer.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the control surface every {@link MediaPlayer} shares, through {@link ServerMediaPlayer} since the sealed class allows no stub. */
@DisplayName("MediaPlayer base surface")
public class MediaPlayerBaseTest {

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("volume")
    class VolumeTests {

        @Test
        @DisplayName("volume round-trips for every 0..100 value")
        void volumeRoundTrip() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            // THE STORED GAIN TIMES 100 CAN FALL JUST SHORT OF THE INPUT (0.53f * 100 = 52.9999f), SO ONLY ROUNDING RECOVERS IT
            for (int v = 0; v <= 100; v++) {
                player.volume(v);
                assertEquals(v, player.volume(), "round-trip failed at " + v);
            }
        }

        @Test
        @DisplayName("volume clamps out-of-range values")
        void volumeClamps() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(150);
            assertEquals(100, player.volume());
            player.volume(-10);
            assertEquals(0, player.volume());
        }
    }

    @Nested
    @DisplayName("mute")
    class MuteTests {

        @Test
        @DisplayName("mute is independent from volume")
        void muteIsOrthogonal() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(50);
            player.mute(true);
            assertTrue(player.mute());

            // CHANGING VOLUME MUST NOT CLEAR THE MUTE FLAG
            player.volume(80);
            assertTrue(player.mute());
            assertEquals(80, player.volume());

            player.mute(false);
            assertFalse(player.mute());
        }

        @Test
        @DisplayName("volume 0 does not implicitly mute")
        void volumeZeroDoesNotMute() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(0);
            assertFalse(player.mute());
        }
    }

    @Nested
    @DisplayName("speed")
    class SpeedTests {

        @Test
        @DisplayName("live source cannot change speed")
        void liveLocksSpeed() {
            // AN UNKNOWN DURATION ONLY HOLDS THE CLOCK; ONLY AN EXPLICIT LIVE FLAG LOCKS THE RATE
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertTrue(player.canSpeed(), "unknown duration must not lock speed");
            player.live(true);
            assertFalse(player.canSpeed());
            assertFalse(player.speed(2.0f));
            // RE-REQUESTING THE CURRENT SPEED IS A SUCCESSFUL NO-OP EVEN WHEN LOCKED
            assertTrue(player.speed(1.0f));
            assertEquals(1.0f, player.speed());
        }

        @Test
        @DisplayName("bounded speed changes apply on a non-live source")
        void speedBounds() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertTrue(player.speed(2.0f));
            assertEquals(2.0f, player.speed());
            assertTrue(player.speed(4.0f));

            // OUT OF RANGE: REJECTED, SPEED UNCHANGED
            assertFalse(player.speed(0f));
            assertFalse(player.speed(-1f));
            assertFalse(player.speed(4.1f));
            assertEquals(4.0f, player.speed());
        }
    }

    @Nested
    @DisplayName("repeat / scale / lod")
    class StateTests {

        @Test
        @DisplayName("repeat toggles and reports the new state")
        void repeatToggles() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertFalse(player.repeat());
            assertTrue(player.repeat(true));
            assertTrue(player.repeat());
        }

        @Test
        @DisplayName("scale stores the cap and rejects a negative axis")
        void scaleValidation() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.scale(160, 90);
            assertEquals(160, player.scaleWidth());
            assertEquals(90, player.scaleHeight());

            // 0 MEANS UNLIMITED (NO_SIZE)
            player.scale(0, 0);
            assertEquals(MediaPlayer.NO_SIZE, player.scaleWidth());
            assertThrows(IllegalArgumentException.class, () -> player.scale(-1, 10));
            assertThrows(IllegalArgumentException.class, () -> player.scale(10, -1));
        }

        @Test
        @DisplayName("lod defaults to MAX and rejects null")
        void lodValidation() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertEquals(LodLevel.MAX, player.lod());
            player.lod(LodLevel.NEAR);
            assertEquals(LodLevel.NEAR, player.lod());
            assertThrows(IllegalArgumentException.class, () -> player.lod(null));
        }

        @Test
        @DisplayName("scaled keeps the native size when nothing reduces it")
        void scaledKeepsNative() {
            assertEquals(1_081, MediaPlayer.scaled(1_081, 0, 100));
            assertEquals(1_081, MediaPlayer.scaled(1_081, 4_096, 100));
            assertEquals(0, MediaPlayer.scaled(0, 720, 50), "an unknown size stays unknown");
        }

        @Test
        @DisplayName("scaled caps, applies the percentage and keeps the result even")
        void scaledCapsAndEvens() {
            assertEquals(720, MediaPlayer.scaled(1_080, 720, 100));
            assertEquals(540, MediaPlayer.scaled(1_080, 0, 50));
            assertEquals(10, MediaPlayer.scaled(1_080, 0, 1));
            assertEquals(2, MediaPlayer.scaled(100, 0, 1));
            assertEquals(360, MediaPlayer.scaled(1_080, 720, 50), "the percentage applies to the capped size");
        }
    }

    @Nested
    @DisplayName("Status")
    class StatusTests {

        @Test
        @DisplayName("of maps every ordinal and rejects the ones outside the enum")
        void ofCoversFullRange() {
            for (final Status s: Status.values()) {
                assertEquals(s, Status.of(s.ordinal()));
            }
            assertThrows(IllegalArgumentException.class, () -> Status.of(-1));
            assertThrows(IllegalArgumentException.class, () -> Status.of(Status.values().length));
        }

        @Test
        @DisplayName("allows only direct moves, never the status itself, null or anything out of STOPPED")
        void allowsDirectMovesOnly() {
            for (final Status s: Status.values()) {
                assertFalse(s.allows(s), s + " allows itself");
                assertFalse(s.allows(null), s + " allows null");
                assertFalse(Status.STOPPED.allows(s), "STOPPED allows " + s);
            }
            assertTrue(Status.WAITING.allows(Status.LOADING));
            assertTrue(Status.PAUSED.allows(Status.ENDED), "a pipeline drained while paused still ends");
            assertTrue(Status.BUFFERING.allows(Status.PAUSED));
            assertTrue(Status.ERROR.allows(Status.STOPPED));
            assertFalse(Status.LOADING.allows(Status.PLAYING), "LOADING leaves only through a start");
            assertFalse(Status.PLAYING.allows(Status.LOADING), "a source switch takes its own path");
            assertFalse(Status.ENDED.allows(Status.PAUSED), "a seek past the end restarts the player");
            assertFalse(Status.ERROR.allows(Status.PLAYING), "only a stop leaves a failure");
        }
    }

    @Nested
    @DisplayName("Release failure merging")
    class FailureTests {

        @Test
        @DisplayName("Preserves the first ordinary cause and avoids self-suppression")
        void preservesFirstOrdinaryCauseAndAvoidsSelfSuppression() {
            final Throwable first = new IllegalStateException("first");
            final Throwable next = new IllegalArgumentException("next");
            assertSame(first, MediaPlayer.mergeFailure(null, first));
            assertSame(first, MediaPlayer.mergeFailure(first, first));
            assertSame(first, MediaPlayer.mergeFailure(first, next));
            assertSame(next, first.getSuppressed()[0]);
            assertEquals(1, first.getSuppressed().length);
        }

        @Test
        @DisplayName("Errors win over exceptions and fatal VM errors win over everything")
        void fatalErrorsWin() {
            final Throwable ordinary = new IllegalStateException("ordinary");
            final Throwable assertion = new AssertionError("assertion");
            final Throwable fatal = new OutOfMemoryError("fatal");
            assertSame(assertion, MediaPlayer.mergeFailure(ordinary, assertion));
            assertSame(fatal, MediaPlayer.mergeFailure(assertion, fatal));
            assertSame(fatal, MediaPlayer.mergeFailure(fatal, new StackOverflowError()));
        }
    }
}
