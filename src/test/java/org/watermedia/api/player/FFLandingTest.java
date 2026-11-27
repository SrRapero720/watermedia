package org.watermedia.api.player;

import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.SampleType;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LogCapture;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies how an {@link FFMediaPlayer} lands and holds its playback: a precise seek on a clip with sparse keyframes starts
 * sound and picture together on its target, and a picture that stops coming while its sound has plenty sends the player to
 * BUFFERING instead of letting the sound run on over a frozen picture.
 */
@DisplayName("FFMediaPlayer landing and buffering")
public class FFLandingTest {

    // TIMEOUTS
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long FROZEN_MS = 2_000L; // HOW LONG A BUFFERING CLOCK IS WATCHED FOR NOT MOVING
    // MEDIA: A KEYFRAME EVERY 2 s (FFEncoder.GOP_SECONDS), SO THE TARGET SITS 1.7 s PAST THE ONE ITS SEEK LANDS ON
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final int FPS = 25;
    private static final int SECONDS = 8;
    private static final int RATE_HZ = 48_000;
    private static final long TARGET_MS = 3_700L;
    private static final long SLACK_MS = 60L; // A VIDEO FRAME OR AN AUDIO SLOT
    // THE LANDING REPORT OF FFMediaPlayer
    private static final Pattern LANDED = Pattern.compile("Seek to (\\d+)ms landed at (-?\\d+)ms after \\d+ms \\(picture at (-?\\d+)ms, sound at (-?\\d+)ms");

    private boolean cache; // THE PROCESS-WIDE CACHE SWITCH AS THE CASE FOUND IT

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer landing tests");
    }

    // THE CACHE STAYS OUT OF FFMPEG'S REQUESTS
    @BeforeEach
    void configure() {
        this.cache = WaterMediaConfig.codecs.ffmpeg.cache;
        WaterMediaConfig.codecs.ffmpeg.cache = false;
    }

    @AfterEach
    void restore() {
        WaterMediaConfig.codecs.ffmpeg.cache = this.cache;
    }

    @Test
    @DisplayName("A precise seek between two keyframes starts sound and picture together on its target")
    void preciseSeekLandsTogether(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("clip.mkv");
        Fixtures.clip(file, Container.MKV, VideoCodec.H264, AudioCodec.OPUS, WIDTH, HEIGHT, FPS, SECONDS);
        final FFMediaPlayer player = new FFMediaPlayer(resolve(file.toUri()), 0, new HeadlessGFXEngine(false), engine(2));
        player.mute(true);
        try (final LogCapture capture = new LogCapture(WaterMedia.ID)) {
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the clip must start, landed " + player.status());
            assertTrue(player.seek(TARGET_MS));
            assertTrue(PlayerWait.awaitCondition(() -> landing(capture) != null, LOAD_TIMEOUT_MS), () -> "the seek must land, " + player.status());

            final Matcher landed = landing(capture);
            assertNotNull(landed);
            final long clock = Long.parseLong(landed.group(2));
            final long picture = Long.parseLong(landed.group(3));
            final long sound = Long.parseLong(landed.group(4));
            final String report = landed.group();
            // NOTHING OF THE KEYFRAME BEFORE THE TARGET PLAYS, AND THE CLOCK STARTS WHERE BOTH STREAMS HAVE SOMETHING
            assertTrue(picture > TARGET_MS - SLACK_MS && picture <= TARGET_MS, "the picture starts on the frame of the target: " + report);
            assertTrue(sound > TARGET_MS - SLACK_MS && sound <= TARGET_MS + SLACK_MS, "the sound starts on the target: " + report);
            assertEquals(Math.max(picture, sound), clock, "the clock starts where the later stream starts: " + report);
        } finally {
            player.release();
        }
    }

    @Test
    @DisplayName("A picture that stops coming while its separate sound track keeps plenty sends the player to BUFFERING, with the clock held")
    void starvedPictureHoldsTheSound(@TempDir final Path directory) throws Exception {
        // ABOUT HALF OF A 10 s VIDEO ON A CONNECTION THAT STAYS OPEN, AND ALL 10 s OF ITS SOUND IN A FILE
        final byte[] webm = Fixtures.webm(WIDTH, HEIGHT, 10 * FPS, FPS);
        final Path track = directory.resolve("track.wav");
        Fixtures.wav(track, RATE_HZ, 1, Fixtures.tone((short) 7_000, RATE_HZ * 10));
        try (final Origin origin = Origin.endless(Arrays.copyOf(webm, webm.length / 2), "video/webm")) {
            final URI main = origin.uri(".webm");
            final String host = "p" + main.getPort();
            final Platform platform = new Platform() {
                @Override public String name() { return "FFLandingTest-" + host; }

                @Override
                public Resolution resolve(final URI uri) {
                    if (!"wmtest-starve".equals(uri.getScheme()) || !host.equals(uri.getHost())) return null;
                    return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(), List.of(new Quality(main, 0, 0)),
                            List.of(new Track("Track", "en", track.toUri())), null));
                }
            };
            PlatformAPI.register(platform);
            try {
                final FFMediaPlayer player = new FFMediaPlayer(resolve(URI.create("wmtest-starve://" + host + "/" + System.nanoTime())), 0,
                        new HeadlessGFXEngine(false), engine(1));
                player.mute(true);
                final List<Status> heard = new CopyOnWriteArrayList<>();
                player.onStatus((source, previous, current) -> heard.add(current));
                try {
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the video must start, landed " + player.status());
                    heard.clear();
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.BUFFERING), () -> "the picture runs out, so the player must buffer: " + player.status());
                    final long held = player.time();
                    assertFalse(PlayerWait.awaitCondition(() -> player.status() != Status.BUFFERING || player.time() != held, FROZEN_MS),
                            () -> "the clock waits for the picture instead of running on with the sound: " + player.status() + " at " + player.time() + " ms, held at " + held + " ms");
                    assertTrue(held < 10_000L, "the player stopped before its sound ended, at " + held + " ms");
                    assertEquals(List.of(Status.BUFFERING), heard, "the player buffers once, it never alternates with PLAYING");
                } finally {
                    player.release();
                }
            } finally {
                PlatformAPI.unregister(platform);
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static MRL resolve(final URI uri) {
        final MRL mrl = PlayerAPI.mrl(uri);
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL must resolve");
        assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
        return mrl;
    }

    // A JAVA SOUND ENGINE FOR S16 AT 48 kHz, OR AN ABORTED TEST WHERE NO LINE TAKES IT
    private static JSEngine engine(final int channels) {
        final JSEngine engine = PlayerAPI.jsEngine();
        if (!engine.format(SampleType.S16, channels, RATE_HZ)) {
            engine.release();
            abort("no Java Sound line plays S16 with " + channels + " channels at " + RATE_HZ + " Hz");
        }
        return engine;
    }

    // THE LANDING REPORT OF THE SEEK TO TARGET_MS, OR null BEFORE IT LANDED
    private static Matcher landing(final LogCapture capture) {
        for (final LogEvent event: capture.events()) {
            final Matcher matcher = LANDED.matcher(event.getMessage().getFormattedMessage());
            if (matcher.find() && Long.parseLong(matcher.group(1)) == TARGET_MS) return matcher;
        }
        return null;
    }
}
