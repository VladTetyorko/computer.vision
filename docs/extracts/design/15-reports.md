# 15 — Inventory reports

**Files:** `features/reports/**`
**Wave:** 2

## Current state

The best-composed page after Command and the cockpit. `page-head` ("Inventory reports" + description),
a notice ("Exportable, generated reports are coming — everything below is live from the same summary
Command uses, just not downloadable yet"), then:

- **Fleet at a glance** — 5 stat tiles: `TOTAL ASSETS 1`, `STREAMING NOW 1●`, `ACTIVE 1`,
  `DEACTIVATED 0`, `NEEDS ATTENTION 1` (red).
- **Assets by category** — a horizontal bar (`Simulated ▓▓▓▓ 1`).
- **Needs attention** — `1 asset(s) flagged`, then `11 [Simulated]` with two `Critical` rows
  ("Failsafe active — returning to home.", "Battery critical at 0%.").

## Problems

- **Nothing here links anywhere.** `NEEDS ATTENTION 1` is the most actionable number in the app and it
  is not a link. The flagged asset `11` is not a link. A report that surfaces a critical battery
  should be one click from that asset's cockpit.
- **`Assets by category` with one category is a single bar** — a chart that will not earn its half of
  the row until there are 3+ categories.
- **No time dimension.** "Inventory reports" implies trend (fleet growth, utilisation over time); the
  page is entirely instantaneous. Every number here is already on `/command`, which makes the page's
  distinct purpose unclear.
- 5 stat tiles stretched across 1100px leave each number floating in ~140px of empty card.
- The `page-head` + notice cost 170px above a page whose content is ~300px tall.
- `1 asset(s) flagged` — placeholder pluralisation, as on `/assets`.

## Suggested design

Give the page the thing Command does not have: **time**, and make every number a door.

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎📊│ 📊 Reports        ( 7d │ 30d │ 90d )              [⤓ Export] │
│    ├──────────────────────────────────────────────────────────────┤
│    │  1 total    1 streaming    1 active    0 deactivated   1 ⚠   │
│    │  ─────────────────────────────────────────────────────────   │
│    │ ┌───────────────────────────┐┌─────────────────────────────┐ │
│    │ │ FLIGHT HOURS · 30d        ││ NEEDS ATTENTION          1  │ │
│    │ │  ▁▂▃▅▇█▅▃                 ││ ▸ 11        Simulated       │ │
│    │ │  44h total                ││   ⬤ Failsafe — returning    │ │
│    │ └───────────────────────────┘│   ⬤ Battery critical 0%     │ │
│    │ ┌───────────────────────────┐│                             │ │
│    │ │ BY CATEGORY               ││ [Open cockpit]              │ │
│    │ │ Simulated ▓▓▓▓▓▓▓▓  1     │└─────────────────────────────┘ │
│    │ └───────────────────────────┘                                │
└────┴──────────────────────────────────────────────────────────────┘
```

- **Stat tiles become a single header strip** — one row of number+label pairs, no boxes. Each is a
  link: `STREAMING NOW` → `/wall`, `NEEDS ATTENTION` → this page's own attention panel,
  `TOTAL/ACTIVE/DEACTIVATED` → `/assets?status=…`.
- **A range selector (`7d | 30d | 90d`)** and a **flight-hours trend** give the page a reason to
  exist next to Command.
- **Every flagged asset row links to its cockpit**, with the primary action inline.
- **`Assets by category` renders only at ≥3 categories**; below that it collapses to a text line.
- **`⤓ Export`** ships as CSV of the current view — that is the page's promised verb, and CSV needs no
  new backend rendering. PDF stays "soon".
- Notice deleted once Export ships; `asset(s)` pluralisation fixed everywhere.

## Refactor list

- **Convert** the stat tiles to a `vision-stat` strip (component exists) with `routerLink`s.
- **Add** the range selector + flight-hours series (check for an existing usage-aggregate endpoint
  before adding one; `AssetUsage` holds the data).
- **Link** flagged assets; add `Open cockpit`.
- **Guard** the category chart on `>= 3`.
- **Add** CSV export (client-side from the loaded view model).
- **Replace** `page-head` + notice with `vision-page-bar`.

## Acceptance

- Every number on the page is either a link or explicitly non-navigable by design.
- `NEEDS ATTENTION` reaches the offending asset in one click.
- Export produces a CSV matching the on-screen figures.
