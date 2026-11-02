package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** Verifies the {@link TxMediaPlayer} streaming path on the animated GIFs: seekable, timed and uploading frames over time. */
@DisplayName("TxMediaPlayer animated image")
public class TxAnimatedImageTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long PLAYBACK_OBSERVATION_MS = 1_500L;

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @TestFactory
    @DisplayName("Animated GIFs stream frames over time")
    Stream<DynamicTest> animatedGifs() {
        final List<Path> fixtures = List.of(
                Fixtures.GIF_ANIMATED,
                Fixtures.GIF_DIR.resolve("2.gif"),
                Fixtures.GIF_DIR.resolve("3.gif"));

        return fixtures.stream().map(path -> dynamicTest(path.getFileName().toString(), () -> {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(path));
            assertTrue(mrl.await(MRL_TIMEOUT_MS));

            // NO FRAME-TEXTURE PATH: upload() RUNS PER FRAME, SO uploadCount() SHOWS THE STREAMING
            final HeadlessGFXEngine gfx = new HeadlessGFXEngine(false);
            final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, gfx);
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING, Status.PAUSED));
                assertTrue(player.canSeek());
                assertTrue(player.duration() > 0L);

                final long baseline = gfx.uploadCount();
                assertTrue(PlayerWait.awaitCondition(() -> gfx.uploadCount() > baseline, PLAYBACK_OBSERVATION_MS));
            } finally {
                player.release();
            }
        }));
    }
}
