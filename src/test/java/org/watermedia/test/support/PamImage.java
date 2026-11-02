package org.watermedia.test.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** PAM (P7) reference image for pixel-exact codec tests, which compare BGRA decoder output through {@link #bgraToRgba}. */
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

    /** Converts a BGRA frame to an RGBA byte array with absolute reads, leaving the buffer position untouched. */
    public static byte[] bgraToRgba(final ByteBuffer bgra, final int width, final int height) {
        final byte[] rgba = new byte[width * height * 4];
        for (int offset = 0; offset < rgba.length; offset += 4) {
            rgba[offset] = bgra.get(offset + 2);
            rgba[offset + 1] = bgra.get(offset + 1);
            rgba[offset + 2] = bgra.get(offset);
            rgba[offset + 3] = bgra.get(offset + 3);
        }
        return rgba;
    }
}
