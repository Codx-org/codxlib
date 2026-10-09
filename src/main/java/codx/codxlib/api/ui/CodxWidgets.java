package codx.codxlib.api.ui;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Factories for the widgets config screens repeat: on/off toggles, value cyclers,
 * and clamped int/double sliders. Each manages its own label updates so callers
 * only supply a getter + setter.
 */
public final class CodxWidgets {

    private CodxWidgets() {
    }

    /** "{label}: ON/OFF" button that flips a boolean. */
    public static Button toggle(int x, int y, int width, int height, Component label,
                                BooleanSupplier getter, Consumer<Boolean> setter) {
        return Button.builder(toggleLabel(label, getter.getAsBoolean()), button -> {
            boolean next = !getter.getAsBoolean();
            setter.accept(next);
            button.setMessage(toggleLabel(label, next));
        }).bounds(x, y, width, height).build();
    }

    /** Cycles through {@code values}, labelling each via {@code labeler} (e.g. enum options). */
    public static <T> Button cycle(int x, int y, int width, int height,
                                   Supplier<T> getter, Consumer<T> setter,
                                   Function<T, Component> labeler, T[] values) {
        return Button.builder(labeler.apply(getter.get()), button -> {
            T current = getter.get();
            int index = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(current)) {
                    index = i;
                    break;
                }
            }
            T next = values[(index + 1) % values.length];
            setter.accept(next);
            button.setMessage(labeler.apply(next));
        }).bounds(x, y, width, height).build();
    }

    /** Slider over an inclusive integer range, labelled "{label}: {value}". */
    public static AbstractSliderButton intSlider(int x, int y, int width, int height, Component label,
                                                 int min, int max, IntSupplier getter, IntConsumer setter) {
        return new IntSlider(x, y, width, height, label, min, max, getter, setter);
    }

    /** Slider over a double range, labelled "{label}: {value}" with {@code decimals} places. */
    public static AbstractSliderButton doubleSlider(int x, int y, int width, int height, Component label,
                                                    double min, double max, int decimals,
                                                    DoubleSupplier getter, DoubleConsumer setter) {
        return new DoubleSlider(x, y, width, height, label, min, max, decimals, getter, setter);
    }

    private static Component toggleLabel(Component label, boolean on) {
        return Component.literal(label.getString() + ": " + (on ? "ON" : "OFF"));
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final Component label;
        private final int min;
        private final int max;
        private final IntConsumer setter;

        private IntSlider(int x, int y, int width, int height, Component label,
                          int min, int max, IntSupplier getter, IntConsumer setter) {
            super(x, y, width, height, Component.empty(),
                    max == min ? 0.0D : (double) (getter.getAsInt() - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            updateMessage();
        }

        private int currentValue() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label.getString() + ": " + currentValue()));
        }

        @Override
        protected void applyValue() {
            setter.accept(currentValue());
        }
    }

    private static final class DoubleSlider extends AbstractSliderButton {
        private final Component label;
        private final double min;
        private final double max;
        private final int decimals;
        private final DoubleConsumer setter;

        private DoubleSlider(int x, int y, int width, int height, Component label,
                             double min, double max, int decimals, DoubleSupplier getter, DoubleConsumer setter) {
            super(x, y, width, height, Component.empty(),
                    max == min ? 0.0D : (getter.getAsDouble() - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.decimals = decimals;
            this.setter = setter;
            updateMessage();
        }

        private double currentValue() {
            return min + value * (max - min);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label.getString() + ": " + String.format("%." + decimals + "f", currentValue())));
        }

        @Override
        protected void applyValue() {
            setter.accept(currentValue());
        }
    }
}
