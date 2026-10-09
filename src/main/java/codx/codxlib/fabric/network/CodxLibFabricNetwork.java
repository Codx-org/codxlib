package codx.codxlib.fabric.network;

import codx.codxlib.api.network.CodxNetwork;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric main-side networking: registers payload types (both directions) and the
 * serverbound receivers as consumers register them, plus the server send path.
 */
public final class CodxLibFabricNetwork {

    private CodxLibFabricNetwork() {
    }

    public static void init() {
        CodxNetwork.setOnRegister(new CodxNetwork.PayloadVisitor() {
            @Override
            public <T extends CustomPacketPayload> void clientbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ClientHandler<T> handler) {
                //? if >=26.1 {
                PayloadTypeRegistry.clientboundPlay().register(type, codec);
                //?} else {
                /*PayloadTypeRegistry.playS2C().register(type, codec);*/
                //?}
            }

            @Override
            public <T extends CustomPacketPayload> void serverbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ServerHandler<T> handler) {
                //? if >=26.1 {
                PayloadTypeRegistry.serverboundPlay().register(type, codec);
                //?} else {
                /*PayloadTypeRegistry.playC2S().register(type, codec);*/
                //?}
                ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                    //? if >=1.20.6 {
                    context.server().execute(() -> handler.handle(payload, context.player())));
                    //?} else {
                    /*context.player().getServer().execute(() -> handler.handle(payload, context.player())));*/
                    //?}
            }
        });
        CodxNetwork.setServerSender(ServerPlayNetworking::send);
    }
}
