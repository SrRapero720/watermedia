package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies how a {@link Dialog} sizes its panel: the content gets the height the other bands leave inside the window. */
@DisplayName("Dialog")
public class DialogTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // A 400 PX WIDE DIALOG WITH EVERY BAND: A 48 PX TITLE, A 30 PX HEADER (54 WITH ITS PADDING), A LIST OF 40 PX ROWS
    // INSIDE THE 20 PX CONTENT PADDING AND A 34 PX BUTTON (58 WITH THE FOOTER PADDING), SO THE BANDS TAKE 160 PX
    private static Dialog dialog(final ListView<String> list, final int rows) {
        final List<String> items = new ArrayList<>();
        for (int i = 0; i < rows; i++) items.add("row" + i);
        list.rowHeight(40).rowFactory((item, index) -> new Box()).items(items).width(Element.MAX_PARENT);
        return new Dialog().title("TITLE").header(new Box().size(100, 30)).content(list).actions(new Button("OK")).panelWidth(400);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Panel height")
    class PanelHeight {

        @Test
        @DisplayName("content that fits keeps its own height")
        void fits() {
            final ListView<String> list = new ListView<>();
            final Dialog dialog = dialog(list, 2);
            dialog.measure(800, 600);
            dialog.layout(0, 0);
            assertEquals(80, list.measuredHeight());
            assertEquals(160 + 40 + 80, dialog.panel().measuredHeight());
            assertEquals((600 - 280) / 2, dialog.panel().top(), "the panel is centered");
        }

        @Test
        @DisplayName("a list taller than what the bands leave shrinks to it and scrolls, with air around the panel")
        void clampsContent() {
            final ListView<String> list = new ListView<>();
            final Dialog dialog = dialog(list, 10);
            dialog.measure(800, 300);
            dialog.layout(0, 0);
            assertEquals(300 - 24, dialog.panel().measuredHeight(), "12 px stay free over and under the panel");
            assertEquals(300 - 24 - 160 - 40, list.measuredHeight());
            assertEquals(12, dialog.panel().top());
            assertTrue(list.dispatchScroll(list.left() + 5, list.top() + 5, -1), "the clipped list takes the wheel");
        }

        @Test
        @DisplayName("content of a fixed height is never squeezed, even when the window is too short for it")
        void fixedContent() {
            final Dialog dialog = new Dialog().title("TITLE").content(new Box().size(100, 400)).panelWidth(400);
            dialog.measure(800, 300);
            assertEquals(48 + 40 + 400, dialog.panel().measuredHeight());
        }
    }
}
