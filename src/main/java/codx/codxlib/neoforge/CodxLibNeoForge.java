package codx.codxlib.neoforge;

import codx.codxlib.CodxLibMod;
import codx.codxlib.api.CodxLibCommands;
import codx.codxlib.api.UpdateChecker;
//? if >=1.20.5 {
import codx.codxlib.neoforge.network.CodxLibNeoForgeNetwork;
//?}
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CodxLibMod.MOD_ID)
public class CodxLibNeoForge {
    public CodxLibNeoForge(IEventBus modEventBus) {
        CodxLibMod.commonInit();
        //? if >=1.20.5 {
        modEventBus.addListener(CodxLibNeoForgeNetwork::register);
        CodxLibNeoForgeNetwork.installSenders();
        //?}
        //? if >=1.21.9 {
        if (FMLEnvironment.getDist().isClient()) {
        //?} else {
        /*if (FMLEnvironment.dist.isClient()) {*/
        //?}
            CodxLibNeoForgeClient.init();
        }
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
            CodxLibCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
            UpdateChecker.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UpdateChecker.onPlayerJoin(player.level().getServer(), player);
        }
    }
}
