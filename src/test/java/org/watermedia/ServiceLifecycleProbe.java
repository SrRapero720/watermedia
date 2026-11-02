package org.watermedia;

import me.srrapero720.waterconfig.WaterConfig;
import org.watermedia.api.network.NetworkAPI;
import org.watermedia.api.player.MRL;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.PlatformResult;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.PlayerWait;
import org.watermedia.tools.ThreadTool;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the loaded services in a fresh JVM: searches, uploads and the MRL cache sweep. */
public final class ServiceLifecycleProbe {
    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    public static void main(final String[] arguments) throws Exception {
        final Path root = Path.of("").toAbsolutePath();
        WaterConfig.setPath(root.resolve("config"));
        WaterMediaConfig.codecs.ffmpeg.disable = true;
        WaterMediaConfig.network.server = WaterMediaConfig.Network.Server.OFF;
        WaterMediaConfig.network.timeout = 2_000;
        final boolean client = !arguments[0].equals("uploads");
        WaterMedia.start("SERVICE_TEST", root.resolve("temp"), root, client);
        switch (arguments[0]) {
            case "platform" -> platforms();
            case "uploads" -> uploads(root);
            case "evicted" -> evicted();
            case "failed-probe" -> failedProbe();
            default -> throw new IllegalArgumentException("Unknown scenario: " + arguments[0]);
        }
        System.out.println("PROBE_OK " + arguments[0]);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static void evicted() throws Exception {
        for (final Platform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final URI key = URI.create("fixture:expired");
        final AtomicBoolean first = new AtomicBoolean(true);
        PlatformAPI.register(new Platform() {
            @Override public String name() { return "Expiring fixture"; }
            @Override public Resolution resolve(final URI uri) {
                final var expires = key.equals(uri) && first.getAndSet(false) ? Instant.EPOCH : null;
                return new Resolution(expires, new Source(MediaType.VIDEO, null, null, new HttpHeaders(),
                        List.of(new Quality(uri, 0, 0)), null, null));
            }
        });
        final MRL old = PlayerAPI.mrl(key);
        if (!old.await(3_000) || old.status() != MRL.Status.EXPIRED || old.sourceCount() != 1)
            throw new AssertionError("Expiring fixture did not load");
        awaitIdle(old);
        final var ownerField = MRL.class.getDeclaredField("owner");
        ownerField.setAccessible(true);
        final Object owner = ownerField.get(old);
        final var cleanupField = owner.getClass().getDeclaredField("nextClean");
        cleanupField.setAccessible(true);
        synchronized (owner) { cleanupField.setLong(owner, 0); }
        final MRL sweep = PlayerAPI.mrl(URI.create("fixture:cleanup"));
        if (!sweep.await(3_000) || old.status() != MRL.Status.FORGOTTEN || old.sourceCount() != 0)
            throw new AssertionError("Cleanup did not invalidate the expired handle");
        final MRL replacement = old.reload();
        if (replacement == old || !replacement.await(3_000) || replacement.status() != MRL.Status.LOADED
                || replacement != PlayerAPI.mrl(key) || old.status() != MRL.Status.FORGOTTEN || old.sourceCount() != 0)
            throw new AssertionError("Reload did not return the canonical replacement");
    }

    private static void awaitIdle(final MRL mrl) throws Exception {
        final var lockField = MRL.class.getDeclaredField("loadLock");
        final var loadingField = MRL.class.getDeclaredField("loading");
        lockField.setAccessible(true);
        loadingField.setAccessible(true);
        final Object lock = lockField.get(mrl);
        if (!PlayerWait.awaitCondition(() -> {
            synchronized (lock) {
                try { return !loadingField.getBoolean(mrl); }
                catch (final IllegalAccessException failure) { throw new AssertionError(failure); }
            }
        }, 3_000)) throw new AssertionError("Loader did not become idle");
    }

    private static void platforms() throws Exception {
        for (final Platform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final CountDownLatch resolveEntered = new CountDownLatch(1);
        final CountDownLatch resolveResume = new CountDownLatch(1);
        final AtomicBoolean callbackGuard = new AtomicBoolean();
        try {
            final Platform controlled = new Platform() {
                @Override public String name() { return "Controlled search"; }
                @Override public Resolution resolve(final URI uri) {
                    if (!"fixture".equals(uri.getScheme())) return null;
                    resolveEntered.countDown();
                    boolean done = false;
                    while (!done) {
                        try { done = resolveResume.await(10, TimeUnit.SECONDS); }
                        catch (final InterruptedException ignored) {}
                    }
                    return new Resolution(null, new Source(MediaType.VIDEO, null, null, new HttpHeaders(),
                            List.of(new Quality(uri, 0, 0)), null, null));
                }
                @Override public List<PlatformResult> search(final String query, final int limit) {
                    if (!ThreadTool.workerThread()) throw new AssertionError("Search callback is not marked as an owned worker");
                    if (query.equals("old")) {
                        entered.countDown();
                        boolean done = false;
                        while (!done) {
                            try { done = resume.await(10, TimeUnit.SECONDS); }
                            catch (final InterruptedException ignored) {}
                        }
                        returned.countDown();
                    } else if (query.equals("fresh")) {
                        try {
                            WaterMedia.start("SERVICE_TEST", null, null, true);
                            throw new AssertionError("Worker initiated a load");
                        } catch (final IllegalStateException expected) {
                            // THE WORKER GUARD ANSWERS BEFORE THE ALREADY-STARTED CHECK
                            callbackGuard.set(expected.getMessage().startsWith("Start WaterMedia from a host thread"));
                        }
                    }
                    return List.of(new PlatformResult(this, query, null, URI.create("https://fixture.invalid/" + query)));
                }
            };
            PlatformAPI.register(controlled);
            final List<PlatformResult> old = PlatformAPI.search("old");
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Old probe did not enter");
            final List<PlatformResult> fresh = PlatformAPI.search("fresh");
            if (!PlayerWait.awaitCondition(() -> !PlatformAPI.searching("fresh"), 3_000)) throw new AssertionError("New search was blocked behind a cancelled probe");
            if (!callbackGuard.get() || fresh.size() != 1 || !fresh.get(0).title().equals("fresh"))
                throw new AssertionError("Fresh search has incorrect results");
            if (!PlatformAPI.searchHistory().equals(List.of("fresh", "old")))
                throw new AssertionError("Search history did not record the queries newest first");
            // A HELD RESOLUTION NEVER GATES THE SEARCH SERVICE: THERE IS NO SESSION TO CLOSE AROUND IT
            final MRL held = PlayerAPI.mrl(URI.create("fixture:held"));
            if (!resolveEntered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Resolver did not enter");
            final List<PlatformResult> during = PlatformAPI.search("during-resolve");
            if (!PlayerWait.awaitCondition(() -> !PlatformAPI.searching("during-resolve"), 3_000) || during.size() != 1)
                throw new AssertionError("A held resolution blocked the search service");
            resolveResume.countDown();
            if (!held.await(3_000) || held.status() != MRL.Status.LOADED) throw new AssertionError("Held resolution did not finish");
            // THE DISCARDED SEARCH NEVER PUBLISHES, EVEN ONCE ITS PROBE FINALLY RETURNS
            if (!old.isEmpty()) throw new AssertionError("The discarded search gained results");
            resume.countDown();
            if (!returned.await(3, TimeUnit.SECONDS)) throw new AssertionError("Old probe did not return");
            if (PlayerWait.awaitCondition(() -> !old.isEmpty(), 250))
                throw new AssertionError("Late old results escaped their generation");
            // A REGISTRY CHANGE DROPS THE CACHED SEARCHES; THE HISTORY KEEPS EVERY QUERY, NEWEST FIRST
            for (final Platform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
            PlatformAPI.register(new Platform() {
                @Override public String name() { return "Replacement search"; }
                @Override public Resolution resolve(final URI uri) { return null; }
                @Override public List<PlatformResult> search(final String query, final int limit) {
                    return List.of(new PlatformResult(this, "replacement", null, URI.create("https://fixture.invalid/new")));
                }
            });
            final List<PlatformResult> again = PlatformAPI.search("fresh");
            if (!PlayerWait.awaitCondition(() -> !PlatformAPI.searching("fresh"), 3_000) || again.size() != 1
                    || !again.get(0).title().equals("replacement") || !PlatformAPI.searchHistory().equals(List.of("fresh", "during-resolve", "old")))
                throw new AssertionError("Search cache survived the registry change or the history lost a query");
        } finally {
            resolveResume.countDown();
            resume.countDown();
        }
    }

    // ONE PROBE FAILS (AND ITS NAME BREAKS ON THE SECOND CALL) WHILE A SLOW ONE SUCCEEDS LATE: THE SEARCH WAITS FOR THE
    // SLOW PROBE, KEEPS ITS RESULT AND CACHES THE FULL SET
    private static void failedProbe() throws Exception {
        for (final Platform platform: PlatformAPI.platforms()) PlatformAPI.unregister(platform);
        final CountDownLatch slowEntered = new CountDownLatch(1);
        final CountDownLatch releaseSlow = new CountDownLatch(1);
        final AtomicInteger successfulProbes = new AtomicInteger();
        PlatformAPI.register(new Platform() {
            @Override public String name() { return "Slow fixture"; }
            @Override public Resolution resolve(final URI uri) { return null; }
            @Override public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
                successfulProbes.incrementAndGet();
                slowEntered.countDown();
                try {
                    if (!releaseSlow.await(5, TimeUnit.SECONDS)) throw new PlatformException("Slow fixture", "not released");
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new PlatformException("Slow fixture", "interrupted");
                }
                return List.of(new PlatformResult(this, "late success", null, URI.create("https://fixture.invalid/success")));
            }
        });
        final AtomicInteger names = new AtomicInteger();
        PlatformAPI.register(new Platform() {
            @Override public String name() {
                if (names.incrementAndGet() > 1) throw new IllegalStateException("Broken diagnostic name");
                return "Failing fixture";
            }
            @Override public Resolution resolve(final URI uri) { return null; }
            @Override public List<PlatformResult> search(final String query, final int limit) throws PlatformException {
                throw new PlatformException("Failing fixture", "Fixture probe failed");
            }
        });
        try {
            final List<PlatformResult> search = PlatformAPI.search("failure contract");
            if (!slowEntered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Second probe did not start");
            // THE FAILING PROBE ANSWERED AT ONCE, BUT THE SEARCH STAYS OPEN UNTIL THE SLOW ONE RETURNS
            if (PlayerWait.awaitCondition(() -> !PlatformAPI.searching("failure contract"), 250))
                throw new AssertionError("Search completed while another probe was still running");
            releaseSlow.countDown();
            if (!PlayerWait.awaitCondition(() -> !PlatformAPI.searching("failure contract"), 3_000) || search.size() != 1
                    || !search.get(0).title().equals("late success"))
                throw new AssertionError("Successful late probe was lost");
            final List<PlatformResult> cached = PlatformAPI.search("failure contract");
            if (PlatformAPI.searching("failure contract") || cached.size() != 1 || !cached.get(0).title().equals("late success")
                    || successfulProbes.get() != 1)
                throw new AssertionError("Completed search did not cache its full result set");
        } finally {
            releaseSlow.countDown();
        }
    }

    private static void uploads(final Path root) throws Exception {
        final CountDownLatch entered = new CountDownLatch(4);
        final CountDownLatch respond = new CountDownLatch(1);
        final var handlers = Executors.newFixedThreadPool(4, ThreadTool.factory("Upload-Origin", Thread.NORM_PRIORITY));
        final Path file = Files.write(root.resolve("upload.bin"), new byte[8_192]);
        final boolean handlersStopped;
        try (final LocalHttp origin = LocalHttp.start("/upload", exchange -> {
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try { if (!respond.await(5, TimeUnit.SECONDS)) throw new IOException("Held upload timed out"); }
            catch (final InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { LocalHttp.respond(exchange, "text/plain", "stored12".getBytes(StandardCharsets.UTF_8), 0); }
            catch (final IOException closed) { exchange.close(); }
        }, handlers)) {
            // A BAD REMOTE FAILS THE STATUS INSTEAD OF THROWING, SO A MISCONFIGURED HOST NEVER CRASHES ITS CALLER
            WaterMediaConfig.network.remoteUrl = "remote.invalid/upload";
            final NetworkAPI.UploadStatus misconfigured = NetworkAPI.upload(file.toFile());
            if (!PlayerWait.awaitCondition(misconfigured::failed, 3_000) || misconfigured.completed()
                    || misconfigured.error() == null || !misconfigured.error().contains("remoteUrl"))
                throw new AssertionError("A misconfigured remote did not fail the upload: " + misconfigured.error());
            WaterMediaConfig.network.remoteUrl = origin.uri("").toString();
            try {
                NetworkAPI.upload(root.resolve("missing.bin").toFile());
                throw new AssertionError("A missing file was accepted for upload");
            } catch (final IllegalArgumentException expected) {}
            final NetworkAPI.UploadStatus[] statuses = new NetworkAPI.UploadStatus[12];
            for (int i = 0; i < statuses.length; i++) statuses[i] = NetworkAPI.upload(file.toFile());
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Upload workers did not start");
            // FOUR WORKERS SERVE THE QUEUE; THE WAITING UPLOADS STAY PENDING INSTEAD OF FAILING
            for (final var status: statuses) {
                if (status.failed() || status.completed()) throw new AssertionError("A queued upload ended before the server answered");
            }
            respond.countDown();
            for (final var status: statuses) {
                if (!PlayerWait.awaitCondition(() -> status.completed() || status.failed(), 10_000) || !status.completed() || !"stored12".equals(status.id()))
                    throw new AssertionError("Upload failed: " + status.error());
            }
        } finally {
            respond.countDown();
            handlers.shutdownNow();
            handlersStopped = handlers.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (!handlersStopped) throw new AssertionError("Upload handlers did not stop");
    }
}
