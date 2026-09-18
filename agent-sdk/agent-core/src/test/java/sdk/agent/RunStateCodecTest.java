package sdk.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

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
import sdk.agent.turn.OpenBlock;
import sdk.agent.turn.TurnState;

class RunStateCodecTest {

    private static final Instant AT = Instant.parse("2026-09-16T12:00:00Z");
    private static final ModelRef MODEL = new ModelRef("anthropic", "anthropic", "claude", 200_000, 8_192);

    private static RunState sample() {
        var call = new ContentBlock.ToolCall("c1", "read", Json.parse("{\"path\":\"a.txt\"}"), "sig");
        var pending = new ContentBlock.ToolCall("c2", "write", Json.Obj.EMPTY, null);
        var replay = Json.obj("itemId", Json.str("item-1"), "encrypted", Json.str("blob"));
        var assistant = new AssistantMessage(List.of(new ContentBlock.Thinking("hm", "s", false), ContentBlock.Text.of("hi"), call, pending), MODEL, "resp-1", Usage.tokens(10, 5), StopReason.TOOL_USE, null, replay, AT);
        var result = new ToolResultMessage("c1", "read", List.of(ContentBlock.Text.of("content")), Json.obj("k", Json.num(1)), false, AT);
        var turn = new TurnState("run-1", 0, TurnPhase.TOOLS_RUNNING, MODEL, Map.of(), Map.of(), Map.of(),
                Set.of("read"), assistant, List.of(Optional.of(result), Optional.empty()), false);
        List<AgentMessage> transcript = List.of(UserMessage.of("look", List.of(new ContentBlock.Image("AAAA", "image/png")), AT), assistant, result);
        return new RunState("run-1", Phase.TOOLS_RUNNING, 0, transcript, 1, List.of(),
                turn, RunLimits.DEFAULTS.withWallClock(Duration.ofMinutes(5)).withToolExecution(ToolExecutionMode.SEQUENTIAL),
                1, 1, Usage.tokens(10, 5), AT, null, "hash");
    }

    @Test
    void roundTripsACompleteState() {
        RunStateCodec codec = RunStateCodec.builtIn();
        RunState original = sample();
        Json json = codec.encode(original);
        RunState back = codec.decode(Json.parse(json.toText()));
        assertEquals(original, back);
        assertEquals(json, codec.encode(back));
        assertEquals(original.transcript().subList(1, 3), back.produced());
    }

    @Test
    void unknownKindsSurviveAsOpaqueCustomMessagesAndCustomCodecsAreHonoured() {
        record Marker(String note, Instant timestamp) implements AgentMessage {
            @Override public String kind() { return "marker"; }
        }
        var markerCodec = new AgentMessageCodec() {
            @Override public String kind() { return "marker"; }
            @Override public Json encode(AgentMessage m) { return Json.obj("note", Json.str(((Marker) m).note()), "at", Json.str(m.timestamp().toString())); }
            @Override public AgentMessage decode(Json j) { var o = (Json.Obj) j; return new Marker(((Json.Str) o.get("note").orElseThrow()).value(), Instant.parse(((Json.Str) o.get("at").orElseThrow()).value())); }
        };
        var withCodec = new RunStateCodec(Map.of("marker", markerCodec));
        Json encoded = withCodec.encodeMessage(new Marker("n", AT));
        assertEquals(new Marker("n", AT), withCodec.decodeMessage(encoded));

        AgentMessage opaque = RunStateCodec.builtIn().decodeMessage(encoded);
        assertInstanceOf(CustomMessage.class, opaque);
        assertEquals("marker", opaque.kind());
        assertEquals(AT, opaque.timestamp());
    }

    @Test
    void explicitlyRejectsSchemaTwoAndForeignVersions() {
        Json json = RunStateCodec.builtIn().encode(sample());
        assertThrows(IllegalArgumentException.class, () -> RunStateCodec.builtIn().decode(((Json.Obj) json).with("schemaVersion", Json.num(2))));
        assertThrows(IllegalArgumentException.class, () -> RunStateCodec.builtIn().decode(((Json.Obj) json).with("schemaVersion", Json.num(99))));
    }

    @Test
    void requiredFieldsNeverCoerceMissingOrWrongTypesToDefaults() {
        var codec = RunStateCodec.builtIn();
        var state = (Json.Obj) codec.encode(sample());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.without("transcript")));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("pendingInjection", Json.Obj.EMPTY)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turnsUsed", Json.str("1"))));
        var turn = (Json.Obj) state.get("turn").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn", turn.without("allowedTools"))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn", turn.with("stalled", Json.num(0)))));
    }

    @Test
    void settledResultsCannotBeErasedOrRewrittenInSlots() {
        var codec = RunStateCodec.builtIn();
        var state = (Json.Obj) codec.encode(sample());
        var turn = (Json.Obj) state.get("turn").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn",
                turn.with("slots", Json.arr(List.of(Json.nil(), Json.nil()))))));
        var result = sample().turn().results().getFirst();
        for (var rewritten : List.of(
                new ToolResultMessage("other", result.toolName(), result.content(), result.details(), false, AT),
                new ToolResultMessage(result.toolCallId(), "other", result.content(), result.details(), false, AT),
                new ToolResultMessage(result.toolCallId(), result.toolName(), List.of(ContentBlock.Text.of("different")), result.details(), false, AT))) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn",
                    turn.with("slots", Json.arr(List.of(codec.encodeMessage(rewritten), Json.nil()))))));
        }
    }

    @Test
    void streamingAccumulatorsCannotBeSilentlyDroppedByEitherEncoder() {
        var codec = RunStateCodec.builtIn();
        var streaming = new TurnState("run-1", 0, TurnPhase.STREAMING, MODEL, Map.of(),
                Map.of(0, OpenBlock.text(0)), Map.of(), Set.of(), null, List.of(), false);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(streaming));
        var state = sample().toBuilder();
        state.phase = Phase.TURN_OPENING;
        state.turn = streaming;
        assertThrows(IllegalArgumentException.class, () -> codec.encode(state.build()));
    }
}
