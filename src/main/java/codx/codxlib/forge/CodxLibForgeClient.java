package codx.codxlib.forge;

import codx.codxlib.api.command.CodxCommands;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
//? if <1.21.6 {
/*import net.minecraftforge.common.MinecraftForge;
*///?}

public final class CodxLibForgeClient {

    private CodxLibForgeClient() {
    }

    public static void init() {
        //? if >=1.21.6 {
        RegisterClientCommandsEvent.BUS.addListener(event ->
            CodxCommands.buildClientInto(event.getDispatcher(), source -> source::sendSystemMessage));
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
            CodxCommands.buildClientInto(event.getDispatcher(), source -> source::sendSystemMessage));
        *///?}
    }
}
