package org.watermedia.bootstrap.app.screen;

import org.watermedia.WaterMedia;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.MediaCatalog;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListRow;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.SectionHead;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Region selector for IPTV channels: a {@link Header} over a panel holding a {@link ListView} of region rows,
 * which owns scrolling, hit-testing and selection.
 */
public final class RegionSelectorScreen extends Screen {
    private static final int ROW_H = 54;
    private static final int ROW_GAP = 8;
    // CONSTANT SO THE PER-FRAME keybinds() POLL OF KeybindsBar ALLOCATES NOTHING
    private static final List<Keybind> KEYS = List.of(
            new Keybind("UP/DOWN", "Region"), new Keybind("ENTER", "Select"), new Keybind("ESC", "Back"));

    private final Consumer<HomeScreen.Action> navigator;
    private final List<String> regions = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int totalChannels;
    private String detectedRegion = "UNKNOWN";

    private ListView<Integer> list;

    public RegionSelectorScreen(final TextRenderer text, final AppContext ctx,
                                final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public void onEnter() {
        super.onEnter();
        this.rebuildRegions();
        final List<Integer> options = new ArrayList<>();
        for (int i = 0; i < this.optionCount(); i++) options.add(i);
        this.list.items(options);
        this.list.selection(0);
    }

    @Override
    public List<Keybind> keybinds() {
        return KEYS;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // THE TREE (LIST ROWS, DIALOGS) GETS THE KEY FIRST; SCREEN NAVIGATION ONLY ON UNCONSUMED RELEASES
        if (super.dispatchKey(key, action)) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> this.openSelected();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
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
        this.list = new ListView<Integer>()
                .rowHeight(ROW_H)
                .spacing(ROW_GAP)
                .rowFactory((index, pos) -> this.regionRow(index))
                .onSelect((index, pos) -> this.openSelected())
                .selectOnHover(true)
                // THE ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .width(Element.MAX_PARENT)
                .height(Element.MAX_PARENT);
        return Parent.column()
                .width(Element.MAX_PARENT)
                .height(Element.MAX_PARENT)
                .add(new Header().name("Television").sub("region selector").right("v" + WaterMedia.VERSION))
                .add(new PanelPane().width(Element.MAX_PARENT).weight(1f).add(this.list));
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void rebuildRegions() {
        this.regions.clear();
        this.counts.clear();
        this.totalChannels = 0;
        final TreeSet<String> available = new TreeSet<>();
        for (final MediaCatalog.IptvChannel channel: this.ctx.catalog.iptvChannels) {
            if (channel == null || blank(channel.url())) continue;
            this.totalChannels++;
            final String region = cleanRegion(channel.region());
            if (region.isEmpty()) continue;
            available.add(region);
            this.counts.merge(region, 1, Integer::sum);
        }
        this.detectedRegion = this.detectRegion(available);
        for (final String region: available) {
            if (!region.equalsIgnoreCase(this.detectedRegion)) this.regions.add(region);
        }
    }

    private void openSelected() {
        final String region = this.regionFor(this.list.selectedIndex());
        final List<MediaCatalog.IptvChannel> channels = new ArrayList<>();
        for (final MediaCatalog.IptvChannel channel: this.ctx.catalog.iptvChannels) {
            if (channel == null || blank(channel.url())) continue;
            if (region == null || region.equalsIgnoreCase(cleanRegion(channel.region()))) channels.add(channel);
        }
        if (channels.isEmpty()) {
            this.ctx.showError("No Channels", "No IPTV channels were found for the selected region.", null);
            return;
        }

        final Set<String> names = new HashSet<>();
        final List<MediaCatalog.TestURI> uris = new ArrayList<>(channels.size());
        this.ctx.media.groupMRLs.clear();
        for (final MediaCatalog.IptvChannel channel: channels) {
            // UNIQUE ROW NAME: THE CHANNEL NAME (OR TVG ID, OR URL), THEN ITS GROUP, THEN A COPY NUMBER
            final String base = (blank(channel.name()) ? blank(channel.tvgId()) ? channel.url() : channel.tvgId() : channel.name()).trim();
            String name = base;
            if (names.contains(name) && !blank(channel.group())) name = base + " - " + channel.group().trim();
            int copy = 2;
            while (!names.add(name)) name = base + " #" + copy++;
            uris.add(new MediaCatalog.TestURI(name, channel.url(), false));
        }

        final String groupName = region == null ? "TELEVISION" : "TELEVISION " + region.toUpperCase(Locale.ROOT);
        this.ctx.media.selectedGroup = new MediaCatalog.URIGroup(groupName, uris.toArray(MediaCatalog.TestURI[]::new));
        this.ctx.sound.play();
        this.navigator.accept(HomeScreen.Action.MRL_SELECTOR);
    }

    private int optionCount() {
        // NO DETECTED REGION → NO SYSTEM ROW; OTHERWISE A DUPLICATE "GLOBAL" ROW WOULD APPEAR AT INDEX 1
        return (this.detectedRegion != null ? 2 : 1) + this.regions.size();
    }

    private String regionFor(final int index) {
        if (index == 0) return null;
        final int firstRegion = this.detectedRegion != null ? 2 : 1;
        if (this.detectedRegion != null && index == 1) return this.detectedRegion;
        // OUT-OF-RANGE (INCLUDING AN EMPTY regions LIST) MAPS TO GLOBAL RATHER THAN CLAMPING INTO THE LIST OR THROWING
        final int i = index - firstRegion;
        return i >= 0 && i < this.regions.size() ? this.regions.get(i) : null;
    }

    private String detectRegion(final Set<String> available) {
        final Locale locale = Locale.getDefault();
        final String language = locale.getLanguage();
        final String country = locale.getCountry();
        final String exact = language.isBlank() || country.isBlank() ? "" : language + "_" + country;
        if (available.contains(exact)) return exact;
        if (!country.isBlank()) {
            for (final String region: available) {
                if (region.endsWith("_" + country)) return region;
            }
        }
        // ONLY SURFACE A DETECTED REGION THAT ACTUALLY HAS CHANNELS; OTHERWISE FALL BACK TO GLOBAL (NULL)
        return null;
    }

    // ONE REGION ROW: INDEX 0 IS GLOBAL, THEN THE SYSTEM ROW WHEN A REGION WAS DETECTED, THEN THE REMAINING REGIONS;
    // THE CHANNEL COUNT TAG SITS RIGHT AND THE DETECTED REGION CARRIES A SYSTEM BADGE AFTER ITS NAME
    private ListRow regionRow(final int index) {
        return new ListRow()
                .fill(AppTheme.alpha(AppTheme.BG_2, 220), null, AppTheme.alpha(AppTheme.NEON_DARK, 82))
                .selectedGlow(AppTheme.GREEN, 0.26f, true)
                .icon("tv", 24, 16, ListRow.CENTER, AppTheme.NEON_LIGHT, AppTheme.GREEN)
                .title(() -> {
                    final String region = this.regionFor(index);
                    return index == 0 || region == null ? "GLOBAL" : region.toUpperCase(Locale.ROOT);
                })
                .titleStyle(AppTheme.TEXT_BUTTON, true, AppTheme.TEXT, AppTheme.GREEN)
                .textAt(52, ListRow.CENTER)
                .binder(row -> {
                    final boolean detected = index == 1 && this.detectedRegion != null;
                    final int tagW = Math.max(98, this.text.width(this.channelCount(index), AppTheme.TEXT_SUBTITLE) + 22);
                    row.outline(index == 0 || detected ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT, AppTheme.GREEN, 1.5f, 2f)
                            .textRight(tagW + (detected ? 116 : 40), 80);
                })
                .trail((canvas, row, x, y, w, h) -> {
                    final boolean sel = row.isPicked();
                    final String meta = this.channelCount(index);
                    final int tagW = Math.max(98, canvas.textWidth(meta, AppTheme.TEXT_SUBTITLE, false) + 22);
                    // THE BADGE AND THE TAG ARE 22 PX TALL AND SIT 16 PX BELOW THE ROW TOP
                    final int textY = y + 16 + Math.max(0, (22 - canvas.textHeight(AppTheme.TEXT_SUBTITLE, false)) / 2);
                    if (index == 1 && this.detectedRegion != null) {
                        final int badgeX = row.titleEnd() + 10;
                        canvas.fill(badgeX, y + 16, 76, 22, AppTheme.alpha(AppTheme.BG_1, 190));
                        canvas.stroke(badgeX, y + 16, 76, 22, AppTheme.AMBER, 1f);
                        canvas.text("SYSTEM", badgeX + 10, textY, AppTheme.AMBER, AppTheme.TEXT_SUBTITLE, false);
                    }
                    final int tagX = x + w - tagW - 12;
                    canvas.fill(tagX, y + 16, tagW, 22, AppTheme.alpha(AppTheme.BG_1, 190));
                    canvas.stroke(tagX, y + 16, tagW, 22, sel ? AppTheme.GREEN : AppTheme.STROKE, 1f);
                    canvas.text(meta, tagX + 11, textY, sel ? AppTheme.GREEN : AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
                });
    }

    private String channelCount(final int index) {
        final String region = this.regionFor(index);
        final int count = index == 0 || region == null ? this.totalChannels : this.counts.getOrDefault(region, 0);
        return count + (count == 1 ? " CHANNEL" : " CHANNELS");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static String cleanRegion(final String region) {
        return region == null ? "" : region.trim();
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // CENTERED PANEL FRAMING THE REGION LIST UNDER ITS SECTION HEAD
    private final class PanelPane extends Group<PanelPane> {
        private int panelX;
        private int panelY;
        private int panelW;
        private int panelH;

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // 28px VERTICAL INSET ON EACH SIDE: A 10px CONTENT GAP PLUS THE 18px STACK MARGIN
            this.panelW = Math.min(760, innerAvailWidth - 64);
            this.panelH = Math.max(260, innerAvailHeight - 56);
            final int rowsW = this.panelW - 48;
            final int rowsH = Math.max(ROW_H, this.panelH - 78);
            for (final Element<?> child: this.children) child.measure(rowsW, rowsH);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.panelX = this.innerLeft() + (this.innerWidth() - this.panelW) / 2;
            this.panelY = this.innerTop() + 28;
            for (final Element<?> child: this.children) child.layout(this.panelX + 24, this.panelY + 58);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.gradientV(this.panelX, this.panelY, this.panelW, this.panelH,
                    AppTheme.alpha(AppTheme.BG_2, 217), AppTheme.alpha(AppTheme.BG_1, 217));
            canvas.stroke(this.panelX, this.panelY, this.panelW, this.panelH, AppTheme.STROKE_BRIGHT, 1f);
            canvas.cube(this.panelX - 1, this.panelY - 1, 10);
            canvas.cube(this.panelX + this.panelW - 9, this.panelY + this.panelH - 9, 10);

            SectionHead.draw(canvas, "REGIONS", RegionSelectorScreen.this.optionCount() + " AVAILABLE",
                    this.panelX + 18, this.panelY + 18, this.panelW - 36);
            super.onDraw(canvas);
        }
    }
}
