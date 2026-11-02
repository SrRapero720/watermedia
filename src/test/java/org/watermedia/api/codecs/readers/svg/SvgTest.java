package org.watermedia.api.codecs.readers.svg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.ImageData;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.api.codecs.UnsupportedFormatException;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.PamImage;
import org.watermedia.test.support.PixelDiff;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Verifies the pure-Java SVG decoder: the reader contract over the SVGs under {@code src/test/resources/svg}, their
 * renders against the reference PNGs under {@code svg/png}, then paint, geometry, visibility and robustness on
 * hand-written documents.
 *
 * <p>Exact pixel parity with the browser or Illustrator renders behind the references is not expected from a
 * from-scratch anti-aliased rasterizer, so the reference match bounds the mean and the share of clearly different values.
 */
@DisplayName("SVG decoder")
public class SvgTest {

    // REFERENCE RENDERS UNDER svg/png: THE WORST FIXTURE TODAY SITS AT A 2.96 MEAN AND 4.1% OF VALUES OFF BY MORE THAN 16
    private static final int REFERENCE_SMALL_DIFF = 16;
    private static final double REFERENCE_MAX_MEAN_DIFF = 4.0;
    private static final double REFERENCE_MAX_PERCENT = 6.0;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @TestFactory
    @DisplayName("Decode contract")
    Iterable<DynamicTest> contract() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        final int cap = WaterMediaConfig.codecs.svg.maxSize;
        final List<Path> files;
        try (final Stream<Path> entries = Files.list(Fixtures.SVG_DIR)) {
            files = entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "No SVG fixtures under " + Fixtures.SVG_DIR);
        for (final Path svg: files) {
            final String name = svg.getFileName().toString();
            tests.add(dynamicTest("SVG contract [" + name + "]", () -> {
                final ByteBuffer bb = ByteBuffer.wrap(Fixtures.readAll(svg));
                try (final ImageReader reader = CodecsAPI.reader(bb)) {
                    assertEquals("SVG", reader.name());
                    assertEquals(0, bb.position(), "SVG is its own body: position must not advance");
                    assertTrue(reader.width() > 0 && reader.height() > 0, "positive dimensions");
                    assertTrue(reader.width() <= cap && reader.height() <= cap, "max side capped to " + cap);
                    assertEquals(PixelFormat.BGRA, reader.pixelFormat());
                    assertEquals(1, reader.frameCount(), "SVG is a single static frame");

                    assertTrue(reader.hasNext());
                    final ByteBuffer frame = reader.next();
                    assertTrue(frame.isDirect(), "frame must be a direct buffer");
                    assertEquals(reader.width() * reader.height() * 4, frame.remaining(), "BGRA buffer size");
                    assertFalse(reader.hasNext(), "only one frame");
                    assertTrue(reader.reset(), "reset must be supported");
                    assertTrue(reader.hasNext(), "reset re-arms the frame");
                }
            }));
        }
        return tests;
    }

    @TestFactory
    @DisplayName("Reference match")
    Iterable<DynamicTest> referenceMatch() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        final List<Path> references;
        try (final Stream<Path> entries = Files.list(Fixtures.SVG_DIR.resolve("png"))) {
            references = entries.sorted().toList();
        }
        assertFalse(references.isEmpty(), "No SVG reference renders under " + Fixtures.SVG_DIR.resolve("png"));
        for (final Path png: references) {
            final String name = png.getFileName().toString();
            // A "-hd" OR "-sd" SUFFIX NAMES A REFERENCE RENDERED BELOW THE INTRINSIC SIZE OF THE SAME SVG
            final String base = name.replaceFirst("(-hd|-sd)?\\.png$", "");
            tests.add(dynamicTest("SVG reference match [" + name + "]", () -> {
                final BufferedImage reference = ImageIO.read(png.toFile());
                final int w = reference.getWidth(), h = reference.getHeight();
                // THE SIZE CAP SCALES THE RENDER DOWN TO THE REFERENCE'S LARGER SIDE
                final int previous = WaterMediaConfig.codecs.svg.maxSize;
                final ImageData img;
                try {
                    WaterMediaConfig.codecs.svg.maxSize = Math.max(w, h);
                    img = CodecsAPI.decode(Fixtures.readAll(Fixtures.SVG_DIR.resolve(base + ".svg")));
                } finally {
                    WaterMediaConfig.codecs.svg.maxSize = previous;
                }
                assertEquals(w, img.width(), name + " width");
                assertEquals(h, img.height(), name + " height");
                final byte[] expected = new byte[w * h * 4];
                for (int i = 0; i < w * h; i++) {
                    final int argb = reference.getRGB(i % w, i / w);
                    expected[i * 4] = (byte) (argb >> 16);
                    expected[i * 4 + 1] = (byte) (argb >> 8);
                    expected[i * 4 + 2] = (byte) argb;
                    expected[i * 4 + 3] = (byte) (argb >>> 24);
                }
                // ANTI-ALIASED EDGES LEGITIMATELY DIFFER, SO ONLY THE MEAN AND THE SHARE OF CLEARLY DIFFERENT VALUES ARE BOUNDED
                final PixelDiff diff = PixelDiff.compare(expected, PamImage.bgraToRgba(img.frames()[0], w, h), REFERENCE_SMALL_DIFF);
                assertTrue(diff.meanDiff() <= REFERENCE_MAX_MEAN_DIFF, String.format(Locale.ROOT, "%s mean diff %.3f exceeds %.1f",
                        name, diff.meanDiff(), REFERENCE_MAX_MEAN_DIFF));
                assertTrue(diff.percentAboveSmall() <= REFERENCE_MAX_PERCENT, String.format(Locale.ROOT, "%s has %.3f%% values off by more than %d (limit %.1f%%)",
                        name, diff.percentAboveSmall(), REFERENCE_SMALL_DIFF, REFERENCE_MAX_PERCENT));
            }));
        }
        return tests;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static byte[] svg(final String viewBox, final String body) {
        return ("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\""
                + viewBox + "\">" + body + "</svg>").getBytes(StandardCharsets.UTF_8);
    }

    // READS A PIXEL FROM THE DEFAULT BGRA FRAME AND RETURNS {r, g, b, a}
    private static int[] rgba(final ImageData img, final int x, final int y) {
        final ByteBuffer bgra = img.frames()[0];
        final int i = (y * img.width() + x) * 4;
        return new int[] { bgra.get(i + 2) & 0xFF, bgra.get(i + 1) & 0xFF, bgra.get(i) & 0xFF, bgra.get(i + 3) & 0xFF };
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Paint and colour")
    class Paint {

        @Test
        @DisplayName("rgb() with surrounding spaces parses to the right colour")
        void rgbWithSpacesParses() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"rgb( 200, 10, 10 )\"/>"));
            final int[] c = rgba(img, img.width() / 2, img.height() / 2);
            // MUST BE RED, NOT THE INHERITED BLACK FALLBACK A PARSE FAILURE WOULD PRODUCE
            assertTrue(c[0] > 150 && c[1] < 70 && c[2] < 70, "expected red, got " + Arrays.toString(c));
        }

        @Test
        @DisplayName("hsl() parses to the expected colour")
        void hslColor() throws IOException {
            final int[] c = rgba(CodecsAPI.decode(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"hsl(120, 100%, 50%)\"/>")), 5, 5);
            assertTrue(c[1] > 200 && c[0] < 60 && c[2] < 60, "hsl(120,100%,50%) is green, got " + Arrays.toString(c));
        }

        @Test
        @DisplayName("Linear gradient interpolates red to blue across the shape")
        void linearGradientInterpolates() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<defs><linearGradient id=\"g\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\">"
                            + "<stop offset=\"0\" stop-color=\"#ff0000\"/>"
                            + "<stop offset=\"1\" stop-color=\"#0000ff\"/></linearGradient></defs>"
                            + "<rect width=\"100\" height=\"100\" fill=\"url(#g)\"/>"));
            final int[] left = rgba(img, 5, 50), right = rgba(img, 94, 50);
            assertTrue(left[0] > 150 && left[2] < 100, "left edge should be red, got " + Arrays.toString(left));
            assertTrue(right[2] > 150 && right[0] < 100, "right edge should be blue, got " + Arrays.toString(right));
        }

        @Test
        @DisplayName("Gradient href chains inherit stops across multiple hops")
        void gradientHrefChainResolvesStops() throws IOException {
            // a -> b -> c: ONLY c DECLARES STOPS AND ONLY a IS REFERENCED, SO THE CHAIN MUST BE FOLLOWED FULLY
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<defs>"
                            + "<linearGradient id=\"c\"><stop offset=\"0\" stop-color=\"#ff0000\"/>"
                            + "<stop offset=\"1\" stop-color=\"#0000ff\"/></linearGradient>"
                            + "<linearGradient id=\"b\" xlink:href=\"#c\"/>"
                            + "<linearGradient id=\"a\" href=\"#b\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"0\"/>"
                            + "</defs>"
                            + "<rect width=\"100\" height=\"100\" fill=\"url(#a)\"/>"));
            final int[] left = rgba(img, 5, 50), right = rgba(img, 94, 50);
            assertTrue(left[0] > 150 && left[2] < 100, "chained gradient must resolve stops (left red), got " + Arrays.toString(left));
            assertTrue(right[2] > 150 && right[0] < 100, "chained gradient must resolve stops (right blue), got " + Arrays.toString(right));
        }

        @Test
        @DisplayName("A RGBA request is honoured with a channel swizzle")
        void requestedRgbaIsHonoured() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/>")), PixelFormat.RGBA)) {
                assertEquals(PixelFormat.RGBA, reader.pixelFormat(), "reader must report the honoured format");
                final ByteBuffer f = reader.next();
                final int i = (reader.height() / 2 * reader.width() + reader.width() / 2) * 4;
                final int r = f.get(i) & 0xFF, g = f.get(i + 1) & 0xFF, b = f.get(i + 2) & 0xFF, a = f.get(i + 3) & 0xFF;
                // RGBA MEMORY ORDER: R FIRST, THEN G, B, A; A RED FILL IS (255,0,0,255)
                assertTrue(r > 200 && g < 60 && b < 60 && a > 200, "expected RGBA red, got " + r + "," + g + "," + b + "," + a);
            }
        }
    }

    @Nested
    @DisplayName("Geometry")
    class Geometry {

        @Test
        @DisplayName("rotate() transform places the shape at the rotated location")
        void transformRotate() throws IOException {
            // ROTATE 90 DEGREES ABOUT (50,50) MAPS A RECT AT x[70,90] y[10,30] TO x[70,90] y[70,90]
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<rect x=\"70\" y=\"10\" width=\"20\" height=\"20\" fill=\"#00ff00\" transform=\"rotate(90 50 50)\"/>"));
            assertTrue(rgba(img, 80, 80)[1] > 150, "rotated rect should cover (80,80)");
            assertTrue(rgba(img, 80, 20)[3] < 16, "original rect location must be empty after rotation");
        }

        @Test
        @DisplayName("matrix() transform translates the shape")
        void transformMatrix() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<rect x=\"0\" y=\"40\" width=\"20\" height=\"20\" fill=\"#00ff00\" transform=\"matrix(1 0 0 1 40 0)\"/>"));
            assertTrue(rgba(img, 50, 50)[1] > 150, "rect should move to x[40,60]");
            assertTrue(rgba(img, 10, 50)[3] < 16, "original x[0,20] must be empty");
        }

        @Test
        @DisplayName("skewX() shifts rows horizontally by tan(angle)*y")
        void transformSkewX() throws IOException {
            // skewX(45): x' = x + y. AT y=80 A STRIP AT x[0,10] SHIFTS TO ABOUT x[80,90]
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<rect x=\"0\" y=\"0\" width=\"10\" height=\"100\" fill=\"#00ff00\" transform=\"skewX(45)\"/>"));
            assertTrue(rgba(img, 84, 80)[1] > 120, "skewed strip should reach x=84 at y=80");
            assertTrue(rgba(img, 5, 80)[3] < 16, "strip has moved away from x=5 at y=80");
        }

        @Test
        @DisplayName("A-command arc fills the correct half-disc")
        void arcCommand() throws IOException {
            // SEMICIRCLE FROM (50,10) TO (50,90) WITH sweep=1: THE RIGHT HALF-DISC CLOSED BY THE VERTICAL CHORD
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100", "<path d=\"M50,10 A40,40 0 0 1 50,90 Z\" fill=\"#ff0000\"/>"));
            assertTrue(rgba(img, 75, 50)[0] > 150, "right half-disc should be filled at (75,50)");
            assertTrue(rgba(img, 25, 50)[3] < 16, "left half must be empty at (25,50)");
        }

        @Test
        @DisplayName("fill-rule evenodd punches a hole that nonzero fills")
        void evenOddFillRule() throws IOException {
            final String d = "d=\"M10,10 H90 V90 H10 Z M30,30 H70 V70 H30 Z\"";
            final ImageData eo = CodecsAPI.decode(svg("0 0 100 100", "<path fill-rule=\"evenodd\" fill=\"#ff0000\" " + d + "/>"));
            final ImageData nz = CodecsAPI.decode(svg("0 0 100 100", "<path fill=\"#ff0000\" " + d + "/>"));
            assertTrue(rgba(eo, 50, 50)[3] < 16, "evenodd: the inner square is a hole");
            assertTrue(rgba(eo, 20, 20)[0] > 150, "evenodd: the outer ring is filled");
            assertTrue(rgba(nz, 50, 50)[0] > 150, "nonzero: the inner square is filled (no hole)");
        }

        @Test
        @DisplayName("Stroke honours width and round caps")
        void strokeWidthAndCaps() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<line x1=\"10\" y1=\"50\" x2=\"90\" y2=\"50\" stroke=\"#ff0000\" stroke-width=\"10\"/>"));
            assertTrue(rgba(img, 50, 50)[0] > 150, "on the stroke centre");
            assertTrue(rgba(img, 50, 80)[3] < 16, "far from the stroke is empty");
            assertTrue(rgba(img, 92, 50)[0] > 120, "round cap extends past the endpoint");
            assertTrue(rgba(img, 99, 50)[3] < 16, "beyond the round cap is empty");
        }

        @Test
        @DisplayName("Percentage lengths resolve against the viewport")
        void percentageLengths() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100", "<rect x=\"25%\" y=\"25%\" width=\"50%\" height=\"50%\" fill=\"#ff0000\"/>"));
            assertTrue(rgba(img, 50, 50)[0] > 150, "centre of the 25%..75% rect is filled");
            assertTrue(rgba(img, 10, 10)[3] < 16, "outside the rect is empty");
        }

        @Test
        @DisplayName("Absolute width/height take precedence over viewBox for intrinsic size")
        void sizingPrecedence() throws IOException {
            final ImageData sized = CodecsAPI.decode(("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"64\" height=\"32\" viewBox=\"0 0 100 100\">"
                    + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\"/></svg>").getBytes(StandardCharsets.UTF_8));
            assertEquals(64, sized.width(), "absolute width wins over viewBox");
            assertEquals(32, sized.height(), "absolute height wins over viewBox");

            final ImageData viewBoxOnly = CodecsAPI.decode(svg("0 0 40 20", "<rect width=\"40\" height=\"20\" fill=\"#ff0000\"/>"));
            assertEquals(40, viewBoxOnly.width(), "viewBox provides intrinsic size when width/height are absent");
            assertEquals(20, viewBoxOnly.height());
        }
    }

    @Nested
    @DisplayName("Visibility")
    class Visibility {

        @Test
        @DisplayName("display:none subtrees are not painted")
        void displayNoneHidesSubtree() throws IOException {
            // A display:none RED RECT OVER THE WHOLE CANVAS MUST NOT PAINT; THE GREEN BASE SHOWS THROUGH
            final int[] c = rgba(CodecsAPI.decode(svg("0 0 100 100",
                    "<rect width=\"100\" height=\"100\" fill=\"#00ff00\"/>"
                            + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\" style=\"display:none\"/>")), 50, 50);
            assertTrue(c[1] > 150 && c[0] < 90, "green base must show, display:none red rect must be skipped, got " + Arrays.toString(c));
        }

        @Test
        @DisplayName("visibility:hidden shapes are laid out but not painted")
        void visibilityHiddenNotPainted() throws IOException {
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100",
                    "<rect width=\"100\" height=\"100\" fill=\"#00ff00\"/>"
                            + "<rect width=\"100\" height=\"100\" fill=\"#ff0000\" visibility=\"hidden\"/>"));
            assertTrue(rgba(img, 50, 50)[1] > 150, "hidden red rect must not paint over green");
        }

        @Test
        @DisplayName("<switch> renders only its first eligible child")
        void switchRendersFirstEligibleChild() throws IOException {
            // THE foreignObject NEEDS AN EXTENSION THE DECODER LACKS, SO THE RED RECT IS THE FIRST ELIGIBLE CHILD AND THE GREEN ONE NEVER PAINTS
            final int[] c = rgba(CodecsAPI.decode(svg("0 0 10 10",
                    "<switch><title>choice</title>"
                            + "<foreignObject requiredExtensions=\"http://example.com/x\"><rect width=\"10\" height=\"10\" fill=\"#0000ff\"/></foreignObject>"
                            + "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/>"
                            + "<rect width=\"10\" height=\"10\" fill=\"#00ff00\"/></switch>")), 5, 5);
            assertTrue(c[0] > 200 && c[1] < 60 && c[2] < 60, "the first eligible child (red) must paint, got " + Arrays.toString(c));
        }

        @Test
        @DisplayName("<switch> skips definition elements whatever their tag case")
        void switchSkipsDefinitionElements() throws IOException {
            // camelCase DEFINITION TAGS ARE NO CANDIDATES: SELECTING ONE WOULD PAINT NOTHING INSTEAD OF THE RED RECT
            final int[] c = rgba(CodecsAPI.decode(svg("0 0 10 10",
                    "<switch><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#0000ff\"/></linearGradient>"
                            + "<radialGradient id=\"r\"/><clipPath id=\"c\"><rect width=\"1\" height=\"1\"/></clipPath>"
                            + "<rect width=\"10\" height=\"10\" fill=\"#ff0000\"/></switch>")), 5, 5);
            assertTrue(c[0] > 200 && c[1] < 60 && c[2] < 60, "the rect after the definitions must paint, got " + Arrays.toString(c));
        }
    }

    @Nested
    @DisplayName("Robustness")
    class Robustness {

        @Test
        @DisplayName("Non-SVG XML is not accepted as an image")
        void rejectsNonSvgXml() {
            final byte[] html = "<?xml version=\"1.0\"?>\n<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8);
            assertThrows(UnsupportedFormatException.class, () -> CodecsAPI.decode(html));
        }

        @Test
        @DisplayName("Path data with a stray operand after Z does not hang")
        void closePathWithStrayOperandTerminates() {
            // d="...z5": Z CONSUMES NO INPUT, SO THE PARSER MUST STILL ADVANCE PAST THE STRAY OPERAND AND FINISH
            final byte[] svg = svg("0 0 100 100", "<path d=\"M10,10 L90,90 L10,90 z5\" fill=\"#ff0000\"/>");
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> CodecsAPI.decode(svg));
        }

        @Test
        @DisplayName("Pathologically deep nesting fails with IOException, not StackOverflowError/hang")
        void deepNestingIsBounded() {
            final byte[] svg = svg("0 0 100 100", "<g>".repeat(4_000) + "<rect width=\"10\" height=\"10\"/>" + "</g>".repeat(4_000));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(IOException.class, () -> CodecsAPI.decode(svg)));
        }

        @Test
        @DisplayName("A missing operand does not swallow the following command")
        void missingOperandDoesNotEatNextCommand() throws IOException {
            // C HAS ONLY 5 OF 6 OPERANDS THEN z: THE MALFORMED CUBIC IS DROPPED AND z STILL CLOSES M..L..L
            final ImageData img = CodecsAPI.decode(svg("0 0 100 100", "<path d=\"M10,10 L90,10 L90,90 C1 2 3 4 5z\" fill=\"#ff0000\"/>"));
            assertTrue(rgba(img, 80, 40)[0] > 150, "close after the malformed C must still fill the triangle interior");
            assertTrue(rgba(img, 10, 5)[3] < 16, "no bogus cubic to (5,0) should leak fill above the triangle");
        }

        @Test
        @DisplayName("reset() then next() returns a fully-readable frame")
        void resetRewindsFrame() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(svg("0 0 10 10", "<rect width=\"10\" height=\"10\" fill=\"#00ff00\"/>")))) {
                final ByteBuffer first = reader.next();
                final int cap = first.capacity();
                // DRAIN THE BUFFER LIKE A GL UPLOAD WOULD
                while (first.hasRemaining()) first.get();
                assertTrue(reader.reset());
                final ByteBuffer second = reader.next();
                assertEquals(0, second.position(), "frame must be rewound to position 0 on the second pass");
                assertEquals(cap, second.remaining(), "the whole frame must be readable again");
            }
        }
    }
}
