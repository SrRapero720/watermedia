package org.watermedia.api.codecs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ImageMetadata} invariants: the read-only {@code EMPTY} singleton, empty-collection
 * accessors (never {@code null}), and the blank/empty filtering in {@code put()} and the setters.
 */
@DisplayName("ImageMetadata")
public class ImageMetadataTest {

    @Test
    @DisplayName("EMPTY is read-only")
    void testEmptyIsReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.title("x"));
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.author("x"));
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.put("k", "v"));
        assertTrue(ImageMetadata.EMPTY.empty());
    }

    @Test
    @DisplayName("Collection accessors return empty, not null")
    void testCollectionsNeverNull() {
        final ImageMetadata md = new ImageMetadata();
        assertNotNull(md.authors());
        assertTrue(md.authors().isEmpty());
        assertNotNull(md.comments());
        assertTrue(md.comments().isEmpty());
        assertNotNull(md.values());
        assertTrue(md.values().isEmpty());
        assertTrue(md.empty());
    }

    @Test
    @DisplayName("Setters and put() filter blank/empty values")
    void testBlankFiltering() {
        final ImageMetadata md = new ImageMetadata();
        md.title("  ").author("  ").put("blank", "  ").put("emptyBytes", new byte[0]).put("emptyList", List.of());
        assertNull(md.title());
        assertTrue(md.authors().isEmpty());
        assertTrue(md.values().isEmpty());
        assertTrue(md.empty(), "only blank/empty values were added, metadata must still be empty");

        md.title(" Hello ").author("Jane").put("k", "v");
        assertEquals("Hello", md.title(), "clean() must trim surrounding whitespace");
        assertEquals(List.of("Jane"), md.authors());
        assertEquals("v", md.value("k"));
        assertFalse(md.empty());
    }
}
