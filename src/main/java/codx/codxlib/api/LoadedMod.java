package codx.codxlib.api;

/**
 * A lightweight, loader-neutral snapshot of an installed mod, as reported by the
 * underlying loader. Returned by {@link CodxLib#loadedMods()} and used by the
 * debug report ({@code /codxlib help}).
 *
 * @param id      the mod id (e.g. {@code "cleanhud"})
 * @param name    the human-readable display name (falls back to {@code id})
 * @param version the installed version string
 */
public record LoadedMod(String id, String name, String version) {
}
