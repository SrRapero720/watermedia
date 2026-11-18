package org.watermedia.api.player;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.IdleOrigin;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies that {@link FFMediaPlayer} resumes an HLS VOD whose origin reset the segment download a long pause left idle, as
 * googlevideo does (watermedia#275). Its segments come like YouTube's, chunked and without ranges, so FFmpeg cannot resume
 * the cut download and used to skip the rest of the segment, freezing the picture until the clock reached the next one; a
 * resume after a long hold now reads the input again from the paused frame, and a short pause resumes in place.
 */
@DisplayName("FFMediaPlayer resume after a hold")
public class FFHoldResumeTest {

    // TIMEOUTS
    private static final long MRL_TIMEOUT_MS = 3_000L;
    private static final long LOAD_TIMEOUT_MS = 15_000L;
    private static final long CUT_TIMEOUT_MS = 30_000L;
    private static final long OBSERVE_MS = 8_000L;          // LONGER THAN THE FREEZE A SKIPPED SEGMENT REST LEAVES
    private static final int ATTEMPTS = 3;                  // A CLOCK-DEPENDENT CASE GETS TWO MORE TRIES ON A STALLED HOST
    private static final long RETRY_MS = 5_000L;
    // ORIGIN: IDLE_MS OUTLASTS THE SEGMENT FETCHED AHEAD, WHICH WAITS UNREAD WHILE THE CURRENT ONE DOWNLOADS
    private static final long IDLE_MS = 5_000L;
    private static final long PATIENT_IDLE_MS = 60_000L;    // NEVER CUTS A SHORT PAUSE
    private static final int PACE = 2;                      // A SEGMENT GOES AT TWICE ITS BITRATE, AS YOUTUBE PACES MEDIA
    // PLAYER
    private static final long HOLD_MS = 10_000L;            // FFMediaPlayer.REREAD_HOLD_MS: THE HOLD PAST WHICH A RESUME READS AGAIN
    private static final long SEEK_MS = 5_000L;             // ONE SECOND INTO THE SECOND SEGMENT
    private static final long LANDING_TOLERANCE_MS = 250L;  // THE RESUME LANDS ON THE PAUSED FRAME, NOT A SEGMENT AWAY
    private static final long SHORT_PAUSE_MS = 500L;        // A PAUSE THAT LASTS, FAR BELOW THE HOLD
    private static final long SHORT_OBSERVE_MS = 3_000L;    // THE WINDOW A SPURIOUS REREAD WOULD SHOW IN, AS BUFFERING AND A NEW REQUEST
    private static final double RENDER_FLOOR = 0.5;         // A FROZEN PICTURE RENDERS A FEW FRAMES OF THE WINDOW, A LIVE ONE NEARLY ALL
    // MEDIA: A SEGMENT OUTGROWS THE SOCKET BUFFERS, SO THE CUT LANDS MID-DOWNLOAD; TWO SEGMENTS STAY UNDER
    // FFDecoder.PTS_DISCONTINUITY_SEC, PAST WHICH THE LOSS WOULD SHOW AS A CUT IN THE CONTENT, NOT AS A FROZEN PICTURE
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final int FPS = 25;
    private static final int SEGMENT_SECONDS = 4;
    private static final int SEGMENTS = 4;
    private static final int QSCALE = 2;
    private static final long SEGMENT_FLOOR_BYTES = 4L * 1_024 * 1_024;

    @TempDir
    static Path media;
    private static final List<byte[]> segments = new ArrayList<>();
    private static long bytesPerSec;

    private boolean cache; // THE PROCESS-WIDE CACHE SWITCH AS THE CASE FOUND IT

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() throws Exception {
        assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable, skipping FFMediaPlayer hold resume tests");
        long total = 0;
        for (final Path file: Fixtures.transportSegments(media, WIDTH, HEIGHT, FPS, SEGMENT_SECONDS, SEGMENTS, QSCALE)) {
            final byte[] body = Files.readAllBytes(file);
            assertTrue(body.length > SEGMENT_FLOOR_BYTES, "a segment must outgrow the socket buffers, it has " + body.length + " bytes");
            segments.add(body);
            total += body.length;
        }
        bytesPerSec = total / (SEGMENTS * SEGMENT_SECONDS);
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

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // WAITS UNTIL THE ORIGIN CUT EVERY CONNECTION THE PAUSE LEFT IDLE AND THE PAUSE MADE AT pausedNs OUTLASTED THE HOLD
    private static void holdPastTheCut(final Playback playback, final long pausedNs) throws InterruptedException {
        assertTrue(PlayerWait.awaitCondition(() -> playback.origin().connections() == 0, CUT_TIMEOUT_MS), "the origin must cut every idle connection");
        assertTrue(playback.origin().cuts() > 0, "the origin must cut the stalled segment downloads");
        final long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pausedNs);
        if (heldMs <= HOLD_MS) Thread.sleep(HOLD_MS - heldMs + 1);
    }

    // THE RENDERING CHECK A STALLED HOST MAY RETRY: RESUMED, THE VIDEO KEEPS PLAYING FROM THE PAUSED FRAME THROUGH THE
    // WINDOW, BRACKETED AS THE FRAMES UPLOADED THERE AGAINST THE FRAMES ITS WALL TIME HOLDS
    private static void resumesPlaying(final Playback playback) throws InterruptedException {
        final FFMediaPlayer player = playback.player();
        final HeadlessGFXEngine gfx = playback.gfx();
        final IdleOrigin origin = playback.origin();
        playback.heard().clear();
        final long pausedMs = player.time();
        final long from = gfx.uploadCount();
        final long started = System.nanoTime();
        assertTrue(player.resume(), "the resume must change the player");
        final boolean moved = PlayerWait.awaitCondition(() -> gfx.uploadCount() > from, OBSERVE_MS);
        final long firstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        final long landedMs = player.time();
        final long restMs = OBSERVE_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (restMs > 0) Thread.sleep(restMs);
        final long to = gfx.uploadCount();
        final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        final long minimum = (long) (elapsedMs * FPS / 1_000.0 * RENDER_FLOOR);
        // THE WINDOW ENDS FAR BEFORE THE MEDIA DOES: A RUN THAT ENDED THERE SKIPPED PART OF IT
        final Status status = player.status();
        assertTrue(moved && to - from >= minimum && status == Status.PLAYING && Math.abs(landedMs - pausedMs) <= LANDING_TOLERANCE_MS,
                () -> "the video must keep playing from the paused frame: " + (to - from) + " frames in " + elapsedMs + " ms (first after "
                        + firstMs + " ms at " + landedMs + " ms, paused at " + pausedMs + " ms), at least " + minimum + ", landed " + status
                        + "; statuses " + playback.heard() + ", segment requests " + origin.requests("/segment0.ts") + "/"
                        + origin.requests("/segment1.ts") + "/" + origin.requests("/segment2.ts"));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Long hold")
    class LongHold {

        @Test
        @DisplayName("A video paused mid-segment until the origin reset its download keeps playing from the paused frame right after the resume")
        void pausedPlayback() throws Exception {
            AssertionError failure = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) Thread.sleep(RETRY_MS);
                try (final Playback playback = Playback.open(IDLE_MS)) {
                    final FFMediaPlayer player = playback.player();
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the video must start, landed " + player.status());
                    // PAUSED RIGHT AFTER A SEEK INTO THE SECOND SEGMENT: IT STALLS MID-DOWNLOAD WITH THE THIRD, FETCHED AHEAD
                    assertTrue(player.seek(SEEK_MS));
                    assertTrue(PlayerWait.awaitCondition(() -> player.playing() && player.time() >= SEEK_MS, LOAD_TIMEOUT_MS),
                            () -> "the seek must land, at " + player.time() + " ms " + player.status());
                    assertTrue(player.pause());
                    holdPastTheCut(playback, System.nanoTime());
                    try {
                        resumesPlaying(playback);
                        return;
                    } catch (final AssertionError e) {
                        failure = e;
                    }
                }
            }
            throw failure;
        }

        @Test
        @DisplayName("A video started paused and resumed once the origin reset its first segment download plays from its start")
        void pausedLaunch() throws Exception {
            AssertionError failure = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) Thread.sleep(RETRY_MS);
                try (final Playback playback = Playback.open(IDLE_MS)) {
                    final FFMediaPlayer player = playback.player();
                    // THE DEMUXER PARKS RIGHT AFTER THE OPEN, WITH THE FIRST SEGMENT READ ONLY AS FAR AS THE PROBE WENT
                    assertTrue(player.startPaused());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "the video must open paused, landed " + player.status());
                    holdPastTheCut(playback, System.nanoTime());
                    try {
                        resumesPlaying(playback);
                        return;
                    } catch (final AssertionError e) {
                        failure = e;
                    }
                }
            }
            throw failure;
        }
    }

    @Nested
    @DisplayName("Short pause")
    class ShortPause {

        @Test
        @DisplayName("A pause shorter than the hold threshold resumes straight to PLAYING, without asking for the segment again")
        void resumesInPlace() throws Exception {
            try (final Playback playback = Playback.open(PATIENT_IDLE_MS)) {
                final FFMediaPlayer player = playback.player();
                final HeadlessGFXEngine gfx = playback.gfx();
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the video must start, landed " + player.status());
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, LOAD_TIMEOUT_MS), "the video must upload frames");
                assertTrue(player.pause());
                Thread.sleep(SHORT_PAUSE_MS);
                playback.heard().clear();
                final long from = gfx.uploadCount();
                assertTrue(player.resume(), "the resume must change the player");
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > from, SHORT_OBSERVE_MS), "the video must keep playing");
                Thread.sleep(SHORT_OBSERVE_MS);
                assertEquals(0, playback.origin().cuts(), "the patient origin must keep every connection");
                assertEquals(List.of(Status.PLAYING), playback.heard(), "a short pause resumes straight to PLAYING");
                assertEquals(1, playback.origin().requests("/segment0.ts"), "a short pause never asks for the segment again");
            }
        }
    }

    // THE HLS VOD ON A FRESH ORIGIN, THE PLATFORM THAT RESOLVES IT AND A VIDEO-ONLY PLAYER RECORDING EVERY STATUS IT HEARS;
    // CLOSING RELEASES THE PLAYER, UNREGISTERS THE PLATFORM AND CLOSES THE ORIGIN
    private record Playback(IdleOrigin origin, Platform platform, FFMediaPlayer player, HeadlessGFXEngine gfx, List<Status> heard) implements AutoCloseable {

        // THE PLAYLIST GOES AS A FILE, THE SEGMENTS AS YOUTUBE SENDS THEM. THE PLATFORM RESOLVES ITS OWN HOST TO THE PLAYLIST
        // WITHOUT A REQUEST, SO THE ORIGIN SEES ONLY THE PLAYER
        private static Playback open(final long idleMs) {
            final IdleOrigin origin = IdleOrigin.resetting(idleMs);
            final StringBuilder list = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SEGMENT_SECONDS
                    + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
            for (int i = 0; i < segments.size(); i++) {
                origin.stream("/segment" + i + ".ts", "video/mp2t", segments.get(i), bytesPerSec * PACE);
                list.append("#EXTINF:").append(SEGMENT_SECONDS).append(".000,\nsegment").append(i).append(".ts\n");
            }
            origin.serve("/media.m3u8", "application/vnd.apple.mpegurl", list.append("#EXT-X-ENDLIST\n").toString().getBytes(StandardCharsets.UTF_8));
            final String host = "p" + origin.uri("/").getPort();
            final URI playlist = origin.uri("/media.m3u8");
            final Platform platform = new Platform() {
                @Override public String name() { return "FFHoldResumeTest-" + host; }

                @Override
                public Resolution resolve(final URI uri) {
                    if (!"wmtest-hold".equals(uri.getScheme()) || !host.equals(uri.getHost())) return null;
                    return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(), List.of(new Quality(playlist, 0, 0)), List.of(), null));
                }
            };
            PlatformAPI.register(platform);
            try {
                final MRL mrl = PlayerAPI.mrl(URI.create("wmtest-hold://" + host + "/" + System.nanoTime()));
                assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL must resolve");
                assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, gfx, null);
                final List<Status> heard = new CopyOnWriteArrayList<>();
                player.onStatus((source, previous, current) -> heard.add(current));
                return new Playback(origin, platform, player, gfx, heard);
            } catch (final RuntimeException | Error e) {
                PlatformAPI.unregister(platform);
                origin.close();
                throw e;
            }
        }

        @Override
        public void close() {
            try {
                this.player.release();
            } finally {
                PlatformAPI.unregister(this.platform);
                this.origin.close();
            }
        }
    }
}
