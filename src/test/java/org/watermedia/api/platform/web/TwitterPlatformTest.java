package org.watermedia.api.platform.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that tweet metadata carries the text X shows: inside {@code display_text_range} and with its HTML entities decoded. */
@DisplayName("Twitter tweet text (offline)")
public class TwitterPlatformTest {

    @Nested
    @DisplayName("Visible range")
    class Range {
        @Test
        @DisplayName("Drops the t.co link of the tweet's own media")
        void dropsMediaLink() {
            // PAYLOAD OF A REAL VIDEO TWEET: THE RANGE COUNTS THE ESCAPED TEXT
            assertEquals("test videos for watermedia :>", TwitterPlatform.text("test videos for watermedia :&gt; https://t.co/ZG7sxM4MgP", new int[] { 0, 32 }));
        }

        @Test
        @DisplayName("Counts UTF-16 units, so emoji keep the range aligned")
        void countsUtf16() {
            assertEquals("🧡💜\n#Rivchet", TwitterPlatform.text("🧡💜\n#Rivchet https://t.co/Qhno2SjhgJ", new int[] { 0, 13 }));
        }

        @Test
        @DisplayName("Drops the leading reply mentions")
        void dropsMentions() {
            assertEquals("hello", TwitterPlatform.text("@a @b hello https://t.co/x", new int[] { 6, 11 }));
        }

        @Test
        @DisplayName("Media-only tweets have no visible text")
        void mediaOnly() {
            assertEquals("", TwitterPlatform.text("https://t.co/Qhno2SjhgJ", new int[] { 0, 0 }));
        }

        @Test
        @DisplayName("A missing or broken range keeps the whole text")
        void brokenRange() {
            assertEquals("a & b", TwitterPlatform.text("a &amp; b", null));
            assertEquals("a & b", TwitterPlatform.text("a &amp; b", new int[] { 0, 99 }));
            assertEquals("a & b", TwitterPlatform.text("a &amp; b", new int[] { 5, 2 }));
            assertEquals("", TwitterPlatform.text(null, new int[] { 0, 3 }));
        }
    }

    @Nested
    @DisplayName("Entities")
    class Entities {
        @Test
        @DisplayName("Decodes the three escaped characters")
        void decodes() {
            assertEquals("<b> & </b>", TwitterPlatform.text("&lt;b&gt; &amp; &lt;/b&gt;", null));
        }

        @Test
        @DisplayName("An escaped entity stays literal")
        void escapedEntity() {
            assertEquals("&gt; is >", TwitterPlatform.text("&amp;gt; is &gt;", null));
        }
    }
}
