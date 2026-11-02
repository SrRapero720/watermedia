package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Font;
import java.util.Locale;

/**
 * A section heading: a glowing neon tab, a bold uppercase label and an optional faint count or detail after it, shown
 * as given. The static {@link #draw} paints the same heading inside composites that lay out by hand.
 */
public final class SectionHead extends Element<SectionHead> {
    /** Height of the heading row. */
    public static final int HEIGHT = 30;
    /** Height of the {@link #compact()} heading row that list screens put above their rows. */
    public static final int COMPACT_HEIGHT = 24;

    private String label = "";
    private String count = "";
    // THE LABEL AS LAST GIVEN: SCREENS RE-BIND EVERY FRAME, AND AN UNCHANGED TEXT SKIPS THE UPPERCASE COPY
    private String labelSource = "";
    private boolean compact;

    public SectionHead() {
        this.width = MAX_PARENT;
        this.height = HEIGHT;
    }

    public SectionHead(final String label) {
        this();
        this.label(label);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public SectionHead label(final String value) {
        final String source = value == null ? "" : value;
        if (!source.equals(this.labelSource)) {
            this.labelSource = source;
            this.label = source.toUpperCase(Locale.ROOT);
        }
        return this;
    }

    public SectionHead count(final String value) {
        this.count = value == null ? "" : value;
        return this;
    }

    /** Switches to the compact metrics: a 24 px row, a 20 px tab and tighter label spacing. */
    public SectionHead compact() {
        this.compact = true;
        this.height = COMPACT_HEIGHT;
        return this;
    }

    /** Paints a heading whose row starts at ({@code x}, {@code y}); the count is cut to fit {@code width}. */
    public static void draw(final Canvas canvas, final String label, final String count, final int x, final int y, final int width) {
        paint(canvas, label, count, x, y, width, 3, 21, 18, 12);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.compact) paint(canvas, this.label, this.count, this.left, this.top, this.measuredWidth, 2, 20, 12, 8);
        else draw(canvas, this.label, this.count, this.left, this.top, this.measuredWidth);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void paint(final Canvas canvas, final String label, final String count, final int x, final int y, final int width,
                              final int tabTop, final int tabH, final int labelX, final int countGap) {
        final int tabY = y + tabTop;
        canvas.fill(x, tabY, 4, tabH, AppTheme.NEON);
        canvas.glow(x, tabY, 4, tabH, 0f, AppTheme.NEON, Theme.GLOW_WEAK);
        canvas.text(label, x + labelX, tabY + Math.max(0, (tabH - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2),
                AppTheme.NEON, AppTheme.TEXT_SECTION, true);
        if (count == null || count.isEmpty()) return;
        final int countX = x + labelX + canvas.textWidth(label, AppTheme.TEXT_SECTION, true) + countGap;
        final String shown = canvas.text().truncateToWidth(count, x + width - countX, AppTheme.TEXT_BODY, Font.PLAIN);
        canvas.text(shown, countX, tabY + Math.max(0, (tabH - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                AppTheme.TEXT_FAINT, AppTheme.TEXT_BODY, false);
    }
}
