package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.api.codecs.CodecsAPI;
import org.watermedia.api.codecs.DownscaleFilter;
import org.watermedia.api.codecs.ImageReader;
import org.watermedia.test.support.Fixtures;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the guide's image decoding example runs against the public API alone and counts every frame, and that
 * its documented {@code DownscaleFilter(0.5f)} halves each frame of a static image and of an animation.
 */
@DisplayName("API guide example")
public class ApiGuideExampleTest {
    // THE EXAMPLE ONLY COUNTS FRAMES, SO A READER WITH THE SAME FILTER CHECKS THE HALVED SIZE THE EXAMPLE CANNOT REPORT
    private static void assertHalved(final ByteBuffer encoded, final int width, final int height) throws IOException {
        try (final ImageReader reader = CodecsAPI.reader(encoded)) {
            reader.addFilter(new DownscaleFilter(0.5f));
            while (reader.hasNext()) {
                reader.next();
                assertEquals(width, reader.frameWidth(), "filtered frame width");
                assertEquals(height, reader.frameHeight(), "filtered frame height");
            }
        }
    }

    @Test
    @DisplayName("A static image decodes to one frame that the guide's filter halves")
    void documentedDecodingCountsAFrame() throws Exception {
        // A 2x1 PPM OF ONE RED AND ONE GREEN PIXEL; HALF OF 2x1 ROUNDS TO 1x1
        final byte[] header = "P6\n2 1\n255\n".getBytes(StandardCharsets.US_ASCII);
        final ByteBuffer image = ByteBuffer.allocate(header.length + 6).put(header).put(new byte[] { -1, 0, 0, 0, -1, 0 }).flip();
        assertEquals(1, ApiGuideExample.decode(image.duplicate()));
        assertHalved(image, 1, 1);
    }

    @Test
    @DisplayName("An animation decodes to every frame, each one halved by the guide's filter")
    void documentedDecodingCountsEveryAnimationFrame() throws Exception {
        // 2.png IS A 150x150 APNG OF 40 FRAMES
        final ByteBuffer animation = ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_DIR.resolve("2.png")));
        assertEquals(40, ApiGuideExample.decode(animation.duplicate()));
        assertHalved(animation, 75, 75);
    }
}
