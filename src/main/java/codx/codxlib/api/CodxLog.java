package codx.codxlib.api;

/**
 * Lightweight conditional debug logger shared across codx mods, replacing the
 * per-mod debug-logger helpers each mod tends to reinvent. Instance-based so each
 * mod keeps its own toggle (and optional prefix) — a mod flips it from its own
 * verbose/debug setting; while disabled, {@code debug(...)} calls are cheap no-ops.
 *
 * <pre>{@code
 * private static final CodxLog LOG = CodxLog.create("[MyMod] ");
 * LOG.setEnabled(config.verboseLogging);
 * LOG.debug("loaded {} entries", count);   // also supports String.format style
 * }</pre>
 */
public final class CodxLog {

    private final String prefix;
    private volatile boolean enabled;

    private CodxLog(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Creates a logger that prefixes every line with {@code prefix} (use "" for none). */
    public static CodxLog create(String prefix) {
        return new CodxLog(prefix);
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void debug(String message) {
        if (enabled) {
            System.out.println(prefix + message);
        }
    }

    public void debug(String format, Object... args) {
        if (enabled) {
            System.out.println(prefix + (args.length == 0 ? format : String.format(format, args)));
        }
    }
}
