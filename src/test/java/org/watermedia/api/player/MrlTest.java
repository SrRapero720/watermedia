package org.watermedia.api.player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.WaterMedia;
import org.watermedia.api.platform.Platform;
import org.watermedia.api.platform.Platform.Quality;
import org.watermedia.api.platform.Platform.Source;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.util.HttpHeaders;
import org.watermedia.api.util.MediaQuality;
import org.watermedia.api.util.MediaType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.Origin;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the {@link MRL} contract: readiness and ERROR states, the cache, subscriptions, local and IPTV sources, the sniffing of platform sources, quality buckets, frozen headers and quality relabels. */
@DisplayName("MRL")
public class MrlTest {

    private static final long TIMEOUT_MS = 5_000L;

    // OPAQUE NAME WITHOUT EXTENSION, LIKE A NETWORK-SERVER ID URL, SO ONLY THE LEADING-BYTE SNIFF CAN CLASSIFY IT;
    // COPIED ONCE INTO build/ BECAUSE A PER-TEST @TempDir DELETE OF A JUST-WRITTEN FILE FLAKES AGAINST WINDOWS AV SCANS
    private static Path opaque;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void clientBootstrap() throws IOException {
        MediaBootstrap.client();
        opaque = Path.of("build", "test-sniff", "8DuZ3dat");
        Files.createDirectories(opaque.getParent());
        Files.copy(Fixtures.PNG_BROKEN, opaque, StandardCopyOption.REPLACE_EXISTING);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static MRL settled(final MRL mrl) {
        assertTrue(mrl.await(TIMEOUT_MS), () -> mrl.uri() + " is still fetching");
        return mrl;
    }

    // A PLATFORM SOURCE OF UNKNOWN TYPE, LIKE water:// OR MEDIAFIRE HAND OUT
    private static Source unknown(final URI target) {
        return new Source(MediaType.UNKNOWN, null, null, HttpHeaders.defaults(target), List.of(new Quality(target, 0, 0)), null, null);
    }

    // RESOLVES claimed THROUGH A MINIMAL HANDLER THAT CLAIMS EXACTLY THAT URI, LIKE WaterPlatform DOES FOR water://
    private static MRL resolve(final URI claimed, final Source... entries) {
        final Platform platform = new Platform() {
            @Override
            public String name() {
                return "MRL Test";
            }

            @Override
            public Resolution resolve(final URI uri) {
                return claimed.equals(uri) ? new Resolution(null, List.of(entries)) : null;
            }
        };
        PlatformAPI.register(platform);
        try {
            final MRL mrl = settled(PlayerAPI.mrl(claimed));
            assertSame(MRL.Status.LOADED, mrl.status(), () -> claimed + " did not load: " + mrl.exception());
            return mrl;
        } finally {
            PlatformAPI.unregister(platform);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Readiness")
    class Readiness {

        @Test
        @DisplayName("A missing file ends its load in ERROR")
        void missingFileEndsInError() {
            final MRL mrl = settled(PlayerAPI.mrl(URI.create("file:///nonexistent/abc.png")));
            // ERROR, NOT BLOCKED: THE FILE FAILED, NO SITE LIST REFUSED IT
            assertSame(MRL.Status.ERROR, mrl.status(), () -> "the load ended with " + mrl.exception());
        }

        @Test
        @DisplayName("A malformed URI string yields an MRL born in ERROR instead of throwing")
        void malformedStringIsBornInError() {
            // A SPACE IN THE AUTHORITY MAKES URI.create THROW, WHICH mrl(String) ABSORBS
            final MRL mrl = PlayerAPI.mrl("http://exa mple.com/bad path");
            assertNotNull(mrl);
            assertSame(MRL.Status.ERROR, mrl.status());
            assertTrue(mrl.status().failed(), "ERROR counts as failed");
            assertNotNull(mrl.exception(), "the parse failure is kept");
        }

        @Test
        @DisplayName("Empty inputs and relative inputs naming no existing file are born in ERROR, never cached, and reload to themselves")
        void relativeInputsAreBornInError() {
            for (final String input: new String[] { "", "missing-file.png", "relative/path.mp4" }) {
                final MRL mrl = PlayerAPI.mrl(input);
                assertSame(MRL.Status.ERROR, mrl.status(), () -> "'" + input + "' is born in ERROR");
                assertNotNull(mrl.exception(), () -> "'" + input + "' keeps its failure");
                assertNotSame(mrl, PlayerAPI.mrl(input), () -> "'" + input + "' is never cached");
                assertSame(mrl, mrl.reload(), () -> "'" + input + "' reloads to itself");
            }
            final MRL relative = PlayerAPI.mrl(URI.create("abc.png"));
            assertSame(MRL.Status.ERROR, relative.status(), "a relative URI is born in ERROR");
            assertNotSame(relative, PlayerAPI.mrl(URI.create("abc.png")), "a relative URI is never cached");
            assertSame(relative, relative.reload(), "a relative URI reloads to itself");
        }

        @Test
        @DisplayName("A file path relative to the working directory loads and shares the cache entry of its normalized form")
        void relativeFilePathResolvesAgainstCwd() throws IOException {
            // UNDER build/ SO A SCANNER HOLDING THE FRESH FILE NEVER LEAVES JUNK IN THE PROJECT ROOT
            final Path file = WaterMedia.cwd().resolve("build/mrl-relative/test.png");
            Files.createDirectories(file.getParent());
            Files.copy(Fixtures.PNG_STATIC, file, StandardCopyOption.REPLACE_EXISTING);
            final MRL mrl = settled(PlayerAPI.mrl("build/mrl-relative/./test.png"));
            assertSame(MRL.Status.LOADED, mrl.status(), () -> "the file did not load: " + mrl.exception());
            assertSame(mrl, PlayerAPI.mrl("build/mrl-relative/test.png"), "the normalized path shares the cache entry");
        }

        @Test
        @DisplayName("The content-type gate ignores casing and parameters and rejects documents")
        void contentTypeGateRejectsDocuments() {
            try (final LocalHttp origin = LocalHttp.start("/", exchange -> {
                final String path = exchange.getRequestURI().getPath();
                final String type = path.endsWith("page") ? "text/html; charset=utf-8"
                        : path.endsWith("hls") ? "application/x-mpegURL" : "Video/MP4; codecs=\"avc1\"";
                LocalHttp.respond(exchange, type, new byte[] { 0, 0, 0, 0 }, 0L);
            })) {
                final MRL video = settled(PlayerAPI.mrl(origin.uri("/clip")));
                assertSame(MRL.Status.LOADED, video.status(), "a capitalized, parameterized video type passes");
                assertEquals(MediaType.VIDEO, video.source(0).type());

                final MRL hls = settled(PlayerAPI.mrl(origin.uri("/hls")));
                assertSame(MRL.Status.LOADED, hls.status(), "the Accept-header spelling of HLS passes");

                final MRL page = settled(PlayerAPI.mrl(origin.uri("/page")));
                assertSame(MRL.Status.ERROR, page.status(), "HTML documents are not media");
            }
        }
    }

    @Nested
    @DisplayName("Cache")
    class Cache {

        @Test
        @DisplayName("preload resolves the same instances that mrl returns for the same URIs")
        void preloadResolvesSameInstancesAsMrl() {
            final URI uriA = Fixtures.fileUri(Fixtures.PNG_STATIC);
            final URI uriB = Fixtures.fileUri(Fixtures.GIF_ANIMATED);

            final MRL[] preloaded = PlayerAPI.preload(uriA, uriB);

            assertEquals(2, preloaded.length);
            assertSame(preloaded[0], PlayerAPI.mrl(uriA), "mrl returns the first preloaded instance");
            assertSame(preloaded[1], PlayerAPI.mrl(uriB), "mrl returns the second preloaded instance");
        }
    }

    @Nested
    @DisplayName("Subscription")
    class Subscription {

        @Test
        @DisplayName("A listener subscribed while loading fires exactly once, on completion")
        void listenerFiresOnceOnCompletion() throws InterruptedException {
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.PNG_STATIC), "image/png")) {
                // THE ORIGIN PARKS THE LOAD BEFORE ITS ANSWER, SO THE LISTENER SUBSCRIBES WHILE THE MRL IS FETCHING
                origin.hold();
                final MRL mrl = PlayerAPI.mrl(origin.uri(".png"));
                final AtomicInteger count = new AtomicInteger();
                mrl.subscribe(m -> count.incrementAndGet());
                assertTrue(origin.awaitEntered(TIMEOUT_MS), "the load reached the origin");
                assertEquals(0, count.get(), "nothing fires while the request is held");
                origin.release();
                settled(mrl);
                assertTrue(PlayerWait.awaitCondition(() -> count.get() >= 1, TIMEOUT_MS), "the listener fired");
                assertEquals(1, count.get(), "the listener fired exactly once");
            }
        }

        @Test
        @DisplayName("A listener subscribed after the load fires immediately on the calling thread")
        void listenerFiresImmediatelyWhenReady() {
            final MRL mrl = settled(PlayerAPI.mrl(Fixtures.fileUri(Fixtures.PNG_DIR.resolve("4.png"))));

            final AtomicInteger count = new AtomicInteger();
            final Thread[] firedOn = new Thread[1];
            mrl.subscribe(m -> {
                firedOn[0] = Thread.currentThread();
                count.incrementAndGet();
            });

            assertEquals(1, count.get(), "the listener fired before subscribe returned");
            assertSame(Thread.currentThread(), firedOn[0], "the listener ran on the calling thread");
        }

        @Test
        @DisplayName("A throwing listener does not break the dispatch chain")
        void throwingListenerDoesNotBreakTheChain() {
            try (final Origin origin = Origin.of(Fixtures.readAll(Fixtures.PNG_STATIC), "image/png")) {
                origin.hold();
                final MRL mrl = PlayerAPI.mrl(origin.uri(".png"));
                final AtomicBoolean secondFired = new AtomicBoolean();
                mrl.subscribe(m -> { throw new RuntimeException("BOOM"); });
                mrl.subscribe(m -> secondFired.set(true));
                origin.release();
                settled(mrl);
                assertTrue(PlayerWait.awaitCondition(secondFired::get, TIMEOUT_MS), "the listener after the throwing one fired");
            }
        }
    }

    @Nested
    @DisplayName("Sources")
    class Sources {

        @Test
        @DisplayName("A local image exposes only IMAGE sources, alike through the first-match lookup and the full filter")
        void localImageOnlyExposesImageSources() {
            final MRL mrl = settled(PlayerAPI.mrl(Fixtures.fileUri(Fixtures.PNG_STATIC)));

            final MRL.Source image = mrl.sourceByType(MediaType.IMAGE);
            assertNotNull(image, () -> "no IMAGE source in " + mrl);
            assertNull(mrl.sourceByType(MediaType.VIDEO), "no VIDEO source");

            // THE FIRST-MATCH LOOKUP AND THE FULL FILTER MUST AGREE ON THE SAME OBJECT
            assertSame(image, mrl.sourcesByType(MediaType.IMAGE).get(0));
            assertEquals(0, mrl.sourcesByType(MediaType.VIDEO).size());
        }

        @Test
        @DisplayName("An IPTV playlist expands into one source per channel carrying the channel metadata")
        void iptvPlaylistExpandsPerChannel() {
            final MRL mrl = settled(PlayerAPI.mrl(Fixtures.fileUri(Fixtures.M3U_IPTV)));
            assertSame(MRL.Status.LOADED, mrl.status(), () -> "the playlist did not load: " + mrl.exception());
            assertEquals(3, mrl.sourceCount(), "one source per channel of the fixture");

            final MRL.Source first = mrl.source(0);
            assertNotNull(first.metadata());
            assertEquals("Channel One", first.metadata().title());
            // THE IPTV GROUP IS A CATEGORY, SO IT TRAVELS AS THE DESCRIPTION AND NOBODY IS CREDITED AS AUTHOR
            assertEquals("USA", first.metadata().description());
            assertNull(first.metadata().author());
            assertEquals("https://logo.example/one.png", String.valueOf(first.thumbnail()));
            // THE CHANNEL TYPE STAYS UNKNOWN AT MRL TIME; A PLAYER RESOLVES IT WHEN IT CONNECTS TO THE STREAM
            assertEquals(MediaType.UNKNOWN, first.type());
        }
    }

    @Nested
    @DisplayName("Platform classification")
    class PlatformClassification {

        @Test
        @DisplayName("A single UNKNOWN platform source is byte-sniffed to IMAGE")
        void singleUnknownSourceIsSniffed() {
            final MRL mrl = resolve(URI.create("watertest://remote/8DuZ3dat"), unknown(opaque.toUri()));
            assertEquals(1, mrl.sourceCount());
            assertEquals(MediaType.IMAGE, mrl.source(0).type());
        }

        @Test
        @DisplayName("The UNKNOWN sources of a multi-source resolution are never probed")
        void multiSourceUnknownStaysUntouched() {
            // THE TARGET IS A REAL SNIFFABLE IMAGE: IF A PROBE EVER RAN, THE TYPE WOULD FLIP TO IMAGE
            final URI target = opaque.toUri();
            final MRL mrl = resolve(URI.create("watertest://iptv/list"), unknown(target), unknown(target));
            assertEquals(2, mrl.sourceCount());
            assertEquals(MediaType.UNKNOWN, mrl.source(0).type());
            assertEquals(MediaType.UNKNOWN, mrl.source(1).type());
        }
    }

    @Nested
    @DisplayName("Qualities")
    class Qualities {

        @Test
        @DisplayName("One URL per bucket: the widest wins, then the tallest, then the first listed")
        void sizeBreaksTies() {
            final URI wide = URI.create("https://example.invalid/1080-wide.mp4");
            final URI narrow = URI.create("https://example.invalid/1080-narrow.mp4");
            final URI slow = URI.create("https://example.invalid/720p30.mp4");
            final URI fast = URI.create("https://example.invalid/720p60.mp4");
            final URI tall = URI.create("https://example.invalid/720-tall.mp4");
            final URI first = URI.create("https://example.invalid/360-first.mp4");
            final URI second = URI.create("https://example.invalid/360-second.mp4");
            // THE WIDER RENDITION OUTRANKS A FASTER NARROW ONE, AND THE TALLER ONE STAYS IN THE 720 BUCKET (THE SMALLER
            // SIDE DECIDES IT) AND OUTRANKS THE FASTER ONE THERE
            final Source source = new Source(MediaType.VIDEO, null, null, null, List.of(
                    new Quality(narrow, 1_920, 1_080, 60f), new Quality(wide, 1_930, 1_080, 24f),
                    new Quality(slow, 1_280, 720, 30f), new Quality(fast, 1_280, 720, 60f), new Quality(tall, 1_280, 760, 24f),
                    new Quality(first, 640, 360), new Quality(second, 640, 360)), null, null);
            final MRL.Source resolved = resolve(URI.create("watertest://bucket/clip"), source).source(0);
            assertEquals(wide, resolved.qualities().get(MediaQuality.HIGH), "the widest rendition owns the bucket");
            assertEquals(tall, resolved.qualities().get(MediaQuality.MEDIUM), "at equal width the tallest wins over the frame rate");
            assertEquals(first, resolved.qualities().get(MediaQuality.LOW), "equal renditions keep the first listed");
        }

        @Test
        @DisplayName("Frame rate breaks ties between renditions of the same size")
        void frameRateBreaksTies() {
            final URI slow = URI.create("https://example.invalid/1080p30.mp4");
            final URI fast = URI.create("https://example.invalid/1080p60.mp4");
            // THE SLOWER RENDITION IS LISTED FIRST SO ONLY THE FRAME RATE CAN PICK THE FASTER ONE
            final Source source = new Source(MediaType.VIDEO, null, null, null, List.of(
                    new Quality(slow, 1_920, 1_080, 30f), new Quality(fast, 1_920, 1_080, 60f)), null, null);
            assertEquals(fast, resolve(URI.create("watertest://bucket/fps"), source).source(0).qualities().get(MediaQuality.HIGH));
        }

        @Test
        @DisplayName("A resolved source carries frozen headers")
        void headersAreFrozen() {
            final Source source = new Source(MediaType.VIDEO, null, null, null,
                    List.of(new Quality(URI.create("https://example.invalid/clip.mp4"), 0, 0)), null, null);
            final MRL.Source resolved = resolve(URI.create("watertest://bucket/headers"), source).source(0);
            assertThrows(UnsupportedOperationException.class, () -> resolved.headers().set("X-Test", "1"));
        }

        @Test
        @DisplayName("moveQuality relabels the unknown quality of its own URI once, in a fresh source array")
        void moveQualityRelabelsUnknownQuality() {
            // A PLATFORM SOURCE WITHOUT A SIZE, SO NO EARLIER PLAYER CAN HAVE RELABELED THE FIXTURE ALREADY
            final URI uri = URI.create("https://example.invalid/unsized.mp4");
            final MRL mrl = resolve(URI.create("watertest://quality/move"), new Source(MediaType.VIDEO, null, null, null,
                    List.of(new Quality(uri, 0, 0)), null, null));
            final MRL.Source before = mrl.source(0);
            assertEquals(uri, before.qualities().get(MediaQuality.UNKNOWN), "a source without a size starts with an unknown quality");
            assertEquals(MediaQuality.UNKNOWN, before.qualityOf(uri));
            assertEquals(uri, before.uri(MediaQuality.HIGHER), "the closest fallback resolves to the only URI");
            assertFalse(mrl.moveQuality(0, URI.create("https://example.invalid/elsewhere.mp4"), MediaQuality.MEDIUM), "another URI is never relabeled");
            assertTrue(mrl.moveQuality(0, uri, MediaQuality.MEDIUM), "the unknown quality of its own URI is relabeled");

            final MRL.Source after = mrl.source(0);
            assertEquals(uri, after.qualities().get(MediaQuality.MEDIUM));
            assertEquals(MediaQuality.MEDIUM, after.qualityOf(uri));
            assertFalse(after.qualities().containsKey(MediaQuality.UNKNOWN), "the unknown label is gone");
            // PLAYERS KEEP THE SOURCE THEY STARTED WITH: THE RELABEL REPLACES THE ARRAY INSTEAD OF EDITING IT
            assertEquals(uri, before.qualities().get(MediaQuality.UNKNOWN));
            // A RESTART OR A SECOND PLAYER STILL HOLDS THE OLD SOURCE AND ASKS AGAIN: THE URI ALREADY SITS THERE
            assertTrue(mrl.moveQuality(0, uri, MediaQuality.MEDIUM), "a repeated relabel succeeds");
            assertFalse(mrl.moveQuality(0, uri, MediaQuality.HIGH), "a known quality is never relabeled");
        }
    }
}
