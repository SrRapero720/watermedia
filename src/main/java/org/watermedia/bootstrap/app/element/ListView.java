package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * A scrollable, selectable list that builds one row per item through a row factory and owns scrolling and highlights.
 * Only a row click fires {@link #onSelect}; with a fixed {@link #rowHeight} only the rows near the viewport are children.
 */
public final class ListView<D> extends Group<ListView<D>> {

    private final List<D> items = new ArrayList<>();
    // EVERY BUILT ROW BY ITEM INDEX; children HOLDS THE WINDOW OF THEM THAT UPDATES, LAYS OUT, DRAWS AND TAKES INPUT
    private final List<Element<?>> rows = new ArrayList<>();
    private BiFunction<D, Integer, Element<?>> rowFactory;
    private BiConsumer<D, Integer> onSelect;
    private int rowHeight; // 0 = EACH ROW'S NATURAL HEIGHT
    private int spacing;
    private int selectedIndex = -1;
    private final ScrollState scroll = new ScrollState();
    private int totalHeight;
    private int scrollbarWidth = 6;
    private boolean selectOnHover;
    private Color selectionColor = AppTheme.alpha(AppTheme.NEON, 46);
    private Color hoverColor = AppTheme.alpha(AppTheme.NEON_DARK, 40);
    // ITEM INDEX OF children.get(0) AND HOW MANY VISIBLE ROWS SIT ABOVE IT
    private int windowFrom;
    private int windowSlot;
    // INNER HEIGHT OF THE LAST LAYOUT; update() PICKS ITS WINDOW WITH IT BEFORE THE NEXT LAYOUT RUNS
    private int viewport;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public ListView<D> items(final List<D> data) {
        this.items.clear();
        if (data != null) this.items.addAll(data);
        if (this.selectedIndex >= this.items.size()) this.selectedIndex = this.items.size() - 1;
        this.rebuild();
        return this;
    }

    public ListView<D> rowFactory(final BiFunction<D, Integer, Element<?>> factory) {
        this.rowFactory = factory;
        this.rebuild();
        return this;
    }

    public ListView<D> rowHeight(final int height) {
        this.rowHeight = Math.max(0, height);
        return this;
    }

    public ListView<D> spacing(final int gap) {
        this.spacing = Math.max(0, gap);
        return this;
    }

    /** Registers the row-click action, including clicks on the already-selected row. */
    public ListView<D> onSelect(final BiConsumer<D, Integer> handler) {
        this.onSelect = handler;
        return this;
    }

    public ListView<D> selectionColor(final Color color) {
        this.selectionColor = color;
        return this;
    }

    public ListView<D> hoverColor(final Color color) {
        this.hoverColor = color;
        return this;
    }

    public ListView<D> scrollbarWidth(final int width) {
        this.scrollbarWidth = Math.max(2, width);
        return this;
    }

    public ListView<D> selectOnHover(final boolean value) {
        this.selectOnHover = value;
        return this;
    }

    public ListView<D> selection(final int index) {
        this.selectedIndex = MathUtil.clamp(index, -1, this.items.size() - 1);
        return this;
    }

    public int selectedIndex() { return this.selectedIndex; }

    public D selectedItem() {
        return this.selectedIndex >= 0 && this.selectedIndex < this.items.size() ? this.items.get(this.selectedIndex) : null;
    }

    /** Moves the selection by {@code delta} without activating it, scrolling it into view only when it changes. */
    public ListView<D> moveSelection(final int delta) {
        if (this.items.isEmpty()) return this;
        final int base = Math.max(0, this.selectedIndex);
        final int next = MathUtil.clamp(base + delta, 0, this.items.size() - 1);
        if (next != this.selectedIndex) {
            this.selection(next).invalidate();
            this.ensureVisible(next);
        }
        return this;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        return this.contains(mx, my) && super.dispatchClick(mx, my);
    }

    @Override
    public Element<?> dispatchPress(final double mx, final double my) {
        // THE WINDOW HOLDS ROWS PAST THE VIEWPORT EDGES, WHICH MUST NOT CAPTURE A PRESS ON WHAT LIES THERE
        return this.contains(mx, my) ? super.dispatchPress(mx, my) : null;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) {
            this.clearHover();
            return false;
        }
        final boolean result = super.dispatchHover(mx, my);
        if (this.selectOnHover)
            for (int i = 0; i < this.children.size(); i++) {
                if (this.children.get(i).hovered && this.windowFrom + i != this.selectedIndex) {
                    this.selection(this.windowFrom + i).invalidate();
                    break;
                }
            }
        return result;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (!this.contains(mx, my) || this.scroll.max <= 0) return false;
        // MOMENTUM WHEEL: EACH NOTCH SHIFTS THE TARGET AND onUpdate EASES THE OFFSET TOWARD IT
        if (this.scroll.scroll(amount)) this.invalidate();
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        if (this.scroll.animate()) this.invalidate();
        // Group.update RUNS THIS BEFORE THE CHILDREN, SO ONLY THE ROWS OF THE NEW WINDOW BIND THIS FRAME
        this.window();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        if (this.rowHeight > 0) {
            int count = 0;
            for (final Element<?> row: this.rows) if (row.visible) count++;
            this.totalHeight = count * this.rowHeight + this.spacing * Math.max(0, count - 1);
            for (final Element<?> row: this.children) {
                if (!row.visible) continue;
                row.measure(innerAvailWidth, this.rowHeight);
                row.measuredHeight = this.rowHeight;
            }
            this.contentWidth = this.rows.isEmpty() ? 0 : innerAvailWidth;
            this.contentHeight = Math.min(this.totalHeight, innerAvailHeight);
            return;
        }
        int used = 0;
        int maxW = 0;
        int visibleCount = 0;
        for (final Element<?> row: this.children) {
            if (!row.visible) continue;
            visibleCount++;
            row.measure(innerAvailWidth, ScrollState.UNBOUNDED);
            used += row.measuredHeight;
            maxW = Math.max(maxW, row.measuredWidth);
        }
        this.totalHeight = used + this.spacing * Math.max(0, visibleCount - 1);
        this.contentWidth = maxW;
        this.contentHeight = Math.min(this.totalHeight, innerAvailHeight);
    }

    @Override
    protected void onLayout() {
        // THE OFFSET AND MOMENTUM FOLLOW THE NEW RANGE, SO A RESIZE OR REBUILD NEVER LEAVES A STALE TARGET
        this.scroll.clamp(this.totalHeight - this.innerHeight());
        this.viewport = this.innerHeight();
        final int oldFrom = this.windowFrom;
        final int oldTo = oldFrom + this.children.size();
        if (this.window()) {
            for (int i = 0; i < this.children.size(); i++) {
                final Element<?> row = this.children.get(i);
                // A ROW THAT JOINS HERE (FIRST FRAME, RESIZE) MISSED THIS FRAME'S UPDATE PASS, SO IT BINDS BEFORE IT DRAWS
                if (this.windowFrom + i < oldFrom || this.windowFrom + i >= oldTo) row.update();
                if (!row.visible) continue;
                row.measure(this.innerWidth(), this.rowHeight);
                row.measuredHeight = this.rowHeight;
            }
        }
        int y = this.innerTop() - this.scroll.offset + this.windowSlot * (this.rowHeight + this.spacing);
        boolean first = true;
        for (final Element<?> row: this.children) {
            if (!row.visible) continue;
            if (!first) y += this.spacing;
            first = false;
            row.layout(this.innerLeft(), y);
            y += row.measuredHeight;
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
        final int viewTop = this.top;
        final int viewBottom = this.top + this.measuredHeight;
        for (int i = 0; i < this.children.size(); i++) {
            final Element<?> row = this.children.get(i);
            if (!row.visible) continue;
            final boolean selected = this.windowFrom + i == this.selectedIndex;
            // THE ROW LEARNS ITS SELECTION STATE SO CUSTOM ROWS CAN SELF-STYLE; THE DEFAULT HIGHLIGHT PAINTS TOO
            row.selected(selected);
            // ROWS FULLY OUTSIDE THE VIEWPORT ARE CULLED IN LOGIC, NOT ONLY BY THE SCISSOR, SO A SCROLLED ROW NEVER
            // BLEEDS OVER THE CHROME EVEN IF A BACKEND'S CLIP MISBEHAVES
            if (row.top + row.measuredHeight <= viewTop || row.top >= viewBottom) continue;
            if (selected) canvas.fill(row.left, row.top, row.measuredWidth, row.measuredHeight, this.selectionColor);
            else if (row.hovered) canvas.fill(row.left, row.top, row.measuredWidth, row.measuredHeight, this.hoverColor);
            row.draw(canvas);
        }
        canvas.popClip();

        if (this.scroll.max > 0) {
            final int trackH = this.measuredHeight;
            final int thumbH = this.scroll.thumbHeight(trackH);
            final int thumbY = this.scroll.thumbY(this.top, trackH, thumbH);
            final int barX = this.left + this.measuredWidth - this.scrollbarWidth;
            canvas.fill(barX, thumbY, this.scrollbarWidth, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void rebuild() {
        this.clear();
        this.rows.clear();
        this.windowFrom = 0;
        this.windowSlot = 0;
        if (this.rowFactory == null) return;
        for (int i = 0; i < this.items.size(); i++) {
            final int index = i;
            final Element<?> row = this.rowFactory.apply(this.items.get(i), index);
            if (row == null) continue;
            row.width(MAX_PARENT);
            if (this.rowHeight > 0) row.height(this.rowHeight);
            row.onClick(v -> {
                this.selection(index).invalidate();
                if (this.onSelect != null && index < this.items.size()) this.onSelect.accept(this.items.get(index), index);
            });
            this.rows.add(row);
        }
        // NATURAL-HEIGHT ROWS ARE ALWAYS ALL CHILDREN; FIXED-HEIGHT ROWS JOIN THROUGH window()
        if (this.rowHeight <= 0) for (final Element<?> row: this.rows) this.add(row);
    }

    private boolean window() {
        // THE ROWS WHOSE SLOTS OVERLAP THE VIEWPORT, PLUS ONE ROW ON EACH SIDE, BECOME THE ONLY CHILDREN; TRUE WHEN
        // THE WINDOW CHANGED, SO A LAYOUT KNOWS ITS NEW ROWS STILL NEED A MEASURE
        if (this.rowHeight <= 0) return false;
        final int pitch = this.rowHeight + this.spacing;
        final int top = this.scroll.offset - pitch;
        final int bottom = this.scroll.offset + this.viewport + pitch;
        int from = -1;
        int to = -1;
        int slot = 0;
        int fromSlot = 0;
        int focus = -1;
        int focusSlot = 0;
        for (int i = 0; i < this.rows.size(); i++) {
            final Element<?> row = this.rows.get(i);
            if (!row.visible) continue;
            final int y = slot * pitch;
            if (to < 0 && y >= bottom) to = i;
            if (from < 0 && y + this.rowHeight > top) {
                from = i;
                fromSlot = slot;
            }
            if (focus < 0 && row.textInputActive()) {
                focus = i;
                focusSlot = slot;
            }
            slot++;
        }
        if (to < 0) to = this.rows.size();
        if (from < 0) {
            from = to;
            fromSlot = slot;
        }
        // A ROW HOLDING A FOCUSED INPUT STRETCHES THE WINDOW TO ITSELF, SO KEYS, BLUR AND HIT TESTS STILL REACH IT
        // WHERE IT REALLY SITS; THE DRAW CULLS IT WHILE IT IS OUT OF VIEW
        if (focus >= 0 && focus < from) {
            from = focus;
            fromSlot = focusSlot;
        }
        if (focus >= to) to = focus + 1;
        if (from == this.windowFrom && to - from == this.children.size()) return false;
        // A ROW LEAVING THE WINDOW KEEPS NO HOVER, OR IT WOULD COME BACK LIT
        for (final Element<?> row: this.children) row.clearHover();
        this.clear();
        for (int i = from; i < to; i++) this.add(this.rows.get(i));
        this.windowFrom = from;
        this.windowSlot = fromSlot;
        return true;
    }

    private void ensureVisible(final int index) {
        int y = 0;
        final int rowH;
        if (this.rowHeight > 0) {
            for (int i = 0; i < index && i < this.rows.size(); i++) if (this.rows.get(i).visible) y += this.rowHeight + this.spacing;
            rowH = this.rowHeight;
        } else {
            for (int i = 0; i < index && i < this.rows.size(); i++) y += this.rows.get(i).measuredHeight + this.spacing;
            rowH = index < this.rows.size() ? this.rows.get(index).measuredHeight : 0;
        }
        final int viewport = this.innerHeight();
        int offset = this.scroll.offset;
        if (y < offset) offset = y;
        else if (y + rowH > offset + viewport) offset = y + rowH - viewport;
        // KEYBOARD NAV IS INSTANT AND KILLS ANY WHEEL MOMENTUM: THE SELECTION MUST LAND EXACTLY IN VIEW, WHICH A
        // COASTING OFFSET WOULD DRIFT BACK OUT
        this.scroll.jump(offset);
    }
}
