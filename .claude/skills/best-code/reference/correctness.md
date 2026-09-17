# Correctness

Your reasoning is not evidence. What makes agentic work trustworthy is a dense field of
deterministic signals you cannot edit away.

## Verification ladder

**Law:** push every check to the cheapest rung that can catch it, and never climb a rung while a
lower one is red.

| Rung | Latency | Catches |
|---|---|---|
| 0 Compiler / type-check | seconds | shape, nullability, exhaustiveness, units — design so this rung catches the most |
| 1 Lint + format + architecture checks | seconds | conventions, boundary violations, cycles, banned constructs |
| 2 Unit tests over the pure core | seconds | logic, edge cases |
| 3 Property tests | seconds–minutes | invariants, round-trips, whole input classes |
| 4 Contract tests | seconds | cross-runtime drift |
| 5 Integration tests | minutes | wiring, persistence, transactions — few, over real seams only |
| 6 Simulation / replay / soak | minutes | emergent, temporal, concurrency behaviour |
| 7 End-to-end | minutes | critical journeys only; keep the set tiny and stable |
| 8 Observability | continuous | what tests cannot foresee |

Rungs 0–3 locally in **under 5 minutes**. Feedback latency is the ceiling on quality: a slow signal
means three more things get built on a broken foundation before the failure is learned.

**A flaky test is worse than no test** — it teaches that failure is noise, which trains re-running
instead of diagnosing. Quarantine on first flake; fix or delete. Never re-run to get green.

When a bug reaches a high rung, ask which lower rung should have caught it and add that check. That
is how the ladder gets stronger.

**Stop when the evidence matches the risk.** More checks past that point are cost, not diligence.

## Types first: make illegal states unrepresentable

The type system is the cheapest verifier — instant, exhaustive, no flakiness. Effort spent here
shortens every later rung.

- **Parse, don't validate.** Convert unstructured input into a precise type once, at the boundary.
  Past that boundary the type carries the guarantee and nothing re-checks it.
- **No primitive obsession at domain boundaries.** `PlayerId`, `MatchId`, `Elo`, `Milliseconds`,
  `Seed` — not `string` and `number`. This one rule removes the argument-swap bug class, which
  machine-written code produces disproportionately often.
- **Sum types for state.** `Queued | InProgress | Finished | Abandoned` with per-state payloads, not
  one struct of nullable fields plus a status string. Illegal combinations stop compiling.
- **Enforce exhaustiveness.** Discriminated union + `never` in the default branch; sealed interface +
  pattern-matching switch. This turns "someone added a state and forgot a place" into a compile
  error — precisely the failure that extending a system produces.
- **Make invalid construction impossible:** private constructor plus a smart constructor returning a
  result; non-empty list types; ranges as refined types.
- **Immutable by default;** mutation is explicit, local, and named.
- **`null` is a design smell at domain level.** Use an option type or an explicit union. Strict null
  checking on.
- **Static types do not replace runtime validation, authorization, or concurrency control.** Never
  silence a type error with a cast; a cast needs an inline reason.

## Contracts at boundaries

Every boundary between two runtimes, two teams, or two release cadences gets a written, versioned,
**generated** contract.

- The contract is the single source of truth and all sides are generated from it. Hand-written
  duplicate type definitions on both sides guarantee invisible drift.
- HTTP → OpenAPI · async/eventing → AsyncAPI or a schema registry · binary/realtime → protobuf or
  FlatBuffers · persisted data → an explicit versioned schema with migrations.
- **Backwards compatibility is a one-way door.** Additive is free; removal and rename need a
  deprecation window, a version bump, and a recorded decision.
- **Contract tests both ways:** the provider verifies it satisfies the contract, each consumer
  verifies it uses only what the contract promises. In CI, on every contract change.
- **In code:** preconditions as guard clauses that fail loudly; postconditions and invariants as
  assertions in the core. An assertion is documentation the machine checks.
- For each important invariant, know **its owning module and the check that can falsify it**. An
  invariant with no owner and no check is a wish.

## Test doctrine

- **Tests are the specification.** They define done; they do not raise a number.
- **Write the test first for anything with real logic** — domain rules, algorithms, state machines,
  parsing, calculations. Red first: a test that has never failed has never been shown to test
  anything. Routine wiring does not need this ceremony.
- **Never edit, weaken, skip, or delete a test to make a build pass.** If a test is genuinely wrong,
  that is a separate, explicitly stated change. This is load-bearing: the moment the implementer can
  move the goalposts, no other guarantee survives.
- **Test through the public interface.** Tests coupled to internals block exactly the refactoring
  that agentic codebases already do too little of.
- **Derive expectations from the requirement, not from the implementation.** A test written by
  reading the code you just wrote confirms your bug. Oracles stay independent.
- **Every test must be able to fail for exactly one reason.** Assertion-free tests, tautologies, and
  tests that only exercise mocks are defects — they turn the suite into theatre.
- **Test the pure core exhaustively, the shell thinly.** Most risk lives where testing is cheapest.
- **Prefer real objects to mocks.** Mock only at genuine I/O seams: network, clock, filesystem, RNG,
  database. Over-mocking produces suites that pass while the system is broken.
- **Every bug fix starts with a failing regression test** that reproduces it.
- **Property tests where properties exist:** round-trip (`decode(encode(x)) == x`), idempotence,
  conservation (total currency unchanged), ordering, commutativity, determinism. Models are reliably
  better at *stating* properties than at writing correct implementations — use that asymmetry.
- **Mutation testing as a scheduled audit** of critical modules — the practical way to find tests
  that assert nothing.
- **Coverage is a floor, never a goal.** A ratchet that forbids decrease is useful; a target that
  rewards increase produces junk tests.

## Error model

Machine-written code shows a measurable rise in error-masking constructs. Treat error handling as
design, not cleanup.

- **Expected failures are values; unexpected failures are exceptions.** Validation failure, not
  found, insufficient funds, already queued → a result or sealed error type *in the signature*.
  Bugs, invariant violations, unavailable infrastructure → throw and propagate.
- **Never swallow.** No empty catch, no `catch { return null }`, no logging-and-continuing past a
  broken invariant, no bare `except`.
- **Catch narrowly, where something can actually be decided.** Catching at the top and logging is
  not error handling; it is error hiding.
- **Errors carry context:** which operation, which identifiers, what was expected, what happened,
  and the cause chain. The audience is whoever debugs this next, with no context.
- **Fail fast on programmer error; degrade gracefully on environmental error.** Never the reverse.
- **Never invent a fallback value to keep going.** A silently wrong number that flows into an economy
  or a leaderboard costs far more than a crash.
- **Fix causes, not symptoms.** Suppressing the signal is the most expensive possible repair.

## Observability is the last rung

- **Structured events, not string logs:** `log.info({event: "match.started", matchId, playerCount})`.
  Structured output is greppable, assertable, and machine-readable — a later session can debug from
  logs instead of guessing.
- **Correlation identifiers** across client → gateway → service → simulation. Without them a
  distributed bug is unreconstructable.
- **Log decisions, not narration.** Log where the system chose between paths, plus every rejected
  invariant.
- **Never log secrets, tokens, or personal data.** Redact in the logger, not at the call sites — a
  rule enforced in one place cannot be forgotten in another.
- **A budget without a metric is folklore.** Anything with a stated limit gets a measurement.
