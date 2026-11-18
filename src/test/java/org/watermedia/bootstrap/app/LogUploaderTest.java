package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.test.support.PlayerWait;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.AbstractList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link LogUploader} walks its stages, scans an instance, masks what it reads and composes the issue
 * report and its link.
 */
@DisplayName("Log uploader")
public class LogUploaderTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("Stages")
    class Stages {

        // step() ONLY OPENS THE DIALOG ON A DEVELOPMENT RUN OR INSIDE A mods FOLDER
        @BeforeEach
        void developmentRun() {
            assertTrue(LogUploader.AVAILABLE, "the stage cases need a JVM that loads the classes from a directory (LogUploader.DEV)");
        }

        @Test
        @DisplayName("reopening while a worker runs shows its stage again instead of starting over")
        void reopensRunningStage() {
            final LogUploader uploader = new LogUploader(() -> {});
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("latest.log", "Game log", null);
            uploader.files.add(entry);
            uploader.stage = 2;
            uploader.working = true;
            uploader.step();
            assertTrue(uploader.visible);
            assertEquals(2, uploader.stage);
            assertEquals(List.of(entry), uploader.files);
        }

        @Test
        @DisplayName("a closed issue stage resumes with its uploads, title and description instead of scanning again")
        void resumesIssueStage() {
            final LogUploader uploader = new LogUploader(() -> {});
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("latest.log", "Game log", null);
            entry.uploaded = true;
            uploader.files.add(entry);
            uploader.uploadsDone = true;
            uploader.title = "Crash on join";
            uploader.description = "It happens every time.";
            uploader.stage = 3;
            uploader.step();
            assertTrue(uploader.visible);
            assertFalse(uploader.working, "no scan and no second upload");
            assertEquals(3, uploader.stage);
            assertTrue(uploader.uploadsDone);
            assertEquals(List.of(entry), uploader.files);
            assertEquals("Crash on join", uploader.title);
            assertEquals("It happens every time.", uploader.description);
        }

        @Test
        @DisplayName("an earlier stage and an opened issue start over, and only the opened issue spends the title and description")
        void startsOver() {
            final LogUploader uploader = new LogUploader(() -> {});
            uploader.files.add(new LogUploader.FileEntry("latest.log", "Game log", null));
            uploader.title = "Crash on join";
            uploader.description = "It happens every time.";
            uploader.stage = 2;
            uploader.step();
            assertEquals(1, uploader.stage, "a closed upload stage scans again");
            assertTrue(PlayerWait.awaitCondition(() -> !uploader.working, 30_000), "the scan of the working directory ends");
            assertEquals("Crash on join", uploader.title);
            assertEquals("It happens every time.", uploader.description);

            uploader.visible = false;
            uploader.stage = 3;
            uploader.issueOpened = true;
            uploader.browserFailed = true;
            uploader.step();
            assertEquals(1, uploader.stage, "an issue already opened is a finished session");
            assertEquals("", uploader.title);
            assertEquals("", uploader.description);
            assertFalse(uploader.issueOpened);
            assertFalse(uploader.browserFailed);
            assertTrue(PlayerWait.awaitCondition(() -> !uploader.working, 30_000), "the scan of the working directory ends");
        }

        @Test
        @DisplayName("a report that cannot be read flags the error and the next step reads it again; then only the title is missing")
        void retriesFailedReport() {
            final LogUploader uploader = new LogUploader(() -> {});
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("latest.log", "Game log", null);
            entry.uploaded = true;
            uploader.files.add(entry);
            uploader.visible = true;
            uploader.stage = 2;
            // THE READ OF THE SYSTEM WALKS THE JAR NAMES, AND THIS LIST BREAKS IT WITH AN Error, THE FAILURE THAT A CATCH OF
            // EXCEPTIONS ALONE WOULD LET THROUGH
            uploader.jars = new AbstractList<>() {
                @Override
                public String get(final int index) {
                    throw new InternalError("probe");
                }

                @Override
                public int size() {
                    return 1;
                }
            };
            uploader.step();
            assertEquals(3, uploader.stage);
            assertTrue(PlayerWait.awaitCondition(() -> !uploader.working, 60_000), "the report worker ends");
            assertTrue(uploader.error);
            assertFalse(uploader.ready());

            uploader.jars = List.of();
            uploader.title = "Crash on join";
            uploader.step();
            assertFalse(uploader.error, "the retry clears the failure");
            assertTrue(PlayerWait.awaitCondition(() -> !uploader.working, 60_000), "the report worker ends");
            assertTrue(uploader.ready());
            assertFalse(uploader.error);
            assertFalse(uploader.issueOpened, "the step read the report again, it did not submit");

            uploader.title = " \n";
            uploader.step();
            assertFalse(uploader.working, "a gathered report is not submitted without a title");
        }
    }

    @Nested
    @DisplayName("Scan")
    class Scan {
        @TempDir Path instance;

        @Test
        @DisplayName("inside a mods folder the game files come from the instance and the app files from the folder itself")
        void modsLayout() throws IOException {
            final Path mods = this.instance.resolve("mods");
            write(this.instance.resolve("logs/latest.log"), "game");
            write(this.instance.resolve("logs/watermedia-app.log"), "not the log of this app");
            write(this.instance.resolve("crash-reports/crash-2026-11-01_10.00.00-client.txt"), "crash");
            write(this.instance.resolve("hs_err_pid42.log"), "fatal");
            write(this.instance.resolve("config/watermedia.toml"), "game config");
            write(mods.resolve("logs/watermedia-app.log"), "app");
            write(mods.resolve("config/watermedia.toml"), "app config");
            write(mods.resolve("config/app.properties"), "engine=VULKAN");
            write(mods.resolve("FancyMenu-Forge-1.20.1.jar"), "");
            write(mods.resolve("WaterMedia_YouTube_Extension-1.0.jar"), "");
            write(mods.resolve("notes.txt"), "");
            Files.createDirectories(mods.resolve("folder.jar"));

            final int[] repaints = new int[1];
            final LogUploader uploader = new LogUploader(() -> repaints[0]++);
            uploader.working = true;
            uploader.scan(mods, true);

            assertFalse(uploader.working);
            assertFalse(uploader.error);
            assertEquals(1, repaints[0]);
            assertEquals(List.of("latest.log", "crash-2026-11-01_10.00.00-client.txt", "hs_err_pid42.log", "watermedia-app.log",
                    "watermedia.toml (game)", "watermedia.toml (app)", "app.properties"), uploader.files.stream().map(entry -> entry.name).toList());
            assertEquals(List.of("Game log", "Crash report", "JVM crash (hs_err)", "App log", "Config (game)", "Config (app)", "App preferences"),
                    uploader.files.stream().map(entry -> entry.label).toList());
            assertEquals(List.of(this.instance.resolve("logs/latest.log"), this.instance.resolve("crash-reports/crash-2026-11-01_10.00.00-client.txt"),
                    this.instance.resolve("hs_err_pid42.log"), mods.resolve("logs/watermedia-app.log"), this.instance.resolve("config/watermedia.toml"),
                    mods.resolve("config/watermedia.toml"), mods.resolve("config/app.properties")), uploader.files.stream().map(entry -> entry.path).toList());
            for (final LogUploader.FileEntry entry: uploader.files) {
                assertTrue(entry.present && entry.valid, entry.name);
                assertEquals("READY", entry.state, entry.name);
            }
            assertEquals(List.of("fancymenu-forge-1.20.1.jar", "watermedia_youtube_extension-1.0.jar"), uploader.jars.stream().sorted().toList());
        }

        @Test
        @DisplayName("a development run reads everything from its working directory, the shared config once and the jars of its mods folder")
        void devLayout() throws IOException {
            write(this.instance.resolve("logs/latest.log"), "game");
            write(this.instance.resolve("logs/watermedia-app.log"), "app");
            write(this.instance.resolve("config/watermedia.toml"), "shared config");
            write(this.instance.resolve("config/app.properties"), "engine=VULKAN");
            write(this.instance.resolve("mods/WaterFrames-2.1.jar"), "");
            write(this.instance.resolve("loose.jar"), "");

            final LogUploader uploader = new LogUploader(() -> {});
            uploader.scan(this.instance, false);

            assertEquals(List.of("latest.log", "crash-reports", "hs_err_pid.log", "watermedia-app.log", "watermedia.toml", "app.properties"),
                    uploader.files.stream().map(entry -> entry.name).toList());
            assertEquals(List.of("Game log", "Crash report", "JVM crash (hs_err)", "App log", "Config", "App preferences"),
                    uploader.files.stream().map(entry -> entry.label).toList());
            assertEquals(List.of(true, false, false, true, true, true), uploader.files.stream().map(entry -> entry.present).toList());
            assertEquals(this.instance.resolve("logs/watermedia-app.log"), uploader.files.get(3).path);
            assertEquals(this.instance.resolve("config/watermedia.toml"), uploader.files.get(4).path);
            assertEquals(List.of("waterframes-2.1.jar"), uploader.jars);
        }

        @Test
        @DisplayName("the JVM crash row takes the newest dump of the game and of the app")
        void newestFatal() throws IOException {
            final Path mods = this.instance.resolve("mods");
            final Path game = this.instance.resolve("hs_err_pid42.log");
            final Path app = mods.resolve("hs_err_pid7.log");
            write(game, "game crash");
            write(app, "app crash");
            write(mods.resolve("hs_err_notes.txt"), "not a dump");

            Files.setLastModifiedTime(game, FileTime.fromMillis(2_000_000L));
            Files.setLastModifiedTime(app, FileTime.fromMillis(1_000_000L));
            final LogUploader gameNewer = new LogUploader(() -> {});
            gameNewer.scan(mods, true);
            assertEquals("hs_err_pid42.log", gameNewer.files.get(2).name);
            assertEquals(game, gameNewer.files.get(2).path);

            Files.setLastModifiedTime(app, FileTime.fromMillis(3_000_000L));
            final LogUploader appNewer = new LogUploader(() -> {});
            appNewer.scan(mods, true);
            assertEquals("hs_err_pid7.log", appNewer.files.get(2).name);
            assertEquals(app, appNewer.files.get(2).path);
            assertEquals("JVM crash (hs_err)", appNewer.files.get(2).label);
            assertEquals(7, appNewer.files.size());
        }

        @Test
        @DisplayName("missing files stay as NOT FOUND rows and a missing mods folder lists no jars")
        void missing() {
            final LogUploader uploader = new LogUploader(() -> {});
            uploader.scan(this.instance, false);

            assertFalse(uploader.error);
            assertEquals(6, uploader.files.size());
            for (final LogUploader.FileEntry entry: uploader.files) {
                assertFalse(entry.present || entry.valid, entry.name);
                assertEquals("NOT FOUND", entry.state, entry.name);
            }
            assertEquals(List.of(), uploader.jars);
        }

        @Test
        @DisplayName("a config copy is read with its token and cookie masked, like any log")
        void masksConfigs() throws IOException {
            final Path mods = this.instance.resolve("mods");
            write(mods.resolve("config/watermedia.toml"), "[network]\n  token = \"abc123\"\n\n[platforms]\n  biliBiliCookie = \"SESSDATA=xyz\"\n");
            write(mods.resolve("config/app.properties"), "engine=VULKAN\nnetwork.token=abc123\n");
            write(this.instance.resolve("logs/latest.log"), "Launched with --accessToken abc123 wm_err:0420\n");

            final LogUploader uploader = new LogUploader(() -> {});
            uploader.scan(mods, true);

            assertEquals("Launched with --accessToken ******** wm_err:0420\n", uploader.readContent(uploader.files.get(0)));
            assertEquals("[network]\n  token = ********\n\n[platforms]\n  biliBiliCookie = ********\n", uploader.readContent(uploader.files.get(5)));
            assertEquals("engine=VULKAN\nnetwork.token=********\n", uploader.readContent(uploader.files.get(6)));
            assertFalse(uploader.error);
        }

        @Test
        @DisplayName("the wm_err codes of every file read are collected once and sorted, and only four-digit ones count")
        void collectsErrorCodes() throws IOException {
            write(this.instance.resolve("logs/latest.log"), "[ERROR] (wm_err:4750) Failed to parse the JPEG stream\n[ERROR] (wm_err:0720) Failed to stop\nwm_err:99 wm_err:12345\n");
            write(this.instance.resolve("crash-reports/crash-1.txt"), "IllegalStateException: (wm_err:0720) Failed to stop the file server\nCaused by: (wm_err:0005) Failed to load\n");

            final LogUploader uploader = new LogUploader(() -> {});
            uploader.scan(this.instance.resolve("mods"), true);
            assertEquals(Set.of(), uploader.codes);
            uploader.readContent(uploader.files.get(0));
            uploader.readContent(uploader.files.get(1));

            assertEquals(List.of("0005", "0720", "4750"), List.copyOf(uploader.codes));
        }

        @Test
        @DisplayName("a file is read with the user name of its paths masked and the rest of every path kept")
        void masksUserPaths() throws IOException {
            write(this.instance.resolve("logs/latest.log"), "Game dir C:\\Users\\Steve\\AppData\\Roaming\\.minecraft\nNatives at /home/alex/.cache/natives\n");

            final LogUploader uploader = new LogUploader(() -> {});
            uploader.scan(this.instance.resolve("mods"), true);

            assertEquals("Game dir C:\\Users\\********\\AppData\\Roaming\\.minecraft\nNatives at /home/********/.cache/natives\n",
                    uploader.readContent(uploader.files.get(0)));
        }

        @Test
        @DisplayName("a file that cannot be read turns into a READ ERROR row")
        void unreadable() {
            final LogUploader uploader = new LogUploader(() -> {});
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("gone.log", "Gone", this.instance.resolve("gone.log"));
            assertNull(uploader.readContent(entry));
            assertEquals("READ ERROR", entry.state);
            assertTrue(uploader.error);
        }
    }

    @Nested
    @DisplayName("Report")
    class Report {

        @Test
        @DisplayName("the body runs the description, alerts, suspected mods, files, environment, folded codecs and system")
        void composes() {
            final LogUploader.FileEntry log = new LogUploader.FileEntry("latest.log", "Game log", null);
            log.url = "https://mclo.gs/abc1234";
            log.uploaded = true;
            final LogUploader.FileEntry crash = new LogUploader.FileEntry("crash-reports", "Crash report", null);
            final SystemInfo info = new SystemInfo(List.of("FFmpeg failed to load", "`watermedia-youtube-extension` is installed"),
                    "- WaterMedia: 3.0.0 (VK 1.3)\n- Extensions: none\n", List.of("aac", "h264", "vp9"), "- OS: Windows 10\n- CPU: 8 threads\n");
            assertEquals("""
                    The video freezes after a minute.
                    It never happens in windowed mode.

                    ## Alerts
                    - FFmpeg failed to load
                    - `watermedia-youtube-extension` is installed

                    ## Suspected mods
                    - FancyMenu (`Keksuccino/FancyMenu`)
                    - WaterFrames (`SrRapero720/waterframes`)

                    ## Error codes
                    - `wm_err:0720`, `wm_err:4750`

                    ## Files
                    - Game log: https://mclo.gs/abc1234
                    - Crash report: N/A

                    ## Environment
                    - WaterMedia: 3.0.0 (VK 1.3)
                    - Extensions: none

                    <details>
                    <summary>FFmpeg SW codecs (3)</summary>

                    aac, h264, vp9

                    </details>

                    ## System Properties
                    - OS: Windows 10
                    - CPU: 8 threads
                    """, LogUploader.body("\n  The video freezes after a minute.\nIt never happens in windowed mode.\n\n", info,
                    List.of("waterframes-2.1.jar", "sodium-0.5.jar", "fancymenu-forge-1.20.1.jar"), List.of(log, crash), new TreeSet<>(List.of("4750", "0720"))));
        }

        @Test
        @DisplayName("a blank description leaves no gap, empty sections read None detected and unloaded FFmpeg takes one line")
        void emptyParts() {
            final SystemInfo info = new SystemInfo(List.of(), "- WaterMedia: 3.0.0 (GL 4.6)\n", null, "- OS: Linux\n");
            assertEquals("""
                    ## Alerts
                    - None detected

                    ## Suspected mods
                    - None detected

                    ## Error codes
                    - None detected

                    ## Files
                    - App log: N/A

                    ## Environment
                    - WaterMedia: 3.0.0 (GL 4.6)
                    - FFmpeg SW codecs: not loaded

                    ## System Properties
                    - OS: Linux
                    """, LogUploader.body(" \n\t", info, List.of("frames.jar", "watermedia-3.0.0.jar"), List.of(new LogUploader.FileEntry("watermedia-app.log", "App log", null)), Set.of()));
        }

        @Test
        @DisplayName("a file that is uploading or failed reports N/A instead of a stale link")
        void pendingFiles() {
            final LogUploader.FileEntry entry = new LogUploader.FileEntry("latest.log", "Game log", null);
            entry.url = "https://mclo.gs/old";
            final String body = LogUploader.body("", new SystemInfo(List.of(), "", null, ""), List.of(), List.of(entry), Set.of());
            assertTrue(body.contains("## Files\n- Game log: N/A\n"), body);
        }

        @Test
        @DisplayName("the body masks the user name of the paths in the system facts and in the description")
        void masksUserPaths() {
            final SystemInfo info = new SystemInfo(List.of(), "- FFmpeg Path: C:\\Users\\Steve\\AppData\\Local\\Temp\\watermedia\n", null, "- Java Home: /Users/alex/jdk-21\n");
            final String body = LogUploader.body("It reads D:/Users/Steve/video.mp4", info, List.of(), List.of(), Set.of());
            assertTrue(body.startsWith("It reads D:/Users/********/video.mp4\n"), body);
            assertTrue(body.contains("- FFmpeg Path: C:\\Users\\********\\AppData\\Local\\Temp\\watermedia\n"), body);
            assertTrue(body.endsWith("- Java Home: /Users/********/jdk-21\n"), body);
        }
    }

    @Nested
    @DisplayName("User name")
    class UserName {

        @Test
        @DisplayName("a Windows home path loses only its user folder, with any separator and inside a file link")
        void windowsPaths() {
            assertEquals("C:\\Users\\********\\AppData", LogUploader.maskUser("C:\\Users\\Steve\\AppData", null));
            assertEquals("c:/users/********/mods/a.jar", LogUploader.maskUser("c:/users/Steve/mods/a.jar", null));
            assertEquals("\"C:\\\\Users\\\\********\\\\x\"", LogUploader.maskUser("\"C:\\\\Users\\\\Steve\\\\x\"", null));
            assertEquals("jar:file:/C:/Users/********/mods/a.jar!/", LogUploader.maskUser("jar:file:/C:/Users/Steve/mods/a.jar!/", null));
            assertEquals("Home: C:\\Users\\********", LogUploader.maskUser("Home: C:\\Users\\STEVE~1", null));
        }

        @Test
        @DisplayName("a Linux or macOS home path loses only its user folder")
        void unixPaths() {
            assertEquals("/home/********/.minecraft/logs", LogUploader.maskUser("/home/alex/.minecraft/logs", null));
            assertEquals("at /Users/********/Library", LogUploader.maskUser("at /Users/alex/Library", null));
            assertEquals("file:///home/********/a.png", LogUploader.maskUser("file:///home/alex/a.png", null));
        }

        @Test
        @DisplayName("links and paths outside a home folder stay whole")
        void keepsLinks() {
            final String text = "https://example.com/home/alex https://api.example.com/Users/42 C:\\Program Files\\Java /opt/home/x /usr/lib/jvm";
            assertEquals(text, LogUploader.maskUser(text, null));
        }

        @Test
        @DisplayName("the real home covers a name with spaces and a home outside the usual folders")
        void realHome() {
            assertEquals("C:\\Users\\********\\AppData and c:/users/********",
                    LogUploader.maskUser("C:\\Users\\John Doe\\AppData and c:/users/john doe", LogUploader.homePath("C:\\Users\\John Doe")));
            assertEquals("D:\\Profiles\\********\\x D:\\Profiles\\johnny", LogUploader.maskUser("D:\\Profiles\\john\\x D:\\Profiles\\johnny", LogUploader.homePath("D:\\Profiles\\john\\")));
        }

        @Test
        @DisplayName("a home without a parent folder is no pattern, so a plain word is never masked")
        void bareHome() {
            assertNull(LogUploader.homePath("/root"));
            assertNull(LogUploader.homePath("D:\\"));
            assertNull(LogUploader.homePath(" "));
            assertNull(LogUploader.homePath(null));
        }

        @Test
        @DisplayName("the user name line of an hs_err environment is masked, other lines are not")
        void environmentLine() {
            assertEquals("PATH=C:\\bin\nUSERNAME=********\nOS=Windows_NT\nSetting user: Steve\n",
                    LogUploader.maskUser("PATH=C:\\bin\nUSERNAME=Steve\nOS=Windows_NT\nSetting user: Steve\n", null));
        }
    }

    @Nested
    @DisplayName("Issue link")
    class Link {
        private static final String PAGE = "https://github.com/SrRapero720/watermedia/issues/new?title=";
        private static final String NOTE = "Paste+the+report+here.";

        @Test
        @DisplayName("a report that fits rides in the link, with the title and the body encoded")
        void fits() {
            assertEquals(PAGE + "Crash+on+join%3A+50%25+%26+%C3%B1&body=%23%23+Alerts%0A-+None+detected%0A",
                    LogUploader.issueUrl("Crash on join: 50% & ñ", "## Alerts\n- None detected\n"));
        }

        @Test
        @DisplayName("a link past the limit carries the title and the paste note instead of the report")
        void tooLong() {
            assertEquals(PAGE + "Crash+on+join&body=" + NOTE, LogUploader.issueUrl("Crash on join", "x".repeat(LogUploader.URL_LIMIT_CHARS)));
            assertEquals(PAGE + "Crash+on+join&body=" + NOTE, LogUploader.issueUrl("Crash on join", LogUploader.PASTE_NOTE));
        }

        @Test
        @DisplayName("the limit counts the whole encoded link, to the character")
        void boundary() {
            final int room = LogUploader.URL_LIMIT_CHARS - (PAGE + "T&body=").length();
            assertEquals(LogUploader.URL_LIMIT_CHARS, LogUploader.issueUrl("T", "x".repeat(room)).length());
            assertEquals(PAGE + "T&body=" + NOTE, LogUploader.issueUrl("T", "x".repeat(room + 1)));
            // A LINE BREAK TAKES THREE CHARACTERS ONCE ENCODED
            assertEquals(PAGE + "T&body=" + NOTE, LogUploader.issueUrl("T", "\n".repeat(room / 3 + 1)));
        }
    }
}
