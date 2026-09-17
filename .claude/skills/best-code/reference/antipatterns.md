# Anti-patterns and the pre-completion check

Each row has a **signal you can check on your own diff** before finishing. A failure mode you cannot
detect is one you will repeat.

## Your own failure modes

| Anti-pattern | Signal in your own work | Counter |
|---|---|---|
| **Hallucinated API** — a method, flag, field, or import that does not exist | the symbol is in your diff but in no file you opened | read it or do not call it; type-check before claiming anything |
| **Confident completion** — "done" with nothing run | no command output in your report | evidence, or an explicit statement of what you could not run |
| **Test weakening** — loosening a test to get green | test files in a diff whose purpose was implementation | absolute prohibition; report the blocker instead |
| **Error masking** — empty catch, `as any`, `@ts-ignore`, bare `except`, swallowed rejection | grep your own diff for these | narrow catch at a level that can decide; lint at error level |
| **Duplicate implementation** — rewriting what exists | you never searched before writing | search by name, domain term, and signature first |
| **Premature abstraction** — interface, factory, generic for one caller | more than one new abstraction in the plan; an interface with one implementation | budget of one; two real implementations before an interface |
| **Silent scope creep** — improving what nobody asked about | the diff touches files outside the declared cone | note the idea instead of implementing it |
| **Comment noise** — narrating every line | comment density far above the surrounding code | explain why, or say nothing |
| **Foreign style** — importing a better pattern into a consistent codebase | your file looks unlike its neighbours | follow the anchor file |
| **God file** — everything lands in one 2000-line module | one file that many unrelated slices import | name the concept, or colocate into the slice |
| **Rewrite instead of understand** — "easier to rewrite this" | you are deleting code you never read | that impulse is usually your context limit talking |
| **Mock theatre** — tests that only exercise doubles | tests still pass when the implementation is stubbed out | real objects; mock only at I/O seams |
| **Fabricated confidence** — asserting the behaviour of an unfamiliar library | a claim with no source and no experiment | read the source, run a probe, or write "unverified" |
| **Flaky tolerance** — re-running until green | you ran the same suite three times | quarantine and diagnose; never re-roll |
| **Symptom patching** — three variations on one fix | three attempts, same failure class | your model is wrong; go back to reading |
| **Sycophancy** — dropping a correct position under push-back | you reversed a technical claim with no new evidence | state the evidence; disagreement is a service |
| **Context exhaustion** — losing the plan and improvising | you are re-reading files you already read | checkpoint early, not when it is too late |
| **Boiling the ocean** — fifty files before the first line | half the context spent before the first edit | breadth-first, detail just in time |
| **Config sprawl** — an option per hypothetical | an option with no non-default caller today | no configuration without a caller |
| **Red between steps** | an intermediate commit that does not build | additive → switch → remove |

## Failure modes of the code

| Anti-pattern | Why it is expensive here | Counter |
|---|---|---|
| Anemic domain + god service | invariants scattered where no type protects them | put behaviour with the data that owns it |
| Deep inheritance chains | behaviour lives in a file nobody read | composition; ≤ 1 level below an interface |
| Boolean flag soup | encodes illegal states; every reader recomputes the legal set | sum types, state machines |
| Stringly-typed keys and events | typos compile; nothing is exhaustive | constants generated from the contract |
| Hidden global state / singletons | invisible coupling; breaks determinism and tests | explicit ownership and injection |
| Circular dependencies | partial reading becomes unsafe | architecture check at error level |
| Layered top-level structure | every feature change touches four directories | vertical slices |
| N+1 queries, unbounded result sets | passes every test at small N, dies in production | query-count and size budget in a test |
| Client-side authority | an entire class of cheating | server-authoritative state |
| Non-determinism in a simulation | destroys replay, rollback, and reproducibility at once | inject clock and RNG |
| Rules duplicated in client and server | they will drift, and the drift is a gameplay bug | one shared core, generated bindings |
| Hand-copied DTOs across runtimes | silent drift across the boundary | generate both sides from the contract |
| Retry without idempotency | duplicate purchases, duplicate grants | an identity key per retryable command |
| Unbounded queues, fire-and-forget | a load spike becomes an out-of-memory kill | bound every queue; every task has an owner |
| Secrets in code or logs | irreversible once committed | secret scanning in CI; redaction in the logger |
| Magic numbers in gameplay code | balance changes need a code change and a redeploy | declarative content files |

## Run this on your own diff before reporting done

Adjust the file globs to the repo. The point is to look, mechanically, at what you actually changed.

```bash
git diff --stat                                     # does the cone match what you declared?
git diff -U0 | grep -E '^\+[^+]' | grep -nE 'TODO|FIXME|XXX|console\.log|println|dbg!|debugger'
git diff -U0 | grep -E '^\+[^+]' | grep -nE 'as any|@ts-ignore|@ts-expect-error|# type: ?ignore|@SuppressWarnings|#\[allow'
git diff -U0 | grep -E '^\+[^+]' | grep -nE 'catch *\([^)]*\) *\{ *\}|except: *$|except Exception: *pass|rescue *$'
git diff -U0 | grep -E '^\+[^+]' | grep -nE '\.skip\(|\.only\(|@Disabled|@Ignore|xit\(|xdescribe\(|pytest\.mark\.skip'
git diff -U0 | grep -E '^\+[^+]' | grep -niE 'api[_-]?key|secret|password|token *=|BEGIN [A-Z ]*PRIVATE KEY'
git diff --name-only | grep -E '(test|spec)'        # did tests change in a change that was not about tests?
```

An empty result is the pass condition; every hit is either fixed or declared. On Windows use
Git Bash or PowerShell equivalents — the patterns, not the shell, are the content.

Then read the whole diff end to end. Look for: an assumption you never verified, a missing failure
path, a stale generated artifact, duplicated policy, a compatibility break, and anything that
contradicts your own plan. Report pre-existing failures separately from ones you introduced.
