package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Verifies that a {@link Spinner}'s typed edit commits, cancels and erases without leaking key releases to the screen,
 * even from a row scrolled out of its list, and that an idle spinner leaves every key to the screen.
 */
@DisplayName("Spinner typed edit")
public class SpinnerTest {

    @Test
    @DisplayName("ENTER commits the typed number and its release stays in the spinner")
    void commits() {
        final List<Double> changes = new ArrayList<>();
        final Spinner spinner = editing(new Spinner().range(0, 100).onChange(changes::add));
        assertTrue(spinner.dispatchChar('4'), "typed 4");
        assertTrue(spinner.dispatchChar('2'), "typed 2");
        assertTrue(spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "the edit takes ENTER");
        assertTrue(spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "the release would activate the row again");
        assertFalse(spinner.textInputActive(), "the edit is closed");
        assertEquals(List.of(42d), changes, "\"042\" commits 42");
    }

    @Test
    @DisplayName("ESC cancels the edit and its release never reaches the screen's back shortcut")
    void cancels() {
        final Spinner spinner = editing(new Spinner().range(0, 100).value(7));
        assertTrue(spinner.dispatchChar('9'), "typed 9");
        assertTrue(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS), "the edit takes ESC");
        assertTrue(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "the release would leave the screen");
        assertFalse(spinner.textInputActive(), "the edit is closed");
        assertEquals(7d, spinner.value(), "the typed \"79\" is dropped");
    }

    @Test
    @DisplayName("a spinner scrolled out of its list still takes the release of the ESC that ended its edit")
    void releaseOutOfView() {
        final List<Spinner> built = new ArrayList<>();
        final ListView<String> list = new ListView<String>().rowHeight(40).rowFactory((item, index) -> {
            final Spinner spinner = new Spinner();
            built.add(spinner);
            return spinner;
        }).items(List.of("a", "b", "c", "d", "e", "f", "g", "h")).size(200, 80);
        settle(list);
        final Spinner first = built.get(0);
        first.dispatchClick(100, 20);
        assertTrue(first.textInputActive(), "a click on the value opens the edit");

        list.selection(0).moveSelection(7);
        settle(list);
        assertTrue(list.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS), "the out-of-view edit takes ESC");
        settle(list);
        assertTrue(list.children().contains(first), "the row left its list before the release arrived");
        assertTrue(list.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "the release would leave the screen");
        settle(list);
        assertFalse(list.children().contains(first), "with the key released the row leaves the window again");
    }

    @ParameterizedTest(name = "{displayName}: {0}")
    @ValueSource(strings = { "BACKSPACE", "DELETE" })
    @DisplayName("an erase key drops the last typed character and keeps its release")
    void erases(final String name) {
        final int key = "BACKSPACE".equals(name) ? GLFW_KEY_BACKSPACE : GLFW_KEY_DELETE;
        final List<Double> changes = new ArrayList<>();
        final Spinner spinner = editing(new Spinner().range(0, 1_000).onChange(changes::add));
        spinner.dispatchChar('5');
        spinner.dispatchChar('6');
        assertTrue(spinner.dispatchKey(key, GLFW_PRESS), "the edit takes the key");
        assertTrue(spinner.dispatchKey(key, GLFW_RELEASE), "the release stays in the spinner");
        spinner.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
        assertEquals(List.of(5d), changes, "\"056\" less its last character commits 5");
    }

    @Test
    @DisplayName("keys reach the screen while the spinner is not editing")
    void idle() {
        final Spinner spinner = new Spinner();
        assertFalse(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS), "ESC goes to the screen");
        assertFalse(spinner.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "and so does its release");
        assertFalse(spinner.dispatchChar('1'), "no edit takes the digit");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void settle(final ListView<String> list) {
        list.update();
        list.measure(200, 80);
        list.layout(0, 0);
    }

    // A CLICK ON THE VALUE, BETWEEN THE 24 PX STEP BOXES, OPENS THE TYPED EDIT; ITS BUFFER STARTS AS THE VALUE, SO A
    // SPINNER AT 0 READS "0" BEFORE ANY TYPING
    private static Spinner editing(final Spinner spinner) {
        spinner.size(200, 30);
        spinner.measure(200, 30);
        spinner.layout(0, 0);
        spinner.dispatchClick(100, 15);
        assertTrue(spinner.textInputActive(), "a click on the value opens the edit");
        return spinner;
    }
}
