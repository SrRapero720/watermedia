package org.watermedia.bootstrap.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies how {@link SystemInfo} spots the WATERMeDIA extension mods among the jar names of a mods folder. */
@DisplayName("System info extension alerts")
public class SystemInfoTest {

    private static final String YOUTUBE = "`watermedia-youtube-extension` is installed";
    private static final String PLATFORM = "`watermedia-platform-extension` is installed";

    @Test
    @DisplayName("an extension is found with dashes, underscores or no separator, in any case")
    void separators() {
        assertEquals(List.of(YOUTUBE), SystemInfo.extensionAlerts(List.of("watermedia-youtube-extension-1.0.0.jar")));
        assertEquals(List.of(YOUTUBE), SystemInfo.extensionAlerts(List.of("WaterMedia_YouTube_Extension-forge-1.20.1.jar")));
        assertEquals(List.of(YOUTUBE), SystemInfo.extensionAlerts(List.of("[1.20.1] watermedia_youtube-extension.jar")));
        assertEquals(List.of(PLATFORM), SystemInfo.extensionAlerts(List.of("WATERMEDIAPLATFORMEXTENSION.JAR")));
    }

    @Test
    @DisplayName("each installed extension alerts once, in a fixed order")
    void both() {
        assertEquals(List.of(YOUTUBE, PLATFORM), SystemInfo.extensionAlerts(List.of("sodium.jar", "watermedia_platform_extension-2.jar",
                "watermedia-youtube-extension-1.jar", "watermedia-youtube-extension-1 (1).jar")));
    }

    @Test
    @DisplayName("the library, its companion mods and unrelated jars raise no alert")
    void others() {
        assertEquals(List.of(), SystemInfo.extensionAlerts(List.of("watermedia-3.0.0.jar", "watermedia-binaries-3.0.0.jar",
                "waterframes-2.1.jar", "youtube-extension.jar", "watermedia-twitch-extension.jar")));
        assertEquals(List.of(), SystemInfo.extensionAlerts(List.of()));
    }
}
