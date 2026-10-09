package codx.codxlib.api.ui.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Builds a compact single-page chest menu. Pick a fixed row count or let it size
 * itself from the highest slot used. Open with {@link #open(ServerPlayer)}.
 *
 * <pre>{@code
 * CodxMenu.simple("§6Settings")
 *     .onChange(state::setDirty)
 *     .layout(l -> l
 *         .toggle(11, Items.BLAZE_POWDER, "Mob Waves", cfg::wavesOn, cfg::setWavesOn, "Hostile waves spawn")
 *         .adjustInt(13, Items.ARROW, "Max Wave", cfg::maxWave, cfg::setMaxWave, Step.of(1, 10).min(1))
 *         .action(15, Items.BARRIER, "§cReset", c -> { cfg.reset(); c.markChanged(); }))
 *     .open(player);
 * }</pre>
 */
public final class SimpleMenuBuilder {

    private final Component title;
    private final CodxMenuLayout layout = new CodxMenuLayout();
    private int rows = -1;
    private Runnable onChange;
    private Predicate<ServerPlayer> canUse = player -> true;

    SimpleMenuBuilder(Component title) {
        this.title = title;
    }

    /** Fix the number of rows (1–6). Omit to auto-size from the highest slot used. */
    public SimpleMenuBuilder rows(int rows) {
        this.rows = Math.max(1, Math.min(6, rows));
        return this;
    }

    /** Run after any widget mutates state (e.g. mark a config dirty / save). */
    public SimpleMenuBuilder onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    /** Predicate that keeps the menu valid (closes it if it returns false). */
    public SimpleMenuBuilder canUse(Predicate<ServerPlayer> canUse) {
        this.canUse = canUse;
        return this;
    }

    /** Populate the menu's widgets. May be called more than once. */
    public SimpleMenuBuilder layout(Consumer<CodxMenuLayout> populate) {
        populate.accept(layout);
        return this;
    }

    /** Open the menu for the player. */
    public void open(ServerPlayer player) {
        int resolvedRows = rows > 0 ? rows : autoRows(layout.maxSlot());
        List<CodxMenu.Page> pages = List.of(new CodxMenu.Page(title, layout.buttons()));
        player.openMenu(new SimpleMenuProvider(
                (syncId, inventory, who) -> new CodxMenu(syncId, inventory, player, resolvedRows, pages, onChange, canUse),
                title));
    }

    private static int autoRows(int maxSlot) {
        if (maxSlot < 0) {
            return 1;
        }
        return Math.max(1, Math.min(6, maxSlot / 9 + 1));
    }
}
