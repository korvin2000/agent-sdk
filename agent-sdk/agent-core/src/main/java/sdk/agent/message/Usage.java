package sdk.agent.message;

import java.util.Objects;

/// Token accounting for one response, or summed over a run. Tokens are `long`; `cost` is
/// `double` on purpose — display-grade money, not ledger money.
public record Usage(long input, long output, long cacheRead, long cacheWrite, long totalTokens, Cost cost) {

    public record Cost(double input, double output, double cacheRead, double cacheWrite, double total) {
        public static final Cost ZERO = new Cost(0, 0, 0, 0, 0);

        public Cost plus(Cost o) {
            return new Cost(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, total + o.total);
        }
    }

    public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0, Cost.ZERO);

    public Usage { cost = Objects.requireNonNullElse(cost, Cost.ZERO); }

    public static Usage tokens(long input, long output) {
        return new Usage(input, output, 0, 0, input + output, Cost.ZERO);
    }

    public Usage plus(Usage o) {
        return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead,
                         cacheWrite + o.cacheWrite, totalTokens + o.totalTokens, cost.plus(o.cost));
    }
}
