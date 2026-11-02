package org.watermedia.api.player;

import org.watermedia.api.util.PixelFormat;

/**
 * Color description of YUV and gray frames: the YCbCr matrix, the sample range, where the subsampled chroma sits,
 * the transfer curve and whether the primaries are BT.2020. GPU engines turn it into the {@link #constants} read by
 * {@code assets/watermedia/shaders/colorimetry.glsl}, the color math every conversion shader includes.
 *
 * @param matrix   YCbCr to RGB matrix
 * @param full     full range samples (0..255 at 8 bits) instead of limited range (16..235 luma, 16..240 chroma)
 * @param cositedX chroma sits on the even luma columns (MPEG-2, H.264 default) instead of between them (MPEG-1, JPEG)
 * @param cositedY chroma sits on the even luma rows instead of between them
 * @param transfer transfer curve of the samples
 * @param wide     BT.2020 primaries, mapped to BT.709 on display
 */
record Colorimetry(Matrix matrix, boolean full, boolean cositedX, boolean cositedY, Transfer transfer, boolean wide) {

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // WHAT AN UNTAGGED FRAME MOST LIKELY IS, AS MPV GUESSES IT (SWSCALE ALONE ASSUMES BT.601 AT ANY SIZE): BT.601 UP TO 576
    // LINES AND BELOW 1280 COLUMNS, BT.709 ABOVE; LIMITED RANGE EXCEPT GRAY; MPEG-2 CHROMA SITING; SDR
    static Colorimetry guess(final PixelFormat format, final int width, final int height) {
        final Matrix matrix = width >= 1_280 || height > 576 ? Matrix.BT709 : Matrix.BT601;
        return new Colorimetry(matrix, format == PixelFormat.GRAY, true, false, Transfer.SDR, false);
    }

    // SHADER CONSTANTS AS FOUR vec4: RANGE (Y SCALE, Y OFFSET, C SCALE, C OFFSET), MATRIX (Cr TO R, Cb TO G, Cr TO G,
    // Cb TO B), CHROMA COORDINATES (SCALE XY, OFFSET XY) AND DISPLAY (TRANSFER ORDINAL, BT.2020 PRIMARIES)
    float[] constants(final PixelFormat format, final int width, final int height, final int bits) {
        // SAMPLES ARRIVE NORMALIZED OVER 2^bits - 1 CODES (FLOAT SAMPLES AS 8-BIT ONES); LIMITED RANGE IS 16..235 LUMA
        // AND 16..240 CHROMA AT 8 BITS, EACH CODE SHIFTED LEFT FOR DEEPER SAMPLES
        final float codes = bits == 32 ? 255.0f : (float) ((1L << bits) - 1);
        final float unit = bits == 32 ? 1.0f : (float) (1L << (bits - 8));
        final float kg = 1.0f - this.matrix.kr - this.matrix.kb;

        // A SUBSAMPLED CHROMA TEXEL SPANS TWO LUMA PIXELS: CENTERED BETWEEN THEM, OR A QUARTER TEXEL RIGHT/DOWN WHEN
        // CO-SITED WITH THE FIRST. PLANES ROUND UP, SO THE SCALE IS width / (2 * chromaWidth), NOT ALWAYS 1/2
        final int cw = format.planes() > 1 ? format.planeWidth(1, width) : width;
        final int ch = format.planes() > 1 ? format.planeHeight(1, height) : height;
        final boolean halfX = cw < width;
        final boolean halfY = ch < height;
        return new float[] {
                this.full ? 1.0f : codes / (219.0f * unit), this.full ? 0.0f : 16.0f / 219.0f,
                this.full ? 1.0f : codes / (224.0f * unit), this.full ? (codes + 1.0f) / 2.0f / codes : 128.0f / 224.0f,
                2.0f * (1.0f - this.matrix.kr), 2.0f * this.matrix.kb * (1.0f - this.matrix.kb) / kg,
                2.0f * this.matrix.kr * (1.0f - this.matrix.kr) / kg, 2.0f * (1.0f - this.matrix.kb),
                halfX ? width / (2.0f * cw) : 1.0f, halfY ? height / (2.0f * ch) : 1.0f,
                halfX && this.cositedX ? 0.25f / cw : 0.0f, halfY && this.cositedY ? 0.25f / ch : 0.0f,
                this.transfer.ordinal(), this.wide ? 1.0f : 0.0f, 0.0f, 0.0f
        };
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // YCBCR MATRIX, NAMED BY ITS STANDARD AND DEFINED BY ITS RED AND BLUE LUMA WEIGHTS
    enum Matrix {
        BT601(0.299f, 0.114f),
        BT709(0.2126f, 0.0722f),
        BT2020(0.2627f, 0.0593f);

        final float kr, kb;

        Matrix(final float kr, final float kb) {
            this.kr = kr;
            this.kb = kb;
        }
    }

    // TRANSFER CURVE: STANDARD DYNAMIC RANGE, OR HDR THROUGH SMPTE ST 2084 (PQ) OR ARIB STD-B67 (HLG); THE SHADER
    // READS THE ORDINAL, SO THE ORDER IS PART OF THE CONTRACT
    enum Transfer { SDR, PQ, HLG }
}
