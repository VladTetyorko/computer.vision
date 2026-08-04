# 10 — Replay

**Files:** `features/replay/**`
**Wave:** 4 (needs a backend list endpoint)

## Current state

**Broken as a navigation destination (F8).** Monitor → **Replay library** ("Scrub any finished
flight, frame by frame") routes to `/replay`, which renders:

> **Replay unavailable**
> No usage specified.
> `[ Back ]`

`ReplayPage` is a *detail* view keyed on a usage id (`/assets/:assetId/replay/:usageId`). There is no
library. `/monitor/replay` redirects to `/replay`, so the nav entry advertises a page that has never
existed.

## Problems

- The nav promises a feature and delivers an error state. This is the worst kind of dead end: it looks
  like a bug in a working feature rather than an unbuilt one.
- The error state is indistinguishable from a real failure — same styling as "stream unavailable".
- `/replay` with no params is not a meaningful URL for a detail view; it should not be routable at all.

## Suggested design

### Immediate (wave 1, one line)

Mark the nav entry `badge: 'soon'` and route `/monitor/replay` at `ComingSoon`, exactly as Missions
and Firmware do. This is honest, costs nothing, and removes the false-bug.

> **Correction (applied in wave 1).** An earlier draft of this file also said to delete the bare
> `/replay` route. That was wrong: `/replay?asset=…&usage=…&t=` is a **shipped deep link**
> (docs/OPS-CORE-PLAN.md §Q1) with three live callers — `shared/ui/notification-bell.ts`,
> `features/wall/wall-facade.ts` and `features/alerts/alerts-facade.ts` all navigate to it to jump
> from a detection event straight to that moment in the recording. Deleting the route would have
> 404'd all three. The route stays; only the *navigation entry* that led to its empty state changed.
> F8's acceptance criterion — "no navigation path reaches 'Replay unavailable'" — is met either way,
> and this way nothing working breaks.

### Real (wave 4) — build the library

```
┌────┬──────────────────────────────────────────┬────────────────────┐
│ ▎⏵ │ ⏵ Replay · 12 flights  [asset ⌄][30d ⌄]  │  11 · 04 Aug 10:36 │
│    ├──────────────────────────────────────────┤  44m 02s           │
│    │ ASSET     STARTED        DUR    EVENTS   │ ┌────────────────┐ │
│    │ ▎11       04 Aug 10:36   44m    50       │ │   thumbnail    │ │
│    │  11       03 Aug 14:02   12m     3       │ └────────────────┘ │
│    │  Falcon-2 02 Aug 09:15   31m    17       │  50 events         │
│    │                                          │  ▁▂▅█▃▁ over time  │
│    │                                          │  [Open replay ›]   │
└────┴──────────────────────────────────────────┴────────────────────┘
```

- **List of finished usages**, newest first — asset, start, duration, event count, and a thumbnail.
- **Two-pane** like the rest of wave 3; the panel previews before committing to a full player load.
- **Event-density sparkline** per usage so an operator can spot the interesting flight without opening
  each one.
- `Open replay ›` navigates to the existing `/assets/:assetId/replay/:usageId` player — that page
  already works and is not being redesigned here.

### Frozen wire contract (wave 4)

`AssetUsage` already carries `id, assetId, startedAt, endedAt, startPosition, lastPosition,
sampleCount, streamId`. The only genuinely missing piece is a **fleet-wide** query —
`AssetUsageRepositoryPort` has `findRecentByAsset(assetId, limit)` and no cross-asset equivalent.

```
GET /api/usages?limit=50[&assetId=<uuid>]
200 → [                                   // newest first
  {
    "usageId":        "uuid",
    "assetId":        "uuid",
    "assetName":      "Falcon-2",         // resolved for display; "" if the asset is gone
    "startedAt":      "2026-08-04T10:36:29.895Z",
    "endedAt":        "2026-08-04T11:20:00.000Z",  // null while the flight is still open
    "durationSeconds": 2610,                        // null while open
    "sampleCount":     1234
  }
]
```

**Scoped out of v1, deliberately** — the earlier sketch above showed a thumbnail and an
event-density sparkline. Neither is buildable honestly today: nothing stores a per-usage frame (the
only image endpoint returns a stream's *current* frame, which would fabricate a value for a finished
flight — the same trap that stopped the Alerts frame preview in wave 3), and detection events are
not joined to usages. The list ships with asset, start, duration and sample count, which is enough
to pick a flight. Revisit when event↔usage association exists.

## Refactor list

**Wave 1:** `nav-entries.ts` — Replay entry gets `badge: 'soon'`; `/monitor/replay` routes at
`ComingSoon` instead of redirecting through the empty state. The bare `/replay` route is **kept** —
see the correction above.

**Wave 4:** add `replay-library.ts` + route `/replay`; add the read model + endpoint; adopt
`shared/ui/two-pane`; restore the nav entry to functional (drop the badge).

## Acceptance

- **Wave 1:** no navigation path reaches "Replay unavailable — No usage specified."
- **Wave 4:** `/replay` lists finished flights and opens any of them in the existing player.
