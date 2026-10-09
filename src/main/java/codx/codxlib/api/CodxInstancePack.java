package codx.codxlib.api;

import codx.codxlib.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports the running instance as a Modrinth modpack ({@code .mrpack}), so a bug report
 * can be handed to someone else as <em>"open this in the Modrinth App"</em> rather than as
 * a list of mods to reassemble by hand.
 *
 * <p>The pack reproduces three things:
 * <ul>
 *   <li><b>The mod set.</b> Every jar in {@code <gameDir>/mods} is hashed and looked up on
 *       Modrinth ({@code POST /v2/version_files}), which answers with the exact CDN download
 *       for that byte-identical file. Jars Modrinth doesn't know — CurseForge-only mods,
 *       private builds, a jar edited locally — cannot be referenced, so they are listed in
 *       {@code overrides/codxlib-debug/README.txt} and in the returned {@link Result} instead
 *       of being silently dropped. Nothing is bundled into the zip: a debug report must stay
 *       small, and re-hosting someone else's jar is not ours to do.</li>
 *   <li><b>Minecraft and the loader</b>, as the pack's {@code dependencies} — resolved from
 *       the loader itself, not from a build constant.</li>
 *   <li><b>The world's seed and settings.</b> The live world's {@code level.dat} is copied to
 *       {@code overrides/saves/<level>/level.dat}. A save folder holding only that file is
 *       valid: Minecraft regenerates the terrain from the seed it carries, so the importer
 *       gets the same world without the region files (which would be hundreds of megabytes).
 *       It also carries the world type, generator settings, gamerules, data-pack list and
 *       enabled feature flags — all of which change what a bug looks like.</li>
 * </ul>
 *
 * <p>CodxLib-managed configs ({@code config/codxlib*.json}) ride along in
 * {@code overrides/config/}, matching the settings dump in {@link CodxDebugReport}.
 *
 * <p>Hashing every jar and querying Modrinth takes seconds, so
 * {@link #writeAsync(MinecraftServer)} does all of it off the server thread. Everything it
 * needs from the server is read up front, on the calling thread.
 */
public final class CodxInstancePack {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Modrinth's hash-lookup endpoint is a POST, so keep the batches sane. */
    private static final int HASH_BATCH = 100;

    /** Only these hosts may appear in an mrpack {@code downloads} entry. */
    private static final String MODRINTH_CDN = "https://cdn.modrinth.com/";

    private CodxInstancePack() {
    }

    /**
     * What the export achieved, for reporting back to whoever ran the command.
     *
     * @param file       the {@code .mrpack} written
     * @param resolved   how many local jars were matched to a Modrinth download
     * @param unresolved file names of the jars that could not be matched
     * @param seed       the overworld seed, or {@code null} if it couldn't be read
     * @param world      whether the world's {@code level.dat} made it into the pack
     */
    public record Result(Path file, int resolved, List<String> unresolved, Long seed, boolean world) {

        /** Total jars considered — the resolved ones plus the ones we couldn't reference. */
        public int total() {
            return resolved + unresolved.size();
        }
    }

    /**
     * Everything read from the live server, captured on the server thread so the export
     * itself can run entirely off it.
     */
    private record Snapshot(String levelName, Path levelDat, Long seed) {
    }

    /**
     * Builds the pack off-thread and completes with the {@link Result}.
     *
     * <p>The returned future completes exceptionally only if the zip itself could not be
     * written. A Modrinth outage is not a failure: the pack is still produced, with every
     * jar reported as unresolved, because the world and the loader versions are worth having
     * on their own.
     */
    public static CompletableFuture<Result> writeAsync(MinecraftServer server) {
        Snapshot snapshot = snapshot(server);
        LocalDateTime now = LocalDateTime.now();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return write(snapshot, now);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Reads the server-thread-only bits. Tolerates a null server (no world section then). */
    private static Snapshot snapshot(MinecraftServer server) {
        if (server == null) {
            return new Snapshot(null, null, null);
        }
        String levelName;
        try {
            levelName = server.getWorldData().getLevelName();
        } catch (RuntimeException e) {
            levelName = "world";
        }
        Path levelDat;
        try {
            levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        } catch (RuntimeException e) {
            levelDat = null;
        }
        Long seed;
        try {
            seed = server.overworld().getSeed();
        } catch (RuntimeException e) {
            seed = null;
        }
        return new Snapshot(levelName, levelDat, seed);
    }

    private static Result write(Snapshot snapshot, LocalDateTime now) throws IOException {
        Path dir = CodxLib.configDir().resolve("codxlib-debug");
        Files.createDirectories(dir);
        Path out = dir.resolve("codxlib-instance-" + now.format(FILE_STAMP) + ".mrpack");

        List<Path> jars = modJars();
        Map<String, Path> bySha1 = new LinkedHashMap<>();
        for (Path jar : jars) {
            String sha1 = sha1(jar);
            if (sha1 != null) {
                bySha1.put(sha1, jar);
            }
        }

        Map<String, JsonObject> versions = lookUpOnModrinth(bySha1.keySet());

        JsonArray files = new JsonArray();
        List<String> unresolved = new ArrayList<>();
        Set<String> projectIds = new HashSet<>();
        Map<String, List<JsonObject>> byProject = new LinkedHashMap<>();

        for (Map.Entry<String, Path> entry : bySha1.entrySet()) {
            JsonObject version = versions.get(entry.getKey());
            JsonObject file = version == null ? null : matchingFile(version, entry.getKey());
            if (file == null) {
                unresolved.add(entry.getValue().getFileName().toString());
                continue;
            }
            JsonObject packFile = packFile(file);
            if (packFile == null) {
                unresolved.add(entry.getValue().getFileName().toString());
                continue;
            }
            files.add(packFile);
            String projectId = asString(version, "project_id");
            if (projectId != null) {
                projectIds.add(projectId);
                byProject.computeIfAbsent(projectId, k -> new ArrayList<>()).add(packFile);
            }
        }

        applyEnvironments(projectIds, byProject);

        JsonObject index = index(files, snapshot, now);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            put(zip, "modrinth.index.json", GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
            boolean world = putWorld(zip, snapshot);
            putConfigs(zip);
            put(zip, "overrides/codxlib-debug/README.txt",
                readme(snapshot, files.size(), unresolved, world).getBytes(StandardCharsets.UTF_8));
            return new Result(out, files.size(), List.copyOf(unresolved), snapshot.seed(), world);
        }
    }

    // ------------------------------------------------------------------ mods

    /** Every enabled jar in {@code <gameDir>/mods}, sorted for a stable pack. */
    private static List<Path> modJars() {
        List<Path> jars = new ArrayList<>();
        Path mods;
        try {
            mods = CodxLib.gameDir().resolve("mods");
        } catch (RuntimeException e) {
            return jars;
        }
        if (!Files.isDirectory(mods)) {
            return jars;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path jar : stream) {
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                }
            }
        } catch (IOException e) {
            return jars;
        }
        jars.sort((a, b) -> a.getFileName().toString()
            .compareToIgnoreCase(b.getFileName().toString()));
        return jars;
    }

    private static String sha1(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(40);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Asks Modrinth which version each hash belongs to. Returns an empty map — never throws —
     * when the API is unreachable, so the rest of the pack is still produced.
     */
    private static Map<String, JsonObject> lookUpOnModrinth(Set<String> hashes) {
        Map<String, JsonObject> found = new HashMap<>();
        List<String> batch = new ArrayList<>(HASH_BATCH);
        for (String hash : hashes) {
            batch.add(hash);
            if (batch.size() == HASH_BATCH) {
                found.putAll(lookUpBatch(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            found.putAll(lookUpBatch(batch));
        }
        return found;
    }

    private static Map<String, JsonObject> lookUpBatch(List<String> hashes) {
        JsonArray array = new JsonArray();
        hashes.forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("hashes", array);
        body.addProperty("algorithm", "sha1");

        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/version_files"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent())
            .timeout(REQUEST_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        Map<String, JsonObject> result = new HashMap<>();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return result;
            }
            JsonElement json = JsonParser.parseString(response.body());
            if (!json.isJsonObject()) {
                return result;
            }
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsJsonObject());
                }
            }
        } catch (IOException | RuntimeException e) {
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result;
        }
        return result;
    }

    /**
     * The file within a version whose sha1 is the one we asked about.
     *
     * <p>A version can carry several files (a sources jar, a second loader's build), and the
     * one the player actually has is the one whose hash matches — not necessarily the
     * {@code primary} one.
     */
    private static JsonObject matchingFile(JsonObject version, String sha1) {
        if (!version.has("files") || !version.get("files").isJsonArray()) {
            return null;
        }
        for (JsonElement element : version.get("files").getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject file = element.getAsJsonObject();
            if (!file.has("hashes") || !file.get("hashes").isJsonObject()) {
                continue;
            }
            String candidate = asString(file.getAsJsonObject("hashes"), "sha1");
            if (candidate != null && candidate.equalsIgnoreCase(sha1)) {
                return file;
            }
        }
        return null;
    }

    /** One {@code files[]} entry, or null if Modrinth's answer is missing anything mandatory. */
    private static JsonObject packFile(JsonObject file) {
        String url = asString(file, "url");
        String filename = asString(file, "filename");
        JsonObject hashes = file.has("hashes") && file.get("hashes").isJsonObject()
            ? file.getAsJsonObject("hashes") : null;
        String sha1 = hashes == null ? null : asString(hashes, "sha1");
        String sha512 = hashes == null ? null : asString(hashes, "sha512");
        if (url == null || filename == null || sha1 == null || sha512 == null) {
            return null;
        }
        // The format only permits a handful of hosts; anything else would fail to import.
        if (!url.startsWith(MODRINTH_CDN)) {
            return null;
        }

        JsonObject packHashes = new JsonObject();
        packHashes.addProperty("sha1", sha1);
        packHashes.addProperty("sha512", sha512);

        JsonArray downloads = new JsonArray();
        downloads.add(url);

        JsonObject entry = new JsonObject();
        entry.addProperty("path", "mods/" + filename);
        entry.add("hashes", packHashes);
        entry.add("downloads", downloads);
        entry.addProperty("fileSize", file.has("size") ? file.get("size").getAsLong() : 0L);
        return entry;
    }

    /**
     * Tags each entry with the sides its project supports, so importing the pack onto a
     * server doesn't try to install client-only mods. Best-effort: without this the entries
     * simply carry no {@code env}, which every launcher reads as "install it".
     */
    private static void applyEnvironments(Set<String> projectIds, Map<String, List<JsonObject>> byProject) {
        if (projectIds.isEmpty()) {
            return;
        }
        StringBuilder ids = new StringBuilder("[");
        boolean first = true;
        for (String id : projectIds) {
            if (!first) {
                ids.append(',');
            }
            ids.append('"').append(id).append('"');
            first = false;
        }
        ids.append(']');

        String url = "https://api.modrinth.com/v2/projects?ids="
            + URLEncoder.encode(ids.toString(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("User-Agent", userAgent())
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return;
            }
            JsonElement json = JsonParser.parseString(response.body());
            if (!json.isJsonArray()) {
                return;
            }
            for (JsonElement element : json.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject project = element.getAsJsonObject();
                List<JsonObject> entries = byProject.get(asString(project, "id"));
                if (entries == null) {
                    continue;
                }
                for (JsonObject entry : entries) {
                    JsonObject env = new JsonObject();
                    env.addProperty("client", side(asString(project, "client_side")));
                    env.addProperty("server", side(asString(project, "server_side")));
                    entry.add("env", env);
                }
            }
        } catch (IOException | RuntimeException e) {
            // No env tags; the pack is still valid.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Modrinth's {@code unknown} has no mrpack equivalent; "optional" is the harmless read. */
    private static String side(String value) {
        if (value == null) {
            return "optional";
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "required" -> "required";
            case "unsupported" -> "unsupported";
            default -> "optional";
        };
    }

    // ----------------------------------------------------------------- index

    private static JsonObject index(JsonArray files, Snapshot snapshot, LocalDateTime now) {
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", CodxLib.minecraftVersion());
        String loaderKey = loaderKey();
        if (loaderKey != null) {
            dependencies.addProperty(loaderKey, loaderVersion());
        }

        String level = snapshot.levelName() == null ? "unknown world" : snapshot.levelName();

        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", now.format(FILE_STAMP));
        index.addProperty("name", "Debug instance — " + level);
        index.addProperty("summary", "Exported by /codxlib help on " + CodxLib.loaderName() + " "
            + CodxLib.minecraftVersion()
            + (snapshot.seed() == null ? "" : " (world seed " + snapshot.seed() + ")"));
        index.add("files", files);
        index.add("dependencies", dependencies);
        return index;
    }

    /** The {@code dependencies} key this loader uses, or null if we don't recognise it. */
    private static String loaderKey() {
        return switch (normalizedLoader()) {
            case "fabric" -> "fabric-loader";
            case "quilt" -> "quilt-loader";
            case "forge" -> "forge";
            case "neoforge" -> "neoforge";
            default -> null;
        };
    }

    /** The loader's own version, read from the loader rather than from a build constant. */
    private static String loaderVersion() {
        return switch (normalizedLoader()) {
            case "fabric" -> CodxLib.version("fabricloader");
            case "quilt" -> CodxLib.version("quilt_loader");
            case "forge" -> CodxLib.version("forge");
            case "neoforge" -> CodxLib.version("neoforge");
            default -> "unknown";
        };
    }

    private static String normalizedLoader() {
        String loader = Services.PLATFORM.getLoaderName();
        return loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
    }

    private static String userAgent() {
        return "codxlib/" + CodxLib.version("codxlib") + " (instance-pack)";
    }

    // ------------------------------------------------------------- overrides

    /**
     * Copies the world's {@code level.dat} into the pack. That single file is what carries
     * the seed, so the imported instance generates the same world — without the region files
     * that would make the pack unshippable.
     */
    private static boolean putWorld(ZipOutputStream zip, Snapshot snapshot) throws IOException {
        Path levelDat = snapshot.levelDat();
        if (levelDat == null || !Files.isRegularFile(levelDat)) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(levelDat);
        } catch (IOException e) {
            return false;
        }
        put(zip, "overrides/saves/" + saveFolder(snapshot.levelName()) + "/level.dat", bytes);
        return true;
    }

    /** Same settings the debug report dumps, so the imported instance starts configured. */
    private static void putConfigs(ZipOutputStream zip) throws IOException {
        Path configDir;
        try {
            configDir = CodxLib.configDir();
        } catch (RuntimeException e) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "codxlib*.json")) {
            for (Path config : stream) {
                if (!Files.isRegularFile(config)) {
                    continue;
                }
                put(zip, "overrides/config/" + config.getFileName(), Files.readAllBytes(config));
            }
        } catch (IOException e) {
            // Configs are a nicety; a pack without them still imports.
        }
    }

    /** A world name is free text; a folder name is not. */
    private static String saveFolder(String levelName) {
        if (levelName == null || levelName.isBlank()) {
            return "world";
        }
        String cleaned = levelName.trim().replaceAll("[^A-Za-z0-9 ._-]", "_");
        return cleaned.isBlank() ? "world" : cleaned;
    }

    private static String readme(Snapshot snapshot, int resolved, List<String> unresolved, boolean world) {
        StringBuilder sb = new StringBuilder();
        sb.append("CodxLib instance export\n");
        sb.append("=======================\n\n");
        sb.append("Import this .mrpack with the Modrinth App (Add instance -> From file) to\n");
        sb.append("recreate the setup this report came from.\n\n");
        sb.append("Minecraft:  ").append(CodxLib.minecraftVersion()).append('\n');
        sb.append("Loader:     ").append(CodxLib.loaderName()).append(' ').append(loaderVersion()).append('\n');
        sb.append("Mods:       ").append(resolved).append(" downloaded from Modrinth\n");
        if (snapshot.levelName() != null) {
            sb.append("World:      ").append(snapshot.levelName());
            sb.append(world ? " (level.dat included)" : " (not included)").append('\n');
        }
        if (snapshot.seed() != null) {
            sb.append("Seed:       ").append(snapshot.seed()).append('\n');
        }
        sb.append('\n');
        sb.append("The world folder holds only level.dat, so Minecraft regenerates the terrain\n");
        sb.append("from the same seed, world type and generator settings. Nothing that was built\n");
        sb.append("or explored comes with it.\n");
        if (!unresolved.isEmpty()) {
            sb.append('\n');
            sb.append("NOT INCLUDED (").append(unresolved.size()).append(")\n");
            sb.append("Modrinth has no download matching these jars byte-for-byte — they are\n");
            sb.append("CurseForge-only, private, or locally modified builds. Add them by hand:\n");
            for (String name : unresolved) {
                sb.append("  - ").append(name).append('\n');
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String asString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
