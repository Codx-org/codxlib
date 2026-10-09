package codx.codxlib.api;

/**
 * CodxLib's own configuration, persisted to {@code config/codxlib.json} via
 * {@link JsonConfig}. Doubles as the reference example for the config framework.
 */
public final class CodxLibConfig {

    /** When false, CodxLib shows no update notices (server console or chat) for any mod. */
    public boolean updateNotifications = true;
}
