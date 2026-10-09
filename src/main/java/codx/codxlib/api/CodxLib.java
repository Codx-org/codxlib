package codx.codxlib.api;

import codx.codxlib.platform.Services;

import java.nio.file.Path;
import java.util.List;

/**
 * Primary entry point for consumer mods. A thin, stable facade over the
 * loader-specific platform layer so consumers never touch
 * {@code codx.codxlib.platform.*} directly.
 */
public final class CodxLib {

    private CodxLib() {
    }

    /** Running version of the given mod, or {@code "unknown"} if not present. */
    public static String version(String modId) {
        return Services.PLATFORM.getModVersion(modId);
    }

    /** Running Minecraft version. */
    public static String minecraftVersion() {
        return Services.PLATFORM.getMinecraftVersion();
    }

    /** Mod loader this instance is running on, e.g. {@code "Fabric"}, {@code "Forge"}, {@code "NeoForge"}. */
    public static String loaderName() {
        return Services.PLATFORM.getLoaderName();
    }

    /** The active {@code config/} directory for this game instance. */
    public static Path configDir() {
        return Services.PLATFORM.getConfigDir();
    }

    /**
     * The game directory of this instance — the {@code .minecraft} folder on a client,
     * the server root on a dedicated server. {@code mods/} and {@code saves/} live here.
     */
    public static Path gameDir() {
        return Services.PLATFORM.getGameDir();
    }

    /** Whether a mod with the given id is loaded. */
    public static boolean isModLoaded(String modId) {
        return Services.PLATFORM.isModLoaded(modId);
    }

    /** Snapshot of every mod the loader reports as installed. */
    public static List<LoadedMod> loadedMods() {
        return Services.PLATFORM.getLoadedMods();
    }

    /** Physical side this instance is running on. */
    public static Environment environment() {
        return Services.PLATFORM.getEnvironment();
    }

    /** Convenience for {@code environment() == Environment.CLIENT}. */
    public static boolean isClient() {
        return environment() == Environment.CLIENT;
    }
}
