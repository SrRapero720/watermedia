package org.watermedia.bootstrap.app.element;

/** Stacks its children in the same box, each placed on both axes by its own {@link Element#gravity}; used for overlays. */
public final class ParentFrame extends Group<ParentFrame> {

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        int maxW = 0;
        int maxH = 0;
        for (final Element<?> child: this.children) {
            if (!child.visible) continue;
            child.measure(innerAvailWidth - child.margin.horizontal(), innerAvailHeight - child.margin.vertical());
            maxW = Math.max(maxW, child.measuredWidth + child.margin.horizontal());
            maxH = Math.max(maxH, child.measuredHeight + child.margin.vertical());
        }
        this.contentWidth = maxW;
        this.contentHeight = maxH;
    }

    @Override
    protected void onLayout() {
        final int cx = this.innerLeft();
        final int cy = this.innerTop();
        final int cw = this.innerWidth();
        final int ch = this.innerHeight();
        for (final Element<?> child: this.children) {
            if (!child.visible) continue;
            final int w = child.measuredWidth;
            final int h = child.measuredHeight;
            final int childX = switch (child.gravity) {
                case LEFT, FILL -> cx + child.margin.left();
                case RIGHT -> cx + cw - w - child.margin.right();
                default -> cx + (cw - w) / 2;
            };
            final int childY = switch (child.gravity) {
                case TOP, FILL -> cy + child.margin.top();
                case BOTTOM -> cy + ch - h - child.margin.bottom();
                case LEFT, RIGHT, CENTER -> cy + (ch - h) / 2;
            };
            child.layout(childX, childY);
        }
    }
}
