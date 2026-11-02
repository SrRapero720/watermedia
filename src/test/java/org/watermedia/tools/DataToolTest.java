package org.watermedia.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.util.PixelFormat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the pure byte/string helpers in {@link DataTool}: log redaction, lenient parsing and pixel math. */
@DisplayName("DataTool")
public class DataToolTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Redaction")
    class Redaction {

        @Test
        @DisplayName("redact keeps links readable and masks only URI passwords and secret query values")
        void redactUris() {
            final String links = "[https://first.test/watch?v=abc123] -> //second.test/video/42?key=two "
                    + "rtsp://admin@camera.test/live file:/home/user/clip.mp4 https://bad.test/%not-hex";
            assertEquals(links, DataTool.redact(links), "links, IDs and user names pass untouched");
            final String safe = DataTool.redact("Source 'ftp://name:password@files.test/path?id=7' failed");
            assertEquals("Source 'ftp://name:********@files.test/path?id=7' failed", safe);
            assertEquals(safe, DataTool.redact(safe));
            assertEquals("https://cdn.test/media?access_token=********&id=9", DataTool.redact("https://cdn.test/media?access_token=private-token&id=9"));
        }

        @Test
        @DisplayName("redact stays linear on hostile runs and masks cookies and every Authorization scheme")
        void redactBounded() {
            // A BACKTRACKING PATTERN WOULD HANG HERE, SO THE BUDGET IS ENFORCED WHILE THE RUN IS STILL GOING
            for (final String run: new String[] { "a.".repeat(40_000), "a-".repeat(40_000), "eyJ".repeat(40_000), "a://b:".repeat(15_000) })
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> DataTool.redact(run), "a 80k run must redact in well under two seconds");
            final String safe = DataTool.redact("Authorization: Basic dXNlcjpwYXNz\nAuthorization: OAuth abc\nCookie: session=private; theme=dark\nSet-Cookie: id=1");
            assertFalse(safe.contains("dXNlcjpwYXNz"), safe);
            assertFalse(safe.contains("abc"), safe);
            assertFalse(safe.contains("private"), safe);
            assertFalse(safe.contains("id=1"), safe);
            assertTrue(safe.contains("Authorization: Basic ********"), safe);
            assertTrue(safe.contains("Cookie: ********"), safe);
        }

        @Test
        @DisplayName("redact masks bare tokens, session arguments, Authorization values and secret pairs")
        void redactSecrets() {
            final String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXZhbHVl";
            final String safe = DataTool.redact("token " + jwt + " --accessToken abc123 --session token:deadbeef:0123-4567 "
                    + "Authorization: Bearer secret-bearer apiKey=plain-key \"password\":\"hunter2\" plain=visible");
            assertFalse(safe.contains(jwt), safe);
            assertFalse(safe.contains("abc123"), safe);
            assertFalse(safe.contains("deadbeef"), safe);
            assertFalse(safe.contains("secret-bearer"), safe);
            assertFalse(safe.contains("plain-key"), safe);
            assertFalse(safe.contains("hunter2"), safe);
            assertTrue(safe.contains("plain=visible"), safe);
            assertTrue(safe.contains("Authorization: Bearer ********"), safe);
            assertFalse(DataTool.redact("accessToken=" + jwt + " loaded").contains(jwt), "a token behind a key is masked too");
            assertFalse(DataTool.redact("\"api_key\":\"AIzaSyExampleKey\"").contains("AIzaSyExampleKey"));
            assertFalse(DataTool.redact("client_secret = mySuperSecret").contains("mySuperSecret"));
            assertFalse(DataTool.redact("authorization=Zm9vYmFyQmF6").contains("Zm9vYmFyQmF6"));
            final String clean = "This is a normal log line with no secrets at timestamp 12:34:56.";
            assertEquals(clean, DataTool.redact(clean), "ordinary text passes untouched");
            assertEquals("", DataTool.redact(""));
            assertNull(DataTool.redact(null));
        }
    }

    @Nested
    @DisplayName("Parsing")
    class Parsing {

        @Test
        @DisplayName("uri parses web URLs leniently: scheme-relative gets https, anything else yields null")
        void uri() {
            assertEquals(URI.create("https://cdn.test/a"), DataTool.uri("https://cdn.test/a"));
            assertEquals(URI.create("https://cdn.test/b"), DataTool.uri("//cdn.test/b"));
            assertEquals(URI.create("HTTPS://cdn.test/c"), DataTool.uri("HTTPS://cdn.test/c"), "schemes compare case-insensitively");
            assertNull(DataTool.uri("file:///etc/passwd"));
            assertNull(DataTool.uri("jar:file:///x.jar!/y"));
            assertNull(DataTool.uri("ftp://files.test/a"));
            assertNull(DataTool.uri("/relative/path"));
            assertNull(DataTool.uri("not a url"));
            assertNull(DataTool.uri(""));
            assertNull(DataTool.uri(null));
        }

        @Test
        @DisplayName("megabytes converts configuration values and the lenient parsers fall back")
        void unitsAndParsers() {
            assertEquals(8_388_608L, DataTool.megabytes(8));
            assertEquals(0L, DataTool.megabytes(0));
            assertEquals(7, DataTool.parseInt(" 7 ", -1));
            assertEquals(-1, DataTool.parseInt("seven", -1));
            assertEquals(-1, DataTool.parseInt(null, -1));
            assertEquals(1_700_000_000L, DataTool.parseLong("1700000000", 0L));
            assertEquals(0L, DataTool.parseLong("", 0L));
            assertEquals(3L, DataTool.parseLong(null, 3L));
            assertEquals(1.5, DataTool.parseDouble("1.5", 0.0));
            assertEquals(0.25, DataTool.parseDouble("x", 0.25));
        }

        @Test
        @DisplayName("expiry reads a signed URL's lifetime in seconds or milliseconds and ignores the rest")
        void expiry() {
            assertEquals(Instant.ofEpochSecond(1_700_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?a=1&expire=1700000000")));
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?validto=1700000000000")));
            assertEquals(Instant.ofEpochSecond(1_700_000_000L), DataTool.expiry(URI.create("https://cdn.test/x?X-Expires=1700000000")));
            assertNull(DataTool.expiry(URI.create("https://cdn.test/x?expires=soon")));
            assertNull(DataTool.expiry(URI.create("https://cdn.test/x?expires=3600")), "a relative lifetime is not an epoch");
            assertNull(DataTool.expiry(URI.create("https://cdn.test/x")));
            assertNull(DataTool.expiry(null));
        }

        @Test
        @DisplayName("startsWith and equalsAnyIgnoreCase match any of their candidates")
        void stringHelpers() {
            assertTrue(DataTool.startsWith("hello", "x", "he"));
            assertFalse(DataTool.startsWith("hello", "x", "y"));
            assertTrue(DataTool.equalsAnyIgnoreCase("ABC", "abc"));
            assertFalse(DataTool.equalsAnyIgnoreCase("ABC", "abd", "x"));
        }

        @Test
        @DisplayName("similarity scores one keystroke apart near 1 and unrelated words near 0, ignoring case")
        void similarity() {
            assertEquals(1f, DataTool.similarity("Cats", "cats"));
            assertEquals(1f, DataTool.similarity("", ""));
            assertEquals(0f, DataTool.similarity("cats", ""));
            assertEquals(0f, DataTool.similarity("abc", "xyz"));
            assertTrue(DataTool.similarity("cats", "cars") > 0.85f, "a fixed letter is the same word");
            assertTrue(DataTool.similarity("cat", "cats") > 0.9f, "one letter more is the same word");
            assertTrue(DataTool.similarity("cast", "cats") > 0.9f, "two letters swapped are the same word");
            assertTrue(DataTool.similarity("cats", "dogs") <= 0.5f, "unrelated words stay low");
            assertThrows(IllegalArgumentException.class, () -> DataTool.similarity(null, "cats"));
        }
    }

    @Nested
    @DisplayName("Pixels")
    class Pixels {

        @Test
        @DisplayName("premultiply rounds every channel and alpha pair exactly within position and limit")
        void premultiply() {
            // EVERY (CHANNEL, ALPHA) PAIR: B AND R CARRY THE CHANNEL, G ITS COMPLEMENT, SO LANES CANNOT HIDE EACH OTHER
            final ByteBuffer pixels = ByteBuffer.allocateDirect(256 * 256 * 4 + 8);
            pixels.put(0, (byte) 0x7F).put(pixels.capacity() - 1, (byte) 0x7F).position(4);
            for (int a = 0; a < 256; a++)
                for (int c = 0; c < 256; c++)
                    pixels.put((byte) c).put((byte) (255 - c)).put((byte) c).put((byte) a);
            pixels.position(4).limit(4 + 256 * 256 * 4);
            DataTool.premultiply(pixels);
            assertEquals(4, pixels.position());
            assertEquals(4 + 256 * 256 * 4, pixels.limit());
            pixels.limit(pixels.capacity());
            for (int a = 0, i = 4; a < 256; a++) {
                for (int c = 0; c < 256; c++, i += 4) {
                    final int expected = Math.round(c * a / 255f);
                    assertEquals(expected, pixels.get(i) & 0xFF, "B c=" + c + " a=" + a);
                    assertEquals(Math.round((255 - c) * a / 255f), pixels.get(i + 1) & 0xFF, "G c=" + c + " a=" + a);
                    assertEquals(expected, pixels.get(i + 2) & 0xFF, "R c=" + c + " a=" + a);
                    assertEquals(a, pixels.get(i + 3) & 0xFF, "A c=" + c + " a=" + a);
                }
            }
            assertEquals(0x7F, pixels.get(0) & 0xFF);
            assertEquals(0x7F, pixels.get(pixels.capacity() - 1) & 0xFF);
        }

        @Test
        @DisplayName("scaleArea walks every plane at its own size and refuses layouts it cannot average")
        void scaleAreaPlanes() {
            // 5x3 YUV420P: Y 5x3, U/V 3x2 EACH, ROUNDED UP; EVERY PLANE IS UNIFORM SO ANY AVERAGE KEEPS ITS VALUE
            final ByteBuffer src = ByteBuffer.allocateDirect((int) PixelFormat.YUV420P.frameBytes(5, 3, 8));
            for (int i = 0; i < 15; i++) src.put((byte) 200);
            for (int i = 0; i < 6; i++) src.put((byte) 60);
            for (int i = 0; i < 6; i++) src.put((byte) 90);
            src.flip();
            final ByteBuffer dst = ByteBuffer.allocateDirect((int) PixelFormat.YUV420P.frameBytes(3, 1, 8));
            DataTool.scaleArea(src, 5, 3, dst, 3, 1, PixelFormat.YUV420P);
            assertEquals(dst.capacity(), dst.position(), "Y 3x1 plus U and V 2x1 fill the target exactly");
            for (int i = 0; i < 3; i++) assertEquals(200, dst.get(i) & 0xFF);
            assertEquals(60, dst.get(3) & 0xFF);
            assertEquals(60, dst.get(4) & 0xFF);
            assertEquals(90, dst.get(5) & 0xFF);
            assertEquals(90, dst.get(6) & 0xFF);
            assertFalse(DataTool.scalable(PixelFormat.YUYV));
            assertFalse(DataTool.scalable(PixelFormat.BC1));
            assertTrue(DataTool.scalable(PixelFormat.NV12));
            assertThrows(IllegalArgumentException.class, () -> DataTool.scaleArea(src, 5, 3, dst, 3, 1, PixelFormat.YUYV));
        }

        @Test
        @DisplayName("blendOver passes opaque and clear sources through and never overflows a channel")
        void blendOver() {
            assertEquals(0x80FF0000, DataTool.blendOver(0x80FF0000, 0x00123456));
            assertEquals(0xFF0000FF, DataTool.blendOver(0x00FF0000, 0xFF0000FF));
            assertEquals(0xFF123456, DataTool.blendOver(0xFF123456, 0x80FF0000));
            // HALF-COVERING RED OVER OPAQUE BLUE IS AN EXACT LERP WITH AN OPAQUE RESULT
            assertEquals(0xFF80007F, DataTool.blendOver(0x80FF0000, 0xFF0000FF));
            // ALPHA 1 RED OVER ALPHA 2 RED MUST STAY RED WITH ALPHA 2: NO CHANNEL MAY OVERFLOW INTO ITS NEIGHBOUR
            assertEquals(0x02FF0000, DataTool.blendOver(0x01FF0000, 0x02FF0000));
            // EVERY ALPHA PAIR: RED COMES ONLY FROM THE SOURCE AND BLUE ONLY FROM THE DESTINATION, SO THEIR SUM IS
            // THE TOTAL COVERAGE AND CAN NEVER EXCEED 255; GREEN HAS NO CONTRIBUTOR AND STAYS ZERO
            for (int sa = 1; sa < 255; sa++) {
                for (int da = 0; da < 256; da++) {
                    final int out = DataTool.blendOver((sa << 24) | 0xFF0000, (da << 24) | 0x0000FF);
                    assertEquals(sa + da * (255 - sa) / 255, out >>> 24, "alpha sa=" + sa + " da=" + da);
                    assertEquals(0, (out >> 8) & 0xFF, "green sa=" + sa + " da=" + da);
                    assertTrue(((out >> 16) & 0xFF) + (out & 0xFF) <= 255, "coverage sa=" + sa + " da=" + da);
                }
            }
        }
    }
}
