package codx.codxlib.api.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Loader-neutral play-phase networking. Consumers define payloads in common using
 * vanilla {@link CustomPacketPayload} + {@link StreamCodec}, then register them here
 * (in {@code commonInit}) with a handler. CodxLib's loader modules wire the
 * registrations into each loader's networking (Fabric {@code PayloadTypeRegistry} +
 * {@code *PlayNetworking}, NeoForge {@code PayloadRegistrar}, Forge channel) and the
 * send paths.
 *
 * <p>Handlers are always invoked on the main thread (server thread for serverbound,
 * client thread for clientbound) — loader modules hop threads before calling them.
 */
public final class CodxNetwork {

    /** Handles a clientbound payload on the client thread. */
    @FunctionalInterface
    public interface ClientHandler<T extends CustomPacketPayload> {
        void handle(T payload);
    }

    /** Handles a serverbound payload on the server thread, with the sending player. */
    @FunctionalInterface
    public interface ServerHandler<T extends CustomPacketPayload> {
        void handle(T payload, ServerPlayer sender);
    }

    /** Visitor used by loader modules to materialize registrations with their concrete type. */
    public interface PayloadVisitor {
        <T extends CustomPacketPayload> void clientbound(CustomPacketPayload.Type<T> type,
                                                         StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                         ClientHandler<T> handler);

        <T extends CustomPacketPayload> void serverbound(CustomPacketPayload.Type<T> type,
                                                         StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                         ServerHandler<T> handler);
    }

    private static final class Clientbound<T extends CustomPacketPayload> {
        final CustomPacketPayload.Type<T> type;
        final StreamCodec<RegistryFriendlyByteBuf, T> codec;
        final ClientHandler<T> handler;

        Clientbound(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, ClientHandler<T> handler) {
            this.type = type;
            this.codec = codec;
            this.handler = handler;
        }

        void accept(PayloadVisitor visitor) {
            visitor.clientbound(type, codec, handler);
        }
    }

    private static final class Serverbound<T extends CustomPacketPayload> {
        final CustomPacketPayload.Type<T> type;
        final StreamCodec<RegistryFriendlyByteBuf, T> codec;
        final ServerHandler<T> handler;

        Serverbound(CustomPacketPayload.Type<T> type, StreamCodec<RegistryFriendlyByteBuf, T> codec, ServerHandler<T> handler) {
            this.type = type;
            this.codec = codec;
            this.handler = handler;
        }

        void accept(PayloadVisitor visitor) {
            visitor.serverbound(type, codec, handler);
        }
    }

    private static final List<Clientbound<?>> CLIENTBOUND = new CopyOnWriteArrayList<>();
    private static final List<Serverbound<?>> SERVERBOUND = new CopyOnWriteArrayList<>();

    /** On-demand visitor (Fabric installs this in main init to register as payloads arrive). */
    private static volatile PayloadVisitor onRegister;

    private static volatile Consumer<CustomPacketPayload> clientSender = payload -> {};
    private static volatile BiConsumer<ServerPlayer, CustomPacketPayload> serverSender = (player, payload) -> {};

    private CodxNetwork() {
    }

    public static <T extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            ClientHandler<T> handler) {
        Clientbound<T> entry = new Clientbound<>(type, codec, handler);
        CLIENTBOUND.add(entry);
        PayloadVisitor visitor = onRegister;
        if (visitor != null) {
            entry.accept(visitor);
        }
    }

    public static <T extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            ServerHandler<T> handler) {
        Serverbound<T> entry = new Serverbound<>(type, codec, handler);
        SERVERBOUND.add(entry);
        PayloadVisitor visitor = onRegister;
        if (visitor != null) {
            entry.accept(visitor);
        }
    }

    /** Client -> server. */
    public static void sendToServer(CustomPacketPayload payload) {
        clientSender.accept(payload);
    }

    /** Server -> a specific client. */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        serverSender.accept(player, payload);
    }

    // --- Loader-module hooks (not for consumers) ---

    /** Installs an on-demand visitor and replays everything already registered through it. */
    public static void setOnRegister(PayloadVisitor visitor) {
        onRegister = visitor;
        for (Clientbound<?> entry : CLIENTBOUND) {
            entry.accept(visitor);
        }
        for (Serverbound<?> entry : SERVERBOUND) {
            entry.accept(visitor);
        }
    }

    /** Replays all clientbound registrations through the visitor (used for client receivers). */
    public static void visitClientbound(PayloadVisitor visitor) {
        for (Clientbound<?> entry : CLIENTBOUND) {
            entry.accept(visitor);
        }
    }

    /** Replays all serverbound registrations through the visitor. */
    public static void visitServerbound(PayloadVisitor visitor) {
        for (Serverbound<?> entry : SERVERBOUND) {
            entry.accept(visitor);
        }
    }

    /** Replays all registrations (clientbound then serverbound) through the visitor. */
    public static void visitAll(PayloadVisitor visitor) {
        for (Clientbound<?> entry : CLIENTBOUND) {
            entry.accept(visitor);
        }
        for (Serverbound<?> entry : SERVERBOUND) {
            entry.accept(visitor);
        }
    }

    public static void setClientSender(Consumer<CustomPacketPayload> sender) {
        clientSender = sender;
    }

    public static void setServerSender(BiConsumer<ServerPlayer, CustomPacketPayload> sender) {
        serverSender = sender;
    }
}
