package codx.codxlib.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Identity of a consumer mod, supplied to CodxLib APIs that act on behalf of a
 * specific mod (e.g. {@link UpdateChecker}). Carries everything those APIs need
 * without coupling to per-mod static fields.
 *
 * <p>CurseForge takes two values rather than one because the two things we do with it
 * need different keys: the <em>link</em> a player clicks is built from the slug, while the
 * only keyless way to <em>read</em> a project's files ({@code api.cfwidget.com}) answers
 * to the numeric project id and 404s on the slug. Either may be omitted — a mod with a
 * slug and no id gets a link but no version check, and one with neither gets a CurseForge
 * search link.
 *
 * @param modId               the mod id (e.g. {@code "cleanhud"})
 * @param modrinthSlug        the Modrinth project slug used for update checks
 * @param version             the currently running version of the mod
 * @param chatPrefix          label shown in broadcast chat messages, e.g. {@code "[CleanHUD]"}
 * @param curseforgeSlug      the CurseForge project slug — the last path segment of
 *                            {@code curseforge.com/minecraft/mc-mods/<slug>} — or {@code null}.
 *                            It is deliberately <em>not</em> derived from {@code modId} or
 *                            {@code modrinthSlug}: they routinely differ ({@code alexsmobs}
 *                            vs {@code alexs-mobs-continued}), and a guessed slug is a 404
 *                            in a player's browser.
 * @param curseforgeProjectId the numeric CurseForge project id, or {@code 0} when unknown.
 *                            It's the number shown on the project page as "Project ID".
 */
public record ModInfo(String modId, String modrinthSlug, String version, String chatPrefix,
                      String curseforgeSlug, int curseforgeProjectId) {

    /**
     * CurseForge identities for codx mods that were published before these fields existed.
     *
     * <p>A consumer's {@code modInfo()} is compiled into <em>that</em> mod's jar, so a mod
     * already in the wild can't start naming its CurseForge project without a rebuild and a
     * re-release. Every one of them depends on CodxLib, though — so CodxLib fills the blank
     * on their behalf and they get correct links the moment the library updates. Anything
     * passed explicitly wins over this table.
     */
    private static final Map<String, CurseForgeId> KNOWN_CURSEFORGE = Map.of(
        "codxlib", new CurseForgeId("codxlib", 1633207),
        "oneblock", new CurseForgeId("theoneblock", 1633271),
        "onedimension", new CurseForgeId("one-dimension", 1633224),
        "cleanhud", new CurseForgeId("cleanhud", 1644758),
        "alexsmobs", new CurseForgeId("alexs-mobs-continued", 1635121));

    private record CurseForgeId(String slug, int projectId) {
    }

    public ModInfo {
        Objects.requireNonNull(modId, "modId");
        Objects.requireNonNull(version, "version");
        if (modrinthSlug == null || modrinthSlug.isBlank()) {
            modrinthSlug = modId;
        }
        if (chatPrefix == null || chatPrefix.isBlank()) {
            chatPrefix = "[" + modId + "]";
        }
        if (curseforgeSlug != null && curseforgeSlug.isBlank()) {
            curseforgeSlug = null;
        }
        CurseForgeId known = KNOWN_CURSEFORGE.get(modId.toLowerCase(Locale.ROOT));
        if (known != null) {
            if (curseforgeSlug == null) {
                curseforgeSlug = known.slug();
            }
            if (curseforgeProjectId <= 0) {
                curseforgeProjectId = known.projectId();
            }
        }
        if (curseforgeProjectId < 0) {
            curseforgeProjectId = 0;
        }
    }

    /**
     * Pre-1.4.0 constructor, kept so every existing consumer compiles unchanged.
     * Leaves CurseForge unnamed — see {@link #withCurseForge(String, int)}.
     */
    public ModInfo(String modId, String modrinthSlug, String version, String chatPrefix) {
        this(modId, modrinthSlug, version, chatPrefix, null, 0);
    }

    /**
     * Convenience factory using a default chat prefix of {@code "[modId]"}.
     */
    public static ModInfo of(String modId, String modrinthSlug, String version) {
        return new ModInfo(modId, modrinthSlug, version, "[" + modId + "]", null, 0);
    }

    /**
     * Convenience factory naming both stores, with a default chat prefix of {@code "[modId]"}.
     */
    public static ModInfo of(String modId, String modrinthSlug, String curseforgeSlug,
                             int curseforgeProjectId, String version) {
        return new ModInfo(modId, modrinthSlug, version, "[" + modId + "]",
            curseforgeSlug, curseforgeProjectId);
    }

    /** This info with the CurseForge project slug and id filled in. */
    public ModInfo withCurseForge(String slug, int projectId) {
        return new ModInfo(modId, modrinthSlug, version, chatPrefix, slug, projectId);
    }

    /** True when the CurseForge project page can be linked to by slug. */
    public boolean hasCurseForge() {
        return curseforgeSlug != null;
    }

    /** True when CurseForge can be queried for versions, i.e. the project id is known. */
    public boolean canQueryCurseForge() {
        return curseforgeProjectId > 0;
    }

    /** The mod's Modrinth project page. */
    public String modrinthUrl() {
        return "https://modrinth.com/mod/" + modrinthSlug;
    }

    /**
     * The mod's CurseForge project page — or a CurseForge search for it when no slug is
     * known, so the link always lands somewhere useful rather than on a 404.
     */
    public String curseforgeUrl() {
        if (curseforgeSlug != null) {
            return "https://www.curseforge.com/minecraft/mc-mods/" + curseforgeSlug;
        }
        return "https://www.curseforge.com/minecraft/search?class=mc-mods&search="
            + URLEncoder.encode(modId, StandardCharsets.UTF_8);
    }
}
