# 04 — Assets

**Files:** `features/assets/**`
**Wave:** 2 (page bar), 3 (two-pane)

## Current state

`page-head` ("Assets" + a 2-line description ending in a prose link to `Devices`) with `+ Add source`
and `Refresh` at the right. A filter bar: search input, `CATEGORY` / `STATUS` / `STREAMING` selects,
`Show archived` checkbox. Then an `Assets` panel with `1 of 1 asset(s)` and a card grid — one 260×120
card holding name, kebab, three chips (`Simulated` `Active` `Live`), `2 device(s)`, and
`Watch live` / `Open` buttons.

## Problems

- **~14% of the viewport used** (F4) — one small card in a 1400px column inside an 1854px window.
- **Cards are the wrong form for record browsing** (F5). A card spends 260×120 on what a row shows in
  32px, and cards cannot be scanned column-wise — you cannot compare category or device count across
  assets without reading each card.
- **`Open` leaves the page** (F6), destroying search + filter context; Back re-runs the query.
- The subtitle's prose link to `/devices` is a second door to a page already in the sidebar (F7).
- The `Assets` panel header repeats the page title 180px below it.
- `1 of 1 asset(s)` — the "(s)" pluralisation and the `N of M` form both read as placeholder text.

## Suggested design

Two-pane: dense list left, detail panel right. Card grid is kept as an opt-in view for the
photo-first case (assets carry photos since UX-REWORK §U-d).

```
┌────┬───────────────────────────────────────────────┬────────────────┐
│ ▎✦ │ ✦ Assets · 1        [search] [▤ ▦]  [+ Add]   │  11            │
│    ├───────────────────────────────────────────────┤  Simulated     │
│  🛩│ [All categories ⌄][Any status ⌄][Live ⌄][□arch]│  ● Streaming   │
│  ▦ ├───────────────────────────────────────────────┤ ─────────────  │
│    │ ▎● 11        Simulated  Active  Live  2 dev   │  2 devices     │
│  ◎ │   Falcon-2   Drone      Active  —     1 dev   │  ▸ 11·video    │
│  ⚠ │   Rover-1    Robot      Archived —    1 dev   │  ▸ 11·telemetry│
│  ⟲ │                                               │ ─────────────  │
│    │                                               │ [Watch live]   │
│  +  │                                               │ [Open cockpit] │
│    │                                               │ [Open full ›]  │
└────┴───────────────────────────────────────────────┴────────────────┘
```

- **Default view is a dense list.** Columns: status dot + name · category · lifecycle · streaming ·
  device count · kebab. Row height 36px. Sortable by name / category / last used.
- **`▤ ▦` toggle** keeps the card grid for anyone who wants photos; it persists per user. When cards
  are shown they auto-fill at 280px so they fill the width.
- **Right panel replaces `Open` for triage** (F6). It carries the chips, the device list, and the
  actions — including `Open full ›` for the cases that need `/assets/:id`. Bound to `?sel=<assetId>`,
  so selection is addressable and Back-able.
- **Filters move into the page bar** as a single row of chips; they collapse into a `Filters (2)`
  popover below 1024px. The separate filter panel disappears, saving 90px.
- **Counts** read `Assets · 1` in the bar and `1 of 12` only when a filter is actually narrowing.
- The subtitle and its `Devices` link are deleted (the sidebar has Devices under `⌄ Advanced`).

## Refactor list

- **Add** `assets-list.ts` (dense rows) alongside the existing card grid; extract the shared row/card
  data mapping into the existing facade so both views read one projection.
- **Add** `assets-detail-panel.ts` on `shared/ui/side-panel.ts`, driven by `?sel=`.
- **Adopt** `shared/ui/two-pane` (extracted in `02-command.md`).
- **Replace** `page-head` + filter panel with `vision-page-bar` + inline filter chips.
- **Delete** the subtitle paragraph and its `/devices` link.
- **Keep** the kebab menu actions (`Rename`, `Archive`) — they work.

## Acceptance

- 20 assets fit on one 1080p screen without scrolling in list view.
- Selecting a row does not navigate; refresh restores the selection.
- Filter state and selection both survive Back.
