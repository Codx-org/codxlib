package codx.codxlib.api.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Loader-neutral Brigadier command node. Build a tree with
 * {@link CodxCommands#literal}/{@link CodxCommands#argument}, {@code .executes(...)}
 * and {@code .then(...)}; CodxLib materializes it into each loader's own
 * source-typed command tree at registration time.
 */
public final class CodxCommandBuilder {

    private final String name;
    private final ArgumentType<?> type; // null => literal node
    private ToIntFunction<CodxCommandContext> action;
    private final List<CodxCommandBuilder> children = new ArrayList<>();

    private CodxCommandBuilder(String name, ArgumentType<?> type) {
        this.name = name;
        this.type = type;
    }

    static CodxCommandBuilder literal(String name) {
        return new CodxCommandBuilder(name, null);
    }

    static CodxCommandBuilder argument(String name, ArgumentType<?> type) {
        return new CodxCommandBuilder(name, type);
    }

    public CodxCommandBuilder executes(ToIntFunction<CodxCommandContext> action) {
        this.action = action;
        return this;
    }

    public CodxCommandBuilder then(CodxCommandBuilder child) {
        children.add(child);
        return this;
    }

    @SuppressWarnings("unchecked")
    <S> ArgumentBuilder<S, ?> materialize(CodxCommands.SourceAdapter<S> adapter) {
        ArgumentBuilder<S, ?> builder = (type == null)
                ? LiteralArgumentBuilder.<S>literal(name)
                : RequiredArgumentBuilder.<S, Object>argument(name, (ArgumentType<Object>) type);
        if (action != null) {
            ToIntFunction<CodxCommandContext> a = action;
            builder.executes(ctx -> a.applyAsInt(new CodxCommandContext(ctx, adapter.wrap(ctx.getSource()))));
        }
        for (CodxCommandBuilder child : children) {
            builder.then(child.materialize(adapter));
        }
        return builder;
    }
}
