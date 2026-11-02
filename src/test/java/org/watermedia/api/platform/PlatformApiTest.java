package org.watermedia.api.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Resolution;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.Track;
import org.watermedia.test.support.MediaBootstrap;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link PlatformAPI} registry contract against the {@link Platform#resolve(URI)}
 * protocol, where a single call both claims and resolves a URI. {@link PlatformAPI#fetch(URI)} must
 * skip handlers that return {@code null}, answer with the first {@link Resolution} in probe order
 * (application handlers before the built-in ones, newest first), pass a {@link PlatformException}
 * through untouched and wrap a handler bug into one. The records of the platform layer reject
 * nonsense arguments up front.
 *
 * <p>Each test tracks the handlers it registers so {@link #cleanup()} pulls only those out of the
 * registry: the bootstrap registers the real built-in handlers and other tests may add their own.
 */
@DisplayName("PlatformAPI registry")
public class PlatformApiTest {
    // STUB PLATFORMS REGISTERED BY THE CURRENT TEST, REMOVED IN @AfterEach
    private final List<Platform> registered = new ArrayList<>();

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @AfterEach
    void cleanup() {
        for (final Platform p: this.registered) PlatformAPI.unregister(p);
        this.registered.clear();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    private Platform register(final Resolver resolver) {
        final Platform platform = stub("stub", resolver);
        PlatformAPI.register(platform);
        this.registered.add(platform);
        return platform;
    }

    // A BUILT-IN STAND-IN: THE SESSION OWNS IT AND IT IS PROBED AFTER EVERY APPLICATION HANDLER
    private Platform builtin(final Resolver resolver) {
        final Platform platform = stub("builtin", resolver);
        PlatformAPI.BUILTIN.add(platform);
        this.registered.add(platform);
        return platform;
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static Platform stub(final String name, final Resolver resolver) {
        return new Platform() {
            @Override public String name() { return name; }
            @Override public Resolution resolve(final URI uri) throws PlatformException { return resolver.resolve(uri); }
        };
    }

    private static Resolution marker(final String url) {
        return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(), List.of(new Quality(URI.create(url), 0, 0)), null, null));
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Claim Resolution")
    class ClaimTests {

        @Test
        @DisplayName("fetch skips platforms that return null")
        void testFetchSkipsPlatformsThatReturnNull() throws Exception {
            final Resolution claimed = marker("https://claimed.example/");
            // REGISTERED FIRST → CHECKED LAST; CLAIMS THE URI
            PlatformApiTest.this.register(uri -> claimed);
            // REGISTERED LAST → CHECKED FIRST; DISCLAIMS (NULL) SO THE PROBE CONTINUES
            PlatformApiTest.this.register(uri -> null);

            final Resolution result = PlatformAPI.fetch(URI.create("https://anything.test/"));
            assertSame(claimed, result, "fetch must skip null-returning platforms and use the next claimant");
        }

        @Test
        @DisplayName("Last-registered platform wins over prior registrations")
        void testLastRegisteredWinsOverPrior() throws Exception {
            final Resolution markerA = marker("https://a.example/");
            final Resolution markerB = marker("https://b.example/");

            PlatformApiTest.this.register(uri -> markerA);
            PlatformApiTest.this.register(uri -> markerB);

            // REVERSE ITERATION: STUB B IS CHECKED FIRST AND WINS
            final Resolution result = PlatformAPI.fetch(URI.create("https://anything.test/"));
            assertSame(markerB, result, "Last-registered platform must win");
        }

        @Test
        @DisplayName("An application handler beats a built-in one registered later")
        void testApplicationHandlerBeatsBuiltin() throws Exception {
            final Resolution custom = marker("https://custom.example/");
            final Resolution shipped = marker("https://builtin.example/");
            final Platform application = PlatformApiTest.this.register(uri -> custom);
            final Platform builtin = PlatformApiTest.this.builtin(uri -> shipped);

            assertSame(custom, PlatformAPI.fetch(URI.create("https://anything.test/")), "Application handlers are probed first");
            final List<Platform> order = PlatformAPI.platforms();
            assertTrue(order.indexOf(application) < order.indexOf(builtin), "platforms() lists the probe order");
        }

        @Test
        @DisplayName("unregister removes a built-in handler and a null handler is rejected")
        void testUnregisterRemovesBuiltin() {
            final Platform builtin = PlatformApiTest.this.builtin(uri -> null);
            assertTrue(PlatformAPI.platforms().contains(builtin));
            PlatformAPI.unregister(builtin);
            assertFalse(PlatformAPI.platforms().contains(builtin), "A built-in handler can be dropped by the application");
            assertThrows(IllegalArgumentException.class, () -> PlatformAPI.register(null));
            assertThrows(IllegalArgumentException.class, () -> PlatformAPI.fetch(null));
        }

        @Test
        @DisplayName("fetch returns null when no platform claims the URI")
        void testFetchReturnsNullWhenNoPlatformClaims() throws Exception {
            PlatformApiTest.this.register(uri -> "only.this".equals(uri.getHost()) ? marker("https://x/") : null);

            assertNull(PlatformAPI.fetch(URI.create("https://nobody.handles.me/")),
                    "fetch must return null when no platform claims the URI");
        }
    }

    @Nested
    @DisplayName("Exception Propagation")
    class ExceptionTests {

        @Test
        @DisplayName("PlatformException from a platform passes through untouched")
        void testFetchPassesPlatformException() {
            // THE EXCEPTION TRAVELS UNTOUCHED, SO ITS SUBTYPE (MATURE) SURVIVES
            final PlatformException boom = new MatureContentException("stub", "blocked");
            PlatformApiTest.this.register(uri -> { throw boom; });

            final PlatformException thrown = assertThrows(PlatformException.class,
                    () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
            assertSame(boom, thrown, "The platform's own exception must reach the caller");
        }

        @Test
        @DisplayName("A runtime exception escaping a handler is wrapped in PlatformException")
        void testFetchWrapsHandlerBug() {
            // A HANDLER BUG IS WRAPPED WITH THE PLATFORM NAME AND THE ORIGINAL AS CAUSE
            final RuntimeException boom = new IllegalStateException("parse blew up");
            PlatformApiTest.this.register(uri -> { throw boom; });

            final PlatformException thrown = assertThrows(PlatformException.class,
                    () -> PlatformAPI.fetch(URI.create("https://anything.test/")));
            assertSame(boom, thrown.getCause(), "The handler bug must be preserved as the cause");
            assertTrue(thrown.getMessage().startsWith("stub: "), "The message names the handler");
        }
    }

    @Nested
    @DisplayName("Record Validation")
    class RecordTests {
        private final URI uri = URI.create("https://example.com/a.mp4");

        @Test
        @DisplayName("Quality needs a URI and non-negative dimensions")
        void testQualityValidation() {
            assertThrows(IllegalArgumentException.class, () -> new Quality(null, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Quality(this.uri, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> new Quality(this.uri, 0, 0, -1f));
            assertEquals(0f, new Quality(this.uri, 0, 0).fps());
        }

        @Test
        @DisplayName("Source needs a media type and at least one quality")
        void testSourceValidation() {
            final List<Quality> one = List.of(new Quality(this.uri, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Source(null, null, null, null, one, null, null));
            assertThrows(IllegalArgumentException.class, () -> new Source(MediaType.VIDEO, null, null, null, List.of(), null, null));
            assertThrows(UnsupportedOperationException.class, () -> new Source(MediaType.VIDEO, null, null, null, one, null, null).headers().set("X-Test", "1"));
        }

        @Test
        @DisplayName("Track, PlatformResult, Metadata and Resolution reject what they cannot carry")
        void testOtherRecords() {
            assertThrows(IllegalArgumentException.class, () -> new Track("English", "en", null));
            assertThrows(IllegalArgumentException.class, () -> new PlatformResult(null, "t", null, this.uri));
            assertThrows(IllegalArgumentException.class, () -> new PlatformResult(stub("p", u -> null), "t", null, null));
            assertThrows(IllegalArgumentException.class, () -> new Metadata("t", null, null, -1L, null));
            assertThrows(IllegalArgumentException.class, () -> new Resolution(null, (List<Source>) null));
            assertEquals(0, new Resolution(null, List.of()).sourceCount());
        }
    }

    // RESOLVER SUPPLIES THE RESOLUTION WHILE name() STAYS FIXED, SO EVERY STUB IS A ONE-LINER
    @FunctionalInterface
    private interface Resolver {
        Resolution resolve(URI uri) throws PlatformException;
    }
}
