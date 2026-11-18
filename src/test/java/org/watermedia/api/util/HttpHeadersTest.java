package org.watermedia.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link HttpHeaders} bag: the refusal of names and values that could inject a header line, the
 * case-insensitive multi-valued mutators, self merge and freezing, insertion order and the raw CRLF rendering, and the
 * default bag {@code defaults(URI)} builds from a target URI.
 */
@DisplayName("HttpHeaders")
public class HttpHeadersTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Names outside the HTTP token set and values with control characters other than tab are refused, and a refused set keeps the old value")
        void refusesHeaderInjection() {
            for (final String name: List.of("", "Bad Name", "Bad:Name", "X\r\nInjected", "é")) {
                assertThrows(IllegalArgumentException.class, () -> new HttpHeaders.Entry(name, "value"), () -> "refuses the name " + name);
            }
            for (final String value: List.of("ok\r\nAuthorization: stolen", "ok\nInjected: yes", "a\u0000b", "a\u007fb")) {
                assertThrows(IllegalArgumentException.class, () -> new HttpHeaders().add("X", value), () -> "refuses the value " + value);
            }
            final HttpHeaders headers = new HttpHeaders().set("X", "original");
            assertThrows(IllegalArgumentException.class, () -> headers.set("X", "bad\rvalue"));
            assertEquals("original", headers.value("X"));
            assertEquals("X: a\tb\r\n", new HttpHeaders().add("X", "a\tb").toRawString(), "a tab is allowed");
        }
    }

    @Nested
    @DisplayName("Mutation")
    class Mutation {

        @Test
        @DisplayName("set replaces the earlier value of a name with a single entry")
        void setReplaces() {
            final HttpHeaders h = new HttpHeaders().set("X", "1").set("X", "2");
            assertEquals("2", h.value("X"));
            assertEquals(1, h.count());
        }

        @Test
        @DisplayName("add appends a value and keeps the earlier ones")
        void addAppends() {
            final HttpHeaders h = new HttpHeaders().add("X", "1").add("X", "2");
            assertEquals(List.of("1", "2"), h.values("X"));
        }

        @Test
        @DisplayName("value finds a name in any case")
        void valueIgnoresCase() {
            final HttpHeaders h = new HttpHeaders().set("Accept", "json");
            assertEquals("json", h.value("ACCEPT"));
            assertEquals("json", h.value("accept"));
        }

        @Test
        @DisplayName("has finds a name in any case and misses an absent one")
        void hasIgnoresCase() {
            final HttpHeaders h = new HttpHeaders().set("present", "yes");
            assertTrue(h.has("present"));
            assertTrue(h.has("PRESENT"));
            assertFalse(h.has("absent"));
        }

        @Test
        @DisplayName("removeAll drops every value of a name in any case and keeps the other names")
        void removeAllDropsEveryMatch() {
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
        @DisplayName("A frozen bag refuses every mutator, still reads, and copies into a mutable bag")
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
            assertEquals("1", frozen.value("X"), "the copy leaves the frozen bag alone");
        }
    }

    @Nested
    @DisplayName("Rendering")
    class Rendering {

        @Test
        @DisplayName("entries keep the insertion order, repeated names included")
        void entriesKeepInsertionOrder() {
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
        @DisplayName("toRawString writes one Name: Value line per entry, each ended by CRLF")
        void rawStringEndsEachLineWithCrlf() {
            final HttpHeaders h = new HttpHeaders().set("A", "1").set("B", "2");
            assertEquals("A: 1\r\nB: 2\r\n", h.toRawString());
        }

        @Test
        @DisplayName("An empty bag is empty() and renders an empty string")
        void rawStringEmpty() {
            assertEquals("", new HttpHeaders().toRawString());
            assertTrue(new HttpHeaders().empty());
        }
    }

    @Nested
    @DisplayName("defaults(URI)")
    class Defaults {

        @Test
        @DisplayName("defaults sets the WaterMedia User-Agent, the media Accept and the Referer of the origin")
        void defaultsCarryAgentAcceptAndReferer() {
            final HttpHeaders h = HttpHeaders.defaults(URI.create("https://example.com/path"));
            assertEquals(NetRequest.UserAgent.WATERMEDIA.value(), h.value("User-Agent"));
            assertEquals(NetRequest.ACCEPT_MEDIA, h.value("Accept"));
            assertEquals("https://example.com/", h.value("Referer"));
        }

        @Test
        @DisplayName("The Referer keeps an explicit port, drops the path and query, and is absent without a host")
        void refererKeepsPort() {
            assertEquals("http://127.0.0.1:8080/", HttpHeaders.defaults(URI.create("http://127.0.0.1:8080/a/b?c=d")).value("Referer"));
            assertEquals("https://example.com:443/", HttpHeaders.defaults(URI.create("https://example.com:443/x")).value("Referer"));
            assertNull(HttpHeaders.defaults(URI.create("file:///tmp/x.mp4")).value("Referer"));
        }

        @Test
        @DisplayName("A null URI gets the User-Agent but no Referer")
        void nullUriSkipsReferer() {
            final HttpHeaders h = HttpHeaders.defaults(null);
            assertNull(h.value("Referer"));
            assertEquals(NetRequest.UserAgent.WATERMEDIA.value(), h.value("User-Agent"));
        }
    }
}
