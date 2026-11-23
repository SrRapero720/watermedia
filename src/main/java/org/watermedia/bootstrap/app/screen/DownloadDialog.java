package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.codecs.AudioCodec;
import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.VideoCodec;
import org.watermedia.api.codecs.encoders.FFTranscoder;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.bootstrap.app.MediaDownloader;
import org.watermedia.bootstrap.app.MediaSession;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.ChoiceGrid;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentScroll;
import org.watermedia.bootstrap.app.element.ProgressBar;
import org.watermedia.bootstrap.app.element.SectionHead;
import org.watermedia.bootstrap.app.element.SeekBar;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.Theme;
import org.watermedia.bootstrap.app.screen.BaseScreen.Keybind;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The download dialog, over any screen: stacked sections of choice buttons (container, resolution, codecs, frame
 * rate, compression, encoder and presets), the bitrate sliders and the size the file should take, then the progress
 * of the download. It only binds {@code ctx.download}, which owns the picks and the work.
 */
public final class DownloadDialog extends Group<DownloadDialog> {
    // FOOTER SHORTCUTS OF EACH STAGE, AS CONSTANT LISTS SO THE FOOTER ALLOCATES NOTHING PER FRAME
    private static final List<Keybind> KEYS_PICKING = List.of(new Keybind("ENTER", "Download"), new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_WORKING = List.of(new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_DONE = List.of(new Keybind("ENTER/ESC", "Close"));
    private static final List<Keybind> KEYS_FAILED = List.of(new Keybind("ENTER", "Close"), new Keybind("ESC", "Back"));

    private static final int PANEL_W = 460;
    private static final int SECTION_GAP = 14;
    // VIDEO CODEC CELLS: THE SOURCE'S TRACK AS IT COMES FIRST, AND AV2 SHOWN LOCKED UNTIL THE NATIVES BRING AN ENCODER FOR IT
    private static final String[] VIDEO_LABELS = { "SRC", "H264", "H265", "AV1", "AV2", "VP9" };
    private static final VideoCodec[] VIDEO_CELLS = { null, VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, null, VideoCodec.VP9 };
    private static final int AV2_CELL = 4;
    private static final String[] AUDIO_LABELS = { "SRC", "AAC", "OPUS", "MP3", "FLAC" };
    private static final AudioCodec[] AUDIO_CELLS = { null, AudioCodec.AAC, AudioCodec.OPUS, AudioCodec.MP3, AudioCodec.FLAC };

    private final Dialog dialog;
    private final ParentScroll options;
    private final Parent status;
    private final ChoiceGrid containers = new ChoiceGrid();
    private final SectionHead resolutionHead = new SectionHead("Resolution").compact();
    private final ChoiceGrid resolutions = new ChoiceGrid();
    private final Parent videoSection;
    private final SectionHead videoHead = new SectionHead("Video codec").compact();
    private final ChoiceGrid videoCodecs = new ChoiceGrid().cells(VIDEO_LABELS);
    private final SectionHead audioHead = new SectionHead("Audio codec").compact();
    private final ChoiceGrid audioCodecs = new ChoiceGrid().cells(AUDIO_LABELS);
    private final SectionHead audioRateHead = new SectionHead("Audio bitrate").compact();
    private final SeekBar audioRate = new SeekBar().trackHeight(6);
    private final Parent videoRateSection;
    private final SectionHead videoRateHead = new SectionHead("Video bitrate").compact();
    private final SeekBar videoRate = new SeekBar().trackHeight(6);
    private final Parent rateSection;
    private final SectionHead rateHead = new SectionHead("Frame rate").compact();
    private final ChoiceGrid rates = new ChoiceGrid();
    private final Parent effortSection;
    private final ChoiceGrid efforts = new ChoiceGrid().cells("FAST", "BALANCED", "BEST");
    private final Parent encoderSection;
    private final ChoiceGrid encoders = new ChoiceGrid().cells("SOFTWARE", "HARDWARE");
    private final ChoiceGrid presets = new ChoiceGrid().columns(2);
    private final Text size = new Text().scale(AppTheme.TEXT_BUTTON).bold(true).width(MAX_PARENT);
    private final Text headline = new Text().scale(AppTheme.TEXT_BUTTON).bold(true).width(MAX_PARENT);
    private final ProgressBar bar = new ProgressBar();
    private final Text detail = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(MAX_PARENT);
    private final Button cancel = new Button();
    private final Button primary = new Button().subText("ENTER");
    // WHAT THE RESOLUTION AND PRESET CELLS WERE BUILT FOR, SO THEY ARE REBUILT ONLY WHEN THE SOURCE CHANGES
    private MediaQuality[] shownQualities;
    private boolean shownYoutube;
    private boolean wasVisible;

    public DownloadDialog() {
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;

        final String[] names = new String[MediaDownloader.CONTAINERS.length];
        for (int i = 0; i < names.length; i++) names[i] = MediaDownloader.CONTAINERS[i].name();
        this.containers.cells(names).onPick(this.pick(i -> this.ctx.download.container = MediaDownloader.CONTAINERS[i]));
        this.resolutions.onPick(this.pick(i -> this.ctx.download.quality = this.ctx.download.qualities[i]));
        this.videoCodecs.onPick(this.pick(i -> this.ctx.download.video = VIDEO_CELLS[i]));
        this.audioCodecs.onPick(this.pick(i -> this.ctx.download.audio = AUDIO_CELLS[i]));
        final String[] limits = new String[MediaDownloader.FRAME_RATES.length + 1];
        limits[0] = "SRC";
        for (int i = 1; i < limits.length; i++) limits[i] = String.valueOf(MediaDownloader.FRAME_RATES[i - 1]);
        this.rates.cells(limits).onPick(this.pick(i -> this.ctx.download.fps = i == 0 ? 0 : MediaDownloader.FRAME_RATES[i - 1]));
        this.audioRate.onChange(v -> {
            this.ctx.download.audioRate = v;
            this.ctx.download.changed();
        });
        this.videoRate.onChange(v -> {
            this.ctx.download.videoRate = v;
            this.ctx.download.changed();
        });
        // THE EFFORT AND THE ENCODER CHANGE NO OTHER PICK, SO A PRESET STAYS ON
        this.efforts.onPick(i -> {
            this.ctx.download.effort = FFTranscoder.Effort.values()[i];
            this.ctx.sound.play();
        });
        this.encoders.onPick(i -> {
            this.ctx.download.hardware = i == 1;
            this.ctx.sound.play();
        });
        this.presets.onPick(i -> {
            this.ctx.download.preset(MediaDownloader.Preset.values()[i]);
            this.ctx.sound.play();
        });

        this.videoSection = section(this.videoHead, this.videoCodecs);
        this.videoRateSection = section(this.videoRateHead, this.videoRate);
        this.rateSection = section(this.rateHead, this.rates);
        this.effortSection = section(new SectionHead("Compression").compact(), this.efforts);
        this.encoderSection = section(new SectionHead("Encoder").compact(), this.encoders);
        // THE RIGHT PADDING KEEPS THE CELLS CLEAR OF THE SCROLLBAR, WHICH PAINTS OVER THE CONTENT
        final Parent column = Parent.column().spacing(SECTION_GAP).width(MAX_PARENT).padding(new Spacing(0, Theme.SPACE_MD, 0, 0))
                .add(section(new SectionHead("Container").compact(), this.containers))
                .add(section(this.resolutionHead, this.resolutions))
                .add(this.videoSection)
                .add(section(this.audioHead, this.audioCodecs))
                .add(section(this.audioRateHead, this.audioRate))
                .add(this.videoRateSection)
                .add(this.rateSection)
                .add(this.effortSection)
                .add(this.encoderSection)
                .add(section(new SectionHead("Presets").compact(), this.presets))
                .add(this.size);
        this.options = new ParentScroll().width(MAX_PARENT).add(column);
        this.status = Parent.column().spacing(Theme.SPACE_MD).width(MAX_PARENT).add(this.headline).add(this.bar).add(this.detail);

        // THE LEFT BUTTON CANCELS LIKE ESC UNTIL THE DOWNLOAD ENDS, THEN IT SHOWS THE FOLDER, WHICH NO KEY DOES
        this.cancel.height(Theme.BUTTON_LG).onClick(b -> {
            if (this.ctx.download.done) {
                this.ctx.download.reveal();
                this.ctx.sound.play();
            } else {
                this.dismiss();
            }
        });
        this.primary.height(Theme.BUTTON_LG).onClick(b -> this.confirm());
        this.dialog = new Dialog()
                .title("DOWNLOAD")
                .content(this.options)
                .content(this.status)
                .onDismiss(this::dismiss)
                .onPrimary(this::confirm)
                .actions(this.cancel, this.primary)
                .visible(false);
        // A CLICK BETWEEN THE CELLS MUST NOT FALL THROUGH TO THE SCRIM
        this.dialog.panel().consumeTouch(true);
        this.add(this.dialog);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        final MediaDownloader download = this.ctx.download;
        if (download.visible != this.wasVisible) {
            this.wasVisible = download.visible;
            // EVERY OPENING STARTS AT THE TOP
            if (download.visible) this.options.scrollTo(0);
            this.invalidate();
        }
        this.dialog.visible(download.visible);
        if (!download.visible) return;

        final boolean failed = download.error != null && !download.working;
        final boolean picking = !download.working && !download.done && !failed;
        this.dialog.title(download.done ? "DOWNLOADED" : failed ? "DOWNLOAD FAILED" : download.working ? "DOWNLOADING" : "DOWNLOAD")
                .accent(failed ? AppTheme.RED : download.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(PANEL_W, this.ctx.logicalWidth() - 48));
        this.options.visible(picking);
        this.status.visible(!picking);

        // FOOTER: CANCEL HIDES THE DIALOG OR STOPS THE DOWNLOAD, AND ONCE IT ENDED THE PAIR SHOWS THE FOLDER OR GOES BACK, AND CLOSES
        final boolean ready = download.ready();
        final Color go = picking && !ready ? AppTheme.TEXT_FAINT : download.done ? AppTheme.GREEN : AppTheme.CYAN;
        this.cancel.label(download.done ? "OPEN FOLDER" : failed ? "BACK" : "CANCEL").subText(download.done ? "" : "ESC")
                .icon(download.done ? "folder" : failed ? "arrow-left" : "x").accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.primary.visible(!download.working).label(picking ? "DOWNLOAD" : "CLOSE").icon(picking ? "download" : "check")
                .accent(go).textColor(go).enabled(!picking || ready);

        if (!picking) {
            // THE TITLE ALREADY SAYS HOW IT WENT: THE LINES BELOW CARRY THE PERCENT, THE FILE AND ITS FOLDER, OR THE CAUSE
            final Path file = download.file;
            final Color tone = failed ? AppTheme.RED : download.done ? AppTheme.GREEN : AppTheme.NEON_LIGHT;
            this.headline.visible(!failed).color(tone)
                    .text(download.done && file != null ? file.getFileName().toString() : Math.round(MathUtil.clamp(download.progress, 0f, 1f) * 100f) + "%");
            this.bar.progress(download.done ? 1f : download.progress).accent(tone);
            this.detail.text(failed ? download.error : download.done && file != null ? String.valueOf(file.getParent())
                    : download.encoder != null ? "ENCODER " + download.encoder.toUpperCase(Locale.ROOT) : "");
            return;
        }

        // CELLS THAT DEPEND ON THE SOURCE
        if (download.qualities != this.shownQualities || download.youtube != this.shownYoutube) {
            this.shownQualities = download.qualities;
            this.shownYoutube = download.youtube;
            final String[] labels = new String[download.qualities.length];
            for (int i = 0; i < labels.length; i++) labels[i] = download.qualities[i] == MediaQuality.UNKNOWN ? "SOURCE" : download.qualities[i].height() + "p";
            this.resolutions.cells(labels);
            this.presets.cells(download.youtube ? "FROM YOUTUBE" : "FROM SOURCE", "BEST COMPATIBILITY", "BEST AUDIO");
        }

        final FFTranscoder.Info info = download.info();
        final FFTranscoder.VideoInfo picture = download.picture();
        final FFTranscoder.AudioInfo sound = download.sound();
        final boolean known = info != null && !download.probing();
        final boolean carriesVideo = !download.container.video().isEmpty();
        final boolean encodes = download.videoOn() && download.video != null;

        // AN AUDIO CONTAINER IS LOCKED FOR A SOURCE WITHOUT SOUND
        for (int i = 0; i < MediaDownloader.CONTAINERS.length; i++) {
            final Container container = MediaDownloader.CONTAINERS[i];
            this.containers.locked(i, known && sound == null && container.video().isEmpty());
            if (container == download.container) this.containers.picked(i);
        }
        for (int i = 0; i < download.qualities.length; i++) if (download.qualities[i] == download.quality) this.resolutions.picked(i);
        this.resolutionHead.count(picture != null ? picture.width() + "x" + picture.height() : "");

        this.videoSection.visible(carriesVideo);
        this.videoHead.count(picture != null ? "SOURCE " + picture.name().toUpperCase(Locale.ROOT) : "");
        for (int i = 0; i < VIDEO_CELLS.length; i++) {
            this.videoCodecs.locked(i, i == AV2_CELL || (known && picture == null) || !download.allowed(VIDEO_CELLS[i]));
            if (i != AV2_CELL && VIDEO_CELLS[i] == download.video) this.videoCodecs.picked(i);
        }
        this.audioHead.count(sound != null ? "SOURCE " + sound.name().toUpperCase(Locale.ROOT) + " " + sound.channels() + "CH" : "");
        for (int i = 0; i < AUDIO_CELLS.length; i++) {
            this.audioCodecs.locked(i, (known && sound == null) || !download.allowed(AUDIO_CELLS[i]));
            if (AUDIO_CELLS[i] == download.audio) this.audioCodecs.picked(i);
        }

        // A SLIDER ONLY MOVES FOR A TRACK THAT IS ENCODED AGAIN WITH A BITRATE: A COPY KEEPS THE SOURCE'S AND FLAC HAS NONE
        final boolean audioSlides = sound != null && download.audio != null && download.audio != AudioCodec.FLAC;
        this.audioRateHead.count(sound == null ? "" : download.audio == AudioCodec.FLAC ? "LOSSLESS" : MediaDownloader.rate(download.audioBitrate()));
        this.audioRate.enabled(audioSlides).accent(audioSlides ? AppTheme.NEON : AppTheme.TEXT_FAINT).value(audioSlides ? download.audioRate : 1f);
        this.videoRateSection.visible(carriesVideo);
        this.videoRateHead.count(picture == null ? "" : MediaDownloader.rate(download.videoBitrate()));
        this.videoRate.enabled(encodes).accent(encodes ? AppTheme.NEON : AppTheme.TEXT_FAINT).value(encodes ? download.videoRate : 1f);

        // FRAME RATE, EFFORT AND ENCODER ONLY APPLY TO A VIDEO ENCODED AGAIN, AND A RATE AT OR ABOVE THE SOURCE'S LIMITS NOTHING
        this.rateSection.visible(carriesVideo);
        this.rateHead.count(picture != null && picture.fps() > 0 ? "SOURCE " + Math.round(picture.fps()) + " FPS" : "");
        this.rates.locked(0, !encodes).picked(download.fps == 0 ? 0 : -1);
        for (int i = 0; i < MediaDownloader.FRAME_RATES.length; i++) {
            final int rate = MediaDownloader.FRAME_RATES[i];
            this.rates.locked(i + 1, !encodes || (picture.fps() > 0 && rate >= Math.round(picture.fps())));
            if (rate == download.fps) this.rates.picked(i + 1);
        }
        this.effortSection.visible(carriesVideo);
        this.efforts.enabled(encodes).picked(download.effort.ordinal());
        this.encoderSection.visible(carriesVideo);
        this.encoders.enabled(encodes).picked(download.hardware ? 1 : 0);
        this.presets.locked(MediaDownloader.Preset.AUDIO.ordinal(), known && sound == null).picked(download.preset == null ? -1 : download.preset.ordinal());

        // THE SIZE LINE ALSO SAYS WHY NOTHING CAN BE DOWNLOADED YET
        final String failure = download.failure();
        if (failure != null) this.size.text(failure).color(AppTheme.RED);
        else if (download.probing() || info == null) this.size.text("READING SOURCE").color(AppTheme.TEXT_FAINT);
        else if (download.durationMs() <= 0) this.size.text("LIVE MEDIA HAS NO END").color(AppTheme.AMBER);
        else if (!download.videoOn() && !download.audioOn()) this.size.text("NO TRACK TO DOWNLOAD").color(AppTheme.AMBER);
        else this.size.text("~ " + MathUtil.displayBytes(download.estimate()) + "   " + MediaSession.formatTime(download.durationMs())).color(AppTheme.CYAN);
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.dialog.measure(innerAvailWidth, innerAvailHeight);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        this.dialog.layout(this.left, this.top);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // ESC AND THE CLOSE BUTTON: STOP A RUNNING DOWNLOAD, GO BACK FROM A FAILED ONE TO THE OPTIONS, ELSE HIDE THE DIALOG
    private void dismiss() {
        final MediaDownloader download = this.ctx.download;
        if (download.done) {
            download.back();
            download.visible = false;
        } else if (download.error != null && !download.working) {
            download.back();
        } else {
            download.cancel();
        }
        this.ctx.sound.play();
    }

    // ENTER AND THE RIGHT FOOTER BUTTON: START THE DOWNLOAD, OR CLOSE ONCE IT ENDED
    private void confirm() {
        final MediaDownloader download = this.ctx.download;
        if (download.working) return;
        if (download.done || download.error != null) {
            download.back();
            download.visible = false;
        } else {
            if (!download.ready()) return;
            download.start();
        }
        this.ctx.sound.play();
    }

    // A PICK THAT MOVES OTHER CHOICES: WRITE IT, LET THE RULES RUN AND CHIME
    private IntConsumer pick(final IntConsumer write) {
        return cell -> {
            write.accept(cell);
            this.ctx.download.changed();
            this.ctx.sound.play();
        };
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    /** Returns the footer shortcuts of the stage the dialog is in. */
    public static List<Keybind> keybinds(final MediaDownloader download) {
        if (download.working) return KEYS_WORKING;
        if (download.done) return KEYS_DONE;
        return download.error != null ? KEYS_FAILED : KEYS_PICKING;
    }

    private static Parent section(final SectionHead head, final Element<?> body) {
        return Parent.column().spacing(Theme.SPACE_SM).width(MAX_PARENT).add(head).add(body);
    }
}
