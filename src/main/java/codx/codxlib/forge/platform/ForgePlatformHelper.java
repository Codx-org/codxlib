package codx.codxlib.forge.platform;

import codx.codxlib.api.Environment;
import codx.codxlib.api.LoadedMod;
import codx.codxlib.platform.IPlatformHelper;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getModVersion(String modId) {
        //? if >=26 {
        return ModList.getModContainerById(modId)
        //?} else {
        /*return ModList.get().getModContainerById(modId)
        *///?}
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public String getMinecraftVersion() {
        //? if >=26 {
        return ModList.getModContainerById("minecraft")
        //?} else {
        /*return ModList.get().getModContainerById("minecraft")
        *///?}
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    @Override
    public String getLoaderName() {
        return "Forge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        //? if >=26 {
        return ModList.getModContainerById(modId).isPresent();
        //?} else {
        /*return ModList.get().getModContainerById(modId).isPresent();*/
        //?}
    }

    @Override
    public List<LoadedMod> getLoadedMods() {
        //? if >=26 {
        return ModList.getMods().stream()
        //?} else {
        /*return ModList.get().getMods().stream()*/
        //?}
            .map(info -> new LoadedMod(
                info.getModId(),
                info.getDisplayName(),
                info.getVersion().toString()))
            .toList();
    }

    @Override
    public Environment getEnvironment() {
        return FMLEnvironment.dist.isClient() ? Environment.CLIENT : Environment.SERVER;
    }

    @Override
    public void registerBuiltinResourcePacks() {
    }
}
