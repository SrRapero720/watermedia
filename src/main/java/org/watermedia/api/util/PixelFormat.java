package org.watermedia.api.util;

/**
 * Pixel layouts, including block-compressed (BCn) texture formats.
 * <p>
 * Each constant carries its intrinsic layout data: {@link #planes()} for raw formats and
 * {@link #blockBytes()} for compressed ones, so producers and engines never hardcode either.
 * <p>
 * Plane 0 holds the luma or the packed pixels, planes 1 and 2 the chroma and plane 3 the alpha. A texel is
 * the smallest addressable unit of a plane row: a pixel, a chroma sample or pair, a two-pixel packed
 * {@link #YUYV} group or a 4x4 compressed block. Subsampled planes round up like FFmpeg, so odd sizes keep
 * their last chroma column and row.
 */
public enum PixelFormat {
    // SINGLE PLANE
    /** Single-channel luminance (grayscale). 1 byte/pixel at 8-bit (Y only). No color, no alpha. */
    GRAY(1),
    /** Packed RGB. 3 bytes/pixel in [R, G, B] order. No alpha channel. */
    RGB(1),
    /** Packed RGBA. 4 bytes/pixel in [R, G, B, A] order. OpenGL's native upload layout. */
    RGBA(1),
    /** Packed BGRA. 4 bytes/pixel in [B, G, R, A] order. Same as {@link #RGBA} with R and B swapped, common on Windows/DirectX and Java's BufferedImage. Used as the fallback CPU format. */
    BGRA(1),
    /** Packed YUV 4:2:2 in [Y0, U, Y1, V] order: 4 bytes per 2 pixels (avg 2 bytes/pixel). Chroma horizontally subsampled, shared between pixel pairs. */
    YUYV(1),
    /** Packed YUV 4:2:2 in [U, Y0, V, Y1] order. Same layout as {@link #YUYV} with luma and chroma bytes swapped. */
    UYVY(1),

    // TWO PLANES (Y + INTERLEAVED CHROMA)
    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [U, V] plane. 1.5 bytes/pixel at 8-bit. */
    NV12(2),
    /** Biplanar YUV 4:2:0: full-resolution Y plane + half-width/half-height interleaved [V, U] plane. Same as {@link #NV12} with U and V swapped (Android camera default). */
    NV21(2),

    // THREE PLANES (Y + U + V)
    /** Planar YUV 4:2:0: separate Y, U, V planes; U and V are half-width and half-height. 1.5 bytes/pixel at 8-bit. The most common video format. */
    YUV420P(3),
    /** Planar YUV 4:2:2: separate Y, U, V planes; U and V are half-width but full height. 2 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P} with vertical chroma resolution preserved. */
    YUV422P(3),
    /** Planar YUV 4:4:4: separate Y, U, V planes all at full resolution, no chroma subsampling. 3 bytes/pixel at 8-bit. Same plane layout as {@link #YUV420P}/{@link #YUV422P} with full chroma. */
    YUV444P(3),

    // FOUR PLANES (Y + U + V + A)
    /** Planar YUV 4:2:0 with alpha: {@link #YUV420P} plus a full-resolution A plane. 2.5 bytes/pixel at 8-bit. */
    YUVA420P(4),
    /** Planar YUV 4:2:2 with alpha: {@link #YUV422P} plus a full-resolution A plane. 3 bytes/pixel at 8-bit. */
    YUVA422P(4),
    /** Planar YUV 4:4:4 with alpha: {@link #YUV444P} plus a full-resolution A plane. 4 bytes/pixel at 8-bit. */
    YUVA444P(4),

    // BLOCK-COMPRESSED (BCn): THE GPU SAMPLES THE BLOCKS DIRECTLY, NO SOFTWARE DECODE
    /** BC1 (DXT1): opaque RGB / 1-bit alpha, 8 bytes per 4x4 block, 1/8 the VRAM of RGBA8. */
    BC1(1, 8),
    /** BC2 (DXT3): RGB + explicit 4-bit alpha, 16 bytes per 4x4 block. */
    BC2(1, 16),
    /** BC3 (DXT5): RGB + interpolated alpha, 16 bytes per 4x4 block, 1/4 the VRAM of RGBA8. */
    BC3(1, 16),
    /** BC5: two-channel (RG) interpolated data, 16 bytes per 4x4 block, for normal maps and chroma pairs. */
    BC5(1, 16),
    /** BC7: high-quality RGBA, 16 bytes per 4x4 block; the best-looking BCn format. */
    BC7(1, 16);

    private final int planes;
    private final int blockBytes;

    PixelFormat(final int planes) {
        this(planes, 0);
    }

    PixelFormat(final int planes, final int blockBytes) {
        this.planes = planes;
        this.blockBytes = blockBytes;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Number of separate data planes one frame carries (compressed formats are a single block plane). */
    public int planes() { return this.planes; }

    /** Bytes per 4x4 block for {@link #compressed()} formats, or 0 for raw layouts. */
    public int blockBytes() { return this.blockBytes; }

    /** Whether this is a block-compressed (BCn) format the GPU samples without decoding. */
    public boolean compressed() { return this.blockBytes > 0; }

    /** Returns the width in texels of {@code plane} for a frame {@code width} pixels wide. */
    public int planeWidth(final int plane, final int width) {
        this.check(plane, width);
        return switch (this) {
            case YUYV, UYVY -> (int) ((width + 1L) >> 1);
            case NV12, NV21, YUV420P, YUV422P, YUVA420P, YUVA422P -> plane == 1 || plane == 2 ? (int) ((width + 1L) >> 1) : width;
            case BC1, BC2, BC3, BC5, BC7 -> (int) ((width + 3L) >> 2);
            default -> width;
        };
    }

    /** Returns the height in texel rows of {@code plane} for a frame {@code height} pixels tall. */
    public int planeHeight(final int plane, final int height) {
        this.check(plane, height);
        return switch (this) {
            case NV12, NV21, YUV420P, YUVA420P -> plane == 1 || plane == 2 ? (int) ((height + 1L) >> 1) : height;
            case BC1, BC2, BC3, BC5, BC7 -> (int) ((height + 3L) >> 2);
            default -> height;
        };
    }

    /** Returns the bytes of one texel of {@code plane} at {@code bits} per sample; compressed formats ignore the depth. */
    public int texelBytes(final int plane, final int bits) {
        this.check(plane, 0);
        if (bits < 1 || bits > 32) throw new IllegalArgumentException("Bits per sample must be within [1, 32], got " + bits);
        if (this.compressed()) return this.blockBytes;
        final int sample = bits <= 8 ? 1 : bits <= 16 ? 2 : 4;
        return switch (this) {
            case RGB -> 3 * sample;
            case RGBA, BGRA, YUYV, UYVY -> 4 * sample;
            case NV12, NV21 -> plane == 1 ? 2 * sample : sample;
            default -> sample;
        };
    }

    /** Returns the tightly packed bytes of every plane of a {@code width} by {@code height} frame at {@code bits} per sample, throwing {@link ArithmeticException} past a {@code long}. */
    public long frameBytes(final int width, final int height, final int bits) {
        long total = 0L;
        for (int p = 0; p < this.planes; p++) {
            // A ROW FITS A long (2^31 TEXELS OF AT MOST 16 BYTES); ROWS TIMES HEIGHT MAY NOT, SO IT THROWS INSTEAD OF WRAPPING
            final long row = (long) this.planeWidth(p, width) * this.texelBytes(p, bits);
            total = Math.addExact(total, Math.multiplyExact(row, this.planeHeight(p, height)));
        }
        return total;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void check(final int plane, final int size) {
        if (plane < 0 || plane >= this.planes) throw new IllegalArgumentException(this + " has no plane " + plane);
        if (size < 0) throw new IllegalArgumentException("Plane size cannot be negative, got " + size);
    }
}
