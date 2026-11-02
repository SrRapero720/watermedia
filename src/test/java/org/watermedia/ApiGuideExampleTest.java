package org.watermedia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.watermedia.test.support.Fixtures;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that the guide's image decoding example runs against the public API alone. */
@DisplayName("API guide example")
public class ApiGuideExampleTest {
    @Test
    @DisplayName("A static image decodes to one filtered frame")
    void documentedDecodingCountsAFrame() throws Exception {
        final ByteBuffer image = ByteBuffer.allocate(17);
        image.put("P6\n2 1\n255\n".getBytes(StandardCharsets.US_ASCII))
                .put(new byte[] { -1, 0, 0, 0, -1, 0 }).flip();
        assertEquals(1, ApiGuideExample.decode(image));
    }

    @Test
    @DisplayName("An animation decodes every filtered frame")
    void documentedDecodingCountsEveryAnimationFrame() throws Exception {
        assertEquals(40, ApiGuideExample.decode(ByteBuffer.wrap(Fixtures.readAll(Fixtures.PNG_DIR.resolve("2.png")))));
    }
}
