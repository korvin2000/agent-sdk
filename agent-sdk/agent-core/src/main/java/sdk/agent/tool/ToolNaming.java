package sdk.agent.tool;

import java.util.regex.Pattern;

/// The single implementation of the naming rule: `[A-Za-z0-9_-]`, at most 64 characters, and an
/// over-long name truncated to 57 plus `_` and 6 hex of the SHA-256 of the **full** name so the
/// mapping is deterministic across restarts. Composed names are never reverse-parsed — the owner
/// keeps an authoritative `agentName → (provider, remoteName)` map.
public final class ToolNaming {

    public static final int MAX_LENGTH = 64;
    private static final int KEEP = 57;
    private static final Pattern ILLEGAL = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private ToolNaming() { }

    /// `namespace__tool`, sanitised and capped.
    public static String compose(String namespace, String tool) { return compose(namespace + "__" + tool); }

    public static String compose(String raw) {
        String s = ILLEGAL.matcher(raw).replaceAll("_");
        if (s.isEmpty()) throw new IllegalArgumentException("tool name sanitises to empty: '" + raw + "'");
        if (s.length() <= MAX_LENGTH) return s;
        return s.substring(0, KEEP) + "_" + Digests.sha256Hex(s).substring(0, 6);
    }

    public static boolean isValid(String name) { return name != null && VALID.matcher(name).matches(); }

    /// @throws IllegalArgumentException with the offending name
    public static String requireValid(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("invalid tool name '" + name + "': must match [A-Za-z0-9_-]{1,64}");
        }
        return name;
    }
}
