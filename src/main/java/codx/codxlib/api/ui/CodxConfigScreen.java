package codx.codxlib.api.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shared base for CodxLib config screens. Renders the unified vanilla "pack screen"
 * style used across all codx mods: a frosted (blurred) game background, translucent
 * header/footer bars with divider lines, a centered title + optional grey subtitle
 * (with a short accent underline) + optional right-aligned tag, and a Done button in
 * the footer.
 *
 * <p>Subclasses implement {@link #addContents()} to lay out widgets — use the
 * geometry accessors ({@link #contentLeft()}, {@link #contentTop()}, …) so content
 * sits between the bars. Override {@link #onSave()} to persist, {@link #drawExtras}
 * for extra drawing, {@link #headerTag()} for a header badge (e.g. a version), and
 * {@link #setAccent(int)} for a per-mod accent colour.
 */
public abstract class CodxConfigScreen extends Screen {

    /** Default accent (amber/gold). Mods may override via {@link #setAccent(int)}. */
    public static final int ACCENT_AMBER = 0xFFE0A030;

    private static final int HEADER_H = 33;
    private static final int FOOTER_H = 36;
    private static final int SIDE_PAD = 8;

    private static final int BAR_TOP = 0xC0101012;
    private static final int BAR_FADE = 0x60101012;
    private static final int LINE_DARK = 0xFF000000;
    private static final int LINE_LIGHT = 0x22FFFFFF;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFFB0B0B0;
    private static final int TAG_COLOR = 0xFF8A8A8A;

    protected final Screen parent;
    private final Component subtitle;
    private int accent = ACCENT_AMBER;

    protected CodxConfigScreen(Screen parent, Component title) {
        this(parent, title, null);
    }

    protected CodxConfigScreen(Screen parent, Component title, Component subtitle) {
        super(title);
        this.parent = parent;
        this.subtitle = subtitle;
    }

    /** Sets this screen's accent colour (ARGB). Call from a subclass constructor. */
    protected final void setAccent(int argb) {
        this.accent = argb;
    }

    protected final int accent() {
        return accent;
    }

    // --- Layout geometry (usable by subclasses) ---
    protected final int contentLeft() {
        return SIDE_PAD;
    }

    protected final int contentRight() {
        return width - SIDE_PAD;
    }

    protected final int contentTop() {
        return HEADER_H + 1;
    }

    protected final int contentBottom() {
        return height - FOOTER_H - 1;
    }

    @Override
    protected void init() {
        clearWidgets();
        addContents();
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onDone())
                .bounds(width / 2 - 100, height - 27, 200, 20)
                .build());
    }

    /** Lay out your widgets here (between the header and footer). Called on open and resize. */
    protected abstract void addContents();

    /** Persist changes here. Called when Done is pressed or the screen is closed. */
    protected void onSave() {
    }

    /** Optional right-aligned header badge (e.g. a version). Default none. */
    protected Component headerTag() {
        return null;
    }

    /**
     * Whether opening this screen should pause singleplayer (the vanilla default for
     * screens). Override to return {@code false} for live/monitoring screens that need
     * the game to keep ticking while open (e.g. a dashboard showing ongoing progress).
     */
    protected boolean pausesGame() {
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return pausesGame();
    }

    /** Optional extra drawing between the bars. Default no-op. */
    protected void drawExtras(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    private void onDone() {
        onSave();
        minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        onDone();
    }

    // --- Render entry point ---
    // The chrome (bars + header text) must land under the widgets but over the background,
    // and which method that is differs by MC era, because the era decides who runs the
    // background pass:
    //   26.x / 1.21.11 — the widget pass draws widgets only; the background is its own
    //                    earlier pass, so drawing the chrome in the widget pass is correct.
    //   1.20.2-1.21.10 — Screen.render() itself calls renderBackground() first, so chrome
    //                    drawn in render() would end up UNDER the background. Hook
    //                    renderBackground instead and draw the chrome after super's.
    //   1.20.1         — no 4-arg renderBackground, and render() draws widgets only, so the
    //                    (2-arg) background call is ours to make.

    //? if >=1.21.11 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The screen's background pass already applies the menu blur this frame
        // (calling blurBeforeThisStratum again throws "Can only blur once per frame").
        extractTransparentBackground(graphics);
        drawChrome(graphics, mouseX, mouseY, partialTick);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    //?} elif >=1.20.2 {
    /*@Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        drawChrome(graphics, mouseX, mouseY, partialTick);
    }
    *///?} else {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        drawChrome(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    *///?}

    private void drawChrome(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int headerLine = HEADER_H;
        int footerLine = height - FOOTER_H;

        // Translucent header + footer bars.
        graphics.fillGradient(0, 0, width, headerLine, BAR_TOP, BAR_FADE);
        graphics.fillGradient(0, footerLine, width, height, BAR_FADE, BAR_TOP);

        // Divider lines (dark line + faint highlight underneath, vanilla separator style).
        graphics.fill(0, headerLine, width, headerLine + 1, LINE_DARK);
        graphics.fill(0, headerLine + 1, width, headerLine + 2, LINE_LIGHT);
        graphics.fill(0, footerLine - 1, width, footerLine, LINE_LIGHT);
        graphics.fill(0, footerLine, width, footerLine + 1, LINE_DARK);

        // Header text: title, short accent underline, optional subtitle + tag.
        graphics.centeredText(font, title, width / 2, subtitle != null ? 7 : 12, TITLE_COLOR);
        int underlineY = subtitle != null ? 18 : 23;
        graphics.fill(width / 2 - 20, underlineY, width / 2 + 20, underlineY + 1, accent);
        if (subtitle != null) {
            graphics.centeredText(font, subtitle, width / 2, 21, SUBTITLE_COLOR);
        }
        Component tag = headerTag();
        if (tag != null) {
            graphics.text(font, tag, width - SIDE_PAD - font.width(tag), 12, TAG_COLOR);
        }

        drawExtras(graphics, mouseX, mouseY, partialTick);
    }
}
