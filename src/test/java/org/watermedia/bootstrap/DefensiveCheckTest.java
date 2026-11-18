package org.watermedia.bootstrap;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.test.support.LogCapture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the TLauncher check: its signature binds only launcher-owned names and excerpts the match, and the startup
 * check blocks a live skin mod thread, passes a clean JVM and only warns about leftovers in the game directory.
 */
@DisplayName("DefensiveCheck")
public class DefensiveCheckTest {
    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("found")
    class Found {
        @Test
        @DisplayName("binds the launcher executables, game files, maven group, skin mod and runtime store")
        void bindsLauncherOwnedNames() {
            // EVERY TEXT IS JUDGED, SO ONE RUN REPORTS ALL THE TEXTS THE SIGNATURE MISSES
            assertAll(Stream.of(
                    "C:\\Users\\me\\AppData\\Roaming\\.minecraft\\TLauncher.exe",
                    "C:\\Users\\me\\AppData\\Roaming\\.minecraft\\Old-TLauncher.exe",
                    "/usr/bin/java -jar /home/me/TLauncher.jar",
                    "C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\jvms\\jre1.8.0_281\\bin\\javaw.exe",
                    "/Users/me/Library/Application Support/tlauncher/jvms/jre1.8.0_281/bin/java",
                    "/home/me/.minecraft/libraries/org/tlauncher/authlib/1.6.25/authlib-1.6.25.jar",
                    "/home/me/.minecraft/mods/tl_skin_cape_forge_1.20_1.20.1-1.38.jar",
                    "C:\\mc\\mods\\tlskincape1.12.2-1.4.jar",
                    "C:\\mc\\mods\\[1.12.2]TLSkinCape-1.2.jar",
                    "--tweakClass org.tlauncher.tweaker.Tweaker",
                    "org.tlauncher:authlib:1.6.25",
                    "TlauncherProfiles.json",
                    "TempOptifineStore-1.0.json",
                    "C:\\mc\\tlauncher_libraries\\x.jar",
                    "TLSkinCape-worker"
            ).<Executable>map(text -> () -> assertNotNull(DefensiveCheck.found(text), text)));
        }

        @Test
        @DisplayName("ignores other launchers, plain folders named after it and unrelated text")
        void ignoresOtherLaunchers() {
            assertAll(Stream.of(
                    "C:\\Users\\me\\AppData\\Roaming\\ATLauncher\\ATLauncher.exe",
                    "C:\\Users\\me\\AppData\\Roaming\\ATLauncher\\jvms\\jre17\\bin\\javaw.exe",
                    "C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\legacy\\Minecraft\\jre\\bin\\javaw.exe",
                    "/home/me/skcraftlauncher/launcher.jar",
                    "D:\\KevenTLauncher\\.minecraft\\mods",
                    "C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\legacy\\Minecraft\\game\\mods",
                    "java -cp LL.jar ru.turikhay.tlauncher.bootstrap.Bootstrap",
                    "D:\\TLauncher\\.minecraft\\libraries\\lwjgl\\lwjgl.jar",
                    "/home/tlauncher/.minecraft",
                    "C:\\mc\\mods\\CustomSkinLoader_Fabric-14.15.jar",
                    "C:\\Program Files\\Java\\bin\\javaw.exe"
            ).<Executable>map(text -> () -> assertNull(DefensiveCheck.found(text), text)));
            assertNull(DefensiveCheck.found(null));
        }

        @Test
        @DisplayName("returns the match with its surrounding context only")
        void excerpts() {
            final String noise = "x".repeat(200);
            final String excerpt = DefensiveCheck.found(noise + "/org/tlauncher/" + noise);
            assertTrue(excerpt.startsWith("...") && excerpt.endsWith("..."), excerpt);
            assertTrue(excerpt.contains("/org/tlauncher/"), excerpt);
            // THE 13 CHARACTER MATCH WITH 48 CHARACTERS OF CONTEXT ON EACH SIDE AND BOTH ELLIPSES IS 115 LONG
            assertTrue(excerpt.length() < 120, excerpt);
            assertEquals("TLauncher.exe", DefensiveCheck.found("TLauncher.exe"));
        }
    }

    @Nested
    @DisplayName("run")
    class Run {
        @Test
        @DisplayName("blocks startup when a thread of the skin mod is alive")
        void blocksLauncherThread(@TempDir final Path gameDir) throws InterruptedException {
            final CountDownLatch release = new CountDownLatch(1), running = new CountDownLatch(1);
            final Thread thread = new Thread(() -> {
                running.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (final InterruptedException ignored) {}
            }, "TLSkinCape-probe");
            thread.setDaemon(true);
            thread.start();
            try {
                assertTrue(running.await(5, TimeUnit.SECONDS), "the skin mod thread started");
                final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DefensiveCheck.run(gameDir));
                assertTrue(failure.getMessage().contains("thread TLSkinCape-probe"), failure.getMessage());
            } finally {
                release.countDown();
                thread.join(5_000);
            }
        }

        @Test
        @DisplayName("passes a clean JVM and warns about leftovers in the game directory without blocking")
        void warnsLeftovers(@TempDir final Path gameDir) throws Exception {
            Files.writeString(gameDir.resolve("TlauncherProfiles.json"), "{}");
            try (final LogCapture logs = new LogCapture(WaterMedia.ID)) {
                assertDoesNotThrow(() -> DefensiveCheck.run(gameDir));
                assertTrue(logs.events().stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getMessage().getFormattedMessage().contains("TlauncherProfiles.json")), "the leftover was reported in a WARN");
            }
        }
    }
}
