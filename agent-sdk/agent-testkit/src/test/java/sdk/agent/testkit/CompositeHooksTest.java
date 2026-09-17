package sdk.agent.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sdk.agent.RunLimits;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.AgentEvent;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.AfterToolCall;
import sdk.agent.hook.AgentHooks;
import sdk.agent.hook.BeforeToolCall;
import sdk.agent.hook.CompositeHooks;
import sdk.agent.hook.ToolDecision;
import sdk.agent.hook.ToolOverride;
import sdk.agent.hook.TurnContext;
import sdk.agent.hook.TurnVerdict;
import sdk.agent.json.Json;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.AssistantMessage;
import sdk.agent.message.ContentBlock;
import sdk.agent.message.ModelRef;
import sdk.agent.message.StopReason;
import sdk.agent.message.Usage;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.ThinkingLevel;
import sdk.agent.tool.ErrorKind;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.tool.ToolResult;

/// Composition rules and the `safely` fallbacks: a throwing hook degrades to its
/// stated fallback — "the rewrite did not apply", never "the tool call vanished".
@DisplayName("CompositeHooks — composition and isolation")
final class CompositeHooksTest {

    private static final ModelRef MODEL = new ModelRef("test", "scripted", "scripted-1", 200_000, 8_000);
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-01-01T00:00:00Z");

    private final List<String> reported = new ArrayList<>();
    private final Cancellation cancel = Cancellation.create();

    private CompositeHooks of(AgentHooks... hooks) {
        return new CompositeHooks(List.of(hooks), (name, _) -> reported.add(name));
    }

    private static TurnContext ctx() {
        return new TurnContext("run-1", 0, List.of(), RunLimits.DEFAULTS, 0, ToolRegistry.EMPTY,
                Cancellation.create(), FakeClock.atEpoch());
    }

    private static AssistantMessage assistant() {
        return new AssistantMessage(List.of(ContentBlock.Text.of("hi")), MODEL, "r", Usage.EMPTY, StopReason.STOP, null, NOW);
    }

    private static ContentBlock.ToolCall call() {
        return new ContentBlock.ToolCall("call-1", "bash", Json.obj("command", Json.str("ls")), null);
    }

    private static BeforeToolCall before(Json bound) {
        return new BeforeToolCall(assistant(), call(), call().arguments(), bound, ctx());
    }

    private static AfterToolCall after(ToolResult result) {
        return new AfterToolCall(assistant(), call(), call().arguments(), result, result.isError(), ctx());
    }

    // ---- left-to-right composition ---------------------------------------------------------------

    @Test
    @DisplayName("transformContext composes left to right; each hook sees the previous output")
    void transformContextComposes() {
        AgentHooks drop = new AgentHooks() {
            @Override public List<AgentMessage> transformContext(List<AgentMessage> messages, Cancellation c) {
                return messages.subList(1, messages.size());
            }
        };
        AgentHooks summarise = new AgentHooks() {
            @Override public List<AgentMessage> transformContext(List<AgentMessage> messages, Cancellation c) {
                return List.of(UserMessage.text("summary of " + messages.size(), NOW));
            }
        };

        var out = of(drop, summarise).transformContext(
                List.of(UserMessage.text("a", NOW), UserMessage.text("b", NOW), UserMessage.text("c", NOW)), cancel);

        assertEquals("summary of 2", ((UserMessage) out.getFirst()).text());
    }

    @Test
    @DisplayName("a throwing transformContext degrades to its input; the chain continues")
    void transformContextIsolatesAThrow() {
        AgentHooks boom = new AgentHooks() {
            @Override public List<AgentMessage> transformContext(List<AgentMessage> m, Cancellation c) {
                throw new IllegalStateException("compaction service down");
            }
        };
        AgentHooks tag = new AgentHooks() {
            @Override public List<AgentMessage> transformContext(List<AgentMessage> m, Cancellation c) {
                var out = new ArrayList<AgentMessage>(m);
                out.add(UserMessage.text("tagged", NOW));
                return out;
            }
        };

        var out = of(boom, tag).transformContext(List.of(UserMessage.text("a", NOW)), cancel);
        assertEquals(2, out.size(), "the failed compaction must not lose the transcript");
        assertEquals(List.of("transformContext"), reported);
    }

    @Test
    @DisplayName("beforeTurn concatenates in registration order")
    void beforeTurnConcatenates() {
        AgentHooks skills = new AgentHooks() {
            @Override public List<AgentMessage> beforeTurn(TurnContext c) { return List.of(UserMessage.text("skill", NOW)); }
        };
        AgentHooks reminder = new AgentHooks() {
            @Override public List<AgentMessage> beforeTurn(TurnContext c) { return List.of(UserMessage.text("reminder", NOW)); }
        };
        AgentHooks boom = new AgentHooks() {
            @Override public List<AgentMessage> beforeTurn(TurnContext c) { throw new IllegalStateException("nope"); }
        };

        var out = of(skills, boom, reminder).beforeTurn(ctx());
        assertEquals(List.of("skill", "reminder"), out.stream().map(m -> ((UserMessage) m).text()).toList());
        assertEquals(List.of("beforeTurn"), reported);
    }

    @Test
    @DisplayName("beforeRequest composes left to right and a throw leaves the request as it was")
    void beforeRequestComposes() {
        var request = new LlmRequest(MODEL, "sys", List.of(), List.of(), ThinkingLevel.OFF, OptionalInt.empty(), Json.Obj.EMPTY);
        AgentHooks thinkHard = new AgentHooks() {
            @Override public LlmRequest beforeRequest(LlmRequest r, TurnContext c) { return r.withThinking(ThinkingLevel.HIGH); }
        };
        AgentHooks boom = new AgentHooks() {
            @Override public LlmRequest beforeRequest(LlmRequest r, TurnContext c) { throw new IllegalStateException("nope"); }
        };
        AgentHooks rename = new AgentHooks() {
            @Override public LlmRequest beforeRequest(LlmRequest r, TurnContext c) { return r.withSystemPrompt(r.systemPrompt() + "!"); }
        };

        var out = of(thinkHard, boom, rename).beforeRequest(request, ctx());
        assertEquals(ThinkingLevel.HIGH, out.thinking());
        assertEquals("sys!", out.systemPrompt());
        assertEquals(ThinkingLevel.OFF, request.thinking(), "the original request is never mutated");
    }

    @Test
    @DisplayName("afterAssistant returns the FIRST non-Proceed verdict and asks nobody else")
    void afterAssistantReturnsTheFirstNonProceed() {
        var asked = new ArrayList<String>();
        AgentHooks proceeds = new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext c) { asked.add("first"); return TurnVerdict.PROCEED; }
        };
        AgentHooks stops = new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext c) {
                asked.add("second");
                return new TurnVerdict.Stop(new RunOutcome.Aborted(), null);
            }
        };
        AgentHooks never = new AgentHooks() {
            @Override public TurnVerdict afterAssistant(AssistantMessage m, TurnContext c) { asked.add("third"); return TurnVerdict.PROCEED; }
        };

        assertInstanceOf(TurnVerdict.Stop.class, of(proceeds, stops, never).afterAssistant(assistant(), ctx()));
        assertEquals(List.of("first", "second"), asked);
    }

    // ---- the permissions seam --------------------------------------------------------------------

    @Test
    @DisplayName("beforeToolCall: the first Block wins and short-circuits the rest")
    void firstBlockWins() {
        var asked = new ArrayList<String>();
        AgentHooks firstBlock = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                asked.add("first");
                return ToolDecision.block("the user said no");
            }
        };
        AgentHooks secondBlock = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                asked.add("second");
                return ToolDecision.block("sandbox says no");
            }
        };

        var decision = assertInstanceOf(ToolDecision.Block.class,
                of(firstBlock, secondBlock).beforeToolCall(before(call().arguments()), cancel));
        assertEquals("the user said no", decision.reason());
        assertEquals(List.of("first"), asked);
    }

    @Test
    @DisplayName("beforeToolCall: rewrites chain — each hook sees the previous hook's arguments")
    void rewritesChain() {
        AgentHooks narrow = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                return ToolDecision.allow(Json.obj("command", Json.str("ls -1")));
            }
        };
        AgentHooks prefix = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                String previous = ((Json.Obj) call.boundArguments()).get("command")
                        .map(j -> ((Json.Str) j).value()).orElseThrow();
                return ToolDecision.allow(Json.obj("command", Json.str("nice " + previous)));
            }
        };

        var allow = assertInstanceOf(ToolDecision.Allow.class,
                of(narrow, prefix).beforeToolCall(before(call().arguments()), cancel));
        assertEquals(Json.obj("command", Json.str("nice ls -1")), allow.rewritten());
    }

    @Test
    @DisplayName("beforeToolCall: a throwing hook degrades to ALLOW and a later Block still wins")
    void aThrowingPermissionHookDoesNotOpenTheGate() {
        AgentHooks boom = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                throw new IllegalStateException("permission service down");
            }
        };
        AgentHooks blocks = new AgentHooks() {
            @Override public ToolDecision beforeToolCall(BeforeToolCall call, Cancellation c) {
                return ToolDecision.block("sandbox says no");
            }
        };

        assertInstanceOf(ToolDecision.Block.class, of(boom, blocks).beforeToolCall(before(call().arguments()), cancel));
        assertEquals(List.of("beforeToolCall"), reported);
        assertSame(ToolDecision.ALLOW, of(boom).beforeToolCall(before(call().arguments()), cancel),
                "with nothing else registered the stated fallback is ALLOW");
    }

    // ---- result overrides -------------------------------------------------------------------------

    @Test
    @DisplayName("afterToolCall: overrides chain, and a later hook sees the earlier hook's result")
    void overridesChain() {
        AgentHooks truncate = new AgentHooks() {
            @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation c) {
                return Optional.of(ToolOverride.content(List.of(ContentBlock.Text.of(call.result().text() + " [truncated]"))));
            }
        };
        AgentHooks annotate = new AgentHooks() {
            @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation c) {
                return Optional.of(ToolOverride.content(List.of(ContentBlock.Text.of(call.result().text() + " [audited]"))));
            }
        };

        ToolResult original = ToolResult.text("a very long listing");
        var override = of(truncate, annotate).afterToolCall(after(original), cancel).orElseThrow();
        assertEquals("a very long listing [truncated] [audited]", override.applyTo(original).text());
    }

    @Test
    @DisplayName("afterToolCall: a throwing hook never loses the result the next hook produces")
    void aThrowingAfterHookNeverLosesAResult() {
        AgentHooks boom = new AgentHooks() {
            @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation c) {
                throw new IllegalStateException("audit sink unreachable");
            }
        };
        AgentHooks annotate = new AgentHooks() {
            @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation c) {
                return Optional.of(ToolOverride.content(List.of(ContentBlock.Text.of("kept"))));
            }
        };

        ToolResult original = ToolResult.text("output");
        var override = of(boom, annotate).afterToolCall(after(original), cancel).orElseThrow();
        assertEquals("kept", override.applyTo(original).text());
        assertEquals(List.of("afterToolCall"), reported);

        assertTrue(of(boom).afterToolCall(after(original), cancel).isEmpty(),
                "with nobody else registered the stated fallback is \"no override\"");
    }

    @Test
    @DisplayName("an override that flips isError keeps the original ErrorKind, never invents one")
    void overrideKeepsTheOriginalErrorKind() {
        ToolResult original = ToolResult.error(ErrorKind.TOOL_REPORTED, "compile failed", Json.obj("code", Json.num(1)));
        AgentHooks clearDetails = new AgentHooks() {
            @Override public Optional<ToolOverride> afterToolCall(AfterToolCall call, Cancellation c) {
                return Optional.of(ToolOverride.details(Json.Null.NULL));
            }
        };

        ToolResult merged = of(clearDetails).afterToolCall(after(original), cancel).orElseThrow().applyTo(original);
        var err = assertInstanceOf(ToolResult.Err.class, merged);
        assertEquals(ErrorKind.TOOL_REPORTED, err.kind());
        assertEquals(Json.Null.NULL, err.details(), "Optional makes \"set to null\" and \"leave alone\" distinguishable");
    }

    @Test
    @DisplayName("onEvent: a throwing hook does not stop the next one")
    void onEventIsolatesAThrow() {
        var seen = new ArrayList<String>();
        AgentHooks boom = new AgentHooks() {
            @Override public void onEvent(AgentEvent e) { throw new IllegalStateException("renderer blew up"); }
        };
        AgentHooks records = new AgentHooks() {
            @Override public void onEvent(AgentEvent e) { seen.add(e.getClass().getSimpleName()); }
        };

        of(boom, records).onEvent(new AgentEvent.RunStart("run-1", -1, NOW));
        assertEquals(List.of("RunStart"), seen);
        assertEquals(List.of("onEvent"), reported);
    }
}
