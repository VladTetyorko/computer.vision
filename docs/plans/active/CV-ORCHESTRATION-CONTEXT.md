# CV-ORCHESTRATION — context

**Started 2026-09-11**, branch `docs/cv-orchestration` (off `feat/live-topics-zones-system` @ `03d0273a`).
Docs-only task: research → synthesis → architecture proposal. No product code in this branch.

## The ask (owner, verbatim)

> Check CV flow. Use sonnet agents to research and clarify the flow from User enables CV to
> Settings that can be applied.
>
> I need you to think and refactor the CV flow — because now it contains many decision makings,
> including: chose model / chose what objects to track / to enable/disable tracking / to enable
> some cached tracking / to click many many many things just to cover 1 task — make computer see
> the object.
>
> Let's do it like this: we have a CV service. Instead of chain — I need an orchestration, when
> each part is working on its own responsibility, and then — it's aggregated to some result.
> Some parts are responsible for "memory", some parts are responsible for tracking, some parts
> are responsible for predictions and so on.
>
> Requirements: all of this should be scalable. In the main application or in some sub-service —
> I want to be able to debug what each process is doing and what is the result of it.
> Additionally, think and research about aggregating all of this.
> And double-check the contract between app and CV — I guess we can have not only x and y of
> square, but all other telemetry, like difference with previous, like confidence, like id of
> object, suggested next coordinates and so on. It should be an object mirroring the state of
> real object, and how our application sees it.
>
> Use sonnet agents only for research and accumulating tasks. Use Opus for summarising.
> Use Fable, ourself to think, propose and argument. As a result — create a MD file with all the
> context and use it for double-check and implementation.

## What the owner is asking for (decoded)

1. **One task, one intent.** "Make the computer see the object" is one operator act; today it is
   a chain of decisions (model, classes, tracking on/off, memory on/off, follow, declutter…).
2. **Orchestration, not a chain.** Inside cv-service each concern (detect, associate, follow,
   memory, predict, compensate, elect label…) owns its responsibility and *contributes evidence*;
   an aggregator composes the result. Parts can be added/removed without re-threading the chain.
3. **Scalable.** Parts must be able to run as separate workers/processes/instances.
4. **Debuggable per part.** From the main app or a sub-service: what did each part do, on which
   frame, with what result.
5. **A richer contract.** The wire carries a *state mirror* of each real object — not a box:
   id, confidence, delta vs previous, predicted next position, provenance, memory state, etc.

## Method (roles the owner mandated)

| Role | Model | Does |
|---|---|---|
| Research | Sonnet ×5 | code-truth inventories, each with disjoint scope, file:line cited |
| Synthesis | Opus | one `O1-SYNTHESIS.md` reconciling R1–R5, contradictions named |
| Architecture | Fable (this session) | proposal + argument in `CV-ORCHESTRATION-PLAN.md`; no code |

Research corpus: `docs/plans/active/cv-orchestration/`

| File | Scope | Question it answers |
|---|---|---|
| `R1-operator-click-path.md` | vision-web | every decision/click from "open /fly" to "it sees my object and keeps seeing it" |
| `R2-backend-control-plane.md` | vision-perception, vision-api, vision-app, cv/grpc (Java) | settings resolution → gRPC call → result fan-out; per-stream JVM state |
| `R3-cv-service-internals.md` | cv/cv-service (Python) | component inventory, per-frame orchestration, state ownership, debug surface |
| `R4-wire-contract.md` | cv.proto → codec → Java records → JSON → TS | field-by-field truth; what a "mirror object" is missing; prior scaling decisions |
| `R5-industry-analogs.md` | external | how mature perception stacks split detect/track/memory/predict and what their object-state schema carries |

## Prior art this must not contradict (read before proposing)

- `docs/extracts/TRACKING-ORCHESTRATION.md` — 2026-08-11 charter: one component per file, per-frame
  sequence, config resolve-once, flow-visibility tiers. The *previous* orchestration attempt.
- `docs/conclusions/TRACKING-REVIEW.md` — "identity is a side effect of frame adjacency inside
  ByteTrack"; proposed inverting association so engines become evidence (done in TRACKING-V2).
- `docs/conclusions/CV-RATE-BUDGET.md` — CV is four jobs (search/hold/geolocate/guide) sharing
  one config; association budget arithmetic.
- `docs/plans/active/CV-SCALE-PLAN.md`, `TRACKING-V3-PLAN.md`, `DOMAIN-SEPARATION-PLAN.md`
  (perception worker role, NATS, leases), `ALWAYS-ON-FLOW-PLAN.md` §3 (no platform-wide inference
  budget; one cv-service saturates at ~3–4 streams @10 fps).

## Status log

- 2026-09-11 — context created; R1–R5 launched in parallel (Sonnet); Fable reading MODULE.md +
  proto + orchestration charter meanwhile.
- 2026-09-11 — R1–R5 written to `cv-orchestration/` (R1 333, R2 362, R3 279, R4 281, R5 356 lines). Fable reconciliation log kept in the session scratchpad; O1 synthesis (Opus) launched.
- 2026-09-11 — `CV-ORCHESTRATION-PLAN.md` written (Fable): diagnosis §1 with report citations, principles P1–P7, target architecture §3–4 (contributors, budget, aggregator, ledger, ObjectState, WorldModel, intent→policy, scaling), frozen contract §5, waves W-pre/W0–W6 §6, standalone defects §7, decisions E1–E15 §8, owner questions §9. Awaiting O1 for the double-check pass.
- 2026-09-11 — O1-SYNTHESIS.md written (Opus, 446 lines: 14 contradictions, 34 verified defects, 36 open questions, 11 unverified claims). Plan double-checked against it (§12): two of O1's unverified claims settled by grep (codec enum switches are exhaustive expressions with no default, `DetectionFrameCodec.java:366-391`; `DetectionExtrapolator` query method has no production caller). Plan status PROPOSED; five owner decisions in §9. Docs only, nothing committed; `docs/plans/README.md` row CVO added; memory `cv-orchestration.md` saved.
- 2026-09-12 — owner: "ok, continue". Docs committed on `docs/cv-orchestration` (d601569b); task branch `feat/cv-orchestration` cut from it (= master); perception MODULE.md gate sentence fixed (13cec002). Launched in isolated worktrees: **W-pre web** (Sonnet, `feat/cv-orchestration/wpre-web`: TS `DetectionState` 4 values + contract spec) and **W0** (Opus, `feat/cv-orchestration/w0-contributors`: golden FrameOutcome fixtures first, then contributors/orchestrator/budget/aggregator/ledger/DetectorClient/Inspect RPC, push-mode drops, MODULE.md corrections; acceptance = BASELINE.md unchanged + golden byte-identical + session.py ≤ 400 lines + single acquire site). §9 owner decisions still open; none block W-pre/W0.
- 2026-09-12 — **W-pre web merged** into `feat/cv-orchestration` (ae103a0a, sub-branch `feat/cv-orchestration-wpre-web` @ ea349da7; test:ci 193 files / 3829 tests green, tsc clean). TS `DetectionState` is now a const tuple pinned to the Java enum by `detection-state.contract.spec.ts`; cockpit panel gets a distinct `running-unwatched` kind; camera-geo strip says "Detecting (no viewer)". Left for W3 on purpose: the static caption "Runs only while this stream is watched — zero cost otherwise." in `cv-control-panel.html` is wrong for a `DetectionPolicy.ALWAYS` asset. Lesson: git refuses `feat/cv-orchestration/<wave>` while `feat/cv-orchestration` exists as a leaf ref — wave branches are dash-named (`feat/cv-orchestration-<wave>`). W0 still running.
- 2026-09-12 — **branch base corrected**: the task branch had been cut from `feat/live-topics-zones-system` (03d0273a), twelve commits behind master; W0's worktree started from master, so master was merged into `feat/cv-orchestration` first (0b01ac10, one doc conflict in `vision-web/MODULE.md` resolved by keeping both status sections in date order). **W0 merged** (c4344bf5, sub-branch `feat/cv-orchestration-w0-contributors` @ 9f2eb1a8, Opus): `session.py` 2 362 → 400 lines; `cv_service/orchestration/` = keys, contract, budget, orchestrator, aggregator (sole writer of `Track`), ledger + ring, facts, `DetectorClient` (sole `acquire()` door, grep-enforced by `tests/orchestration/test_gate_seam.py`), contributors `detect.full/roi, egomotion.*, predict.cv, appearance.*, assoc.cost/bytetrack, memory.gallery, propose.cost, follow.<engine>, aggregate`; `Inference.Inspect` RPC + `FrameLedger*`/`SessionFacts`/`ProcessFacts`/`ObjectClaims` proto messages; push mode reports dropped frames; cv-service MODULE.md corrected (associator `cost`, ROI rescue ON, `tracker_millis` excludes ego-motion, FOLLOW recovers too). Evidence: 1423 passed / 7 skipped; `BASELINE.md` diff vs master empty; 30 golden `FrameOutcome` fixtures byte-identical; vision-proto scoped compile EXIT=0. Deviations from the plan's mapping table recorded as the "As built in W0" block under plan §4.1. Re-verified on the merged tree: web `test:ci` 196 files / 3890 tests green; cv-service pytest re-run in progress. Next: W1 (wire mirror) launched from the merged tip.
- 2026-09-12 — **W1 (wire mirror) built** on `feat/cv-orchestration-w1-wire-mirror` (Opus owner, four Sonnet sub-agents on disjoint scopes), seven commits `ffe9d206`→`cc874e85`. `ObjectState` now exists end to end: proto (`DetectionResponse.objects = 27`, `.ledger = 28`, `FrameRequest.trace = 13`, `PullControl.trace = 12`, new enums `ObjectLifecycle`/`EvidenceSource` per decision E8, nothing renumbered or removed) → cv-service (`orchestration/mirror.py`, one entry per LIVE track then one per DORMANT identity) → Java domain → `DetectionFrameCodec` → `ObjectStateResponse` on `/detections` and `/tracks` → TS mirror + two enum contract specs. **Evidence:** cv-service pytest `1443 passed, 7 skipped`; scoped Maven `BUILD SUCCESS` 26/26 modules (vision-perception 760, adapter-cv-grpc 187, vision-api 1074, adapter-persistence 283, vision-app 355, all 0 failures/errors); `npm run test:ci` `Test Files 199 passed (199), Tests 3904 passed (3904)`; `BASELINE.md` and the 30 golden `FrameOutcome` fixtures byte-identical (`git diff d7a40e41..HEAD` over both paths is empty). Acceptance closed by `ObjectStateRoundTripTest` + `object-state.wire.json` + `object-state.wire.contract.spec.ts`: proto→Java→JSON→TS for every group, key-for-key both ways, plus a minimal object proving an absent group is a MISSING KEY.
- 2026-09-12 — **W1 deviations and findings**, all deliberate, none silent. (1) The mirror is built in a pure `orchestration/mirror.py` called from `session.process()`, NOT in the aggregator as §4.5 says — `provenance.contributors` is a `FrameLedger` fact the aggregator never receives and `belief.confidence_raw` comes from boxes it has only just produced, so closing the gap would give the one class that MUTATES `Track` a reference to the record of what it did. (2) `DetectionResponse.ledger` is encoded but deliberately NOT decoded: there is no domain `FrameLedger`, `trace` is `false` at every call site, so a decoder would be a record family with zero readers — W2's `TraceDemand` owns both halves. (3) **Plan contradiction to settle:** `orchestration/ledger.py`'s docstring paraphrases E7 as "predicted box, per-term association cost, memory match distance, label vote tally … live HERE and never on the object mirror", but §4.5's table — which W1 was told to match exactly — puts `predicted_box`, `assoc_cost`, `match_distance` and the candidate list ON `ObjectState`. §4.5 won for W1; E7 needs correcting or §4.5 does. (4) Three wrong comments corrected, all the same claim propagated: the proto, `ObjectState.Timing`'s javadoc and the TS `ObjectTiming` doc each said the instants are epoch milliseconds — they share the response's own `timestamp_millis` timebase and no other, because cv-service ages tracks on `time.monotonic()` and rebases once at the wire. (5) **A pre-existing bug fixed:** `StreamPipeline#applyLabelFilters` was DROPPING `PullTelemetry` on every frame a label filter bit, hidden by the six-arg convenience constructor rule 10 retired. (6) Four dead convenience constructors deleted from the two DTOs (zero call sites each). (7) `objects[]` IS label-filtered (by `identity().label()`, keeping an object whose `identity` is absent) so a denied label cannot reappear under a new JSON key; it is NOT confidence-filtered, unlike `detections[]`. (8) Not a defect, but a trap worth recording: summing `surefire-reports/*.txt` under-counts `adapter-persistence` tenfold, because `PostgresDockerIntegrationTest`'s 35 `@Nested` classes get no `.txt` of their own and the outer class honestly reports `Tests run: 0`. Read Maven's per-module `Results:` line — an earlier pass in this wave mistook that for a Docker-gate skip.
