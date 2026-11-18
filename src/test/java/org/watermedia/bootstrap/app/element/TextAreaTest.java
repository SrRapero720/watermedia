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
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Verifies how a {@link TextArea} edits at its caret, hands CTRL+ENTER, TAB and ESC to the screen, normalizes inserted
 * text, keeps a single-line box to one paragraph, moves the caret, wraps its rows, takes clicks and scrolls to the caret.
 */
@DisplayName("TextArea")
public class TextAreaTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A FOCUSED 120x76 BOX: THE PADDING LEAVES 100x60 INSIDE, SO WITH A 10 PX ADVANCE AND 20 PX ROWS IT HOLDS 10
    // CHARACTERS PER ROW AND SHOWS 3 ROWS; THE CARET STARTS AT THE END OF THE VALUE
    private static TextArea area(final String value) {
        final TextArea area = new TextArea().value(value).size(120, 76).focused(true);
        area.metrics = new TextArea.Metrics(text -> text.length() * 10, 20);
        relayout(area);
        return area;
    }

    private static void relayout(final TextArea area) {
        area.measure(500, 500);
        area.layout(0, 0);
    }

    // A PRESS AND ITS RELEASE, BOTH OF WHICH THE AREA MUST CONSUME
    private static void tap(final TextArea area, final int key) {
        assertTrue(area.dispatchKey(key, GLFW_PRESS), "the press is consumed");
        assertTrue(area.dispatchKey(key, GLFW_RELEASE), "the release is consumed");
    }

    private static void ctrlTap(final TextArea area, final AppContext ctx, final int key) {
        ctx.ctrlDown = true;
        tap(area, key);
        ctx.ctrlDown = false;
    }

    private static void type(final TextArea area, final String text) {
        for (final char c: text.toCharArray()) assertTrue(area.dispatchChar(c), "typed " + c);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Editing")
    class Editing {

        @Test
        @DisplayName("typing inserts and reports every edit, backspace erases before the caret and stops at the start")
        void typesAndReports() {
            final List<String> changes = new ArrayList<>();
            final TextArea area = area("").onChange(changes::add);
            type(area, "ab");
            tap(area, GLFW_KEY_BACKSPACE);
            tap(area, GLFW_KEY_BACKSPACE);
            tap(area, GLFW_KEY_BACKSPACE);
            assertEquals("", area.value());
            assertEquals(List.of("a", "ab", "a", ""), changes);
        }

        @Test
        @DisplayName("any BMP character but controls goes in under the length cap, which counts line breaks")
        void filtersCharacters() {
            final TextArea area = area("").maxLength(4);
            assertTrue(area.dispatchChar('ñ'), "a Latin letter");
            assertTrue(area.dispatchChar('中'), "a CJK letter");
            assertFalse(area.dispatchChar(31), "a C0 control");
            assertFalse(area.dispatchChar(127), "DEL");
            assertFalse(area.dispatchChar(0x85), "a C1 control");
            assertFalse(area.dispatchChar(0x1F600), "a supplementary character cannot render");
            assertFalse(area.dispatchChar(0xD800), "a lone surrogate");
            type(area, "ab");
            assertFalse(area.dispatchChar('c'), "the cap is reached");
            assertEquals("ñ中ab", area.value());

            final TextArea capped = area("ab").maxLength(3);
            tap(capped, GLFW_KEY_ENTER);
            tap(capped, GLFW_KEY_ENTER);
            assertFalse(capped.dispatchChar('x'), "the first line break filled the cap");
            assertEquals("ab\n", capped.value());
        }

        @Test
        @DisplayName("CTRL makes a shortcut instead of text, while CTRL with ALT is AltGr and types")
        void ctrlCombinations() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("a");
            area.attach(ctx);
            ctx.ctrlDown = true;
            assertFalse(area.dispatchChar('x'), "CTRL+X is a shortcut");
            ctx.altDown = true;
            assertTrue(area.dispatchChar('@'), "AltGr types");
            assertEquals("a@", area.value());
        }

        @Test
        @DisplayName("ENTER breaks the line on press and repeat and swallows the release")
        void enterBreaksLines() {
            final TextArea area = area("ab");
            tap(area, GLFW_KEY_ENTER);
            assertTrue(area.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT), "a held ENTER breaks again");
            assertTrue(area.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "its release is swallowed");
            type(area, "c");
            assertEquals("ab\n\nc", area.value());
        }

        @Test
        @DisplayName("backspace and delete erase around the caret and do nothing at the text edges")
        void erasesAtCaret() {
            final List<String> changes = new ArrayList<>();
            final TextArea area = area("abcd").onChange(changes::add);
            tap(area, GLFW_KEY_LEFT);
            tap(area, GLFW_KEY_LEFT);
            tap(area, GLFW_KEY_BACKSPACE);
            tap(area, GLFW_KEY_DELETE);
            type(area, "x");
            assertEquals("axd", area.value());

            final TextArea edges = area("ab").onChange(changes::add);
            tap(edges, GLFW_KEY_DELETE);
            tap(edges, GLFW_KEY_LEFT);
            tap(edges, GLFW_KEY_LEFT);
            tap(edges, GLFW_KEY_BACKSPACE);
            assertEquals("ab", edges.value());
            assertEquals(List.of("acd", "ad", "axd"), changes, "the edges report nothing");
        }

        @Test
        @DisplayName("CTRL+BACKSPACE and CTRL+DELETE erase a word, a link part, or a line break on its own")
        void erasesWords() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("one two\nthree");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_BACKSPACE);
            assertEquals("one two\n", area.value());
            ctrlTap(area, ctx, GLFW_KEY_BACKSPACE);
            assertEquals("one two", area.value(), "the line break goes alone, not with the word before it");
            ctrlTap(area, ctx, GLFW_KEY_BACKSPACE);
            assertEquals("one ", area.value());
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            ctrlTap(area, ctx, GLFW_KEY_DELETE);
            assertEquals(" ", area.value());

            final TextArea link = area("x a.b/c");
            link.attach(ctx);
            ctrlTap(link, ctx, GLFW_KEY_BACKSPACE);
            assertEquals("x a.b", link.value());
            ctrlTap(link, ctx, GLFW_KEY_BACKSPACE);
            assertEquals("x a", link.value());
        }

        @Test
        @DisplayName("a blurred or disabled area ignores keys and characters")
        void inactive() {
            final TextArea blurred = area("a").focused(false);
            assertFalse(blurred.dispatchChar('x'), "a blurred area takes no character");
            assertFalse(blurred.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS), "a blurred area takes no key");
            assertEquals("a", blurred.value());
            final TextArea disabled = area("a").enabled(false);
            assertFalse(disabled.dispatchKey(GLFW_KEY_BACKSPACE, GLFW_PRESS), "a disabled area takes no key");
            assertFalse(disabled.dispatchChar('x'), "a disabled area takes no character");
            assertEquals("a", disabled.value());
        }
    }

    @Nested
    @DisplayName("Screen keys")
    class ScreenKeys {

        @Test
        @DisplayName("CTRL+ENTER reaches the screen, and the press decides for the release whichever way CTRL moves in between")
        void ctrlEnterSubmits() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("ab");
            area.attach(ctx);
            ctx.ctrlDown = true;
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "CTRL+ENTER goes to the screen");
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT), "and so does its repeat");
            ctx.ctrlDown = false;
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "CTRL lifted before ENTER still submits");
            assertEquals("ab", area.value());

            assertTrue(area.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "a plain ENTER breaks the line");
            ctx.ctrlDown = true;
            assertTrue(area.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "a plain press keeps its release");
            assertEquals("ab\n", area.value());
        }

        @Test
        @DisplayName("TAB and ESC are never consumed")
        void tabAndEscape() {
            final TextArea area = area("ab");
            assertFalse(area.dispatchKey(GLFW_KEY_TAB, GLFW_PRESS), "TAB press");
            assertFalse(area.dispatchKey(GLFW_KEY_TAB, GLFW_RELEASE), "TAB release");
            assertFalse(area.dispatchKey(GLFW_KEY_ESCAPE, GLFW_PRESS), "ESC press");
            assertFalse(area.dispatchKey(GLFW_KEY_ESCAPE, GLFW_RELEASE), "ESC release");
            assertEquals("ab", area.value());
        }
    }

    @Nested
    @DisplayName("Insert")
    class Insert {

        @Test
        @DisplayName("insert types at the caret: one break per CRLF or CR, a space per tab, no controls or surrogates")
        void normalizes() {
            final List<String> changes = new ArrayList<>();
            final TextArea area = area("ab").onChange(changes::add);
            tap(area, GLFW_KEY_LEFT);
            area.insert("x\r\ny\rz\tw\u0007v😀");
            assertEquals("ax\ny\nz wvb", area.value());
            assertEquals(1, changes.size(), "one report for the whole insert");
            type(area, "Q");
            assertEquals("ax\ny\nz wvQb", area.value(), "the caret ends after the inserted text");
        }

        @Test
        @DisplayName("insert cuts to the room left and reports nothing when nothing went in")
        void cutsToRoom() {
            final List<String> changes = new ArrayList<>();
            final TextArea area = area("").maxLength(5).onChange(changes::add);
            area.insert("abc\r\ndef");
            assertEquals("abc\nd", area.value());
            area.insert("zz");
            area.insert(null);
            area.insert("\u0001\u0002");
            assertEquals(List.of("abc\nd"), changes, "only the first insert reported");
        }
    }

    @Nested
    @DisplayName("Single line")
    class SingleLine {

        @Test
        @DisplayName("ENTER never breaks the line and always reaches the screen, with or without CTRL")
        void enterReachesTheScreen() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("ab").singleLine(true);
            area.attach(ctx);
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "ENTER press");
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT), "ENTER repeat");
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "ENTER release");
            ctx.ctrlDown = true;
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS), "CTRL+ENTER press");
            assertFalse(area.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE), "CTRL+ENTER release");
            assertEquals("ab", area.value());
        }

        @Test
        @DisplayName("every line break set, inserted or already held becomes one space")
        void breaksBecomeSpaces() {
            final List<String> changes = new ArrayList<>();
            final TextArea area = area("").singleLine(true).onChange(changes::add);
            area.insert("a\r\nb\nc\rd");
            assertEquals("a b c d", area.value());
            assertEquals(List.of("a b c d"), changes);
            area.value("x\ny\r\nz");
            assertEquals("x y z", area.value());

            final TextArea held = area("one\ntwo");
            held.singleLine(true);
            assertEquals("one two", held.value(), "switching the mode on flattens the text it holds");
            held.singleLine(false);
            tap(held, GLFW_KEY_ENTER);
            assertEquals("one two\n", held.value(), "and switching it off breaks lines again");
        }

        @Test
        @DisplayName("a single-line box still soft wraps, so HOME, LEFT and BACKSPACE act on its rows as in a multi-line one")
        void stillWraps() {
            final TextArea area = area("hello world again").singleLine(true);
            tap(area, GLFW_KEY_HOME);
            type(area, "X");
            tap(area, GLFW_KEY_LEFT);
            tap(area, GLFW_KEY_BACKSPACE);
            assertEquals("hello worldXagain", area.value(), "HOME reached the start of the wrapped row, not of the text");
        }
    }

    @Nested
    @DisplayName("Caret")
    class Caret {

        @Test
        @DisplayName("LEFT and RIGHT step one character and stop at the text edges")
        void leftRight() {
            final TextArea area = area("abc");
            tap(area, GLFW_KEY_LEFT);
            tap(area, GLFW_KEY_LEFT);
            type(area, "x");
            tap(area, GLFW_KEY_RIGHT);
            type(area, "y");
            assertEquals("axbyc", area.value());
            for (int i = 0; i < 3; i++) tap(area, GLFW_KEY_RIGHT);
            type(area, "z");
            for (int i = 0; i < 9; i++) tap(area, GLFW_KEY_LEFT);
            type(area, "0");
            assertEquals("0axbycz", area.value());
        }

        @Test
        @DisplayName("CTRL+LEFT jumps to the start of a word and CTRL+RIGHT to its end")
        void wordJumps() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("one two three");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_LEFT);
            ctrlTap(area, ctx, GLFW_KEY_LEFT);
            type(area, "X");
            ctrlTap(area, ctx, GLFW_KEY_RIGHT);
            type(area, "Y");
            assertEquals("one XtwoY three", area.value());
        }

        @Test
        @DisplayName("HOME and END reach the ends of the visual row, and with CTRL the ends of the text")
        void homeEnd() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("hello world again");
            area.attach(ctx);
            tap(area, GLFW_KEY_HOME);
            type(area, "X");
            assertEquals("hello world Xagain", area.value(), "the last row starts after the second space");
            tap(area, GLFW_KEY_END);
            type(area, "Y");
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            type(area, "Z");
            ctrlTap(area, ctx, GLFW_KEY_END);
            type(area, "W");
            assertEquals("Zhello world XagainYW", area.value());
        }

        @Test
        @DisplayName("END on a soft-wrapped row keeps the caret on that row, so HOME returns to its start")
        void endKeepsSoftRow() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("hello world again");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            tap(area, GLFW_KEY_END);
            tap(area, GLFW_KEY_HOME);
            type(area, "Y");
            assertEquals("Yhello world again", area.value());
            tap(area, GLFW_KEY_END);
            type(area, "X");
            assertEquals("Yhello Xworld again", area.value(), "END lands after the space that ends the row");
        }

        @Test
        @DisplayName("UP and DOWN move by row and keep the column across a shorter row until another action")
        void upDownKeepColumn() {
            final TextArea area = area("abcdef\nab\nabcdef");
            tap(area, GLFW_KEY_LEFT);
            tap(area, GLFW_KEY_UP);
            tap(area, GLFW_KEY_UP);
            type(area, "X");
            assertEquals("abcdeXf\nab\nabcdef", area.value());
            tap(area, GLFW_KEY_DOWN);
            type(area, "Y");
            assertEquals("abcdeXf\nabY\nabcdef", area.value());
        }

        @Test
        @DisplayName("UP on the first row goes to the text start and DOWN on the last row to its end")
        void upDownEdges() {
            final TextArea area = area("abc\ndef");
            tap(area, GLFW_KEY_DOWN);
            tap(area, GLFW_KEY_UP);
            tap(area, GLFW_KEY_UP);
            type(area, "X");
            assertEquals("Xabc\ndef", area.value());
            tap(area, GLFW_KEY_DOWN);
            tap(area, GLFW_KEY_DOWN);
            type(area, "Y");
            assertEquals("Xabc\ndefY", area.value());
        }
    }

    @Nested
    @DisplayName("Wrapping")
    class Wrapping {

        @Test
        @DisplayName("a row breaks after its last space that fits, and the space right past the edge hangs on it")
        void softWrap() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("hello world again");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            tap(area, GLFW_KEY_DOWN);
            type(area, "X");
            assertEquals("hello Xworld again", area.value());

            final TextArea hanging = area("aaaaaaaaaa bbb");
            hanging.attach(ctx);
            ctrlTap(hanging, ctx, GLFW_KEY_HOME);
            tap(hanging, GLFW_KEY_DOWN);
            type(hanging, "X");
            assertEquals("aaaaaaaaaa Xbbb", hanging.value());
        }

        @Test
        @DisplayName("a word longer than a row breaks at the row width, and line breaks make rows of their own")
        void hardBreaks() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("abcdefghijklmnopqrstuvwxy");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            tap(area, GLFW_KEY_DOWN);
            tap(area, GLFW_KEY_DOWN);
            type(area, "X");
            assertEquals("abcdefghijklmnopqrstXuvwxy", area.value());

            final TextArea lines = area("ab\n\ncd");
            lines.attach(ctx);
            ctrlTap(lines, ctx, GLFW_KEY_HOME);
            tap(lines, GLFW_KEY_DOWN);
            type(lines, "X");
            assertEquals("ab\nX\ncd", lines.value(), "the empty paragraph is a row");
        }
    }

    @Nested
    @DisplayName("Mouse")
    class Mouse {

        @Test
        @DisplayName("a click focuses and puts the caret at the nearest boundary of the row under it")
        void clickPlacesCaret() {
            final TextArea area = area("hello world again").focused(false);
            assertTrue(area.dispatchClick(33, 33), "row 1, 23 px in: nearer to the boundary after 2 characters");
            assertTrue(area.focused());
            type(area, "X");
            assertEquals("hello woXrld again", area.value());

            assertTrue(area.dispatchClick(105, 13), "past the end of row 0");
            type(area, "Y");
            assertEquals("hello YwoXrld again", area.value(), "the caret stays on the clicked row, after its space");

            assertTrue(area.dispatchClick(10, 75), "the bottom padding, under the last row");
            type(area, "Z");
            assertEquals("hello YwoXrld Zagain", area.value());
        }

        @Test
        @DisplayName("a click outside blurs and passes on, and clearFocus blurs too")
        void focusAndBlur() {
            final TextArea area = area("a").focused(false);
            assertFalse(area.textInputActive(), "the area starts blurred");
            assertTrue(area.dispatchClick(50, 30), "a click inside is taken");
            assertTrue(area.textInputActive(), "and focuses the area");
            assertFalse(area.dispatchClick(300, 300), "a click outside passes on");
            assertFalse(area.focused(), "and blurs the area");
            area.focused(true).clearFocus();
            assertFalse(area.textInputActive(), "clearFocus blurs the area");
        }
    }

    @Nested
    @DisplayName("Scrolling")
    class Scrolling {

        @Test
        @DisplayName("every edit and move scrolls the least that shows the caret row")
        void followsCaret() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("1\n2\n3\n4\n5\n6");
            area.attach(ctx);
            assertEquals(60, area.scrollOffset(), "six rows of 20 px in a 60 px view, caret on the last");
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            relayout(area);
            assertEquals(0, area.scrollOffset());
            for (int i = 0; i < 3; i++) tap(area, GLFW_KEY_DOWN);
            relayout(area);
            assertEquals(20, area.scrollOffset(), "row 3 lands at the bottom of the view");
            tap(area, GLFW_KEY_UP);
            relayout(area);
            assertEquals(20, area.scrollOffset(), "row 2 is already visible");
            ctrlTap(area, ctx, GLFW_KEY_END);
            tap(area, GLFW_KEY_ENTER);
            relayout(area);
            assertEquals(80, area.scrollOffset(), "the new seventh row comes into view");
        }

        @Test
        @DisplayName("a box that gets shorter or taller shows the caret row again")
        void followsCaretOnResize() {
            final AppContext ctx = new AppContext();
            final TextArea area = area("1\n2\n3\n4\n5\n6");
            area.attach(ctx);
            ctrlTap(area, ctx, GLFW_KEY_HOME);
            relayout(area);
            for (int i = 0; i < 4; i++) tap(area, GLFW_KEY_DOWN);
            relayout(area);
            assertEquals(40, area.scrollOffset(), "the third to fifth rows show, the caret on the last of them");

            area.size(120, 36);
            relayout(area);
            assertEquals(80, area.scrollOffset(), "the one row left is the caret row, not the old top row");

            area.size(120, 116);
            relayout(area);
            assertEquals(20, area.scrollOffset(), "five rows fit: the offset only follows its new range, the caret row stays in view");
        }

        @Test
        @DisplayName("a box that gets narrower rewraps and shows the caret row again")
        void followsCaretOnRewrap() {
            final TextArea area = area("hello world again");
            area.size(220, 36);
            relayout(area);
            assertEquals(0, area.scrollOffset(), "one row of 20 characters");

            area.size(120, 36);
            relayout(area);
            assertEquals(40, area.scrollOffset(), "three rows now, and the caret is at the end of the last one");
        }

        @Test
        @DisplayName("the wheel is consumed only inside a box whose text overflows")
        void wheel() {
            final TextArea tall = area("1\n2\n3\n4\n5\n6");
            assertTrue(tall.dispatchScroll(50, 30, 1), "six rows overflow the three the box shows");
            assertFalse(tall.dispatchScroll(300, 300, 1), "a point outside the box");
            assertFalse(area("one").dispatchScroll(50, 30, 1), "a short text leaves the wheel to its holder");
        }
    }
}
