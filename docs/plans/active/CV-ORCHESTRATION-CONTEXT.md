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
