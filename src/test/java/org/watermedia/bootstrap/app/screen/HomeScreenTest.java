package org.watermedia.bootstrap.app.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.LogUploader;
import org.watermedia.bootstrap.app.screen.BaseScreen.Keybind;
import org.watermedia.bootstrap.app.screen.HomeScreen.Action;
import org.watermedia.bootstrap.app.screen.HomeScreen.ReportStatus;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_REPEAT;

/**
 * Verifies the upload dialog of {@link HomeScreen}: which keys of each stage fire its primary action, move the focus or
 * type, the key hints of each stage, and the status its issue stage shows for each state of the uploader.
 */
@DisplayName("Home screen")
public class HomeScreenTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A HOME SCREEN BUILT WITHOUT A WINDOW, ITS UPLOAD DIALOG OPEN AT THE STAGE; THE NAVIGATOR RECORDS WHAT THE SCREEN FIRES
    // INSTEAD OF RUNNING THE UPLOADER, SO NOTHING IS UPLOADED OR OPENED. onEnter() STILL STARTS THE READ-ONLY WALK OF THE
    // CACHE FOLDER ON A WaterMedia-CacheSize THREAD, WHOSE MENU REBUILD WAITS IN A ctx QUEUE THAT NOTHING DRAINS
    private static HomeScreen screen(final AppContext ctx, final List<Action> fired, final int stage) {
        final HomeScreen screen = new HomeScreen(null, ctx, fired::add);
        screen.onEnter();
        ctx.upload.visible = true;
        ctx.upload.stage = stage;
        screen.update();
        return screen;
    }

    private static void tap(final HomeScreen screen, final int key) {
        screen.dispatchKey(key, GLFW_PRESS);
        screen.dispatchKey(key, GLFW_RELEASE);
    }

    private static void type(final HomeScreen screen, final String text) {
        for (final char c: text.toCharArray()) screen.dispatchChar(c);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Issue stage keys")
    class IssueKeys {

        @Test
        @DisplayName("the stage opens on the title, and ENTER there moves to the description, where it breaks lines")
        void enterWalksTheBoxes() {
            final AppContext ctx = new AppContext();
            final List<Action> fired = new ArrayList<>();
            final HomeScreen screen = screen(ctx, fired, 3);
            assertTrue(screen.textInputActive(), "a box holds the focus");
            type(screen, "Crash");
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE);
            type(screen, "one");
            assertEquals("one", ctx.upload.description, "the ENTER held in the title broke no line in the box it moved to");
            tap(screen, GLFW_KEY_KP_ENTER);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE);
            type(screen, "two");
            assertEquals("Crash", ctx.upload.title);
            assertEquals("one\n\n\ntwo", ctx.upload.description, "ENTER breaks lines there, on its repeats too");
            assertEquals(List.of(), fired);
        }

        @Test
        @DisplayName("a plain ENTER with nothing focused goes to the title and never fires the primary action")
        void plainEnterNeverSubmits() {
            final AppContext ctx = new AppContext();
            final List<Action> fired = new ArrayList<>();
            final HomeScreen screen = screen(ctx, fired, 3);
            type(screen, "Crash");
            // WHAT A CLICK ON THE DEAD SPACE OF THE PANEL DOES TO THE BOXES
            screen.clearFocus();
            assertFalse(screen.textInputActive(), "no box holds the focus");
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_REPEAT);
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE);
            assertEquals(List.of(), fired);
            type(screen, "!");
            assertEquals("Crash!", ctx.upload.title);
            assertEquals("", ctx.upload.description);
        }

        @Test
        @DisplayName("CTRL+ENTER fires the primary action from either box or from none, decided on the press")
        void ctrlEnterSubmits() {
            final AppContext ctx = new AppContext();
            final List<Action> fired = new ArrayList<>();
            final HomeScreen screen = screen(ctx, fired, 3);
            type(screen, "Crash");
            ctx.ctrlDown = true;
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            assertEquals(List.of(), fired, "the release submits, not the press");
            ctx.ctrlDown = false;
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE);
            assertEquals(List.of(Action.UPLOAD_LOGS), fired, "CTRL lifted before ENTER still submits");

            tap(screen, GLFW_KEY_TAB);
            ctx.ctrlDown = true;
            tap(screen, GLFW_KEY_ENTER);
            screen.clearFocus();
            tap(screen, GLFW_KEY_ENTER);
            ctx.ctrlDown = false;
            assertEquals(List.of(Action.UPLOAD_LOGS, Action.UPLOAD_LOGS, Action.UPLOAD_LOGS), fired);
            assertEquals("Crash", ctx.upload.title);
            assertEquals("", ctx.upload.description, "no line break went into the description");

            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_PRESS);
            ctx.ctrlDown = true;
            screen.dispatchKey(GLFW_KEY_ENTER, GLFW_RELEASE);
            ctx.ctrlDown = false;
            assertEquals(3, fired.size(), "CTRL pressed after ENTER does not turn it into a submit");
        }

        @Test
        @DisplayName("TAB swaps the boxes, and from no focus it starts at the title")
        void tabSwapsBoxes() {
            final AppContext ctx = new AppContext();
            final HomeScreen screen = screen(ctx, new ArrayList<>(), 3);
            type(screen, "a");
            tap(screen, GLFW_KEY_TAB);
            type(screen, "b");
            tap(screen, GLFW_KEY_TAB);
            type(screen, "c");
            screen.clearFocus();
            tap(screen, GLFW_KEY_TAB);
            type(screen, "d");
            assertEquals("acd", ctx.upload.title);
            assertEquals("b", ctx.upload.description);
        }

        @Test
        @DisplayName("the boxes follow the uploader when a new session clears its texts, and blur when the dialog closes")
        void bindsAndBlurs() {
            final AppContext ctx = new AppContext();
            final HomeScreen screen = screen(ctx, new ArrayList<>(), 3);
            type(screen, "old");
            ctx.upload.title = "";
            screen.update();
            type(screen, "new");
            assertEquals("new", ctx.upload.title, "the box dropped the cleared text instead of appending to it");

            ctx.upload.visible = false;
            screen.update();
            assertFalse(screen.textInputActive(), "the closed dialog holds no focus");
            type(screen, "x");
            assertEquals("new", ctx.upload.title);
        }
    }

    @Nested
    @DisplayName("Earlier stage keys")
    class EarlierKeys {

        @Test
        @DisplayName("a plain ENTER fires the primary action at the scan stage (Upload) and at the upload stage (Continue)")
        void plainEnterContinues() {
            final AppContext ctx = new AppContext();
            final List<Action> fired = new ArrayList<>();
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("latest.log", "Game log", null);
            entry.present = true;
            entry.valid = true;
            ctx.upload.files.add(entry);
            final HomeScreen screen = screen(ctx, fired, 1);
            assertFalse(screen.textInputActive(), "the boxes of the issue stage hold no focus here");
            tap(screen, GLFW_KEY_ENTER);
            assertEquals(List.of(Action.UPLOAD_LOGS), fired);

            entry.uploaded = true;
            ctx.upload.stage = 2;
            screen.update();
            tap(screen, GLFW_KEY_ENTER);
            assertEquals(List.of(Action.UPLOAD_LOGS, Action.UPLOAD_LOGS), fired);
        }

        @Test
        @DisplayName("the key hints cancel only at the scan stage: once the files go up, ESC closes")
        void escapeHint() {
            final AppContext ctx = new AppContext();
            final HomeScreen screen = screen(ctx, new ArrayList<>(), 1);
            assertEquals(List.of(new Keybind("ENTER", "Upload"), new Keybind("ESC", "Cancel")), screen.keybinds());
            ctx.upload.stage = 2;
            assertEquals(List.of(new Keybind("ENTER", "Continue"), new Keybind("ESC", "Close")), screen.keybinds());
            ctx.upload.stage = 3;
            assertEquals(List.of(new Keybind("TAB", "Field"), new Keybind("CTRL+ENTER", "Open issue"), new Keybind("ESC", "Close")), screen.keybinds());
        }

        @Test
        @DisplayName("ENTER does nothing while the stage has nothing to send")
        void nothingToSend() {
            final AppContext ctx = new AppContext();
            final List<Action> fired = new ArrayList<>();
            final HomeScreen screen = screen(ctx, fired, 1);
            tap(screen, GLFW_KEY_ENTER);
            assertEquals(List.of(), fired);
        }
    }

    @Nested
    @DisplayName("Report status")
    class Status {

        @Test
        @DisplayName("the report is preparing until it is gathered, and says so when its read failed")
        void preparing() {
            assertSame(ReportStatus.PREPARING, ReportStatus.of(true, false, false, false, false, false, false));
            assertSame(ReportStatus.PREPARING, ReportStatus.of(true, false, true, true, true, true, true));
            assertSame(ReportStatus.PREPARING, ReportStatus.of(false, false, true, false, false, false, false));
            assertSame(ReportStatus.REPORT_FAILED, ReportStatus.of(false, false, true, false, false, false, true));
            assertSame(ReportStatus.PREPARING, ReportStatus.of(true, false, true, false, false, false, true), "the retry is running");
        }

        @Test
        @DisplayName("a gathered report asks for the title, then reads ready")
        void ready() {
            assertSame(ReportStatus.UNTITLED, ReportStatus.of(false, true, false, false, false, false, false));
            assertSame(ReportStatus.READY, ReportStatus.of(false, true, true, false, false, false, false));
        }

        @Test
        @DisplayName("a running submit reads opening, over the result of an earlier one")
        void opening() {
            assertSame(ReportStatus.OPENING, ReportStatus.of(true, true, true, false, false, false, false));
            assertSame(ReportStatus.OPENING, ReportStatus.of(true, true, true, true, true, true, true));
        }

        @Test
        @DisplayName("an opened issue says whether the report reached the clipboard, until the title is emptied")
        void opened() {
            assertSame(ReportStatus.OPENED, ReportStatus.of(false, true, true, true, true, false, false));
            assertSame(ReportStatus.OPENED_UNCOPIED, ReportStatus.of(false, true, true, true, false, false, false));
            assertSame(ReportStatus.UNTITLED, ReportStatus.of(false, true, false, true, true, false, false));
        }

        @Test
        @DisplayName("a failed submit names what failed: the clipboard, the browser or both, even after an earlier issue opened")
        void failed() {
            assertSame(ReportStatus.CLIPBOARD_FAILED, ReportStatus.of(false, true, true, false, false, false, true));
            assertSame(ReportStatus.BROWSER_FAILED, ReportStatus.of(false, true, true, false, true, true, true));
            assertSame(ReportStatus.ALL_FAILED, ReportStatus.of(false, true, true, false, false, true, true));
            assertSame(ReportStatus.BROWSER_FAILED, ReportStatus.of(false, true, true, true, true, true, true));
            assertSame(ReportStatus.ALL_FAILED, ReportStatus.of(false, true, false, true, false, true, true));
        }
    }
}
