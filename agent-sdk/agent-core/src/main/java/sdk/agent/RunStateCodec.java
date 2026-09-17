package sdk.agent;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/// [RunState] ⇄ [Json], so a run can be checkpointed at any `advance()` boundary and resumed with
/// fresh collaborators. The three built-in message kinds are encoded here; every other kind goes
/// through its registered [AgentMessageCodec]. Encode is **fail-closed** — an unregistered kind
/// throws naming the class — while decode keeps an unknown kind as an opaque [CustomMessage] so
/// nothing is ever dropped. `RunOutcome.Failed.cause` does not survive the round trip.
///
/// A checkpoint never falls inside a provider stream, so a turn's streaming accumulator (open
/// block, partial tool-call arguments) is always empty and is not written; `content` is the
/// finished assistant message's content.
public final class RunStateCodec {

    public static final int SCHEMA_VERSION = 2;

    private final Map<String, AgentMessageCodec> custom;

    public RunStateCodec(Map<String, AgentMessageCodec> customCodecs) { this.custom = Map.copyOf(customCodecs); }

    public static RunStateCodec builtIn() { return new RunStateCodec(Map.of()); }

    // ---- run state -----------------------------------------------------------------------------

    public Json encode(RunState s) {
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
        int version = num(o, "schemaVersion").asInt();
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported RunState schema version " + version + " (this SDK writes " + SCHEMA_VERSION + ")");
        }
        return new RunState(
                str(o, "runId"),
                Phase.valueOf(str(o, "phase")),
                num(o, "turnIndex").asInt(),
                messages(arr(o, "transcript")),
                num(o, "seedSize").asInt(),
                messages(arr(o, "pendingInjection")),
                nullable(o, "turn", this::decodeTurn),
                decodeLimits(obj(o.get("limits").orElseThrow(), "limits")),
                num(o, "turnsUsed").asInt(),
                num(o, "toolCallsUsed").asInt(),
                decodeUsage(obj(o.get("usage").orElseThrow(), "usage")),
                Instant.parse(str(o, "startedAt")),
                nullable(o, "outcome", this::decodeOutcome),
                optStr(o, "toolSetHash"));
    }

    // ---- messages ------------------------------------------------------------------------------

    /// @throws IllegalStateException for a kind with no codec — fail-closed, naming the class
    public Json encodeMessage(AgentMessage m) {
        var env = new LinkedHashMap<String, Json>();
        env.put("kind", Json.str(m.kind()));
        env.put("at", Json.str(m.timestamp().toString()));
        Json data = switch (m) {
            case UserMessage u       -> Json.obj("content", blocks(u.content()));
            case AssistantMessage a  -> encodeAssistant(a);
            case ToolResultMessage t -> Json.obj("toolCallId", Json.str(t.toolCallId()), "toolName", Json.str(t.toolName()),
                                                  "content", blocks(t.content()), "details", t.details(), "isError", Json.bool(t.isError()));
            default -> {
                AgentMessageCodec codec = custom.get(m.kind());
                if (codec == null) {
                    if (m instanceof CustomMessage c) yield c.payload();     // opaque, survives as it arrived
                    throw new IllegalStateException("no AgentMessageCodec registered for kind '" + m.kind() + "' (" + m.getClass().getName() + ")");
                }
                yield codec.encode(m);
            }
        };
        env.put("data", data);
        return Json.obj(env);
    }

    public AgentMessage decodeMessage(Json json) {
        Json.Obj env = obj(json, "message");
        String kind = str(env, "kind");
        Instant at = Instant.parse(str(env, "at"));
        Json data = env.get("data").orElse(Json.Obj.EMPTY);
        return switch (kind) {
            case "user"       -> new UserMessage(decodeBlocks(arr(obj(data, "user"), "content")), at);
            case "assistant"  -> decodeAssistant(obj(data, "assistant"), at);
            case "toolResult" -> {
                Json.Obj o = obj(data, "toolResult");
                yield new ToolResultMessage(str(o, "toolCallId"), str(o, "toolName"), decodeBlocks(arr(o, "content")),
                        o.get("details").orElse(Json.Null.NULL), bool(o, "isError"), at);
            }
            default -> {
                AgentMessageCodec codec = custom.get(kind);
                yield codec != null ? codec.decode(data) : new CustomMessage(kind, data, at);
            }
        };
    }

    private Json.Arr messages(List<AgentMessage> ms) { return Json.arr(ms.stream().map(this::encodeMessage).toList()); }

    private List<AgentMessage> messages(Json.Arr a) { return a.values().stream().map(this::decodeMessage).toList(); }

    private Json encodeAssistant(AssistantMessage a) {
        var m = new LinkedHashMap<String, Json>();
        m.put("content", blocks(a.content()));
        m.put("model", encode(a.model()));
        putIfPresent(m, "responseId", a.responseId());
        m.put("usage", encode(a.usage()));
        m.put("stopReason", Json.str(a.stopReason().name()));
        putIfPresent(m, "errorMessage", a.errorMessage());
        return Json.obj(m);
    }

    private AssistantMessage decodeAssistant(Json.Obj o, Instant at) {
        return new AssistantMessage(decodeBlocks(arr(o, "content")), decodeModel(obj(o.get("model").orElseThrow(), "model")),
                optStr(o, "responseId"), decodeUsage(obj(o.get("usage").orElseThrow(), "usage")),
                StopReason.valueOf(str(o, "stopReason")), optStr(o, "errorMessage"), at);
    }

    // ---- content blocks ------------------------------------------------------------------------

    public Json encode(ContentBlock b) {
        var m = new LinkedHashMap<String, Json>();
        switch (b) {
            case ContentBlock.Text t     -> { m.put("type", Json.str("text")); m.put("text", Json.str(t.text())); putIfPresent(m, "signature", t.signature()); }
            case ContentBlock.Thinking t -> { m.put("type", Json.str("thinking")); m.put("thinking", Json.str(t.thinking())); putIfPresent(m, "signature", t.signature()); m.put("redacted", Json.bool(t.redacted())); }
            case ContentBlock.Image i    -> { m.put("type", Json.str("image")); m.put("data", Json.str(i.data())); m.put("mimeType", Json.str(i.mimeType())); }
            case ContentBlock.Audio a    -> { m.put("type", Json.str("audio")); m.put("data", Json.str(a.data())); m.put("mimeType", Json.str(a.mimeType())); }
            case ContentBlock.Resource r -> {
                m.put("type", Json.str("resource")); m.put("uri", Json.str(r.uri().toString())); putIfPresent(m, "mimeType", r.mimeType());
                r.text().ifPresent(t -> m.put("text", Json.str(t))); r.blob().ifPresent(bl -> m.put("blob", Json.str(bl)));
            }
            case ContentBlock.ToolCall c -> { m.put("type", Json.str("tool_call")); m.put("id", Json.str(c.id())); m.put("name", Json.str(c.name())); m.put("arguments", c.arguments()); putIfPresent(m, "thoughtSignature", c.thoughtSignature()); }
        }
        return Json.obj(m);
    }

    public ContentBlock decodeBlock(Json json) {
        Json.Obj o = obj(json, "content block");
        return switch (str(o, "type")) {
            case "text"      -> new ContentBlock.Text(str(o, "text"), optStr(o, "signature"));
            case "thinking"  -> new ContentBlock.Thinking(str(o, "thinking"), optStr(o, "signature"), bool(o, "redacted"));
            case "image"     -> new ContentBlock.Image(str(o, "data"), str(o, "mimeType"));
            case "audio"     -> new ContentBlock.Audio(str(o, "data"), str(o, "mimeType"));
            case "resource"  -> new ContentBlock.Resource(URI.create(str(o, "uri")), optStr(o, "mimeType"),
                                    Optional.ofNullable(optStr(o, "text")), Optional.ofNullable(optStr(o, "blob")));
            case "tool_call" -> new ContentBlock.ToolCall(str(o, "id"), str(o, "name"), o.get("arguments").orElse(Json.Obj.EMPTY), optStr(o, "thoughtSignature"));
            default          -> throw new IllegalArgumentException("unknown content block type '" + str(o, "type") + "'");
        };
    }

    private Json.Arr blocks(List<ContentBlock> bs) { return Json.arr(bs.stream().map(this::encode).toList()); }

    private List<ContentBlock> decodeBlocks(Json.Arr a) { return a.values().stream().map(this::decodeBlock).toList(); }

    // ---- turn state ----------------------------------------------------------------------------

    public Json encode(TurnState t) {
        var m = new LinkedHashMap<String, Json>();
        m.put("runId", Json.str(t.runId()));
        m.put("index", Json.num(t.index()));
        m.put("phase", Json.str(t.phase().name()));
        m.put("model", encode(t.model()));
        var preflight = new LinkedHashMap<String, Json>();
        t.preflight().forEach((id, text) -> preflight.put(id, Json.str(text)));
        m.put("preflight", Json.obj(preflight));
        m.put("assistant", t.assistant() == null ? Json.nil() : encodeMessage(t.assistant()));
        m.put("slots", Json.arr(t.slots().stream().map(s -> s.<Json>map(this::encodeMessage).orElse(Json.Null.NULL)).toList()));
        m.put("stalled", Json.bool(t.stalled()));
        return Json.obj(m);
    }

    private TurnState decodeTurn(Json json) {
        Json.Obj o = obj(json, "turn state");
        var preflight = new LinkedHashMap<String, String>();
        obj(o.get("preflight").orElse(Json.Obj.EMPTY), "preflight").members().forEach((k, v) -> preflight.put(k, ((Json.Str) v).value()));
        AssistantMessage assistant = nullable(o, "assistant", j -> (AssistantMessage) decodeMessage(j));
        List<Optional<ToolResultMessage>> slots = arr(o, "slots").values().stream()
                .map(s -> s == Json.Null.NULL ? Optional.<ToolResultMessage>empty() : Optional.of((ToolResultMessage) decodeMessage(s)))
                .toList();
        return new TurnState(str(o, "runId"), num(o, "index").asInt(), TurnPhase.valueOf(str(o, "phase")),
                decodeModel(obj(o.get("model").orElseThrow(), "model")),
                assistant == null ? List.of() : assistant.content(), Optional.empty(), new LinkedHashMap<>(),
                preflight, assistant, slots, bool(o, "stalled"));
    }

    // ---- small records -------------------------------------------------------------------------

    private static Json encode(ModelRef m) {
        return Json.obj("api", Json.str(m.api()), "provider", Json.str(m.provider()), "id", Json.str(m.id()),
                "contextWindow", Json.num(m.contextWindow()), "maxOutputTokens", Json.num(m.maxOutputTokens()));
    }

    private static ModelRef decodeModel(Json.Obj o) {
        return new ModelRef(str(o, "api"), str(o, "provider"), str(o, "id"), num(o, "contextWindow").asInt(), num(o, "maxOutputTokens").asInt());
    }

    private static Json encode(Usage u) {
        return Json.obj("input", Json.num(u.input()), "output", Json.num(u.output()), "cacheRead", Json.num(u.cacheRead()),
                "cacheWrite", Json.num(u.cacheWrite()), "totalTokens", Json.num(u.totalTokens()),
                "cost", Json.obj("input", Json.num(u.cost().input()), "output", Json.num(u.cost().output()),
                        "cacheRead", Json.num(u.cost().cacheRead()), "cacheWrite", Json.num(u.cost().cacheWrite()), "total", Json.num(u.cost().total())));
    }

    private static Usage decodeUsage(Json.Obj o) {
        Json.Obj c = obj(o.get("cost").orElse(Json.Obj.EMPTY), "cost");
        var cost = c.isEmpty() ? Usage.Cost.ZERO
                : new Usage.Cost(num(c, "input").asDouble(), num(c, "output").asDouble(), num(c, "cacheRead").asDouble(), num(c, "cacheWrite").asDouble(), num(c, "total").asDouble());
        return new Usage(num(o, "input").asLong(), num(o, "output").asLong(), num(o, "cacheRead").asLong(), num(o, "cacheWrite").asLong(), num(o, "totalTokens").asLong(), cost);
    }

    private static Json encode(RunLimits l) {
        var m = new LinkedHashMap<String, Json>();
        m.put("maxTurns", Json.num(l.maxTurns()));
        m.put("maxToolCalls", Json.num(l.maxToolCalls()));
        l.wallClock().ifPresent(d -> m.put("wallClockMillis", Json.num(d.toMillis())));
        m.put("toolExecution", Json.str(l.toolExecution().name()));
        return Json.obj(m);
    }

    private static RunLimits decodeLimits(Json.Obj o) {
        return new RunLimits(num(o, "maxTurns").asInt(), num(o, "maxToolCalls").asInt(),
                o.get("wallClockMillis").map(v -> Duration.ofMillis(((Json.Num) v).asLong())),
                ToolExecutionMode.valueOf(str(o, "toolExecution")));
    }

    private static Json encode(RunOutcome outcome) {
        return switch (outcome) {
            case RunOutcome.Completed(var reason)         -> Json.obj("type", Json.str("completed"), "reason", Json.str(reason.name()));
            case RunOutcome.Aborted()                     -> Json.obj("type", Json.str("aborted"));
            case RunOutcome.Failed(var reason, var msg, _) -> Json.obj("type", Json.str("failed"), "reason", Json.str(reason.name()), "message", Json.str(String.valueOf(msg)));
            case RunOutcome.LimitExceeded(var limit, var detail) -> Json.obj("type", Json.str("limitExceeded"), "limit", Json.str(limit.name()), "detail", Json.str(String.valueOf(detail)));
        };
    }

    private RunOutcome decodeOutcome(Json json) {
        Json.Obj o = obj(json, "outcome");
        return switch (str(o, "type")) {
            case "completed"     -> new RunOutcome.Completed(StopReason.valueOf(str(o, "reason")));
            case "aborted"       -> new RunOutcome.Aborted();
            case "failed"        -> new RunOutcome.Failed(StopReason.valueOf(str(o, "reason")), str(o, "message"), null);
            case "limitExceeded" -> new RunOutcome.LimitExceeded(RunOutcome.Limit.valueOf(str(o, "limit")), str(o, "detail"));
            default              -> throw new IllegalArgumentException("unknown outcome type '" + str(o, "type") + "'");
        };
    }

    // ---- accessors -----------------------------------------------------------------------------

    private static void putIfPresent(Map<String, Json> m, String key, String value) { if (value != null) m.put(key, Json.str(value)); }

    private static Json.Obj obj(Json j, String what) {
        if (j instanceof Json.Obj o) return o;
        throw new IllegalArgumentException(what + " must be a JSON object, got " + j.getClass().getSimpleName());
    }

    private static Json.Arr arr(Json.Obj o, String key) {
        return o.get(key).filter(Json.Arr.class::isInstance).map(Json.Arr.class::cast).orElse(Json.Arr.EMPTY);
    }

    private static String str(Json.Obj o, String key) {
        String s = optStr(o, key);
        if (s == null) throw new IllegalArgumentException("missing string member '" + key + "'");
        return s;
    }

    private static String optStr(Json.Obj o, String key) {
        return o.get(key).filter(Json.Str.class::isInstance).map(j -> ((Json.Str) j).value()).orElse(null);
    }

    private static Json.Num num(Json.Obj o, String key) {
        return o.get(key).filter(Json.Num.class::isInstance).map(Json.Num.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("missing number member '" + key + "'"));
    }

    private static boolean bool(Json.Obj o, String key) {
        return o.get(key).filter(Json.Bool.class::isInstance).map(j -> ((Json.Bool) j).value()).orElse(false);
    }

    private static <T> T nullable(Json.Obj o, String key, Function<Json, T> decode) {
        return o.get(key).filter(j -> j != Json.Null.NULL).map(decode).orElse(null);
    }
}
