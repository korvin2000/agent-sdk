# Planning and execution

## Plan depth follows blast radius, not effort

| Blast radius | Plan form |
|---|---|
| One function inside one slice, reversible | 1–3 bullets, inline, no ceremony |
| One new use case, additive | Structured plan; surface it before building |
| Several modules, a shared contract, a schema change, a new dependency | Structured plan + explicit list of affected files and callers; pause for a beat |
| One-way door: data migration, public API break, runtime/framework choice, auth or security model, deletion of user data | Structured plan + ADR + explicit human confirmation. Do not proceed unprompted. |

Every plan step names: **what changes · which files · how it is verified · how it is undone.** A step
whose verification is "review the code" is not a step — find a real signal or mark it an accepted risk.

## Order the work by uncertainty, not by structure

Do the probe most likely to invalidate an expensive decision first: transport compatibility before
designing the networking layer; the database's actual concurrency behaviour before claiming
duplicate safety; can the tool even export this before building the UI around it. A ten-minute
failed experiment beats a detailed plan resting on a false premise. Do not invent probability
numbers — the judgment is qualitative: uncertainty removed vs. probe cost vs. downstream cost.

## Slicing law

A task is correctly sized when you can implement **and verify green** within one context window,
without compaction, and the diff is reviewable in one sitting. Otherwise split — by preference:

1. **By use case** — one user-visible behaviour. Aligns with slices, tests, and commits.
2. **By verification rung** — contract/schema → pure logic → wiring/IO → UI. Each independently provable.
3. **By enabling refactor** — make the change easy, then make the easy change. The preparatory
   refactor is its own task with its own green run and its own commit. Never mixed into the feature diff.
4. **By risk** — isolate the risky part so it can be reverted alone.

Too big if: you cannot name the files up front · more than ~7 steps · more than one new abstraction ·
the diff crosses two bounded contexts · you are re-reading files you already read.

Split additively: add the new path → switch callers → remove the old. Never rip-and-replace.

## Change cone

Before a cross-boundary edit, name the cone: the owning module, its callers, the contracts it
produces and consumes, its tests, generated artifacts, and any migration obligation. Expand
discovery **along real dependency edges**, not by reading the repository breadth-first.

After implementing, compare the actual diff against the cone. Unexpected expansion is a signal to
re-check an assumption, not automatically a failure. Unrelated churn is removed.

A semantic change is a **coordinated set**: implementation, public type/schema, fixtures, tests,
migration, generated bindings, docs that depend on it. The characteristic agent failure is a
convincing local patch that leaves one distant generated artifact stale. Prefer regeneration and
compatibility checks over remembering.

## Exploration budget

Read breadth-first and shallow — paths, exported names, signatures, tests — then load detail just in
time. Before opening a file, know the question you are answering; reading without a question is how
context gets spent on nothing. Grep for the symbol, read the one function, move on.

Two opposite failures, both common: editing within the first two tool calls, and reading fifty files
"to be safe" before writing a line.

## Recovery: a failed attempt must buy information

- A repeated attempt requires a **new hypothesis**, not a variation. Read the actual error, state
  what you believe is happening, confirm that, then fix the cause.
- **Three failed fixes on the same symptom means your model of the system is wrong.** Stop patching;
  go back to reading. The fourth variation will not be the one.
- A bug is reproduced before it is fixed, wherever practical.
- Stop an unsupported or unauthorized action at its actual boundary and report a concrete blocker.
  Never manufacture success.

## Long work and handoffs

Keep an external plan file with checkboxes, updated as you go. Record: current step · decisions and
why · findings that were expensive to obtain · dead ends already tried · open questions. Dead ends
are the highest-value entry — they stop the next session paying for them again.

Checkpoint **before** risk (a long tool run, a big refactor, a context-heavy exploration), not after
failure. After a reset, re-orient from the plan file rather than memory, and re-verify repository
state before continuing — the world may have moved.

## Delegation

Delegate what is **context-expensive but summary-cheap**: broad search, dependency survey, log
triage, "find every place that does X". Give a precise question and the expected output shape.

Do not delegate the judgment you have already built in this context — architecture decisions, the
coherent implementation itself, or anything where the plan is still forming. Parallelize only
genuinely independent work; two agents in overlapping files produce a conflict you must then resolve
with less context than either had. Treat a delegate's report as a claim to verify, not a fact.

## Finishing

Complete the authorized work, then report: what was done · what was verified with which output ·
what was **not** done and why · what was deferred · what you remain uncertain about. Uncertainty is
information; report it. A confident wrong report is worse than an honest partial one. Do not claim
deployment, runtime correctness, or a performance improvement from code inspection alone.
