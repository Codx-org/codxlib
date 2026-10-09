package codx.codxlib.api;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

import java.net.URI;

/**
 * Shared helpers for building and sending chat notifications.
 *
 * <p>This is the server/shared half of CodxLib's notification toolkit: it owns the
 * repeated "clickable aqua link" component pattern (previously hand-rolled in
 * {@link UpdateChecker} and {@link CodxLibCommands}) and the common ways of pushing
 * a {@link Component} to a player, the console, or every operator. The client-side
 * toast half lives in {@code codx.codxlib.api.ui.CodxToast}.
 *
 * <p>All methods are null-tolerant: a null target or message is silently ignored.
 */
public final class CodxNotify {

    private CodxNotify() {
    }

    /** A clickable, underlined aqua component that opens {@code url} and shows it as its own label. */
    public static MutableComponent link(String url) {
        return link(url, url);
    }

    /** A clickable, underlined aqua component showing {@code label} that opens {@code url} when clicked. */
    public static MutableComponent link(String label, String url) {
        return Component.literal(label).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            //? if >=1.21.5 {
            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
            //?} else {
            /*.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));*/
            //?}
    }

    /** A grey {@code "[Mod] "} prefix followed by {@code message} (which keeps its own formatting). */
    public static MutableComponent prefixed(String chatPrefix, String message) {
        return Component.literal("§7" + chatPrefix + " §r").append(message);
    }

    /** Sends a system message to a single player's chat. */
    public static void toPlayer(ServerPlayer player, Component message) {
        if (player != null && message != null) {
            player.sendSystemMessage(message);
        }
    }

    /** Sends a message to the server console / log. */
    public static void toConsole(MinecraftServer server, Component message) {
        if (server != null && message != null) {
            server.sendSystemMessage(message);
        }
    }

    /**
     * Sends a message to every connected operator — or, in single-player, to the
     * lone player. "Operator" here means any op ({@code Permissions.COMMANDS_MODERATOR}),
     * matching the gate used by {@link UpdateChecker} and {@code /codxlib help}.
     */
    public static void toOperators(MinecraftServer server, Component message) {
        if (server == null || message == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            //? if >=1.21.11 {
            if (server.isSingleplayer()
                || player.permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
            //?} else {
            /*if (server.isSingleplayer() || player.hasPermissions(2)) {*/
            //?}
                player.sendSystemMessage(message);
            }
        }
    }
}
