package org.watermedia.bootstrap.app;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.encoders.FFTranscoder;
import org.watermedia.api.player.MRL;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.Metadata;
import org.watermedia.tools.DataTool;
import org.watermedia.tools.ThreadTool;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * The download dialog: the options picked for one source, the probes of what it holds and the transcode that writes
 * the file. Picks and probes belong to the render thread; a worker only probes or transcodes and hands its result back.
 */
public final class MediaDownloader {
    private static final Marker IT = MarkerManager.getMarker(MediaDownloader.class.getSimpleName());

    // CHOICES, IN THE ORDER THE DIALOG SHOWS THEM
    public static final Container[] CONTAINERS = { Container.MP4, Container.MKV, Container.MOV, Container.MP3, Container.OGG, Container.M4A, Container.WEBM };
    public static final VideoCodec[] VIDEO_CODECS = { VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.VP9 };
    public static final AudioCodec[] AUDIO_CODECS = { AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.FLAC };
    public static final int[] FRAME_RATES = { 60, 50, 30, 25, 24 };
    // THE CODEC A CONTAINER GETS WHEN IT REFUSES THE PICKED ONE: THE MOST PLAYED IT CARRIES, THE SLOWEST TO ENCODE LAST
    private static final VideoCodec[] VIDEO_FALLBACK = { VideoCodec.H264, VideoCodec.VP9, VideoCodec.HEVC, VideoCodec.AV1 };

    // BITRATES
    private static final long VIDEO_FLOOR_BPS = 100_000L;    // LOW END OF THE VIDEO SLIDER, OR A TWENTIETH OF THE SOURCE WHEN HIGHER
    private static final long AUDIO_FLOOR_BPS = 32_000L;     // LOW END OF THE AUDIO SLIDER
    private static final long AUDIO_CEILING_BPS = 320_000L;  // NO LOSSY CODEC GAINS ANYTHING ABOVE IT
    private static final long AUDIO_GUESS_BPS = 64_000L;     // PER CHANNEL, FOR A SOURCE THAT STATES NO BITRATE
    private static final double VIDEO_GUESS_BPP = 0.07;      // BITS PER PIXEL, FOR A SOURCE THAT STATES NO BITRATE
    private static final double FLAC_RATIO = 0.6;            // FLAC AGAINST THE 16-BIT PCM IT PACKS

    // FILES
    private static final int NAME_LIMIT_CHARS = 120;
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|conin\\$|conout\\$|(com|lpt)[0-9¹²³])(\\..*)?"); // WINDOWS DEVICES
    private static final long SHUTDOWN_WAIT_MS = 3_000L;
    private static final Runnable WAKE = () -> {};           // AN EMPTY RENDER TASK STILL REPAINTS

    // DIALOG STATE, READ BY THE RENDER THREAD EVERY FRAME
    public volatile boolean visible;
    public volatile boolean working;
    public volatile boolean done;
    public volatile String error;      // WHY THE LAST DOWNLOAD FAILED, OR NULL
    public volatile float progress;
    public volatile String encoder;    // VIDEO ENCODER OF THE RUNNING DOWNLOAD, OR NULL
    public volatile Path file;         // THE WRITTEN FILE ONCE DONE

    // PICKS, OWNED BY THE RENDER THREAD: A NULL CODEC COPIES THE TRACK AND 0 FPS KEEPS THE SOURCE'S
    public Container container = Container.MP4;
    public MediaQuality quality = MediaQuality.UNKNOWN;
    public VideoCodec video;
    public AudioCodec audio;
    public int fps;
    public FFTranscoder.Effort effort = FFTranscoder.Effort.BALANCED;
    public boolean hardware;
    public float videoRate = 1f;       // SHARE OF THE SOURCE'S BITRATE, FROM THE SLIDER
    public float audioRate = 1f;
    public Preset preset = Preset.SOURCE;
    public MediaQuality[] qualities = new MediaQuality[0]; // OF THE SOURCE, BEST FIRST
    public boolean youtube;

    private final Executor render;
    private final Path folder;
    private MRL.Source source;
    private String title = "";         // THE SOURCE'S OWN, WRITTEN AS A TAG OF THE FILE
    private String name = "";          // WHAT THE FILE IS NAMED AFTER
    private final Map<URI, FFTranscoder.Info> infos = new HashMap<>();
    private final Map<URI, String> failures = new HashMap<>();
    private final Set<URI> pending = new HashSet<>();
    private volatile FFTranscoder transcoder;
    private volatile boolean cancelling;
    private volatile Thread worker;
    // PICKS OF THE LAST DOWNLOAD STARTED, KEPT WHILE THE APP RUNS SO THE NEXT DIALOG OPENS ON THEM
    private Picks last;

    MediaDownloader(final Executor render) {
        this(render, null);
    }

    // TEST SEAM: A FOLDER OF THE TEST'S OWN INSTEAD OF THE USER'S DOWNLOADS
    MediaDownloader(final Executor render, final Path folder) {
        this.render = render;
        this.folder = folder;
    }

    /** Opens the dialog on a source with every track copied as it comes; a null {@code title} names the file after {@code page}, a null {@code preferred} picks the best resolution. */
    public void open(final MRL.Source source, final String title, final URI page, final MediaQuality preferred) {
        // A RUNNING DOWNLOAD SHOWS ITSELF AGAIN INSTEAD OF STARTING OVER
        this.visible = true;
        if (this.working) return;

        // PROBES STAY FOR THE SAME SOURCE, SO A DIALOG OPENED AGAIN READS NOTHING TWICE, AND ONES THAT FAILED GET ANOTHER TRY
        if (!source.equals(this.source)) {
            this.source = source;
            this.infos.clear();
            this.pending.clear();
        }
        this.failures.clear();
        this.title = title == null ? "" : title.strip();
        // A SOURCE WITHOUT A TITLE IS NAMED AFTER THE LAST PART OF ITS LINK, LESS THE EXTENSION
        this.name = this.title;
        if (this.name.isEmpty() && page != null && page.getPath() != null) {
            final String last = page.getPath().substring(page.getPath().lastIndexOf('/') + 1);
            this.name = last.lastIndexOf('.') > 0 ? last.substring(0, last.lastIndexOf('.')) : last;
        }
        final String host = page == null || page.getHost() == null ? "" : page.getHost().toLowerCase(Locale.ROOT);
        this.youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com");
        this.qualities = source.qualities().keySet().toArray(MediaQuality[]::new);
        Arrays.sort(this.qualities, Comparator.comparingInt(MediaQuality::height).reversed());
        this.quality = preferred == null ? this.qualities[0] : MediaQuality.closest(source.qualities().keySet(), preferred);
        this.done = false;
        this.error = null;
        this.progress = 0f;
        this.encoder = null;
        this.file = null;

        // THE PICKS OF THE LAST DOWNLOAD STARTED COME BACK AND THE RULES FIT THEM TO THIS SOURCE, ELSE EVERY TRACK IS COPIED
        final Picks kept = this.last;
        if (kept == null) {
            this.fps = 0;
            this.effort = FFTranscoder.Effort.BALANCED;
            this.hardware = false;
            this.preset = Preset.SOURCE;
        } else {
            this.container = kept.container();
            this.quality = MediaQuality.closest(source.qualities().keySet(), kept.quality());
            this.video = kept.video();
            this.audio = kept.audio();
            this.fps = kept.fps();
            this.effort = kept.effort();
            this.hardware = kept.hardware();
            this.videoRate = kept.videoRate();
            this.audioRate = kept.audioRate();
            this.preset = kept.preset();
        }
        this.normalize();
    }

    /** Applies a pick the dialog wrote: it leaves the preset, and whatever the pick made invalid moves to the nearest valid choice. */
    public void changed() {
        this.preset = null;
        this.normalize();
    }

    /** Applies a preset; it stays on until another pick, so a probe that lands later completes it. */
    public void preset(final Preset preset) {
        this.preset = preset;
        this.normalize();
    }

    /** Starts the download with the current picks; does nothing until {@link #ready()}. */
    public void start() {
        if (!this.ready()) return;
        final boolean videoOn = this.videoOn();
        final boolean audioOn = this.audioOn();
        final Metadata metadata = this.source.metadata();

        // THE USER'S DOWNLOADS FOLDER, OR downloads UNDER THE WORKING DIRECTORY ON A SYSTEM WITHOUT ONE
        final Path downloads = Path.of(System.getProperty("user.home", "."), "Downloads");
        final Path folder = this.folder != null ? this.folder : Files.isDirectory(downloads) ? downloads : WaterMedia.cwd().resolve("downloads");

        // THE WORKER TAKES THE FIRST FREE NAME AFTER THIS ONE
        final Path file = folder.resolve(fileName(this.name) + "." + this.container.name().toLowerCase(Locale.ROOT));
        final FFTranscoder.Job job = new FFTranscoder.Job(this.source.uri(this.quality), this.track(), this.source.headers(), this.container, file,
                videoOn ? new FFTranscoder.Video(this.video, this.video == null ? 0 : this.videoBitrate(), this.fps, this.effort, this.hardware) : null,
                audioOn ? new FFTranscoder.Audio(this.audio, this.audio == null ? 0 : this.audioBitrate()) : null,
                this.title, metadata == null ? null : metadata.author());
        this.error = null;
        this.progress = 0f;
        this.encoder = null;
        this.file = null;
        this.cancelling = false;
        this.working = true;
        this.last = new Picks(this.container, this.quality, this.video, this.audio, this.fps, this.effort, this.hardware, this.videoRate, this.audioRate, this.preset);
        this.worker = ThreadTool.thread("MediaDownloader-Transcode", () -> this.run(job));
    }

    /** Cancels a running download, or hides the dialog when none runs. */
    public void cancel() {
        if (!this.working) {
            this.visible = false;
            return;
        }
        this.cancelling = true;
        final FFTranscoder running = this.transcoder;
        if (running != null) running.cancel();
    }

    /** Returns from a finished or failed download to the options. */
    public void back() {
        this.done = false;
        this.error = null;
        this.progress = 0f;
    }

    /** Shows the folder of the written file in the system's file manager. */
    public void reveal() {
        final Path written = this.file;
        if (written == null) return;
        ThreadTool.thread("MediaDownloader-Reveal", () -> {
            try { Desktop.getDesktop().open(written.getParent().toFile()); }
            catch (final IOException | RuntimeException e) { WaterMedia.LOGGER.warn(IT, "Failed to show {}", written.getParent(), e); }
        });
    }

    // ==========================================================================
    // WHAT THE PICKS LEAD TO
    // ==========================================================================
    /** Returns what the picked resolution holds, or null until its probe lands. */
    public FFTranscoder.Info info() {
        return this.source == null ? null : this.infos.get(this.source.uri(this.quality));
    }

    /** Returns the video track of the picked resolution, or null when it has none or its probe has not landed. */
    public FFTranscoder.VideoInfo picture() {
        final FFTranscoder.Info info = this.info();
        return info == null ? null : info.video();
    }

    /** Returns the audio track the file gets: the source's separate one, else the picked resolution's own. */
    public FFTranscoder.AudioInfo sound() {
        final URI track = this.track();
        final FFTranscoder.Info info = track != null ? this.infos.get(track) : this.info();
        return info == null ? null : info.audio();
    }

    /** Returns whether a probe the picks need is still running. */
    public boolean probing() {
        return this.source != null && (this.pending.contains(this.source.uri(this.quality)) || this.pending.contains(this.track()));
    }

    /** Returns why a probe the picks need failed, or null. */
    public String failure() {
        if (this.source == null) return null;
        final String main = this.failures.get(this.source.uri(this.quality));
        return main != null ? main : this.failures.get(this.track());
    }

    /** Returns the length of the media in milliseconds, 0 while unknown and for a live one. */
    public long durationMs() {
        final FFTranscoder.Info info = this.info();
        final FFTranscoder.Info track = this.infos.get(this.track());
        return Math.max(info == null ? 0 : info.durationMs(), track == null ? 0 : track.durationMs());
    }

    /** Returns whether the file gets a video track: the container carries one and the source has it. */
    public boolean videoOn() { return !this.container.video().isEmpty() && this.picture() != null; }
    /** Returns whether the file gets an audio track. */
    public boolean audioOn() { return this.sound() != null; }

    /** Returns whether the container takes the codec; null asks for the source's track copied as it comes, which Matroska always takes. */
    public boolean allowed(final VideoCodec codec) {
        if (codec != null) return this.container.video().contains(codec);
        final FFTranscoder.VideoInfo picture = this.picture();
        return picture == null || this.container == Container.MKV || (picture.codec() != null && this.container.video().contains(picture.codec()));
    }

    /** Returns whether the container takes the codec; null asks for the source's track copied as it comes, which Matroska always takes. */
    public boolean allowed(final AudioCodec codec) {
        if (codec != null) return this.container.audio().contains(codec);
        final FFTranscoder.AudioInfo sound = this.sound();
        return sound == null || this.container == Container.MKV || (sound.codec() != null && this.container.audio().contains(sound.codec()));
    }

    /** Returns the source's video bits per second, the top of the slider; one that states none is guessed from its size. */
    public long videoMax() {
        final FFTranscoder.VideoInfo picture = this.picture();
        if (picture == null) return 0;
        if (picture.bitrate() > 0) return picture.bitrate();
        return Math.round(picture.width() * (double) picture.height() * (picture.fps() > 0 ? picture.fps() : 30f) * VIDEO_GUESS_BPP);
    }

    /** Returns the video bits per second the file gets: the source's when copied, else the slider's share of it. */
    public long videoBitrate() {
        final long max = this.videoMax();
        if (this.video == null) return max;
        final long floor = Math.min(max, Math.max(VIDEO_FLOOR_BPS, max / 20));
        return floor + Math.round((max - floor) * (double) this.videoRate);
    }

    /** Returns the top of the audio slider: the source's bits per second, under the ceiling of lossy codecs. */
    public long audioMax() {
        final FFTranscoder.AudioInfo sound = this.sound();
        if (sound == null) return 0;
        final long stated = sound.bitrate() > 0 ? sound.bitrate() : AUDIO_GUESS_BPS * Math.max(1, sound.channels());
        return this.audio == null ? stated : Math.min(stated, AUDIO_CEILING_BPS);
    }

    /** Returns the audio bits per second the file gets: the source's when copied, FLAC's usual share of PCM, else the slider's share. */
    public long audioBitrate() {
        final FFTranscoder.AudioInfo sound = this.sound();
        if (sound == null) return 0;
        final long max = this.audioMax();
        if (this.audio == null) return max;
        if (this.audio == AudioCodec.FLAC) return Math.round(sound.sampleRate() * (double) Math.max(1, sound.channels()) * 16 * FLAC_RATIO);
        final long floor = Math.min(max, AUDIO_FLOOR_BPS);
        return floor + Math.round((max - floor) * (double) this.audioRate);
    }

    /** Returns the bytes the file should take, 0 while the length is unknown. */
    public long estimate() {
        return ((this.videoOn() ? this.videoBitrate() : 0) + (this.audioOn() ? this.audioBitrate() : 0)) * this.durationMs() / 8_000L;
    }

    /** Returns whether the download can start: the probes landed, the media has an end and the file gets a track. */
    public boolean ready() {
        return !this.working && !this.done && this.source != null && !this.probing() && this.failure() == null
                && this.durationMs() > 0 && (this.videoOn() || this.audioOn());
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // ENDS A RUNNING DOWNLOAD BEFORE THE PROCESS EXITS, SO ITS UNFINISHED FILE IS DELETED
    void shutdown() {
        final Thread running = this.worker;
        if (running == null || !running.isAlive()) return;
        this.cancel();
        try { running.join(SHUTDOWN_WAIT_MS); }
        catch (final InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    // THE SOURCE'S SEPARATE AUDIO INPUT, THE ORIGINAL LANGUAGE FIRST, OR NULL WHEN THE RESOLUTIONS CARRY THEIR OWN SOUND
    private URI track() {
        return this.source == null || this.source.audioTracks().isEmpty() ? null : this.source.audioTracks().get(0).uri();
    }

    // RE-APPLIES THE ACTIVE PRESET, MOVES EVERY PICK THE CONTAINER OR THE SOURCE REFUSES TO A VALID ONE AND ASKS FOR THE PROBES
    private void normalize() {
        if (this.source == null) return;
        if (this.preset == Preset.COMPATIBILITY) {
            // THE BEST RESOLUTION UP TO 1080p, WHICH EVERY PHONE AND CHAT PLAYS, OUT OF qualities SORTED BEST FIRST
            MediaQuality pick = this.qualities[this.qualities.length - 1];
            for (int i = this.qualities.length - 1; i >= 0; i--) if (this.qualities[i].height() <= 1_080) pick = this.qualities[i];
            this.quality = pick;
        }
        final FFTranscoder.VideoInfo picture = this.picture();
        final FFTranscoder.AudioInfo sound = this.sound();
        // A SOURCE READ WHOLE WITHOUT SOUND HAS NOTHING FOR AN AUDIO CONTAINER, SO THE AUDIO PRESET TURNS INTO THE SOURCE ONE
        final boolean silent = sound == null && this.info() != null && !this.probing();
        if (silent && this.preset == Preset.AUDIO) this.preset = Preset.SOURCE;

        // THE CONTAINER BOTH TRACKS FIT AS THEY COME: WEBM FOR THE CODECS BORN IN IT, ELSE MP4, ELSE MATROSKA, WHICH CARRIES
        // ANYTHING, AND MP4 WHILE NOTHING IS READ YET
        Container natural = picture == null && sound == null ? Container.MP4 : Container.MKV;
        for (final Container candidate: new Container[] { Container.MP4, Container.WEBM }) {
            final boolean video = picture == null || (picture.codec() != null && candidate.video().contains(picture.codec()));
            final boolean audio = sound == null || (sound.codec() != null && candidate.audio().contains(sound.codec()));
            if ((picture != null || sound != null) && video && audio) natural = candidate;
        }

        if (this.preset != null) {
            this.videoRate = 1f;
            this.audioRate = 1f;
            // THE AUDIO CONTAINER MADE FOR THE SOURCE'S CODEC, SO ITS SOUND IS COPIED WHENEVER ONE EXISTS
            final Container soundOnly = sound == null || sound.codec() == null ? Container.M4A : switch (sound.codec()) {
                case OPUS, FLAC -> Container.OGG;
                case MP3 -> Container.MP3;
                case AAC, PCM -> Container.M4A;
            };
            switch (this.preset) {
                case SOURCE -> {
                    this.video = null;
                    this.audio = null;
                    this.fps = 0;
                    this.container = picture == null && this.info() != null ? soundOnly : natural;
                }
                case COMPATIBILITY -> {
                    // H.264 AND AAC IN MP4: A TRACK ALREADY IN THEM IS COPIED, ANY OTHER IS ENCODED AGAIN
                    this.container = Container.MP4;
                    this.video = picture != null && picture.codec() == VideoCodec.H264 ? null : VideoCodec.H264;
                    this.audio = sound != null && sound.codec() == AudioCodec.AAC ? null : AudioCodec.AAC;
                    this.fps = picture != null && picture.fps() > 60.5f ? 60 : 0;
                }
                case AUDIO -> {
                    this.container = soundOnly;
                    this.audio = null;
                }
            }
        }
        if (silent && this.container.video().isEmpty()) this.container = natural;

        if (!this.container.video().isEmpty() && !this.allowed(this.video)) {
            VideoCodec pick = null;
            if (!this.allowed((VideoCodec) null)) for (final VideoCodec codec: VIDEO_FALLBACK) if (pick == null && this.container.video().contains(codec)) pick = codec;
            this.video = pick;
        }
        if (!this.allowed(this.audio)) {
            AudioCodec pick = null;
            if (!this.allowed((AudioCodec) null)) for (final AudioCodec codec: AUDIO_CODECS) if (pick == null && this.container.audio().contains(codec)) pick = codec;
            this.audio = pick;
        }
        // A LIMIT AT OR ABOVE THE SOURCE'S RATE LIMITS NOTHING
        if (this.fps > 0 && picture != null && picture.fps() > 0 && this.fps >= Math.round(picture.fps())) this.fps = 0;

        // THE PICKED RESOLUTION AND THE AUDIO TRACK ARE PROBED ONCE EACH, AND A PROBE THAT LANDS RUNS THE RULES AGAIN ON THE RENDER THREAD
        final MRL.Source source = this.source;
        final HttpHeaders headers = source.headers();
        for (final URI uri: new URI[] { source.uri(this.quality), this.track() }) {
            if (uri == null || this.infos.containsKey(uri) || this.failures.containsKey(uri) || !this.pending.add(uri)) continue;
            ThreadTool.thread("MediaDownloader-Probe", () -> {
                FFTranscoder.Info read = null;
                String failed = null;
                try {
                    read = FFTranscoder.probe(uri, headers);
                } catch (final IOException | RuntimeException e) {
                    WaterMedia.LOGGER.warn(IT, "Failed to probe {}", uri, e);
                    failed = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final FFTranscoder.Info info = read;
                final String failure = failed;
                this.render.execute(() -> {
                    // A PROBE OF A SOURCE THE DIALOG ALREADY LEFT IS DROPPED
                    if (this.source != source) return;
                    this.pending.remove(uri);
                    if (info != null) this.infos.put(uri, info);
                    else this.failures.put(uri, failure);
                    this.normalize();
                });
            });
        }
    }

    // TRANSCODE WORKER: THE RESULT GOES BACK TO THE RENDER THREAD, WHICH OWNS THE DIALOG STATE
    private void run(final FFTranscoder.Job wanted) {
        boolean written = false;
        String failed = null;
        Path reserved = null;
        try {
            // THE FIRST NAME NO FILE HAS, HELD BY AN EMPTY FILE UNTIL THE DOWNLOAD REPLACES IT, SO NOTHING SAVED MEANWHILE IS LOST
            final Path folder = wanted.file().toAbsolutePath().getParent();
            final String name = wanted.file().getFileName().toString();
            final int dot = name.lastIndexOf('.');
            Files.createDirectories(folder);
            for (int n = 0; reserved == null; n++) {
                try { reserved = Files.createFile(folder.resolve(n == 0 ? name : name.substring(0, dot) + " (" + n + ")" + name.substring(dot))); }
                catch (final FileAlreadyExistsException taken) { /* THE NEXT NAME */ }
            }
            final FFTranscoder.Job job = new FFTranscoder.Job(wanted.input(), wanted.track(), wanted.headers(), wanted.container(), reserved,
                    wanted.video(), wanted.audio(), wanted.title(), wanted.author());
            final FFTranscoder created = new FFTranscoder(job, () -> {
                final FFTranscoder running = this.transcoder;
                if (running == null) return;
                this.progress = running.progress();
                this.encoder = running.encoder();
                this.render.execute(WAKE);
            });
            this.transcoder = created;
            // A CANCEL THAT ARRIVED BEFORE THE TRANSCODER EXISTED
            if (this.cancelling) created.cancel();
            written = created.run();
        } catch (final IOException | RuntimeException e) {
            WaterMedia.LOGGER.error(IT, DataTool.errCode(WaterMedia.ERR_APP_DOWNLOAD_RUN) + "Failed to download {}", wanted.input(), e);
            failed = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        // A DOWNLOAD THAT WROTE NOTHING GIVES ITS NAME BACK, UNLESS SOMETHING ELSE ALREADY FILLED THAT FILE
        if (!written && reserved != null) {
            try { if (Files.size(reserved) == 0) Files.delete(reserved); }
            catch (final IOException e) { WaterMedia.LOGGER.warn(IT, "Failed to delete the reserved name {}", reserved, e); }
        }
        final boolean finished = written;
        final String failure = failed;
        final Path file = reserved;
        this.render.execute(() -> {
            this.transcoder = null;
            this.done = finished;
            this.error = failure;
            if (finished) {
                this.file = file;
                this.progress = 1f;
            }
            this.working = false;
        });
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    /** Formats bits per second as {@code 128 kbps} or {@code 4.20 Mbps}. */
    public static String rate(final long bps) {
        return bps >= 1_000_000L ? String.format(Locale.ROOT, "%.2f Mbps", bps / 1_000_000.0) : Math.round(bps / 1_000.0) + " kbps";
    }

    // A FILE NAME OUT OF A TITLE: NO SEPARATOR OR CHARACTER A FILE SYSTEM REFUSES, NO TRAILING DOT AND NO WINDOWS DEVICE
    static String fileName(final String title) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < title.length() && out.length() < NAME_LIMIT_CHARS; ) {
            final int point = title.codePointAt(i);
            i += Character.charCount(point);
            // A LONE SURROGATE HAS NO UTF-8 FORM, AND THE PATH REACHES FFMPEG AS UTF-8
            final boolean refused = point < ' ' || "\\/:*?\"<>|".indexOf(point) >= 0 || !Character.isDefined(point)
                    || (point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
            out.appendCodePoint(refused ? ' ' : point);
        }
        String name = out.toString().strip().replaceAll(" {2,}", " ");
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1).strip();
        return name.isEmpty() || RESERVED.matcher(name).matches() ? "watermedia-" + (name.isEmpty() ? "download" : name) : name;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Shortcut of picks: the source as it comes, the widest playback, or the sound alone. */
    public enum Preset { SOURCE, COMPATIBILITY, AUDIO }

    /** The picks a download started with: a null codec copies the track, 0 fps keeps the source's and a null preset means none was on. */
    public record Picks(Container container, MediaQuality quality, VideoCodec video, AudioCodec audio, int fps, FFTranscoder.Effort effort,
                        boolean hardware, float videoRate, float audioRate, Preset preset) {}
}
