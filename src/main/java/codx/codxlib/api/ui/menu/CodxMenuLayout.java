package codx.codxlib.api.ui.menu;

import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import codx.codxlib.api.ui.menu.CodxMenuButton.DoubleStep;
import codx.codxlib.api.ui.menu.CodxMenuButton.Step;

/**
 * The content of a single menu screen: a slot → button mapping. Both the
 * {@code simple} menu (one layout) and each {@code paged} page receive one of
 * these and bind widgets to config getters/setters. Slots index left-to-right,
 * top-to-bottom (row {@code r}, column {@code c} → {@code r * 9 + c}).
 */
public final class CodxMenuLayout {

    private final Map<Integer, CodxMenuButton> buttons = new LinkedHashMap<>();

    Map<Integer, CodxMenuButton> buttons() {
        return buttons;
    }

    int maxSlot() {
        int max = -1;
        for (int slot : buttons.keySet()) {
            max = Math.max(max, slot);
        }
        return max;
    }

    /** Place any pre-built button at a slot. */
    public CodxMenuLayout put(int slot, CodxMenuButton button) {
        buttons.put(slot, button);
        return this;
    }

    /** Non-interactive label / header. */
    public CodxMenuLayout info(int slot, Item item, String name, String... lore) {
        return put(slot, CodxMenuButton.info(item, name, lore));
    }

    /** Boolean toggle bound to a getter/setter. */
    public CodxMenuLayout toggle(int slot, Item item, String label, BooleanSupplier get,
                                 Consumer<Boolean> set, String... lore) {
        return put(slot, CodxMenuButton.toggle(item, label, get, set, lore));
    }

    /** Integer adjuster (left −, right +, shift = big step). */
    public CodxMenuLayout adjustInt(int slot, Item item, String label, IntSupplier get,
                                    IntConsumer set, Step step, String... lore) {
        return put(slot, CodxMenuButton.adjustInt(item, label, get, set, step, lore));
    }

    /** Integer adjuster that also shows the value as the item's stack count. */
    public CodxMenuLayout slider(int slot, Item item, String label, IntSupplier get,
                                 IntConsumer set, Step step, String... lore) {
        return put(slot, CodxMenuButton.slider(item, label, get, set, step, lore));
    }

    /** Decimal adjuster (left −, right +, shift = big step). */
    public CodxMenuLayout adjustDouble(int slot, Item item, String label, DoubleSupplier get,
                                       DoubleConsumer set, DoubleStep step, String... lore) {
        return put(slot, CodxMenuButton.adjustDouble(item, label, get, set, step, lore));
    }

    /** Steps through a fixed set of values — an enum setting, a mode, a colour. */
    public CodxMenuLayout cycle(int slot, Item item, String label, Supplier<String> get,
                                Runnable next, String... lore) {
        return put(slot, CodxMenuButton.cycle(item, label, get, next, lore));
    }

    /** Raw action button. */
    public CodxMenuLayout action(int slot, Item item, String name, Consumer<CodxMenuClick> onClick, String... lore) {
        return put(slot, CodxMenuButton.action(item, name, onClick, lore));
    }
}
