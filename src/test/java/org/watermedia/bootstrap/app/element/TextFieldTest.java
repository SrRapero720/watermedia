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
            assertTrue(field.dispatchChar('a'));
            assertTrue(field.dispatchChar('b'));
            assertTrue(field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS));
            assertTrue(field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_RELEASE));
            assertTrue(field.dispatchKey(GLFW_KEY_DELETE, GLFW_PRESS));
            assertEquals("", field.value());
            assertEquals(List.of("a", "ab", "a", ""), changes);
        }

        @Test
        @DisplayName("plain fields keep printable ASCII under the length cap; unicode ones take any character")
        void filtersCharacters() {
            final TextField ascii = new TextField().maxLength(2).focused(true);
            assertFalse(ascii.dispatchChar('ñ'));
            assertTrue(ascii.dispatchChar('1'));
            assertTrue(ascii.dispatchChar('2'));
            assertFalse(ascii.dispatchChar('3'));
            assertEquals("12", ascii.value());

            final TextField wide = new TextField().unicode(true).focused(true);
            assertTrue(wide.dispatchChar('ñ'));
            assertFalse(wide.dispatchChar(127));
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
            assertTrue(field.dispatchChar('@'));
            assertTrue(field.dispatchChar('\\'));
            assertEquals("a/b@\\", field.value());
            field.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS);
            assertEquals("a/b@", field.value());
        }

        @Test
        @DisplayName("a disabled or blurred field ignores keys and characters")
        void inactive() {
            final TextField blurred = new TextField();
            assertFalse(blurred.dispatchChar('a'));
            assertFalse(blurred.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS));
            final TextField disabled = new TextField().value("a").focused(true).enabled(false);
            assertFalse(disabled.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS));
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
            assertFalse(plain.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE));
            assertFalse(plain.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE));

            final List<String> actions = new ArrayList<>();
            final TextField wired = new TextField().onSubmit(() -> actions.add("submit")).onEscape(() -> actions.add("escape")).focused(true);
            assertTrue(wired.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS));
            assertTrue(wired.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE));
            assertTrue(wired.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE));
            assertEquals(List.of("submit", "escape"), actions);
        }

        @Test
        @DisplayName("a hover-focused field focuses when the pointer enters and stays focused when it leaves")
        void focusOnHover() {
            final TextField field = new TextField().focusOnHover(true).size(100, 30);
            field.measure(100, 30);
            field.layout(0, 0);
            assertTrue(field.dispatchHover(10, 10));
            assertTrue(field.focused());
            assertFalse(field.dispatchHover(300, 300));
            assertTrue(field.focused());
            assertFalse(field.dispatchClick(300, 300), "a click elsewhere blurs it");
            assertFalse(field.focused());
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
            assertFalse(kept.size(100, 30).dispatchClick(300, 300), "a click that reaches it outside still blurs it");
            assertFalse(kept.focused());
        }
    }
}
