package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.TxMediaPlayer;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.MediaSession;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Dropdown;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.Icon;
import org.watermedia.bootstrap.app.element.IconButton;
import org.watermedia.bootstrap.app.element.ListRow;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.element.SectionHead;
import org.watermedia.bootstrap.app.element.SeekBar;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.element.VideoSurface;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.awt.Color;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import static org.lwjgl.glfw.GLFW.*;
import static org.watermedia.bootstrap.app.render.RenderSystem.mediaEngineSupplier;

/**
 * Plays the selected media under a HUD with the info row, metrics panel and transport bar, plus modal
 * source/quality and video-settings dialogs.
 */
public final class PlayerScreen extends Screen {
    private static final float META_SCALE = AppTheme.TEXT_SECTION;
    private static final float META_HEAD_SCALE = AppTheme.TEXT_DISPLAY;
    private static final float META_DESC_LABEL_SCALE = AppTheme.TEXT_BUTTON;
    private static final float META_DESC_SCALE = AppTheme.TEXT_SECTION;
    private static final int RES_FIELD_MAX_DIGITS = 5;
    // SPEED DROPDOWN PRESETS; INDEX 3 (1.0x) IS THE DEFAULT
    private static final float[] SPEED_VALUES = {0.25f, 0.50f, 0.75f, 1.0f, 1.25f, 1.50f, 1.75f, 2.0f, 2.5f, 3.0f, 4.0f};
    private static final String[] SPEED_LABELS = {"0.25x", "0.50x", "0.75x", "1.0x", "1.25x", "1.50x", "1.75x", "2.0x", "2.5x", "3.0x", "4.0x"};
    // MUST LIST EVERY METRICS PANEL LABEL: THE WIDEST ONE SIZES THE VALUE COLUMN SO NO LABEL OVERLAPS ITS VALUE
    private static final String[] METRIC_LABELS = {"Engine", "MRL", "Source", "FPS", "Status", "Time",
            "Volume", "Quality", "Dimensions", "Speed", "Live", "Title", "Author", "Published"};
    // LOGICAL PX PER WHEEL NOTCH, THE SAME STEP AS ScrollState
    private static final int METRIC_SCROLL_STEP = 48;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());
    // A PAUSED PLAYER KEEPS PAINTING THIS LONG AFTER INPUT SO A PAUSED SEEK SHOWS ITS FRAME ON VULKAN,
    // WHOSE ENGINE DOES NOT WAKE THE RENDER LOOP WHEN A FRAME LANDS
    private static final long SETTLE_NS = 1_000_000_000L;
    private static final List<Keybind> KEYS_VIDEO = List.of(new Keybind("TYPE", "Resolution"), new Keybind("TAB", "Field"),
            new Keybind("UP/DOWN", "LOD"), new Keybind("ENTER/ESC", "Close"));
    private static final List<Keybind> KEYS_QUALITY = List.of(new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"),
            new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_SOURCES = List.of(new Keybind("SPACE", "Play/Pause"), new Keybind("L", "Loop"),
            new Keybind("V", "Video"), new Keybind("ESC", "Menu"), new Keybind("ENTER", "Sources"));
    private static final List<Keybind> KEYS_QUALITIES = List.of(new Keybind("SPACE", "Play/Pause"), new Keybind("L", "Loop"),
            new Keybind("V", "Video"), new Keybind("ESC", "Menu"), new Keybind("ENTER", "Quality"));
    // HUD EDGE FADE AND TOP-BAND GRADIENT STOPS
    private static final Color FADE_DARK = new Color(0, 0, 0, 230);
    private static final Color FADE_CLEAR = new Color(0, 0, 0, 0);
    private static final Color BAND_DARK = new Color(6, 9, 26, 235);
    private static final Color BAND_CLEAR = new Color(6, 9, 26, 0);

    private final Consumer<HomeScreen.Action> navigator;

    // LIVE PLAYBACK STATE
    private boolean debugOpen = true;
    private boolean endedSoundPlayed;
    private long settleUntil;
    private boolean loopEnabled = true;
    private float speed = 1f;
    // KEY WHOSE PRESS CLOSED THE SPEED MENU: ONLY ITS RELEASE IS SWALLOWED SO ONE TAP DOES NOT ALSO
    // LEAVE THE SCREEN (-1 = NONE)
    private int swallowKey = -1;
    private String resWidthText = "";
    private String resHeightText = "";
    private int lodSelectedIndex;
    // SOURCE WHOSE QUALITIES THE QUALITY DIALOG LISTS; HOVER MOVES IT WITHOUT SWITCHING PLAYBACK
    private int sourcePreviewIndex;
    private int qualitySelectedIndex;
    // SCROLLS THE SELECTED SOURCE INTO VIEW ONCE THE LIST HAS A VIEWPORT, THEN NEVER AGAIN SO WHEEL
    // AND SCROLLBAR SCROLLING ARE NOT STOLEN BACK
    private boolean sourceFollowPending;

    // TREE
    private Dialog qualityDialog;
    private ListView<MRL.Source> srcList;
    private ListView<MediaQuality> qualList;
    private Parent qualLeftCol;
    private Parent qualRightCol;
    private Box qualDivider;
    private SectionHead qualHead;
    private Dialog videoDialog;
    private TextField resWidthField;
    private TextField resHeightField;
    private Text activeMaxText;
    private Parent videoLeftCol;
    private Parent videoRightCol;
    private Box videoDivider;

    public PlayerScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public void onEnter() {
        super.onEnter();
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.debugOpen = true;
        this.endedSoundPlayed = false;
        this.loopEnabled = true;
        this.speed = 1f;
        this.swallowKey = -1;
        this.resWidthText = "";
        this.resHeightText = "";
        this.lodSelectedIndex = 0;
        this.startPlayer();
    }

    @Override
    public void onExit() {
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.ctx.media.releasePlayer();
    }

    @Override
    public void releaseMedia() {
        this.ctx.media.releasePlayer();
    }

    @Override
    public boolean continuous() {
        if (this.textInputActive()) return true;
        final MediaPlayer player = this.ctx.media.player;
        if (player == null) return false;
        return switch (player.status()) {
            case WAITING, LOADING, BUFFERING -> true;
            // A STILL PICTURE HAS NO TIMELINE: ONCE SHOWN IT NEVER CHANGES
            case PLAYING -> !(player instanceof TxMediaPlayer) || player.duration() > 0L;
            case PAUSED -> System.nanoTime() < this.settleUntil;
            case ENDED -> !this.endedSoundPlayed;
            case STOPPED, ERROR -> false;
        };
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        this.settleUntil = System.nanoTime() + SETTLE_NS;
        return super.dispatchClick(mx, my);
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.videoDialog != null) return KEYS_VIDEO;
        if (this.qualityDialog != null) return KEYS_QUALITY;
        return this.ctx.media.availableSources != null && this.ctx.media.availableSources.length > 1 ? KEYS_SOURCES : KEYS_QUALITIES;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        this.settleUntil = System.nanoTime() + SETTLE_NS;
        // THE TREE (INCLUDING OPEN DIALOGS) ALWAYS WINS FIRST. THE SPEED MENU CLOSES ON THE ESC/ENTER PRESS; IF THIS
        // PRESS CLOSED IT, ITS RELEASE IS SWALLOWED SO ONE TAP DOES NOT ALSO FIRE A SCREEN SHORTCUT
        final boolean menuOpen = this.speedMenuOpen();
        if (super.dispatchKey(key, action)) {
            if (menuOpen && action != GLFW_RELEASE && !this.speedMenuOpen()) this.swallowKey = key;
            return true;
        }
        if (action != GLFW_RELEASE) {
            // A FRESH PRESS DROPS A STALE SWALLOW (E.G. THE MOUSE CLOSED THE MENU); SHORTCUTS ACT ON RELEASE
            this.swallowKey = -1;
            return false;
        }
        if (key == this.swallowKey) {
            this.swallowKey = -1;
            return true;
        }
        // NO SCREEN SHORTCUT FIRES WHILE THE SPEED MENU IS OPEN
        if (menuOpen) return false;
        // ESC GOES BEFORE THE PLAYER CHECK SO IT LEAVES EVEN WHEN PLAYER CREATION FAILED
        if (key == GLFW_KEY_ESCAPE) {
            this.returnToMenu();
            return true;
        }
        final MediaPlayer player = this.ctx.media.player;
        if (player == null) return false;
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openQualityDialog();
            case GLFW_KEY_V -> this.openVideoDialog();
            case GLFW_KEY_SPACE -> this.togglePlayback(player);
            case GLFW_KEY_LEFT -> player.rewind();
            case GLFW_KEY_RIGHT -> player.forward();
            case GLFW_KEY_U -> player.seekQuick(player.time() + 5_000);
            case GLFW_KEY_Y -> player.seekQuick(player.time() - 5_000);
            case GLFW_KEY_S -> player.stop();
            case GLFW_KEY_UP -> player.volume(player.volume() + 5);
            case GLFW_KEY_DOWN -> player.volume(player.volume() - 5);
            case GLFW_KEY_PERIOD -> player.nextFrame();
            case GLFW_KEY_COMMA -> player.previousFrame();
            case GLFW_KEY_N -> this.navigateSource(1);
            case GLFW_KEY_B -> this.navigateSource(-1);
            case GLFW_KEY_L -> this.toggleLoop(player);
            case GLFW_KEY_0, GLFW_KEY_KP_0 -> this.seekToPercent(0);
            case GLFW_KEY_1, GLFW_KEY_KP_1 -> this.seekToPercent(10);
            case GLFW_KEY_2, GLFW_KEY_KP_2 -> this.seekToPercent(20);
            case GLFW_KEY_3, GLFW_KEY_KP_3 -> this.seekToPercent(30);
            case GLFW_KEY_4, GLFW_KEY_KP_4 -> this.seekToPercent(40);
            case GLFW_KEY_5, GLFW_KEY_KP_5 -> this.seekToPercent(50);
            case GLFW_KEY_6, GLFW_KEY_KP_6 -> this.seekToPercent(60);
            case GLFW_KEY_7, GLFW_KEY_KP_7 -> this.seekToPercent(70);
            case GLFW_KEY_8, GLFW_KEY_KP_8 -> this.seekToPercent(80);
            case GLFW_KEY_9, GLFW_KEY_KP_9 -> this.seekToPercent(90);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected Element<?> build() {
        return new ParentFrame().add(new VideoSurface()).add(new CrtOverlay()).add(new Hud());
    }

    @Override
    protected void onUpdate() {
        final MediaPlayer player = this.ctx.media.player;
        // CHIME ONCE PER ENDING, ONLY WHILE NO DIALOG IS OPEN
        if (player != null && this.qualityDialog == null && this.videoDialog == null) {
            if (player.ended()) {
                if (!this.endedSoundPlayed) {
                    this.ctx.sound.play();
                    this.endedSoundPlayed = true;
                }
            } else if (!player.stopped() && !player.error()) {
                this.endedSoundPlayed = false;
            }
        }
        if (this.qualityDialog != null) this.syncQualityDialog();
        if (this.videoDialog != null) this.syncVideoDialog();
    }

    // ==========================================================================
    // PLAYER CONTROL
    // ==========================================================================

    private void startPlayer() {
        if (this.ctx.media.selectedMRL == null || this.ctx.media.selectedSource == null) return;

        this.ctx.media.releasePlayer();

        this.ctx.media.player = PlayerAPI.player(
                this.ctx.media.selectedMRL,
                this.ctx.media.selectedSourceIndex,
                // THE ACTIVE RENDER BACKEND PICKS THE ENGINE (GLEngine OR VKEngine)
                mediaEngineSupplier(Thread.currentThread(), this.ctx),
                PlayerAPI::alEngine
        );

        if (this.ctx.media.player == null) {
            this.ctx.showError("Player Error",
                    "WaterMedia failed to create media player.\nNo compatible player engine available.",
                    this::returnToMenu);
            return;
        }

        this.ctx.media.player.quality(this.ctx.media.selectedQuality);
        this.ctx.media.player.repeat(this.loopEnabled);
        this.ctx.media.player.speed(this.speed);
        this.applyVideoSettings();
        this.ctx.media.player.start();
        this.endedSoundPlayed = false;
    }

    private void returnToMenu() {
        this.hideQualityDialog();
        this.hideVideoDialog();
        this.ctx.media.releasePlayer();

        if (this.ctx.media.selectedMRL == null || this.ctx.media.selectedGroup == null) this.navigator.accept(HomeScreen.Action.BACK);
        else this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
    }

    private void navigateSource(final int delta) {
        if (this.ctx.media.availableSources == null || this.ctx.media.availableSources.length <= 1) return;
        final int newIndex = (this.ctx.media.selectedSourceIndex + delta + this.ctx.media.availableSources.length) % this.ctx.media.availableSources.length;
        if (newIndex != this.ctx.media.selectedSourceIndex) {
            this.ctx.media.selectedSourceIndex = newIndex;
            this.ctx.media.selectedSource = this.ctx.media.availableSources[this.ctx.media.selectedSourceIndex];
            this.startPlayer();
        }
    }

    private void seekToPercent(final int percent) {
        final MediaPlayer player = this.ctx.media.player;
        if (player == null || player.duration() <= 0) return;
        player.seek((player.duration() * percent) / 100);
    }

    private void togglePlayback(final MediaPlayer player) {
        if (player.ended() || player.stopped()) {
            player.repeat(this.loopEnabled);
            player.start();
            this.endedSoundPlayed = false;
            this.ctx.requestRender();
            return;
        }
        player.togglePlay();
    }

    private void toggleLoop(final MediaPlayer player) {
        this.loopEnabled = !this.loopEnabled;
        player.repeat(this.loopEnabled);
        this.ctx.requestRender();
    }

    // BOTH SETTINGS APPLY FROM THE NEXT UPLOADED FRAME; startPlayer RE-APPLIES THEM SO THEY SURVIVE RESTARTS
    private void applyVideoSettings() {
        final MediaPlayer player = this.ctx.media.player;
        if (player == null) return;
        player.scale(this.parseDim(this.resWidthText, player.sourceWidth()), this.parseDim(this.resHeightText, player.sourceHeight()));
        player.lod(MediaPlayer.LodLevel.values()[this.lodSelectedIndex]);
    }

    // EMPTY, INVALID OR NON-POSITIVE MEANS UNLIMITED (NO_SIZE); A POSITIVE VALUE IS CAPPED AT THE SOURCE SIZE
    private int parseDim(final String text, final int nativeMax) {
        if (text.isEmpty()) return 0;
        final int value;
        try {
            value = Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return 0;
        }
        if (value <= 0) return 0;
        return nativeMax > 0 ? Math.min(value, nativeMax) : value;
    }

    private String scaleLabel() {
        final MediaPlayer player = this.ctx.media.player;
        if (player == null) return "FULL";
        final int mw = player.scaleWidth();
        final int mh = player.scaleHeight();
        if (mw == 0 && mh == 0) return "FULL";
        return (mw == 0 ? "*" : mw) + "x" + (mh == 0 ? "*" : mh);
    }

    private int typeTagWidth(final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        return this.text.width(label, AppTheme.TEXT_BODY) + 22;
    }

    private void drawTypeTag(final Canvas canvas, final int x, final int y, final MediaType type) {
        final String label = type == null ? "MEDIA" : type.name();
        final Color color = type == MediaType.IMAGE ? AppTheme.GREEN
                : type == MediaType.VIDEO ? AppTheme.AMBER
                : type == MediaType.AUDIO ? AppTheme.CYAN
                : AppTheme.TEXT_FAINT;
        final int w = this.typeTagWidth(type);
        canvas.fill(x, y, w, 22, AppTheme.alpha(AppTheme.BG_1, 188));
        canvas.stroke(x, y, w, 22, color, 1f);
        canvas.glow(x, y, w, 22, 0f, color, 0.16f);
        canvas.text(label, x + 11, y + Math.max(0, (22 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2), color, AppTheme.TEXT_BODY, false);
    }

    // ==========================================================================
    // SPEED MENU
    // ==========================================================================

    // THE OVERLAY ONLY HOSTS THE TWO DIALOGS AND THE SPEED MENU, SO ANY OTHER CHILD IS THE MENU
    private boolean speedMenuOpen() {
        for (final Element<?> child: this.overlay().children()) {
            if (child != this.qualityDialog && child != this.videoDialog) return true;
        }
        return false;
    }

    private int nearestSpeedIndex(final float value) {
        int nearest = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < SPEED_VALUES.length; i++) {
            final float delta = Math.abs(SPEED_VALUES[i] - value);
            if (delta < best) {
                best = delta;
                nearest = i;
            }
        }
        return nearest;
    }

    // ==========================================================================
    // QUALITY DIALOG (SOURCE + QUALITY SELECTION)
    // ==========================================================================

    private void openQualityDialog() {
        if (this.ctx.media.availableSources == null || this.ctx.media.availableSources.length == 0) return;
        this.sourcePreviewIndex = MathUtil.clamp(this.ctx.media.selectedSourceIndex, 0, this.ctx.media.availableSources.length - 1);
        this.loadQualitiesForSource(this.sourcePreviewIndex);
        if (this.ctx.media.availableQualities == null || this.ctx.media.availableQualities.length == 0) return;

        this.srcList = new ListView<>();
        this.srcList.rowHeight(50).spacing(8).scrollbarWidth(3)
                .selectionColor(null).hoverColor(null)
                .rowFactory((src, i) -> {
                    final ListRow row = this.sourceRow(src, i);
                    // HOVER LISTS THE SOURCE'S QUALITIES WITHOUT SWITCHING PLAYBACK
                    row.onHover(r -> {
                        if (this.sourcePreviewIndex != i) {
                            this.srcList.selection(i);
                            this.selectSource(i, false);
                            this.ctx.sound.play();
                            r.invalidate();
                        }
                    });
                    return row;
                })
                .onSelect((src, i) -> {
                    this.selectSource(i, true);
                    this.ctx.sound.play();
                })
                .items(Arrays.asList(this.ctx.media.availableSources))
                .selection(this.sourcePreviewIndex)
                .width(MAX_PARENT);

        this.qualList = new ListView<>();
        this.qualList.rowHeight(34).spacing(6).scrollbarWidth(3)
                .selectionColor(null).hoverColor(null)
                .rowFactory((q, i) -> {
                    final ListRow row = pickRow(q.name(), q.height() + "p", null, false);
                    row.onHover(r -> {
                        if (this.qualList.selectedIndex() != i) {
                            this.qualList.selection(i);
                            this.qualitySelectedIndex = i;
                            this.ctx.sound.play();
                            r.invalidate();
                        }
                    });
                    return row;
                })
                .onSelect((q, i) -> this.applyQuality(i))
                .items(Arrays.asList(this.ctx.media.availableQualities))
                .selection(this.qualitySelectedIndex)
                .width(MAX_PARENT);

        final SectionHead srcHead = new SectionHead("Sources");
        srcHead.count(this.ctx.media.availableSources.length + " ITEMS");
        this.qualHead = new SectionHead("Quality");
        this.qualHead.count(this.ctx.media.availableQualities.length + " AVAILABLE");

        this.qualLeftCol = Parent.column().spacing(12).add(srcHead).add(this.srcList);
        this.qualLeftCol.margin(new Spacing(0, 24, 0, 4));
        this.qualRightCol = Parent.column().spacing(12).add(this.qualHead).add(this.qualList);
        this.qualRightCol.margin(new Spacing(0, 2, 0, 21));
        this.qualDivider = new Box().background(AppTheme.STROKE_BRIGHT);

        final DialogKeys content = new DialogKeys(this::qualityKey);
        content.add(this.qualLeftCol).add(this.qualDivider).add(this.qualRightCol);

        this.qualityDialog = new Dialog()
                .accent(AppTheme.NEON)
                .content(content)
                .onDismiss(this::hideQualityDialog)
                .onPrimary(() -> this.applyQuality(this.qualList.selectedIndex()));
        // CLICKS ON EMPTY PANEL AREA MUST NOT FALL THROUGH TO THE SCRIM
        this.qualityDialog.panel().consumeTouch(true);

        this.syncQualityDialog();
        this.showDialog(this.qualityDialog);
        this.sourceFollowPending = true;
    }

    private void hideQualityDialog() {
        if (this.qualityDialog == null) return;
        this.hideDialog(this.qualityDialog);
        this.qualityDialog = null;
        this.srcList = null;
        this.qualList = null;
        this.qualLeftCol = null;
        this.qualRightCol = null;
        this.qualDivider = null;
        this.qualHead = null;
        this.sourceFollowPending = false;
    }

    private void syncQualityDialog() {
        final int winW = this.ctx.logicalWidth();
        final int winH = this.ctx.logicalHeight();
        final int srcCount = this.ctx.media.availableSources != null ? this.ctx.media.availableSources.length : 1;
        final int qualCount = this.ctx.media.availableQualities != null ? this.ctx.media.availableQualities.length : 1;
        final int dialogW = MathUtil.clamp((int) (winW * 0.60f), 620, winW - 72);
        final int dialogH = MathUtil.clamp(98 + Math.max(Math.min(srcCount, 5) * 58, Math.max(1, qualCount) * 40), 320, winH - 96);
        final int split = MathUtil.clamp((int) (dialogW * 0.72f), 360, dialogW - 250);
        final int listH = dialogH - 82;
        this.qualityDialog.panelWidth(dialogW);
        this.qualLeftCol.width(split - 48);
        this.qualRightCol.width(dialogW - split - 44);
        this.qualDivider.size(1, listH + 42);
        this.srcList.height(listH);
        this.qualList.height(listH);
        if (this.sourceFollowPending && this.srcList.innerHeight() > 0) {
            final int idx = Math.max(0, this.srcList.selectedIndex());
            this.srcList.selection(-1);
            this.srcList.moveSelection(idx);
            this.sourceFollowPending = false;
        }
    }

    private boolean qualityKey(final int key) {
        switch (key) {
            case GLFW_KEY_LEFT -> {
                if (this.srcList.selectedIndex() > 0) {
                    this.srcList.moveSelection(-1);
                    this.selectSource(this.srcList.selectedIndex(), true);
                    this.ctx.sound.play();
                }
                return true;
            }
            case GLFW_KEY_RIGHT -> {
                if (this.ctx.media.availableSources != null && this.srcList.selectedIndex() < this.ctx.media.availableSources.length - 1) {
                    this.srcList.moveSelection(1);
                    this.selectSource(this.srcList.selectedIndex(), true);
                    this.ctx.sound.play();
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                this.moveQualitySelection(-1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                this.moveQualitySelection(1);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void moveQualitySelection(final int delta) {
        final int before = this.qualList.selectedIndex();
        this.qualList.moveSelection(delta);
        if (this.qualList.selectedIndex() != before) {
            this.qualitySelectedIndex = this.qualList.selectedIndex();
            this.ctx.sound.play();
        }
    }

    private void loadQualitiesForSource(final int sourceIndex) {
        if (this.ctx.media.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.media.availableSources.length) return;
        final var qualities = this.ctx.media.availableSources[sourceIndex].qualities().keySet();
        if (qualities.isEmpty()) return;

        this.ctx.media.availableQualities = qualities.toArray(new MediaQuality[0]);
        Arrays.sort(this.ctx.media.availableQualities, Comparator.comparingInt(MediaQuality::height));

        this.qualitySelectedIndex = 0;
        for (int i = 0; i < this.ctx.media.availableQualities.length; i++) {
            if (this.ctx.media.availableQualities[i] == this.ctx.media.selectedQuality) {
                this.qualitySelectedIndex = i;
                break;
            }
        }
    }

    private void selectSource(final int sourceIndex, final boolean apply) {
        if (this.ctx.media.availableSources == null || sourceIndex < 0 || sourceIndex >= this.ctx.media.availableSources.length) return;
        this.sourcePreviewIndex = sourceIndex;
        this.loadQualitiesForSource(sourceIndex);
        if (this.qualList != null && this.ctx.media.availableQualities != null) {
            this.qualList.items(Arrays.asList(this.ctx.media.availableQualities)).selection(this.qualitySelectedIndex);
            if (this.qualHead != null) this.qualHead.count(this.ctx.media.availableQualities.length + " AVAILABLE");
        }
        if (!apply || sourceIndex == this.ctx.media.selectedSourceIndex) return;
        if (this.ctx.media.availableQualities != null && this.ctx.media.availableQualities.length > 0)
            this.ctx.media.selectedQuality = this.ctx.media.availableQualities[this.qualitySelectedIndex];
        this.ctx.media.selectedSourceIndex = sourceIndex;
        this.ctx.media.selectedSource = this.ctx.media.availableSources[sourceIndex];
        this.startPlayer();
    }

    private void applyQuality(final int index) {
        if (this.ctx.media.availableQualities == null || index < 0 || index >= this.ctx.media.availableQualities.length) return;
        this.qualitySelectedIndex = index;
        this.ctx.media.selectedQuality = this.ctx.media.availableQualities[index];
        if (this.ctx.media.availableSources != null && this.sourcePreviewIndex != this.ctx.media.selectedSourceIndex) {
            this.ctx.media.selectedSourceIndex = this.sourcePreviewIndex;
            this.ctx.media.selectedSource = this.ctx.media.availableSources[this.sourcePreviewIndex];
            this.startPlayer();
        } else if (this.ctx.media.player != null) {
            this.ctx.media.player.quality(this.ctx.media.selectedQuality);
        }
        this.hideQualityDialog();
    }

    // SOURCE ROW OF THE QUALITY DIALOG: THE ICON MARKS THE PLAYING SOURCE, THE MEDIA TYPE TAG SITS RIGHT
    private ListRow sourceRow(final MRL.Source source, final int index) {
        final int tagW = this.typeTagWidth(source.type());
        return new ListRow()
                .fill(AppTheme.alpha(AppTheme.BG_1, 150), null, AppTheme.alpha(AppTheme.NEON_DARK, 90))
                .outline(AppTheme.STROKE_BRIGHT, AppTheme.NEON_LIGHT, 1f, 2f)
                .selectedGlow(AppTheme.NEON, 0.20f, false)
                .binder(row -> {
                    final boolean active = index == this.ctx.media.selectedSourceIndex;
                    row.icon(active ? "play" : "folder", 14, 12, 15, active ? AppTheme.GREEN : AppTheme.NEON_LIGHT, null);
                })
                .title(() -> {
                    // THE SOURCE TITLE, ELSE THE SELECTED MRL NAME, ELSE ITS POSITION
                    final Metadata meta = source.metadata();
                    if (meta != null && meta.title() != null && !meta.title().isBlank()) return meta.title().toUpperCase(Locale.ROOT);
                    if (this.ctx.media.selectedMRLName != null && !this.ctx.media.selectedMRLName.isBlank())
                        return this.ctx.media.selectedMRLName.toUpperCase(Locale.ROOT);
                    return "SOURCE " + (index + 1);
                })
                .titleStyle(AppTheme.TEXT_BODY, true, AppTheme.TEXT_SOFT, AppTheme.TEXT)
                .textAt(34, 10)
                .textRight(tagW + 34, 0)
                .subtitle(() -> "SOURCE " + (index + 1) + "/" + (this.ctx.media.availableSources != null ? this.ctx.media.availableSources.length : 1)
                        + " - " + source.qualities().size() + " QUALITIES", AppTheme.TEXT_SUBTITLE, AppTheme.TEXT_FAINT, 32)
                .trail((canvas, row, x, y, w, h) -> this.drawTypeTag(canvas, x + w - tagW - 12, y + Math.max(0, (h - 22) / 2), source.type()));
    }

    // ==========================================================================
    // VIDEO DIALOG (UPLOAD RESOLUTION CAP + LOD LEVEL)
    // ==========================================================================

    private void openVideoDialog() {
        if (this.ctx.media.player == null || this.ctx.media.player.error()) return;

        this.resWidthField = new TextField();
        this.resWidthField.placeholder("AUTO").maxLength(RES_FIELD_MAX_DIGITS).accent(AppTheme.NEON_LIGHT)
                .value(this.resWidthText).height(34).margin(new Spacing(6, 0, 0, 0))
                .onChange(v -> this.resEdit(this.resWidthField, true, v));
        this.resHeightField = new TextField();
        this.resHeightField.placeholder("AUTO").maxLength(RES_FIELD_MAX_DIGITS).accent(AppTheme.NEON_LIGHT)
                .value(this.resHeightText).height(34).margin(new Spacing(6, 0, 0, 0))
                .onChange(v -> this.resEdit(this.resHeightField, false, v));
        this.activeMaxText = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.CYAN).margin(new Spacing(14, 0, 0, 0));

        this.videoLeftCol = Parent.column()
                .add(new SectionHead("Resolution").count("CUSTOM PX"))
                .add(new Text("WIDTH").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(12, 0, 0, 0)))
                .add(this.resWidthField)
                .add(new Text("HEIGHT").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(20, 0, 0, 0)))
                .add(this.resHeightField)
                .add(this.activeMaxText)
                .add(new Text("EMPTY FIELD = UNLIMITED").scale(AppTheme.TEXT_SUBTITLE).color(AppTheme.TEXT_FAINT).margin(new Spacing(10, 0, 0, 0)));
        this.videoLeftCol.margin(new Spacing(0, 22, 0, 4));

        final MediaPlayer.LodLevel[] lods = MediaPlayer.LodLevel.values();
        final Parent lodCol = Parent.column().spacing(6).margin(new Spacing(10, 0, 0, 0)).width(MAX_PARENT);
        for (int i = 0; i < lods.length; i++) {
            final int idx = i;
            lodCol.add(pickRow(lods[idx].name(), lods[idx].percent() + "%", () -> this.lodSelectedIndex == idx, true)
                    .size(MAX_PARENT, 34)
                    .onClick(r -> {
                        this.clearResFocus();
                        this.selectVideoLod(idx);
                    }));
        }
        this.videoRightCol = Parent.column()
                .add(new SectionHead("Level of Detail").count(lods.length + " LEVELS"))
                .add(lodCol);
        this.videoRightCol.margin(new Spacing(0, 2, 0, 23));
        this.videoDivider = new Box().background(AppTheme.STROKE_BRIGHT);

        final DialogKeys content = new DialogKeys(this::videoKey);
        content.add(this.videoLeftCol).add(this.videoDivider).add(this.videoRightCol);

        this.videoDialog = new Dialog()
                .accent(AppTheme.NEON)
                .content(content)
                .onDismiss(this::hideVideoDialog)
                .onPrimary(this::hideVideoDialog)
                .dismissOnScrim(this::hideVideoDialog);
        // A CLICK ON THE PANEL OUTSIDE THE FIELDS AND ROWS DROPS FIELD FOCUS
        this.videoDialog.panel().onClick(p -> this.clearResFocus());

        this.syncVideoDialog();
        this.showDialog(this.videoDialog);
    }

    private void hideVideoDialog() {
        if (this.videoDialog == null) return;
        this.hideDialog(this.videoDialog);
        this.videoDialog = null;
        this.resWidthField = null;
        this.resHeightField = null;
        this.activeMaxText = null;
        this.videoLeftCol = null;
        this.videoRightCol = null;
        this.videoDivider = null;
    }

    private void syncVideoDialog() {
        final int winW = this.ctx.logicalWidth();
        final int dialogW = MathUtil.clamp((int) (winW * 0.54f), 580, winW - 72);
        final int colW = dialogW / 2 - 46;
        this.videoDialog.panelWidth(dialogW);
        this.videoLeftCol.width(colW);
        this.videoRightCol.width(colW);
        // THE DIVIDER SPANS THE TALLER COLUMN; THE SUMS FOLLOW EACH COLUMN'S MARGINS AND ROW HEIGHTS
        final int subH = this.text.glyphHeight(AppTheme.TEXT_SUBTITLE);
        final int bodyH = this.text.glyphHeight(AppTheme.TEXT_BODY);
        final int leftH = 30 + 12 + subH + 6 + 34 + 20 + subH + 6 + 34 + 14 + bodyH + 10 + subH;
        final int rightH = 30 + 10 + MediaPlayer.LodLevel.values().length * 40 - 6;
        this.videoDivider.size(1, Math.max(leftH, rightH));

        final MediaPlayer player = this.ctx.media.player;
        final int srcW = player == null ? 0 : player.sourceWidth();
        final int srcH = player == null ? 0 : player.sourceHeight();
        this.activeMaxText.text("ACTIVE MAX  " + (srcW > 0 && srcH > 0 ? srcW + " x " + srcH : "PENDING"));
    }

    private boolean videoKey(final int key) {
        switch (key) {
            case GLFW_KEY_TAB -> {
                final TextField target = this.resWidthField.focused() ? this.resHeightField : this.resWidthField;
                this.resWidthField.focused(false);
                this.resHeightField.focused(false);
                target.focused(true).invalidate();
                return true;
            }
            case GLFW_KEY_UP -> {
                this.selectVideoLod(this.lodSelectedIndex - 1);
                return true;
            }
            case GLFW_KEY_DOWN -> {
                this.selectVideoLod(this.lodSelectedIndex + 1);
                return true;
            }
            case GLFW_KEY_ESCAPE -> {
                // FIRST ESC ONLY DROPS FIELD FOCUS; THE NEXT ONE FALLS THROUGH TO THE DIALOG DISMISS
                if (this.resWidthField.focused() || this.resHeightField.focused()) {
                    this.clearResFocus();
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void clearResFocus() {
        if (this.resWidthField != null) this.resWidthField.focused(false).invalidate();
        if (this.resHeightField != null) this.resHeightField.focused(false).invalidate();
    }

    // KEEPS DIGITS ONLY, CAPS THE VALUE AT THE NATIVE SOURCE SIZE AND PUSHES IT TO THE LIVE PLAYER
    private void resEdit(final TextField field, final boolean width, final String raw) {
        final MediaPlayer player = this.ctx.media.player;
        final int nativeMax = player == null ? 0 : (width ? player.sourceWidth() : player.sourceHeight());
        final StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && digits.length() < RES_FIELD_MAX_DIGITS; i++) {
            final char c = raw.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String value = digits.toString();
        if (!value.isEmpty() && nativeMax > 0 && Integer.parseInt(value) > nativeMax) value = String.valueOf(nativeMax);
        if (!value.equals(raw)) field.value(value);
        if (width) this.resWidthText = value;
        else this.resHeightText = value;
        this.applyVideoSettings();
        this.ctx.requestRender();
    }

    private void selectVideoLod(final int index) {
        final int next = MathUtil.clamp(index, 0, MediaPlayer.LodLevel.values().length - 1);
        if (next == this.lodSelectedIndex) return;
        this.lodSelectedIndex = next;
        this.applyVideoSettings();
        this.ctx.sound.play();
        this.ctx.requestRender();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A PICKABLE DIALOG ROW (QUALITY OR LOD); strong IS THE LOD LOOK: A HOVER TINT, A 2 px PICKED BORDER AND A
    // RIGHT-ALIGNED DETAIL, WHERE QUALITY ROWS START IT 62 px FROM THE EDGE
    private static ListRow pickRow(final String label, final String detail, final BooleanSupplier picked, final boolean strong) {
        final ListRow row = new ListRow()
                .fill(AppTheme.alpha(AppTheme.BG_1, 148), strong ? AppTheme.alpha(AppTheme.NEON_DARK, 44) : null, AppTheme.alpha(AppTheme.NEON_DARK, 88))
                .outline(AppTheme.STROKE_BRIGHT, AppTheme.NEON_LIGHT, 1f, strong ? 2f : 1f)
                .lead((canvas, r, x, y, w, h) -> canvas.fill(x + 8, y + Math.max(0, (h - 8) / 2), 8, 8, r.isPicked() ? AppTheme.AMBER : AppTheme.TEXT_FAINT))
                .title(label.toUpperCase(Locale.ROOT))
                .titleStyle(AppTheme.TEXT_BODY, true, AppTheme.TEXT_SOFT, AppTheme.NEON_LIGHT)
                .textAt(28, ListRow.CENTER)
                .detail(() -> detail, AppTheme.TEXT_FAINT, AppTheme.CYAN, 12);
        if (!strong) row.detailColumn(62);
        // A LOD ROW LIVES OUTSIDE A ListView, SO ITS SELECTION COMES FROM THE DIALOG STATE
        return picked == null ? row : row.picked(picked);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // DIALOG CONTENT ROW: CHILDREN GET EACH KEY FIRST, THEN THE HANDLER RUNS ON RELEASE;
    // THE HOSTING Dialog SWALLOWS WHATEVER IS LEFT
    private static final class DialogKeys extends Parent {
        private final IntPredicate keys;

        DialogKeys(final IntPredicate keys) {
            super(Orientation.HORIZONTAL);
            this.keys = keys;
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (super.dispatchKey(key, action)) return true;
            return action == GLFW_RELEASE && this.keys.test(key);
        }
    }

    // DEBUG METRICS AND METADATA PANEL: A DRAW-ONLY LEAF THAT READS THE LIVE PLAYER EVERY FRAME
    private final class MetricsPanel extends Element<MetricsPanel> {
        // maxScroll IS THE PREVIOUS FRAME'S OVERFLOW, WHICH CLAMPS THE WHEEL OFFSET
        private int scroll;
        private int maxScroll;
        private int labelColW;
        // REFORMATTED ONLY WHEN THE 2-DECIMAL VALUE CHANGES: A String.format PER FRAME IS THE PANEL'S
        // HEAVIEST ALLOCATION DURING PLAYBACK
        private String fpsStr = "0.00";
        private String speedStr = "1.00";
        private long fpsKey = Long.MIN_VALUE;
        private long speedKey = Long.MIN_VALUE;
        private final WrapCache mrlWrap = new WrapCache();
        private final WrapCache titleWrap = new WrapCache();
        private final WrapCache descWrap = new WrapCache();

        // ======================================================================
        // PUBLIC API
        // ======================================================================

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            if (!this.visible || this.maxScroll <= 0 || !this.contains(mx, my)) return false;
            final int next = MathUtil.clamp(this.scroll - (int) (amount * METRIC_SCROLL_STEP), 0, this.maxScroll);
            if (next != this.scroll) {
                this.scroll = next;
                this.invalidate();
            }
            return true;
        }

        // ======================================================================
        // INTERNAL API
        // ======================================================================

        @Override
        protected void onUpdate() {
            final MediaPlayer player = PlayerScreen.this.ctx.media.player;
            if (player == null) return;
            final double fps = player.fps();
            final long fpsKey = Math.round(fps * 100.0);
            if (fpsKey != this.fpsKey) {
                this.fpsKey = fpsKey;
                this.fpsStr = String.format("%.2f", fps);
            }
            final double speed = player.speed();
            final long speedKey = Math.round(speed * 100.0);
            if (speedKey != this.speedKey) {
                this.speedKey = speedKey;
                this.speedStr = String.format("%.2f", speed);
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MediaPlayer player = PlayerScreen.this.ctx.media.player;
            if (player == null) return;
            final int px = this.left;
            final int py = this.top;
            final int pw = this.measuredWidth;
            final int ph = this.measuredHeight;
            // THE FRAME GOES BEFORE THE CLIP SO ITS BORDER AND GLOW ARE NOT CUT
            canvas.stroke(px, py, pw, ph, AppTheme.NEON, 2f);
            canvas.glow(px, py, pw, ph, 0f, AppTheme.NEON, 0.20f);
            canvas.fill(px, py, pw, ph, AppTheme.alpha(AppTheme.BG_1, 209));

            // THE CLIP KEEPS LONG METADATA OFF THE SEEK BAR; CONTENT IS SHIFTED UP BY scroll
            this.scroll = MathUtil.clamp(this.scroll, 0, this.maxScroll);
            canvas.pushClip(px, py, pw, ph);

            final AppContext ctx = PlayerScreen.this.ctx;
            final Metadata meta = ctx.media.selectedSource != null ? ctx.media.selectedSource.metadata() : null;
            final int x = px + 14;
            final int top0 = py + 14 - this.scroll;
            int y = top0;

            y = this.head(canvas, "ENGINE", x, y);
            y = this.metric(canvas, "Engine", player.getClass().getSimpleName(), x, y, AppTheme.NEON_LIGHT);
            if (ctx.media.selectedMRL != null) y = this.wrappedMetric(canvas, this.mrlWrap, "MRL", ctx.media.selectedMRL.uri().toString(), x, y);
            y = this.metric(canvas, "Source", (ctx.media.selectedSourceIndex + 1) + "/" +
                    (ctx.media.availableSources != null ? ctx.media.availableSources.length : 1), x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "FPS", this.fpsStr, x, y, AppTheme.GREEN);
            y = this.metric(canvas, "Status", player.status().name(), x, y, AppTheme.TEXT_SOFT);

            final long duration = player.duration();
            final String timeValue = duration <= 0
                    ? MediaSession.formatTime(player.time())
                    : MediaSession.formatTime(player.time()) + " / " + MediaSession.formatTime(duration);
            y = this.metric(canvas, "Time", timeValue, x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Volume", player.volume() + "%", x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Quality", player.quality().name() + " - " + PlayerScreen.this.scaleLabel(), x, y, AppTheme.CYAN);
            y = this.metric(canvas, "Dimensions", player.width() <= 0 || player.height() <= 0 ? "--" : player.width() + "x" + player.height(),
                    x, y, AppTheme.CYAN);
            y = this.metric(canvas, "Speed", this.speedStr + "x", x, y, AppTheme.TEXT_SOFT);
            y = this.metric(canvas, "Live", player.live() ? "Yes" : "No", x, y, AppTheme.TEXT_SOFT);

            y += 12;
            y = this.head(canvas, "METADATA", x, y);

            if (meta != null) {
                y = this.wrappedMetric(canvas, this.titleWrap, "Title", meta.title(), x, y);
                y = this.metric(canvas, "Author", meta.author(), x, y, AppTheme.TEXT_SOFT);
                if (meta.postedAt() != null) y = this.metric(canvas, "Published", DATE_FORMAT.format(meta.postedAt()), x, y, AppTheme.TEXT_SOFT);
                y = this.descriptionBox(canvas, meta, x, y + 8, pw - 28);
            } else {
                canvas.text("Unavailable", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
                y += PlayerScreen.this.text.lineHeight(META_SCALE);
            }
            canvas.popClip();

            // THE OVERFLOW CLAMPS THE NEXT FRAME'S OFFSET; A SCROLL THUMB SHOWS WHILE CONTENT OVERFLOWS
            final int contentH = y - top0;
            final int viewH = ph - 28;
            this.maxScroll = Math.max(0, contentH - viewH);
            if (this.maxScroll > 0) {
                final int trackH = ph - 32;
                final int thumbH = Math.max(26, (int) ((long) trackH * viewH / contentH));
                final int thumbY = py + 16 + (int) ((long) (trackH - thumbH) * this.scroll / this.maxScroll);
                canvas.fill(px + pw - 18, py + 16, 3, trackH, AppTheme.alpha(AppTheme.BG_3, 140));
                canvas.fill(px + pw - 18, thumbY, 3, thumbH, AppTheme.alpha(AppTheme.NEON, 160));
                canvas.glow(px + pw - 18, thumbY, 3, thumbH, 0f, AppTheme.NEON, 0.35f);
            }
        }

        // ======================================================================
        // HELPERS
        // ======================================================================

        private int metric(final Canvas canvas, final String label, final String value, final int x, final int y, final Color valueColor) {
            final TextRenderer text = PlayerScreen.this.text;
            final int valueX = this.valueX(x);
            final int maxValueW = Math.max(80, this.left + this.measuredWidth - valueX - 14);
            canvas.text(label + ":", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
            if (value != null) canvas.text(text.truncateToWidth(value, maxValueW, META_SCALE), valueX, y, valueColor, META_SCALE, false);
            return y + text.lineHeight(META_SCALE) + 4;
        }

        // A LONG VALUE WRAPS ONTO AT MOST TWO LINES
        private int wrappedMetric(final Canvas canvas, final WrapCache cache, final String label, final String value, final int x, final int y) {
            final TextRenderer text = PlayerScreen.this.text;
            final int valueX = this.valueX(x);
            final int maxPixelW = Math.max(80, this.left + this.measuredWidth - valueX - 14);
            final List<String> lines = cache.lines(value, maxPixelW, META_SCALE, 2);
            canvas.text(label + ":", x, y, AppTheme.TEXT_FAINT, META_SCALE, false);
            final int lineH = text.lineHeight(META_SCALE) + 2;
            for (int i = 0; i < lines.size(); i++) {
                canvas.text(lines.get(i), valueX, y + i * lineH, AppTheme.TEXT_SOFT, META_SCALE, false);
            }
            return y + Math.max(text.lineHeight(META_SCALE), lines.size() * lineH) + 4;
        }

        private int head(final Canvas canvas, final String label, final int x, final int y) {
            canvas.text("| " + label, x, y, AppTheme.NEON_LIGHT, META_HEAD_SCALE, true);
            final int lineY = y + canvas.textHeight(META_HEAD_SCALE, true) / 2;
            for (int dx = x + 118; dx < this.left + this.measuredWidth - 14; dx += 8) {
                canvas.fill(dx, lineY, 4, 1, AppTheme.STROKE_BRIGHT);
            }
            return y + PlayerScreen.this.text.lineHeight(META_HEAD_SCALE) + 6;
        }

        private int descriptionBox(final Canvas canvas, final Metadata meta, final int x, final int y, final int w) {
            final TextRenderer text = PlayerScreen.this.text;
            final String desc = meta != null && meta.description() != null && !meta.description().isBlank()
                    ? meta.description()
                    : "No description available.";
            final List<String> lines = this.descWrap.lines(desc, w - 20, META_DESC_SCALE, 3);
            final int labelH = text.lineHeight(META_DESC_LABEL_SCALE);
            final int lineH = text.lineHeight(META_DESC_SCALE) + 2;
            final int h = 22 + labelH + lines.size() * lineH + 14;
            canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 188));
            // DOTTED BORDER
            for (int dx = x; dx < x + w; dx += 8) {
                canvas.fill(dx, y, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
                canvas.fill(dx, y + h - 1, Math.min(4, x + w - dx), 1, AppTheme.STROKE_BRIGHT);
            }
            for (int dy = y; dy < y + h; dy += 8) {
                canvas.fill(x, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
                canvas.fill(x + w - 1, dy, 1, Math.min(4, y + h - dy), AppTheme.STROKE_BRIGHT);
            }
            canvas.text("DESCRIPTION", x + 10, y + 10, AppTheme.TEXT_FAINT, META_DESC_LABEL_SCALE, true);
            for (int i = 0; i < lines.size(); i++) {
                canvas.text(lines.get(i), x + 10, y + 20 + labelH + i * lineH, AppTheme.TEXT_SOFT, META_DESC_SCALE, false);
            }
            return y + h + 10;
        }

        private int valueX(final int x) {
            if (this.labelColW == 0) {
                final TextRenderer text = PlayerScreen.this.text;
                int max = 0;
                for (final String label: METRIC_LABELS) max = Math.max(max, text.width(label + ":", META_SCALE));
                this.labelColW = max + 14;
            }
            return x + this.labelColW;
        }

        private List<String> wrap(final String value, final int maxPixelWidth, final float scale, final int maxLines) {
            final List<String> lines = new ArrayList<>();
            String remaining = value == null ? "" : value.trim();
            while (!remaining.isEmpty() && lines.size() < maxLines) {
                final int fit = this.fitPrefix(remaining, maxPixelWidth, scale);
                if (fit >= remaining.length()) {
                    lines.add(remaining);
                    break;
                }
                final int preferred = this.wrapBreak(remaining, fit);
                lines.add(remaining.substring(0, preferred).trim());
                remaining = remaining.substring(Math.min(preferred, remaining.length())).trim();
            }
            if (lines.isEmpty()) lines.add("Unavailable.");
            return lines;
        }

        private int fitPrefix(final String value, final int maxPixelWidth, final float scale) {
            // width() NEVER SHRINKS AS THE PREFIX GROWS, SO A BINARY SEARCH FINDS THE LONGEST FITTING PREFIX;
            // IT KEEPS AT LEAST ONE CHAR SO wrap() CANNOT STALL
            final TextRenderer text = PlayerScreen.this.text;
            int lo = 1, hi = value.length(), lastFit = 1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (text.width(value.substring(0, mid), scale) > maxPixelWidth) {
                    hi = mid - 1;
                } else {
                    lastFit = mid;
                    lo = mid + 1;
                }
            }
            return lastFit;
        }

        private int wrapBreak(final String value, final int fit) {
            final int limit = MathUtil.clamp(fit, 1, value.length());
            int best = -1;
            // PREFER WORD AND URL-SEPARATOR BREAKS NEAR THE FIT POINT
            for (int i = limit - 1; i > Math.max(0, limit - 18); i--) {
                final char c = value.charAt(i);
                if (Character.isWhitespace(c) || c == '/' || c == '?' || c == '&' || c == '-' || c == '_') {
                    best = i + 1;
                    break;
                }
            }
            return best > 4 ? best : limit;
        }

        // ======================================================================
        // NESTED TYPES
        // ======================================================================

        // WRAPPED LINES OF ONE ROW, REBUILT ONLY WHEN ITS TEXT, WIDTH OR LINE CAP CHANGES; ONLY onDraw
        // TOUCHES IT, ON THE RENDER THREAD, SO IT NEEDS NO SYNCHRONIZATION
        private final class WrapCache {
            private String text;
            private int width = -1;
            private int maxLines = -1;
            private List<String> lines;

            List<String> lines(final String value, final int maxPixelWidth, final float scale, final int maxLines) {
                if (this.lines == null || this.width != maxPixelWidth || this.maxLines != maxLines
                        || !Objects.equals(this.text, value)) {
                    this.text = value;
                    this.width = maxPixelWidth;
                    this.maxLines = maxLines;
                    this.lines = MetricsPanel.this.wrap(value, maxPixelWidth, scale, maxLines);
                }
                return this.lines;
            }
        }
    }

    // HUD LAYER: onDraw PAINTS THE CHROME (FADES, BAR PANELS, DIVIDERS, TEXTS) AND EVERY INTERACTIVE
    // PIECE IS A CHILD PLACED AT ABSOLUTE POSITIONS BY onLayout
    private final class Hud extends Group<Hud> {
        private final Button back;
        private final Icon srcIcon;
        private final MetricsPanel metrics = new MetricsPanel();
        private final Button dbg;
        private final Button qual;
        private final Button lod;
        private final IconButton prev;
        private final IconButton rew;
        private final IconButton stopBtn;
        private final IconButton play;
        private final IconButton fwd;
        private final IconButton next;
        private final Icon spk;
        private final SeekBar seek;
        private final SeekBar vol;
        private final Button loop;
        private final Dropdown speedDrop;

        // FRAME STATE (REBUILT EVERY UPDATE/MEASURE/LAYOUT PASS)
        private boolean errorMode = true;
        private boolean multiSource;
        private int transportYL;   // LOCAL Y OF THE TRANSPORT BAR TOP
        private int barW;
        private String leftTime = "";
        private String rightTime = "--:--";
        // ABSOLUTE GEOMETRY FOR onDraw
        private int topYA;
        private int transportYA;
        private int controlsYA;
        private int dividerXA;
        private int volDividerXA;
        private int volPctXA;
        private String titleStr = "";
        private String authorStr = "";
        private String srcLabel = "";
        private boolean showTypeTag;
        private int typeTagXA;
        private MediaType tagType;
        private int srcTagXA;
        private int srcTagW;

        Hud() {
            this.size(MAX_PARENT, MAX_PARENT);
            // WRAP_CONTENT SO "BACK" AND ITS ARROW ARE NEVER TRUNCATED
            this.back = new Button("BACK").icon("arrow-left")
                    .accent(AppTheme.TEXT_SOFT).textColor(AppTheme.TEXT_SOFT).width(WRAP_CONTENT).height(34)
                    .onClick(b -> PlayerScreen.this.returnToMenu());
            this.srcIcon = new Icon("folder").iconSize(12).color(AppTheme.NEON_LIGHT);
            this.dbg = new Button("DEBUG").icon("debug")
                    .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT).size(112, 30)
                    .onClick(b -> {
                        PlayerScreen.this.debugOpen = !PlayerScreen.this.debugOpen;
                        PlayerScreen.this.ctx.sound.play();
                    });
            // WRAP_CONTENT: THE QUALITY AND LOD LABELS AND THEIR RESOLUTION CHIPS VARY IN WIDTH
            this.qual = new Button().icon("folder").accent(AppTheme.CYAN).textColor(AppTheme.CYAN).subTextColor(AppTheme.CYAN);
            this.qual.width(WRAP_CONTENT).height(30).onClick(v -> {
                PlayerScreen.this.openQualityDialog();
                PlayerScreen.this.ctx.sound.play();
            });
            this.lod = new Button().icon("tv").accent(AppTheme.AMBER).textColor(AppTheme.AMBER).subTextColor(AppTheme.CYAN);
            this.lod.width(WRAP_CONTENT).height(30).onClick(v -> {
                PlayerScreen.this.openVideoDialog();
                PlayerScreen.this.ctx.sound.play();
            });
            this.prev = this.key("prev", AppTheme.TEXT_SOFT, 42, p -> PlayerScreen.this.navigateSource(-1));
            this.rew = this.key("rewind", AppTheme.TEXT_SOFT, 46, MediaPlayer::rewind);
            this.stopBtn = this.key("stop", AppTheme.RED, 42, MediaPlayer::stop);
            this.play = this.key("play", AppTheme.GREEN, 78, PlayerScreen.this::togglePlayback);
            this.fwd = this.key("forward", AppTheme.TEXT_SOFT, 46, MediaPlayer::forward);
            this.next = this.key("next", AppTheme.TEXT_SOFT, 42, p -> PlayerScreen.this.navigateSource(1));
            this.spk = new Icon("speaker").iconSize(14).color(AppTheme.TEXT_SOFT);
            this.seek = new SeekBar().trackHeight(6).onChange(v -> {
                final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                if (p != null && !p.error() && p.duration() > 0) p.seek((long) (p.duration() * v));
            });
            this.vol = new SeekBar().trackHeight(5);
            this.vol.size(130, 20).onChange(v -> {
                final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                if (p != null && !p.error()) p.volume(Math.round(v * 100f));
            });
            this.loop = new Button("ON").icon("repeat").size(78, 30)
                    .onClick(b -> {
                        final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                        if (p == null || p.error()) return;
                        PlayerScreen.this.toggleLoop(p);
                        PlayerScreen.this.ctx.sound.play();
                    });
            // THE MENU MOUNTS ON THE SCREEN OVERLAY SO IT PAINTS OVER THE HUD; Dropdown FLIPS IT ABOVE
            // THE CONTROL NEAR THE BOTTOM EDGE
            this.speedDrop = new Dropdown().mode(Dropdown.Mode.ATTACH).accent(AppTheme.NEON)
                    .items(Arrays.asList(SPEED_LABELS)).selected(3)
                    .overlayHost(PlayerScreen.this.overlay())
                    .onSelect(i -> {
                        final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                        if (p != null && !p.error() && p.speed(SPEED_VALUES[i])) {
                            PlayerScreen.this.speed = SPEED_VALUES[i];
                            PlayerScreen.this.ctx.sound.play();
                        }
                    });
            this.speedDrop.size(90, 30);
            this.add(this.metrics).add(this.srcIcon).add(this.spk)
                    .add(this.seek).add(this.vol)
                    .add(this.dbg).add(this.qual).add(this.lod)
                    .add(this.prev).add(this.rew).add(this.stopBtn).add(this.play).add(this.fwd).add(this.next)
                    .add(this.loop).add(this.speedDrop)
                    .add(this.back);
        }

        // ======================================================================
        // PUBLIC API
        // ======================================================================

        @Override
        public Element<?> dispatchPress(final double mx, final double my) {
            final Element<?> hit = super.dispatchPress(mx, my);
            // ONLY THE PRESS THAT GRABS A BAR CHIMES; DRAG MOVES STAY SILENT
            if (hit == this.seek || hit == this.vol) PlayerScreen.this.ctx.sound.play();
            return hit;
        }

        // ======================================================================
        // INTERNAL API
        // ======================================================================

        @Override
        protected void onUpdate() {
            final MediaPlayer p = PlayerScreen.this.ctx.media.player;
            this.errorMode = p == null || p.error();
            this.multiSource = PlayerScreen.this.ctx.media.availableSources != null && PlayerScreen.this.ctx.media.availableSources.length > 1;
            final boolean alive = !this.errorMode;
            this.metrics.visible(alive && PlayerScreen.this.debugOpen);
            this.srcIcon.visible(alive);
            this.spk.visible(alive);
            this.seek.visible(alive);
            this.vol.visible(alive);
            this.dbg.visible(alive);
            this.qual.visible(alive);
            this.lod.visible(alive);
            this.prev.visible(alive && this.multiSource);
            this.rew.visible(alive);
            this.stopBtn.visible(alive);
            this.play.visible(alive);
            this.fwd.visible(alive);
            this.next.visible(alive && this.multiSource);
            this.loop.visible(alive);
            this.speedDrop.visible(alive);
            if (!alive) return;

            final long duration = Math.max(0, p.duration());
            this.seek.enabled(duration > 0);
            this.seek.value(duration > 0 ? (float) p.time() / duration : 0f);
            this.vol.value(MathUtil.clamp(p.volume(), 0, 100) / 100f);
            this.spk.color(this.vol.hovered() ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT);
            final boolean playing = p.playing();
            this.play.icon(playing ? "pause" : "play")
                    .accent(playing ? AppTheme.AMBER : AppTheme.GREEN)
                    .iconColor(playing ? AppTheme.AMBER : AppTheme.GREEN);
            final boolean loopOn = PlayerScreen.this.loopEnabled;
            this.loop.label(loopOn ? "ON" : "OFF")
                    .accent(loopOn ? AppTheme.GREEN : AppTheme.TEXT_FAINT)
                    .textColor(loopOn ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
            final String scale = PlayerScreen.this.scaleLabel();
            this.qual.label(p.quality().name()).subText(scale);
            this.lod.label(MediaPlayer.LodLevel.values()[PlayerScreen.this.lodSelectedIndex].name()).subText(scale);
            // THE SELECTION SNAPS TO THE PRESET NEAREST THE LIVE SPEED; LOCKED WHEN THE PLAYER CANNOT
            // CHANGE SPEED (LIVE STREAM OR AN ENGINE FIXED AT 1.0x)
            this.speedDrop.enabled(p.canSpeed());
            this.speedDrop.selected(PlayerScreen.this.nearestSpeedIndex(p.speed()));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.transportYL = Math.max(120, innerAvailHeight - 92);
            this.metrics.size(MathUtil.clamp(innerAvailWidth / 4, 240, 405), Math.max(220, this.transportYL - 80 - 14));

            final MediaPlayer p = PlayerScreen.this.ctx.media.player;
            final long duration = p == null ? 0 : Math.max(0, p.duration());
            this.leftTime = p == null ? "" : MediaSession.formatTime(p.time());
            this.rightTime = duration > 0 ? MediaSession.formatTime(duration) : "--:--";
            final int rightTimeW = PlayerScreen.this.text.width(this.rightTime, AppTheme.TEXT_BODY);
            this.barW = Math.max(120, innerAvailWidth - 112 - rightTimeW - 44);
            this.seek.size(this.barW, 20);

            for (final Element<?> child: this.children) {
                if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
            }
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int x0 = this.innerLeft();
            final int y0 = this.innerTop();
            final int w = this.innerWidth();
            this.topYA = y0 + 24;
            // BACK IS PLACED BEFORE THE ERROR EXIT SO IT STAYS CLICKABLE WHEN THE PLAYER FAILS
            this.back.layout(x0 + 16, this.topYA);
            if (this.errorMode) return;

            this.transportYA = y0 + this.transportYL;
            this.controlsYA = this.transportYA + 42;
            this.metrics.layout(x0 + 14, y0 + 80);
            this.seek.layout(x0 + 112, this.transportYA + 4);

            // LEFT CLUSTER: DEBUG, QUALITY, LOD AND DIVIDER, PACKED BY MEASURED WIDTH AS QUALITY AND LOD VARY
            final int rowY = this.controlsYA - 5;
            int lx = x0 + 28;
            this.dbg.layout(lx, rowY);
            lx += this.dbg.measuredWidth() + 8;
            this.qual.layout(lx, rowY);
            lx += this.qual.measuredWidth() + 8;
            this.lod.layout(lx, rowY);
            this.dividerXA = lx + this.lod.measuredWidth() + 14;

            // CENTER CLUSTER: CENTERED TRANSPORT KEYS, PUSHED RIGHT OF THE DIVIDER WHEN CRAMPED
            final int controlsW = 244 + (this.multiSource ? 100 : 0);
            int cx = Math.max(this.dividerXA + 20, x0 + (w - controlsW) / 2);
            if (this.multiSource) {
                this.prev.layout(cx, rowY);
                cx += 50;
            }
            this.rew.layout(cx, rowY);
            cx += 54;
            this.stopBtn.layout(cx, rowY);
            cx += 50;
            this.play.layout(cx, rowY);
            cx += 86;
            this.fwd.layout(cx, rowY);
            cx += 54;
            if (this.multiSource) this.next.layout(cx, rowY);

            // RIGHT CLUSTER: VOLUME + DIVIDER + LOOP + SPEED (RIGHT GROUP WIDTH 413, ANCHORED RIGHT)
            int rx = Math.max(cx + 78, x0 + w - 28 - 413);
            this.spk.layout(rx + 8, rowY + 8);
            this.vol.layout(rx + 30, this.controlsYA);
            this.volPctXA = rx + 170;
            rx += 222;
            this.volDividerXA = rx;
            rx += 14;
            this.loop.layout(rx, rowY);
            rx += 86;
            this.speedDrop.layout(rx, rowY);

            // TOP INFO ROW TEXTS AND TAGS, BUILT HERE BECAUSE TRUNCATION DEPENDS ON THE WIDTH
            final TextRenderer text = PlayerScreen.this.text;
            final AppContext ctx = PlayerScreen.this.ctx;
            final MediaPlayer p = ctx.media.player;
            final Metadata meta = ctx.media.selectedSource != null ? ctx.media.selectedSource.metadata() : null;
            final String title = meta != null && meta.title() != null ? meta.title() : ctx.media.selectedMRLName;
            final String author = meta != null && meta.author() != null ? meta.author() : p.getClass().getSimpleName();
            final String host = ctx.media.selectedMRL != null && ctx.media.selectedMRL.uri().getHost() != null ? ctx.media.selectedMRL.uri().getHost() : "local";
            final String posted = meta != null && meta.postedAt() != null ? DATE_FORMAT.format(meta.postedAt()) : "unknown date";
            final int sources = ctx.media.availableSources != null ? ctx.media.availableSources.length : 1;
            this.titleStr = text.truncateToWidth(title, Math.max(180, w - 520), AppTheme.TEXT_SECTION, Font.BOLD);
            int tagX = x0 + 108 + text.widthBold(this.titleStr, AppTheme.TEXT_SECTION) + 12;
            this.tagType = ctx.media.selectedSource == null ? null : ctx.media.selectedSource.type();
            this.showTypeTag = this.tagType != null && tagX < x0 + w - 180;
            if (this.showTypeTag) {
                this.typeTagXA = tagX;
                tagX += PlayerScreen.this.typeTagWidth(this.tagType) + 8;
            }
            this.srcLabel = (ctx.media.selectedSourceIndex + 1) + "/" + sources;
            this.srcTagW = text.width(this.srcLabel, AppTheme.TEXT_BODY) + 46;
            final boolean showSrc = tagX + this.srcTagW < x0 + w - 20;
            this.srcIcon.visible(showSrc);
            if (showSrc) {
                this.srcTagXA = tagX;
                this.srcIcon.layout(tagX + 9, this.topYA - 6 + 6);
            }
            this.authorStr = text.truncateToWidth(author + " - " + host + " - " + posted, Math.max(180, w - 520), AppTheme.TEXT_BODY);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // THE HUD SITS OVER THE FULL-SCREEN CRT, WHOSE BAND KEEPS MOVING UNDER IT WHILE THE PLAYER IS PAUSED
            canvas.raise();
            if (!this.errorMode) {
                // CHROME USES this.left AND measuredWidth, NEVER canvas.windowWidth(), SO A SLOT INSET
                // CANNOT SPLIT IT FROM THE CHILDREN PLACED BY onLayout
                final int x0 = this.left;
                final int w = this.measuredWidth;
                final int hudY = this.top;
                final int hudH = this.measuredHeight;
                canvas.gradientH(x0, hudY, 380, hudH, FADE_DARK, FADE_CLEAR);
                canvas.gradientV(x0, hudY + hudH - 120, w, 120, FADE_CLEAR, FADE_DARK);
                canvas.gradientV(x0, hudY, w, 96, BAND_DARK, BAND_CLEAR);

                // TRANSPORT BAR CHROME
                canvas.fill(x0 + 14, this.transportYA, w - 28, 76, AppTheme.alpha(AppTheme.BG_1, 224));
                canvas.stroke(x0 + 14, this.transportYA, w - 28, 76, AppTheme.NEON, 2f);
                canvas.glow(x0 + 14, this.transportYA, w - 28, 76, 0f, AppTheme.NEON, 0.16f);
                canvas.fill(x0 + 28, this.transportYA + 74, 10, 3, AppTheme.AMBER);
                canvas.fill(x0 + w - 38, this.transportYA + 74, 10, 3, AppTheme.AMBER);
                canvas.stroke(x0, hudY, w, hudH, AppTheme.STROKE_BRIGHT, 1f);

                // TOP INFO ROW
                canvas.text(this.titleStr, x0 + 108, this.topYA - 3, AppTheme.TEXT, AppTheme.TEXT_SECTION, true);
                if (this.showTypeTag) PlayerScreen.this.drawTypeTag(canvas, this.typeTagXA, this.topYA - 5, this.tagType);
                if (this.srcIcon.visible()) {
                    canvas.fill(this.srcTagXA, this.topYA - 6, this.srcTagW, 24, AppTheme.alpha(AppTheme.BG_1, 188));
                    canvas.stroke(this.srcTagXA, this.topYA - 6, this.srcTagW, 24, AppTheme.NEON_LIGHT, 1f);
                    canvas.text(this.srcLabel, this.srcTagXA + 28,
                            this.topYA - 6 + Math.max(0, (24 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2) + 1,
                            AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
                }
                canvas.text(this.authorStr, x0 + 108, this.topYA + 24, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);

                // TIME LABELS, VOLUME PERCENT AND VERTICAL DIVIDERS
                final int timeY = this.transportYA + Math.max(0, (28 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2);
                canvas.text(this.leftTime, x0 + 26, timeY, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
                canvas.text(this.rightTime, x0 + 112 + this.barW + 18, timeY, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
                canvas.fill(this.dividerXA, this.controlsYA - 6, 1, 32, AppTheme.STROKE_BRIGHT);
                canvas.fill(this.volDividerXA, this.controlsYA - 6, 1, 32, AppTheme.STROKE_BRIGHT);
                final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                if (p != null) {
                    final int volume = MathUtil.clamp(p.volume(), 0, 100);
                    canvas.text(volume + "%", this.volPctXA,
                            this.controlsYA - 5 + Math.max(0, (30 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                            AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
                }
            }
            super.onDraw(canvas);
        }

        // ======================================================================
        // HELPERS
        // ======================================================================

        // ICON-ONLY TRANSPORT KEY WITH THE SHARED GUARD: NO-OP WITHOUT A HEALTHY PLAYER, CHIME ON HIT
        private IconButton key(final String icon, final Color color, final int w, final Consumer<MediaPlayer> action) {
            final IconButton button = new IconButton(icon).accent(color).iconColor(color);
            button.size(w, 30).onClick(b -> {
                final MediaPlayer p = PlayerScreen.this.ctx.media.player;
                if (p == null || p.error()) return;
                action.accept(p);
                PlayerScreen.this.ctx.sound.play();
            });
            return button;
        }
    }
}
