package org.watermedia.api.codecs.common.png;

/**
 * PNG Color Types as defined in the PNG specification.
 *
 * @see <a href="https://www.w3.org/TR/png-3/#3colourType">PNG Specification - Colour Type</a>
 */
public enum ColorType {
    GREYSCALE,        // 0
    FORBIDDEN_1,      // 1 (NOT VALID)
    TRUECOLOR,        // 2
    INDEXED,          // 3
    GREYSCALE_ALPHA,  // 4
    FORBIDDEN_5,      // 5 (NOT VALID)
    TRUECOLOR_ALPHA;  // 6

    private static final ColorType[] VALUES = values();

    /**
     * Returns the ColorType for the given ordinal value.
     * @throws IllegalArgumentException if value is out of range (not 0-6)
     */
    public static ColorType of(final int value) {
        if (value < 0 || value >= VALUES.length) throw new IllegalArgumentException("Invalid PNG color type: " + value);
        return VALUES[value];
    }
}
