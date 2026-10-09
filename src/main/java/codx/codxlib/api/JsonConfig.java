package codx.codxlib.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Gson-backed JSON config stored under the game's config directory. Consumers
 * supply a data class and a defaults factory; this handles loading, atomic
 * saving, missing-file creation, and corruption recovery.
 *
 * <pre>{@code
 * public static final class Settings { public boolean enabled = true; public int x = 10; }
 *
 * private static final JsonConfig<Settings> CONFIG =
 *         JsonConfig.of("cleanhud.json", Settings.class, Settings::new);
 *
 * CONFIG.get().enabled = false;
 * CONFIG.save();
 * }</pre>
 *
 * Instances are safe to share; {@link #load()}, {@link #save()} and {@link #set}
 * are synchronized. The object returned by {@link #get()} is the live instance —
 * mutate its fields and call {@link #save()}.
 */
public final class JsonConfig<T> {

    private static final Gson DEFAULT_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path path;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final Gson gson;
    private volatile T value;

    private JsonConfig(Path path, Class<T> type, Supplier<T> defaults, Gson gson) {
        this.path = path;
        this.type = type;
        this.defaults = defaults;
        this.gson = gson;
    }

    /**
     * Convention helper: creates a config named {@code codxlib.<modName>.json} so all
     * CodxLib-managed configs share a recognizable prefix. Prefer this in consumer mods.
     */
    public static <T> JsonConfig<T> forMod(String modName, Class<T> type, Supplier<T> defaults) {
        return of("codxlib." + modName + ".json", type, defaults, DEFAULT_GSON);
    }

    /** As {@link #forMod(String, Class, Supplier)} with a custom Gson. */
    public static <T> JsonConfig<T> forMod(String modName, Class<T> type, Supplier<T> defaults, Gson gson) {
        return of("codxlib." + modName + ".json", type, defaults, gson);
    }

    /** Creates and immediately loads a config at {@code <configDir>/fileName}. */
    public static <T> JsonConfig<T> of(String fileName, Class<T> type, Supplier<T> defaults) {
        return of(fileName, type, defaults, DEFAULT_GSON);
    }

    /** As {@link #of(String, Class, Supplier)} with a custom Gson (type adapters, etc.). */
    public static <T> JsonConfig<T> of(String fileName, Class<T> type, Supplier<T> defaults, Gson gson) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(gson, "gson");
        Path path = CodxLib.configDir().resolve(fileName);
        JsonConfig<T> config = new JsonConfig<>(path, type, defaults, gson);
        config.load();
        return config;
    }

    /** The live config instance. Mutate its fields, then call {@link #save()}. */
    public T get() {
        return value;
    }

    /** Replaces the in-memory config (does not write to disk until {@link #save()}). */
    public synchronized void set(T newValue) {
        this.value = Objects.requireNonNull(newValue, "newValue");
    }

    public Path path() {
        return path;
    }

    /**
     * (Re)loads from disk. A missing file is created with defaults; a corrupt file
     * is backed up alongside and replaced with defaults so user edits aren't lost.
     */
    public synchronized void load() {
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                T loaded = gson.fromJson(reader, type);
                if (loaded != null) {
                    this.value = loaded;
                    return;
                }
            } catch (IOException | JsonParseException e) {
                backupCorrupt();
            }
        }
        this.value = defaults.get();
        save();
    }

    /** Atomically writes the current config to disk (temp file + move). */
    public synchronized void save() {
        if (value == null) {
            return;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[CodxLib] Failed to save config " + path + ": " + e.getMessage());
        }
    }

    private void backupCorrupt() {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[CodxLib] Corrupt config " + path.getFileName()
                    + " backed up to " + backup.getFileName() + "; using defaults.");
        } catch (IOException ignored) {
        }
    }
}
