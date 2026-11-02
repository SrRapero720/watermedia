package org.watermedia.api.codecs.encoders;

import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.common.png.ACTL;
import org.watermedia.api.codecs.common.png.ColorType;
import org.watermedia.api.codecs.common.png.FCTL;
import org.watermedia.api.codecs.common.png.FDAT;
import org.watermedia.api.codecs.common.png.IChunk;
import org.watermedia.api.codecs.common.png.IDAT;
import org.watermedia.api.codecs.common.png.IEND;
import org.watermedia.api.codecs.common.png.IHDR;
import org.watermedia.api.util.PixelFormat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

/**
 * PNG encoder, APNG when more than one frame arrives. It takes 8-bit RGBA, BGRA, RGB or GRAY frames, compresses them
 * losslessly with adaptive filtering and stores only the changed region of every animation frame after the first.
 */
public final class PngEncoder extends AnimationEncoder {
    // FORMAT
    private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
    private static final int BLOCK_BYTES = 65_536;                     // DEFLATE OUTPUT CARRIED BY ONE IDAT OR fdAT
    private static final int CHUNK_OVERHEAD_BYTES = 16;                // LENGTH, TYPE AND CRC, PLUS THE fdAT SEQUENCE NUMBER

    // TIMING
    private static final long MAX_DELAY = 65_535L;                     // fcTL DELAY FIELDS ARE UNSIGNED 16-BIT
    private static final long MIN_DELAY_UNIT_US = 1_000L;              // MILLISECONDS, WHILE THE DELAY FITS
    private static final long MAX_DELAY_UNIT_US = 1_000_000L;          // WHOLE SECONDS, THE COARSEST UNIT

    private final IHDR header;
    private final int channels;
    private final boolean bgra;
    private final byte[] previous;   // LAST FRAME AS GIVEN, THE BASE OF THE CHANGED-REGION SEARCH
    private final byte[] line;       // UNFILTERED ROWS, LED BY A ZERO BYTE SO THEY DEFLATE AS FILTER NONE IN PLACE
    private final byte[] above;
    private final byte[][] filtered; // THE ROW UNDER SUB, UP, AVERAGE AND PAETH, EACH LED BY ITS FILTER TYPE BYTE
    private final byte[] staging;    // DEFLATE OUTPUT OF THE BLOCK BEING FILLED
    private final List<Picture> pictures = new ArrayList<>();
    private int staged;
    private int sequence;            // NEXT APNG SEQUENCE NUMBER, SHARED BY fcTL AND fdAT
    private long carryUs;            // DURATION THE DELAYS WRITTEN SO FAR LOST OR GAINED BY ROUNDING

    /** Creates an encoder of {@code video} into {@code out}; the frames must be RGBA, BGRA, RGB or GRAY. */
    public PngEncoder(final OutputStream out, final Video video) {
        super(Container.PNG, out, video, null);
        final ColorType type = switch (video.format()) {
            case RGBA, BGRA -> ColorType.TRUECOLOR_ALPHA;
            case RGB -> ColorType.TRUECOLOR;
            case GRAY -> ColorType.GREYSCALE;
            default -> throw new IllegalArgumentException("PNG encodes RGBA, BGRA, RGB or GRAY frames, not " + video.format());
        };
        this.header = new IHDR(video.width(), video.height(), 8, type.ordinal(), 0, 0, 0);
        this.channels = video.format().texelBytes(0, 8);
        this.bgra = video.format() == PixelFormat.BGRA;
        this.previous = new byte[(int) video.frameBytes()];
        final int stride = video.width() * this.channels;
        this.line = new byte[stride + 1];
        this.above = new byte[stride + 1];
        this.filtered = new byte[4][stride + 1];
        for (int f = 0; f < 4; f++) this.filtered[f][0] = (byte) (f + 1);
        this.staging = new byte[BLOCK_BYTES];
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    void picture(final ByteBuffer frame, final long durationUs, final int index) {
        final int width = this.video.width();
        final int height = this.video.height();
        final int bpp = this.channels;
        final int stride = width * bpp;
        final int base = frame.position();

        // BOUNDS OF THE PIXELS THAT CHANGED SINCE THE PREVIOUS FRAME; THE FIRST ONE IS THE DEFAULT IMAGE AND GOES WHOLE,
        // AN UNCHANGED ONE STILL NEEDS ITS fcTL FOR THE DELAY AND REWRITES A SINGLE PIXEL
        int left = 0, top = 0, right = width - 1, bottom = height - 1;
        if (index > 0) {
            left = width;
            top = -1;
            right = -1;
            for (int y = 0; y < height; y++) {
                final int row = y * stride;
                final int first = frame.slice(base + row, stride).mismatch(ByteBuffer.wrap(this.previous, row, stride));
                if (first < 0) continue;
                int last = stride - 1;
                while (frame.get(base + row + last) == this.previous[row + last]) last--;
                if (top < 0) top = y;
                bottom = y;
                left = Math.min(left, first / bpp);
                right = Math.max(right, last / bpp);
            }
            if (top < 0) left = top = right = bottom = 0;
        }
        final int regionWidth = right - left + 1;
        final int regionHeight = bottom - top + 1;
        frame.get(base + top * stride, this.previous, top * stride, regionHeight * stride);

        // ADAPTIVE FILTERING: EACH ROW TAKES THE FILTER WHOSE BYTES, READ AS SIGNED, HAVE THE SMALLEST ABSOLUTE SUM.
        // THE REGION IS AN IMAGE OF ITS OWN, SO ITS FIRST ROW FILTERS AGAINST ZEROS
        final int bytes = regionWidth * bpp;
        final byte[] sub = this.filtered[0], up = this.filtered[1], average = this.filtered[2], paeth = this.filtered[3];
        byte[] line = this.line;
        byte[] above = this.above;
        Arrays.fill(above, 0, bytes + 1, (byte) 0);
        final List<byte[]> blocks = new ArrayList<>();
        final Deflater deflater = new Deflater();
        this.staged = 0;
        try {
            for (int y = top; y <= bottom; y++) {
                frame.get(base + y * stride + left * bpp, line, 1, bytes);
                if (this.bgra) for (int i = 1; i < bytes; i += 4) {
                    final byte blue = line[i];
                    line[i] = line[i + 2];
                    line[i + 2] = blue;
                }
                long sumNone = 0, sumSub = 0, sumUp = 0, sumAverage = 0, sumPaeth = 0;
                for (int i = 1; i <= bytes; i++) {
                    final int x = line[i] & 0xFF;
                    final int a = i > bpp ? line[i - bpp] & 0xFF : 0;
                    final int b = above[i] & 0xFF;
                    final int c = i > bpp ? above[i - bpp] & 0xFF : 0;
                    final int p = a + b - c;
                    final int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    sub[i] = (byte) (x - a);
                    up[i] = (byte) (x - b);
                    average[i] = (byte) (x - ((a + b) >> 1));
                    paeth[i] = (byte) (x - (pa <= pb && pa <= pc ? a : pb <= pc ? b : c));
                    sumNone += Math.abs(line[i]);
                    sumSub += Math.abs(sub[i]);
                    sumUp += Math.abs(up[i]);
                    sumAverage += Math.abs(average[i]);
                    sumPaeth += Math.abs(paeth[i]);
                }
                // TIES KEEP THE SIMPLER FILTER
                byte[] chosen = line;
                long least = sumNone;
                if (sumSub < least) {
                    chosen = sub;
                    least = sumSub;
                }
                if (sumUp < least) {
                    chosen = up;
                    least = sumUp;
                }
                if (sumAverage < least) {
                    chosen = average;
                    least = sumAverage;
                }
                if (sumPaeth < least) chosen = paeth;
                deflater.setInput(chosen, 0, bytes + 1);
                this.drain(deflater, blocks, false);

                final byte[] done = above;
                above = line;
                line = done;
            }
            deflater.finish();
            this.drain(deflater, blocks, true);
        } finally {
            deflater.end();
        }

        // fcTL DELAYS ARE 16-BIT FRACTIONS OF A SECOND: MILLISECONDS WHILE THEY FIT, A COARSER UNIT FOR LONG PAUSES.
        // THE ROUNDING ERROR CARRIES INTO THE NEXT FRAME, SO THE ANIMATION KEEPS ITS LENGTH
        final long wantUs = durationUs + this.carryUs;
        long unitUs = MIN_DELAY_UNIT_US;
        while (unitUs < MAX_DELAY_UNIT_US && Math.floorDiv(wantUs + unitUs / 2, unitUs) > MAX_DELAY) unitUs *= 10;
        final long delay = Math.min(MAX_DELAY, Math.max(0L, Math.floorDiv(wantUs + unitUs / 2, unitUs)));
        this.carryUs = wantUs - delay * unitUs;

        // THE FIRST FRAME'S DATA IS THE IDAT DEFAULT IMAGE, WHICH TAKES NO SEQUENCE NUMBERS; EVERY LATER fdAT DOES
        this.pictures.add(new Picture(new FCTL(this.sequence, regionWidth, regionHeight, left, top, (short) delay,
                (short) (MAX_DELAY_UNIT_US / unitUs), FCTL.DISPOSE_OP_NONE, FCTL.BLEND_OP_SOURCE), blocks));
        this.sequence += index == 0 ? 1 : 1 + blocks.size();
    }

    @Override
    void end(final int pictures) throws IOException {
        // acTL MUST PRECEDE THE IMAGE DATA, SO THE FILE IS ONLY WRITTEN ONCE THE FRAME COUNT IS KNOWN
        final boolean animated = pictures > 1;
        final ByteBuffer scratch = ByteBuffer.allocate(BLOCK_BYTES + CHUNK_OVERHEAD_BYTES);
        this.out.write(SIGNATURE);
        this.write(this.header, scratch);
        if (animated) this.write(new ACTL(pictures, this.video.repeat()), scratch);
        for (int i = 0; i < pictures; i++) {
            final Picture picture = this.pictures.get(i);
            if (animated) this.write(picture.control(), scratch);
            for (int b = 0; b < picture.blocks().size(); b++) {
                final byte[] block = picture.blocks().get(b);
                this.write(i == 0 ? new IDAT(block) : new FDAT(picture.control().sequence() + 1 + b, block), scratch);
            }
        }
        this.write(new IEND(), scratch);
        this.pictures.clear();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    // CUTS THE PENDING DEFLATE OUTPUT INTO BLOCK_BYTES BLOCKS, ONE IDAT OR fdAT PAYLOAD EACH; last ALSO FLUSHES THE STREAM END
    private void drain(final Deflater deflater, final List<byte[]> blocks, final boolean last) {
        while (last ? !deflater.finished() : !deflater.needsInput()) {
            this.staged += deflater.deflate(this.staging, this.staged, BLOCK_BYTES - this.staged);
            if (this.staged < BLOCK_BYTES) continue;
            blocks.add(this.staging.clone());
            this.staged = 0;
        }
        if (last && this.staged > 0) blocks.add(Arrays.copyOf(this.staging, this.staged));
    }

    // WRITES ONE COMPLETE CHUNK THROUGH THE SCRATCH BUFFER, WHICH FITS THE LARGEST: AN fdAT OF A FULL BLOCK
    private void write(final IChunk chunk, final ByteBuffer scratch) throws IOException {
        chunk.toChunk().write(scratch.clear());
        this.out.write(scratch.array(), 0, scratch.position());
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    // ONE ENCODED FRAME: ITS fcTL AND THE COMPRESSED BLOCKS OF ITS REGION
    private record Picture(FCTL control, List<byte[]> blocks) {}
}
