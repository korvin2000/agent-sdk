package sdk.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import sdk.agent.turn.OpenBlock;
import sdk.agent.turn.TurnPhase;
import sdk.agent.turn.TurnState;

class RunStateCodecTest {

    private static final Instant AT = Instant.parse("2026-09-16T12:00:00Z");
    private static final ModelRef MODEL = new ModelRef("anthropic", "anthropic", "claude", 200_000, 8_192);

    /// A run caught between two tool batches: one slot settled, one pending, tools advertised.
    private static RunState sample() {
        var call = new ContentBlock.ToolCall("c1", "read", Json.parse("{\"path\":\"a.txt\"}"), "sig");
        var pending = new ContentBlock.ToolCall("c2", "write", Json.Obj.EMPTY, null);
        var assistant = new AssistantMessage(List.of(new ContentBlock.Thinking("hm", "s", false), ContentBlock.Text.of("hi"), call, pending),
                MODEL, "resp-1", Usage.tokens(10, 5), StopReason.TOOL_USE, null, AT);
        var result = new ToolResultMessage("c1", "read", List.of(ContentBlock.Text.of("content")), Json.obj("k", Json.num(1)), false, AT);
        var turn = new TurnState("run-1", 1, TurnPhase.TOOLS_RUNNING, MODEL, List.of(), Optional.empty(),
                new LinkedHashMap<>(), Set.of("read", "write"), assistant, List.of(Optional.of(result), Optional.empty()));
        List<AgentMessage> transcript = List.of(
                UserMessage.of("look", List.of(new ContentBlock.Image("AAAA", "image/png")), AT),
                assistant, result,
                new CustomMessage("todo.snapshot", Json.obj("tasks", Json.arr()), AT),
                new UserMessage(List.of(new ContentBlock.Resource(URI.create("file:///x"), "text/plain", Optional.of("t"), Optional.empty()),
                                        new ContentBlock.Audio("BBBB", "audio/wav")), AT));
        return new RunState("run-1", Phase.TOOLS_RUNNING, 1, transcript, 1, List.of(UserMessage.text("next", AT)),
                turn, RunLimits.DEFAULTS.withWallClock(Duration.ofMinutes(5)).withToolExecution(ToolExecutionMode.SEQUENTIAL),
                2, 1, Usage.tokens(10, 5), AT, null, "hash");
    }

    @Test
    void roundTripsACompleteState() {
        RunStateCodec codec = RunStateCodec.builtIn();
        RunState original = sample();
        Json json = codec.encode(original);
        RunState back = codec.decode(Json.parse(json.toText()));
        assertEquals(original, back);
        assertEquals(json, codec.encode(back));
        assertEquals(original.transcript().subList(1, 5), back.produced());
        assertEquals(Set.of("read", "write"), back.turn().allowedTools(), "the advertised tools survive, so resume keeps the turn's authority");
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

        assertThrows(IllegalStateException.class, () -> RunStateCodec.builtIn().encodeMessage(new Marker("n", AT)));
    }

    @Test
    void refusesEveryOtherSchemaVersion() {
        Json.Obj json = (Json.Obj) RunStateCodec.builtIn().encode(sample());
        assertThrows(IllegalArgumentException.class, () -> RunStateCodec.builtIn().decode(json.with("schemaVersion", Json.num(2))));
        assertThrows(IllegalArgumentException.class, () -> RunStateCodec.builtIn().decode(json.with("schemaVersion", Json.num(99))));
    }

    @Test
    void missingOrMistypedMembersAreRefusedNotDefaulted() {
        var codec = RunStateCodec.builtIn();
        var state = (Json.Obj) codec.encode(sample());
        var turn = (Json.Obj) state.get("turn").orElseThrow();
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.without("transcript"))),
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turnsUsed", Json.str("1")))),
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("phase", Json.str("LIMBO")))),
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("startedAt", Json.str("yesterday")))),
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn", turn.without("allowedTools")))),
                () -> assertThrows(IllegalArgumentException.class, () -> codec.decode(state.with("turn", turn.with("slots", Json.arr())))));
    }

    @Test
    void aStreamingTurnCannotBeCheckpointed() {
        var streaming = new TurnState("run-1", 0, TurnPhase.STREAMING, MODEL, List.of(), Optional.of(OpenBlock.text(0)),
                new LinkedHashMap<>(), Set.of(), null, List.of());
        var thrown = assertThrows(IllegalStateException.class, () -> RunStateCodec.builtIn().encode(streaming));
        assertTrue(thrown.getMessage().contains("STREAMING"));
    }

    private static void assertAll(org.junit.jupiter.api.function.Executable... checks) {
        org.junit.jupiter.api.Assertions.assertAll(checks);
    }
}
