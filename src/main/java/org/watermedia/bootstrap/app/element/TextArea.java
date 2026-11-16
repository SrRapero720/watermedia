package org.watermedia.bootstrap.app.element;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_END;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_HOME;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * A multi-line text box that soft wraps its value to the box width, edits at a caret placed by click or keys and
 * scrolls to keep that caret in view, reporting every edit through {@code onChange}. ENTER breaks the line, CTRL+V
 * pastes, and CTRL+ENTER, TAB and ESC reach the screen. It has no selection, copy, cut or undo. As a
 * {@link #singleLine} box it holds one paragraph and hands every ENTER to the screen.
 */
public final class TextArea extends Element<TextArea> {
    private static final long BLINK_MS = 500L;
    private static final int SCROLLBAR_SIZE = 6;
    private static final int EMPTY_CARET_GAP = 3;
    // CHARACTERS THAT END A WORD FOR THE CTRL MOVES AND ERASES, SO A LINK OR A PATH GOES ONE PART AT A TIME
    private static final String WORD_BREAKS = "/\\?&=#.:_-";
    private static final Color FILL = AppTheme.alpha(AppTheme.BG_1, 200);
    private static final Color THUMB = AppTheme.alpha(AppTheme.NEON, 130);

    private String value = "";
    private String placeholder = "";
    private Color accent = AppTheme.AMBER;
    private int maxLength = 2_000;
    private boolean singleLine;
    private float scale = AppTheme.TEXT_BODY;
    private Consumer<String> onChange;
    // CARET INDEX IN value; A TRAILING CARET AT A SOFT WRAP SHOWS AT THE END OF THE UPPER ROW, NOT AT THE LOWER ROW START
    private int caret;
    private boolean trailing;
    // X THAT CONSECUTIVE UP/DOWN MOVES AIM AT (-1 = NONE), SO A SHORT ROW ON THE WAY DOES NOT LOSE THE COLUMN
    private int column = -1;
    // THE NEXT LAYOUT SCROLLS THE CARET ROW INTO VIEW
    private boolean reveal;
    // INNER SIZE OF THE LAST LAYOUT (-1 = NONE YET); A BOX THAT CHANGES IT SHOWS THE CARET ROW AGAIN
    private int viewWidth = -1;
    private int viewport = -1;
    // A CTRL+ENTER PRESS WENT TO THE SCREEN, SO ITS REPEATS AND RELEASE GO THERE TOO, EVEN WHEN CTRL LIFTS FIRST
    private boolean submitting;
    private final ScrollState scroll = new ScrollState();
    // WRAPPED ROWS, REBUILT ONLY WHEN THE VALUE, THE WIDTH, THE TEXT SCALE OR THE UI SCALE (WHICH SHIFTS THE GLYPH
    // ADVANCES) CHANGE
    private final List<Row> rows = new ArrayList<>();
    private String wrapValue;
    private int wrapWidth = -1;
    private float wrapScale;
    private float wrapUi;
    // TEXT METRICS: NULL MEASURES THROUGH ctx.text; HEADLESS TESTS (NO GLYPH ATLAS) PLUG FIXED ONES
    Metrics metrics;

    public TextArea() {
        this.width = MAX_PARENT;
        this.padding = Spacing.hv(10, 8);
    }

    /** Sets the text and puts the caret at its end; the same text keeps the caret where it is. */
    public TextArea value(final String v) {
        final String next = v == null ? "" : this.singleLine ? v.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ') : v;
        if (next.equals(this.value)) return this;
        this.value = next;
        this.moveTo(next.length(), false);
        return this;
    }

    public TextArea placeholder(final String v) {
        this.placeholder = v == null ? "" : v;
        return this;
    }

    /** Sets the most characters the text holds, line breaks included. */
    public TextArea maxLength(final int value) {
        this.maxLength = Math.max(0, value);
        return this;
    }

    /** Keeps the text to one paragraph: every line break set, pasted or already held becomes a space and ENTER reaches the screen. */
    public TextArea singleLine(final boolean single) {
        this.singleLine = single;
        return this.value(this.value);
    }

    public TextArea accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public TextArea scale(final float value) {
        this.scale = value;
        return this;
    }

    public TextArea onChange(final Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    /** Inserts text at the caret as if typed: line breaks become one {@code \n}, tabs a space, and other controls drop. */
    public TextArea insert(final String text) {
        if (text == null) return this;
        final int room = this.maxLength - this.value.length();
        final StringBuilder typed = new StringBuilder();
        for (int i = 0; i < text.length() && typed.length() < room; i++) {
            final char c = text.charAt(i);
            // CRLF AND A LONE CR ARE ONE BREAK; SURROGATE PAIRS DROP, AS THE RENDERER DRAWS ONE GLYPH PER char
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') continue;
            if (c == '\r' || c == '\n') typed.append(this.singleLine ? ' ' : '\n');
            else if (c == '\t') typed.append(' ');
            else if (!Character.isISOControl(c) && !Character.isSurrogate(c)) typed.append(c);
        }
        if (!typed.isEmpty()) this.replace(this.caret, this.caret, typed.toString());
        return this;
    }

    public String value() { return this.value; }

    @Override
    public boolean textInputActive() {
        return this.focused;
    }

    @Override
    public void clearFocus() {
        if (this.focused) {
            this.focused = false;
            this.invalidate();
        }
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        if (!this.contains(mx, my)) {
            this.clearFocus();
            return false;
        }
        this.focused = true;
        this.wrap(this.innerWidth());
        // THE ROW UNDER THE POINTER IN SCROLLED CONTENT SPACE; A CLICK IN THE PADDING TAKES THE NEAREST ROW
        final int lineH = this.lineHeight();
        final int row = lineH <= 0 ? 0 : (int) Math.floor((my - this.innerTop() + this.scroll.offset) / lineH);
        this.place(MathUtil.clamp(row, 0, this.rows.size() - 1), (int) Math.round(mx - this.innerLeft()));
        this.column = -1;
        this.reveal = true;
        this.invalidate();
        return true;
    }

    @Override
    public boolean dispatchScroll(final double mx, final double my, final double amount) {
        // ONLY TEXT TALLER THAN THE BOX TAKES THE WHEEL, SO A SHORT NOTE LETS WHATEVER HOLDS IT SCROLL
        if (!this.visible || !this.contains(mx, my) || this.scroll.max <= 0) return false;
        if (this.scroll.scroll(amount)) this.invalidate();
        return true;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        if (!this.focused || !this.enabled) return false;
        final boolean ctrl = this.ctx != null && this.ctx.ctrlDown && !this.ctx.altDown;
        // EDITS AND MOVES ACT ON PRESS AND REPEAT; THE RELEASE IS SWALLOWED TOO SO IT NEVER FIRES A SCREEN SHORTCUT
        final boolean act = action != GLFW_RELEASE;
        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                // A SINGLE-LINE BOX HAS NO LINE TO BREAK; ELSE CTRL+ENTER SUBMITS THE SCREEN, AND THE PRESS DECIDES FOR
                // THE WHOLE KEYSTROKE
                if (this.singleLine) return false;
                if (action == GLFW_PRESS) this.submitting = ctrl;
                if (this.submitting) return false;
                if (act && this.value.length() < this.maxLength) this.replace(this.caret, this.caret, "\n");
            }
            case GLFW_KEY_LEFT -> {
                if (act) this.moveTo(ctrl ? wordStart(this.value, this.caret) : Math.max(0, this.caret - 1), false);
            }
            case GLFW_KEY_RIGHT -> {
                if (act) this.moveTo(ctrl ? wordEnd(this.value, this.caret) : Math.min(this.value.length(), this.caret + 1), false);
            }
            case GLFW_KEY_UP, GLFW_KEY_DOWN -> {
                if (act) this.vertical(key == GLFW_KEY_UP ? -1 : 1);
            }
            case GLFW_KEY_HOME -> {
                if (act) this.moveTo(ctrl ? 0 : this.rows.get(this.caretLine()).start(), false);
            }
            case GLFW_KEY_END -> {
                if (act && ctrl) this.moveTo(this.value.length(), false);
                else if (act) {
                    // A SOFT-WRAPPED ROW ENDS WHERE THE NEXT ONE STARTS, SO ITS END KEEPS THE CARET ON THIS ROW
                    final Row row = this.rows.get(this.caretLine());
                    this.moveTo(row.end(), row.soft());
                }
            }
            case GLFW_KEY_BACKSPACE -> {
                if (act && this.caret > 0) this.replace(ctrl ? wordStart(this.value, this.caret) : this.caret - 1, this.caret, "");
            }
            case GLFW_KEY_DELETE -> {
                if (act && this.caret < this.value.length())
                    this.replace(this.caret, ctrl ? wordEnd(this.value, this.caret) : this.caret + 1, "");
            }
            case GLFW_KEY_V -> {
                if (act && ctrl) this.insert(AppContext.clipboard());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        // CTRL COMBINATIONS ARE SHORTCUTS SUCH AS PASTE, NEVER TEXT; WINDOWS SENDS ALTGR AS CTRL+ALT, AND IT TYPES @, \ OR #
        if (!this.focused || !this.enabled || (this.ctx != null && this.ctx.ctrlDown && !this.ctx.altDown)) return false;
        // ANY BMP CHARACTER BUT CONTROLS: THE RENDERER DRAWS ONE GLYPH PER char, SO A SURROGATE PAIR CANNOT SHOW
        if (!Character.isBmpCodePoint(codepoint) || Character.isISOControl(codepoint) || Character.isSurrogate((char) codepoint)
                || this.value.length() >= this.maxLength) return false;
        this.replace(this.caret, this.caret, String.valueOf((char) codepoint));
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    int scrollOffset() { return this.scroll.offset; }

    @Override
    protected void onUpdate() {
        if (this.scroll.animate()) this.invalidate();
    }

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        // A TEXT AREA FILLS THE WIDTH IT GETS, SO THE WRAP WIDTH IS ALWAYS THE LAID-OUT ONE
        this.wrap(innerAvailWidth);
        this.contentWidth = innerAvailWidth;
        this.contentHeight = Math.min(this.rows.size() * this.lineHeight(), innerAvailHeight);
    }

    @Override
    protected void onLayout() {
        final int lineH = this.lineHeight();
        final int viewport = this.innerHeight();
        this.scroll.clamp(this.rows.size() * lineH - viewport);
        // A NEW HEIGHT ONLY CLAMPED THE OFFSET AND A NEW WIDTH REWRAPPED THE ROWS: EITHER MAY LEAVE THE CARET ROW OUT OF VIEW
        if (viewport != this.viewport || this.innerWidth() != this.viewWidth) this.reveal = true;
        this.viewport = viewport;
        this.viewWidth = this.innerWidth();
        // AN EDIT, MOVE, CLICK, RESIZE OR REWRAP SCROLLS THE LEAST THAT SHOWS THE CARET ROW; AN UNSIZED BOX WAITS FOR A
        // REAL LAYOUT
        if (!this.reveal || viewport <= 0) return;
        this.reveal = false;
        final int top = this.caretLine() * lineH;
        int next = this.scroll.offset;
        if (top < next) next = top;
        else if (top + lineH > next + viewport) next = top + lineH - viewport;
        next = MathUtil.clamp(next, 0, this.scroll.max);
        if (next != this.scroll.offset) this.scroll.jump(next);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        if (this.focused) canvas.glow(x, y, w, h, 0f, this.accent, Theme.GLOW_WEAK);
        canvas.fill(x, y, w, h, FILL);
        canvas.stroke(x, y, w, h, this.focused ? this.accent : AppTheme.STROKE,
                this.focused ? Theme.BORDER_ACTIVE : Theme.BORDER_HAIRLINE);

        // ONLY THE ROWS CROSSING THE VIEWPORT DRAW, EACH GLYPH CELL CENTERED IN ITS ROW; THE CLIP SPANS THE FULL BOX
        // WIDTH SO A CARET AFTER A HANGING SPACE STILL SHOWS IN THE RIGHT PADDING
        this.wrap(this.innerWidth());
        final int lineH = Math.max(1, this.lineHeight());
        final int th = canvas.textHeight(this.scale, false);
        final int textX = this.innerLeft();
        final int rowsY = this.innerTop() - this.scroll.offset + (lineH - th) / 2;
        canvas.pushClip(x, this.innerTop(), w, this.innerHeight());
        if (this.value.isEmpty()) {
            final String hint = this.width(this.placeholder) <= this.innerWidth()
                    ? this.placeholder : canvas.text().truncateToWidth(this.placeholder, this.innerWidth(), this.scale, Font.PLAIN);
            canvas.text(hint, textX, rowsY, AppTheme.TEXT_FAINT, this.scale, false);
        } else {
            final int last = Math.min(this.rows.size() - 1, (this.scroll.offset + this.innerHeight()) / lineH);
            for (int i = this.scroll.offset / lineH; i <= last; i++)
                canvas.text(this.rows.get(i).text(), textX, rowsY + i * lineH, AppTheme.TEXT, this.scale, false);
        }
        if (this.focused && (System.currentTimeMillis() / BLINK_MS) % 2 == 0) {
            // IN AN EMPTY BOX THE CARET STEPS INTO THE LEFT PADDING, CLEAR OF THE FIRST LETTER OF THE PLACEHOLDER
            final int line = this.caretLine();
            final Row row = this.rows.get(line);
            final int caretX = this.value.isEmpty() ? textX - EMPTY_CARET_GAP : textX + this.width(row.text().substring(0, this.caret - row.start()));
            canvas.fill(Math.min(caretX, textX + this.innerWidth()), rowsY + line * lineH, 1, th, this.accent);
        }
        canvas.popClip();

        if (this.scroll.max > 0) {
            final int trackH = h - 4;
            final int thumbH = this.scroll.thumbHeight(trackH);
            canvas.fill(x + w - SCROLLBAR_SIZE - 2, this.scroll.thumbY(y + 2, trackH, thumbH), SCROLLBAR_SIZE, thumbH, THUMB);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void wrap(final int width) {
        final float ui = this.ctx != null ? this.ctx.uiScale : 1f;
        if (width == this.wrapWidth && this.scale == this.wrapScale && ui == this.wrapUi
                && this.value.equals(this.wrapValue)) return;
        this.wrapValue = this.value;
        this.wrapWidth = width;
        this.wrapScale = this.scale;
        this.wrapUi = ui;
        this.rows.clear();

        // EVERY PARAGRAPH FILLS ROWS GREEDILY: THE LONGEST PREFIX THAT FITS, CUT AFTER ITS LAST SPACE (THE SPACE RIGHT
        // PAST THE EDGE MAY HANG THERE) OR MID-WORD WHEN IT HAS NONE; AN EMPTY PARAGRAPH IS ONE EMPTY ROW
        final String v = this.value;
        int start = 0;
        do {
            final int lineBreak = v.indexOf('\n', start);
            final int end = lineBreak < 0 ? v.length() : lineBreak;
            int from = start;
            while (end - from > 1) {
                // GALLOP, THEN BISECT: A PREFIX NEVER NARROWS AS IT GROWS; ONE CHAR IS FORCED SO A NARROW BOX ADVANCES
                final int rest = end - from;
                int fit = 1;
                int over = 0;
                int probe = 2;
                while (over == 0 && fit < rest) {
                    final int length = Math.min(probe, rest);
                    if (this.width(v.substring(from, from + length)) <= width) fit = length;
                    else over = length;
                    probe <<= 1;
                }
                if (over == 0) break;
                while (over - fit > 1) {
                    final int mid = (fit + over) >>> 1;
                    if (this.width(v.substring(from, from + mid)) <= width) fit = mid;
                    else over = mid;
                }
                final int space = v.lastIndexOf(' ', from + fit);
                final int cut = space >= from ? space + 1 : from + fit;
                this.rows.add(new Row(from, v.substring(from, cut), true));
                from = cut;
            }
            this.rows.add(new Row(from, v.substring(from, end), false));
            start = end + 1;
        } while (start <= v.length());
    }

    private int width(final String text) {
        if (this.metrics != null) return this.metrics.width().applyAsInt(text);
        return this.ctx != null && this.ctx.text != null ? this.ctx.text.width(text, this.scale) : 0;
    }

    private int lineHeight() {
        if (this.metrics != null) return this.metrics.line();
        return this.ctx != null && this.ctx.text != null ? this.ctx.text.lineHeight(this.scale) : 0;
    }

    private int caretLine() {
        // THE LAST ROW STARTING AT OR BEFORE THE CARET, OR THE ROW ABOVE FOR A TRAILING CARET AT ITS SOFT WRAP
        this.wrap(this.innerWidth());
        int lo = 0;
        int hi = this.rows.size() - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (this.rows.get(mid).start() <= this.caret) lo = mid;
            else hi = mid - 1;
        }
        return this.trailing && lo > 0 && this.rows.get(lo - 1).soft() && this.rows.get(lo).start() == this.caret ? lo - 1 : lo;
    }

    private void place(final int line, final int x) {
        // THE BOUNDARY NEAREST TO x: THE FIRST PREFIX REACHING x, OR THE ONE BEFORE IT WHEN x SITS CLOSER TO THAT ONE
        final Row row = this.rows.get(line);
        final String text = row.text();
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (this.width(text.substring(0, mid)) < x) lo = mid + 1;
            else hi = mid;
        }
        if (lo > 0 && x - this.width(text.substring(0, lo - 1)) < this.width(text.substring(0, lo)) - x) lo--;
        this.caret = row.start() + lo;
        this.trailing = row.soft() && lo == text.length();
    }

    private void vertical(final int delta) {
        final int line = this.caretLine();
        final Row row = this.rows.get(line);
        // THE FIRST MOVE OF A RUN RECORDS THE CARET X AND THE NEXT ONES AIM AT IT; PAST THE FIRST OR LAST ROW THE CARET
        // GOES TO THE TEXT START OR END
        if (this.column < 0) this.column = this.width(row.text().substring(0, this.caret - row.start()));
        final int target = line + delta;
        if (target >= 0 && target < this.rows.size()) this.place(target, this.column);
        else {
            this.caret = target < 0 ? 0 : this.value.length();
            this.trailing = false;
        }
        this.reveal = true;
        this.invalidate();
    }

    private void moveTo(final int index, final boolean trailing) {
        this.caret = index;
        this.trailing = trailing;
        this.column = -1;
        this.reveal = true;
        this.invalidate();
    }

    private void replace(final int from, final int to, final String text) {
        this.value = this.value.substring(0, from) + text + this.value.substring(to);
        this.moveTo(from + text.length(), false);
        if (this.onChange != null) this.onChange.accept(this.value);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static int wordStart(final String value, final int from) {
        // SKIPS SPACES, THEN THE WORD, THEN ONE BREAK CHARACTER BEFORE IT; A LINE BREAK RIGHT BEFORE IS A STEP OF ITS OWN
        int i = from;
        while (i > 0 && value.charAt(i - 1) == ' ') i--;
        while (i > 0 && !Character.isWhitespace(value.charAt(i - 1)) && WORD_BREAKS.indexOf(value.charAt(i - 1)) < 0) i--;
        if (i > 0 && WORD_BREAKS.indexOf(value.charAt(i - 1)) >= 0) i--;
        return i == from ? Math.max(0, from - 1) : i;
    }

    private static int wordEnd(final String value, final int from) {
        // THE MIRROR OF wordStart AFTER THE CARET
        int i = from;
        while (i < value.length() && value.charAt(i) == ' ') i++;
        while (i < value.length() && !Character.isWhitespace(value.charAt(i)) && WORD_BREAKS.indexOf(value.charAt(i)) < 0) i++;
        if (i < value.length() && WORD_BREAKS.indexOf(value.charAt(i)) >= 0) i++;
        return i == from ? Math.min(value.length(), from + 1) : i;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // FIXED TEXT METRICS FOR HEADLESS TESTS: THE WIDTH OF A RUN AND THE ROW PITCH
    record Metrics(ToIntFunction<String> width, int line) {}

    // ONE WRAPPED ROW: ITS FIRST INDEX IN THE VALUE, ITS TEXT WITHOUT THE LINE BREAK, AND WHETHER A SOFT WRAP ENDS IT
    private record Row(int start, String text, boolean soft) {
        int end() {
            return this.start + this.text.length();
        }
    }
}
