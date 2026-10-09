package codx.codxlib.api.ui.menu;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;*/
//?}
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Server-side chest-style menu engine. Renders a grid of {@link CodxMenuButton}s
 * over a read-only container, decodes clicks (left/right + shift), dispatches them
 * to button handlers, and refreshes. It is loader-neutral: it reuses the vanilla
 * {@code GENERIC_9xN} menu types, so no per-loader {@code MenuType} registration is
 * needed and the whole feature lives in {@code common}.
 *
 * <p>Do not construct directly — use {@link #simple(Component)} or
 * {@link #paged(Component)} and open via the returned builder.
 */
public class CodxMenu extends AbstractContainerMenu {

    /** One screen of the menu: its (display) title and slot → button mapping. */
    record Page(Component title, Map<Integer, CodxMenuButton> buttons) {
    }

    private final ServerPlayer player;
    private final SimpleContainer container;
    private final int rows;
    private final List<Page> pages;
    private final Runnable onChange;
    private final Predicate<ServerPlayer> canUse;
    private int page;

    CodxMenu(int syncId, Inventory playerInventory, ServerPlayer player, int rows,
             List<Page> pages, Runnable onChange, Predicate<ServerPlayer> canUse) {
        super(typeForRows(rows), syncId);
        this.player = player;
        this.rows = rows;
        this.pages = pages;
        this.onChange = onChange;
        this.canUse = canUse;
        this.container = new SimpleContainer(rows * 9);

        // Top container slots (read-only display).
        for (int i = 0; i < rows * 9; i++) {
            int col = i % 9;
            int row = i / 9;
            this.addSlot(new Slot(container, i, 8 + col * 18, 18 + row * 18) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }

        // Player inventory + hotbar, positioned below the container.
        int invY = 32 + rows * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, invY + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, invY + 58));
        }

        render();
    }

    /** Begin a compact single-page menu. */
    public static SimpleMenuBuilder simple(Component title) {
        return new SimpleMenuBuilder(title);
    }

    /** Begin a compact single-page menu. */
    public static SimpleMenuBuilder simple(String title) {
        return new SimpleMenuBuilder(Component.literal(title));
    }

    /** Begin a paginated settings panel (auto nav row at the bottom). */
    public static PagedMenuBuilder paged(Component title) {
        return new PagedMenuBuilder(title);
    }

    /** Begin a paginated settings panel (auto nav row at the bottom). */
    public static PagedMenuBuilder paged(String title) {
        return new PagedMenuBuilder(Component.literal(title));
    }

    int currentPage() {
        return page;
    }

    void setPage(int index) {
        this.page = Math.max(0, Math.min(pages.size() - 1, index));
    }

    private void render() {
        container.clearContent();
        pages.get(page).buttons().forEach((slot, button) -> {
            if (slot >= 0 && slot < rows * 9) {
                container.setItem(slot, button.icon.get());
            }
        });
    }

    @Override
    //? if >=26.1 {
    public void clicked(int slotIndex, int button, ContainerInput actionType, Player who) {
    //?} else {
    /*public void clicked(int slotIndex, int button, ClickType actionType, Player who) {*/
    //?}
        // Defer to vanilla for the player's own inventory rows.
        if (slotIndex < 0 || slotIndex >= rows * 9 || !(who instanceof ServerPlayer serverPlayer)) {
            super.clicked(slotIndex, button, actionType, who);
            return;
        }

        CodxMenuButton btn = pages.get(page).buttons().get(slotIndex);
        if (btn == null || btn.onClick == null) {
            return;
        }

        //? if >=26.1 {
        boolean shift = actionType == ContainerInput.QUICK_MOVE;
        //?} else {
        /*boolean shift = actionType == ClickType.QUICK_MOVE;*/
        //?}
        boolean rightClick = button == 1;
        CodxMenuClick click = new CodxMenuClick(this, serverPlayer, shift, rightClick);
        btn.onClick.accept(click);

        if (click.closed) {
            serverPlayer.closeContainer();
            return;
        }
        if (click.changed && onChange != null) {
            onChange.run();
        }
        render();
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return who instanceof ServerPlayer serverPlayer && canUse.test(serverPlayer);
    }

    @Override
    public void removed(Player who) {
        super.removed(who);
        container.clearContent();
    }

    static MenuType<?> typeForRows(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }
}
