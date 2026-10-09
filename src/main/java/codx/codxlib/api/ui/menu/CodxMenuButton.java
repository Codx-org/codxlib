package codx.codxlib.api.ui.menu;

//? if >=1.20.5 {
import net.minecraft.core.component.DataComponents;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//? if >=1.20.5 {
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * One clickable item in a {@link CodxMenu}. A button is an icon supplier
 * (re-evaluated on every refresh, so toggles/values stay live) plus an optional
 * click handler. The static factories cover the cases chest menus repeat:
 * static labels, boolean toggles, clamped integer adjusters/sliders, and raw
 * actions. Colour codes use the legacy {@code §} style, matching vanilla lore.
 */
public final class CodxMenuButton {

    final Supplier<ItemStack> icon;
    final Consumer<CodxMenuClick> onClick;

    private CodxMenuButton(Supplier<ItemStack> icon, Consumer<CodxMenuClick> onClick) {
        this.icon = icon;
        this.onClick = onClick;
    }

    /** Fully custom button: supply your own icon and click behaviour. */
    public static CodxMenuButton of(Supplier<ItemStack> icon, Consumer<CodxMenuClick> onClick) {
        return new CodxMenuButton(icon, onClick);
    }

    /** Non-interactive label / header / decoration. */
    public static CodxMenuButton info(Item item, String name, String... lore) {
        return new CodxMenuButton(() -> makeInfo(item, name, lore), null);
    }

    /** "{label}: ON/OFF" button that flips a boolean and marks the menu changed. */
    public static CodxMenuButton toggle(Item item, String label, BooleanSupplier get,
                                        Consumer<Boolean> set, String... lore) {
        return new CodxMenuButton(
                () -> makeToggle(item, label, get.getAsBoolean(), lore),
                click -> {
                    set.accept(!get.getAsBoolean());
                    click.markChanged();
                });
    }

    /** Integer adjuster: left = decrement, right = increment, shift = big step. */
    public static CodxMenuButton adjustInt(Item item, String label, IntSupplier get,
                                           IntConsumer set, Step step, String... lore) {
        return adjust(item, label, get, set, step, false, lore);
    }

    /** Like {@link #adjustInt} but also renders the value as the item's stack count. */
    public static CodxMenuButton slider(Item item, String label, IntSupplier get,
                                        IntConsumer set, Step step, String... lore) {
        return adjust(item, label, get, set, step, true, lore);
    }

    /** Decimal adjuster: left = decrement, right = increment, shift = big step. */
    public static CodxMenuButton adjustDouble(Item item, String label, DoubleSupplier get,
                                              DoubleConsumer set, DoubleStep step, String... lore) {
        return new CodxMenuButton(
                () -> makeAdjustDouble(item, label, get.getAsDouble(), step, lore),
                click -> {
                    double delta = step.delta(click.rightClick(), click.shift());
                    set.accept(step.clamp(get.getAsDouble() + delta));
                    click.markChanged();
                });
    }

    /**
     * "{label}: {current}" button that steps to the next value of a fixed set. Deliberately takes a
     * {@code Supplier<String>} and a {@code Runnable} rather than an enum, so it fits anything that
     * cycles — an enum setting, a list of modes, a colour.
     */
    public static CodxMenuButton cycle(Item item, String label, Supplier<String> get,
                                       Runnable next, String... lore) {
        return new CodxMenuButton(
                () -> makeCycle(item, label, get.get(), lore),
                click -> {
                    next.run();
                    click.markChanged();
                });
    }

    /** Raw action button — runs your handler; call {@code click.markChanged()} if it mutated state. */
    public static CodxMenuButton action(Item item, String name, Consumer<CodxMenuClick> onClick, String... lore) {
        return new CodxMenuButton(() -> makeInfo(item, name, lore), onClick);
    }

    private static CodxMenuButton adjust(Item item, String label, IntSupplier get, IntConsumer set,
                                         Step step, boolean asSlider, String... lore) {
        return new CodxMenuButton(
                () -> makeAdjust(item, label, get.getAsInt(), step, asSlider, lore),
                click -> {
                    int delta = step.delta(click.rightClick(), click.shift());
                    set.accept(step.clamp(get.getAsInt() + delta));
                    click.markChanged();
                });
    }

    // ── icon builders ────────────────────────────────────────────────────────

    private static ItemStack makeInfo(Item item, String name, String[] lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, lit(name));
        if (lore.length > 0) {
            setLore(stack, lines(lore));
        }
        hideAttrs(stack);
        return stack;
    }

    private static ItemStack makeToggle(Item item, String label, boolean on, String[] lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, lit((on ? "§a" : "§c") + label + ": " + (on ? "ON" : "OFF")));

        List<Component> l = new ArrayList<>();
        l.add(lit(on ? "§7Status: §aEnabled" : "§7Status: §cDisabled"));
        if (lore.length > 0) {
            l.add(lit(""));
        }
        for (String line : lore) {
            l.add(lit(line));
        }
        l.add(lit(""));
        l.add(lit("§7Click to toggle"));
        setLore(stack, l);

        hideAttrs(stack);
        if (on) {
            setGlint(stack);
        }
        return stack;
    }

    private static ItemStack makeAdjust(Item item, String label, int value, Step step,
                                        boolean asSlider, String[] lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, lit("§b" + label));

        List<Component> l = new ArrayList<>();
        l.add(lit("§7Current: §e" + step.format.apply(value)));
        if (lore.length > 0) {
            l.add(lit(""));
        }
        for (String line : lore) {
            l.add(lit(line));
        }
        l.add(lit(""));
        l.add(lit("§7Left: §c-" + step.step + " §7| Right: §a+" + step.step));
        if (step.shiftStep != step.step) {
            l.add(lit("§7Shift+Left: §c-" + step.shiftStep + " §7| Shift+Right: §a+" + step.shiftStep));
        }
        setLore(stack, l);
        hideAttrs(stack);

        if (asSlider) {
            stack.setCount(Math.max(1, Math.min(64, value)));
        }
        return stack;
    }

    private static ItemStack makeAdjustDouble(Item item, String label, double value, DoubleStep step, String[] lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, lit("§b" + label));

        List<Component> l = new ArrayList<>();
        l.add(lit("§7Current: §e" + step.format.apply(value)));
        if (lore.length > 0) {
            l.add(lit(""));
        }
        for (String line : lore) {
            l.add(lit(line));
        }
        l.add(lit(""));
        l.add(lit("§7Left: §c-" + step.text(step.step) + " §7| Right: §a+" + step.text(step.step)));
        if (step.shiftStep != step.step) {
            l.add(lit("§7Shift+Left: §c-" + step.text(step.shiftStep) + " §7| Shift+Right: §a+" + step.text(step.shiftStep)));
        }
        setLore(stack, l);
        hideAttrs(stack);
        return stack;
    }

    private static ItemStack makeCycle(Item item, String label, String current, String[] lore) {
        ItemStack stack = new ItemStack(item);
        setName(stack, lit("§b" + label + ": §e" + current));

        List<Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(lit(line));
        }
        if (lore.length > 0) {
            l.add(lit(""));
        }
        l.add(lit("§7Click to change"));
        setLore(stack, l);
        hideAttrs(stack);
        return stack;
    }

    private static List<Component> lines(String[] lore) {
        List<Component> l = new ArrayList<>(lore.length);
        for (String line : lore) {
            l.add(lit(line));
        }
        return l;
    }

    private static Component lit(String text) {
        return Component.literal(text);
    }

    // ── item decoration: 1.20.5+ DataComponents vs 1.20.1 NBT ────────────────
    //? if >=1.20.5 {
    private static void setName(ItemStack s, Component name) {
        s.set(DataComponents.CUSTOM_NAME, name);
    }

    private static void setLore(ItemStack s, List<Component> lore) {
        s.set(DataComponents.LORE, new ItemLore(lore));
    }

    private static void hideAttrs(ItemStack s) {
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
    }

    private static void setGlint(ItemStack s) {
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
    }
    //?} else {
    /*private static void setName(ItemStack s, Component name) {
        s.setHoverName(name);
    }

    private static void setLore(ItemStack s, List<Component> lore) {
        net.minecraft.nbt.ListTag tag = new net.minecraft.nbt.ListTag();
        for (Component c : lore) {
            tag.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(c)));
        }
        s.getOrCreateTagElement("display").put("Lore", tag);
    }

    private static void hideAttrs(ItemStack s) {
        // 1.20.1: no ATTRIBUTE_MODIFIERS component; menu items are display-only.
    }

    private static void setGlint(ItemStack s) {
        // 1.20.1: no ENCHANTMENT_GLINT_OVERRIDE; skip glint (cosmetic).
    }
    *///?}

    /**
     * Step model for integer adjusters/sliders: a normal step, a shift step, an
     * optional clamp range, and an optional display formatter (e.g. add "blocks").
     */
    public static final class Step {

        final int step;
        final int shiftStep;
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        IntFunction<String> format = Integer::toString;

        private Step(int step, int shiftStep) {
            this.step = step;
            this.shiftStep = shiftStep;
        }

        /** Normal step magnitude and a larger shift-step magnitude. */
        public static Step of(int step, int shiftStep) {
            return new Step(step, shiftStep);
        }

        /** Same magnitude with and without shift. */
        public static Step of(int step) {
            return new Step(step, step);
        }

        public Step min(int min) {
            this.min = min;
            return this;
        }

        public Step max(int max) {
            this.max = max;
            return this;
        }

        public Step range(int lo, int hi) {
            this.min = lo;
            this.max = hi;
            return this;
        }

        /** Customise how the current value is displayed (e.g. {@code v -> v + " blocks"}). */
        public Step format(IntFunction<String> format) {
            this.format = format;
            return this;
        }

        int delta(boolean right, boolean shift) {
            int mag = shift ? shiftStep : step;
            return right ? mag : -mag;
        }

        int clamp(int value) {
            return Math.max(min, Math.min(max, value));
        }
    }

    /**
     * Step model for {@link #adjustDouble}. The default formatter trims a trailing {@code .0} and
     * otherwise prints two decimals, so a whole number reads as {@code 3} rather than {@code 3.00}
     * and {@code 0.25} survives intact.
     */
    public static final class DoubleStep {

        final double step;
        final double shiftStep;
        double min = -Double.MAX_VALUE;
        double max = Double.MAX_VALUE;
        DoubleFunction<String> format = DoubleStep::plain;

        private DoubleStep(double step, double shiftStep) {
            this.step = step;
            this.shiftStep = shiftStep;
        }

        /** Normal step magnitude and a larger shift-step magnitude. */
        public static DoubleStep of(double step, double shiftStep) {
            return new DoubleStep(step, shiftStep);
        }

        /** Same magnitude with and without shift. */
        public static DoubleStep of(double step) {
            return new DoubleStep(step, step);
        }

        public DoubleStep min(double min) {
            this.min = min;
            return this;
        }

        public DoubleStep max(double max) {
            this.max = max;
            return this;
        }

        public DoubleStep range(double lo, double hi) {
            this.min = lo;
            this.max = hi;
            return this;
        }

        /** Customise how the current value is displayed (e.g. {@code v -> v + "x"}). */
        public DoubleStep format(DoubleFunction<String> format) {
            this.format = format;
            return this;
        }

        double delta(boolean right, boolean shift) {
            double mag = shift ? shiftStep : step;
            return right ? mag : -mag;
        }

        double clamp(double value) {
            return Math.max(min, Math.min(max, value));
        }

        String text(double value) {
            return plain(value);
        }

        private static String plain(double value) {
            if (value == Math.rint(value) && !Double.isInfinite(value)) {
                return String.valueOf((long) value);
            }
            String formatted = String.format(java.util.Locale.ROOT, "%.4f", value);
            // Trim the padding %.4f adds, but never the digit before the point.
            formatted = formatted.replaceAll("0+$", "");
            return formatted.endsWith(".") ? formatted.substring(0, formatted.length() - 1) : formatted;
        }
    }
}
