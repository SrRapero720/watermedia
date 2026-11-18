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
 * Verifies {@link ImageMetadata} invariants: the read-only {@code EMPTY} singleton, empty-collection accessors (never
 * {@code null}), and the blank and empty filtering of {@code put()} and the setters, which trim what they keep.
 */
@DisplayName("ImageMetadata")
public class ImageMetadataTest {

    @Test
    @DisplayName("EMPTY refuses every change and reports itself empty")
    void emptyIsReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.title("x"), "title");
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.author("x"), "author");
        assertThrows(UnsupportedOperationException.class, () -> ImageMetadata.EMPTY.put("k", "v"), "put");
        assertTrue(ImageMetadata.EMPTY.empty(), "EMPTY reports itself empty");
    }

    @Test
    @DisplayName("The collection accessors of new metadata are empty, never null")
    void collectionsAreNeverNull() {
        final ImageMetadata md = new ImageMetadata();
        assertNotNull(md.authors(), "authors");
        assertTrue(md.authors().isEmpty(), "authors");
        assertNotNull(md.comments(), "comments");
        assertTrue(md.comments().isEmpty(), "comments");
        assertNotNull(md.values(), "values");
        assertTrue(md.values().isEmpty(), "values");
        assertTrue(md.empty(), "new metadata is empty");
    }

    @Test
    @DisplayName("The setters and put() drop blank and empty values and trim the ones they keep")
    void blankValuesAreDropped() {
        final ImageMetadata md = new ImageMetadata();
        md.title("  ").author("  ").put("blank", "  ").put("emptyBytes", new byte[0]).put("emptyList", List.of());
        assertNull(md.title(), "a blank title is dropped");
        assertTrue(md.authors().isEmpty(), "a blank author is dropped");
        assertTrue(md.values().isEmpty(), "blank text, empty bytes and an empty list are dropped");
        assertTrue(md.empty(), "only blank and empty values were added, so the metadata stays empty");

        md.title(" Hello ").author("Jane").put("k", "v");
        assertEquals("Hello", md.title(), "the title is stored trimmed");
        assertEquals(List.of("Jane"), md.authors());
        assertEquals("v", md.value("k"));
        assertFalse(md.empty(), "real values make the metadata non-empty");
    }
}
