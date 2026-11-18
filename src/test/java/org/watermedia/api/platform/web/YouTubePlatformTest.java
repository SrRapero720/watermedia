package org.watermedia.api.platform.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.test.support.LocalHttp;
import org.watermedia.test.support.MediaBootstrap;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bot gate ladder of {@link YouTubePlatform} offline, with scripts standing in for yt-dlp and
 * rustypipe-botguard and a loopback visitor_id endpoint: a po_token retry first, then an attempt over IPv4, which
 * stays on for every later link and search while it gets links through and is given up once the gate stands there too.
 */
@DisplayName("YouTube bot gate (offline)")
public class YouTubePlatformTest {
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final String YTDLP_PROPERTY = "watermedia.ytdlp.binary";
    private static final String BOTGUARD_PROPERTY = "watermedia.botguard.binary";
    private static final String INFO = "{\"webpage_url\":\"https://www.youtube.com/watch?v=test\",\"title\":\"Test\",\"formats\":[{\"format_id\":\"18\","
            + "\"url\":\"https://cdn.test/18\",\"protocol\":\"https\",\"vcodec\":\"avc1\",\"acodec\":\"mp4a\",\"width\":640,\"height\":360}]}";
    private static final URI FIRST = URI.create("https://www.youtube.com/watch?v=first");
    private static final URI SECOND = URI.create("https://www.youtube.com/watch?v=second");

    @TempDir
    Path directory;
    private String previousYtDlp;
    private String previousBotGuard;

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void bootstrap() { MediaBootstrap.client(); }

    @BeforeEach
    void fakes() throws IOException {
        this.previousYtDlp = System.getProperty(YTDLP_PROPERTY);
        this.previousBotGuard = System.getProperty(BOTGUARD_PROPERTY);
        Files.writeString(this.directory.resolve("info.json"), INFO);
        // THE TOKEN IS THE FIRST WORD OF THE ONLY LINE rustypipe-botguard PRINTS
        final Path botGuard = this.script("botguard", "@echo TOKEN\r\n", "#!/bin/sh\necho TOKEN\n");
        System.setProperty(BOTGUARD_PROPERTY, botGuard.toString());
    }

    @AfterEach
    void restore() {
        if (this.previousYtDlp == null) System.clearProperty(YTDLP_PROPERTY);
        else System.setProperty(YTDLP_PROPERTY, this.previousYtDlp);
        if (this.previousBotGuard == null) System.clearProperty(BOTGUARD_PROPERTY);
        else System.setProperty(BOTGUARD_PROPERTY, this.previousBotGuard);
    }

    @Test
    @DisplayName("A gate that survives the po_token is retried over IPv4, and later links and searches start over IPv4")
    void ipv4GetsThroughAndStays() throws Exception {
        this.ytDlp("--force-ipv4", null);
        try (final LocalHttp visitor = visitor()) {
            final YouTubePlatform youtube = new YouTubePlatform(visitor.uri("/visitor"));
            assertEquals(1, youtube.resolve(FIRST).sourceCount());

            final List<String> first = this.calls();
            assertEquals(3, first.size(), first::toString);
            assertFalse(first.get(0).contains("po_token") || first.get(0).contains("--force-ipv4"), first.get(0));
            assertTrue(first.get(1).contains("po_token") && !first.get(1).contains("--force-ipv4"), first.get(1));
            assertTrue(first.get(2).contains("po_token") && first.get(2).contains("--force-ipv4"), first.get(2));

            assertEquals(1, youtube.resolve(SECOND).sourceCount());
            final List<String> later = this.calls();
            assertEquals(4, later.size(), later::toString);
            assertTrue(later.get(3).contains("--force-ipv4") && !later.get(3).contains("po_token"), later.get(3));

            // THE STAND-IN ANSWERS A SEARCH WITH THE SAME VIDEO, WHICH CARRIES NO ENTRIES
            assertTrue(youtube.search("cats", 3).isEmpty(), "the stand-in's answer carries no entries");
            final List<String> searched = this.calls();
            assertEquals(5, searched.size(), searched::toString);
            assertTrue(searched.get(4).contains("--flat-playlist") && searched.get(4).contains("--force-ipv4"), searched.get(4));
        }
    }

    @Test
    @DisplayName("IPv4 is not tried again once the gate stands there too")
    void ipv4IsTriedOnce() throws Exception {
        this.ytDlp("nothing matches this", null);
        try (final LocalHttp visitor = visitor()) {
            final YouTubePlatform youtube = new YouTubePlatform(visitor.uri("/visitor"));
            final PlatformException first = assertThrows(PlatformException.class, () -> youtube.resolve(FIRST));
            assertTrue(first.getMessage().startsWith("YouTube: (wm_err:2412) Failed to retry the blocked request with a po_token"), first.getMessage());
            final List<String> gated = this.calls();
            assertEquals(3, gated.size(), gated::toString);

            assertThrows(PlatformException.class, () -> youtube.resolve(SECOND));
            final List<String> calls = this.calls();
            assertEquals(5, calls.size(), calls::toString);
            assertFalse(calls.get(3).contains("--force-ipv4") || calls.get(4).contains("--force-ipv4"), calls::toString);
        }
    }

    @Test
    @DisplayName("A failure over IPv4 that is not the gate is reported with the gate behind it and leaves IPv4 for the next link")
    void otherFailureKeepsIpv4() throws Exception {
        this.ytDlp("--force-ipv4", "v=first");
        try (final LocalHttp visitor = visitor()) {
            final YouTubePlatform youtube = new YouTubePlatform(visitor.uri("/visitor"));
            final PlatformException first = assertThrows(PlatformException.class, () -> youtube.resolve(FIRST));
            assertTrue(first.getMessage().contains("Network is unreachable"), first.getMessage());
            final Throwable[] behind = first.getCause().getSuppressed();
            assertTrue(behind.length == 1 && behind[0].getMessage().contains("not a bot"), () -> "the gate must stay behind the failure: " + List.of(behind));
            final List<String> failed = this.calls();
            assertEquals(3, failed.size(), failed::toString);

            assertEquals(1, youtube.resolve(SECOND).sourceCount());
            final List<String> calls = this.calls();
            assertEquals(6, calls.size(), calls::toString);
            assertTrue(calls.get(5).contains("po_token") && calls.get(5).contains("--force-ipv4"), calls.get(5));
        }
    }

    @Test
    @DisplayName("IPv4 that stops getting links through is dropped, the next link gives it one more try and then it is given up")
    void ipv4ThatStopsHelpingIsDropped() throws Exception {
        this.ytDlp("--force-ipv4", null);
        try (final LocalHttp visitor = visitor()) {
            final YouTubePlatform youtube = new YouTubePlatform(visitor.uri("/visitor"));
            assertEquals(1, youtube.resolve(FIRST).sourceCount());
            final List<String> through = this.calls();
            assertEquals(3, through.size(), through::toString);

            // FROM HERE THE GATE STANDS OVER BOTH FAMILIES
            this.ytDlp("nothing matches this", null);
            assertThrows(PlatformException.class, () -> youtube.resolve(SECOND));
            final List<String> dropped = this.calls();
            assertEquals(5, dropped.size(), dropped::toString);
            assertTrue(dropped.get(3).contains("--force-ipv4") && dropped.get(4).contains("--force-ipv4"), dropped::toString);

            assertThrows(PlatformException.class, () -> youtube.resolve(FIRST));
            final List<String> retried = this.calls();
            assertEquals(8, retried.size(), retried::toString);
            assertFalse(retried.get(5).contains("--force-ipv4") || retried.get(6).contains("--force-ipv4"), retried::toString);
            assertTrue(retried.get(7).contains("--force-ipv4"), retried.get(7));

            assertThrows(PlatformException.class, () -> youtube.resolve(SECOND));
            final List<String> settled = this.calls();
            assertEquals(10, settled.size(), settled::toString);
            assertFalse(settled.get(8).contains("--force-ipv4") || settled.get(9).contains("--force-ipv4"), settled::toString);
        }
    }

    @Test
    @DisplayName("A po_token that lifts the gate never brings IPv4 in")
    void tokenGetsThroughWithoutIpv4() throws Exception {
        this.ytDlp("po_token", null);
        try (final LocalHttp visitor = visitor()) {
            final YouTubePlatform youtube = new YouTubePlatform(visitor.uri("/visitor"));
            assertEquals(1, youtube.resolve(FIRST).sourceCount());
            assertEquals(1, youtube.resolve(SECOND).sourceCount());

            final List<String> calls = this.calls();
            assertEquals(4, calls.size(), calls::toString);
            assertFalse(calls.stream().anyMatch(call -> call.contains("--force-ipv4")), calls::toString);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================

    // A STAND-IN EXECUTABLE FOR THIS OS; THE BINARY RESOLVER MARKS ITS OVERRIDE EXECUTABLE ITSELF ON POSIX
    private Path script(final String name, final String batch, final String shell) throws IOException {
        final Path file = this.directory.resolve(name + (WINDOWS ? ".cmd" : ".sh"));
        return Files.writeString(file, WINDOWS ? batch : shell, StandardCharsets.US_ASCII);
    }

    // A yt-dlp THAT LOGS EVERY RUN AND ANSWERS THE BOT GATE UNLESS ITS ARGUMENTS CARRY needle; AN IPv4 RUN WHOSE ARGUMENTS CARRY
    // unreachable (WHEN NOT NULL) FAILS FOR THE NETWORK INSTEAD
    private void ytDlp(final String needle, final String unreachable) throws IOException {
        final String batch = unreachable == null ? "" : "echo %* | findstr /C:\"--force-ipv4\" | findstr /C:\"" + unreachable + "\" >nul\r\nif not errorlevel 1 (\r\n"
                + "  echo ERROR: [youtube] test: Unable to download webpage: Network is unreachable 1>&2\r\n  exit /b 1\r\n)\r\n";
        final String shell = unreachable == null ? "" : "case \"$*\" in\n  *\"--force-ipv4\"*\"" + unreachable + "\"*|*\"" + unreachable + "\"*\"--force-ipv4\"*)"
                + " echo \"ERROR: [youtube] test: Unable to download webpage: Network is unreachable\" 1>&2; exit 1 ;;\nesac\n";
        final Path file = this.script("yt-dlp",
                "@echo off\r\n>>\"%~dp0calls.log\" echo %*\r\n" + batch + "echo %* | findstr /C:\"" + needle + "\" >nul\r\nif errorlevel 1 (\r\n"
                        + "  echo ERROR: [youtube] test: Sign in to confirm you're not a bot 1>&2\r\n  exit /b 1\r\n)\r\ntype \"%~dp0info.json\"\r\n",
                "#!/bin/sh\ndir=$(dirname \"$0\")\necho \"$*\" >> \"$dir/calls.log\"\n" + shell + "case \"$*\" in\n  *\"" + needle + "\"*) cat \"$dir/info.json\" ;;\n"
                        + "  *) echo \"ERROR: [youtube] test: Sign in to confirm you're not a bot\" 1>&2; exit 1 ;;\nesac\n");
        System.setProperty(YTDLP_PROPERTY, file.toString());
    }

    // EVERY yt-dlp RUN SO FAR, ONE LINE OF ARGUMENTS EACH
    private List<String> calls() throws IOException {
        return Files.readAllLines(this.directory.resolve("calls.log"));
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static LocalHttp visitor() {
        final byte[] body = "{\"responseContext\":{\"visitorData\":\"VISITOR\"}}".getBytes(StandardCharsets.UTF_8);
        return LocalHttp.start("/visitor", exchange -> LocalHttp.respond(exchange, "application/json", body, 0L));
    }
}
