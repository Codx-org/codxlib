package codx.codxlib.fabric;

import codx.codxlib.CodxLibMod;
import codx.codxlib.api.CodxLibCommands;
import codx.codxlib.api.UpdateChecker;
//? if >=1.20.5 {
import codx.codxlib.fabric.network.CodxLibFabricNetwork;
//?}
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class CodxLibFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CodxLibMod.commonInit();
        //? if >=1.20.5 {
        CodxLibFabricNetwork.init();
        //?}
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CodxLibCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(UpdateChecker::onServerStarted);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            UpdateChecker.onPlayerJoin(server, handler.player));
    }
}
