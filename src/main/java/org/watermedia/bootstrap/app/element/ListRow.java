package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A row of a list or a dialog: a frame styled by hover and selection, an optional leading icon, a title with an optional
 * prefix, a subtitle, a detail, custom marks and placed child elements, plus an optional dotted separator. A row inside
 * a {@link ListView} takes its selection from the list; any other row reads it from {@link #picked}.
 */
public class ListRow extends Group<ListRow> {
    /** Coordinate that centers a part vertically in the row. */
    public static final int CENTER = Integer.MIN_VALUE;
    private static final Color SEPARATOR = AppTheme.alpha(AppTheme.STROKE_BRIGHT, 90);

    // FRAME BY STATE; A null COLOR IS NOT PAINTED
    private Color fill;
    private Color hoverFill;
    private Color selectedFill;
    private Color stroke;
    private Color selectedStroke;
    private float strokeWidth = 1f;
    private float selectedStrokeWidth = 1f;
    private Color glow;
    private float glowAlpha;
    // A HALO UNDER THE FILL ONLY RIMS THE ROW; OVER IT, IT ALSO TINTS THE INSIDE
    private boolean glowUnder;
    private BooleanSupplier picked;
    private Consumer<ListRow> binder;

    private String icon;
    private int iconSize;
    private int iconX;
    private int iconY = CENTER;
    private Color iconColor;
    private Color iconSelectedColor;

    private Supplier<String> title = () -> "";
    private float titleScale = AppTheme.TEXT_BODY;
    private boolean titleBold;
    private Color titleColor = AppTheme.TEXT;
    private Color titleSelectedColor;
    private int textX;
    private int titleY = CENTER;
    // THE TITLE AND SUBTITLE END titleRight PX BEFORE THE RIGHT EDGE, BUT NEVER NARROWER THAN titleMin
    private int titleRight;
    private int titleMin;
    private Supplier<String> prefix;
    private Color prefixColor = AppTheme.NEON_LIGHT;
    private int titleEnd;

    private Supplier<String> subtitle;
    private float subtitleScale = AppTheme.TEXT_SUBTITLE;
    private Color subtitleColor = AppTheme.TEXT_FAINT;
    private int subtitleY;

    private Supplier<String> detail;
    private Color detailColor = AppTheme.TEXT_FAINT;
    private Color detailSelectedColor;
    // > 0: THE DETAIL STARTS THAT FAR FROM THE RIGHT EDGE; OTHERWISE IT ENDS detailInset PX BEFORE IT
    private int detailColumn;
    private int detailInset = 12;

    private Mark lead;
    private Mark trail;
    private final List<Placement> placements = new ArrayList<>();
    private int separatorX = -1;
    private boolean separator = true;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets the fill at rest, while hovered and while selected; a null one falls back to the rest fill or to none. */
    public ListRow fill(final Color normal, final Color hovered, final Color selected) {
        this.fill = normal;
        this.hoverFill = hovered;
        this.selectedFill = selected;
        return this;
    }

    /** Sets the outline color and width at rest and while selected; a null color draws no outline in that state. */
    public ListRow outline(final Color normal, final Color selected, final float width, final float selectedWidth) {
        this.stroke = normal;
        this.selectedStroke = selected;
        this.strokeWidth = width;
        this.selectedStrokeWidth = selectedWidth;
        return this;
    }

    /** Sets the halo of a selected row, drawn under the fill or over it. */
    public ListRow selectedGlow(final Color color, final float alpha, final boolean under) {
        this.glow = color;
        this.glowAlpha = alpha;
        this.glowUnder = under;
        return this;
    }

    /** Reads the selection from a supplier, for rows outside a {@link ListView}. */
    public ListRow picked(final BooleanSupplier value) {
        this.picked = value;
        return this;
    }

    /** Runs before every measure to pull live state, such as texts, colors and placements, into the row. */
    public ListRow binder(final Consumer<ListRow> value) {
        this.binder = value;
        return this;
    }

    /** Sets the leading pixel icon at {@code x}, and {@code y} or {@link #CENTER}; a null name removes it. */
    public ListRow icon(final String name, final int size, final int x, final int y, final Color color, final Color selectedColor) {
        this.icon = name;
        this.iconSize = size;
        this.iconX = x;
        this.iconY = y;
        this.iconColor = color;
        this.iconSelectedColor = selectedColor;
        return this;
    }

    public ListRow title(final String value) {
        final String text = value == null ? "" : value;
        this.title = () -> text;
        return this;
    }

    public ListRow title(final Supplier<String> value) {
        this.title = value;
        return this;
    }

    /** Sets the title type and colors; a null selected color keeps the rest one. */
    public ListRow titleStyle(final float scale, final boolean bold, final Color color, final Color selectedColor) {
        this.titleScale = scale;
        this.titleBold = bold;
        this.titleColor = color;
        this.titleSelectedColor = selectedColor;
        return this;
    }

    /** Places the texts at {@code x} and the title at {@code y} or {@link #CENTER}. */
    public ListRow textAt(final int x, final int y) {
        this.textX = x;
        this.titleY = y;
        return this;
    }

    /** Cuts the title and subtitle {@code right} px before the right edge, never narrower than {@code min} px. */
    public ListRow textRight(final int right, final int min) {
        this.titleRight = right;
        this.titleMin = min;
        return this;
    }

    /** Sets a bold label drawn before the title, which then shifts right by its width and a 6 px gap. */
    public ListRow prefix(final Supplier<String> value, final Color color) {
        this.prefix = value;
        this.prefixColor = color;
        return this;
    }

    public ListRow subtitle(final Supplier<String> value, final float scale, final Color color, final int y) {
        this.subtitle = value;
        this.subtitleScale = scale;
        this.subtitleColor = color;
        this.subtitleY = y;
        return this;
    }

    /** Sets a vertically centered body-size detail, right-aligned {@code inset} px before the edge. */
    public ListRow detail(final Supplier<String> value, final Color color, final Color selectedColor, final int inset) {
        this.detail = value;
        this.detailColor = color;
        this.detailSelectedColor = selectedColor;
        this.detailInset = inset;
        this.detailColumn = 0;
        return this;
    }

    /** Starts the detail {@code column} px before the right edge instead of right-aligning it. */
    public ListRow detailColumn(final int column) {
        this.detailColumn = column;
        return this;
    }

    /** Sets a custom part painted after the icon and before the texts, such as a thumbnail. */
    public ListRow lead(final Mark mark) {
        this.lead = mark;
        return this;
    }

    /** Sets a custom part painted after the texts and before the children, such as a tag. */
    public ListRow trail(final Mark mark) {
        this.trail = mark;
        return this;
    }

    /** Adds a child at {@code x} from the left edge, or from the right edge when negative, and {@code y} or {@link #CENTER}. */
    public ListRow place(final Element<?> child, final int x, final int y) {
        this.add(child);
        this.placements.add(new Placement(child, x, y));
        return this;
    }

    /** Draws a dotted hairline along the bottom edge from {@code x} to the right edge. */
    public ListRow separatorFrom(final int x) {
        this.separatorX = x;
        return this;
    }

    /** Shows or hides the separator, so the last row of a list can drop it. */
    public ListRow separator(final boolean value) {
        this.separator = value;
        return this;
    }

    /** Returns whether the row draws as selected: its {@link #picked} supplier, else the list's stamp. */
    public boolean isPicked() {
        return this.picked == null ? this.selected : this.picked.getAsBoolean();
    }

    /** Returns the x where the title drawn last ended, for marks that follow it. */
    public int titleEnd() {
        return this.titleEnd;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onUpdate() {
        if (this.binder != null) this.binder.accept(this);
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        for (final Placement placement: this.placements) placement.child().measure(innerAvailWidth, innerAvailHeight);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = innerAvailHeight;
    }

    @Override
    protected void onLayout() {
        for (final Placement placement: this.placements) {
            final Element<?> child = placement.child();
            final int x = placement.x() < 0 ? this.left + this.measuredWidth + placement.x() : this.left + placement.x();
            final int y = placement.y() == CENTER
                    ? this.top + Math.max(0, (this.measuredHeight - child.measuredHeight) / 2)
                    : this.top + placement.y();
            child.layout(x, y);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final boolean sel = this.isPicked();
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        final boolean halo = sel && this.glow != null;
        if (halo && this.glowUnder) canvas.glow(x, y, w, h, 0f, this.glow, this.glowAlpha);
        final Color frame = sel && this.selectedFill != null ? this.selectedFill
                : this.hovered && this.hoverFill != null ? this.hoverFill : this.fill;
        if (frame != null) canvas.fill(x, y, w, h, frame);
        final Color outline = sel && this.selectedStroke != null ? this.selectedStroke : this.stroke;
        if (outline != null) canvas.stroke(x, y, w, h, outline, sel ? this.selectedStrokeWidth : this.strokeWidth);
        if (halo && !this.glowUnder) canvas.glow(x, y, w, h, 0f, this.glow, this.glowAlpha);

        if (this.icon != null) {
            final int iy = this.iconY == CENTER ? y + Math.max(0, (h - this.iconSize) / 2) : y + this.iconY;
            canvas.icon(this.icon, x + this.iconX, iy, this.iconSize,
                    sel && this.iconSelectedColor != null ? this.iconSelectedColor : this.iconColor);
        }
        if (this.lead != null) this.lead.paint(canvas, this, x, y, w, h);
        this.paintTexts(canvas, sel, x, y, w, h);
        if (this.trail != null) this.trail.paint(canvas, this, x, y, w, h);
        super.onDraw(canvas);

        if (this.separatorX >= 0 && this.separator) {
            final int sy = y + h - 1;
            for (int dx = x + this.separatorX; dx < x + w; dx += 8) canvas.fill(dx, sy, 4, 1, SEPARATOR);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void paintTexts(final Canvas canvas, final boolean sel, final int x, final int y, final int w, final int h) {
        // TITLE (WITH ITS PREFIX), SUBTITLE AND DETAIL; THE TITLE AND SUBTITLE SHARE ONE WIDTH BUDGET
        final int maxW = Math.max(this.titleMin, w - this.titleRight - this.textX);
        final String text = this.title.get();
        this.titleEnd = x + this.textX;
        if (text != null && !text.isEmpty()) {
            final String pre = this.prefix == null ? null : this.prefix.get();
            final boolean prefixed = pre != null && !pre.isEmpty();
            final int preW = prefixed ? canvas.textWidth(pre, this.titleScale, true) : 0;
            // THE PREFIX CENTERS ON THE REGULAR GLYPH HEIGHT EVEN THOUGH IT IS BOLD, LIKE THE TITLE BESIDE IT
            if (prefixed) canvas.text(pre, x + this.textX, this.titleY == CENTER
                    ? y + Math.max(0, (h - canvas.textHeight(this.titleScale, false)) / 2) : y + this.titleY,
                    this.prefixColor, this.titleScale, true);
            final int tx = x + this.textX + (prefixed ? preW + 6 : 0);
            final int titleW = prefixed ? Math.max(this.titleMin, maxW - preW - 6) : maxW;
            final String shown = canvas.text().truncateToWidth(text, titleW, this.titleScale, this.titleBold ? Font.BOLD : Font.PLAIN);
            final int ty = this.titleY == CENTER
                    ? y + Math.max(0, (h - canvas.textHeight(this.titleScale, this.titleBold)) / 2)
                    : y + this.titleY;
            canvas.text(shown, tx, ty, sel && this.titleSelectedColor != null ? this.titleSelectedColor : this.titleColor,
                    this.titleScale, this.titleBold);
            this.titleEnd = tx + canvas.textWidth(shown, this.titleScale, this.titleBold);
        }
        if (this.subtitle != null) {
            final String sub = this.subtitle.get();
            if (sub != null && !sub.isEmpty())
                canvas.text(canvas.text().truncateToWidth(sub, maxW, this.subtitleScale), x + this.textX, y + this.subtitleY,
                        this.subtitleColor, this.subtitleScale, false);
        }
        if (this.detail != null) {
            final String value = this.detail.get();
            if (value != null && !value.isEmpty()) {
                final int dx = this.detailColumn > 0 ? x + w - this.detailColumn
                        : x + w - canvas.textWidth(value, AppTheme.TEXT_BODY, false) - this.detailInset;
                canvas.text(value, dx, y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, false)) / 2),
                        sel && this.detailSelectedColor != null ? this.detailSelectedColor : this.detailColor, AppTheme.TEXT_BODY, false);
            }
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    /** Paints a custom part of the row, such as a thumbnail or a tag, into the row's box. */
    @FunctionalInterface
    public interface Mark {
        void paint(Canvas canvas, ListRow row, int x, int y, int w, int h);
    }

    // A CHILD AND WHERE IT SITS: x FROM THE LEFT EDGE, OR FROM THE RIGHT ONE WHEN NEGATIVE
    private record Placement(Element<?> child, int x, int y) {}
}
