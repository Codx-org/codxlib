package codx.codxlib.fabric.network;

import codx.codxlib.api.network.CodxNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric client-side networking: registers clientbound receivers and the client
 * send path. Runs after all main inits, so every clientbound payload is present.
 */
public final class CodxLibFabricClientNetwork {

    private CodxLibFabricClientNetwork() {
    }

    public static void init() {
        CodxNetwork.visitClientbound(new CodxNetwork.PayloadVisitor() {
            @Override
            public <T extends CustomPacketPayload> void clientbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ClientHandler<T> handler) {
                ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                    context.client().execute(() -> handler.handle(payload)));
            }

            @Override
            public <T extends CustomPacketPayload> void serverbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ServerHandler<T> handler) {
                // client side does not receive serverbound payloads
            }
        });
        CodxNetwork.setClientSender(payload -> {
            if (ClientPlayNetworking.canSend(payload.type())) {
                ClientPlayNetworking.send(payload);
            }
        });
    }
}
