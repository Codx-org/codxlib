package codx.codxlib.api.ui.menu;

import codx.codxlib.api.settings.CodxSettings;
import codx.codxlib.api.settings.CodxSettingsCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Turns a {@link CodxSettings} spec into a browsable chest menu — one call, no per-setting work.
 * The third of the four ways a setting can be changed, beside the JSON file, the command
 * ({@link CodxSettingsCommand}) and code holding the handle.
 *
 * <pre>{@code
 * CodxSettingsMenu.open(player, SPEC);      // or from /<mod> config menu, which is free
 * }</pre>
 *
 * <p>The landing page lists the spec's categories; clicking one drills into its settings, laid out
 * 28 to a page. Booleans become toggles, numbers become adjusters (left −, right +, shift for the
 * big step), enums cycle. Text and lists are shown read-only — a chest menu has no keyboard — with
 * a lore line pointing at the command, which can set them.
 *
 * <p><b>Server-side and vanilla-client-safe.</b> Like everything in {@code api.ui.menu} this is a
 * {@code GENERIC_9xN} container with decorated items in it, so it works for a player on an
 * unmodified client and needs no per-loader registration.
 *
 * <p><b>Every click writes the file.</b> Each mutation runs {@link CodxSettings#apply()}, so the
 * menu, the command and a hand-edited file never disagree, and the mod's {@code onChange} hook
 * re-bakes live values on the spot.
 *
 * <p><b>Big categories drill down.</b> A category with hundreds of options is unusable as a flat
 * list, so {@link Builder#group} takes a function from setting to group name — Alex's Mobs uses it
 * to turn 176 spawn numbers into ~90 per-mob pages of two.
 *
 * <p><b>A list or string setting can be made clickable</b> with {@link Builder#editor}, which hands
 * one named setting to a page the mod builds itself. That is the only way a chest menu can edit a
 * value it cannot type: the mod knows what the entries mean and can offer them as buttons, where
 * the generic renderer can only show the text and point at the command.
 */
public final class CodxSettingsMenu {

    /** Slots 0–44 are the page body; this is an inset 7×4 grid inside it, which reads better. */
    private static final int PAGE_CAPACITY = 28;

    /**
     * Free in {@code PagedMenuBuilder}'s nav row whenever no reset button is set — which is exactly
     * the sub-pages, where a back button is what is wanted there instead.
     */
    private static final int SLOT_BACK = 45;

    private CodxSettingsMenu() {
    }

    /** Opens the menu with defaults for everything. */
    public static void open(ServerPlayer player, CodxSettings spec) {
        builder(spec).open(player);
    }

    /** Opens the menu, annotating any setting the note applies to. */
    public static void open(ServerPlayer player, CodxSettings spec, CodxSettingsCommand.ChangeNote note) {
        builder(spec).note(note).open(player);
    }

    public static Builder builder(CodxSettings spec) {
        return new Builder(spec);
    }

    /**
     * A mod-supplied page for one setting the generic renderer can only show read-only.
     *
     * <p>{@code back} reopens the settings page the player clicked from, so the editor can put it on
     * its own back button and the player lands where they were rather than at the root.
     */
    @FunctionalInterface
    public interface Editor {
        void open(ServerPlayer player, Runnable back);
    }

    /** Per-mod presentation: title, icons, drill-down grouping, the command to point at. */
    public static final class Builder {

        private final CodxSettings spec;
        private final Map<String, Item> icons = new LinkedHashMap<>();
        private final Map<String, Function<CodxSettings.ConfigValue<?>, String>> groupers = new LinkedHashMap<>();
        private final Map<String, Editor> editors = new LinkedHashMap<>();
        private String title;
        private String command;
        private CodxSettingsCommand.ChangeNote note;

        private Builder(CodxSettings spec) {
            this.spec = spec;
        }

        /** Menu title. Defaults to the config file's name. */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * The command that can do what the menu cannot — set text and list options. Shown in the
         * lore of those settings, e.g. {@code "/aac config"}.
         */
        public Builder command(String command) {
            this.command = command;
            return this;
        }

        /** Adds a lore line to any setting the note applies to, e.g. "needs a world reload". */
        public Builder note(CodxSettingsCommand.ChangeNote note) {
            this.note = note;
            return this;
        }

        /** Icon for a category or a group, by its name. */
        public Builder icon(String key, Item item) {
            icons.put(key, item);
            return this;
        }

        /**
         * Splits one category into sub-pages. The function names the group a setting belongs to;
         * returning null puts it on the category's own page alongside the group buttons.
         */
        public Builder group(String category, Function<CodxSettings.ConfigValue<?>, String> grouper) {
            groupers.put(category, grouper);
            return this;
        }

        /**
         * Makes one setting open a page of the mod's own instead of rendering read-only. Intended
         * for lists and strings, whose tiles are otherwise inert; registering one for a setting the
         * menu can already edit replaces that widget, so don't.
         *
         * @param setting the setting's {@link CodxSettings.ConfigValue#name() name}, exactly
         */
        public Builder editor(String setting, Editor editor) {
            editors.put(setting, editor);
            return this;
        }

        public void open(ServerPlayer player) {
            openRoot(player, this);
        }

        private String title() {
            return title != null ? title : "§6§l" + spec.fileName();
        }

        private Item iconOr(String key, Item fallback) {
            return icons.getOrDefault(key, fallback);
        }

        private Editor editorFor(CodxSettings.ConfigValue<?> value) {
            return editors.get(value.name());
        }
    }

    // ── screens ─────────────────────────────────────────────────────────────────

    private static void openRoot(ServerPlayer player, Builder cfg) {
        List<String> categories = new ArrayList<>(cfg.spec.categories());
        PagedMenuBuilder menu = CodxMenu.paged(cfg.title())
                .onChange(cfg.spec::apply)
                .resetButton(click -> {
                    cfg.spec.resetAll();
                    cfg.spec.apply();
                    openRoot(click.player(), cfg);
                });
        paginate(menu, "Categories", categories, (layout, slot, category) -> {
            int size = cfg.spec.category(category).size();
            int changed = (int) cfg.spec.category(category).stream().filter(v -> !v.isDefault()).count();
            layout.action(slot, cfg.iconOr(category, Items.BOOK), "§e§l" + category,
                    click -> openCategory(click.player(), cfg, category),
                    "§7" + size + " setting(s)",
                    changed == 0 ? "§8all at their defaults" : "§a" + changed + " changed",
                    "",
                    "§7Click to open");
        });
        menu.open(player);
    }

    private static void openCategory(ServerPlayer player, Builder cfg, String category) {
        List<CodxSettings.ConfigValue<?>> values = cfg.spec.category(category);
        Consumer<ServerPlayer> reopen = who -> openCategory(who, cfg, category);
        Function<CodxSettings.ConfigValue<?>, String> grouper = cfg.groupers.get(category);
        PagedMenuBuilder menu = CodxMenu.paged(cfg.title() + " §7— " + category)
                .onChange(cfg.spec::apply);

        if (grouper == null) {
            paginate(menu, category, values, (layout, slot, value) -> widget(layout, slot, cfg, value, reopen));
            back(menu, click -> openRoot(click.player(), cfg));
            menu.open(player);
            return;
        }

        // Grouped: anything the grouper declined to place first, then the group buttons. A value
        // that opted out of grouping is a property of the category itself rather than of one of its
        // subjects, so burying it behind ~90 drill-downs — where it lands on the last page and is
        // found by nobody — is exactly backwards.
        Map<String, List<CodxSettings.ConfigValue<?>>> groups = new LinkedHashMap<>();
        List<CodxSettings.ConfigValue<?>> loose = new ArrayList<>();
        for (CodxSettings.ConfigValue<?> value : values) {
            String group = grouper.apply(value);
            if (group == null || group.isEmpty()) {
                loose.add(value);
            } else {
                groups.computeIfAbsent(group, k -> new ArrayList<>()).add(value);
            }
        }

        List<Object> entries = new ArrayList<>(loose);
        entries.addAll(groups.keySet());
        paginate(menu, category, entries, (layout, slot, entry) -> {
            if (entry instanceof CodxSettings.ConfigValue<?> value) {
                widget(layout, slot, cfg, value, reopen);
                return;
            }
            String group = (String) entry;
            List<CodxSettings.ConfigValue<?>> members = groups.get(group);
            int changed = (int) members.stream().filter(v -> !v.isDefault()).count();
            layout.action(slot, cfg.iconOr(group, Items.CHEST), "§e" + prettify(group),
                    click -> openGroup(click.player(), cfg, category, group, members),
                    "§7" + members.size() + " setting(s)",
                    changed == 0 ? "§8all at their defaults" : "§a" + changed + " changed",
                    "",
                    "§7Click to open");
        });
        back(menu, click -> openRoot(click.player(), cfg));
        menu.open(player);
    }

    private static void openGroup(ServerPlayer player, Builder cfg, String category, String group,
                                  List<CodxSettings.ConfigValue<?>> members) {
        Consumer<ServerPlayer> reopen = who -> openGroup(who, cfg, category, group, members);
        PagedMenuBuilder menu = CodxMenu.paged(cfg.title() + " §7— " + prettify(group))
                .onChange(cfg.spec::apply);
        paginate(menu, group, members, (layout, slot, value) -> widget(layout, slot, cfg, value, reopen));
        back(menu, click -> openCategory(click.player(), cfg, category));
        menu.open(player);
    }

    // ── layout ──────────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Placer<T> {
        void place(CodxMenuLayout layout, int slot, T entry);
    }

    /**
     * Spreads entries over as many pages as they need, 28 per page in an inset 7×4 grid — slots 0–8,
     * both edge columns and the nav row are left empty, which is what makes a generated menu look
     * laid out rather than dumped.
     */
    private static <T> void paginate(PagedMenuBuilder menu, String title, Collection<T> entries, Placer<T> placer) {
        List<T> list = new ArrayList<>(entries);
        int pages = Math.max(1, (list.size() + PAGE_CAPACITY - 1) / PAGE_CAPACITY);
        for (int page = 0; page < pages; page++) {
            int from = page * PAGE_CAPACITY;
            List<T> slice = list.subList(from, Math.min(list.size(), from + PAGE_CAPACITY));
            menu.page(prettify(title), layout -> {
                for (int i = 0; i < slice.size(); i++) {
                    placer.place(layout, slotFor(i), slice.get(i));
                }
            });
        }
    }

    /** Index → slot in the inset grid: rows 1–4, columns 1–7. */
    private static int slotFor(int index) {
        return 10 + (index / 7) * 9 + (index % 7);
    }

    /**
     * The back button rides in the nav row's reset slot, which is free precisely because a sub-page
     * sets no reset button — only the landing page does. {@code decorate} puts it on every page of
     * the sub-menu without the caller having to repeat it per page.
     */
    private static void back(PagedMenuBuilder menu, Consumer<CodxMenuClick> onBack) {
        menu.decorate(layout -> layout.action(SLOT_BACK, Items.ARROW, "§a§l← Back", onBack, "§7Go back"));
    }

    // ── widgets ─────────────────────────────────────────────────────────────────

    private static void widget(CodxMenuLayout layout, int slot, Builder cfg, CodxSettings.ConfigValue<?> value,
                               Consumer<ServerPlayer> reopen) {
        String label = prettify(value.name());
        String[] lore = lore(cfg, value);

        Editor editor = cfg.editorFor(value);
        if (editor != null) {
            // The editor page is the mod's, so it writes through the same ConfigValue and the menu's
            // own onChange never sees it — reopen is what brings the player back to a redrawn tile.
            layout.action(slot, cfg.iconOr(value.name(), Items.WRITABLE_BOOK),
                    "§b" + label + ": §e" + shorten(value.asString()),
                    click -> editor.open(click.player(), () -> reopen.accept(click.player())), lore);
            return;
        }

        if (value instanceof CodxSettings.BooleanValue bool) {
            layout.toggle(slot, Items.LEVER, label, bool::get, on -> bool.toggle(), lore);
        } else if (value instanceof CodxSettings.IntValue number) {
            // ⚠️ number::set, NOT number::add. CodxMenuButton.adjust does the arithmetic and the
            // clamping itself and hands the setter an ABSOLUTE value; passing the delta-taking add
            // made every click apply value + (value ± step), which walked the setting to its max
            // whichever button was pressed. Fixed in 1.6.0.
            layout.adjustInt(slot, Items.REPEATER, label, number::get, number::set,
                    intStep(number), lore);
        } else if (value instanceof CodxSettings.DoubleValue number) {
            layout.adjustDouble(slot, Items.COMPARATOR, label, number::get, number::set,
                    doubleStep(number), lore);
        } else if (value instanceof CodxSettings.EnumValue<?> option) {
            layout.cycle(slot, Items.HOPPER, label, option::asString, option::cycle, lore);
        } else {
            Item item = value instanceof CodxSettings.ListValue<?> ? Items.WRITABLE_BOOK : Items.NAME_TAG;
            layout.info(slot, item, "§b" + label + ": §e" + shorten(value.asString()), lore);
        }
    }

    /**
     * The adjusters take a delta, and the value clamps itself to its own declared range — so the
     * {@code Step}'s own clamp is set to the same range and never bites first. Step size scales with
     * the span so a 0–3 option moves by 1 and a 0–100000 one is not 100000 clicks wide.
     */
    private static CodxMenuButton.Step intStep(CodxSettings.IntValue value) {
        long span = (long) value.max() - value.min();
        int step = span > 5000 ? 100 : span > 500 ? 10 : 1;
        return CodxMenuButton.Step.of(step, step * 10).range(value.min(), value.max());
    }

    private static CodxMenuButton.DoubleStep doubleStep(CodxSettings.DoubleValue value) {
        double span = value.max() - value.min();
        double step = span > 100 ? 1 : span > 10 ? 0.5 : span > 2 ? 0.1 : 0.05;
        return CodxMenuButton.DoubleStep.of(step, step * 10).range(value.min(), value.max());
    }

    private static String[] lore(Builder cfg, CodxSettings.ConfigValue<?> value) {
        List<String> lore = new ArrayList<>(wrap(value.comment()));
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.add("§8" + value.name());
        if (!value.rangeText().isEmpty()) {
            lore.add("§7Range: §f" + value.rangeText());
        }
        lore.add("§7Default: §f" + shorten(value.defaultAsString()));
        boolean readOnly = value instanceof CodxSettings.ListValue<?> || value instanceof CodxSettings.StringValue;
        if (readOnly) {
            lore.add("");
            if (cfg.editorFor(value) != null) {
                lore.add("§7Click to edit");
            } else {
                lore.add(cfg.command != null
                        ? "§eSet this with " + cfg.command + " set " + value.name()
                        : "§eEdit this in " + cfg.spec.fileName());
            }
        }
        String note = cfg.note == null ? null : cfg.note.noteFor(value);
        if (note != null && !note.isEmpty()) {
            lore.add("");
            lore.addAll(wrap("§6" + note));
        }
        return lore.toArray(new String[0]);
    }

    // ── text ────────────────────────────────────────────────────────────────────

    /** {@code restrictFarseerSpawns} → {@code Restrict Farseer Spawns}. */
    private static String prettify(String name) {
        StringBuilder out = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '.') {
                out.append(' ');
            } else if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                out.append(' ').append(c);
            } else {
                out.append(i == 0 ? Character.toUpperCase(c) : c);
            }
        }
        return out.toString();
    }

    /** Lore lines are unwrapped by the client, so wrap here — long comments are the norm. */
    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > 44) {
                lines.add("§7" + line);
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add("§7" + line);
        }
        return lines;
    }

    /** A list setting can hold dozens of ids; a lore line cannot. */
    private static String shorten(String text) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= 40 ? flat : flat.substring(0, 37) + "...";
    }
}
