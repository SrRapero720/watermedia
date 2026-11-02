package org.watermedia.bootstrap.app.element;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.test.support.RecordingBackend;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how a {@link ListRow} places its children, binds live state, decides whether it draws as selected and fits
 * its title into the width left beside its prefix and right side.
 */
@DisplayName("ListRow")
public class ListRowTest {

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Placement")
    class Placement {

        @Test
        @DisplayName("children sit from the left edge, from the right edge when negative, or centered")
        void places() {
            final Box left = new Box().size(10, 10);
            final Box right = new Box().size(26, 26);
            final ListRow row = new ListRow().place(left, 12, ListRow.CENTER).place(right, -44, 14).size(300, 56);
            row.measure(300, 56);
            row.layout(100, 20);
            assertEquals(112, left.left());
            assertEquals(20 + 23, left.top(), "a 10 px child centers in a 56 px row");
            assertEquals(100 + 300 - 44, right.left());
            assertEquals(34, right.top());
        }
    }

    @Nested
    @DisplayName("State")
    class State {

        @Test
        @DisplayName("the binder runs on every update, before measure")
        void binds() {
            final List<String> calls = new ArrayList<>();
            final ListRow row = new ListRow().binder(r -> calls.add("bind"));
            row.update();
            row.update();
            assertEquals(List.of("bind", "bind"), calls);
        }

        @Test
        @DisplayName("a picked supplier overrides the list's selection stamp")
        void picked() {
            final ListRow listed = new ListRow();
            assertFalse(listed.isPicked());
            listed.selected(true);
            assertTrue(listed.isPicked());

            final boolean[] lod = { false };
            final ListRow free = new ListRow().picked(() -> lod[0]);
            free.selected(true);
            assertFalse(free.isPicked(), "a row outside a list ignores a stale stamp");
            lod[0] = true;
            assertTrue(free.isPicked());
        }
    }

    @Nested
    @DisplayName("Texts")
    class Texts {
        private static final String LONG = "https://example.com/a/very/long/path/to/a/media/file/that/never/fits.webm";

        private TextRenderer text;
        private Canvas canvas;

        @BeforeEach
        void setup() {
            new RecordingBackend().install();
            this.text = new TextRenderer();
            this.canvas = new Canvas(this.text).viewport(400, 100);
        }

        @AfterEach
        void teardown() {
            RecordingBackend.uninstall();
        }

        @Test
        @DisplayName("a title past its budget ends in an ellipsis inside the row; a short one draws whole")
        void truncates() {
            final ListRow row = this.drawn(new ListRow().title(LONG).textAt(10, ListRow.CENTER).textRight(60, 20));
            final String shown = this.text.truncateToWidth(LONG, 130, AppTheme.TEXT_BODY, Font.PLAIN);
            assertTrue(shown.endsWith("...") && shown.length() < LONG.length());
            assertEquals(row.left() + 10 + this.text.width(shown, AppTheme.TEXT_BODY, Font.PLAIN), row.titleEnd());
            assertTrue(row.titleEnd() <= row.left() + 200 - 60, "the title stops before the reserved right side");

            final ListRow small = this.drawn(new ListRow().title("Hi").textAt(10, ListRow.CENTER).textRight(60, 20));
            assertEquals(small.left() + 10 + this.text.width("Hi", AppTheme.TEXT_BODY, Font.PLAIN), small.titleEnd());
        }

        @Test
        @DisplayName("a prefix takes its width and a 6 px gap from the title's budget")
        void prefixBudget() {
            final ListRow row = this.drawn(new ListRow().title(LONG).prefix(() -> "#12", AppTheme.NEON).textAt(10, ListRow.CENTER)
                    .textRight(60, 20));
            final int preW = this.text.width("#12", AppTheme.TEXT_BODY, Font.BOLD);
            final String shown = this.text.truncateToWidth(LONG, 130 - preW - 6, AppTheme.TEXT_BODY, Font.PLAIN);
            assertEquals(row.left() + 10 + preW + 6 + this.text.width(shown, AppTheme.TEXT_BODY, Font.PLAIN), row.titleEnd());
            assertTrue(row.titleEnd() <= row.left() + 200 - 60);
        }

        @Test
        @DisplayName("a squeezed row still gives the title its minimum width")
        void minimum() {
            final ListRow row = this.drawn(new ListRow().title(LONG).textAt(10, ListRow.CENTER).textRight(195, 40));
            final String shown = this.text.truncateToWidth(LONG, 40, AppTheme.TEXT_BODY, Font.PLAIN);
            assertEquals(row.left() + 10 + this.text.width(shown, AppTheme.TEXT_BODY, Font.PLAIN), row.titleEnd());
        }

        // A 200 x 40 ROW AT (30, 10), DRAWN ONCE
        private ListRow drawn(final ListRow row) {
            row.size(200, 40);
            row.measure(200, 40);
            row.layout(30, 10);
            row.draw(this.canvas);
            return row;
        }
    }
}
