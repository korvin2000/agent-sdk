# agent-sdk — a coding-agent harness for Java 26

A small, dependency-free SDK for building coding agents: a **pure turn state machine**, a run engine
that owns the I/O, an event stream with twelve enforced invariants, a tool contract with schema
derivation and validation, hooks for policy, four base tools (`read`, `write`, `edit`, `bash`) and
a short system prompt with workspace instructions (`AGENTS.md`, `CLAUDE.md`).
Built in plain Java 26 — records, sealed types, pattern matching, virtual threads — with
no preview features, so any JDK ≥ 26 consumer can use it.

```
agent-sdk/
├── agent-core/      module sdk.agent.core     zero dependencies, zero OS surface
├── agent-tools/     module sdk.agent.tools    read/write/edit/bash + workspace instructions
├── agent-testkit/   module sdk.agent.testkit  ScriptedProvider, RecordingSink, FakeClock, FakeTool
└── agent-mcp/       automatic module          optional: MCP servers as tools (mcp-core 2.0.1, brings Reactor)
```

`agent-mcp` is the only module with third-party dependencies; drop it from the classpath and a host
loses one `.extension(new McpExtension(servers))` line and nothing else.

**Model transport is not part of this project.** `sdk.agent.provider.LlmProvider` is the seam;
`AnthropicProvider` and `OpenAiProvider` explicitly throw without network I/O. The neutral protocol
carries indexed tool calls/results, thinking signatures/redaction, and opaque
`AssistantMessage.providerData` for complete replay items, IDs and encrypted payloads. Adapters
must map API/model-specific features and negotiate support; `ThinkingLevel` is intent, not a
universal wire mapping. Protocol preservation does not certify provider API interoperability.

## Build

Requires JDK 26. The checked-in wrapper downloads checksum-pinned Gradle 9.7.1.
Point `JAVA_HOME` at your JDK; toolchain auto-download is off. From this directory:

```bash
export JAVA_HOME="$HOME/.cache/agent-sdk-toolchains/jdk-26.0.2.1+1"
PATH="$JAVA_HOME/bin:$PATH" ./gradlew --no-daemon build
```

`build` compiles with `-Xlint:all -Werror`, runs every test, checks that `agent-core` has no runtime
dependency (`checkCorePurity`) and that every jar ships `META-INF/THIRD-PARTY-NOTICES.md`
(`licenseNotices`).

## Five-minute tour

```java
var env = new ToolEnvironment(Path.of("/path/to/workspace"),
        List.of("bash", "-c"), Duration.ofMinutes(2));

try (var agent = AgentBuilder.create()
        .provider(myLlmProvider)                      // implements sdk.agent.provider.LlmProvider
        .extension(new CodingToolsExtension(env))     // read, write, edit, bash + system prompt
        .model(new ModelRef("anthropic", "anthropic", "claude-opus-5", 1_000_000, 128_000))
        .limits(RunLimits.DEFAULTS.withMaxTurns(50))
        .build()) {

    agent.subscribe(event -> {
        if (event instanceof AgentEvent.MessageUpdate u) render(u.partial());
    });

    AgentRun run = agent.prompt("Inspect the project and fix the reported bug");
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
bounded, lossy observation stream (256 events, oldest evicted on overflow). Only its consumer
waits: unread or abandoned events never block run completion. `RunResult` and the transcript are
authoritative. `agent.codec().encode(run.state())` writes schema-3 durable checkpoints;
`agent.resume(state)` supplies fresh collaborators. Schema 2, malformed state, live streaming
accumulators, and a changed tool registry are rejected. Opening, assistant-ready, partially settled
tools, closed turns and finished states can be encoded; a finished run cannot be resumed.

Resume skips durably settled slots and preserves the pending turn's originally advertised tools.
Pending external effects are **at-least-once after a crash**, not exactly-once: an effect may have
happened before its result was saved. Recovery does not automatically retry failed mutating tools.

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

## System prompt

`CodingToolsExtension` contributes four `SectionSpec`s, rendered once per run:

| Order | Section | Content |
|---:|---|---|
| 0 | coding.base | coding-agent role and inspect-before-modify rule |
| 10 | coding.tools | actual registered tools' `promptGuidelines()`, deduplicated |
| 20 | coding.workspace | canonical workspace path and unsandboxed-shell warning |
| 30 | coding.project | workspace `AGENTS.md`, then `CLAUDE.md` |

Project instruction files are strict UTF-8, each bounded to 32 KiB. Missing files are optional;
present unreadable, oversized, or outside-workspace targets fail visibly. No ancestor/home skill
discovery, slash commands, git subprocesses, or prompt-template subsystem is included.
`AgentBuilder.systemPromptOverride(...)` replaces or appends to the assembled prompt.

## The tools

`ToolEnvironment` owns the canonical workspace, an immutable shell prefix, command timeout, and
shared content-version observations. Its one-argument constructor defaults to `List.of("bash", "-c")`
and 60 seconds. Both classpath and module-path ServiceLoader discovery are supported.

- `read(path, offset?, limit?)` returns an exact line window (one-based offset, default 2000 lines),
  excluding a leading BOM from display. Text output is capped at 64 KiB with continuation guidance.
  PNG/JPEG/GIF/WebP magic bytes produce an image block; images reject explicit line options.
- `write(path, content)` writes exactly the supplied UTF-8; it does not normalize BOMs or newlines.
- `edit(path, edits)` applies unique, nonoverlapping exact replacements against the original file.
  Missing or ambiguous matches change nothing; inserted text is never rematched.
- `bash(command)` runs in the workspace with stdin closed, drains merged stdout/stderr concurrently,
  retains a 64 KiB tail, and reports exit status and total bytes. Nonzero exit is an error.
  Timeout/cancellation requests termination, waits a one-second grace, then force-kills surviving
  captured process handles. It does not spill unbounded output to disk.

File input/output is bounded to 8 MiB, images to 5 MiB. Existing targets require a successful read
and an unchanged raw-byte digest before edit/overwrite; the observation cache holds 4096 paths.
Replacements use a sibling temporary file and atomic move, preserving POSIX permissions where
supported. Unsupported atomic replacement returns `UNAVAILABLE`; new writes never replace an
existing destination. Expected refusals return typed error results; genuine I/O errors propagate
to the tool funnel.

Filesystem tools reject workspace escapes and writes through `.git`, `.hg`, or `.svn`.
Internal symlinks are allowed only when the real target stays inside the workspace. These checks
are not an OS sandbox or atomic compare-and-swap against external writers. Shell execution is
**unsandboxed**; hosts must authorize commands and provide any required OS isolation.

## Extending

An `Extension` declares `Contributions` — tools, tool providers, prompt sections, hooks, message
codecs, optionally a provider — and the builder validates them fail-closed (name collisions,
duplicate section ids, duplicate codecs all fail at `build()` naming both owners).

- **A new tool** implements `Tool<P>` with a parameter record: `@Doc` strings become the JSON
  Schema the model sees, `@Constraint(min/max/minItems)` is emitted *and* enforced, and the tool's
  `promptGuidelines()` appear under `# Tool usage` automatically. Declare `ToolKind.READ_ONLY` to
  be batched concurrently with other read-only calls.
- **A new prompt section** is a `SectionSpec` — id, order, inclusion predicate, renderer — from a
  `PromptContributor`. Pick an order between the base pack's (0–30) or after them.
  A blank render drops the section.
- **Policy** (permissions, sandboxing, loop detection, compaction) is an `AgentHooks`
  implementation. `beforeToolCall` is the permissions seam — it may block a call or rewrite its
  arguments (a sandbox prefix on a `bash` command lives here); `transformContext` is the compaction
  seam (per-request, non-destructive); `beforeRequest` rewrites the request before it is sent;
  `afterAssistant` can `Retry` (reprompt) or `Stop` the run. Decision failures stop the affected
  operation; output-filter failures never expose the unfiltered result. Only event observers
  are isolated. `TurnGuard` checks repeated identical batches and empty/truncated responses;
  replacing or disabling it does not disable engine turn, tool-call, or wall-clock budgets.
- **Custom transcript entries** implement `AgentMessage` and register an `AgentMessageCodec` so
  they survive checkpoint and resume.
- **A dynamic tool source** implements `ToolProvider` and returns a new list from `tools()`
  whenever its set changes; the next run registers it.

## Guarantees worth knowing

- Twelve event invariants on the full event sink (`RecordingSink.assertInvariants()` in the
  testkit): `RunStart` first, `RunEnd` last on normal and handled-failure paths, every message and
  tool call paired, results in source order, no event after `RunEnd`. A lossy pull stream can
  omit earlier framing events; it retains the final `RunEnd` unless observation is abandoned.
- A failed turn is a message with `stopReason ∈ {ERROR, ABORTED}`, never an exception; a run's
  outcome is a sealed `RunOutcome`, never a thrown one.
- Cancellation and wall-clock deadlines interrupt blocked provider opening/reads and owned tool
  work. Abandoned tools cannot publish late progress. Scope shutdown has a bounded grace;
  interrupt-ignoring provider/tool code may leave a logged abandoned virtual thread.
- Hooks, listeners, and extension callbacks must cooperate with interruption and return: Java
  cannot safely terminate arbitrary user code. Closing an agent is idempotent, rejects new runs
  and control operations, and closes extensions in reverse order.
- MCP discovery publishes complete snapshots only: at most 100 pages within one configured
  request timeout, with repeated cursors rejected. List-change notifications coalesce into
  serial refreshes; failed refreshes retain the previous catalog.
- MCP structured results are visible to the model as JSON, including structured-only responses.
  HTTP configuration preserves endpoint paths, queries, and explicit authentication headers.
- MCP close detaches the catalog and cancels owned work before bounded SDK cleanup. Late
  successful connection attempts are closed rather than published. The upstream stdio transport
  requests termination but cannot guarantee force-killing a hostile child.

## Licence notices

Model-facing strings and the prompt templates are reproduced from MIT-licensed projects; see
`THIRD-PARTY-NOTICES.md` (shipped in every jar).
