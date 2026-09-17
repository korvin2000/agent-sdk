---
name: compact-java-code
description: Write new Java code, or refactor/rewrite machine-ported, bulky, over-engineered, copy-paste Java projects into compact, lightweight, reference-quality implementations — fewer concepts, classes, layers, dependencies and lines, same required behavior. Use whenever Java code should become smaller, cleaner and better designed without losing correctness.
---

# Compact Java — Reference-Implementation Rules

**Goal:** the smallest *well-designed* solution, not the shortest text. Unnecessary bulk is a design smell; compactness comes from a better model. The result should look inevitable: a clear model, the right algorithm, cohesive classes, direct execution paths, and nothing the problem does not need. Optimize the architecture as a whole, not one function at a time.

Required contracts and constraints are binding; other rules are strong defaults, not mechanical gates — justify consequential deviations briefly.

## 1. Mindset and priorities

- Write like a hardcore perfectionist who ships firmware: every class, layer, allocation, dependency and line must earn its place; portability, memory and resource efficiency are first-class concerns.
- Prioritize **fewer source lines with clear intent**. Moving complexity into a framework, generator or focused utility is useful when it reduces maintained code. Also consider concepts, indirections, dependencies, build machinery, generated code and configuration, but do not reject worthwhile source reduction merely because some complexity moves behind a reusable mechanism.
- Avoid problems instead of solving them: a design that makes a problem disappear beats the most elegant solution to it. Remove before adding — delete needless work, state, representations, config and extension points before inventing abstractions to manage them.
- Preserve the problem, simplify the solution: required behavior, public/wire contracts, safety and resource constraints stay. Change a contract deliberately, never as a side effect of compression.
- For every complex routine ask: fewer lines? less overhead? a better model? does the JDK, an existing dependency or a suitable library already do this?
- Patterns and best practices are instruments: name the problem each one solves; a pattern diagram is never a reason to add a class. Skip design styles, ceremonies, checks and tests that add no real benefit; avoid the anti-patterns in §10.
- When rules collide: required contracts and constraints → fewer source lines with clear intent → fewer concepts and indirections → resource cost. Reduce code, not just whitespace; never trade correctness or readability for brevity.

## 2. Procedure for an existing project

1. **Map first.** Read repository instructions; map the relevant scope, reusing `PROJECT_MAP.md` if present: modules/packages, key types and their relationships, entry points, data flow, hot paths, generated sources, language level, build/test commands. Understand the design before editing it.
2. **Capture the contract:** inputs, outputs, invariants, side effects, compatibility boundaries, scale. Establish the relevant build/test baseline to distinguish existing failures from regressions. Existing behavior is evidence, not spec — surface discovered bugs explicitly instead of silently preserving or fixing them. Verify unfamiliar APIs against installed versions; never invent symbols or dependencies.
3. **Find the highest-leverage simplification**, in this order: drop the implementation of an unneeded requirement → remove redundant state, representations, conversions → improve the model or algorithm → replace custom machinery with a JDK/library facility → consolidate shared knowledge → simplify control flow and syntax. A better model removes more code than any syntactic trick.
4. **Rethink cumbersome architecture as a whole.** Bounded rewrite when the structure itself is the problem; incremental refactoring when the structure is worth keeping. Prove the new design on one end-to-end slice, then propagate. Keep scope coherent; leave unrelated code and the user's work untouched. For machine-ported code, recover the underlying operation and express it idiomatically in Java; remove source-language emulation and translation scaffolding when their semantics are no longer needed.
5. **Finish.** Update every consumer; delete superseded paths, dead code, unused imports, config and dependencies. Check framework/configuration and reflective entry points before declaring code unused. No parallel old/new implementations unless migration truly requires them. Stop when the goal is met and further changes offer no concrete benefit.
6. **Report** in a few lines: what became simpler, deliberate contract changes, what was verified, what stays uncertain. Claim reductions only when measured.

## 3. Structure, packages, names

- Cohesive feature/domain packages in a logical hierarchy: neither one flat package holding everything nor `controller/service/manager/helper` layers stamped everywhere. Start shallow; add a subpackage when it marks a real boundary.
- Aim for acyclic module dependencies; resolve cycles by clarifying ownership, not adding forwarding interfaces. Domain relationships may still form graphs.
- Small public surface, substantial internals; package-private by default, `public` only for the API. A class or method has a coherent purpose, not a line budget: extract when it isolates a concept, invariant or reusable step; inline pass-through fragments that only add navigation. Private methods and static nested types (records, enums) for local concepts.
- No god classes and no class explosion — both hide the design. Never shrink a file by scattering its explanation across ten files.
- Interfaces, factories, layers and DTOs mark real boundaries (independently varying policy, required integration, a seam that matters) — never "one interface per class".
- Names are compact and self-explanatory for every element (constants, fields, locals, methods, types): domain vocabulary, precise verbs, no redundant prefixes or type suffixes (`ManagerImpl`, `userDataObject`); short locals (`i`, `key`) where scope is tiny; units explicit where they matter. A reader should reconstruct a type's purpose from package + name + public signatures. Don't rename stable public APIs cosmetically.
- Code as documentation: structure and names explain *what*; comments explain only *why* — non-obvious invariants, algorithm choice, protocol or compatibility constraints. No narrative or stale comments.

## 4. Consolidate knowledge, model data

- Group related data with its behavior: coordinates → `Point`, amount + currency → `Money`, validated strings → `Email`, `Id`. Cohesive types instead of parallel lists, positional arrays and repeated parameter bundles. Keep stateless transformations as functions when an object would add no identity, state or ownership.
- Prefer records for transparent data carriers when accessors, equality and serialization fit the contract; compact constructors validate; copy mutable inputs/outputs where ownership requires it (immutability is shallow; array components compare by reference). Keep ordinary classes for encapsulated representation, identity, lifecycle or framework needs.
- One authoritative representation per concern: no mirrored collections, redundant fields, DTO hops between layers with identical contracts, or temporaries that exist only because of layering. Derive cheap values rather than storing and synchronizing them. Separate models only where boundaries genuinely differ (wire vs domain vs persistence).
- Consolidate *knowledge*, not resemblance: a business rule, validation, constant or schema that must change together gets one owner. Incidental similarity with different reasons to change stays separate — no reflexive abstraction on the third occurrence.
- Repeated validation, conversion, calculation or sequence → the smallest useful helper (private method, mini-routine, `Checks`/`TextFormats`-style utility, shared aggregator component) at the narrowest owner that explains all uses; promote only when reuse is real. If feasible, avoid `CommonUtils` dumping ground, no wrappers that merely rename existing methods.
- Stable sequence + varying step → parameter, enum, `Predicate`/`Function` first; Strategy when the policy carries behavior or state; Template Method when subclasses truly share an invariant lifecycle with a few hooks. Never a "generic engine" of flags, callbacks and casts.
- Variants: enums with behavior, or `sealed` types + exhaustive `switch` — not boolean flags, `instanceof` chains or a class-per-case hierarchy in separate files:

  ```java
  sealed interface Shape permits Circle, Rect {
      default double area() { return switch (this) { case Circle c -> Math.PI * c.r() * c.r(); case Rect r -> r.w() * r.h(); }; }
  }
  record Circle(double r) implements Shape {}
  record Rect(double w, double h) implements Shape {}
  ```
- Domain DSLs, value objects and fluent builders only when they centralize repeated semantics or prevent real mistakes; start with a record, enum or typed fluent method. No wrapper per primitive, no builder per tiny value.

## 5. Reuse before rebuilding

- Reuse ladder: JDK/JRE facility → existing project dependency → small maintained library (Apache Commons Lang / Codec / IO / Collections, Guava, …) → custom code only when justified. Judge by total cost — transitive dependencies, footprint, license, maintenance — not by a familiar name.
- Adapt, don't reimplement: configure, compose, delegate, subclass, override, overload. When a JDK or library type nearly fits, adapt it before writing a Stack, Cache or Collection from scratch — a simple single-threaded, size-bounded LRU cache can use `new LinkedHashMap<>(16, .75f, true) { protected boolean removeEldestEntry(Map.Entry<K, V> e) { return size() > max; } }`; views come from `AbstractList`/`AbstractMap`; `ArrayDeque` replaces legacy `Stack`.
- Composition is the default; inheritance is a legitimate tool for real subtype relations and types designed for extension — neither banned nor reflexive.
- Boilerplate killers — use when they remove real repetition, after inspecting what they generate: records; Lombok (targeted annotations, not blanket `@Data`); MapStruct for repetitive model mapping; AutoValue only where records cannot serve; annotation processors / code generation when a stable schema is the source of truth (never hand-edit output); reflection and dynamic proxies for runtime discovery, binding and cross-cutting interception (keep the dynamic boundary small; mind native-image metadata); record helpers such as ReflectionRecords or record builders (verify artifact coordinates before adding).
- Use a DI container only when the project already has one; otherwise wire collaborators through ordinary constructors and direct creation. Keep dependencies explicit; do not add a container for compaction. Follow existing framework conventions; isolate a framework only at a concrete boundary.
- Extension ladder — climb one step only for a *present* need: data/config → enum/function → strategy → registry → plugin. YAGNI: leave seams, don't build hypothetical features.

## 6. Multi-paradigm routing

Pick the paradigm by the shape of the problem, mix them at clear boundaries, never showcase all of them.

| Problem shape | Compact default | Escalate / boundary |
|---|---|---|
| Stateful concept with invariants | OOP: cohesive class or value type with behavior | hierarchy only for a real subtype relation or stable extension contract |
| Straight-line orchestration, small algorithm | Procedural: one direct method, loops, explicit calls | extract semantic steps when they read better on their own |
| Stateless transformation, policy | Functional: pure methods, lambdas, method refs, JDK functional types | own functional interface only when JDK types can't express the contract |
| Filter / map / group / reduce | Streams, or a plain loop when clearer | loop for complex state, early exit, measured hot paths |
| Sustained async data flow with backpressure | Reactive pipeline (Reactor/RxJava) on an existing stack or for a real concurrency need | otherwise sequential code or virtual threads |
| Independent reactions, state transitions, flow orchestration | Events, explicit state machine, transition table | define ordering, failure and delivery semantics; no broker for in-process calls |
| Cross-cutting concerns (transactions, logging, retry, metrics) | AOP, proxies, decorators | keep domain decisions visible; proxy-based AOP misses self-invocation |
| Long-running / durable workflows | direct workflow with explicit state | an engine only when persistence, recovery or coordination demand it |

## 7. Compact modern Java

- Use the project's real language level to the full: lambdas, method references, `var`, records, sealed types, pattern matching (`instanceof`, `switch`, record patterns), switch expressions, text blocks, `List.of`/`Map.of`/`List.copyOf`, `Stream.toList()`, `String.formatted`, generics, flexible constructor bodies (Java 25). Don't enable preview features or bump the JDK just to shorten a snippet.
- Boolean algebra, guard clauses, ternaries, switch expressions and named predicates instead of nested `if/else` pyramids — preserving short-circuiting, evaluation order/count, null handling, overflow, floating-point behavior and side effects. A named local that explains a computation is not waste; an opaque one-liner is.
- Fluent APIs and method chaining wherever a chain reads as one operation (builders, streams, configuration); break it with locals when it hides effects or repeats work. No fluent facade over already-clear calls.
- Generics, enums and sealed types remove bookkeeping (casts, raw types, parallel containers, flag combinations); no deep generic frameworks.
- Immutable by default: `final` fields, records, unmodifiable collections; mutate locally within a scope. Mind the contracts of compact APIs: `Stream.toList()` and `List.copyOf` are unmodifiable; `Optional` is for return values, not fields or parameters.
- Uncommon but valid syntax is welcome when it is shorter and still clear:

  ```java
  int x, a = 5, c = 3;                          // one declaration instead of three
  public class A { { checkAndInitialize(); } }  // class sketch: shared instance setup; method omitted
  ```
  Instance and field initializers run in textual order after superclass construction, once per instance even with `this(...)` chaining. Moving constructor code here must preserve order and the constructor contract (visibility, annotations, exceptions). Prefer field initializers for plain values and constructors for parameter-dependent setup; avoid overridable calls during construction. Not double-brace initialization (anonymous subclass). `var` allows no multiple declarators.

## 8. Algorithms, resources, concurrency

- Bring contest-grade algorithmic skill to complex routines, engines, workflows and event/data orchestration: recognize the problem class and use its standard structure — set/map for membership, dedup and joins; `PriorityQueue`/bounded heap for top-k and scheduling; BFS, Dijkstra, topological sort for graphs; state machine or transition table for conditional workflows; bit sets/arrays for dense flags; parse once at the boundary and reuse the representation. Right data structure first; a framework only if something is still missing.
- Remove unnecessary work before micro-optimizing; measure hot paths (JMH for isolated microbenchmarks, representative runs otherwise). Never infer speed or memory from line count.
- Resources are owned: try-with-resources, explicit lifecycle, bounded caches/queues/buffers/histories, streaming I/O instead of materializing whole datasets.
- Simplest adequate concurrency: sequential unless concurrency serves a present need; then make ownership, cancellation, capacity and failure propagation visible. Virtual threads for blocking-I/O scale; keep an existing reactive stack when replacing it would cost more. No `synchronized` or callbacks sprinkled through domain code; `ConcurrentHashMap.computeIfAbsent` and friends are narrow atomic contracts, not transaction mechanisms.

## 9. Correctness without defensive or testing bloat

- Compaction preserves observable semantics: return values, ordering, duplicates, null policy, exceptions, side effects, mutability, equality, transactions, resource ownership, wire formats. Anything else is a contract change — make it explicit and migrate callers.
- Validate at trust boundaries and invariant-owning constructors (`Objects.requireNonNull`, compact record constructors); rely on those guarantees inside. Remove a check or defensive copy only when its guarantee remains enforced across all relevant paths and mutations; fewer lines alone is no justification.
- Failure handling is direct: throw the right exception, translate or add context at one useful boundary, no catch-log-rethrow per layer, no swallowing, no defaults masking defects, preserve interruption.
- Tests are evidence, not ceremony: reuse existing ones, add focused tests for changed behavior, compare old vs new on edge inputs, use an independent oracle for algorithms. No getter tests, no mock replicas of the implementation. Never weaken or delete a failing test to make a rewrite pass.
- Verification scales with blast radius: compile + relevant tests for a local change; mechanism tests (generated mappers, proxies, serialization, native image) when that mechanism changed.

## 10. Smells → corrections

| Signal | Correction |
|---|---|
| A request passes through classes that only forward arguments | Collapse layers; keep boundaries that own a decision |
| Interface + factory for every concrete class | Keep only contracts with a present purpose |
| Copied workflows differing in one predicate | Share the sequence, pass the policy |
| "Generic" engine of flags, callbacks, casts | Recover the concrete operations |
| One business rule edited in several places | Give it one owner |
| Public `utils` catch-all | Move helpers to their owning features |
| A trivial feature spans many tiny files | Co-locate; inline shallow fragments |
| God class owning unrelated state | Split by ownership, not by size |
| Same value copied through near-identical DTOs | One representation; map only across real boundaries |
| Compact code hiding reflection, I/O, blocking or mutation | Make effects visible in names and structure |
| Caches, plugins, brokers, options with no user | Delete until a requirement appears |
| Source shrank while build, dependencies or runtime grew | Weigh the added cost against source reduction and required budgets |
| Tests edited to bless unintended behavior | Restore the contract or declare the change |

## 11. Tensions and final check

- Compactness vs readability → fewer concepts and clear intent; two explanatory lines beat one opaque expression.
- DRY vs premature abstraction → same knowledge: consolidate; incidental resemblance: leave apart.
- Composition vs inheritance → compose by default; subclass a designed extension point when it yields the cleaner correct design.
- Library or reflection vs handwritten code → favor source reduction; weigh added dependencies, build, diagnostics and runtime costs against that benefit.
- Small patch vs complete simplification → coherent scope, but every consumer updated and dead machinery removed.
- Fewer checks/tests vs confidence → remove duplicated evidence, never the guarantee or failure detection behind it.

**Final check:** easier to understand and change, less machinery, same guaranteed behavior? Could a good developer explain the structure without reconstructing a framework that never needed to exist? Would the next realistic change stay local and straightforward?
