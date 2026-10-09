package codx.codxlib.forge;

import codx.codxlib.CodxLibMod;
import codx.codxlib.api.CodxLibCommands;
import codx.codxlib.api.UpdateChecker;
//? if >=1.20.5 {
import codx.codxlib.forge.network.CodxLibForgeNetwork;
//?}
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if >=1.21.6 {
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
//?} else {
/*import net.minecraftforge.common.MinecraftForge;
*///?}

@Mod(CodxLibMod.MOD_ID)
public class CodxLibForge {

    //? if >=1.21.6 {
    public CodxLibForge() {
        CodxLibMod.commonInit();
        //? if >=1.20.5 {
        CodxLibForgeNetwork.installSenders();
        FMLCommonSetupEvent.getBus(FMLJavaModLoadingContext.get().getModBusGroup())
            .addListener(event -> CodxLibForgeNetwork.buildChannel());
        //?}
        if (FMLEnvironment.dist.isClient()) {
            CodxLibForgeClient.init();
        }
        RegisterCommandsEvent.BUS.addListener(event ->
            CodxLibCommands.register(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(event ->
            UpdateChecker.onServerStarted(event.getServer()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                UpdateChecker.onPlayerJoin(player.level().getServer(), player);
            }
        });
    }
    //?} else {
    /*public CodxLibForge() {
        CodxLibMod.commonInit();
        // Old-era Forge (<1.21.6, incl. all 1.20.x): game events go on MinecraftForge.EVENT_BUS.
        // Forge switched to the per-event .BUS + bus-group API at 1.21.6 (56.x). Networking
        // (mod-bus setup) is gated off below 1.20.5; when a 1.20.6-era network listener is needed,
        // add the getModEventBus() wiring here.
        if (FMLEnvironment.dist.isClient()) {
            CodxLibForgeClient.init();
        }
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            CodxLibCommands.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
            UpdateChecker.onServerStarted(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                UpdateChecker.onPlayerJoin(player.level().getServer(), player);
            }
        });
    }
    *///?}
}
