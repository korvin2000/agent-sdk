package sdk.agent;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import sdk.agent.event.RunOutcome;
import sdk.agent.json.Json;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AgentMessageCodec;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.CustomMessage;
import sdk.agent.message.ModelRef;
import sdk.agent.message.StopReason;
import sdk.agent.message.ToolResultMessage;
import sdk.agent.message.Usage;
import sdk.agent.message.UserMessage;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

/// [RunState] ⇄ [Json] at engine-supported durable boundaries. Unknown message kinds remain opaque;
/// live stream accumulators and schema versions before 3 do not.
public final class RunStateCodec {

    public static final int SCHEMA_VERSION = 3;

    private final Map<String, AgentMessageCodec> custom;

    public RunStateCodec(Map<String, AgentMessageCodec> customCodecs) { this.custom = Map.copyOf(customCodecs); }

    public static RunStateCodec builtIn() { return new RunStateCodec(Map.of()); }

    // ---- run state -----------------------------------------------------------------------------

    public Json encode(RunState s) {
        validateDurable(s);
        var m = new LinkedHashMap<String, Json>();
        m.put("schemaVersion", Json.num(SCHEMA_VERSION));
        m.put("runId", Json.str(s.runId()));
        m.put("phase", Json.str(s.phase().name()));
        m.put("turnIndex", Json.num(s.turnIndex()));
        m.put("transcript", messages(s.transcript()));
        m.put("seedSize", Json.num(s.seedSize()));
        m.put("pendingInjection", messages(s.pendingInjection()));
        m.put("turn", s.turn() == null ? Json.nil() : encode(s.turn()));
        m.put("limits", encode(s.limits()));
        m.put("turnsUsed", Json.num(s.turnsUsed()));
        m.put("toolCallsUsed", Json.num(s.toolCallsUsed()));
        m.put("usage", encode(s.usage()));
        m.put("startedAt", Json.str(s.startedAt().toString()));
        m.put("outcome", s.outcome() == null ? Json.nil() : encode(s.outcome()));
        m.put("toolSetHash", s.toolSetHash() == null ? Json.nil() : Json.str(s.toolSetHash()));
        return Json.obj(m);
    }

    public RunState decode(Json json) {
        Json.Obj o = obj(json, "run state");
        int version = integer(o, "schemaVersion");
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported RunState schema version " + version + " (this SDK writes " + SCHEMA_VERSION + ")");
        }
        RunState state = new RunState(
                str(o, "runId"), enumValue(Phase.class, str(o, "phase"), "phase"), integer(o, "turnIndex"),
                messages(arr(o, "transcript")), integer(o, "seedSize"), messages(arr(o, "pendingInjection")),
                nullable(o, "turn", this::decodeTurn), decodeLimits(obj(member(o, "limits"), "limits")),
                integer(o, "turnsUsed"), integer(o, "toolCallsUsed"), decodeUsage(obj(member(o, "usage"), "usage")),
                instant(o, "startedAt"), nullable(o, "outcome", this::decodeOutcome), optionalString(o, "toolSetHash"));
        validateDurable(state);
        return state;
    }

    // ---- messages ------------------------------------------------------------------------------

    /// @throws IllegalStateException for a kind with no codec — fail-closed, naming the class
    public Json encodeMessage(AgentMessage message) {
        var envelope = new LinkedHashMap<String, Json>();
        envelope.put("kind", Json.str(message.kind()));
        envelope.put("at", Json.str(message.timestamp().toString()));
        Json data = switch (message) {
            case UserMessage user -> Json.obj("content", blocks(user.content()));
            case AssistantMessage assistant -> encodeAssistant(assistant);
            case ToolResultMessage result -> Json.obj("toolCallId", Json.str(result.toolCallId()), "toolName", Json.str(result.toolName()),
                    "content", blocks(result.content()), "details", result.details(), "isError", Json.bool(result.isError()));
            default -> {
                AgentMessageCodec codec = custom.get(message.kind());
                if (codec == null) {
                    if (message instanceof CustomMessage customMessage) yield customMessage.payload();
                    throw new IllegalStateException("no AgentMessageCodec registered for kind '" + message.kind() + "' (" + message.getClass().getName() + ")");
                }
                yield codec.encode(message);
            }
        };
        envelope.put("data", data);
        return Json.obj(envelope);
    }

    public AgentMessage decodeMessage(Json json) {
        Json.Obj envelope = obj(json, "message");
        String kind = str(envelope, "kind");
        Instant at = instant(envelope, "at");
        Json data = member(envelope, "data");
        return switch (kind) {
            case "user" -> new UserMessage(decodeBlocks(arr(obj(data, "user data"), "content")), at);
            case "assistant" -> decodeAssistant(obj(data, "assistant data"), at);
            case "toolResult" -> {
                Json.Obj result = obj(data, "tool result data");
                yield new ToolResultMessage(str(result, "toolCallId"), str(result, "toolName"), decodeBlocks(arr(result, "content")),
                        member(result, "details"), bool(result, "isError"), at);
            }
            default -> {
                AgentMessageCodec codec = custom.get(kind);
                yield codec != null ? codec.decode(data) : new CustomMessage(kind, data, at);
            }
        };
    }

    private Json.Arr messages(List<AgentMessage> messages) { return Json.arr(messages.stream().map(this::encodeMessage).toList()); }

    private List<AgentMessage> messages(Json.Arr messages) { return messages.values().stream().map(this::decodeMessage).toList(); }

    private Json encodeAssistant(AssistantMessage assistant) {
        var m = new LinkedHashMap<String, Json>();
        m.put("content", blocks(assistant.content()));
        m.put("model", encode(assistant.model()));
        putIfPresent(m, "responseId", assistant.responseId());
        m.put("usage", encode(assistant.usage()));
        m.put("stopReason", Json.str(assistant.stopReason().name()));
        putIfPresent(m, "errorMessage", assistant.errorMessage());
        m.put("providerData", assistant.providerData());
        return Json.obj(m);
    }

    private AssistantMessage decodeAssistant(Json.Obj assistant, Instant at) {
        return new AssistantMessage(decodeBlocks(arr(assistant, "content")), decodeModel(obj(member(assistant, "model"), "model")),
                optionalString(assistant, "responseId"), decodeUsage(obj(member(assistant, "usage"), "usage")),
                enumValue(StopReason.class, str(assistant, "stopReason"), "assistant.stopReason"), optionalString(assistant, "errorMessage"),
                member(assistant, "providerData"), at);
    }

    // ---- content blocks ------------------------------------------------------------------------

    public Json encode(ContentBlock block) {
        var m = new LinkedHashMap<String, Json>();
        switch (block) {
            case ContentBlock.Text text -> { m.put("type", Json.str("text")); m.put("text", Json.str(text.text())); putIfPresent(m, "signature", text.signature()); }
            case ContentBlock.Thinking thinking -> { m.put("type", Json.str("thinking")); m.put("thinking", Json.str(thinking.thinking())); putIfPresent(m, "signature", thinking.signature()); m.put("redacted", Json.bool(thinking.redacted())); }
            case ContentBlock.Image image -> { m.put("type", Json.str("image")); m.put("data", Json.str(image.data())); m.put("mimeType", Json.str(image.mimeType())); }
            case ContentBlock.Audio audio -> { m.put("type", Json.str("audio")); m.put("data", Json.str(audio.data())); m.put("mimeType", Json.str(audio.mimeType())); }
            case ContentBlock.Resource resource -> {
                m.put("type", Json.str("resource")); m.put("uri", Json.str(resource.uri().toString())); putIfPresent(m, "mimeType", resource.mimeType());
                resource.text().ifPresent(text -> m.put("text", Json.str(text))); resource.blob().ifPresent(blob -> m.put("blob", Json.str(blob)));
            }
            case ContentBlock.ToolCall call -> { m.put("type", Json.str("tool_call")); m.put("id", Json.str(call.id())); m.put("name", Json.str(call.name())); m.put("arguments", call.arguments()); putIfPresent(m, "thoughtSignature", call.thoughtSignature()); }
        }
        return Json.obj(m);
    }

    public ContentBlock decodeBlock(Json json) {
        Json.Obj block = obj(json, "content block");
        return switch (str(block, "type")) {
            case "text" -> new ContentBlock.Text(str(block, "text"), optionalString(block, "signature"));
            case "thinking" -> new ContentBlock.Thinking(str(block, "thinking"), optionalString(block, "signature"), bool(block, "redacted"));
            case "image" -> new ContentBlock.Image(str(block, "data"), str(block, "mimeType"));
            case "audio" -> new ContentBlock.Audio(str(block, "data"), str(block, "mimeType"));
            case "resource" -> new ContentBlock.Resource(URI.create(str(block, "uri")), optionalString(block, "mimeType"),
                    Optional.ofNullable(optionalString(block, "text")), Optional.ofNullable(optionalString(block, "blob")));
            case "tool_call" -> new ContentBlock.ToolCall(str(block, "id"), str(block, "name"), member(block, "arguments"), optionalString(block, "thoughtSignature"));
            default -> throw new IllegalArgumentException("unknown content block type '" + str(block, "type") + "'");
        };
    }

    private Json.Arr blocks(List<ContentBlock> blocks) { return Json.arr(blocks.stream().map(this::encode).toList()); }

    private List<ContentBlock> decodeBlocks(Json.Arr blocks) { return blocks.values().stream().map(this::decodeBlock).toList(); }

    // ---- turn state ----------------------------------------------------------------------------

    public Json encode(TurnState turn) {
        if (turn.phase() == TurnPhase.STREAM_REQUESTED || turn.phase() == TurnPhase.STREAMING) {
            throw invalid("turn.phase", "cannot be checkpointed while streaming");
        }
        if (!turn.content().isEmpty() || !turn.openBlocks().isEmpty() || !turn.activeCalls().isEmpty()) {
            throw invalid("turn", "contains live stream accumulators");
        }
        validateTurnCalls(turn);
        var m = new LinkedHashMap<String, Json>();
        m.put("runId", Json.str(turn.runId()));
        m.put("index", Json.num(turn.index()));
        m.put("phase", Json.str(turn.phase().name()));
        m.put("model", encode(turn.model()));
        m.put("allowedTools", Json.arr(turn.allowedTools().stream().sorted().map(Json::str).toList()));
        m.put("assistant", turn.assistant() == null ? Json.nil() : encodeMessage(turn.assistant()));
        m.put("slots", Json.arr(turn.slots().stream().map(slot -> slot.<Json>map(this::encodeMessage).orElse(Json.Null.NULL)).toList()));
        m.put("stalled", Json.bool(turn.stalled()));
        return Json.obj(m);
    }

    private TurnState decodeTurn(Json json) {
        Json.Obj turn = obj(json, "turn state");
        Set<String> allowedTools = stringSet(arr(turn, "allowedTools"), "turn.allowedTools");
        AssistantMessage assistant = nullable(turn, "assistant", value -> {
            AgentMessage message = decodeMessage(value);
            if (message instanceof AssistantMessage decoded) return decoded;
            throw new IllegalArgumentException("turn.assistant must be an assistant message");
        });
        List<Optional<ToolResultMessage>> slots = arr(turn, "slots").values().stream().map(slot -> {
            if (slot == Json.Null.NULL) return Optional.<ToolResultMessage>empty();
            AgentMessage message = decodeMessage(slot);
            if (message instanceof ToolResultMessage decoded) return Optional.of(decoded);
            throw new IllegalArgumentException("turn.slots entries must be tool-result messages or null");
        }).toList();
        return new TurnState(str(turn, "runId"), integer(turn, "index"), enumValue(TurnPhase.class, str(turn, "phase"), "turn.phase"),
                decodeModel(obj(member(turn, "model"), "turn.model")), Map.of(), Map.of(), Map.of(), allowedTools, assistant, slots, bool(turn, "stalled"));
    }

    // ---- durable invariants --------------------------------------------------------------------

    /// The engine intentionally builds transient states; only checkpoints and resume cross this boundary.
    static void validateDurable(RunState state) {
        if (state.runId().isBlank()) throw invalid("runId", "must not be blank");
        if (state.turnIndex() < 0) throw invalid("turnIndex", "must be nonnegative");
        if (state.seedSize() < 0 || state.seedSize() > state.transcript().size()) throw invalid("seedSize", "is outside transcript");
        if (state.turnsUsed() < 0) throw invalid("turnsUsed", "must be nonnegative");
        if (state.toolCallsUsed() < 0) throw invalid("toolCallsUsed", "must be nonnegative");
        if (state.toolCallsUsed() > state.limits().maxToolCalls()) throw invalid("toolCallsUsed", "exceeds maxToolCalls");
        validateUsage(state.usage(), "usage");
        if ((state.phase() == Phase.FINISHED) != (state.outcome() != null)) throw invalid("outcome", "must be present exactly for FINISHED state");
        validateTranscript(state.transcript(), state.turn());

        TurnState turn = state.turn();
        if (turn == null) {
            if (state.phase() != Phase.NEW && state.phase() != Phase.TURN_OPENING && state.phase() != Phase.FINISHED) {
                throw invalid("turn", "is required for " + state.phase());
            }
            return;
        }
        if (!turn.runId().equals(state.runId())) throw invalid("turn.runId", "does not match runId");
        if (turn.index() < 0) throw invalid("turn.index", "must be nonnegative");
        boolean priorTurn = turn.phase() == TurnPhase.CLOSED && state.turnIndex() - turn.index() == 1;
        if (!priorTurn && turn.index() != state.turnIndex()) throw invalid("turn.index", "does not match turnIndex");
        if (priorTurn && state.phase() != Phase.TURN_OPENING && state.phase() != Phase.FINISHED) {
            throw invalid("turn.index", "previous turn is only valid before opening or after termination");
        }
        if (!turn.content().isEmpty() || !turn.openBlocks().isEmpty() || !turn.activeCalls().isEmpty()) {
            throw invalid("turn", "contains live stream accumulators");
        }
        if (turn.phase() == TurnPhase.STREAM_REQUESTED || turn.phase() == TurnPhase.STREAMING) {
            throw invalid("turn.phase", "cannot be checkpointed while streaming");
        }
        for (String tool : turn.allowedTools()) if (tool == null || tool.isBlank()) throw invalid("turn.allowedTools", "contains a blank name");

        switch (state.phase()) {
            case NEW -> throw invalid("turn", "must be absent for NEW state");
            case TURN_OPENING -> {
                if (turn.phase() != TurnPhase.CLOSED && turn.phase() != TurnPhase.OPENING) {
                    throw invalid("turn.phase", "must be CLOSED or OPENING for TURN_OPENING");
                }
            }
            case ASSISTANT_READY -> requirePhase(turn, TurnPhase.ASSISTANT_READY, state.phase());
            case TOOLS_RUNNING -> {
                requirePhase(turn, TurnPhase.TOOLS_RUNNING, state.phase());
                if (turn.assistant() != null && turn.assistant().terminal()) {
                    throw invalid("turn.assistant.stopReason", "terminal assistant cannot run tools");
                }
                if (turn.assistant() != null && turn.assistant().stopReason() == StopReason.LENGTH) {
                    throw invalid("turn.assistant.stopReason", "truncated assistant cannot run tools");
                }
            }
            case TURN_CLOSED, FOLLOW_UP, FINISHED -> requirePhase(turn, TurnPhase.CLOSED, state.phase());
        }
        if (turn.phase() != TurnPhase.OPENING && turn.assistant() == null) throw invalid("turn.assistant", "is required after opening");
        if (turn.phase() == TurnPhase.OPENING && turn.assistant() != null) throw invalid("turn.assistant", "must be absent while opening");
        if (turn.phase() == TurnPhase.ASSISTANT_READY && turn.slots().stream().anyMatch(Optional::isPresent)) {
            throw invalid("turn.slots", "cannot be settled before the verdict");
        }
        if (turn.phase() == TurnPhase.TOOLS_RUNNING && turn.allSettled()) throw invalid("turn.slots", "must have pending calls while tools run");
        validateTurnCalls(turn);
        validateCurrentTurnTranscript(state.transcript(), turn);
    }

    private static void validateUsage(Usage usage, String field) {
        if (usage.input() < 0 || usage.output() < 0 || usage.cacheRead() < 0 || usage.cacheWrite() < 0 || usage.totalTokens() < 0) {
            throw invalid(field, "contains a negative token count");
        }
        Usage.Cost cost = usage.cost();
        if (cost.input() < 0 || cost.output() < 0 || cost.cacheRead() < 0 || cost.cacheWrite() < 0 || cost.total() < 0) {
            throw invalid(field, "contains a negative cost");
        }
    }

    private static void validateTranscript(List<AgentMessage> transcript, TurnState turn) {
        List<ContentBlock.ToolCall> calls = List.of();
        AssistantMessage latest = null;
        int settled = 0;
        for (AgentMessage message : transcript) {
            if (message instanceof ToolResultMessage result) {
                if (settled == calls.size()) throw invalid("transcript", "contains orphan tool result '" + result.toolCallId() + "'");
                ContentBlock.ToolCall call = calls.get(settled++);
                if (!call.id().equals(result.toolCallId()) || !call.name().equals(result.toolName())) {
                    throw invalid("transcript", "tool result does not match its source-ordered call '" + call.id() + "'");
                }
                continue;
            }
            if (settled != calls.size()) throw invalid("transcript", "contains a message before all prior tool results");
            calls = List.of();
            settled = 0;
            if (message instanceof AssistantMessage assistant) {
                latest = assistant;
                validateUsage(assistant.usage(), "assistant.usage");
                calls = assistant.toolCalls();
                var ids = new HashSet<String>();
                for (ContentBlock.ToolCall call : calls) {
                    if (call.id().isBlank() || call.name().isBlank()) throw invalid("assistant.toolCalls", "contains a blank id or name");
                    if (!ids.add(call.id())) throw invalid("assistant.toolCalls", "contains duplicate id '" + call.id() + "'");
                }
            }
        }
        if (settled != calls.size() && (turn == null || !latest.equals(turn.assistant())
                || turn.phase() != TurnPhase.ASSISTANT_READY && turn.phase() != TurnPhase.TOOLS_RUNNING)) {
            throw invalid("transcript", "contains unfinished tool calls outside the active turn");
        }
    }

    private static void validateTurnCalls(TurnState turn) {
        if (turn.assistant() == null) {
            if (!turn.slots().isEmpty()) throw invalid("turn.slots", "require an assistant");
            return;
        }
        List<ContentBlock.ToolCall> calls = turn.assistant().toolCalls();
        if (turn.slots().size() != calls.size()) throw invalid("turn.slots", "count does not match assistant tool calls");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < calls.size(); i++) {
            ContentBlock.ToolCall call = calls.get(i);
            if (call.id().isBlank()) throw invalid("turn.assistant.toolCalls", "contains a blank id");
            if (!ids.add(call.id())) throw invalid("turn.assistant.toolCalls", "contains duplicate id '" + call.id() + "'");
            Optional<ToolResultMessage> slot = turn.slots().get(i);
            if (slot.isPresent() && (!slot.get().toolCallId().equals(call.id()) || !slot.get().toolName().equals(call.name()))) {
                throw invalid("turn.slots[" + i + "]", "does not match its tool call");
            }
        }
        if (turn.phase() == TurnPhase.CLOSED && turn.slots().stream().anyMatch(Optional::isEmpty)) {
            throw invalid("turn.slots", "contains pending results in CLOSED state");
        }
    }

    private static void validateCurrentTurnTranscript(List<AgentMessage> transcript, TurnState turn) {
        if (turn.assistant() == null) return;
        int assistantIndex = transcript.lastIndexOf(turn.assistant());
        if (assistantIndex < 0) throw invalid("turn.assistant", "is absent from transcript");
        int index = assistantIndex + 1;
        boolean pending = false;
        for (Optional<ToolResultMessage> slot : turn.slots()) {
            if (slot.isEmpty()) { pending = true; continue; }
            if (pending) throw invalid("turn.slots", "settled slots must form a source-ordered prefix");
            if (index == transcript.size() || !slot.get().equals(transcript.get(index++))) {
                throw invalid("turn.slots", "settled result differs from transcript");
            }
        }
        if (index < transcript.size() && (transcript.get(index) instanceof ToolResultMessage
                || turn.phase() != TurnPhase.CLOSED)) {
            throw invalid("turn.slots", "transcript contains entries not represented by current turn");
        }
    }

    private static void requirePhase(TurnState turn, TurnPhase expected, Phase statePhase) {
        if (turn.phase() != expected) throw invalid("turn.phase", "must be " + expected + " for " + statePhase);
    }

    private static IllegalArgumentException invalid(String field, String detail) { return new IllegalArgumentException(field + " " + detail); }

    // ---- small records -------------------------------------------------------------------------

    private static Json encode(ModelRef model) {
        return Json.obj("api", Json.str(model.api()), "provider", Json.str(model.provider()), "id", Json.str(model.id()),
                "contextWindow", Json.num(model.contextWindow()), "maxOutputTokens", Json.num(model.maxOutputTokens()));
    }

    private static ModelRef decodeModel(Json.Obj model) {
        return new ModelRef(str(model, "api"), str(model, "provider"), str(model, "id"), integer(model, "contextWindow"), integer(model, "maxOutputTokens"));
    }

    private static Json encode(Usage usage) {
        return Json.obj("input", Json.num(usage.input()), "output", Json.num(usage.output()), "cacheRead", Json.num(usage.cacheRead()),
                "cacheWrite", Json.num(usage.cacheWrite()), "totalTokens", Json.num(usage.totalTokens()),
                "cost", Json.obj("input", Json.num(usage.cost().input()), "output", Json.num(usage.cost().output()),
                        "cacheRead", Json.num(usage.cost().cacheRead()), "cacheWrite", Json.num(usage.cost().cacheWrite()), "total", Json.num(usage.cost().total())));
    }

    private static Usage decodeUsage(Json.Obj usage) {
        Json.Obj cost = obj(member(usage, "cost"), "usage.cost");
        return new Usage(number(usage, "input").asLong(), number(usage, "output").asLong(), number(usage, "cacheRead").asLong(),
                number(usage, "cacheWrite").asLong(), number(usage, "totalTokens").asLong(),
                new Usage.Cost(number(cost, "input").asDouble(), number(cost, "output").asDouble(), number(cost, "cacheRead").asDouble(),
                        number(cost, "cacheWrite").asDouble(), number(cost, "total").asDouble()));
    }

    private static Json encode(RunLimits limits) {
        var m = new LinkedHashMap<String, Json>();
        m.put("maxTurns", Json.num(limits.maxTurns()));
        m.put("maxToolCalls", Json.num(limits.maxToolCalls()));
        limits.wallClock().ifPresent(duration -> m.put("wallClockMillis", Json.num(duration.toMillis())));
        m.put("toolExecution", Json.str(limits.toolExecution().name()));
        return Json.obj(m);
    }

    private static RunLimits decodeLimits(Json.Obj limits) {
        Optional<Duration> wallClock = optionalNumber(limits, "wallClockMillis").map(number -> Duration.ofMillis(number.asLong()));
        return new RunLimits(integer(limits, "maxTurns"), integer(limits, "maxToolCalls"), wallClock,
                enumValue(ToolExecutionMode.class, str(limits, "toolExecution"), "limits.toolExecution"));
    }

    private static Json encode(RunOutcome outcome) {
        return switch (outcome) {
            case RunOutcome.Completed(var reason) -> Json.obj("type", Json.str("completed"), "reason", Json.str(reason.name()));
            case RunOutcome.Aborted() -> Json.obj("type", Json.str("aborted"));
            case RunOutcome.Failed(var reason, var message, _) -> Json.obj("type", Json.str("failed"), "reason", Json.str(reason.name()), "message", Json.str(String.valueOf(message)));
            case RunOutcome.LimitExceeded(var limit, var detail) -> Json.obj("type", Json.str("limitExceeded"), "limit", Json.str(limit.name()), "detail", Json.str(String.valueOf(detail)));
        };
    }

    private RunOutcome decodeOutcome(Json json) {
        Json.Obj outcome = obj(json, "outcome");
        return switch (str(outcome, "type")) {
            case "completed" -> new RunOutcome.Completed(enumValue(StopReason.class, str(outcome, "reason"), "outcome.reason"));
            case "aborted" -> new RunOutcome.Aborted();
            case "failed" -> new RunOutcome.Failed(enumValue(StopReason.class, str(outcome, "reason"), "outcome.reason"), str(outcome, "message"), null);
            case "limitExceeded" -> new RunOutcome.LimitExceeded(enumValue(RunOutcome.Limit.class, str(outcome, "limit"), "outcome.limit"), str(outcome, "detail"));
            default -> throw new IllegalArgumentException("unknown outcome type '" + str(outcome, "type") + "'");
        };
    }

    // ---- JSON accessors ------------------------------------------------------------------------

    private static void putIfPresent(Map<String, Json> map, String key, String value) { if (value != null) map.put(key, Json.str(value)); }

    private static Json member(Json.Obj object, String key) {
        return object.get(key).orElseThrow(() -> new IllegalArgumentException("missing member '" + key + "'"));
    }

    private static Json.Obj obj(Json json, String field) {
        if (json instanceof Json.Obj object) return object;
        throw new IllegalArgumentException(field + " must be a JSON object");
    }

    private static Json.Arr arr(Json.Obj object, String key) {
        Json value = member(object, key);
        if (value instanceof Json.Arr array) return array;
        throw new IllegalArgumentException("member '" + key + "' must be a JSON array");
    }

    private static Json.Num number(Json.Obj object, String key) {
        Json value = member(object, key);
        if (value instanceof Json.Num number) return number;
        throw new IllegalArgumentException("member '" + key + "' must be a JSON number");
    }

    private static int integer(Json.Obj object, String key) {
        try { return number(object, key).asInt(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("member '" + key + "' must be an integer", invalid); }
    }

    private static String str(Json.Obj object, String key) {
        Json value = member(object, key);
        if (value instanceof Json.Str string) return string.value();
        throw new IllegalArgumentException("member '" + key + "' must be a JSON string");
    }

    private static String optionalString(Json.Obj object, String key) {
        Json value = object.get(key).orElse(null);
        if (value == null || value == Json.Null.NULL) return null;
        if (value instanceof Json.Str string) return string.value();
        throw new IllegalArgumentException("member '" + key + "' must be a JSON string when present");
    }

    private static Optional<Json.Num> optionalNumber(Json.Obj object, String key) {
        Json value = object.get(key).orElse(null);
        if (value == null || value == Json.Null.NULL) return Optional.empty();
        if (value instanceof Json.Num number) return Optional.of(number);
        throw new IllegalArgumentException("member '" + key + "' must be a JSON number when present");
    }

    private static boolean bool(Json.Obj object, String key) {
        Json value = member(object, key);
        if (value instanceof Json.Bool bool) return bool.value();
        throw new IllegalArgumentException("member '" + key + "' must be a JSON boolean");
    }

    private static Instant instant(Json.Obj object, String key) {
        try { return Instant.parse(str(object, key)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("member '" + key + "' must be an ISO-8601 instant", invalid); }
    }

    private static <T> T nullable(Json.Obj object, String key, Function<Json, T> decode) {
        Json value = member(object, key);
        return value == Json.Null.NULL ? null : decode.apply(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(field + " has unknown value '" + value + "'", invalid); }
    }

    private static Set<String> stringSet(Json.Arr values, String field) {
        Set<String> result = new LinkedHashSet<>();
        for (Json value : values.values()) {
            if (!(value instanceof Json.Str string)) throw new IllegalArgumentException(field + " must contain only strings");
            if (!result.add(string.value())) throw new IllegalArgumentException(field + " contains duplicate '" + string.value() + "'");
        }
        return result;
    }
}
