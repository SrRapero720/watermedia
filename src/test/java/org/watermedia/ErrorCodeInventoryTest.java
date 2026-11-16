package org.watermedia;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the {@code wm_err} codes of the main sources: every exception, error log and {@code safeCall} message carries
 * one {@code WaterMedia.ERR_*} constant, no value or constant names two sites, each value sits in the range of the
 * package that uses it and its message starts with "Failed to".
 */
@DisplayName("Error code inventory")
public class ErrorCodeInventoryTest {
    private static final Path SOURCES = Path.of("src", "main", "java", "org", "watermedia");
    private static final String REGISTRY = "WaterMedia.java";
    private static final String UNKNOWN = "ERR_UNKNOWN";
    private static final Pattern DECLARATION = Pattern.compile("\\bpublic static final int (ERR_[A-Z0-9_]+) = (\\d+);");
    private static final Pattern USE = Pattern.compile("\\berrCode\\(([^)]*)\\)");
    private static final Pattern CONSTANT = Pattern.compile("(?:WaterMedia\\.)?(ERR_[A-Z0-9_]+)");
    // AN ERROR SITE: AN EXCEPTION OR ERROR BEING BUILT, AN ERROR LOG LINE OR THE MESSAGE ThreadTool.safeCall LOGS AS ONE
    private static final Pattern SITE = Pattern.compile("\\bnew [\\w.]*(?:Exception|Error)\\(|\\bLOGGER\\.error\\(|\\bThreadTool\\.safeCall\\(");
    // SITES THAT CARRY NO CODE OF THEIR OWN: safeCall LOGS THE MESSAGE ITS CALLER ALREADY CODED
    private static final List<String> EXEMPT = List.of("LOGGER.error(it, log, e)");
    // FIRST AND LAST CODE OF EACH PACKAGE BY THE PATH OF ITS FILES; THE EMPTY PATH IS THE ROOT PACKAGE, 0 MEANS UNKNOWN
    // AND 9000 TO 9999 BELONG TO THE BINARIES AND THE EXTENSIONS
    private static final Map<String, int[]> RANGES = Map.of(
            "", new int[] { 1, 99 },
            "tools/", new int[] { 100, 399 },
            "api/util/", new int[] { 400, 699 },
            "api/network/", new int[] { 700, 999 },
            "api/player/", new int[] { 1_000, 1_999 },
            "api/platform/", new int[] { 2_000, 3_999 },
            "api/codecs/", new int[] { 4_000, 7_999 },
            "bootstrap/", new int[] { 8_000, 8_999 });
    // RELATIVE PATH OF EVERY MAIN SOURCE AND ITS TEXT; NAME AND VALUE OF EVERY CONSTANT WaterMedia DECLARES
    private static final Map<String, String> FILES = new TreeMap<>();
    private static final Map<String, Integer> CODES = new TreeMap<>();

    @BeforeAll
    static void read() throws IOException {
        assumeTrue(Files.isDirectory(SOURCES), "The main sources are not under the working directory");
        try (final Stream<Path> walk = Files.walk(SOURCES)) {
            for (final Path file: walk.filter(path -> path.toString().endsWith(".java")).toList())
                FILES.put(SOURCES.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        }
        final Matcher declaration = DECLARATION.matcher(code(FILES.get(REGISTRY)));
        while (declaration.find()) CODES.put(declaration.group(1), Integer.parseInt(declaration.group(2)));
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================
    // THE SOURCE WITH ITS COMMENTS AND THE CONTENT OF ITS STRING AND CHAR LITERALS BLANKED: SAME LENGTH, SAME LINES, SO A
    // MATCH IN IT IS REAL CODE AND ITS OFFSET POINTS AT THE SAME PLACE OF THE SOURCE
    private static String code(final String source) {
        final char[] out = source.toCharArray();
        // 0 CODE, 1 LINE COMMENT, 2 BLOCK COMMENT, 3 STRING, 4 CHAR, 5 TEXT BLOCK
        int state = 0;
        for (int i = 0; i < out.length; i++) {
            final char c = out[i];
            final char next = i + 1 < out.length ? out[i + 1] : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '/' && next == '/') state = 1;
                    else if (c == '/' && next == '*') state = 2;
                    else if (source.startsWith("\"\"\"", i)) { state = 5; i += 2; }
                    else if (c == '"') state = 3;
                    else if (c == '\'') state = 4;
                    continue;
                }
                case 1 -> { if (c == '\n') state = 0; }
                case 2 -> {
                    if (c == '*' && next == '/') { out[i] = out[i + 1] = ' '; i++; state = 0; continue; }
                }
                case 3, 4 -> {
                    if (c == '\\') { out[i] = out[i + 1] = ' '; i++; continue; }
                    if (c == (state == 3 ? '"' : '\'')) { state = 0; continue; }
                }
                default -> {
                    if (c == '\\') { out[i] = out[i + 1] = ' '; i++; continue; }
                    if (source.startsWith("\"\"\"", i)) { state = 0; i += 2; continue; }
                }
            }
            if (c != '\n' && c != '\r') out[i] = ' ';
        }
        return new String(out);
    }

    private static String at(final String file, final String text, final int offset) {
        return file + ":" + (text.substring(0, offset).split("\n", -1).length);
    }

    // ==========================================================================
    // TESTS
    // ==========================================================================
    @Test
    @DisplayName("every exception, error log and safeCall message of the main sources carries a code")
    void everySiteIsCoded() {
        final List<String> missing = new ArrayList<>();
        FILES.forEach((file, source) -> {
            final String code = code(source);
            final List<Integer> starts = new ArrayList<>();
            final Matcher site = SITE.matcher(code);
            while (site.find()) starts.add(site.start());
            for (int i = 0; i < starts.size(); i++) {
                // A SITE RUNS UP TO THE END OF ITS STATEMENT OR TO THE NEXT SITE INSIDE IT, WHICH NEEDS ITS OWN CODE
                final int start = starts.get(i);
                final int end = code.indexOf(';', start);
                final int next = i + 1 < starts.size() ? starts.get(i + 1) : code.length();
                final String segment = code.substring(start, Math.min(end < 0 ? code.length() : end, next));
                if (!segment.contains("errCode(") && EXEMPT.stream().noneMatch(segment::startsWith))
                    missing.add(at(file, code, start) + " " + segment.strip().replaceAll("\\s+", " "));
            }
        });
        assertEquals(List.of(), missing, "Sites without a wm_err code");
    }

    @Test
    @DisplayName("every code is a WaterMedia constant used by one site, in the range of its package and ahead of a Failed to message")
    void everyCodeNamesOneSite() {
        final Map<String, String> used = new TreeMap<>();
        final List<String> wrong = new ArrayList<>();
        FILES.forEach((file, source) -> {
            final String code = code(source);
            final int[] range = RANGES.entrySet().stream()
                    .filter(entry -> entry.getKey().isEmpty() ? file.indexOf('/') < 0 : file.startsWith(entry.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            final Matcher use = USE.matcher(code);
            while (use.find()) {
                // THE DECLARATION OF errCode ITSELF TAKES A PARAMETER, NOT A CODE
                if (use.group(1).startsWith("final ")) continue;
                final String where = use.group(1) + " at " + at(file, code, use.start());
                final Matcher constant = CONSTANT.matcher(use.group(1));
                final Integer value = constant.matches() ? CODES.get(constant.group(1)) : null;
                if (value == null) wrong.add(where + " is not a constant declared in " + REGISTRY);
                else if (range == null || value < range[0] || value > range[1]) wrong.add(where + " (" + value + ") is outside the range of its package");
                else if (!source.startsWith(" + \"Failed to ", use.end())) wrong.add(where + " is not followed by a Failed to message");
                if (value == null) continue;
                final String first = used.putIfAbsent(constant.group(1), where);
                if (first != null) wrong.add(where + " is already used by " + first);
            }
        });
        assertFalse(used.isEmpty(), "No wm_err code was found: the scan is broken");
        assertEquals(List.of(), wrong, "Misused codes");
    }

    @Test
    @DisplayName("no value belongs to two constants and every constant but the unknown one has its site")
    void everyConstantIsUniqueAndUsed() {
        final Map<Integer, String> values = new TreeMap<>();
        final List<String> wrong = new ArrayList<>();
        final String sources = String.join("\n", FILES.values().stream().map(ErrorCodeInventoryTest::code).toList());
        CODES.forEach((name, value) -> {
            final String first = values.putIfAbsent(value, name);
            if (first != null) wrong.add(name + " repeats the value " + value + " of " + first);
            if (!name.equals(UNKNOWN) && !Pattern.compile("\\berrCode\\((?:WaterMedia\\.)?" + name + "\\)").matcher(sources).find())
                wrong.add(name + " (" + value + ") has no site");
        });
        assertEquals(0, CODES.get(UNKNOWN), "The unknown code is 0");
        assertEquals(List.of(), wrong, "Broken constants");
    }
}
