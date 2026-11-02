package org.watermedia.api.codecs.common.dds;

import org.watermedia.api.codecs.CodecException;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.codecs.common.Limits;
import org.watermedia.api.util.PixelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DirectDraw Surface (DDS) container with the modern {@code DX10} extended header: the storage format for
 * BC-compressed frames, the same way RIFF is the container for WebP. This class knows the container only; the block
 * layout comes from the frame's {@link PixelFormat}.
 *
 * <p>An animation is stored as a 2D texture array (one array slice per frame). DDS itself has no
 * field for per-frame delays, so an optional WaterMedia footer is appended after the texture data:
 * <pre>
 *   "DDS " (4) + DDS_HEADER (124) + DDS_HEADER_DXT10 (20)     // {@value #BYTES}-byte prefix
 *   frame[0..N) block data, each {@link Info#frameBytes()} bytes
 *   footer: "WMTC" (4) + int version + int frameCount + long[frameCount] delaysMs   // little-endian ints and longs
 * </pre>
 * The DDS prefix is a valid stand-alone texture; readers that ignore trailing bytes still load it, and
 * {@link #readFooter} gives zero delays when the bytes after the texture do not start with the footer magic.
 */
public final class DdsHeader {
    /** Byte length of the {@code "DDS "} + {@code DDS_HEADER} + {@code DDS_HEADER_DXT10} prefix. */
    public static final int BYTES = 148;

    // DDS HEADER
    private static final int MAGIC = 0x20534444; // 'D','D','S',' ' AS A LITTLE-ENDIAN INT
    private static final int FOURCC_DX10 = 0x30315844; // 'D','X','1','0' AS A LITTLE-ENDIAN INT
    private static final int HEADER_SIZE = 124;
    private static final int PIXEL_FORMAT_SIZE = 32;
    private static final int ARRAYSIZE_OFFSET = 140; // LITTLE-ENDIAN ARRAY SIZE IN THE DXT10 HEADER
    private static final int DDPF_FOURCC = 0x4;
    private static final int DDSCAPS_TEXTURE = 0x1000;
    private static final int DX10_RESOURCE_DIMENSION_TEXTURE1D = 2;
    private static final int DX10_RESOURCE_DIMENSION_TEXTURE2D = 3;
    private static final int DX10_RESOURCE_DIMENSION_TEXTURE3D = 4;
    private static final int DX10_RESOURCE_MISC_TEXTURECUBE = 0x4;

    // DXGI_FORMAT VALUES FOR THE BLOCK-COMPRESSED, UNORM VARIANTS
    private static final int DXGI_BC1_UNORM = 71;
    private static final int DXGI_BC3_UNORM = 77;
    private static final int DXGI_BC7_UNORM = 98;

    // WATERMEDIA ANIMATION FOOTER
    private static final int FOOTER_MAGIC = 0x43544D57; // 'W','M','T','C' AS A LITTLE-ENDIAN INT, READS IN A HEX DUMP LIKE "DDS "
    private static final int FOOTER_VERSION = 1;
    private static final int FOOTER_HEAD_BYTES = 12; // MAGIC, VERSION AND FRAME COUNT PRECEDE THE DELAY LONGS
    // ONE SLICE PER ANIMATION FRAME; A DECLARED COUNT COSTS TWO BUFFER OBJECTS AND EIGHT FOOTER BYTES
    private static final int MAX_ARRAY_SIZE = 4_096;

    private DdsHeader() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /**
     * Parses and validates the prefix of a BC-in-DDS file from {@code src.position()}, leaving the
     * position unchanged.
     *
     * @throws UnsupportedFormatException when the file is a valid DDS this reader does not read: a legacy
     *                                    (non-{@code DX10}) header, a mip chain, a cube map, a volume or 1D
     *                                    texture, or a DXGI format other than BC1, BC3 and BC7 UNORM
     * @throws CodecException             when the magic or the header structure is broken, or the declared
     *                                    dimensions or array size are outside the bounds the block
     *                                    arithmetic can represent
     */
    public static Info read(final ByteBuffer src) throws CodecException {
        final int base = src.position();
        if (src.remaining() < BYTES) throw new CodecException("Truncated DDS: " + src.remaining() + " bytes");
        final ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(base) != MAGIC) throw new CodecException("Not a DDS container");
        if (b.getInt(base + 4) != HEADER_SIZE || b.getInt(base + 76) != PIXEL_FORMAT_SIZE || (b.getInt(base + 108) & DDSCAPS_TEXTURE) == 0)
            throw new CodecException("Malformed DDS header: size " + b.getInt(base + 4) + ", pixel format size " + b.getInt(base + 76)
                    + ", caps 0x" + Integer.toHexString(b.getInt(base + 108)));
        // A LEGACY HEADER NAMES ITS LAYOUT WITH A D3D9 FOURCC OR CHANNEL MASKS INSTEAD OF THE DX10 DXGI FORMAT
        if ((b.getInt(base + 80) & DDPF_FOURCC) == 0 || b.getInt(base + 84) != FOURCC_DX10)
            throw new UnsupportedFormatException("Unsupported legacy DDS header: only DX10-extended files are read");

        // THE BLOCK SLICES BELOW REPRESENT ONLY TOP-LEVEL 2D TEXTURES; MIPS, VOLUMES, 1D AND CUBES ARE VALID BUT UNREAD
        final int depth = b.getInt(base + 24);
        final int mips = b.getInt(base + 28);
        final int dimension = b.getInt(base + 132);
        if (depth < 0 || mips < 0) throw new CodecException("Invalid DDS depth " + Integer.toUnsignedString(depth)
                + " or mip count " + Integer.toUnsignedString(mips));
        if (mips > 1) throw new UnsupportedFormatException("Unsupported DDS mip chain: " + mips + " levels");
        if (depth > 1 || dimension == DX10_RESOURCE_DIMENSION_TEXTURE3D) throw new UnsupportedFormatException("Unsupported DDS volume texture");
        if (dimension == DX10_RESOURCE_DIMENSION_TEXTURE1D) throw new UnsupportedFormatException("Unsupported DDS 1D texture");
        if (dimension != DX10_RESOURCE_DIMENSION_TEXTURE2D) throw new CodecException("Invalid DDS resource dimension: " + dimension);
        if ((b.getInt(base + 136) & DX10_RESOURCE_MISC_TEXTURECUBE) != 0) throw new UnsupportedFormatException("Unsupported DDS cube map");

        final int height = b.getInt(base + 12);
        final int width = b.getInt(base + 16);
        final int dxgi = b.getInt(base + 128);
        final int arraySize = b.getInt(base + ARRAYSIZE_OFFSET);
        final PixelFormat format = switch (dxgi) {
            case DXGI_BC1_UNORM -> PixelFormat.BC1;
            case DXGI_BC3_UNORM -> PixelFormat.BC3;
            case DXGI_BC7_UNORM -> PixelFormat.BC7;
            // DXGI_FORMAT_UNKNOWN (0) NAMES NO LAYOUT AT ALL; EVERY OTHER VALUE IS A FORMAT THIS READER DOES NOT CARRY
            default -> throw dxgi <= 0 ? new CodecException("Invalid DDS DXGI format: " + dxgi)
                    : new UnsupportedFormatException("Unsupported DDS DXGI format: " + dxgi + " (only BC1, BC3 and BC7 UNORM are read)");
        };
        // A PER-AXIS CAP IS NOT A BUDGET: THE BLOCK COUNT, AND EVERY ALLOCATION DERIVED FROM IT, IS THE PRODUCT
        if (!Limits.fits(width, height))
            throw new CodecException("Invalid DDS dimensions: " + width + "x" + height
                    + " (max " + Limits.MAX_DIM + " per axis, " + Limits.MAX_PIXELS + " pixels)");
        if (arraySize <= 0 || arraySize > MAX_ARRAY_SIZE)
            throw new CodecException("Invalid DDS arraySize: " + arraySize + " (max " + MAX_ARRAY_SIZE + ")");
        return new Info(width, height, format, arraySize);
    }

    /**
     * Reads the per-frame delays from the optional footer at {@code src.position()}, leaving the position
     * unchanged; bytes that do not start with the footer magic are not a footer and yield zero delays.
     *
     * @throws IllegalArgumentException when {@code expectedCount} is negative or above the largest array size {@link #read} accepts
     * @throws CodecException           when a footer that starts with the magic is truncated or its version/count is wrong
     */
    public static long[] readFooter(final ByteBuffer src, final int expectedCount) throws CodecException {
        // THE ARRAY-SIZE CAP BOUNDS THE ZERO-DELAY ARRAY OF AN ABSENT FOOTER AND KEEPS THE SIZE MATH BELOW IN int RANGE
        if (expectedCount < 0 || expectedCount > MAX_ARRAY_SIZE)
            throw new IllegalArgumentException("Frame count must be within [0, " + MAX_ARRAY_SIZE + "], got " + expectedCount);
        final ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        // ONLY THE MAGIC CLAIMS THE TRAILING BYTES AS A FOOTER; ANYTHING ELSE AFTER THE TEXTURE IS IGNORED
        if (b.remaining() < Integer.BYTES || b.getInt(b.position()) != FOOTER_MAGIC) return new long[expectedCount];
        if (FOOTER_HEAD_BYTES + expectedCount * Long.BYTES > b.remaining()) throw new CodecException("Truncated DDS footer");
        b.position(b.position() + Integer.BYTES);
        if (b.getInt() != FOOTER_VERSION) throw new CodecException("Unsupported DDS footer version");
        final int count = b.getInt();
        if (count != expectedCount) throw new CodecException("DDS footer frame count mismatch: " + count + " != " + expectedCount);
        final long[] delays = new long[count];
        for (int i = 0; i < count; i++) delays[i] = b.getLong();
        return delays;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** Parsed DDS prefix: dimensions, the file's BC layout and the frame count. */
    public record Info(int width, int height, PixelFormat format, int arraySize) {
        /** Compressed byte size of one frame, edges padded up to the 4x4 block grid. */
        public long frameBytes() {
            return this.format.frameBytes(this.width, this.height, 8);
        }
    }
}
