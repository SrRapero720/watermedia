package org.watermedia.test.support;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback origin serving one body under every path; {@link #hold()} keeps later requests in flight until {@link #release()}. */
public final class Origin implements AutoCloseable {
    private static final long HOLD_LIMIT_MS = 30_000L; // A HELD REQUEST NEVER OUTLIVES A FAILED TEST BY MORE

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();
    private final LocalHttp http;
    private CountDownLatch gate = new CountDownLatch(0);     // GUARDED BY this
    private CountDownLatch entered = new CountDownLatch(1);  // GUARDED BY this

    private Origin(final byte[] body, final String type, final boolean endless) {
        this.http = LocalHttp.start("/", exchange -> {
            this.requests.incrementAndGet();
            // BOTH LATCHES ARE READ TOGETHER, SO A hold() NEVER PAIRS A NEW ARRIVAL LATCH WITH AN OLD, OPEN GATE
            final CountDownLatch admission;
            final CountDownLatch arrival;
            synchronized (this) {
                admission = this.gate;
                arrival = this.entered;
            }
            arrival.countDown();
            try (exchange) {
                if (!admission.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)) throw new IOException("Held request outlived its test");
                if (!endless) {
                    LocalHttp.respondRange(exchange, type, body);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, 0); // CHUNKED: NO Content-Length AND NO RANGES, LIKE A LIVE FEED
                exchange.getResponseBody().write(body);
                exchange.getResponseBody().flush();
                if (!this.closed.await(HOLD_LIMIT_MS, TimeUnit.MILLISECONDS)) throw new IOException("Endless feed outlived its test");
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, this.workers);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    /** Creates an origin answering every request with {@code body}, honouring byte ranges like a media CDN. */
    public static Origin of(final byte[] body, final String type) { return new Origin(body, type, false); }

    /** Creates an origin that sends {@code body} chunked, without a length, and never finishes the response. */
    public static Origin endless(final byte[] body, final String type) { return new Origin(body, type, true); }

    /** Returns a fresh path per call, so every caller gets its own MRL and cache key. */
    public URI uri(final String extension) { return this.http.uri("/clip-" + System.nanoTime() + extension); }

    /** Parks every later request before its headers until {@link #release()}; {@link #awaitEntered} answers once the first arrives. */
    public synchronized void hold() {
        this.entered = new CountDownLatch(1);
        this.gate = new CountDownLatch(1);
    }

    /** Waits until a request reaches the origin after the last {@link #hold()}. */
    public boolean awaitEntered(final long timeoutMs) throws InterruptedException {
        final CountDownLatch arrival;
        synchronized (this) { arrival = this.entered; }
        return arrival.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Lets the parked requests answer. */
    public synchronized void release() { this.gate.countDown(); }

    /** Returns the number of requests the origin received. */
    public int requests() { return this.requests.get(); }

    @Override
    public void close() {
        this.release();
        this.closed.countDown();
        this.http.close();
        this.workers.shutdownNow();
    }
}
