package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.annotations.*;
import me.srrapero720.waterconfig.impl.fields.StringField;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.WaterMediaModule;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** WaterMedia configuration: the TOML-backed settings of the codecs, the network, the media players and the platforms. */
@Spec(value = WaterMedia.ID, format = WaterConfig.FORMAT_TOML)
public final class WaterMediaConfig {
    private static final int DEFAULT_NETWORK_SERVER_PORT = 25_572;
    /** Upload token shipped by default; a file server reachable from other machines refuses to start with it. */
    public static final String DEFAULT_NETWORK_TOKEN = "watermedia_default_token_change_it";

    @Spec.Field
    @Comment("CodecsAPI settings")
    public static final Codecs codecs = new Codecs();

    @Spec.Field
    @Comment("NetworkAPI settings")
    public static final Network network = new Network();

    @Spec.Field
    @Comment("PlayerAPI settings")
    public static final Player player = new Player();

    @Spec.Field
    @Comment("Platform-specific settings")
    public static final Platforms platforms = new Platforms();

    private WaterMediaConfig() {}

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Settings of the house image codecs and the FFmpeg natives. */
    @Spec(value = "codecs", disableStatic = true)
    public static final class Codecs {
        @Spec.Field(suffix = "MB")
        @Comment("Hard cap (in MB) for image downloads consumed by TxMediaPlayer")
        @Comment("Sources above this limit are aborted before full buffering")
        @NumberConditions(minInt = 1, math = true)
        public int maxImageSourceSize = 128;

        @Spec.Field
        @Comment("Related config for PNG codec")
        public final Png png = new Png();

        @Spec.Field
        @Comment("Related config for SVG codec")
        public final Svg svg = new Svg();

        @Spec.Field
        @Comment("Related config for VP8 codec (WEBP lossy)")
        public final Vp8 vp8 = new Vp8();

        @Spec.Field
        @Comment("FFMPEG general settings")
        public final Ffmpeg ffmpeg = new Ffmpeg();

        /** Settings of the SVG rasterizer. */
        @Spec(value = "svg", disableStatic = true)
        public static final class Svg {
            @Spec.Field(suffix = "px")
            @Comment("Default maximum dimension (px) when rasterizing vector SVG images")
            @Comment("SVG is resolution-independent; the larger raster side is capped to this, preserving aspect ratio (never upscaled)")
            @NumberConditions(minInt = 1, maxInt = 8_192, math = true)
            public int maxSize = 512;
        }

        /** Settings of the PNG and APNG decoder. */
        @Spec(value = "png", disableStatic = true)
        public static final class Png {
            @Spec.Field
            @Comment("Decoder fails when PNG chunk data doesn't match with the CRC")
            public boolean failOnCorruptedData = true;

            @Spec.Field
            @Comment("Present PNG and APNG pictures against the colour declared by their bKGD chunk instead of transparency")
            @Comment("When true the canvas starts filled with it, static pictures and frames blended as SOURCE are flattened onto it, and APNG dispose-to-background restores it")
            @Comment("When false transparency is kept everywhere (recommended for compositing)")
            public boolean useBKGDChunk = false;
        }

        /** Settings of the VP8 (lossy WebP) decoder. */
        @Spec(value = "vp8", disableStatic = true)
        public static final class Vp8 {
            @Spec.Field
            @Comment("Attempt to repair VP8 frames declaring broken token partition sizes instead of failing the decode")
            @Comment("Broken sizes are clamped to the bytes actually available; empty partitions are replaced with a duplicate of the previous one or with fake zeroed data")
            @Comment("Repaired frames decode best-effort and may show visual artifacts")
            public boolean brokenTokens = false;
        }

        /** Settings of the FFmpeg natives and the inputs they open. */
        @Spec(value = "ffmpeg", disableStatic = true)
        public static final class Ffmpeg {
            @Spec.Field
            @Comment("Disables the FFmpeg bootstrap; changing it requires a restart")
            public boolean disable = false;

            @Spec.Field(control = Control.INPUT_FOLDER)
            @Comment("Adds this path to the discovery path")
            @Comment("Path must not be the fat ffmpeg.exe file")
            public Path customPath = new File("").toPath();

            @Spec.Field
            @Comment("Enables hardware-accelerated video decoding (NVDEC, QuickSync, D3D11VA, VAAPI...)")
            @Comment("Disable if you experience stutter, lag or black video; software decoding is often smoother on some GPUs (notably AMD)")
            public boolean hardwareAccel = true;

            @Spec.Field(suffix = "packets", control = Control.SEEKBAR)
            @Comment("Configures how many audio packets to read per loop when the video carries a separate audio track")
            @Comment("Increment this value if you find YouTube videos with slow playback")
            @NumberConditions(minInt = 1, maxInt = 12)
            public int trackPacketReads = 3;

            @Spec.Field
            @Comment("Enables the on-disk HTTP media cache used by FFMediaPlayer for small files")
            public boolean cache = true;

            @Spec.Field(suffix = "MB")
            @Comment("Maximum size of a network response FFMediaPlayer keeps in the cache, in megabytes")
            @NumberConditions(minInt = 1, math = true)
            public int cacheMaxResponse = 10;

            @Spec.Field(suffix = "ms")
            @Comment("FFMediaPlayer stream probing duration (FFmpeg analyzeduration), in milliseconds")
            @Comment("Higher values help FFmpeg detect audio/video parameters (sample rate, channels) on slow or live HLS streams, at the cost of startup latency")
            @Comment("Zero leaves FFmpeg's own default in place")
            @NumberConditions(minLong = 0L, maxLong = 60_000)
            public long analyzeDuration = 7_000;

            @Spec.Field(suffix = "MB")
            @Comment("FFMediaPlayer stream probing size (FFmpeg probesize), in megabytes")
            @Comment("Higher values let FFmpeg read more data while detecting stream parameters, improving codec detection on streams with sparse headers")
            @NumberConditions(minInt = 1, math = true)
            public int probeSize = 10;
        }
    }

    /** Settings of the media players and their caches. */
    @Spec(value = "player", disableStatic = true)
    public static final class Player {
        @Spec.Field(control = Control.DROPDOWN)
        @Comment("Default quality for streaming media without a specified quality")
        public MediaQuality defaultQuality = MediaQuality.HIGHER;

        @Spec.Field(suffix = "min")
        @Comment("Cleanup Interval in Minutes for all expired MRL or with errors (memory saver)")
        @Comment("MRL manager usually doesn't consume much memory, intervals below 60 minutes will not reduce memory consumption at all")
        @NumberConditions(minFloat = 1f)
        public float mrlCleanupInterval = 60.0f;

        @Spec.Field(suffix = "MB")
        @Comment("Total on-disk budget (in MB) for the shared cache of downloaded media files under %TEMP%")
        @Comment("Checked after every cache write; once the store grows past this cap the oldest entries are evicted until it fits again")
        @NumberConditions(minInt = 1, math = true)
        public int cacheBudget = 8 * 1_024;

        @Spec.Field
        @Comment("WaterTexture general settings")
        public final WaterTx tx = new WaterTx();

        /** Settings of the picture player, publicly known as WaterTX. */
        @Spec(value = "watertx", disableStatic = true)
        public static final class WaterTx {
            @Spec.Field
            @Comment("Enables the on-disk HTTP image cache used by TxMediaPlayer")
            public boolean cache = true;

            @Spec.Field(suffix = "MB", control = Control.SEEKBAR)
            @Comment("VRAM budget (in MB) for keeping an animated image as one GL texture per frame")
            @Comment("Animations whose decoded frames fit under this budget skip per-frame streaming entirely (best performance, like v2)")
            @Comment("Set to 0 to force TxMediaPlayer to stream animated image frames into a single texture")
            @NumberConditions(minInt = 0, maxInt = 512)
            public int texturesBudget = 32;
        }
    }

    /** Settings of the web platforms. */
    @Spec(value = "platforms", disableStatic = true)
    public static final class Platforms {
        @Spec.Field
        @Comment("Allow resolving streams/videos marked as mature content (e.g. Kick).")
        @Comment("When false, mature streams throw and never reach the player.")
        public boolean allowMatureContent = false;

        @Spec.Field(suffix = "min")
        @Comment("Cleanup interval in minutes for the in-memory platform search results cache")
        @Comment("Identical searches are served from memory until the whole cache is flushed on this interval (general sweep)")
        @Comment("Set to 0 to disable caching and always query the platforms")
        @NumberConditions(minFloat = 0f)
        public float searchCleanupInterval = 15.0f;

        @Spec.Field
        @Comment("Results each platform may contribute to a search; a search asking for more is capped here")
        @NumberConditions(minInt = 1, maxInt = 50)
        public int searchLimit = 2;

        @Spec.Field(control = Control.PASSWORD)
        @Comment("BiliBili session cookie for authenticated access")
        @Comment("Without it only 360p/480p are available, with a free account up to 1080p, VIP up to 8K")
        @Comment("Value should look like: SESSDATA=abc123; bili_jct=def456; DedeUserID=789")
        public String biliBiliCookie = "";
    }

    /** Settings of the HTTP requests and the WaterMedia file servers. */
    @Spec(value = "network", disableStatic = true)
    public static final class Network {
        @Spec.Field(control = Control.DROPDOWN)
        @Comment("Where the file storage server runs: OFF, DEDICATED (only on a dedicated server) or ALWAYS (also on clients)")
        @Comment("ALWAYS doesn't open the port by itself: forward it and point remoteUrl at your public IP or domain")
        public Server server = Server.OFF;

        @Spec.Field
        @Comment("Address used by the file server; use a LAN address or 0.0.0.0 to accept remote clients")
        @Comment("Remote listeners require a non-default token")
        public String serverBindAddress = "127.0.0.1";

        @Spec.Field
        @Comment("Maximum simultaneous file-server requests; further requests are refused as busy (HTTP 503)")
        @NumberConditions(minInt = 1, maxInt = 256)
        public int maxServerRequests = 8;

        @Spec.Field(suffix = "ms")
        @Comment("Maximum idle time of a file-server request: it is cut once this long passes without headers or transfer progress")
        @NumberConditions(minInt = 1_000, maxInt = 600_000)
        public int serverTimeout = 30_000;

        @Spec.Field(suffix = "MB")
        @Comment("Total file-server storage budget, including uploads in progress")
        @NumberConditions(minInt = 1, math = true)
        public int maxStorageSize = 1_024;

        @Spec.Field(suffix = "MB")
        @Comment("Maximum upload file size in megabytes, files larger than this will be rejected by the server")
        @Comment("Zero means unlimited, but be careful with that if your users aren't trustworthy enough")
        @Comment("NOTE: this doesn't affect manual file uploads to the server storage folder or existing files, but only uploads through the API")
        @Comment("Used by the file server: on the server, or on a client with server = ALWAYS")
        @NumberConditions(minInt = 0, math = true)
        public int maxUploadSize = 8;

        @Spec.Field
        @NumberConditions(minInt = 1, maxInt = 65_535)
        @Comment("File server port; check with your provider that it is reachable from outside before starting the server")
        @Comment("Used by the file server: on the server, or on a client with server = ALWAYS")
        public int serverPort = DEFAULT_NETWORK_SERVER_PORT;

        @Spec.Field
        // FULL URL (scheme://host[:port][/path]) WHOSE HOST MAY BE AN IPv4 OR A DOMAIN, THE FORM NetworkAPI.upload
        // RESOLVES "<remoteUrl>/upload" AGAINST
        @StringConditions(value = "^https?://([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)(\\.([a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?))*(:\\d{1,5})?(/.*)?$", regexFlags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, mode = StringField.Mode.REGEX)
        @Comment("Sets the remote file server to upload and download files, it must match your game server IP or domain with the given port")
        @Comment("Include the scheme and port, e.g. http://your-host:25572/")
        @Comment("Used by every host that uploads through NetworkAPI, clients included")
        public String remoteUrl = "http://localhost:" + DEFAULT_NETWORK_SERVER_PORT + "/";

        @Spec.Field(control = Control.PASSWORD)
        @Comment("The token clients present to the file server, change it every time you update your server and the clients to prevent external access and bandwidth abuse")
        @Comment("Used on server-side and client-side")
        public String token = DEFAULT_NETWORK_TOKEN;

        @Spec.Field(suffix = "ms")
        @Comment("Default connect/read timeout for NetRequest, in milliseconds")
        @NumberConditions(minInt = 1_000, maxInt = 600_000)
        public int timeout = 15_000;

        @Spec.Field(control = Control.SEEKBAR)
        @Comment("Maximum number of redirect hops NetRequest will follow before aborting")
        @NumberConditions(minInt = 0, maxInt = 50)
        public int maxRedirects = 10;

        @Spec.Field(suffix = "bytes")
        @Comment("Hard cap (in bytes) for NetRequest text/JSON bodies; anything larger throws")
        @NumberConditions(minInt = 1_024, math = true)
        public int maxTextBytes = 16 * 1_024 * 1_024;

        /** Where the file storage server runs. */
        public enum Server { OFF, DEDICATED, ALWAYS }
    }

    /** Internal bootstrap operation for the configuration facade. */
    public static final class Module extends WaterMediaModule {
        public Module(final WaterMedia watermedia) { super(watermedia); }

        @Override
        public void start() throws InterruptedException {
            WaterConfig.init();
            final ConfigSpec existing = WaterConfig.spec(WaterMedia.ID, false);
            if (existing == null) {
                WaterConfig.registerBlocking(WaterMediaConfig.class);
            } else {
                // THE HOST CONFIGURATION SERVICE IS PROCESS-SCOPED; NEVER REGISTER A SECOND WRITER.
                synchronized (existing) {
                    while (!existing.isLoaded() && existing.status() != ConfigSpec.Status.FAILED) existing.wait();
                    if (!existing.isLoaded()) throw new IllegalStateException("WaterMedia configuration failed", existing.loadError());
                    existing.refresh();
                }
            }
        }
    }
}
