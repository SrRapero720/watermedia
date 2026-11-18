package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.api.player.MediaPlayer.Status;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link TxMediaPlayer} still-image mode: an SVG served under a Content-Type that names no image plays
 * through {@link PlayerAPI#player} as a still that uploads once and frees its engine, and a still without a display
 * time never ends.
 */
@DisplayName("TxMediaPlayer static image")
public class TxStaticImageTest {

    private static final long MRL_TIMEOUT_MS = 2_000L;
    private static final long PLAYER_TIMEOUT_MS = 3_000L;
    private static final long OBSERVE_MS = 400L; // A STILL TAKEN FOR TIMED WITH A 0 MS WINDOW WOULD END WITHIN A MILLISECOND

    @BeforeAll
    static void boot() { MediaBootstrap.client(); }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = { "text/xml", "application/octet-stream" })
    @DisplayName("An SVG served under a non-image Content-Type plays as a still that uploads once and frees its engine")
    void nonImageContentTypePlays(final String contentType) {
        try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.SVG_DIR.resolve("car-filled-color.svg")), contentType)) {
            // NO EXTENSION: ONLY THE BYTE SNIFF OF THE MRL TYPES THE SOURCE, AND THE PLAYER MUST NOT REFUSE IT FOR ITS HEADER
            final MRL mrl = PlayerAPI.mrl(origin.uri(""));
            assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
            assertEquals(MRL.Status.LOADED, mrl.status(), () -> "the MRL failed: " + mrl.exception());
            final HeadlessGFXEngine gfx = PlayerAPI.headlessEngine();
            final TxMediaPlayer player = assertInstanceOf(TxMediaPlayer.class, PlayerAPI.player(mrl, () -> gfx, () -> null),
                    "the sniffed SVG plays on the image player");
            try {
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the still went live, not " + player.status());
                assertTrue(player.canPlay());
                assertTrue(player.width() > 0 && player.height() > 0, "the engine holds the decoded size");
                assertEquals(1L, gfx.uploadCount(), "a still uploads its single frame once");
            } finally {
                player.release();
            }
            assertTrue(gfx.released(), "the player's release frees its graphics engine");
        }
    }

    @Test
    @DisplayName("A still without a display time never ends and reports no duration")
    void untimedStillNeverEnds() {
        final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.JPEG_GRAY));
        assertTrue(mrl.await(MRL_TIMEOUT_MS), "the MRL resolves");
        final TxMediaPlayer player = new TxMediaPlayer(mrl, 0, new HeadlessGFXEngine(false));
        try {
            assertTrue(player.start());
            assertTrue(PlayerWait.awaitStatus(player, PLAYER_TIMEOUT_MS, Status.PLAYING), () -> "the still went live, not " + player.status());
            assertFalse(PlayerWait.awaitCondition(player::ended, OBSERVE_MS), "a still without a display time never ends");
            assertEquals(0L, player.duration(), "an untimed still reports no duration");
        } finally {
            player.release();
        }
    }
}
