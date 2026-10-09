package codx.codxlib.api.settings;

import codx.codxlib.api.ui.menu.CodxSettingsMenu;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Turns a {@link CodxSettings} spec into a working server command — one call, no per-setting work.
 *
 * <pre>{@code
 * // from RegisterCommandsEvent (Forge/NeoForge) or CommandRegistrationCallback (Fabric):
 * CodxSettingsCommand.register(dispatcher, "mymod", "config", SPEC);
 * }</pre>
 *
 * <p>That gives an operator {@code /mymod config} with:
 * <ul>
 *   <li>{@code list [category] [page]} — browse, categories first</li>
 *   <li>{@code search <word>} — by name <i>or</i> description</li>
 *   <li>{@code get <option>} — value, description, type, range, default, category</li>
 *   <li>{@code set <option> <value>} — tab-completed, type-checked, clamped to range</li>
 *   <li>{@code reset <option>} and {@code reset everything}</li>
 *   <li>{@code reload} / {@code save} — re-read the file, or force-write it</li>
 *   <li>{@code menu} — opens the same settings as a chest menu, see {@link CodxSettingsMenu}</li>
 * </ul>
 *
 * <p><b>Every write goes straight to the file</b>, so an admin editing the JSON by hand and an admin
 * using this command are editing the same thing and never disagree, and the mod's
 * {@link CodxSettings.Builder#onChange} hook fires so live values re-bake on the spot.
 *
 * <p><b>Operators only</b> on every node — see {@link #isOperator}. Register it on the <i>server</i>
 * dispatcher (not a client one) and it behaves identically in singleplayer, on a LAN world and on a
 * dedicated server, with a non-op seeing nothing in tab-completion.
 *
 * <p><b>Settings that need a restart.</b> Plenty of options are consumed once, when a world loads —
 * spawn weights, world-gen switches — and telling the admin "changed" without saying so invites a
 * bug report. Pass a {@link ChangeNote} and the command prints your note under any edit it applies
 * to; the {@link #register(CommandDispatcher, String, String, CodxSettings)} overload passes none.
 */
public final class CodxSettingsCommand {

    private static final int PAGE_SIZE = 12;

    private CodxSettingsCommand() {
    }

    /**
     * An extra line printed after an edit — typically "this one only takes effect when a world
     * loads". Called with the single value that changed, or {@code null} for a bulk edit such as
     * {@code reset everything}.
     */
    @FunctionalInterface
    public interface ChangeNote {
        /** The note to print, or null for none. */
        String noteFor(CodxSettings.ConfigValue<?> changed);
    }

    /** Registers {@code /<root> <sub> …} for operators, with no restart note. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String root, String sub, CodxSettings spec) {
        register(dispatcher, root, sub, spec, null);
    }

    /** Registers {@code /<root> <sub> …} for operators. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String root, String sub,
                                CodxSettings spec, ChangeNote note) {
        register(dispatcher, root, sub, spec, note, CodxSettingsCommand::isOperator);
    }

    /**
     * Full control: registers {@code /<root> <sub> …} behind {@code permission}.
     *
     * <p>Brigadier merges a literal that already exists, so a mod that owns {@code /<root>} for
     * other things can call this and keep them — the settings tree is grafted under it rather than
     * replacing it.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, String root, String sub,
                                CodxSettings spec, ChangeNote note, Predicate<CommandSourceStack> permission) {
        dispatcher.register(Commands.literal(root).requires(permission).then(node(sub, spec, note)));
    }

    /**
     * The {@code <sub>} subtree on its own, for a mod that wants to graft it into a command tree it
     * is already building rather than registering a fresh root.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> node(String sub, CodxSettings spec, ChangeNote note) {
        return node(sub, spec, note, player -> CodxSettingsMenu.open(player, spec, note));
    }

    /**
     * As {@link #node(String, CodxSettings, ChangeNote)}, but {@code menu} opens whatever
     * {@code menuOpener} opens instead of the plain generated menu.
     *
     * <p>This is the seam for a mod that has tuned its menu with {@link CodxSettingsMenu#builder} —
     * a title, per-category icons, a grouper that folds a 176-option category into one page per
     * subject. Without it the command would open the default menu while {@code /<root> <sub> menu}
     * is exactly where a player expects the tuned one.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> node(String sub, CodxSettings spec, ChangeNote note,
                                                                 Consumer<ServerPlayer> menuOpener) {
        return Commands.literal(sub)
                .executes(context -> listCategories(context.getSource(), spec, sub))
                .then(Commands.literal("list")
                        .executes(context -> listCategories(context.getSource(), spec, sub))
                        .then(Commands.argument("category", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(spec.categories(), builder))
                                .executes(context -> listCategory(context, spec, sub, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> listCategory(context, spec, sub,
                                                IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("search")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> search(context, spec))))
                .then(Commands.literal("get")
                        .then(optionArgument(spec).executes(context -> get(context, spec))))
                .then(Commands.literal("set")
                        .then(optionArgument(spec)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            CodxSettings.ConfigValue<?> value = lookup(context, spec);
                                            return value == null
                                                    ? builder.buildFuture()
                                                    : SharedSuggestionProvider.suggest(suggestionsFor(value), builder);
                                        })
                                        .executes(context -> set(context, spec, note)))))
                .then(Commands.literal("reset")
                        // Literal children are matched before argument ones, so this never shadows an
                        // option — unless a mod really does name one "everything", which nothing does.
                        .then(Commands.literal("everything").executes(context -> resetEverything(context, spec, note)))
                        .then(optionArgument(spec).executes(context -> reset(context, spec, note))))
                .then(Commands.literal("reload").executes(context -> reload(context, spec)))
                .then(Commands.literal("save").executes(context -> save(context, spec)))
                .then(Commands.literal("menu").executes(context -> menu(context, menuOpener)));
    }

    /**
     * Gamemaster level, i.e. what vanilla asks for {@code /gamerule}, plus anyone in singleplayer.
     *
     * <p>1.21.11 replaced the numeric ladder with named permissions ({@code hasPermission(int)} is
     * simply gone from {@code CommandSourceStack} there). {@code getServer()} is null-checked
     * deliberately: this predicate runs while the server builds the per-player command tree at join,
     * and a raw dereference there once threw an NPE that disconnected clients with "Invalid player
     * data".
     */
    public static boolean isOperator(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        return (server != null && server.isSingleplayer())
                //? if >=1.21.11 {
                || source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
                //?} else {
                /*|| source.hasPermission(2);*/
                //?}
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> optionArgument(CodxSettings spec) {
        return Commands.argument("option", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                        spec.values().stream().map(CodxSettings.ConfigValue::name), builder));
    }

    // ── reads ───────────────────────────────────────────────────────────────────

    private static int listCategories(CommandSourceStack source, CodxSettings spec, String sub) {
        source.sendSuccess(() -> Component.literal(spec.values().size() + " settings in " + spec.fileName())
                .withStyle(ChatFormatting.GOLD), false);
        for (String category : spec.categories()) {
            int size = spec.category(category).size();
            source.sendSuccess(() -> Component.literal(" " + category).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" (" + size + ")").withStyle(ChatFormatting.GRAY)), false);
        }
        source.sendSuccess(() -> Component.literal(sub + " list <category>, " + sub + " search <word>, or "
                + sub + " menu").withStyle(ChatFormatting.GRAY), false);
        return spec.categories().size();
    }

    private static int listCategory(CommandContext<CommandSourceStack> context, CodxSettings spec, String sub, int page) {
        String category = StringArgumentType.getString(context, "category");
        List<CodxSettings.ConfigValue<?>> values = spec.category(category);
        if (values.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No settings category called '" + category + "'."));
            return 0;
        }
        return sendPage(context.getSource(), values, page, sub + " list " + category);
    }

    private static int search(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        String needle = StringArgumentType.getString(context, "text").toLowerCase(Locale.ROOT);
        List<CodxSettings.ConfigValue<?>> hits = new ArrayList<>();
        for (CodxSettings.ConfigValue<?> value : spec.values()) {
            if (value.name().toLowerCase(Locale.ROOT).contains(needle)
                    || value.comment().toLowerCase(Locale.ROOT).contains(needle)) {
                hits.add(value);
            }
        }
        if (hits.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Nothing matches '" + needle + "'."));
            return 0;
        }
        return sendPage(context.getSource(), hits, 1, null);
    }

    /** One page of a value list, each row {@code name = value}, non-defaults highlighted. */
    private static int sendPage(CommandSourceStack source, List<CodxSettings.ConfigValue<?>> values, int page, String pageCommand) {
        int pages = Math.max(1, (values.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clamped = Math.min(page, pages);
        int from = (clamped - 1) * PAGE_SIZE;
        int to = Math.min(values.size(), from + PAGE_SIZE);
        source.sendSuccess(() -> Component.literal(values.size() + " setting(s) — page " + clamped + "/" + pages)
                .withStyle(ChatFormatting.GOLD), false);
        for (CodxSettings.ConfigValue<?> value : values.subList(from, to)) {
            source.sendSuccess(() -> Component.literal(" " + value.name() + " = ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(value.asString())
                            .withStyle(value.isDefault() ? ChatFormatting.GRAY : ChatFormatting.GREEN)), false);
        }
        if (pageCommand != null && clamped < pages) {
            source.sendSuccess(() -> Component.literal(pageCommand + " " + (clamped + 1) + " for more")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return to - from;
    }

    private static int get(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        CodxSettings.ConfigValue<?> value = lookup(context, spec);
        if (value == null) {
            return unknown(context, spec);
        }
        context.getSource().sendSuccess(() -> Component.literal(value.name() + " = ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(value.asString())
                        .withStyle(value.isDefault() ? ChatFormatting.GRAY : ChatFormatting.GREEN)), false);
        if (!value.comment().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(" " + value.comment()).withStyle(ChatFormatting.WHITE), false);
        }
        String range = value.rangeText();
        context.getSource().sendSuccess(() -> Component.literal(" " + value.typeName()
                + (range.isEmpty() ? "" : ", " + range)
                + ", default " + value.defaultAsString()
                + " [" + value.category() + "]").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    // ── writes ──────────────────────────────────────────────────────────────────

    private static int set(CommandContext<CommandSourceStack> context, CodxSettings spec, ChangeNote note) {
        CodxSettings.ConfigValue<?> value = lookup(context, spec);
        if (value == null) {
            return unknown(context, spec);
        }
        String text = StringArgumentType.getString(context, "value");
        String before = value.asString();
        if (!value.setFromString(text)) {
            context.getSource().sendFailure(Component.literal("'" + text + "' is not a valid " + value.typeName()
                    + " for " + value.name() + (value.rangeText().isEmpty() ? "." : " (" + value.rangeText() + ").")));
            return 0;
        }
        return applied(context, spec, note, value.name() + ": " + before + " -> " + value.asString(), value);
    }

    private static int reset(CommandContext<CommandSourceStack> context, CodxSettings spec, ChangeNote note) {
        CodxSettings.ConfigValue<?> value = lookup(context, spec);
        if (value == null) {
            return unknown(context, spec);
        }
        String before = value.asString();
        value.reset();
        return applied(context, spec, note, value.name() + ": " + before + " -> " + value.asString() + " (default)", value);
    }

    private static int resetEverything(CommandContext<CommandSourceStack> context, CodxSettings spec, ChangeNote note) {
        int changed = 0;
        for (CodxSettings.ConfigValue<?> value : spec.values()) {
            if (!value.isDefault()) {
                value.reset();
                changed++;
            }
        }
        return applied(context, spec, note, changed + " setting(s) put back to their defaults", null);
    }

    private static int reload(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        spec.load();
        context.getSource().sendSuccess(() -> Component.literal("Re-read " + spec.fileName() + " from disk.")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        if (!spec.save()) {
            context.getSource().sendFailure(Component.literal("Could not write " + spec.fileName()
                    + " — see the server log."));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Wrote " + spec.fileName() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int menu(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> menuOpener) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("Only a player can open the settings menu."));
            return 0;
        }
        menuOpener.accept(player);
        return 1;
    }

    /**
     * Persists an edit and reports it. {@code value} is the single option that changed, or null when
     * the edit was a bulk one — in which case the note is asked for anyway, with null, so a mod can
     * print its "some of these need a restart" line unconditionally there.
     */
    static int applied(CommandContext<CommandSourceStack> context, CodxSettings spec, ChangeNote note,
                       String message, CodxSettings.ConfigValue<?> value) {
        boolean written = spec.apply();
        context.getSource().sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        if (!written) {
            context.getSource().sendFailure(Component.literal("Changed for this session only — "
                    + spec.fileName() + " could not be written, see the server log."));
        }
        String extra = note == null ? null : note.noteFor(value);
        if (extra != null && !extra.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(extra).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static CodxSettings.ConfigValue<?> lookup(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        return spec.find(StringArgumentType.getString(context, "option"));
    }

    private static int unknown(CommandContext<CommandSourceStack> context, CodxSettings spec) {
        String name = StringArgumentType.getString(context, "option");
        Function<String, String> hint = text -> {
            for (CodxSettings.ConfigValue<?> value : spec.values()) {
                if (value.name().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
                    return " Did you mean " + value.name() + "?";
                }
            }
            return "";
        };
        context.getSource().sendFailure(Component.literal("No setting called '" + name + "'." + hint.apply(name)));
        return 0;
    }

    /** Tab-completion for a {@code set} value; the value type decides what is useful. */
    private static List<String> suggestionsFor(CodxSettings.ConfigValue<?> value) {
        List<String> suggestions = new ArrayList<>();
        for (String suggestion : value.suggestions()) {
            if (!suggestions.contains(suggestion)) {
                suggestions.add(suggestion);
            }
        }
        return suggestions;
    }
}
