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
proposed model "boxes are the index, one target is the story" (priority tiers at draw time,
label-collision yield, focus-on-hover/lock dimming, strip↔box linkage, click-to-hide), waves V1–V6
all pure frontend, 3 backend candidates listed separately. Not implemented — research only.
