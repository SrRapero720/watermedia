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
    private static final long HOLD_MS = 10_000L;            // THE HOLD PAST WHICH FFMediaPlayer READS AGAIN ON RESUME
    private static final long SEEK_MS = 5_000L;             // ONE SECOND INTO THE SECOND SEGMENT
    private static final long LANDING_TOLERANCE_MS = 250L;  // THE RESUME LANDS ON THE PAUSED FRAME, NOT A SEGMENT AWAY
    private static final long SHORT_PAUSE_MS = 500L;
    private static final long SHORT_OBSERVE_MS = 3_000L;
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

    private boolean cache;

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

    // AN ORIGIN SERVING THE HLS VOD: THE PLAYLIST AS A FILE, THE SEGMENTS AS YOUTUBE SENDS THEM
    private static IdleOrigin origin(final long idleMs) {
        final IdleOrigin origin = IdleOrigin.resetting(idleMs);
        final StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:" + SEGMENT_SECONDS
                + "\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < segments.size(); i++) {
            origin.stream("/segment" + i + ".ts", "video/mp2t", segments.get(i), bytesPerSec * PACE);
            playlist.append("#EXTINF:").append(SEGMENT_SECONDS).append(".000,\nsegment").append(i).append(".ts\n");
        }
        origin.serve("/media.m3u8", "application/vnd.apple.mpegurl", playlist.append("#EXT-X-ENDLIST\n").toString().getBytes(StandardCharsets.UTF_8));
        return origin;
    }

    // A PLATFORM THAT RESOLVES host TO THE PLAYLIST WITHOUT A REQUEST OF ITS OWN, SO THE ORIGIN SEES ONLY THE PLAYER
    private static Platform platform(final String host, final URI playlist) {
        return new Platform() {
            @Override public String name() { return "HoldResumeTest-" + host; }

            @Override
            public Resolution resolve(final URI uri) {
                if (!"wmtest-hold".equals(uri.getScheme()) || !host.equals(uri.getHost())) return null;
                return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(), List.of(new Quality(playlist, 0, 0)), List.of(), null));
            }
        };
    }

    private static MRL resolve(final String host) {
        final MRL mrl = PlayerAPI.mrl(URI.create("wmtest-hold://" + host + "/" + System.nanoTime()));
        assertTrue(mrl.await(MRL_TIMEOUT_MS));
        assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
        return mrl;
    }

    // RESUMES THE PAUSED PLAYER ONCE THE ORIGIN RESET EVERY CONNECTION AND THE HOLD IS LONG; NULL WHEN ITS VIDEO KEPT
    // PLAYING THROUGH THE WINDOW AFTER THE RESUME, ELSE WHAT IT RENDERED THERE
    private static String resumesPlaying(final FFMediaPlayer player, final HeadlessGFXEngine gfx, final IdleOrigin origin,
                                         final long pausedNs, final List<Status> heard) throws InterruptedException {
        assertTrue(PlayerWait.awaitCondition(() -> origin.connections() == 0, CUT_TIMEOUT_MS), "the origin must cut every idle connection");
        assertTrue(origin.cuts() > 0, "the origin must cut the stalled segment downloads");
        final long heldMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pausedNs);
        if (heldMs <= HOLD_MS) Thread.sleep(HOLD_MS - heldMs + 1);
        heard.clear();

        // BRACKETED: THE FRAMES UPLOADED IN THE WINDOW AGAINST THE FRAMES ITS WALL TIME HOLDS
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
        if (moved && to - from >= minimum && status == Status.PLAYING && Math.abs(landedMs - pausedMs) <= LANDING_TOLERANCE_MS) return null;
        return "the video must keep playing from the paused frame: " + (to - from) + " frames in " + elapsedMs + " ms (first after "
                + firstMs + " ms at " + landedMs + " ms, paused at " + pausedMs + " ms), at least " + minimum + ", landed " + status
                + "; statuses " + heard + ", segment requests " + origin.requests("/segment0.ts") + "/" + origin.requests("/segment1.ts")
                + "/" + origin.requests("/segment2.ts");
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
            String stalled = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) Thread.sleep(RETRY_MS);
                try (final IdleOrigin origin = origin(IDLE_MS)) {
                    final String host = "p" + origin.uri("/").getPort();
                    final Platform platform = platform(host, origin.uri("/media.m3u8"));
                    PlatformAPI.register(platform);
                    final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                    final FFMediaPlayer player = new FFMediaPlayer(resolve(host), 0, gfx, null);
                    final List<Status> heard = new CopyOnWriteArrayList<>();
                    player.onStatus((source, previous, current) -> heard.add(current));
                    try {
                        assertTrue(player.start());
                        assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the video must start, landed " + player.status());
                        // PAUSED RIGHT AFTER A SEEK INTO THE SECOND SEGMENT: IT STALLS MID-DOWNLOAD WITH THE THIRD, FETCHED AHEAD
                        assertTrue(player.seek(SEEK_MS));
                        assertTrue(PlayerWait.awaitCondition(() -> player.playing() && player.time() >= SEEK_MS, LOAD_TIMEOUT_MS),
                                () -> "the seek must land, at " + player.time() + " ms " + player.status());
                        assertTrue(player.pause());
                        stalled = resumesPlaying(player, gfx, origin, System.nanoTime(), heard);
                        if (stalled == null) return;
                    } finally {
                        player.release();
                        PlatformAPI.unregister(platform);
                    }
                }
            }
            throw new AssertionError(stalled);
        }

        @Test
        @DisplayName("A video started paused and resumed once the origin reset its first segment download plays from its start")
        void pausedLaunch() throws Exception {
            String stalled = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) Thread.sleep(RETRY_MS);
                try (final IdleOrigin origin = origin(IDLE_MS)) {
                    final String host = "p" + origin.uri("/").getPort();
                    final Platform platform = platform(host, origin.uri("/media.m3u8"));
                    PlatformAPI.register(platform);
                    final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                    final FFMediaPlayer player = new FFMediaPlayer(resolve(host), 0, gfx, null);
                    final List<Status> heard = new CopyOnWriteArrayList<>();
                    player.onStatus((source, previous, current) -> heard.add(current));
                    try {
                        // THE DEMUXER PARKS RIGHT AFTER THE OPEN, WITH THE FIRST SEGMENT READ ONLY AS FAR AS THE PROBE WENT
                        assertTrue(player.startPaused());
                        assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PAUSED), () -> "the video must open paused, landed " + player.status());
                        stalled = resumesPlaying(player, gfx, origin, System.nanoTime(), heard);
                        if (stalled == null) return;
                    } finally {
                        player.release();
                        PlatformAPI.unregister(platform);
                    }
                }
            }
            throw new AssertionError(stalled);
        }
    }

    @Nested
    @DisplayName("Short pause")
    class ShortPause {

        @Test
        @DisplayName("A pause shorter than the hold threshold resumes in place, without buffering nor asking for the segment again")
        void resumesInPlace() throws Exception {
            try (final IdleOrigin origin = origin(PATIENT_IDLE_MS)) {
                final String host = "p" + origin.uri("/").getPort();
                final Platform platform = platform(host, origin.uri("/media.m3u8"));
                PlatformAPI.register(platform);
                final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
                final FFMediaPlayer player = new FFMediaPlayer(resolve(host), 0, gfx, null);
                final List<Status> heard = new CopyOnWriteArrayList<>();
                player.onStatus((source, previous, current) -> heard.add(current));
                try {
                    assertTrue(player.start());
                    assertTrue(PlayerWait.awaitStatus(player, LOAD_TIMEOUT_MS, Status.PLAYING), () -> "the video must start, landed " + player.status());
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > 0, LOAD_TIMEOUT_MS), "the video must upload frames");
                    assertTrue(player.pause());
                    Thread.sleep(SHORT_PAUSE_MS);
                    heard.clear();
                    final long from = gfx.uploadCount();
                    assertTrue(player.resume(), "the resume must change the player");
                    assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > from, SHORT_OBSERVE_MS), "the video must keep playing");
                    Thread.sleep(SHORT_OBSERVE_MS);
                    assertEquals(0, origin.cuts(), "the patient origin must keep every connection");
                    assertEquals(List.of(Status.PLAYING), heard, "a short pause resumes straight to PLAYING");
                    assertEquals(1, origin.requests("/segment0.ts"), "a short pause never asks for the segment again");
                } finally {
                    player.release();
                    PlatformAPI.unregister(platform);
                }
            }
        }
    }
}
