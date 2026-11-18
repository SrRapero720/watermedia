package org.watermedia.api.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * Verifies the {@link PlatformAPI} registry: {@link PlatformAPI#fetch(URI)} skips handlers that return {@code null}
 * and answers with the first {@link Resolution} in probe order (application handlers before the built-in ones, newest
 * first), {@link PlatformAPI#unregister(Platform)} drops a built-in handler, null handlers and URIs are refused with
 * their codes, a {@link PlatformException} passes through untouched and a handler bug is wrapped into one; also the
 * argument checks of the platform records and of the {@code api.util} records they carry.
 * <p>
 * The cases register stubs next to the real built-in handlers, and the built-in stand-ins go straight into the
 * package-private {@code BUILTIN} list: both registries are copied before every case and put back after it.
 */
@DisplayName("PlatformAPI registry")
public class PlatformApiTest {
    private static final URI ANY = URI.create("https://anything.test/");

    private List<Platform> savedBuiltin;
    private List<Platform> savedCustom;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void snapshot() {
        this.savedBuiltin = new ArrayList<>(PlatformAPI.BUILTIN);
        this.savedCustom = new ArrayList<>(PlatformAPI.CUSTOM);
    }

    @AfterEach
    void restore() {
        // WHATEVER THE CASE ADDED OR DROPPED, A NULL LET IN BY A BROKEN GUARD INCLUDED
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        PlatformAPI.BUILTIN.addAll(this.savedBuiltin);
        PlatformAPI.CUSTOM.addAll(this.savedCustom);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static Platform register(final Resolver resolver) {
        final Platform platform = stub("stub", resolver);
        PlatformAPI.register(platform);
        return platform;
    }

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
    @DisplayName("Probe order")
    class Probing {

        @Test
        @DisplayName("fetch skips platforms that return null")
        void fetchSkipsPlatformsThatReturnNull() throws Exception {
            final Resolution claimed = marker("https://claimed.example/");
            // REGISTERED FIRST, SO PROBED LAST: IT CLAIMS THE URI
            register(uri -> claimed);
            // REGISTERED LAST, SO PROBED FIRST: IT DISCLAIMS THE URI AND THE PROBE GOES ON
            register(uri -> null);
            assertSame(claimed, PlatformAPI.fetch(ANY), "fetch must skip null-returning platforms and use the next claimant");
        }

        @Test
        @DisplayName("The last registered platform wins over the earlier ones")
        void lastRegisteredWins() throws Exception {
            final Resolution first = marker("https://a.example/");
            final Resolution last = marker("https://b.example/");
            register(uri -> first);
            register(uri -> last);
            assertSame(last, PlatformAPI.fetch(ANY), "The last registered platform must win");
        }

        @Test
        @DisplayName("An application handler beats a built-in one registered later, and platforms() lists that order")
        void applicationHandlerBeatsBuiltin() throws Exception {
            final Resolution custom = marker("https://custom.example/");
            final Platform application = register(uri -> custom);
            final Platform builtin = stub("builtin", uri -> marker("https://builtin.example/"));
            PlatformAPI.BUILTIN.add(builtin);

            assertSame(custom, PlatformAPI.fetch(ANY), "Application handlers are probed first");
            final List<Platform> order = PlatformAPI.platforms();
            assertTrue(order.indexOf(application) < order.indexOf(builtin), "platforms() lists the probe order");
        }

        @Test
        @DisplayName("unregister drops a built-in handler, and register and fetch refuse null with their codes")
        void unregisterDropsBuiltinAndNullIsRefused() {
            final Platform builtin = stub("builtin", uri -> null);
            PlatformAPI.BUILTIN.add(builtin);
            assertTrue(PlatformAPI.platforms().contains(builtin), "The built-in stand-in is registered");
            PlatformAPI.unregister(builtin);
            assertFalse(PlatformAPI.platforms().contains(builtin), "A built-in handler can be dropped by the application");

            assertEquals("(wm_err:2014) Failed to register the platform due to null platform",
                    assertThrows(IllegalArgumentException.class, () -> PlatformAPI.register(null)).getMessage());
            assertEquals("(wm_err:2011) Failed to resolve the URI due to null URI",
                    assertThrows(IllegalArgumentException.class, () -> PlatformAPI.fetch(null)).getMessage());
        }

        @Test
        @DisplayName("fetch returns null when no platform claims the URI")
        void fetchReturnsNullWhenNoPlatformClaims() throws Exception {
            register(uri -> "only.this".equals(uri.getHost()) ? marker("https://x/") : null);
            assertNull(PlatformAPI.fetch(URI.create("https://nobody.handles.me/")), "fetch must return null when no platform claims the URI");
        }
    }

    @Nested
    @DisplayName("Exception propagation")
    class Failures {

        @Test
        @DisplayName("A PlatformException of a platform passes through untouched, subtype included")
        void fetchPassesPlatformExceptionThrough() {
            final PlatformException boom = new MatureContentException("stub", "blocked");
            register(uri -> { throw boom; });
            assertSame(boom, assertThrows(PlatformException.class, () -> PlatformAPI.fetch(ANY)), "The platform's own exception must reach the caller");
        }

        @Test
        @DisplayName("A runtime exception escaping a handler is wrapped in a coded PlatformException naming the handler, the bug as its cause")
        void fetchWrapsHandlerBug() {
            final RuntimeException boom = new IllegalStateException("parse blew up");
            register(uri -> { throw boom; });

            final PlatformException thrown = assertThrows(PlatformException.class, () -> PlatformAPI.fetch(ANY));
            assertSame(boom, thrown.getCause(), "The handler bug must be preserved as the cause");
            assertEquals("stub: (wm_err:2013) Failed to resolve https://anything.test/ due to an unexpected error (java.lang.IllegalStateException: parse blew up)",
                    thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("Record validation")
    class Records {
        private final URI uri = URI.create("https://example.com/a.mp4");

        @Test
        @DisplayName("Quality needs a URI and non-negative dimensions, and its frame rate defaults to unknown (0)")
        void qualityNeedsUriAndNonNegativeDimensions() {
            assertThrows(IllegalArgumentException.class, () -> new Quality(null, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Quality(this.uri, -1, 0));
            assertThrows(IllegalArgumentException.class, () -> new Quality(this.uri, 0, 0, -1f));
            assertEquals(0f, new Quality(this.uri, 0, 0).fps());
        }

        @Test
        @DisplayName("Source needs a media type and at least one quality, and freezes its headers")
        void sourceNeedsTypeAndQualities() {
            final List<Quality> one = List.of(new Quality(this.uri, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new Source(null, null, null, null, one, null, null));
            assertThrows(IllegalArgumentException.class, () -> new Source(MediaType.VIDEO, null, null, null, List.of(), null, null));
            assertThrows(UnsupportedOperationException.class, () -> new Source(MediaType.VIDEO, null, null, null, one, null, null).headers().set("X-Test", "1"));
        }

        @Test
        @DisplayName("Track, PlatformResult, Metadata and Resolution reject what they cannot carry, while an empty resolution is valid")
        void otherRecordsRejectWhatTheyCannotCarry() {
            assertThrows(IllegalArgumentException.class, () -> new Track("English", "en", null));
            assertThrows(IllegalArgumentException.class, () -> new PlatformResult(null, "t", null, this.uri));
            assertThrows(IllegalArgumentException.class, () -> new PlatformResult(stub("p", u -> null), "t", null, null));
            assertThrows(IllegalArgumentException.class, () -> new Metadata("t", null, null, -1L, null));
            assertThrows(IllegalArgumentException.class, () -> new Resolution(null, (List<Source>) null));
            assertEquals(0, new Resolution(null, List.of()).sourceCount());
        }
    }

    // THE STUB'S ANSWER, WHILE name() STAYS FIXED, SO EVERY STUB IS A ONE-LINER
    @FunctionalInterface
    private interface Resolver {
        Resolution resolve(URI uri) throws PlatformException;
    }
}
