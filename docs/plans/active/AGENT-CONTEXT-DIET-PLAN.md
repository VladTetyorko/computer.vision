# AGENT-CONTEXT-DIET — the agent harness stops paying interest on its own changelog

Status: **IN PROGRESS**. Started 2026-09-13, restarted 2026-09-17 on `chore/agent-context-diet-2`
(see §7 — the first attempt's uncommitted work was destroyed by a branch switch) · owner request:
*"I guess it's a bit overspending tokens, because I have ~150k tokens used for tests updates,
reading the context and so on. I need to cut up and localize/encapsulate most of the work the
agents do, but leave the orchestrational approach for high level of my input."*

This plan changes **no product code**. It changes what an agent is required to read before it may
write, and it splits the module docs that requirement loads.

---

## 1. Measurement — where the tokens actually go

`CLAUDE.md` says *"Before modifying a module: read its `MODULE.md`"*; all seven agent definitions in
`.claude/agents/` hardened that to **"IN FULL"**. What that cost at the moment an agent started, and
how it moved over the four days between the two attempts:

| Agent | Told to read in full | 2026-09-13 | 2026-09-17 | ≈ tokens now |
|---|---|---:|---:|---:|
| `web-ui` | `station/vision-web/MODULE.md` | 518 KB | **671 KB** | **~168k** |
| `spring-integrator` | `vision-app` + `vision-api` | 246 KB | **353 KB** | ~88k |
| `application-service` | `perception` + `flight` | 200 KB | **295 KB** | ~74k |
| `adapter-builder` | `mavlink` + `perception` | 202 KB | **288 KB** | ~72k |

All 26 `MODULE.md` files: **1.76 MB → 2.18 MB (+24%) in four days.** `vision-perception` alone went
95 KB → 181 KB (+90%). That growth is the bug, not the size: cv-orchestration W0–W9 merged in that
window, and each wave appended its narrative to the docs every future agent must read first.

`station/vision-web/MODULE.md` as measured on 2026-09-13, 833 lines at ~620 bytes/line:

```
189 KB  ## API surface
  3 KB  ## Conventions
 21 KB  ## Gotchas
231 KB  ## Status                       ← 22 lines of current state, 265 lines of wave narrative
 74 KB  ## Status — <10 dated sections>
```

**58% of the file was history.** `station/vision-app/MODULE.md` was worse in proportion: its
`## Status` ran lines 326→1168 — 843 of 1168 lines — and was almost entirely *"wave B3 done"*, maven
invocations, test counts, and notes an agent wrote to itself while working.

## 2. Root cause — the loop

Each agent was told to *append* a status entry when it finished. So every wave added ~8 KB (~2k
tokens) to what **every future agent in that module must read before writing a line**. The cost of
starting work grew monotonically with the number of waves already done. That is the whole bug.

Git already stores this history, and every wave already has a plan doc in `docs/plans/`. The module
doc was paying to store it a third time, in the one place that is loaded eagerly.

## 3. Contradictions found in the harness while measuring

| # | Where | Said | But |
|---|---|---|---|
| C1 | `.claude/agents/domain-modeler.md:12` | *"N-1-arg convenience constructors for optional trailing fields"* | `CLAUDE.md` rule 10 **withdrew** exactly this convention (audit R1, commit `7bffe0d1`) |
| C2 | `CLAUDE.md` §"Agentic rules" | *"Opus — for thinking on module lvl, can create a code"* | the 2026-09-12 decision is that **every** implementing agent/sub-agent is Sonnet |
| C3 | `CLAUDE.md` §§"Overall rules" 2/6 + "Separate and Standalone" | SOLID / IoC / layering, stated three times | `.claude/skills/java-clean-code/SKILL.md` already states it once, better, and loads on demand |
| C4 | `CLAUDE.md` module index | 25 rows | `drone-link/mavlink-core` is a real Maven module with its own `MODULE.md` and was **missing** |

## 4. The law, after this plan

**Two files per module, and only one of them is loaded eagerly.**

| File | Holds | Read when |
|---|---|---|
| `MODULE.md` | purpose · deps · build/test · API surface · conventions · gotchas · current status | **always**, before touching the module |
| `MODULE-HISTORY.md` | every *"wave X done"*, dated entry, build log, test count, agent note-to-self | **only** when you need to know *why* something is the way it is |

The boundary, stated so it can be applied mechanically:

> Keep a sentence in `MODULE.md` if it says **what is true now**.
> Move it to `MODULE-HISTORY.md` if it says **what a wave did** — when, on what branch, with what
> test counts, or what the agent learned on the way.

Finishing a task **updates `MODULE.md` in place**. It never appends a wave section to it.

Every `MODULE.md` ends with `Wave-by-wave history: MODULE-HISTORY.md` — required, not decorative:
~146 source files cite these docs by path, some as *"see MODULE.md's Status entry for why"*, and the
pointer is what keeps such a citation resolvable in one hop after the narrative moves.

## 5. Waves

| Wave | Scope | State |
|---|---|---|
| **D1** | `module-docs/SKILL.md` rewritten around the two-file law + progressive disclosure | done |
| **D2** | root `CLAUDE.md`: C2, C3, C4 fixed; `## Build` section added (see §6) | done |
| **D3** | all 7 `.claude/agents/*.md`: "IN FULL" → progressive; append → update-in-place; C1 fixed | done |
| **D4** | `station/vision-app` + `drone-link/mavlink` split — the recipe proven | done 09-13, **lost in §7, redo** |
| **D5** | the remaining 11 oversized module docs split, one agent per file, disjoint scopes, **committed in batches** | open |
| **D6** | *(follow-up, not this branch)* shard `vision-web` / `vision-app` / `vision-api` / `vision-perception` API surface by responsibility, so an agent loads one feature's contract instead of all of them | open |

## 6. The build-discipline half

Separate from doc size, three recurring costs were burning agent context and agent turns. All three
are now stated once in `CLAUDE.md` §Build and referenced from the four Java agent definitions:

1. **`-am … install -Dmaven.test.skip=true` before multi-module `-pl` tests.** Without it the second
   `-pl` invocation resolves the first's output from a stale `~/.m2` jar and invents
   "cannot find symbol"/constructor-arity errors that look like a code defect.
2. **Never background a build.** A `./mvnw` started with `run_in_background` or a bare `&` is killed
   the instant the issuing turn ends — and may still report `completed`.
3. **Keep the log on disk, not in context**, and never verify with an `[INFO]`-anchored grep, which
   hides failures.

## 7. Incident 2026-09-13→17 — why this plan has a `-2` branch

The first attempt did D1–D3 plus D4 for two modules, then fanned out nine Sonnet agents in one
batch. Two finished; **seven were killed mid-edit by a session rate limit**. Between sessions the
working tree was switched from `chore/agent-context-diet` to `master`, and because nothing had been
committed, every tracked edit was discarded — the whole config layer, and both finished splits'
`MODULE.md` halves. Only the untracked `MODULE-HISTORY.md` files survived, orphaned: their content
still duplicated in the `MODULE.md` files that had been reverted.

Three rules came out of it, and the first is now in `CLAUDE.md` §"Delegation model":

- **The orchestrator commits each wave as it lands.** Agents still never commit, but uncommitted work
  in a shared tree does not survive a branch switch.
- **Fan out in small batches.** Nine concurrent agents is one rate limit away from seven half-edited
  files; three, committed between batches, bounds the loss.
- **A killed agent leaves damage, not nothing.** Assess the tree before resuming — half-written docs
  and orphaned new files both occurred here.

## 8. What this deliberately does not do

- **No product code changes.** Nothing in `src/` is touched; no build is affected.
- **No deletion of history.** Every moved line lands in `MODULE-HISTORY.md`.
- **No change to the orchestration surface.** The owner still delegates by wave to named agents with
  disjoint file scopes; only what those agents load changes.
- **D6 is left open on purpose.** Sharding a 189 KB API-surface section by feature needs a judgment
  call per feature and a scripted repoint of anything citing it; it is a plan of its own.
