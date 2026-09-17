package sdk.agent.hook;

import java.util.EnumMap;
import java.util.Map;

/// The streak rule in one place. A failing turn of one kind increments that counter and **zeroes
/// every other** (a different failure mode is evidence this one is not a streak). A healthy turn
/// **decays** every counter by one rather than zeroing it, so a model alternating malformed/valid
/// turns cannot burn the whole budget without ever tripping a cap. One documented meaning: the
/// N-th failing turn of a kind is refused.
public final class ThrashCounters {

    private final EnumMap<Thrash, Integer> caps = new EnumMap<>(Thrash.class);
    private final EnumMap<Thrash, Integer> counts = new EnumMap<>(Thrash.class);

    /// @throws IllegalArgumentException naming the first tier whose cap is missing or below one
    public ThrashCounters(Map<Thrash, Integer> caps) {
        for (Thrash t : Thrash.values()) {
            Integer cap = caps.get(t);
            if (cap == null || cap < 1) throw new IllegalArgumentException("cap for " + t + " must be >= 1, was " + cap);
            this.caps.put(t, cap);
            this.counts.put(t, 0);
        }
    }

    public static Map<Thrash, Integer> defaultCaps() {
        return Map.of(Thrash.REPEATED, 3, Thrash.SAME_TOOL, 5, Thrash.DOMINANT, 8,
                      Thrash.EMPTY, 2, Thrash.MALFORMED, 2, Thrash.TRUNCATED, 2);
    }

    public static ThrashCounters defaults() { return new ThrashCounters(defaultCaps()); }

    /// A failing turn of `kind`: increment mine, zero every other. `true` when the cap is reached.
    public boolean tripped(Thrash kind) {
        int n = counts.merge(kind, 1, Integer::sum);
        for (Thrash other : Thrash.values()) if (other != kind) counts.put(other, 0);
        return n >= caps.get(kind);
    }

    /// A healthy turn: decay every counter by one.
    public void healthy() { counts.replaceAll((_, v) -> Math.max(0, v - 1)); }

    public int cap(Thrash kind)   { return caps.get(kind); }

    public int count(Thrash kind) { return counts.get(kind); }
}
