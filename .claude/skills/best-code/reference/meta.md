# Maintaining this skill

Read this only when editing the skill or judging whether a rule earns its place.

## Why it is this small

| Evidence | Finding | Consequence here |
|---|---|---|
| Gloaguen et al., *Evaluating AGENTS.md* (2026) | Generated repository context files **reduced** success on average across 138 Python issue-solving tasks in 12 repositories, and raised inference cost by over 20%. Developer-written files showed modest average gains. | Volume is a cost, not a contribution. Nothing generic goes in. Every addition is tested against a no-skill baseline. |
| Li et al., *SkillsBench* (2026) | Across 87 tasks, 8 domains, 18 model–harness configurations, curated skills raised average pass rate 33.9% → 50.5%. **Focused bundles outperformed exhaustive ones.** | Progressive disclosure by trigger, not one large body. Load one or two files, never the tree. |
| Anthropic, context engineering | Context should be relevant and compact; instructions need enough specificity to guide without scripting every decision. | The Kernel is decision-shaped, not procedure-shaped. Freedom is stated explicitly so silence is not read as prohibition. |
| Anthropic, Claude Code practices | Executable checks help agents verify their own changes; planning adds overhead on small obvious tasks. | Plan depth scales with blast radius. Detection signals are grep commands, not prose. |

Neither result establishes an ideal universal length. What both support: **selection matters more
than volume.**

**Evidence labels.** *documented* = a source defines the behaviour · *empirical* = a study reports a
measurement · *recommended* = engineering synthesis. The two rows above are empirical; the ranking
of design approaches in this skill is **recommended**, not experimentally established for current
models. Do not present it as measured.

## Budgets, and why they are budgets

`SKILL.md` ≤ 100 lines. Each reference file ≤ 160 lines. Enforced by `check.mjs`, which also verifies
that every file the router names exists and every existing file is reachable from the router.

```bash
node .claude/skills/best-code/check.mjs
```

An unreachable file is dead weight that still costs maintenance; a dangling route is a broken
promise. Both fail the check.

## Adding a rule

A rule earns a place only if it clears all four:

1. **Non-obvious.** A capable model does not already do this by default. "Read before you edit" and
   "match the surrounding style" are not rules; they are padding that dilutes the rest.
2. **Actionable at generation time.** It changes a decision, not just an attitude. "Write clean code"
   fails; "one new abstraction per change" passes.
3. **Detectable.** There is a signal — a grep, a type error, a countable property, a visible diff
   feature — that says whether it was followed. Undetectable rules cannot be evaluated or retired.
4. **It fits.** If it needs more than ~4 lines, it belongs in a reference file behind a trigger, and
   the trigger must be something a task actually presents.

Deliberately **not** in this skill, and why: the nine-level tier ranking and its scoring (it
justifies rules to humans; the rule is what an agent needs) · a machine-readable rule register (the
file router already is the index; a second index doubles the maintenance surface and implies a
precision the evidence does not support) · source bibliographies (provenance belongs with whoever
edits, not in the working path) · full worked code examples (the repo's own anchor files are better
examples than any generic one, and they are always current).

## Retiring a rule

Every rule has a reconsideration trigger: a runtime or model upgrade, repeated ignoring in practice,
or evidence that it catches nothing while costing something. A rule that is repeatedly ignored is
either wrong or badly placed — do not restate it louder. Prose is the weakest available enforcement:
if a rule can be a compiler setting, a lint rule, a schema, or a permission boundary, move it there
and delete the prose. Keep genuine safety, integrity, and compatibility constraints even when no
recent failure argues for them.

## Evaluating a change to this skill

Minimum viable protocol — five paired tasks, run with and without the skill, same model and harness:

1. Add a feature that crosses a module boundary and requires a contract change.
2. Fix a bug whose obvious repair masks the cause.
3. Refactor to remove a duplicated domain rule from two places.
4. Implement something with a known algorithm and a stated N.
5. Extend a system that has an intended extension point and one that does not.

Score: task passed · defects introduced elsewhere · files touched vs. files that needed touching ·
whether the report's claims match what was actually run · token cost. Seed one deliberate defect
per task to check whether verification is real or ceremonial.

A rule that survives no evaluation stays labelled *recommended*. Prefer removing a rule that shows
no effect over keeping it because it sounds correct.
