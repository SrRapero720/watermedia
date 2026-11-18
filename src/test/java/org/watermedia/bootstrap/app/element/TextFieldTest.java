package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.AppContext;

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

/** Verifies how a {@link TextField} takes focus, edits its value and hands ENTER and ESC to the screen or its actions. */
@DisplayName("TextField")
public class TextFieldTest {

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Editing")
    class Editing {

        @Test
        @DisplayName("typing appends and reports, backspace and delete erase on press and swallow the release")
        void typesAndErases() {
            final List<String> changes = new ArrayList<>();
            final TextField field = new TextField().onChange(changes::add).focused(true);
            assertTrue(field.dispatchChar('a'), "typed a");
            assertTrue(field.dispatchChar('b'), "typed b");
            assertTrue(field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS), "the field takes the BACKSPACE press");
            assertTrue(field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_RELEASE), "the release is swallowed");
            assertTrue(field.dispatchKey(GLFW_KEY_DELETE, GLFW_PRESS), "the field takes the DELETE press");
            assertEquals("", field.value());
            assertEquals(List.of("a", "ab", "a", ""), changes);
        }

        @Test
        @DisplayName("plain fields keep printable ASCII under the length cap; unicode ones take any character")
        void filtersCharacters() {
            final TextField ascii = new TextField().maxLength(2).focused(true);
            assertFalse(ascii.dispatchChar('ñ'), "a plain field takes only ASCII");
            assertTrue(ascii.dispatchChar('1'), "typed 1");
            assertTrue(ascii.dispatchChar('2'), "typed 2");
            assertFalse(ascii.dispatchChar('3'), "the cap is reached");
            assertEquals("12", ascii.value());

            final TextField wide = new TextField().unicode(true).focused(true);
            assertTrue(wide.dispatchChar('ñ'), "a unicode field takes a Latin letter");
            assertFalse(wide.dispatchChar(127), "DEL is a control");
            assertEquals("ñ", wide.value());
        }

        @Test
        @DisplayName("CTRL+BACKSPACE erases the last word of a link, one part at a time, and CTRL blocks typing")
        void erasesWords() {
            final AppContext ctx = new AppContext();
            final TextField field = new TextField().unicode(true).value("https://site.com/path name").focused(true);
            field.attach(ctx);
            ctx.ctrlDown = true;
            assertFalse(field.dispatchChar('x'), "a CTRL combination is a shortcut, not text");
            field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS);
            assertEquals("https://site.com/path ", field.value());
            field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS);
            assertEquals("https://site.com", field.value());
        }

        @Test
        @DisplayName("CTRL with ALT is AltGr on Windows: it types characters and erases one at a time")
        void altGr() {
            final AppContext ctx = new AppContext();
            final TextField field = new TextField().unicode(true).value("a/b").focused(true);
            field.attach(ctx);
            ctx.ctrlDown = true;
            ctx.altDown = true;
            assertTrue(field.dispatchChar('@'), "AltGr types @");
            assertTrue(field.dispatchChar('\\'), "AltGr types a backslash");
            assertEquals("a/b@\\", field.value());
            field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS);
            assertEquals("a/b@", field.value());
        }

        @Test
        @DisplayName("a disabled or blurred field ignores keys and characters")
        void inactive() {
            final TextField blurred = new TextField();
            assertFalse(blurred.dispatchChar('a'), "a blurred field takes no character");
            assertFalse(blurred.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS), "a blurred field takes no key");
            final TextField disabled = new TextField().value("a").focused(true).enabled(false);
            assertFalse(disabled.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS), "a disabled field takes no key");
            assertFalse(disabled.dispatchChar('x'), "a disabled field takes no character");
            assertEquals("a", disabled.value());
        }
    }

    @Nested
    @DisplayName("Keys and focus")
    class KeysAndFocus {

        @Test
        @DisplayName("ENTER and ESC reach the screen unless the field has actions, which run on release")
        void submitAndEscape() {
            final TextField plain = new TextField().focused(true);
            assertFalse(plain.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "a plain field leaves ENTER to the screen");
            assertFalse(plain.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "a plain field leaves ESC to the screen");

            final List<String> actions = new ArrayList<>();
            final TextField wired = new TextField().onSubmit(() -> actions.add("submit")).onEscape(() -> actions.add("escape")).focused(true);
            assertTrue(wired.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "the field takes the ENTER press");
            assertEquals(List.of(), actions, "the press runs nothing");
            assertTrue(wired.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "the field takes the ENTER release");
            assertTrue(wired.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "the field takes the ESC release");
            assertEquals(List.of("submit", "escape"), actions);
        }

        @Test
        @DisplayName("a hover-focused field focuses when the pointer enters and stays focused when it leaves")
        void focusOnHover() {
            final TextField field = new TextField().focusOnHover(true).size(100, 30);
            field.measure(100, 30);
            field.layout(0, 0);
            assertTrue(field.dispatchHover(10, 10), "the field takes the hover");
            assertTrue(field.focused(), "the pointer entering focused it");
            assertFalse(field.dispatchHover(300, 300), "a point outside passes on");
            assertTrue(field.focused(), "the pointer leaving kept the focus");
            assertFalse(field.dispatchClick(300, 300), "a click elsewhere passes on");
            assertFalse(field.focused(), "a click elsewhere blurs it");
        }

        @Test
        @DisplayName("a click another element takes blurs a plain field, but not one that keeps its focus")
        void clickTakenElsewhere() {
            final TextField plain = new TextField().size(100, 30).focused(true);
            final Box button = new Box().size(100, 30).consumeTouch(true);
            final Parent row = Parent.row().size(200, 30).add(plain).add(button);
            row.measure(200, 30);
            row.layout(0, 0);
            assertTrue(row.dispatchClick(150, 15), "the button takes the click");
            assertFalse(plain.focused(), "the consumed click swept the plain field");

            final TextField kept = new TextField().keepFocus(true).focused(true);
            kept.clearFocus();
            assertTrue(kept.focused(), "typing goes on after pressing a button or picking a row");
            assertFalse(kept.size(100, 30).dispatchClick(300, 300), "a click outside passes on");
            assertFalse(kept.focused(), "a click that reaches it outside still blurs it");
        }
    }
}
