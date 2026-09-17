# Architecture, boundaries, and evolution

## Decide the runtime shape before choosing patterns

Answer these before drawing modules. Each answer eliminates whole families of design:

- What state is **authoritative**, and where does it live?
- What must survive a **restart**, and what may be lost?
- What is **real-time** and what is request/response?
- Who may **read** and who may **write** each piece of state?
- What is expected to be **extended**, and by whom — you, another team, or a third party?
- What is the **compatibility obligation** (clients in the wild, saved data, persisted schemas)?

Start as a **modular monolith with enforced module boundaries**, plus a separate real-time process
if the workload needs one. Modules first, processes later: you can split a well-bounded module into
a service, but you cannot un-distribute a system cheaply. Distribution buys independent scaling and
deployment; it costs you a network in every call path, partial failure, and eventual consistency.
Do not introduce it without a concrete need you can name.

**Not required by default:** Kubernetes, a service mesh, Kafka, a workflow engine, Redis, an actor
cluster, a custom ECS, a universal DI container, event sourcing, CQRS, a plugin marketplace,
in-process hot reload. Each may become right; adding one must solve an identified problem at an
acceptable operational and migration cost.

## The contract layer is the first thing to read and the first thing to change

Put every cross-boundary agreement in one place, versioned, and generate all sides from it:

```
/contracts/
  openapi/    REST      asyncapi/   events and realtime messages
  proto/      wire      schemas/    content, save data, config, balance
  CHANGELOG.md          every change, with its compatibility note
```

- **Generated code is never hand-edited** and is regenerated in CI; a diff after regeneration fails
  the build. That check is what makes drift impossible rather than unlikely.
- **Wire format, persistence entity, and domain model are three different types.** Letting a
  protobuf message or a database row become the domain model couples your rules to your transport.
- **A contract change is always a declared blast radius** and regenerates every consumer in the same
  change.
- **Different workloads get different representations of the same concept.** The persistent account
  model need not be the scene tree, the ECS component layout, or the network snapshot. That is not
  duplication; it is a boundary doing its job.

## Extension ladder — climb one rung at a time, and only on evidence

| Rung | Add when | Mechanism | Evidence it works |
|---|---|---|---|
| 1 Data / configuration | new values or combinations of existing capabilities | validated definitions with stable IDs | invalid content rejected; references resolved; old content handled deliberately |
| 2 Explicit strategy | a real behavioural variation exists | a function or object behind a narrow capability | the real variants pass one shared behavioural contract |
| 3 Registry | several independently selected contributions need discovery | explicit registration at startup | duplicate IDs, missing dependencies, and deterministic selection all tested |
| 4 Trusted plugins | separate trusted packages contribute independently | versioned plugin API with a lifecycle | init, teardown, compatibility, failure isolation, upgrade |
| 5 Untrusted extensions | the product explicitly supports third-party code | sandbox, separate process, or WASM chosen for the actual threat model | capability limits, CPU/memory/time limits, storage and network policy, abuse tests |

Configuration is not automatically safe: a content file can cause pathological allocation, a
quadratic algorithm, unauthorized resource loading, or code execution through an unsafe importer.
Validate references and resource budgets, not only shape. For executable extensions, decide
explicitly whether a failure disables the plugin, fails the session, or prevents startup.

## Repository layout that survives a context reset

Organize the top level by **capability and runtime**, not by technical layer. Every top-level
directory gets a ~10-line `README.md`: purpose, public API, invariants it maintains, and the mistake
newcomers make. Those readmes are the highest-value tokens in the repository.

The deterministic core (rules, entities, simulation) imports no framework, no network, no
filesystem, no clock, and no RNG — enforced by an import-restriction check, not by discipline.

## Ownership: hierarchy for people, DAG for dependencies

Four different graphs get confused with each other. Keep them apart:

| Graph | Should be | Why |
|---|---|---|
| Ownership / directories | a shallow tree | so a change has one obvious home |
| Module dependencies | acyclic | so partial reading is safe |
| Runtime object references | may be a graph | the world is a graph; that is fine |
| Type inheritance | at most one level | depth is non-local reasoning |

Every mutable state and every invariant has exactly one authoritative owner. If you cannot name the
owner, that is the design problem to solve first.

## Evolution

- **Declare the blast radius before changing anything**, and confirm afterwards that the diff
  matches. Unexpected expansion is a signal to re-check an assumption.
- **Two-door test.** A reversible decision: decide and move. A one-way door — data migration, public
  API break, runtime or framework choice, auth model, deletion of user data — gets a written
  decision record and explicit human confirmation.
- **Record a decision only when the consequence outlives the change:** context, decision,
  alternatives considered, consequences, and the condition that should reopen it. Not for routine
  features.
- **Refactoring is scheduled work, not a hope.** Keep deferred items in one visible list with the
  reason and the trigger. An invisible backlog is a decision to never do it.
- **Migrate additively:** add the new path → move callers → remove the old. Branch by abstraction for
  anything that cannot be done in one step. Never a big-bang rewrite of something you have not read.
- **Automate only the rules that are worth a gate.** Boundary violations, dependency cycles, forbidden
  imports, secret scanning, contract regeneration, coverage-must-not-decrease — these belong in CI at
  error level. Size and shape heuristics do **not** become gates; they are smells that need a human
  or a judgment call. Every automated message names the rule and the remedy.
- **Instructions expire.** Every persistent rule, gate, or repo instruction has an owner, a purpose,
  and a reconsideration trigger — a runtime upgrade, a model upgrade, repeated ignoring, or evidence
  that it catches nothing while costing something. Retire redundant guidance; keep genuine safety,
  integrity, and compatibility constraints even when no recent failure argues for them.
