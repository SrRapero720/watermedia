import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes a synthetic baseline 4:2:0 JPEG with the JDK encoder and its RGB PAM reference with the JDK decoder, without WaterMedia classes. */
public final class GenerateJpegReference {
    // NEITHER SIDE IS A MULTIPLE OF THE 16x16 MCU, SO THE LAST MCU ROW AND COLUMN ARE PARTIAL
    private static final int WIDTH = 127;
    private static final int HEIGHT = 95;
    private static final float QUALITY = 0.9F;

    public static void main(final String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected output JPEG and output PAM paths");

        // SMOOTH GRADIENTS IN EVERY CHANNEL, A SOFT-EDGED WARM DISC, FINE LUMA STRIPES AND A DARK DIAGONAL LINE
        final BufferedImage picture = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double r = 40 + 180.0 * x / (WIDTH - 1);
                double g = 60 + 150.0 * y / (HEIGHT - 1);
                double b = 200 - 120.0 * (x + y) / (WIDTH + HEIGHT - 2);
                final double cover = Math.max(0, Math.min(1, (22 - Math.hypot(x - 84, y - 38)) / 3));
                r += (240 - r) * cover;
                g += (200 - g) * cover;
                b += (40 - b) * cover;
                if (x >= 8 && x < 56 && y >= 58 && y < 88) {
                    final double stripe = (x & 2) == 0 ? 40 : -40;
                    r += stripe;
                    g += stripe;
                    b += stripe;
                }
                if (Math.abs(x - y - 20) < 1.5) {
                    r *= 0.3;
                    g *= 0.3;
                    b *= 0.3;
                }
                picture.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }

        // THE JDK ENCODER WRITES BASELINE (SOF0) WITH 2x2 LUMA AND 1x1 CHROMA SAMPLING UNLESS TOLD OTHERWISE
        final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (final ImageOutputStream output = ImageIO.createImageOutputStream(encoded)) {
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(picture, null, null), param);
        } finally {
            writer.dispose();
        }
        final byte[] jpeg = encoded.toByteArray();
        final String frame = frame(jpeg);
        if (!frame.equals("SOF0 3 components 22 11 11")) throw new IllegalStateException("The JDK encoder wrote " + frame + " instead of a baseline 4:2:0 frame");

        // THE REFERENCE IS WHAT THE JDK DECODER MAKES OF THE WRITTEN BYTES, NOT THE SOURCE PICTURE
        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (decoded == null) throw new IllegalStateException("The JDK could not decode its own JPEG");
        Files.write(Path.of(args[0]), jpeg);
        final String header = "P7\nWIDTH " + decoded.getWidth() + "\nHEIGHT " + decoded.getHeight() + "\nDEPTH 3\nMAXVAL 255\nTUPLTYPE RGB\nENDHDR\n";
        try (final OutputStream output = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            for (int y = 0; y < decoded.getHeight(); y++) {
                for (int x = 0; x < decoded.getWidth(); x++) {
                    final int rgb = decoded.getRGB(x, y);
                    output.write(rgb >>> 16);
                    output.write(rgb >>> 8);
                    output.write(rgb);
                }
            }
        }
        System.out.println(WIDTH + "x" + HEIGHT + " " + frame + " JPEG (" + jpeg.length + " bytes) written to " + args[0] + ", RGB PAM to " + args[1]);
    }

    private static int clamp(final double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    // NAMES THE FRAME HEADER: ITS SOF NUMBER, COMPONENT COUNT AND THE SAMPLING BYTE OF EVERY COMPONENT
    private static String frame(final byte[] jpeg) {
        int at = 2;
        while (at + 3 < jpeg.length && (jpeg[at] & 0xFF) == 0xFF) {
            final int marker = jpeg[at + 1] & 0xFF;
            if (marker >= 0xC0 && marker <= 0xC2) {
                final StringBuilder out = new StringBuilder("SOF").append(marker - 0xC0).append(' ').append(jpeg[at + 9]).append(" components");
                for (int c = 0; c < jpeg[at + 9]; c++) out.append(String.format(" %02X", jpeg[at + 11 + c * 3] & 0xFF));
                return out.toString();
            }
            at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
        }
        return "no SOF0, SOF1 or SOF2 frame";
    }
}
