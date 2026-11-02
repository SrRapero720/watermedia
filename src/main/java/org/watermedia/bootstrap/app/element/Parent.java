package org.watermedia.bootstrap.app.element;

/**
 * Stacks its children along one axis; weighted children, and main-axis {@code MAX_PARENT} ones as weight 1, share the
 * leftover space in proportion. Each child's {@link Element#gravity} aligns it on the cross axis.
 */
public class Parent extends Group<Parent> {

    private final Orientation orientation;
    private int spacing;

    public Parent(final Orientation orientation) {
        this.orientation = orientation == null ? Orientation.VERTICAL : orientation;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public static Parent column() {
        return new Parent(Orientation.VERTICAL);
    }

    public static Parent row() {
        return new Parent(Orientation.HORIZONTAL);
    }

    public Parent spacing(final int gap) {
        this.spacing = Math.max(0, gap);
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        int usedMain = 0;
        int maxCross = 0;
        int visibleCount = 0;
        float totalWeight = 0f;

        // FIRST PASS: MEASURE THE FIXED CHILDREN AND TALLY THE SPACE THEY TAKE; WEIGHTED CHILDREN AND MAIN-AXIS
        // MAX_PARENT CHILDREN (EFFECTIVE WEIGHT 1) FOLD INTO THE WEIGHTED SECOND PASS
        for (final Element<?> child: this.children) {
            if (!child.visible) continue;
            visibleCount++;
            final float mainWeight = this.mainWeight(child);
            if (mainWeight > 0f) {
                totalWeight += mainWeight;
                continue;
            }
            if (this.orientation == Orientation.VERTICAL) {
                child.measure(innerAvailWidth - child.margin.horizontal(), innerAvailHeight);
                usedMain += child.measuredHeight + child.margin.vertical();
                maxCross = Math.max(maxCross, child.measuredWidth + child.margin.horizontal());
            } else {
                child.measure(innerAvailWidth, innerAvailHeight - child.margin.vertical());
                usedMain += child.measuredWidth + child.margin.horizontal();
                maxCross = Math.max(maxCross, child.measuredHeight + child.margin.vertical());
            }
        }

        final int gaps = this.spacing * Math.max(0, visibleCount - 1);
        final int mainAvail = this.orientation == Orientation.VERTICAL ? innerAvailHeight : innerAvailWidth;
        final int leftover = Math.max(0, mainAvail - usedMain - gaps);

        // SECOND PASS: WEIGHTED CHILDREN SPLIT THE LEFTOVER MAIN-AXIS SPACE AND TAKE EXACTLY THEIR SHARE
        for (final Element<?> child: this.children) {
            if (!child.visible) continue;
            final float mainWeight = this.mainWeight(child);
            if (mainWeight <= 0f) continue;
            final int share = totalWeight > 0f ? Math.round(leftover * (mainWeight / totalWeight)) : 0;
            if (this.orientation == Orientation.VERTICAL) {
                child.measure(innerAvailWidth - child.margin.horizontal(), share);
                child.measuredHeight = share;
                usedMain += share + child.margin.vertical();
                maxCross = Math.max(maxCross, child.measuredWidth + child.margin.horizontal());
            } else {
                child.measure(share, innerAvailHeight - child.margin.vertical());
                child.measuredWidth = share;
                usedMain += share + child.margin.horizontal();
                maxCross = Math.max(maxCross, child.measuredHeight + child.margin.vertical());
            }
        }

        if (this.orientation == Orientation.VERTICAL) {
            this.contentHeight = usedMain + gaps;
            this.contentWidth = maxCross;
        } else {
            this.contentWidth = usedMain + gaps;
            this.contentHeight = maxCross;
        }
    }

    @Override
    protected void onLayout() {
        final int cx = this.innerLeft();
        final int cy = this.innerTop();
        final int cw = this.innerWidth();
        final int ch = this.innerHeight();
        boolean first = true;

        if (this.orientation == Orientation.VERTICAL) {
            int y = cy;
            for (final Element<?> child: this.children) {
                if (!child.visible) continue;
                if (!first) y += this.spacing;
                first = false;
                y += child.margin.top();
                final int childX = switch (child.gravity) {
                    case CENTER, FILL -> cx + (cw - child.measuredWidth) / 2;
                    case RIGHT -> cx + cw - child.measuredWidth - child.margin.right();
                    default -> cx + child.margin.left();
                };
                child.layout(childX, y);
                y += child.measuredHeight + child.margin.bottom();
            }
        } else {
            int x = cx;
            for (final Element<?> child: this.children) {
                if (!child.visible) continue;
                if (!first) x += this.spacing;
                first = false;
                x += child.margin.left();
                final int childY = switch (child.gravity) {
                    case CENTER, FILL -> cy + (ch - child.measuredHeight) / 2;
                    case BOTTOM -> cy + ch - child.measuredHeight - child.margin.bottom();
                    default -> cy + child.margin.top();
                };
                child.layout(x, childY);
                x += child.measuredWidth + child.margin.right();
            }
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private float mainWeight(final Element<?> child) {
        // AN EXPLICIT WEIGHT WINS; A MAIN-AXIS MAX_PARENT CHILD ACTS AS WEIGHT 1
        if (child.weight > 0f) return child.weight;
        final int main = this.orientation == Orientation.VERTICAL ? child.height : child.width;
        return main == MAX_PARENT ? 1f : 0f;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    public enum Orientation {
        VERTICAL,
        HORIZONTAL
    }
}
