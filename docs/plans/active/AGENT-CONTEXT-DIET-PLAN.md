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
| **D4** | `station/vision-app` + `drone-link/mavlink` split — the recipe proven | done 09-13, lost in §7, **redone under D5** |
| **D5a** | `station/vision-web` — the only doc with a mechanical boundary (line 342/343); moved bytes, did not judge them | done `94b620ab` |
| **D5b** | `station/vision-app` + `contexts/vision-perception` | done `1f1ce1e9` |
| **D5c** | `station/vision-api` | done `55f5736e` |
| **D5d** | `contexts/vision-flight` | done `a97f50c8` |
| **D5e** | `drone-link/mavlink-core`, then `drone-link/mavlink` | done `0ccfb12b`, `a05228d1` |
| **D5f** | `cv/cv-service` 80→76 KB, `contexts/vision-warehouse` 66→56 KB | done `e820c900`; `storage/persistence` still running |
| **D5g** | the three docs the narrative-share measurement says are worth an agent: `cv/vision-proto` (69% narrative — the worst in the repo), `video-input/rtsp` (28%, **orphaned `MODULE-HISTORY.md` — overwrite wholesale**), `cv/grpc` (25%) | running |
| **D5h** | *(marginal, decide after D5g)* `core/vision-platform` 33%, `contexts/vision-identity` 29%, `core/vision-kernel` 24%, `device-discovery/onvif-mdns-v4l2` 23% — ~32 KB of narrative between all four, so one agent for the set, not four | open |
| ~~**D5i**~~ | the remaining 9 docs — `contexts/vision-learning` 10%, `video-output/publish-hls` 4%, `contexts/vision-map` 5%, `contexts/vision-simulation` 3%, `video-input/mjpeg` 4%, `video-input/v4l2` 13%, `cv/tiles` 10%, `simulation-sources/sim` 12%, `contexts/vision-events` 9% | **retired, will not do** — see below |
| **D5-lift** | lift pass on `station/vision-web`: D5a was mechanical, so still-current facts buried in the 435 KB of moved history are not yet in the contract | open |
| **D6** | *(follow-up, not this branch)* shard `vision-web` / `vision-app` / `vision-api` / `vision-perception` API surface by responsibility, so an agent loads one feature's contract instead of all of them | open |

### Measured result of D5a–D5e

The seven heaviest docs, before → contract now (history is still on disk, just no longer eagerly read):

| Doc | Before | Contract | History |
|---|---:|---:|---:|
| `station/vision-web/MODULE.md` | 672 KB | 238 KB | 435 KB |
| `contexts/vision-perception/MODULE.md` | 181 KB | 138 KB | 56 KB |
| `station/vision-app/MODULE.md` | 184 KB | 85 KB | 112 KB |
| `station/vision-api/MODULE.md` | 169 KB | 98 KB | 74 KB |
| `contexts/vision-flight/MODULE.md` | 114 KB | 95 KB | 22 KB |
| `drone-link/mavlink/MODULE.md` | 108 KB | 66 KB | 56 KB |
| `drone-link/mavlink-core/MODULE.md` | 80 KB | 50 KB | 52 KB |
| **total** | **1.51 MB** | **770 KB** | **806 KB** |

**The split has a floor, and four of these seven are already on it.** `vision-perception` only came down 181 → 138 KB because 105 KB of the remainder is genuine `## API surface` and 24 KB genuine `## Gotchas`; its `## Status` went from 364 lines of changelog to 2.7 KB, which is the whole of what a split can do there. 78 KB of `vision-api`'s 98 KB is a single ~190-row endpoint table. Going below this floor is D6, not D5 — it needs per-responsibility judgment plus a scripted repoint of the ~146 source files that cite these docs by path.

**The higher-value outcome was recovery, not reduction.** Facts that existed only inside wave narrative and are now in the contract: the SSE topic set (`tracks:`, `cv-trace:`, `discovery`, `zones`, `system`), absent from `vision-api`'s API surface entirely; the force-arm magic split (2989 vs 21196) and that ArduPilot *silently bypasses pre-arm checks* on the wrong value; extension channels 9–16 using release sentinel 65534, not 0; and the three build traps now in `CLAUDE.md` §Build. Four stale facts were also corrected in passing — the session cookie is `same-site: strict`, not Lax; nearly every `vision.*.enabled` flag that compiles `false` is ON in `docker-compose.yml`; `RoutingFrameSink` calls `sendTo()`, not `broadcast`; and `vision-flight`'s "fully implemented" line omitted `SeatService`, `BatteryMonitor` and `LinkLossNotifier`.

### Narrative share decides whether a doc is worth an agent

After `cv-service` returned 4 KB and `vision-warehouse` 10 KB, I stopped splitting by file size and
measured what fraction of each remaining doc is actually wave narrative (`## Status`-family sections
plus dated lines). Size had been the wrong proxy the whole time:

| Doc | Size | Narrative | Worth an agent? |
|---|---:|---:|---|
| `cv/vision-proto` | 31 KB | **69%** | yes — worst in the repo, and a *wire contract* doc at that |
| `core/vision-platform` | 25 KB | 33% | marginal |
| `contexts/vision-identity` | 33 KB | 29% | marginal |
| `video-input/rtsp` | 58 KB | 28% | yes |
| `cv/grpc` | 57 KB | 25% | yes |
| `core/vision-kernel` | 26 KB | 24% | marginal |
| `device-discovery/onvif-mdns-v4l2` | 33 KB | 23% | marginal |
| `video-input/v4l2` | 16 KB | 13% | no |
| `simulation-sources/sim` | 15 KB | 12% | no |
| `contexts/vision-learning` | 44 KB | 10% | no — the clearest case that size misleads |
| `cv/tiles` | 15 KB | 10% | no |
| `contexts/vision-events` | 10 KB | 9% | no |
| `contexts/vision-map` | 26 KB | 5% | no |
| `video-output/publish-hls` | 30 KB | 4% | no |
| `video-input/mjpeg` | 16 KB | 4% | no |
| `contexts/vision-simulation` | 16 KB | 3% | no |

**The rule this establishes: do not split a doc below ~20% narrative share.** Below that the split
costs an agent, a commit and a second file per module, and buys back a few kilobytes — while adding
real risk, since every split so far has had to be hand-repaired for dropped or invented citations.
`contexts/vision-learning` is the clearest case: 44 KB, the 3rd-largest doc left, and only 10%
narrative. Splitting it would have been pure churn.

That retires nine docs from this plan for good. Their cost is API surface, and API surface is D6's
problem, not D5's. The same is true of the four already on the floor (§ above) and of `cv-service`,
whose 76 KB is 50 KB of env-var/gRPC/class-listing surface and 17 KB of Gotchas.

## 5b. Defects this plan found (none of them fixable on this branch)

This is a docs-only plan and it touches no product code, so each of these needs an owner elsewhere.
They are listed because relocating prose turned out to be an effective audit: every one of them was
found by reading text that had sat unread inside a `## Status` section.

### Code

**`DetectionFrameCodec#toPullTelemetry` mis-detects pull mode.** cv-service now sets
`DetectionResponse.dropped_frames` (field 19) under `DetectStream`, not only `DetectPulled`, from the
same `LatestOnlyMailbox.dropped` counter push mode already kept. `toPullTelemetry` still decides
"this is a pull response" by probing that all six diagnostic fields (16–21) are proto-zero, an
inference the wire no longer supports — so a push response that dropped a frame decodes a non-`null`
`PullTelemetry`. The method's javadoc states the assumption that was invalidated ("the shape a
`DetectStream` response always has").

*Latent, not live:* `StreamPipeline#recordPullTelemetry` is reachable only from the pull-mode
driver's subscriber, and no API or web surface reads the accessor. It goes live the moment a push
path consults it, and the value is actively wrong rather than merely present —
`PullTelemetry(0, 0f, 0f, n, 0, 0)` feeds `decodeMillis = 0` into the rate controller's capacity
ceiling. Fix belongs on the decode side: discriminate on the RPC that produced the response.
Owner: whoever next takes a `cv/grpc` wave. Gotcha recorded in both `cv/grpc/MODULE.md` and
`cv/cv-service/MODULE.md`.

### Docs — corrected in place on this branch

- **Two phantom citations**, both pre-dating this plan: `ZERO-CONFIG-ONBOARDING-PLAN.md` (never
  existed; the doc is `…-CONTEXT.md`) and `PLATFORM-AUDIT-2026-08-21.md` (never existed; the audit is
  six `PLATFORM-AUDIT-*.md` lane reports), the latter in two modules. A fabricated citation is worse
  than a bare one — it reads as correct and sends the next agent nowhere.
- **`cv-service` claimed `bytetrack` was "one env var away"** while the same file said it was retired.
  `BUILTIN_ASSOCIATORS` is `{"cost"}` and `CV_TRACK_ASSOCIATE_ENGINE=bytetrack` silently resolves to
  `cost` via `RETIRED_ASSOCIATORS`. An agent trusting that row would have A/B-tested two identical
  configurations and believed the result.
- **`adapter-persistence`'s `## Status` contradicted its own API surface** — 32 repositories through
  `V34`, against a table two sections above saying 33 and a ledger saying `V36`.
- **`adapter-cv-grpc` said "six gRPC services"**; there are three with Java callers, plus a
  cv-service-internal `Detector`. `Inspect` was missing from `Inference`'s method list despite being
  called in production, and `PipelineConfig.trace()` was documented as "false at every call site",
  stale since `TraceDemandPort` began flipping it per stream.
- Four stale facts corrected during D5a–D5e, listed in §5's measured-result note.

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
