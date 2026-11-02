package org.watermedia.api.codecs.readers.webp.lossless;

import org.watermedia.api.util.MathUtil;

// THE VP8L PREDICTOR INVERSE TRANSFORM (RFC 9649 MODES 0-13)
final class Predictor {
    // SWAR PER-BYTE MASKS FOR ARGB ARITHMETIC
    private static final int MASK_LO = 0x00FF00FF;
    private static final int MASK_HI = 0xFF00FF00;
    private static final int MASK_LSB = 0xFEFEFEFE;

    private Predictor() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    // ADDS THE BLOCK-SELECTED PREDICTORS TO THE RESIDUAL ARGB PIXELS IN ROW ORDER, IN PLACE
    public static void inverse(final int[] pixels, final int width, final int height, final int[] modes, final int blockBits) {
        if (width == 0 || height == 0) return;

        final int blockSize = 1 << blockBits;
        final int blocksPerRow = (width + blockSize - 1) / blockSize;

        // ROW 0: TOP-LEFT IS BLACK, REST USE LEFT
        pixels[0] = addPixels(pixels[0], 0xFF000000);
        for (int x = 1; x < width; x++) pixels[x] = addPixels(pixels[x], pixels[x - 1]);

        // ROWS 1..H-1: PROCESS TILE-BY-TILE TO HOIST BLOCK LOOKUP OUT OF THE INNER LOOP
        for (int y = 1; y < height; y++) {
            final int blockY = y >> blockBits;
            final int rowOff = blockY * blocksPerRow;
            final int yOff = y * width;
            final int prevRow = yOff - width;

            // X = 0: TOP PREDICTOR
            pixels[yOff] = addPixels(pixels[yOff], pixels[prevRow]);

            for (int blockX = 0; blockX < blocksPerRow; blockX++) {
                final int mode = (modes[rowOff + blockX] >> 8) & 0xF;
                final int xStart = Math.max(1, blockX << blockBits);
                final int xEnd = Math.min(width, (blockX + 1) << blockBits);
                if (xStart >= xEnd) continue;
                applyTileRow(pixels, mode, yOff, prevRow, width, xStart, xEnd);
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static void applyTileRow(final int[] pixels, final int mode,
                                     final int yOff, final int prevRow, final int width,
                                     final int xStart, final int xEnd) {
        final int lastX = width - 1;
        switch (mode) {
            case 1 -> {
                for (int x = xStart; x < xEnd; x++) pixels[yOff + x] = addPixels(pixels[yOff + x], pixels[yOff + x - 1]);
            }
            case 2 -> {
                for (int x = xStart; x < xEnd; x++) pixels[yOff + x] = addPixels(pixels[yOff + x], pixels[prevRow + x]);
            }
            case 3 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int tr = (x < lastX) ? pixels[prevRow + x + 1] : pixels[yOff];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], tr);
                }
            }
            case 4 -> {
                for (int x = xStart; x < xEnd; x++) pixels[yOff + x] = addPixels(pixels[yOff + x], pixels[prevRow + x - 1]);
            }
            case 5 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int L = pixels[yOff + x - 1];
                    final int T = pixels[prevRow + x];
                    final int TR = (x < lastX) ? pixels[prevRow + x + 1] : pixels[yOff];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(avg2(L, TR), T));
                }
            }
            case 6 -> {
                for (int x = xStart; x < xEnd; x++) {
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(pixels[yOff + x - 1], pixels[prevRow + x - 1]));
                }
            }
            case 7 -> {
                for (int x = xStart; x < xEnd; x++) {
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(pixels[yOff + x - 1], pixels[prevRow + x]));
                }
            }
            case 8 -> {
                for (int x = xStart; x < xEnd; x++) {
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(pixels[prevRow + x - 1], pixels[prevRow + x]));
                }
            }
            case 9 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int T = pixels[prevRow + x];
                    final int TR = (x < lastX) ? pixels[prevRow + x + 1] : pixels[yOff];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(T, TR));
                }
            }
            case 10 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int L = pixels[yOff + x - 1];
                    final int T = pixels[prevRow + x];
                    final int TL = pixels[prevRow + x - 1];
                    final int TR = (x < lastX) ? pixels[prevRow + x + 1] : pixels[yOff];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], avg2(avg2(L, TL), avg2(T, TR)));
                }
            }
            case 11 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int L = pixels[yOff + x - 1];
                    final int T = pixels[prevRow + x];
                    final int TL = pixels[prevRow + x - 1];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], select(L, T, TL));
                }
            }
            case 12 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int L = pixels[yOff + x - 1];
                    final int T = pixels[prevRow + x];
                    final int TL = pixels[prevRow + x - 1];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], clampAddSubFull(L, T, TL));
                }
            }
            case 13 -> {
                for (int x = xStart; x < xEnd; x++) {
                    final int L = pixels[yOff + x - 1];
                    final int T = pixels[prevRow + x];
                    final int TL = pixels[prevRow + x - 1];
                    pixels[yOff + x] = addPixels(pixels[yOff + x], clampAddSubHalf(avg2(L, T), TL));
                }
            }
            // MODE 0 AND THE RESERVED MODES 14-15 ADD OPAQUE BLACK
            default -> {
                for (int x = xStart; x < xEnd; x++) pixels[yOff + x] = addPixels(pixels[yOff + x], 0xFF000000);
            }
        }
    }

    // ADDS RESIDUAL TO PREDICTED PER CHANNEL, WRAPPING AT 256 (SWAR): A MASK_LO SUM MAY CARRY INTO THE HIGH BYTE
    // OF ITS PAIR, BUT THE FINAL MASK STRIPS THAT CARRY BEFORE THE HIGH BYTES ARE OR'ED IN
    private static int addPixels(final int a, final int b) {
        final int lo = ((a & MASK_LO) + (b & MASK_LO)) & MASK_LO;
        final int hi = ((a & MASK_HI) + (b & MASK_HI)) & MASK_HI;
        return lo | hi;
    }

    // PER-CHANNEL AVERAGE OF TWO PIXELS (SWAR): (a + b) / 2 = (a & b) + ((a ^ b) >> 1), CLEARING EACH BYTE'S LSB
    // BEFORE THE SHIFT SO IT NEVER LEAKS INTO THE NEIGHBOUR BYTE'S MSB
    private static int avg2(final int a, final int b) {
        return (a & b) + (((a ^ b) & MASK_LSB) >>> 1);
    }

    // SELECT PREDICTOR: L OR T, WHICHEVER IS CLOSER (MANHATTAN, TIES TO T) TO THE GRADIENT ESTIMATE L + T - TL
    private static int select(final int L, final int T, final int TL) {
        final int pA = ((L >> 24) & 0xFF) + ((T >> 24) & 0xFF) - ((TL >> 24) & 0xFF);
        final int pR = ((L >> 16) & 0xFF) + ((T >> 16) & 0xFF) - ((TL >> 16) & 0xFF);
        final int pG = ((L >> 8) & 0xFF) + ((T >> 8) & 0xFF) - ((TL >> 8) & 0xFF);
        final int pB = (L & 0xFF) + (T & 0xFF) - (TL & 0xFF);

        final int pL = Math.abs(pA - ((L >> 24) & 0xFF)) + Math.abs(pR - ((L >> 16) & 0xFF)) +
                Math.abs(pG - ((L >> 8) & 0xFF)) + Math.abs(pB - (L & 0xFF));
        final int pT = Math.abs(pA - ((T >> 24) & 0xFF)) + Math.abs(pR - ((T >> 16) & 0xFF)) +
                Math.abs(pG - ((T >> 8) & 0xFF)) + Math.abs(pB - (T & 0xFF));

        return (pL < pT) ? L : T;
    }

    // CLAMP_ADD_SUBTRACT_FULL: a + b - c PER CHANNEL, CLAMPED TO 0-255
    private static int clampAddSubFull(final int a, final int b, final int c) {
        final int aa = MathUtil.clip255(((a >> 24) & 0xFF) + ((b >> 24) & 0xFF) - ((c >> 24) & 0xFF));
        final int ra = MathUtil.clip255(((a >> 16) & 0xFF) + ((b >> 16) & 0xFF) - ((c >> 16) & 0xFF));
        final int ga = MathUtil.clip255(((a >> 8) & 0xFF) + ((b >> 8) & 0xFF) - ((c >> 8) & 0xFF));
        final int ba = MathUtil.clip255((a & 0xFF) + (b & 0xFF) - (c & 0xFF));
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }

    // CLAMP_ADD_SUBTRACT_HALF: a + (a - b) / 2 PER CHANNEL, CLAMPED TO 0-255
    private static int clampAddSubHalf(final int a, final int b) {
        final int aa = MathUtil.clip255(((a >> 24) & 0xFF) + (((a >> 24) & 0xFF) - ((b >> 24) & 0xFF)) / 2);
        final int ra = MathUtil.clip255(((a >> 16) & 0xFF) + (((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) / 2);
        final int ga = MathUtil.clip255(((a >> 8) & 0xFF) + (((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) / 2);
        final int ba = MathUtil.clip255((a & 0xFF) + ((a & 0xFF) - (b & 0xFF)) / 2);
        return (aa << 24) | (ra << 16) | (ga << 8) | ba;
    }
}
