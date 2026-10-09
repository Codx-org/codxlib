package codx.codxlib.fabric.platform;

import codx.codxlib.api.Environment;
import codx.codxlib.api.LoadedMod;
import codx.codxlib.platform.IPlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getModVersion(String modId) {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public String getMinecraftVersion() {
        return FabricLoader.getInstance()
            .getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public String getLoaderName() {
        return "Fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path getGameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public List<LoadedMod> getLoadedMods() {
        return FabricLoader.getInstance().getAllMods().stream()
            .map(c -> new LoadedMod(
                c.getMetadata().getId(),
                c.getMetadata().getName(),
                c.getMetadata().getVersion().getFriendlyString()))
            .toList();
    }

    @Override
    public Environment getEnvironment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
            ? Environment.CLIENT
            : Environment.SERVER;
    }

    @Override
    public void registerBuiltinResourcePacks() {
    }
}
