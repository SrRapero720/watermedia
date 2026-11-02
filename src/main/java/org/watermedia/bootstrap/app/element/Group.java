package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.AppContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Base container that draws its children in order and dispatches input topmost-first, so later children win hits.
 * Subclasses measure and place the children in {@link #onMeasure} and {@link #onLayout}.
 */
public abstract class Group<T extends Group<T>> extends Element<T> {

    protected final List<Element<?>> children = new ArrayList<>();

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public T add(final Element<?> child) {
        if (child != null) {
            child.parent = this;
            if (this.ctx != null) child.attach(this.ctx);
            this.children.add(child);
        }
        return this.self();
    }

    public T remove(final Element<?> child) {
        if (this.children.remove(child)) child.parent = null;
        return this.self();
    }

    public T clear() {
        for (final Element<?> child: this.children) child.parent = null;
        this.children.clear();
        return this.self();
    }

    public List<Element<?>> children() { return this.children; }

    @Override
    public void attach(final AppContext context) {
        super.attach(context);
        for (final Element<?> child: this.children) child.attach(context);
    }

    @Override
    public void update() {
        super.update();
        // HIDDEN CHILDREN UPDATE TOO, SO A HIDDEN VIEW CAN BIND ITS OWN VISIBILITY; THE INDEXED LOOP LETS AN
        // onUpdate APPEND CHILDREN WITHOUT A ConcurrentModificationException
        for (int i = 0; i < this.children.size(); i++) this.children.get(i).update();
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        for (int i = this.children.size() - 1; i >= 0; i--) {
            if (this.children.get(i).dispatchClick(mx, my)) {
                // BLUR ANY FOCUSED TEXT INPUT THE CONSUMED CLICK DID NOT LAND IN: THE DISPATCH SHORT-CIRCUITS
                // TOPMOST-FIRST, SO THE CLICK NEVER REACHES THE SIBLINGS BELOW A CONSUMING ONE
                for (int j = this.children.size() - 1; j >= 0; j--) if (j != i) this.children.get(j).clearFocus();
                return true;
            }
        }
        if (!this.contains(mx, my)) return false;
        if (this.onClick != null) {
            this.onClick.accept(this.self());
            return true;
        }
        return this.consumeTouch;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.visible) return false;
        boolean consumed = false;
        // TOPMOST-FIRST: THE FIRST CHILD (IN PAINT ORDER FROM THE TOP) THAT CONSUMES THE HOVER WINS AND EVERY LAYER
        // BELOW IT IS FORCE-CLEARED, SO ELEMENTS UNDER A MODAL STOP REACTING
        for (int i = this.children.size() - 1; i >= 0; i--) {
            final Element<?> child = this.children.get(i);
            if (consumed) child.clearHover();
            else if (child.dispatchHover(mx, my)) consumed = true;
        }
        final boolean inside = this.enabled && this.contains(mx, my);
        this.hovered = inside;
        if (inside && this.onHover != null) this.onHover.accept(this.self());
        // A CONTAINER CONSUMES BY ITSELF ONLY WHEN IT IS INTERACTIVE: A BARE FULL-RECT FRAME (SCREEN OVERLAY, DIALOG
        // LAYER) MUST STAY HOVER-TRANSPARENT OR IT WOULD CLEAR EVERY LAYER BELOW IT
        return consumed || (inside && (this.consumeTouch || this.onClick != null || this.onHover != null));
    }

    @Override
    public void clearHover() {
        super.clearHover();
        for (final Element<?> child: this.children) child.clearHover();
    }

    @Override
    public void clearFocus() {
        for (final Element<?> child: this.children) child.clearFocus();
    }

    @Override
    public Element<?> dispatchPress(final double mx, final double my) {
        if (!this.visible || !this.enabled) return null;
        for (int i = this.children.size() - 1; i >= 0; i--) {
            final Element<?> hit = this.children.get(i).dispatchPress(mx, my);
            if (hit != null) return hit;
        }
        return this.contains(mx, my) && this.onPress(mx, my) ? this : null;
    }

    @Override
    public boolean textInputActive() {
        for (final Element<?> child: this.children) if (child.textInputActive()) return true;
        return false;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        for (int i = this.children.size() - 1; i >= 0; i--) if (this.children.get(i).dispatchScroll(mx, my, amount)) return true;
        return false;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        for (int i = this.children.size() - 1; i >= 0; i--) if (this.children.get(i).dispatchKey(key, action)) return true;
        return false;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        for (int i = this.children.size() - 1; i >= 0; i--) if (this.children.get(i).dispatchChar(codepoint)) return true;
        return false;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onDraw(final Canvas canvas) {
        for (final Element<?> child: this.children) child.draw(canvas);
    }
}
