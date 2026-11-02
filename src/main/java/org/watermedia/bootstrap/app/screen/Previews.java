package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.TxMediaPlayer;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.Collection;
import java.util.List;

// MEDIA PREVIEW RULES SHARED BY THE MRL SELECTOR AND THE OPEN MULTIMEDIA SCREEN: STATUS WORDS AND COLORS, AND
// THE THUMBNAIL PLAYERS' ANIMATION CHECK AND RELEASE. A null MRL IS NEITHER LOADED NOR FAILED
final class Previews {
    private Previews() {}

    static boolean loaded(final MRL mrl) {
        return mrl != null && mrl.status().loaded();
    }

    static boolean failed(final MRL mrl) {
        return mrl != null && mrl.status().failed();
    }

    static String label(final MRL mrl) {
        return switch (mrl.status()) {
            case LOADED -> "READY";
            case FETCHING -> "LOADING";
            case ERROR -> "ERROR";
            case BLOCKED -> "BLOCKED";
            case EXPIRED -> "EXPIRED";
            case FORGOTTEN -> "FORGOTTEN";
        };
    }

    static Color color(final MRL mrl) {
        if (mrl == null) return AppTheme.TEXT_FAINT;
        return switch (mrl.status()) {
            case LOADED -> AppTheme.GREEN;
            case FETCHING -> AppTheme.NEON;
            case ERROR, BLOCKED -> AppTheme.RED;
            case EXPIRED, FORGOTTEN -> AppTheme.AMBER;
        };
    }

    // A STILL PICTURE NEVER CHANGES ONCE SHOWN, SO ONLY TIMED OR STREAMED THUMBNAILS KEEP THE SCREEN REPAINTING
    static boolean animating(final Collection<MediaPlayer> players) {
        for (final MediaPlayer player: players) {
            if (player == null || player.error() || player.stopped() || player.ended() || player.paused()) continue;
            if (!(player instanceof TxMediaPlayer) || player.duration() > 0L) return true;
        }
        return false;
    }

    // THE PLAYERS MUST ALREADY BE OUT OF THE SCREEN'S MAP: stop() RETURNS AT ONCE, AND THE BLOCKING release() RUNS
    // ON THE SHUTDOWN-AWAITED CHAIN SO A TEARDOWN CANNOT OUTLIVE THE RENDER DEVICE
    static void release(final AppContext ctx, final List<MediaPlayer> players) {
        if (players.isEmpty()) return;
        for (final MediaPlayer player: players) player.stop();
        ctx.releaseAsync("WaterMedia-ThumbnailRelease", () -> {
            for (final MediaPlayer player: players) player.release();
        });
    }
}
