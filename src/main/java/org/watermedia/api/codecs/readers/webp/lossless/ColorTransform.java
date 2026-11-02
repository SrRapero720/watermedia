package org.watermedia.api.codecs.readers.webp.lossless;

// THE VP8L COLOR, SUBTRACT-GREEN AND COLOR-INDEXING INVERSE TRANSFORMS
final class ColorTransform {
    private ColorTransform() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    // RESTORES RED AND BLUE IN AN ARGB IMAGE FROM ONE TRANSFORM PIXEL PER BLOCK
    public static void inverse(final int[] pixels, final int width, final int height, final int[] transforms, final int blockBits) {
        if (width == 0 || height == 0) return;
        final int blockSize = 1 << blockBits;
        final int blocksPerRow = (width + blockSize - 1) / blockSize;

        for (int y = 0; y < height; y++) {
            final int rowOff = (y >> blockBits) * blocksPerRow;
            final int yOff = y * width;

            for (int blockX = 0; blockX < blocksPerRow; blockX++) {
                final int transform = transforms[rowOff + blockX];
                final byte greenToRed = (byte) (transform & 0xFF);
                final byte greenToBlue = (byte) ((transform >> 8) & 0xFF);
                final byte redToBlue = (byte) ((transform >> 16) & 0xFF);
                final int xStart = blockX << blockBits;
                final int xEnd = Math.min(width, xStart + blockSize);

                for (int x = xStart; x < xEnd; x++) {
                    final int pos = yOff + x;
                    final int pixel = pixels[pos];

                    final int a = pixel & 0xFF000000;
                    final int r = (pixel >> 16) & 0xFF;
                    final int g = (pixel >> 8) & 0xFF;
                    final int b = pixel & 0xFF;

                    final int newR = (r + ((greenToRed * (byte) g) >> 5)) & 0xFF;
                    int newB = (b + ((greenToBlue * (byte) g) >> 5)) & 0xFF;
                    newB = (newB + ((redToBlue * (byte) newR) >> 5)) & 0xFF;

                    pixels[pos] = a | (newR << 16) | (g << 8) | newB;
                }
            }
        }
    }

    // ADDS EACH PIXEL'S GREEN BACK INTO RED AND BLUE, MODULO 256, IN PLACE
    public static void addGreen(final int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            final int pixel = pixels[i];
            final int a = (pixel >> 24) & 0xFF;
            int r = (pixel >> 16) & 0xFF;
            final int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            r = (r + g) & 0xFF;
            b = (b + g) & 0xFF;

            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // EXPANDS GREEN-CHANNEL PALETTE INDEXES INTO ARGB; PACKED INPUT (bits > 0) YIELDS A NEW FULL-WIDTH ARRAY
    public static int[] applyPalette(final int[] pixels, final int width, final int height, final int[] colorTable, final int bits) {
        if (bits == 0) {
            // ONE INDEX PER PIXEL, IN PLACE; AN INDEX PAST THE TABLE IS TRANSPARENT BLACK (RFC 9649)
            for (int i = 0; i < pixels.length; i++) {
                final int index = (pixels[i] >> 8) & 0xFF;
                pixels[i] = index < colorTable.length ? colorTable[index] : 0;
            }
            return pixels;
        }
        // EACH GREEN BYTE OF A PACKED ROW HOLDS 1 << bits INDEXES, THE LEFTMOST PIXEL IN THE LOW BITS
        final int bitsPerPixel = 8 >> bits;
        final int mask = (1 << bitsPerPixel) - 1;
        final int countMask = (1 << bits) - 1;
        final int packedWidth = (width + countMask) >> bits;
        final int[] unpacked = new int[width * height];
        for (int y = 0; y < height; y++) {
            int src = y * packedWidth;
            final int dst = y * width;
            int packed = 0;
            for (int x = 0; x < width; x++) {
                if ((x & countMask) == 0) packed = (pixels[src++] >> 8) & 0xFF;
                final int index = packed & mask;
                unpacked[dst + x] = index < colorTable.length ? colorTable[index] : 0;
                packed >>= bitsPerPixel;
            }
        }
        return unpacked;
    }

    // RECONSTRUCTS A NONEMPTY DELTA-CODED ARGB COLOR TABLE (PER-COMPONENT SUMS MODULO 256) INTO A NEW ARRAY
    public static int[] decodeColorTable(final int[] rawTable) {
        final int[] table = new int[rawTable.length];
        table[0] = rawTable[0];

        for (int i = 1; i < rawTable.length; i++) {
            final int prev = table[i - 1];
            final int curr = rawTable[i];
            final int a = (((prev >> 24) & 0xFF) + ((curr >> 24) & 0xFF)) & 0xFF;
            final int r = (((prev >> 16) & 0xFF) + ((curr >> 16) & 0xFF)) & 0xFF;
            final int g = (((prev >> 8) & 0xFF) + ((curr >> 8) & 0xFF)) & 0xFF;
            final int b = ((prev & 0xFF) + (curr & 0xFF)) & 0xFF;

            table[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        return table;
    }

    // WEBP COLOR-INDEX PACKING BITS: 3, 2 OR 1 FOR AT MOST 2, 4 OR 16 COLORS, OTHERWISE 0
    public static int widthBits(final int colorTableSize) {
        if (colorTableSize <= 2) return 3;      // 8 PIXELS PER BYTE
        if (colorTableSize <= 4) return 2;      // 4 PIXELS PER BYTE
        if (colorTableSize <= 16) return 1;     // 2 PIXELS PER BYTE
        return 0;                               // NO BUNDLING
    }
}
