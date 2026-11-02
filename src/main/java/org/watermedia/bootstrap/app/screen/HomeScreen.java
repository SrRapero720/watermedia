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
public class HomeScreen extends Screen {
    private static final Marker IT = MarkerManager.getMarker(HomeScreen.class.getSimpleName());

    private static final int UPLOAD_ROW_H = 52;
    private static final int UPLOAD_ROW_DETAIL_H = 58;
    private static final int UPLOAD_PANEL_PAD = 14;
    private static final int CLEANUP_ROW_H = 58;
    private static final int REPO_ROW_H = Theme.ROW_MD;
    // VIEWPORT OF THREE MOD CARDS AND THEIR TWO 8px GAPS
    private static final int REPO_LIST_H = REPO_ROW_H * 3 + 8 * 2;

    // ACTION TILE FILLS
    private static final Color TILE_BG_DISABLED = new Color(15, 20, 36, 148);
    private static final Color TILE_BG_EXIT = new Color(51, 10, 20, 235);
    private static final Color TILE_BG_EXIT_SEL = new Color(66, 18, 28, 235);
    private static final Color TILE_BG_SEL = new Color(AppTheme.BG_2.getRed(), 34, 66, 235);

    // ACCENT PALETTES, STATIC SO THE PER-TILE AND PER-CARD DRAWS NEVER ALLOCATE
    private static final Color[] CATEGORY_PALETTE = {AppTheme.NEON, AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT};
    private static final Color[] REPO_PALETTE = {AppTheme.AMBER, AppTheme.CYAN, AppTheme.NEON_LIGHT, AppTheme.NEON};

    // FOOTER KEYBINDS: CONSTANTS SO THE PER-FRAME keybinds() POLL OF KeybindsBar ALLOCATES NOTHING
    private static final List<Keybind> KEYS_UPLOAD_REPORT = List.of(
            new Keybind("UP/DOWN", "Repository"), new Keybind("ENTER", "Submit"), new Keybind("ESC", "Close"));
    private static final List<Keybind> KEYS_UPLOAD = List.of(
            new Keybind("ENTER", "Continue"), new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_CLEANUP = List.of(
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
    private ListView<RepoTarget> repoList;
    private Text noMods;
    private Button uploadCancel;
    private Button uploadPrimary;
    private Button cleanupCancel;
    private Button cleanupPrimary;
    private List<LogUploader.FileEntry> uploadRows = List.of();
    private List<RepoTarget> repoTargets = List.of();
    private int repoSelected;

    // CACHE-SIZE LABEL FOR THE CLEANUP ENTRY, COMPUTED OFF THE RENDER THREAD (Files.walk BLOCKS ON LARGE
    // CACHES); rebuildMenu() READS THE LAST PUBLISHED VALUE AND cacheWalking COALESCES OVERLAPPING WALKS
    private volatile String cacheLabel = "--";
    private volatile boolean cacheWalking;
    private volatile boolean cacheWarned;

    public HomeScreen(final TextRenderer text, final AppContext ctx, final Consumer<Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

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
        if (this.ctx.upload.visible) return this.ctx.upload.stage >= 3 ? KEYS_UPLOAD_REPORT : KEYS_UPLOAD;
        if (this.ctx.cleanup.visible) return KEYS_CLEANUP;
        return KEYS_MENU;
    }

    @Override
    public boolean continuous() {
        return this.ctx.backendsLoading || this.ctx.upload.working || this.ctx.cleanup.working;
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
                .title(up.done ? "SUCCESS" : "UPLOAD LOG FILES")
                .accent(up.done ? AppTheme.GREEN : AppTheme.CYAN)
                .panelWidth(Math.min(report ? 900 : 890, this.ctx.logicalWidth() - 48));
        this.filesPanel.visible(!report);
        this.reportPane.visible(report);

        if (up.visible && !report) {
            final int rowH = up.stage >= 2 ? UPLOAD_ROW_DETAIL_H : UPLOAD_ROW_H;
            this.fileList.rowHeight(rowH);
            final List<LogUploader.FileEntry> rows = this.visibleUploadFiles();
            // ROWS ARE REBUILT ONLY WHEN MEMBERSHIP CHANGES; ROW CONTENT BINDS ITSELF EVERY FRAME
            if (!rows.equals(this.uploadRows)) {
                this.uploadRows = rows;
                this.fileList.items(rows);
            }
            // AN EMPTY SCAN KEEPS ONE BLANK ROW OF PANEL HEIGHT
            this.fileList.height(rows.isEmpty() ? rowH : WRAP_CONTENT);
        }

        if (up.visible && report) {
            final List<RepoTarget> targets = this.uploadRepoTargets();
            if (!targets.equals(this.repoTargets)) {
                this.repoTargets = targets;
                this.repoList.items(targets.subList(1, targets.size()));
            }
            final boolean mods = targets.size() > 1;
            this.repoList.visible(mods);
            this.noMods.visible(!mods);
        } else if (!this.repoTargets.isEmpty()) {
            // OUTSIDE THE REPORT STAGE THE REPO PICKER STATE IS DORMANT, RESET SO IT STARTS FRESH
            this.repoTargets = List.of();
            this.repoSelected = 0;
            this.repoList.items(List.of()).selection(-1);
        }

        this.uploadCancel.label(up.done ? "CLOSE" : "CANCEL");
        final boolean upEnabled = this.uploadPrimaryEnabled();
        final String upLabel = up.stage <= 1 ? "UPLOAD TO MCLO.GS" : up.stage == 2 ? "GENERATE REPORT"
                : "OPEN " + this.selectedRepo().name().toUpperCase(Locale.ROOT);
        final Color upColor = !upEnabled ? AppTheme.TEXT_FAINT : up.done || up.uploadsDone ? AppTheme.GREEN : AppTheme.CYAN;
        this.uploadPrimary.label(upLabel)
                .icon(upLabel.startsWith("UPLOAD") ? "upload" : upLabel.startsWith("OPEN") ? "link" : upLabel.startsWith("GENERATE") ? "check" : "")
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

        // STAGE 3 REPORT PANE: CLIPBOARD STRIP, WATERMEDIA HERO, SUSPECTED-MOD LIST WITH SUBMIT BUTTONS
        this.repoList = new ListView<RepoTarget>()
                .rowFactory((repo, i) -> new RepoCard(repo, i + 1))
                .rowHeight(REPO_ROW_H)
                .spacing(8)
                .selectionColor(null)
                .hoverColor(null)
                .scrollbarWidth(4);
        this.repoList.size(MAX_PARENT, REPO_LIST_H).margin(new Spacing(6, 0, 0, 0));
        this.noMods = new Text("No known suspect mods detected in this instance; submit to WaterMedia above.")
                .color(AppTheme.TEXT_FAINT);
        this.noMods.width(MAX_PARENT).margin(new Spacing(10, 0, 0, 0)).visible(false);
        this.reportPane = new ReportPane();
        this.reportPane.width(MAX_PARENT);
        this.reportPane
                .add(new CopyStrip().size(MAX_PARENT, 30))
                .add(new RepoHero().size(MAX_PARENT, 104).margin(new Spacing(14, 0, 0, 0)))
                .add(Parent.row().spacing(14)
                        .add(new Text("SUSPECTED MODS").bold(true).scale(AppTheme.TEXT_BUTTON).color(AppTheme.NEON))
                        .add(new Text("SUBMIT TO THE MATCHING REPOSITORY").scale(AppTheme.TEXT_SUBTITLE)
                                .color(AppTheme.TEXT_FAINT).gravity(Gravity.CENTER))
                        .margin(new Spacing(16, 0, 0, 0)))
                .add(this.repoList)
                .add(this.noMods);

        this.uploadCancel = new Button("CANCEL").subText("ESC")
                .accent(AppTheme.STROKE_BRIGHT).textColor(AppTheme.TEXT);
        this.uploadCancel.size(170, Theme.BUTTON_LG).onClick(b -> this.closeUpload());
        this.uploadPrimary = new Button().subText("ENTER");
        this.uploadPrimary.height(Theme.BUTTON_LG).onClick(b -> this.uploadPrimaryAction());

        return new Dialog()
                .title("UPLOAD LOG FILES")
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
            row.url.visible(false);
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
        this.actions.add(new MenuEntry("Upload Logs", LogUploader.WORKDIR_IS_MODS ? "U" : "LOCKED", Action.UPLOAD_LOGS, -1));
        this.actions.add(new MenuEntry("Cleanup cache", this.cacheLabel, Action.CLEANUP, -1));
        if (WaterMedia.LOGGER.isDebugEnabled()) this.actions.add(new MenuEntry("Settings", "S", Action.SETTINGS, -1));
        this.actions.add(new MenuEntry("Exit", "ESC", Action.EXIT, -1));

        if (this.ctx.catalog.uriGroups != null) {
            for (int i = 0; i < this.ctx.catalog.uriGroups.length; i++) {
                final MediaCatalog.URIGroup group = this.ctx.catalog.uriGroups[i];
                this.mediaTests.add(new MenuEntry(group.name(), group.uris().length + " samples", null, i));
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
        if (entry.action() == Action.UPLOAD_LOGS) return LogUploader.WORKDIR_IS_MODS;
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
        if (this.ctx.upload.stage >= 3) {
            this.submitRepo(this.selectedRepo(), this.repoSelected);
            return;
        }
        if (!this.uploadPrimaryEnabled()) return;
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.sound.play();
    }

    private boolean uploadPrimaryEnabled() {
        if (this.ctx.upload.working) return false;
        // RUNS ON EVERY UPDATE PASS, SO PLAIN LOOPS INSTEAD OF STREAMS
        if (this.ctx.upload.stage > 2) return true;
        final boolean scan = this.ctx.upload.stage <= 1;
        for (final LogUploader.FileEntry entry: this.ctx.upload.files)
            if (scan ? entry.present : entry.uploaded) return true;
        return false;
    }

    private List<LogUploader.FileEntry> visibleUploadFiles() {
        // SCAN SHOWS EVERY FILE, UPLOAD THE CANDIDATES AND THE FAILURES, THE REPORT ONLY THE UPLOADED ONES
        final List<LogUploader.FileEntry> files = new ArrayList<>();
        for (final LogUploader.FileEntry entry: this.ctx.upload.files) {
            if (this.ctx.upload.stage <= 1) {
                files.add(entry);
            } else if (this.ctx.upload.stage == 2) {
                if ((entry.present && entry.valid) || "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) files.add(entry);
            } else if (entry.uploaded) {
                files.add(entry);
            }
        }
        return files;
    }

    private List<RepoTarget> uploadRepoTargets() {
        final String wmUrl = this.ctx.upload.issueUrl != null ? this.ctx.upload.issueUrl : "https://github.com/" + LogUploader.REPO + "/issues/new";
        final List<RepoTarget> repos = new ArrayList<>();
        repos.add(new RepoTarget("WaterMedia", LogUploader.REPO, wmUrl, AppTheme.GREEN));
        // ONLY THE SUSPECT MODS WHOSE JAR THE UPLOAD SCAN FOUND; THE ACCENT FOLLOWS THE CATALOG POSITION
        int idx = 0;
        for (final LogUploader.SuspectMod mod: LogUploader.SUSPECT_MODS) {
            if (this.ctx.upload.suspectModIds.contains(mod.id()))
                repos.add(new RepoTarget(mod.name(), mod.slug(), mod.url(), REPO_PALETTE[idx % REPO_PALETTE.length]));
            idx++;
        }
        return repos;
    }

    private RepoTarget selectedRepo() {
        final List<RepoTarget> repos = this.repoTargets.isEmpty() ? this.uploadRepoTargets() : this.repoTargets;
        return repos.get(MathUtil.clamp(this.repoSelected, 0, repos.size() - 1));
    }

    private void submitRepo(final RepoTarget repo, final int index) {
        this.repoSelected = index;
        this.ctx.upload.repoUrl = repo.url();
        this.navigator.accept(Action.UPLOAD_LOGS);
        this.ctx.sound.play();
    }

    private void moveRepoSelection(final int delta) {
        final List<RepoTarget> targets = this.repoTargets.isEmpty() ? this.uploadRepoTargets() : this.repoTargets;
        this.repoSelected = MathUtil.clamp(this.repoSelected + delta, 0, targets.size() - 1);
        // DRIVE THE LIST SELECTION SO THE PICKED MOD CARD (TARGETS 1..n) STAYS INSIDE THE SCROLL VIEWPORT
        if (this.repoSelected >= 1) this.repoList.moveSelection(this.repoSelected - 1 - Math.max(0, this.repoList.selectedIndex()));
        else this.repoList.selection(-1);
        this.ctx.sound.play();
        this.invalidate();
    }

    // ROW FACTORY FOR THE UPLOAD FILE LIST: EACH ROW SELF-BINDS TO ITS LIVE ENTRY EVERY FRAME
    private Element<?> uploadRow(final LogUploader.FileEntry entry, final int index) {
        final FileRow row = new FileRow();
        row.pip.status(() -> uploadStatus(entry));
        row.binder(r -> {
            final int stage = this.ctx.upload.stage;
            final boolean errored = "FAILED".equals(entry.state) || "READ ERROR".equals(entry.state);
            final boolean linked = stage >= 3 && entry.uploaded && !entry.url.isBlank();
            final int pct = MathUtil.clamp(entry.progress, 0, 100);
            row.twoLine = linked || (stage == 2 && entry.present && entry.valid && !errored);
            row.name.text(entry.name).color(entry.present ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            row.url.visible(linked).text(entry.url);
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
        if (entry.uploaded || "READ OK".equals(entry.state)) return AppTheme.GREEN;
        if ("UPLOADING".equals(entry.state)) return AppTheme.NEON_LIGHT;
        if ("INVALID".equals(entry.state)) return AppTheme.AMBER;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return AppTheme.RED;
        return entry.present ? AppTheme.TEXT_FAINT : AppTheme.RED;
    }

    private static StatusSquare.Status uploadStatus(final LogUploader.FileEntry entry) {
        if (entry.uploaded || "READ OK".equals(entry.state)) return StatusSquare.Status.OK;
        if ("UPLOADING".equals(entry.state)) return StatusSquare.Status.PENDING;
        if ("INVALID".equals(entry.state)) return StatusSquare.Status.WARN;
        if ("FAILED".equals(entry.state) || "READ ERROR".equals(entry.state)) return StatusSquare.Status.ERROR;
        return entry.present ? StatusSquare.Status.OFF : StatusSquare.Status.ERROR;
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
        private static final int BOX_H = 82;
        private static final int NOTCH_H = 10;
        private static final boolean BLOCKED = !LogUploader.WORKDIR_IS_MODS;
        private static final String TITLE = BLOCKED ? "NO MC CONTEXT" : "SENDS LOGS TO MCLO.GS";
        private static final String LINE_1 = BLOCKED ? "Upload logs is blocked outside" : "Reads logs/latest.log and crash reports,";
        private static final String LINE_2 = BLOCKED ? "the Minecraft mods folder." : "then uploads.";

        @Override
        protected void onUpdate() {
            final int idx = HomeScreen.this.actionIndex(Action.UPLOAD_LOGS);
            this.visible = idx >= 0 && HomeScreen.this.selectedPanel == 0 && HomeScreen.this.selectedAction == idx && !HomeScreen.this.ctx.upload.visible;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final TextRenderer text = HomeScreen.this.ctx.text;
            final int desiredW = Math.max(text.width(TITLE, AppTheme.TEXT_BUTTON),
                    Math.max(text.width(LINE_1, AppTheme.TEXT_BODY), text.width(LINE_2, AppTheme.TEXT_BODY))) + 24;
            this.contentWidth = MathUtil.clamp(desiredW, 340, Math.max(120, innerAvailWidth - 24));
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
            canvas.text(LINE_1, x + 12, y + 36, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            canvas.text(LINE_2, x + 12, y + 56, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }

    // ==========================================================================
    // NESTED TYPES: DIALOG BUILDING BLOCKS
    // ==========================================================================

    private record RepoTarget(String name, String slug, String url, Color accent) {}

    // ONE FILE OR SUMMARY ROW: STATUS PIP, NAME (WITH THE URL OR A PROGRESS BAR UNDER IT), SIZE AND STATE BADGE; A
    // ListRow WHOSE ONLY ADDITION IS THIS TWO-LINE LAYOUT OF LIVE ELEMENTS, BOUND EVERY FRAME BY ITS BINDER
    private static final class FileRow extends ListRow {
        private final StatusSquare pip = new StatusSquare();
        private final Text name = new Text();
        private final Text url = new Text().color(AppTheme.CYAN).scale(AppTheme.TEXT_SUBTITLE);
        private final ProgressBar bar = new ProgressBar().accent(AppTheme.NEON_LIGHT);
        private final Text size = new Text().color(AppTheme.TEXT_FAINT).scale(AppTheme.TEXT_SUBTITLE);
        private final Badge tag = new Badge();
        private boolean twoLine;

        private FileRow() {
            this.pip.size(10, 10);
            this.url.visible(false);
            this.bar.visible(false);
            this.separatorFrom(28).separator(false);
            this.add(this.pip).add(this.name).add(this.url).add(this.bar).add(this.size).add(this.tag);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.pip.measure(10, 10);
            final int nameW = Math.max(40, innerAvailWidth - 28 - 190);
            this.name.width(nameW).measure(nameW, 20);
            final int urlW = Math.max(40, innerAvailWidth - 40);
            this.url.width(urlW).measure(urlW, 16);
            final int barW = Math.max(120, innerAvailWidth - 124);
            this.bar.width(barW).measure(barW, 6);
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
            // TWO-LINE MODE CENTERS THE NAME IN THE TOP 28px BAND, WITH THE URL/PROGRESS BAR UNDERNEATH
            final int nameY = this.twoLine
                    ? y + Math.max(0, (28 - this.name.measuredHeight()) / 2)
                    : y + Math.max(0, (h - this.name.measuredHeight()) / 2);
            this.name.layout(x + 28, nameY);
            this.url.layout(x + 28, y + 28);
            this.bar.layout(x + 28, y + 31);
            final int sizeY = this.twoLine ? nameY : y + Math.max(0, (h - this.size.measuredHeight()) / 2);
            this.size.layout(x + w - 180, sizeY);
            this.tag.layout(x + w - this.tag.measuredWidth(), y + Math.max(0, (h - this.tag.measuredHeight()) / 2));
        }
    }

    // REPORT PANE: HOSTS THE CLIPBOARD STRIP, HERO AND MOD LIST, AND OWNS THE STAGE-3 UP/DOWN KEYS
    // (RUNNING BEFORE THE DIALOG'S OWN ESC/ENTER FALLBACK)
    private final class ReportPane extends Parent {
        private ReportPane() {
            super(Orientation.VERTICAL);
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (!this.visible) return false;
            if (action == GLFW_RELEASE && (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN)) {
                HomeScreen.this.moveRepoSelection(key == GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            return super.dispatchKey(key, action);
        }
    }

    // CLIPBOARD CONFIRMATION STRIP: GREEN "COPIED" OR MUTED "UNAVAILABLE", RESOLVED LIVE PER DRAW
    private final class CopyStrip extends Element<CopyStrip> {
        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean copied = HomeScreen.this.ctx.upload.issueCopied;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, AppTheme.alpha(copied ? AppTheme.GREEN : AppTheme.BG_2, copied ? 26 : 164));
            canvas.stroke(x, y, w, h, copied ? AppTheme.GREEN : AppTheme.STROKE_BRIGHT, 1f);
            PixelIcon.draw(copied ? "check" : "copy", x + 12, y + (h - 14) / 2, 14,
                    copied ? AppTheme.GREEN : AppTheme.TEXT_FAINT);
            final String msg = copied
                    ? "Report template copied to clipboard: paste it into the issue body"
                    : "Clipboard copy unavailable: write the report manually";
            canvas.text(canvas.text().truncateToWidth(msg, w - 44, AppTheme.TEXT_BODY), x + 34,
                    y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                    copied ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
        }
    }

    // WATERMEDIA HERO BANNER (PRIMARY REPORT TARGET): LOGO, PRIMARY BADGE, COPY AND ITS SUBMIT BUTTON;
    // CLICKS ROUTE BY COORDINATE: THE SUBMIT BOX FIRES THE REPO ACTION, THE BODY ONLY SELECTS
    private final class RepoHero extends Element<RepoHero> {
        private static final int PAD = 14;
        private int submitX;
        private int submitY;
        private boolean submitHover;

        @Override
        protected void onLayout() {
            this.submitX = this.left + this.measuredWidth - PAD - 150;
            this.submitY = this.top + this.measuredHeight - PAD - 32;
        }

        private boolean inSubmit(final double mx, final double my) {
            return mx >= this.submitX && mx < this.submitX + 150 && my >= this.submitY && my < this.submitY + 32;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
            if (this.inSubmit(mx, my) && !HomeScreen.this.repoTargets.isEmpty()) {
                HomeScreen.this.submitRepo(HomeScreen.this.repoTargets.get(0), 0);
            } else if (HomeScreen.this.repoSelected != 0) {
                // THE BODY ONLY SELECTS; THE SUBMIT BUTTON OPENS THE ISSUE TRACKER
                HomeScreen.this.repoSelected = 0;
                HomeScreen.this.ctx.sound.play();
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            final boolean inside = this.visible && this.enabled && this.contains(mx, my);
            this.hovered = inside;
            this.submitHover = inside && this.inSubmit(mx, my);
            return inside;
        }

        @Override
        public void clearHover() {
            super.clearHover();
            this.submitHover = false;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            if (HomeScreen.this.repoTargets.isEmpty()) return;
            final RepoTarget repo = HomeScreen.this.repoTargets.get(0);
            final boolean selected = HomeScreen.this.repoSelected == 0;
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.glow(x, y, w, h, 0f, AppTheme.GREEN, selected ? 0.26f : 0.12f);
            canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.GREEN, selected ? 30 : 18));
            canvas.stroke(x, y, w, h, AppTheme.GREEN, selected ? 2f : 1.5f);
            canvas.fill(x, y, 4, h, AppTheme.GREEN);

            // PACK.PNG LOGO: SQUARE, ASPECT-PRESERVED, CENTERED IN ITS BOX; TEXTURE ID READ PER DRAW
            final int logoBox = h - PAD * 2;
            final int logoX = x + PAD + 8;
            final var assets = HomeScreen.this.ctx.assets;
            if (assets.iconId > 0 && assets.iconWidth > 0 && assets.iconHeight > 0) {
                final float s = Math.min((float) logoBox / assets.iconWidth, (float) logoBox / assets.iconHeight);
                final int lw = (int) (assets.iconWidth * s);
                final int lh = (int) (assets.iconHeight * s);
                canvas.image(assets.iconId, logoX + (logoBox - lw) / 2f, y + PAD + (logoBox - lh) / 2f, lw, lh, null);
            }

            final int textX = logoX + logoBox + 20;
            final int textRight = x + w - PAD;
            // PRIMARY BADGE TOP-RIGHT
            final int badgeW = canvas.textWidth("PRIMARY", AppTheme.TEXT_SUBTITLE, false) + 20;
            canvas.fill(textRight - badgeW, y + PAD, badgeW, 20, AppTheme.alpha(AppTheme.GREEN, 40));
            canvas.stroke(textRight - badgeW, y + PAD, badgeW, 20, AppTheme.GREEN, 1f);
            canvas.text("PRIMARY", textRight - badgeW + 10,
                    y + PAD + Math.max(0, (20 - canvas.textHeight(AppTheme.TEXT_SUBTITLE, false)) / 2),
                    AppTheme.GREEN, AppTheme.TEXT_SUBTITLE, false);

            // TEXT: EACH LINE CLAMPED SO IT NEVER SPILLS PAST THE BADGE, THE BUTTON OR THE HERO EDGE
            canvas.text(canvas.text().truncateToWidth("SUBMIT A BUG REPORT ON WATERMEDIA ISSUE TRACKER",
                            textRight - badgeW - 12 - textX, AppTheme.TEXT_BUTTON, Font.BOLD),
                    textX, y + 16, AppTheme.GREEN, AppTheme.TEXT_BUTTON, true);
            canvas.text(canvas.text().truncateToWidth("AND, if a listed mod is the suspect, also file it in that mod's repo below.",
                            textRight - textX, AppTheme.TEXT_BODY),
                    textX, y + 40, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);
            canvas.text(canvas.text().truncateToWidth(repo.slug(), this.submitX - 12 - textX, AppTheme.TEXT_SUBTITLE),
                    textX, y + 64, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);

            // SUBMIT: SHARED DESIGN-SYSTEM BUTTON LOOK; ACTIVE WHEN SELECTED OR ITS BOX IS HOVERED
            Button.render(canvas, this.submitX, this.submitY, 150, 32,
                    "SUBMIT", "", "link", 12, AppTheme.GREEN, AppTheme.GREEN, false,
                    selected || this.submitHover, true);
        }
    }

    // ONE SUSPECTED-MOD CARD IN THE REPORT LIST: ACCENT STRIPE, NAME/SLUG AND ITS OWN SUBMIT BUTTON
    private final class RepoCard extends Element<RepoCard> {
        private final RepoTarget repo;
        private final int targetIdx; // INDEX INTO repoTargets (1..n, 0 IS THE HERO)
        private int submitX;
        private int submitY;
        private boolean submitHover;

        private RepoCard(final RepoTarget repo, final int targetIdx) {
            this.repo = repo;
            this.targetIdx = targetIdx;
        }

        @Override
        protected void onLayout() {
            this.submitX = this.left + this.measuredWidth - 138;
            this.submitY = this.top + (this.measuredHeight - 32) / 2;
        }

        private boolean inSubmit(final double mx, final double my) {
            return mx >= this.submitX && mx < this.submitX + 122 && my >= this.submitY && my < this.submitY + 32;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
            if (this.inSubmit(mx, my)) {
                HomeScreen.this.submitRepo(this.repo, this.targetIdx);
            } else if (HomeScreen.this.repoSelected != this.targetIdx) {
                // CARD BODY ONLY SELECTS; THE SUBMIT BUTTON OPENS THE ISSUE TRACKER
                HomeScreen.this.repoSelected = this.targetIdx;
                HomeScreen.this.ctx.sound.play();
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            final boolean inside = this.visible && this.enabled && this.contains(mx, my);
            this.hovered = inside;
            this.submitHover = inside && this.inSubmit(mx, my);
            return inside;
        }

        @Override
        public void clearHover() {
            super.clearHover();
            this.submitHover = false;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean sel = HomeScreen.this.repoSelected == this.targetIdx;
            final Color accent = this.repo.accent();
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, sel ? AppTheme.alpha(accent, 30) : AppTheme.alpha(AppTheme.BG_2, 210));
            canvas.stroke(x, y, w, h, sel ? accent : AppTheme.STROKE_BRIGHT, sel ? 2f : 1f);
            if (sel) canvas.glow(x, y, w, h, 0f, accent, 0.18f);
            canvas.fill(x, y, 4, h, accent);
            // SQUARE STATUS PIP IN THE CARD ACCENT
            canvas.fill(x + 16, y + (h - 10) / 2, 10, 10, accent);
            canvas.glow(x + 16, y + (h - 10) / 2, 10, 10, 0f, accent, 0.5f);
            canvas.text(this.repo.name().toUpperCase(Locale.ROOT), x + 36, y + 12,
                    sel ? accent : AppTheme.TEXT, AppTheme.TEXT_BUTTON, true);
            canvas.text(this.repo.slug(), x + 36, y + 32, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            Button.render(canvas, this.submitX, this.submitY, 122, 32,
                    "SUBMIT", "", "link", 12, accent, accent, false,
                    sel || this.submitHover, true);
        }
    }
}
