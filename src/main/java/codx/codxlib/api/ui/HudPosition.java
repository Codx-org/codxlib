package codx.codxlib.api.ui;

/**
 * Resolution-independent position for a HUD element: an {@link HudAnchor} plus a
 * pixel offset. Plain mutable fields so it serializes directly inside a
 * {@code JsonConfig} data class. Resolve to absolute top-left screen coordinates
 * with {@link #x}/{@link #y}, given the screen and element size.
 *
 * <pre>{@code
 * public final class Settings { public HudPosition icon = new HudPosition(); }
 * int x = settings.icon.x(screenW, elemW);
 * int y = settings.icon.y(screenH, elemH);
 * }</pre>
 */
public final class HudPosition {

    public HudAnchor anchor = HudAnchor.TOP_LEFT;
    public int offsetX = 0;
    public int offsetY = 0;

    public HudPosition() {
    }

    public HudPosition(HudAnchor anchor, int offsetX, int offsetY) {
        this.anchor = anchor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** Absolute left edge of the element on a screen of the given width. */
    public int x(int screenWidth, int elementWidth) {
        return Math.round(screenWidth * anchor.fx - elementWidth * anchor.fx) + offsetX;
    }

    /** Absolute top edge of the element on a screen of the given height. */
    public int y(int screenHeight, int elementHeight) {
        return Math.round(screenHeight * anchor.fy - elementHeight * anchor.fy) + offsetY;
    }

    /**
     * Re-anchors this position so the element's top-left lands at ({@code x},{@code y})
     * using the given anchor, storing the remainder as the offset. Used by the editor
     * to snap a dragged element to the nearest anchor while preserving where it was dropped.
     */
    public void setFromTopLeft(int x, int y, int screenWidth, int screenHeight,
                               int elementWidth, int elementHeight, HudAnchor anchor) {
        this.anchor = anchor;
        this.offsetX = x - Math.round(screenWidth * anchor.fx - elementWidth * anchor.fx);
        this.offsetY = y - Math.round(screenHeight * anchor.fy - elementHeight * anchor.fy);
    }
}
