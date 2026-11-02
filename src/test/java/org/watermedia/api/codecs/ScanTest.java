package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ImageReader#scan()}: a static image reuses {@link ImageData.Scan#EMPTY}, and the animation summary
 * an animated reader computes at construction matches the frames and delays the same reader then streams.
 */
@DisplayName("ImageReader.scan()")
public class ScanTest {

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ONE READER: ITS PRE-GENERATED SCAN MUST DESCRIBE A REAL ANIMATION, THEN READING THE SAME READER TO THE END MUST
    // YIELD EXACTLY THE SCANNED FRAME COUNT AND THE SCANNED DELAY FOR EVERY FRAME
    private static void assertScanMatchesStreaming(final Path resource) throws IOException {
        try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(resource)))) {
            final ImageData.Scan scan = reader.scan();
            assertTrue(scan.duration() > 0L, resource + ": scan duration must be positive");
            assertTrue(scan.frameCount() > 1, resource + ": animated file must report >1 frames");
            // READERS THAT TOTAL THEIR OWN DURATION MUST AGREE WITH THE DELAYS THEY LIST
            long total = 0L;
            for (final long delay: scan.delays()) total += delay;
            assertEquals(total, scan.duration(), resource + ": scan duration must be the sum of its delays");
            final ImageData image = reader.readAll();
            assertEquals(scan.frameCount(), image.frames().length, resource + ": streamed frame count must match scan");
            assertArrayEquals(scan.delays(), image.delays(), resource + ": streamed delays must match scan");
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    @Nested
    @DisplayName("Static")
    class Static {

        @Test
        @DisplayName("Static PNG scan reuses the EMPTY singleton")
        void staticPngScanIsEmpty() throws IOException {
            try (final ImageReader reader = CodecsAPI.reader(ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_STATIC)))) {
                assertSame(ImageData.Scan.EMPTY, reader.scan(), "static PNGs should reuse the EMPTY scan singleton");
            }
        }
    }

    @Nested
    @DisplayName("Animated")
    class Animated {

        @Test
        @DisplayName("APNG scan matches streaming")
        void apngScanMatchesStreaming() throws IOException {
            assertScanMatchesStreaming(Fixtures.PNG_ANIMATED);
        }

        @Test
        @DisplayName("Animated WebP scan matches streaming")
        void animatedWebpScanMatchesStreaming() throws IOException {
            assertScanMatchesStreaming(Fixtures.WEBP_ANIMATED);
        }
    }
}
