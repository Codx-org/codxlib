package codx.codxlib.forge.network;

import codx.codxlib.CodxLibMod;
import codx.codxlib.api.network.CodxNetwork;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.payload.PayloadFlow;
import net.minecraftforge.network.payload.PayloadProtocol;

/**
 * Forge networking: builds a single payload channel carrying every CodxNetwork
 * payload (each payload id is mod-namespaced, so one shared channel is fine). Built
 * during common setup, after all mod constructors have registered their payloads.
 */
public final class CodxLibForgeNetwork {

    private static PayloadFlow<RegistryFriendlyByteBuf, CustomPacketPayload> flow;
    private static Channel<CustomPacketPayload> channel;

    private CodxLibForgeNetwork() {
    }

    /** Installs the send paths (called from the mod constructor; channel is built later). */
    public static void installSenders() {
        CodxNetwork.setClientSender(payload -> {
            if (channel != null) {
                channel.send(payload, PacketDistributor.SERVER.noArg());
            }
        });
        CodxNetwork.setServerSender((player, payload) -> {
            if (channel != null) {
                channel.send(payload, PacketDistributor.PLAYER.with(player));
            }
        });
    }

    public static void buildChannel() {
        PayloadProtocol<RegistryFriendlyByteBuf, CustomPacketPayload> protocol = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(CodxLibMod.MOD_ID, "main"))
            .networkProtocolVersion(1)
            .optional()
            .payloadChannel()
            .play();

        flow = protocol.clientbound();
        CodxNetwork.visitClientbound(new CodxNetwork.PayloadVisitor() {
            @Override
            public <T extends CustomPacketPayload> void clientbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ClientHandler<T> handler) {
                flow = flow.add(type, codec, (payload, context) -> {
                    context.enqueueWork(() -> handler.handle(payload));
                    context.setPacketHandled(true);
                });
            }

            @Override
            public <T extends CustomPacketPayload> void serverbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ServerHandler<T> handler) {
            }
        });

        flow = flow.serverbound();
        CodxNetwork.visitServerbound(new CodxNetwork.PayloadVisitor() {
            @Override
            public <T extends CustomPacketPayload> void clientbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ClientHandler<T> handler) {
            }

            @Override
            public <T extends CustomPacketPayload> void serverbound(
                    CustomPacketPayload.Type<T> type,
                    StreamCodec<RegistryFriendlyByteBuf, T> codec,
                    CodxNetwork.ServerHandler<T> handler) {
                flow = flow.add(type, codec, (payload, context) -> {
                    context.enqueueWork(() -> {
                        if (context.getSender() != null) {
                            handler.handle(payload, context.getSender());
                        }
                    });
                    context.setPacketHandled(true);
                });
            }
        });

        channel = flow.build();
        flow = null;
    }
}
