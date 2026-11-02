package org.watermedia.api.codecs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalized image metadata shared by every image decoder.
 *
 * <p>Common fields get explicit accessors. Format-specific, uncommon, private, or non-standard
 * metadata is exposed through {@link #values()} using keys defined in {@link CodecsAPI}.
 * String accessors return {@code null} when the value does not exist or is blank; collection
 * accessors return an empty immutable collection so callers can iterate without null checks.
 * <p>Mutation is not synchronized. Fluent mutators return this instance and reject changes to {@link #EMPTY}
 * and to the {@link #frozen()} snapshots readers hand out. Custom values use only JDK types (text, numbers,
 * {@code byte[]}, arrays, lists and maps of those), so every host can read them.
 */
public final class ImageMetadata {
    public static final ImageMetadata EMPTY = new ImageMetadata(true);

    private final boolean readOnly;
    private String title;
    private String description;
    private final List<String> authors = new ArrayList<>();
    private String copyright;
    private final List<String> comments = new ArrayList<>();
    private String creationTime;
    private String software;
    private String source;
    private final Map<String, Object> values = new LinkedHashMap<>();

    /** Creates empty, mutable metadata. */
    public ImageMetadata() {
        this(false);
    }

    private ImageMetadata(final boolean readOnly) {
        this.readOnly = readOnly;
    }

    // A READ-ONLY COPY; LISTS, THE VALUE MAP AND EVERY NESTED MAP OR LIST ARE COPIED, SCALARS AND ARRAYS ARE SHARED
    private ImageMetadata(final ImageMetadata source) {
        this.readOnly = true;
        this.title = source.title;
        this.description = source.description;
        this.authors.addAll(source.authors);
        this.copyright = source.copyright;
        this.comments.addAll(source.comments);
        this.creationTime = source.creationTime;
        this.software = source.software;
        this.source = source.source;
        for (final Map.Entry<String, Object> entry: source.values.entrySet()) this.values.put(entry.getKey(), freeze(entry.getValue()));
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Returns a read-only snapshot of this metadata; later changes to this instance never reach it. */
    public ImageMetadata frozen() {
        return new ImageMetadata(this);
    }

    /** Returns whether all normalized fields, lists and the custom-value map are empty. */
    public boolean empty() {
        return this.title() == null
                && this.description() == null
                && this.authors.isEmpty()
                && this.copyright() == null
                && this.comments.isEmpty()
                && this.creationTime() == null
                && this.software() == null
                && this.source() == null
                && this.values.isEmpty();
    }

    /** Returns the trimmed image title, or null when absent. */
    public String title() {
        return clean(this.title);
    }

    /** Stores a trimmed title; null or blank clears it. Returns this instance. */
    public ImageMetadata title(final String value) {
        this.checkMutable();
        this.title = clean(value);
        return this;
    }

    /** Returns the trimmed image description, or null when absent. */
    public String description() {
        return clean(this.description);
    }

    /** Stores a trimmed description; null or blank clears it. Returns this instance. */
    public ImageMetadata description(final String value) {
        this.checkMutable();
        this.description = clean(value);
        return this;
    }

    /** Returns authors in insertion order; a nonempty result is an unmodifiable view of the stored list. */
    public List<String> authors() {
        return this.authors.isEmpty() ? List.of() : Collections.unmodifiableList(this.authors);
    }

    /** Appends a trimmed nonblank author without deduplication; ignores null or blank. Returns this instance. */
    public ImageMetadata author(final String value) {
        this.checkMutable();
        final String clean = clean(value);
        if (clean != null) this.authors.add(clean);
        return this;
    }

    /** Returns the trimmed copyright notice, or null when absent. */
    public String copyright() {
        return clean(this.copyright);
    }

    /** Stores a trimmed copyright notice; null or blank clears it. Returns this instance. */
    public ImageMetadata copyright(final String value) {
        this.checkMutable();
        this.copyright = clean(value);
        return this;
    }

    /** Returns comments in insertion order; a nonempty result is an unmodifiable view of the stored list. */
    public List<String> comments() {
        return this.comments.isEmpty() ? List.of() : Collections.unmodifiableList(this.comments);
    }

    /** Appends a trimmed nonblank comment without deduplication; ignores null or blank. Returns this instance. */
    public ImageMetadata comment(final String value) {
        this.checkMutable();
        final String clean = clean(value);
        if (clean != null) this.comments.add(clean);
        return this;
    }

    /** Returns the trimmed creation-time text as supplied by the decoder, without parsing its date format. */
    public String creationTime() {
        return clean(this.creationTime);
    }

    /** Stores trimmed creation-time text without date parsing; null or blank clears it. Returns this instance. */
    public ImageMetadata creationTime(final String value) {
        this.checkMutable();
        this.creationTime = clean(value);
        return this;
    }

    /** Returns the trimmed creating-software identifier, or null when absent. */
    public String software() {
        return clean(this.software);
    }

    /** Stores a trimmed creating-software identifier; null or blank clears it. Returns this instance. */
    public ImageMetadata software(final String value) {
        this.checkMutable();
        this.software = clean(value);
        return this;
    }

    /** Returns the trimmed source description, or null when absent. */
    public String source() {
        return clean(this.source);
    }

    /** Stores a trimmed source description; null or blank clears it. Returns this instance. */
    public ImageMetadata source(final String value) {
        this.checkMutable();
        this.source = clean(value);
        return this;
    }

    /** Returns an unmodifiable custom-value map; nonempty results are live views and values are not copied. */
    public Map<String, Object> values() {
        return this.values.isEmpty() ? Map.of() : Collections.unmodifiableMap(this.values);
    }

    /** Returns the stored custom value, or null for a null, blank or unknown key; lookup does not trim keys. */
    public Object value(final String key) {
        return key == null || key.isBlank() ? null : this.values.get(key);
    }

    /**
     * Stores a custom value by reference, replacing any previous value for the unchanged key. Null or blank keys, null or
     * blank text and empty byte arrays, lists or maps are ignored and leave an existing entry in place. Returns this instance.
     */
    public ImageMetadata put(final String key, final Object value) {
        this.checkMutable();
        if (key == null || key.isBlank() || value == null) return this;
        if (value instanceof final String text && clean(text) == null) return this;
        if (value instanceof final byte[] bytes && bytes.length == 0) return this;
        if (value instanceof final List<?> list && list.isEmpty()) return this;
        if (value instanceof final Map<?, ?> map && map.isEmpty()) return this;
        this.values.put(key, value);
        return this;
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void checkMutable() {
        if (this.readOnly) throw new UnsupportedOperationException("This ImageMetadata is read-only");
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // READERS PUBLISH LIVE VIEWS OVER THEIR OWN MAPS AND LISTS; A SNAPSHOT COPIES THEM SO NEITHER SIDE CAN CHANGE THE OTHER
    private static Object freeze(final Object value) {
        if (value instanceof final Map<?, ?> map) {
            final Map<Object, Object> copy = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry: map.entrySet()) copy.put(entry.getKey(), freeze(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        return value instanceof final List<?> list ? List.copyOf(list) : value;
    }

    private static String clean(final String value) {
        if (value == null) return null;
        final String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
}
