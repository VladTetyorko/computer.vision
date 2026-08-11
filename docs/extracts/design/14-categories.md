# 14 — Asset categories

**Files:** `features/categories/**`
**Wave:** 2

## Current state

`page-head` ("Asset categories" + "Every category assets are grouped by, with live counts."), a
full-width search input, a boxed notice ("Creating and editing categories is coming — browsing and
filtering assets by category already works below"), then a `Categories / 7 shown` table:
`CATEGORY` · `TOTAL` · `ACTIVE` · `DEACTIVATED` · `STREAMING` · `View assets` button per row.

## Problems

- **6 of 7 rows are all zeros.** `Drone 0 0 0 0`, `ESP32-CAM 0 0 0 0`, `FPV Drone 0 0 0 0`… The table
  is technically correct and practically empty, and nothing distinguishes the one row that matters
  (`Simulated 1 1 0 ●1`).
- **Read-only** — the page's whole verb ("manage categories") is unimplemented, which the notice
  admits. As shipped it is a filtered view of `/assets` with extra steps.
- `View assets` renders on all 7 rows including the 6 that would show an empty list.
- The search input is 1070px wide for a 7-row table.
- 170px of header + notice above a 7-row table.
- Four numeric columns where `TOTAL = ACTIVE + DEACTIVATED` — one column is derivable.

## Suggested design

Either make it editable or fold it into `/assets`. **Recommendation: make it editable** — categories
are domain data (`CategoryId` is a kebab-case slug, categories are records not an enum), and asset
onboarding already requires choosing one.

```
┌────┬──────────────────────────────────────────────────────────────┐
│ ▎🏷│ 🏷 Categories · 7        [search]            [+ New category]│
│    ├──────────────────────────────────────────────────────────────┤
│    │ CATEGORY        ASSETS   ACTIVE   LIVE                       │
│    │ Simulated          1        1      ●1     [View] [Edit] [⋯]  │
│    │ ─────────────────────────────────────────────────────────    │
│    │ EMPTY (6)                                              [⌄]   │
│    │  Drone · ESP32-CAM · FPV Drone · IP Camera · Robot · USB Cam  │
└────┴──────────────────────────────────────────────────────────────┘
```

- **Non-empty categories first, full rows.** Empty ones collapse into one `EMPTY (6)` disclosure line
  — present, not deleted, but not occupying six rows of zeros.
- **`DEACTIVATED` column dropped** (derivable from `ASSETS − ACTIVE`); shown in the row's expanded
  detail instead. Three columns, all carrying signal.
- **`+ New category`, `Edit`, and rename/delete** implemented — this is the notice's promise. Deleting
  a category in use must be blocked or require reassignment; that rule belongs in the domain, not the
  UI.
- **`View assets` only on non-empty rows.**
- Search in the page bar, sized to content; hidden entirely below ~15 categories.
- Notice deleted once editing ships.

## Refactor list

- **Backend check first**: confirm whether category create/update/delete endpoints exist. If not, this
  page's wave-2 scope is presentation only (grouping, column drop, page bar) and editing moves to its
  own task with a domain + API slice. Do not build UI against an endpoint that is not there.
- **Add** empty-category grouping (pure function, unit-tested).
- **Drop** the `DEACTIVATED` column.
- **Replace** `page-head` + notice with `vision-page-bar`.
- **Conditional** `View assets`.

## Acceptance

- The one category with assets is visible without scanning past six zero rows.
- If editing ships: creating a category makes it selectable in `/add-source` immediately.
- Deleting an in-use category is refused with a clear reason.
