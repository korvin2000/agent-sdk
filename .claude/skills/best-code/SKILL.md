---
name: best-code
description: Engineering discipline for non-trivial coding work — planning, structure, boundaries, correctness, algorithms, and self-review before completion. Use when designing, implementing, refactoring, or reviewing a change that spans more than one file; when choosing an architecture, abstraction, algorithm, concurrency model, or test strategy; when a change crosses an API, schema, protocol, or module boundary; or before declaring work done. Skip for one-line edits, formatting, and factual questions.
---

# Best Code

Load the **Kernel** below. Then load **at most one or two** reference files whose trigger actually
fired. Never load the whole tree — a focused subset outperforms an exhaustive one, and every
unused line costs judgment as well as tokens. Paths are relative to this file.

## Kernel

1. **Ground every symbol.** If you have not read it in this repo or fetched its docs, it does not
   exist. Search for existing capability before writing a new one.
2. **Plan in writing once the blast radius exceeds one file.** State how you will verify *before*
   how you will implement. If you cannot name the signal, you do not yet understand the task.
3. **Slice to single-pass implementability.** One increment = one context window, one reviewable
   diff, one green run. Never leave the repo red between steps.
4. **One new abstraction per change.** Interface, layer, generic parameter, config surface,
   extension point, dependency, named pattern — each counts. "Might need it later", "for
   flexibility", "in case we swap X" are not warrants. A second real implementation is.
5. **Complete ≠ minimal.** Simplicity is about the number of concepts, never the number of cases
   handled. Do not trade away validation, error paths, cleanup, or edge cases for elegance.
6. **Make the machine able to disagree with you.** Push every check to the cheapest rung that can
   catch it — types → lint/arch → unit → property → contract → integration → e2e — and never climb
   a rung while a lower one is red.
7. **Never weaken a test, type, assertion, schema, or error path to get green.** No empty catch, no
   `as any`, no `@ts-ignore`, no skipped test. Blocking behaviour is a finding to report, not an
   obstacle to remove.
8. **Optimize the next agent's read cost.** The next session starts with none of your reasoning.
   Minimize the files someone must open to make the *next* change here.
9. **Evidence, not assertion.** Report actual command output, or state precisely what you could not
   run and why. "Should work" is not a completion claim.
10. **Deviate consciously, never silently.** Every rule below the Kernel may be broken.
11. **Planning** Make a well-thought-out plan before you start working on larger tasks.
12. **Project Structure** A well-structured, hierarchical, tree-like project structure is solid 
   foundation and the path to flexibility, clarity, and scalability in the project
13. **Combining SOTA programming paradigms** combine best programming paradigms where it is beneficial: 
   OOP (Object-Oriented Programming), FP (Functional Programming), Reactive Programming, events and 
   streams - the best design and programming patterns. Try to follow best-practices for every technology
   stack, avoid anti-patterns where possible.
14. **building blocks** Everything complex—just like in biology with DNA—is built from simple building 
   blocks that play/interact together. break down complex structures and logic into simple, testable 
   entities and subtasks. Use appropriate programming patterns
15. **keep it small and simple** All well-made cannot be overly complicated or confusing. Everything 
   well written is always logical, self-explanatory and transparent. Avoid overengined, bloated code.

## Reference routing

| Trigger | Load |
|---|---|
| Multi-step or multi-module change; unclear scope; repeated failed attempts | `reference/planning.md` |
| Deciding file/module layout, an abstraction, a name, a split, a refactor | `reference/structure.md` |
| Types, validation, tests, error handling, "how do I prove this works" | `reference/correctness.md` |
| Before declaring done; reviewing your own diff or someone else's | `reference/antipatterns.md` |
| Non-trivial computation, data structure choice, or performance work | `reference/algorithms.md` |
| async, threads, tasks, timers, retries, transactions, simulation, determinism | `reference/concurrency.md` |
| Crossing a module/API/schema/protocol/plugin boundary; new service; scaffolding a system | `reference/architecture.md` |
| Node, Java/Spring, React/Angular, Godot specifics; cross-runtime contracts; UI state | `reference/stack.md` |
| Editing this skill or judging whether a rule earns its place | `reference/meta.md` |

## Tie-breakers when principles collide

Resolve, do not average. In order of authority: explicit user instruction → repo-local rules
(`CLAUDE.md`/`AGENTS.md`, ADRs, lint config) → Kernel → everything else → habit.

- **DRY vs. AHA** — centralize *decisions*; tolerate duplicated *shape*. Two implementations of one
  domain rule is a defect; two similar-looking formatters are not.
- **Locality vs. separation** — prefer whichever design makes fewer files necessary for a typical
  change. This settles most style arguments mechanically.
- **Small functions vs. deep modules** — split at semantic boundaries, never at a line count.
- **YAGNI vs. "must be extensible"** — name the expected extension category, build *one* concrete
  seam, prove it with a second example. Not a plugin framework before the first feature works.
- **Existing tests/contracts vs. the new requirement** — established behaviour holds by default;
  changing it is an explicit, separately-stated change with its old assumption named.
- **Repo convention vs. this skill** — the repo wins; note the conflict once.

## What this skill does not prescribe

Silence here means freedom, not a hidden rule. You choose: file and function decomposition,
identifier style beyond the verb lexicon, library selection, formatting (defer to the repo's
tooling), whether any named design pattern applies, test framework and layout, commit granularity
beyond one logical change, and your own reasoning process — no mandated ritual, no reasoning
transcript, no prescribed technique. Outcome, authority limits, and contracts are explicit;
implementation is yours.

## Deviation

Any rule below the Kernel may be broken. The price is four lines, once, in the plan or the report:

```
DEVIATION: <rule>
REASON:    <what makes this situation different>
INSTEAD:   <what you did>
REVISIT:   <condition that should reopen this>
```

A declared deviation is engineering judgment. A silent one is a defect.
