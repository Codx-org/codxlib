package codx.codxlib.api.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Reusable RGB colour picker: three channel sliders plus a live preview swatch and
 * hex readout. Reads and writes a 0xRRGGBB colour through the supplied
 * getter/setter, so it works for any colour field. Changes apply live; Done
 * returns to the parent screen.
 */
public class CodxColorScreen extends CodxConfigScreen {

    private final IntSupplier getColor;
    private final IntConsumer setColor;

    public CodxColorScreen(Screen parent, Component title, IntSupplier getColor, IntConsumer setColor) {
        super(parent, title);
        this.getColor = getColor;
        this.setColor = setColor;
    }

    @Override
    protected void addContents() {
        int left = width / 2 - 110;
        addRenderableWidget(new ChannelSlider(left, 56, Component.literal("Red"), 16));
        addRenderableWidget(new ChannelSlider(left, 88, Component.literal("Green"), 8));
        addRenderableWidget(new ChannelSlider(left, 120, Component.literal("Blue"), 0));
    }

    @Override
    protected void drawExtras(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int color = getColor.getAsInt();
        graphics.fill(width / 2 - 40, 152, width / 2 + 40, 176, 0xFF000000 | color);
        graphics.outline(width / 2 - 40, 152, 80, 24, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal(String.format("#%06X", color & 0xFFFFFF)),
                width / 2, 182, 0xFFFFFF);
    }

    private final class ChannelSlider extends AbstractSliderButton {
        private final Component channelLabel;
        private final int shift;

        private ChannelSlider(int x, int y, Component channelLabel, int shift) {
            super(x, y, 220, 20, Component.empty(), ((getColor.getAsInt() >> shift) & 0xFF) / 255.0D);
            this.channelLabel = channelLabel;
            this.shift = shift;
            updateMessage();
        }

        private int channelValue() {
            return (int) Math.round(value * 255.0D);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(channelLabel.getString() + ": " + channelValue()));
        }

        @Override
        protected void applyValue() {
            int mask = 0xFF << shift;
            int updated = (getColor.getAsInt() & ~mask) | (channelValue() << shift);
            setColor.accept(updated & 0xFFFFFF);
            updateMessage();
        }
    }
}
