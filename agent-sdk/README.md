# agent-sdk — a coding-agent harness for Java 26

A small, dependency-free SDK for building coding agents: a **pure turn state machine**, a run engine
that owns the I/O, an event stream with twelve enforced invariants, a tool contract with schema
derivation and validation, hooks for policy, four base tools (`read`, `write`, `edit`, `bash`) and
kon's tiny system prompt — a few lines of base text plus what the workspace adds. Built from scratch
in Java 26 (records, sealed types, pattern matching, virtual threads, `ScopedValue`, sequenced
collections) — no preview features, so any JDK ≥ 26 consumer can use it.

```
agent-sdk/
├── agent-core/      module sdk.agent.core     zero dependencies, zero OS surface
├── agent-tools/     module sdk.agent.tools    read/write/edit/bash + system prompt (AGENTS.md, skills, git)
├── agent-testkit/   module sdk.agent.testkit  ScriptedProvider, RecordingSink, FakeClock, FakeTool
└── agent-mcp/       automatic module          optional: MCP servers as tools (mcp-core 2.0.1, brings Reactor)
```

`agent-mcp` is the only module with third-party dependencies; drop it from the classpath and a host
loses one `.extension(new McpExtension(servers))` line and nothing else.

## Build

Requires JDK 26 and Gradle 9.7 (both are under `../tools`).

```bash
export JAVA_HOME=/c/work.ai/coding_agents/tools/jdk-26.0.2.1+1
/c/work.ai/coding_agents/tools/gradle-9.7.1/bin/gradle -p /c/work.ai/coding_agents/agent-sdk build
```

`build` compiles with `-Xlint:all -Werror`, runs every test, checks that `agent-core` has no runtime
dependency (`checkCorePurity`) and that every jar ships `META-INF/THIRD-PARTY-NOTICES.md`
(`licenseNotices`).

## Five-minute tour

```java
var env = ToolEnvironment.builder(Path.of("/path/to/workspace"))
        .defaultCommandTimeout(Duration.ofMinutes(2))
        .build();

try (var agent = AgentBuilder.create()
        .provider(myLlmProvider)                      // implements sdk.agent.provider.LlmProvider
        .extension(new CodingToolsExtension(env))     // read, bash, edit, write + the system prompt
        .model(new ModelRef("anthropic", "anthropic", "claude-sonnet-5", 200_000, 8_192))
        .limits(RunLimits.DEFAULTS.withMaxTurns(50))
        .build()) {

    agent.subscribe(event -> {
        if (event instanceof AgentEvent.MessageUpdate u) render(u.partial());
    });

    AgentRun run = agent.prompt("Add a unit test for PathPolicy.expand");
    RunResult result = run.result().join();          // completes on every path, never exceptionally
    switch (result.outcome()) {
        case RunOutcome.Completed c     -> System.out.println("done: " + c.reason());
        case RunOutcome.Aborted a       -> System.out.println("aborted");
        case RunOutcome.Failed f        -> System.out.println("failed: " + f.message());
        case RunOutcome.LimitExceeded l -> System.out.println("limit: " + l.limit() + " " + l.detail());
    }
}
```

`agent.steer(msg)` injects a message before the next turn; `agent.followUp(msg)` runs after the
current work finishes; `agent.abort()` cancels and clears both queues; `run.events()` is a
bounded, blocking stream of this run's events; `agent.codec().encode(run.state())` checkpoints a
run and `agent.resume(state)` continues it with fresh collaborators.

## Architecture in one paragraph

`TurnMachine` (`sdk.agent.turn`) is a **pure, total function** `(TurnState, StepInput, Instant) →
StepOutcome`: it accumulates the provider stream into an assistant message, asks for a verdict,
tracks one result slot per tool call, and pads what never ran. `RunEngine` (`sdk.agent`) drives it
one phase-table row per `advance()`, satisfying each `Need` (open a stream, pull a chunk, ask the
hooks, run a tool batch) and returning only at durable checkpoints; `RunState` is a value that
serialises at every boundary, `RunDeps` are the live collaborators supplied fresh each time. Every
tool call leaves the `ToolFunnel` through one exit, so a `ToolStart` always gets its `ToolEnd` and
its `ToolResultMessage`, in assistant source order, whatever went wrong. `Agent` is a thin facade
over one live run, two message queues and a listener fan-out.

## System prompt and commands

The prompt follows kon: a short base text, then only what this workspace adds. Every piece of prompt
text is a markdown template under `agent-tools/src/main/resources/sdk/agent/tools/prompts/`, rendered
through `PromptTemplate` (`${name}` placeholders, a missing value fails loudly). Nothing is cached;
the prompt is built once per run.

| Order | Section | Template | Content |
|---:|---|---|---|
| 0 | base | `system-prompt.md` | kon's default text, or the host's own |
| 10 | tool-usage | `tool-usage.md` | every registered tool's `promptGuidelines()`, duplicates dropped |
| 20 | project-context | `project-context.md` | `AGENTS.md`/`CLAUDE.md` from the git root down to the workspace |
| 30 | skills | `skills.md` | `.agents/skills/*/SKILL.md` in the workspace, its ancestors and `~/.agents/skills` |
| 40 | git-context | `git-context.md` | branch, main branch, `git status --porcelain`, last five commits |
| 50 | env | `env.md` | date and time, workspace root |

A **skill** is a directory holding a `SKILL.md`: frontmatter (`name`, `description`, `register_cmd`,
`cmd_info`) and instructions the model reads when a task matches. With `register_cmd: true` it is
also a **slash command**: `Skills.load(workspace).command("/review PR 68")` returns the user message
to send, with `$ARGUMENTS` filled in (`skill-command.md`). `/init` and `/review` ship built in,
commands only.

`CodingPrompts(env, baseText, gitContext)` swaps the base text or drops the git snapshot;
`AgentBuilder.systemPromptOverride(...)` replaces or appends to the assembled prompt as a whole.

## Extending

An `Extension` declares `Contributions` — tools, tool providers, prompt sections, hooks, message
codecs, optionally a provider — and the builder validates them fail-closed (name collisions,
duplicate section ids, duplicate codecs all fail at `build()` naming both owners).

- **A new tool** implements `Tool<P>` with a parameter record: `@Doc` strings become the JSON
  Schema the model sees, `@Constraint(min/max/minItems)` is emitted *and* enforced, and the tool's
  `promptGuidelines()` appear under `# Tool usage` automatically. Declare `ToolKind.READ_ONLY` to
  be batched concurrently with other read-only calls.
- **A new prompt section** is a `SectionSpec` — id, order, inclusion predicate, renderer — from a
  `PromptContributor`; load its text with `PromptTemplate.read(MyClass.class.getResourceAsStream("x.md"), "x.md")`
  and pick an order between the base pack's (0–50) or after them. A blank render drops the section.
- **Policy** (permissions, sandboxing, loop detection, compaction) is an `AgentHooks`
  implementation. `beforeToolCall` is the permissions seam; `transformContext` is the compaction
  seam (per-request, non-destructive); `beforeRequest` rewrites the request before it is sent;
  `afterAssistant` can `Retry` (reprompt) or `Stop` the run. `TurnGuard` ships as the default
  loop/budget policy and is replaceable with `AgentBuilder.turnGuard(...)`.
- **Custom transcript entries** implement `AgentMessage` and register an `AgentMessageCodec` so
  they survive checkpoint and resume.
- **Another execution backend** (SSH, container): build one `ToolEnvironment` with your
  `ReadOperations`/`WriteOperations`/`BashOperations`/`BashSpawnHook` and every tool follows.

## Guarantees worth knowing

- Twelve event invariants (`RecordingSink.assertInvariants()` in the testkit): `RunStart` first,
  `RunEnd` last on every path including uncaught throws, every message and tool call paired, tool
  results in source order, no event after `RunEnd`.
- A failed turn is a message with `stopReason ∈ {ERROR, ABORTED}`, never an exception; a run's
  outcome is a sealed `RunOutcome`, never a thrown one.
- Cancellation reaches work through the flag, thread interrupt and callbacks at once; five
  checkpoints in the engine observe it; `Fork.close()` can never hang.
- `read` never materialises a whole file; `write` is atomic and preserves BOM/line endings;
  `edit` is all-or-nothing with a hash compare-and-swap; `bash` closes stdin, drains both pipes
  concurrently, kills the process tree with a real SIGTERM grace, and reports true output totals.

## Licence notices

Model-facing strings and the prompt templates are reproduced from MIT-licensed projects; see
`THIRD-PARTY-NOTICES.md` (shipped in every jar).
