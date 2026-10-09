package codx.codxlib.api.settings;

import codx.codxlib.api.CodxLib;
import codx.codxlib.api.ui.menu.CodxSettingsMenu;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Declare a mod's settings once and get four ways to change them: the JSON file, a server command
 * ({@link CodxSettingsCommand}), an in-game chest menu ({@link CodxSettingsMenu}) and any code
 * holding the returned handles.
 *
 * <p><b>The point of this class.</b> Every loader has its own config system and none of them cover
 * all three of Fabric/Forge/NeoForge, so a multi-loader mod ends up with two backends and two file
 * formats for one list of options. Worse, neither of the loader systems can change a value at
 * runtime in a way a server admin can reach — Forge's reload fires only when the loader notices the
 * file change, and Fabric has nothing at all. This is a single loader-neutral spec that owns the
 * file, so a value can be written back mid-game and the mod told to re-read it.
 *
 * <p><b>The builder deliberately mirrors {@code ForgeConfigSpec.Builder}</b>
 * ({@code push}/{@code pop}/{@code comment}/{@code translation}/{@code define}/
 * {@code defineInRange}/{@code defineList}/{@code configure}), so porting an existing Forge mod
 * onto it is a change of import and nothing else. New surface that Forge did not have —
 * {@link Builder#defineEnum} and the string overload of {@code define} — is additive.
 *
 * <pre>{@code
 * // one class holding the handles, exactly as a ForgeConfigSpec mod already has:
 * public final class MySettings {
 *     public final CodxSettings.BooleanValue fancyParticles;
 *     public final CodxSettings.IntValue spawnWeight;
 *
 *     MySettings(CodxSettings.Builder b) {
 *         b.push("general");
 *         b.comment("Draw the fancy particles.");
 *         fancyParticles = b.define("fancyParticles", true);
 *         b.comment("How often the thing spawns.");
 *         spawnWeight = b.defineInRange("spawnWeight", 10, 0, 100);
 *         b.pop();
 *     }
 * }
 *
 * var configured = CodxSettings.builder("mymod").onChange(MyMod::bake).configure(MySettings::new);
 * MySettings SETTINGS = configured.holder();
 * CodxSettings SPEC = configured.settings();
 * SPEC.load();                                         // reads config/mymod.json, writes it back
 * CodxSettingsCommand.register(dispatcher, "mymod", SPEC);   // /mymod config get|set|…
 * CodxSettingsMenu.open(player, SPEC);                 // the chest menu
 * }</pre>
 *
 * <p><b>Format.</b> Gson ships with Minecraft on every loader; NightConfig does not. Comments are
 * emitted as sibling {@code "// <name>"} keys, which keeps the file self-documenting for an admin
 * editing it by hand and is ignored on read. The file is rewritten on every load so that keys added
 * by a mod update appear with their defaults — the behaviour Forge had.
 *
 * <p><b>Legacy import.</b> Name any older files with {@link Builder#legacyFiles} and the first one
 * present is read when the new file does not exist yet, so an existing server keeps its tuning.
 * Both {@code .json} and NightConfig-style {@code .toml} are understood, on every loader, so a pack
 * that moved from Forge to Fabric carries its settings across. The old file is left on disk
 * untouched and ignored from then on.
 *
 * <p><b>Writes do not bake themselves.</b> Setting a value changes only the handle. A mod that
 * copies handles into static fields must pass {@link Builder#onChange} so that the command, the
 * menu and {@link #load()} can tell it to re-bake; {@link #apply()} is the one call that saves the
 * file and fires that hook.
 */
public final class CodxSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Every spec built in this game instance, so {@code /codxlib settings} can list them. */
    private static final List<CodxSettings> REGISTERED = new CopyOnWriteArrayList<>();

    private final String modId;
    private final String fileName;
    private final List<String> legacyFiles;
    private final Runnable onChange;
    private final Logger logger;

    /** Insertion-ordered so the written file follows the order the settings were declared in. */
    private final Map<String, List<ConfigValue<?>>> byCategory;
    /**
     * Every value by lowercased name. Flat rather than {@code category.name}: names are expected to
     * be unique across one mod's whole spec, and an admin typing {@code set restrictSpawns false}
     * should not have to know which section it lives in. The category is still recoverable from
     * {@link ConfigValue#category()}. A duplicate name is logged and the first declaration wins.
     */
    private final Map<String, ConfigValue<?>> byName;

    private CodxSettings(Builder builder) {
        this.modId = builder.modId;
        this.fileName = builder.fileName != null ? builder.fileName : builder.modId + ".json";
        this.legacyFiles = List.copyOf(builder.legacyFiles);
        this.onChange = builder.onChange;
        this.logger = LogManager.getLogger(builder.modId);
        this.byCategory = builder.byCategory;

        Map<String, ConfigValue<?>> index = new LinkedHashMap<>();
        for (List<ConfigValue<?>> values : byCategory.values()) {
            for (ConfigValue<?> value : values) {
                ConfigValue<?> clash = index.putIfAbsent(value.name.toLowerCase(Locale.ROOT), value);
                if (clash != null) {
                    logger.warn(
                            "Duplicate setting name {} in both {} and {} — only the first is reachable by name",
                            value.name, clash.category, value.category);
                }
            }
        }
        this.byName = index;
        REGISTERED.add(this);
    }

    /** Starts a spec for the given mod id; the file defaults to {@code config/<modId>.json}. */
    public static Builder builder(String modId) {
        return new Builder(modId);
    }

    /** Every spec built in this game instance, in creation order. */
    public static List<CodxSettings> registered() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTERED));
    }

    /** The mod this spec belongs to. */
    public String modId() {
        return modId;
    }

    /** The file name inside {@code config/}, e.g. {@code amc.json}. */
    public String fileName() {
        return fileName;
    }

    /**
     * The file this spec reads and writes. Resolved through {@link CodxLib#configDir()} rather than
     * a bare {@code Path.of("config")}: a server started with a non-default game directory resolves
     * that correctly and the relative path does not.
     */
    public Path configFile() {
        return CodxLib.configDir().resolve(fileName);
    }

    // ── file ────────────────────────────────────────────────────────────────────

    /**
     * Reads the file (or imports a legacy one when it is absent), writes it back out, and fires the
     * change hook.
     *
     * <p>Every failure is caught and logged: a corrupt config must not stop the mod loading, and
     * every value already holds its default, so a failed read degrades to the defaults rather than
     * to a crash.
     */
    public void load() {
        Path file = configFile();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    if (root != null && root.isJsonObject()) {
                        read(root.getAsJsonObject());
                    }
                }
            } else {
                importLegacy();
            }
        } catch (Exception e) {
            logger.warn("Could not read {} — falling back to defaults for anything unparsed", file, e);
        }
        apply();
    }

    /** Writes the file and fires the change hook. Returns false if the write failed. */
    public boolean apply() {
        boolean saved = save();
        if (onChange != null) {
            try {
                onChange.run();
            } catch (Exception e) {
                logger.warn("The change hook for {} threw", modId, e);
            }
        }
        return saved;
    }

    /** Writes the current values to the file without firing the change hook. */
    public boolean save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(write(), writer);
            }
            return true;
        } catch (IOException e) {
            logger.warn("Could not write {}", file, e);
            return false;
        }
    }

    // ── lookup ──────────────────────────────────────────────────────────────────

    /** Every value, in declaration order. */
    public Collection<ConfigValue<?>> values() {
        List<ConfigValue<?>> all = new ArrayList<>();
        byCategory.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    /** The category names, in declaration order. */
    public Collection<String> categories() {
        return Collections.unmodifiableCollection(byCategory.keySet());
    }

    /** The values in one category, in declaration order, or an empty list if there is no such category. */
    public List<ConfigValue<?>> category(String name) {
        return Collections.unmodifiableList(byCategory.getOrDefault(name, Collections.emptyList()));
    }

    /** Case-insensitive lookup by option name, or null. */
    public ConfigValue<?> find(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Resets every value to its default. The caller still has to {@link #apply()}. */
    public void resetAll() {
        values().forEach(ConfigValue::reset);
    }

    // ── json ────────────────────────────────────────────────────────────────────

    private void read(JsonObject root) {
        for (Map.Entry<String, List<ConfigValue<?>>> category : byCategory.entrySet()) {
            JsonElement section = root.get(category.getKey());
            if (section == null || !section.isJsonObject()) {
                continue;
            }
            JsonObject obj = section.getAsJsonObject();
            for (ConfigValue<?> value : category.getValue()) {
                JsonElement element = obj.get(value.name);
                if (element == null) {
                    continue;
                }
                try {
                    value.read(element);
                } catch (Exception e) {
                    logger.warn("Ignoring bad config value {}.{}", category.getKey(), value.name, e);
                }
            }
        }
    }

    private JsonObject write() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<ConfigValue<?>>> category : byCategory.entrySet()) {
            JsonObject obj = new JsonObject();
            for (ConfigValue<?> value : category.getValue()) {
                if (value.comment != null) {
                    obj.add("// " + value.name, new JsonPrimitive(value.comment));
                }
                obj.add(value.name, value.write());
            }
            root.add(category.getKey(), obj);
        }
        return root;
    }

    // ── legacy import ───────────────────────────────────────────────────────────

    private void importLegacy() {
        for (String legacy : legacyFiles) {
            Path file = CodxLib.configDir().resolve(legacy);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                int applied = legacy.endsWith(".toml") ? readLegacyToml(file) : readLegacyJson(file);
                logger.info("Imported {} setting(s) from the older {} into {}", applied, legacy, fileName);
                return;
            } catch (Exception e) {
                logger.warn("Could not import the older {} — starting from defaults", file, e);
            }
        }
    }

    private int readLegacyJson(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                return 0;
            }
            read(root.getAsJsonObject());
            // The old file is this same format, so read() has already applied everything it
            // recognised; count the keys it could have matched rather than instrumenting read().
            int matched = 0;
            for (Map.Entry<String, JsonElement> section : root.getAsJsonObject().entrySet()) {
                if (section.getValue().isJsonObject()) {
                    for (String key : section.getValue().getAsJsonObject().keySet()) {
                        if (!key.startsWith("//") && find(key) != null) {
                            matched++;
                        }
                    }
                }
            }
            return matched;
        }
    }

    /**
     * A deliberately small TOML reader, used once per install, for one file: the kind NightConfig
     * writes for a {@code ForgeConfigSpec}. It understands section headers, {@code key = value} with
     * a boolean/number/quoted-string value, and inline or line-wrapped arrays; everything else —
     * including every comment line — is skipped.
     *
     * <p>Hand-rolled rather than NightConfig because that library is on the Forge and NeoForge
     * classpaths but not Fabric's, and importing across loaders is the point. Nothing here has to be
     * robust against arbitrary TOML: an unparsed line just leaves that option at its default, which
     * is the same outcome as not importing at all.
     */
    private int readLegacyToml(Path file) throws IOException {
        int applied = 0;
        StringBuilder pending = null;
        String pendingKey = null;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (pending != null) {
                pending.append(' ').append(line);
                if (line.contains("]")) {
                    applied += applyToml(pendingKey, pending.toString());
                    pending = null;
                    pendingKey = null;
                }
                continue;
            }
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.startsWith("[") && !value.contains("]")) {
                pending = new StringBuilder(value);
                pendingKey = key;
                continue;
            }
            applied += applyToml(key, value);
        }
        return applied;
    }

    private int applyToml(String key, String value) {
        ConfigValue<?> target = find(key);
        if (target == null || value.isEmpty()) {
            return 0;
        }
        try {
            return target.readToml(value) ? 1 : 0;
        } catch (Exception e) {
            logger.warn("Ignoring unreadable legacy value {} = {}", key, value);
            return 0;
        }
    }

    static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.charAt(0) == '"' && trimmed.endsWith("\""))
                || (trimmed.charAt(0) == '\'' && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    // ── builder ─────────────────────────────────────────────────────────────────

    /** What {@link Builder#configure} hands back: the mod's own handle class, plus the spec. */
    public record Configured<T>(T holder, CodxSettings settings) {
    }

    /** Mirrors {@code ForgeConfigSpec.Builder}, plus the file/hook setters this needs. */
    public static final class Builder {

        private final String modId;
        private final Deque<String> stack = new ArrayDeque<>();
        private final Map<String, List<ConfigValue<?>>> byCategory = new LinkedHashMap<>();
        private final List<String> legacyFiles = new ArrayList<>();
        private String fileName;
        private Runnable onChange;
        private String pendingComment;

        private Builder(String modId) {
            this.modId = modId;
        }

        /** Overrides the default {@code <modId>.json}. */
        public Builder fileName(String name) {
            this.fileName = name;
            return this;
        }

        /**
         * Older config files to import from, in order, on the first run where the new file does not
         * exist. {@code .toml} and {@code .json} are both understood.
         */
        public Builder legacyFiles(String... names) {
            Collections.addAll(legacyFiles, names);
            return this;
        }

        /**
         * Run after every load and every {@link CodxSettings#apply()} — where a mod that copies
         * handles into static fields re-bakes them.
         */
        public Builder onChange(Runnable hook) {
            this.onChange = hook;
            return this;
        }

        public Builder push(String category) {
            stack.addLast(category);
            return this;
        }

        public Builder pop() {
            stack.pollLast();
            return this;
        }

        public Builder comment(String comment) {
            this.pendingComment = comment;
            return this;
        }

        /** No-op: {@code translation} named a lang key for Forge's config GUI, which nothing reads now. */
        public Builder translation(String key) {
            return this;
        }

        public BooleanValue define(String name, boolean defaultValue) {
            return add(new BooleanValue(name, takeComment(), currentCategory(), defaultValue));
        }

        public StringValue define(String name, String defaultValue) {
            return add(new StringValue(name, takeComment(), currentCategory(), defaultValue));
        }

        public IntValue defineInRange(String name, int defaultValue, int min, int max) {
            return add(new IntValue(name, takeComment(), currentCategory(), defaultValue, min, max));
        }

        public DoubleValue defineInRange(String name, double defaultValue, double min, double max) {
            return add(new DoubleValue(name, takeComment(), currentCategory(), defaultValue, min, max));
        }

        public <E extends Enum<E>> EnumValue<E> defineEnum(String name, E defaultValue) {
            return add(new EnumValue<>(name, takeComment(), currentCategory(), defaultValue));
        }

        public <T> ListValue<T> defineList(String name, List<? extends T> defaultValue, Predicate<Object> validator) {
            return add(new ListValue<>(name, takeComment(), currentCategory(), defaultValue, validator));
        }

        /**
         * Mirrors Forge's {@code configure}: hands this builder to the mod's handle class, then
         * pairs the constructed holder with the finished spec.
         */
        public <T> Configured<T> configure(Function<Builder, T> factory) {
            T holder = factory.apply(this);
            return new Configured<>(holder, new CodxSettings(this));
        }

        /** For a mod that declares its settings inline rather than in a handle class. */
        public CodxSettings build() {
            return new CodxSettings(this);
        }

        private String currentCategory() {
            return stack.isEmpty() ? "general" : String.join(".", stack);
        }

        private String takeComment() {
            String comment = pendingComment;
            pendingComment = null;
            return comment;
        }

        private <V extends ConfigValue<?>> V add(V value) {
            byCategory.computeIfAbsent(currentCategory(), k -> new ArrayList<>()).add(value);
            return value;
        }
    }

    // ── values ──────────────────────────────────────────────────────────────────

    /**
     * A single setting. {@code get()} is the only member a mod's read sites need; the rest of the
     * surface exists for the command and the menu.
     */
    public abstract static class ConfigValue<T> {

        final String name;
        final String comment;
        final String category;
        final T defaultValue;
        T value;

        ConfigValue(String name, String comment, String category, T defaultValue) {
            this.name = name;
            this.comment = comment;
            this.category = category;
            this.defaultValue = defaultValue;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        public String name() {
            return name;
        }

        public String category() {
            return category;
        }

        public String comment() {
            return comment == null ? "" : comment;
        }

        /** The value as an admin would type it, and as the menu prints it. */
        public String asString() {
            return String.valueOf(value);
        }

        public String defaultAsString() {
            return String.valueOf(defaultValue);
        }

        public boolean isDefault() {
            return value.equals(defaultValue);
        }

        public void reset() {
            value = defaultValue;
        }

        /** {@code boolean}, {@code integer}, {@code number}, {@code text}, {@code option} or {@code list}. */
        public abstract String typeName();

        /** The accepted range or option list, or an empty string when the type has none. */
        public String rangeText() {
            return "";
        }

        /** What the command's value argument should suggest. Empty when there is nothing sensible. */
        public List<String> suggestions() {
            return List.of();
        }

        /**
         * Parses one admin-typed value. Returns false when the text does not name a value of this
         * type; out-of-range numbers are clamped rather than rejected, matching what a hand-edited
         * file already does on read.
         */
        public abstract boolean setFromString(String text);

        abstract void read(JsonElement element);

        /** Applies one TOML right-hand side during a legacy import. */
        abstract boolean readToml(String text);

        abstract JsonElement write();
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {

        BooleanValue(String name, String comment, String category, boolean defaultValue) {
            super(name, comment, category, defaultValue);
        }

        /** Flips the value. The menu's toggle widget; nothing else should need it. */
        public void toggle() {
            value = !value;
        }

        @Override
        public String typeName() {
            return "boolean";
        }

        @Override
        public List<String> suggestions() {
            return List.of("true", "false");
        }

        @Override
        public boolean setFromString(String text) {
            if ("true".equalsIgnoreCase(text)) {
                value = Boolean.TRUE;
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                value = Boolean.FALSE;
                return true;
            }
            return false;
        }

        @Override
        void read(JsonElement element) {
            value = element.getAsBoolean();
        }

        @Override
        boolean readToml(String text) {
            return setFromString(unquote(text));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(value);
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {

        private final int min;
        private final int max;

        IntValue(String name, String comment, String category, int defaultValue, int min, int max) {
            super(name, comment, category, defaultValue);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        /** Adds to the value and clamps. */
        public void add(int delta) {
            value = clamp((long) value + delta);
        }

        /**
         * Sets the value and clamps. This is what the menu's adjust widget wants: it computes and
         * clamps the new value itself and hands the setter an absolute number, not a delta.
         */
        public void set(int newValue) {
            value = clamp(newValue);
        }

        private int clamp(long raw) {
            return (int) Math.max(min, Math.min(max, raw));
        }

        @Override
        public String typeName() {
            return "integer";
        }

        @Override
        public String rangeText() {
            return min + " to " + max;
        }

        @Override
        public List<String> suggestions() {
            return List.of(String.valueOf(value), defaultAsString());
        }

        @Override
        public boolean setFromString(String text) {
            try {
                value = clamp(Long.parseLong(text.trim()));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        void read(JsonElement element) {
            value = clamp(element.getAsLong());
        }

        @Override
        boolean readToml(String text) {
            return setFromString(unquote(text));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(value);
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {

        private final double min;
        private final double max;

        DoubleValue(String name, String comment, String category, double defaultValue, double min, double max) {
            super(name, comment, category, defaultValue);
            this.min = min;
            this.max = max;
        }

        public double min() {
            return min;
        }

        public double max() {
            return max;
        }

        /** Adds to the value and clamps. */
        public void add(double delta) {
            value = clamp(value + delta);
        }

        /** Sets the value and clamps. See {@link IntValue#set} for why the widget needs this. */
        public void set(double newValue) {
            value = clamp(newValue);
        }

        private double clamp(double raw) {
            return Math.max(min, Math.min(max, raw));
        }

        @Override
        public String typeName() {
            return "number";
        }

        @Override
        public String rangeText() {
            return min + " to " + max;
        }

        @Override
        public List<String> suggestions() {
            return List.of(asString(), defaultAsString());
        }

        @Override
        public boolean setFromString(String text) {
            try {
                value = clamp(Double.parseDouble(text.trim()));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        void read(JsonElement element) {
            value = clamp(element.getAsDouble());
        }

        @Override
        boolean readToml(String text) {
            return setFromString(unquote(text));
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(value);
        }
    }

    /** Free text — a registry name, a URL, a greeting. Written and read as a JSON string. */
    public static final class StringValue extends ConfigValue<String> {

        StringValue(String name, String comment, String category, String defaultValue) {
            super(name, comment, category, defaultValue == null ? "" : defaultValue);
        }

        @Override
        public String typeName() {
            return "text";
        }

        @Override
        public boolean setFromString(String text) {
            value = unquote(text);
            return true;
        }

        @Override
        void read(JsonElement element) {
            value = element.getAsString();
        }

        @Override
        boolean readToml(String text) {
            return setFromString(text);
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(value);
        }
    }

    /**
     * One of a fixed set of names. Stored as the constant's name so the file stays readable, and
     * matched case-insensitively on read — an unknown name leaves the value alone rather than
     * throwing, same as every other type here.
     */
    public static final class EnumValue<E extends Enum<E>> extends ConfigValue<E> {

        private final List<E> constants;

        EnumValue(String name, String comment, String category, E defaultValue) {
            super(name, comment, category, defaultValue);
            this.constants = List.of(defaultValue.getDeclaringClass().getEnumConstants());
        }

        /** The accepted constants, in declaration order. The menu cycles through these. */
        public List<E> constants() {
            return constants;
        }

        /** Steps to the next constant, wrapping. The menu's cycle widget. */
        public void cycle() {
            value = constants.get((constants.indexOf(value) + 1) % constants.size());
        }

        @Override
        public String typeName() {
            return "option";
        }

        @Override
        public String rangeText() {
            return String.join(", ", suggestions());
        }

        @Override
        public List<String> suggestions() {
            return constants.stream().map(Enum::name).toList();
        }

        @Override
        public String asString() {
            return value.name();
        }

        @Override
        public String defaultAsString() {
            return defaultValue.name();
        }

        @Override
        public boolean setFromString(String text) {
            String wanted = unquote(text);
            for (E constant : constants) {
                if (constant.name().equalsIgnoreCase(wanted)) {
                    value = constant;
                    return true;
                }
            }
            return false;
        }

        @Override
        void read(JsonElement element) {
            setFromString(element.getAsString());
        }

        @Override
        boolean readToml(String text) {
            return setFromString(text);
        }

        @Override
        JsonElement write() {
            return new JsonPrimitive(value.name());
        }
    }

    /**
     * A list of strings — registry names, biome ids, whatever the validator accepts. Entries that
     * fail the validator are dropped rather than rejecting the whole list, which is what Forge did
     * per-element too.
     */
    public static final class ListValue<T> extends ConfigValue<List<? extends T>> {

        private final Predicate<Object> validator;

        ListValue(String name, String comment, String category, List<? extends T> defaultValue, Predicate<Object> validator) {
            super(name, comment, category, defaultValue);
            this.validator = validator;
        }

        /** The entries as plain strings, for the menu's read-only display. */
        public List<String> entries() {
            return value.stream().map(String::valueOf).toList();
        }

        @Override
        public String typeName() {
            return "list";
        }

        @Override
        public String asString() {
            return String.join(", ", entries());
        }

        @Override
        public String defaultAsString() {
            return String.join(", ", defaultValue.stream().map(String::valueOf).toList());
        }

        @Override
        public boolean setFromString(String text) {
            List<Object> parsed = new ArrayList<>();
            for (String entry : text.split(",")) {
                String trimmed = unquote(entry);
                if (!trimmed.isEmpty() && validator.test(trimmed)) {
                    parsed.add(trimmed);
                }
            }
            return apply(parsed);
        }

        @SuppressWarnings("unchecked")
        private boolean apply(List<Object> parsed) {
            value = (List<? extends T>) parsed;
            return true;
        }

        @Override
        void read(JsonElement element) {
            if (!element.isJsonArray()) {
                return;
            }
            List<Object> parsed = new ArrayList<>();
            for (JsonElement entry : element.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    String string = entry.getAsString();
                    if (validator.test(string)) {
                        parsed.add(string);
                    }
                }
            }
            apply(parsed);
        }

        @Override
        boolean readToml(String text) {
            String body = text.trim();
            if (body.startsWith("[")) {
                body = body.substring(1);
            }
            int close = body.lastIndexOf(']');
            if (close >= 0) {
                body = body.substring(0, close);
            }
            return setFromString(body);
        }

        @Override
        JsonElement write() {
            JsonArray array = new JsonArray();
            for (Object entry : value) {
                array.add(String.valueOf(entry));
            }
            return array;
        }
    }
}
