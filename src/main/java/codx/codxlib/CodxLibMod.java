package codx.codxlib;

import codx.codxlib.api.CodxLib;
import codx.codxlib.api.CodxLibConfig;
import codx.codxlib.api.JsonConfig;
import codx.codxlib.api.ModInfo;
import codx.codxlib.api.UpdateChecker;

/**
 * Entry point for the shared CodxLib library. Each loader module calls
 * {@link #commonInit()} once from its mod initializer. CodxLib is a dependency
 * library: it registers no content of its own, it only exposes shared APIs
 * (platform services, config, UI, commands, networking) for consumer mods.
 */
public final class CodxLibMod {

    public static final String MOD_ID = "codxlib";

    // Hardcoded on purpose. Every codx mod ships a root "mod-metadata.properties",
    // and they share one classloader at runtime, so reading it by that generic name
    // returns an arbitrary mod's file. CodxLib's Modrinth slug equals its mod id.
    public static final String MODRINTH_SLUG = "codxlib";

    public static final String MOD_SAYS = MOD_ID + ": ";

    /** CodxLib's own config (config/codxlib.json) — also the reference JsonConfig usage. */
    public static final JsonConfig<CodxLibConfig> CONFIG =
            JsonConfig.of("codxlib.json", CodxLibConfig.class, CodxLibConfig::new);

    private CodxLibMod() {
    }

    /** Identity of CodxLib itself, resolved against the running version. */
    public static ModInfo selfInfo() {
        return new ModInfo(MOD_ID, MODRINTH_SLUG, CodxLib.version(MOD_ID), "[CodxLib]");
    }

    /**
     * Called once from each loader's mod initializer after the loader-specific
     * event wiring has been set up. Loader-agnostic startup goes here.
     */
    public static void commonInit() {
        UpdateChecker.setEnabled(CONFIG.get().updateNotifications);
        // CodxLib checks itself for updates too.
        UpdateChecker.register(selfInfo());
        System.out.println(MOD_SAYS + "common init complete (v" + CodxLib.version(MOD_ID) + ").");
    }
}
