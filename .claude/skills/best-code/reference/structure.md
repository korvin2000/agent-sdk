# Structure

## The one metric that settles most arguments

**Context Radius (CR)** of a module = the number of files *outside* it you must read to implement a
typical change to it correctly. It is coupling made countable.

| CR | Read | Do |
|---|---|---|
| 0–2 | self-explanatory | preserve |
| 3–5 | acceptable | the default target |
| 6–9 | the module leaks | look for a missing boundary or a shared mutable concept |
| 10+ | broken | changes here will be guessed, not reasoned; restructure before adding features |

Between two designs of comparable correctness, take the lower CR. A refactor that raises CR needs a
stated reason — "it is more DRY" is not one. Count files you *actually had to open*.

CR is **not** "fewer files". Splitting one 400-line file into eight 50-line files in the same folder
changes nothing and usually makes it worse by adding interfaces to read.

## When a boundary is real

All four must hold, or it is decoration — and decoration raises CR:

1. **Changes together, for the same reason.**
2. **Hides a meaningful amount of interior** behind a small interface.
3. **Nameable in domain terms** without "and", "manager", "helper", or "util".
4. **Verifiable through its interface alone.**

Corollary: `utils/` and `helpers/` fail (1) and (3) by construction and become the highest-CR files
in every codebase. Genuinely generic, dependency-free primitives get a named module (`shared/time`,
`shared/result`, `shared/rng`) with tests and a public API. Everything else lives in the slice that
uses it.

## Default layout: vertical slices inside enforced modules

```
<capability>/                  ← named for the domain, not for a technical layer
  <use-case>/                  ← the unit of work
    <use-case>.contract.*      ← input/output types, schema
    <use-case>.*               ← decision logic, no I/O
    <use-case>.handler.*       ← wiring, I/O, transactions
    <use-case>.test.*          ← colocated; the executable example read first
  domain/                      ← entities, value objects, invariants of this capability
  ports/                       ← interfaces to the outside — only real seams
  index.*                      ← the public API: the only legal import path
```

Cross-module imports go through the public API, and reaching into another module's internals should
**fail the build**, not a review comment. Dependencies point one way; cycles fail the build. Shared
code is *promoted* after a third slice needs it, never anticipated.

Layered top-level structure (`controllers/`, `services/`, `models/`) is the alternative, and its
cost is that every feature change touches four directories.

## Shape the call graph as a tree

- **One entry point per slice.** Three entry points means three slices.
- **Depth ≤ 4–5** from entry to leaf inside a slice; **fan-out ≤ 7** direct collaborators. High
  fan-out is an unnamed concept asking to exist.
- **No cycles** between modules, files, or classes. Cycles make partial reading unsafe, which is
  what agentic work depends on.
- **Leaves are pure.** Side effects live near the root.
- **One level of abstraction per function.** A function that orchestrates *and* twiddles bits forces
  the reader to switch altitude mid-sentence.
- **Deep modules, shallow directories.** Small interface, rich interior — but a 6-level folder path
  is navigation tax with no hiding benefit.
- **Composition forms the tree; inheritance is at most one level below an interface or abstract
  base.** An interface → abstract → concrete → concrete chain is a non-local reasoning device.

Ownership is a hierarchy; runtime references, state machines, and the game world may legitimately be
graphs. A deep inheritance tree is not the goal of "tree-like code".

## Names are the interface to your intent

- **Reconstruction test:** given only the path, the exported names, and their signatures — could a
  competent agent with no other context say what this does and when to use it? If not, the names are
  wrong. Apply it to every file you create.
- **Say what, not how.** `monthlyRevenue`, not `value1`. `sendInvoice()`, not `process()`. Never
  `data`, `info`, `manager`, `util`, `temp`, `obj`, `doStuff`.
- **One verb, one meaning.** Fix the lexicon and hold it: `get*` local, cheap, total · `find*` local,
  may be empty · `fetch*` remote/IO · `load*` persistence → memory · `compute*` pure, possibly
  expensive · `apply*` state + event → new state · `ensure*` idempotent · `assert*` throws.
- **Domain words, not synonyms.** If the glossary says `Match`, never `Game`, `Session`, or `Round`
  for the same thing. Inconsistent vocabulary is how one concept becomes two models.
- **Name the thing, not the category.** `create-match.ts` beats `matchService.ts`.
- **Booleans are questions; a third mode means a sum type**, not two booleans that admit a fourth,
  illegal state.
- **Units and identity in the type**, not the comment: `durationMs: Milliseconds`, `PlayerId`.
- **Idiomatic per language.** In-distribution code is cheaper to read and to extend.

## Comments

Explain the *why*: the chosen algorithm and its complexity, a workaround with a link, a tricky
invariant, a deliberate deviation, a protocol quirk. A comment explaining *what* the code does is a
naming bug — rename instead. Public interfaces get purpose, contract, and failure modes; private
implementation usually gets none. Never narrate your own process in the code. Excessive commenting
is the most recognizable marker of machine-written code and is pure context cost.

## Size: smells, not laws

Crossing one means *look*, not *split reflexively* — mechanical splitting produces shallow modules.

| Unit | Look at ~ | What it usually means |
|---|---|---|
| Function | 50 lines | doing more than one thing, or a sub-concept wants a name |
| File | 300–400 lines | a deep module (fine) or several concepts sharing a file (split by concept) |
| Parameters | 4 | a parameter object or a missing domain type; long positional lists are a top source of call-site bugs |
| Nesting | 3 | invert with guard clauses and early returns |
| Public exports | 10 | interface bloat: the module is shallow |

## Duplication policy, in both directions

Agentic codebases fail in two directions at once — speculative abstraction *and* unconsolidated
copies. So:

- **Search before you write** anything longer than a few lines: by likely name, by domain term, by a
  distinctive signature. If something similar exists: use it, extend it, or say in the plan why a
  separate implementation is correct. All three are fine; silence is not.
- **Rule of three.** Inline it; duplicate it and note the duplicate; then extract — *if* the three
  share a reason to change, not merely a shape.
- **Extract to the deepest module all three callers already depend on**, never upward into `shared/`
  by default.
- **An abstraction that needs a boolean flag, a mode parameter, or a check on who called it is the
  wrong abstraction.** Inline it back. Parameterize *data* over *behaviour* — a table beats a flag.
- **Two implementations of one domain rule is a defect** regardless of how different they look.

## Anchor files

Agents replicate what they can see; use that. Each recurring pattern class (use case, repository,
endpoint, feature slice, ECS system, plugin) gets **one designated exemplar** that is deliberately
excellent and named in the repo's agent instructions. Read it before writing the next instance and
follow it — a locally-consistent mediocre pattern beats a locally-inconsistent excellent one,
because the next generation will be consistent too. Improving the anchor is a tracked migration
task, not a silent divergence.

## Hidden coupling — the forms that break non-local reasoning

| Form | Symptom | Counter |
|---|---|---|
| Global state | behaviour depends on process-wide mutable state | pass explicitly; one owner per state |
| Temporal | correctness depends on call order | encode order in types or a state machine |
| Control | flag/mode parameters steer internal branches | split into named operations |
| Semantic | two files agree by convention, nothing enforces it | shared type, shared schema, or a check |
| Content | reaching into another module's internals | public API + boundary lint |
| Duplicated policy | the same rule reimplemented in several places | one policy module, enforced by a test |
| Stringly-typed | magic strings as keys, states, events | enums, sum types, constants generated from the contract |
