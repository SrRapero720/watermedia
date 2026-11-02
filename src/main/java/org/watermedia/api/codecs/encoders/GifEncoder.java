package org.watermedia.api.codecs.encoders;

import org.watermedia.api.codecs.Container;
import org.watermedia.api.codecs.common.gif.ColorTable;
import org.watermedia.api.codecs.common.gif.GraphicExtension;
import org.watermedia.api.codecs.common.gif.ImageDescriptor;
import org.watermedia.api.codecs.common.gif.ScreenDescriptor;
import org.watermedia.api.util.PixelFormat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** GIF89a encoder, still or animated, with a local palette per frame and frames cropped to what changed. */
public final class GifEncoder extends AnimationEncoder {
    // LIMITS
    private static final int MAX_SIDE = 65_535;
    private static final int MAX_PLAYS = 65_536; // NETSCAPE STORES THE PLAYS AFTER THE FIRST IN 16 BITS
    private static final int MAX_COLORS = ColorTable.MAX_COLORS;
    private static final Set<PixelFormat> FORMATS = EnumSet.of(PixelFormat.RGBA, PixelFormat.BGRA, PixelFormat.RGB, PixelFormat.GRAY);

    // PIXELS
    private static final int OPAQUE = 0xFF000000;
    private static final int TRANSPARENT = 0; // EVERY TRANSPARENT PIXEL, SO TWO OF THEM COMPARE EQUAL WHATEVER THEIR COLOR
    private static final int ALPHA_THRESHOLD = 128;

    // BLOCKS
    private static final byte[] SIGNATURE = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NETSCAPE = "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII);
    private static final int EXTENSION_INTRODUCER = 0x21;
    private static final int APPLICATION_LABEL = 0xFF;
    private static final int IMAGE_SEPARATOR = 0x2C;
    private static final int TRAILER = 0x3B;
    private static final int COLOR_RESOLUTION = 8;
    private static final int OUTPUT_BUFFER_BYTES = 64 << 10;

    // DISPOSAL
    private static final int KEEP = 1;  // THE NEXT IMAGE DRAWS OVER THIS ONE
    private static final int CLEAR = 2; // THE IMAGE AREA RESTORES TO TRANSPARENT BEFORE THE NEXT ONE

    // DELAYS
    private static final long CENTISECOND_US = 10_000L;
    private static final int MIN_DELAY_CS = 2; // READERS STRETCH 0 AND 1 CS TO 100 MS
    private static final int MAX_DELAY_CS = 65_535;

    // EXACT PALETTE: OPEN ADDRESSING AT A QUARTER LOAD, SLOTS PICKED BY THE TOP BITS OF A FIBONACCI HASH
    private static final int EXACT_SLOTS = 1_024;
    private static final int EXACT_SHIFT = 22;
    private static final int EXACT_HASH = 0x9E3779B1;
    private static final int EMPTY = 1; // NEITHER TRANSPARENT NOR OPAQUE, SO NO PIXEL EVER MATCHES IT

    // QUANTIZER: A HISTOGRAM OF BIN_BITS PER CHANNEL
    private static final int BIN_BITS = 5; // 6 BITS ONLY HELPS PURE RAMPS, AT 8X THE TABLES AND TWICE THE TIME
    private static final int BIN_SIDE = 1 << BIN_BITS;
    private static final int BIN_SHIFT = 8 - BIN_BITS;
    private static final int BIN_HALF = 1 << BIN_SHIFT - 1;
    private static final int BINS = 1 << BIN_BITS * 3;

    // LZW
    private static final int MIN_CODE_SIZE = 2;
    private static final int MAX_CODE_SIZE = 12;
    private static final int MAX_CODES = 1 << MAX_CODE_SIZE;
    private static final int LZW_SLOTS = 5_003; // THE PRIME TABLE OF compress(1), 80% LOADED AT 4096 CODES
    private static final int LZW_SHIFT = 4;     // SPREADS THE 8-BIT INDEX OVER THE 12-BIT PREFIX
    private static final int SUB_BLOCK_BYTES = 255;

    private final boolean dither;
    private final int pixelBytes;
    private final OutputStream sink;
    private final byte[] row;
    private final byte[] indices;
    private final int[] bounds = new int[8];
    private int[] prev; // PENDING FRAME, THEN WHAT THE DECODER SHOWS ONCE IT IS DISPOSED
    private int[] cur;
    // PENDING FRAME'S INCLUSIVE BOUNDS (right NEGATIVE WHEN NOTHING CHANGED) AND DELAY: IT IS WRITTEN ONCE THE NEXT
    // FRAME TELLS WHETHER IT MUST CLEAR ITS AREA
    private int left, top, right, bottom, delay;
    private long carryUs;

    // PALETTE
    private final int[] palette = new int[MAX_COLORS];
    private final int[] exactKeys = new int[EXACT_SLOTS];
    private final int[] exactSlots = new int[EXACT_SLOTS];
    private final int[] counts = new int[BINS];
    private final long[] sums = new long[BINS * 3];
    private final int[] nearest = new int[BINS];
    private final int[] boxLo = new int[MAX_COLORS * 3];
    private final int[] boxHi = new int[MAX_COLORS * 3];
    private final double[] boxScore = new double[MAX_COLORS];
    private final int[] boxAxis = new int[MAX_COLORS];
    private final int[] boxCut = new int[MAX_COLORS];
    private final int[] projection = new int[BIN_SIDE * 3];
    private final int[] errors;

    // LZW
    private final int[] lzwKeys = new int[LZW_SLOTS];
    private final int[] lzwCodes = new int[LZW_SLOTS];
    private final byte[] block = new byte[SUB_BLOCK_BYTES];
    private int blockLength;
    private int accumulator;
    private int accumulated;

    /** Creates an encoder of RGBA, BGRA, RGB or GRAY frames up to 65535 pixels per side and 65536 plays. */
    public GifEncoder(final OutputStream out, final Video video) {
        super(Container.GIF, out, video, null);
        if (video.width() > MAX_SIDE || video.height() > MAX_SIDE)
            throw new IllegalArgumentException("GIF sides stop at " + MAX_SIDE + " pixels: " + video.width() + "x" + video.height());
        if (!FORMATS.contains(video.format())) throw new IllegalArgumentException("GIF encodes RGBA, BGRA, RGB or GRAY frames, not " + video.format());
        if (video.repeat() > MAX_PLAYS) throw new IllegalArgumentException("GIF plays at most " + MAX_PLAYS + " times: " + video.repeat());
        final int pixels = video.width() * video.height();
        this.dither = video.quality() >= DEFAULT_QUALITY;
        this.pixelBytes = video.format().texelBytes(0, 8);
        this.sink = new BufferedOutputStream(out, OUTPUT_BUFFER_BYTES);
        this.row = new byte[video.width() * this.pixelBytes];
        this.indices = new byte[pixels];
        this.prev = new int[pixels];
        this.cur = new int[pixels];
        this.errors = new int[(video.width() + 2) * 6];
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @Override
    void picture(final ByteBuffer frame, final long durationUs, final int index) throws IOException {
        final int width = this.video.width();
        final int height = this.video.height();
        final int rowBytes = this.row.length;
        final PixelFormat format = this.video.format();
        final byte[] row = this.row;
        final int[] cur = this.cur;
        for (int y = 0, at = frame.position(), i = 0; y < height; y++, at += rowBytes) {
            frame.get(at, row, 0, rowBytes);
            switch (format) {
                case RGBA -> {
                    for (int p = 0; p < rowBytes; p += 4, i++)
                        cur[i] = (row[p + 3] & 0xFF) < ALPHA_THRESHOLD ? TRANSPARENT : OPAQUE | (row[p] & 0xFF) << 16 | (row[p + 1] & 0xFF) << 8 | row[p + 2] & 0xFF;
                }
                case BGRA -> {
                    for (int p = 0; p < rowBytes; p += 4, i++)
                        cur[i] = (row[p + 3] & 0xFF) < ALPHA_THRESHOLD ? TRANSPARENT : OPAQUE | (row[p + 2] & 0xFF) << 16 | (row[p + 1] & 0xFF) << 8 | row[p] & 0xFF;
                }
                case RGB -> {
                    for (int p = 0; p < rowBytes; p += 3, i++) cur[i] = OPAQUE | (row[p] & 0xFF) << 16 | (row[p + 1] & 0xFF) << 8 | row[p + 2] & 0xFF;
                }
                default -> {
                    for (int p = 0; p < rowBytes; p++, i++) cur[i] = OPAQUE | (row[p] & 0xFF) * 0x01_01_01;
                }
            }
        }

        // CENTISECONDS, CARRYING THE ROUNDING REMAINDER SO THE TOTAL LENGTH HOLDS; THE FLOOR ONLY DODGES THE 100 MS STRETCH
        final long exact = durationUs + this.carryUs;
        final long rounded = Math.floorDiv(exact + CENTISECOND_US / 2, CENTISECOND_US);
        this.carryUs = exact - rounded * CENTISECOND_US;
        final int delay = (int) Math.max(MIN_DELAY_CS, Math.min(MAX_DELAY_CS, rounded));

        if (index == 0) {
            this.left = 0;
            this.top = 0;
            this.right = width - 1;
            this.bottom = height - 1;
        } else {
            if (index == 1) this.header(true);
            final int[] bounds = this.bounds;
            this.compare();
            // OPAQUE PIXELS TURNING TRANSPARENT CANNOT BE DRAWN OVER: THE PENDING FRAME GROWS OVER THEM AND CLEARS ITS AREA
            // ONCE SHOWN, AND THIS FRAME IS COMPARED AGAIN AGAINST THAT CLEARED CANVAS
            final boolean clears = bounds[6] >= 0;
            if (clears) {
                this.left = Math.min(this.left, bounds[4]);
                this.top = Math.min(this.top, bounds[5]);
                this.right = Math.max(this.right, bounds[6]);
                this.bottom = Math.max(this.bottom, bounds[7]);
            }
            this.write(clears ? CLEAR : KEEP);
            if (clears) {
                for (int y = this.top; y <= this.bottom; y++) Arrays.fill(this.prev, y * width + this.left, y * width + this.right + 1, TRANSPARENT);
                this.compare();
            }
            this.left = bounds[0];
            this.top = bounds[1];
            this.right = bounds[2];
            this.bottom = bounds[3];
        }
        this.delay = delay;
        this.cur = this.prev;
        this.prev = cur;
    }

    @Override
    void end(final int pictures) throws IOException {
        // A STILL IMAGE CARRIES NO LOOP NOR DELAY; THE HEADER WAITS FOR THE FIRST IMAGE TO KNOW WHICH KIND IT OPENS
        if (pictures == 1) {
            this.header(false);
            this.delay = 0;
        }
        this.write(KEEP);
        this.sink.write(TRAILER);
        this.sink.flush();
    }

    // ==========================================================================
    // FRAMES
    // ==========================================================================
    // WRITES THE SIGNATURE AND THE LOGICAL SCREEN, PLUS THE NETSCAPE LOOP WHEN AN ANIMATION DOES NOT PLAY JUST ONCE
    private void header(final boolean animated) throws IOException {
        final OutputStream sink = this.sink;
        sink.write(SIGNATURE);
        sink.write(new ScreenDescriptor(this.video.width(), this.video.height(), false, COLOR_RESOLUTION, false, 0, 0, 0).toBytes());
        if (!animated || this.video.repeat() == 1) return;
        // NETSCAPE COUNTS THE LOOPS AFTER THE FIRST PLAY, 0 MEANING FOREVER AS IN repeat
        final int loops = Math.max(0, this.video.repeat() - 1);
        sink.write(EXTENSION_INTRODUCER);
        sink.write(APPLICATION_LABEL);
        sink.write(NETSCAPE.length);
        sink.write(NETSCAPE);
        sink.write(new byte[] { 3, 1, (byte) loops, (byte) (loops >> 8), 0 });
    }

    // BOUNDS WHERE cur DIFFERS FROM prev INTO bounds[0..3] AND WHERE IT TURNS TRANSPARENT INTO bounds[4..7], AS INCLUSIVE
    // x0, y0, x1, y1; AN EMPTY SET KEEPS x0, y0 AT THE SIZE AND x1, y1 AT -1, SO A MIN/MAX UNION IGNORES IT
    private void compare() {
        final int width = this.video.width();
        final int height = this.video.height();
        final int[] cur = this.cur;
        final int[] prev = this.prev;
        int dx0 = width, dy0 = height, dx1 = -1, dy1 = -1, cx0 = width, cy0 = height, cx1 = -1, cy1 = -1;
        for (int y = 0, i = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) {
                final int c = cur[i];
                if (c == prev[i]) continue;
                if (y < dy0) dy0 = y;
                dy1 = y;
                if (x < dx0) dx0 = x;
                if (x > dx1) dx1 = x;
                if (c != TRANSPARENT) continue;
                if (y < cy0) cy0 = y;
                cy1 = y;
                if (x < cx0) cx0 = x;
                if (x > cx1) cx1 = x;
            }
        }
        final int[] bounds = this.bounds;
        bounds[0] = dx0;
        bounds[1] = dy0;
        bounds[2] = dx1;
        bounds[3] = dy1;
        bounds[4] = cx0;
        bounds[5] = cy0;
        bounds[6] = cx1;
        bounds[7] = cy1;
    }

    // ENCODES THE PENDING FRAME'S AREA OF prev WITH ITS GRAPHIC CONTROL EXTENSION AND LOCAL PALETTE
    private void write(final int disposal) throws IOException {
        // AN UNCHANGED FRAME STILL NEEDS AN IMAGE: ONE PIXEL REDRAWN AS IT IS, PICKED ONLY HERE SO A CLEAR CAN GIVE IT AN AREA
        if (this.right < 0) {
            this.left = 0;
            this.top = 0;
            this.right = 0;
            this.bottom = 0;
        }
        final int width = this.video.width();
        final int w = this.right - this.left + 1;
        final int h = this.bottom - this.top + 1;
        final int[] pixels = this.prev;
        final int[] keys = this.exactKeys;
        final int[] slots = this.exactSlots;
        final int[] palette = this.palette;

        // EXACT PALETTE: THE DISTINCT OPAQUE COLORS IN FIRST-SEEN ORDER, COUNTING PAST 256 ONLY TO FLAG THE OVERFLOW. A
        // CLEARING FRAME ALWAYS RESERVES THE TRANSPARENT INDEX: ITS AREA RESTORES TO TRANSPARENT ONLY WHEN IT DECLARES ONE
        Arrays.fill(keys, EMPTY);
        boolean transparent = disposal == CLEAR;
        int colors = 0;
        for (int y = this.top; y <= this.bottom; y++) {
            for (int i = y * width + this.left, end = i + w, last = EMPTY; i < end; i++) {
                final int c = pixels[i];
                if (c == last) continue;
                last = c;
                if (c == TRANSPARENT) {
                    transparent = true;
                    continue;
                }
                if (colors > MAX_COLORS) continue;
                int slot = c * EXACT_HASH >>> EXACT_SHIFT;
                while (keys[slot] != EMPTY && keys[slot] != c) slot = (slot + 1) & (EXACT_SLOTS - 1);
                if (keys[slot] != EMPTY) continue;
                if (colors == MAX_COLORS) {
                    colors++;
                    continue;
                }
                keys[slot] = c;
                slots[slot] = colors;
                palette[colors++] = c;
            }
        }

        final boolean exact = colors + (transparent ? 1 : 0) <= MAX_COLORS;
        final int count = exact ? colors : this.quantize(transparent ? MAX_COLORS - 1 : MAX_COLORS);
        if (exact) {
            // THE TRANSPARENT INDEX FOLLOWS THE COLORS, SO EVERY PIXEL MAPS THROUGH THE SAME TABLE
            int slot = TRANSPARENT * EXACT_HASH >>> EXACT_SHIFT;
            while (keys[slot] != EMPTY) slot = (slot + 1) & (EXACT_SLOTS - 1);
            keys[slot] = TRANSPARENT;
            slots[slot] = colors;
            final byte[] indices = this.indices;
            for (int y = this.top, o = 0; y <= this.bottom; y++) {
                for (int i = y * width + this.left, end = i + w, last = EMPTY, index = 0; i < end; i++) {
                    final int c = pixels[i];
                    if (c != last) {
                        last = c;
                        slot = c * EXACT_HASH >>> EXACT_SHIFT;
                        while (keys[slot] != c) slot = (slot + 1) & (EXACT_SLOTS - 1);
                        index = slots[slot];
                    }
                    indices[o++] = (byte) index;
                }
            }
        }

        final int entries = count + (transparent ? 1 : 0);
        final int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(entries - 1));
        Arrays.fill(palette, count, 1 << bits, 0);
        final OutputStream sink = this.sink;
        sink.write(EXTENSION_INTRODUCER);
        sink.write(GraphicExtension.GCE_LABEL);
        sink.write(new GraphicExtension(disposal, false, transparent, this.delay, transparent ? count : 0).toBytes());
        sink.write(IMAGE_SEPARATOR);
        sink.write(new ImageDescriptor(this.left, this.top, w, h, true, false, false, bits - 1).toBytes());
        sink.write(new ColorTable(1 << bits, palette).toBytes());
        this.lzw(w * h, Math.max(MIN_CODE_SIZE, bits));
    }

    // ==========================================================================
    // PALETTE
    // ==========================================================================
    // MEDIAN CUT ON A 5-BIT HISTOGRAM: SPLITS GO WHERE THE SQUARED ERROR PEAKS, SMOOTHER ON GRADIENTS THAN OCTREE MERGES,
    // AT A COST BOUND BY THE 32K BINS INSTEAD OF THE PIXELS. FILLS THE INDICES AND RETURNS THE PALETTE SIZE
    private int quantize(final int limit) {
        final int width = this.video.width();
        final int w = this.right - this.left + 1;
        final int[] pixels = this.prev;
        final int[] counts = this.counts;
        final long[] sums = this.sums;
        Arrays.fill(counts, 0);
        Arrays.fill(sums, 0L);
        for (int y = this.top; y <= this.bottom; y++) {
            for (int i = y * width + this.left, end = i + w; i < end; i++) {
                final int c = pixels[i];
                if (c == TRANSPARENT) continue;
                final int bin = (c >> 16 & 0xFF) >> BIN_SHIFT << BIN_BITS * 2 | (c >> 8 & 0xFF) >> BIN_SHIFT << BIN_BITS | (c & 0xFF) >> BIN_SHIFT;
                counts[bin]++;
                sums[bin * 3] += c >> 16 & 0xFF;
                sums[bin * 3 + 1] += c >> 8 & 0xFF;
                sums[bin * 3 + 2] += c & 0xFF;
            }
        }

        // THE BOX OF LARGEST ERROR SPLITS AT ITS CUT UNTIL THE PALETTE FILLS OR EVERY BOX HOLDS A SINGLE BIN
        final int[] lo = this.boxLo;
        final int[] hi = this.boxHi;
        final double[] score = this.boxScore;
        Arrays.fill(lo, 0, 3, 0);
        Arrays.fill(hi, 0, 3, BIN_SIDE - 1);
        this.measure(0);
        int boxes = 1;
        while (boxes < limit) {
            int pick = 0;
            for (int b = 1; b < boxes; b++) if (score[b] > score[pick]) pick = b;
            if (score[pick] <= 0.0) break;
            final int axis = this.boxAxis[pick];
            System.arraycopy(lo, pick * 3, lo, boxes * 3, 3);
            System.arraycopy(hi, pick * 3, hi, boxes * 3, 3);
            hi[pick * 3 + axis] = this.boxCut[pick];
            lo[boxes * 3 + axis] = this.boxCut[pick] + 1;
            this.measure(pick);
            this.measure(boxes++);
        }

        final int[] nearest = this.nearest;
        final int[] palette = this.palette;
        final byte[] indices = this.indices;
        final byte transparent = (byte) boxes;
        Arrays.fill(nearest, -1);
        if (!this.dither) {
            for (int y = this.top, o = 0; y <= this.bottom; y++) {
                for (int i = y * width + this.left, end = i + w; i < end; i++) {
                    final int c = pixels[i];
                    if (c == TRANSPARENT) {
                        indices[o++] = transparent;
                        continue;
                    }
                    final int bin = (c >> 16 & 0xFF) >> BIN_SHIFT << BIN_BITS * 2 | (c >> 8 & 0xFF) >> BIN_SHIFT << BIN_BITS | (c & 0xFF) >> BIN_SHIFT;
                    indices[o++] = (byte) (nearest[bin] >= 0 ? nearest[bin] : this.closest(bin, boxes));
                }
            }
            return boxes;
        }

        // FLOYD-STEINBERG IN 1/16 UNITS OVER TWO ALTERNATING ROWS: A PIXEL ADDS THE ERROR PUSHED ONTO IT, TAKES THE NEAREST
        // ENTRY AND PUSHES ITS OWN ERROR 7 RIGHT AND 3, 5, 1 BELOW; TRANSPARENT PIXELS NEITHER TAKE NOR PUSH ANY
        final int[] errors = this.errors;
        final int stride = (w + 2) * 3;
        Arrays.fill(errors, 0, stride, 0);
        for (int y = this.top, o = 0, here = 0; y <= this.bottom; y++, here ^= stride) {
            final int below = here ^ stride;
            Arrays.fill(errors, below, below + stride, 0);
            for (int i = y * width + this.left, end = i + w, e = here + 3; i < end; i++, e += 3) {
                final int c = pixels[i];
                if (c == TRANSPARENT) {
                    indices[o++] = transparent;
                    continue;
                }
                final int r = Math.max(0, Math.min(255, (c >> 16 & 0xFF) + ((errors[e] + 8) >> 4)));
                final int g = Math.max(0, Math.min(255, (c >> 8 & 0xFF) + ((errors[e + 1] + 8) >> 4)));
                final int b = Math.max(0, Math.min(255, (c & 0xFF) + ((errors[e + 2] + 8) >> 4)));
                final int bin = r >> BIN_SHIFT << BIN_BITS * 2 | g >> BIN_SHIFT << BIN_BITS | b >> BIN_SHIFT;
                final int n = nearest[bin] >= 0 ? nearest[bin] : this.closest(bin, boxes);
                indices[o++] = (byte) n;
                final int p = palette[n];
                final int er = r - (p >> 16 & 0xFF), eg = g - (p >> 8 & 0xFF), eb = b - (p & 0xFF);
                final int d = below + e - here; // SAME COLUMN, ROW BELOW
                errors[e + 3] += er * 7;
                errors[e + 4] += eg * 7;
                errors[e + 5] += eb * 7;
                errors[d - 3] += er * 3;
                errors[d - 2] += eg * 3;
                errors[d - 1] += eb * 3;
                errors[d] += er * 5;
                errors[d + 1] += eg * 5;
                errors[d + 2] += eb * 5;
                errors[d + 3] += er;
                errors[d + 4] += eg;
                errors[d + 5] += eb;
            }
        }
        return boxes;
    }

    // SHRINKS BOX b TO ITS OCCUPIED BINS, TAKES THEIR MEAN COLOR AND SCORES ITS SQUARED SPREAD; THE CUT SITS AT THE
    // POPULATION MEDIAN OF THE WIDEST AXIS, SO A SPLIT HALVES THE PIXELS THAT ERR THE MOST
    private void measure(final int b) {
        final int[] counts = this.counts;
        final long[] sums = this.sums;
        final int[] projection = this.projection;
        final int[] lo = this.boxLo;
        final int[] hi = this.boxHi;
        final int at = b * 3;
        Arrays.fill(projection, 0);
        long n = 0L, red = 0L, green = 0L, blue = 0L;
        for (int r = lo[at]; r <= hi[at]; r++) {
            for (int g = lo[at + 1]; g <= hi[at + 1]; g++) {
                for (int bl = lo[at + 2], bin = r << BIN_BITS * 2 | g << BIN_BITS | bl; bl <= hi[at + 2]; bl++, bin++) {
                    final int count = counts[bin];
                    if (count == 0) continue;
                    projection[r] += count;
                    projection[BIN_SIDE + g] += count;
                    projection[BIN_SIDE * 2 + bl] += count;
                    n += count;
                    red += sums[bin * 3];
                    green += sums[bin * 3 + 1];
                    blue += sums[bin * 3 + 2];
                }
            }
        }
        this.palette[b] = OPAQUE | (int) ((red + n / 2) / n) << 16 | (int) ((green + n / 2) / n) << 8 | (int) ((blue + n / 2) / n);

        double total = 0.0;
        double widest = -1.0;
        for (int axis = 0, off = 0; axis < 3; axis++, off += BIN_SIDE) {
            int first = lo[at + axis];
            int last = hi[at + axis];
            while (projection[off + first] == 0) first++;
            while (projection[off + last] == 0) last--;
            lo[at + axis] = first;
            hi[at + axis] = last;
            long sum = 0L;
            for (int v = first; v <= last; v++) sum += (long) projection[off + v] * v;
            // TWO PASSES AROUND THE MEAN: A SINGLE BIN SCORES EXACTLY ZERO AND NOTHING OVERFLOWS ON HUGE FRAMES
            final double mean = (double) sum / n;
            double spread = 0.0;
            for (int v = first; v <= last; v++) spread += projection[off + v] * (v - mean) * (v - mean);
            total += spread;
            if (spread > widest) {
                widest = spread;
                this.boxAxis[b] = axis;
            }
        }
        this.boxScore[b] = total;

        final int off = this.boxAxis[b] * BIN_SIDE;
        final int last = hi[at + this.boxAxis[b]];
        int cut = lo[at + this.boxAxis[b]];
        long below = projection[off + cut];
        while (cut < last - 1 && below * 2 < n) below += projection[off + ++cut];
        this.boxCut[b] = cut;
    }

    // FINDS AND CACHES THE ENTRY NEAREST TO A BIN: THE MEAN OF ITS PIXELS WHEN IT HAS ANY, ITS CENTER OTHERWISE
    private int closest(final int bin, final int entries) {
        final int n = this.counts[bin];
        final long[] sums = this.sums;
        final int r = n > 0 ? (int) ((sums[bin * 3] + n / 2) / n) : (bin >> BIN_BITS * 2) << BIN_SHIFT | BIN_HALF;
        final int g = n > 0 ? (int) ((sums[bin * 3 + 1] + n / 2) / n) : (bin >> BIN_BITS & BIN_SIDE - 1) << BIN_SHIFT | BIN_HALF;
        final int b = n > 0 ? (int) ((sums[bin * 3 + 2] + n / 2) / n) : (bin & BIN_SIDE - 1) << BIN_SHIFT | BIN_HALF;
        final int[] palette = this.palette;
        int best = 0;
        for (int i = 0, bestDistance = Integer.MAX_VALUE; i < entries; i++) {
            final int c = palette[i];
            final int dr = r - (c >> 16 & 0xFF), dg = g - (c >> 8 & 0xFF), db = b - (c & 0xFF);
            final int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        this.nearest[bin] = best;
        return best;
    }

    // ==========================================================================
    // LZW
    // ==========================================================================
    // GIF LZW AS compress(1) DOES IT: A STRING IS ITS PREFIX CODE PLUS ONE INDEX IN A DOUBLE-HASHED TABLE, CODES WIDEN AS
    // THE TABLE PASSES EACH POWER OF TWO AND A FULL TABLE RESTARTS AFTER A CLEAR CODE
    private void lzw(final int length, final int minCodeSize) throws IOException {
        final byte[] indices = this.indices;
        final int[] keys = this.lzwKeys;
        final int[] codes = this.lzwCodes;
        final int clearCode = 1 << minCodeSize;
        int codeSize = minCodeSize + 1;
        int next = clearCode + 2;
        this.sink.write(minCodeSize);
        this.blockLength = 0;
        this.accumulator = 0;
        this.accumulated = 0;
        Arrays.fill(keys, -1);
        this.emit(clearCode, codeSize);

        int prefix = indices[0] & 0xFF;
        scan:
        for (int i = 1; i < length; i++) {
            final int c = indices[i] & 0xFF;
            final int key = c << MAX_CODE_SIZE | prefix;
            int slot = c << LZW_SHIFT ^ prefix;
            if (keys[slot] == key) {
                prefix = codes[slot];
                continue;
            }
            if (keys[slot] >= 0) {
                // A TAKEN SLOT PROBES BACKWARDS BY A STEP TIED TO THE FIRST SLOT UNTIL THE STRING OR A FREE SLOT SHOWS UP
                final int step = slot == 0 ? 1 : LZW_SLOTS - slot;
                do {
                    if ((slot -= step) < 0) slot += LZW_SLOTS;
                    if (keys[slot] == key) {
                        prefix = codes[slot];
                        continue scan;
                    }
                } while (keys[slot] >= 0);
            }
            // A NEW STRING: EMIT ITS KNOWN PREFIX AND WIDEN ONCE THE DECODER, ONE ENTRY BEHIND, FILLS THE CURRENT WIDTH
            this.emit(prefix, codeSize);
            if (next >= 1 << codeSize && codeSize < MAX_CODE_SIZE) codeSize++;
            prefix = c;
            if (next < MAX_CODES) {
                keys[slot] = key;
                codes[slot] = next++;
            } else {
                this.emit(clearCode, codeSize);
                Arrays.fill(keys, -1);
                codeSize = minCodeSize + 1;
                next = clearCode + 2;
            }
        }
        this.emit(prefix, codeSize);
        if (next >= 1 << codeSize && codeSize < MAX_CODE_SIZE) codeSize++;
        this.emit(clearCode + 1, codeSize);

        // THE LAST PARTIAL BYTE, THE LAST SHORT SUB-BLOCK AND THE ZERO-LENGTH TERMINATOR
        if (this.accumulated > 0) this.block[this.blockLength++] = (byte) this.accumulator;
        if (this.blockLength > 0) {
            this.sink.write(this.blockLength);
            this.sink.write(this.block, 0, this.blockLength);
        }
        this.sink.write(0);
    }

    // PACKS A CODE LEAST SIGNIFICANT BIT FIRST AND SPILLS EVERY FULL 255 BYTES AS ONE SUB-BLOCK
    private void emit(final int code, final int size) throws IOException {
        this.accumulator |= code << this.accumulated;
        this.accumulated += size;
        while (this.accumulated >= 8) {
            this.block[this.blockLength++] = (byte) this.accumulator;
            this.accumulator >>>= 8;
            this.accumulated -= 8;
            if (this.blockLength < SUB_BLOCK_BYTES) continue;
            this.sink.write(SUB_BLOCK_BYTES);
            this.sink.write(this.block, 0, SUB_BLOCK_BYTES);
            this.blockLength = 0;
        }
    }
}
