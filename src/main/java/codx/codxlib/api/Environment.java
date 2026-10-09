package codx.codxlib.api;

/**
 * Physical side the game is running on, normalised across loaders.
 */
public enum Environment {
    /** A client (integrated server or multiplayer client). */
    CLIENT,
    /** A dedicated server. */
    SERVER
}
