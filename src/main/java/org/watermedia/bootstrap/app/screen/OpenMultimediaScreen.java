package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.MediaCatalog;
import org.watermedia.bootstrap.app.MediaSession;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.IconButton;
import org.watermedia.bootstrap.app.element.ListRow;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.watermedia.bootstrap.app.render.RenderSystem.mediaEngineSupplier;

/**
 * Dialog over the home screen that previews and plays a custom URL or path, or searches the platforms
 * when the input is neither.
 */
public class OpenMultimediaScreen extends Screen {
    private static final long LOAD_TIMEOUT_MS = 30_000L;
    private static final int SEARCH_PREVIEW_H = 130;      // SHRUNK PREVIEW HEIGHT WHILE SEARCHING
    private static final long PREVIEW_ANIM_MS = 220L;     // PREVIEW GROW/SHRINK EASE DURATION

    // TRANSLUCENT SO THE HOME SCREEN STAYS VISIBLE BEHIND THE DIALOG
    private static final Color SCRIM_COLOR = AppTheme.rgba(5, 8, 26, 166);
    private static final Color CLOSE_HOVER = AppTheme.rgba(255, 132, 160, 255);
    private static final int RESULT_ROW_H = 38;
    // A RESULT'S TEXTS START 10 PX AFTER ITS 16:9 THUMBNAIL, WHICH SITS 6 PX IN WITH 4 PX ABOVE AND BELOW
    private static final int RESULT_TEXT_X = 6 + Math.round((RESULT_ROW_H - 8) * 16f / 9f) + 10;
    private static final Color GRADIENT_TOP = AppTheme.rgba(0, 0, 0, 0);

    // DROPDOWN ROW KINDS: ONE FLAT Row LIST FEEDS THE ListView
    private static final int ROW_HEAD = 0;
    private static final int ROW_WAIT = 1;
    private static final int ROW_RESULT = 2;
    private static final int ROW_RECENT = 3;
    private static final int ROW_HISTORY = 4;

    // CONSTANT LISTS SO keybinds() ALLOCATES NOTHING PER FRAME
    private static final List<Keybind> KEYS_LOADING = List.of(new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_SEARCH = List.of(
            new Keybind("TYPE", "Search"), new Keybind("CLICK", "Pick result"),
            new Keybind("CTRL+V", "Paste"), new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_DEFAULT = List.of(
            new Keybind("ENTER", "Play"), new Keybind("SPACE", "Save"),
            new Keybind("CTRL+V", "Paste"), new Keybind("R", "Reload"), new Keybind("ESC", "Cancel"));

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private volatile int previewGeneration;
    private long loadStartTime;
    private volatile MRL previewMRL;
    private String previewUrl = "";

    // THE RESULT LIST FILLS OFF-THREAD AND onUpdate POLLS IT EVERY FRAME; searchRenderedDone GUARANTEES ONE FINAL FRAME
    // ONCE THE SEARCH COMPLETES, AND searchQuery IS THE TEXT IT WAS ISSUED FOR
    private List<PlatformResult> search;
    private String searchQuery;
    private boolean searchRenderedDone = true;
    // searchMode() CACHED FOR searchModeText, THE TRIMMED INPUT IT WAS COMPUTED FROM (null = NOT COMPUTED YET)
    private boolean searchMode;
    private String searchModeText;
    private float animPreviewH;
    private float animPreviewFrom;
    // -1 MAKES THE NEXT PREVIEW HEIGHT SNAP INSTEAD OF EASING
    private int animPreviewTarget = -1;
    private long animPreviewStart;

    // STATE BEHIND THE CURRENT DROPDOWN ROWS; syncDropdown REBUILDS THEM ONLY WHEN IT CHANGES
    private List<PlatformResult> rowsSearch;
    private int rowsResults;
    private int rowsHistory;
    private boolean rowsSearching;
    private boolean rowsBuilt;

    // TREE ELEMENTS BOUND FROM LIVE STATE IN onUpdate
    // THE LINK OR QUERY BEING TYPED; THE SCREEN OUTLIVES ITS VISITS, SO THE TEXT IS STILL THERE ON THE NEXT ONE
    private TextField field;
    private Button reload;
    private Button save;
    private Button play;
    private PreviewPanel preview;
    private StatusChip chip;
    private ListView<Row> dropdown;
    private Dialog loadDialog;
    private Text loadUrl;
    private boolean loadShown;

    // RESULT THUMBNAIL PLAYERS, RELEASED ON EACH NEW SEARCH AND ON EXIT
    private final Map<URI, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<URI> thumbAttempted = new HashSet<>();
    private final Set<URI> thumbSubscribed = new HashSet<>();

    public OpenMultimediaScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    // THE SHELL KEEPS THE HOME SCREEN MOUNTED UNDER THIS ONE IN THE CENTER SLOT
    @Override
    public String under() {
        return "home";
    }

    @Override
    public void onEnter() {
        super.onEnter(); // BUILDS THE TREE ON FIRST ENTRY
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
        this.animPreviewTarget = -1; // SNAP THE PREVIEW SIZE ON ENTRY INSTEAD OF EASING
        this.clearSearch();
        this.field.focused(true);
        this.syncLoadDialog();
    }

    @Override
    public void onExit() {
        this.loading = false;
        this.loadGeneration++;
        this.previewGeneration++;
        this.previewMRL = null;
        this.previewUrl = "";
        this.clearSearch();
        this.syncLoadDialog();
    }

    // RENDER BACKEND HOT-SWAP: THE ROWS REBUILD THEIR THUMBNAIL PLAYERS ON THE NEW ENGINE AS THEY DRAW
    @Override
    public void releaseMedia() {
        this.releaseThumbs();
    }

    @Override
    public List<Keybind> keybinds() {
        if (this.loading) return KEYS_LOADING;
        return this.searchMode() ? KEYS_SEARCH : KEYS_DEFAULT;
    }

    @Override
    public boolean continuous() {
        // CARET BLINK, LOADING DOTS, SEARCH POLLING, PREVIEW EASE AND ANIMATED THUMBNAILS
        return this.textInputActive() || this.loading
                || (this.search != null && !this.searchRenderedDone)
                || System.currentTimeMillis() - this.animPreviewStart < PREVIEW_ANIM_MS
                || Previews.animating(this.thumbPlayers.values());
    }

    // ==========================================================================
    // PUBLIC API: INPUT
    // ==========================================================================

    // THE TREE HANDLES POINTERS; ONLY THE SCREEN SHORTCUTS LIVE HERE

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // TREE FIRST: THE LOADING DIALOG (FULLY MODAL) AND THE FOCUSED FIELD (BACKSPACE/DELETE) CONSUME THEIRS
        if (super.dispatchKey(key, action)) return true;
        if (action == GLFW_RELEASE) {
            final String url = this.field.value();
            switch (key) {
                // ONLY WHILE THE FIELD IS BLURRED: A TYPED SPACE IS INPUT, NOT THE SAVE SHORTCUT
                case GLFW_KEY_SPACE -> {
                    if (!this.field.textInputActive() && !this.searchMode() && !url.isEmpty()) this.saveCustomUrl();
                }
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (!url.isEmpty()) {
                        if (this.searchMode()) this.doSearch(true);
                        else this.playCustomUrl();
                    }
                }
                case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
                case GLFW_KEY_V -> {
                    if (this.ctx.ctrlDown && !this.ctx.altDown) this.pasteFromClipboard();
                }
                // ONLY WHILE THE FIELD IS BLURRED: AN R TYPED INTO A URL MUST NOT TRIGGER A NETWORK RELOAD
                case GLFW_KEY_R -> {
                    if (!this.field.textInputActive() && !this.searchMode() && !url.isEmpty()) this.reloadPreviewMRL();
                }
            }
        }
        return true; // MODAL: THE HOME SCREEN UNDERNEATH MUST NEVER SEE KEYS
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (super.dispatchScroll(mx, my, amount)) return true; // DROPDOWN / LOADING DIALOG
        return this.contains(mx, my); // SWALLOWED INSIDE THE SLOT SO THE HOME SCREEN DOES NOT SCROLL
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected Element<?> build() {
        // EVERY EDIT FEEDS THE SEARCH DEBOUNCE AND THE PREVIEW; onUpdate SWAPS THE LINK AND SEARCH ICONS. PASTE, A RESULT
        // OR THE HISTORY KEEP IT FOCUSED, SO SPACE AND R STAY TEXT INSTEAD OF SAVE AND RELOAD
        this.field = new TextField()
                .placeholder("search, or paste a URL / path...")
                .unicode(true)
                .keepFocus(true)
                .maxLength(Integer.MAX_VALUE)
                .scale(AppTheme.TEXT_BUTTON)
                .accent(AppTheme.NEON)
                .frame(AppTheme.BG_2, AppTheme.STROKE_BRIGHT, null, 1f, 1f)
                .focusGlow(0.28f, true)
                .caret(AppTheme.NEON_LIGHT, 2, true)
                .onChange(v -> this.ensurePreviewMRL())
                .padding(new Spacing(0, 14, 0, 32))
                .size(Element.MAX_PARENT, 40);
        this.reload = new Button("RELOAD").icon("reload")
                .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT)
                .size(112, 40).onClick(b -> this.reloadPreviewMRL());
        this.preview = new PreviewPanel().width(Element.MAX_PARENT).margin(new Spacing(20, 20, 0, 20));
        this.chip = new StatusChip();
        this.dropdown = new ListView<Row>()
                .rowFactory((row, index) -> this.rowFor(row))
                .onSelect((row, index) -> this.pickRow(row))
                // ROWS DRAW THEIR OWN HOVER FILL, SO THE LIST HIGHLIGHTS ARE TRANSPARENT
                .selectionColor(AppTheme.alpha(AppTheme.NEON, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .padding(new Spacing(6, 2, 6, 2))
                .background(AppTheme.BG_2_ALPHA)
                .border(AppTheme.NEON, 1f)
                .shadow(0.45f)
                // A CLICK INSIDE THE BOX BUT NOT ON A ROW IS SWALLOWED
                .consumeTouch(true)
                .visible(false)
                .width(Element.MAX_PARENT);

        final Button cancel = new Button("CANCEL").subText("ESC").icon("x")
                .accent(AppTheme.TEXT_SOFT).textColor(AppTheme.TEXT_SOFT).height(36)
                .onClick(b -> this.navigator.accept(HomeScreen.Action.BACK));
        this.save = new Button("SAVE").subText("SPACE").icon("save")
                .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT).height(36)
                .onClick(b -> this.saveCustomUrl());
        this.play = new Button("PLAY").subText("ENTER").icon("play")
                .accent(AppTheme.GREEN).textColor(AppTheme.GREEN).height(36)
                .onClick(b -> {
                    if (this.searchMode()) this.doSearch(true);
                    else this.playCustomUrl();
                });

        final Parent footer = Parent.row().spacing(12)
                .width(Element.MAX_PARENT).height(36)
                .margin(new Spacing(0, 20, 22, 20))
                .add(cancel)
                .add(new Box().size(0, 1).weight(1f))
                .add(this.save)
                .add(this.play);

        // DIALOG TITLE STRIP: BOLD TITLE, A DIVIDER UNDER IT AND THE RED CLOSE BUTTON PINNED RIGHT
        final ListRow titleRow = new ListRow()
                .fill(AppTheme.BG_2, null, null)
                .title("OPEN MULTIMEDIA")
                .titleStyle(AppTheme.TEXT_BUTTON, true, AppTheme.NEON_LIGHT, null)
                .textAt(20, ListRow.CENTER)
                .trail((canvas, row, x, y, w, h) -> canvas.fill(x, y + h, w, 1, AppTheme.STROKE_BRIGHT))
                .place(new IconButton("x").accent(AppTheme.RED).iconColor(AppTheme.RED).hoverColor(CLOSE_HOVER).size(26, 26)
                        .onClick(b -> this.navigator.accept(HomeScreen.Action.BACK)), -44, 14);
        final Parent column = Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(titleRow.width(Element.MAX_PARENT).height(56))
                .add(this.preview)
                .add(new InputBlock().width(Element.MAX_PARENT).height(68).margin(new Spacing(0, 20, 0, 20)))
                .add(new MiddleZone().width(Element.MAX_PARENT).weight(1f).margin(new Spacing(0, 20, 0, 20)))
                .add(footer);

        final PanelBox panel = new PanelBox().add(column)
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .background(AppTheme.BG_1)
                .border(AppTheme.NEON, 1f)
                .glow(AppTheme.NEON, 0.35f)
                .shadow(0.55f);

        // LOADING MODAL; onUpdate REFRESHES ITS TITLE DOTS AND URL
        this.loadUrl = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT);
        this.loadDialog = new Dialog()
                .title("Loading")
                .accent(AppTheme.NEON)
                .panelWidth(480)
                .content(this.loadUrl)
                .content(new Text("ESC to cancel").scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT))
                .onDismiss(this::cancelLoading);

        final Box scrim = new Box().size(Element.MAX_PARENT, Element.MAX_PARENT)
                .background(SCRIM_COLOR)
                .consumeTouch(true)
                // A CLICK OUTSIDE THE PANEL BLURS THE FIELD AND NEVER REACHES THE HOME SCREEN
                .onClick(b -> this.field.focused(false).invalidate());
        return new Body(scrim, panel);
    }

    @Override
    protected void onUpdate() {
        // PER-FRAME BINDING: PULLS LIVE STATE INTO THE TREE BEFORE MEASURE/DRAW
        this.ensurePreviewMRL();
        if (this.loading) this.checkLoadingState();
        this.syncLoadDialog();

        final String url = this.field.value();
        if (this.loading) {
            this.loadDialog.title("Loading" + ".".repeat((int) ((System.currentTimeMillis() / 500) % 4)));
            this.loadUrl.text(url.isEmpty() ? "(empty)" : url);
        }

        final boolean search = this.searchMode();
        // A LOADING LINK IS READ-ONLY; THE GLYPH SAYS WHETHER THE TEXT IS A SEARCH OR A LINK
        this.field.enabled(!this.loading).icon(search ? "search" : "link", search ? AppTheme.CYAN : AppTheme.TEXT_FAINT, 10);

        // EVERY EDIT OF THE QUERY REACHES THE API, WHICH WAITS OUT THE TYPING ITSELF
        if (search) {
            final String query = url.trim();
            if (!query.isEmpty() && !query.equals(this.searchQuery)) this.doSearch(false);
        }

        // RELOAD AND SAVE ONLY TAKE A URL OR PATH; THE PRIMARY BUTTON SWITCHES BETWEEN PLAY AND SEARCH
        final boolean empty = url.isEmpty();
        this.reload.enabled(!search && !empty);
        this.save.accent(search || empty ? AppTheme.TEXT_FAINT : AppTheme.NEON_LIGHT).enabled(!search && !empty);
        final Color playColor = empty ? AppTheme.TEXT_FAINT : search ? AppTheme.CYAN : AppTheme.GREEN;
        this.play.label(search ? "SEARCH" : "PLAY").icon(search ? "search" : "play")
                .accent(playColor).textColor(playColor).enabled(!empty);

        // STATUS CHIP: SEARCH PROGRESS IN SEARCH MODE, OTHERWISE THE PREVIEW MRL STATE
        if (search) {
            final boolean done = this.search != null && !PlatformAPI.searching(this.searchQuery);
            this.chip.set(done ? this.search.size() + " RESULTS" : "SEARCHING",
                    done ? AppTheme.CYAN : AppTheme.NEON);
        } else {
            this.chip.set(this.statusLabel(), Previews.color(this.previewMRL));
        }

        this.syncDropdown(search);
        this.searchRenderedDone = this.search == null || !PlatformAPI.searching(this.searchQuery);
    }

    // ==========================================================================
    // ACTIONS
    // ==========================================================================

    private void pasteFromClipboard() {
        try {
            final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                this.field.value(((String) clipboard.getData(DataFlavor.stringFlavor)).trim().replace("\"", ""));
                this.ensurePreviewMRL();
            }
        } catch (final Exception ignored) {
            // A BUSY CLIPBOARD OR A FLAVOR ERROR KEEPS THE URL ALREADY TYPED
        }
    }

    private void reloadPreviewMRL() {
        final String url = this.field.value().trim();
        if (url.isEmpty()) return;
        if (this.previewMRL == null || !url.equals(this.previewUrl)) {
            this.previewUrl = "";
            this.ensurePreviewMRL();
            return;
        }

        final int generation = ++this.previewGeneration;
        this.previewMRL = this.previewMRL.reload();
        this.loadStartTime = System.currentTimeMillis();
        this.subscribePreviewMRL(this.previewMRL, generation);
        this.ctx.requestRender();
    }

    private void saveCustomUrl() {
        final String url = this.field.value();
        if (url.isEmpty()) return;
        final String name = url.length() > 40 ? url.substring(0, 37) + "..." : url;
        this.ctx.catalog.customTests.add(new MediaCatalog.TestURI(name, url, false));
        this.field.value("");
        this.navigator.accept(HomeScreen.Action.BACK);
    }

    private void playCustomUrl() {
        if (this.field.value().isEmpty()) return;

        this.ensurePreviewMRL();
        if (this.previewMRL == null) return;
        switch (this.previewMRL.status()) {
            case LOADED -> { /* PROCEED BELOW */ }
            case FETCHING -> {
                this.beginLoading(this.previewMRL);
                return;
            }
            case ERROR -> {
                this.ctx.showError("Invalid URL", "Failed to load this URL.", null);
                return;
            }
            case BLOCKED -> {
                this.ctx.showError("Blocked", "This media was blocked by the platform.", null);
                return;
            }
            // EXPIRED OR FORGOTTEN MRLS ARE UNUSABLE: REGENERATE A FRESH ONE BEFORE PLAYING
            case EXPIRED, FORGOTTEN -> {
                this.previewUrl = "";
                this.ensurePreviewMRL();
                if (this.previewMRL != null) this.beginLoading(this.previewMRL);
                return;
            }
        }

        this.ctx.media.selectedMRL = this.previewMRL;
        this.ctx.media.selectedMRLName = this.previewTitle();
        this.ctx.media.selectedGroup = null;
        if (!this.ctx.media.selectSources()) {
            this.ctx.showError("No Sources", "No sources available for this URL", null);
            return;
        }
        this.navigator.accept(HomeScreen.Action.PLAYER);
    }

    private void ensurePreviewMRL() {
        final String url = this.field.value().trim();
        if (url.isEmpty()) {
            if (this.previewMRL != null || !this.previewUrl.isEmpty()) this.previewGeneration++;
            this.previewMRL = null;
            this.previewUrl = "";
            return;
        }
        if (url.equals(this.previewUrl)) return;
        this.previewUrl = url;
        final int generation = ++this.previewGeneration;
        try {
            if (!loadable(url)) {
                this.previewMRL = null;
                return;
            }
            this.previewMRL = PlayerAPI.mrl(url);
            this.loadStartTime = System.currentTimeMillis();
            this.subscribePreviewMRL(this.previewMRL, generation);
        } catch (final Exception ignored) {
            this.previewMRL = null;
        }
    }

    // ==========================================================================
    // SEARCH MODE
    // ==========================================================================

    // TRUE WHEN THE INPUT IS NOT loadable(); MEMOIZED ON THE INPUT TEXT BECAUSE IT RUNS EVERY FRAME
    // AND loadable() BLOCKS ON A FILE SYSTEM STAT
    private boolean searchMode() {
        final String text = this.field.value().trim();
        if (!text.equals(this.searchModeText)) {
            this.searchModeText = text;
            this.searchMode = !text.isEmpty() && !loadable(text);
        }
        return this.searchMode;
    }

    // EASE-OUT QUAD TOWARD target; A NEW target RESTARTS FROM THE CURRENT HEIGHT SO RAPID MODE FLIPS
    // STAY SMOOTH
    private int animatePreviewH(final int target) {
        if (this.animPreviewTarget != target) {
            this.animPreviewFrom = this.animPreviewTarget < 0 ? target : this.animPreviewH; // FIRST FRAME SNAPS
            this.animPreviewTarget = target;
            this.animPreviewStart = System.currentTimeMillis();
        }
        final float t = Math.min(1f, (System.currentTimeMillis() - this.animPreviewStart) / (float) PREVIEW_ANIM_MS);
        final float eased = 1f - (1f - t) * (1f - t);
        this.animPreviewH = this.animPreviewFrom + (this.animPreviewTarget - this.animPreviewFrom) * eased;
        return Math.round(this.animPreviewH);
    }

    // PlatformAPI DISCARDS ANY PREVIOUS SEARCH AND onUpdate POLLS THE NEW LIST AS RESULTS LAND; AN EXPLICIT SEARCH OF THE
    // SAME TEXT (ENTER, THE SEARCH BUTTON) ASKS THE PLATFORMS AGAIN INSTEAD OF THE CACHE
    private void doSearch(final boolean again) {
        final String query = this.field.value().trim();
        if (query.isEmpty()) return;
        this.releaseThumbs();
        this.searchRenderedDone = false;
        this.rowsBuilt = false;
        this.searchQuery = query;
        this.search = PlatformAPI.search(query, again);
        this.ctx.requestRender();
    }

    private void pickRow(final Row row) {
        if (row == null) return;
        if (row.kind() == ROW_RESULT) {
            // THE RESULT'S URL REPLACES THE INPUT, WHICH LEAVES SEARCH MODE SO IT CAN BE PLAYED
            final PlatformResult result = row.result();
            if (result == null || result.uri() == null) return;
            this.field.value(result.uri().toString());
            this.clearSearch();
            this.field.focused(true);
            this.ensurePreviewMRL();
            this.ctx.sound.play();
            this.ctx.requestRender();
        } else if (row.kind() == ROW_HISTORY) {
            this.field.value(row.text());
            this.doSearch(false);
        }
    }

    private void clearSearch() {
        this.search = null;
        this.searchQuery = null;
        this.searchRenderedDone = true;
        this.rowsBuilt = false;
        if (this.dropdown != null) {
            this.dropdown.items(List.of());
            this.dropdown.visible(false);
        }
        this.releaseThumbs();
    }

    // OUTSIDE SEARCH MODE IT DROPS ANY STALE SEARCH; INSIDE, ROWS REBUILD ONLY WHEN THE POLLED DATA CHANGES
    private void syncDropdown(final boolean searchMode) {
        if (!searchMode) {
            if (this.search != null) this.clearSearch();
            if (this.dropdown.visible()) this.dropdown.visible(false);
            this.rowsBuilt = false;
            return;
        }

        final boolean searching = this.search != null && PlatformAPI.searching(this.searchQuery);
        final List<PlatformResult> results = this.search != null ? this.search : List.of();
        final List<String> history = PlatformAPI.searchHistory();
        if (this.rowsBuilt && this.search == this.rowsSearch && results.size() == this.rowsResults
                && history.size() == this.rowsHistory && searching == this.rowsSearching)
            return;
        this.rowsSearch = this.search;
        this.rowsResults = results.size();
        this.rowsHistory = history.size();
        this.rowsSearching = searching;
        this.rowsBuilt = true;

        final List<Row> rows = new ArrayList<>();
        if (this.search != null) {
            rows.add(new Row(ROW_HEAD, null, searching ? "SEARCHING..." : "RESULTS (" + results.size() + ")"));
            if (results.isEmpty() && searching) rows.add(new Row(ROW_WAIT, null, null));
            else for (final PlatformResult result: results) rows.add(new Row(ROW_RESULT, result, null));
        }
        if (!history.isEmpty()) {
            rows.add(new Row(ROW_RECENT, null, null));
            for (final String entry: history) rows.add(new Row(ROW_HISTORY, null, entry));
        }
        this.dropdown.items(rows);
        this.dropdown.visible(!rows.isEmpty());
    }

    private Element<?> rowFor(final Row row) {
        return switch (row.kind()) {
            case ROW_HEAD -> headRow(row.text(), false);
            case ROW_RECENT -> headRow("RECENT", true);
            // PLACEHOLDER WHILE THE FIRST RESULTS ARE STILL IN FLIGHT
            case ROW_WAIT -> new ListRow().title("Looking across platforms...")
                    .titleStyle(AppTheme.TEXT_BODY, false, AppTheme.NEON, null).textAt(12, ListRow.CENTER)
                    .height(38).enabled(false);
            case ROW_RESULT -> this.resultRow(row.result());
            // A RECENT QUERY: SEARCH GLYPH AND THE QUERY TEXT
            default -> new ListRow().fill(null, AppTheme.alpha(AppTheme.NEON_DARK, 60), null)
                    .icon("search", 11, 10, ListRow.CENTER, AppTheme.TEXT_FAINT, null)
                    .title(row.text()).titleStyle(AppTheme.TEXT_BODY, false, AppTheme.TEXT_SOFT, null)
                    .textAt(30, ListRow.CENTER).textRight(10, 0)
                    .height(26);
        };
    }

    // ONE SEARCH RESULT: A 16:9 THUMBNAIL (THE LIVE MEDIA TEXTURE, ASPECT-FIT) AND THE PLATFORM TAG BEFORE THE TITLE
    private ListRow resultRow(final PlatformResult result) {
        return new ListRow()
                .fill(null, AppTheme.alpha(AppTheme.NEON_DARK, 70), null)
                .lead((canvas, row, x, y, w, h) -> {
                    final int thumbH = h - 8;
                    final int thumbW = Math.round(thumbH * 16f / 9f);
                    final int thumbX = x + 6;
                    final int thumbY = y + 4;
                    // MEDIA TEXTURE READ PER DRAW; ASPECT-FIT NEVER OVERFLOWS THE CELL, SO THE LIST CLIP SUFFICES
                    final MediaPlayer player = this.thumbPlayer(result.thumbnail());
                    if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
                        final float imgAspect = (float) player.width() / player.height();
                        final float boxAspect = (float) thumbW / thumbH;
                        final float bw = imgAspect > boxAspect ? thumbW : thumbH * imgAspect;
                        final float bh = imgAspect > boxAspect ? thumbW / imgAspect : thumbH;
                        canvas.mediaImage(player.texture(), thumbX + (thumbW - bw) / 2f, thumbY + (thumbH - bh) / 2f, bw, bh);
                    } else {
                        canvas.fill(thumbX, thumbY, thumbW, thumbH, AppTheme.BG_0);
                        canvas.stroke(thumbX, thumbY, thumbW, thumbH, AppTheme.STROKE, 1f);
                        canvas.icon(result.thumbnail() == null ? "warn" : "tv",
                                thumbX + (thumbW - 12) / 2, thumbY + (thumbH - 12) / 2, 12, AppTheme.TEXT_FAINT);
                    }
                })
                .prefix(() -> (result.platform() == null ? "?" : result.platform().name()) + ":", AppTheme.NEON_LIGHT)
                .title(result.title() == null || result.title().isEmpty() ? "(untitled)" : result.title())
                .titleStyle(AppTheme.TEXT_BODY, false, AppTheme.TEXT, null)
                .textAt(RESULT_TEXT_X, ListRow.CENTER)
                .textRight(8, 20)
                .height(RESULT_ROW_H);
    }

    // ==========================================================================
    // THUMBNAIL PLAYERS
    // ==========================================================================

    private void releaseThumbs() {
        if (!this.thumbPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbPlayers.values());
            this.thumbPlayers.clear();
            Previews.release(this.ctx, players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
    }

    // LAZY IMAGE PLAYER FOR A RESULT THUMBNAIL; null WHILE ITS MRL FETCHES OR WHEN IT HAS NO IMAGE SOURCE
    private MediaPlayer thumbPlayer(final URI thumbnail) {
        if (thumbnail == null) return null;
        final MediaPlayer existing = this.thumbPlayers.get(thumbnail);
        if (existing != null) return existing;
        if (this.thumbAttempted.contains(thumbnail)) return null;

        final MRL mrl = PlayerAPI.mrl(thumbnail.toString());
        final MRL.Status status = mrl.status();
        if (status == MRL.Status.FETCHING) {
            if (this.thumbSubscribed.add(thumbnail)) mrl.subscribe(done -> this.ctx.requestRender());
            return null; // RETRY NEXT FRAME
        }
        this.thumbAttempted.add(thumbnail);
        if (status != MRL.Status.LOADED) return null;

        final var sources = mrl.sources();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).type() == MediaType.IMAGE) {
                final MediaPlayer player = PlayerAPI.player(mrl, i,
                        mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                if (player != null) {
                    player.repeat(true); // LOOP ANIMATED THUMBNAILS (GIF, WEBP)
                    player.start();
                    this.thumbPlayers.put(thumbnail, player);
                }
                return player;
            }
        }
        return null;
    }

    // ==========================================================================
    // PREVIEW AND LOADING STATE
    // ==========================================================================

    // FOLLOWS THE loading FLAG, WHICH ALSO FLIPS OUTSIDE INPUT EVENTS
    private void syncLoadDialog() {
        if (this.loading == this.loadShown || this.loadDialog == null) return;
        this.loadShown = this.loading;
        if (this.loading) this.showDialog(this.loadDialog);
        else this.hideDialog(this.loadDialog);
    }

    private void cancelLoading() {
        this.loading = false;
        this.loadGeneration++;
        this.ctx.media.selectedMRL = null;
        this.ctx.requestRender();
    }

    private void subscribePreviewMRL(final MRL mrl, final int generation) {
        if (mrl == null || Previews.loaded(mrl)) return;
        mrl.subscribe(done -> {
            if (this.previewGeneration == generation && this.previewMRL == done) this.ctx.requestRender();
        });
    }

    private void beginLoading(final MRL mrl) {
        this.ctx.media.selectedMRL = mrl;
        this.ctx.media.selectedMRLName = this.previewTitle();
        this.loading = true;
        // KEYS LEFT IN THIS INPUT BATCH AFTER THE ENTER MUST NOT EDIT THE LINK; onUpdate ONLY CATCHES UP NEXT FRAME
        this.field.enabled(false);
        this.loadGeneration++;
        this.loadStartTime = System.currentTimeMillis();
        if (!Previews.loaded(mrl)) {
            final int generation = this.loadGeneration;
            mrl.subscribe(done -> {
                if (this.loading && this.loadGeneration == generation && this.ctx.media.selectedMRL == done) this.ctx.requestRender();
            });
            this.scheduleLoadTimeout(generation);
        }
        this.ctx.requestRender();
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.thread("OpenMultimediaScreen-LoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) this.ctx.requestRender();
        });
    }

    private void checkLoadingState() {
        if (this.ctx.media.selectedMRL == null) {
            this.loading = false;
            this.loadGeneration++;
            return;
        }

        final MRL.Status status = this.ctx.media.selectedMRL.status();
        // ANY FINAL STATE OTHER THAN LOADED (ERROR, BLOCKED, EXPIRED, FORGOTTEN) ENDS THE WAIT
        if (status != MRL.Status.LOADED && status != MRL.Status.FETCHING) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: " + status.name(), null);
            this.ctx.media.selectedMRL = null;
            return;
        }

        if (status == MRL.Status.LOADED) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.media.selectedMRLName = this.previewTitle();
            if (!this.ctx.media.selectSources()) {
                this.ctx.showError("No Sources", "No sources available for this URL", null);
                this.ctx.media.selectedMRL = null;
                return;
            }
            // QUEUED BECAUSE THIS RUNS INSIDE THE UPDATE PASS: THE MOUNT SWAP MUST HAPPEN BETWEEN FRAMES
            this.ctx.execute(() -> this.navigator.accept(HomeScreen.Action.PLAYER));
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.loading = false;
            this.loadGeneration++;
            this.ctx.showError("Load Error", "Failed to load URL: Timeout", null);
            this.ctx.media.selectedMRL = null;
        }
    }

    private String statusLabel() {
        return this.previewMRL == null ? "AWAITING URL" : Previews.label(this.previewMRL);
    }

    private MRL.Source firstSource() {
        if (!Previews.loaded(this.previewMRL) || this.previewMRL.sources().isEmpty()) return null;
        return this.previewMRL.sources().get(0);
    }

    private String previewTitle() {
        final MRL.Source src = this.firstSource();
        final Metadata meta = src != null ? src.metadata() : null;
        if (meta != null && meta.title() != null && !meta.title().isEmpty()) return meta.title();
        return "Custom URL";
    }

    // RUNS EVERY FRAME IN THE PREVIEW DRAW, SO IT AVOIDS STREAMS
    private String bestQuality() {
        final MRL.Source src = this.firstSource();
        if (src == null) return null;
        MediaQuality best = MediaQuality.UNKNOWN;
        for (final MediaQuality q: src.qualities().keySet()) {
            if (q.height() > best.height()) best = q;
        }
        return best == MediaQuality.UNKNOWN ? null : best.name();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // THE ONE "URL/PATH VS SEARCH QUERY" RULE, SHARED BY searchMode() AND ensurePreviewMRL();
    // text MUST BE TRIMMED AND NON-EMPTY
    private static boolean loadable(final String text) {
        if (new File(text).exists()) return true;
        try {
            return URI.create(text).getScheme() != null;
        } catch (final IllegalArgumentException e) {
            return false; // UNPARSEABLE (E.G. HAS SPACES), SO IT IS A SEARCH QUERY
        }
    }

    // A SECTION HEAD OF THE DROPDOWN ("RESULTS (n)", "SEARCHING...", "RECENT"); THE RECENT ONE SITS ON A RULE
    private static ListRow headRow(final String label, final boolean rule) {
        final ListRow row = new ListRow().title(label)
                .titleStyle(AppTheme.TEXT_SUBTITLE, false, AppTheme.TEXT_FAINT, null).textAt(8, ListRow.CENTER);
        if (rule) row.lead((canvas, r, x, y, w, h) -> canvas.fill(x + 8, y + h / 2f, w - 16, 1, AppTheme.STROKE));
        return row.height(20).enabled(false);
    }

    // ==========================================================================
    // NESTED TYPES: BODY (SCRIM + CENTERED DIALOG PANEL)
    // ==========================================================================

    // THE SCRIM COVERS THE WHOLE SLOT AND THE PANEL IS CENTERED WITH AN 8 PX TOP MARGIN AND A 36 PX BOTTOM GAP
    private final class Body extends Group<Body> {
        private final Box scrim;
        private final PanelBox panel;

        private Body(final Box scrim, final PanelBox panel) {
            this.scrim = scrim;
            this.panel = panel;
            this.width = MAX_PARENT;
            this.height = MAX_PARENT;
            this.add(scrim);
            this.add(panel);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.scrim.measure(innerAvailWidth, innerAvailHeight);
            final boolean search = OpenMultimediaScreen.this.searchMode();
            final int dialogH = Math.max(260, innerAvailHeight - 44);
            final int reserved = 56 + 20 + 28 + 18 + 42 + 12 + 26 + 20 + 38 + 18;
            final int targetPreviewH = Math.max(80, dialogH - reserved);
            final int maxDialogW = Math.max(360, innerAvailWidth - 100);
            final int dialogW = MathUtil.clamp(Math.round(targetPreviewH * 16f / 9f) + 40, 660, maxDialogW);
            // SEARCH MODE HAS NOTHING TO PREVIEW, SO THE PREVIEW SHRINKS AND THE RESULTS DROPDOWN GETS THE SPACE
            final int fullPreviewH = Math.round((dialogW - 40) * 9f / 16f);
            OpenMultimediaScreen.this.preview.height(OpenMultimediaScreen.this.animatePreviewH(search ? Math.min(fullPreviewH, SEARCH_PREVIEW_H) : fullPreviewH));
            this.panel.measure(dialogW, dialogH);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.scrim.layout(this.left, this.top);
            this.panel.layout(this.left + Math.max(0, (this.measuredWidth - this.panel.measuredWidth()) / 2),
                    this.top + 8);
        }
    }

    // DIALOG PANEL: THE ELEMENT DECORATORS PAINT ITS FRAME AND THE FOUR AMBER CORNER CUBES GO UNDER THE CHILDREN
    private static final class PanelBox extends Group<PanelBox> {
        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            for (final Element<?> child: this.children) child.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            for (final Element<?> child: this.children) child.layout(this.innerLeft(), this.innerTop());
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.cube(this.left - 1, this.top - 1, 10);
            canvas.cube(this.left + this.measuredWidth - 9, this.top - 1, 10);
            canvas.cube(this.left - 1, this.top + this.measuredHeight - 9, 10);
            canvas.cube(this.left + this.measuredWidth - 9, this.top + this.measuredHeight - 9, 10);
            super.onDraw(canvas);
        }
    }

    // INPUT ROW: THE MODE LABEL SITS 20 PX ABOVE THE FIELD AND PASTE AND RELOAD TRAIL IT AT 8 PX GAPS
    private final class InputBlock extends Group<InputBlock> {
        private final Button paste = new Button("PASTE").icon("copy")
                .accent(AppTheme.NEON).textColor(AppTheme.NEON_LIGHT)
                .size(104, 40).onClick(b -> OpenMultimediaScreen.this.pasteFromClipboard());

        private InputBlock() {
            this.add(OpenMultimediaScreen.this.field);
            this.add(this.paste);
            this.add(OpenMultimediaScreen.this.reload);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            OpenMultimediaScreen.this.field.measure(Math.max(0, innerAvailWidth - 232), 40);
            this.paste.measure(104, 40);
            OpenMultimediaScreen.this.reload.measure(112, 40);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int y = this.top + 28; // LABEL ZONE ABOVE THE FIELD
            OpenMultimediaScreen.this.field.layout(this.left, y);
            this.paste.layout(this.left + OpenMultimediaScreen.this.field.measuredWidth() + 8, y);
            OpenMultimediaScreen.this.reload.layout(this.paste.left() + 104 + 8, y);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean search = OpenMultimediaScreen.this.searchMode();
            canvas.text(search ? "SEARCH" : "URL OR PATH", this.left, this.top + 8,
                    search ? AppTheme.CYAN : AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas);
        }
    }

    // ONE DROPDOWN ROW: ITS ROW_* KIND, THE RESULT OF A RESULT ROW AND THE TEXT OF A HEAD OR HISTORY ROW
    private record Row(int kind, PlatformResult result, String text) {}

    // ZONE BETWEEN THE INPUT ROW AND THE FOOTER: THE RESULTS DROPDOWN COVERS THE STATUS CHIP, MATCHES
    // THE INPUT FIELD'S WIDTH AND STOPS 10 PX ABOVE THE FOOTER
    private final class MiddleZone extends Group<MiddleZone> {
        private MiddleZone() {
            this.add(OpenMultimediaScreen.this.chip); // ADDED FIRST SO IT PAINTS UNDER THE DROPDOWN
            this.add(OpenMultimediaScreen.this.dropdown);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            OpenMultimediaScreen.this.chip.measure(innerAvailWidth, 24);
            OpenMultimediaScreen.this.dropdown.measure(Math.max(0, innerAvailWidth - 232), Math.max(0, innerAvailHeight - 12));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            OpenMultimediaScreen.this.chip.layout(this.left, this.top + 12);
            OpenMultimediaScreen.this.dropdown.layout(this.left, this.top + 2);
        }
    }

    // DETECTED-STATUS CHIP: TINTED FILL, OUTLINE, SQUARE PIP AND LABEL
    private static final class StatusChip extends Element<StatusChip> {
        private String label = "";
        private Color color = AppTheme.TEXT_FAINT;

        private void set(final String label, final Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.contentWidth = (this.ctx != null && this.ctx.text != null
                    ? this.ctx.text.width(this.label, AppTheme.TEXT_BODY) : 0) + 34;
            this.contentHeight = 24;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            canvas.fill(x, y, w, h, AppTheme.alpha(this.color, this.color == AppTheme.TEXT_FAINT ? 24 : 36));
            canvas.stroke(x, y, w, h, this.color, 1f);
            canvas.fill(x + 8, y + 8, 8, 8, this.color);
            canvas.glow(x + 8, y + 8, 8, 8, 0f, this.color, 0.5f);
            canvas.text(this.label, x + 24, y + 5, this.color, AppTheme.TEXT_BODY, false);
        }
    }

    // ==========================================================================
    // NESTED TYPES: PREVIEW PANEL (TV FRAME, CRT, STATE CONTENT, QUALITY CHIP)
    // ==========================================================================

    private final class PreviewPanel extends Group<PreviewPanel> {
        private final CrtOverlay crt = new CrtOverlay();

        private PreviewPanel() {
            this.add(this.crt);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.crt.measure(Math.max(0, innerAvailWidth - 12), Math.max(0, innerAvailHeight - 12));
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.crt.layout(this.left + 6, this.top + 6);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;

            // TV FRAME, ALWAYS FOCUSED, WITH THE INNER SCREEN WELL
            canvas.fill(x, y, w, h, AppTheme.BG_2);
            canvas.stroke(x, y, w, h, AppTheme.NEON, 2f);
            canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
            canvas.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
            canvas.stroke(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
            canvas.cube(x + 2, y + h - 12, 10);
            canvas.cube(x + w - 12, y + h - 12, 10);

            super.onDraw(canvas);
            // THE STATE TEXT AND THE QUALITY CHIP GO OVER THE CRT BAND IN A COMPOSITE TOO, AS IN A DIRECT FRAME
            canvas.raise();

            final int ix = x + 6;
            final int iy = y + 6;
            final int iw = w - 12;
            final int ih = h - 12;
            final MRL mrl = OpenMultimediaScreen.this.previewMRL; // READ ONCE PER DRAW: THE FIELD FLIPS OFF-THREAD
            if (mrl == null) {
                PixelIcon.draw("copy", ix + iw / 2 - 16, iy + ih / 2 - 32, 32, AppTheme.TEXT_FAINT);
                canvas.text("NO MEDIA", ix + iw / 2 - canvas.textWidth("NO MEDIA", AppTheme.TEXT_BODY, false) / 2,
                        iy + ih / 2 + 12, AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
            } else {
                final MRL.Status status = mrl.status();
                if (status == MRL.Status.FETCHING) {
                    canvas.text("LOADING", ix + 22, iy + ih - 48, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(OpenMultimediaScreen.this.previewUrl, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 24, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
                } else if (status != MRL.Status.LOADED) {
                    // ERROR, BLOCKED, EXPIRED OR FORGOTTEN: SHOW THE EXACT STATE
                    final Color color = Previews.color(OpenMultimediaScreen.this.previewMRL);
                    final String label = OpenMultimediaScreen.this.statusLabel();
                    PixelIcon.draw("warn", ix + iw / 2 - 16, iy + ih / 2 - 36, 32, color);
                    canvas.text(label, ix + iw / 2 - canvas.textWidth(label, AppTheme.TEXT_BUTTON, true) / 2,
                            iy + ih / 2 + 8, color, AppTheme.TEXT_BUTTON, true);
                } else {
                    final MRL.Source src = OpenMultimediaScreen.this.firstSource();
                    final Metadata meta = src != null ? src.metadata() : null;
                    final String title = meta != null && meta.title() != null ? meta.title() : OpenMultimediaScreen.this.previewTitle();
                    final String desc = meta != null && meta.description() != null ? meta.description() : OpenMultimediaScreen.this.previewUrl;
                    final String duration = meta != null && meta.durationMs() > 0 ? MediaSession.formatTime(meta.durationMs()) : "--:--";
                    canvas.gradientV(ix, iy + ih / 2f, iw, ih / 2f, GRADIENT_TOP, AppTheme.alpha(AppTheme.BG_1, 224));
                    canvas.text(canvas.text().truncateToWidth(title.toUpperCase(Locale.ROOT), iw - 44, AppTheme.TEXT_BUTTON, Font.BOLD),
                            ix + 22, iy + ih - 76, AppTheme.NEON_LIGHT, AppTheme.TEXT_BUTTON, true);
                    canvas.text(canvas.text().truncateToWidth(desc, iw - 44, AppTheme.TEXT_SUBTITLE),
                            ix + 22, iy + ih - 48, AppTheme.TEXT_SOFT, AppTheme.TEXT_SUBTITLE, false);
                    canvas.text(duration, ix + 22, iy + ih - 24, AppTheme.CYAN, AppTheme.TEXT_BODY, false);
                }
            }

            // BEST-QUALITY CHIP OVER THE FRAME'S TOP-RIGHT CORNER
            final String chipLabel = OpenMultimediaScreen.this.bestQuality();
            if (chipLabel != null) {
                final int chipW = canvas.textWidth(chipLabel, AppTheme.TEXT_BODY, false) + 18;
                final int chipX = x + w - chipW - 8;
                canvas.fill(chipX, y, chipW, 22, AppTheme.alpha(AppTheme.BG_1, 235));
                canvas.stroke(chipX, y, chipW, 22, AppTheme.NEON, 1f);
                canvas.glow(chipX, y, chipW, 22, 0f, AppTheme.NEON, 0.34f);
                canvas.text(chipLabel, chipX + 9, y + 5, AppTheme.NEON_LIGHT, AppTheme.TEXT_BODY, false);
            }
        }
    }
}
