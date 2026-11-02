package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.watermedia.test.support.MediaBootstrap.runProbe;

/** Verifies that the launcher and the app spec share {@code config/app.properties}, and how the interface scale snaps. */
@DisplayName("App preferences")
public class AppConfigTest {

    @Nested
    @DisplayName("Launcher and spec")
    class SharedFile {
        @TempDir Path directory;

        @Test
        @DisplayName("a fresh directory runs on Vulkan with an automatic scale")
        void defaults() throws Exception {
            runProbe(AppConfigProbe.class, this.directory, "defaults", false);
        }

        @Test
        @DisplayName("the launcher reads the engine the spec wrote")
        void shared() throws Exception {
            runProbe(AppConfigProbe.class, this.directory, "shared", false);
        }

        @Test
        @DisplayName("a malformed file falls back to Vulkan instead of failing the launch")
        void malformed() throws Exception {
            runProbe(AppConfigProbe.class, this.directory, "malformed", false);
        }

        @Test
        @DisplayName("the -D override wins for one launch and never rewrites the file")
        void override() throws Exception {
            runProbe(AppConfigProbe.class, this.directory, "override", false);
        }
    }

    @Nested
    @DisplayName("Interface scale")
    class Scale {
        @Test
        @DisplayName("zero and negative values mean automatic")
        void automatic() {
            assertEquals(0f, AppConfig.scaleFactor(0f));
            assertEquals(0f, AppConfig.scaleFactor(-1f));
            assertEquals(0f, AppConfig.scaleFactor(Float.NaN));
        }

        @Test
        @DisplayName("factors snap to quarter steps between 1 and 3")
        void snaps() {
            assertEquals(1.25f, AppConfig.scaleFactor(1.3f));
            assertEquals(1.5f, AppConfig.scaleFactor(1.4f));
            assertEquals(1f, AppConfig.scaleFactor(0.3f));
            assertEquals(3f, AppConfig.scaleFactor(7f));
        }

        @Test
        @DisplayName("the settings stops run from automatic through every factor")
        void stops() {
            assertArrayEquals(new double[] { 0, 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.75, 3 }, AppConfig.scales());
        }

        @Test
        @DisplayName("labels read Auto or the snapped factor")
        void labels() {
            assertEquals("Auto", AppConfig.scaleLabel(0));
            assertEquals("x1.0", AppConfig.scaleLabel(1));
            assertEquals("x1.25", AppConfig.scaleLabel(1.2));
            assertEquals("x3.0", AppConfig.scaleLabel(3));
        }
    }
}
