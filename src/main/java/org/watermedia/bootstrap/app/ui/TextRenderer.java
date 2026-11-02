package org.watermedia.bootstrap.app.ui;

import org.lwjgl.system.MemoryUtil;
import org.watermedia.bootstrap.app.render.RenderSystem;
import org.watermedia.tools.IOTool;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders text from glyph atlases cached per weight and physical size. */
public final class TextRenderer {

    private static final int DEFAULT_FONT_SIZE = 20;
    private static final int LINE_MARGIN = 5;
    private static final int GLYPH_PAD = 3;
    private static final float DEFAULT_TRACKING = 1f;

    private final Map<AtlasKey, FontAtlas> atlases = new HashMap<>();
    private final Font plainFont;
    private final Font boldFont;
    private final int defaultStyle;
    private final int baseSize;
    private int margin = LINE_MARGIN;
    // PHYSICAL PX PER LOGICAL PX: PICKS THE ATLAS RESOLUTION, WHILE QUADS AND METRICS STAY LOGICAL
    private volatile float uiScale = 1f;
    // ONE IMMUTABLE RECORD, SO A READER NEVER PAIRS A SCALE WITH ANOTHER CALL'S RUN
    private volatile LastRun last;

    public TextRenderer() {
        this.plainFont = loadBundledFont(Font.PLAIN);
        this.boldFont = loadBundledFont(Font.BOLD);
        this.defaultStyle = Font.PLAIN;
        this.baseSize = DEFAULT_FONT_SIZE;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public void margin(final int margin) {
        this.margin = Math.max(0, margin);
    }

    /** Drops every glyph atlas, whose textures died with their render context, so they rebuild lazily. */
    public void reset() {
        this.atlases.clear();
        this.last = null;
    }

    /** Sets the UI scale that picks the atlas resolution; quads and metrics stay in logical pixels. */
    public void setUiScale(final float scale) {
        this.uiScale = scale > 0f ? scale : 1f;
    }

    public int lineHeight(final float scale) {
        return this.glyphHeight(scale) + this.margin;
    }

    public int glyphHeight(final float scale) {
        final FontRun run = this.fontRun(scale, this.defaultStyle);
        return Math.max(1, Math.round(run.atlas.glyphHeight * run.drawScale));
    }

    public int glyphHeightBold(final float scale) {
        final FontRun run = this.fontRun(scale, Font.BOLD);
        return Math.max(1, Math.round(run.atlas.glyphHeight * run.drawScale));
    }

    /** Returns the distance from a {@code render()} y (the glyph-cell top) to the baseline, which aligns mixed sizes on one line. */
    public int baselineOffset(final float scale, final int style) {
        // CELL CENTERING WOULD DRIFT WITH THE UNEVEN DESCENDER SPACE OF EACH SIZE
        final FontRun run = this.fontRun(scale, style);
        return Math.round((run.atlas.baseline - run.atlas.fontTop) * run.drawScale);
    }

    public int baselineOffset(final float scale) {
        return this.baselineOffset(scale, this.defaultStyle);
    }

    public int baselineOffsetBold(final float scale) {
        return this.baselineOffset(scale, Font.BOLD);
    }

    public void render(final String text, final int x, final int y, final Color color) {
        this.render(text, (float) x, (float) y, color, 1f, this.defaultStyle);
    }

    public void render(final String text, final float x, final float y, final Color color, final float scale) {
        this.render(text, x, y, color, scale, this.defaultStyle);
    }

    public void renderBold(final String text, final float x, final float y, final Color color, final float scale) {
        this.render(text, x, y, color, scale, Font.BOLD);
    }

    public void render(final String text, final float x, final float y, final Color color, final float scale, final int style) {
        if (text == null || text.isEmpty()) return;
        RenderSystem.color(color);

        final FontRun run = this.fontRun(scale, style);
        float currentX = x;
        // INDEXED WITH charAt (NO toCharArray COPY PER FRAME): THIS IS THE HOTTEST UI PATH, LABELS REDRAW EVERY FRAME
        final int n = text.length();
        for (int i = 0; i < n; i++) {
            final CharGlyph g = run.atlas.glyph(text.charAt(i));
            if (g.textureId() > 0 && g.width() > 0 && g.height() > 0) {
                // ASCII GLYPHS ALL SHARE THE ATLAS TEXTURE, SO bindTexture IS A NO-OP AFTER THE FIRST AND THE WHOLE RUN
                // BATCHES INTO ONE DRAW; THE UV RECT SELECTS THIS GLYPH WITHIN THE ATLAS
                RenderSystem.bindTexture(g.textureId());
                RenderSystem.blit(
                        currentX + g.offsetX() * run.drawScale,
                        y + g.offsetY() * run.drawScale,
                        g.width() * run.drawScale,
                        g.height() * run.drawScale,
                        g.u0(), g.v0(), g.u1(), g.v1()
                );
            }
            currentX += (g.advance() + (i < n - 1 ? DEFAULT_TRACKING : 0f)) * run.drawScale;
        }
    }

    /** Renders {@code text} with {@code letterSpacing} logical pixels between glyphs. */
    public void renderSpaced(final String text, final float x, final float y, final Color color,
                             final float scale, final boolean bold, final int letterSpacing) {
        if (text == null || text.isEmpty()) return;
        RenderSystem.color(color);
        final FontRun run = this.fontRun(scale, bold ? Font.BOLD : Font.PLAIN);
        float cursor = x;
        final int n = text.length();
        for (int i = 0; i < n; i++) {
            final CharGlyph g = run.atlas.glyph(text.charAt(i));
            if (g.textureId() > 0 && g.width() > 0 && g.height() > 0) {
                RenderSystem.bindTexture(g.textureId());
                RenderSystem.blit(cursor + g.offsetX() * run.drawScale, y + g.offsetY() * run.drawScale,
                        g.width() * run.drawScale, g.height() * run.drawScale, g.u0(), g.v0(), g.u1(), g.v1());
            }
            // EACH CHAR ADVANCES BY ITS OWN CEILED RUN WIDTH PLUS THE SPACING, EXACTLY AS spacedWidth MEASURES
            cursor += (float) Math.ceil(g.advance() * run.drawScale) + letterSpacing;
        }
    }

    public int width(final String text) {
        return this.width(text, 1f, this.defaultStyle);
    }

    public int width(final String text, final float scale) {
        return this.width(text, scale, this.defaultStyle);
    }

    public int widthBold(final String text, final float scale) {
        return this.width(text, scale, Font.BOLD);
    }

    public int width(final String text, final float scale, final int style) {
        if (text == null || text.isEmpty()) return 0;
        final FontRun run = this.fontRun(scale, style);
        float width = 0f;
        final int n = text.length();
        for (int i = 0; i < n; i++) {
            final CharGlyph g = run.atlas.glyph(text.charAt(i));
            width += (g.advance() + (i < n - 1 ? DEFAULT_TRACKING : 0f)) * run.drawScale;
        }
        return (int) Math.ceil(width);
    }

    /** Returns the width {@link #renderSpaced} draws, ceiling each advance the same way so both agree to the pixel. */
    public int spacedWidth(final String text, final float scale, final boolean bold, final int letterSpacing) {
        if (text == null || text.isEmpty()) return 0;
        final FontRun run = this.fontRun(scale, bold ? Font.BOLD : Font.PLAIN);
        int width = 0;
        final int n = text.length();
        for (int i = 0; i < n; i++) {
            width += (int) Math.ceil(run.atlas.glyph(text.charAt(i)).advance() * run.drawScale);
            if (i + 1 < n) width += letterSpacing;
        }
        return width;
    }

    public String truncateToWidth(final String text, final int maxWidth) {
        return this.truncateToWidth(text, maxWidth, 1f, this.defaultStyle);
    }

    public String truncateToWidth(final String text, final int maxWidth, final float scale) {
        return this.truncateToWidth(text, maxWidth, scale, this.defaultStyle);
    }

    public String truncateToWidth(final String text, final int maxWidth, final float scale, final int style) {
        if (text == null || text.isEmpty()) return "";
        if (this.width(text, scale, style) <= maxWidth) return text;

        final String ellipsis = "...";
        final int ellipsisW = this.width(ellipsis, scale, style);
        if (maxWidth <= ellipsisW) return "";

        // THE RUN RESOLVES ONCE AND EACH CHAR MEASURES FROM IT, WITHOUT A String OR FontRun PER CHAR; EVERY KEPT CHAR
        // IS FOLLOWED BY THE TRACKING GAP render() DRAWS BEFORE THE NEXT GLYPH, THE ELLIPSIS INCLUDED
        final FontRun run = this.fontRun(scale, style);
        final StringBuilder sb = new StringBuilder();
        float currentW = 0f;
        final int n = text.length();
        for (int i = 0; i < n; i++) {
            final char c = text.charAt(i);
            final float advance = (run.atlas.glyph(c).advance() + DEFAULT_TRACKING) * run.drawScale;
            if (currentW + advance + ellipsisW > maxWidth) break;
            sb.append(c);
            currentW += advance;
        }
        return sb + ellipsis;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private FontRun fontRun(final float scale, final int style) {
        final int weight = style == Font.BOLD ? Font.BOLD : Font.PLAIN;
        final float ui = this.uiScale;
        // MEASURE AND DRAW CALLS COME IN RUNS OF ONE SCALE AND WEIGHT, SO THE LAST RUN SKIPS THE ATLAS LOOKUP
        final LastRun last = this.last;
        if (last != null && last.scale() == scale && last.style() == weight && last.ui() == ui) return last.run();
        // THE ATLAS IS PICKED IN PHYSICAL PX (SNAPPED TO THE PIXEL GRID, ODD-SIZE SNAP KEPT); QUADS ARE EMITTED IN
        // LOGICAL PX BY DIVIDING THE SNAPPED PHYSICAL SIZE BACK BY uiScale
        final int targetSize = Math.max(1, Math.round(this.baseSize * Math.max(0.05f, scale) * ui));
        final int atlasSize = targetSize % 2 == 0 ? targetSize + 1 : targetSize;
        // A PLAIN get/put: computeIfAbsent WOULD ALLOCATE ITS CAPTURING LAMBDA ON EVERY SCALE OR WEIGHT SWITCH
        final AtlasKey key = new AtlasKey(weight, atlasSize);
        FontAtlas atlas = this.atlases.get(key);
        if (atlas == null) {
            atlas = new FontAtlas((weight == Font.BOLD ? this.boldFont : this.plainFont).deriveFont((float) atlasSize));
            this.atlases.put(key, atlas);
        }
        final FontRun run = new FontRun(atlas, targetSize / ui / atlasSize);
        this.last = new LastRun(scale, weight, ui, run);
        return run;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static Font loadBundledFont(final int style) {
        final String resource = style == Font.BOLD ? "assets/watermedia/font/jetbrainsmono-bld.ttf" : "assets/watermedia/font/jetbrainsmono-reg.ttf";
        try (final InputStream in = IOTool.jarOpenFile(resource)) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, 1f);
        } catch (final Exception ignored) {}
        return new Font("Consolas", style, 1);
    }

    private static Graphics2D textGraphics(final BufferedImage img, final Font font) {
        final Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g2d.setFont(font);
        g2d.setColor(Color.WHITE);
        return g2d;
    }

    private static int alpha(final BufferedImage image, final int x, final int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    private record AtlasKey(int style, int size) {}
    private record FontRun(FontAtlas atlas, float drawScale) {}
    private record LastRun(float scale, int style, float ui, FontRun run) {}
    private record GlyphBitmap(int[] pixels, int width, int height, int offsetX, int offsetY, int advance) {}
    private record PackedGlyph(char c, GlyphBitmap bmp, int x, int y) {}
    private record CharGlyph(int textureId, float u0, float v0, float u1, float v1,
                             int width, int height, int offsetX, int offsetY, int advance) {}

    private static final class FontAtlas {
        private static final String SAMPLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789[]{}()/_-|";
        // PRINTABLE ASCII IS PRE-PACKED INTO ONE TEXTURE SO A TEXT RUN BINDS ONCE AND BATCHES; CHARS OUTSIDE THIS RANGE
        // FALL BACK TO A LAZY STANDALONE TEXTURE (RARE, SUCH AS ACCENTS IN A URL)
        private static final char FIRST_ASCII = 0x20;
        private static final char LAST_ASCII = 0x7E;
        private static final int MAX_ATLAS_WIDTH = 1_024;
        private static final int GUTTER = 1; // TRANSPARENT SEPARATION SO LINEAR SAMPLING NEVER BLEEDS NEIGHBORS

        private final Map<Character, CharGlyph> glyphs = new HashMap<>();
        private final Font font;
        private final FontMetrics metrics;
        private final int fontTop;
        private final int baseline;
        private final int glyphHeight;

        private FontAtlas(final Font font) {
            this.font = font;

            final BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = textGraphics(metricsImage, font);
            this.metrics = g2d.getFontMetrics(font);
            g2d.dispose();

            final int sampleW = Math.max(64, this.metrics.stringWidth(SAMPLE) + GLYPH_PAD * 2);
            final int sampleH = Math.max(8, this.metrics.getHeight() + GLYPH_PAD * 2);
            this.baseline = GLYPH_PAD + this.metrics.getAscent();

            final BufferedImage sample = new BufferedImage(sampleW, sampleH, BufferedImage.TYPE_INT_ARGB);
            g2d = textGraphics(sample, font);
            g2d.drawString(SAMPLE, GLYPH_PAD, this.baseline);
            g2d.dispose();

            int top = sampleH;
            int bottom = -1;
            for (int y = 0; y < sampleH; y++) {
                for (int x = 0; x < sampleW; x++) {
                    if (alpha(sample, x, y) == 0) continue;
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }

            if (bottom < top) {
                top = GLYPH_PAD;
                bottom = GLYPH_PAD + this.metrics.getAscent();
            }
            this.fontTop = top;
            this.glyphHeight = Math.max(1, bottom - top + 1);
            this.buildAtlas();
        }

        // ======================================================================
        // HELPERS
        // ======================================================================
        private void buildAtlas() {
            // RASTERIZES PRINTABLE ASCII AND SHELF-PACKS IT INTO ONE TEXTURE, RECORDING EACH GLYPH'S UV RECT
            final List<PackedGlyph> packed = new ArrayList<>();
            int penX = GUTTER;
            int penY = GUTTER;
            int shelfHeight = 0;
            int atlasWidth = GUTTER;
            for (char c = FIRST_ASCII; c <= LAST_ASCII; c++) {
                final GlyphBitmap bmp = this.rasterize(c);
                if (bmp.pixels() == null) {
                    // WHITESPACE OR BLANK GLYPH: ADVANCE ONLY, NO TEXTURE REGION
                    this.glyphs.put(c, new CharGlyph(0, 0f, 0f, 0f, 0f, 0, 0, 0, 0, bmp.advance()));
                    continue;
                }
                if (penX + bmp.width() + GUTTER > MAX_ATLAS_WIDTH) {
                    penX = GUTTER;
                    penY += shelfHeight + GUTTER;
                    shelfHeight = 0;
                }
                packed.add(new PackedGlyph(c, bmp, penX, penY));
                penX += bmp.width() + GUTTER;
                atlasWidth = Math.max(atlasWidth, penX);
                shelfHeight = Math.max(shelfHeight, bmp.height());
            }
            final int atlasHeight = penY + shelfHeight + GUTTER;

            // memCalloc ZEROES THE BUFFER (TRANSPARENT GUTTERS); ABSOLUTE PUTS LEAVE THE POSITION AT 0 SO THE WHOLE
            // BUFFER IS UPLOADED
            final ByteBuffer buffer = MemoryUtil.memCalloc(atlasWidth * atlasHeight * 4);
            for (final PackedGlyph pg: packed) blitGlyph(buffer, atlasWidth, pg);
            final int textureId = RenderSystem.createTexture(atlasWidth, atlasHeight, buffer);
            MemoryUtil.memFree(buffer);

            for (final PackedGlyph pg: packed) {
                final GlyphBitmap bmp = pg.bmp();
                this.glyphs.put(pg.c(), new CharGlyph(textureId,
                        pg.x() / (float) atlasWidth, pg.y() / (float) atlasHeight,
                        (pg.x() + bmp.width()) / (float) atlasWidth, (pg.y() + bmp.height()) / (float) atlasHeight,
                        bmp.width(), bmp.height(), bmp.offsetX(), bmp.offsetY(), bmp.advance()));
            }
        }

        private CharGlyph glyph(final char c) {
            CharGlyph cached = this.glyphs.get(c);
            if (cached != null) return cached;

            // A CHAR OUTSIDE THE PRE-BUILT ASCII ATLAS RASTERIZES A STANDALONE TEXTURE ON DEMAND
            final GlyphBitmap bmp = this.rasterize(c);
            if (bmp.pixels() == null) {
                cached = new CharGlyph(0, 0f, 0f, 0f, 0f, 0, 0, 0, 0, bmp.advance());
            } else {
                final ByteBuffer buffer = MemoryUtil.memAlloc(bmp.width() * bmp.height() * 4);
                for (final int pixel: bmp.pixels()) {
                    buffer.put((byte) ((pixel >> 16) & 0xFF));
                    buffer.put((byte) ((pixel >> 8) & 0xFF));
                    buffer.put((byte) (pixel & 0xFF));
                    buffer.put((byte) ((pixel >> 24) & 0xFF));
                }
                buffer.flip();
                final int textureId = RenderSystem.createTexture(bmp.width(), bmp.height(), buffer);
                MemoryUtil.memFree(buffer);
                cached = new CharGlyph(textureId, 0f, 0f, 1f, 1f,
                        bmp.width(), bmp.height(), bmp.offsetX(), bmp.offsetY(), bmp.advance());
            }
            this.glyphs.put(c, cached);
            return cached;
        }

        private GlyphBitmap rasterize(final char c) {
            // ONE GLYPH CROPPED TO ITS ALPHA BOUNDS; pixels() == null MEANS WHITESPACE OR BLANK
            final int advance = Math.max(1, this.metrics.charWidth(c));
            if (Character.isWhitespace(c)) return new GlyphBitmap(null, 0, 0, 0, 0, advance);

            final int imgW = Math.max(1, advance + GLYPH_PAD * 2);
            final int imgH = Math.max(8, this.metrics.getHeight() + GLYPH_PAD * 2);
            final BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2d = textGraphics(image, this.font);
            g2d.drawString(String.valueOf(c), GLYPH_PAD, this.baseline);
            g2d.dispose();

            int left = imgW;
            int right = -1;
            int top = imgH;
            int bottom = -1;
            for (int y = 0; y < imgH; y++) {
                for (int x = 0; x < imgW; x++) {
                    if (alpha(image, x, y) == 0) continue;
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                }
            }

            if (right < left || bottom < top) return new GlyphBitmap(null, 0, 0, 0, 0, advance);

            final int w = right - left + 1;
            final int h = bottom - top + 1;
            final int[] pixels = new int[w * h];
            image.getRGB(left, top, w, h, pixels, 0, w);
            return new GlyphBitmap(pixels, w, h, left - GLYPH_PAD, top - this.fontTop, advance);
        }

        // ======================================================================
        // UTILITY
        // ======================================================================
        private static void blitGlyph(final ByteBuffer atlas, final int atlasWidth, final PackedGlyph pg) {
            // THE CROPPED GLYPH'S RGBA GOES INTO THE ATLAS BUFFER AT ITS PACKED POSITION THROUGH ABSOLUTE PUTS
            final int[] pixels = pg.bmp().pixels();
            final int w = pg.bmp().width();
            for (int row = 0; row < pg.bmp().height(); row++) {
                int dst = ((pg.y() + row) * atlasWidth + pg.x()) * 4;
                int src = row * w;
                for (int col = 0; col < w; col++) {
                    final int pixel = pixels[src++];
                    atlas.put(dst++, (byte) ((pixel >> 16) & 0xFF));
                    atlas.put(dst++, (byte) ((pixel >> 8) & 0xFF));
                    atlas.put(dst++, (byte) (pixel & 0xFF));
                    atlas.put(dst++, (byte) ((pixel >> 24) & 0xFF));
                }
            }
        }
    }
}
