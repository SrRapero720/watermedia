package org.watermedia.bootstrap.app.screen;

import me.srrapero720.waterconfig.ConfigGroup;
import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.api.Control;
import me.srrapero720.waterconfig.api.IConfigField;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.ui.AppTheme;

import java.awt.Color;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/** Tree the settings screen edits: each WaterConfig spec as sections of settings, with the spec's autosave state. */
final class SettingsModel {
    private SettingsModel() {}

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    // A ROOT NODE NAMED AFTER THE SPEC (HIDDEN WITHOUT DIRECT FIELDS), THEN EVERY GROUP IN DFS ORDER WITH ONE
    // INDENT PER LEVEL; EACH NODE LISTS ONLY ITS DIRECT FIELDS
    static void collectSections(final ConfigSpec configSpec, final SettingSpec spec) {
        final String specName = titleCase(configSpec.name());
        final SettingSection home = new SettingSection(specName, groupDetail(configSpec, "root settings"), specName, 0);
        final Collection<IConfigField<?, ?>> fields = configSpec.getFields();
        for (final IConfigField<?, ?> field: fields) {
            if (!(field instanceof ConfigGroup)) home.settings.add(new ConfigSetting(field, field.name(), spec.stops.get(field.name())));
        }
        if (!home.settings.isEmpty()) spec.sections.add(home);
        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof final ConfigGroup child) collectGroup(child, 0, "", "", spec);
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    private static void collectGroup(final ConfigGroup group, final int depth, final String parentKey,
                                     final String parentPath, final SettingSpec spec) {
        final String key = parentKey.isEmpty() ? group.name() : parentKey + "." + group.name();
        final String path = parentPath.isEmpty() ? group.name() : parentPath + " / " + group.name();
        final SettingSection section = new SettingSection(titleCase(group.name()), groupDetail(group, key), path, depth);
        final Collection<IConfigField<?, ?>> fields = group.getFields();
        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof ConfigGroup) continue;
            final String fieldKey = key + "." + field.name();
            section.settings.add(new ConfigSetting(field, fieldKey, spec.stops.get(fieldKey)));
        }
        // THE NODE STAYS EVEN WITH NO DIRECT FIELDS: IT IS THE TREE PATH TO ITS CHILD GROUPS
        spec.sections.add(section);
        for (final IConfigField<?, ?> field: fields) {
            if (field instanceof final ConfigGroup child) collectGroup(child, depth + 1, key, path, spec);
        }
    }

    // TURNS AN IDENTIFIER INTO A DISPLAY NAME: _ AND - BECOME SPACES, camelCase SPLITS INTO CAPITALIZED
    // WORDS ("pngUseBKGDChunk" -> "Png Use BKGD Chunk")
    static String titleCase(final String value) {
        if (value == null || value.isBlank()) return "Settings";
        final String spaced = value.replace('_', ' ').replace('-', ' ');
        final StringBuilder out = new StringBuilder(spaced.length() + 4);
        boolean upper = true;
        for (int i = 0; i < spaced.length(); i++) {
            final char c = spaced.charAt(i);
            if (Character.isWhitespace(c)) {
                upper = true;
                out.append(c);
                continue;
            }
            if (i > 0 && Character.isUpperCase(c)) {
                final char prev = spaced.charAt(i - 1);
                final boolean nextLower = i + 1 < spaced.length() && Character.isLowerCase(spaced.charAt(i + 1));
                // BREAK ON lower->UPPER AND ON THE LAST CAPITAL OF AN ACRONYM RUN ("BKGDChunk" -> "BKGD Chunk")
                if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextLower)) out.append(' ');
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    private static String groupDetail(final ConfigGroup group, final String fallback) {
        for (final String comment: group.comments()) {
            if (comment != null && !comment.isBlank()) return comment.trim();
        }
        return fallback;
    }

    // ==========================================================================
    // NESTED TYPES: SPECS AND SECTIONS
    // ==========================================================================

    enum SaveState {
        READY, SAVING, SAVED, FAILED_ONCE, RESAVING, FAILED_TWICE
    }

    // saving GATES CONCURRENT WRITERS AND resavePending COALESCES MID-SAVE CHANGES; RENDER-THREAD CONFINED
    // (THE WORKER PUBLISHES ITS OUTCOME THROUGH ctx.execute), SO NO volatile IS NEEDED
    static final class SaveMachine {
        SaveState state = SaveState.READY;
        int failures;
        boolean saving;
        boolean resavePending;
    }

    // A CACHED REVERSE-SPEC PARSE TIED TO ITS SOURCE FILE'S mtime (-1 = ABSENT); spec IS null WHEN THE FILE
    // WAS ABSENT/UNPARSEABLE AT THAT mtime, CACHING THE "SKIP THIS TAB" DECISION TOO
    record ReverseSpec(long mtime, ConfigSpec spec) {}

    static final class SettingSpec {
        final String title;
        final String subtitle;
        // null WHEN WATERCONFIG DID NOT EXPOSE THE SPEC: THE TAB SHOWS A PLACEHOLDER AND NEVER SAVES
        final ConfigSpec configSpec;
        // RUNS AFTER EVERY MUTATION OF THIS SPEC: THE APP SPEC PUSHES ITS VALUES INTO THE LIVE APP
        // STATE HERE (AppConfig.apply) SO EDITS TAKE EFFECT IMMEDIATELY; null WHEN NOTHING TO APPLY
        final Runnable applier;
        // THE SCREEN-OWNED SAVE MACHINE OF THIS SPEC SLOT, SHARED ACROSS REBUILDS (SEE saves)
        final SaveMachine save;
        // PIXEL-ICON NAME FOR THE TAB, OR null FOR THE APP TEXTURE ICON OF WATERMEDIA'S OWN SPECS
        final String iconName;
        // FLAT DFS-ORDERED SECTION TREE: depth EXPRESSES NESTING, PGUP/PGDN WALKS THIS LIST IN ORDER
        final List<SettingSection> sections = new ArrayList<>();
        // DISCRETE VALUES OF NUMBER FIELDS, BY DOTTED FIELD KEY; WATERCONFIG ONLY KNOWS THEIR BOUNDS
        final Map<String, Stops> stops;

        SettingSpec(final String title, final String subtitle, final ConfigSpec configSpec,
                    final Runnable applier, final SaveMachine save, final String iconName, final Map<String, Stops> stops) {
            this.title = title;
            this.subtitle = subtitle;
            this.configSpec = configSpec;
            this.applier = applier;
            this.save = save;
            this.iconName = iconName;
            this.stops = stops;
        }
    }

    // THE ONLY VALUES A NUMBER FIELD MAY TAKE, IN ORDER, AND HOW EACH ONE READS; ITS SEEK BAR SPACES THEM EVENLY
    record Stops(double[] values, DoubleFunction<String> label) {}

    static final class SettingSection {
        final String name;
        final String detail;
        // HEADER SUBTITLE PATH ("audio" OR "group1 / group2"); THE HOME NODE CARRIES THE SPEC NAME
        final String path;
        // TREE NESTING LEVEL (0 = TOP); THE SECTION COLUMN INDENTS BY THIS
        final int depth;
        final List<Setting> settings = new ArrayList<>();

        SettingSection(final String name, final String detail, final String path, final int depth) {
            this.name = name;
            this.detail = detail;
            this.path = path;
            this.depth = depth;
        }
    }

    // ==========================================================================
    // NESTED TYPES: SETTINGS
    // ==========================================================================

    abstract static class Setting {
        protected final String label;
        protected final String key;
        protected final String type;
        protected final String note;
        protected final boolean mutable;

        protected Setting(final String label, final String key, final String type, final String note, final boolean mutable) {
            this.label = label;
            this.key = key;
            this.type = type;
            // BLANK STAYS BLANK: A SETTING WITHOUT A DESCRIPTION SIMPLY SHOWS NO TOOLTIP
            this.note = note == null || note.isBlank() ? "" : note.trim();
            this.mutable = mutable;
        }

        abstract String valueLabel();

        // DEFAULTS OF A READ-ONLY, NON-NUMERIC SETTING; ConfigSetting OVERRIDES WHAT ITS FIELD SUPPORTS
        String editValue() { return this.valueLabel(); }
        boolean editableText() { return false; }
        boolean isEnumControl() { return false; }
        boolean isSeekbarControl() { return false; }
        boolean isSpinnerControl() { return false; }
        boolean booleanValue() { return false; }
        Class<?> valueType() { return Object.class; }
        String widestValueLabel() { return this.valueLabel(); }
        List<String> optionLabels() { return List.of(); }
        int selectedOptionIndex() { return -1; }
        boolean selectOption(final int index) { return false; }
        Control control() { return Control.LABEL; }
        String suffix() { return ""; }
        float rangePercent() { return 0f; }
        boolean adjust(final int direction, final int step) { return false; }
        void commit(final String value) {}
        void reset() {}

        // SETS A RANGED NUMBER FROM A 0..1 FRACTION (SEEK BAR SKIN ADAPTER)
        boolean percent(final double pct) { return false; }

        // CURRENT NUMERIC VALUE FOR THE SPINNER SKIN; 0 WHEN THE SETTING IS NOT A NUMBER
        double number() { return 0d; }

        // SETS A NUMBER DIRECTLY FROM THE SPINNER (STEPPED OR TYPED), CLAMPED THROUGH THE SAME PATH AS
        // commit/adjust; RETURNS WHETHER THE STORED VALUE ACTUALLY CHANGED
        boolean number(final double value) { return false; }

        Color accent() {
            return this.mutable ? AppTheme.NEON_LIGHT : AppTheme.TEXT_FAINT;
        }
    }

    static final class ReadOnlySetting extends Setting {
        private final Supplier<String> value;

        ReadOnlySetting(final String label, final String key, final String type,
                        final String note, final Supplier<String> value) {
            super(label, key, type, note, false);
            this.value = value;
        }

        @Override
        String valueLabel() {
            final String current = this.value.get();
            return current == null || current.isBlank() ? "EMPTY" : current;
        }
    }

    static final class ConfigSetting extends Setting {
        private final IConfigField<?, ?> field;
        private final Class<?> valueType;
        private final Control control;
        private final String suffix;
        private final Double minValue;
        private final Double maxValue;
        private final Stops stops; // null FOR A CONTINUOUS NUMBER

        ConfigSetting(final IConfigField<?, ?> field, final String key, final Stops stops) {
            super(titleCase(field.name()), key, typeName(field), joinedComments(field), fieldMutable(field));
            this.field = field;
            this.valueType = field.type();
            this.control = safeControl(field);
            final String rawSuffix = field.suffix();
            this.suffix = rawSuffix == null || rawSuffix.isBlank() ? "" : rawSuffix.trim();
            this.minValue = numberLimit(field, "minValueString");
            this.maxValue = numberLimit(field, "maxValueString");
            this.stops = stops;
        }

        // ======================================================================
        // INTERNAL API
        // ======================================================================

        @Override
        String valueLabel() {
            final Object value = this.field.get();
            if (value == null) return "EMPTY";
            if (this.stops != null) return this.stops.label().apply(this.number());
            if (value instanceof final Boolean bool) return bool ? "ON" : "OFF";
            if (value instanceof final Path path) {
                final String raw = path.toString();
                return raw.isBlank() ? "." : raw;
            }
            if (this.control == Control.PASSWORD) {
                final int len = Math.max(8, String.valueOf(value).length());
                return "*".repeat(Math.min(32, len));
            }
            return String.valueOf(value);
        }

        @Override
        String editValue() {
            final Object value = this.field.get();
            return value == null ? "" : String.valueOf(value);
        }

        @Override
        boolean editableText() {
            return this.mutable && switch (this.control) {
                case INPUT, TEXT_AREA, INPUT_PASTE, PASSWORD, INPUT_FILE, INPUT_FOLDER, COLOR_PICKER, KEYBIND -> true;
                default -> false;
            };
        }

        @Override
        boolean isEnumControl() {
            return this.valueType.isEnum()
                    && (this.control == Control.DROPDOWN || this.control == Control.RADIO_BUTTON
                    || this.control == Control.LIST_SPINNER || this.control == Control.SEGMENTED);
        }

        @Override
        boolean isSeekbarControl() {
            return this.isNumber() && this.hasUsableRange()
                    && (this.control == Control.SEEKBAR || this.control == Control.RANGE_SLIDER || this.control == Control.KNOB);
        }

        @Override
        boolean isSpinnerControl() {
            return this.isNumber() && (this.control == Control.NUMBER_SPINNER || !this.hasUsableRange());
        }

        @Override
        boolean booleanValue() {
            return Boolean.TRUE.equals(this.field.get());
        }

        @Override
        Class<?> valueType() {
            return this.valueType;
        }

        @Override
        String widestValueLabel() {
            String widest = this.valueLabel();
            if (this.stops != null) {
                for (final double value: this.stops.values()) widest = wider(widest, this.stops.label().apply(value));
                return widest;
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants != null) {
                    for (final Object constant: constants) {
                        widest = wider(widest, String.valueOf(constant));
                    }
                }
                return widest;
            }
            if (this.isNumber()) {
                if (this.minValue != null) widest = wider(widest, String.valueOf(this.numberOf(this.minValue)));
                if (this.maxValue != null) widest = wider(widest, String.valueOf(this.numberOf(this.maxValue)));
            }
            return widest;
        }

        @Override
        List<String> optionLabels() {
            final Object[] constants = this.valueType.isEnum() ? this.valueType.getEnumConstants() : null;
            if (constants == null || constants.length == 0) return List.of();
            final List<String> labels = new ArrayList<>(constants.length);
            for (final Object constant: constants) {
                labels.add(String.valueOf(constant));
            }
            return labels;
        }

        @Override
        int selectedOptionIndex() {
            if (!this.valueType.isEnum()) return -1;
            final Object[] constants = this.valueType.getEnumConstants();
            if (constants == null || constants.length == 0) return -1;
            final Object current = this.field.get();
            for (int i = 0; i < constants.length; i++) {
                if (constants[i] == current) return i;
            }
            return 0;
        }

        @Override
        boolean selectOption(final int index) {
            if (!this.valueType.isEnum()) return false;
            final Object[] constants = this.valueType.getEnumConstants();
            if (constants == null || index < 0 || index >= constants.length) return false;
            this.setValue(constants[index]);
            return true;
        }

        @Override
        Control control() {
            return this.control;
        }

        @Override
        String suffix() {
            return this.suffix;
        }

        @Override
        float rangePercent() {
            if (this.stops != null) return (float) this.stop() / Math.max(1, this.stops.values().length - 1);
            if (!this.hasUsableRange()) return 0f;
            final double value = ((Number) this.field.get()).doubleValue();
            final double min = this.minValue;
            final double max = this.maxValue;
            // hasUsableRange() ALREADY GUARANTEES MAX > MIN, SO THE SPAN IS SAFE TO DIVIDE BY DIRECTLY
            return (float) MathUtil.clamp((value - min) / (max - min), 0d, 1d);
        }

        @Override
        boolean percent(final double pct) {
            if (!this.isSeekbarControl()) return false;
            if (this.stops != null) {
                final double[] values = this.stops.values();
                return this.number(values[(int) Math.round(MathUtil.clamp(pct, 0d, 1d) * (values.length - 1))]);
            }
            // ONLY REPORT A CHANGE (AND ARM THE DRAG-SAVE / CHIME) WHEN THE STORED VALUE ACTUALLY MOVES,
            // MIRRORING number(): A SEEK TICK LANDING ON THE SAME VALUE MUST NOT TRIGGER A POINTLESS SAVE
            final double raw = this.minValue + (this.maxValue - this.minValue) * MathUtil.clamp(pct, 0d, 1d);
            final Object next = this.isInteger() ? this.numberOf(Math.round(raw)) : this.numberOf(raw);
            if (((Number) next).doubleValue() == ((Number) this.field.get()).doubleValue()) return false;
            this.setValue(next);
            return true;
        }

        @Override
        double number() {
            return this.isNumber() ? ((Number) this.field.get()).doubleValue() : 0d;
        }

        @Override
        boolean number(final double value) {
            if (!this.isNumber()) return false;
            // WRITE ONLY WHEN THE CLAMPED VALUE ACTUALLY MOVES, SO A NO-OP EDIT NEITHER SAVES NOR CHIMES
            final Object next = this.clampNumber(value);
            if (((Number) next).doubleValue() == ((Number) this.field.get()).doubleValue()) return false;
            this.setValue(next);
            return true;
        }

        @Override
        boolean adjust(final int direction, final int step) {
            if (this.isBoolean()) {
                this.setValue(!Boolean.TRUE.equals(this.field.get()));
                return true;
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants == null || constants.length == 0) return false;
                final Object current = this.field.get();
                int currentIndex = 0;
                for (int i = 0; i < constants.length; i++) {
                    if (constants[i] == current) {
                        currentIndex = i;
                        break;
                    }
                }
                final int next = Math.floorMod(currentIndex + direction, constants.length);
                this.setValue(constants[next]);
                return true;
            }
            if (this.stops != null) {
                final double[] values = this.stops.values();
                return this.number(values[MathUtil.clamp(this.stop() + direction * step, 0, values.length - 1)]);
            }
            if (this.isInteger()) {
                final Number current = (Number) this.field.get();
                final Object next = parseNumber(String.valueOf(current.longValue() + (long) direction * Math.max(1, step)), this.valueType);
                this.setValue(this.clampNumber(next));
                return true;
            }
            if (this.isNumber()) {
                final Number current = (Number) this.field.get();
                // FRACTIONAL FIELD: DERIVE THE PER-PRESS DELTA FROM THE SPAN WITHOUT FLOORING IT TO 1.0
                // SO INTERMEDIATE FLOAT VALUES STAY REACHABLE BY KEYBOARD
                final double delta = this.hasUsableRange()
                        ? (this.maxValue - this.minValue) / 100d * direction * step
                        : direction * step;
                this.setValue(this.clampNumber(current.doubleValue() + delta));
                return true;
            }
            return false;
        }

        @Override
        void commit(final String value) {
            this.setValue(this.parse(value));
        }

        @Override
        void reset() {
            this.field.reset();
        }

        @Override
        Color accent() {
            final Object value = this.field.get();
            if (value instanceof final Boolean bool) return bool ? AppTheme.GREEN : AppTheme.TEXT_FAINT;
            if (this.valueType.isEnum()) return AppTheme.CYAN;
            if (this.isNumber()) return AppTheme.AMBER;
            return AppTheme.NEON_LIGHT;
        }

        // ======================================================================
        // HELPERS
        // ======================================================================

        private Object parse(final String raw) {
            final String value = raw == null ? "" : raw.trim();
            if (this.isBoolean()) {
                if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) return Boolean.TRUE;
                if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) return Boolean.FALSE;
                throw new IllegalArgumentException("Expected on/off or true/false");
            }
            if (this.valueType.isEnum()) {
                final Object[] constants = this.valueType.getEnumConstants();
                if (constants != null) {
                    for (final Object constant: constants) {
                        if (((Enum<?>) constant).name().equalsIgnoreCase(value)) return constant;
                    }
                }
                throw new IllegalArgumentException("Unknown enum value: " + value);
            }
            if (this.isNumber()) return this.clampNumber(parseNumber(value, this.valueType));
            if (Path.class.isAssignableFrom(this.valueType)) return Path.of(value);
            if (this.valueType == Character.class || this.valueType == char.class) {
                if (value.length() != 1) throw new IllegalArgumentException("Expected one character");
                return value.charAt(0);
            }
            return raw == null ? "" : raw;
        }

        // THE STOP THAT READS LIKE THE STORED VALUE, SO A HAND-EDITED FILE SITS WHERE ITS LABEL SAYS; ELSE THE NEAREST
        private int stop() {
            final double[] values = this.stops.values();
            final double current = this.number();
            final String label = this.stops.label().apply(current);
            int nearest = 0;
            for (int i = 0; i < values.length; i++) {
                if (this.stops.label().apply(values[i]).equals(label)) return i;
                if (Math.abs(values[i] - current) < Math.abs(values[nearest] - current)) nearest = i;
            }
            return nearest;
        }

        private boolean hasUsableRange() {
            if (this.minValue == null || this.maxValue == null) return false;
            if (!Double.isFinite(this.minValue) || !Double.isFinite(this.maxValue)) return false;
            if (this.maxValue <= this.minValue) return false;
            final double span = this.maxValue - this.minValue;
            if (this.valueType == Integer.class || this.valueType == int.class) return span <= 1_000_000d;
            if (this.valueType == Long.class || this.valueType == long.class) return span <= 1_000_000d;
            if (this.valueType == Short.class || this.valueType == short.class) return true;
            if (this.valueType == Byte.class || this.valueType == byte.class) return true;
            return span <= 10_000d;
        }

        private Object clampNumber(final Object value) {
            if (!(value instanceof final Number number)) return value;
            double next = number.doubleValue();
            if (this.minValue != null) next = Math.max(next, this.minValue);
            if (this.maxValue != null) next = Math.min(next, this.maxValue);
            return this.numberOf(next);
        }

        private Object numberOf(final double value) {
            if (this.valueType == Integer.class || this.valueType == int.class) return (int) Math.round(value);
            if (this.valueType == Long.class || this.valueType == long.class) return Math.round(value);
            if (this.valueType == Short.class || this.valueType == short.class) return (short) Math.round(value);
            if (this.valueType == Byte.class || this.valueType == byte.class) return (byte) Math.round(value);
            if (this.valueType == Float.class || this.valueType == float.class) return (float) value;
            if (this.valueType == Double.class || this.valueType == double.class) return value;
            throw new IllegalArgumentException("Unsupported number type: " + this.valueType.getSimpleName());
        }

        private void setValue(final Object next) {
            final Object previous = this.field.get();
            try {
                this.field.set0(next);
            } catch (final RuntimeException e) {
                // ROLL BACK TO THE PREVIOUS VALUE; THE ORIGINAL FAILURE IS WHAT THE CALLER SEES
                try { this.field.set0(previous); }
                catch (final RuntimeException ignored) {}
                throw e;
            }
        }

        private boolean isBoolean() {
            return this.valueType == Boolean.class || this.valueType == boolean.class;
        }

        private boolean isInteger() {
            return this.valueType == Integer.class || this.valueType == int.class
                    || this.valueType == Long.class || this.valueType == long.class
                    || this.valueType == Short.class || this.valueType == short.class
                    || this.valueType == Byte.class || this.valueType == byte.class;
        }

        private boolean isNumber() {
            return this.isInteger()
                    || this.valueType == Float.class || this.valueType == float.class
                    || this.valueType == Double.class || this.valueType == double.class;
        }

        // ======================================================================
        // UTILITY
        // ======================================================================

        private static boolean fieldMutable(final IConfigField<?, ?> field) {
            final Control control = safeControl(field);
            return control != Control.LABEL
                    && control != Control.LIST_EDITOR
                    && control != Control.TAG_INPUT
                    && control != Control.CHECKBOX_LIST;
        }

        private static Control safeControl(final IConfigField<?, ?> field) {
            final Control control = field.control();
            return control == null || control == Control.DEFAULT ? Control.INPUT : control;
        }

        private static String wider(final String current, final String candidate) {
            return candidate != null && candidate.length() > current.length() ? candidate : current;
        }

        private static Object parseNumber(final String raw, final Class<?> type) {
            if (type == Integer.class || type == int.class) return Integer.parseInt(raw);
            if (type == Long.class || type == long.class) return Long.parseLong(raw);
            if (type == Short.class || type == short.class) return Short.parseShort(raw);
            if (type == Byte.class || type == byte.class) return Byte.parseByte(raw);
            if (type == Float.class || type == float.class) return Float.parseFloat(raw);
            if (type == Double.class || type == double.class) return Double.parseDouble(raw);
            throw new IllegalArgumentException("Unsupported number type: " + type.getSimpleName());
        }

        private static Double numberLimit(final IConfigField<?, ?> field, final String methodName) {
            try {
                final Method method = field.getClass().getMethod(methodName);
                final Object value = method.invoke(field);
                if (value == null) return null;
                return Double.parseDouble(String.valueOf(value));
            } catch (final ReflectiveOperationException | NumberFormatException e) {
                return null;
            }
        }

        private static String typeName(final IConfigField<?, ?> field) {
            final Class<?> type = field.type();
            if (type == Boolean.class || type == boolean.class) return "BOOL";
            if (type == Integer.class || type == int.class) return "INT";
            if (type == Long.class || type == long.class) return "LONG";
            if (type == Short.class || type == short.class) return "SHORT";
            if (type == Byte.class || type == byte.class) return "BYTE";
            if (type == Float.class || type == float.class) return "FLOAT";
            if (type == Double.class || type == double.class) return "DOUBLE";
            if (type.isEnum()) return "ENUM";
            if (Path.class.isAssignableFrom(type)) return "PATH";
            if (type == String.class) return "TEXT";
            return type.getSimpleName().toUpperCase(Locale.ROOT);
        }

        // JOINED FIELD COMMENTS FOR THE ROW TOOLTIP; EMPTY WHEN THE FIELD HAS NONE (NO TOOLTIP THEN)
        private static String joinedComments(final IConfigField<?, ?> field) {
            final String[] comments = field.comments();
            if (comments == null || comments.length == 0) return "";
            final StringBuilder out = new StringBuilder();
            for (final String comment: comments) {
                if (comment == null || comment.isBlank()) continue;
                if (!out.isEmpty()) out.append(" ");
                out.append(comment.trim());
                if (out.length() > 180) break;
            }
            return out.toString();
        }
    }
}
