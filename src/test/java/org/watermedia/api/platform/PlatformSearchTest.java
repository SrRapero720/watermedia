package org.watermedia.api.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMediaConfig;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PlatformAPI#search(String, int, boolean)} offline: the live result list and {@link PlatformAPI#searching(String)},
 * history dedup and cap, the cache and its invalidation, the per-platform limit under its configured ceiling, the typing
 * delay, the re-search flag and the discard of the search in flight. All pure-JVM logic driven by stub {@link Platform}s.
 * <p>
 * The registry is a package-private static and the search state is private, so the latter is reached through
 * reflection: each case snapshots and clears the registry, history and cache before running and restores the registry after.
 */
@DisplayName("PlatformAPI search")
public class PlatformSearchTest {
    private List<Platform> savedBuiltin;
    private List<Platform> savedCustom;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void isolate() throws Exception {
        // SNAPSHOT + CLEAR BOTH REGISTRIES SO ONLY THIS TEST'S STUBS ARE PROBED (NO REAL PLATFORM NETWORKING)
        this.savedBuiltin = new ArrayList<>(PlatformAPI.BUILTIN);
        this.savedCustom = new ArrayList<>(PlatformAPI.CUSTOM);
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        resetSearchState();
    }

    @AfterEach
    void restore() throws Exception {
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        PlatformAPI.BUILTIN.addAll(this.savedBuiltin);
        PlatformAPI.CUSTOM.addAll(this.savedCustom);
        resetSearchState();
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static boolean awaitDone(final String query) {
        return PlayerWait.awaitCondition(() -> !PlatformAPI.searching(query), 3_000);
    }

    // DISCARDS THE SEARCH IN FLIGHT AND CLEARS HISTORY, THE RESULT CACHE AND THE CACHE-SWEEP CLOCK, SO EACH CASE STARTS CLEAN
    @SuppressWarnings("unchecked")
    private static void resetSearchState() throws Exception {
        final Object lock = field("SEARCH_LOCK").get(null);
        synchronized (lock) {
            for (final Future<?> probe: (List<Future<?>>) field("searchProbes").get(null)) probe.cancel(true);
            ((List<?>) field("searchProbes").get(null)).clear();
            field("searchRemaining").set(null, 0);
            field("searchQuery").set(null, null);
            field("searchGeneration").set(null, (long) field("searchGeneration").get(null) + 1);
            ((ArrayDeque<?>) field("HISTORY").get(null)).clear();
            ((Map<?, ?>) field("SEARCH_CACHE").get(null)).clear();
            field("nextCacheClean").set(null, 0L);
        }
    }

    private static Field field(final String name) throws Exception {
        final Field f = PlatformAPI.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("History")
    class History {

        @Test
        @DisplayName("Blank query returns an empty list, searches nothing and is not remembered")
        void testBlankQueryIsNoOp() {
            final List<PlatformResult> results = PlatformAPI.search("   ");
            assertTrue(results.isEmpty());
            assertFalse(PlatformAPI.searching("   "));
            assertTrue(PlatformAPI.searchHistory().isEmpty(), "A blank query is not worth remembering");
        }

        @Test
        @DisplayName("History dedups to most-recent-first and caps at the limit")
        void testHistoryDedupAndCap() {
            // DEDUP: RE-SEARCHING 'alpha' MOVES IT BACK TO THE FRONT RATHER THAN DUPLICATING
            PlatformAPI.search("alpha");
            PlatformAPI.search("beta");
            PlatformAPI.search("alpha");
            assertEquals(List.of("alpha", "beta"), PlatformAPI.searchHistory(), "History must dedup, newest first");

            // CAP: 15 DISTINCT QUERIES COLLAPSE TO THE 10 MOST RECENT (q15..q6), NEWEST FIRST
            for (int i = 1; i <= 15; i++) PlatformAPI.search("q" + i);
            final List<String> history = PlatformAPI.searchHistory();
            assertEquals(10, history.size(), "History must be capped at 10 entries");
            assertEquals("q15", history.get(0), "Most recent query must be first");
            assertEquals("q6", history.get(9), "Oldest retained query must be the 10th");
        }
    }

    @Nested
    @DisplayName("Results and cache")
    class Results {

        @Test
        @DisplayName("The list fills off-thread, is read-only and a platform over the limit is capped")
        void testLiveListAndCap() {
            // RETURNS 5 HITS WHATEVER IT IS ASKED; THE API KEEPS THE LIMIT
            PlatformAPI.register(new StubPlatform(5, null));
            final List<PlatformResult> results = PlatformAPI.search("lions");
            assertTrue(awaitDone("lions"), "Search must complete");
            assertEquals(WaterMediaConfig.platforms.searchLimit, results.size(), "Hits beyond the per-platform limit must be dropped");
            assertThrows(UnsupportedOperationException.class, results::clear);
        }

        @Test
        @DisplayName("The limit reaches the platform under the configured ceiling, keys the cache and rejects zero")
        void testLimitAndCeiling() {
            final StubPlatform stub = new StubPlatform(5, null);
            PlatformAPI.register(stub);
            final int ceiling = WaterMediaConfig.platforms.searchLimit;
            assertThrows(IllegalArgumentException.class, () -> PlatformAPI.search("tigers", 0, false));

            final List<PlatformResult> one = PlatformAPI.search("tigers", 1, false);
            assertTrue(awaitDone("tigers"), "Search must complete");
            assertEquals(1, stub.lastLimit, "The platform is asked for the limit");
            assertEquals(1, one.size());

            // THE SAME TEXT WITH ANOTHER LIMIT IS ANOTHER SEARCH, SO THE CACHED ONE IS NOT SERVED
            final List<PlatformResult> more = PlatformAPI.search("tigers", ceiling + 10, false);
            assertTrue(awaitDone("tigers"), "Search must complete");
            assertEquals(2, stub.probes.get(), "A different limit must probe again");
            assertEquals(ceiling, stub.lastLimit, "The configured ceiling caps what the platform is asked for");
            assertEquals(ceiling, more.size());
            assertSame(more, PlatformAPI.search("tigers", ceiling, false), "The ceiling and a limit above it are the same search");
            assertEquals(2, stub.probes.get(), "A cache hit must not re-probe the platform");
        }

        @Test
        @DisplayName("An identical query is served the same list without re-probing, finished or still filling")
        void testCacheHitDoesNotReprobe() {
            final StubPlatform stub = new StubPlatform(1, 300);
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("dogs");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
            final List<PlatformResult> during = PlatformAPI.search("dogs");
            assertTrue(PlatformAPI.searching("dogs"), "The query in flight stays in flight");
            assertFalse(PlatformAPI.searching("cats"), "Only the query in flight is searching");
            assertSame(first, during, "The query in flight is served its own list");
            assertTrue(awaitDone("dogs"), "Search must complete");
            assertEquals(1, first.size());

            final List<PlatformResult> again = PlatformAPI.search("dogs");
            assertFalse(PlatformAPI.searching("dogs"), "A cache hit does not search");
            assertSame(first, again, "The cached results are the same list");
            assertEquals(1, stub.probes.get(), "A cache hit must not re-probe the platform");
        }

        @Test
        @DisplayName("reSearch drops the cached results and probes the platforms again")
        void testReSearch() {
            final StubPlatform stub = new StubPlatform(1, null);
            PlatformAPI.register(stub);
            PlatformAPI.search("owls");
            assertTrue(awaitDone("owls"), "Search must complete");
            PlatformAPI.search("owls");
            assertEquals(1, stub.probes.get());

            final List<PlatformResult> fresh = PlatformAPI.search("owls", true);
            assertTrue(awaitDone("owls"), "The re-search must complete");
            assertEquals(2, stub.probes.get(), "A re-search asks the platform again");
            assertEquals(1, fresh.size());
        }

        @Test
        @DisplayName("An empty result is not cached, so the next call asks again")
        void testEmptyResultNotCached() {
            final StubPlatform stub = new StubPlatform(0, null);
            PlatformAPI.register(stub);
            PlatformAPI.search("nothing");
            assertTrue(awaitDone("nothing"), "Search must complete");
            PlatformAPI.search("nothing");
            assertTrue(awaitDone("nothing"), "Search must complete");
            assertEquals(2, stub.probes.get(), "A transient total failure must retry instead of serving stale nothing");
        }

        @Test
        @DisplayName("A registration during a search keeps that search out of the cache")
        void testRegistrationDuringSearchIsNotCached() {
            // BLOCKS SO THE REGISTRY CHANGES WHILE THE SEARCH RUNS
            final StubPlatform stub = new StubPlatform(1, 300);
            PlatformAPI.register(stub);
            final List<PlatformResult> first = PlatformAPI.search("race");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
            PlatformAPI.register(new StubPlatform(1, null));
            assertTrue(awaitDone("race"), "Search must complete");
            assertEquals(1, first.size(), "The running search keeps its snapshot of the registry");

            final List<PlatformResult> again = PlatformAPI.search("race");
            assertTrue(awaitDone("race"), "Search must complete");
            assertEquals(2, again.size(), "The query is probed again with the new handler instead of served from cache");
        }

        @Test
        @DisplayName("Registering a handler drops the cached searches")
        void testRegistrationInvalidatesCache() {
            final StubPlatform stub = new StubPlatform(1, null);
            PlatformAPI.register(stub);
            PlatformAPI.search("birds");
            assertTrue(awaitDone("birds"), "Search must complete");

            PlatformAPI.register(new StubPlatform(1, null));
            final List<PlatformResult> again = PlatformAPI.search("birds");
            assertTrue(awaitDone("birds"), "Search must complete");
            assertEquals(2, stub.probes.get(), "A new handler must force the query to be probed again");
            assertEquals(2, again.size(), "Both handlers answer the re-probed query");
        }
    }

    @Nested
    @DisplayName("Discard and typing")
    class Discard {

        @Test
        @DisplayName("A new query discards the search in flight, whose list stays empty and leaves the cache")
        void testNewQueryDiscardsTheOldSearch() {
            // BLOCKS SO THE FIRST SEARCH STAYS IN-FLIGHT
            final StubPlatform stub = new StubPlatform(1, 2_000);
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("cats");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
            assertTrue(PlatformAPI.searching("cats"));
            // NOTHING ALIKE: NO TYPING DELAY
            final List<PlatformResult> second = PlatformAPI.search("dogs");
            assertFalse(PlatformAPI.searching("cats"), "The discarded search is no longer in flight");
            // THE CANCELLED PROBE UNWINDS ON ITS OWN THREAD; ONLY ONCE IT HAS RETURNED CAN ITS HITS BE RULED OUT
            assertTrue(PlayerWait.awaitCondition(() -> stub.finished.get() == 1, 3_000), "The cancelled probe must unwind");
            assertTrue(first.isEmpty(), "A discarded search never gains the hits of its cancelled probes");
            assertTrue(awaitDone("dogs"), "The new search must complete");
            assertEquals(1, second.size());
            assertEquals(2, stub.probes.get(), "The new query was probed at once");
        }

        @Test
        @DisplayName("A query one keystroke away waits for the next one before asking the platforms, and a discarded step is re-asked later")
        void testTypingDelay() throws Exception {
            // THE DELAY IS MEASURED AGAINST THE WALL CLOCK, SO A STALLED JVM GETS TWO MORE TRIES BEFORE THE CASE FAILS
            AssertionError failure = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                if (attempt > 1) {
                    PlatformAPI.CUSTOM.clear();
                    resetSearchState();
                    ThreadTool.sleep(5_000);
                }
                try {
                    final StubPlatform stub = new StubPlatform(1, null);
                    PlatformAPI.register(stub);
                    PlatformAPI.search("cats");
                    assertTrue(awaitDone("cats"), "The first query probes at once");
                    assertEquals(1, stub.probes.get());

                    final long started = System.nanoTime();
                    // ONE LETTER FIXED: THE SAME INTENTION
                    final List<PlatformResult> typed = PlatformAPI.search("cars");
                    assertTrue(PlatformAPI.searching("cars"), "The delayed search counts as in flight");
                    assertFalse(PlayerWait.awaitCondition(() -> stub.probes.get() == 2, 120), "A typing continuation must wait before probing");
                    assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 2, 3_000), "The delayed probe must run");
                    assertTrue(System.nanoTime() - started >= 250_000_000L, "The probe ran before the typing delay elapsed");
                    assertTrue(awaitDone("cars"));
                    assertEquals(1, typed.size());

                    // A DISCARDED TYPING STEP LEAVES THE CACHE: ASKING FOR IT AGAIN PROBES AGAIN
                    PlatformAPI.search("carts");
                    final List<PlatformResult> deleted = PlatformAPI.search("cars");
                    assertTrue(awaitDone("cars"));
                    assertEquals(1, deleted.size());
                    assertEquals(2, stub.probes.get(), "The finished 'cars' search stays cached while 'carts' was discarded before probing");
                    return;
                } catch (final AssertionError e) {
                    failure = e;
                }
            }
            throw failure;
        }

        @Test
        @DisplayName("A blank query leaves the running search alone")
        void testBlankQueryDoesNotDiscard() {
            // BLOCKS BRIEFLY SO THE FIRST SEARCH IS STILL IN-FLIGHT
            final StubPlatform stub = new StubPlatform(1, 300);
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("wolves");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, 3_000), "The probe must start");
            assertTrue(PlatformAPI.search("   ").isEmpty(), "The blank query is a no-op");
            assertTrue(PlatformAPI.searching("wolves"), "A no-op must not discard the search in flight");
            assertTrue(awaitDone("wolves"), "The in-flight search must complete");
            assertEquals(1, first.size());
        }
    }

    // EMITS `count` MARKER HITS PER SEARCH WHATEVER LIMIT IT IS ASKED, REMEMBERING THAT LIMIT; A NON-NULL `blockMs` SLEEPS
    // (INTERRUPTIBLY) TO STAY IN-FLIGHT
    private static final class StubPlatform implements Platform {
        private final int count;
        private final Integer blockMs;
        private final AtomicInteger probes = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();
        private volatile int lastLimit;

        StubPlatform(final int count, final Integer blockMs) {
            this.count = count;
            this.blockMs = blockMs;
        }

        @Override public String name() { return "STUB"; }
        @Override public Resolution resolve(final URI uri) { return null; }

        @Override
        public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
            this.probes.incrementAndGet();
            this.lastLimit = limit;
            try {
                if (this.blockMs != null && !ThreadTool.sleep(this.blockMs)) throw new PlatformException("STUB", "interrupted");
                final List<PlatformResult> out = new ArrayList<>(this.count);
                for (int i = 0; i < this.count; i++) {
                    out.add(new PlatformResult(this, query + "#" + i, null, URI.create("https://stub.test/" + i)));
                }
                return out;
            } finally {
                this.finished.incrementAndGet();
            }
        }
    }
}
