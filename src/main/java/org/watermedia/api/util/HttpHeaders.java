package org.watermedia.api.util;

import org.watermedia.WaterMedia;
import org.watermedia.api.platform.Platform;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Insertion-ordered, case-insensitive, multi-valued HTTP header bag.
 * <p>
 * Used by {@link NetRequest} for both the headers we send and the headers we receive.
 * {@link #toRawString()} produces the {@code "Name: Value\r\n..."} form expected by raw
 * consumers like FFmpeg's {@code -headers} option; {@link #toString()} lists header names only.
 * A bag shared across threads (every resolved source carries one) is {@link #freeze() frozen}:
 * its mutators then throw, and {@link #HttpHeaders(HttpHeaders) copying} it yields a mutable bag again.
 */
public final class HttpHeaders implements Iterable<HttpHeaders.Entry> {
    private List<Entry> entries = new ArrayList<>();

    /** Creates an empty, mutable bag. */
    public HttpHeaders() {}

    /** Creates a mutable copy of {@code other}, frozen or not; null yields an empty bag. */
    public HttpHeaders(final HttpHeaders other) {
        if (other != null) this.entries.addAll(other.entries);
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Creates the default bag platforms stamp on each {@link Platform.Source}: WaterMedia User-Agent, {@link NetRequest#ACCEPT_MEDIA} and a Referer from {@code uri}'s origin. */
    public static HttpHeaders defaults(final URI uri) {
        final HttpHeaders h = new HttpHeaders().set("User-Agent", WaterMedia.USER_AGENT).set("Accept", NetRequest.ACCEPT_MEDIA);
        final String referer = referer(uri);
        if (referer != null) h.set("Referer", referer);
        return h;
    }

    /** Makes this bag immutable: every later mutator throws {@link UnsupportedOperationException}. */
    public HttpHeaders freeze() {
        this.entries = Collections.unmodifiableList(this.entries);
        return this;
    }

    /** Replaces every value of {@code name}, matched case-insensitively, with a single entry. */
    public HttpHeaders set(final String name, final String value) {
        final Entry entry = new Entry(name, value);
        this.removeAll(name);
        this.entries.add(entry);
        return this;
    }

    /** Appends a value for {@code name}, keeping the existing ones. */
    public HttpHeaders add(final String name, final String value) {
        this.entries.add(new Entry(name, value));
        return this;
    }

    /** Applies {@code other} on top of this bag: the first value of each name replaces ({@link #set}), later ones append ({@link #add}); null, empty or this bag change nothing. */
    public HttpHeaders merge(final HttpHeaders other) {
        if (other == null || other == this || other.empty()) return this;
        final Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Entry e: other.entries) {
            if (seen.add(e.name)) this.set(e.name, e.value);
            else this.add(e.name, e.value);
        }
        return this;
    }

    /** Removes every entry whose name matches case-insensitively. */
    public HttpHeaders removeAll(final String name) {
        this.entries.removeIf(entry -> DataTool.equalsIgnoreCase(entry.name, name));
        return this;
    }

    /** Returns the first value of {@code name}, or null when there is none. */
    public String value(final String name) {
        for (final Entry e: this.entries) {
            if (DataTool.equalsIgnoreCase(e.name, name)) return e.value;
        }
        return null;
    }

    /** Returns every value of {@code name} in insertion order. */
    public List<String> values(final String name) {
        final List<String> out = new ArrayList<>();
        for (final Entry e: this.entries) {
            if (DataTool.equalsIgnoreCase(e.name, name)) out.add(e.value);
        }
        return out;
    }

    /** Whether any value exists for {@code name}, matched case-insensitively. */
    public boolean has(final String name) {
        for (final Entry e: this.entries) {
            if (DataTool.equalsIgnoreCase(e.name, name)) return true;
        }
        return false;
    }

    /** Whether the bag has no entries. */
    public boolean empty() { return this.entries.isEmpty(); }

    /** Number of stored values, counting repeated names. */
    public int count() { return this.entries.size(); }

    /** Immutable snapshot of every entry, values included, in insertion order. */
    public List<Entry> entries() { return List.copyOf(this.entries); }

    /** Serializes the bag as {@code "Name: Value\r\n"} lines, the form of FFmpeg's {@code headers} option; empty without entries. */
    public String toRawString() {
        if (this.entries.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder(this.entries.size() * 32);
        for (final Entry e: this.entries) sb.append(e.name).append(": ").append(e.value).append("\r\n");
        return sb.toString();
    }

    @Override
    public Iterator<Entry> iterator() { return Collections.unmodifiableList(this.entries).iterator(); }

    @Override
    public String toString() { return "HttpHeaders" + this.entries; }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================
    // REPEATED HEADER NAMES USE ADD AFTER THE FIRST VALUE SO NONE ARE LOST.
    void writeTo(final URLConnection conn) {
        final Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (final Entry e: this.entries) {
            if (seen.add(e.name)) conn.setRequestProperty(e.name, e.value);
            else conn.addRequestProperty(e.name, e.value);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // ORIGIN-SHAPED REFERER scheme://host[:port]/ KEEPING AN EXPLICIT PORT, OR NULL WITHOUT A HOST
    static String referer(final URI uri) {
        if (uri == null || uri.getHost() == null) return null;
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "") + "/";
    }

    // THE STATUS LINE HAS NO HEADER NAME AND IS NOT PART OF THE SNAPSHOT.
    static HttpHeaders fromResponse(final URLConnection conn) {
        final HttpHeaders out = new HttpHeaders();
        final Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) return out;
        for (final var entry: fields.entrySet()) {
            final String name = entry.getKey();
            if (name == null) continue;
            for (final String value: entry.getValue()) if (value != null) out.add(name, value);
        }
        return out;
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================
    /** One header field: an HTTP token name and a value free of control characters. */
    public record Entry(String name, String value) {
        public Entry {
            if (name == null || value == null) throw new IllegalArgumentException("Header name and value are required");
            if (name.isEmpty()) throw new IllegalArgumentException("Header name cannot be empty");
            for (int i = 0; i < name.length(); i++) {
                final char c = name.charAt(i);
                if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && "!#$%&'*+-.^_`|~".indexOf(c) < 0)
                    throw new IllegalArgumentException("Header name must be an HTTP token");
            }
            // RAW NATIVE CONSUMERS REQUIRE THE SAME FIELD BOUNDARIES AS HTTP CONNECTIONS.
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if ((c < 0x20 && c != '\t') || c == 0x7f)
                    throw new IllegalArgumentException("Header value contains a prohibited control character");
            }
        }

        // NAME ONLY: LOGGED SOURCES MUST NEVER PRINT COOKIE OR AUTHORIZATION VALUES
        @Override
        public String toString() { return this.name; }
    }
}
