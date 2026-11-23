package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;
import java.util.function.IntConsumer;

/**
 * A grid of choice buttons in rows of {@link #columns}: the picked one is drawn filled, and a locked one is drawn dim
 * and ignores the pointer. A click reports the cell through {@code onPick}; the picked cell is whatever the owner
 * binds, so the grid never picks by itself.
 */
public final class ChoiceGrid extends Element<ChoiceGrid> {

    private static final int GAP = 6;
    private static final int TEXT_PAD = 8;

    private String[] cells = new String[0];
    private boolean[] locked = new boolean[0];
    private int columns = 3;
    private int cellHeight = 30;
    private int picked = -1;
    private int hover = -1;
    private Color accent = AppTheme.NEON;
    private IntConsumer onPick;

    public ChoiceGrid() {
        this.width = MAX_PARENT;
    }

    /** Replaces the cells, all unlocked and none picked. */
    public ChoiceGrid cells(final String... labels) {
        this.cells = labels == null ? new String[0] : labels;
        this.locked = new boolean[this.cells.length];
        this.picked = -1;
        this.hover = -1;
        return this;
    }

    /** Locks or unlocks one cell; an index outside the grid does nothing. */
    public ChoiceGrid locked(final int index, final boolean value) {
        if (index >= 0 && index < this.locked.length) this.locked[index] = value;
        return this;
    }

    public ChoiceGrid columns(final int count) {
        this.columns = Math.max(1, count);
        return this;
    }

    /** Sets the picked cell, or -1 for none. */
    public ChoiceGrid picked(final int index) {
        this.picked = index;
        return this;
    }

    public ChoiceGrid accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public ChoiceGrid onPick(final IntConsumer handler) {
        this.onPick = handler;
        return this;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        final int cell = this.cellAt(mx, my);
        if (cell < 0) return false;
        // A LOCKED CELL SWALLOWS ITS CLICK, SO IT NEVER FALLS THROUGH TO WHAT LIES UNDER THE GRID
        if (!this.locked[cell] && this.onPick != null) this.onPick.accept(cell);
        return true;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible) return false;
        final int cell = this.enabled ? this.cellAt(mx, my) : -1;
        final int next = cell >= 0 && !this.locked[cell] ? cell : -1;
        if (next != this.hover) {
            this.hover = next;
            this.invalidate();
        }
        this.hovered = cell >= 0;
        return this.hovered;
    }

    @Override
    public void clearHover() {
        if (this.hover >= 0) {
            this.hover = -1;
            this.invalidate();
        }
        super.clearHover();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        final int rows = (this.cells.length + this.columns - 1) / this.columns;
        this.contentWidth = innerAvailWidth;
        this.contentHeight = rows * this.cellHeight + Math.max(0, rows - 1) * GAP;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int cellW = this.cellWidth();
        final int textH = canvas.textHeight(AppTheme.TEXT_BUTTON, true);
        for (int i = 0; i < this.cells.length; i++) {
            final int x = this.innerLeft() + (i % this.columns) * (cellW + GAP);
            final int y = this.innerTop() + (i / this.columns) * (this.cellHeight + GAP);
            final boolean open = this.enabled && !this.locked[i];
            final boolean filled = i == this.picked && open;
            Button.box(canvas, x, y, cellW, this.cellHeight, this.accent, filled, i == this.hover, open);

            final String label = this.cells[i] == null ? "" : this.cells[i];
            final int avail = Math.max(0, cellW - TEXT_PAD * 2);
            final String draw = canvas.textWidth(label, AppTheme.TEXT_BUTTON, true) > avail
                    ? canvas.text().truncateToWidth(label, avail, AppTheme.TEXT_BUTTON, Font.BOLD) : label;
            final Color color = !open ? AppTheme.TEXT_FAINT : filled ? AppTheme.BG_0 : AppTheme.TEXT;
            canvas.text(draw, x + (cellW - canvas.textWidth(draw, AppTheme.TEXT_BUTTON, true)) / 2f, y + (this.cellHeight - textH) / 2f,
                    color, AppTheme.TEXT_BUTTON, true);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private int cellWidth() {
        return Math.max(0, (this.innerWidth() - GAP * (this.columns - 1)) / this.columns);
    }

    // THE CELL UNDER THE POINT, OR -1 OVER A GAP OR OUTSIDE THE GRID
    private int cellAt(final double mx, final double my) {
        final int cellW = this.cellWidth();
        if (cellW <= 0 || mx < this.innerLeft() || my < this.innerTop()) return -1;
        final int col = (int) ((mx - this.innerLeft()) / (cellW + GAP));
        final int row = (int) ((my - this.innerTop()) / (this.cellHeight + GAP));
        if (col >= this.columns || mx - this.innerLeft() - col * (cellW + GAP) >= cellW) return -1;
        if (my - this.innerTop() - row * (this.cellHeight + GAP) >= this.cellHeight) return -1;
        final int cell = row * this.columns + col;
        return cell < this.cells.length ? cell : -1;
    }
}
