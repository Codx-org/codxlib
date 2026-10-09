package codx.codxlib.api.ui;

/**
 * Screen reference point for a HUD element, so positions stay sensible across
 * resolutions: the element sticks to a corner/edge/center and is fine-tuned with a
 * pixel offset. {@code fx}/{@code fy} are the 0..1 fractions of the screen the
 * anchor sits at (and the matching fraction of the element that aligns to it).
 */
public enum HudAnchor {
    TOP_LEFT(0.0f, 0.0f),
    TOP_CENTER(0.5f, 0.0f),
    TOP_RIGHT(1.0f, 0.0f),
    MIDDLE_LEFT(0.0f, 0.5f),
    CENTER(0.5f, 0.5f),
    MIDDLE_RIGHT(1.0f, 0.5f),
    BOTTOM_LEFT(0.0f, 1.0f),
    BOTTOM_CENTER(0.5f, 1.0f),
    BOTTOM_RIGHT(1.0f, 1.0f);

    public final float fx;
    public final float fy;

    HudAnchor(float fx, float fy) {
        this.fx = fx;
        this.fy = fy;
    }
}
