package org.watermedia.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

/**
 * Shared Gson binding and lenient accessors: one {@link Gson} instance for the whole codebase, and accessors
 * that never throw (a missing key, JSON null or a wrongly typed value yields null or the default), nested as
 * {@code str(obj(root, "a", "b"), "c")}.
 */
public final class JSONTool {
    private static final Gson GSON = new Gson();

    private JSONTool() {}

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Binds a JSON tree to {@code type}, or returns null for a null tree; a shape mismatch throws {@link JsonSyntaxException}. */
    public static <T> T parse(final JsonElement json, final Class<T> type) {
        return json == null ? null : GSON.fromJson(json, type);
    }

    /** Parses JSON text into {@code type}, or returns null for null, empty or {@code null} text; invalid or mismatched JSON throws {@link JsonSyntaxException}. */
    public static <T> T parse(final String json, final Class<T> type) {
        return json == null ? null : GSON.fromJson(json, type);
    }

    /** Serializes {@code value} to JSON text, {@code "null"} for null. */
    public static String write(final Object value) {
        return GSON.toJson(value);
    }

    /** Returns the object at the end of the {@code keys} path, {@code root} itself without keys, or {@code null} when a step is absent, JSON {@code null} or not an object. */
    public static JsonObject obj(final JsonElement root, final String... keys) {
        JsonElement current = root;
        for (final String key: keys) {
            if (current == null || !current.isJsonObject()) return null;
            current = current.getAsJsonObject().get(key);
        }
        return current != null && current.isJsonObject() ? current.getAsJsonObject() : null;
    }

    /** Returns the array at {@code key}, or {@code null} when absent, JSON {@code null} or not an array. */
    public static JsonArray arr(final JsonObject o, final String key) {
        final JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** Returns the string, number or boolean at {@code key} as text, or {@code null} when absent, JSON {@code null}, an object or an array. */
    public static String str(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        return p == null ? null : p.getAsString();
    }

    /** Returns the finite number at {@code key}, numeric strings included, or {@code 0} when absent, JSON {@code null} or not a number. */
    public static double num(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return 0d;
        try {
            final double value = p.getAsDouble();
            return Double.isFinite(value) ? value : 0d;
        } catch (final NumberFormatException e) {
            return 0d;
        }
    }

    /** Returns the boolean at {@code key}, {@code "true"} strings included, or {@code false} when absent, JSON {@code null} or not a boolean. */
    public static boolean bool(final JsonObject o, final String key) {
        final JsonPrimitive p = primitive(o, key);
        return p != null && p.getAsBoolean();
    }

    /** Returns the int at {@code key}, numeric strings included, or {@code def} when absent, JSON {@code null} or not a number. */
    public static int intOr(final JsonObject o, final String key, final int def) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return def;
        try { return p.getAsInt(); }
        catch (final NumberFormatException e) { return def; }
    }

    /** Returns the long at {@code key}, numeric strings included, or {@code def} when absent, JSON {@code null} or not a number. */
    public static long longOr(final JsonObject o, final String key, final long def) {
        final JsonPrimitive p = primitive(o, key);
        if (p == null) return def;
        try { return p.getAsLong(); }
        catch (final NumberFormatException e) { return def; }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE ONLY SHAPE THE ACCESSORS READ; A NULL OBJECT, A MISSING KEY, JSON NULL, OBJECTS AND ARRAYS ALL YIELD NULL
    private static JsonPrimitive primitive(final JsonObject o, final String key) {
        final JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsJsonPrimitive() : null;
    }
}
