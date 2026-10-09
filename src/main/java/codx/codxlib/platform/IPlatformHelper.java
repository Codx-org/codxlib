package codx.codxlib.platform;

import codx.codxlib.api.Environment;
import codx.codxlib.api.LoadedMod;

import java.nio.file.Path;
import java.util.List;

/**
 * Loader-specific platform abstraction, resolved at runtime by {@link Services}
 * via Java {@link java.util.ServiceLoader}. Consumers should prefer the
 * {@code codx.codxlib.api.CodxLib} facade over using this directly.
 */
public interface IPlatformHelper {

    /** Running version of the given mod, or {@code "unknown"} if not present. */
    String getModVersion(String modId);

    /** Running Minecraft version. */
    String getMinecraftVersion();

    /** Mod loader this instance is running on, e.g. {@code "Fabric"}, {@code "Forge"}, {@code "NeoForge"}. */
    String getLoaderName();

    /** The active {@code config/} directory for this game instance. */
    Path getConfigDir();

    /**
     * The game directory of this instance — the {@code .minecraft} folder on a client,
     * the server root on a dedicated server. {@code mods/} and {@code saves/} live here.
     */
    Path getGameDir();

    /** Whether a mod with the given id is loaded. */
    boolean isModLoaded(String modId);

    /** Snapshot of every mod the loader reports as installed. */
    List<LoadedMod> getLoadedMods();

    /** Physical side this instance is running on. */
    Environment getEnvironment();

    /** Registers any builtin resource packs shipped in the mod jar (loader-dependent). */
    void registerBuiltinResourcePacks();
}
