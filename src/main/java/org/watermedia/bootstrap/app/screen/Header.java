package org.watermedia.bootstrap.app.screen;

import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.Assets;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Font;
import java.util.Locale;

/**
 * The screen header: the app banner, an amber separator, the uppercase screen name with an optional subtitle
 * and right tag, over a gradient band closed by a neon rule. A right slot hosts screen-specific controls.
 */
public final class Header extends Group<Header> {
    /** Fixed header height in pixels. */
    public static final int H = 124;
    private static final int BANNER_H = 100;

    private String name = "";
    private String sub;
    private String right;
    private Element<?> extra;

    public Header() {
        this.width = MAX_PARENT;
        this.height = H;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Sets the screen name (rendered bold uppercase). */
    public Header name(final String value) {
        this.name = value == null ? "" : value;
        return this;
    }

    /** Sets the optional subtitle rendered after the name; null or empty hides it. */
    public Header sub(final String value) {
        this.sub = value;
        return this;
    }

    /** Sets the optional right-aligned tag; null or empty hides it. */
    public Header right(final String value) {
        this.right = value;
        return this;
    }

    /** Places an element in the right slot (e.g. a screen action button); null clears the slot. */
    public Header rightSlot(final Element<?> element) {
        if (this.extra != null) this.remove(this.extra);
        this.extra = element;
        if (element != null) this.add(element);
        return this;
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
        // SLOT CHILDREN WRAP TO THEIR OWN SIZE; THE HEADER ITSELF IS A FIXED BAND
        for (final Element<?> child: this.children) {
            if (child.visible()) child.measure(innerAvailWidth, innerAvailHeight);
        }
        this.contentWidth = 0;
        this.contentHeight = H;
    }

    @Override
    protected void onLayout() {
        // THE RIGHT SLOT SHARES THE RIGHT TAG'S ANCHOR (32px INSET, y+25)
        for (final Element<?> child: this.children) {
            if (!child.visible()) continue;
            child.layout(this.left + this.measuredWidth - child.measuredWidth() - 32, this.top + 25);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        if (this.ctx == null || this.ctx.text == null) {
            super.onDraw(canvas);
            return;
        }
        final int x = this.left;
        final int y = this.top;
        final int w = this.measuredWidth;
        canvas.gradientV(x, y, w, H,
                AppTheme.BG_2.getRed() / 255f, AppTheme.BG_2.getGreen() / 255f, AppTheme.BG_2.getBlue() / 255f, 0.86f,
                AppTheme.BG_1.getRed() / 255f, AppTheme.BG_1.getGreen() / 255f, AppTheme.BG_1.getBlue() / 255f, 0.55f);
        canvas.lineH(x, y + H - 7, w, AppTheme.NEON, 2f);
        canvas.lineH(x, y + H - 2, w, AppTheme.NEON_DARK, 1f);
        canvas.glow(x, y + H - 8, w, 4, 0f, AppTheme.NEON, 0.16f);

        // THE BANNER FITS bannerW x BANNER_H; WITHOUT ITS TEXTURE THE SEPARATOR STILL SITS AFTER THE FULL bannerW
        final Assets assets = this.ctx.assets;
        final boolean banner = assets.bannerId > 0 && assets.bannerWidth > 0 && assets.bannerHeight > 0;
        final int bannerX = x + 18;
        final int bannerW = MathUtil.clamp((int) (w * 0.40f), 430, 665);
        final float bannerScale = banner ? Math.min((float) bannerW / assets.bannerWidth, (float) BANNER_H / assets.bannerHeight) : 0f;
        final int drawnW = banner ? (int) (assets.bannerWidth * bannerScale) : bannerW;
        final int sepX = bannerX + drawnW + 14;
        canvas.fill(sepX, y + 38, 4, 42, AppTheme.AMBER);
        canvas.glow(sepX - 1, y + 38, 6, 42, 0f, AppTheme.AMBER, 0.24f);
        if (banner) {
            final int drawnH = (int) (assets.bannerHeight * bannerScale);
            canvas.image(assets.bannerId, bannerX, y + 10 + (BANNER_H - drawnH) / 2f, drawnW, drawnH, null);
        }

        final int titleX = sepX + 24;
        int rightReserve = this.right != null && !this.right.isEmpty()
                ? this.tagWidth(this.right) + 52
                : 24;
        if (this.extra != null && this.extra.visible()) rightReserve = Math.max(rightReserve, this.extra.measuredWidth() + 52);
        final float titleScale = AppTheme.TEXT_SECTION;
        final float subScale = AppTheme.TEXT_BODY;
        final int titleMaxW = Math.max(120, x + w - titleX - rightReserve);
        final String title = this.ctx.text.truncateToWidth(this.name.toUpperCase(Locale.ROOT), titleMaxW, titleScale, Font.BOLD);
        final int titleBoxY = y + 42;
        final int titleBoxH = 36;
        this.ctx.text.renderBold(title, titleX,
                titleBoxY + Math.max(0, (titleBoxH - this.ctx.text.glyphHeightBold(titleScale)) / 2),
                AppTheme.NEON_LIGHT, titleScale);
        if (this.sub != null && !this.sub.isEmpty()) {
            final int subX = titleX + this.ctx.text.widthBold(title, titleScale) + 18;
            final int subMaxW = Math.max(80, x + w - subX - rightReserve);
            final String subtitle = this.ctx.text.truncateToWidth(this.sub.toUpperCase(Locale.ROOT), subMaxW, subScale);
            this.ctx.text.render(subtitle, subX,
                    titleBoxY + Math.max(0, (titleBoxH - this.ctx.text.glyphHeight(subScale)) / 2),
                    AppTheme.TEXT_FAINT, subScale);
        }
        if (this.right != null && !this.right.isEmpty()) {
            final int tagW = this.tagWidth(this.right);
            final int tagX = x + w - tagW - 32;
            final int tagY = y + 25;
            canvas.fill(tagX, tagY, tagW, 24, AppTheme.alpha(AppTheme.BG_1, 170));
            canvas.stroke(tagX, tagY, tagW, 24, AppTheme.TEXT_SOFT, 1f);
            this.ctx.text.render(this.right.toUpperCase(Locale.ROOT), tagX + 14,
                    tagY + Math.max(0, (24 - this.ctx.text.glyphHeight(AppTheme.TEXT_BODY)) / 2) + 1,
                    AppTheme.TEXT_SOFT, AppTheme.TEXT_BODY);
        }
        super.onDraw(canvas);
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private int tagWidth(final String label) {
        return this.ctx.text.width(label.toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY) + 28;
    }
}
