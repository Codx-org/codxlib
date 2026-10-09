package codx.codxlib.neoforge.network;

import codx.codxlib.api.network.CodxNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge networking: registers all CodxNetwork payloads on the payload-handler
 * event (which fires after all mod constructors, so the registry is complete) and
 * installs the send paths.
 */
public final class CodxLibNeoForgeNetwork {

    private CodxLibNeoForgeNetwork() {
    }

    /** Installs the server send path (called from the mod constructor; client send is in the client class). */
    public static void installSenders() {
        CodxNetwork.setServerSender(PacketDistributor::sendToPlayer);
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        CodxNetwork.visitAll(new CodxNetwork.PayloadVisitor() {
            @Override
            public <T extends CustomPacketPayload> void clientbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ClientHandler<T> handler) {
                registrar.playToClient(type, codec, (payload, context) ->
                    context.enqueueWork(() -> handler.handle(payload)));
            }

            @Override
            public <T extends CustomPacketPayload> void serverbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ServerHandler<T> handler) {
                registrar.playToServer(type, codec, (payload, context) ->
                    context.enqueueWork(() -> {
                        if (context.player() instanceof ServerPlayer serverPlayer) {
                            handler.handle(payload, serverPlayer);
                        }
                    }));
            }
        });
    }
}
