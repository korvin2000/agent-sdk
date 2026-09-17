package sdk.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

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

class RunStateCodecTest {

    private static final Instant AT = Instant.parse("2026-09-16T12:00:00Z");
    private static final ModelRef MODEL = new ModelRef("anthropic", "anthropic", "claude", 200_000, 8_192);

    private static RunState sample() {
        var call = new ContentBlock.ToolCall("c1", "read", Json.parse("{\"path\":\"a.txt\"}"), "sig");
        var assistant = new AssistantMessage(List.of(new ContentBlock.Thinking("hm", "s", false), ContentBlock.Text.of("hi"), call),
                MODEL, "resp-1", Usage.tokens(10, 5), StopReason.TOOL_USE, null, AT);
        var result = new ToolResultMessage("c1", "read", List.of(ContentBlock.Text.of("content")), Json.obj("k", Json.num(1)), false, AT);
        var turn = new TurnState("run-1", 1, TurnPhase.TOOLS_RUNNING, MODEL, assistant.content(), Optional.empty(),
                new LinkedHashMap<>(), Map.of("c9", "cut off"), assistant, List.of(Optional.of(result)), true);
        List<AgentMessage> transcript = List.of(
                UserMessage.of("look", List.of(new ContentBlock.Image("AAAA", "image/png"))),
                assistant, result,
                new CustomMessage("todo.snapshot", Json.obj("tasks", Json.arr()), AT),
                new UserMessage(List.of(new ContentBlock.Resource(URI.create("file:///x"), "text/plain", Optional.of("t"), Optional.empty()),
                                        new ContentBlock.Audio("BBBB", "audio/wav")), AT));
        return new RunState("run-1", Phase.TOOLS_RUNNING, 1, transcript, 1, List.of(UserMessage.text("next", AT)),
                turn, RunLimits.DEFAULTS.withWallClock(Duration.ofMinutes(5)).withToolExecution(ToolExecutionMode.SEQUENTIAL),
                2, 1, Usage.tokens(10, 5), AT, new RunOutcome.LimitExceeded(RunOutcome.Limit.THRASH, "d"), "hash");
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
    void refusesAForeignSchemaVersion() {
        Json json = RunStateCodec.builtIn().encode(sample());
        Json bumped = ((Json.Obj) json).with("schemaVersion", Json.num(99));
        assertThrows(IllegalArgumentException.class, () -> RunStateCodec.builtIn().decode(bumped));
    }
}
