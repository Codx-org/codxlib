package codx.codxlib.api.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Entry point for CodxLib's loader-neutral commands. Consumers build a tree with
 * {@link #literal}/{@link #argument} and register it with {@link #registerClient};
 * CodxLib's loader modules materialize it into each loader's client-command
 * dispatcher (handling the differing source types and feedback methods).
 */
public final class CodxCommands {

    private static final List<Supplier<CodxCommandBuilder>> CLIENT = new CopyOnWriteArrayList<>();

    private CodxCommands() {
    }

    public static CodxCommandBuilder literal(String name) {
        return CodxCommandBuilder.literal(name);
    }

    public static CodxCommandBuilder argument(String name, ArgumentType<?> type) {
        return CodxCommandBuilder.argument(name, type);
    }

    /**
     * Registers a client command tree. The supplier is invoked each time the tree
     * is materialized (per loader, on each client-command registration), so build
     * a fresh tree in it. Call from client init.
     */
    public static void registerClient(Supplier<CodxCommandBuilder> command) {
        CLIENT.add(command);
    }

    /** Called by CodxLib loader modules from each loader's client-command event. */
    public static <S> void buildClientInto(CommandDispatcher<S> dispatcher, SourceAdapter<S> adapter) {
        for (Supplier<CodxCommandBuilder> command : CLIENT) {
            ArgumentBuilder<S, ?> built = command.get().materialize(adapter);
            if (built instanceof LiteralArgumentBuilder<?>) {
                @SuppressWarnings("unchecked")
                LiteralArgumentBuilder<S> literal = (LiteralArgumentBuilder<S>) built;
                dispatcher.register(literal);
            }
        }
    }

    /** Adapts a loader's command source to {@link CodxCommandSource}. */
    @FunctionalInterface
    public interface SourceAdapter<S> {
        CodxCommandSource wrap(S source);
    }
}
