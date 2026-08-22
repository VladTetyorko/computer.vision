# CV-FLY-INTERACTION — task context

Task started: 2026-08-20. Owner ask (verbatim intent): rethink the computer-vision flow on `/fly`
from the UI perspective — "not as efficient and clear how to use as it could be, because of many
detections overlaying each other". Check current functions, check analogs, propose future work.
Deliverable: a research/design `.md` (this task produces `CV-FLY-INTERACTION-RESEARCH.md`).

## Scope split vs prior work

- `docs/plans/active/CV-UX-RESEARCH.md` (2026-08-16, **specced not built**, waves U1–U5) already
  covers the **Detection settings panel** (15 controls → 3 tiers, intent cards, honest copy). This
  task does NOT redo it — it covers the **on-video layer**: boxes, labels, overlap/clutter,
  hover/click interactions, the detections strip, and how an operator actually consumes detections
  mid-flight.
- `docs/conclusions/UX-SIMPLIFY-REVIEW.md` F4 flagged the 7-drawer cockpit rail; grouping is
  proposed there, not here.

## Current-state facts (verified in source, 2026-08-20)

- Overlay draw path: `shared/player/player.ts#redrawOverlay/drawBox/drawTrails` (~l.1781–1930) +
  pure logic `shared/player/detection-overlay-logic.ts`.
- Every detection in the selected batch draws box + always-on label ("#id label conf%", fixed 14 px,
  top-left, filled background) — **no label decluttering, no collision handling, no min-box-size
  gate, no confidence de-emphasis, no cluster merge**. Dense scenes = overlapping labels.
- Colors: per-track hash hue when `track` present, else per-model hue (default `#4f8cff`); hover =
  amber highlight + DOM tooltip; click on tracked box = FOLLOW lock (`trackFollowed`).
- Trails: 2 s fading polyline per track, drawn under boxes.
- Batch selection is latency-synced (`selectDetectionResult`, HLS behind-live / WHEP getStats).
- Boxes modes: `overlay | burned | off`; default is **`burned`** when the stream carries burn-in
  (server Java2D burn-in is on by default) — so the crisp, interactive client overlay is NOT the
  default experience; burned frames have their own baked style and no hover/click.
- Detections drawer: `shared/player/detections-strip.ts` — last ~8 label chips + CV dot; separate
  drawer from the cv panel; no linkage to boxes (no hover-sync, no click-to-hide).
- Store: `core/detections/detections-store.ts` — 2 s poll fallback or SSE `detections:<assetId>`,
  keeps 50 batches.
- Backend already serves but SPA doesn't read: measured `rate`/`latency` on `GET .../tracks`
  (CV-UX-RESEARCH §1.2).

## Known constraints

- Frozen wire contract: `PATCH /api/streams/{id}/config`, `GET /api/cv/models`, tracking lock —
  prefer pure-frontend proposals; list backend candidates separately (CV-UX-RESEARCH §9 pattern).
- UX-DESIGN.md §7: honesty rules (measured numbers, named states, no invented data).
- labelFilter is a drop-before-fan-out (screen+alerts+recording), not display-only.
- Angular: 3-file components, facade/store layering, frontend-style skill.

## Plan of this task

1. ✔ Read prior docs + overlay/strip/store sources (this file's facts).
2. Analog research (web): drone GCS (DJI, Skydio, Anduril Lattice), VMS/NVR (Frigate, Milestone),
   ATAK, broadcast/AR tracking overlays, avionics HUD declutter, label-placement literature.
3. Write `docs/plans/active/CV-FLY-INTERACTION-RESEARCH.md`: diagnosis → operator tasks → proposed
   interaction model → analog evidence → ranked waves (pure-FE first) → backend candidates.
4. Close: summarize outcome here, update memory index.

## Outcome (filled at close)

Delivered `docs/plans/active/CV-FLY-INTERACTION-RESEARCH.md` (2026-08-20): diagnosis of 9 clutter/
interaction defects D1–D9, analog survey (Frigate, DJI, Lattice/ATAK, HUD declutter, broadcast),
proposed model "boxes are the index, one target is the story", waves V1–V6, 3 backend candidates.

**Escalated same day by owner decision** into `docs/plans/active/CV-CLEAN-FEED-PLAN.md` (remove
burn-in entirely; two streams video+data; fix the filter; compact drawers/feeds) and **implemented**
on `feat/cv-clean-feed`:

- `283df88e` docs — research + plan
- `adc66bc1` W3 web — burned mode removed, overlay default, staleness fade + paused notice
- `8eaa4253` W4 web — priority tiers, label collision-yield, All/Priority/Locked-only/Off
- `a4735f24` W1 backend — adapter-overlay module deleted, OverlayPort/burnedIn off the wire,
  labelDenyFilter added + enforced pre-fan-out; CameraPose telemetry supplier kept unconditional
- `2148c773` cv/grpc test follow-up (155/155; label filtering confirmed off the gRPC wire)
- `2b3237d8` W5 web — one Vision drawer (rail 7→6), deny-list strip chips (counts/hover/click-hide),
  DetectionsStore owns the tracks poll

Per-module verification all green: perception 560, api 753, app 242 (Docker), cv/grpc 155,
web 2371 tests + tsc + prod build. **W6 still open at close of this session:** full-reactor verify
(first attempt was killed externally) and the live `/fly` wire smoke (no `burnedIn` on wire,
labelDenyFilter drop observed live, poses flowing without overlay). Known follow-ups: replay box
overlay (plan §6.1), tracks-in-SSE (§6.2), `alerting` wire flag (§6.3), `DetectionExtrapolator#at()`
now dead code (perception MODULE.md gotcha).

**W6 smoke, first finding (2026-08-20):** owner observed boxes trailing moving objects — the
deleted burn-in path had been velocity-projecting boxes onto each published frame
(`DetectionExtrapolator.at`), a catching-up the client overlay never had. Fixed as wave W7
(plan §7): projection ported to `detection-overlay-logic.ts` mirroring the server's tuning
(track match / 0.15 gate / 800 ms cap-then-freeze), applied per redraw at the estimated
on-screen instant. `a8f12b6a` docs + `d775c4de` web (2400 tests, tsc, prod build green).
Known bound: under the 2 s poll fallback boxes freeze at the 800 ms cap — §6.2 (feed into SSE)
is the real fix. W6 remains open: re-run the live smoke to confirm the trailing is gone.
