package codx.codxlib.api.ui.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builds a paginated 6-row settings panel. Each {@link #page} gets its own
 * widgets; the builder auto-injects a navigation row across the bottom (slots
 * 45–53): previous, page title, next, close, and an optional reset. Open with
 * {@link #open(ServerPlayer)}.
 *
 * <p>The bottom row (slots 45–53) is reserved for navigation — place page
 * widgets in slots 0–44.
 *
 * <pre>{@code
 * CodxMenu.paged("§6§lOneBlock Admin")
 *     .onChange(state::setDirty)
 *     .resetButton(c -> cfg.resetToDefaults())
 *     .page("Core Settings", l -> l
 *         .info(0, Items.COMMAND_BLOCK, "§6Core Features", "Main toggles")
 *         .toggle(1, Items.BLAZE_POWDER, "Mob Waves", cfg::wavesOn, cfg::setWavesOn))
 *     .page("Biomes", l -> l
 *         .toggle(1, Items.GRASS_BLOCK, "Biome Cycling", cfg::cyclingOn, cfg::setCyclingOn))
 *     .open(player);
 * }</pre>
 */
public final class PagedMenuBuilder {

    private static final int SLOT_RESET = 45;
    private static final int SLOT_PREV = 47;
    private static final int SLOT_TITLE = 49;
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;

    private final Component title;
    private final List<CodxMenu.Page> pages = new ArrayList<>();
    private final List<Consumer<CodxMenuLayout>> decorations = new ArrayList<>();
    private Runnable onChange;
    private Consumer<CodxMenuClick> reset;
    private Predicate<ServerPlayer> canUse = player -> true;

    PagedMenuBuilder(Component title) {
        this.title = title;
    }

    /** Run after any widget mutates state (e.g. mark a config dirty / save). */
    public PagedMenuBuilder onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    /** Predicate that keeps the menu valid (closes it if it returns false). */
    public PagedMenuBuilder canUse(Predicate<ServerPlayer> canUse) {
        this.canUse = canUse;
        return this;
    }

    /** Add a "Reset to Defaults" button (slot 45) on every page running this handler. */
    public PagedMenuBuilder resetButton(Consumer<CodxMenuClick> reset) {
        this.reset = reset;
        return this;
    }

    /**
     * Add a widget to <i>every</i> page — a back button, a legend, a border. Applied before each
     * page's own widgets, so a page can override a decorated slot, and before the nav row, which
     * always wins. Call it in any order relative to {@link #page}: decorations are applied when the
     * menu opens.
     */
    public PagedMenuBuilder decorate(Consumer<CodxMenuLayout> decoration) {
        decorations.add(decoration);
        return this;
    }

    /** Add a page with the given title; {@code populate} fills its widgets. */
    public PagedMenuBuilder page(String pageTitle, Consumer<CodxMenuLayout> populate) {
        CodxMenuLayout layout = new CodxMenuLayout();
        populate.accept(layout);
        pages.add(new CodxMenu.Page(Component.literal(pageTitle), layout.buttons()));
        return this;
    }

    /** Open the menu for the player. */
    public void open(ServerPlayer player) {
        Map<Integer, CodxMenuButton> decorated = new LinkedHashMap<>();
        if (!decorations.isEmpty()) {
            CodxMenuLayout layout = new CodxMenuLayout();
            for (Consumer<CodxMenuLayout> decoration : decorations) {
                decoration.accept(layout);
            }
            decorated.putAll(layout.buttons());
        }
        List<CodxMenu.Page> finalPages = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) {
            CodxMenu.Page src = pages.get(i);
            Map<Integer, CodxMenuButton> buttons = new LinkedHashMap<>(decorated);
            buttons.putAll(src.buttons());
            addNav(buttons, i, pages.size(), src.title());
            finalPages.add(new CodxMenu.Page(src.title(), buttons));
        }
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, who) -> new CodxMenu(syncId, inventory, player, 6, finalPages, onChange, canUse),
                title));
    }

    private void addNav(Map<Integer, CodxMenuButton> buttons, int index, int total, Component pageTitle) {
        if (reset != null) {
            buttons.put(SLOT_RESET, CodxMenuButton.action(Items.BARRIER, "§c§lReset to Defaults",
                    click -> {
                        reset.accept(click);
                        click.markChanged();
                    },
                    "§7Reset all settings to defaults"));
        }
        if (index > 0) {
            buttons.put(SLOT_PREV, CodxMenuButton.action(Items.ARROW, "§a§l← Previous",
                    CodxMenuClick::prevPage, "§7Go to the previous page"));
        }
        buttons.put(SLOT_TITLE, CodxMenuButton.info(Items.MAP,
                "§e§l" + pageTitle.getString() + " §7(" + (index + 1) + "/" + total + ")"));
        if (index < total - 1) {
            buttons.put(SLOT_NEXT, CodxMenuButton.action(Items.ARROW, "§a§lNext →",
                    CodxMenuClick::nextPage, "§7Go to the next page"));
        }
        buttons.put(SLOT_CLOSE, CodxMenuButton.action(Items.OAK_DOOR, "§c§lClose",
                CodxMenuClick::close, "§7Close the menu"));
    }
}
