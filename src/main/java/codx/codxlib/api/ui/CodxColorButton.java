package codx.codxlib.api.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.IntSupplier;

/**
 * Button that shows a colour swatch plus its hex value and runs {@code onPress}
 * (typically opening a {@link CodxColorScreen}). The swatch and label track the
 * live colour returned by {@code colorSupplier}.
 */
public final class CodxColorButton extends Button {

    private final IntSupplier colorSupplier;

    public CodxColorButton(int x, int y, int width, int height, IntSupplier colorSupplier, OnPress onPress) {
        super(x, y, width, height, hexLabel(colorSupplier.getAsInt()), onPress, DEFAULT_NARRATION);
        this.colorSupplier = colorSupplier;
    }

    private static Component hexLabel(int color) {
        return Component.literal(String.format("#%06X", color & 0xFFFFFF));
    }

    // The hook that draws a button's contents on top of its vanilla sprite was renamed and,
    // before 1.21.11, did not exist at all — there the whole widget pass (sprite included) is
    // one overridable method, so super has to be called explicitly.
    //? if >=26.1 {
    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawSwatch(graphics);
    }
    //?} elif >=1.21.11 {
    /*@Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        drawSwatch(graphics);
    }
    *///?} else {
    /*@Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        drawSwatch(graphics);
    }
    *///?}

    private void drawSwatch(GuiGraphicsExtractor graphics) {
        setMessage(hexLabel(colorSupplier.getAsInt()));
        int swatchColor = 0xFF000000 | (colorSupplier.getAsInt() & 0x00FFFFFF);
        int inset = isHoveredOrFocused() ? 2 : 3;
        int contentLeft = getX() + inset;
        int contentTop = getY() + inset;
        int contentRight = getX() + getWidth() - inset;
        int contentBottom = getY() + getHeight() - inset;
        int contentWidth = getWidth() - (inset * 2);
        int contentHeight = getHeight() - (inset * 2);
        int swatchWidth = Math.min(18, contentWidth - 8);
        int textLeft = contentLeft + swatchWidth + 3;

        graphics.fill(contentLeft, contentTop, contentRight, contentBottom,
                isHoveredOrFocused() ? 0xFF2B2B2B : 0xFF1E1E1E);
        graphics.fill(contentLeft, contentTop, contentLeft + swatchWidth, contentTop + contentHeight, swatchColor);
        graphics.outline(contentLeft, contentTop, contentWidth, contentHeight,
                isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF5A5A5A);
        graphics.outline(contentLeft, contentTop, swatchWidth, contentHeight, 0xFF101010);

        int textCenterX = textLeft + ((contentRight - textLeft) / 2);
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), textCenterX, getY() + 6, 0xFFFFFFFF);
    }
}
