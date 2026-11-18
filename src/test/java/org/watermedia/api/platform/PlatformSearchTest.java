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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PlatformAPI#search(String, int, boolean)} offline with stub platforms: the live read-only result list
 * and {@link PlatformAPI#searching(String)}, the history (newest first, no duplicates, capped), the cache (hits while
 * filling and after, re-search, empty results, registry changes), the per-platform limit under its configured ceiling,
 * the typing delay and the discard of the search in flight.
 * <p>
 * The registries are package-private statics and the search state is private, reached through reflection: every case
 * starts with both registries empty and a clean search state, and puts the registries back when it ends.
 */
@DisplayName("PlatformAPI search")
public class PlatformSearchTest {
    private static final long DONE_MS = 3_000L;
    private static final long TYPING_DELAY_MS = 300L; // PlatformAPI.SEARCH_TYPING_DELAY_MS
    private static final long LATE_HITS_MS = 500L; // HOW LONG A RELEASED PROBE OF A DISCARDED SEARCH GETS TO PUBLISH ITS HITS
    private static final int ATTEMPTS = 3;
    private static final long RETRY_PAUSE_MS = 5_000L;

    // HELD PROBES WAIT ON IT; restore() OPENS IT, SO A FAILED CASE NEVER LEAVES A SEARCH WORKER BLOCKED
    private final CountDownLatch release = new CountDownLatch(1);
    private List<Platform> savedBuiltin;
    private List<Platform> savedCustom;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void isolate() throws Exception {
        // ONLY THE STUBS OF THE CASE ARE PROBED: NO REAL PLATFORM TOUCHES THE NETWORK
        this.savedBuiltin = new ArrayList<>(PlatformAPI.BUILTIN);
        this.savedCustom = new ArrayList<>(PlatformAPI.CUSTOM);
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        resetSearchState();
    }

    @AfterEach
    void restore() throws Exception {
        this.release.countDown();
        PlatformAPI.BUILTIN.clear();
        PlatformAPI.CUSTOM.clear();
        PlatformAPI.BUILTIN.addAll(this.savedBuiltin);
        PlatformAPI.CUSTOM.addAll(this.savedCustom);
        resetSearchState();
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // A STUB OF ONE HIT WHOSE PROBES OF query STAY IN FLIGHT UNTIL THE CASE OPENS release
    private StubPlatform holding(final String query) {
        return new StubPlatform(1, query, this.release);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static boolean awaitDone(final String query) {
        return PlayerWait.awaitCondition(() -> !PlatformAPI.searching(query), DONE_MS);
    }

    // DISCARDS THE SEARCH IN FLIGHT AND CLEARS HISTORY, THE RESULT CACHE AND THE CACHE-SWEEP CLOCK; THE NEW GENERATION
    // KEEPS A LATE PROBE OF AN EARLIER CASE FROM WRITING
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
        @DisplayName("A blank query returns an empty list and is neither in flight nor remembered")
        void blankQueryIsNoOp() {
            assertTrue(PlatformAPI.search("   ").isEmpty(), "A blank query returns an empty list");
            assertFalse(PlatformAPI.searching("   "), "A blank query is never in flight");
            assertTrue(PlatformAPI.searchHistory().isEmpty(), "A blank query is not worth remembering");
        }

        @Test
        @DisplayName("History keeps the newest query first without duplicates, capped at 10")
        void historyDedupsAndCaps() {
            PlatformAPI.search("alpha");
            PlatformAPI.search("beta");
            PlatformAPI.search("alpha");
            assertEquals(List.of("alpha", "beta"), PlatformAPI.searchHistory(), "A repeated query moves to the front instead of twice");

            // 15 DISTINCT QUERIES KEEP THE 10 MOST RECENT (q15 TO q6)
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
        @DisplayName("The list fills off-thread, is read-only and keeps a platform over the limit to the limit")
        void listFillsCappedPerPlatform() {
            // MORE HITS THAN THE CEILING WHATEVER IT IS ASKED; THE API KEEPS THE LIMIT
            final int ceiling = WaterMediaConfig.platforms.searchLimit;
            PlatformAPI.register(new StubPlatform(ceiling + 3));
            final List<PlatformResult> results = PlatformAPI.search("lions");
            assertTrue(awaitDone("lions"), "Search must complete");
            assertEquals(ceiling, results.size(), "Hits beyond the per-platform limit must be dropped");
            assertThrows(UnsupportedOperationException.class, results::clear);
        }

        @Test
        @DisplayName("The limit reaches the platform under the configured ceiling, keys the cache and rejects zero")
        void limitReachesPlatformUnderCeiling() {
            final int ceiling = WaterMediaConfig.platforms.searchLimit;
            final StubPlatform stub = new StubPlatform(ceiling + 3);
            PlatformAPI.register(stub);
            assertEquals("(wm_err:2010) Failed to set the limit due to a value below 1",
                    assertThrows(IllegalArgumentException.class, () -> PlatformAPI.search("tigers", 0, false)).getMessage());

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
        void identicalQueryIsServedFromCache() {
            final StubPlatform stub = PlatformSearchTest.this.holding("dogs");
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("dogs");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, DONE_MS), "The probe must start");
            final List<PlatformResult> during = PlatformAPI.search("dogs");
            assertTrue(PlatformAPI.searching("dogs"), "The query in flight stays in flight");
            assertFalse(PlatformAPI.searching("cats"), "Only the query in flight is searching");
            assertSame(first, during, "The query in flight is served its own list");
            PlatformSearchTest.this.release.countDown();
            assertTrue(awaitDone("dogs"), "Search must complete");
            assertEquals(1, first.size());

            final List<PlatformResult> again = PlatformAPI.search("dogs");
            assertFalse(PlatformAPI.searching("dogs"), "A cache hit does not search");
            assertSame(first, again, "The cached results are the same list");
            assertEquals(1, stub.probes.get(), "A cache hit must not re-probe the platform");
        }

        @Test
        @DisplayName("reSearch drops the cached results and probes the platforms again")
        void reSearchProbesAgain() {
            final StubPlatform stub = new StubPlatform(1);
            PlatformAPI.register(stub);
            final List<PlatformResult> first = PlatformAPI.search("owls");
            assertTrue(awaitDone("owls"), "Search must complete");
            assertSame(first, PlatformAPI.search("owls"), "Without reSearch the cached list is served");
            assertEquals(1, stub.probes.get());

            final List<PlatformResult> fresh = PlatformAPI.search("owls", true);
            assertNotSame(first, fresh, "A re-search fills a new list");
            assertTrue(awaitDone("owls"), "The re-search must complete");
            assertEquals(2, stub.probes.get(), "A re-search asks the platform again");
            assertEquals(1, fresh.size());
        }

        @Test
        @DisplayName("An empty result is not cached, so the next call asks again")
        void emptyResultIsNotCached() {
            final StubPlatform stub = new StubPlatform(0);
            PlatformAPI.register(stub);
            PlatformAPI.search("nothing");
            assertTrue(awaitDone("nothing"), "Search must complete");
            PlatformAPI.search("nothing");
            assertTrue(awaitDone("nothing"), "Search must complete");
            assertEquals(2, stub.probes.get(), "A transient total failure must retry instead of serving stale nothing");
        }

        @Test
        @DisplayName("A registration during a search keeps that search out of the cache")
        void registrationDuringSearchIsNotCached() {
            final StubPlatform stub = PlatformSearchTest.this.holding("race");
            PlatformAPI.register(stub);
            final List<PlatformResult> first = PlatformAPI.search("race");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, DONE_MS), "The probe must start");
            PlatformAPI.register(new StubPlatform(1));
            PlatformSearchTest.this.release.countDown();
            assertTrue(awaitDone("race"), "Search must complete");
            assertEquals(1, first.size(), "The running search keeps its snapshot of the registry");

            final List<PlatformResult> again = PlatformAPI.search("race");
            assertTrue(awaitDone("race"), "Search must complete");
            assertEquals(2, again.size(), "The query is probed again with the new handler instead of served from cache");
        }

        @Test
        @DisplayName("Registering a handler drops the cached searches")
        void registrationDropsCachedSearches() {
            final StubPlatform stub = new StubPlatform(1);
            PlatformAPI.register(stub);
            PlatformAPI.search("birds");
            assertTrue(awaitDone("birds"), "Search must complete");

            PlatformAPI.register(new StubPlatform(1));
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
        @DisplayName("A new query discards the search in flight: its list never gains the late hits and its query leaves the cache")
        void newQueryDiscardsSearchInFlight() {
            final StubPlatform stub = PlatformSearchTest.this.holding("cats");
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("cats");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, DONE_MS), "The probe must start");
            assertTrue(PlatformAPI.searching("cats"), "The held probe keeps the search in flight");

            // NOTHING ALIKE, SO NO TYPING DELAY: THE NEW QUERY PROBES WHILE THE DISCARDED PROBE IS STILL HELD
            final List<PlatformResult> second = PlatformAPI.search("dogs");
            assertTrue(awaitDone("dogs"), "The new search must complete");
            assertEquals(1, second.size());
            assertEquals(2, stub.probes.get(), "The new query was probed at once");

            // THE DISCARDED PROBE IGNORED ITS INTERRUPT AND ANSWERS NOW: ONLY THE GENERATION GUARD KEEPS ITS HIT OUT OF first
            PlatformSearchTest.this.release.countDown();
            assertTrue(PlayerWait.awaitCondition(() -> stub.answered.get() == 2, DONE_MS), "The discarded probe must answer once released");
            assertFalse(PlayerWait.awaitCondition(() -> !first.isEmpty(), LATE_HITS_MS), "A discarded search never gains the hits of its late probes");

            final List<PlatformResult> again = PlatformAPI.search("cats");
            assertNotSame(first, again, "The discarded search must leave the cache");
            assertTrue(awaitDone("cats"), "The repeated search must complete");
            assertEquals(1, again.size());
            assertEquals(3, stub.probes.get(), "The discarded query is probed again");
        }

        @Test
        @DisplayName("A query one keystroke from the last one waits the typing delay, and a step discarded during it never probes and leaves the cache")
        void typedQueryWaitsForTheNextKey() throws Exception {
            // TIMED AGAINST THE TYPING DELAY: A STALLED JVM GETS TWO MORE TRIES BEFORE THE CASE FAILS (OWNER RULE)
            AssertionError failure = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (attempt > 1) {
                    PlatformAPI.CUSTOM.clear();
                    resetSearchState();
                    ThreadTool.sleep(RETRY_PAUSE_MS);
                }
                try {
                    final StubPlatform stub = new StubPlatform(1);
                    PlatformAPI.register(stub);
                    PlatformAPI.search("cats");
                    assertTrue(awaitDone("cats"), "The first query probes at once");
                    assertEquals(1, stub.probes.get());

                    // ONE LETTER FIXED: THE SAME INTENTION STILL BEING TYPED
                    final long started = System.nanoTime();
                    final List<PlatformResult> typed = PlatformAPI.search("cars");
                    assertTrue(PlatformAPI.searching("cars"), "The delayed search counts as in flight");
                    assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 2, DONE_MS), "The delayed probe must run");
                    assertTrue(stub.probedAt - started >= TimeUnit.MILLISECONDS.toNanos(TYPING_DELAY_MS), "The probe must wait out the typing delay");
                    assertTrue(awaitDone("cars"), "The delayed search must complete");
                    assertEquals(1, typed.size());

                    // THE NEXT STEP IS DISCARDED BEFORE ITS DELAY ENDS, BY THE FINISHED 'cars' SERVED FROM THE CACHE
                    final List<PlatformResult> step = PlatformAPI.search("carts");
                    assertSame(typed, PlatformAPI.search("cars"), "The finished 'cars' search is served from the cache");
                    assertFalse(PlayerWait.awaitCondition(() -> stub.probes.get() > 2, 2 * TYPING_DELAY_MS), "The discarded 'carts' step must never probe");
                    assertTrue(step.isEmpty(), "The discarded step gains no hits");

                    final List<PlatformResult> again = PlatformAPI.search("carts");
                    assertNotSame(step, again, "The discarded step must leave the cache");
                    assertTrue(awaitDone("carts"), "The repeated step must complete");
                    assertEquals(1, again.size());
                    assertEquals(3, stub.probes.get(), "Only the repeated step probes");
                    return;
                } catch (final AssertionError e) {
                    failure = e;
                }
            }
            throw failure;
        }

        @Test
        @DisplayName("A blank query leaves the running search alone")
        void blankQueryKeepsSearchInFlight() {
            final StubPlatform stub = PlatformSearchTest.this.holding("wolves");
            PlatformAPI.register(stub);

            final List<PlatformResult> first = PlatformAPI.search("wolves");
            assertTrue(PlayerWait.awaitCondition(() -> stub.probes.get() == 1, DONE_MS), "The probe must start");
            assertTrue(PlatformAPI.search("   ").isEmpty(), "The blank query is a no-op");
            assertTrue(PlatformAPI.searching("wolves"), "A no-op must not discard the search in flight");
            PlatformSearchTest.this.release.countDown();
            assertTrue(awaitDone("wolves"), "The search in flight must complete");
            assertEquals(1, first.size());
        }
    }

    // ANSWERS count MARKER HITS PER SEARCH WHATEVER LIMIT IT IS ASKED, REMEMBERING THAT LIMIT AND WHEN IT WAS PROBED. A
    // PROBE OF held WAITS FOR release DEAF TO INTERRUPTS, LIKE A BLOCKING READ THAT ANSWERS AFTER A DISCARD
    private static final class StubPlatform implements Platform {
        private final int count;
        private final String held;
        private final CountDownLatch release;
        private final AtomicInteger probes = new AtomicInteger();
        private final AtomicInteger answered = new AtomicInteger();
        private volatile int lastLimit;
        private volatile long probedAt;

        StubPlatform(final int count) { this(count, null, null); }

        StubPlatform(final int count, final String held, final CountDownLatch release) {
            this.count = count;
            this.held = held;
            this.release = release;
        }

        @Override public String name() { return "STUB"; }
        @Override public Resolution resolve(final URI uri) { return null; }

        @Override
        public List<PlatformResult> search(final String query, final int limit) {
            // WRITTEN BEFORE THE COUNT, SO A CASE THAT SAW THE COUNT SEES THEM TOO
            this.probedAt = System.nanoTime();
            this.lastLimit = limit;
            this.probes.incrementAndGet();
            if (query.equals(this.held)) {
                boolean interrupted = false;
                while (this.release.getCount() > 0) {
                    try { this.release.await(); }
                    catch (final InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            final List<PlatformResult> out = new ArrayList<>(this.count);
            for (int i = 0; i < this.count; i++) out.add(new PlatformResult(this, query + "#" + i, null, URI.create("https://stub.test/" + i)));
            this.answered.incrementAndGet();
            return out;
        }
    }
}
