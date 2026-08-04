# 01 — Fly (drone picker + cockpit)

**Files:** `features/fly/**`
**Wave:** 1 (chrome), 4 (routing + overlay fixes)

## Current state

`/fly` renders one of two things from the same route:

**Picker** — `page-head` ("Fly" + 2-line description) and a grid of drone cards. With one drone:
a single 210×140 card at the top-left of an 1854px viewport, showing name, `Streaming` chip,
category, `LAST SEEN`, `POSITION`, and an `Enter cockpit →` link.

**Cockpit** — full-bleed video with burned-in detections, a `FAILSAFE ACTIVE` banner, telemetry
overlay top-left, `DRONE` selector + `Bring home` top-right, a Leaflet minimap bottom-left with
basemap/Following/Expand controls, a 4-icon rail on the right edge, and a bottom strip of
`POWER` / `NAV` / `LINK` / `ENV` chip groups with a `Less` toggle and `Stop stream`.

## Problems

- **Picker uses ~13% of the viewport** (F4) — a huge empty canvas for what is a chooser.
- **Picker is a dead-end page for the common case.** With one streaming drone there is nothing to
  choose; the operator still lands here first every session.
- **Cockpit is not addressable** (F12) — entering it does not change the URL. No bookmark, no
  refresh, no sharing, and Back does not exit it.
- **Cockpit clipping** (F11): `Bring home` is cut by the right edge behind the `DRONE` selector; the
  telemetry overlay's first glyph is eaten by the collapse chevron — `lat 50.44829` renders as
  `at 50.44829`.
- **Global header steals 40px** above the failsafe banner in a piloting view.
- The right icon rail's four buttons are unlabeled with no tooltips.
- The minimap floats over the video's bottom-left, occluding exactly where ground detections appear.

## Suggested design

### Picker — a compact chooser, not a page

```
┌──────────────────────────────────────────────────────────────────┐
│ 🛩 Fly · 1 drone                              [⟳]  [+ Add source] │
├──────────────────────────────────────────────────────────────────┤
│ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐            │
│ │ ● 11          │ │   Falcon-2    │ │   Rover-1     │            │
│ │   STREAMING   │ │   idle        │ │   offline     │            │
│ │ ▓▓▓▓▓▓▓▓▓▓▓▓▓ │ │   ▓▓▓▓▓▓▓▓▓▓▓ │ │   ▓▓▓▓▓▓▓▓▓▓▓ │  ← live     │
│ │ 50.4484 30.52 │ │               │ │               │    thumb    │
│ │ [Enter cockpit]│ │ [Enter]      │ │ [Enter]       │            │
│ └───────────────┘ └───────────────┘ └───────────────┘            │
└──────────────────────────────────────────────────────────────────┘
```

- Cards become **auto-fill 280px** across the full fluid width — the grid finally fills the screen.
- Each streaming card carries a **live thumbnail** (single WebRTC keyframe poll, not a full player) —
  turning an abstract list into a visual chooser. This is the one place a card grid is right.
- **Skip the picker when it has nothing to ask**: if a remembered drone is still streaming, `/fly`
  redirects straight to `/fly/:assetId`. The picker remains reachable at `/fly` via the sidebar and a
  `⌂ All drones` control in the cockpit.
- Streaming drones sort first (already true), offline ones dim rather than disappear.

### Cockpit — own the whole viewport

```
┌────┬─────────────────────────────────────────────────────────────┐
│ ▎🛩│  ⚠ FAILSAFE ACTIVE                                          │  ← banner only
│  ▦ ├─────────────────────────────────────────────────────────────┤
│  ☑ │ lat 50.44829  lon 30.51913     [DRONE 11 ⌄]  [Bring home]   │  ← safe-area row
│    │ alt 0%                                                      │
│  ◎ │                                                             │
│  ⚠ │                    video (full bleed)                       │
│  ⟲ │                                                             │
│    │                                                    ┌──────┐ │
│  ✦ │                                                    │ mini │ │  ← moved right
│  + │                                                    │ map  │ │
│    │                                                    └──────┘ │
│    ├─────────────────────────────────────────────────────────────┤
│    │ POWER 0% ARMED │ NAV 50.44 30.51 288° │ LINK 93% │ ENV GO   │
│    │                    [ Stop stream ]                          │
└────┴─────────────────────────────────────────────────────────────┘
```

- **Sidebar auto-collapses to the 56px rail** — navigation stays reachable, chrome drops from 72px to
  0px of *horizontal* band. No global header.
- **Overlay safe-area**: a single top row with `padding-inline: var(--space-16)` holding telemetry
  left and drone/actions right, so nothing can clip either edge (fixes both clipping bugs).
- **Minimap moves to bottom-right.** Ground targets enter frame low-left in the sample footage; the
  map was sitting on them. `Expand` promotes it to a 50/50 split.
- **Right icon rail gets tooltips + `aria-label`**, and moves to sit under the minimap so one edge
  owns all secondary controls.
- **URL becomes `/fly/:assetId`** (F12) — bookmarkable, refresh-safe, shareable, Back exits to the
  picker.

## Refactor list

- **Route:** add `{ path: 'fly/:assetId' }` to `fly.routes.ts`; `/fly` resolves the remembered drone
  and `redirectTo` when it is live, else renders the picker.
- **Split the component**: `fly.ts` currently switches picker/cockpit internally. Extract
  `drone-picker.ts` (route `/fly`) and `cockpit.ts` (route `/fly/:assetId`) — they share nothing but
  the asset store.
- **Add** `fullBleed: true` to those routes' `data`, read by the shell to auto-collapse the sidebar.
- **Overlay CSS**: replace absolutely-positioned corners with a grid overlay
  (`grid-template-rows: auto 1fr auto`) that respects a `--overlay-pad`.
- **Picker grid**: `repeat(auto-fill, minmax(280px, 1fr))`.
- **Live thumbnails**: reuse `shared/player/**`'s existing snapshot path; poll at 1 fps, pause when
  the tab is hidden.

## Acceptance

- `/fly/:assetId` loads the cockpit directly on refresh.
- At 1280×720 no cockpit overlay text is clipped at either edge.
- Picker fills the viewport width at 1854px and at 1280px.
