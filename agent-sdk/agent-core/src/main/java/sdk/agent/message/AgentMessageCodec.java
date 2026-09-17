package sdk.agent.message;

import sdk.agent.json.Json;

/// Makes a custom message survive persist/resume. Registered through an extension's
/// `Contributions`; looked up by [AgentMessage#kind]. Encode is fail-closed (an unregistered kind
/// throws naming the class); decode is not — an unknown kind survives as an opaque [CustomMessage].
public interface AgentMessageCodec {

    String kind();

    /// MUST include the timestamp.
    Json encode(AgentMessage message);

    /// MUST restore the original timestamp — stamping `Instant.now()` here rewrites history on every resume.
    AgentMessage decode(Json json);
}
