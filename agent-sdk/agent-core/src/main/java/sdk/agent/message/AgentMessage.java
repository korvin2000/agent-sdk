package sdk.agent.message;

import java.time.Instant;

/// Anything that can sit in an agent transcript. **Not sealed** — this is the extension point:
/// skills, TODO snapshots, sub-agent transcripts, compaction markers and notifications implement
/// it directly. The closed LLM-facing subset is [Message]; [MessageConverter] narrows one to the
/// other exactly once per request.
///
/// `kind()` is the persistence discriminator and must be stable across versions; an
/// [AgentMessageCodec] registered for that kind is what makes a custom message survive resume.
public interface AgentMessage {

    Instant timestamp();

    default String kind() { return "custom"; }
}
