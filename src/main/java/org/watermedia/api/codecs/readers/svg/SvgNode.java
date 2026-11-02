package org.watermedia.api.codecs.readers.svg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A parsed SVG element: its local tag name (no namespace prefix), the presentation attributes with the inline
 * {@code style} declarations overlaid, and the children filled during parsing; the renderer dispatches on {@link #tag()}.
 */
record SvgNode(String tag, Map<String, String> attrs, List<SvgNode> children) {
    SvgNode(final String tag, final Map<String, String> attrs) {
        this(tag, attrs, new ArrayList<>());
    }

    String attr(final String name) {
        return this.attrs.get(name);
    }
}
