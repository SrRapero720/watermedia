package org.watermedia.bootstrap.app.ui;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/** Colors and text scales of the app UI. */
public final class AppTheme {

    public static final Color BG_0 = hex(0x04061A);
    public static final Color BG_1 = hex(0x0A0E1F);
    public static final Color BG_2 = hex(0x11172E);
    public static final Color BG_3 = hex(0x1B2342);
    public static final Color BG_2_ALPHA = rgba(17, 23, 46, 218);
    public static final Color BG_1_ALPHA = rgba(10, 14, 31, 218);

    public static final Color STROKE = rgba(110, 168, 255, 46);
    public static final Color STROKE_BRIGHT = rgba(110, 168, 255, 115);

    public static final Color TEXT = hex(0xD8E2FF);
    public static final Color TEXT_SOFT = hex(0x8AA0D0);
    public static final Color TEXT_FAINT = hex(0x5B6B94);

    public static final Color NEON = hex(0x6EA8FF);
    public static final Color NEON_LIGHT = hex(0x9BC2FF);
    public static final Color NEON_DARK = hex(0x4D7FD6);
    public static final Color CYAN = hex(0x5DD9FF);
    public static final Color GREEN = hex(0x6DFFB0);
    public static final Color AMBER = hex(0xFFC266);
    public static final Color RED = hex(0xFF6C8E);

    // TEXT SCALES BY ROLE; TINY, SUBTITLE AND BODY SHARE A SIZE ON PURPOSE SO EACH CAN BE RETUNED WITHOUT
    // TOUCHING ITS CALLERS
    private static final float FONT_BASE_PX = 20f;
    public static final float TEXT_TINY = textScale(12);
    public static final float TEXT_SUBTITLE = textScale(12);
    public static final float TEXT_BODY = textScale(12);
    public static final float TEXT_BUTTON = textScale(14);
    public static final float TEXT_SECTION = textScale(16);
    public static final float TEXT_DISPLAY = textScale(18);

    // alpha() RUNS MANY TIMES PER FRAME: ONE 256-SLOT ROW PER BASE COLOR AVOIDS BOTH A NEW Color AND A BOXED
    // INT KEY ON EVERY CALL. ONLY THE RENDER THREAD CALLS IT
    private static final Map<Color, Color[]> ALPHA_CACHE = new HashMap<>();

    private AppTheme() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static Color hex(final int rgb) {
        return new Color(rgb);
    }

    public static Color rgba(final int r, final int g, final int b, final int a) {
        return new Color(r, g, b, a);
    }

    public static Color alpha(final Color color, final int alpha) {
        final int a = alpha < 0 ? 0 : Math.min(alpha, 255);
        final Color[] row = ALPHA_CACHE.computeIfAbsent(color, base -> new Color[256]);
        final Color cached = row[a];
        if (cached != null) return cached;
        final Color made = new Color(color.getRed(), color.getGreen(), color.getBlue(), a);
        row[a] = made;
        return made;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static float textScale(final int px) {
        return px / FONT_BASE_PX;
    }
}
