package sdk.agent.provider;

import sdk.agent.json.Json;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.StopReason;
import sdk.agent.message.Usage;

/// The provider protocol — twelve cases, declared in core so the provider layer is a genuine seam.
/// A delta event carries the delta only (no per-event snapshot of the whole message). Deltas are
/// opaque: a text delta may split a surrogate pair and an arguments fragment is partial JSON, so
/// neither is inspected before its `*End`.
///
/// `ToolCallStart.initialArguments` and `ToolCallDelta.replace` exist for one rule: when the
/// assembled arguments fail to parse, the start snapshot is a fallback **only if the stream did
/// not stall** — otherwise a truncated `replace=true` delta would make the engine act on stale data.
public sealed interface LlmStreamEvent {

    record Start()                                                                     implements LlmStreamEvent { }
    record TextStart(int index)                                                        implements LlmStreamEvent { }
    record TextDelta(int index, String delta)                                          implements LlmStreamEvent { }
    record TextEnd(int index, String text, String signature)                           implements LlmStreamEvent { }
    record ThinkingStart(int index)                                                    implements LlmStreamEvent { }
    record ThinkingDelta(int index, String delta)                                      implements LlmStreamEvent { }
    record ThinkingEnd(int index, String text, String signature, boolean redacted)     implements LlmStreamEvent { }
    record ToolCallStart(int index, String id, String name, Json initialArguments)     implements LlmStreamEvent { }
    record ToolCallDelta(int index, String argumentsFragment, boolean replace)         implements LlmStreamEvent { }
    record ToolCallEnd(int index, ContentBlock.ToolCall call)                          implements LlmStreamEvent { }
    /// Terminal.
    record Done(StopReason reason, Usage usage, String responseId)                     implements LlmStreamEvent { }
    /// Terminal, in-band: the one shape for every provider-side failure.
    record Failed(StopReason reason, String message)                                   implements LlmStreamEvent { }

    default boolean terminal() { return this instanceof Done || this instanceof Failed; }
}
