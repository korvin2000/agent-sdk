package sdk.agent.message;

import java.time.Instant;
import java.util.Objects;

import sdk.agent.json.Json;

/// Ready-made custom message so a host does not have to define a class for simple state. Also
/// what an unknown `kind` decodes to on resume, so nothing is ever dropped.
public record CustomMessage(String kind, Json payload, Instant timestamp) implements AgentMessage {

    public CustomMessage {
        Objects.requireNonNull(kind, "kind");
        payload = payload == null ? Json.Null.NULL : payload;
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
