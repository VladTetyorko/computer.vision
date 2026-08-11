# docs/ — index

Every planning, decision and design document in the project, grouped by **what a document is for**,
not by when it was written. Four rules decide where a file lives:

| Folder | Holds | You read it when |
|---|---|---|
| [`main/`](main/) | the orientation set — always current, read first | you want to know where the project stands |
| [`plans/active/`](plans/active/) | authoritative specs with unbuilt or gated scope | you are about to build something |
| [`plans/done/`](plans/done/) | specs whose scope has shipped | you need to know *why* existing code is shaped the way it is |
| [`conclusions/`](conclusions/) | investigations, surveys, verdicts — reasoning, no waves | you are deciding what to build next |
| [`extracts/`](extracts/) | material derived from a plan — companions, per-page design specs | you need the detail a plan deliberately left out |

Architecture itself lives at the repo root: [`ARCHITECTURE.md`](../ARCHITECTURE.md).
Per-module API surface lives in each module's `MODULE.md`.

**Code cites these files by path** (`// docs/plans/done/MVP2-PLAN.md §R`). If you move a document,
rewrite the citations in the same commit.

---

## main/ — orientation

| Doc | What it answers |
|---|---|
| [MASTER-MATRIX.md](main/MASTER-MATRIX.md) | every capability in one table, with status, drone-side cost and effort. **The single row-level view** |
| [TWO-TARGETS-PLAN.md](main/TWO-TARGETS-PLAN.md) | what to do on Monday — the hardware track (H1–H4) and the software track (S1–S3) |
| [UX-DESIGN.md](main/UX-DESIGN.md) | what the product is, for whom, and what its surfaces look like |
| [CYCLES-PLAN.md](main/CYCLES-PLAN.md) | how the work is run — alternating backend/UI cycles, delegation model, TX/RX doctrine |

## plans/active/ — unbuilt or gated

| Doc | State |
|---|---|
| [CV-SCALE-PLAN.md](plans/active/CV-SCALE-PLAN.md) | S1/S2 shipped via CV-CONTROL; **S3 model roster, S4 multi-worker pool, S5 pull-based frames unbuilt** |
| [LAYERING-REFACTOR-PLAN.md](plans/active/LAYERING-REFACTOR-PLAN.md) | conventions in force and widely cited; **the class decompositions (matrix K3) are not done** |
| [DRONE-INFRA-PLAN.md](plans/active/DRONE-INFRA-PLAN.md) | I-a/b/e/g shipped; **edge kits (I-d) and command TX stage 3 remain, stage 3 gated** |
| [RC-CONTROL-PLAN.md](plans/active/RC-CONTROL-PLAN.md) | Phase 0 + Phase 1 shipped; **Phase 2 (real airframe) gated on explicit user go** |

## conclusions/ — decisions and investigations

| Doc | Verdict it carries |
|---|---|
| [MOAT.md](conclusions/MOAT.md) | the four structural inversions of a vendor platform — which capabilities are ours alone |
| [ANY-DRONE-PLAN.md](conclusions/ANY-DRONE-PLAN.md) | the adoption funnel: PROBE → DIAGNOSE → REMEDIATE → VERIFY |
| [BASE-COMPUTE-MATRIX.md](conclusions/BASE-COMPUTE-MATRIX.md) | the offload law — what the base computes so the drone doesn't have to |
| [DRONE-COMPONENTS-MATRIX.md](conclusions/DRONE-COMPONENTS-MATRIX.md) | position stack, link classes, component ladder, what each feature costs the owner |
| [FEATURE-MATRIX.md](conclusions/FEATURE-MATRIX.md) | effort/value by persona against an 8-product competitor survey |
| [UX-SIMPLIFY-REVIEW.md](conclusions/UX-SIMPLIFY-REVIEW.md) | where the app overwhelms, and how to make it simple |

## extracts/ — derived detail

| Doc | Extracted from |
|---|---|
| [TRACKING-ORCHESTRATION.md](extracts/TRACKING-ORCHESTRATION.md) | TRACKING-PLAN — boundaries, flow, configuration, visibility |
| [design/](extracts/design/) | NAV-IA-REDESIGN-PLAN — 20 per-page design specs (shell, fly, command, wall, assets, replay, …) |

## plans/done/ — shipped

**Foundations** — [PHASE0-PLAN.md](plans/done/PHASE0-PLAN.md) (skeleton, domain, contracts) ·
[PHASE1-PLAN.md](plans/done/PHASE1-PLAN.md) (RTSP in, HLS out, REST) ·
[WEB-PLAN.md](plans/done/WEB-PLAN.md) (the Angular SPA) ·
[DISCOVERY-PLAN.md](plans/done/DISCOVERY-PLAN.md) (ONVIF/mDNS/V4L2 scan) ·
[REALTIME-PLAN.md](plans/done/REALTIME-PLAN.md) (persistent WHEP + one push channel)

**Milestones** — [MVP1-PLAN.md](plans/done/MVP1-PLAN.md) (the friends demo) ·
[MVP2-PLAN.md](plans/done/MVP2-PLAN.md) (demo → daily tool) ·
[MVP3-PLAN.md](plans/done/MVP3-PLAN.md) (the command point)

**Assets & identity** — [ASSET-MODEL-PLAN.md](plans/done/ASSET-MODEL-PLAN.md) ·
[ASSET-MANAGER-PAGE-PLAN.md](plans/done/ASSET-MANAGER-PAGE-PLAN.md) ·
[U-AUTH-PLAN.md](plans/done/U-AUTH-PLAN.md) ·
[U-SCOPE-PLAN.md](plans/done/U-SCOPE-PLAN.md)

**CV** — [CV-MODELS-PLAN.md](plans/done/CV-MODELS-PLAN.md) (registry + composite) ·
[REMOTE-CV-PLAN.md](plans/done/REMOTE-CV-PLAN.md) (offload to the GB4005 box) ·
[CV-CONTROL-PLAN.md](plans/done/CV-CONTROL-PLAN.md) (live per-stream control + YOLOE) ·
[CV-TRAINING-PLAN.md](plans/done/CV-TRAINING-PLAN.md) (capture→correct→train→promote) ·
[CV-TRAINING-V2-PLAN.md](plans/done/CV-TRAINING-V2-PLAN.md) (one-button training, capture from replay) ·
[TRACKING-PLAN.md](plans/done/TRACKING-PLAN.md) (**the tracking engine, T0–T8, matrix K2**)

**Operations & map** — [OPS-CORE-PLAN.md](plans/done/OPS-CORE-PLAN.md) (geofence, recording, weather) ·
[TACTICAL-MARKS-PLAN.md](plans/done/TACTICAL-MARKS-PLAN.md) ·
[MAP-REWORK-PLAN.md](plans/done/MAP-REWORK-PLAN.md) (the COP — layers, grants, verify/promote)

**Flight** — [FC-INTEGRATIONS-PLAN.md](plans/done/FC-INTEGRATIONS-PLAN.md) (ArduPilot/INAV/Betaflight decode) ·
[RC-CONTROL-PHASE1-PLAN.md](plans/done/RC-CONTROL-PHASE1-PLAN.md) (transmitter → SITL relay)

**UI/UX** — [UX-REWORK-PLAN.md](plans/done/UX-REWORK-PLAN.md) ·
[UX-QUICKWINS-PLAN.md](plans/done/UX-QUICKWINS-PLAN.md) ·
[UI-STRUCTURE-PLAN.md](plans/done/UI-STRUCTURE-PLAN.md) (feature folders) ·
[UI-ARCHITECTURE-PLAN.md](plans/done/UI-ARCHITECTURE-PLAN.md) (Component→Facade→Store→Service) ·
[UI-STATE-PLAN.md](plans/done/UI-STATE-PLAN.md) (overlay lifecycle) ·
[UI-REDESIGN-PLAN.md](plans/done/UI-REDESIGN-PLAN.md) (hub-and-spoke IA) ·
[NAV-IA-REDESIGN-PLAN.md](plans/done/NAV-IA-REDESIGN-PLAN.md) (the shell) ·
[STYLE-TOKENS-PLAN.md](plans/done/STYLE-TOKENS-PLAN.md) ·
[VISUAL-REFRESH-PLAN.md](plans/done/VISUAL-REFRESH-PLAN.md) (daylight theme)

---

**A doc in `plans/done/` is not frozen truth** — it records the spec as delivered. Where a shipped
plan's own header still says "proposed" or "draft", the code that cites it is the authority.
