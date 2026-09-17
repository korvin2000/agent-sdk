# Stack-specific traps

These are the places where plausible-looking code is silently wrong. Verify version-sensitive
behaviour against primary documentation for the version actually installed — not from memory.

## The trap that costs the most: cross-runtime protocols

**Godot's high-level multiplayer protocol is documented as an implementation detail not intended for
non-Godot servers, and may change without notice.** Do not write a Spring or Node WebSocket handler
and assume it speaks Godot scene RPC. Two valid paths:

- **A — Godot client + headless Godot simulation.** Use the engine's multiplayer features, and give
  the backend a separate, explicit public contract.
- **B — Godot client + Java/Node simulation.** Define your own application protocol: serialization,
  message validation, authority, ordering, reconnection, and version negotiation, all deliberate.

A shared transport does not imply shared application semantics. The first experiment worth running
exchanges one versioned message between the **actually exported** client and the **actual** candidate
server, including a malformed payload and a disconnect. Test web and mobile export targets
separately when they matter.

**A protocol is more than a DTO.** Specify: message identity and version · ordering guarantees ·
duplicate handling · retry safety · maximum size · authority (who may send this) · what happens on
malformed input · what happens under overload · how an old client is rejected or upgraded.

## Java / Spring

- Records for value objects and DTOs; sealed interfaces plus pattern-matching switch for domain
  states, so adding a state breaks compilation at every place that must change.
- Constructor injection only. No field injection, no service locator.
- `@Transactional` at the application-service boundary, never inside pure logic. Know that it is
  proxy-based: a self-invocation inside the same bean does not open a transaction, and neither does a
  call from a `private` method — a very common silently-wrong result.
- Never let a persistence entity escape into the API or the domain core. Lazy loading outside the
  transaction is where that mistake surfaces, as an exception in an unrelated layer.
- One module per capability with an automated module-boundary verification in the test suite.
- Virtual threads and `StructuredTaskScope` for concurrency; no unmanaged executors.
- Uniqueness and monetary invariants belong in database constraints, not only in service code.

## Node.js / TypeScript

- `strict: true`, plus `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`. `any` banned at
  error level; `unknown` plus a parser at every boundary.
- **One definition, not two:** validate at the boundary with a schema library and *derive* the type
  from the schema. A hand-written interface next to a validator will drift.
- No floating promises — lint-enforced. An unhandled rejection is a lost error.
- Thread `AbortSignal` through every async operation, including the ones you think are fast.
- ESM with explicit named exports.
- The real-time edge holds **no game rules**. If a rule appears there, it belongs in the shared core.
- Remember what is single-threaded: a CPU-bound loop blocks every connection on that process.

## React / Angular

- **State ownership first.** Every piece of UI state has one owner. Derive, do not duplicate: a value
  computed from server data is not a second source of truth. Server state, URL state, form state, and
  ephemeral UI state are four different things with four different lifetimes.
- Unidirectional flow: state → view → event → new state. Effects live at the edge, not inside
  rendering logic.
- Model multi-step flows as an explicit state machine, not as a set of booleans. `isLoading` plus
  `isError` plus `isEmpty` admits combinations that must never happen.
- Use the **generated** API client. Hand-written fetch wrappers duplicating the contract will drift.
- React: function components; no logic in JSX; memoize only against a measurement.
- Angular: standalone components, signals or one store pattern, `OnPush` change detection, no logic
  in templates.
- Accessibility as a lint gate, not a later pass.

## Godot / GDScript

- Typed GDScript or C#, explicit `class_name`. Untyped GDScript loses the cheapest verification rung
  you have.
- **No `get_node("../../..")` path climbing.** It couples a script to a scene layout that will move,
  and it fails at runtime rather than at build time. Use exported node references or signals.
- Composition through child nodes and resources; deep node inheritance is prohibited.
- Resources (`.tres`) for declarative content. Never hard-code balance numbers in scripts — a balance
  change must not require a code change and a redeploy.
- Deterministic paths use the shared simulation core, never engine randomness or wall-clock time.
- `_process` is frame-rate dependent; `_physics_process` is the fixed-timestep one. Game logic that
  reads frame delta is non-deterministic by construction.

## Which rungs carry the weight where

| Component | Verification that actually catches things |
|---|---|
| Simulation core | types → unit → **property** → replay/golden → cross-platform determinism |
| Spring services | types → module/architecture checks → unit over pure logic → slice tests → contract tests |
| Node edge | strict types → boundary lint → unit → integration over a real socket → load test |
| Godot | typed scripts → unit over pure scripts → headless integration → replay against the core |
| Web client | types → boundary lint → component tests → contract tests against the generated client → a handful of e2e journeys |
| Content and plugins | schema validation → referential integrity → balance-bound checks → simulated-play smoke run |
