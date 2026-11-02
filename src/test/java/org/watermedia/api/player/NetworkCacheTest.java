package org.watermedia.api.player;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.test.support.LocalHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies {@link NetworkCache} against a loopback origin: index hits, cache keys, refused transfers and abandoned reads. */
@DisplayName("NetworkCache round-trip")
public class NetworkCacheTest {

    private static final String ACCEPT_IMAGE = "image/*,*/*";
    private static final String ACCEPT_VIDEO = "video/*,*/*";
    private static final int READ_LIMIT_BYTES = 1_024;

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
        @DisplayName("Caches HTTP bytes and uses the index on the second read")
        void testCachesHttpBytesAndUsesIndexOnSecondRead(@TempDir final Path cache) throws Exception {
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

                assertFalse(first.cached());
                assertTrue(second.cached());
                assertArrayEquals(body, first.bytes());
                assertArrayEquals(body, second.bytes());
                assertEquals("image/png", second.contentType());
                assertEquals(1, hits.get());
                assertTrue(Files.isRegularFile(cache.resolve("index.dat")));
            }
        }

        @Test
        @DisplayName("Caches an HTTP response as a reusable file")
        void testCachesHttpResponseAsReusableFile(@TempDir final Path cache) throws Exception {
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

                assertNotNull(first);
                assertNotNull(second);
                assertFalse(first.cached());
                assertTrue(second.cached());
                assertEquals(first.path(), second.path());
                assertEquals("video/mp4", second.contentType());
                assertArrayEquals(body, Files.readAllBytes(second.path()));
                assertEquals(1, hits.get());
            }
        }
    }

    @Nested
    @DisplayName("Cache keys")
    class Keys {

        @Test
        @DisplayName("Derives stable on-disk cache keys")
        void testDerivesStableOnDiskCacheKeys(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 7, 7, 7 };
            try (final LocalHttp server = LocalHttp.start("/pinned.png", exchange ->
                    LocalHttp.respond(exchange, "image/png", body, 3_600))) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/pinned.png");
                final HttpHeaders headers = new HttpHeaders().set("X-Variant", "first");
                NetworkCache.read(uri, headers, ACCEPT_IMAGE, READ_LIMIT_BYTES, true);

                // RE-DERIVE THE KEY FROM ITS CONTRACT: sha256(uri + '\n' + LOWERCASED "name:value" LINES + "accept:" TAIL)
                // HEX-ENCODED INTO THE PAYLOAD FILENAME; A DRIFT ORPHANS EVERY EXISTING ON-DISK CACHE
                final String key = uri.toASCIIString() + '\n' + "x-variant:first\n" + "accept:" + ACCEPT_IMAGE;
                final byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
                final StringBuilder hex = new StringBuilder(hash.length * 2);
                for (final byte b: hash) {
                    hex.append(Character.forDigit((b >>> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                }
                assertTrue(Files.isRegularFile(cache.resolve("wm_" + hex + ".tmp")));
            }
        }

        @Test
        @DisplayName("Separates cache entries by request headers")
        void testSeparatesCacheEntriesByRequestHeaders(@TempDir final Path cache) throws Exception {
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
                assertFalse(second.cached());
                assertTrue(firstAgain.cached());
                assertEquals(2, hits.get());
            }
        }
    }

    @Nested
    @DisplayName("Refused transfers")
    class Refusals {

        @Test
        @DisplayName("An unbounded media response (chunked, no Content-Length) is never cached as a file")
        void testUnboundedResponseIsNotCachedAsFile(@TempDir final Path cache) throws Exception {
            final byte[] body = new byte[] { 1, 2, 3 };
            try (final LocalHttp server = LocalHttp.start("/radio", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
                exchange.getResponseHeaders().set("icy-name", "endless");
                exchange.sendResponseHeaders(200, 0); // CHUNKED: THE LENGTH IS UNKNOWN, LIKE A LIVE RADIO
                exchange.getResponseBody().write(body);
                exchange.close();
            })) {
                NetworkCache.start(cache);
                final URI uri = server.uri("/radio");
                assertThrows(IOException.class, () -> NetworkCache.readFile(uri, null, ACCEPT_VIDEO, READ_LIMIT_BYTES, true),
                        "the file cache refuses to download an endless source");
                // THE BYTE READER (IMAGES) STILL ACCEPTS CHUNKED RESPONSES
                assertArrayEquals(body, NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true).bytes());
            }
        }

        @Test
        @DisplayName("A body shorter than its Content-Length fails instead of being cached")
        void testTruncatedBodyIsRefused(@TempDir final Path cache) throws Exception {
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
                assertThrows(IOException.class, () -> NetworkCache.read(uri, null, ACCEPT_IMAGE, READ_LIMIT_BYTES, true), "a short body is a failure");
                try (final var stream = Files.list(cache)) {
                    assertFalse(stream.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")), "nothing was cached");
                }
            }
        }

        @Test
        @DisplayName("An abandoned uncached read stops and leaves the origin instead of reading on to its limit")
        void testAbandonedReadStops() throws Exception {
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
                final Thread caller = new Thread(() -> {
                    try { NetworkCache.read(uri, null, ACCEPT_IMAGE, 2_000_000L, false); }
                    catch (final IOException expected) { /* THE INTERRUPT ENDS THE WAIT */ }
                }, "NetworkCacheTest-Caller");
                caller.start();
                assertTrue(streaming.await(5, TimeUnit.SECONDS), "the origin started streaming");
                final long start = System.nanoTime();
                caller.interrupt();
                caller.join(5_000L);
                assertFalse(caller.isAlive(), "the interrupted caller leaves");
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000L, "giving up never waits on the transfer");
                assertTrue(disconnected.await(5, TimeUnit.SECONDS), "the transfer stopped instead of trickling on to its limit");
            }
        }
    }
}
