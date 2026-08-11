# 09 — My activity

**Files:** `features/activity/**`
**Wave:** 2 (page bar), 3 (density)

## Current state

`page-head` ("My activity" + "The changes you've made recently, newest first."), then a panel with
three rows: a `Created` chip, an entity-type chip (`ASSET` / `DEVICE`), the message
("Created asset 11 with 2 source(s)"), and a right-aligned relative time ("43m 01s ago").

## Problems

- **Same sparse-row problem as Alerts** (F4) — a 1070px row holding ~35 characters, with the
  timestamp exiled to the far right edge.
- **No filtering, no grouping, no pagination.** At 3 entries this is fine; at 3 000 it is unusable,
  and the page offers no affordance to narrow.
- No date grouping — "43m 01s ago" ×3 gives no sense of session or day boundaries.
- The `Created` chip and the message both say "created"; the chip is redundant with the verb.
- Reachable from two places (Monitor nav + avatar menu) with the same name — acceptable as a
  shortcut, but the page never indicates scope: is this *my* activity or the org's? The title says
  "My", the Monitor placement implies fleet-wide.

## Suggested design

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎⟲ │ ⟲ Activity   ( Mine │ Everyone )  [type ⌄] [7 days ⌄]   [⟳] │
│    ├──────────────────────────────────────────────────────────────┤
│    │ TODAY                                                        │
│    │  10:36  ASSET   Created asset 11 with 2 sources        admin  │
│    │  10:36  DEVICE  Registered source 11 · telemetry (sim) admin  │
│    │  10:36  DEVICE  Registered source 11 · video (file)    admin  │
│    │ YESTERDAY                                                    │
│    │  17:02  ASSET   Archived asset Rover-1                 admin  │
└────┴──────────────────────────────────────────────────────────────┘
```

- **Day-grouped rows** with absolute times in a left gutter — an audit log is read by "when", so time
  leads. Relative time moves to a `title` tooltip.
- **`Mine | Everyone` segmented control** resolves the scope ambiguity, and makes the Monitor
  placement honest (managers want fleet-wide; the avatar-menu link deep-links to `Mine`).
  `Everyone` is `managerOnly`.
- **Type + date-range filters** in the bar, so the page survives real data volume.
- **Actor column** appears only in `Everyone` scope.
- **Verb becomes the row's colour accent**, not a chip — `Created` green, `Updated` blue, `Archived`
  amber, `Deleted` red. Removes 3 chips per row while adding scan-ability.
- Infinite scroll with a `Load more` fallback.

## Refactor list

- **Replace** `page-head` with `vision-page-bar` carrying the scope toggle + filters.
- **Add** day grouping (pure function in `core/`, unit-tested — it is date logic, not view code).
- **Add** `scope` / `type` / `since` query params, all addressable.
- **Add** the `Everyone` scope behind `canManageOrg` (reuse `core/org/org-logic.ts`).
- **Delete** the verb chip; add the accent border.
- **Add** pagination (the backend list endpoint already pages — confirm before wiring).

## Acceptance

- 30 entries visible at 1080p.
- Day headers separate entries across a date boundary.
- `?scope=everyone` is refresh-safe and hidden from PILOT.
