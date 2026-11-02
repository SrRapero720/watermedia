package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.TLSLifecycleProbe;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.ServerMediaPlayer;
import org.watermedia.binaries.WaterMediaBinaries;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exercises one WaterMedia load per process: the load is final, so every scenario gets its own JVM. */
public final class BootstrapProbe {
    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void main(final String[] arguments) throws Exception {
        final String scenario = arguments[0];
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.OFF;
        WaterMediaConfig.network.timeout = 1_000;
        WaterMediaConfig.codecs.ffmpeg.disable = !scenario.equals("native") && !scenario.equals("native-failure") && !scenario.startsWith("tls");
        switch (scenario) {
            case "tls" -> TLSLifecycleProbe.run(root, false);
            case "tls-empty" -> TLSLifecycleProbe.run(root, true);
            case "native-failure" -> nativeFailure(root);
            case "optional-failure" -> optionalFailure(root);
            case "server" -> server(root);
            case "native" -> nativeLoad(root);
            case "client" -> client(root);
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
        System.out.println("PROBE_OK " + scenario);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // AN INSTALLED BINARIES MODULE THAT CANNOT PROVISION ITS NATIVES ENDS THE LOAD BEFORE THE MODULES BEHIND IT
    private static void nativeFailure(final Path root) throws Exception {
        Files.createDirectories(root.resolve("temp"));
        Files.writeString(root.resolve("temp/ffmpeg"), "untrusted native base");
        try {
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
            throw new AssertionError("Startup accepted a failed installed binaries module");
        } catch (final IllegalStateException expected) {
            if (WaterMedia.loaded() || WaterMedia.loading() != null || expected.getCause() == null
                    || !expected.getMessage().contains(WaterMediaBinaries.class.getSimpleName()))
                throw new AssertionError("The failure did not name the binaries module with its cause", expected);
            if (WaterMedia.step() != 1 || WaterMedia.steps() != 5)
                throw new AssertionError("Progress did not stop at the failed module: " + WaterMedia.step() + "/" + WaterMedia.steps());
        }
        if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null) throw new AssertionError("Failed installation published a native path");
        try {
            PlayerAPI.mrl("https://example.invalid/clip.mp4");
            throw new AssertionError("A failed load resolved media");
        } catch (final IllegalStateException expected) {}
        requireFinal(root);
    }

    // A PART A MODULE CAN LIVE WITHOUT FAILS, THE MODULE LOGS IT AND THE LOAD STILL COMPLETES
    private static void optionalFailure(final Path root) throws Exception {
        // THE FILE SERVER BINDS THE LOOPBACK ADDRESS; A WILDCARD SOCKET WOULD NOT CONFLICT WITH IT ON EVERY OS
        try (final ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.ALWAYS;
            WaterMediaConfig.network.serverPort = occupied.getLocalPort();
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        }
        if (!WaterMedia.loaded() || WaterMedia.loading() != null || WaterMedia.step() != WaterMedia.steps())
            throw new AssertionError("An occupied port did not leave the load complete: " + WaterMedia.step() + "/" + WaterMedia.steps());
        // THE REST OF THE MODULE WORKS: AN UPLOAD IS ACCEPTED WITHOUT AN ILLEGAL STATE AND FAILS ON ITS OWN
        // (ServiceLifecycleProbe CHECKS THE MESSAGE A MISCONFIGURED REMOTE FAILS IT WITH)
        WaterMediaConfig.network.remoteUrl = "remote.invalid/upload";
        final NetworkAPI.UploadStatus upload = NetworkAPI.upload(Files.writeString(root.resolve("upload.bin"), "payload").toFile());
        if (!PlayerWait.awaitCondition(upload::failed, 5_000) || upload.completed()) throw new AssertionError("The misconfigured upload did not fail");
        requireFinal(root);
    }

    // A DEDICATED SERVER WALKS EVERY MODULE, BUT THE CLIENT ONES DECIDE BY THEMSELVES TO DO NOTHING THERE
    private static void server(final Path root) {
        WaterMedia.start("PROBE", root.resolve("temp"), root, false);
        if (!WaterMedia.loaded() || WaterMedia.steps() != 5 || WaterMedia.step() != 5)
            throw new AssertionError("The server did not walk every module: " + WaterMedia.step() + "/" + WaterMedia.steps());
        if (WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID) != null || WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID) != null)
            throw new AssertionError("Server initialized native paths");
        if (Files.exists(root.resolve("temp/ffmpeg"))) throw new AssertionError("Server extracted client natives");
        try {
            PlayerAPI.mrl("https://example.invalid/clip.mp4");
            throw new AssertionError("A server resolved media");
        } catch (final IllegalStateException expected) {}
        final ServerMediaPlayer player = PlayerAPI.serverPlayer();
        player.release();
        requireFinal(root);
    }

    private static void nativeLoad(final Path root) {
        WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        if (!WaterMedia.loaded() || !CodecsAPI.ffmpegLoaded() || CodecsAPI.ffmpegError()) throw new AssertionError("Required FFmpeg did not load");
        final Path installed = WaterMediaBinaries.pathOf(WaterMediaBinaries.FFMPEG_ID);
        if (installed == null || !Files.isRegularFile(installed.resolve("version.cfg")))
            throw new AssertionError("Native module did not publish its verified installation");
        if (!root.resolve("temp/yt-dlp").equals(WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID)))
            throw new AssertionError("Executable paths were not published");
        requireFinal(root);
    }

    // DISABLED FFMPEG PUBLISHES NO NATIVE STATE, AND A CANCELLED WAIT NEVER CANCELS THE LOAD BEHIND IT
    private static void client(final Path root) throws Exception {
        WaterMedia.start("PROBE", root.resolve("temp"), root, true);
        if (!WaterMedia.loaded() || WaterMedia.steps() != 5 || WaterMedia.step() != 5)
            throw new AssertionError("The client did not walk every module: " + WaterMedia.step() + "/" + WaterMedia.steps());
        if (CodecsAPI.ffmpegLoaded() || CodecsAPI.ffmpegError() || CodecsAPI.vulkanDecode()) throw new AssertionError("Disabled FFmpeg published native state");
        if (Files.exists(root.resolve("temp/ffmpeg"))) throw new AssertionError("Disabled FFmpeg was extracted");
        if (!root.resolve("temp/yt-dlp").equals(WaterMediaBinaries.pathOf(WaterMediaBinaries.YTDLP_ID)))
            throw new AssertionError("Executable paths were not published");
        // THE ORIGIN ANSWERS ONLY AFTER THE INTERRUPTED WAIT GAVE UP, SO THE LOAD MUST OUTLIVE THAT WAIT AND STILL SUCCEED
        WaterMediaConfig.network.timeout = 5_000;
        try (final Origin origin = Origin.of(new byte[] { 1, 2, 3 }, "image/png")) {
            origin.hold();
            final URI slow = origin.uri(".png");
            final MRL previous = PlayerAPI.mrl(slow);
            if (!origin.awaitEntered(5_000L)) throw new AssertionError("Request never started");
            Thread.currentThread().interrupt();
            try {
                if (previous.await(10_000) || !Thread.currentThread().isInterrupted())
                    throw new AssertionError("MRL wait ignored cancellation");
            } finally {
                Thread.interrupted();
            }
            if (PlayerAPI.mrl(slow) != previous) throw new AssertionError("The cached MRL was replaced");
            origin.release();
            if (!previous.await(10_000) || previous.status() != MRL.Status.LOADED) throw new AssertionError("The interrupted wait cancelled the load: " + previous.status());
        }
        requireFinal(root);
    }

    // THE LOAD HAPPENS ONCE PER PROCESS, WHATEVER STATE IT LEFT
    private static void requireFinal(final Path root) {
        try {
            WaterMedia.start("PROBE", root.resolve("temp"), root, true);
            throw new AssertionError("WaterMedia accepted a second start");
        } catch (final IllegalStateException expected) {}
    }
}
