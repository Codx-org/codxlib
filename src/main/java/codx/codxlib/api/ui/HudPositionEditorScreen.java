package codx.codxlib.api.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.9 {
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Reusable "drag to position" editor for a HUD element. Shows a live preview of the
 * element (drawn by the supplied {@link Renderer}) that the player can drag anywhere;
 * on save it snaps to the nearest {@link HudAnchor} and stores the offset, so the
 * placement stays resolution-independent. Built on the shared {@link CodxConfigScreen}
 * look.
 *
 * <pre>{@code
 * minecraft.setScreen(new HudPositionEditorScreen(this,
 *     Component.literal("Set Icon Position"), 24, 28,
 *     config.iconPosition,
 *     (g, x, y) -> drawIcon(g, x, y),
 *     pos -> { config.save(); }));
 * }</pre>
 */
public class HudPositionEditorScreen extends CodxConfigScreen {

    /** Draws the element with its top-left corner at ({@code x},{@code y}). */
    @FunctionalInterface
    public interface Renderer {
        void render(GuiGraphicsExtractor graphics, int x, int y);
    }

    private final int elementWidth;
    private final int elementHeight;
    private final HudPosition position;
    private final Renderer renderer;
    private final Consumer<HudPosition> onSave;

    private int curX;
    private int curY;
    private boolean dragging;
    private int grabDX;
    private int grabDY;

    public HudPositionEditorScreen(Screen parent, Component title, int elementWidth, int elementHeight,
                                   HudPosition position, Renderer renderer, Consumer<HudPosition> onSave) {
        super(parent, title, Component.literal("Drag the element — it snaps to the nearest anchor"));
        this.elementWidth = elementWidth;
        this.elementHeight = elementHeight;
        this.position = position;
        this.renderer = renderer;
        this.onSave = onSave;
    }

    @Override
    protected void addContents() {
        // Clamp into the visible area so a stale/off-screen stored position still opens
        // grabbable (e.g. an offset migrated from a different anchor convention).
        curX = clamp(position.x(width, elementWidth), 0, Math.max(0, width - elementWidth));
        curY = clamp(position.y(height, elementHeight), 0, Math.max(0, height - elementHeight));
    }

    @Override
    protected void drawExtras(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(curX - 2, curY - 2, curX + elementWidth + 2, curY + elementHeight + 2,
                dragging ? 0x40FFFFFF : 0x18FFFFFF);
        graphics.outline(curX - 2, curY - 2, elementWidth + 4, elementHeight + 4, accent());
        renderer.render(graphics, curX, curY);
    }

    // 1.21.9 replaced the (x, y, button) mouse-listener parameters with a MouseButtonEvent
    // record, so the three drag handlers exist in two shapes. Both delegate to the same
    // era-independent grab/drag/drop logic below.
    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && grab(event.x(), event.y())) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (drag(event.x(), event.y())) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && drop()) {
            return true;
        }
        return super.mouseReleased(event);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && grab(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (drag(mouseX, mouseY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && drop()) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }
    *///?}

    private boolean grab(double mouseX, double mouseY) {
        if (!inElement(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        grabDX = (int) Math.round(mouseX) - curX;
        grabDY = (int) Math.round(mouseY) - curY;
        return true;
    }

    private boolean drag(double mouseX, double mouseY) {
        if (!dragging) {
            return false;
        }
        curX = clamp((int) Math.round(mouseX) - grabDX, 0, width - elementWidth);
        curY = clamp((int) Math.round(mouseY) - grabDY, 0, height - elementHeight);
        return true;
    }

    private boolean drop() {
        if (!dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    @Override
    protected void onSave() {
        HudAnchor anchor = nearestAnchor(curX + elementWidth / 2, curY + elementHeight / 2);
        position.setFromTopLeft(curX, curY, width, height, elementWidth, elementHeight, anchor);
        if (onSave != null) {
            onSave.accept(position);
        }
    }

    private boolean inElement(double mouseX, double mouseY) {
        return mouseX >= curX && mouseX < curX + elementWidth
                && mouseY >= curY && mouseY < curY + elementHeight;
    }

    private HudAnchor nearestAnchor(int centerX, int centerY) {
        HudAnchor best = HudAnchor.TOP_LEFT;
        long bestDistance = Long.MAX_VALUE;
        for (HudAnchor anchor : HudAnchor.values()) {
            int ax = Math.round(width * anchor.fx);
            int ay = Math.round(height * anchor.fy);
            long distance = (long) (ax - centerX) * (ax - centerX) + (long) (ay - centerY) * (ay - centerY);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = anchor;
            }
        }
        return best;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
