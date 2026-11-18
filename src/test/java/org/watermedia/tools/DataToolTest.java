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

/**
 * Verifies the pure helpers of {@link DataTool}: log redaction (links, URI passwords, tokens, headers, config copies,
 * hostile runs), lenient parsing (web URIs, error codes, units, numbers, signed URL expiry, string matching and
 * similarity) and pixel math (premultiply, area scaling, source-over blending).
 */
@DisplayName("DataTool")
public class DataToolTest {

    // A BACKTRACKING PATTERN WOULD HANG ON A HOSTILE RUN, SO EACH REDACTION OF ONE GETS THIS BUDGET
    private static final long REDACT_BUDGET_MS = 2_000L;

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
            assertEquals(safe, DataTool.redact(safe), "a second pass changes nothing");
            assertEquals("https://cdn.test/media?access_token=********&id=9", DataTool.redact("https://cdn.test/media?access_token=private-token&id=9"));
        }

        @Test
        @DisplayName("redact stays linear on hostile runs and masks cookies and every Authorization scheme")
        void redactBounded() {
            // THE BUDGET IS ENFORCED WHILE THE RUN IS STILL GOING, SO A HANG FAILS INSTEAD OF STALLING THE SUITE
            for (final String run: new String[] { "a.".repeat(40_000), "a-".repeat(40_000), "eyJ".repeat(40_000), "a://b:".repeat(15_000) })
                assertTimeoutPreemptively(Duration.ofMillis(REDACT_BUDGET_MS), () -> DataTool.redact(run), "an 80k run redacts within the budget");
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

        @Test
        @DisplayName("redact masks the bare token key and the cookie of a config copy, and the upload header")
        void redactConfigs() {
            // THE SHAPES WATERCONFIG WRITES: INDENTED TOML PAIRS UNDER A TABLE AND DOTTED PROPERTIES KEYS
            assertEquals("[network]\n  # The token clients present to the file server\n  token = ********\n  timeout = 15000\n\n[platforms]\n  biliBiliCookie = ********\n",
                    DataTool.redact("[network]\n  # The token clients present to the file server\n  token = \"abc123\"\n  timeout = 15000\n\n[platforms]\n  biliBiliCookie = \"SESSDATA=xyz\"\n"));
            assertEquals("# WATERMeDIA standalone app preferences\nengine=VULKAN\nnetwork.token=********\nplatforms.biliBiliCookie=********\n",
                    DataTool.redact("# WATERMeDIA standalone app preferences\nengine=VULKAN\nnetwork.token=abc123\nplatforms.biliBiliCookie=SESSDATA\\=xyz\n"));
            assertEquals("token = ********", DataTool.redact("token = \"my server, 2026 \\\"quoted\\\"\""), "a quoted value is masked whole, escapes included");
            assertEquals("token = ********", DataTool.redact("token = 'my server, 2026'"));
            assertEquals("TOKEN=******** next", DataTool.redact("TOKEN=abc123 next"));
            assertEquals("token = ********", DataTool.redact("token = \"abc"), "an unterminated value is masked too");
            assertEquals("token:********", DataTool.redact("token:deadbeef:0123-4567"), "the session triple keeps its own shape");
            // THE UPLOAD HEADER OF THE FILE SERVER CARRIES THE SAME TOKEN; ITS VALUE MAY HOLD SPACES, SO THE LINE GOES WHOLE
            assertEquals("X-WaterMedia-Token: ********\r\nAccept: */*", DataTool.redact("X-WaterMedia-Token: my server, 2026\r\nAccept: */*"));
            assertEquals("sent x-watermedia-token=********", DataTool.redact("sent x-watermedia-token=abc123"));
            for (final String empty: new String[] { "token = \"\"", "network.token=\nengine=VULKAN\n" })
                assertEquals(empty, DataTool.redact(empty), "an empty value has nothing to mask");
        }

        @Test
        @DisplayName("redact keeps compound token keys and link parameters, and long token values never overflow the stack")
        void redactTokenBounds() {
            final String kept = "nextPageToken=CAUQAA page_token=xyz page-token=xyz token_type=bearer tokens=3 "
                    + "\"nextPageToken\": \"CAUQAA\" https://api.test/list?token=abc&token=def unexpected token: }";
            assertEquals(kept, DataTool.redact(kept));
            // A GREEDY GROUP RECURSES ONCE PER CHARACTER OF THE VALUE, SO THESE RUNS WOULD THROW StackOverflowError
            for (final String run: new String[] { "token = \"" + "a".repeat(80_000) + "\"", "token = \"" + "a".repeat(80_000), "token=\"" + "\\\\".repeat(40_000) })
                assertEquals("********", assertTimeoutPreemptively(Duration.ofMillis(REDACT_BUDGET_MS), () -> DataTool.redact(run)).replaceFirst("token ?= ?", ""),
                        "the whole value is masked");
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
        @DisplayName("errCode prefixes a message with its code padded to four digits")
        void errorCodePrefix() {
            assertEquals("(wm_err:0000) ", DataTool.errCode(0));
            assertEquals("(wm_err:0007) ", DataTool.errCode(7));
            assertEquals("(wm_err:0720) ", DataTool.errCode(720));
            assertEquals("(wm_err:4750) Failed to parse", DataTool.errCode(4_750) + "Failed to parse");
            assertEquals("(wm_err:12345) ", DataTool.errCode(12_345));
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
            final int end = 4 + 256 * 256 * 4;
            final ByteBuffer pixels = ByteBuffer.allocateDirect(end + 4);
            // A PIXEL OF BLUE 0x7F AND ZERO ALPHA ON EACH SIDE OF THE RANGE: A PASS THAT STRAYS ONTO ONE CLEARS ITS BLUE
            pixels.put(0, (byte) 0x7F).put(end, (byte) 0x7F).position(4);
            for (int a = 0; a < 256; a++)
                for (int c = 0; c < 256; c++)
                    pixels.put((byte) c).put((byte) (255 - c)).put((byte) c).put((byte) a);
            pixels.position(4).limit(end);
            DataTool.premultiply(pixels);
            assertEquals(4, pixels.position(), "the position stays");
            assertEquals(end, pixels.limit(), "the limit stays");
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
            assertEquals(0x7F, pixels.get(0) & 0xFF, "the pixel before the position stays");
            assertEquals(0x7F, pixels.get(end) & 0xFF, "the pixel at the limit stays");
        }

        @Test
        @DisplayName("scaleArea averages each target texel over its source block on every plane and refuses layouts it cannot average")
        void scaleAreaPlanes() {
            // 5x3 YUV420P: Y 5x3, U AND V 3x2 EACH (ROUNDED UP). Y(x, y) = 40x + 20y, SO THE 3x1 TARGET AVERAGES THE
            // COLUMNS {0}, {1, 2} AND {3, 4} OVER ALL THREE ROWS INTO 20, 80 AND 160; THE UNIFORM CHROMA KEEPS ITS VALUE
            final ByteBuffer src = ByteBuffer.allocateDirect((int) PixelFormat.YUV420P.frameBytes(5, 3, 8));
            for (int y = 0; y < 3; y++)
                for (int x = 0; x < 5; x++) src.put((byte) (40 * x + 20 * y));
            for (int i = 0; i < 6; i++) src.put((byte) 60);
            for (int i = 0; i < 6; i++) src.put((byte) 90);
            src.flip();
            final ByteBuffer dst = ByteBuffer.allocateDirect((int) PixelFormat.YUV420P.frameBytes(3, 1, 8));
            DataTool.scaleArea(src, 5, 3, dst, 3, 1, PixelFormat.YUV420P);
            assertEquals(dst.capacity(), dst.position(), "Y 3x1 plus U and V 2x1 fill the target exactly");
            assertEquals(20, dst.get(0) & 0xFF, "Y over column 0");
            assertEquals(80, dst.get(1) & 0xFF, "Y over columns 1 and 2");
            assertEquals(160, dst.get(2) & 0xFF, "Y over columns 3 and 4");
            assertEquals(60, dst.get(3) & 0xFF, "U");
            assertEquals(60, dst.get(4) & 0xFF, "U");
            assertEquals(90, dst.get(5) & 0xFF, "V");
            assertEquals(90, dst.get(6) & 0xFF, "V");
            assertFalse(DataTool.scalable(PixelFormat.YUYV), "YUYV pairs share chroma");
            assertFalse(DataTool.scalable(PixelFormat.BC1), "BC1 is compressed");
            assertTrue(DataTool.scalable(PixelFormat.NV12), "NV12 averages per plane");
            assertThrows(IllegalArgumentException.class, () -> DataTool.scaleArea(src, 5, 3, dst, 3, 1, PixelFormat.YUYV));
        }

        @Test
        @DisplayName("blendOver passes opaque and clear sources through, composes Porter-Duff alpha and never overflows a channel")
        void blendOver() {
            assertEquals(0x80FF0000, DataTool.blendOver(0x80FF0000, 0x00123456));
            assertEquals(0xFF0000FF, DataTool.blendOver(0x00FF0000, 0xFF0000FF));
            assertEquals(0xFF123456, DataTool.blendOver(0xFF123456, 0x80FF0000));
            // HALF-COVERING RED OVER OPAQUE BLUE IS AN EXACT LERP WITH AN OPAQUE RESULT
            assertEquals(0xFF80007F, DataTool.blendOver(0x80FF0000, 0xFF0000FF));
            // ALPHA 1 RED OVER ALPHA 2 RED MUST STAY RED WITH ALPHA 2: NO CHANNEL MAY OVERFLOW INTO ITS NEIGHBOUR
            assertEquals(0x02FF0000, DataTool.blendOver(0x01FF0000, 0x02FF0000));
            // EVERY ALPHA PAIR: THE ALPHA STAYS WITHIN ONE OF THE REAL a_s + a_d * (1 - a_s); RED COMES ONLY FROM THE
            // SOURCE AND BLUE ONLY FROM THE DESTINATION, SO THEIR SUM IS THE TOTAL COVERAGE AND NEVER EXCEEDS 255;
            // GREEN HAS NO CONTRIBUTOR AND STAYS ZERO
            for (int sa = 1; sa < 255; sa++) {
                for (int da = 0; da < 256; da++) {
                    final int out = DataTool.blendOver((sa << 24) | 0xFF0000, (da << 24) | 0x0000FF);
                    assertEquals(255.0 * (sa / 255.0 + da / 255.0 * (1.0 - sa / 255.0)), out >>> 24, 1.0, "alpha sa=" + sa + " da=" + da);
                    assertEquals(0, (out >> 8) & 0xFF, "green sa=" + sa + " da=" + da);
                    assertTrue(((out >> 16) & 0xFF) + (out & 0xFF) <= 255, "coverage sa=" + sa + " da=" + da);
                }
            }
        }
    }
}
