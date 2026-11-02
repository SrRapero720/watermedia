package org.watermedia.bootstrap.app.element;

/**
 * Non-color design tokens of the view system: spacings, radii, border widths, decorator tiers and control heights.
 * Colors and the type ramp live in {@code AppTheme}.
 */
public final class Theme {

    // SPACING SCALE (PADDING / MARGIN / GAPS)
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;
    public static final int SPACE_XL = 20;
    public static final int SPACE_XXL = 28;

    // CORNER RADIUS: THE UI DRAWS SQUARE CORNERS, SO NO VIEW APPLIES THESE RADII
    public static final float RADIUS_NONE = 0f;
    public static final float RADIUS_SM = 4f;
    public static final float RADIUS_MD = 8f;

    // BORDER WIDTHS
    public static final float BORDER_HAIRLINE = 1f;
    public static final float BORDER_ACTIVE = 1.5f;
    public static final float BORDER_HEAVY = 2f;

    // GLOW INTENSITY TIERS (ALPHA PASSED TO glowRect)
    public static final float GLOW_FAINT = 0.08f;
    public static final float GLOW_WEAK = 0.16f;
    public static final float GLOW_MED = 0.24f;
    public static final float GLOW_STRONG = 0.35f;

    // SHADOW / SCRIM ALPHA TIERS
    public static final float SHADOW_PANEL = 0.42f;
    public static final float SHADOW_DIALOG = 0.55f;
    public static final float SCRIM = 0.60f;

    // COMMON CONTROL / ROW HEIGHTS
    public static final int ROW_SM = 40;
    public static final int ROW_MD = 56;
    public static final int ROW_LG = 64;
    public static final int CONTROL = 26;
    public static final int BUTTON = 34;
    public static final int BUTTON_LG = 48;

    // TRAILING CHIP GEOMETRY SHARED BY Button AND Text, SO BOTH CHIPS KEEP THE SAME LOOK
    public static final int CHIP_PAD = 6;
    public static final int CHIP_H = 20;

    private Theme() {}
}
