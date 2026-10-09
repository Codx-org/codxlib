package codx.codxlib.api;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Builds a human-readable debugging report covering the running instance: all
 * installed mods, the codx mods registered with {@link UpdateChecker} (with their
 * persisted settings), and the currently connected players. Written to disk by
 * {@code /codxlib help} so operators can attach it to bug reports.
 */
public final class CodxDebugReport {

    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter HUMAN_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CodxDebugReport() {
    }

    /**
     * Generates the report and writes it under {@code config/codxlib-debug/}.
     *
     * @return the file written
     * @throws IOException if the report could not be written
     */
    public static Path write(MinecraftServer server) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        Path dir = CodxLib.configDir().resolve("codxlib-debug");
        Files.createDirectories(dir);
        Path file = dir.resolve("codxlib-help-" + now.format(FILE_STAMP) + ".txt");
        Files.writeString(file, generate(server, now), StandardCharsets.UTF_8);
        return file;
    }

    /** Builds the full report text. */
    public static String generate(MinecraftServer server, LocalDateTime now) {
        StringBuilder sb = new StringBuilder();
        List<LoadedMod> mods = new ArrayList<>(CodxLib.loadedMods());
        mods.sort(Comparator.comparing(LoadedMod::id, String.CASE_INSENSITIVE_ORDER));

        section(sb, "CodxLib debug report");
        sb.append("Generated:        ").append(now.format(HUMAN_STAMP)).append('\n');
        sb.append("Minecraft:        ").append(CodxLib.minecraftVersion()).append('\n');
        sb.append("Loader:           ").append(CodxLib.loaderName()).append('\n');
        sb.append("CodxLib:          ").append(CodxLib.version("codxlib")).append('\n');
        sb.append("Environment:      ").append(CodxLib.environment()).append('\n');
        if (server != null) {
            sb.append("Server type:      ")
                .append(server.isSingleplayer() ? "Integrated (singleplayer)" : "Dedicated")
                .append('\n');
            sb.append("Level name:       ").append(safeLevelName(server)).append('\n');
            sb.append("Players online:   ")
                .append(server.getPlayerList().getPlayers().size())
                .append('/').append(server.getPlayerList().getMaxPlayers()).append('\n');
        }
        sb.append("Installed mods:   ").append(mods.size()).append('\n');

        appendServerPlayers(sb, server);
        appendCodxMods(sb, mods);
        appendSettings(sb);
        appendAllMods(sb, mods);

        return sb.toString();
    }

    private static void appendServerPlayers(StringBuilder sb, MinecraftServer server) {
        section(sb, "Connected players");
        if (server == null) {
            sb.append("(no server)\n");
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            sb.append("(none)\n");
            return;
        }
        List<String> ops = Arrays.asList(server.getPlayerList().getOpNames());
        for (ServerPlayer p : players) {
            String name = p.getName().getString();
            boolean op = ops.contains(name)
                //? if >=1.21.11 {
                || p.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
                //?} else {
                /*|| p.hasPermissions(2);*/
                //?}
            sb.append("- ").append(name)
                .append("  [").append(p.getUUID()).append("]\n");
            sb.append("    operator:  ").append(op).append('\n');
            sb.append("    gamemode:  ").append(safeGameMode(p)).append('\n');
            sb.append("    dimension: ").append(p.level().dimension().identifier()).append('\n');
            sb.append("    position:  ").append(p.blockPosition().toShortString()).append('\n');
            sb.append("    health:    ")
                .append(String.format("%.1f", p.getHealth()))
                .append('/').append(String.format("%.1f", p.getMaxHealth())).append('\n');
        }
    }

    private static void appendCodxMods(StringBuilder sb, List<LoadedMod> mods) {
        section(sb, "Codx mods (registered with CodxLib)");
        List<ModInfo> codx = UpdateChecker.registered();
        if (codx.isEmpty()) {
            sb.append("(none registered)\n");
            return;
        }
        for (ModInfo mod : codx) {
            String name = mods.stream()
                .filter(m -> m.id().equals(mod.modId()))
                .map(LoadedMod::name)
                .findFirst()
                .orElse(mod.modId());
            sb.append("- ").append(name)
                .append(" (").append(mod.modId()).append(") ")
                .append(UpdateChecker.currentVersionOf(mod)).append('\n');
            sb.append("    modrinth: ").append(mod.modrinthSlug()).append('\n');
        }
    }

    private static void appendSettings(StringBuilder sb) {
        section(sb, "CodxLib-managed settings");
        Path configDir = CodxLib.configDir();
        List<Path> configs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "codxlib*.json")) {
            for (Path p : stream) {
                configs.add(p);
            }
        } catch (IOException e) {
            sb.append("(could not list config directory: ").append(e.getMessage()).append(")\n");
            return;
        }
        if (configs.isEmpty()) {
            sb.append("(no codxlib*.json config files found in ").append(configDir).append(")\n");
            return;
        }
        configs.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (Path config : configs) {
            sb.append("--- ").append(config.getFileName()).append(" ---\n");
            try {
                sb.append(Files.readString(config, StandardCharsets.UTF_8).strip()).append('\n');
            } catch (IOException e) {
                sb.append("(could not read: ").append(e.getMessage()).append(")\n");
            }
            sb.append('\n');
        }
    }

    private static void appendAllMods(StringBuilder sb, List<LoadedMod> mods) {
        section(sb, "All installed mods (" + mods.size() + ")");
        for (LoadedMod mod : mods) {
            sb.append("- ").append(mod.id())
                .append(' ').append(mod.version())
                .append("  (").append(mod.name()).append(")\n");
        }
    }

    private static String safeLevelName(MinecraftServer server) {
        try {
            return server.getWorldData().getLevelName();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String safeGameMode(ServerPlayer player) {
        try {
            //? if >=26.1 {
            return player.gameMode().getName();
            //?} else {
            /*return player.gameMode.getGameModeForPlayer().getName();*/
            //?}
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static void section(StringBuilder sb, String title) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append("=== ").append(title).append(" ===\n");
    }
}
