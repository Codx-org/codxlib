package codx.codxlib.platform;

/**
 * Resolves the loader-specific {@link IPlatformHelper}. Under the flat-src
 * Stonecutter build there is no {@code META-INF/services} file — exactly one
 * loader constant is active per node, so the matching implementation is
 * selected at preprocess time.
 */
public final class Services {
    public static final IPlatformHelper PLATFORM = load();

    private Services() {
    }

    private static IPlatformHelper load() {
        //? fabric {
        return new codx.codxlib.fabric.platform.FabricPlatformHelper();
        //?}
        //? neoforge {
        /*return new codx.codxlib.neoforge.platform.NeoForgePlatformHelper();*/
        //?}
        //? forge {
        /*return new codx.codxlib.forge.platform.ForgePlatformHelper();*/
        //?}
    }
}
