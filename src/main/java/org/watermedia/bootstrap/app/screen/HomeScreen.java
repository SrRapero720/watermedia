package org.watermedia.bootstrap.app.screen;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.WaterMedia;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.CacheCleaner;
import org.watermedia.bootstrap.app.LogUploader;
import org.watermedia.bootstrap.app.MediaCatalog;
import org.watermedia.bootstrap.app.element.Badge;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListRow;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ProgressBar;
import org.watermedia.bootstrap.app.element.SectionHead;
import org.watermedia.bootstrap.app.element.Stepper;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextArea;
import org.watermedia.bootstrap.app.element.Theme;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Home screen: menu tiles grouped by section and the upload-logs and cleanup {@link Dialog}s, bound every frame
 * to {@code ctx.upload} and {@code ctx.cleanup}, which own their flow; this screen only fires actions.
 */
public class HomeScreen extends BaseScreen {
    private static final Marker IT = MarkerManager.getMarker(HomeScreen.class.getSimpleName());

    // ONE ROW HEIGHT FOR THE SCAN AND THE UPLOAD: THE NAME WITH THE PROGRESS BAR UNDER IT TAKES ABOUT 31 PX, AND SEVEN
    // ROWS FIT A 1280x720 WINDOW WITHOUT SCROLLING
    private static final int UPLOAD_ROW_H = 52;
    private static final int UPLOAD_PANEL_PAD = 14;
    // ONE WIDTH FOR EVERY STAGE: ITS TITLE BOX (830 PX INSIDE) SHOWS A TITLE AT THE 80 CHARACTER LIMIT ON ONE ROW
    private static final int UPLOAD_PANEL_W = 890;
    private static final int CLEANUP_ROW_H = 58;

    // ACTION TILE FILLS
    private static final Color TILE_BG_DISABLED = new Color(15, 20, 36, 148);
    private static final Color TILE_BG_EXIT = new Color(51, 10, 20, 235);
    private static final Color TILE_BG_EXIT_SEL = new Color(66, 18, 28, 235);
    private static final Color TILE_BG_SEL = new Color(AppTheme.BG_2.getRed(), 34, 66, 235);

    // ACCENT PALETTE, STATIC SO THE PER-TILE DRAWS NEVER ALLOCATE
    private static final Color[] CATEGORY_PALETTE = {AppTheme.NEON, AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT};

    // FOOTER KEYBINDS: CONSTANTS SO THE PER-FRAME keybinds() POLL OF KeybindsBar ALLOCATES NOTHING
    private static final List<Keybind> KEYS_UPLOAD_REPORT = List.of(
            new Keybind("TAB", "Field"), new Keybind("CTRL+ENTER", "Open issue"), new Keybind("ESC", "Close"));
    private static final List<Keybind> KEYS_UPLOAD_SCAN = List.of(
            new Keybind("ENTER", "Upload"), new Keybind("ESC", "Cancel"));
    // THE UPLOAD STAGE (NOTHING IS LEFT TO CANCEL ONCE THE FILES GO UP) AND THE CLEANUP DIALOG SHARE ONE LIST
    private static final List<Keybind> KEYS_CONTINUE = List.of(
            new Keybind("ENTER", "Continue"), new Keybind("ESC", "Close"));
    private static final List<Keybind> KEYS_MENU = List.of(
            new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Exit"));

    private final Consumer<Action> navigator;
    private final List<MenuEntry> actions = new ArrayList<>();
    private final List<MenuEntry> mediaTests = new ArrayList<>();
    private final List<MenuEntry> entertainment = new ArrayList<>();
    private int selectedPanel;
    private int selectedAction;
    private int selectedMedia;
    // MEDIA TILES LAID OUT THIS FRAME; THE KEYBOARD SELECTION IS CLAMPED TO IT SO ARROWS AND ENTER NEVER
    // LAND ON AN OVERFLOWING TILE THAT IS NEITHER DRAWN NOR CLICKABLE
    private int visibleMediaCount;
    private int selectedEntertainment;

    // RETAINED TREE: BUILT ONCE, BOUND TO LIVE STATE EVERY FRAME IN onUpdate
    private HomeBody grid;
    private Dialog uploadDialog;
    private Dialog cleanupDialog;
    private ListView<LogUploader.FileEntry> fileList;
    private Parent filesPanel;
    private ReportPane reportPane;
    private Button uploadCancel;
    private Button uploadPrimary;
    private Button cleanupCancel;
    private Button cleanupPrimary;
    private List<LogUploader.FileEntry> uploadRows = List.of();

    // CACHE-SIZE LABEL FOR THE CLEANUP ENTRY, COMPUTED OFF THE RENDER THREAD (Files.walk BLOCKS ON LARGE
    // CACHES); rebuildMenu() READS THE LAST PUBLISHED VALUE AND cacheWalking COALESCES OVERLAPPING WALKS
    private volatile String cacheLabel = "--";
    private volatile boolean cacheWalking;
    private volatile boolean cacheWarned;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    @Override
    public void onEnter() {
        this.rebuildMenu();
        super.onEnter(); // BUILDS THE TREE ON FIRST ENTRY, AFTER THE MENU LISTS EXIST
        this.refreshCacheSize();
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE GOES FIRST: A VISIBLE DIALOG IS MODAL AND SWALLOWS EVERYTHING (ESC/ENTER/ARROWS)
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_UP -> this.moveSelection(-1);
            case GLFW_KEY_DOWN -> this.moveSelection(1);
            case GLFW_KEY_LEFT -> this.switchPanel(0);
            case GLFW_KEY_RIGHT -> this.switchPanel(this.mediaTests.isEmpty() && !this.entertainment.isEmpty() ? 2 : 1);
            case GLFW_KEY_U, GLFW_KEY_S -> {
                // RESOLVE BY ACTION, NOT A HARDCODED SLOT: A MENU REORDER MUST NOT RETARGET A SHORTCUT AT ANOTHER ENTRY
                final int idx = this.actionIndex(key == GLFW_KEY_U ? Action.UPLOAD_LOGS : Action.SETTINGS);
                if (idx >= 0) {
                    this.selectedPanel = 0;
                    this.selectedAction = idx;
                    this.confirmSelection();
                }
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.confirmSelection();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(Action.EXIT);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.ctx.upload.visible)
            return this.ctx.upload.stage >= 3 ? KEYS_UPLOAD_REPORT : this.ctx.upload.stage == 2 ? KEYS_CONTINUE : KEYS_UPLOAD_SCAN;
        if (this.ctx.cleanup.visible) return KEYS_CONTINUE;
        return KEYS_MENU;
    }

    @Override
    public boolean continuous() {
        // LOADING AND WORKER PROGRESS, AND THE CARET BLINK OF THE ISSUE INPUTS
        return this.ctx.backendsLoading || this.ctx.upload.working || this.ctx.cleanup.working || this.textInputActive();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected Element<?> build() {
        this.grid = new HomeBody();
        this.grid.sync();
        final Parent column = Parent.column()
                .size(MAX_PARENT, MAX_PARENT)
                .add(new Header().name("Multimedia API").sub("main menu").right("v" + WaterMedia.VERSION))
                .add(this.grid.size(MAX_PARENT, MAX_PARENT).padding(new Spacing(20, 22, 10, 22)));

        // BOTH DIALOGS STAY MOUNTED ON THE OVERLAY; onUpdate ONLY TOGGLES THEIR VISIBILITY
        this.uploadDialog = this.buildUploadDialog();
        this.cleanupDialog = this.buildCleanupDialog();
        this.overlay().add(this.uploadDialog.visible(false));
        this.overlay().add(this.cleanupDialog.visible(false));
        return column;
    }

    @Override
    protected void onUpdate() {
        // FRAME BINDING: PULLS ctx.upload AND ctx.cleanup INTO THE RETAINED DIALOGS
        if (this.uploadDialog == null) return; // TREE NOT BUILT YET

        final var up = this.ctx.upload;
        final boolean report = up.stage >= 3;
        if (this.uploadDialog.visible() != up.visible || this.cleanupDialog.visible() != this.ctx.cleanup.visible) this.invalidate();

        this.uploadDialog.visible(up.visible)
                .accent(up.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(UPLOAD_PANEL_W, this.ctx.logicalWidth() - 48));
        this.filesPanel.visible(!report);
        this.reportPane.visible(report);

        if (up.visible && !report) {
            // SCAN SHOWS EVERY FILE, UPLOAD ONLY THE CANDIDATES AND THE FAILURES
            final List<LogUploader.FileEntry> rows = new ArrayList<>();
            for (final LogUploader.FileEntry entry: up.files)
                if (up.stage <= 1 || (entry.present && entry.valid) || "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) rows.add(entry);
            // ROWS ARE REBUILT ONLY WHEN MEMBERSHIP CHANGES; ROW CONTENT BINDS ITSELF EVERY FRAME
            if (!rows.equals(this.uploadRows)) {
                this.uploadRows = rows;
                this.fileList.items(rows);
            }
            // AN EMPTY SCAN KEEPS ONE BLANK ROW; A LIST TALLER THAN WHAT THE DIALOG OFFERS CLIPS TO THAT AND SCROLLS
            this.fileList.height(rows.isEmpty() ? UPLOAD_ROW_H : WRAP_CONTENT);
        }

        // A REPORT THAT FAILED TO GATHER TURNS THE ISSUE BUTTON INTO ITS RETRY, WHICH NEEDS NO TITLE
        final boolean retry = report && !up.working && !up.ready();
        // NOTHING CAN BE CANCELLED ONCE THE FILES WENT UP: FROM THE UPLOAD STAGE ON THE BUTTON ONLY CLOSES
        this.uploadCancel.label(up.stage <= 1 ? "CANCEL" : "CLOSE");
        final boolean upEnabled = this.uploadPrimaryEnabled();
        final Color upColor = !upEnabled ? AppTheme.TEXT_FAINT : up.done || up.uploadsDone ? AppTheme.GREEN : AppTheme.CYAN;
        this.uploadPrimary.label(up.stage <= 1 ? "UPLOAD TO MCLO.GS" : up.stage == 2 ? "CONTINUE" : retry ? "RETRY" : "OPEN ISSUE")
                .subText(report ? "CTRL+ENTER" : "ENTER")
                .icon(up.stage <= 1 ? "upload" : up.stage == 2 ? "check" : retry ? "reload" : "link")
                .accent(upColor).textColor(upColor)
                .enabled(upEnabled);

        final var cl = this.ctx.cleanup;
        this.cleanupDialog.visible(cl.visible)
                .title(cl.done ? "CACHE CLEANED" : "CLEAN CACHE")
                .accent(cl.error ? AppTheme.RED : cl.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(740, this.ctx.logicalWidth() - 48));
        // ONCE THE CLEAN RAN THE PRIMARY ALREADY SAYS CLOSE: HIDE CANCEL SO THE FOOTER NEVER SHOWS TWO CLOSE BUTTONS
        this.cleanupCancel.visible(cl.stage <= 1);
        final boolean clEnabled = this.cleanupPrimaryEnabled();
        final String clLabel = cl.stage <= 1 ? "CLEAN CACHE" : "CLOSE";
        final Color clColor = !clEnabled ? AppTheme.TEXT_FAINT : cl.done ? AppTheme.GREEN : AppTheme.CYAN;
        this.cleanupPrimary.label(clLabel)
                .icon(clLabel.startsWith("CLEAN") ? "broom" : "x")
                .accent(clColor).textColor(clColor)
                .enabled(clEnabled);
    }

    // ==========================================================================
    // TREE CONSTRUCTION
    // ==========================================================================

    private Dialog buildUploadDialog() {
        final Stepper strip = new Stepper("SCAN", "UPLOAD", "ISSUE")
                .complete(step -> this.ctx.upload.stage > step || this.ctx.upload.done || (step == 2 && this.ctx.upload.uploadsDone))
                .active(step -> this.ctx.upload.stage == step && !this.ctx.upload.done);

        this.fileList = new ListView<LogUploader.FileEntry>()
                .rowHeight(UPLOAD_ROW_H)
                .rowFactory(this::uploadRow)
                .selectionColor(null)
                .hoverColor(null);
        this.fileList.width(MAX_PARENT);
        this.filesPanel = Parent.column()
                .padding(new Spacing(UPLOAD_PANEL_PAD, 18, UPLOAD_PANEL_PAD, 18))
                .add(this.fileList);
        this.filesPanel.background(AppTheme.alpha(AppTheme.BG_2, 164))
                .border(AppTheme.STROKE_BRIGHT, 1f)
                .width(MAX_PARENT);

        this.reportPane = new ReportPane();

        this.uploadCancel = new Button("CANCEL").subText("ESC").icon("x")
                .accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.uploadCancel.size(170, Theme.BUTTON_LG).onClick(b -> this.closeUpload());
        this.uploadPrimary = new Button().subText("ENTER");
        this.uploadPrimary.height(Theme.BUTTON_LG).onClick(b -> this.uploadPrimaryAction());

        return new Dialog()
                .title("UPLOAD LOGS")
                .header(strip)
                .content(this.filesPanel)
                .content(this.reportPane)
                .onDismiss(this::closeUpload)
                .onPrimary(this::uploadPrimaryAction)
                .actions(this.uploadCancel, this.uploadPrimary);
    }

    private Dialog buildCleanupDialog() {
        final Stepper strip = new Stepper("SCAN", "CLEAN")
                .complete(step -> this.ctx.cleanup.stage > step || (step == 2 && this.ctx.cleanup.done))
                .active(step -> this.ctx.cleanup.stage == step && !this.ctx.cleanup.done);

        final FileRow row = new FileRow();
        row.pip.status(() -> {
            if (this.ctx.cleanup.error) return StatusSquare.Status.ERROR;
            if ("EMPTY".equals(this.ctx.cleanup.state)) return StatusSquare.Status.WARN;
            if (this.ctx.cleanup.working) return StatusSquare.Status.PENDING;
            if (this.ctx.cleanup.done || "FOUND".equals(this.ctx.cleanup.state)) return StatusSquare.Status.OK;
            return StatusSquare.Status.OFF;
        });
        row.binder(r -> {
            final int count = this.ctx.cleanup.fileCount;
            row.twoLine = this.ctx.cleanup.stage == 2 && this.ctx.cleanup.working;
            row.name.text(count == 1 ? "1 FILE" : count + " FILES")
                    .color(count > 0 ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            row.bar.visible(row.twoLine)
                    .progress(MathUtil.clamp(this.ctx.cleanup.progress, 0, 100) / 100f);
            row.size.text(this.ctx.cleanup.sizeLabel);
            row.tag.label(this.ctx.cleanup.stage == 2 && this.ctx.cleanup.working
                    ? MathUtil.clamp(this.ctx.cleanup.progress, 0, 100) + "%" : this.ctx.cleanup.state).color(this.cleanupStateColor());
        });
        row.size(MAX_PARENT, CLEANUP_ROW_H);
        final Parent panel = Parent.column()
                .padding(new Spacing(UPLOAD_PANEL_PAD, 18, UPLOAD_PANEL_PAD, 18))
                .add(row);
        panel.background(AppTheme.alpha(AppTheme.BG_2, 164))
                .border(AppTheme.STROKE_BRIGHT, 1f)
                .width(MAX_PARENT);

        this.cleanupCancel = new Button("CANCEL").subText("ESC").icon("x")
                .accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.cleanupCancel.size(170, Theme.BUTTON_LG).onClick(b -> this.dismissCleanup());
        this.cleanupPrimary = new Button().subText("ENTER");
        this.cleanupPrimary.height(Theme.BUTTON_LG).onClick(b -> this.cleanupPrimaryAction());

        return new Dialog()
                .title("CLEAN CACHE")
                .header(strip)
                .content(panel)
                .onDismiss(this::dismissCleanup)
                .onPrimary(this::cleanupPrimaryAction)
                .actions(this.cleanupCancel, this.cleanupPrimary);
    }

    // ==========================================================================
    // MENU BEHAVIOR
    // ==========================================================================

    private void rebuildMenu() {
        this.actions.clear();
        this.mediaTests.clear();
        this.entertainment.clear();

        this.actions.add(new MenuEntry("Play media", "ENTER", Action.OPEN_MULTIMEDIA, -1));
        this.actions.add(new MenuEntry("Upload Logs", LogUploader.AVAILABLE ? "U" : "LOCKED", Action.UPLOAD_LOGS, -1));
        this.actions.add(new MenuEntry("Cleanup cache", this.cacheLabel, Action.CLEANUP, -1));
        if (WaterMedia.LOGGER.isDebugEnabled()) this.actions.add(new MenuEntry("Settings", "S", Action.SETTINGS, -1));
        this.actions.add(new MenuEntry("Exit", "ESC", Action.EXIT, -1));

        if (this.ctx.catalog.uriGroups != null) {
            for (int i = 0; i < this.ctx.catalog.uriGroups.length; i++) {
                final MediaCatalog.URIGroup group = this.ctx.catalog.uriGroups[i];
                this.mediaTests.add(new MenuEntry(group.name(), group.uris().length == 1 ? "1 sample" : group.uris().length + " samples", null, i));
            }
        }
        if (!this.ctx.catalog.customTests.isEmpty()) {
            final String label = "CUSTOM (" + this.ctx.catalog.customTests.size() + ")";
            this.mediaTests.add(new MenuEntry(label, "custom", null, -2));
        }
        if (this.ctx.catalog.iptvChannels.length > 0) this.entertainment.add(new MenuEntry("Television", "", Action.REGION_SELECTOR, -1));

        // THE KEYBOARD SELECTION MUST NEVER POINT AT A TILE THE FRESH LISTS DROPPED
        this.selectedAction = MathUtil.clamp(this.selectedAction, 0, this.actions.size() - 1);
        this.selectedMedia = MathUtil.clamp(this.selectedMedia, 0, Math.max(0, this.mediaTests.size() - 1));
        this.selectedEntertainment = MathUtil.clamp(this.selectedEntertainment, 0, Math.max(0, this.entertainment.size() - 1));
        if (this.selectedPanel == 1 && this.mediaTests.isEmpty()) this.selectedPanel = 0;
        if (this.selectedPanel == 2 && this.entertainment.isEmpty()) this.selectedPanel = 0;

        // REBUILD THE TILE ELEMENTS SO THEY MATCH THE CURRENT MENU ENTRIES
        if (this.grid != null) this.grid.sync();
    }

    private void handleSelect(final MenuEntry entry) {
        // THE CUSTOM GROUP (-2) IS BUILT FROM THE LINKS ADDED THIS RUN; ACTION ENTRIES CARRY NO GROUP
        if (entry.groupIndex() >= 0 && entry.groupIndex() < this.ctx.catalog.uriGroups.length)
            this.openGroup(this.ctx.catalog.uriGroups[entry.groupIndex()]);
        else if (entry.groupIndex() == -2 && !this.ctx.catalog.customTests.isEmpty())
            this.openGroup(new MediaCatalog.URIGroup("CUSTOM", this.ctx.catalog.customTests.toArray(new MediaCatalog.TestURI[0])));
        else if (entry.action() != null && this.actionEnabled(entry))
            this.navigator.accept(entry.action());
    }

    private void openGroup(final MediaCatalog.URIGroup group) {
        this.ctx.media.selectedGroup = group;
        this.ctx.media.groupMRLs.clear();
        for (final MediaCatalog.TestURI testUri: group.uris())
            this.ctx.media.groupMRLs.put(testUri.name(), PlayerAPI.mrl(testUri.uri()));
        this.navigator.accept(Action.MRL_SELECTOR);
    }

    private boolean actionEnabled(final MenuEntry entry) {
        if (entry.action() == Action.UPLOAD_LOGS) return LogUploader.AVAILABLE;
        if (entry.action() == Action.SETTINGS) return WaterMedia.LOGGER.isDebugEnabled();
        return true;
    }

    private int actionIndex(final Action action) {
        for (int i = 0; i < this.actions.size(); i++) {
            if (this.actions.get(i).action() == action) return i;
        }
        return -1;
    }

    private void moveSelection(final int delta) {
        if (this.selectedPanel == 0 && !this.actions.isEmpty()) {
            this.selectedAction = MathUtil.clamp(this.selectedAction + delta, 0, this.actions.size() - 1);
        } else if (this.selectedPanel == 2) {
            if (delta < 0 && !this.mediaTests.isEmpty()) {
                this.selectedPanel = 1;
                this.selectedMedia = Math.max(0, this.visibleMediaCount - 1);
            } else {
                this.selectedEntertainment = Math.max(0, Math.min(this.entertainment.size() - 1, this.selectedEntertainment + delta));
            }
        } else if (!this.mediaTests.isEmpty()) {
            final int next = this.selectedMedia + delta * 2;
            if (delta > 0 && next >= this.mediaTests.size() && !this.entertainment.isEmpty()) {
                this.selectedPanel = 2;
                this.selectedEntertainment = 0;
            } else {
                this.selectedMedia = Math.max(0, Math.min(this.visibleMediaCount - 1, next));
            }
        } else if (!this.entertainment.isEmpty()) {
            this.selectedPanel = 2;
            this.selectedEntertainment = MathUtil.clamp(this.selectedEntertainment + delta, 0, this.entertainment.size() - 1);
        }
        this.ctx.sound.play();
        this.invalidate();
    }

    private void switchPanel(final int panel) {
        if (this.selectedPanel != panel) {
            this.selectedPanel = panel;
            this.ctx.sound.play();
            this.invalidate();
        }
    }

    private void confirmSelection() {
        if (this.selectedPanel == 0 && this.selectedAction < this.actions.size())
            this.handleSelect(this.actions.get(this.selectedAction));
        else if (this.selectedPanel == 1 && this.selectedMedia < this.visibleMediaCount)
            this.handleSelect(this.mediaTests.get(this.selectedMedia));
        else if (this.selectedPanel == 2 && this.selectedEntertainment < this.entertainment.size())
            this.handleSelect(this.entertainment.get(this.selectedEntertainment));
    }

    private void refreshCacheSize() {
        // COALESCE: SKIP IF A WALK IS ALREADY RUNNING SO RE-ENTERING HOME DOES NOT SPAWN REDUNDANT WALKS
        if (this.cacheWalking) return;
        this.cacheWalking = true;
        ThreadTool.thread("WaterMedia-CacheSize", () -> {
            long bytes = 0L;
            try {
                bytes = CacheCleaner.scan()[1];
            } catch (final IOException | RuntimeException e) {
                // THE WALK REPEATS ON EVERY HOME ENTRY AND THE CLEANUP DIALOG REPORTS THE ERROR AGAIN WHEN OPENED
                if (!this.cacheWarned) WaterMedia.LOGGER.warn(IT, "Cannot measure the media cache; showing it as empty", e);
                this.cacheWarned = true;
            } finally {
                // RESET IN finally: A THROWN WALK MUST NOT LEAVE cacheWalking LATCHED (WHICH WOULD FREEZE THE LABEL FOREVER)
                this.cacheLabel = MathUtil.displayBytes(bytes);
                this.cacheWalking = false;
                // REBUILD ON THE RENDER THREAD SO THE CLEANUP ENTRY PICKS UP THE NEW LABEL
                this.ctx.execute(this::rebuildMenu);
            }
        });
    }

    // ==========================================================================
    // UPLOAD DIALOG BEHAVIOR
    // ==========================================================================

    // STATE AND FLOW LIVE IN ctx.upload, REACHED THROUGH navigateAction

    private void closeUpload() {
        this.ctx.upload.visible = false;
        this.ctx.sound.play();
        this.invalidate();
    }

    private void uploadPrimaryAction() {
        if (!this.uploadPrimaryEnabled()) return;
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.sound.play();
    }

    private boolean uploadPrimaryEnabled() {
        if (this.ctx.upload.working) return false;
        // THE ISSUE NEEDS A TITLE AND THE RETRY OF ITS REPORT NOTHING; THE EARLIER STAGES A FILE TO SEND, THEN ONE THAT WENT UP
        if (this.ctx.upload.stage > 2) return !this.ctx.upload.ready() || !this.ctx.upload.title.isBlank();
        // RUNS ON EVERY UPDATE PASS, SO PLAIN LOOPS INSTEAD OF STREAMS
        final boolean scan = this.ctx.upload.stage <= 1;
        for (final LogUploader.FileEntry entry: this.ctx.upload.files)
            if (scan ? entry.present && entry.valid : entry.uploaded) return true;
        return false;
    }

    // ROW FACTORY FOR THE UPLOAD FILE LIST: EACH ROW SELF-BINDS TO ITS LIVE ENTRY EVERY FRAME
    private Element<?> uploadRow(final LogUploader.FileEntry entry, final int index) {
        final FileRow row = new FileRow();
        row.pip.status(() -> uploadStatus(entry));
        row.binder(r -> {
            final int stage = this.ctx.upload.stage;
            final boolean errored = "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state);
            final int pct = MathUtil.clamp(entry.progress, 0, 100);
            row.twoLine = stage == 2 && entry.present && entry.valid && !errored;
            row.name.text(entry.name).color(entry.present ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            row.bar.visible(stage == 2 && entry.present).progress(pct / 100f);
            row.size.text(entry.sizeLabel);
            final String tag = stage == 2 && errored
                    ? "ERROR"
                    : stage == 2 && entry.present && entry.valid && !entry.uploaded
                    ? pct + "%"
                    : entry.state;
            row.tag.label(tag).color(uploadStateColor(entry));
            row.separator(index < this.uploadRows.size() - 1);
        });
        return row;
    }

    // ==========================================================================
    // CLEANUP DIALOG BEHAVIOR
    // ==========================================================================

    // STATE AND FLOW LIVE IN ctx.cleanup, REACHED THROUGH navigateAction

    private void dismissCleanup() {
        this.closeCleanupDialog();
        this.ctx.sound.play();
    }

    private void cleanupPrimaryAction() {
        if (!this.cleanupPrimaryEnabled()) return;
        if (this.ctx.cleanup.stage > 1) this.closeCleanupDialog();
        else this.navigator.accept(Action.CLEANUP);
        this.ctx.sound.play();
    }

    private boolean cleanupPrimaryEnabled() {
        if (this.ctx.cleanup.working) return false;
        return this.ctx.cleanup.stage > 1 || (this.ctx.cleanup.fileCount > 0 && !this.ctx.cleanup.error);
    }

    private Color cleanupStateColor() {
        if (this.ctx.cleanup.error) return AppTheme.RED;
        if ("EMPTY".equals(this.ctx.cleanup.state)) return AppTheme.AMBER;
        if (this.ctx.cleanup.working) return AppTheme.NEON_LIGHT;
        if (this.ctx.cleanup.done || "FOUND".equals(this.ctx.cleanup.state)) return AppTheme.GREEN;
        return AppTheme.TEXT_FAINT;
    }

    private void closeCleanupDialog() {
        this.ctx.cleanup.visible = false;
        this.rebuildMenu();
        this.refreshCacheSize();
        this.invalidate();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static Color uploadStateColor(final LogUploader.FileEntry entry) {
        if (entry.uploaded || "READY".equals(entry.state)) return AppTheme.GREEN;
        if ("UPLOADING".equals(entry.state)) return AppTheme.NEON_LIGHT;
        if ("TOO LARGE".equals(entry.state)) return AppTheme.AMBER;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return AppTheme.RED;
        return entry.present ? AppTheme.TEXT_FAINT : AppTheme.RED;
    }

    private static StatusSquare.Status uploadStatus(final LogUploader.FileEntry entry) {
        if (entry.uploaded || "READY".equals(entry.state)) return StatusSquare.Status.OK;
        if ("UPLOADING".equals(entry.state)) return StatusSquare.Status.PENDING;
        if ("TOO LARGE".equals(entry.state)) return StatusSquare.Status.WARN;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return StatusSquare.Status.ERROR;
        return entry.present ? StatusSquare.Status.OFF : StatusSquare.Status.ERROR;
    }

    // LIVE "n/limit" COUNTER OF AN INPUT, AMBER ONCE THE LIMIT IS REACHED
    private static void count(final Text counter, final int length, final int limit) {
        counter.text(length + "/" + limit).color(length >= limit ? AppTheme.AMBER : AppTheme.TEXT_FAINT);
    }

    // ==========================================================================
    // NESTED TYPES: MENU GRID
    // ==========================================================================

    /** Navigation vocabulary of the app: the home menu actions plus the cross-screen moves. */
    public enum Action {
        OPEN_MULTIMEDIA, UPLOAD_LOGS, CLEANUP,
        SETTINGS,
        REGION_SELECTOR,
        MRL_SELECTOR, PLAYER,
        EXIT, BACK
    }

    private record MenuEntry(String label, String meta, Action action, int groupIndex) {}

    // TWO-PANEL GRID: ACTIONS ON THE LEFT, MEDIA AND ENTERTAINMENT TILES ON THE RIGHT, WITH THE SECTION HEADS
    // AND THE UPLOAD TOOLTIP
    private final class HomeBody extends Group<HomeBody> {
        private static final int TILE_H = 94;
        private final List<ActionTile> actionElements = new ArrayList<>();
        private final List<MediaTile> mediaElements = new ArrayList<>();
        private final List<EntertainmentTile> entElements = new ArrayList<>();
        private UploadTip tip;
        private int rightXRel;
        private int tileW;
        private int rightXAbs;
        private int entYAbs;
        private boolean entHeadVisible;

        // RECREATES THE TILE ELEMENTS FROM THE CURRENT MENU LISTS (CALLED ON EVERY MENU REBUILD)
        private void sync() {
            this.children.clear();
            this.actionElements.clear();
            this.mediaElements.clear();
            this.entElements.clear();
            for (int i = 0; i < HomeScreen.this.actions.size(); i++) {
                final ActionTile tile = new ActionTile(i);
                this.actionElements.add(tile);
                this.add(tile);
            }
            for (int i = 0; i < HomeScreen.this.mediaTests.size(); i++) {
                final MediaTile tile = new MediaTile(i);
                this.mediaElements.add(tile);
                this.add(tile);
            }
            for (int i = 0; i < HomeScreen.this.entertainment.size(); i++) {
                final EntertainmentTile tile = new EntertainmentTile(i);
                this.entElements.add(tile);
                this.add(tile);
            }
            // TOOLTIP LAST SO IT PAINTS ON TOP OF THE TILES BELOW ITS ANCHOR
            this.tip = new UploadTip();
            this.add(this.tip);
            this.invalidate();
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final int gap = 18;
            final int colGap = 10;
            final int leftW = Math.max(330, (innerAvailWidth - gap) / 2);
            this.rightXRel = leftW + gap;
            final int rightW = innerAvailWidth - this.rightXRel;
            this.tileW = Math.max(160, (rightW - colGap) / 2);
            for (final ActionTile tile: this.actionElements) {
                tile.size(leftW, 56);
                tile.measure(leftW, 56);
            }
            for (final MediaTile tile: this.mediaElements) {
                tile.size(this.tileW, TILE_H);
                tile.measure(this.tileW, TILE_H);
            }
            for (final EntertainmentTile tile: this.entElements) {
                tile.size(rightW, 72);
                tile.measure(rightW, 72);
            }
            if (this.tip != null && this.tip.visible()) this.tip.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int colGap = 10;
            final int x = this.innerLeft();
            final int y = this.innerTop();
            final int contentH = this.innerHeight();
            int rowY = y + 36;
            for (final ActionTile tile: this.actionElements) {
                tile.layout(x, rowY);
                rowY += 64;
            }
            this.rightXAbs = x + this.rightXRel;
            int fit = this.mediaElements.size();
            for (int i = 0; i < this.mediaElements.size(); i++) {
                final int col = i % 2;
                final int row = i / 2;
                final int tx = this.rightXAbs + col * (this.tileW + colGap);
                final int ty = y + 36 + row * (TILE_H + 10);
                // TILES THAT OVERFLOW THE PANEL ARE HIDDEN: NEITHER DRAWN NOR HIT-TESTED
                if (ty + TILE_H > y + contentH) {
                    fit = i;
                    break;
                }
                this.mediaElements.get(i).visible(true).layout(tx, ty);
            }
            for (int i = fit; i < this.mediaElements.size(); i++) this.mediaElements.get(i).visible(false);
            HomeScreen.this.visibleMediaCount = fit;

            this.entHeadVisible = false;
            if (!this.entElements.isEmpty()) {
                final int mediaRows = Math.max(1, (this.mediaElements.size() + 1) / 2);
                this.entYAbs = y + 36 + mediaRows * (TILE_H + 10) + 24;
                if (this.entYAbs + 112 <= y + contentH) {
                    this.entHeadVisible = true;
                    int entRowY = this.entYAbs + 36;
                    for (final EntertainmentTile tile: this.entElements) {
                        tile.visible(true).layout(this.rightXAbs, entRowY);
                        entRowY += 82;
                    }
                } else {
                    for (final EntertainmentTile tile: this.entElements) tile.visible(false);
                }
            }

            // THE TOOLTIP HANGS 8px UNDER ITS ANCHOR TILE (THE ELEMENT BOX INCLUDES THE 10px NOTCH ON TOP)
            if (this.tip != null && this.tip.visible()) {
                final int idx = HomeScreen.this.actionIndex(Action.UPLOAD_LOGS);
                if (idx >= 0 && idx < this.actionElements.size()) {
                    final ActionTile anchor = this.actionElements.get(idx);
                    this.tip.layout(anchor.left(), anchor.top() + anchor.measuredHeight() + 8 - UploadTip.NOTCH_H);
                }
            }
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // EACH HEAD IS CUT TO ITS OWN COLUMN; THE 18 px GAP SEPARATES THEM
            final int rightW = this.innerWidth() - this.rightXRel;
            SectionHead.draw(canvas, "ACTIONS", HomeScreen.this.actions.size() + " AVAILABLE", this.innerLeft(), this.innerTop(), this.rightXRel - 18);
            SectionHead.draw(canvas, "MEDIA TESTS", HomeScreen.this.mediaTests.size() + " CATEGORIES", this.rightXAbs, this.innerTop(), rightW);
            if (this.entHeadVisible) SectionHead.draw(canvas, "ENTERTAINMENT", HomeScreen.this.entertainment.size() + " AVAILABLE", this.rightXAbs, this.entYAbs, rightW);
            super.onDraw(canvas); // TILES, THEN THE TOOLTIP ON TOP
        }
    }

    // ONE ACTION ROW: HOVER SELECTS IT AND CHIMES WHEN THE SELECTION MOVES. DISABLED ACTIONS STAY HOVERABLE
    // AND CLICKABLE; handleSelect IGNORES THEM
    private final class ActionTile extends Element<ActionTile> {
        private final int index;

        private ActionTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (HomeScreen.this.selectedPanel != 0 || HomeScreen.this.selectedAction != this.index) {
                    HomeScreen.this.selectedPanel = 0;
                    HomeScreen.this.selectedAction = this.index;
                    HomeScreen.this.ctx.sound.play();
                }
            });
            this.onClick(v -> HomeScreen.this.handleSelect(HomeScreen.this.actions.get(this.index)));
        }

        private Color accentColor(final MenuEntry entry, final boolean enabled) {
            return !enabled ? AppTheme.TEXT_FAINT : switch (entry.action()) {
                case OPEN_MULTIMEDIA -> AppTheme.GREEN;
                case EXIT -> AppTheme.RED;
                default -> AppTheme.NEON_LIGHT;
            };
        }

        @Override
        protected void onUpdate() {
            if (this.index >= HomeScreen.this.actions.size()) return;
            final MenuEntry entry = HomeScreen.this.actions.get(this.index);
            final boolean selected = HomeScreen.this.selectedPanel == 0 && HomeScreen.this.selectedAction == this.index;
            final boolean enabled = HomeScreen.this.actionEnabled(entry);
            final Color accent = this.accentColor(entry, enabled);
            this.background(!enabled
                    ? TILE_BG_DISABLED
                    : entry.action() == Action.EXIT
                    ? (selected ? TILE_BG_EXIT_SEL : TILE_BG_EXIT)
                    : selected ? TILE_BG_SEL : AppTheme.alpha(AppTheme.BG_2, 235));
            this.border(!enabled
                    ? AppTheme.STROKE
                    : selected || entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                    ? accent
                    : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(enabled ? selected ? accent : AppTheme.NEON : AppTheme.STROKE_BRIGHT,
                    enabled ? selected ? 0.28f : 0.08f : 0.03f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = HomeScreen.this.actions.get(this.index);
            final boolean selected = HomeScreen.this.selectedPanel == 0 && HomeScreen.this.selectedAction == this.index;
            final boolean enabled = HomeScreen.this.actionEnabled(entry);
            final Color accent = this.accentColor(entry, enabled);
            final Color textColor = !enabled
                    ? AppTheme.TEXT_FAINT
                    : entry.action() == Action.OPEN_MULTIMEDIA || entry.action() == Action.EXIT
                    ? accent
                    : selected ? accent : AppTheme.TEXT;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            // A SELECTED TILE STACKS THIS FLARE ON TOP OF ITS DECOR GLOW
            if (selected && enabled) canvas.glow(x, y, w, h, 0f, accent, 0.35f);
            final String icon = switch (entry.action()) {
                case OPEN_MULTIMEDIA -> "play";
                case UPLOAD_LOGS -> "upload";
                case CLEANUP -> "broom";
                case SETTINGS -> "settings";
                case EXIT -> "x";
                default -> "info";
            };
            PixelIcon.draw(icon, x + 14, y + 17, 18, accent);
            canvas.text(entry.label().toUpperCase(Locale.ROOT), x + 48,
                    y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BUTTON, true)) / 2),
                    textColor, AppTheme.TEXT_BUTTON, true);
            final int hintW = canvas.textWidth(entry.meta(), AppTheme.TEXT_BODY, false) + 14;
            canvas.fill(x + w - hintW - 12, y + 17, hintW, 22, AppTheme.alpha(AppTheme.BG_1, 180));
            canvas.stroke(x + w - hintW - 12, y + 17, hintW, 22, AppTheme.STROKE, 1f);
            canvas.text(entry.meta(), x + w - hintW - 5,
                    y + 17 + Math.max(0, (22 - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
        }
    }

    // ONE MEDIA-TEST TILE: DECOR FROM LIVE STATE, FOLDER ICON AND TITLES THROUGH THE CANVAS
    private final class MediaTile extends Element<MediaTile> {
        private final int index;

        private MediaTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (HomeScreen.this.selectedPanel != 1 || HomeScreen.this.selectedMedia != this.index) {
                    HomeScreen.this.selectedPanel = 1;
                    HomeScreen.this.selectedMedia = this.index;
                    HomeScreen.this.ctx.sound.play();
                }
            });
            this.onClick(v -> HomeScreen.this.handleSelect(HomeScreen.this.mediaTests.get(this.index)));
        }

        @Override
        protected void onUpdate() {
            final boolean selected = HomeScreen.this.selectedPanel == 1 && HomeScreen.this.selectedMedia == this.index;
            this.background(selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            this.border(selected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(selected ? AppTheme.NEON : null, selected ? 0.28f : 0f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = HomeScreen.this.mediaTests.get(this.index);
            final Color folderColor = CATEGORY_PALETTE[Math.floorMod(entry.groupIndex(), CATEGORY_PALETTE.length)];
            final int x = this.left;
            final int y = this.top;
            PixelIcon.draw("folder", x + 14, y + 15, 18, folderColor);
            final String title = canvas.text().truncateToWidth(entry.label().toUpperCase(Locale.ROOT),
                    this.measuredWidth - 62, AppTheme.TEXT_BUTTON, Font.BOLD);
            canvas.text(title, x + 42,
                    y + 15 + Math.max(0, (18 - canvas.textHeight(AppTheme.TEXT_BUTTON, true)) / 2),
                    folderColor, AppTheme.TEXT_BUTTON, true);
            canvas.text((entry.meta() + " - click to load").toUpperCase(Locale.ROOT), x + 14, y + 50,
                    AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
        }
    }

    // ONE ENTERTAINMENT ROW: DECOR FROM LIVE STATE, CENTERED TV ICON + LABEL THROUGH THE CANVAS
    private final class EntertainmentTile extends Element<EntertainmentTile> {
        private final int index;

        private EntertainmentTile(final int index) {
            this.index = index;
            this.onHover(v -> {
                if (HomeScreen.this.selectedPanel != 2 || HomeScreen.this.selectedEntertainment != this.index) {
                    HomeScreen.this.selectedPanel = 2;
                    HomeScreen.this.selectedEntertainment = this.index;
                    HomeScreen.this.ctx.sound.play();
                }
            });
            this.onClick(v -> HomeScreen.this.handleSelect(HomeScreen.this.entertainment.get(this.index)));
        }

        @Override
        protected void onUpdate() {
            final boolean selected = HomeScreen.this.selectedPanel == 2 && HomeScreen.this.selectedEntertainment == this.index;
            this.background(selected ? AppTheme.alpha(AppTheme.NEON_DARK, 78) : AppTheme.alpha(AppTheme.BG_2, 220));
            this.border(selected ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 2f);
            this.glow(selected ? AppTheme.GREEN : null, selected ? 0.30f : 0f);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MenuEntry entry = HomeScreen.this.entertainment.get(this.index);
            final boolean selected = HomeScreen.this.selectedPanel == 2 && HomeScreen.this.selectedEntertainment == this.index;
            final Color accent = selected ? AppTheme.GREEN : AppTheme.NEON_LIGHT;
            final String label = entry.label().toUpperCase(Locale.ROOT);
            final int iconSize = 34;
            final int gap = 18;
            final int labelW = canvas.textWidth(label, AppTheme.TEXT_DISPLAY, true);
            final int groupW = iconSize + gap + labelW;
            final int groupX = this.left + Math.max(0, (this.measuredWidth - groupW) / 2);
            PixelIcon.draw("tv", groupX, this.top + Math.max(0, (this.measuredHeight - iconSize) / 2), iconSize, accent);
            canvas.text(label, groupX + iconSize + gap,
                    this.top + Math.max(0, (this.measuredHeight - canvas.textHeight(AppTheme.TEXT_DISPLAY, true)) / 2),
                    accent, AppTheme.TEXT_DISPLAY, true);
        }
    }

    // UPLOAD-LOGS TOOLTIP, SHOWN WHILE ITS TILE IS SELECTED AND THE DIALOG IS CLOSED; IT IGNORES INPUT SO
    // THE TILES UNDER IT KEEP THEIR HOVER AND CLICKS
    private final class UploadTip extends Element<UploadTip> {
        private static final int NOTCH_H = 10;
        private static final boolean BLOCKED = !LogUploader.AVAILABLE;
        private static final String TITLE = BLOCKED ? "NO MINECRAFT INSTANCE" : "FOR A BUG REPORT";
        // THE BOX IS AS WIDE AND AS TALL AS ITS TEXT: THE LOCKED TIP SAYS ALL IT HAS TO IN ONE LINE
        private static final String[] LINES = BLOCKED
                ? new String[] { "Run the app from its mods folder." }
                : new String[] { "Uploads logs, crash reports and configs,", "then opens the issue page." };
        private static final int BOX_H = 42 + 20 * LINES.length;

        @Override
        protected void onUpdate() {
            final int idx = HomeScreen.this.actionIndex(Action.UPLOAD_LOGS);
            this.visible = idx >= 0 && HomeScreen.this.selectedPanel == 0 && HomeScreen.this.selectedAction == idx && !HomeScreen.this.ctx.upload.visible;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final TextRenderer text = HomeScreen.this.ctx.text;
            int desiredW = text.widthBold(TITLE, AppTheme.TEXT_BUTTON);
            for (final String line: LINES) desiredW = Math.max(desiredW, text.width(line, AppTheme.TEXT_BODY));
            this.contentWidth = Math.min(desiredW + 24, Math.max(120, innerAvailWidth - 24));
            this.contentHeight = BOX_H + NOTCH_H;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            return false; // INPUT-TRANSPARENT
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            return false; // INPUT-TRANSPARENT
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Color color = BLOCKED ? AppTheme.RED : AppTheme.AMBER;
            final int x = this.left;
            final int y = this.top + NOTCH_H;
            final int w = this.measuredWidth;
            canvas.fill(x, y, w, BOX_H, AppTheme.alpha(AppTheme.BG_1, 242));
            canvas.stroke(x, y, w, BOX_H, color, 1f);
            canvas.glow(x, y, w, BOX_H, 0f, color, 0.24f);
            // POINTER NOTCH DRAWN AS STACKED 2px STEPS
            for (int k = 0; k < NOTCH_H / 2; k++) {
                final int half = Math.round(7f * (NOTCH_H - (2 * k + 1)) / NOTCH_H);
                if (half <= 0) continue;
                canvas.fill(x + 23 - half, y - 2 * (k + 1), half * 2, 2, color);
            }
            canvas.text(TITLE, x + 12, y + 12, color, AppTheme.TEXT_BUTTON, true);
            for (int i = 0; i < LINES.length; i++) canvas.text(LINES[i], x + 12, y + 36 + 20 * i, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }

    // ==========================================================================
    // NESTED TYPES: DIALOG BUILDING BLOCKS
    // ==========================================================================

    // ONE FILE OR SUMMARY ROW: STATUS PIP, NAME (WITH A PROGRESS BAR UNDER IT WHILE IT WORKS), SIZE AND STATE BADGE; A
    // ListRow WHOSE ONLY ADDITION IS THIS TWO-LINE LAYOUT OF LIVE ELEMENTS, BOUND EVERY FRAME BY ITS BINDER
    private static final class FileRow extends ListRow {
        private static final int BAR_H = 6;
        private static final int BAR_GAP = 10;

        private final StatusSquare pip = new StatusSquare();
        private final Text name = new Text();
        private final ProgressBar bar = new ProgressBar().accent(AppTheme.NEON_LIGHT);
        private final Text size = new Text().color(AppTheme.TEXT_FAINT).scale(AppTheme.TEXT_SUBTITLE);
        private final Badge tag = new Badge();
        private boolean twoLine;

        private FileRow() {
            this.pip.size(10, 10);
            this.bar.visible(false);
            this.separatorFrom(28).separator(false);
            this.add(this.pip).add(this.name).add(this.bar).add(this.size).add(this.tag);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.pip.measure(10, 10);
            final int nameW = Math.max(40, innerAvailWidth - 28 - 190);
            this.name.width(nameW).measure(nameW, 20);
            final int barW = Math.max(120, innerAvailWidth - 124);
            this.bar.width(barW).measure(barW, BAR_H);
            this.size.width(140).measure(140, 16);
            this.tag.measure(200, 26);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int x = this.innerLeft();
            final int y = this.innerTop();
            final int w = this.innerWidth();
            final int h = this.innerHeight();
            this.pip.layout(x + 4, y + Math.max(0, (h - 10) / 2));
            // TWO-LINE MODE STACKS THE NAME OVER THE PROGRESS BAR AND CENTERS THAT BLOCK, SO IT SHARES ONE AXIS WITH THE PIP
            // AND THE BADGE
            final int nameH = this.name.measuredHeight();
            final int nameY = y + Math.max(0, (h - (this.twoLine ? nameH + BAR_GAP + BAR_H : nameH)) / 2);
            this.name.layout(x + 28, nameY);
            this.bar.layout(x + 28, nameY + nameH + BAR_GAP);
            final int sizeY = this.twoLine ? nameY : y + Math.max(0, (h - this.size.measuredHeight()) / 2);
            this.size.layout(x + w - 180, sizeY);
            this.tag.layout(x + w - this.tag.measuredWidth(), y + Math.max(0, (h - this.tag.measuredHeight()) / 2));
        }
    }

    // STAGE 3 PANE: THE TITLE AND DESCRIPTION BOXES UNDER THEIR LIVE COUNTERS, THEN THE STATUS STRIP. IT BINDS ITSELF TO
    // ctx.upload EVERY FRAME AND OWNS THE FOCUS AND EVERY ENTER OF THE STAGE, SO THE DIALOG NEVER SUBMITS ON A PLAIN ONE
    private final class ReportPane extends Parent {
        private static final int HEAD_H = 20;
        private static final int HEAD_GAP = 6;
        private static final int STRIP_H = 30;
        private static final int BOX_PAD = 8;
        private static final int MAX_LINES = 8;
        private static final int MIN_LINES = 2;
        // HEIGHT OF EVERYTHING BUT THE TEXT LINES: BOTH HEADS, THE STRIP, THE FOUR GAPS, THE EXTRA GAP OVER THE SECOND
        // HEAD AND THE PADDING OF BOTH BOXES
        private static final int FIXED_H = HEAD_H * 2 + STRIP_H + Theme.SPACE_SM * 4 + HEAD_GAP + BOX_PAD * 4;

        private final Text titleLabel = new Text("TITLE").bold(true).scale(AppTheme.TEXT_BUTTON).height(HEAD_H);
        private final Text titleCount = new Text().scale(AppTheme.TEXT_SUBTITLE).gravity(Gravity.RIGHT).weight(1f).height(HEAD_H);
        private final TextArea title = new TextArea().singleLine(true).maxLength(LogUploader.TITLE_LIMIT_CHARS)
                .placeholder("Short summary").padding(Spacing.hv(10, BOX_PAD));
        private final Text descriptionLabel = new Text("DESCRIPTION").bold(true).scale(AppTheme.TEXT_BUTTON).height(HEAD_H);
        private final Text descriptionCount = new Text().scale(AppTheme.TEXT_SUBTITLE).gravity(Gravity.RIGHT).weight(1f).height(HEAD_H);
        private final TextArea description = new TextArea().maxLength(LogUploader.DESCRIPTION_LIMIT_CHARS)
                .placeholder("What happened and how to repeat it").padding(Spacing.hv(10, BOX_PAD));
        // THE PANE SHOWED ON THE LAST FRAME; ITS EDGES GIVE AND TAKE THE FOCUS
        private boolean shown;
        // WHAT THE PRESS OF THE LAST ENTER MADE OF THE WHOLE KEYSTROKE, AS CTRL MAY LIFT AND THE FOCUS MOVE BEFORE ITS
        // REPEATS AND RELEASE: A SUBMIT (WITH CTRL), OR A LINE BREAK OF THE DESCRIPTION THAT HELD THE FOCUS
        private boolean submitting;
        private boolean breaking;

        private ReportPane() {
            super(Orientation.VERTICAL);
            this.spacing(Theme.SPACE_SM).width(MAX_PARENT);
            this.title.onChange(value -> {
                HomeScreen.this.ctx.upload.title = value;
                count(this.titleCount, value.length(), LogUploader.TITLE_LIMIT_CHARS);
            });
            this.description.onChange(value -> {
                HomeScreen.this.ctx.upload.description = value;
                count(this.descriptionCount, value.length(), LogUploader.DESCRIPTION_LIMIT_CHARS);
            });
            count(this.titleCount, 0, LogUploader.TITLE_LIMIT_CHARS);
            count(this.descriptionCount, 0, LogUploader.DESCRIPTION_LIMIT_CHARS);
            this.add(Parent.row().width(MAX_PARENT).add(this.titleLabel).add(this.titleCount))
                    .add(this.title)
                    .add(Parent.row().width(MAX_PARENT).margin(new Spacing(HEAD_GAP, 0, 0, 0))
                            .add(this.descriptionLabel).add(this.descriptionCount))
                    .add(this.description)
                    .add(new ReportStrip().size(MAX_PARENT, STRIP_H));
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (!this.shown) return false;
            final boolean enter = key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER;
            if (enter && action == GLFW_PRESS) {
                this.submitting = HomeScreen.this.ctx.ctrlDown && !HomeScreen.this.ctx.altDown;
                this.breaking = !this.submitting && this.description.focused();
            }
            // THE FOCUSED BOX GOES FIRST, BUT AN ENTER ONLY WHEN IT BREAKS A DESCRIPTION LINE: THE REPEATS OF ONE THAT
            // MOVED THE FOCUS MUST NOT BREAK LINES IN THE BOX IT JUST FOCUSED
            if ((!enter || this.breaking) && super.dispatchKey(key, action)) return true;
            if (enter) {
                // NO ENTER REACHES THE DIALOG, WHICH WOULD OPEN THE ISSUE ON A PLAIN ONE: ONLY CTRL+ENTER DOES, ON ITS RELEASE
                if (this.submitting && action == GLFW_RELEASE) HomeScreen.this.uploadPrimaryAction();
                if (this.submitting || this.breaking || action != GLFW_PRESS) return true;
            } else if (key != GLFW_KEY_TAB || action != GLFW_PRESS) {
                return key == GLFW_KEY_TAB;
            }
            // A PLAIN ENTER OUTSIDE THE DESCRIPTION AND TAB MOVE THE FOCUS ON THEIR PRESS: FROM THE TITLE TO THE
            // DESCRIPTION, ELSE TO THE TITLE
            final boolean toTitle = !this.title.focused();
            this.title.focused(toTitle);
            this.description.focused(!toTitle);
            this.invalidate();
            return true;
        }

        @Override
        protected void onUpdate() {
            final LogUploader up = HomeScreen.this.ctx.upload;
            // KEYS REACH HIDDEN CHILDREN TOO, SO THE BOXES HOLD THE FOCUS ONLY WHILE THE PANE SHOWS, STARTING AT THE TITLE
            final boolean showing = up.visible && this.visible;
            if (showing != this.shown) {
                this.shown = showing;
                this.clearFocus();
                this.title.focused(showing);
            }
            if (!showing) return;

            // THE UPLOADER OWNS BOTH TEXTS: A NEW SESSION AFTER AN OPENED ISSUE CLEARS THEM, AND THE BOXES FOLLOW
            final String title = up.title;
            final String description = up.description;
            if (!title.equals(this.title.value())) {
                this.title.value(title);
                count(this.titleCount, title.length(), LogUploader.TITLE_LIMIT_CHARS);
            }
            if (!description.equals(this.description.value())) {
                this.description.value(description);
                count(this.descriptionCount, description.length(), LogUploader.DESCRIPTION_LIMIT_CHARS);
            }

            final Color accent = up.done ? AppTheme.GREEN : AppTheme.CYAN;
            this.titleLabel.color(accent);
            this.descriptionLabel.color(accent);
            this.title.accent(accent);
            this.description.accent(accent);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // THE TITLE BOX IS ONE LINE HIGH; THE DESCRIPTION HAS 8 LINES AND GIVES THEM UP, DOWN TO 2, WHEN THE DIALOG
            // OFFERS LESS HEIGHT
            final TextRenderer text = HomeScreen.this.ctx.text;
            final int line = text == null ? 0 : text.lineHeight(AppTheme.TEXT_BODY);
            final int lines = line == 0 ? MIN_LINES : MathUtil.clamp((innerAvailHeight - FIXED_H - line) / line, MIN_LINES, MAX_LINES);
            this.title.height(line + BOX_PAD * 2);
            this.description.height(lines * line + BOX_PAD * 2);
            super.onMeasure(innerAvailWidth, innerAvailHeight);
        }
    }

    // STAGE 3 STATUS STRIP: ONE SHORT LINE WITH ITS ICON AND TINT, RESOLVED PER DRAW FROM THE UPLOADER FLAGS
    private final class ReportStrip extends Element<ReportStrip> {
        @Override
        protected void onDraw(final Canvas canvas) {
            final LogUploader up = HomeScreen.this.ctx.upload;
            final ReportStatus status = ReportStatus.of(up.working, up.ready(), !up.title.isBlank(), up.issueOpened, up.issueCopied,
                    up.browserFailed, up.error);
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, status.tint == null ? AppTheme.alpha(AppTheme.BG_2, 164) : AppTheme.alpha(status.tint, 26));
            canvas.stroke(x, y, w, h, status.tint == null ? AppTheme.STROKE_BRIGHT : status.tint, 1f);
            canvas.icon(status.icon, x + 12, y + (h - 14) / 2, 14, status.tint == null ? AppTheme.TEXT_FAINT : status.tint);
            canvas.text(canvas.text().truncateToWidth(status.line, w - 44, AppTheme.TEXT_BODY), x + 34,
                    y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    status.tint == null ? AppTheme.TEXT_SOFT : AppTheme.TEXT, AppTheme.TEXT_BODY, false);
        }
    }

    // WHAT THE STAGE 3 STRIP SHOWS: ITS LINE, ITS ICON AND ITS TINT (null FOR THE MUTED LOOK). AFTER A FAILED SUBMIT IT
    // TELLS WHAT FAILED AND WHETHER THE REPORT AT LEAST REACHED THE CLIPBOARD, FROM WHERE IT CAN BE PASTED BY HAND
    enum ReportStatus {
        PREPARING("Preparing report...", "info", null),
        REPORT_FAILED("Report failed. Retry", "warn", AppTheme.RED),
        OPENING("Opening...", "info", null),
        BROWSER_FAILED("Browser failed. Report copied", "warn", AppTheme.RED),
        ALL_FAILED("Clipboard and browser failed", "warn", AppTheme.RED),
        CLIPBOARD_FAILED("Clipboard failed. Retry", "warn", AppTheme.RED),
        UNTITLED("Title required", "info", null),
        OPENED("Opened. Report copied", "check", AppTheme.GREEN),
        OPENED_UNCOPIED("Opened", "check", AppTheme.GREEN),
        READY("Ready", "info", null);

        private final String line;
        private final String icon;
        private final Color tint;

        ReportStatus(final String line, final String icon, final Color tint) {
            this.line = line;
            this.icon = icon;
            this.tint = tint;
        }

        // THE FIRST THAT APPLIES, IN THE ORDER OF THE CONSTANTS: THE REPORT WORKER OR ITS FAILURE, THE SUBMIT WORKER OR
        // ITS FAILURE (THE CLIPBOARD ALONE FAILS BEFORE THE BROWSER IS TRIED), WHAT THE SUBMIT STILL NEEDS, THEN ITS RESULT
        static ReportStatus of(final boolean working, final boolean ready, final boolean titled, final boolean opened,
                               final boolean copied, final boolean browserFailed, final boolean error) {
            if (!ready) return working || !error ? PREPARING : REPORT_FAILED;
            if (working) return OPENING;
            if (error && browserFailed) return copied ? BROWSER_FAILED : ALL_FAILED;
            if (error) return CLIPBOARD_FAILED;
            if (!titled) return UNTITLED;
            if (opened) return copied ? OPENED : OPENED_UNCOPIED;
            return READY;
        }
    }
}
