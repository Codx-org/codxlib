package codx.codxlib.api.ui.menu;

import net.minecraft.server.level.ServerPlayer;

/**
 * Context handed to a button's click handler. Carries who clicked and how
 * (left/right, with or without shift), and lets the handler request page
 * navigation, a close, or signal that backing state changed.
 *
 * <p>Built-in widgets (toggle/adjust/slider) call {@link #markChanged()} for you;
 * a raw {@code action} handler should call it itself if it mutated config so the
 * menu's {@code onChange} callback fires.
 */
public final class CodxMenuClick {

    private final CodxMenu menu;
    private final ServerPlayer player;
    private final boolean shift;
    private final boolean rightClick;

    boolean changed;
    boolean closed;

    CodxMenuClick(CodxMenu menu, ServerPlayer player, boolean shift, boolean rightClick) {
        this.menu = menu;
        this.player = player;
        this.shift = shift;
        this.rightClick = rightClick;
    }

    /** The player who clicked. */
    public ServerPlayer player() {
        return player;
    }

    /** True if shift (or any quick-move modifier) was held. */
    public boolean shift() {
        return shift;
    }

    /** True for a right-click. */
    public boolean rightClick() {
        return rightClick;
    }

    /** True for a left-click. */
    public boolean leftClick() {
        return !rightClick;
    }

    /** Zero-based index of the page currently shown. */
    public int page() {
        return menu.currentPage();
    }

    /** Mark backing state as changed so the menu's {@code onChange} callback runs. */
    public void markChanged() {
        this.changed = true;
    }

    /** Close the menu for this player. */
    public void close() {
        this.closed = true;
    }

    /** Jump to an absolute page index (clamped to the available pages). */
    public void gotoPage(int index) {
        menu.setPage(index);
    }

    /** Advance one page (clamped). */
    public void nextPage() {
        menu.setPage(menu.currentPage() + 1);
    }

    /** Go back one page (clamped). */
    public void prevPage() {
        menu.setPage(menu.currentPage() - 1);
    }
}
