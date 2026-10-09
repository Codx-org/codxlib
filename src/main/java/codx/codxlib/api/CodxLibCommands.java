package codx.codxlib.api;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Registers CodxLib's own commands. Each loader module calls {@link #register}
 * from its command-registration event.
 *
 * <p>{@code /codxlib versions} runs a fresh Modrinth check for every mod
 * registered with {@link UpdateChecker} and reports the result to the caller.
 *
 * <p>{@code /codxlib help} (operators only) writes a {@link CodxDebugReport} —
 * all installed mods, the registered codx mods with their persisted settings,
 * and the connected players — to {@code config/codxlib-debug/} for bug reports,
 * and alongside it a {@link CodxInstancePack} {@code .mrpack} that rebuilds the whole
 * setup — mods, loader, and a world on the same seed — in the Modrinth App.
 *
 * <p>{@code /codxlib pack} writes only that {@code .mrpack}, for when the instance is
 * what needs sharing and the text report has already been sent.
 */
public final class CodxLibCommands {

    /** Discord support channel surfaced after {@code /codxlib help}. */
    private static final String SUPPORT_URL =
        "https://discord.com/channels/1187428541072158860/1506642708532432986";

    private CodxLibCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("codxlib")
                .then(Commands.literal("versions")
                    .executes(ctx -> runVersions(ctx.getSource())))
                .then(Commands.literal("help")
                    .requires(CodxLibCommands::canRequestHelp)
                    .executes(ctx -> runHelp(ctx.getSource())))
                .then(Commands.literal("pack")
                    .requires(CodxLibCommands::canRequestHelp)
                    .executes(ctx -> runPack(ctx.getSource())))
        );
    }

    /**
     * Who may run {@code /codxlib help}. In single-player it is always available so
     * a player can request help without enabling cheats/commands; on a dedicated
     * server it stays operator-only (the server console has full permissions and
     * always qualifies). Matches the gate used by {@link UpdateChecker} and
     * {@link CodxNotify#toOperators}.
     *
     * <p>Must be null-safe on {@code getServer()}: this predicate is evaluated while
     * the server builds the per-player command tree ({@code ClientboundCommandsPacket})
     * sent at join, and in that context {@link CommandSourceStack#getServer()} can be
     * {@code null}. A raw dereference here threw an NPE during the join handshake and
     * disconnected clients with "Invalid player data" (regression in 1.3.1).
     */
    private static boolean canRequestHelp(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        return (server != null && server.isSingleplayer())
            //? if >=1.21.11 {
            || source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR);
            //?} else {
            /*|| source.hasPermission(2);*/
            //?}
    }

    private static int runHelp(CommandSourceStack source) {
        try {
            Path file = CodxDebugReport.write(source.getServer());
            source.sendSuccess(() -> Component.literal(
                "§7[CodxLib] Debug report written to §6" + file), false);
            source.sendSuccess(() -> Component.literal(
                "§7[CodxLib] Lists all installed mods, codx mods + their settings, and online players."), false);
            source.sendSuccess(() -> Component.literal("§7[CodxLib] Need help? Open a support request: ")
                .append(CodxNotify.link(SUPPORT_URL)), false);
            startInstancePack(source);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal(
                "§c[CodxLib] Failed to write debug report: " + e.getMessage()));
            return 0;
        }
    }

    /** {@code /codxlib pack} — the instance export on its own. */
    private static int runPack(CommandSourceStack source) {
        return startInstancePack(source) ? 1 : 0;
    }

    /**
     * Kicks off the {@code .mrpack} export and reports it when it lands.
     *
     * <p>Hashing every jar and asking Modrinth about each one takes seconds, so this
     * returns immediately and {@link CodxInstancePack} does the work off-thread; the
     * result is delivered back through {@code server.execute} the same way
     * {@code /codxlib versions} delivers its update checks.
     *
     * @return false if there was no server to export
     */
    private static boolean startInstancePack(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal(
                "§c[CodxLib] Cannot export an instance pack without a running world."));
            return false;
        }
        source.sendSuccess(() -> Component.literal(
            "§7[CodxLib] Building a Modrinth instance pack — this takes a few seconds..."), false);

        CodxInstancePack.writeAsync(server).whenComplete((result, error) -> server.execute(() -> {
            if (error != null || result == null) {
                String reason = error == null ? "unknown error" : rootMessage(error);
                source.sendFailure(Component.literal(
                    "§c[CodxLib] Failed to write the instance pack: " + reason));
                return;
            }
            source.sendSuccess(() -> Component.literal(
                "§7[CodxLib] Instance pack written to §6" + result.file()), false);
            source.sendSuccess(() -> Component.literal(
                "§7[CodxLib] Open it in the Modrinth App (Add instance -> From file) to rebuild this setup: §6"
                    + result.resolved() + "§7 of §6" + result.total() + "§7 mods"
                    + (result.seed() == null ? "" : ", world seed §6" + result.seed())), false);
            if (!result.unresolved().isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                    "§e[CodxLib] §7" + result.unresolved().size()
                        + " mod(s) are not on Modrinth and must be added by hand — they are listed"
                        + " in the pack's codxlib-debug/README.txt."), false);
            }
        }));
        return true;
    }

    /** The most specific message we have, unwrapping the CompletableFuture wrapper. */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static int runVersions(CommandSourceStack source) {
        List<ModInfo> mods = UpdateChecker.registered();
        if (mods.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[CodxLib] No mods are registered for update checks."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
            "§7[CodxLib] Checking versions for §6" + mods.size() + "§7 mod(s)..."), false);

        for (ModInfo mod : mods) {
            String current = UpdateChecker.currentVersionOf(mod);
            UpdateChecker.checkVersionAsync(source.getServer(), mod, (hasUpdate, latest) -> {
                Component line = hasUpdate && latest != null
                    ? UpdateChecker.updateAvailableMessage(mod, latest)
                    : Component.literal("§7" + mod.chatPrefix() + " up to date (§a" + current + "§7)");
                source.sendSuccess(() -> line, false);
            });
        }
        return mods.size();
    }
}
