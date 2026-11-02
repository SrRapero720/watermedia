package org.watermedia.bootstrap.app.element;

import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Spacing;

import java.awt.Color;
import java.awt.Font;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * A single-line text box that takes focus on click (or on hover with {@link #focusOnHover}), appends typed characters
 * up to {@code maxLength} and reports every edit through {@code onChange}. BACKSPACE and DELETE erase a character, or a
 * word while CTRL is held; ENTER and ESC reach {@link #onSubmit} and {@link #onEscape} when set, else the screen. The
 * text starts at the left padding, after an optional leading icon.
 */
public final class TextField extends Element<TextField> {
    private static final long BLINK_MS = 500L;
    // CHARACTERS THAT END A WORD FOR CTRL+BACKSPACE, SO A URL ERASES ONE PATH OR QUERY PART AT A TIME
    private static final String WORD_BREAKS = "/\\?&=#.:_-";

    private String value = "";
    private String placeholder = "";
    private Color accent = AppTheme.AMBER;
    private int maxLength = 256;
    private boolean unicode;
    private Consumer<String> onChange;
    private Runnable onSubmit;
    private Runnable onEscape;
    private boolean focusOnHover;
    private boolean keepFocus;
    private float scale = AppTheme.TEXT_BODY;
    private Color fill = AppTheme.alpha(AppTheme.BG_1, 200);
    private Color stroke = AppTheme.STROKE;
    // null FOCUS COLORS FOLLOW THE ACCENT
    private Color focusStroke;
    private float strokeWidth = 1f;
    private float focusStrokeWidth = 1.5f;
    private float glowAlpha = Theme.GLOW_WEAK;
    // A HALO UNDER THE FILL ONLY RIMS THE BOX; OVER IT, IT ALSO TINTS THE INSIDE
    private boolean glowUnder = true;
    private Color caretColor;
    private int caretWidth = 1;
    // A WIDE CARET SITS 1 PX AFTER THE TEXT, OR 5 PX BEFORE AN EMPTY ONE, AND NEVER PAST THE RIGHT PADDING
    private boolean caretGap;
    private String icon;
    private Color iconColor = AppTheme.TEXT_FAINT;
    private int iconX;

    public TextField() {
        this.width = MAX_PARENT;
        this.padding = Spacing.hv(10, 8);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    public TextField value(final String v) {
        this.value = v == null ? "" : v;
        return this;
    }

    public TextField placeholder(final String v) {
        this.placeholder = v == null ? "" : v;
        return this;
    }

    public TextField accent(final Color color) {
        this.accent = color == null ? AppTheme.NEON : color;
        return this;
    }

    public TextField maxLength(final int value) {
        this.maxLength = Math.max(0, value);
        return this;
    }

    /** Accepts any character past the control range, not only printable ASCII. */
    public TextField unicode(final boolean value) {
        this.unicode = value;
        return this;
    }

    public TextField onChange(final Consumer<String> handler) {
        this.onChange = handler;
        return this;
    }

    /** Runs on the ENTER release while focused; without it ENTER reaches the screen. */
    public TextField onSubmit(final Runnable action) {
        this.onSubmit = action;
        return this;
    }

    /** Runs on the ESC release while focused; without it ESC reaches the screen. */
    public TextField onEscape(final Runnable action) {
        this.onEscape = action;
        return this;
    }

    /** Takes focus when the pointer enters the box; leaving it does not blur. */
    public TextField focusOnHover(final boolean value) {
        this.focusOnHover = value;
        return this;
    }

    /** Keeps the focus when another element takes a click, so typing goes on after pressing a button or picking a row. */
    public TextField keepFocus(final boolean value) {
        this.keepFocus = value;
        return this;
    }

    public TextField scale(final float value) {
        this.scale = value;
        return this;
    }

    /** Sets the box fill and its outline at rest and while focused; a null focus color follows the accent. */
    public TextField frame(final Color fill, final Color stroke, final Color focusStroke, final float width, final float focusWidth) {
        this.fill = fill;
        this.stroke = stroke;
        this.focusStroke = focusStroke;
        this.strokeWidth = width;
        this.focusStrokeWidth = focusWidth;
        return this;
    }

    /** Sets the accent halo of a focused box, drawn under the fill or over it. */
    public TextField focusGlow(final float alpha, final boolean under) {
        this.glowAlpha = alpha;
        this.glowUnder = under;
        return this;
    }

    /** Sets the caret color, null for the accent, its width and whether it keeps the wide-caret gap. */
    public TextField caret(final Color color, final int width, final boolean gap) {
        this.caretColor = color;
        this.caretWidth = Math.max(1, width);
        this.caretGap = gap;
        return this;
    }

    /** Sets a vertically centered 14 px pixel icon at {@code x}; a null name removes it. */
    public TextField icon(final String name, final Color color, final int x) {
        this.icon = name;
        this.iconColor = color;
        this.iconX = x;
        return this;
    }

    public String value() { return this.value; }

    @Override
    public boolean textInputActive() {
        return this.focused;
    }

    @Override
    public void clearFocus() {
        // A CONSUMED CLICK LANDED ELSEWHERE: DROP THE CARET AND STOP SUPPRESSING GLOBAL SHORTCUTS
        if (this.focused && !this.keepFocus) {
            this.focused = false;
            this.invalidate();
        }
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        if (!this.focusOnHover) return super.dispatchHover(mx, my);
        final boolean inside = this.visible && this.enabled && this.contains(mx, my);
        this.hovered = inside;
        if (inside && !this.focused) {
            this.focused = true;
            this.invalidate();
        }
        return inside;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        if (!this.visible || !this.enabled) return false;
        if (this.contains(mx, my)) {
            if (!this.focused) {
                this.focused = true;
                this.invalidate();
            }
            return true;
        }
        this.focused = false;
        return false;
    }

    @Override
    public boolean dispatchKey(final int key, final int action) {
        if (!this.focused || !this.enabled) return false;
        switch (key) {
            case GLFW_KEY_BACKSPACE, GLFW_KEY_DELETE -> {
                // EDITS ON PRESS AND REPEAT; THE RELEASE IS SWALLOWED TOO SO IT NEVER FIRES A SCREEN SHORTCUT
                if (action != GLFW_RELEASE && !this.value.isEmpty()) {
                    final boolean word = this.ctx != null && this.ctx.ctrlDown && !this.ctx.altDown;
                    this.value = word ? withoutLastWord(this.value) : this.value.substring(0, this.value.length() - 1);
                    this.changed();
                }
                return true;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (this.onSubmit == null) return false;
                if (action == GLFW_RELEASE) this.onSubmit.run();
                return true;
            }
            case GLFW_KEY_ESCAPE -> {
                if (this.onEscape == null) return false;
                if (action == GLFW_RELEASE) this.onEscape.run();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public boolean dispatchChar(final int codepoint) {
        // CTRL COMBINATIONS ARE SHORTCUTS SUCH AS PASTE, NEVER TEXT; WINDOWS SENDS ALTGR AS CTRL+ALT, AND IT TYPES @, \ OR #
        if (!this.focused || !this.enabled || (this.ctx != null && this.ctx.ctrlDown && !this.ctx.altDown)) return false;
        final boolean accepted = this.unicode ? codepoint >= 32 && codepoint != 127 : codepoint >= 32 && codepoint < 127;
        if (!accepted || this.value.length() >= this.maxLength) return false;
        this.value += new String(Character.toChars(codepoint));
        this.changed();
        return true;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        this.contentWidth = 0;
        this.contentHeight = this.ctx != null && this.ctx.text != null ? this.ctx.text.glyphHeight(this.scale) : 0;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        final int h = this.measuredHeight;
        if (this.focused && this.glowUnder) canvas.glow(x, y, w, h, 0f, this.accent, this.glowAlpha);
        if (this.fill != null) canvas.fill(x, y, w, h, this.fill);
        canvas.stroke(x, y, w, h, this.focused ? this.focusStroke == null ? this.accent : this.focusStroke : this.stroke,
                this.focused ? this.focusStrokeWidth : this.strokeWidth);
        if (this.focused && !this.glowUnder) canvas.glow(x, y, w, h, 0f, this.accent, this.glowAlpha);
        if (this.icon != null) canvas.icon(this.icon, x + this.iconX, y + (h - 14) / 2, 14, this.iconColor);

        final int avail = this.innerWidth();
        final boolean empty = this.value.isEmpty();
        final String text = empty ? this.placeholder : this.value;
        final String draw = text.isEmpty() || canvas.textWidth(text, this.scale, false) <= avail
                ? text : canvas.text().truncateToWidth(text, avail, this.scale, Font.PLAIN);
        final int th = canvas.textHeight(this.scale, false);
        final int ty = this.innerTop() + Math.max(0, (this.innerHeight() - th) / 2);
        if (!draw.isEmpty()) canvas.text(draw, this.innerLeft(), ty, empty ? AppTheme.TEXT_FAINT : AppTheme.TEXT, this.scale, false);
        if (this.focused && (System.currentTimeMillis() / BLINK_MS) % 2 == 0) {
            final int end = this.innerLeft() + (empty ? 0 : canvas.textWidth(draw, this.scale, false));
            final int caretX = this.caretGap ? Math.min(this.innerLeft() + avail, end + (empty ? -5 : 1)) : end;
            canvas.fill(caretX, ty, this.caretWidth, th, this.caretColor == null ? this.accent : this.caretColor);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private void changed() {
        this.invalidate();
        if (this.onChange != null) this.onChange.accept(this.value);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static String withoutLastWord(final String value) {
        // DROPS TRAILING SPACES, THEN THE LAST WORD, THEN ONE BREAK CHARACTER BEFORE IT
        int i = value.length();
        while (i > 0 && Character.isWhitespace(value.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(value.charAt(i - 1)) && WORD_BREAKS.indexOf(value.charAt(i - 1)) < 0) i--;
        if (i > 0 && WORD_BREAKS.indexOf(value.charAt(i - 1)) >= 0) i--;
        return value.substring(0, Math.max(0, i));
    }
}
