# Effects, concurrency, and determinism

## Make effects visible

Keep calculation and policy separately testable from I/O. The shape that pays for itself:
**a pure core with an imperative shell** — decisions are pure functions of explicit inputs; the
shell does I/O, transactions, and time. Most risk then lives where testing is cheapest.

I/O, time, randomness, mutation, and transactions are explicit in the signature, never ambient.

## Concurrency

- **Prefer none.** The fastest correct code is often a single-threaded loop over a partition of the
  data. Concurrency is a complexity purchase; require a reason.
- **One owner per piece of mutable state.** For a game server: one room or match = one owner,
  messages in, snapshots out. No shared mutable state across rooms.
- **Structured concurrency always.** Every task, timer, subscription, and resource has an owner, a
  parent scope, a deadline, a cancellation path, and a cleanup. Java 21+ `StructuredTaskScope` and
  virtual threads; `AbortSignal` and explicit task scopes in Node.
- **Never fire-and-forget.** An un-awaited promise or an unmanaged thread is a leak plus a lost
  error, and it will surface as an unexplained failure somewhere else.
- **Cancellation is part of the contract.** Every long-running operation accepts and honours it, and
  releases what it holds on the way out.
- **Bound every queue and every pool.** Unbounded buffering converts a load spike into an
  out-of-memory kill. State the shed policy: drop oldest, drop newest, reject, or block.
- **Make concurrency testable:** injectable clock, deterministic scheduler in tests, seeded
  interleavings. A concurrency bug you cannot reproduce is one you cannot fix.
- **Model non-trivial protocols as explicit state machines** and test the transition table
  exhaustively. Reach for formal specification only when the state space defeats testing.

## Retries, duplicates, and durable effects

Retry without idempotency creates duplicates, and a duplicate is a data-integrity bug, not a
reliability feature. For every remote or durable operation:

- **Timeout, bounded retries, exponential backoff with jitter.** Retry only what is safe to retry.
- **An identity per retryable command**, supplied by the caller, persisted with the effect. The
  uniqueness constraint lives in the database, not in application logic that "checks first" — a
  check-then-insert under concurrency is a race, not a guard.
- **Decide and record the semantics you actually provide**: at-most-once, at-least-once plus
  idempotent handling, or effectively-once via a dedup table. Write down which one.
- **Prove it with a test that replays the same command twice** and asserts one durable effect.
  Concurrently, if the store allows it.
- **Multi-step effects that must all succeed or all undo** get a saga or a durable workflow with
  explicit compensation — not nested try/catch with manual rollback.
- **Order, duplicates, and overload behaviour are part of an async contract**, alongside the payload
  schema. A DTO is not a protocol.

## Determinism as an architectural asset

Worth designing for on its own merits, and for a simulation it pays three times over: testability,
replay and rollback, and reproducible bug reports.

- **All non-determinism is injected, never ambient.** Clock, RNG, UUID generation, network,
  filesystem, environment enter through the shell as explicit dependencies. `Date.now()` and
  `Math.random()` inside core logic are defects.
- **Seeded RNG:** one named generator per simulation, the seed stored with the record. Same seed +
  same inputs = same outcome.
- **The simulation step is a pure function:** `step(state, inputs, dt) -> state`. No I/O, no globals,
  no wall-clock reads.
- **Fixed timestep** for simulation; interpolate for rendering only. Never drive game logic from
  frame delta.
- **Avoid floating point in cross-platform deterministic paths.** Use fixed-point or integer math
  where bit-exactness across runtimes matters — the same expression does not necessarily produce the
  same bits in two engines.
- **State is serializable and snapshot-able.** This is the precondition for rollback, replay, save
  games, migration testing, and cheap golden tests.
- **Replay tests are integration tests that cost almost nothing:** record inputs from a real
  session, re-run the core, assert the same final state. Add one for every serious gameplay bug.

## Authority

Anything a client can compute, a client can lie about. The server owns state that affects other
players, currency, progression, or ranking; the client predicts and displays. Client-side authority
is one class of cheating; rules duplicated in client and server is another, because they will drift
and the drift is a gameplay bug. One shared core, generated bindings.
