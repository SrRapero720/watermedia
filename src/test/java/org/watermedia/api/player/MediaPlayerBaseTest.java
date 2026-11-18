package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.player.MediaPlayer.AudioTrack;
import org.watermedia.api.player.MediaPlayer.LodLevel;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.SampleType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the control surface every {@link MediaPlayer} shares: volume and mute with the gain they leave on the audio
 * engine, the speed bounds, repeat, scale and LOD with their sizing rule, the audio preference of a clock, the
 * {@link Status} rules and the release failure merging. A {@link ServerMediaPlayer} stands in for the sealed class, which
 * allows no stub; the gain needs an audio engine, so that case drives an unstarted {@link FFMediaPlayer} over Java Sound.
 */
@DisplayName("MediaPlayer base surface")
public class MediaPlayerBaseTest {

    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final int RATE_HZ = 48_000;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        // THE AUDIO ENGINE AND THE FF PLAYER ARE CLIENT-ONLY
        MediaBootstrap.client();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Volume")
    class Volume {

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
    @DisplayName("Mute")
    class Mute {

        @Test
        @DisplayName("mute and volume are independent settings")
        void muteIsOrthogonal() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            player.volume(50);
            player.mute(true);
            assertTrue(player.mute());

            // CHANGING VOLUME MUST NOT CLEAR THE MUTE FLAG
            player.volume(80);
            assertTrue(player.mute(), "a volume change keeps the mute");
            assertEquals(80, player.volume());

            player.mute(false);
            assertFalse(player.mute());
        }

        @Test
        @DisplayName("Muting silences the audio engine, a volume change keeps it silent and unmuting plays at the latest volume")
        void muteGatesTheEngineGain() {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
            final JSEngine engine = PlayerAPI.jsEngine();
            final boolean opened = engine.format(SampleType.S16, 2, RATE_HZ);
            if (!opened) engine.release();
            assumeTrue(opened, "no Java Sound output line for S16 stereo");
            // NEVER STARTED: THE BASE CONTROLS DRIVE THE ENGINE GAIN THEMSELVES
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, null, engine);
            try {
                player.volume(50);
                assertEquals(0.5f, engine.volume(), "an unmuted volume reaches the engine");
                player.mute(true);
                assertEquals(0f, engine.volume(), "muting silences the engine");
                player.volume(80);
                assertEquals(0f, engine.volume(), "a volume change while muted keeps the engine silent");
                player.mute(false);
                assertEquals(0.8f, engine.volume(), "unmuting plays at the latest volume");
            } finally {
                player.release();
            }
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
    @DisplayName("Speed")
    class Speed {

        @Test
        @DisplayName("A live source refuses a new speed but accepts its current one")
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
        @DisplayName("Speeds within (0, 4] apply on a non-live source and the rest, NaN included, are refused")
        void speedBounds() {
            final ServerMediaPlayer player = new ServerMediaPlayer();
            assertTrue(player.speed(2.0f));
            assertEquals(2.0f, player.speed());
            assertTrue(player.speed(4.0f));

            // OUT OF RANGE: REJECTED, SPEED UNCHANGED
            assertFalse(player.speed(0f));
            assertFalse(player.speed(-1f));
            assertFalse(player.speed(4.1f));
            assertFalse(player.speed(Float.NaN), "NaN never reaches the clocks");
            assertEquals(4.0f, player.speed());
        }
    }

    @Nested
    @DisplayName("Settings: repeat, scale, LOD and audio")
    class Settings {

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

        @Test
        @DisplayName("A server clock keeps the audio language preference but lists and switches no track")
        void audioLanguageWithoutTracks() {
            final ServerMediaPlayer clock = PlayerAPI.serverPlayer();
            try {
                clock.audioLanguage("es-MX");
                assertEquals("es-MX", clock.audioLanguage());
                clock.audioLanguage("  ");
                assertNull(clock.audioLanguage(), "a blank tag is no preference");
                assertEquals(List.of(), clock.audioTracks());
                assertNull(clock.audioTrack());
                assertFalse(clock.audioTrack(new AudioTrack(AudioTrack.MAIN, 0, null, null)));
                assertThrows(IllegalArgumentException.class, () -> clock.audioTrack(null));
            } finally {
                clock.release();
            }
        }
    }

    @Nested
    @DisplayName("Status ordinals and direct transitions")
    class States {

        @Test
        @DisplayName("of maps every ordinal and rejects the ones outside the enum")
        void ofCoversFullRange() {
            for (final Status s: Status.values()) assertEquals(s, Status.of(s.ordinal()));
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
            assertFalse(Status.ENDED.allows(Status.PAUSED), "a seek out of ENDED takes its own path");
            assertFalse(Status.ERROR.allows(Status.PLAYING), "only a stop leaves a failure");
        }
    }

    @Nested
    @DisplayName("Release failure merging")
    class Failure {

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
