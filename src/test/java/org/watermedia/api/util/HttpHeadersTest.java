package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the multi-valued, case-insensitive contract of {@link HttpHeaders}
 * and the default header bag built from a target URI.
 */
@DisplayName("HttpHeaders")
public class HttpHeadersTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Validation")
    class ValidationTests {

        @Test
        @DisplayName("names and values that could inject a header line are rejected before any raw serialization")
        void rejectsInjectedHeadersBeforeRawSerialization() {
            for (final String name: List.of("", "Bad Name", "Bad:Name", "X\r\nInjected", "é")) {
                assertThrows(IllegalArgumentException.class, () -> new HttpHeaders.Entry(name, "value"));
            }
            for (final String value: List.of("ok\r\nAuthorization: stolen", "ok\nInjected: yes", "a\u0000b", "a\u007fb")) {
                assertThrows(IllegalArgumentException.class, () -> new HttpHeaders().add("X", value));
            }
            final HttpHeaders headers = new HttpHeaders().set("X", "original");
            assertThrows(IllegalArgumentException.class, () -> headers.set("X", "bad\rvalue"));
            assertEquals("original", headers.value("X"));
            assertEquals("X: a\tb\r\n", new HttpHeaders().add("X", "a\tb").toRawString());
        }
    }

    @Nested
    @DisplayName("Mutation operations")
    class MutationTests {

        @Test
        @DisplayName("set replaces an existing single value")
        void setReplaces() {
            final HttpHeaders h = new HttpHeaders().set("X", "1").set("X", "2");
            assertEquals("2", h.value("X"));
            assertEquals(1, h.count());
        }

        @Test
        @DisplayName("add appends additional values without clobbering")
        void addAppends() {
            final HttpHeaders h = new HttpHeaders().add("X", "1").add("X", "2");
            assertEquals(List.of("1", "2"), h.values("X"));
        }

        @Test
        @DisplayName("name lookup is case-insensitive")
        void caseInsensitiveLookup() {
            final HttpHeaders h = new HttpHeaders().set("Accept", "json");
            assertEquals("json", h.value("ACCEPT"));
            assertEquals("json", h.value("accept"));
        }

        @Test
        @DisplayName("has reflects presence")
        void hasFlag() {
            final HttpHeaders h = new HttpHeaders().set("present", "yes");
            assertTrue(h.has("present"));
            assertTrue(h.has("PRESENT"));
            assertFalse(h.has("absent"));
        }

        @Test
        @DisplayName("removeAll wipes every match")
        void removeAllWipesEntries() {
            final HttpHeaders h = new HttpHeaders().add("X", "1").add("x", "2").add("Y", "3");
            h.removeAll("X");
            assertFalse(h.has("X"));
            assertEquals("3", h.value("Y"));
            assertEquals(1, h.count());
        }

        @Test
        @DisplayName("merging a bag into itself is a no-op")
        void selfMergeIsNoOp() {
            final HttpHeaders h = new HttpHeaders().add("X", "1").add("X", "2").set("Y", "3");
            assertSame(h, h.merge(h));
            assertEquals(List.of("1", "2"), h.values("X"));
            assertEquals(3, h.count());
        }

        @Test
        @DisplayName("a frozen bag refuses every mutator, still reads, and copies into a mutable bag")
        void frozenBagRefusesMutators() {
            final HttpHeaders frozen = new HttpHeaders().set("X", "1").freeze();
            assertThrows(UnsupportedOperationException.class, () -> frozen.set("X", "2"));
            assertThrows(UnsupportedOperationException.class, () -> frozen.add("Y", "2"));
            assertThrows(UnsupportedOperationException.class, () -> frozen.removeAll("X"));
            assertThrows(UnsupportedOperationException.class, () -> frozen.merge(new HttpHeaders().set("Z", "3")));
            assertEquals("1", frozen.value("X"));
            assertEquals(1, frozen.count());
            assertEquals("X: 1\r\n", frozen.toRawString());
            final HttpHeaders copy = new HttpHeaders(frozen).set("X", "2");
            assertEquals("2", copy.value("X"));
            assertEquals("1", frozen.value("X"));
        }
    }

    @Nested
    @DisplayName("Iteration and rendering")
    class RenderTests {

        @Test
        @DisplayName("entries preserve insertion order")
        void insertionOrder() {
            final HttpHeaders h = new HttpHeaders()
                    .set("First", "a")
                    .set("Second", "b")
                    .add("Second", "c");

            final List<HttpHeaders.Entry> entries = h.entries();
            assertEquals(3, entries.size());
            assertEquals("First", entries.get(0).name());
            assertEquals("Second", entries.get(1).name());
            assertEquals("b", entries.get(1).value());
            assertEquals("Second", entries.get(2).name());
            assertEquals("c", entries.get(2).value());
        }

        @Test
        @DisplayName("toRawString emits Name: Value lines terminated by CRLF")
        void rawString() {
            final HttpHeaders h = new HttpHeaders().set("A", "1").set("B", "2");
            assertEquals("A: 1\r\nB: 2\r\n", h.toRawString());
        }

        @Test
        @DisplayName("empty bag renders to empty string")
        void rawStringEmpty() {
            assertEquals("", new HttpHeaders().toRawString());
            assertTrue(new HttpHeaders().empty());
        }
    }

    @Nested
    @DisplayName("defaults(URI)")
    class DefaultsTests {

        @Test
        @DisplayName("populates User-Agent, media-friendly Accept and host-derived Referer")
        void defaultsForHttpsUri() {
            final HttpHeaders h = HttpHeaders.defaults(URI.create("https://example.com/path"));

            final String userAgent = h.value("User-Agent");
            assertNotNull(userAgent);
            assertFalse(userAgent.isBlank());

            assertEquals(NetRequest.ACCEPT_MEDIA, h.value("Accept"));
            assertEquals("https://example.com/", h.value("Referer"));
        }

        @Test
        @DisplayName("Referer keeps an explicit port and drops path and query")
        void refererKeepsPort() {
            assertEquals("http://127.0.0.1:8080/", HttpHeaders.defaults(URI.create("http://127.0.0.1:8080/a/b?c=d")).value("Referer"));
            assertEquals("https://example.com:443/", HttpHeaders.defaults(URI.create("https://example.com:443/x")).value("Referer"));
            assertNull(HttpHeaders.defaults(URI.create("file:///tmp/x.mp4")).value("Referer"));
        }

        @Test
        @DisplayName("null URI skips Referer")
        void nullUriSkipsReferer() {
            final HttpHeaders h = HttpHeaders.defaults(null);
            assertNull(h.value("Referer"));
            assertNotNull(h.value("User-Agent"));
        }
    }
}
