package org.watermedia.bootstrap.app.screen;

import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Dialog;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ParentFrame;
import org.watermedia.bootstrap.app.ui.TextRenderer;

import java.util.List;

/**
 * Base class for retained screens: a view stacking the {@code body} built lazily by {@link #build()} and an
 * {@code overlay} {@link ParentFrame} for its dialogs. Input arrives only through the {@link Element} dispatch.
 */
public abstract class Screen extends Group<Screen> {
    protected final TextRenderer text;
    private final ParentFrame overlay = new ParentFrame();
    private Element<?> body;
    private boolean built;

    protected Screen(final TextRenderer text, final AppContext ctx) {
        this.text = text;
        this.ctx = ctx;
        this.width = MAX_PARENT;
        this.height = MAX_PARENT;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** The content slot built by {@link #build()}, or {@code null} before the first build. */
    public final Element<?> body() {
        return this.body;
    }

    /** The overlay slot hosting this screen's dialogs. */
    public final ParentFrame overlay() {
        return this.overlay;
    }

    /** Shows a dialog on this screen's overlay. */
    public final void showDialog(final Dialog dialog) {
        if (dialog != null) {
            this.overlay.add(dialog);
            this.invalidate();
        }
    }

    /** Hides a dialog previously shown on this screen's overlay. */
    public final void hideDialog(final Dialog dialog) {
        if (dialog != null) {
            this.overlay.remove(dialog);
            this.invalidate();
        }
    }

    /** Called when the screen becomes the active one. Builds the tree on first entry. */
    public void onEnter() {
        this.ensureBuilt();
    }

    /** Called when the screen stops being the active one. */
    public void onExit() {}

    /** The footer shortcuts for this screen. Default: none. */
    public List<Keybind> keybinds() {
        return List.of();
    }

    /** Whether this screen animates every frame. Default: no. */
    public boolean continuous() {
        return false;
    }

    /** Name of the screen that must stay mounted underneath this one, or {@code null} for none. */
    public String under() {
        return null;
    }

    /** Releases any media resources this screen holds (hot-swap hook). Default: no-op. */
    public void releaseMedia() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    /** Builds this screen's content tree. Called once, lazily, before the first measure. */
    protected abstract Element<?> build();

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.ensureBuilt();
        for (final Element<?> child: this.children) {
            if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
        }
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        for (final Element<?> child: this.children) {
            if (child.visible()) child.layout(this.innerLeft(), this.innerTop());
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void ensureBuilt() {
        if (this.built) return;
        this.built = true;
        this.body = this.build();
        // THE OVERLAY IS ADDED LAST SO ITS DIALOGS PAINT ABOVE THE BODY
        if (this.body != null) {
            this.body.size(MAX_PARENT, MAX_PARENT);
            this.add(this.body);
        }
        this.overlay.size(MAX_PARENT, MAX_PARENT);
        this.add(this.overlay);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** A footer shortcut: the key cap text such as {@code "ESC"}, empty for a plain hint, and its action label. */
    public record Keybind(String key, String label) {}
}
