package org.watermedia.api.player;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.player.MediaPlayer.AudioTrack;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@link MediaPlayer} audio track surface on {@link FFMediaPlayer}: the language preference, the list of the
 * streams each opened input carries (separate tracks provisional until opened), the pick and its hot switch at the current
 * time, which survives restarts; and the empty surface of players without audio tracks. Audio plays muted through Java Sound.
 */
@DisplayName("FFMediaPlayer audio tracks")
public class FFAudioTracksTest {
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long LANDING_MS = 1_500L; // HOW FAR A SWITCH MAY LAND FROM THE POSITION IT LEFT
    private static final int RATE_HZ = 48_000;
    private static final int SECONDS = 10;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer audio track tests");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static MRL resolve(final URI uri) {
        final MRL mrl = PlayerAPI.mrl(uri);
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
        return mrl;
    }

    // A MUTED AUDIO-ONLY PLAYER ON JAVA SOUND, OR AN ABORTED TEST WHERE NO LINE TAKES THE FIXTURES' MONO S16
    private static FFMediaPlayer player(final MRL mrl) {
        final JSEngine engine = PlayerAPI.jsEngine();
        if (!engine.format(SampleType.S16, 1, RATE_HZ)) {
            engine.release();
            Assumptions.abort("no Java Sound line plays mono S16 at " + RATE_HZ + " Hz");
        }
        final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, null, engine);
        player.mute(true);
        return player;
    }

    // TWO TAGGED STREAMS, THE SPANISH ONE FLAGGED DEFAULT
    private static Path dubbed(final Path directory) throws Exception {
        final Path file = directory.resolve("dubbed.mka");
        Fixtures.dubbed(file, RATE_HZ, SECONDS, 1, "eng", "spa");
        return file;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Streams of the main input")
    class Embedded {

        @Test
        @DisplayName("The preferred language picks the stream, a pick switches the audio at the current time and survives a restart")
        void preferenceThenHotSwitch(@TempDir final Path directory) throws Exception {
            final Path file = directory.resolve("dubbed.mka");
            Fixtures.dubbed(file, RATE_HZ, SECONDS, 1, "eng", "spa", "fre");
            final FFMediaPlayer player = player(resolve(file.toUri()));
            try {
                player.audioLanguage(" fr ");
                assertEquals("fr", player.audioLanguage());
                assertEquals(List.of(), player.audioTracks(), "the main input's streams are unknown until a run opens it");
                assertNull(player.audioTrack());

                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "landed " + player.status());
                final AudioTrack french = new AudioTrack(AudioTrack.MAIN, 2, "Audio 2", "fre");
                assertEquals(french, player.audioTrack(), "the preferred language outranks the default stream");
                assertEquals(List.of(new AudioTrack(AudioTrack.MAIN, 0, "Audio 0", "eng"), new AudioTrack(AudioTrack.MAIN, 1, "Audio 1", "spa"), french),
                        player.audioTracks());

                assertTrue(PlayerWait.awaitCondition(() -> player.time() >= 1_000L, LOAD_TIMEOUT_MS), "playback must advance");
                final AudioTrack english = player.audioTracks().get(0);
                final long before = player.time();
                assertTrue(player.audioTrack(english));
                assertFalse(player.audioTrack(english), "the pick is already this entry");
                assertFalse(player.audioTrack(new AudioTrack(AudioTrack.MAIN, 7, null, null)), "an entry the list lacks is unknown");
                assertTrue(PlayerWait.awaitCondition(() -> english.equals(player.audioTrack()) && player.playing(), LOAD_TIMEOUT_MS),
                        () -> "the switch must land, playing " + player.audioTrack() + " " + player.status());
                assertTrue(player.time() >= before - LANDING_MS, "the switch resumes where playback was, not at " + player.time());

                // THE PICK OUTRANKS THE PREFERENCE ON A RESTART TOO
                assertTrue(player.stop());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED));
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                assertEquals(english, player.audioTrack());
            } finally {
                player.release();
            }
            assertFalse(player.audioTrack(player.audioTracks().get(1)), "a released player switches nothing");
        }

        @Test
        @DisplayName("A pick made before start plays from the first open")
        void pickBeforeStart(@TempDir final Path directory) throws Exception {
            final FFMediaPlayer player = player(resolve(dubbed(directory).toUri()));
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                assertEquals(1, player.audioTrack().stream(), "the default stream plays without a preference");
                assertTrue(player.stop());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.STOPPED));

                final AudioTrack english = player.audioTracks().get(0);
                assertTrue(player.audioTrack(english), "a stopped player keeps the pick for its next start");
                assertEquals(1, player.audioTrack().stream(), "nothing switches until it starts");
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                assertEquals(english, player.audioTrack());
            } finally {
                player.release();
            }
        }
    }

    @Nested
    @DisplayName("Separate tracks")
    class Separate {

        @Test
        @DisplayName("Separate tracks list provisionally until opened, play over the main input's audio, and a provisional pick resolves to its stream")
        void provisionalTracks(@TempDir final Path directory) throws Exception {
            final URI main = dubbed(directory).toUri();
            final Path german = directory.resolve("german.wav");
            final Path french = directory.resolve("french.wav");
            Fixtures.wav(german, RATE_HZ, 1, Fixtures.tone((short) 7_000, RATE_HZ * SECONDS));
            Fixtures.wav(french, RATE_HZ, 1, Fixtures.tone((short) 8_000, RATE_HZ * SECONDS));
            final List<Track> tracks = List.of(new Track("Deutsch", "de", german.toUri()), new Track("Français", "fr", french.toUri()));
            // A PLATFORM SOURCE LIKE A DUBBED VIDEO'S: THE QUALITY CARRIES ITS OWN AUDIO, THE TRACKS COME APART, ORIGINAL FIRST
            final Platform platform = new Platform() {
                @Override public String name() { return "AudioTracksTest"; }

                @Override
                public Resolution resolve(final URI uri) {
                    if (!"wmtest-tracks".equals(uri.getScheme())) return null;
                    return new Resolution(null, new Source(MediaType.AUDIO, null, null, new HttpHeaders(), List.of(new Quality(main, 0, 0)), tracks, null));
                }
            };
            PlatformAPI.register(platform);
            try {
                final FFMediaPlayer player = player(resolve(URI.create("wmtest-tracks://dub/" + System.nanoTime())));
                try {
                    final AudioTrack germanProvisional = new AudioTrack(0, -1, "Deutsch", "de");
                    final AudioTrack frenchProvisional = new AudioTrack(1, -1, "Français", "fr");
                    assertEquals(List.of(germanProvisional, frenchProvisional), player.audioTracks(), "no track opens before a run");
                    assertTrue(frenchProvisional.provisional());

                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                    final AudioTrack germanOpened = new AudioTrack(0, 0, "Deutsch", "de");
                    assertEquals(germanOpened, player.audioTrack(), "the first separate track plays over the main input's audio");
                    assertEquals(List.of(new AudioTrack(AudioTrack.MAIN, 0, "Audio 0", "eng"), new AudioTrack(AudioTrack.MAIN, 1, "Audio 1", "spa"),
                            germanOpened, frenchProvisional), player.audioTracks(), "only the opened track lists its stream");

                    assertTrue(player.audioTrack(frenchProvisional));
                    final AudioTrack frenchOpened = new AudioTrack(1, 0, "Français", "fr");
                    assertTrue(PlayerWait.awaitCondition(() -> frenchOpened.equals(player.audioTrack()) && player.playing(), LOAD_TIMEOUT_MS),
                            () -> "the provisional pick must resolve, playing " + player.audioTrack());
                    assertEquals(List.of(new AudioTrack(AudioTrack.MAIN, 0, "Audio 0", "eng"), new AudioTrack(AudioTrack.MAIN, 1, "Audio 1", "spa"),
                            germanOpened, frenchOpened), player.audioTracks(), "an opened track keeps its streams for the player's life");
                    assertFalse(player.audioTrack(frenchOpened), "the stream the provisional pick resolved to is already playing");
                    assertFalse(player.audioTrack(frenchProvisional), "the provisional entry left the list");

                    final AudioTrack spanish = player.audioTracks().get(1);
                    assertTrue(player.audioTrack(spanish), "the main input's own audio is a pick too");
                    assertTrue(PlayerWait.awaitCondition(() -> spanish.equals(player.audioTrack()) && player.playing(), LOAD_TIMEOUT_MS),
                            () -> "the main input pick must land, playing " + player.audioTrack());
                } finally {
                    player.release();
                }
            } finally {
                PlatformAPI.unregister(platform);
            }
        }

        @Test
        @DisplayName("The preferred language picks among the separate tracks")
        void preferenceAmongTracks(@TempDir final Path directory) throws Exception {
            final URI main = dubbed(directory).toUri();
            final Path german = directory.resolve("german.wav");
            final Path spanish = directory.resolve("spanish.wav");
            Fixtures.wav(german, RATE_HZ, 1, Fixtures.tone((short) 7_000, RATE_HZ * SECONDS));
            Fixtures.wav(spanish, RATE_HZ, 1, Fixtures.tone((short) 9_000, RATE_HZ * SECONDS));
            final List<Track> tracks = List.of(new Track("Deutsch", "de-DE", german.toUri()), new Track("Español", "es-419", spanish.toUri()));
            final Platform platform = new Platform() {
                @Override public String name() { return "AudioPreferenceTest"; }

                @Override
                public Resolution resolve(final URI uri) {
                    if (!"wmtest-preference".equals(uri.getScheme())) return null;
                    return new Resolution(null, new Source(MediaType.AUDIO, null, null, new HttpHeaders(), List.of(new Quality(main, 0, 0)), tracks, null));
                }
            };
            PlatformAPI.register(platform);
            try {
                final FFMediaPlayer player = player(resolve(URI.create("wmtest-preference://dub/" + System.nanoTime())));
                try {
                    player.audioLanguage("es-MX");
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING));
                    assertEquals(new AudioTrack(1, 0, "Español", "es-419"), player.audioTrack(), "es-MX takes the es-419 track over the original");
                } finally {
                    player.release();
                }
            } finally {
                PlatformAPI.unregister(platform);
            }
        }
    }

    @Nested
    @DisplayName("Players without audio tracks")
    class Without {

        @Test
        @DisplayName("A player without an audio engine lists nothing and switches nothing")
        void noAudioEngine() {
            final FFMediaPlayer player = new FFMediaPlayer(resolve(Fixtures.fileUri(Fixtures.MP4_H264)), 0, new HeadlessGFXEngine(false), null);
            try {
                assertEquals(List.of(), player.audioTracks());
                assertNull(player.audioTrack());
                assertFalse(player.audioTrack(new AudioTrack(AudioTrack.MAIN, 0, null, null)));
                assertThrows(IllegalArgumentException.class, () -> player.audioTrack(null));
            } finally {
                player.release();
            }
        }

        @Test
        @DisplayName("A server clock keeps the preference but lists and switches nothing")
        void serverClock() {
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
}
