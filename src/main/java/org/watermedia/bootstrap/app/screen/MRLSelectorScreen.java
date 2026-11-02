package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.MediaPlayer;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.util.MathUtil;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.bootstrap.app.AppContext;
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
import org.watermedia.bootstrap.app.element.SectionHead;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.PixelIcon;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Lists the media of the selected group with a filter field and a live preview of the selected entry,
 * opening it in the player once its MRL loads.
 */
public final class MRLSelectorScreen extends Screen {
    private static final long LOAD_TIMEOUT_MS = 30_000L;
    private static final int CORNER = 10; // AMBER CORNER TRIANGLE SIDE

    // CONSTANT LISTS SO keybinds() ALLOCATES NOTHING PER FRAME
    private static final List<Keybind> KEYS_LOADING = List.of(new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_DEFAULT = List.of(
            new Keybind("ARROWS", "Navigate"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Back"));

    private final Consumer<HomeScreen.Action> navigator;
    private volatile boolean loading;
    private volatile int loadGeneration;
    private long loadStartTime;
    private MediaCatalog.TestURI pendingUri;

    private Header header;
    private TextField search;
    private ListView<MediaCatalog.TestURI> list;
    private PreviewPane preview;
    // HEADER ROW, REFRESHED EACH FRAME WITH THE GROUP NAME, ITEM COUNT AND FAILED COUNT
    private SectionHead headerHead;
    private Badge headerFailed;
    // LOADING MODAL, BUILT LAZILY AND SHOWN WHILE AN MRL FETCH IS IN FLIGHT
    private Dialog loadDialog;
    private Text loadName;
    // FILTERED ITEMS BACKING THE ListView
    private List<MediaCatalog.TestURI> filtered = new ArrayList<>();

    // THUMBNAIL PLAYERS KEYED BY ENTRY NAME
    private final Map<String, MediaPlayer> thumbPlayers = new LinkedHashMap<>();
    private final Set<String> thumbAttempted = new HashSet<>();
    private final Set<String> thumbSubscribed = new HashSet<>();
    private final Set<String> groupSubscriptions = new HashSet<>();

    public MRLSelectorScreen(final TextRenderer text, final AppContext ctx, final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public void onEnter() {
        super.onEnter();
        this.stopLoad();
        this.groupSubscriptions.clear();
        this.thumbSubscribed.clear();
        this.search.value("").focused(false);
        this.applyFilter();
        this.list.selection(0);
        this.subscribeGroupMRLs();
    }

    @Override
    public void onExit() {
        this.stopLoad();
        this.releaseThumbs();
    }

    @Override
    public void releaseMedia() {
        // RENDER BACKEND HOT-SWAP: THE NEXT FRAME REBUILDS THE THUMBNAIL PLAYERS ON THE NEW ENGINE
        this.releaseThumbs();
    }

    @Override
    public List<Keybind> keybinds() {
        return this.loading ? KEYS_LOADING : KEYS_DEFAULT;
    }

    @Override
    public boolean continuous() {
        // CARET BLINK, LOADING DOTS AND ANIMATED THUMBNAILS
        return this.loading || this.textInputActive() || Previews.animating(this.thumbPlayers.values());
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE FIRST: A FOCUSED FILTER FIELD OR THE LOADING MODAL CONSUMES ITS OWN KEYS
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_SLASH -> this.search.focused(true).invalidate();
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                final MediaCatalog.TestURI uri = this.selectedUri();
                if (uri != null) {
                    final MRL mrl = this.mrlFor(uri);
                    if (regenerable(mrl)) this.reloadMRL(uri);
                    else this.handleSelect(uri);
                }
            }
            case GLFW_KEY_ESCAPE -> {
                this.ctx.media.clearGroupState();
                this.navigator.accept(HomeScreen.Action.BACK);
            }
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
        // THE FILTER FOCUSES ON HOVER, BLURS ON ENTER, ESC OR AN OPENED ROW, AND CLEARS ON ESC; BLURRED, THOSE KEYS DRIVE
        // THE LIST. A CLICK ON THE PREVIEW KEEPS IT FOCUSED
        this.search = new TextField()
                .placeholder("filter...")
                .unicode(true)
                .keepFocus(true)
                .maxLength(Integer.MAX_VALUE)
                .accent(AppTheme.NEON)
                .frame(AppTheme.BG_2, AppTheme.STROKE_BRIGHT, null, 1f, 1f)
                .focusGlow(0.24f, false)
                .caret(AppTheme.NEON_LIGHT, 2, true)
                .icon("search", AppTheme.TEXT_FAINT, 8)
                .focusOnHover(true)
                .onChange(v -> this.applyFilter())
                .onSubmit(() -> this.search.focused(false))
                .onEscape(() -> {
                    this.search.focused(false);
                    if (this.search.value().isEmpty()) return;
                    this.search.value("");
                    this.applyFilter();
                })
                .padding(new Spacing(0, 8, 0, 30))
                .width(Element.MAX_PARENT)
                .height(30);

        this.headerHead = new SectionHead().compact().weight(1f);
        this.headerFailed = new Badge().gravity(Gravity.CENTER);
        final Parent headRow = Parent.row()
                .spacing(8)
                .width(Element.MAX_PARENT)
                .height(SectionHead.COMPACT_HEIGHT)
                .add(this.headerHead)
                .add(this.headerFailed);

        this.list = new ListView<MediaCatalog.TestURI>()
                .rowHeight(60)
                .spacing(6)
                .rowFactory((uri, index) -> {
                    final ListRow row = this.mediaRow(uri);
                    // selectOnHover MOVES THE SELECTION; CHIME WHEN IT LANDS ON A NEW ROW
                    row.onHover(v -> { if (!v.selected()) this.ctx.sound.play(); });
                    return row;
                })
                .onSelect((uri, index) -> {
                    this.search.focused(false);
                    this.handleSelect(uri);
                })
                .selectOnHover(true)
                // ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .width(Element.MAX_PARENT)
                .weight(1f);

        final Parent leftCol = Parent.column()
                .spacing(8)
                .padding(new Spacing(8, 4, 8, 10))
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.search)
                .add(headRow)
                .add(this.list);

        this.preview = new PreviewPane();
        this.header = new Header().name("Select media").right("v" + WaterMedia.VERSION);
        return Parent.column()
                .size(Element.MAX_PARENT, Element.MAX_PARENT)
                .add(this.header)
                .add(new SelectorPane(leftCol).width(Element.MAX_PARENT).weight(1f));
    }

    @Override
    protected void onUpdate() {
        if (this.search == null || this.ctx == null || this.ctx.media.selectedGroup == null) return;
        if (this.loading) {
            this.checkLoadingState();
            // THE WAIT ENDED THIS FRAME: SKIP THE REBUILDS SO NEW THUMBNAIL PLAYERS DO NOT UNDO onExit'S RELEASE
            if (!this.loading) return;
            if (this.loadDialog != null) this.loadDialog.title("Loading" + ".".repeat((int) ((System.currentTimeMillis() / 500L) % 4L)));
        }
        this.subscribeGroupMRLs();
        this.updateThumbs(this.activeThumbNames());
        this.header.sub(this.ctx.media.selectedGroup.name());
        this.headerHead.label(this.ctx.media.selectedGroup.name()).count(this.filtered.size() + " ITEMS");
        int failed = 0;
        for (final MRL mrl: this.ctx.media.groupMRLs.values()) {
            if (Previews.failed(mrl)) failed++;
        }
        this.headerFailed.label(failed + " FAILED").color(failed > 0 ? AppTheme.RED : AppTheme.TEXT_FAINT);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // KEEPS THE SAME ITEM SELECTED WHEN IT SURVIVES THE FILTER
    private void applyFilter() {
        final MediaCatalog.TestURI prev = this.selectedUri();
        final List<MediaCatalog.TestURI> next = new ArrayList<>();
        if (this.ctx.media.selectedGroup != null) {
            final String q = this.search.value() == null ? "" : this.search.value().trim().toLowerCase(Locale.ROOT);
            for (final MediaCatalog.TestURI uri: this.ctx.media.selectedGroup.uris())
                if (q.isEmpty() || uri.name().toLowerCase(Locale.ROOT).contains(q) || uri.uri().toLowerCase(Locale.ROOT).contains(q))
                    next.add(uri);
        }
        this.filtered = next;
        this.list.items(next);
        final int idx = prev == null ? 0 : next.indexOf(prev);
        this.list.selection(Math.max(0, idx));
    }

    // THE SELECTED ROW, OR THE GROUP'S FIRST ITEM WHEN THE FILTER HIDES EVERYTHING
    private MediaCatalog.TestURI selectedUri() {
        if (this.ctx.media.selectedGroup == null) return null;
        final MediaCatalog.TestURI[] uris = this.ctx.media.selectedGroup.uris();
        if (!this.filtered.isEmpty()) {
            final int i = this.list.selectedIndex();
            if (i >= 0 && i < this.filtered.size()) return this.filtered.get(i);
        }
        return uris.length > 0 ? uris[0] : null;
    }

    private void subscribeGroupMRLs() {
        if (this.ctx.media.selectedGroup == null) return;
        for (final MediaCatalog.TestURI uri: this.ctx.media.selectedGroup.uris()) {
            final MRL mrl = this.ctx.media.groupMRLs.get(uri.name());
            if (mrl != null && !Previews.loaded(mrl) && this.groupSubscriptions.add(uri.name()))
                mrl.subscribe(done -> this.ctx.requestRender());
        }
    }

    // CREATES AND SUBSCRIBES A MISSING MRL; CALL IT FROM onUpdate OR INPUT, NEVER FROM onDraw
    private MRL mrlFor(final MediaCatalog.TestURI uri) {
        if (uri == null) return null;
        MRL mrl = this.ctx.media.groupMRLs.get(uri.name());
        if (mrl != null) return mrl;
        mrl = PlayerAPI.mrl(uri.uri());
        this.ctx.media.groupMRLs.put(uri.name(), mrl);
        if (!Previews.loaded(mrl) && this.groupSubscriptions.add(uri.name())) mrl.subscribe(done -> this.ctx.requestRender());
        this.ctx.requestRender();
        return mrl;
    }

    // READ-ONLY LOOKUP FOR onDraw; mrlFor CREATES MISSING MRLS DURING onUpdate
    private MRL mrlOf(final MediaCatalog.TestURI uri) {
        return uri == null ? null : this.ctx.media.groupMRLs.get(uri.name());
    }

    private void scheduleLoadTimeout(final int generation) {
        final long deadline = this.loadStartTime + LOAD_TIMEOUT_MS;
        ThreadTool.thread("MRLSelectorScreen-LoadTimeout", () -> {
            final long wait = Math.max(0L, deadline - System.currentTimeMillis());
            ThreadTool.sleep(wait);
            if (this.loading && this.loadGeneration == generation) this.ctx.requestRender();
        });
    }

    // KEEPS THUMBNAIL PLAYERS ONLY FOR THE ENTRIES ON SCREEN
    private void updateThumbs(final Set<String> activeNames) {
        if (this.ctx.media.selectedGroup == null) return;

        // RELEASE THE PLAYERS OF ROWS THAT LEFT THE SCREEN; Previews.release NEEDS THEM OUT OF THE MAP BEFORE IT FREES
        // THEM OFF-THREAD
        List<MediaPlayer> evicted = null;
        final Iterator<Map.Entry<String, MediaPlayer>> it = this.thumbPlayers.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<String, MediaPlayer> entry = it.next();
            if (activeNames.contains(entry.getKey())) continue;
            if (evicted == null) evicted = new ArrayList<>();
            evicted.add(entry.getValue());
            it.remove();
            this.thumbAttempted.remove(entry.getKey());
        }
        if (evicted != null) Previews.release(this.ctx, evicted);

        // CREATE THE MISSING ONES
        for (final MediaCatalog.TestURI uri: this.ctx.media.selectedGroup.uris()) {
            final String name = uri.name();
            if (!activeNames.contains(name)) continue;
            if (this.thumbPlayers.containsKey(name)) continue;
            if (this.thumbAttempted.contains(name)) continue;

            final MRL mrl = this.mrlFor(uri);
            if (mrl == null) continue;
            if (!Previews.loaded(mrl)) continue;

            final var sources = mrl.sources();
            if (sources.isEmpty()) {
                this.thumbAttempted.add(name);
                continue;
            }

            MediaPlayer player = null;
            boolean pendingThumbnail = false;

            // TRY THE THUMBNAIL URI FIRST
            for (final MRL.Source src: sources) {
                final URI thumbnailUri = src.thumbnail();
                if (thumbnailUri == null) continue;
                final MRL thumbnailMrl = PlayerAPI.mrl(thumbnailUri.toString());
                final MRL.Status thumbStatus = thumbnailMrl.status();
                if (thumbStatus == MRL.Status.FETCHING) {
                    pendingThumbnail = true;
                    if (!Previews.loaded(thumbnailMrl) && this.thumbSubscribed.add(thumbnailUri.toString()))
                        thumbnailMrl.subscribe(done -> this.ctx.requestRender());
                    break;
                }
                if (thumbStatus != MRL.Status.LOADED) continue;
                player = PlayerAPI.player(thumbnailMrl, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                if (player != null) break;
            }

            if (pendingThumbnail) continue; // RETRY NEXT FRAME

            // FALLBACK: THE FIRST IMAGE SOURCE ITSELF
            if (player == null) {
                for (int i = 0; i < sources.size(); i++) {
                    if (sources.get(i).type() == MediaType.IMAGE) {
                        player = PlayerAPI.player(mrl, i, RenderSystem.mediaEngineSupplier(Thread.currentThread(), this.ctx), () -> null);
                        break;
                    }
                }
            }

            this.thumbAttempted.add(name);

            if (player != null) {
                player.repeat(true);
                player.start();
                this.thumbPlayers.put(name, player);
            }
        }
    }

    private void releaseThumbs() {
        if (!this.thumbPlayers.isEmpty()) {
            // SNAPSHOT AND CLEAR FIRST SO THE RENDER THREAD CAN NEVER TOUCH A RELEASING PLAYER
            final List<MediaPlayer> players = new ArrayList<>(this.thumbPlayers.values());
            this.thumbPlayers.clear();
            Previews.release(this.ctx, players);
        }
        this.thumbAttempted.clear();
        this.thumbSubscribed.clear();
        this.groupSubscriptions.clear();
    }

    private void handleSelect(final MediaCatalog.TestURI uri) {
        this.ctx.media.selectedMRLName = uri.name();
        final MRL mrl = this.mrlFor(uri);
        this.ctx.media.selectedMRL = mrl;

        if (mrl == null) {
            this.ctx.showError("Null", "The MRL is null", null);
            return;
        }

        switch (mrl.status()) {
            case LOADED -> {
                if (this.ctx.media.selectSources()) this.navigator.accept(HomeScreen.Action.PLAYER);
            }
            case FETCHING -> {
                this.loading = true;
                this.loadGeneration++;
                this.loadStartTime = System.currentTimeMillis();
                this.pendingUri = uri;
                mrl.subscribe(done -> this.ctx.requestRender());
                this.scheduleLoadTimeout(this.loadGeneration);
                this.showLoadDialog();
                this.ctx.requestRender();
            }
            case ERROR -> this.ctx.showError("Error", "Unable to open media, exception occurred on opening", null);
            case BLOCKED -> this.ctx.showError("Blocked", "This media was blocked by the platform", null);
            // EXPIRED OR CACHE-EVICTED MRLS ARE UNUSABLE: DROP THE CACHED ONE SO THE NEXT ACCESS REGENERATES IT
            case EXPIRED, FORGOTTEN -> this.ctx.showError("MRL expired", "Re-freshing MRL", () -> {
                this.ctx.media.selectedMRL = null;
                this.ctx.media.groupMRLs.remove(uri.name());
            });
        }
    }

    private void reloadMRL(final MediaCatalog.TestURI uri) {
        if (uri == null) return;
        final String name = uri.name();
        MRL mrl = this.ctx.media.groupMRLs.get(name);
        mrl = mrl == null ? PlayerAPI.mrl(uri.uri()) : mrl.reload();
        this.ctx.media.groupMRLs.put(name, mrl);

        final MediaPlayer thumbnail = this.thumbPlayers.remove(name);
        if (thumbnail != null) Previews.release(this.ctx, List.of(thumbnail));
        this.thumbAttempted.remove(name);
        this.groupSubscriptions.remove(name);
        if (!Previews.loaded(mrl) && this.groupSubscriptions.add(name)) mrl.subscribe(done -> this.ctx.requestRender());
        this.ctx.media.selectedMRLName = name;
        this.ctx.media.selectedMRL = mrl;
        this.ctx.requestRender();
    }

    // THE GENERATION BUMP DISARMS THE PENDING TIMEOUT THREAD
    private void stopLoad() {
        this.loading = false;
        this.loadGeneration++;
        this.pendingUri = null;
        if (this.loadDialog != null) this.hideDialog(this.loadDialog);
    }

    private void showLoadDialog() {
        if (this.loadDialog == null) {
            this.loadName = new Text().scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT);
            this.loadDialog = new Dialog()
                    .accent(AppTheme.NEON)
                    .title("Loading")
                    .panelWidth(400)
                    .onDismiss(this::stopLoad); // ESC CANCELS THE WAIT
            this.loadDialog.content(this.loadName);
            this.loadDialog.content(new Text("ESC to cancel").scale(AppTheme.TEXT_BODY).color(AppTheme.TEXT_SOFT).width(Element.MAX_PARENT));
        }
        this.loadName.text(this.pendingUri == null ? "" : this.pendingUri.name());
        this.hideDialog(this.loadDialog); // IDEMPOTENT RE-SHOW
        this.showDialog(this.loadDialog);
    }

    private void checkLoadingState() {
        if (this.ctx.media.selectedMRL == null) {
            this.stopLoad();
            return;
        }

        final MRL.Status status = this.ctx.media.selectedMRL.status();
        if (status == MRL.Status.LOADED) {
            this.stopLoad();
            if (this.ctx.media.selectSources()) this.navigator.accept(HomeScreen.Action.PLAYER);
            return;
        }

        // ANY OTHER FINAL STATE (ERROR, BLOCKED, EXPIRED, FORGOTTEN) ENDS THE WAIT
        if (status != MRL.Status.FETCHING) {
            this.stopLoad();
            this.ctx.showError("Error", "Unable to open media: " + status.name(), null);
            return;
        }

        if (System.currentTimeMillis() - this.loadStartTime >= LOAD_TIMEOUT_MS) {
            this.stopLoad();
            this.ctx.showError("Load Error", "MRL loading timed out", null);
        }
    }

    // THE SELECTED ITEM PLUS EVERY ROW ON SCREEN IN THE PREVIOUS FRAME'S LAYOUT
    private Set<String> activeThumbNames() {
        final Set<String> active = new LinkedHashSet<>();
        final MediaCatalog.TestURI selected = this.selectedUri();
        if (selected != null) active.add(selected.name());
        final int viewTop = this.list.top();
        final int viewBottom = viewTop + this.list.measuredHeight();
        for (final Element<?> child: this.list.children()) {
            if (!(child.tag() instanceof final MediaCatalog.TestURI uri) || child.measuredHeight() <= 0) continue;
            final int rowTop = child.top();
            if (rowTop + child.measuredHeight() > viewTop && rowTop < viewBottom) active.add(uri.name());
        }
        return active;
    }

    // ONE MEDIA ROW: A TV FRAME WITH THE THUMBNAIL AND ITS MINI CRT, THE NAME OVER THE URI, AND THE STATUS PIP RIGHT
    private ListRow mediaRow(final MediaCatalog.TestURI uri) {
        final String name = uri.name().toUpperCase(Locale.ROOT);
        return new ListRow()
                .fill(null, null, AppTheme.alpha(AppTheme.NEON, 26))
                .outline(null, AppTheme.NEON, 1f, 1f)
                .selectedGlow(AppTheme.NEON, 0.20f, false)
                .lead((canvas, row, x, y, w, h) -> {
                    tvFrame(canvas, x + 6, y + 8, 70, 46, row.isPicked());
                    this.drawThumb(canvas, uri, x + 12, y + 14, 58, 34, true);
                })
                .title(name)
                .titleStyle(AppTheme.TEXT_BUTTON, true, AppTheme.TEXT, AppTheme.NEON_LIGHT)
                .textAt(88, 12)
                // THE TEXTS STOP 14 PX BEFORE THE PIP, WHICH SITS 19 PX FROM THE RIGHT EDGE
                .textRight(33, 40)
                .subtitle(uri::uri, AppTheme.TEXT_SUBTITLE, AppTheme.TEXT_FAINT, 34)
                .trail((canvas, row, x, y, w, h) -> pip(canvas, x + w - 19, y + 25, 8, Previews.color(this.mrlOf(uri)), false))
                .place(new CrtOverlay().size(58, 34), 12, 14)
                .tag(uri);
    }

    // LIVE FRAME (COVER-FIT AND CLIPPED) OR A STATUS FALLBACK; THE CALLER'S CrtOverlay CHILD PAINTS OVER IT
    private void drawThumb(final Canvas canvas, final MediaCatalog.TestURI uri, final int x, final int y,
                           final int w, final int h, final boolean mini) {
        final MediaPlayer player = this.thumbPlayers.get(uri.name());
        if (player != null && player.texture() != 0 && player.width() > 0 && player.height() > 0) {
            canvas.pushClip(x, y, w, h);
            final float imgAspect = (float) player.width() / player.height();
            final float boxAspect = (float) w / h;
            float bw = w;
            float bh = h;
            float bx = x;
            float by = y;
            if (imgAspect > boxAspect) {
                bw = h * imgAspect;
                bx = x + (w - bw) / 2f;
            } else {
                bh = w / imgAspect;
                by = y + (h - bh) / 2f;
            }
            canvas.mediaImage(player.texture(), bx, by, bw, bh);
            canvas.popClip();
            return;
        }
        final MRL mrl = this.mrlOf(uri);
        final MRL.Status status = mrl == null ? null : mrl.status();
        final boolean ready = status == MRL.Status.LOADED;
        // ERROR, BLOCKED, EXPIRED OR FORGOTTEN: A FINAL STATE THAT NEEDS REGENERATION
        final boolean failed = status != null && status != MRL.Status.LOADED && status != MRL.Status.FETCHING;
        canvas.fill(x, y, w, h, AppTheme.BG_0);
        if (mini && !failed) {
            if (ready) {
                final float okScale = AppTheme.TEXT_TINY;
                canvas.text("[OK]", x + (w - canvas.textWidth("[OK]", okScale, false)) / 2,
                        y + (h - canvas.textHeight(okScale, false)) / 2f, AppTheme.GREEN, okScale, false);
            }
            return;
        }
        final String label = failed ? (mini ? "[" + statusLabel(mrl) + "]" : statusLabel(mrl))
                : ready ? "[ media thumbnail ]" : "LOADING...";
        final Color color = failed ? Previews.color(mrl) : ready ? AppTheme.TEXT_SOFT : AppTheme.NEON;
        if (!mini && failed) PixelIcon.draw("warn", x + w / 2 - 14, y + h / 2 - 36, 28, Previews.color(mrl));
        final float scale = mini ? AppTheme.TEXT_TINY : AppTheme.TEXT_BUTTON;
        final float textY = mini
                ? y + (h - canvas.textHeight(scale, false)) / 2f
                : y + h / 2f - this.text.lineHeight(scale) / 2f + 22;
        canvas.text(label, x + (w - canvas.textWidth(label, scale, false)) / 2, textY, color, scale, false);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // TV FRAME: OUTER PLATE, FOCUS BORDER AND GLOW, INNER SCREEN WELL AND AMBER FOOT CUBES
    private static void tvFrame(final Canvas canvas, final int x, final int y, final int w, final int h, final boolean focused) {
        canvas.fill(x, y, w, h, AppTheme.BG_2);
        canvas.stroke(x, y, w, h, focused ? AppTheme.NEON : AppTheme.STROKE_BRIGHT, 2f);
        if (focused) canvas.glow(x, y, w, h, 0f, AppTheme.NEON, 0.48f);
        canvas.fill(x + 6, y + 6, w - 12, h - 12, AppTheme.BG_0);
        canvas.stroke(x + 6, y + 6, w - 12, h - 12, AppTheme.STROKE, 1f);
        canvas.cube(x + 2, y + h - 12, 10);
        canvas.cube(x + w - 12, y + h - 12, 10);
    }

    // AMBER CORNER TRIANGLE, RASTERIZED AS 1 PX ROWS BECAUSE Canvas HAS NO TRIANGLE PRIMITIVE
    private static void triangle(final Canvas canvas, final int x, final int y, final boolean left) {
        canvas.glow(x, y, CORNER, CORNER, 0f, AppTheme.AMBER, 0.32f);
        for (int i = 0; i < CORNER; i++) {
            if (left) canvas.fill(x, y + i, CORNER - i, 1f, AppTheme.AMBER);
            else canvas.fill(x + CORNER - 1 - i, y + i, i + 1, 1f, AppTheme.AMBER);
        }
    }

    // STATUS PIP; THE CIRCLE IS RASTERIZED AS 1 PX ROWS BECAUSE Canvas HAS NO CIRCLE PRIMITIVE
    private static void pip(final Canvas canvas, final int x, final int y, final int size, final Color color, final boolean circle) {
        if (circle) {
            final float r = size / 2f;
            for (int i = 0; i < size; i++) {
                final float dy = i + 0.5f - r;
                final float half = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
                canvas.fill(x + r - half, y + i, half * 2f, 1f, color);
            }
        } else {
            canvas.fill(x, y, size, size, color);
        }
        canvas.glow(x, y, size, size, 0f, color, 0.5f);
    }

    // regenerable AND statusLabel ARE NULL-SAFE UI WRAPPERS OVER THE MRL.Status PREDICATES
    private static boolean regenerable(final MRL mrl) {
        return mrl != null && mrl.status().regenerable();
    }

    private static String statusLabel(final MRL mrl) {
        return mrl == null ? "NULL" : Previews.label(mrl);
    }

    // RUNS EVERY FRAME IN THE PREVIEW DRAW, SO IT AVOIDS STREAMS
    private static String bestQuality(final MRL mrl) {
        if (!Previews.loaded(mrl)) return "UNKNOWN";
        MediaQuality best = MediaQuality.UNKNOWN;
        for (final MRL.Source source: mrl.sources()) {
            for (final MediaQuality q: source.qualities().keySet()) {
                if (q.height() > best.height()) best = q;
            }
        }
        return best.name();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // SPLITS THE CONTENT BAND INTO THE FILTER AND LIST COLUMN AND THE PREVIEW STACK, WITH 10 PX GAPS ABOVE
    // AND BELOW AND A LEFT COLUMN A THIRD OF THE WIDTH, CLAMPED TO 320-380 PX
    private final class SelectorPane extends Group<SelectorPane> {
        private final Parent leftCol;
        private int leftW;
        private int listH;

        private SelectorPane(final Parent leftCol) {
            this.leftCol = leftCol;
            this.add(leftCol);
            this.add(MRLSelectorScreen.this.preview);
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            this.leftW = MathUtil.clamp(innerAvailWidth / 3, 320, 380);
            this.listH = Math.max(0, innerAvailHeight - 20);
            this.leftCol.measure(this.leftW, this.listH);
            if (MRLSelectorScreen.this.preview.visible())
                MRLSelectorScreen.this.preview.measure(Math.max(0, innerAvailWidth - this.leftW - 36), this.listH);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.leftCol.layout(this.innerLeft(), this.innerTop() + 10);
            if (MRLSelectorScreen.this.preview.visible()) MRLSelectorScreen.this.preview.layout(this.innerLeft() + this.leftW + 18, this.innerTop() + 10);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // LEFT COLUMN PLATE AND DIVIDER, ONLY WHEN THE GROUP HAS ITEMS
            if (MRLSelectorScreen.this.ctx.media.selectedGroup != null && MRLSelectorScreen.this.ctx.media.selectedGroup.uris().length > 0) {
                final int y = this.top + 10;
                canvas.fill(this.left, y, this.leftW, this.listH, AppTheme.alpha(AppTheme.BG_1, 150));
                canvas.line(this.left + this.leftW, y, this.left + this.leftW, y + this.listH, AppTheme.STROKE_BRIGHT, 1f);
            }
            super.onDraw(canvas);
        }
    }

    // PREVIEW STACK: TV FRAME WITH THE LIVE THUMBNAIL UNDER A CRT OVERLAY, THEN THE METADATA PANEL
    // WITH THE COPY AND PLAY BUTTONS
    private final class PreviewPane extends Group<PreviewPane> {
        private final CrtOverlay crt = new CrtOverlay();
        private final Button copyBtn;
        private final Button playBtn;
        private int tvY;
        private int tvH;
        private int panelY;
        private int panelH;

        private PreviewPane() {
            this.width = MAX_PARENT;
            this.height = MAX_PARENT;
            this.copyBtn = new Button("COPY LINK").icon("copy")
                    .accent(AppTheme.NEON_LIGHT).textColor(AppTheme.NEON_LIGHT)
                    .size(154, 38)
                    .onClick(b -> {
                        final MediaCatalog.TestURI sel = MRLSelectorScreen.this.selectedUri();
                        if (sel != null) {
                            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sel.uri()), null);
                            MRLSelectorScreen.this.ctx.sound.play();
                        }
                    });
            this.playBtn = new Button("PLAY").icon("play").subText("ENTER")
                    .accent(AppTheme.GREEN).textColor(AppTheme.GREEN)
                    .size(130, 38)
                    .onClick(b -> {
                        final MediaCatalog.TestURI sel = MRLSelectorScreen.this.selectedUri();
                        if (sel != null) {
                            final MRL mrl = MRLSelectorScreen.this.mrlFor(sel);
                            if (regenerable(mrl)) MRLSelectorScreen.this.reloadMRL(sel);
                            else MRLSelectorScreen.this.handleSelect(sel);
                        }
                    });
            this.add(this.crt);
            this.add(this.copyBtn);
            this.add(this.playBtn);
        }

        @Override
        protected void onUpdate() {
            final MediaCatalog.URIGroup group = MRLSelectorScreen.this.ctx == null ? null : MRLSelectorScreen.this.ctx.media.selectedGroup;
            final MediaCatalog.TestURI sel = group != null && group.uris().length > 0 ? MRLSelectorScreen.this.selectedUri() : null;
            this.visible = sel != null;
            if (sel == null) return;
            final MRL mrl = MRLSelectorScreen.this.mrlFor(sel);
            final boolean regen = regenerable(mrl);
            final String playLabel = regen ? "RELOAD" : "PLAY";
            final String playIcon = regen ? "reload" : "play";
            final Color playColor = regen ? AppTheme.NEON_LIGHT : AppTheme.GREEN;
            this.copyBtn.enabled(mrl != null);
            this.playBtn.label(playLabel).icon(playIcon)
                    .accent(playColor).textColor(playColor)
                    .enabled(regen || Previews.loaded(mrl))
                    .width(Math.max(130, Button.width(MRLSelectorScreen.this.text, playLabel, "ENTER", playIcon, 12)));
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // 16 PX GAP BETWEEN TV AND PANEL, 18 PX STACK MARGINS ABOVE AND BELOW
            this.panelH = MathUtil.clamp(innerAvailHeight / 5, 96, 112);
            this.tvH = Math.max(220, innerAvailHeight - this.panelH - 16 - 36);
            this.crt.measure(Math.max(0, innerAvailWidth - 16), Math.max(0, this.tvH - 16));
            this.copyBtn.measure(innerAvailWidth, innerAvailHeight);
            this.playBtn.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int stackH = this.tvH + 16 + this.panelH;
            this.tvY = this.top + Math.max(0, (this.measuredHeight - stackH) / 2);
            this.panelY = this.tvY + this.tvH + 16;
            this.crt.layout(this.left + 8, this.tvY + 8);
            final int playX = this.left + this.measuredWidth - this.playBtn.measuredWidth() - 18;
            this.playBtn.layout(playX, this.panelY + 34);
            this.copyBtn.layout(playX - 166, this.panelY + 34);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final MediaCatalog.TestURI sel = MRLSelectorScreen.this.selectedUri();
            if (sel == null) return;
            final int x = this.left;
            final int w = this.measuredWidth;
            tvFrame(canvas, x, this.tvY, w, this.tvH, true);
            MRLSelectorScreen.this.drawThumb(canvas, sel, x + 8, this.tvY + 8, w - 16, this.tvH - 16, false);

            // METADATA PANEL PLATE AND AMBER CORNER TRIANGLES
            canvas.gradientV(x, this.panelY, w, this.panelH,
                    AppTheme.alpha(AppTheme.BG_2, 217), AppTheme.alpha(AppTheme.BG_1, 217));
            canvas.stroke(x, this.panelY, w, this.panelH, AppTheme.STROKE_BRIGHT, 1f);
            triangle(canvas, x - 1, this.panelY - 1, true);
            triangle(canvas, x + w - CORNER + 1, this.panelY + this.panelH - CORNER + 1, false);

            final MRL mrl = MRLSelectorScreen.this.mrlOf(sel);
            final String title = MRLSelectorScreen.this.text.truncateToWidth(sel.name().toUpperCase(Locale.ROOT), w - 410, AppTheme.TEXT_SECTION, Font.BOLD);
            canvas.text(title, x + 16, this.panelY + 14, AppTheme.NEON_LIGHT, AppTheme.TEXT_SECTION, true);
            final MediaType type = Previews.loaded(mrl) && !mrl.sources().isEmpty() ? mrl.sources().get(0).type() : null;
            if (type != null) {
                // MEDIA-TYPE TAG IN THE TYPE HUE
                final Color tc = type == MediaType.IMAGE ? AppTheme.GREEN
                        : type == MediaType.VIDEO ? AppTheme.AMBER
                        : type == MediaType.AUDIO ? AppTheme.CYAN
                        : AppTheme.TEXT_FAINT;
                final int tagX = x + 28 + MRLSelectorScreen.this.text.widthBold(title, AppTheme.TEXT_SECTION);
                final int tagY = this.panelY + 12;
                final int tagW = MRLSelectorScreen.this.text.width(type.name(), AppTheme.TEXT_BODY) + 22;
                canvas.fill(tagX, tagY, tagW, 22, AppTheme.alpha(AppTheme.BG_1, 188));
                canvas.stroke(tagX, tagY, tagW, 22, tc, 1f);
                canvas.glow(tagX, tagY, tagW, 22, 0f, tc, 0.16f);
                canvas.text(type.name(), tagX + 11,
                        tagY + Math.max(0, (22 - MRLSelectorScreen.this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2),
                        tc, AppTheme.TEXT_BODY, false);
            }
            canvas.text(MRLSelectorScreen.this.text.truncateToWidth(sel.uri(), w - 270, AppTheme.TEXT_BODY),
                    x + 16, this.panelY + 42, AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY, false);

            final Color sc = Previews.color(mrl);
            final int pipY = this.panelY + 72;
            pip(canvas, x + 18, pipY, 10, sc, true);
            canvas.text(bestQuality(mrl) + " - " + statusLabel(mrl), x + 36,
                    pipY + (10 - MRLSelectorScreen.this.text.glyphHeight(AppTheme.TEXT_BODY)) / 2f, sc, AppTheme.TEXT_BODY, false);
            super.onDraw(canvas); // CRT OVER THE THUMBNAIL, THEN THE COPY/PLAY BUTTONS
        }
    }
}
