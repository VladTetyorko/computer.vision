# VISUAL-GEO-RESEARCH — context (started 2026-08-19)

## Why this file exists
The visual-geolocation branch `feat/visual-geo` (VPR against satellite tiles) is parked: the
retrieval engine never cleared its own §5 gate (recall@1 ≥ 0.85; measured 0/12 top-1 on the
real-video probe, correct tile median rank 14 of ~40). Abstention is sound, ranking is not.
Before any new build, the user asked for a **full research context**: approaches, best practices,
working algorithms and services for camera-vs-satellite/aerial geolocation, organised into two
sub-ways:

1. **Light** — onboard the drone: mission corridor + road/landmark data preloaded before flight,
   cheap matching, GNSS-denied hold / drift bounding.
2. **Heavy** — station-side: most accurate, compute-rich, double-checks and corrects the
   telemetry stream (position / heading / AGL) after the fact or with latency.

## Inputs
- Branch `feat/visual-geo` — `docs/VISUAL-GEO-PLAN.md` (§12 Wave-0 results, §13 any-angle tiered
  architecture, §13.6 fields synthesis, §13.7 line/edge matching), `cv-service/spikes/geo/`,
  `adapters/adapter-geo-grpc/MODULE.md`, `adapters/adapter-tiles/MODULE.md`.
- Master geolocation stack — `GeoProjection`/`CameraAim` (kernel), `docs/plans/active/GEO-POSE-PLAN.md`,
  `FIXED-CAMERA-GEO-PLAN.md`/`-CONTEXT.md`, `TACTICAL-MARKS-PLAN.md`, `MISSIONS-PLAN.md`,
  `DRONE-INFRA-PLAN.md`, `docs/conclusions/ANY-DRONE-PLAN.md`.
- User-supplied seeds: ISPRS Archives XL-1 381 (2014); arXiv 2407.14910.

## Outputs (research reports, one file per agent)
`docs/conclusions/visual-geo-research/`
- `00-existing-state.md` — what the parked branch built, measured, and already surveyed
- `01-master-geo-stack.md` — contracts on master where a fix/correction plugs in
- `10-classical-registration.md` — seeds + photogrammetric / template / TRN literature
- `11-deep-crossview-vpr.md` — UAV↔satellite deep retrieval, re-ranking, datasets, SOTA
- `12-light-onboard.md` — GNSS-denied onboard nav: products, open source, compute budgets
- `13-heavy-server.md` — dense matching, SfM/ortho, sequence filters, data/tile/DEM services
- `VISUAL-GEO-RESEARCH.md` (in `docs/conclusions/`) — synthesis + recommended sub-way designs

## Status
- [x] context agents run (00, 01)
- [x] research agents run (10, 11, 12, 13) — ~270 refs
- [x] synthesis written: `docs/conclusions/VISUAL-GEO-RESEARCH.md`

## Outcome (2026-08-19)
Diagnosis confirmed: ranking fails from sensor/domain gap, fix is pipeline order (rectify → retrieve →
geometric re-rank → sequence fuse), mostly already built-but-unwired on the branch. Recommended: heavy-A
(XFeat+LightGlue on OpenVINO + DEM ray-cast + robust smoothing, corrected-track stream) first; light-1
(dead-reckon + periodic tile-template correction → GPS_INPUT, corridor pack via MISSIONS) second.
Five user decisions listed in the synthesis §3. Next artefact: VISUAL-GEO-V2-PLAN.md (not started).
