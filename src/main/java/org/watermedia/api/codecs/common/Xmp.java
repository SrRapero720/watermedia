package org.watermedia.api.codecs.common;

import org.watermedia.api.codecs.ImageMetadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads the Dublin Core and XMP Basic fields of an XMP packet into the normalized image metadata. */
public final class Xmp {
    // VALUES ARE RETAINED IN THE METADATA FOR THE READER'S LIFETIME, SO THE ELEMENT COUNT AND EACH VALUE NEED
    // A CEILING; NO REAL TITLE, AUTHOR OR DESCRIPTION COMES NEAR THESE AND ANYTHING PAST THEM IS DROPPED
    private static final int MAX_VALUES = 64;
    private static final int MAX_VALUE_LENGTH = 4_096;

    private Xmp() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Adds the title, description, copyright, label comment, creation time, software and authors found in the packet. */
    public static void read(final ImageMetadata metadata, final byte[] packet) {
        if (packet == null || packet.length == 0) return;
        final String xml = new String(packet, StandardCharsets.UTF_8);
        final String title = first(xml, "dc:title", "rdf:li");
        final String description = first(xml, "dc:description", "rdf:li");
        final String copyright = first(xml, "dc:rights", "rdf:li");
        final String created = first(xml, "xmp:CreateDate", null);
        final String software = first(xml, "xmp:CreatorTool", null);
        if (title != null) metadata.title(title);
        if (description != null) metadata.description(description);
        if (copyright != null) metadata.copyright(copyright);
        if (created != null) metadata.creationTime(created);
        if (software != null) metadata.software(software);
        metadata.comment(first(xml, "xmp:Label", null));
        for (final String author: texts(xml, "dc:creator", "rdf:li")) metadata.author(author);
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    private static String first(final String xml, final String outerTag, final String innerTag) {
        final List<String> values = texts(xml, outerTag, innerTag);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> texts(final String xml, final String outerTag, final String innerTag) {
        final List<String> values = new ArrayList<>();
        final String outer = content(xml, outerTag);
        if (outer == null) return values;
        if (innerTag == null) {
            final String clean = outer.length() > MAX_VALUE_LENGTH ? null : clean(outer);
            if (clean != null) values.add(clean);
            return values;
        }
        int pos = 0;
        while (values.size() < MAX_VALUES) {
            final int openStart = open(outer, innerTag, pos);
            if (openStart < 0) break;
            final int openEnd = outer.indexOf('>', openStart);
            if (openEnd < 0) break;
            final int closeStart = outer.indexOf("</" + innerTag + ">", openEnd + 1);
            if (closeStart < 0) break;
            final String clean = closeStart - openEnd - 1 > MAX_VALUE_LENGTH ? null : clean(outer.substring(openEnd + 1, closeStart));
            if (clean != null) values.add(clean);
            // RESUME PAST THE CONSUMED ELEMENT: ANCHORING ON A CLOSE TAG FOUND FROM pos WOULD RETAIN THE SAME BODY ONCE
            // PER STRAY TERMINATOR, O(L^2) LIVE BYTES FOR AN L-BYTE PACKET INSIDE THE READER CONSTRUCTOR
            pos = closeStart + innerTag.length() + 3;
        }
        return values;
    }

    private static String content(final String xml, final String tag) {
        final int openStart = open(xml, tag, 0);
        if (openStart < 0) return null;
        final int openEnd = xml.indexOf('>', openStart);
        if (openEnd < 0) return null;
        final int closeStart = xml.indexOf("</" + tag + ">", openEnd + 1);
        if (closeStart < 0) return null;
        return xml.substring(openEnd + 1, closeStart);
    }

    // POSITION OF THE NEXT OPENING TAG NAMED EXACTLY tag (NOT A LONGER NAME SHARING ITS PREFIX), OR -1
    private static int open(final String xml, final String tag, final int from) {
        int pos = from;
        while (true) {
            final int start = xml.indexOf("<" + tag, pos);
            if (start < 0) return -1;
            final int nameEnd = start + tag.length() + 1;
            if (nameEnd >= xml.length()) return -1;
            final char next = xml.charAt(nameEnd);
            if (next == '>' || next == '/' || Character.isWhitespace(next)) return start;
            pos = nameEnd;
        }
    }

    private static String clean(final String value) {
        if (value == null) return null;
        final String clean = value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .trim();
        return clean.isEmpty() ? null : clean;
    }
}
