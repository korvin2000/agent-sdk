# agent-sdk — a coding-agent harness for Java 26

A small, dependency-free SDK for building coding agents: a **pure turn state machine**, a run engine
that owns the I/O, an event stream with twelve enforced invariants, a tool contract with schema
derivation and validation, hooks for policy, four base tools (`read`, `write`, `edit`, `bash`) and
a short system prompt that grows only with what the workspace adds (`AGENTS.md`, skills, git
status). Built in plain Java 26 — records, sealed types, pattern matching, virtual threads — with
no preview features, so any JDK ≥ 26 consumer can use it.

```
agent-sdk/
├── agent-core/      module sdk.agent.core     zero dependencies, zero OS surface
├── agent-tools/     module sdk.agent.tools    read/write/edit/bash + system prompt (AGENTS.md, skills, git)
├── agent-testkit/   module sdk.agent.testkit  ScriptedProvider, RecordingSink, FakeClock, FakeTool
└── agent-mcp/       automatic module          optional: MCP servers as tools (mcp-core 2.0.1, brings Reactor)
```

`agent-mcp` is the only module with third-party dependencies; drop it from the classpath and a host
loses one `.extension(new McpExtension(servers))` line and nothing else.

**The connection to a model is not part of this project.** `sdk.agent.provider.LlmProvider` is the
seam; `AnthropicProvider` and `OpenAiProvider` are empty placeholders whose documentation says how
the core protocol maps onto each API. The protocol is provider-neutral by construction: tool calls
stream as `(id, name)` plus argument fragments, reasoning blocks carry an opaque `signature`, and
`ThinkingLevel` and `StopReason` map one to one onto both APIs' effort levels and finish reasons.

## Build

Requires JDK 26 and Gradle 9.7 (both are under `../tools`). Point `JAVA_HOME` at the JDK; Gradle
picks it up as the current JVM (toolchain auto-download is off).

```bash
export JAVA_HOME=/c/work.ai/agent-sdk/tools/jdk-26.0.2.1+1
/c/work.ai/agent-sdk/tools/gradle-9.7.1/bin/gradle -p /c/work.ai/agent-sdk/agent-sdk build
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
        .model(new ModelRef("anthropic", "anthropic", "claude-opus-5", 1_000_000, 128_000))
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
bounded, **lossy** stream of this run's events (the newest 256 are kept; a consumer that stops
reading never stalls the run — `run.result()` and the transcript are authoritative);
`agent.codec().encode(run.state())` checkpoints a run (schema 3, strict on decode) and
`agent.resume(state)` continues it with fresh collaborators, skipping the tool calls that already
settled and keeping the turn's originally advertised tools. `agent.close()` is idempotent; a
closed agent refuses new runs.

## Architecture in one paragraph

`TurnMachine` (`sdk.agent.turn`) is a **pure, total function** `(TurnState, StepInput, Instant) →
StepOutcome`: it accumulates the provider stream into an assistant message, asks for a verdict,
tracks one result slot per tool call, and pads what never ran. `RunEngine` (`sdk.agent`) drives it
one phase-table row per `advance()`, satisfying each `Need` (open a stream, pull a chunk, ask the
hooks, run a tool batch) and returning only at durable checkpoints; `RunState` is a value that
serialises at every boundary, `RunDeps` are the live collaborators supplied fresh each time. Every
tool call leaves the `ToolFunnel` through one exit, so a `ToolStart` always gets its `ToolEnd` and
its `ToolResultMessage`, in assistant source order, whatever went wrong. `Agent` is a thin facade
over one live run, two message queues and a listener fan-out; it rebuilds the `ToolRegistry` from
the extensions' `ToolProvider`s at every run start, which is how a dynamic source such as MCP
`tools/list_changed` reaches the model.

## System prompt and commands

A short base text, then only what this workspace adds. Every piece of prompt text is a markdown
template under `agent-tools/src/main/resources/sdk/agent/tools/prompts/`, rendered through
`PromptTemplate` (`${name}` placeholders, a missing value fails loudly). Nothing is cached; the
prompt is built once per run.

| Order | Section | Template | Content |
|---:|---|---|---|
| 0 | base | `system-prompt.md` | the default text, or the host's own |
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

## The tools

`ToolEnvironment` is the one object the four tools share: workspace root and `PathPolicy`
(containment plus a write deny-set for `.git`/`.hg`/`.svn`), the session's `FileVersions`, the
shell (discovered lazily — Git Bash locations and `PATH` on Windows, rejecting the WSL launcher in
`System32`; `/bin/bash` then `PATH` then `sh` elsewhere — or supplied), output limits, and two
switches: `lineNumbers` on `read` (off by default so `edit` gets byte-exact text) and
`readBeforeOverwrite` on `write` (on by default: a lost-update guard, not a permission check).

- `read` streams one pass to EOF, materialising only the requested window while counting the whole
  file; an image (png, jpeg, gif, webp, sniffed by magic number) comes back as one image block.
- `write` is atomic and adopts the existing file's BOM and line endings.
- `edit` applies many exact replacements all-or-nothing against the original text, echoes the
  changed region with line numbers, and refuses with a compare-and-swap if the file changed on disk
  since the session read it (the session carries the version — the model never does).
- `bash` closes stdin, drains both pipes concurrently, frames `EXIT_CODE`/`STDERR`/`STDOUT`,
  tail-truncates with the true totals, spills the full output to a temp file, and kills the process
  tree with a real SIGTERM grace on timeout or abort.

A refusal on the way to the model is a `ToolException` (unchecked, message-only); the funnel turns
any `ToolFailure` into an error result with its `ErrorKind`.

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
  implementation. `beforeToolCall` is the permissions seam — it may block a call or rewrite its
  arguments (a sandbox prefix on a `bash` command lives here); `transformContext` is the compaction
  seam (per-request, non-destructive); `beforeRequest` rewrites the request before it is sent — a
  tool it strips cannot be executed that turn; `afterAssistant` can `Retry` (reprompt) or `Stop`
  the run. Hooks **fail closed**: a throwing `beforeToolCall`/`afterToolCall` becomes that call's
  `HOOK_FAILED` result (never `ALLOW`, never the unfiltered output), any other throwing hook ends
  the run as `Failed`; only `onEvent` is isolated. `TurnGuard` ships as the default loop and
  response-quality policy (repeated batches, a dominant call, empty and truncated turns) and is
  replaceable with `AgentBuilder.turnGuard(...)`; the turn, tool-call and wall-clock budgets are
  the engine's own and stay enforced without it.
- **Custom transcript entries** implement `AgentMessage` and register an `AgentMessageCodec` so
  they survive checkpoint and resume.
- **A dynamic tool source** implements `ToolProvider` and returns a new list from `tools()`
  whenever its set changes; the next run registers it.

## Guarantees worth knowing

- Twelve event invariants (`RecordingSink.assertInvariants()` in the testkit): `RunStart` first,
  `RunEnd` last on every path including a throwing hook, every message and tool call paired, one
  result per tool call in source order — a call that never ran (a `Stop`/`Retry` verdict, a
  truncated or failed turn, an abort) is padded with `action was not executed`, so the transcript
  is always valid for the next request — no event after `RunEnd`.
- A failed turn is a message with `stopReason ∈ {ERROR, ABORTED}`, never an exception, and never
  executes the calls it collected; a stalled stream is a failed turn; a run's outcome is a sealed
  `RunOutcome`, never a thrown one.
- Cancellation reaches work through the flag, thread interrupt and callbacks at once: every
  checkpoint observes it and, while a row runs, the driver thread is interrupted wherever it
  blocks — a provider that never answers, a hook that never returns, a tool that never finishes.
  A `wallClock` limit arms a deadline timer that cancels the run the same way and reports
  `WALL_CLOCK`. An abandoned tool can publish nothing after its `ToolEnd`; `Fork.close()` can
  never hang; `agent.abort()` never blocks on a child process.
- MCP: structured results reach the model as JSON even without text content; `tools/list` is
  paginated with a page cap and cursor-cycle detection, and a partial catalog is never published;
  a connection that completes after the connect budget, or a catalog collision at start-up,
  closes its child process instead of leaking it.

## Licence notices

Model-facing strings and the prompt templates are reproduced from MIT-licensed projects; see
`THIRD-PARTY-NOTICES.md` (shipped in every jar).
