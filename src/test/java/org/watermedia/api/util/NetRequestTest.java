package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.tools.DataTool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link NetRequest} against a loopback HTTP server: the redirect policy (origins, schemes
 * and 303), the default and caller headers, and body reading (error bodies, JSON content types, the
 * single-read rule and the total text deadline).
 */
@DisplayName("NetRequest")
public class NetRequestTest {
    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // SERVES /start AS A 302 REDIRECT TO location
    private static LocalHttp redirect(final String location) {
        return LocalHttp.start("/start", ex -> {
            ex.getResponseHeaders().set("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
    }

    // SERVES /echo WITH THE VALUE OF THE REQUEST HEADER name AS ITS BODY, SO ASSERTIONS COVER WHAT WENT ON THE WIRE
    private static LocalHttp echo(final String name) {
        return LocalHttp.start("/echo", ex -> LocalHttp.respond(ex, "text/plain",
                String.valueOf(ex.getRequestHeaders().getFirst(name)).getBytes(StandardCharsets.UTF_8), 0));
    }

    private static String text(final NetRequest request) throws IOException {
        try (final NetRequest req = request.send()) {
            return req.text();
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Query")
    class QueryTests {
        @Test
        @DisplayName("query decodes pairs once, tolerates value-less keys and keeps malformed pairs raw")
        void queryDecodesOnce() {
            final Map<String, String> q = NetRequest.query(URI.create("https://cdn.test/x?a=1&b=%20&c&&d=x=y"));
            assertEquals("1", q.get("a"));
            assertEquals(" ", q.get("b"));
            assertEquals("", q.get("c"));
            assertEquals("x=y", q.get("d"));
            assertEquals(4, q.size());
            final Map<String, String> raw = NetRequest.query(URI.create("https://cdn.test/x?sig=a%2Bb%253D&bad=100%25&tail=%E2%82%AC"));
            assertEquals("a+b%3D", raw.get("sig"), "the raw query is decoded exactly once");
            assertEquals("100%", raw.get("bad"));
            assertEquals("€", raw.get("tail"));
            assertThrows(UnsupportedOperationException.class, () -> raw.put("x", "y"));
            assertEquals("a b", NetRequest.query(URI.create("https://cdn.test/x?q=a+b")).get("q"), "form encoding: plus is a space");
            assertEquals("last", NetRequest.query(URI.create("https://cdn.test/x?q=first&q=last")).get("q"), "the last value wins");
            assertTrue(NetRequest.query(URI.create("https://cdn.test/x")).isEmpty());
            assertTrue(NetRequest.query(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("Budgets")
    class BudgetTests {
        @Test
        @DisplayName("httpsOnly refuses a plain http origin before sending anything")
        void httpsOnlyRefusesHttp(@TempDir final Path directory) {
            final int[] hits = { 0 };
            try (final LocalHttp server = LocalHttp.start("/binary", exchange -> {
                hits[0]++;
                LocalHttp.respond(exchange, "application/octet-stream", new byte[] { 1 }, 0);
            })) {
                final URI uri = server.uri("/binary");
                assertThrows(IOException.class, () -> new NetRequest(uri).httpsOnly().send());
                final Path output = directory.resolve("binary");
                assertThrows(IOException.class, () -> {
                    try (final NetRequest request = new NetRequest(uri).httpsOnly().maxBytes(128).send()) { request.download(output, "0".repeat(64)); }
                });
                assertFalse(Files.exists(output));
                assertEquals(0, hits[0]);
            }
        }

        @Test
        @DisplayName("maxBytes caps text and downloads, and a digest mismatch deletes the file")
        void maxBytesAndDigest(@TempDir final Path directory) throws IOException {
            final byte[] body = "0123456789".repeat(20).getBytes(StandardCharsets.UTF_8);
            try (final LocalHttp server = LocalHttp.start("/data", exchange -> LocalHttp.respond(exchange, "text/plain", body, 0))) {
                final URI uri = server.uri("/data");
                try (final NetRequest request = new NetRequest(uri).maxBytes(64).send()) { assertThrows(IOException.class, request::text); }
                try (final NetRequest request = new NetRequest(uri).maxBytes(1_024).send()) { assertEquals(new String(body, StandardCharsets.UTF_8), request.text()); }
                final Path output = directory.resolve("data");
                try (final NetRequest request = new NetRequest(uri).maxBytes(64).send()) {
                    assertThrows(IOException.class, () -> request.download(output, "0".repeat(64)));
                }
                assertFalse(Files.exists(output));
                try (final NetRequest request = new NetRequest(uri).maxBytes(1_024).send()) {
                    assertThrows(IOException.class, () -> request.download(output, "0".repeat(64)), "a wrong digest fails");
                }
                assertFalse(Files.exists(output), "a failed download leaves no file behind");
                final String digest = HexFormat.of().formatHex(DataTool.sha256(body));
                try (final NetRequest request = new NetRequest(uri).maxBytes(1_024).deadline(10_000L).send()) { request.download(output, digest); }
                assertArrayEquals(body, Files.readAllBytes(output));
            }
        }
    }

    @Nested
    @DisplayName("Redirects")
    class RedirectTests {
        // ======================================================================
        // INTERNAL API
        // ======================================================================
        @Test
        @DisplayName("A cross-port redirect strips credentials on the wire")
        void crossPortRedirectRemovesCredentialsOnWire() throws IOException {
            try (final LocalHttp target = LocalHttp.start("/final", ex -> {
                final StringBuilder received = new StringBuilder();
                for (final String name: List.of("Authorization", "X-WaterMedia-Token", "Cookie", "Cookie2")) {
                    received.append(ex.getRequestHeaders().getFirst(name)).append('\n');
                }
                LocalHttp.respond(ex, "text/plain", received.toString().getBytes(StandardCharsets.UTF_8), 0);
            }); final LocalHttp redirector = redirect(target.uri("/final").toString()); final NetRequest req = new NetRequest(redirector.uri("/start"))
                    .header("Authorization", "Bearer test")
                    .header("X-WaterMedia-Token", "test")
                    .header("Cookie", "session=test")
                    .header("Cookie2", "session=test")
                    .send()) {
                assertEquals(200, req.statusCode());
                assertEquals("null\nnull\nnull\nnull\n", req.text());
            }
        }

        @Test
        @DisplayName("A same-origin redirect keeps credentials")
        void sameOriginRedirectPreservesCredentials() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/", ex -> {
                if ("/start".equals(ex.getRequestURI().getPath())) {
                    ex.getResponseHeaders().set("Location", "/final");
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                } else {
                    final String received = ex.getRequestHeaders().getFirst("Authorization") + "|"
                            + ex.getRequestHeaders().getFirst("Cookie");
                    LocalHttp.respond(ex, "text/plain", received.getBytes(StandardCharsets.UTF_8), 0);
                }
            }); final NetRequest req = new NetRequest(server.uri("/start"))
                    .header("Authorization", "Bearer test").header("Cookie", "session=test").send()) {
                assertEquals("Bearer test|session=test", req.text());
            }
        }

        @Test
        @DisplayName("The origin policy handles downgrades, default ports and host case")
        void originPolicyHandlesDowngradesDefaultPortsAndHostCase() throws Exception {
            final NetRequest request = new NetRequest("https://EXAMPLE.com/start")
                    .header("Authorization", "Bearer test").header("Proxy-Authorization", "proxy test")
                    .header("Cookie", "session=test").header("X-WaterMedia-Token", "test");
            for (final String target: List.of("https://example.com:443/end", "https://EXAMPLE.com/end")) {
                final HttpHeaders headers = request.headersFor(URI.create(target));
                assertEquals("Bearer test", headers.value("Authorization"));
                assertEquals("session=test", headers.value("Cookie"));
            }
            for (final String target: List.of("http://example.com/end", "http://example.com:443/end",
                    "https://example.com:8443/end", "https://other.example/end")) {
                final HttpHeaders headers = request.headersFor(URI.create(target));
                for (final String name: List.of("Authorization", "Proxy-Authorization", "Cookie", "X-WaterMedia-Token")) {
                    assertNull(headers.value(name), name + " leaked to " + target);
                }
            }
        }

        @Test
        @DisplayName("Bodies follow redirects to another origin only when crossOrigin is set")
        void crossOriginBodies() throws IOException {
            try (final LocalHttp target = LocalHttp.start("/final", ex -> {
                final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LocalHttp.respond(ex, "text/plain", (ex.getRequestMethod() + " " + body).getBytes(StandardCharsets.UTF_8), 0);
            }); final LocalHttp redirector = LocalHttp.start("/start", ex -> {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().set("Location", target.uri("/final").toString());
                ex.sendResponseHeaders(307, -1);
                ex.close();
            })) {
                final IOException failure = assertThrows(IOException.class,
                        () -> new NetRequest(redirector.uri("/start")).method("POST").body("payload").send());
                assertTrue(failure.getMessage().contains("another origin"), failure.getMessage());
                try (final NetRequest req = new NetRequest(redirector.uri("/start")).method("POST").body("payload", true).send()) {
                    assertEquals("POST payload", req.text());
                }
            }
        }

        @Test
        @DisplayName("A 303 is re-requested as GET without the body, and a request is sent once")
        void seeOtherRequestsGet() throws IOException {
            final List<String> starts = new CopyOnWriteArrayList<>();
            try (final LocalHttp target = LocalHttp.start("/final", ex -> {
                final String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                LocalHttp.respond(ex, "text/plain", (ex.getRequestMethod() + " " + body).getBytes(StandardCharsets.UTF_8), 0);
            }); final LocalHttp redirector = LocalHttp.start("/start", ex -> {
                starts.add(ex.getRequestMethod() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                ex.getResponseHeaders().set("Location", target.uri("/final").toString());
                ex.sendResponseHeaders(303, -1);
                ex.close();
            })) {
                final NetRequest request = new NetRequest(redirector.uri("/start")).method("POST").body("payload");
                assertEquals(redirector.uri("/start"), request.uri(), "before send, uri() is the request's own");
                assertThrows(IllegalStateException.class, request::statusCode, "the response is unavailable before send");
                try (final NetRequest req = request.send()) {
                    assertSame(request, req);
                    assertEquals("GET ", req.text());
                    assertEquals("/final", req.uri().getPath(), "after send, uri() is the final hop");
                }
                assertEquals(List.of("POST payload"), starts);
                assertThrows(IllegalStateException.class, request::send, "a request is sent once");
            }
        }

        @Test
        @DisplayName("A redirect to file: or jar: fails instead of returning local bytes")
        void localSchemesRejected(@TempDir final Path directory) throws IOException {
            final Path secret = Files.writeString(directory.resolve("secret.txt"), "local secret");
            final Path archive = directory.resolve("secret.zip");
            try (final ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("x"));
                zip.write("zipped secret".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            for (final String location: List.of(secret.toUri().toString(), "jar:" + archive.toUri() + "!/x")) {
                try (final LocalHttp server = redirect(location)) {
                    final IOException failure = assertThrows(IOException.class, () -> new NetRequest(server.uri("/start")).send());
                    assertTrue(failure.getMessage().startsWith("Redirect to unsupported scheme"), failure.getMessage());
                }
            }
            // A FOLLOWED jar: HOP CACHES AN OPEN JarFile, WHICH WOULD KEEP THE ARCHIVE LOCKED ON WINDOWS
            Files.delete(archive);
        }

        @Test
        @DisplayName("http and https hops stay allowed whatever the scheme case")
        void webSchemesFollowed() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/", ex -> {
                final InetSocketAddress local = ex.getLocalAddress();
                final String authority = local.getHostString() + ":" + local.getPort();
                switch (ex.getRequestURI().getPath()) {
                    case "/upper" -> {
                        ex.getResponseHeaders().set("Location", "HTTP://" + authority + "/final");
                        ex.sendResponseHeaders(302, -1);
                        ex.close();
                    }
                    case "/secure" -> {
                        ex.getResponseHeaders().set("Location", "https://" + authority + "/final");
                        ex.sendResponseHeaders(302, -1);
                        ex.close();
                    }
                    default -> LocalHttp.respond(ex, "text/plain", "done".getBytes(StandardCharsets.UTF_8), 0);
                }
            })) {
                try (final NetRequest req = new NetRequest(server.uri("/upper")).send()) {
                    assertEquals("done", req.text());
                    assertEquals("/final", req.uri().getPath());
                }
                // THE PLAIN SERVER CANNOT SPEAK TLS: THE HTTPS HOP IS ATTEMPTED AND FAILS ON THE WIRE, NOT ON THE SCHEME RULE
                final IOException failure = assertThrows(IOException.class,
                        () -> new NetRequest(server.uri("/secure")).connectTimeout(1_000).readTimeout(1_000).send());
                assertFalse(String.valueOf(failure.getMessage()).startsWith("Redirect to unsupported scheme"), failure.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Headers")
    class HeaderTests {

        @Test
        @DisplayName("accept() sets the outbound Accept header")
        void acceptHeader() throws IOException {
            try (final LocalHttp server = echo("Accept");
                 final NetRequest req = new NetRequest(server.uri("/echo")).accept("image/png").send()) {
                assertEquals("image/png", req.text());
            }
        }

        @Test
        @DisplayName("The User-Agent defaults to WaterMedia and its last writer wins")
        void userAgentLastWriterWins() throws IOException {
            try (final LocalHttp server = echo("User-Agent")) {
                final URI uri = server.uri("/echo");
                assertEquals(NetRequest.UserAgent.WATERMEDIA.value(), text(new NetRequest(uri)));
                assertEquals("custom", text(new NetRequest(uri).header("User-Agent", "custom")));
                assertEquals("bag", text(new NetRequest(uri).headers(new HttpHeaders().set("User-Agent", "bag"))));
                assertEquals(NetRequest.UserAgent.WATERMEDIA.value(),
                        text(new NetRequest(uri).userAgent(NetRequest.UserAgent.GENERIC).headers(HttpHeaders.defaults(uri))));
                assertEquals("x", text(new NetRequest(uri).userAgent(NetRequest.UserAgent.GENERIC).header("User-Agent", "x")));
                assertEquals(NetRequest.UserAgent.GENERIC.value(),
                        text(new NetRequest(uri).header("User-Agent", "x").userAgent(NetRequest.UserAgent.GENERIC)));
            }
        }

        @Test
        @DisplayName("The derived Referer keeps an explicit port")
        void refererKeepsPort() throws IOException {
            try (final LocalHttp server = echo("Referer")) {
                assertEquals(server.uri("/").toString(), text(new NetRequest(server.uri("/echo"))));
                assertEquals("https://set.example/", text(new NetRequest(server.uri("/echo")).referer("https://set.example/")));
            }
        }
    }

    @Nested
    @DisplayName("Bodies")
    class BodyTests {

        @Test
        @DisplayName("json() binds application/json, text/json and +json subtypes, and skips other types unread")
        void jsonContentTypes() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/", ex -> {
                final String type = switch (ex.getRequestURI().getPath()) {
                    case "/json" -> "application/json";
                    case "/api" -> "application/vnd.api+json";
                    case "/text" -> "text/json; charset=utf-8";
                    default -> "text/plain";
                };
                LocalHttp.respond(ex, type, "{\"a\":1}".getBytes(StandardCharsets.UTF_8), 0);
            })) {
                for (final String path: List.of("/json", "/api", "/text")) {
                    try (final NetRequest req = new NetRequest(server.uri(path)).accept(NetRequest.ACCEPT_JSON).send()) {
                        final Map<?, ?> map = req.json(Map.class);
                        assertNotNull(map, path);
                        // GSON DESERIALIZES UNTYPED NUMBERS AS Double
                        assertEquals(1.0, map.get("a"), path);
                    }
                }
                try (final NetRequest req = new NetRequest(server.uri("/plain")).send()) {
                    assertNull(req.json(Map.class));
                    assertEquals("{\"a\":1}", req.text());
                }
            }
        }

        @Test
        @DisplayName("Malformed JSON throws IOException")
        void malformedJson() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/broken",
                    ex -> LocalHttp.respond(ex, "application/json", "{\"a\":".getBytes(StandardCharsets.UTF_8), 0));
                 final NetRequest req = new NetRequest(server.uri("/broken")).send()) {
                final IOException failure = assertThrows(IOException.class, () -> req.json(Map.class));
                assertTrue(failure.getMessage().startsWith("Malformed JSON"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("A failed status exposes its error body, or an empty one")
        void errorBodies() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/", ex -> {
                if ("/missing".equals(ex.getRequestURI().getPath())) {
                    final byte[] body = "not here".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, body.length);
                    ex.getResponseBody().write(body);
                } else {
                    ex.sendResponseHeaders(410, -1);
                }
                ex.close();
            })) {
                try (final NetRequest req = new NetRequest(server.uri("/missing")).send()) {
                    assertEquals(404, req.statusCode());
                    assertEquals("not here", req.text());
                }
                try (final NetRequest req = new NetRequest(server.uri("/gone")).send()) {
                    assertEquals(410, req.statusCode());
                    assertEquals("", req.text());
                }
            }
        }

        @Test
        @DisplayName("A plain GET exposes its status and content type, and the body can be read only once")
        void singleRead() throws IOException {
            try (final LocalHttp server = LocalHttp.start("/text", ex -> LocalHttp.respond(ex, "text/plain", "hello".getBytes(), 0));
                 final NetRequest req = new NetRequest(server.uri("/text")).send()) {
                assertEquals(200, req.statusCode());
                assertTrue(String.valueOf(req.contentType()).contains("text/plain"));
                assertEquals("hello", req.text());
                final IllegalStateException again = assertThrows(IllegalStateException.class, req::text);
                assertEquals("The response body was already read", again.getMessage());
                assertThrows(IllegalStateException.class, req::inputStream);
                assertThrows(IllegalStateException.class, () -> req.json(Map.class));
            }
        }

        @Test
        @DisplayName("A trickling body fails once it outlasts four read timeouts in total")
        void tricklingBodyHitsTheDeadline() throws IOException {
            final ExecutorService handlers = Executors.newSingleThreadExecutor();
            try (final LocalHttp server = LocalHttp.start("/slow", ex -> {
                ex.getResponseHeaders().set("Content-Type", "text/plain");
                ex.sendResponseHeaders(200, 0);
                // EVERY BYTE LANDS WELL INSIDE THE 200 MS SOCKET TIMEOUT, BUT THE WHOLE BODY WOULD TAKE THREE SECONDS
                try (final OutputStream out = ex.getResponseBody()) {
                    for (int i = 0; i < 30; i++) {
                        out.write('x');
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (final IOException gone) {
                    // THE CLIENT GAVE UP
                }
            }, handlers); final NetRequest req = new NetRequest(server.uri("/slow")).readTimeout(200).send()) {
                final long start = System.nanoTime();
                final IOException failure = assertThrows(IOException.class, req::text);
                final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                assertTrue(failure.getMessage().contains("deadline"), failure.getMessage());
                assertTrue(elapsedMs >= 800L, "Stopped before the deadline: " + elapsedMs + " ms");
                assertTrue(elapsedMs < 3_000L, "Waited for the whole body: " + elapsedMs + " ms");
            } finally {
                handlers.shutdownNow();
            }
        }
    }
}
