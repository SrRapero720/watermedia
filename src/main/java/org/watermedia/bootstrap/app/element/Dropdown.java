package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * A value-and-arrow control that opens its options as an anchored modal menu or a centered picker {@link Dialog},
 * chosen by {@link Mode}. The popup mounts into {@link #overlayHost(Group)}, else the highest {@link ParentFrame} ancestor.
 */
public final class Dropdown extends Element<Dropdown> {

    private static final int ROW_H = 26;
    private static final int PAD = Theme.SPACE_XS;
    private static final int ARROW_ZONE = 26;
    private static final int OPEN_MS = 160;

    private List<String> items = new ArrayList<>();
    private int selectedIndex;
    private Color accent = AppTheme.NEON;
    private Mode mode = Mode.ATTACH;
    private IntConsumer onSelect;
    private String title = "SELECT";
    private float scale = AppTheme.TEXT_BODY;
    private Group<?> overlayHost;

    // OPEN STATE: EXACTLY ONE IS NON-NULL WHILE OPEN, DEPENDING ON THE MODE
    private Popup popup;
    private Dialog pickerDialog;

    public Dropdown() {
        this.height = Theme.CONTROL;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public Dropdown items(final List<String> values) {
        this.items = values == null ? new ArrayList<>() : new ArrayList<>(values);
        if (this.selectedIndex >= this.items.size()) this.selectedIndex = Math.max(0, this.items.size() - 1);
        return this;
    }

    public Dropdown selected(final int index) {
        // CLAMPED INTO RANGE LIKE items() DOES: AN OUT-OF-RANGE INDEX WOULD DRAW AND PICK A BLANK VALUE
        this.selectedIndex = this.items.isEmpty() ? Math.max(0, index)
                : MathUtil.clamp(index, 0, this.items.size() - 1);
        return this;
    }

    public Dropdown accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public Dropdown mode(final Mode value) {
        this.mode = value == null ? Mode.ATTACH : value;
        return this;
    }

    public Dropdown onSelect(final IntConsumer handler) {
        this.onSelect = handler;
        return this;
    }

    /** Sets the title of the {@link Mode#DIALOG} picker. */
    public Dropdown title(final String value) {
        this.title = value == null ? "" : value;
        return this;
    }

    public Dropdown scale(final float value) {
        this.scale = value;
        return this;
    }

    /** Sets the layer the open menu or dialog mounts into, scoping it to a screen; {@code null} uses the root layer. */
    public Dropdown overlayHost(final Group<?> host) {
        this.overlayHost = host;
        return this;
    }

    @Override
    public Dropdown enabled(final boolean e) {
        // DISABLING WHILE OPEN TEARS DOWN THE FLOATING MENU OR DIALOG, SO A DEAD CONTROL LEAVES NO LIVE POPUP
        if (!e && this.isOpen()) this.close();
        return super.enabled(e);
    }

    public int selectedIndex() { return this.selectedIndex; }

    public String selectedValue() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.items.size() ? this.items.get(this.selectedIndex) : "";
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        // WHILE ATTACH IS OPEN THE POPUP INTERCEPTS THIS CLICK ITSELF; THE TOGGLE HERE ONLY OPENS
        if (this.isOpen()) this.close();
        else this.open();
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentHeight = Theme.CONTROL;
        this.contentWidth = 10 + Math.max(40, this.widestItemWidth()) + ARROW_ZONE;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        final boolean open = this.isOpen();
        final boolean hot = this.enabled && (this.hovered || open);
        // A DISABLED CONTROL DRAWS FLAT LIKE A DISABLED BUTTON: NO GLOW, FAINT TEXT AND A DIM BORDER
        if (hot) canvas.glow(x, y, w, h, 0f, AppTheme.NEON, open ? 0.20f : 0.12f);
        canvas.fill(x, y, w, h, AppTheme.alpha(open ? AppTheme.BG_3 : AppTheme.BG_1, open ? 230 : 210));
        canvas.fill(x + w - ARROW_ZONE, y, 1, h, AppTheme.STROKE);

        final Color tc = !this.enabled ? AppTheme.TEXT_FAINT : hot ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT;
        final int th = canvas.textHeight(this.scale, true);
        final int ty = y + (h - th) / 2;
        final int avail = w - 10 - ARROW_ZONE - 6;
        final String value = this.selectedValue();
        final String draw = canvas.textWidth(value, this.scale, true) > avail
                ? canvas.text().truncateToWidth(value, Math.max(0, avail), this.scale, Font.BOLD) : value;
        canvas.text(draw, x + 10, ty, tc, this.scale, true);

        // DOWN CHEVRON, CENTERED IN THE ARROW ZONE
        final int cx = x + w - ARROW_ZONE / 2;
        final int cy = y + h / 2;
        canvas.line(cx - 4, cy - 2, cx, cy + 2, tc, 1.5f);
        canvas.line(cx, cy + 2, cx + 4, cy - 2, tc, 1.5f);

        canvas.stroke(x, y, w, h, !this.enabled ? AppTheme.STROKE : hot ? AppTheme.NEON_LIGHT : AppTheme.STROKE_BRIGHT,
                open ? Theme.BORDER_ACTIVE : Theme.BORDER_HAIRLINE);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private boolean isOpen() {
        return this.popup != null || this.pickerDialog != null;
    }

    private void open() {
        final Group<?> host = this.overlayTarget();
        if (host == null || this.items.isEmpty()) return;
        if (this.mode == Mode.DIALOG) {
            this.openDialog(host);
        } else {
            this.popup = new Popup();
            host.add(this.popup);
        }
        this.invalidate();
    }

    private void openDialog(final Group<?> host) {
        final Parent column = Parent.column().spacing(6).width(MAX_PARENT);
        for (int i = 0; i < this.items.size(); i++) {
            final int index = i;
            final boolean sel = i == this.selectedIndex;
            column.add(new Button(this.items.get(i)).width(MAX_PARENT).height(Theme.BUTTON)
                    .accent(sel ? AppTheme.AMBER : this.accent).filled(sel)
                    .onClick(b -> {
                        this.select(index);
                        this.close();
                    }));
        }
        final ParentScroll scroll = new ParentScroll().width(MAX_PARENT).add(column);
        // CAP THE VISIBLE HEIGHT SO A LONG LIST SCROLLS INSTEAD OF OVERFLOWING THE SCREEN
        scroll.height(MathUtil.clamp(this.items.size(), 1, 8) * (Theme.BUTTON + 6));
        this.pickerDialog = new Dialog()
                .title(this.title)
                .accent(this.accent)
                .content(scroll)
                .panelWidth(360)
                .onDismiss(this::close)
                .dismissOnScrim(this::close);
        host.add(this.pickerDialog);
    }

    private void close() {
        if (this.popup != null) {
            if (this.popup.parent != null) this.popup.parent.remove(this.popup);
            this.popup = null;
        }
        if (this.pickerDialog != null) {
            if (this.pickerDialog.parent != null) this.pickerDialog.parent.remove(this.pickerDialog);
            this.pickerDialog = null;
        }
        this.invalidate();
    }

    private void select(final int index) {
        if (index < 0 || index >= this.items.size()) return;
        if (index != this.selectedIndex) {
            this.selectedIndex = index;
            if (this.onSelect != null) this.onSelect.accept(index);
        }
        this.invalidate();
    }

    private Group<?> overlayTarget() {
        // THE EXPLICIT HOST, ELSE THE HIGHEST ParentFrame ANCESTOR (THE WINDOW ROOT LAYER), SO THE OPEN MENU OR DIALOG
        // ALWAYS PAINTS ON TOP EVEN WITHOUT WIRING
        if (this.overlayHost != null) return this.overlayHost;
        ParentFrame top = null;
        Group<?> node = this.parent;
        while (node != null) {
            if (node instanceof final ParentFrame frame) top = frame;
            node = node.parent;
        }
        return top != null ? top : this.parent;
    }

    private int widestItemWidth() {
        if (this.ctx == null || this.ctx.text == null) return 0;
        int max = 0;
        for (final String item: this.items) max = Math.max(max, this.ctx.text.width(item, this.scale));
        return max;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** How the option list is presented when the control is clicked. */
    public enum Mode {
        /** A floating menu anchored to the control. */
        ATTACH,
        /** A centered picker {@link Dialog}. */
        DIALOG
    }

    // ATTACH MENU: A FULL-LAYER MODAL THAT DRAWS AN ANCHORED PANEL AND WINS INPUT WHILE OPEN
    private final class Popup extends Element<Popup> {

        private final long openedAt = System.currentTimeMillis();
        private int scroll;

        private Popup() {
            this.width = MAX_PARENT;
            this.height = MAX_PARENT;
        }

        @Override
        protected void onUpdate() {
            // SELF-SUSTAINED UNFOLD: REQUEST FRAMES UNTIL THE PANEL REACHES ITS FULL HEIGHT
            if (System.currentTimeMillis() - this.openedAt < OPEN_MS) this.invalidate();
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            canvas.raise();
            final PanelBox b = this.box();
            final int x = b.x();
            final int y = b.y();
            final int w = b.w();
            final int h = b.h();
            final int rows = Math.max(1, (h - PAD * 2) / ROW_H);
            this.scroll = MathUtil.clamp(this.scroll, 0, this.maxScroll(h));

            // THE PANEL UNFOLDS FROM ITS ANCHORED EDGE WITH A CUBIC EASE-OUT AND THE CLIP REVEALS THE ROWS; ONLY
            // THE DRAW SHRINKS, HIT-TESTS USE THE FULL BOX SO INPUT WORKS FROM THE FIRST FRAME
            final float t = Math.min(1f, (System.currentTimeMillis() - this.openedAt) / (float) OPEN_MS);
            final float inv = 1f - t;
            final int drawH = t >= 1f ? h : Math.max(1, Math.round(h * (1f - inv * inv * inv)));
            final int drawY = b.flip() ? y + h - drawH : y;

            canvas.shadow(x, drawY, w, drawH, 0f, Theme.SHADOW_PANEL);
            canvas.fill(x, drawY, w, drawH, AppTheme.alpha(AppTheme.BG_1, 238));
            canvas.glow(x, drawY, w, drawH, 0f, AppTheme.NEON, Theme.GLOW_WEAK);

            canvas.pushClip(x, drawY, w, drawH);
            final double mxp = Dropdown.this.ctx != null ? Dropdown.this.ctx.mouseX : -1d;
            final double myp = Dropdown.this.ctx != null ? Dropdown.this.ctx.mouseY : -1d;
            final int th = canvas.textHeight(Dropdown.this.scale, false);
            for (int i = 0; i < rows && i + this.scroll < Dropdown.this.items.size(); i++) {
                final int idx = i + this.scroll;
                final int rx = x + PAD;
                final int ry = y + PAD + i * ROW_H;
                final int rw = w - PAD * 2;
                final int rh = ROW_H - 2;
                final boolean sel = idx == Dropdown.this.selectedIndex;
                final boolean hover = mxp >= rx && mxp < rx + rw && myp >= ry && myp < ry + rh;
                final Color color = sel ? AppTheme.GREEN : hover ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT;
                canvas.fill(rx, ry, rw, rh, sel ? AppTheme.alpha(AppTheme.GREEN, 45)
                        : hover ? AppTheme.alpha(AppTheme.NEON_DARK, 88) : AppTheme.alpha(AppTheme.BG_2, 110));
                if (sel || hover) canvas.stroke(rx, ry, rw, rh, color, sel ? Theme.BORDER_ACTIVE : Theme.BORDER_HAIRLINE);
                // SELECTED ROW CARRIES THE AMBER MARKER CUBE
                if (sel) canvas.fill(rx + 7, ry + (rh - 6) / 2, 6, 6, AppTheme.AMBER);
                canvas.text(Dropdown.this.items.get(idx), rx + 18, ry + (rh - th) / 2, color, Dropdown.this.scale, false);
            }
            canvas.popClip();
            canvas.stroke(x, drawY, w, drawH, AppTheme.STROKE_BRIGHT, Theme.BORDER_ACTIVE);
            final int max = this.maxScroll(h);
            // THE SCROLL THUMB WAITS FOR THE UNFOLD TO FINISH SO IT NEVER PAINTS OUTSIDE THE GROWING PANEL
            if (max > 0 && drawH == h) {
                final int total = Dropdown.this.items.size();
                final int trackH = h - PAD * 2;
                final int thumbH = Math.max(20, trackH * rows / total);
                final int thumbY = y + PAD + (trackH - thumbH) * this.scroll / max;
                canvas.fill(x + w - 5, thumbY, 3, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
            }
        }

        @Override
        public Element<?> dispatchPress(final double mx, final double my) {
            // MODAL: CAPTURE EVERY PRESS SO NOTHING UNDER THE LAYER STARTS A DRAG
            return this;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            final PanelBox b = this.box();
            final int x = b.x();
            final int y = b.y();
            final int w = b.w();
            final int h = b.h();
            if (mx >= x && mx < x + w && my >= y && my < y + h) {
                final int row = (int) ((my - (y + PAD)) / ROW_H) + this.scroll;
                if (my >= y + PAD && row >= 0 && row < Dropdown.this.items.size()) {
                    Dropdown.this.select(row);
                    Dropdown.this.close();
                }
                return true; // INSIDE THE PANEL: ALWAYS SWALLOWED, DEAD SPACE DOES NOT CLOSE
            }
            // A CLICK OFF THE PANEL CLOSES THE MENU AND IS SWALLOWED (MODAL)
            Dropdown.this.close();
            return true;
        }

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            final int max = this.maxScroll(this.box().h());
            if (max > 0 && amount != 0) {
                // AT LEAST ONE ROW PER EVENT, SO PRECISION-TOUCHPAD DELTAS BELOW 1 STILL SCROLL
                final int rows = amount > 0 ? Math.max(1, (int) amount) : Math.min(-1, (int) amount);
                final int next = MathUtil.clamp(this.scroll - rows, 0, max);
                if (next != this.scroll) {
                    this.scroll = next;
                    this.invalidate();
                }
            }
            return true; // MODAL: NEVER LET THE UNDERLYING VIEW SCROLL WHILE OPEN
        }

        @Override
        public boolean dispatchKey(final int key, final int action) {
            if (action == GLFW_RELEASE) return false;
            if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) {
                Dropdown.this.close();
                return true;
            }
            return false;
        }

        private PanelBox box() {
            // RESOLVED FROM THE ANCHOR EACH TIME: RIGHT-ALIGNED UNDER THE CONTROL, FLIPPED ABOVE WHEN THERE IS MORE ROOM
            // THERE, HEIGHT CLAMPED TO THE LAYER SO A LONG LIST SCROLLS
            final int anchorT = Dropdown.this.top;
            final int anchorB = anchorT + Dropdown.this.measuredHeight;
            final int anchorR = Dropdown.this.left + Dropdown.this.measuredWidth;
            final int panelW = Math.max(Dropdown.this.measuredWidth, Dropdown.this.widestItemWidth() + 44);
            final int fullH = Dropdown.this.items.size() * ROW_H + PAD * 2;
            final int frameT = this.top;
            final int frameB = this.top + this.measuredHeight;
            final int availBelow = frameB - (anchorB + 4) - 4;
            final int availAbove = (anchorT - 4) - (frameT + 4);
            final int minH = ROW_H + PAD * 2;
            final int panelY;
            final int panelH;
            boolean flip = false;
            if (fullH <= availBelow || availBelow >= availAbove) {
                panelY = anchorB + 4;
                panelH = MathUtil.clamp(availBelow, minH, fullH);
            } else {
                panelH = MathUtil.clamp(availAbove, minH, fullH);
                panelY = anchorT - 4 - panelH;
                flip = true;
            }
            final int frameR = this.left + this.measuredWidth;
            final int panelX = Math.max(this.left + 4, Math.min(anchorR - panelW, frameR - panelW - 4));
            return new PanelBox(panelX, panelY, panelW, panelH, flip);
        }

        private int maxScroll(final int panelH) {
            return Math.max(0, Dropdown.this.items.size() - Math.max(1, (panelH - PAD * 2) / ROW_H));
        }

        // ANCHORED PANEL GEOMETRY; flip MEANS THE PANEL SITS ABOVE THE CONTROL, SO THE UNFOLD KNOWS ITS ANCHORED EDGE
        private record PanelBox(int x, int y, int w, int h, boolean flip) {}
    }
}
