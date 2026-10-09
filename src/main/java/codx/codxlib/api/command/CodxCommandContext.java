package codx.codxlib.api.command;

import com.mojang.brigadier.context.CommandContext;

/**
 * Wraps a Brigadier {@link CommandContext} with the loader-neutral
 * {@link CodxCommandSource}, plus typed argument accessors. Argument lookups are
 * source-type independent, so the same command body works on every loader.
 */
public final class CodxCommandContext {

    private final CommandContext<?> context;
    private final CodxCommandSource source;

    CodxCommandContext(CommandContext<?> context, CodxCommandSource source) {
        this.context = context;
        this.source = source;
    }

    public CodxCommandSource source() {
        return source;
    }

    public <T> T get(String name, Class<T> type) {
        return context.getArgument(name, type);
    }

    public String getString(String name) {
        return context.getArgument(name, String.class);
    }

    public int getInt(String name) {
        return context.getArgument(name, Integer.class);
    }

    public boolean getBool(String name) {
        return context.getArgument(name, Boolean.class);
    }
}
