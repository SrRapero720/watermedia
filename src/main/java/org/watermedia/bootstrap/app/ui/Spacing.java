package org.watermedia.bootstrap.app.ui;

/** Insets used as padding or margin. */
public record Spacing(int top, int right, int bottom, int left) {
    public static final Spacing ZERO = new Spacing(0, 0, 0, 0);

    public static Spacing all(final int value) {
        return new Spacing(value, value, value, value);
    }

    public static Spacing hv(final int horizontal, final int vertical) {
        return new Spacing(vertical, horizontal, vertical, horizontal);
    }

    public int horizontal() { return this.left + this.right; }
    public int vertical() { return this.top + this.bottom; }
}
