package sdk.agent;

/// A cheap, consistent read of the facade's state — what a UI polls. Small, allocation-free to
/// produce, safe to take while a run is in flight. A UI that wants the streaming message
/// subscribes to `MessageUpdate` rather than polling for it.
public record AgentSnapshot(boolean running,
                            String runId,
                            int turnIndex,
                            int turnsUsed,
                            int toolCallsUsed,
                            int transcriptSize,
                            int pendingSteering,
                            int pendingFollowUps) { }
