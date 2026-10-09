package codx.codxlib.fabric;

import codx.codxlib.api.command.CodxCommands;
//? if >=1.20.5 {
import codx.codxlib.fabric.network.CodxLibFabricClientNetwork;
//?}
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class CodxLibFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        //? if >=1.20.5 {
        CodxLibFabricClientNetwork.init();
        //?}
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            CodxCommands.buildClientInto(dispatcher, source -> source::sendFeedback));
    }
}
