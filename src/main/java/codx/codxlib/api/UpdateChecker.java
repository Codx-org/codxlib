package codx.codxlib.api;

import codx.codxlib.platform.Services;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.11 {
import net.minecraft.server.permissions.Permissions;
//?}

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared update checker, querying <em>both</em> stores the codx mods publish to.
 *
 * <p>Consumer mods call {@link #register(ModInfo)} once during init. CodxLib's
 * own loader modules hook the player-join event and call {@link #onPlayerJoin},
 * which — for every registered mod that has a newer version on Modrinth or on
 * CurseForge — sends a chat notice to the joining player and logs it to the
 * server console. The notice links to <em>both</em> project pages, so the player
 * updates from whichever store they actually use.
 *
 * <p>Modrinth is queried through its official API and filtered by loader and
 * Minecraft version. CurseForge has no keyless version API at all, so its side
 * goes through {@code api.cfwidget.com}, a third-party read-only mirror — and is
 * therefore treated as best-effort: any failure there leaves the Modrinth answer
 * standing rather than suppressing the notice. The two results are merged by
 * taking whichever version is newer, so a release that has landed on only one
 * store still notifies.
 *
 * <p>The queries are throttled per mod (30 min) and the result cached, so every
 * joining player is notified without hammering either API. Running versions are
 * resolved live from the platform, so registration timing doesn't matter.
 */
public final class UpdateChecker {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Set true to force the update message regardless of versions (testing only). */
    private static final boolean FORCE_SHOW_UPDATE = false;

    private static final long THROTTLE_SECONDS = 30 * 60;

    /** Neither store gets to hold up a join notice; both queries are best-effort. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** First dotted number in a jar file name, once the Minecraft version is out of the way. */
    private static final Pattern FILE_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    private static final List<ModInfo> REGISTERED = new CopyOnWriteArrayList<>();
    private static final Map<String, State> STATE = new ConcurrentHashMap<>();

    /** Master switch (driven by CodxLib's config); when false, no notices are shown. */
    private static volatile boolean enabled = true;

    private static final class State {
        Instant lastCheck = Instant.EPOCH;
        volatile boolean checked = false;
        volatile boolean hasUpdate = false;
        volatile String latestVersion = null;
    }

    private UpdateChecker() {
    }

    /** Enables/disables all update notices (server console + chat). Default true. */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** All mods currently registered for update checks. */
    public static List<ModInfo> registered() {
        return List.copyOf(REGISTERED);
    }

    /** Live-resolved running version of a registered mod (for reporting). */
    public static String currentVersionOf(ModInfo mod) {
        return currentVersion(mod);
    }

    /** Registers a mod to be update-checked when players join. Idempotent per mod id. */
    public static void register(ModInfo mod) {
        if (mod == null) {
            return;
        }
        for (ModInfo existing : REGISTERED) {
            if (existing.modId().equals(mod.modId())) {
                return;
            }
        }
        REGISTERED.add(mod);
    }

    /**
     * Invoked by CodxLib's loader modules on {@code ServerStarted}. Checks every
     * registered mod and logs any available update to the server console.
     */
    public static void onServerStarted(MinecraftServer server) {
        if (server == null || !enabled) {
            return;
        }
        for (ModInfo mod : REGISTERED) {
            refresh(server, mod, (hasUpdate, latest) -> {
                if (hasUpdate && latest != null) {
                    server.sendSystemMessage(updateAvailableConsoleMessage(mod, latest)); // -> server console/log
                }
            });
        }
    }

    /**
     * Invoked by CodxLib's loader modules when a player joins. Sends the update
     * notice to that player's chat, but only in single-player or when the player
     * is an operator on a multiplayer server.
     */
    public static void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null || !enabled) {
            return;
        }
        // Single-player: always notify. Multiplayer: only operators (command level 2+).
        //? if >=1.21.11 {
        if (!(server.isSingleplayer() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))) {
        //?} else {
        /*if (!(server.isSingleplayer() || player.hasPermissions(2))) {*/
        //?}
            return;
        }
        for (ModInfo mod : REGISTERED) {
            State state = STATE.computeIfAbsent(mod.modId(), k -> new State());
            boolean useCache;
            synchronized (state) {
                useCache = state.checked
                    && Instant.now().isBefore(state.lastCheck.plusSeconds(THROTTLE_SECONDS));
            }
            if (useCache) {
                if (state.hasUpdate && state.latestVersion != null) {
                    player.sendSystemMessage(updateAvailableMessage(mod, state.latestVersion));
                }
            } else {
                refresh(server, mod, (hasUpdate, latest) -> {
                    if (hasUpdate && latest != null) {
                        player.sendSystemMessage(updateAvailableMessage(mod, latest));
                    }
                });
            }
        }
    }

    /** Runs a fresh check, updates the per-mod cache, then invokes {@code sink}. */
    private static void refresh(MinecraftServer server, ModInfo mod, BiConsumer<Boolean, String> sink) {
        State state = STATE.computeIfAbsent(mod.modId(), k -> new State());
        synchronized (state) {
            state.lastCheck = Instant.now();
        }
        checkVersionAsync(server, mod, (hasUpdate, latest) -> {
            state.checked = true;
            state.hasUpdate = hasUpdate;
            state.latestVersion = latest;
            sink.accept(hasUpdate, latest);
        });
    }

    /**
     * Chat line announcing an available update, ending in a clickable link to
     * <em>each</em> store.
     *
     * <p>1.3.6 dropped the link entirely, because naming one store sent roughly half of
     * the players to a place they don't use. Offering both is the better answer to the
     * same problem: the player clicks whichever they installed the mod from.
     *
     * <p>A log line can't be clicked, so the server console gets
     * {@link #updateAvailableConsoleMessage} instead, which spells the URLs out.
     */
    public static Component updateAvailableMessage(ModInfo mod, String latest) {
        return Component.literal(headline(mod, latest) + " — ")
            .append(CodxNotify.link("Modrinth", mod.modrinthUrl()))
            .append(Component.literal("§7 or "))
            .append(CodxNotify.link("CurseForge", mod.curseforgeUrl()));
    }

    /**
     * The same notice for the server console: both URLs written out in full, because
     * nothing in a log file is clickable.
     */
    public static Component updateAvailableConsoleMessage(ModInfo mod, String latest) {
        return Component.literal(headline(mod, latest)
            + "§7 — " + mod.modrinthUrl() + " §7or " + mod.curseforgeUrl());
    }

    private static String headline(ModInfo mod, String latest) {
        return "§7" + mod.chatPrefix() + " §aUpdate available: §6" + displayVersion(latest)
            + " §7(current " + displayVersion(currentVersion(mod)) + ")";
    }

    /**
     * Strips the {@code +<build metadata>} suffix for display.
     *
     * <p>Our published version numbers carry the node they were built for
     * ({@code 1.3.5+fabric-1.20.1}, {@code 3.3.1+26.1.2-neoforge}). The link goes to the
     * project page rather than a file, and the player is already running that loader on
     * that Minecraft version, so the suffix only adds noise. Pre-release tags (a
     * {@code -beta} that isn't preceded by {@code +}) are kept — those are real version
     * information.
     */
    private static String displayVersion(String version) {
        if (version == null) {
            return "unknown";
        }
        int plus = version.indexOf('+');
        return plus >= 0 ? version.substring(0, plus) : version;
    }

    /**
     * Asynchronously queries Modrinth <em>and</em> CurseForge and invokes {@code callback}
     * on the server thread with {@code (hasUpdate, latestVersion)}; {@code latestVersion}
     * is {@code null} when no newer version exists.
     *
     * <p>Both queries run at once and the newer of the two answers wins. Either may come
     * back {@code null} — a store that is down, rate-limiting, or simply hasn't got the
     * release yet — and that is not treated as "no update": the other store still decides.
     */
    public static void checkVersionAsync(MinecraftServer server, ModInfo mod,
                                         BiConsumer<Boolean, String> callback) {
        if (server == null || mod == null) {
            if (callback != null) {
                callback.accept(false, null);
            }
            return;
        }

        String currentVersion = currentVersion(mod);
        String minecraftVersion = Services.PLATFORM.getMinecraftVersion();

        latestOnModrinth(mod, currentVersion, minecraftVersion)
                .thenCombine(latestOnCurseForge(mod, currentVersion, minecraftVersion),
                        UpdateChecker::newerOf)
                .exceptionally(ex -> null)
                .thenAccept(latest -> server.execute(() -> {
                    boolean hasUpdate = FORCE_SHOW_UPDATE
                            || (latest != null && isNewerVersion(latest, currentVersion));
                    if (callback != null) {
                        callback.accept(hasUpdate, hasUpdate ? latest : null);
                    }
                }));
    }

    /** The newer of two candidate version strings, either of which may be null. */
    private static String newerOf(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return isNewerVersion(b, a) ? b : a;
    }

    private static HttpRequest jsonRequest(String url, ModInfo mod, String currentVersion) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", mod.modId() + "/" + currentVersion)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    /** Highest version on Modrinth for this loader + Minecraft version, or null. */
    private static CompletableFuture<String> latestOnModrinth(ModInfo mod, String currentVersion,
                                                              String minecraftVersion) {
        String encodedLoaders = URLEncoder.encode(runningLoadersJson(), StandardCharsets.UTF_8);
        String encodedMc = URLEncoder.encode("[\"" + minecraftVersion + "\"]", StandardCharsets.UTF_8);

        String url = "https://api.modrinth.com/v2/project/" + mod.modrinthSlug()
                + "/version?loaders=" + encodedLoaders
                + "&game_versions=" + encodedMc;

        return HTTP.sendAsync(jsonRequest(url, mod, currentVersion), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return null;
                    }
                    JsonElement json = JsonParser.parseString(response.body());
                    if (!json.isJsonArray()) {
                        return null;
                    }
                    String latest = null;
                    for (JsonElement element : json.getAsJsonArray()) {
                        if (!element.isJsonObject()) continue;
                        JsonObject obj = element.getAsJsonObject();
                        if (!obj.has("version_number")) continue;
                        String candidate = obj.get("version_number").getAsString();
                        latest = newerOf(latest, candidate);
                    }
                    return latest;
                })
                .exceptionally(ex -> null);
    }

    /**
     * Highest version on CurseForge for this loader + Minecraft version, or null.
     *
     * <p>CurseForge's own upload API has no listing endpoint and its Core API needs a key
     * we can't ship, so this goes through {@code api.cfwidget.com} — a keyless read-only
     * mirror of a project's file list. It is a third-party caching proxy: a brand-new
     * release can be invisible there for a few minutes, and an uncached project answers
     * {@code 202} with no files at all. Both cases return null here and leave the Modrinth
     * answer standing, which is why nothing about the notice depends on this succeeding.
     *
     * <p>It is addressed by <em>numeric project id</em>. Its documented slug route
     * ({@code /minecraft/mc-mods/<slug>}) 404s even for projects the id route serves
     * happily — measured on five of ours — so a mod that knows only its slug gets the
     * link and no version check.
     *
     * <p>The mirror gives no version field for the <em>mod</em> — only the file name and
     * the game versions — so the mod version is read out of the file name, with the
     * Minecraft version removed first. That keeps it correct for both of the naming
     * schemes in use ({@code x-1.4.0-fabric+26.2.jar} and {@code x-fabric-26.2-1.4.0.jar}).
     */
    private static CompletableFuture<String> latestOnCurseForge(ModInfo mod, String currentVersion,
                                                                String minecraftVersion) {
        if (!mod.canQueryCurseForge()) {
            return CompletableFuture.completedFuture(null);
        }
        String loader = normalizedLoader();
        String url = "https://api.cfwidget.com/" + mod.curseforgeProjectId();

        return HTTP.sendAsync(jsonRequest(url, mod, currentVersion), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return null;
                    }
                    JsonElement json = JsonParser.parseString(response.body());
                    if (!json.isJsonObject()) {
                        return null;
                    }
                    JsonObject project = json.getAsJsonObject();
                    if (!project.has("files") || !project.get("files").isJsonArray()) {
                        return null; // 202 "in queue", or a shape we don't recognise
                    }
                    String latest = null;
                    for (JsonElement element : project.get("files").getAsJsonArray()) {
                        if (!element.isJsonObject()) continue;
                        JsonObject file = element.getAsJsonObject();
                        if (!matchesRunningNode(file, loader, minecraftVersion)) continue;
                        latest = newerOf(latest, versionFromFileName(
                                file.has("name") ? file.get("name").getAsString() : null,
                                minecraftVersion));
                    }
                    return latest;
                })
                .exceptionally(ex -> null);
    }

    /** True when a cfwidget file entry is tagged with our Minecraft version and loader. */
    private static boolean matchesRunningNode(JsonObject file, String loader, String minecraftVersion) {
        if (!file.has("versions") || !file.get("versions").isJsonArray()) {
            return false;
        }
        boolean mcMatch = false;
        boolean loaderMatch = false;
        for (JsonElement tag : file.get("versions").getAsJsonArray()) {
            String value = tag.getAsString();
            if (value.equals(minecraftVersion)) {
                mcMatch = true;
            } else if (value.toLowerCase(Locale.ROOT).equals(loader)) {
                loaderMatch = true;
            }
        }
        return mcMatch && loaderMatch;
    }

    /**
     * Pulls the mod version out of a jar file name. The Minecraft version is stripped
     * first so it can't be mistaken for the mod's own — every remaining dotted number is
     * a candidate and the first one wins.
     */
    private static String versionFromFileName(String fileName, String minecraftVersion) {
        if (fileName == null) {
            return null;
        }
        String stripped = fileName.replace(minecraftVersion, "");
        Matcher matcher = FILE_VERSION.matcher(stripped);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Modrinth {@code loaders} filter for the loader we're actually running on, so a
     * Fabric instance is never offered the Forge/NeoForge build of the same release.
     * Falls back to all three when the loader name isn't one we recognise.
     */
    private static String runningLoadersJson() {
        String loader = Services.PLATFORM.getLoaderName();
        String normalized = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fabric" -> "[\"fabric\"]";
            // Quilt loads Fabric mods, so Fabric builds are valid updates there too.
            case "quilt" -> "[\"quilt\",\"fabric\"]";
            case "forge" -> "[\"forge\"]";
            case "neoforge" -> "[\"neoforge\"]";
            default -> "[\"fabric\",\"forge\",\"neoforge\"]";
        };
    }

    /**
     * The running loader as a lower-case name, with Quilt reported as Fabric.
     *
     * <p>CurseForge tags a file with exactly one loader name and has no Quilt tag at all,
     * so a Quilt instance has to look for the Fabric files it is actually running.
     */
    private static String normalizedLoader() {
        String loader = Services.PLATFORM.getLoaderName();
        String normalized = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
        return "quilt".equals(normalized) ? "fabric" : normalized;
    }

    /** Live-resolved running version of the mod, falling back to the registered value. */
    private static String currentVersion(ModInfo mod) {
        String live = Services.PLATFORM.getModVersion(mod.modId());
        return (live == null || live.isBlank() || "unknown".equals(live)) ? mod.version() : live;
    }

    /** @return true if {@code newVersion} is newer than {@code currentVersion}. */
    private static boolean isNewerVersion(String newVersion, String currentVersion) {
        String parsedNew = normalizeVersion(newVersion);
        String parsedCurrent = normalizeVersion(currentVersion);
        try {
            String[] newParts = parsedNew.split("\\.");
            String[] currentParts = parsedCurrent.split("\\.");
            int maxLength = Math.max(newParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int newPart = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
                if (newPart > currentPart) return true;
                if (newPart < currentPart) return false;
            }
            return false;
        } catch (NumberFormatException e) {
            return !parsedNew.equals(parsedCurrent);
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        String normalized = version.trim();
        int plusIndex = normalized.indexOf('+');
        if (plusIndex >= 0) {
            normalized = normalized.substring(0, plusIndex);
        }
        int dashIndex = normalized.indexOf('-');
        if (dashIndex >= 0) {
            normalized = normalized.substring(0, dashIndex);
        }
        return normalized.replaceAll("[^0-9.]", "");
    }
}
