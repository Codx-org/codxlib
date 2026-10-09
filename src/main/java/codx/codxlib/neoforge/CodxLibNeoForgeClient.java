package codx.codxlib.neoforge;

import codx.codxlib.api.command.CodxCommands;
//? if >=1.20.5 {
import codx.codxlib.api.network.CodxNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
//?}
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class CodxLibNeoForgeClient {

    private CodxLibNeoForgeClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
            CodxCommands.buildClientInto(event.getDispatcher(), source -> source::sendSystemMessage));
        //? if >=1.20.5 {
        CodxNetwork.setClientSender(payload -> {
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new ServerboundCustomPayloadPacket(payload));
            }
        });
        //?}
    }
}
