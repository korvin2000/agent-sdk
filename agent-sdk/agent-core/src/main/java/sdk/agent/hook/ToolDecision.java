package sdk.agent.hook;

import java.util.Objects;
import java.util.Optional;

import sdk.agent.json.Json;

/// What [AgentHooks#beforeToolCall] decides — the permissions seam. `Allow(rewritten)` may
/// **narrow** the arguments (a permissions module trimming a `bash` command); the rewrite is
/// trusted first-party code and is not re-validated.
public sealed interface ToolDecision {

    ToolDecision ALLOW = new Allow(null);

    /// `rewritten` null means "unchanged".
    record Allow(Json rewritten) implements ToolDecision {
        public Optional<Json> rewrite() { return Optional.ofNullable(rewritten); }
    }

    record Block(String reason) implements ToolDecision {
        public Block { reason = Objects.requireNonNullElse(reason, ""); }
    }

    static ToolDecision allow(Json rewritten) { return new Allow(rewritten); }
    static ToolDecision block(String reason)  { return new Block(reason); }
}
