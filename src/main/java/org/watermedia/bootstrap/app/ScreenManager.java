package org.watermedia.bootstrap.app;

import org.watermedia.bootstrap.app.screen.RootScreen;
import org.watermedia.bootstrap.app.screen.Screen;
import org.watermedia.bootstrap.app.screen.Screen.Keybind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Navigates between the registered screens: runs their exit/enter hooks and mounts the target in the root's
 * center slot, over the underlay it declares through {@link Screen#under()}.
 */
final class ScreenManager {
    private final Map<String, Screen> screens = new HashMap<>();
    private RootScreen root;
    private Screen current;
    private String currentName;
    private BiConsumer<String, String> onNavigate;

    public void register(final String name, final Screen screen) {
        this.screens.put(name, screen);
    }

    /** Sets the root screen whose center slot receives every navigated screen. */
    public void root(final RootScreen root) {
        this.root = root;
    }

    /** Sets the listener fired after every effective navigation with the previous and next screen names. */
    public void onNavigate(final BiConsumer<String, String> listener) {
        this.onNavigate = listener;
    }

    public void navigate(final String screenName) {
        if (screenName == null) {
            this.backToHome();
            return;
        }
        final Screen next = this.screens.get(screenName);
        if (next == null) return;
        // NAVIGATING TO THE ALREADY-CURRENT SCREEN MUST NOT RE-RUN onExit/onEnter (WHICH RESET ITS STATE)
        if (screenName.equals(this.currentName)) return;

        final String previous = this.currentName;
        if (this.current != null) this.current.onExit();
        this.currentName = screenName;
        this.current = next;
        this.current.onEnter();
        // A SCREEN DECLARING under() KEEPS THAT SCREEN MOUNTED (AND ANIMATING) BENEATH IT; THE TOP
        // MOUNT'S FULL-SLOT HIT BOX BLOCKS EVERY POINTER EVENT FROM REACHING THE UNDERLAY
        if (this.root != null) {
            final Screen under = this.current.under() != null ? this.screens.get(this.current.under()) : null;
            if (under != null && under != this.current) this.root.mountUnder(under, this.current);
            else this.root.mount(this.current);
        }
        if (this.onNavigate != null) this.onNavigate.accept(previous, screenName);
    }

    public void backToHome() {
        // THROUGH navigate() SO A BACK WHILE ON HOME HITS THE SAME-SCREEN GUARD AND KEEPS ITS STATE
        this.navigate("home");
    }

    public String currentName() {
        return this.currentName;
    }

    /** Returns the footer shortcuts of the active screen, or an empty list without one. */
    public List<Keybind> currentKeybinds() {
        return this.current != null ? this.current.keybinds() : List.of();
    }

    /** Releases the media of every registered screen before an engine hot swap. */
    public void releaseMediaAll() {
        for (final Screen screen: this.screens.values()) screen.releaseMedia();
    }
}
