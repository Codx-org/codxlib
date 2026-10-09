package codx.codxlib.api.command;

import net.minecraft.network.chat.Component;

/**
 * Loader-neutral command sender. Each loader's client-command source
 * (Fabric's {@code FabricClientCommandSource}, Forge/NeoForge's
 * {@code CommandSourceStack}) is adapted to this so command bodies stay shared.
 */
@FunctionalInterface
public interface CodxCommandSource {
    /** Sends a feedback message to whoever ran the command. */
    void reply(Component message);
}
