package org.watermedia;

import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.DownscaleFilter;
import org.watermedia.api.codecs.Encoder;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.player.ALEngine;
import org.watermedia.api.player.GFXEngine;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.SFXEngine;
import org.watermedia.api.player.SFXEngine.SpatialAudio;
import org.watermedia.api.player.ServerMediaPlayer;
import org.watermedia.api.player.VKContext;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.api.util.SampleType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compiled counterparts of the public API guide's integration examples. */
public final class ApiGuideExample {
    private ApiGuideExample() {}

    public static boolean start(final Path temporaryDirectory, final Path workingDirectory) {
        WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
        return WaterMedia.loaded();
    }

    public static MRL refresh(final MRL mrl) {
        return mrl.reload();
    }

    public static GFXEngine graphics(final String type, final Thread renderThread, final Executor executor,
                                     final VKContext context, final Runnable onFrame) {
        return switch (type) {
            case "GL" -> PlayerAPI.glEngine(renderThread, executor);
            case "VK" -> PlayerAPI.vkEngine(context);
            case "AWT" -> PlayerAPI.awtEngine(onFrame);
            case "JFX" -> PlayerAPI.jfxEngine(onFrame);
            case "HEADLESS" -> PlayerAPI.headlessEngine(false);
            default -> throw new IllegalArgumentException("Unknown graphics engine");
        };
    }

    public static SFXEngine audio(final String type) {
        return switch (type) {
            case "AL" -> PlayerAPI.alEngine();
            case "SPATIAL" -> PlayerAPI.alEngine(true);
            case "JS" -> PlayerAPI.jsEngine();
            default -> throw new IllegalArgumentException("Unknown audio engine");
        };
    }

    public static boolean position(final MediaPlayer player, final double x, final double y, final double z) {
        return player.spatialAudio(new SpatialAudio(x, y, z, 8.0f, 64.0f, 1.0f, false, null));
    }

    // A STEREO PICTURE: THE PRIMARY SINK PLAYS THE LEFT EDGE AND A NEW ONE THE RIGHT EDGE
    public static ALEngine.Sink stereo(final MediaPlayer player, final double leftX, final double rightX, final double y, final double z) {
        final ALEngine.Sink left = player.sinks().get(0);
        left.mix(ALEngine.Mix.LEFT);
        left.spatialAudio(new SpatialAudio(leftX, y, z, 8.0f, 64.0f, 1.0f, false, null));
        final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
        right.spatialAudio(new SpatialAudio(rightX, y, z, 8.0f, 64.0f, 1.0f, false, null));
        return right;
    }

    // A DUBBED VIDEO: PREFER THE VIEWER'S LANGUAGE AT THE NEXT START, OR SWITCH TO A LISTED TRACK RIGHT AWAY
    public static boolean dub(final MediaPlayer player, final String language) {
        player.audioLanguage(language);
        for (final MediaPlayer.AudioTrack track: player.audioTracks())
            if (language.equalsIgnoreCase(track.lang())) return player.audioTrack(track);
        return false;
    }

    public static MediaPlayer play(final MRL mrl, final Supplier<GFXEngine> graphics, final Supplier<SFXEngine> audio) {
        if (mrl.status() != MRL.Status.LOADED) throw new IllegalStateException("Media has not loaded");
        final MediaPlayer player = PlayerAPI.player(mrl, 0, graphics, audio);
        if (player == null) throw new IllegalStateException("Source is unavailable or its backend failed");
        try {
            if (!player.start()) throw new IllegalStateException("Player refused to start");
            return player;
        } catch (final RuntimeException | Error failure) {
            player.release();
            throw failure;
        }
    }

    public static void controls(final MediaPlayer player) {
        player.pause(true);
        player.pause(false);
        player.seek(15_000);
        player.volume(50);
        player.mute(true);
        player.speed(1.25f);
        player.repeat(true);
        player.scale(1_280, 720);
    }

    public static ServerMediaPlayer clock(final long durationMs, final Consumer<MediaPlayer.PlayerState> transport) {
        final ServerMediaPlayer clock = PlayerAPI.serverPlayer();
        clock.syncDuration(durationMs);
        clock.start();
        // THE HOST SENDS A SNAPSHOT AGAIN WHENEVER clock.revision() MOVES
        transport.accept(clock.snapshot());
        return clock;
    }

    public static void follow(final MediaPlayer player, final MediaPlayer.PlayerState snapshot) {
        player.tolerance(500L);
        player.sync(snapshot);
    }

    public static long decode(final ByteBuffer encoded) throws IOException {
        long frames = 0L;
        try (final ImageReader reader = CodecsAPI.reader(encoded)) {
            reader.addFilter(new DownscaleFilter(0.5f));
            while (reader.hasNext()) {
                final ByteBuffer pixels = reader.next();
                if (!pixels.hasRemaining() || reader.frameWidth() > reader.width() || reader.frameHeight() > reader.height())
                    throw new IOException("Filtered frame is empty or larger than its source");
                frames++;
            }
        }
        return frames;
    }

    public static void encode(final Path target, final ByteBuffer frame, final long ptsUs, final ByteBuffer pcm) throws IOException {
        final Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1_280, 720, PixelFormat.BGRA, 60);
        final Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
        try (final Encoder encoder = CodecsAPI.encoder(Container.MP4, target, video, audio)) {
            encoder.video(frame, ptsUs);
            encoder.audio(pcm);
        }
    }
}
