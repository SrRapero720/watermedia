package org.watermedia.api.player;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLCapabilities;
import org.watermedia.api.player.Colorimetry.Matrix;
import org.watermedia.api.player.Colorimetry.Transfer;
import org.watermedia.api.util.PixelFormat;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.YuvFrames;

import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/** Verifies {@link GLEngine} plane layouts, colorimetry, uploads and shared programs on a real OpenGL 3.2 core context, if any. */
@DisplayName("GLEngine")
public class GLEngineTest {

    private static final double[] BT601 = { 0.299, 0.114 };
    private static final double[] BT709 = { 0.2126, 0.0722 };
    private static final double[] BT2020 = { 0.2627, 0.0593 };

    private static boolean initialized;
    private static long window = NULL;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    @BeforeAll
    static void context() {
        assumeTrue(!GraphicsEnvironment.isHeadless(), "No display, skipping the OpenGL uploads");
        MediaBootstrap.client();
        // MACOS REFUSES GLFW OUTSIDE THE MAIN THREAD WITH AN EXCEPTION, WHICH ALSO MEANS "NO CONTEXT HERE"
        try { initialized = glfwInit(); }
        catch (final IllegalStateException offMainThread) { initialized = false; }
        assumeTrue(initialized, "GLFW could not initialize, skipping");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        window = glfwCreateWindow(16, 16, "GLEngineTest", NULL, NULL);
        assumeTrue(window != NULL, "No OpenGL 3.2 core context, skipping");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    @AfterAll
    static void destroy() {
        if (window != NULL) {
            glfwMakeContextCurrent(NULL);
            glfwDestroyWindow(window);
        }
        if (initialized) glfwTerminate();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // READS A PRIVATE FIELD OF AN ENGINE OR ITS KIT: THE RING AND THE SHARED PROGRAMS ARE NOT PART OF THE API
    private static Object field(final Object owner, final String member) {
        try {
            final Field field = owner.getClass().getDeclaredField(member);
            field.setAccessible(true);
            return field.get(owner);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(owner.getClass().getSimpleName() + " has no field " + member, e);
        }
    }

    // WHETHER THE CONTEXT CAN BACK THE ENGINE'S PERSISTENT-MAPPED PRODUCER RING
    private static boolean ringCapable() {
        final GLCapabilities caps = GL.getCapabilities();
        return caps.GL_ARB_buffer_storage || caps.OpenGL44;
    }

    // RUNS EVERY TASK THE ENGINE QUEUED FOR THE RENDER THREAD, INCLUDING THE ONES QUEUED WHILE PUMPING
    private static void pump(final Queue<Runnable> tasks) {
        for (Runnable task = tasks.poll(); task != null; task = tasks.poll()) task.run();
    }

    // 8-BIT LIMITED-RANGE GRAY AS ANY MATRIX SHOWS IT (NEUTRAL CHROMA)
    private static int gray(final int luma) {
        return YuvFrames.rgb(BT601[0], BT601[1], false, 8, luma, 128, 128)[0];
    }

    // AN OPAQUE RGBA EXPECTATION FOR A CONVERTED rgb COLOR
    private static int[] opaque(final int[] rgb) {
        return new int[] { rgb[0], rgb[1], rgb[2], 255 };
    }

    // A 2x2 BGRA FRAME WHOSE EVERY BYTE IS shade
    private static ByteBuffer bgra(final int shade) {
        return YuvFrames.plane(0, 8, 2, (row, col) -> shade);
    }

    // A 2x2 YUV444P FRAME OF ONE UNIFORM YCbCr SAMPLE
    private static ByteBuffer[] uniform(final int y, final int cb, final int cr) {
        return new ByteBuffer[] {
                YuvFrames.plane(0, 2, 2, (row, col) -> y),
                YuvFrames.plane(0, 2, 2, (row, col) -> cb),
                YuvFrames.plane(0, 2, 2, (row, col) -> cr) };
    }

    // A 2x2 10-BIT YUV420P GRAY: LUMA code, NEUTRAL CHROMA
    private static ByteBuffer[] gray10(final int code) {
        return new ByteBuffer[] {
                YuvFrames.plane16(2, 2, (row, col) -> code),
                YuvFrames.plane16(1, 1, (row, col) -> 512),
                YuvFrames.plane16(1, 1, (row, col) -> 512) };
    }

    // LINEAR FILTERING OF A ROW OF TEXELS AT A TEXEL COORDINATE, CLAMPED TO THE EDGES LIKE GL_CLAMP_TO_EDGE
    private static double bilinear(final int[] texels, final double at) {
        final double t = Math.max(0.0, Math.min(texels.length - 1.0, at - 0.5));
        final int first = (int) Math.floor(t);
        final int second = Math.min(first + 1, texels.length - 1);
        return texels[first] + (texels[second] - texels[first]) * (t - first);
    }

    // CONVERTS ONE TIGHT FRAME ON A FRESH ENGINE (null COLOR = THE UNTAGGED GUESS) AND READS ITS RGBA BACK
    private static ByteBuffer convert(final PixelFormat format, final int width, final int height, final int bits,
                                      final Colorimetry color, final ByteBuffer[] planes) {
        final GLEngine engine = PlayerAPI.glEngine(null, null);
        try {
            assertTrue(color == null ? engine.format(format, width, height, bits) : engine.format(format, width, height, bits, color));
            engine.upload(planes, new int[planes.length]);
            return pixels(engine, width, height);
        } finally {
            engine.release();
        }
    }

    // READS THE ENGINE'S RGBA TEXTURE BACK, ROW 0 FIRST
    private static ByteBuffer pixels(final GLEngine engine, final int width, final int height) {
        final ByteBuffer out = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, (int) engine.texture());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return out;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Plane layouts")
    class Layouts {

        @Test
        @DisplayName("An odd-width YUV420P frame keeps its last chroma column, with padded planes ending on a tight row")
        void oddWidthKeepsLastChromaColumn() {
            final int w = 853;
            final int h = 3;
            final int cw = 427; // FFMPEG ROUNDS 4:2:0 CHROMA UP: A 853x3 FRAME CARRIES 427x2 CHROMA SAMPLES
            final int ch = 2;
            // Y AND U ROWS ARE PADDED BUT EACH PLANE STOPS RIGHT AFTER ITS LAST ROW; U MARKS ONLY ITS LAST COLUMN (STRONG BLUE)
            final ByteBuffer y = YuvFrames.plane(864, w, h, (row, col) -> 128);
            final ByteBuffer u = YuvFrames.plane(448, cw, ch, (row, col) -> col == cw - 1 ? 255 : 128);
            final ByteBuffer v = YuvFrames.plane(0, cw, ch, (row, col) -> 128);
            final GLEngine engine = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(engine.format(PixelFormat.YUV420P, w, h, 8));
                engine.upload(new ByteBuffer[] { y, u, v }, new int[] { 864, 448, 0 });
                final ByteBuffer rgba = pixels(engine, w, h);
                final int first = rgba.get(2) & 0xFF;
                final int middle = rgba.get(400 * 4 + 2) & 0xFF;
                final int last = rgba.get((w - 1) * 4 + 2) & 0xFF;
                assertTrue(Math.abs(first - gray(128)) <= 2, "the minimum-size planes were accepted and converted: " + first);
                assertTrue(Math.abs(middle - first) <= 2, "neutral chroma stays neutral: " + first + " vs " + middle);
                assertTrue(last > first + 80, "the last pixel samples the last chroma column: blue " + last + " vs " + first);
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("An odd-width YUYV frame gives every pixel the luma of its own pair")
        void oddWidthPackedLuma() {
            // FIVE PIXELS ARE THREE [Y0 U Y1 V] GROUPS; THE LAST Y1 IS PADDING NO PIXEL SHOWS
            final int[] luma = { 50, 100, 150, 200, 250 };
            final ByteBuffer yuyv = YuvFrames.plane(0, 12, 1, (row, col) -> col % 2 == 1 ? 128 : col / 2 < luma.length ? luma[col / 2] : 0);
            final GLEngine engine = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(engine.format(PixelFormat.YUYV, 5, 1, 8));
                engine.upload(new ByteBuffer[] { yuyv }, new int[] { 0 });
                final ByteBuffer rgba = pixels(engine, 5, 1);
                for (int x = 0; x < luma.length; x++)
                    assertTrue(Math.abs((rgba.get(x * 4) & 0xFF) - gray(luma[x])) <= 2, "pixel " + x + " shows " + (rgba.get(x * 4) & 0xFF));
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("An RGB stride that is not a whole number of texels lands every row in place")
        void raggedRgbStride() {
            // 5 RGB PIXELS ARE 15 BYTES; A 16-BYTE STRIDE CANNOT BE A WHOLE ROW LENGTH IN TEXELS
            final ByteBuffer rgb = YuvFrames.plane(16, 15, 2, (row, col) -> row * 100 + col + 1);
            final GLEngine engine = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(engine.format(PixelFormat.RGB, 5, 2, 8));
                engine.upload(new ByteBuffer[] { rgb }, new int[] { 16 });
                final ByteBuffer rgba = pixels(engine, 5, 2);
                for (int row = 0; row < 2; row++) {
                    for (int col = 0; col < 5; col++) {
                        for (int c = 0; c < 3; c++)
                            assertEquals(row * 100 + col * 3 + c + 1, rgba.get((row * 5 + col) * 4 + c) & 0xFF, "row " + row + " pixel " + col);
                    }
                }
            } finally {
                engine.release();
            }
        }
    }

    @Nested
    @DisplayName("Colorimetry")
    class ColorConversion {

        @Test
        @DisplayName("Gray frames stay full range unless tagged otherwise")
        void grayIsFullRange() {
            final ByteBuffer gray = YuvFrames.plane(0, 2, 1, (row, col) -> col == 0 ? 16 : 235);
            final ByteBuffer rgba = convert(PixelFormat.GRAY, 2, 1, 8, null, new ByteBuffer[] { gray });
            assertEquals(16, rgba.get(0) & 0xFF, "full-range black level");
            assertEquals(235, rgba.get(4) & 0xFF, "full-range near white");
        }

        @Test
        @DisplayName("Each matrix and range converts like the BT.601, BT.709 and BT.2020 formulas")
        void matricesAndRanges() {
            record Case(Matrix matrix, boolean full, double[] weights) {}
            final Case[] cases = {
                    new Case(Matrix.BT601, false, BT601), new Case(Matrix.BT709, false, BT709),
                    new Case(Matrix.BT2020, false, BT2020), new Case(Matrix.BT709, true, BT709)
            };
            for (final Case c: cases) {
                final Colorimetry color = new Colorimetry(c.matrix(), c.full(), true, false, Transfer.SDR, false);
                final ByteBuffer rgba = convert(PixelFormat.YUV444P, 2, 2, 8, color, uniform(120, 90, 200));
                final int[] expected = YuvFrames.rgb(c.weights()[0], c.weights()[1], c.full(), 8, 120, 90, 200);
                YuvFrames.assertUniform(rgba, opaque(expected), 3, c.matrix() + (c.full() ? " full" : " limited"));
            }
        }

        @Test
        @DisplayName("Subsampled chroma sits centered or co-sited with the first luma column and row")
        void chromaSiting() {
            // ONE STRONG-BLUE CHROMA SAMPLE NEXT TO A ZERO ONE: THE BLUE OF EACH PIXEL REVEALS WHERE ITS CHROMA WAS SAMPLED
            final int[] samples = { 255, 0 };
            for (final boolean cosited: new boolean[] { false, true }) {
                final Colorimetry horizontal = new Colorimetry(Matrix.BT601, false, cosited, false, Transfer.SDR, false);
                final ByteBuffer wide = convert(PixelFormat.YUV420P, 4, 2, 8, horizontal, new ByteBuffer[] {
                        YuvFrames.plane(0, 4, 2, (row, col) -> 128),
                        YuvFrames.plane(0, 2, 1, (row, col) -> samples[col]),
                        YuvFrames.plane(0, 2, 1, (row, col) -> 128) });
                final Colorimetry vertical = new Colorimetry(Matrix.BT601, false, false, cosited, Transfer.SDR, false);
                final ByteBuffer tall = convert(PixelFormat.YUV420P, 2, 4, 8, vertical, new ByteBuffer[] {
                        YuvFrames.plane(0, 2, 4, (row, col) -> 128),
                        YuvFrames.plane(0, 1, 2, (row, col) -> samples[row]),
                        YuvFrames.plane(0, 1, 2, (row, col) -> 128) });
                for (int i = 0; i < 4; i++) {
                    // CHROMA TEXEL COORDINATE OF LUMA PIXEL i: (i + 0.5) / 2 CENTERED, i / 2 + 0.5 CO-SITED
                    final double at = cosited ? i / 2.0 + 0.5 : (i + 0.5) / 2.0;
                    final int blue = YuvFrames.rgb(BT601[0], BT601[1], false, 8, 128, (int) Math.round(bilinear(samples, at)), 128)[2];
                    final String siting = cosited ? "co-sited" : "centered";
                    assertTrue(Math.abs((wide.get(i * 4 + 2) & 0xFF) - blue) <= 3, siting + " column " + i + ": " + (wide.get(i * 4 + 2) & 0xFF) + " vs " + blue);
                    assertTrue(Math.abs((tall.get(i * 8 + 2) & 0xFF) - blue) <= 3, siting + " row " + i + ": " + (tall.get(i * 8 + 2) & 0xFF) + " vs " + blue);
                }
            }
        }

        @Test
        @DisplayName("PQ and HLG grays keep their SDR brightness below the knee and a 1000-nit peak lands on white")
        void hdrTransfers() {
            final int[] cases = { 100, 1_000 };
            for (final int nits: cases) {
                final int code = (int) Math.round(64 + YuvFrames.pq(nits) * 876);
                final ByteBuffer rgba = convert(PixelFormat.YUV420P, 2, 2, 10, new Colorimetry(Matrix.BT2020, false, true, false, Transfer.PQ, true), gray10(code));
                final int shown = rgba.get(0) & 0xFF;
                if (nits == 1_000) assertTrue(shown >= 252, "PQ 1000 nits reaches white: " + shown);
                else assertTrue(Math.abs(shown - YuvFrames.hdrGray(nits)) <= 3, "PQ " + nits + " nits: " + shown + " vs " + YuvFrames.hdrGray(nits));
            }
            final int code = (int) Math.round(64 + 0.5 * 876);
            final ByteBuffer rgba = convert(PixelFormat.YUV420P, 2, 2, 10, new Colorimetry(Matrix.BT2020, false, true, false, Transfer.HLG, true), gray10(code));
            final int expected = YuvFrames.hdrGray(YuvFrames.hlgNits(0.5));
            assertTrue(Math.abs((rgba.get(0) & 0xFF) - expected) <= 3, "HLG half signal: " + (rgba.get(0) & 0xFF) + " vs " + expected);
        }

        @Test
        @DisplayName("BT.2020 primaries map to the same BT.709 color")
        void wideGamut() {
            // A BT.709 COLOR (LINEAR 0.5, 0.3, 0.2) RE-EXPRESSED IN BT.2020 PRIMARIES (BT.2087), ENCODED WITH GAMMA 2.4
            final double[] linear = { 0.5, 0.3, 0.2 };
            final double r = 0.6274 * linear[0] + 0.3293 * linear[1] + 0.0433 * linear[2];
            final double g = 0.0691 * linear[0] + 0.9195 * linear[1] + 0.0114 * linear[2];
            final double b = 0.0164 * linear[0] + 0.0880 * linear[1] + 0.8956 * linear[2];
            final int[] codes = YuvFrames.ycbcr(BT2020[0], BT2020[1], Math.pow(r, 1 / 2.4), Math.pow(g, 1 / 2.4), Math.pow(b, 1 / 2.4));
            final Colorimetry color = new Colorimetry(Matrix.BT2020, false, true, false, Transfer.SDR, true);
            final ByteBuffer rgba = convert(PixelFormat.YUV444P, 2, 2, 8, color, uniform(codes[0], codes[1], codes[2]));
            final int[] expected = new int[3];
            for (int c = 0; c < 3; c++) expected[c] = (int) Math.round(Math.pow(linear[c], 1 / 2.4) * 255);
            YuvFrames.assertUniform(rgba, opaque(expected), 3, "BT.2020 to BT.709");
        }
    }

    @Nested
    @DisplayName("Upload pipeline")
    class Pipeline {

        @Test
        @DisplayName("Without a render thread every upload shows its own frame at once, through the alternating PBOs too")
        void synchronousUploadsShowTheFrameJustUploaded() {
            final GLEngine engine = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(engine.format(PixelFormat.BGRA, 2, 2));
                // THE FIRST FRAME SPECIFIES THE TEXTURE; THE NEXT ONES STREAM THROUGH THE TWO PBOS IN TURN
                for (final int shade: new int[] { 40, 120, 200, 80 }) {
                    engine.upload(new ByteBuffer[] { bgra(shade) }, new int[] { 0 });
                    YuvFrames.assertUniform(pixels(engine, 2, 2), new int[] { shade, shade, shade, shade }, 0, "frame " + shade);
                }
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("An engine without a render thread never maps the producer ring it cannot use")
        void synchronousEngineMapsNoRing() {
            assumeTrue(ringCapable(), "the context cannot map a persistent ring");
            final GLEngine engine = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(engine.format(PixelFormat.BGRA, 2, 2));
                for (final int shade: new int[] { 40, 120 }) engine.upload(new ByteBuffer[] { bgra(shade) }, new int[] { 0 });
                assertEquals(0L, field(engine, "ringAddr"), "no ring is mapped");
                assertEquals(0, field(engine, "ringBufferId"), "no ring buffer exists");
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A producer thread's frames reach the texture when the render thread drains, newest first, and release defers its GL work")
        void renderThreadPipeline() throws Exception {
            // THIS THREAD OWNS THE CONTEXT AND PLAYS THE RENDER LOOP: IT RUNS WHATEVER THE ENGINE QUEUED WHEN IT PUMPS
            final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
            final GLEngine engine = PlayerAPI.glEngine(Thread.currentThread(), tasks::add);
            final ExecutorService producer = Executors.newSingleThreadExecutor();
            try {
                assertTrue(producer.submit(() -> engine.format(PixelFormat.BGRA, 2, 2)).get(5, TimeUnit.SECONDS));
                assertEquals(0, engine.width(), "the layout waits for the render thread");
                pump(tasks);
                assertEquals(2, engine.width());
                producer.submit(() -> engine.upload(new ByteBuffer[] { bgra(40) }, new int[] { 0 })).get(5, TimeUnit.SECONDS);
                assertEquals(0L, engine.texture(), "nothing is uploaded before the render thread drains");
                pump(tasks);
                YuvFrames.assertUniform(pixels(engine, 2, 2), new int[] { 40, 40, 40, 40 }, 0, "the first frame");

                // TWO FRAMES BEFORE ONE DRAIN: THE NEWEST WINS, THROUGH THE PRODUCER RING WHEN THE CONTEXT CAN MAP ONE
                producer.submit(() -> {
                    engine.upload(new ByteBuffer[] { bgra(120) }, new int[] { 0 });
                    engine.upload(new ByteBuffer[] { bgra(200) }, new int[] { 0 });
                }).get(5, TimeUnit.SECONDS);
                pump(tasks);
                YuvFrames.assertUniform(pixels(engine, 2, 2), new int[] { 200, 200, 200, 200 }, 0, "the newest frame");
                assertEquals(ringCapable(), (long) field(engine, "ringAddr") != 0L, "the ring is mapped exactly when the context supports it");

                final int texture = (int) engine.texture();
                producer.submit(() -> engine.release()).get(5, TimeUnit.SECONDS);
                assertEquals(0L, engine.texture(), "a released engine exposes no texture at once");
                assertTrue(GL11.glIsTexture(texture), "the GL teardown waits for the render thread");
                pump(tasks);
                assertFalse(GL11.glIsTexture(texture), "the drained teardown deleted the texture no unit binds");
            } finally {
                producer.shutdownNow();
                engine.release();
                pump(tasks);
            }
        }
    }

    @Nested
    @DisplayName("Shared objects")
    class Shared {

        @Test
        @DisplayName("Engines on one context share their conversion programs and the last one released deletes them")
        void sharedConversionKit() {
            final int[] expected = opaque(YuvFrames.rgb(BT601[0], BT601[1], false, 8, 120, 90, 200));
            final Colorimetry color = new Colorimetry(Matrix.BT601, false, true, false, Transfer.SDR, false);
            final GLEngine first = PlayerAPI.glEngine(null, null);
            final GLEngine second = PlayerAPI.glEngine(null, null);
            final Object kit;
            final int program;
            try {
                assertTrue(first.format(PixelFormat.YUV444P, 2, 2, 8, color));
                first.upload(uniform(120, 90, 200), new int[3]);
                assertTrue(second.format(PixelFormat.YUV444P, 2, 2, 8, color));
                second.upload(uniform(120, 90, 200), new int[3]);
                kit = field(first, "kit");
                assertNotNull(kit, "a conversion layout takes the context's kit");
                assertSame(kit, field(second, "kit"), "a second engine on the same context reuses the kit");
                // THE KIT'S YUV 3-PLANE PROGRAM, THE ONLY KIND THIS TEST CONVERTS
                program = Arrays.stream((int[]) field(kit, "programs")).filter(id -> id != 0).findFirst().orElse(0);
                assertTrue(GL20.glIsProgram(program), "the shared program is live");

                first.release();
                assertTrue(GL20.glIsProgram(program), "the kit survives while an engine still uses it");
                second.upload(uniform(120, 90, 200), new int[3]);
                YuvFrames.assertUniform(pixels(second, 2, 2), expected, 3, "the remaining engine still converts");
            } finally {
                first.release();
                second.release();
            }
            assertFalse(GL20.glIsProgram(program), "the last engine released deletes the program");

            final GLEngine third = PlayerAPI.glEngine(null, null);
            try {
                assertTrue(third.format(PixelFormat.YUV444P, 2, 2, 8, color));
                third.upload(uniform(120, 90, 200), new int[3]);
                assertNotSame(kit, field(third, "kit"), "a later engine builds a fresh kit");
                YuvFrames.assertUniform(pixels(third, 2, 2), expected, 3, "the fresh kit converts");
            } finally {
                third.release();
            }
        }
    }
}
