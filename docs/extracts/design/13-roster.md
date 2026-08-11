# 13 — Pilots / roster

**Files:** `features/roster/**`
**Wave:** 2 (page bar), 3 (two-pane)

## Current state

`page-head` ("Pilots / roster" + "Every asset's pilot assignments in one place — expand an asset to
add or remove a pilot."), a full-width search input, and one collapsed accordion row:
`› 11 [Simulated] No pilots assigned`.

## Problems

- **The page is an accordion over assets, not a roster over pilots.** Its name promises "who flies
  what"; its structure answers "what does asset X have". A manager asking "what is Pilot A assigned
  to" must expand every asset.
- ~11% of the viewport used (F4): one 36px row under 170px of header and search.
- The search input is 1070px wide for a name query.
- Everything is behind a disclosure — with 1 asset that is 1 click; with 40 assets the page is 40
  clicks to read.
- No indication of how many pilots exist, or which are unassigned. `No pilots assigned` is stated per
  asset with no fleet-level summary.
- Duplicates a concern with `/org` (who the users are) and `/assets/:id` → Pilots tab (per-asset
  assignment) without being clearly the place that owns it.

## Suggested design

A two-way matrix, pivotable, with assignment as a direct manipulation rather than an accordion.

```
┌────┬──────────────────────────────────────────┬────────────────────┐
│ ▎👤│ 👤 Roster  ( By asset │ By pilot )        │  11                │
│    │    1 asset · 3 pilots  [search]  [+Assign]│  Simulated         │
│    ├──────────────────────────────────────────┤  ──────────────    │
│    │ ASSET        CATEGORY    PILOTS          │  PILOTS       0    │
│    │ ▎11          Simulated   — none          │  No one assigned   │
│    │  Falcon-2    Drone       Pilot, Manager  │  [+ Add pilot ⌄]   │
│    │  Rover-1     Robot       Pilot           │  ──────────────    │
│    │                                          │  UNASSIGNED        │
│    │ ⚠ 1 asset has no pilot                   │  Pilot             │
│    │                                          │  Manager           │
└────┴──────────────────────────────────────────┴────────────────────┘
```

- **`By asset | By pilot` pivot** — the same data read either direction. "By pilot" lists people with
  their assigned assets, which is what the page's name implies and what it cannot currently answer.
- **Assignments are visible in the row**, not behind a disclosure. The accordion is deleted.
- **Right panel does the editing** (`?sel=`), with the unassigned pool listed for one-click adds.
- **A gap indicator** — `⚠ 1 asset has no pilot` — makes the page answer a real management question
  at a glance.
- Search sized to content, in the bar.

## Refactor list

- **Delete** the accordion; render assignments inline.
- **Add** the pivot toggle with a `?by=asset|pilot` param; the pivot itself is a pure function in
  `core/` (unit-tested), not view logic.
- **Adopt** `shared/ui/two-pane` + `side-panel`.
- **Replace** `page-head` with `vision-page-bar`.
- **Add** the unassigned-asset count.
- **Reuse** the per-asset Pilots tab (`05-asset-detail.md`) as the single-asset view of this data —
  one assignment service, two entry points, no third implementation.

## Acceptance

- "Which assets have no pilot" is answerable without expanding anything.
- `By pilot` lists every pilot including those with zero assignments.
- Assignment changes reflect on `/assets/:id` → Pilots without a reload.
