# 17 — Pre-flight checklist

**Files:** `features/preflight/**`
**Wave:** 2, then folded into the cockpit (wave 4)

## Current state

`page-head` ("Pre-flight checklist" + "The live status card for any drone — video feed, telemetry
link, GPS fix, battery, armable."), a notice ("Saved, editable checklist templates are coming — the
card below is today's live status only"), a `DRONE` select, and a 195×190 card:

```
PRE-FLIGHT
✓ VIDEO FEED
✓ TELEMETRY LINK
✓ GPS FIX          3D
✗ BATTERY          0% — below 45% minimum.
✓ ARMABLE          Armed.
```

## Problems

- **~11% of the viewport used** (F4) — a 195px card under 210px of header, notice and select.
- **It is a separate page from the thing it gates.** A pre-flight check is the step immediately before
  flying; making it a nav destination means the operator must visit it, remember the result, then
  navigate to the cockpit. Nothing carries the check forward.
- **The check has no outcome.** A red `BATTERY ✗` produces no verdict, no block, no acknowledgement —
  the page states facts and stops. There is no "GO / NO-GO".
- The `DRONE` select duplicates the cockpit's own drone selector.
- All five items are already displayed in the cockpit's bottom telemetry strip
  (`POWER 0% ARMED`, `NAV … 13 sat`, `LINK 93%`) — this page restates them with different labels.
- 190px of card is 60% empty on its right side; the status detail ("below 45% minimum") wraps under
  its label instead of aligning in a column.

## Suggested design

**Fold pre-flight into the cockpit as a gate, and keep a thin page for the fleet view.**

### In the cockpit (the real fix)

```
┌─────────────────────────────────────────────────────────────┐
│  ⚠ NO-GO — battery 0% (min 45%)              [Override ⌄]   │
├─────────────────────────────────────────────────────────────┤
│  ✓ video   ✓ telemetry   ✓ GPS 3D   ✗ battery   ✓ armable  │
└─────────────────────────────────────────────────────────────┘
```

- A **one-row verdict strip** at the top of the cockpit before/while streaming: `GO` (collapsed to a
  single green line) or `NO-GO` with the failing item named.
- `Override` requires an explicit acknowledgement and is recorded to activity — a check that can be
  ignored silently is not a check.
- Items expand on click for detail; they read from the same telemetry the bottom strip uses, so there
  is one source and two presentations, not two implementations.

### The page (`/operate/preflight`)

Becomes the **fleet readiness board** — the thing a page can do that an in-cockpit strip cannot:

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎☑ │ ☑ Pre-flight · 1 of 3 ready                            [⟳]  │
│    ├──────────────────────────────────────────────────────────────┤
│    │ ASSET      VIDEO  TELEM  GPS   BATTERY  ARMABLE   STATUS     │
│    │ 11           ✓     ✓     3D    ✗ 0%      ✓        NO-GO      │
│    │ Falcon-2     ✓     ✓     3D    ✓ 87%     ✓        GO         │
│    │ Rover-1      ✗     ✓     —     ✓ 62%     ✗        NO-GO      │
└────┴──────────────────────────────────────────────────────────────┘
```

- One row per asset, one column per check — answers "what can fly right now" at a glance, which is a
  real question the app currently cannot answer.
- The `DRONE` select disappears (the table covers every drone).
- Rows link to the cockpit.

## Refactor list

- **Add** the verdict strip to the cockpit; derive `GO`/`NO-GO` in a pure `core/` function shared with
  this page (one rule set, two renderings).
- **Rewrite** the page as a per-asset matrix; delete the drone select and the single card.
- **Replace** `page-head` + notice with `vision-page-bar`.
- **Record** overrides through the existing activity/audit path.
- **Confirm** the minimum-battery threshold's source (hard-coded 45% vs configurable) before building
  the verdict rule — if configurable it belongs next to detection defaults in `/settings/detection`.

## Acceptance

- The cockpit shows a GO/NO-GO verdict without leaving it.
- The page answers "which drones are ready" without per-drone selection.
- An override is recorded and visible in Activity.
