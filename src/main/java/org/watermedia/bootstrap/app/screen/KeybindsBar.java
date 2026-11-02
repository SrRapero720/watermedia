package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.KeyChip;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.StatusText;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.bootstrap.app.screen.Screen.Keybind;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The footer bar: the active screen's keybind chips on the left and the FFMPEG / OPENGL / VULKAN status strip
 * on the right. It pulls the keybinds from its supplier every update and appends the global CRT chip itself.
 */
final class KeybindsBar extends Group<KeybindsBar> {
    /** Fixed footer height in pixels. */
    public static final int H = 44;

    // THE GLOBAL CRT CHIP HAS ONLY TWO STATES: CACHE BOTH SO onUpdate NEVER ALLOCATES A Keybind PER FRAME
    private static final Keybind CRT_ON = new Keybind("C", "CRT ON");
    private static final Keybind CRT_OFF = new Keybind("C", "CRT OFF");

    private Supplier<List<Keybind>> source;
    private final Parent row;
    private final Parent binds;
    private List<Keybind> applied;

    public KeybindsBar(final Supplier<List<Keybind>> keybinds) {
        this.source = keybinds;
        this.width = MAX_PARENT;
        this.height = H;

        this.binds = Parent.row().spacing(0);
        final StatusText ffmpeg = new StatusText("FFMPEG")
                .status(() -> {
                    if (CodecsAPI.ffmpegError()) return StatusSquare.Status.ERROR;
                    if (CodecsAPI.ffmpegLoaded()) return StatusSquare.Status.OK;
                    return this.ctx != null && this.ctx.backendsLoading ? StatusSquare.Status.PENDING : StatusSquare.Status.ERROR;
                });
        // RENDER ENGINES REFLECT THE ACTIVE BACKEND: OK = ACTIVE, OFF = AVAILABLE BUT INACTIVE (DIM),
        // ERROR+STRIKE = UNSUPPORTED; OPENGL IS ALWAYS AVAILABLE, VULKAN DEPENDS ON THE LOADER/DEVICE
        final StatusText opengl = new StatusText("OpenGL")
                .status(() -> RenderSystem.vulkanActive() ? StatusSquare.Status.OFF : StatusSquare.Status.OK);
        final StatusText vulkan = new StatusText("Vulkan")
                .status(() -> {
                    if (!RenderSystem.vulkanAvailable()) return StatusSquare.Status.ERROR;
                    return RenderSystem.vulkanActive() ? StatusSquare.Status.OK : StatusSquare.Status.OFF;
                })
                .strike(() -> !RenderSystem.vulkanAvailable());
        // THE CHIPS SIT 9px BELOW THE BAR TOP AND THE STATUS STRIP 10px: ROW PADDING 9 PLUS A 1px STATUS MARGIN
        final Parent status = Parent.row().spacing(0)
                .margin(new Spacing(1, 0, 0, 0))
                .add(ffmpeg)
                .add(opengl)
                .add(vulkan);
        this.row = Parent.row()
                .size(MAX_PARENT, MAX_PARENT)
                .padding(new Spacing(9, 18, 13, 14))
                .add(this.binds)
                .add(new Box().weight(1f))
                .add(status);
        this.add(this.row);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Pins a fixed keybind list, replacing the constructor supplier as the source. */
    public KeybindsBar keybinds(final List<Keybind> list) {
        final List<Keybind> copy = list == null ? List.of() : List.copyOf(list);
        this.source = () -> copy;
        this.applied = null; // FORCE THE NEXT UPDATE PASS TO REBUILD FROM THE PINNED LIST
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        final List<Keybind> base = this.source == null ? List.of() : this.source.get();
        // THE BAR OWNS THE GLOBAL CRT CHIP (SCREENS NEVER DECLARE IT); THE APPLIED LIST IS ALWAYS base + THIS CHIP
        final Keybind crtChip = this.ctx != null && this.ctx.crt ? CRT_ON : CRT_OFF;
        // ALLOCATION-FREE CHANGE GATE: BAIL WHEN base AND THE CRT CHIP STILL MATCH THE APPLIED LIST
        final int n = base.size();
        boolean same = this.applied != null && this.applied.size() == n + 1 && this.applied.get(n) == crtChip;
        for (int i = 0; same && i < n; i++) same = this.applied.get(i).equals(base.get(i));
        if (same) return;

        final List<Keybind> list = new ArrayList<>(n + 1);
        list.addAll(base);
        list.add(crtChip);
        this.applied = list;
        this.binds.clear();
        for (final Keybind bind: list) {
            if (!bind.key().isEmpty())
                this.binds.add(new KeyChip(bind.key())
                        .icon(KeyChip.keyIcon(bind.key()))
                        .margin(new Spacing(0, 6, 0, 0)));
            this.binds.add(new Text(bind.label())
                    .scale(AppTheme.TEXT_BODY)
                    .color(AppTheme.TEXT_FAINT)
                    .height(22)
                    .margin(new Spacing(0, 18, 0, 0)));
        }
        this.invalidate();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.row.measure(innerAvailWidth, innerAvailHeight);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        this.row.layout(this.innerLeft(), this.innerTop());
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        canvas.gradientV(x, y, w, H,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 1f,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 1f);
        canvas.lineH(x, y, w, AppTheme.STROKE_BRIGHT, 1f);
        super.onDraw(canvas);
    }
}
