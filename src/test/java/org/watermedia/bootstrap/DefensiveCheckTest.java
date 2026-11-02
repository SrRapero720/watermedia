package org.watermedia.bootstrap;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.watermedia.WaterMedia;
import org.watermedia.test.support.LogCapture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the TLauncher check passes a clean JVM, binds only launcher-owned names and reports leftovers without blocking. */
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
            assertNotNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\.minecraft\\TLauncher.exe"));
            assertNotNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\.minecraft\\Old-TLauncher.exe"));
            assertNotNull(DefensiveCheck.found("/usr/bin/java -jar /home/me/TLauncher.jar"));
            assertNotNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\jvms\\jre1.8.0_281\\bin\\javaw.exe"));
            assertNotNull(DefensiveCheck.found("/Users/me/Library/Application Support/tlauncher/jvms/jre1.8.0_281/bin/java"));
            assertNotNull(DefensiveCheck.found("/home/me/.minecraft/libraries/org/tlauncher/authlib/1.6.25/authlib-1.6.25.jar"));
            assertNotNull(DefensiveCheck.found("/home/me/.minecraft/mods/tl_skin_cape_forge_1.20_1.20.1-1.38.jar"));
            assertNotNull(DefensiveCheck.found("C:\\mc\\mods\\tlskincape1.12.2-1.4.jar"));
            assertNotNull(DefensiveCheck.found("C:\\mc\\mods\\[1.12.2]TLSkinCape-1.2.jar"));
            assertNotNull(DefensiveCheck.found("--tweakClass org.tlauncher.tweaker.Tweaker"));
            assertNotNull(DefensiveCheck.found("org.tlauncher:authlib:1.6.25"));
            assertNotNull(DefensiveCheck.found("TlauncherProfiles.json"));
            assertNotNull(DefensiveCheck.found("TempOptifineStore-1.0.json"));
            assertNotNull(DefensiveCheck.found("C:\\mc\\tlauncher_libraries\\x.jar"));
            assertNotNull(DefensiveCheck.found("TLSkinCape-worker"));
        }

        @Test
        @DisplayName("ignores other launchers, plain folders named after it and unrelated text")
        void ignoresOtherLaunchers() {
            assertNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\ATLauncher\\ATLauncher.exe"));
            assertNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\ATLauncher\\jvms\\jre17\\bin\\javaw.exe"));
            assertNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\legacy\\Minecraft\\jre\\bin\\javaw.exe"));
            assertNull(DefensiveCheck.found("/home/me/skcraftlauncher/launcher.jar"));
            assertNull(DefensiveCheck.found("D:\\KevenTLauncher\\.minecraft\\mods"));
            assertNull(DefensiveCheck.found("C:\\Users\\me\\AppData\\Roaming\\.tlauncher\\legacy\\Minecraft\\game\\mods"));
            assertNull(DefensiveCheck.found("java -cp LL.jar ru.turikhay.tlauncher.bootstrap.Bootstrap"));
            assertNull(DefensiveCheck.found("D:\\TLauncher\\.minecraft\\libraries\\lwjgl\\lwjgl.jar"));
            assertNull(DefensiveCheck.found("/home/tlauncher/.minecraft"));
            assertNull(DefensiveCheck.found("C:\\mc\\mods\\CustomSkinLoader_Fabric-14.15.jar"));
            assertNull(DefensiveCheck.found("C:\\Program Files\\Java\\bin\\javaw.exe"));
            assertNull(DefensiveCheck.found(null));
        }

        @Test
        @DisplayName("returns the match with its surrounding context only")
        void excerpts() {
            final String noise = "x".repeat(200);
            final String excerpt = DefensiveCheck.found(noise + "/org/tlauncher/" + noise);
            assertTrue(excerpt.startsWith("...") && excerpt.endsWith("..."));
            assertTrue(excerpt.contains("/org/tlauncher/"));
            assertTrue(excerpt.length() < 120);
            assertEquals("TLauncher.exe", DefensiveCheck.found("TLauncher.exe"));
        }
    }

    @Nested
    @DisplayName("tlcheck")
    class Tlcheck {
        @Test
        @DisplayName("passes a clean JVM and game directory")
        void passesCleanEnvironment(@TempDir final Path gameDir) {
            assertDoesNotThrow(() -> DefensiveCheck.run(gameDir));
        }

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
                assertTrue(running.await(5, TimeUnit.SECONDS));
                final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> DefensiveCheck.run(gameDir));
                assertTrue(failure.getMessage().contains("thread TLSkinCape-probe"), failure.getMessage());
            } finally {
                release.countDown();
                thread.join(5_000);
            }
        }

        @Test
        @DisplayName("warns about leftovers in the game directory without blocking")
        void warnsLeftovers(@TempDir final Path gameDir) throws Exception {
            Files.writeString(gameDir.resolve("TlauncherProfiles.json"), "{}");
            try (final LogCapture logs = new LogCapture(WaterMedia.ID)) {
                assertDoesNotThrow(() -> DefensiveCheck.run(gameDir));
                assertTrue(logs.events().stream().anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getMessage().getFormattedMessage().contains("TlauncherProfiles.json")));
            }
        }
    }
}
