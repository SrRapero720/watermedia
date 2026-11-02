package org.watermedia.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the plane-size rule every {@link GFXEngine} backend shares. */
@DisplayName("GFXEngine")
public class GFXEngineTest {

    @Test
    @DisplayName("minBytes spares the last row its padding and refuses a stride shorter than a row")
    void minBytes() {
        assertEquals(12L, GFXEngine.minBytes(0, 4, 3), "a tight plane is rows times row bytes");
        assertEquals(2L * 16 + 4, GFXEngine.minBytes(16, 4, 3));
        assertEquals(4L, GFXEngine.minBytes(16, 4, 1), "a single row needs no stride at all");
        assertEquals(12L, GFXEngine.minBytes(4, 4, 3));
        assertEquals(-1L, GFXEngine.minBytes(3, 4, 3));
        assertEquals(65_535L * 65_536 + 65_536, GFXEngine.minBytes(65_536, 65_536, 65_536), "large planes never wrap");
    }
}
