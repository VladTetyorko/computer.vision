# 05 — Asset detail

**Files:** `features/asset-detail/**`
**Wave:** 2 (page bar), 4 (drill-in flattening)

## Current state

The most information-dense page after the cockpit, and largely well-built. Title `11` with chips
(`Simulated` `ACTIVE` `Streaming`); actions `All devices` / `Rename asset…` / `Archive asset`; a
streaming notice with `Open cockpit` / `Watch live`; then a 2-column body:

- **Position** — Leaflet map + `POSITION` / `ALTITUDE` / `HEADING` / `BATTERY` / `SAMPLE`, and a
  `Full telemetry ›` link.
- **Utilization** — 5 stat tiles (`TOTAL FLIGHT TIME`, `FLIGHTS`, `LAST FLOWN`, `AVG FLIGHT`, `BATTERY`).
- **Recent flights** — a small bar chart with `Oldest` / `Most recent` axis labels.
- **More on this asset** — a "DRILL-IN" panel of 6 buttons: Full telemetry, Events, Usage history,
  Hardware & devices, Characteristics, Pilots.

## Problems

- **Six drill-in buttons are six page navigations** (F6) for what is one entity's data. The panel is
  effectively a second, page-local navigation menu — the same anti-pattern as the hubs, one level down.
- `BATTERY` appears twice, in Position and in Utilization, with the same `0%`.
- The `Recent flights` chart shows one bar and two axis labels; with n=1 it carries no information but
  occupies a half-width panel.
- `Utilization`'s 5 tiles stretch to fill 700px, so each stat floats in ~140px of empty card.
- `All devices` (goes to `/devices`) sits in the title row next to destructive `Archive asset`.
- Duplicate `page-head` blocks in the template (lines 3 and 18) for the loading/loaded states.

## Suggested design

Turn the drill-in menu into **tabs on this page** — same URL space, no context loss.

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎✦ │ ‹ Assets   11  ● Streaming  Simulated  ACTIVE    [⋯] [Cockpit]│
│    ├──────────────────────────────────────────────────────────────┤
│    │ Overview │ Telemetry │ Events │ Usage │ Hardware │ Pilots     │
│    ├──────────────────────────────────────────────────────────────┤
│    │ ┌─────────────────┐ ┌──────────────────────────────────────┐ │
│    │ │ POSITION        │ │ 44m      1        43m ago      0%     │ │
│    │ │  [   map    ]   │ │ flight   flights  last flown  battery │ │
│    │ │ 50.450  30.517  │ ├──────────────────────────────────────┤ │
│    │ │ alt —   hdg 0°  │ │ RECENT FLIGHTS                       │ │
│    │ └─────────────────┘ │ ▁▂▅▃█                                │ │
│    │                     └──────────────────────────────────────┘ │
└────┴──────────────────────────────────────────────────────────────┘
```

- **Tab strip replaces the drill-in grid.** `/assets/:id` → `/assets/:id/{overview,telemetry,events,
  usage,hardware,pilots}`. Child routes, lazy, so nothing loads until its tab is opened. The six
  destinations already exist as pages — they become tab bodies.
- **Breadcrumb `‹ Assets`** returns to the list with its filters intact.
- **Actions collapse into one `⋯` kebab** except the primary (`Open cockpit` / `Watch live`).
  `Archive asset` moves inside the kebab behind a confirm — destructive actions should not be one
  mis-click from `Rename`.
- **Battery de-duplicated** — it belongs in Utilization (the summary strip), not on the Position card.
- **Utilization becomes a 4-up stat strip** across the top of Overview rather than a boxed card, so
  the numbers read as a header line, not a panel.
- **Recent flights hides below n=3** and shows `Not enough flights yet` — a chart with one bar is
  noise.

## Refactor list

- **Add** child routes under `ASSET_DETAIL_ROUTES` for the six tabs; move the existing drill-in target
  pages under them.
- **Delete** the `More on this asset` panel and `tile-accent` usage here.
- **Extract** the title row into `vision-page-bar` with a `breadcrumb` input.
- **Merge** the two `page-head` blocks; loading state becomes a skeleton inside one layout.
- **Move** `BATTERY` out of the Position card.
- **Guard** `Recent flights` on `flights.length >= 3`.

## Acceptance

- Switching tabs does not remount the map or refetch the asset.
- `Archive asset` requires a confirm and is not adjacent to `Rename`.
- Back from any tab returns to `/assets` with filters and selection intact.
