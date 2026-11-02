package org.watermedia.bootstrap.app.screen;

import me.srrapero720.waterconfig.ConfigSpec;
import me.srrapero720.waterconfig.WaterConfig;
import me.srrapero720.waterconfig.api.Control;
import org.watermedia.WaterMedia;
import org.watermedia.api.util.MathUtil;
import org.watermedia.bootstrap.app.AppConfig;
import org.watermedia.bootstrap.app.AppContext;
import org.watermedia.bootstrap.app.element.Box;
import org.watermedia.bootstrap.app.element.Button;
import org.watermedia.bootstrap.app.element.Canvas;
import org.watermedia.bootstrap.app.element.CheckBox;
import org.watermedia.bootstrap.app.element.Dropdown;
import org.watermedia.bootstrap.app.element.Element;
import org.watermedia.bootstrap.app.element.Group;
import org.watermedia.bootstrap.app.element.ListView;
import org.watermedia.bootstrap.app.element.Parent;
import org.watermedia.bootstrap.app.element.ParentScroll;
import org.watermedia.bootstrap.app.element.SectionHead;
import org.watermedia.bootstrap.app.element.SeekBar;
import org.watermedia.bootstrap.app.element.SegmentedControl;
import org.watermedia.bootstrap.app.element.Spinner;
import org.watermedia.bootstrap.app.element.StatusSquare;
import org.watermedia.bootstrap.app.element.Switch;
import org.watermedia.bootstrap.app.element.Text;
import org.watermedia.bootstrap.app.element.TextField;
import org.watermedia.bootstrap.app.element.Theme;
import org.watermedia.bootstrap.app.screen.SettingsModel.ReadOnlySetting;
import org.watermedia.bootstrap.app.screen.SettingsModel.ReverseSpec;
import org.watermedia.bootstrap.app.screen.SettingsModel.SaveMachine;
import org.watermedia.bootstrap.app.screen.SettingsModel.SaveState;
import org.watermedia.bootstrap.app.screen.SettingsModel.Setting;
import org.watermedia.bootstrap.app.screen.SettingsModel.SettingSection;
import org.watermedia.bootstrap.app.screen.SettingsModel.SettingSpec;
import org.watermedia.bootstrap.app.screen.SettingsModel.Stops;
import org.watermedia.bootstrap.app.ui.AppTheme;
import org.watermedia.bootstrap.app.ui.Gravity;
import org.watermedia.bootstrap.app.ui.Spacing;
import org.watermedia.bootstrap.app.ui.TextRenderer;
import org.watermedia.tools.ThreadTool;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Settings screen for the WaterConfig specs: a spec tab strip over a sections column and a {@link ListView} of
 * setting widgets. Each tab is built reflectively from its {@link ConfigSpec} and shows its autosave state.
 */
public final class SettingsScreen extends Screen {
    private static final int SIDEBAR_W = 220;
    private static final int ROW_H = Theme.ROW_MD;
    private static final int CONTROL_W = 320;
    private static final int CONTROL_H = Theme.CONTROL;
    private static final int TAB_H = 64; // FITS THE ~35px TITLE AND SUBTITLE BLOCK WITH ROOM ABOVE AND BELOW
    private static final int SECTION_H = 36;
    private static final List<Keybind> KEYS_EDIT = List.of(new Keybind("ENTER", "Commit"), new Keybind("BACKSPACE", "Delete"),
            new Keybind("ESC", "Cancel"));
    private static final List<Keybind> KEYS_BROWSE = List.of(new Keybind("UP/DOWN", "Field"), new Keybind("LEFT/RIGHT", "Change"),
            new Keybind("TAB", "Spec"), new Keybind("PGUP/PGDN", "Section"), new Keybind("R", "Reset"), new Keybind("ESC", "Back"));

    // EXTERNAL MOD CONFIG FILES REVERSE-ENGINEERED INTO EDITABLE TABS WHEN PRESENT AND PARSABLE
    private static final String[] REVERSE_FILES = {"chloride-client.json", "waterframes-client.toml"};

    // ConfigSpec.save() IS PACKAGE-PRIVATE IN WATERCONFIG, SO IT IS REFLECTED ONCE; A null (RENAMED OR
    // HIDDEN UPSTREAM) SURFACES AS A FAILED SAVE STATE
    private static final Method CONFIG_SAVE;
    static {
        Method save;
        try {
            save = ConfigSpec.class.getDeclaredMethod("save");
            save.setAccessible(true);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            save = null;
        }
        CONFIG_SAVE = save;
    }

    // PER-TAB ACCENTS CYCLED BY TAB INDEX: TAB 0 (APP) CYAN, TAB 1 (INSTANCE) AMBER, THEN THE REST
    private static final Color[] TAB_ACCENTS = {AppTheme.CYAN, AppTheme.AMBER, AppTheme.GREEN, AppTheme.NEON_LIGHT, AppTheme.NEON};

    private final Consumer<HomeScreen.Action> navigator;
    private final List<SettingSpec> specs = new ArrayList<>();
    // EVERY TEXT FIELD OF THE CURRENT ROWS; FOCUS IS RECONCILED AFTER EACH CLICK (ONE EDIT AT MOST)
    private final List<TextField> textFields = new ArrayList<>();

    // TREE SLOTS: BUILT ONCE IN build(), REFILLED ON SPEC/SECTION SWITCHES
    private Header header;
    private TabScroll tabs;
    private Parent sectionColumn;
    private SectionHead paneHead;
    private ListView<Setting> list;

    private int activeSpecIndex;
    private int activeSectionIndex;

    // ACTIVE TEXT EDIT: ENTER COMMITS THROUGH Setting.commit, ESC CANCELS (VALUE RE-SYNCS FROM THE SETTING)
    private TextField editField;
    private Setting editSetting;

    // ENUM DROPDOWN POPUPS MOUNT INTO this.overlay(); popupKey REMEMBERS A NAV/DISMISS KEY A POPUP CONSUMED
    // ON PRESS SO THE MATCHING RELEASE IS SWALLOWED INSTEAD OF FIRING A SCREEN SHORTCUT (BACK/ACTIVATE)
    private int popupKey = -1;

    // SEEKBAR GESTURES COALESCE INTO ONE SAVE FLUSHED ON POINTER RELEASE; dragSaveSpec PINS THE MUTATED SPEC
    // BECAUSE TAB AND PGUP/PGDN CAN SWITCH SPECS DURING A SEEKBAR CAPTURE
    private boolean dragSave;
    private SettingSpec dragSaveSpec;

    // ONE AUTOSAVE MACHINE PER SPEC IDENTITY, FEEDING ITS TAB PIP; THE MACHINES OUTLIVE rebuildSpecs SO AN
    // IN-FLIGHT SAVE KEEPS ITS COALESCING GATE ACROSS RE-ENTRIES AND SPEC SET CHANGES
    private final Map<String, SaveMachine> saves = new HashMap<>();

    // REVERSE-SPEC PARSE CACHE KEYED BY FILE, REUSED WHILE THE FILE'S mtime IS UNCHANGED SO A SCREEN
    // RE-ENTRY DOES NOT RE-READ AND RE-PARSE UNCHANGED EXTERNAL MOD CONFIGS ON THE RENDER THREAD
    private final Map<String, ReverseSpec> reverseCache = new HashMap<>();

    // HOVERED ROW DESCRIPTION, REPUBLISHED EVERY DRAW PASS; FEEDS THE CURSOR TOOLTIP (SEE onDraw)
    private String hoverNote;

    public SettingsScreen(final TextRenderer text, final AppContext ctx,
                          final Consumer<HomeScreen.Action> navigator) {
        super(text, ctx);
        this.navigator = navigator;
    }

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================

    @Override
    public void onEnter() {
        super.onEnter(); // ensureBuilt() CONSTRUCTS THE TREE ONCE
        this.rebuildSpecs();
        this.syncNav();
        this.populateRows();
    }

    @Override
    public boolean continuous() {
        // KEEP PAINTING WHILE ANY SPEC'S SAVE PULSES ITS TAB PIP, WHILE A CARET BLINKS, OR UNTIL A
        // DRAG-SAVE FLUSHES
        for (final SaveMachine save: this.saves.values()) {
            if (save.state == SaveState.SAVING || save.state == SaveState.RESAVING) return true;
        }
        return this.textInputActive() || this.dragSave;
    }

    @Override
    public List<Keybind> keybinds() {
        return this.editField != null || this.textInputActive() ? KEYS_EDIT : KEYS_BROWSE;
    }

    // ==========================================================================
    // PUBLIC API: INPUT
    // ==========================================================================

    // TREE DISPATCH FIRST, THEN THE SCREEN SHORTCUTS ON GLFW_RELEASE

    @Override
    public boolean dispatchKey(final int key, final int action) {
        // AN OPEN DROPDOWN IS KEYBOARD-MODAL AND CLOSES ON THE ESC/ENTER PRESS; popupKey CARRIES THAT KEY TO
        // ITS RELEASE SO BACK OR ACTIVATE DO NOT FIRE TOO
        final boolean popupOpen = !this.overlay().children().isEmpty();
        if (super.dispatchKey(key, action)) { // POPUP DISMISS / FOCUSED FIELD BACKSPACE
            if (popupOpen && action != GLFW_RELEASE) this.popupKey = key;
            return true;
        }
        if (popupOpen) return true;
        // SWALLOW ONLY THE RELEASE PAIRED WITH THE DISMISS PRESS; A FRESH PRESS CLEARS A STALE popupKey (MENU
        // CLOSED BY MOUSE) SO THE NEXT KEYSTROKE IS NOT EATEN
        if (action == GLFW_RELEASE) {
            if (key == this.popupKey) {
                this.popupKey = -1;
                return true;
            }
        } else if (this.popupKey != -1) {
            this.popupKey = -1;
        }
        if (this.editField != null) {
            // EDIT MODE SWALLOWS THE KEYBOARD; ENTER COMMITS AND ESC CANCELS ON RELEASE
            if (action == GLFW_RELEASE) {
                if (key == GLFW_KEY_ESCAPE) this.closeEdit();
                else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) this.commitEdit();
            }
            return true;
        }
        // A SPINNER'S TYPED EDIT KEEPS THE KEYBOARD TOO; IT HANDLES ITS OWN ENTER, ESC AND ERASE KEYS
        if (this.textInputActive()) return true;
        if (action != GLFW_RELEASE) return false;
        switch (key) {
            case GLFW_KEY_UP -> {
                this.list.moveSelection(-1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_DOWN -> {
                this.list.moveSelection(1);
                this.ctx.sound.play();
            }
            case GLFW_KEY_PAGE_UP -> this.moveSection(-1);
            case GLFW_KEY_PAGE_DOWN -> this.moveSection(1);
            case GLFW_KEY_TAB -> this.switchSpec(this.activeSpecIndex + 1);
            case GLFW_KEY_LEFT -> this.adjustSelected(-1);
            case GLFW_KEY_RIGHT -> this.adjustSelected(1);
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                // A TEXT SETTING OPENS ITS EDIT; ANY OTHER STEPS FORWARD
                final Setting setting = this.list.selectedItem();
                if (setting != null && setting.mutable && setting.editableText()) this.beginEdit(setting);
                else this.adjustSelected(1);
            }
            case GLFW_KEY_R -> this.resetActiveSection();
            case GLFW_KEY_ESCAPE -> this.navigator.accept(HomeScreen.Action.BACK);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean dispatchClick(final double mx, final double my) {
        final boolean handled = super.dispatchClick(mx, my);
        // RECONCILE TEXT FOCUS AFTER THE CLICK: AT MOST ONE FIELD STAYS FOCUSED AND BECOMES THE ACTIVE EDIT; A FIELD
        // SCROLLED OUT OF VIEW STILL HAS A PLACE, WHICH ONLY COUNTS INSIDE THE LIST
        final boolean inList = this.list.contains(mx, my);
        TextField focused = null;
        for (final TextField field: this.textFields) {
            if (!field.focused()) continue;
            if (focused == null && inList && field.contains(mx, my)) focused = field;
            else field.focused(false);
        }
        if (focused != this.editField) {
            if (focused == null) {
                this.closeEdit(); // CLICKED AWAY: DISCARD THE EDIT, THE VALUE RE-SYNCS FROM THE SETTING
            } else {
                this.editField = focused;
                this.editSetting = (Setting) focused.tag();
                focused.value(this.editSetting.editValue());
                this.invalidate();
            }
        }
        return handled;
    }

    @Override
    public boolean dispatchHover(final double mx, final double my) {
        // HOVER FREEZES DURING A TEXT EDIT SO selectOnHover CANNOT MOVE THE SELECTION OFF THE EDITED ROW
        if (this.editField != null) return false;
        return super.dispatchHover(mx, my);
    }

    @Override
    public Element<?> dispatchPress(final double mx, final double my) {
        // NO SEEKBAR DRAG CAPTURE WHILE A TEXT EDIT IS ACTIVE
        return this.editField != null ? null : super.dispatchPress(mx, my);
    }

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @Override
    protected Element<?> build() {
        this.header = new Header().name("Settings").right("v" + WaterMedia.VERSION);

        final Button reset = new Button("RESET TO DEFAULT").icon("reset")
                .accent(AppTheme.AMBER).textColor(AppTheme.TEXT_SOFT)
                .size(Math.max(190, Button.width(this.text, "RESET TO DEFAULT", "", "reset", 12) + 24), Theme.BUTTON)
                .gravity(Gravity.CENTER)
                .onClick(v -> {
                    this.resetActiveSection();
                    this.ctx.sound.play();
                });
        // SPEC TAB STRIP: THE FOLDER TABS SCROLL HORIZONTALLY, THE RESET BUTTON STAYS PINNED RIGHT AND TabStrip
        // DRAWS THE OPEN-FOLDER BASELINE ACROSS BOTH
        this.tabs = new TabScroll().height(MAX_PARENT).weight(1f);
        final TabStrip strip = new TabStrip();
        strip.spacing(12).width(MAX_PARENT).height(TAB_H).padding(new Spacing(12, 22, 0, 22));
        strip.add(this.tabs).add(reset);

        // SECTIONS: A SECTION HEAD OVER A FLAT PLATE
        this.sectionColumn = Parent.column().spacing(2).width(MAX_PARENT);
        final Parent sections = Parent.column().spacing(10)
                .size(SIDEBAR_W, MAX_PARENT)
                .padding(new Spacing(8, 4, 8, 4))
                .background(AppTheme.alpha(AppTheme.BG_1, 150))
                .add(new SectionHead("Sections").compact())
                .add(new ParentScroll().scrollbarWidth(3).size(MAX_PARENT, MAX_PARENT).add(this.sectionColumn));

        // SETTINGS PANE: A SECTION HEAD WITH THE SECTION'S DETAIL OVER THE OPEN ROW LIST
        this.paneHead = new SectionHead().compact();
        this.list = new ListView<Setting>()
                .rowHeight(ROW_H)
                .spacing(8)
                .rowFactory((setting, index) -> {
                    final RowElement row = new RowElement(setting);
                    // HOVER SELECTS THE ROW AND CHIMES WHEN THE SELECTION MOVES
                    row.onHover(v -> {
                        if (!v.selected()) this.ctx.sound.play();
                    });
                    return row;
                })
                .onSelect((setting, index) -> this.activateBody(setting))
                .selectOnHover(true)
                .scrollbarWidth(4)
                // THE ROWS SELF-DRAW THEIR SELECTION FILL/GLOW, SO SUPPRESS THE LIST'S OWN HIGHLIGHTS
                .selectionColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .hoverColor(AppTheme.alpha(AppTheme.NEON_DARK, 0))
                .size(MAX_PARENT, MAX_PARENT);
        final Parent pane = Parent.column().spacing(10)
                .size(MAX_PARENT, MAX_PARENT)
                .padding(new Spacing(8, 0, 0, 18))
                .add(this.paneHead)
                .add(this.list);

        // CONTENT: SECTIONS PLATE | HAIRLINE RULE | OPEN ROWS, DIRECTLY OVER THE APP BACKGROUND
        return Parent.column().size(MAX_PARENT, MAX_PARENT)
                .add(this.header)
                .add(strip)
                .add(Parent.row().size(MAX_PARENT, MAX_PARENT)
                        .padding(new Spacing(14, 22, 10, 22))
                        .add(sections)
                        .add(new Box().size(1, MAX_PARENT).background(AppTheme.STROKE_BRIGHT))
                        .add(pane));
    }

    @Override
    protected void onUpdate() {
        final SettingSection section = this.activeSection();
        // THE HEADER SUBTITLE SHOWS THE ACTIVE TREE PATH ("media" OR "group1 / group2")
        this.header.sub(section == null ? "" : section.path.toLowerCase(Locale.ROOT));
        this.paneHead.label(section == null ? "" : section.name).count(section == null ? "" : section.detail);
        // FLUSH THE SEEKBAR SAVE ON POINTER RELEASE FOR THE SPEC THE GESTURE MUTATED, NOT activeSpec(),
        // WHICH MAY HAVE SWITCHED MID-GESTURE
        if (this.dragSave && !this.ctx.mouseDown) {
            this.dragSave = false;
            final SettingSpec spec = this.dragSaveSpec;
            this.dragSaveSpec = null;
            this.saveSpec(spec);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        // THE HOVERED ROW REPUBLISHES ITS DESCRIPTION WHILE THE TREE DRAWS; THE TOOLTIP PAINTS LAST, ABOVE
        // ROWS AND DROPDOWN POPUPS, AND IS NOT AN ELEMENT, SO IT NEVER TAKES INPUT
        this.hoverNote = null;
        super.onDraw(canvas);
        final String note = this.hoverNote;
        // NO NOTE, NO TOOLTIP; HOVER IS FROZEN DURING A TEXT EDIT, SO THE TOOLTIP HIDES THERE TOO
        if (note == null || this.editField != null) return;
        // AMBER CURSOR TOOLTIP: GREEDY WORD WRAP INTO THE WIDTH BUDGET, POSITIONED AT A SMALL OFFSET
        // FROM THE CURSOR AND CLAMPED TO THE WINDOW EDGES (FLIPS ABOVE THE CURSOR NEAR THE BOTTOM)
        final int pad = 10;
        final int maxW = Math.min(340, canvas.windowWidth() - pad * 2 - 8);
        final List<String> lines = new ArrayList<>();
        String line = "";
        int textW = 0;
        for (String word: note.split(" ")) {
            if (word.isEmpty()) continue;
            if (canvas.textWidth(word, AppTheme.TEXT_BODY, false) > maxW) word = canvas.text().truncateToWidth(word, maxW, AppTheme.TEXT_BODY);
            final String joined = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && canvas.textWidth(joined, AppTheme.TEXT_BODY, false) > maxW) {
                textW = Math.max(textW, canvas.textWidth(line, AppTheme.TEXT_BODY, false));
                lines.add(line);
                line = word;
            } else {
                line = joined;
            }
        }
        if (!line.isEmpty()) {
            textW = Math.max(textW, canvas.textWidth(line, AppTheme.TEXT_BODY, false));
            lines.add(line);
        }
        if (lines.isEmpty()) return;
        final int lineH = canvas.textHeight(AppTheme.TEXT_BODY, false) + 4;
        final int boxW = textW + pad * 2;
        final int boxH = lines.size() * lineH - 4 + pad * 2;
        int tx = (int) this.ctx.mouseX + 14;
        int ty = (int) this.ctx.mouseY + 18;
        if (tx + boxW > canvas.windowWidth() - 4) tx = canvas.windowWidth() - 4 - boxW;
        if (ty + boxH > canvas.windowHeight() - 4) ty = (int) this.ctx.mouseY - boxH - 10;
        tx = Math.max(4, tx);
        ty = Math.max(4, ty);
        canvas.fill(tx, ty, boxW, boxH, AppTheme.alpha(AppTheme.BG_1, 244));
        canvas.stroke(tx, ty, boxW, boxH, AppTheme.AMBER, 1f);
        canvas.glow(tx, ty, boxW, boxH, 0f, AppTheme.AMBER, 0.14f);
        int lineY = ty + pad;
        for (final String row: lines) {
            canvas.text(row, tx + pad, lineY, AppTheme.TEXT, AppTheme.TEXT_BODY, false);
            lineY += lineH;
        }
    }

    // ==========================================================================
    // SPEC AND SECTION NAVIGATION
    // ==========================================================================

    private void rebuildSpecs() {
        this.specs.clear();
        // TABS 0/1: THE APP PREFERENCES (APPLIED LIVE ON EVERY EDIT) AND THE INSTANCE CONFIG
        this.specs.add(this.buildOwnSpec("App", AppConfig.ID, AppConfig.spec(), () -> AppConfig.apply(this.ctx),
                Map.of(AppConfig.SCALE, new Stops(AppConfig.scales(), AppConfig::scaleLabel))));
        this.specs.add(this.buildOwnSpec("Instance config", WaterMedia.ID, WaterConfig.spec(WaterMedia.ID), null, Map.of()));

        // EVERY OTHER REGISTERED SPEC, SORTED BY NAME BECAUSE WaterConfig.specs() IS HASH-BACKED AND
        // HAS NO STABLE ORDER
        final List<ConfigSpec> registered = new ArrayList<>(WaterConfig.specs());
        registered.sort(Comparator.comparing(ConfigSpec::name));
        for (final ConfigSpec cs: registered) {
            final String name = cs.name();
            if (AppConfig.ID.equals(name) || WaterMedia.ID.equals(name)) continue;
            this.specs.add(this.buildGenericSpec(SettingsModel.titleCase(name), cs, "settings", name, null, Map.of()));
        }

        // ABSENT OR UNPARSABLE MOD CONFIG FILES ARE SKIPPED (WaterConfig.reverseSpec RETURNS null)
        for (final String file: REVERSE_FILES) {
            final SettingSpec reverse = this.buildReverseSpec(file);
            if (reverse != null) this.specs.add(reverse);
        }

        if (this.activeSpecIndex >= this.specs.size()) this.activeSpecIndex = Math.max(0, this.specs.size() - 1);
        final SettingSpec active = this.activeSpec();
        this.activeSectionIndex = active == null || active.sections.isEmpty() ? 0
                : MathUtil.clamp(this.activeSectionIndex, 0, active.sections.size() - 1);
    }

    // KEYS ARE STABLE PER LOGICAL SPEC: THE APP AND INSTANCE IDS, EACH REGISTERED SPEC NAME AND EACH
    // REVERSE-SPEC'D FILE NAME
    private SaveMachine saveFor(final String key) {
        return this.saves.computeIfAbsent(key, k -> new SaveMachine());
    }

    // (RE)FILLS THE SPEC TAB STRIP AND THE ACTIVE SPEC'S SECTION LIST
    private void syncNav() {
        this.tabs.clear();
        for (int i = 0; i < this.specs.size(); i++) {
            final int index = i;
            final SettingSpec spec = this.specs.get(i);
            this.tabs.add(new SpecTab(index, spec.title, spec.subtitle, spec.iconName)
                    .onClick(v -> {
                        this.switchSpec(index);
                        this.ctx.sound.play();
                    }));
        }
        // KEEP THE ACTIVE TAB IN VIEW AFTER A REBUILD/SWITCH (THE STRIP MAY BE SCROLLED PAST IT)
        this.tabs.followActive = true;
        this.sectionColumn.clear();
        final SettingSpec active = this.activeSpec();
        if (active != null) {
            for (int i = 0; i < active.sections.size(); i++) {
                final int index = i;
                this.sectionColumn.add(new SectionItem(index, active.sections.get(i))
                        .onClick(v -> {
                            this.switchSection(index);
                            this.ctx.sound.play();
                        }));
            }
        }
        this.invalidate();
    }

    // (RE)LOADS THE ACTIVE SECTION'S SETTINGS INTO THE ROW LIST AND RESETS THE SELECTION TO THE TOP
    private void populateRows() {
        // DROP ANY OPEN ENUM DROPDOWN MENU: THE ROWS (AND THEIR DROPDOWNS) ARE ABOUT TO BE REBUILT
        this.overlay().clear();
        final SettingSection section = this.activeSection();
        this.editField = null;
        this.editSetting = null;
        this.textFields.clear();
        this.list.items(section == null ? List.of() : section.settings);
        this.list.selection(section == null || section.settings.isEmpty() ? -1 : 0);
    }

    private void switchSpec(final int index) {
        if (this.specs.isEmpty()) return;
        this.activeSpecIndex = Math.floorMod(index, this.specs.size());
        this.activeSectionIndex = 0;
        this.closeEdit();
        this.syncNav();
        this.populateRows();
    }

    private void switchSection(final int index) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.activeSectionIndex = MathUtil.clamp(index, 0, spec.sections.size() - 1);
        this.closeEdit();
        this.populateRows();
    }

    private void moveSection(final int delta) {
        final SettingSpec spec = this.activeSpec();
        if (spec == null || spec.sections.isEmpty()) return;
        this.switchSection(Math.floorMod(this.activeSectionIndex + delta, spec.sections.size()));
        this.ctx.sound.play();
    }

    private SettingSpec activeSpec() {
        return this.specs.isEmpty() ? null : this.specs.get(MathUtil.clamp(this.activeSpecIndex, 0, this.specs.size() - 1));
    }

    private SettingSection activeSection() {
        final SettingSpec spec = this.activeSpec();
        return spec == null || spec.sections.isEmpty()
                ? null
                : spec.sections.get(MathUtil.clamp(this.activeSectionIndex, 0, spec.sections.size() - 1));
    }

    // ==========================================================================
    // SETTING ACTIVATION
    // ==========================================================================

    // MOUSE BODY CLICKS, KEYBOARD ADJUST/ACTIVATE, WIDGET CHANGES

    // RUNS A SETTING MUTATION FROM A WIDGET, THEN AUTOSAVES + CHIMES; AN INVALID VALUE IS REVERTED BY THE
    // SETTING ITSELF (setValue RESTORES THE PREVIOUS VALUE) AND THE WIDGET RE-SYNCS ON THE NEXT UPDATE PASS
    private void apply(final BooleanSupplier mutation) {
        try {
            if (mutation.getAsBoolean()) {
                this.autosave();
                this.ctx.sound.play();
            }
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    // SEEKBAR TICK: APPLY THE PERCENT IMMEDIATELY BUT DEFER THE SAVE TO THE END OF THE GESTURE,
    // CHIMING ONLY ON THE FIRST TICK
    private void seek(final Setting setting, final float pct) {
        try {
            if (setting.percent(pct)) {
                if (!this.dragSave) this.ctx.sound.play();
                this.dragSave = true;
                // PIN THE SPEC OWNING THIS SEEKBAR NOW: activeSpec() COULD CHANGE BEFORE THE RELEASE FLUSH
                this.dragSaveSpec = this.activeSpec();
            }
        } catch (final RuntimeException e) {
            this.dragSave = false;
            this.dragSaveSpec = null;
            this.invalidate();
        }
    }

    // A CLICK ON A ROW BODY OUTSIDE ITS CONTROL OPENS THE TEXT EDIT OR STEPS THE VALUE FORWARD
    private void activateBody(final Setting setting) {
        this.ctx.sound.play();
        if (setting == null || !setting.mutable) return;
        if (setting.editableText()) {
            this.beginEdit(setting);
            return;
        }
        try {
            if (setting.adjust(1, 1)) this.autosave();
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    private void adjustSelected(final int delta) {
        final Setting setting = this.list.selectedItem();
        if (setting == null || !setting.mutable) return;
        try {
            if (setting.adjust(delta, this.ctx.ctrlDown ? 10 : 1)) {
                this.autosave();
                this.ctx.sound.play();
            } else if (setting.editableText()) {
                this.beginEdit(setting);
            }
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    private void resetActiveSection() {
        final SettingSection section = this.activeSection();
        if (section == null) return;
        try {
            for (final Setting setting: section.settings) {
                if (setting.mutable) setting.reset();
            }
            this.autosave();
        } catch (final RuntimeException e) {
            this.invalidate();
        }
    }

    // ==========================================================================
    // TEXT EDITING
    // ==========================================================================

    // THE TextField WIDGET OWNS TYPING; THE SCREEN OWNS COMMIT/CANCEL

    private void beginEdit(final Setting setting) {
        for (final TextField field: this.textFields) {
            if (field.tag() != setting) continue;
            if (this.editField != null && this.editField != field) this.editField.focused(false);
            this.editField = field;
            this.editSetting = setting;
            field.value(setting.editValue()).focused(true);
            this.invalidate();
            return;
        }
    }

    private void commitEdit() {
        if (this.editField == null || this.editSetting == null) {
            this.closeEdit();
            return;
        }
        try {
            this.editSetting.commit(this.editField.value());
            this.closeEdit();
            this.autosave();
        } catch (final RuntimeException e) {
            // INVALID INPUT STAYS IN EDIT MODE SO THE USER CAN FIX THE VALUE
            this.invalidate();
        }
    }

    private void closeEdit() {
        if (this.editField != null) this.editField.focused(false);
        this.editField = null;
        this.editSetting = null;
        this.invalidate();
    }

    // ==========================================================================
    // ENUM CONTROL PICK
    // ==========================================================================

    // SEGMENTED CONTROL FOR SMALL SETS, DROPDOWN (ATTACH MENU) FOR THE REST

    // WHETHER AN ENUM SETTING USES THE DROPDOWN INSTEAD OF A SEGMENTED CONTROL: MORE THAN 4 OPTIONS, OR
    // SEGMENTS THAT WOULD BE WIDER THAN THE CONTROL BUDGET
    private boolean needsDropdown(final Setting setting) {
        if (!setting.isEnumControl()) return false;
        final List<String> options = setting.optionLabels();
        if (options.size() > 4) return true;
        int segW = 0;
        for (final String option: options) {
            segW += SegmentedControl.segmentWidth(this.text, option.toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY);
        }
        return segW > CONTROL_W;
    }

    // ==========================================================================
    // SPEC TABS
    // ==========================================================================

    // EVERY WATERCONFIG SPEC GOES THROUGH THE SAME REFLECTIVE TREE BUILDER (SettingsModel)

    // WATERMEDIA'S OWN SPECS (APP AND INSTANCE) KEEP THE APP ICON; A SPEC WATERCONFIG DID NOT EXPOSE SHOWS A
    // READ-ONLY PLACEHOLDER INSTEAD OF AN EMPTY TAB
    private SettingSpec buildOwnSpec(final String title, final String id, final ConfigSpec configSpec, final Runnable applier,
                                     final Map<String, Stops> stops) {
        if (configSpec != null) return this.buildGenericSpec(title, configSpec, null, id, applier, stops);
        final SettingSpec spec = new SettingSpec(title, id, null, applier, this.saveFor(id), null, Map.of());
        final SettingSection unavailable = new SettingSection("Unavailable",
                "WaterConfig did not expose the active spec", "unavailable", 0);
        unavailable.settings.add(new ReadOnlySetting(title, id, "STATE",
                "The app could not read the registered WaterConfig spec.",
                () -> WaterConfig.isRegistered(id) ? "REGISTERED" : "NOT REGISTERED"));
        spec.sections.add(unavailable);
        return spec;
    }

    // BUILDS A PLAIN WATERCONFIG SPEC TAB (OWN, WATERCONFIG-EXPOSED OR REVERSE-SPEC'D): ITS SECTION
    // TREE COMES FROM collectSections, WITH A READ-ONLY PLACEHOLDER WHEN THE SPEC EXPOSES NO FIELDS
    private SettingSpec buildGenericSpec(final String title, final ConfigSpec configSpec, final String icon,
                                         final String saveKey, final Runnable applier, final Map<String, Stops> stops) {
        final SettingSpec spec = new SettingSpec(title, String.valueOf(configSpec.path().getFileName()), configSpec, applier,
                this.saveFor(saveKey), icon, stops);
        SettingsModel.collectSections(configSpec, spec);
        if (spec.sections.isEmpty()) {
            final SettingSection empty = new SettingSection("Empty", "No fields were discovered", "empty", 0);
            empty.settings.add(new ReadOnlySetting(SettingsModel.titleCase(configSpec.name()), configSpec.name(), "STATE",
                    "No configurable fields were discovered in this spec.", () -> "EMPTY"));
            spec.sections.add(empty);
        }
        return spec;
    }

    // RETURNS null WHEN THE FILE IS ABSENT, UNPARSABLE OR HAS NO EDITABLE SECTIONS, SO THE TAB DOES NOT
    // APPEAR; THE FILE NAME KEYS BOTH THE TAB SUBTITLE AND ITS SAVE MACHINE
    private SettingSpec buildReverseSpec(final String file) {
        final Path path = WaterConfig.getPath().resolve(file);
        // REUSE THE LAST PARSE WHILE THE FILE'S mtime IS UNCHANGED (ABSENT/UNREADABLE = -1, WHICH ALSO
        // CACHES THE "SKIP" DECISION); A SAVE BUMPS THE mtime, SO THE NEXT ENTRY RE-PARSES FRESH VALUES
        long mtime = -1L;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            // ABSENT/UNREADABLE: mtime STAYS -1; reverseSpec BELOW RETURNS null AND THE TAB IS SKIPPED
        }
        final ReverseSpec cached = this.reverseCache.get(file);
        final ConfigSpec configSpec;
        if (cached != null && cached.mtime() == mtime) {
            configSpec = cached.spec();
        } else {
            configSpec = WaterConfig.reverseSpec(path);
            this.reverseCache.put(file, new ReverseSpec(mtime, configSpec));
        }
        if (configSpec == null) return null;
        final SettingSpec spec = new SettingSpec(SettingsModel.titleCase(configSpec.name()), file, configSpec, null,
                this.saveFor(file), "folder", Map.of());
        SettingsModel.collectSections(configSpec, spec);
        return spec.sections.isEmpty() ? null : spec;
    }

    // ==========================================================================
    // AUTOSAVE
    // ==========================================================================

    // LIVE-APPLIES THE APP SPEC, PERSISTS OFF-THREAD AND SHOWS THE STATE IN THE TAB PIP

    private void autosave() {
        this.saveSpec(this.activeSpec());
    }

    // PERSISTS ONE SPECIFIC SPEC: THE ACTIVE ONE FOR IMMEDIATE EDITS (autosave), OR THE SPEC PINNED AT
    // SEEKBAR-ARM TIME FOR THE DEFERRED DRAG FLUSH (SEE onUpdate)
    private void saveSpec(final SettingSpec spec) {
        if (spec == null) return;
        // THE APP SPEC PUSHES ITS FRESH VALUES INTO THE LIVE APP STATE BEFORE PERSISTING THEM
        if (spec.applier != null) spec.applier.run();
        final SaveMachine save = spec.save;
        if (spec.configSpec == null) {
            save.failures = 0;
            save.state = SaveState.READY;
            return;
        }
        // A SAVE OF THIS SPEC IS IN FLIGHT: COALESCE INTO ONE FOLLOW-UP SAVE INSTEAD OF A SECOND WRITER ON
        // THE SAME FILE; OTHER SPECS HAVE THEIR OWN MACHINES AND NEVER BLOCK THIS ONE
        if (save.saving) {
            save.resavePending = true;
            return;
        }
        this.startSave(spec);
    }

    private void startSave(final SettingSpec spec) {
        final SaveMachine save = spec.save;
        save.saving = true;
        save.state = save.failures > 0 ? SaveState.RESAVING : SaveState.SAVING;
        this.ctx.requestRender();
        final ConfigSpec configSpec = spec.configSpec;
        // RUN THE REFLECTIVE DISK WRITE OFF THE RENDER THREAD, THEN PUBLISH THE OUTCOME BACK ON IT VIA
        // ctx.execute SO THE SAVE STATE STAYS RENDER-THREAD-CONFINED AND THE SAVING FRAME ACTUALLY RENDERS
        ThreadTool.thread("WaterMedia-ConfigSave", () -> {
            ReflectiveOperationException error = null;
            try {
                if (CONFIG_SAVE == null) throw new NoSuchMethodException("ConfigSpec#save unavailable");
                CONFIG_SAVE.invoke(configSpec);
            } catch (final ReflectiveOperationException e) {
                error = e;
            }
            final ReflectiveOperationException outcome = error;
            this.ctx.execute(() -> this.finishSave(spec, outcome));
        });
    }

    private void finishSave(final SettingSpec spec, final ReflectiveOperationException error) {
        final SaveMachine save = spec.save;
        if (error == null) {
            save.failures = 0;
            save.state = SaveState.SAVED;
        } else {
            save.failures++;
            save.state = save.failures >= 2 ? SaveState.FAILED_TWICE : SaveState.FAILED_ONCE;
        }
        save.saving = false;
        this.invalidate();
        // A CHANGE ARRIVED WHILE SAVING: PERSIST IT NOW WITH ONE COALESCED FOLLOW-UP SAVE
        if (save.resavePending) {
            save.resavePending = false;
            this.startSave(spec);
        }
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    // CUSTOM ELEMENTS WITH CANVAS-ONLY DRAWING

    // SPEC TAB SCROLLER: A HORIZONTAL, CLIPPING VIEWPORT OVER THE FOLDER TABS; THE WHEEL SCROLLS IT AND
    // A SPEC SWITCH FOLLOWS THE ACTIVE TAB INTO VIEW; SCROLLED-OUT TABS ARE CLIPPED AND NON-INTERACTIVE
    private final class TabScroll extends Group<TabScroll> {
        private static final int STEP = 48; // PX PER WHEEL NOTCH
        private static final int SPACING = 6; // GAP BETWEEN TABS

        private int scrollX;
        private int maxScrollX;
        private int naturalWidth; // SUM OF TAB WIDTHS + GAPS: THE SCROLLABLE CONTENT WIDTH
        private boolean followActive; // ON A SPEC SWITCH, BRING THE ACTIVE TAB INTO VIEW ON THE NEXT LAYOUT

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            int total = 0;
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                child.measure(innerAvailWidth, innerAvailHeight); // TABS WRAP THEIR WIDTH, FILL THE HEIGHT
                if (!first) total += SPACING;
                first = false;
                total += child.measuredWidth();
            }
            this.naturalWidth = total;
            this.contentWidth = total;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            final int viewport = this.innerWidth();
            this.maxScrollX = Math.max(0, this.naturalWidth - viewport);
            // FOLLOW THE ACTIVE TAB BEFORE CLAMPING SO A JUST-SWITCHED, OFF-SCREEN TAB SNAPS INTO VIEW
            if (this.followActive) {
                this.followActive = false;
                this.scrollX = this.offsetForActive(viewport);
            }
            this.scrollX = MathUtil.clamp(this.scrollX, 0, this.maxScrollX);
            int x = this.innerLeft() - this.scrollX;
            final int y = this.innerTop();
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                if (!first) x += SPACING;
                first = false;
                child.layout(x, y);
                x += child.measuredWidth();
            }
        }

        // MINIMAL OFFSET THAT BRINGS THE ACTIVE TAB FULLY INTO THE VIEWPORT (KEEPS THE CURRENT OFFSET WHEN
        // THE TAB IS ALREADY VISIBLE)
        private int offsetForActive(final int viewport) {
            int x = 0;
            boolean first = true;
            for (final Element<?> child: this.children) {
                if (!child.visible()) continue;
                if (!first) x += SPACING;
                first = false;
                if (child instanceof final SpecTab tab && tab.index == SettingsScreen.this.activeSpecIndex) {
                    final int right = x + child.measuredWidth();
                    if (x < this.scrollX) return x;
                    if (right > this.scrollX + viewport) return right - viewport;
                    return this.scrollX;
                }
                x += child.measuredWidth();
            }
            return this.scrollX;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            // CLIP TO THE VIEWPORT SO SCROLLED-OUT TABS ARE NOT PAINTED (A PARTIAL EDGE TAB HINTS AT MORE)
            canvas.pushClip(this.left, this.top, this.measuredWidth, this.measuredHeight);
            super.onDraw(canvas);
            canvas.popClip();
        }

        @Override
        public boolean dispatchScroll(final double mx, final double my, final double amount) {
            if (!this.contains(mx, my) || this.maxScrollX <= 0) return false;
            // VERTICAL WHEEL DRIVES THE HORIZONTAL OFFSET, THE USUAL STRIP-SCROLL GESTURE
            final int next = MathUtil.clamp(this.scrollX - (int) (amount * STEP), 0, this.maxScrollX);
            if (next != this.scrollX) {
                this.scrollX = next;
                this.invalidate();
            }
            return true;
        }

        @Override
        public boolean dispatchClick(final double mx, final double my) {
            // TABS STAY CHILDREN WHEN SCROLLED OUT, SO GATE HITS ON THE VIEWPORT RECT
            return this.contains(mx, my) && super.dispatchClick(mx, my);
        }

        @Override
        public boolean dispatchHover(final double mx, final double my) {
            if (!this.contains(mx, my)) {
                for (final Element<?> child: this.children) child.clearHover();
                this.hovered = false;
                return false;
            }
            return super.dispatchHover(mx, my);
        }
    }

    // SPEC TAB STRIP: HOSTS THE SCROLLABLE TABS AND THE PINNED RESET ACTION, THEN CLOSES THE ROW WITH A
    // BASELINE RULE BROKEN UNDER THE ACTIVE TAB SO IT READS AS AN OPEN FOLDER INTO THE CONTENT BELOW
    private final class TabStrip extends Parent {
        private TabStrip() {
            super(Orientation.HORIZONTAL);
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            // THE BASELINE PAINTS AFTER THE CHILDREN TO SEAL THE INACTIVE TAB BOTTOMS; THE ACTIVE TAB'S GAP IS
            // CLAMPED TO THE SCROLLER VIEWPORT SO A SCROLLED-OUT TAB NEVER BREAKS THE RULE
            final int y = this.top + this.measuredHeight - 1;
            final TabScroll scroll = SettingsScreen.this.tabs;
            final int viewL = scroll.left();
            final int viewR = scroll.left() + scroll.measuredWidth();
            int gapL = -1;
            int gapR = -1;
            for (final Element<?> child: scroll.children()) {
                if (child instanceof final SpecTab tab && tab.index == SettingsScreen.this.activeSpecIndex) {
                    gapL = Math.max(viewL, tab.left());
                    gapR = Math.min(viewR, tab.left() + tab.measuredWidth());
                    if (gapR <= gapL) { gapL = -1; gapR = -1; } // ACTIVE TAB FULLY SCROLLED OUT OF VIEW
                    break;
                }
            }
            if (gapL < 0) {
                canvas.fill(this.left, y, this.measuredWidth, 1, AppTheme.STROKE_BRIGHT);
            } else {
                canvas.fill(this.left, y, Math.max(0, gapL - this.left), 1, AppTheme.STROKE_BRIGHT);
                canvas.fill(gapR, y, Math.max(0, this.left + this.measuredWidth - gapR), 1, AppTheme.STROKE_BRIGHT);
            }
        }
    }

    // ONE SPEC FOLDER TAB WITH ITS ICON, TITLES AND SAVE PIP; THE ACTIVE TAB LIGHTS ITS ACCENT AND BREAKS
    // THE STRIP BASELINE (SEE TabStrip) SO IT OPENS INTO THE SETTINGS BELOW
    private final class SpecTab extends Element<SpecTab> {
        private static final int ICON = 16; // ICON EDGE IN LOGICAL PX, SAME AS THE TITLEBAR
        private static final int PIP = 8;   // SAVE-STATUS SQUARE EDGE, SAME AS StatusSquare

        private final int index;
        private final String title;
        private final String subtitle;
        // PIXEL-ICON GLYPH NAME, OR null TO DRAW THE APP TEXTURE ICON (WATERMEDIA'S OWN SPECS)
        private final String iconName;

        private SpecTab(final int index, final String title, final String subtitle, final String iconName) {
            this.index = index;
            this.title = title.toUpperCase(Locale.ROOT);
            this.subtitle = subtitle.toUpperCase(Locale.ROOT);
            this.iconName = iconName;
            this.height = MAX_PARENT;
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            final TextRenderer text = SettingsScreen.this.text;
            // 16 PAD + ICON + 8 GAP | TEXT BLOCK | 12 GAP + PIP + 16 PAD
            this.contentWidth = Math.max(text.widthBold(this.title, AppTheme.TEXT_BUTTON),
                    text.width(this.subtitle, AppTheme.TEXT_SUBTITLE)) + 76;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean active = this.index == SettingsScreen.this.activeSpecIndex;
            final Color accent = TAB_ACCENTS[this.index % TAB_ACCENTS.length];
            final int x = this.left;
            final int y = this.top;
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (active) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_2, 235));
                canvas.glow(x, y, w, h, 0f, accent, 0.18f);
            } else {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.BG_1, this.hovered ? 205 : 140));
            }
            // OPEN-BOTTOM FRAME: TOP AND SIDE EDGES ONLY; THE STRIP BASELINE CLOSES INACTIVE TABS
            final Color edge = active ? accent : this.hovered ? AppTheme.STROKE_BRIGHT : AppTheme.STROKE;
            final float edgeW = active ? 1.5f : 1f;
            canvas.lineH(x, y, w, edge, edgeW);
            canvas.line(x, y, x, y + h, edge, edgeW);
            canvas.line(x + w, y, x + w, y + h, edge, edgeW);
            final int titleH = canvas.textHeight(AppTheme.TEXT_BUTTON, true);
            final int subH = canvas.textHeight(AppTheme.TEXT_SUBTITLE, false);
            final int blockH = titleH + 2 + subH;
            final int textY = y + Math.max(0, (h - blockH) / 2);
            // WATERMEDIA'S OWN SPECS SHOW THE APP TEXTURE, READ PER DRAW BECAUSE IT CHANGES ACROSS ENGINE SWAPS;
            // OTHER SPECS SHOW A GLYPH TINTED LIKE THE TITLE
            final int iconY = textY + (blockH - ICON) / 2;
            if (this.iconName == null) {
                final int iconId = SettingsScreen.this.ctx.assets.iconId;
                if (iconId > 0) canvas.image(iconId, x + 16, iconY, ICON, ICON, null);
            } else {
                canvas.icon(this.iconName, x + 16, iconY, ICON,
                        active ? accent : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT);
            }
            final int textX = x + 16 + ICON + 8;
            canvas.text(this.title, textX, textY,
                    active ? accent : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT, AppTheme.TEXT_BUTTON, true);
            canvas.text(this.subtitle, textX, textY + titleH + 2, AppTheme.TEXT_FAINT, AppTheme.TEXT_SUBTITLE, false);
            // SAVE PIP: THE SAME 8x8 SQUARE StatusSquare DRAWS, FED BY THIS SPEC'S SaveMachine
            final StatusSquare.Status status = switch (SettingsScreen.this.specs.get(this.index).save.state) {
                case FAILED_TWICE -> StatusSquare.Status.ERROR;
                case FAILED_ONCE, RESAVING -> StatusSquare.Status.WARN;
                case SAVING -> StatusSquare.Status.PENDING;
                default -> StatusSquare.Status.OK; // READY / SAVED
            };
            final Color pip = StatusSquare.tint(status);
            final int pipX = x + w - 16 - PIP;
            final int pipY = y + (h - PIP) / 2;
            canvas.fill(pipX, pipY, PIP, PIP, pip);
            canvas.glow(pipX, pipY, PIP, PIP, 0f, pip, 0.42f);
        }
    }

    // SECTION TREE ROW: NESTED GROUPS INDENT 14px PER LEVEL; THE ACTIVE NODE LIGHTS AN AMBER BAR AND FILL,
    // MATCHING THE AMBER CONTROL THUMBS
    private final class SectionItem extends Element<SectionItem> {
        private static final int INDENT = 14;

        private final int index;
        private final String name;
        private final int indent;

        private SectionItem(final int index, final SettingSection section) {
            this.index = index;
            this.name = section.name.toUpperCase(Locale.ROOT);
            this.indent = section.depth * INDENT;
            this.width = MAX_PARENT;
            this.height = SECTION_H;
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final boolean active = this.index == SettingsScreen.this.activeSectionIndex;
            final int x = this.left + this.indent;
            final int y = this.top;
            final int w = this.measuredWidth - this.indent;
            final int h = this.measuredHeight;
            if (active) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.AMBER, 30));
                canvas.fill(x, y + 4, 3, h - 8, AppTheme.AMBER);
                canvas.glow(x, y, w, h, 0f, AppTheme.AMBER, 0.14f);
            } else if (this.hovered) {
                canvas.fill(x, y, w, h, AppTheme.alpha(AppTheme.AMBER, 18));
            }
            canvas.text(canvas.text().truncateToWidth(this.name, w - 26, AppTheme.TEXT_BODY, Font.BOLD),
                    x + 14, y + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_BODY, true)) / 2),
                    active ? AppTheme.AMBER : this.hovered ? AppTheme.TEXT : AppTheme.TEXT_SOFT,
                    AppTheme.TEXT_BODY, true);
        }
    }

    // ONE SETTING ROW: SELF-DRAWN LABEL AND SELECTION, WITH A REAL WIDGET RIGHT-ALIGNED AND VERTICALLY
    // CENTERED; THE binder PULLS LIVE VALUES EVERY UPDATE PASS
    private final class RowElement extends Group<RowElement> {
        private final Setting setting;
        private final Element<?> control;
        private final int ctlW; // PREFERRED CONTROL WIDTH IN PIXELS; 0 = WRAP TO THE WIDGET'S OWN SIZE
        private final Runnable binder;
        private Text seekValue; // SEEKBAR VALUE LABEL, SELECTION-TINTED AT DRAW TIME

        private RowElement(final Setting setting) {
            this.setting = setting;
            this.width = MAX_PARENT;
            final TextRenderer text = SettingsScreen.this.text;
            final Control kind = setting.control();
            final boolean enumKind = kind == Control.DROPDOWN || kind == Control.RADIO_BUTTON
                    || kind == Control.LIST_SPINNER || kind == Control.SEGMENTED;
            final Element<?> ctl;
            final Runnable bind;
            int prefW = 0;

            if (!setting.mutable || kind == Control.LABEL || kind == Control.LIST_EDITOR
                    || kind == Control.TAG_INPUT || kind == Control.CHECKBOX_LIST
                    || (enumKind && !setting.isEnumControl())) {
                // READ-ONLY VALUE: RIGHT-ALIGNED, STATUS-COLORED TEXT
                final Text value = new Text().scale(AppTheme.TEXT_BODY).gravity(Gravity.RIGHT).height(CONTROL_H);
                bind = () -> {
                    // GREEN FOR A HEALTHY STATE, RED FOR A FAILED ONE, AMBER FOR A LIMITED ONE
                    final String upper = setting.valueLabel().toUpperCase(Locale.ROOT);
                    value.text(upper).color(upper.contains("READY") || upper.contains("LOADED") || upper.contains("AVAILABLE") ? AppTheme.GREEN
                            : upper.contains("ERROR") || upper.contains("UNLOADED") ? AppTheme.RED
                            : upper.contains("ONLY") || upper.contains("OFFLINE") ? AppTheme.AMBER
                            : AppTheme.TEXT_FAINT);
                };
                prefW = CONTROL_W + 120;
                ctl = value;
            } else if (kind == Control.SWITCH) {
                final Switch control = new Switch().accent(AppTheme.AMBER)
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.adjust(1, 1)));
                bind = () -> control.on(setting.booleanValue());
                ctl = control;
            } else if (kind == Control.CHECKBOX) {
                final CheckBox control = new CheckBox().accent(AppTheme.GREEN)
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.adjust(1, 1)));
                bind = () -> control.checked(setting.booleanValue());
                ctl = control;
            } else if (enumKind && !SettingsScreen.this.needsDropdown(setting)) {
                // COMPACT ENUM: ONE SEGMENT PER CONSTANT
                final List<String> options = setting.optionLabels();
                final String[] segments = new String[options.size()];
                int segW = 0;
                for (int i = 0; i < segments.length; i++) {
                    segments[i] = options.get(i).toUpperCase(Locale.ROOT);
                    segW += SegmentedControl.segmentWidth(text, segments[i], AppTheme.TEXT_BODY);
                }
                final SegmentedControl control = new SegmentedControl().segments(segments)
                        .accent(AppTheme.CYAN).scale(AppTheme.TEXT_BODY).height(CONTROL_H)
                        .onSelect(i -> SettingsScreen.this.apply(() -> setting.selectOption(i)));
                bind = () -> control.selected(setting.selectedOptionIndex());
                prefW = segW;
                ctl = control;
            } else if (enumKind) {
                // WIDE ENUM: DROPDOWN WITH A FLOATING MENU ANCHORED TO THE CONTROL; THE POPUP MOUNTS INTO THE
                // SCREEN OVERLAY SO IT STACKS ON TOP AND IS TORN DOWN ON SECTION SWITCH
                final List<String> options = setting.optionLabels();
                final List<String> labels = new ArrayList<>(options.size());
                for (final String option: options) labels.add(option.toUpperCase(Locale.ROOT));
                final Dropdown control = new Dropdown().mode(Dropdown.Mode.ATTACH)
                        .accent(AppTheme.CYAN).scale(AppTheme.TEXT_BODY).height(CONTROL_H)
                        .items(labels)
                        .overlayHost(SettingsScreen.this.overlay())
                        .onSelect(i -> SettingsScreen.this.apply(() -> setting.selectOption(i)));
                bind = () -> control.selected(setting.selectedOptionIndex());
                // THE BOX RENDERS THE VALUE BOLD; SIZE FOR THE WIDEST LABEL PLUS THE 10px INSET, ARROW ZONE AND MARGIN
                prefW = Math.max(160, text.widthBold(setting.widestValueLabel().toUpperCase(Locale.ROOT), AppTheme.TEXT_BODY) + 46);
                ctl = control;
            } else if (setting.isSeekbarControl()) {
                // RANGED NUMBER: SEEK BAR PLUS THE LIVE VALUE LABEL (FIXED WIDTH SO THE TRACK NEVER JITTERS)
                final SeekBar bar = new SeekBar().trackHeight(6).height(16).gravity(Gravity.CENTER)
                        .onChange(f -> SettingsScreen.this.seek(setting, f));
                final String suffix = setting.suffix().isBlank() ? "" : " " + setting.suffix();
                final Text value = new Text().scale(AppTheme.TEXT_SUBTITLE).gravity(Gravity.RIGHT)
                        .size(Math.max(58, text.width(setting.widestValueLabel() + suffix, AppTheme.TEXT_SUBTITLE) + 6), CONTROL_H);
                this.seekValue = value;
                bind = () -> {
                    bar.value(setting.rangePercent());
                    value.text(suffix.isEmpty() ? setting.valueLabel() : setting.valueLabel() + suffix);
                };
                prefW = CONTROL_W;
                ctl = Parent.row().spacing(14).height(CONTROL_H).add(bar).add(value);
            } else if (kind == Control.NUMBER_SPINNER || kind == Control.SEEKBAR
                    || kind == Control.RANGE_SLIDER || kind == Control.KNOB || setting.isSpinnerControl()) {
                // UNRANGED OR SPINNER NUMBER: [-] VALUE [+] WITH AN EDITABLE CENTER; onChange SETS THE VALUE
                // STRAIGHT THROUGH number(), WHICH CLAMPS LIKE commit AND adjust
                final String suffix = setting.suffix();
                final Spinner control = new Spinner().range(-1e18, 1e18).step(1).height(CONTROL_H)
                        .suffix(suffix.isBlank() ? "" : " " + suffix.toUpperCase(Locale.ROOT))
                        .onChange(v -> SettingsScreen.this.apply(() -> setting.number(v)));
                bind = () -> control.value(setting.number());
                final int suffixW = suffix.isBlank() ? 0 : text.width(suffix, AppTheme.TEXT_SUBTITLE) + 16;
                prefW = Math.max(190, text.width(setting.widestValueLabel(), AppTheme.TEXT_BODY) + suffixW + 110);
                ctl = control;
            } else {
                // FREE TEXT: LIVE TEXT FIELD; ENTER COMMITS, ESC CANCELS (SEE dispatchKey/dispatchClick). NO
                // EXPLICIT ACCENT SO FOCUS HIGHLIGHTS IN THE FRAMEWORK'S DEFAULT AMBER
                final TextField field = new TextField()
                        .padding(Spacing.hv(8, 4)).height(CONTROL_H).tag(setting);
                SettingsScreen.this.textFields.add(field);
                bind = () -> {
                    if (!field.focused()) field.value(setting.valueLabel());
                };
                prefW = Path.class.isAssignableFrom(setting.valueType()) ? CONTROL_W + 120 : CONTROL_W;
                final String suffix = setting.suffix();
                if (suffix.isBlank()) ctl = field;
                else ctl = Parent.row().spacing(6).height(CONTROL_H)
                        .add(field)
                        .add(new Text(suffix.toUpperCase(Locale.ROOT)).scale(AppTheme.TEXT_SUBTITLE)
                                .color(AppTheme.TEXT_FAINT).height(CONTROL_H));
            }

            this.control = ctl;
            this.ctlW = prefW;
            this.binder = bind;
            this.add(ctl);
        }

        // ======================================================================
        // INTERNAL API
        // ======================================================================

        @Override
        protected void onUpdate() {
            this.binder.run();
        }

        @Override
        protected void onMeasure(final int innerAvailWidth, final int innerAvailHeight) {
            // FIXED-WIDTH CONTROLS SHRINK SO THE LABEL ALWAYS KEEPS 160px
            if (this.ctlW > 0) this.control.width(MathUtil.clamp(innerAvailWidth - 160, 120, this.ctlW));
            this.control.measure(innerAvailWidth, innerAvailHeight);
            this.contentWidth = innerAvailWidth;
            this.contentHeight = innerAvailHeight;
        }

        @Override
        protected void onLayout() {
            this.control.layout(this.left + this.measuredWidth - this.control.measuredWidth() - 14,
                    this.top + Math.max(0, (this.measuredHeight - this.control.measuredHeight()) / 2));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final Color accent = this.setting.accent();
            final int w = this.measuredWidth;
            final int h = this.measuredHeight;
            if (this.selected) {
                canvas.fill(this.left, this.top, w, h, AppTheme.alpha(AppTheme.NEON_DARK, 36));
                canvas.fill(this.left, this.top + 6, 3, h - 12, accent);
                canvas.glow(this.left, this.top + 4, w, h - 8, 0f, accent, 0.14f);
            }
            // DOTTED BOTTOM DIVIDER
            for (int dx = this.left; dx < this.left + w; dx += 8) {
                canvas.fill(dx, this.top + h - 1, 4, 1, AppTheme.alpha(AppTheme.STROKE_BRIGHT, 76));
            }
            // OPTION NAME VERBATIM AT SECTION SCALE, VERTICALLY CENTERED; THE DESCRIPTION LIVES IN THE
            // CURSOR TOOLTIP
            final int labelMaxW = Math.max(80, this.control.left() - this.left - 30);
            final int labelY = this.top + Math.max(0, (h - canvas.textHeight(AppTheme.TEXT_SECTION, true)) / 2);
            canvas.text(canvas.text().truncateToWidth(this.setting.label, labelMaxW, AppTheme.TEXT_SECTION, Font.BOLD),
                    this.left + 18, labelY, this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT, AppTheme.TEXT_SECTION, true);
            // PUBLISH THE HOVERED ROW'S DESCRIPTION FOR THE SCREEN'S CURSOR TOOLTIP (DRAWN ABOVE THE TREE)
            if (this.hovered && !this.setting.note.isBlank()) SettingsScreen.this.hoverNote = this.setting.note;
            // SELECTION TINT FOR THE SEEKBAR VALUE, SET AT DRAW TIME, AFTER THE LIST STAMPED selected
            if (this.seekValue != null) this.seekValue.color(this.selected ? AppTheme.NEON_LIGHT : AppTheme.TEXT_SOFT);
            super.onDraw(canvas);
        }
    }
}
