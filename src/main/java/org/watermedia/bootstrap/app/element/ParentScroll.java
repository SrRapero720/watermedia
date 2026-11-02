package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

/**
 * Clips its first visible child to its bounds and scrolls it vertically with wheel momentum and a thin scrollbar.
 * Programmatic scrolls ({@link #scrollTo}, {@link #ensureVisible}) are instant.
 */
public final class ParentScroll extends Group<ParentScroll> {

    private int scrollbarWidth = 6;
    private final ScrollState scroll = new ScrollState();

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public ParentScroll scrollbarWidth(final int width) {
        this.scrollbarWidth = Math.max(2, width);
        return this;
    }

    public ParentScroll scrollTo(final int offset) {
        // PROGRAMMATIC JUMPS ARE INSTANT AND KILL ANY WHEEL MOMENTUM: CALLERS EXPECT EXACT GEOMETRY
        this.scroll.jump(offset);
        return this;
    }

    /** Scrolls the least amount that shows a laid-out descendant; one taller than the viewport aligns to the top. */
    public ParentScroll ensureVisible(final Element<?> child) {
        if (child == null) return this;
        // CHILD TOP IN CONTENT SPACE: layout PLACED IT AT ABSOLUTE COORDS SHIFTED BY THE CURRENT OFFSET
        final int rel = child.top - this.innerTop() + this.scroll.offset;
        final int viewport = this.innerHeight();
        int next = this.scroll.offset;
        if (rel + child.measuredHeight > next + viewport) next = rel + child.measuredHeight - viewport;
        if (rel < next) next = rel;
        next = MathUtil.clamp(next, 0, this.scroll.max);
        final boolean moved = next != this.scroll.offset;
        // INSTANT AND KILLS ANY WHEEL MOMENTUM: KEYBOARD NAV AND CALLERS RELY ON THE CHILD BEING IN VIEW RIGHT AFTER
        // THE CALL, WHICH A COASTING OFFSET WOULD DRIFT BACK OUT
        this.scroll.jump(next);
        if (moved) this.invalidate();
        return this;
    }

    public int scrollOffset() { return this.scroll.offset; }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        if (!this.contains(mx, my) || this.scroll.max <= 0) return false;
        // MOMENTUM WHEEL: EACH NOTCH SHIFTS THE TARGET AND onUpdate EASES THE OFFSET TOWARD IT
        if (this.scroll.scroll(amount)) this.invalidate();
        return true;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        // ONLY CLICKS INSIDE THE VIEWPORT ROUTE: SCROLLED-OUT CHILDREN MUST NOT BE CLICKABLE
        return this.contains(mx, my) && super.dispatchClick(mx, my);
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.contains(mx, my)) {
            for (final Element<?> child: this.children) child.clearHover();
            this.hovered = false;
            return false;
        }
        return super.dispatchHover(mx, my);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        if (this.scroll.animate()) this.invalidate();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        final Element<?> content = this.content();
        if (content == null) {
            this.contentWidth = 0;
            this.contentHeight = 0;
            return;
        }
        content.measure(innerAvailWidth, ScrollState.UNBOUNDED);
        this.contentWidth = content.measuredWidth;
        // CAPPED AT THE OFFERED HEIGHT SO A WRAP_CONTENT SCROLLER BOUNDS ITSELF INSTEAD OF GROWING TO THE CONTENT
        this.contentHeight = Math.min(content.measuredHeight, innerAvailHeight);
    }

    @Override
    protected void onLayout() {
        final Element<?> content = this.content();
        if (content == null) {
            this.scroll.clamp(0);
            return;
        }
        // THE OFFSET AND MOMENTUM FOLLOW THE NEW RANGE, SO A RESIZE OR REBUILD NEVER LEAVES A STALE TARGET
        this.scroll.clamp(content.measuredHeight - this.innerHeight());
        content.layout(this.innerLeft(), this.innerTop() - this.scroll.offset);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
        super.onDraw(canvas);
        canvas.popClip();

        if (this.scroll.max > 0) {
            final int trackH = this.measuredHeight;
            final int thumbH = this.scroll.thumbHeight(trackH);
            final int thumbY = this.scroll.thumbY(this.top, trackH, thumbH);
            final int barX = this.left + this.measuredWidth - this.scrollbarWidth - 2;
            canvas.fill(barX, thumbY, this.scrollbarWidth, thumbH, AppTheme.alpha(AppTheme.NEON, 130));
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Element<?> content() {
        for (final Element<?> child: this.children) if (child.visible) return child;
        return null;
    }
}
