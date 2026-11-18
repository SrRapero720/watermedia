package org.watermedia.api.player;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NetworkCache} against a loopback origin: index hits for bytes and files, the on-disk key and its split by
 * request headers, the refusal of unbounded (chunked or ICY) and truncated transfers, and an abandoned uncached read.
 */
@DisplayName("NetworkCache")
public class NetworkCacheTest {

    private static final String ACCEPT_IMAGE = "image/*,*/*";
    private static final String ACCEPT_VIDEO = "video/*,*/*";
    private static final int READ_LIMIT_BYTES = 1_024;
    private static final long TIMEOUT_MS = 5_000L;
    private static final long LEAVE_MS = 1_000L; // GIVING UP IS IMMEDIATE, WHILE THE TRICKLE WOULD NEED HOURS TO REACH ITS LIMIT

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // THE STORE IS PROCESS-WIDE: LATER CLASSES OF THIS JVM MUST NOT WRITE INTO A TEMP DIRECTORY JUNIT ALREADY DELETED.
    // BEFORE WaterMedia LOADS THERE IS NOTHING TO RESTORE; ITS PLAYER MODULE OPENS THE PRODUCTION STORE ITSELF
    @AfterAll
    static void restoreStore() throws IOException {
        if (WaterMedia.loaded()) NetworkCache.start(WaterMedia.tmp().resolve("cache"));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Cached reads")
    class Reads {

        @Test
        @DisplayName("Caches HTTP bytes and serves the second read from the index")
        void secondReadHitsTheIndex(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 1, 2, 3, 4, 5 };
            final AtomicInteger hits = new AtomicInteger();
            try (final LocalHttp server = LocalHttp.start("/image.png", exchange -> {
                hits.incrementAndGet();
                exchange.getResponseHeaders().set("Expires", LocalHttp.expiresIn(3_600));
                LocalHttp.respond(exchange, "image/png", body, 0);
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/image.png");

                final NetworkCache.CachedBytes first = NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);
                final NetworkCache.CachedBytes second = NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);

                assertFalse(first.cached(), "the first read comes from the origin");
                assertTrue(second.cached(), "the second read comes from the index");
                assertArrayEquals(body, first.bytes());
                assertArrayEquals(body, second.bytes());
                assertEquals("image/png", second.contentType());
                assertEquals(1, hits.get(), "only the first read reaches the origin");
                assertTrue(Files.isRegularFile(cache.resolve("index.dat")), "the index is written to disk");
            }
        }

        @Test
        @DisplayName("Caches an HTTP response as a file that the second read reuses")
        void cachesResponseAsReusableFile(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 10, 20, 30, 40 };
            final AtomicInteger hits = new AtomicInteger();
            try (final LocalHttp server = LocalHttp.start("/clip.mp4", exchange -> {
                hits.incrementAndGet();
                LocalHttp.respond(exchange, "video/mp4", body, 3_600);
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/clip.mp4");

                final NetworkCache.CachedFile first = NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT_BYTES, true);
                final NetworkCache.CachedFile second = NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT_BYTES, true);

                assertNotNull(first, "the first read stores a file");
                assertNotNull(second, "the second read finds the file");
                assertFalse(first.cached(), "the first read comes from the origin");
                assertTrue(second.cached(), "the second read comes from the index");
                assertEquals(first.path(), second.path());
                assertEquals("video/mp4", second.contentType());
                assertArrayEquals(body, Files.readAllBytes(second.path()));
                assertEquals(1, hits.get(), "only the first read reaches the origin");
            }
        }
    }

    @Nested
    @DisplayName("Cache keys")
    class Keys {

        @Test
        @DisplayName("Derives the on-disk key from the URI, the header lines (names lowercased, values verbatim) and the Accept tail")
        void derivesStableOnDiskKeys(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 7, 7, 7 };
            try (final LocalHttp server = LocalHttp.start("/pinned.png", exchange ->
                    LocalHttp.respond(exchange, "image/png", body, 3_600))) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/pinned.png");
                final HttpHeaders headers = new HttpHeaders().set("X-Variant", "First-Cut");
                NetworkCache.read(uri, headers, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);

                // RE-DERIVED FROM THE CONTRACT: sha256(uri + '\n' + "name:value\n" PER HEADER + "accept:" TAIL), HEX-ENCODED INTO
                // THE PAYLOAD NAME; THE MIXED-CASE VALUE PINS THAT ONLY NAMES ARE LOWERCASED. A DRIFT ORPHANS EVERY CACHE ON DISK
                final String key = uri.toASCIIString() + '\n' + "x-variant:First-Cut\n" + "accept:" + ACCEPT_IMAGE;
                final byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
                final Path payload = cache.resolve("wm_" + HexFormat.of().formatHex(hash) + ".tmp");
                assertTrue(Files.isRegularFile(payload), "the payload must be stored as " + payload.getFileName());
            }
        }

        @Test
        @DisplayName("Separates cache entries by request headers")
        void separatesEntriesByRequestHeaders(@TempDir final Path cache) throws Exception {
            final byte[] firstBody = new byte[] { 1, 1, 1 };
            final byte[] secondBody = new byte[] { 2, 2, 2 };
            final AtomicInteger hits = new AtomicInteger();
            try (final LocalHttp server = LocalHttp.start("/variant.png", exchange -> {
                hits.incrementAndGet();
                final boolean second = "second".equals(exchange.getRequestHeaders().getFirst("X-Variant"));
                LocalHttp.respond(exchange, "image/png", second ? secondBody : firstBody, 3_600);
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/variant.png");
                final HttpHeaders firstHeaders = new HttpHeaders().set("X-Variant", "first");
                final HttpHeaders secondHeaders = new HttpHeaders().set("X-Variant", "second");

                final NetworkCache.CachedBytes first = NetworkCache.read(uri, firstHeaders, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);
                final NetworkCache.CachedBytes second = NetworkCache.read(uri, secondHeaders, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);
                final NetworkCache.CachedBytes firstAgain = NetworkCache.read(uri, firstHeaders, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);

                assertArrayEquals(firstBody, first.bytes());
                assertArrayEquals(secondBody, second.bytes());
                assertArrayEquals(firstBody, firstAgain.bytes());
                assertFalse(first.cached());
                assertFalse(second.cached(), "other headers are another entry");
                assertTrue(firstAgain.cached(), "the same headers hit their own entry");
                assertEquals(2, hits.get(), "each header set reaches the origin once");
            }
        }
    }

    @Nested
    @DisplayName("Refused transfers")
    class Refusals {

        @Test
        @DisplayName("A chunked media response without a Content-Length is refused as a file and still read as bytes")
        void chunkedResponseIsNotCachedAsFile(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 1, 2, 3 };
            try (final LocalHttp server = LocalHttp.start("/radio", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                exchange.sendResponseHeaders(200, 0); // CHUNKED: THE LENGTH IS UNKNOWN, LIKE A LIVE RADIO
                exchange.getResponseBody().write(body);
                exchange.close();
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/radio");
                final IOException refusal = assertThrows(IOException.class, () -> NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT_BYTES, true),
                        "the file cache refuses to download an endless source");
                assertEquals("(wm_err:1174) Failed to cache the media due to unbounded source: " + uri, refusal.getMessage());
                // THE BYTE READER (IMAGES) STILL ACCEPTS CHUNKED RESPONSES
                assertArrayEquals(body, NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true).bytes());
            }
        }

        @ParameterizedTest(name = "{displayName}: {0}")
        @ValueSource(strings = { "icy-metaint", "icy-name" })
        @DisplayName("An ICY media response is refused as a file even with a Content-Length")
        void icyResponseIsNotCachedAsFile(final String header, @TempDir final Path cache) throws Exception {
            try (final LocalHttp server = LocalHttp.start("/icy", exchange -> {
                // AN INTERNET RADIO THAT ANNOUNCES A LENGTH IS STILL ENDLESS: EITHER ICY HEADER GIVES IT AWAY
                exchange.getResponseHeaders().set(header, "16000");
                LocalHttp.respond(exchange, "audio/mpeg", new byte[] { 1, 2, 3 }, 0);
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/icy");
                final IOException refusal = assertThrows(IOException.class, () -> NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT_BYTES, true),
                        "the file cache refuses an ICY stream");
                assertEquals("(wm_err:1174) Failed to cache the media due to unbounded source: " + uri, refusal.getMessage());
            }
        }

        @Test
        @DisplayName("A body shorter than its Content-Length fails with the truncation error instead of being cached")
        void truncatedBodyIsRefused(@TempDir final Path cache) throws Exception {
            try (final LocalHttp server = LocalHttp.start("/cut.png", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Expires", LocalHttp.expiresIn(3_600));
                exchange.sendResponseHeaders(200, 100L);
                // TEN OF THE HUNDRED ANNOUNCED BYTES: THE FIXED-LENGTH STREAM REFUSES THE SHORT BODY AND DROPS THE CONNECTION
                exchange.getResponseBody().write(new byte[10]);
                exchange.getResponseBody().flush();
                exchange.close();
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/cut.png");
                final IOException failure = assertThrows(IOException.class, () -> NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true),
                        "a short body is a failure");
                // THE DROPPED CONNECTION READS AS A CLEAN END OF THE BODY, SO ONLY THE CACHE'S OWN LENGTH CHECK CATCHES IT
                assertEquals("(wm_err:1175) Failed to download the media due to truncated body: 10 of 100 bytes, " + uri, failure.getMessage());
                try (final var stream = Files.list(cache)) {
                    assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")), "nothing was cached");
                }
            }
        }

        @Test
        @DisplayName("An abandoned uncached read stops and leaves the origin instead of reading on to its limit")
        void abandonedReadStops() throws Exception {
            final CountDownLatch streaming = new CountDownLatch(1);
            final CountDownLatch disconnected = new CountDownLatch(1);
            // THE ORIGIN TRICKLES ITS BODY: A TRANSFER PARKED ON A SILENT SOCKET IGNORES INTERRUPTS UNTIL ITS READ TIMEOUT, SO ONLY
            // A BODY IN FLIGHT SHOWS THE ABORT REACHING THE TRANSFER
            try (final LocalHttp server = LocalHttp.start("/slow.png", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, 1_000_000L);
                streaming.countDown();
                try (final OutputStream out = exchange.getResponseBody()) {
                    while (true) {
                        out.write(0);
                        out.flush();
                        Thread.sleep(20L);
                    }
                } catch (final IOException | InterruptedException gone) {
                    disconnected.countDown();
                }
            })) {
                final URI uri = server.uri("/slow.png");
                final IOException[] outcome = new IOException[1]; // WRITTEN BY THE CALLER, READ AFTER join() PUBLISHED IT
                final Thread caller = new Thread(() -> {
                    try { NetworkCache.read(uri, null, ACCEPT_IMAGE, 2_000_000L, false); }
                    catch (final IOException failure) { outcome[0] = failure; }
                }, "NetworkCacheTest-Caller");
                caller.start();
                assertTrue(streaming.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the origin started streaming");
                final long start = System.nanoTime();
                caller.interrupt();
                caller.join(TIMEOUT_MS);
                final long left = System.nanoTime();
                assertFalse(caller.isAlive(), "the interrupted caller leaves");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(left - start) < LEAVE_MS, "giving up never waits on the transfer");
                assertInstanceOf(InterruptedIOException.class, outcome[0], "the interrupt ends the wait");
                assertEquals("(wm_err:1170) Failed to wait for the media cache download due to interruption", outcome[0].getMessage());
                assertTrue(disconnected.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "the transfer stopped instead of trickling on to its limit");
            }
        }
    }
}
