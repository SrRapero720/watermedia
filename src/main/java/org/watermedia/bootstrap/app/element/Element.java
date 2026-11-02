package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Base view of the retained tree; every rendered frame it runs {@link #update}, {@link #measure}, {@link #layout} and
 * {@link #draw}. Self-typed so the fluent setters return the concrete view.
 */
public abstract class Element<T extends Element<T>> {

    /** Size param: fill the space the parent offers. */
    public static final int MAX_PARENT = -1;
    /** Size param: shrink to the content's intrinsic size. */
    public static final int WRAP_CONTENT = -2;

    // LAYOUT PARAMS
    protected int width = WRAP_CONTENT;
    protected int height = WRAP_CONTENT;
    protected Spacing margin = Spacing.ZERO;
    protected Spacing padding = Spacing.ZERO;
    protected Gravity gravity = Gravity.LEFT;
    protected float weight;

    // DECOR
    protected Color background;
    protected Color borderColor;
    protected float borderWidth;
    protected float radius;
    protected Color glowColor;
    protected float glowAlpha;
    protected float shadowAlpha;

    // STATE
    protected boolean visible = true;
    protected boolean enabled = true;
    protected boolean hovered;
    protected boolean focused;
    protected boolean selected;
    protected boolean consumeTouch; // WHEN TRUE, A HIT IS SWALLOWED EVEN WITHOUT AN onClick (SCRIMS/MODALS)

    // COMPUTED GEOMETRY (VALID AFTER measure + layout)
    protected int left;
    protected int top;
    protected int measuredWidth;
    protected int measuredHeight;
    protected int contentWidth;
    protected int contentHeight;

    // WIRING
    protected Group<?> parent;
    protected AppContext ctx;
    protected Object tag;

    protected Consumer<T> onClick;
    protected Consumer<T> onHover;

    // ==========================================================================
    // PUBLIC API: MEASURE / LAYOUT / DRAW PIPELINE
    // ==========================================================================

    /** Refreshes this view from live state before measure; containers forward it to every child, visible or not. */
    public void update() {
        this.onUpdate();
    }

    /** Resolves {@link #measuredWidth}/{@link #measuredHeight} from the size params, padding and content. */
    public final void measure(final int availWidth, final int availHeight) {
        final int boxW = this.width == MAX_PARENT || this.width == WRAP_CONTENT ? availWidth : this.width;
        final int boxH = this.height == MAX_PARENT || this.height == WRAP_CONTENT ? availHeight : this.height;
        this.onMeasure(Math.max(0, boxW - this.padding.horizontal()), Math.max(0, boxH - this.padding.vertical()));
        this.measuredWidth = switch (this.width) {
            case MAX_PARENT -> availWidth;
            case WRAP_CONTENT -> this.contentWidth + this.padding.horizontal();
            default -> this.width;
        };
        this.measuredHeight = switch (this.height) {
            case MAX_PARENT -> availHeight;
            case WRAP_CONTENT -> this.contentHeight + this.padding.vertical();
            default -> this.height;
        };
    }

    /** Places this view's border box top-left at ({@code x},{@code y}) and lays out any children. */
    public final void layout(final int x, final int y) {
        this.left = x;
        this.top = y;
        this.onLayout();
    }

    /** Paints decor, then content, then the border on top. Skipped when the view is hidden. */
    public final void draw(final Canvas canvas) {
        if (!this.visible) return;
        if (this.shadowAlpha > 0f)
            canvas.shadow(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.shadowAlpha);
        if (this.glowColor != null && this.glowAlpha > 0f)
            canvas.glow(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.glowColor, this.glowAlpha);
        if (this.background != null)
            canvas.fillRound(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.background);
        this.onDraw(canvas);
        if (this.borderColor != null && this.borderWidth > 0f)
            canvas.strokeRound(this.left, this.top, this.measuredWidth, this.measuredHeight, this.radius, this.borderColor, this.borderWidth);
    }

    // ==========================================================================
    // PUBLIC API: EVENT DISPATCH
    // ==========================================================================

    // EVERY DISPATCHER RETURNS true WHEN IT CONSUMED THE EVENT
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled || !this.contains(mx, my)) return false;
        if (this.onClick != null) {
            this.onClick.accept(this.self());
            return true;
        }
        return this.consumeTouch;
    }

    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible) return false;
        final boolean inside = this.enabled && this.contains(mx, my);
        this.hovered = inside;
        if (inside && this.onHover != null) this.onHover.accept(this.self());
        return inside;
    }

    /** Clears the hover state when a layer above consumed the hover; repaints only on a change. */
    public void clearHover() {
        if (this.hovered) {
            this.hovered = false;
            this.invalidate();
        }
    }

    /** Returns this view when {@link #onPress} accepts the press, capturing the pointer for drags until release. */
    public Element<?> dispatchPress(final double mx, final double my) {
        return this.visible && this.enabled && this.contains(mx, my) && this.onPress(mx, my) ? this : null;
    }

    public void onDrag(final double mx, final double my) {}
    public void onRelease(final double mx, final double my) {}
    public boolean dispatchScroll(final double mx, final double my, final double amount) { return false; }
    public boolean dispatchKey(final int key, final int action) { return false; }
    public boolean dispatchChar(final int codepoint) { return false; }

    /** Returns whether this view or a descendant is a focused text input, which mutes global shortcuts. */
    public boolean textInputActive() {
        return false;
    }

    /** Blurs any focused text input in this subtree; called on the layers a consumed click missed. */
    public void clearFocus() {}

    /** Returns whether the point hits this view; the half-open test gives a shared border to one view only. */
    public boolean contains(final double mx, final double my) {
        return mx >= this.left && mx < this.left + this.measuredWidth
                && my >= this.top && my < this.top + this.measuredHeight;
    }

    // ==========================================================================
    // PUBLIC API: WIRING
    // ==========================================================================

    /** Requests a repaint of the window this view belongs to. */
    public T invalidate() {
        if (this.ctx != null) this.ctx.requestRender();
        return this.self();
    }

    /** Attaches the shared context to this view and, in a {@link Group}, to its subtree. */
    public void attach(final AppContext context) {
        this.ctx = context;
    }

    // ==========================================================================
    // PUBLIC API: ACCESSORS
    // ==========================================================================

    public int left() { return this.left; }
    public int top() { return this.top; }
    public int measuredWidth() { return this.measuredWidth; }
    public int measuredHeight() { return this.measuredHeight; }
    public Spacing margin() { return this.margin; }
    public float weight() { return this.weight; }
    public Gravity gravity() { return this.gravity; }
    public boolean visible() { return this.visible; }
    public boolean enabled() { return this.enabled; }
    public boolean hovered() { return this.hovered; }
    public boolean focused() { return this.focused; }
    public boolean selected() { return this.selected; }
    public Object tag() { return this.tag; }

    // CONTENT BOX (INSIDE THE PADDING), VALID AFTER layout
    public int innerLeft() { return this.left + this.padding.left(); }
    public int innerTop() { return this.top + this.padding.top(); }
    public int innerWidth() { return Math.max(0, this.measuredWidth - this.padding.horizontal()); }
    public int innerHeight() { return Math.max(0, this.measuredHeight - this.padding.vertical()); }

    // ==========================================================================
    // PUBLIC API: FLUENT SETTERS
    // ==========================================================================

    public T size(final int w, final int h) {
        this.width = w;
        this.height = h;
        return this.self();
    }

    public T width(final int w) {
        this.width = w;
        return this.self();
    }

    public T height(final int h) {
        this.height = h;
        return this.self();
    }

    public T margin(final Spacing m) {
        this.margin = m == null ? Spacing.ZERO : m;
        return this.self();
    }

    public T margin(final int all) {
        this.margin = Spacing.all(all);
        return this.self();
    }

    public T padding(final Spacing p) {
        this.padding = p == null ? Spacing.ZERO : p;
        return this.self();
    }

    public T padding(final int all) {
        this.padding = Spacing.all(all);
        return this.self();
    }

    public T gravity(final Gravity g) {
        this.gravity = g == null ? Gravity.LEFT : g;
        return this.self();
    }

    public T weight(final float w) {
        this.weight = w;
        return this.self();
    }

    public T background(final Color color) {
        this.background = color;
        return this.self();
    }

    public T border(final Color color, final float lineWidth) {
        this.borderColor = color;
        this.borderWidth = lineWidth;
        return this.self();
    }

    public T radius(final float r) {
        this.radius = r;
        return this.self();
    }

    public T glow(final Color color, final float alpha) {
        this.glowColor = color;
        this.glowAlpha = alpha;
        return this.self();
    }

    public T shadow(final float alpha) {
        this.shadowAlpha = alpha;
        return this.self();
    }

    public T visible(final boolean v) {
        this.visible = v;
        return this.self();
    }

    public T enabled(final boolean e) {
        this.enabled = e;
        return this.self();
    }

    public T selected(final boolean s) {
        this.selected = s;
        return this.self();
    }

    public T focused(final boolean f) {
        this.focused = f;
        return this.self();
    }

    public T consumeTouch(final boolean consume) {
        this.consumeTouch = consume;
        return this.self();
    }

    public T onClick(final Consumer<T> handler) {
        this.onClick = handler;
        return this.self();
    }

    public T onHover(final Consumer<T> handler) {
        this.onHover = handler;
        return this.self();
    }

    public T tag(final Object value) {
        this.tag = value;
        return this.self();
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    /** Pulls live state such as labels, progress or visibility into this view before measure. */
    protected void onUpdate() {}

    /** Sets {@link #contentWidth}/{@link #contentHeight} from the space inside the padding; the default is empty. */
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = 0;
    }

    /** Positions children within the content box. No-op for leaves. */
    protected void onLayout() {}

    /** Paints the view's own content (text, image, children). No-op for a bare view. */
    protected void onDraw(final Canvas canvas) {}

    protected boolean onPress(final double mx, final double my) { return false; }

    @SuppressWarnings("unchecked")
    protected final T self() {
        return (T) this;
    }
}
