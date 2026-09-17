package sdk.agent.testkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;

import sdk.agent.MessageQueue;
import sdk.agent.RunDeps;
import sdk.agent.RunEngine;
import sdk.agent.RunLimits;
import sdk.agent.Phase;
import sdk.agent.RunResult;
import sdk.agent.RunState;
import sdk.agent.Step;
import sdk.agent.ToolExecutionMode;
import sdk.agent.concurrent.Cancellation;
import sdk.agent.event.RunOutcome;
import sdk.agent.hook.AgentHooks;
import sdk.agent.json.Json;
import sdk.agent.message.AgentMessage;
import sdk.agent.message.MessageConverter;
import sdk.agent.message.ModelRef;
import sdk.agent.message.UserMessage;
import sdk.agent.provider.LlmProvider;
import sdk.agent.provider.LlmRequest;
import sdk.agent.provider.ThinkingLevel;
import sdk.agent.tool.Tool;
import sdk.agent.tool.ToolRegistry;
import sdk.agent.turn.TurnMachine;

/// Drives [RunEngine] directly: synchronous, single-threaded and fully determined by the script,
/// which is what makes an exact event trace assertable. The [sdk.agent.Agent] facade is exercised
/// separately, where its own concurrency is the subject.
final class Rig {

    static final ModelRef MODEL = new ModelRef("test", "scripted", "scripted-1", 200_000, 8_000);

    LlmProvider provider = ScriptedProvider.simpleText();
    final List<Tool<?>> tools = new ArrayList<>();
    AgentHooks hooks = AgentHooks.NONE;
    MessageConverter converter = MessageConverter.DEFAULT;
    RunLimits limits = RunLimits.DEFAULTS.withMaxTurns(20);
    Duration idleTimeout = Duration.ofMillis(150);
    final FakeClock clock = FakeClock.atEpoch();
    final Cancellation cancel = Cancellation.create();
    final MessageQueue steering = new MessageQueue();
    final MessageQueue followUps = new MessageQueue();
    final RecordingSink sink = new RecordingSink();
    Supplier<List<AgentMessage>> steeringSupplier = null;

    Rig provider(LlmProvider p)          { provider = p; return this; }
    Rig tools(Tool<?>... t)              { tools.addAll(List.of(t)); return this; }
    Rig hooks(AgentHooks h)              { hooks = h; return this; }
    Rig limits(RunLimits l)              { limits = l; return this; }
    Rig sequential()                     { limits = limits.withToolExecution(ToolExecutionMode.SEQUENTIAL); return this; }
    Rig idleTimeout(Duration d)          { idleTimeout = d; return this; }

    ToolRegistry registry() { return tools.isEmpty() ? ToolRegistry.EMPTY : ToolRegistry.ofTools("test", tools); }

    RunDeps deps() {
        var template = new LlmRequest(MODEL, "system prompt", List.of(), List.of(),
                ThinkingLevel.OFF, OptionalInt.empty(), Json.Obj.EMPTY);
        return new RunDeps(provider, registry(), hooks, converter, sink, cancel,
                steeringSupplier != null ? steeringSupplier : steering::drain, followUps::drain, template, clock);
    }

    RunState initial(String prompt) {
        return RunEngine.start(List.of(UserMessage.text(prompt, clock.instant())), List.of(),
                limits, registry().hash(), "run-1", clock.instant());
    }

    RunEngine engine() { return new RunEngine(new TurnMachine(idleTimeout)); }

    /// Runs to completion on the calling thread.
    RunResult run(String prompt) { return engine().run(initial(prompt), deps()); }

    RunOutcome outcome(String prompt) { return run(prompt).outcome(); }

    List<String> trace() { return sink.trace(); }

    /// One `advance()` at a time, so a checkpoint can be tripped at an exact row.
    Driver driver(String prompt) { return new Driver(engine(), deps(), initial(prompt)); }

    static final class Driver {
        private final RunEngine engine;
        private final RunDeps deps;
        private Step step;

        private Driver(RunEngine engine, RunDeps deps, RunState initial) {
            this.engine = engine;
            this.deps = deps;
            this.step = new Step.Continue(initial);
        }

        RunState state() { return step.state(); }

        /// Advances until the run is about to perform `phase`'s row, failing if the run ends first.
        Driver until(Phase phase) {
            for (int guard = 0; guard < 200; guard++) {
                if (step.state().phase() == phase) return this;
                if (step instanceof Step.Done) throw new AssertionError("run finished before reaching " + phase);
                step = engine.advance(step.state(), deps);
            }
            throw new AssertionError("no " + phase + " row within 200 advances");
        }

        Driver one() { step = engine.advance(step.state(), deps); return this; }

        RunResult finish() {
            for (int guard = 0; guard < 200; guard++) {
                if (step instanceof Step.Done done) return new RunResult(done.state(), done.outcome());
                step = engine.advance(step.state(), deps);
            }
            throw new AssertionError("run did not finish within 200 advances");
        }
    }
}
