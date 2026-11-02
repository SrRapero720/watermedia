import javax.imageio.ImageIO;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates an RGBA PAM reference with the JDK PNG decoder, without WaterMedia classes. */
public final class GeneratePngReference {
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected input PNG and output PAM paths");
        final var image = ImageIO.read(Path.of(args[0]).toFile());
        if (image == null) throw new IllegalArgumentException("The JDK could not decode the input image");
        final String header = "P7\nWIDTH " + image.getWidth() + "\nHEIGHT " + image.getHeight()
                + "\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n";
        try (final var output = new BufferedOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            output.write(header.getBytes(StandardCharsets.US_ASCII));
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int argb = image.getRGB(x, y);
                    output.write(argb >>> 16);
                    output.write(argb >>> 8);
                    output.write(argb);
                    output.write(argb >>> 24);
                }
            }
        }
        System.out.println(image.getWidth() + "x" + image.getHeight() + " RGBA PAM written to " + args[1]);
    }
}
