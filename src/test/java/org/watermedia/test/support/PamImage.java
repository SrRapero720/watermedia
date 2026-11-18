package org.watermedia.test.support;

import org.watermedia.api.codecs.ImageData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** RGB or RGBA reference raster in PAM (P7) sample order, read from a PAM file or built from another decoder, that pixel tests compare decoded frames against. */
public record PamImage(int width, int height, int depth, byte[] pixels) {

    /** Reads {@code path} and parses the PAM payload. */
    public static PamImage read(final Path path) {
        final byte[] data;
        try { data = Files.readAllBytes(path); }
        catch (final IOException e) { throw new UncheckedIOException("Failed to read PAM " + path, e); }

        // THE RASTER STARTS RIGHT AFTER THE "ENDHDR\n" LINE
        int headerEnd = -1;
        for (int i = 0; i <= data.length - 7 && headerEnd < 0; i++) {
            if (data[i] == 'E' && data[i + 1] == 'N' && data[i + 2] == 'D' && data[i + 3] == 'H'
                    && data[i + 4] == 'D' && data[i + 5] == 'R' && data[i + 6] == '\n')
                headerEnd = i + 7;
        }
        if (headerEnd < 0) throw new IllegalArgumentException("Invalid PAM payload: ENDHDR marker missing in " + path);

        int width = 0;
        int height = 0;
        int depth = 0;
        for (final String line: new String(data, 0, headerEnd, StandardCharsets.US_ASCII).split("\\R")) {
            if (line.startsWith("WIDTH ")) width = Integer.parseInt(line.substring(6).trim());
            else if (line.startsWith("HEIGHT ")) height = Integer.parseInt(line.substring(7).trim());
            else if (line.startsWith("DEPTH ")) depth = Integer.parseInt(line.substring(6).trim());
        }
        if (width <= 0 || height <= 0 || depth <= 0)
            throw new IllegalArgumentException("Invalid PAM header: w=" + width + " h=" + height + " d=" + depth);
        final byte[] pixels = new byte[data.length - headerEnd];
        System.arraycopy(data, headerEnd, pixels, 0, pixels.length);
        return new PamImage(width, height, depth, pixels);
    }

    /** Returns one frame of a four-byte layout as little-endian ints, which reads BGRA as ARGB. */
    public static int[] argb(final ImageData image, final int frame) {
        final int[] pixels = new int[image.width() * image.height()];
        image.frames()[frame].duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        return pixels;
    }

    /** Compares a BGRA frame of this size with the reference, channel by channel in its RGB (depth 3) or RGBA (depth 4) order. */
    public PixelDiff diff(final ByteBuffer bgra, final int smallDiff) {
        if (this.depth != 3 && this.depth != 4) throw new IllegalArgumentException("A BGRA frame compares only against an RGB or RGBA reference, not depth " + this.depth);
        // ABSOLUTE READS LEAVE THE FRAME POSITION UNTOUCHED
        final byte[] actual = new byte[this.width * this.height * this.depth];
        for (int in = 0, out = 0; out < actual.length; in += 4, out += this.depth) {
            actual[out] = bgra.get(in + 2);
            actual[out + 1] = bgra.get(in + 1);
            actual[out + 2] = bgra.get(in);
            if (this.depth == 4) actual[out + 3] = bgra.get(in + 3);
        }
        return PixelDiff.compare(this.pixels, actual, smallDiff);
    }
}
