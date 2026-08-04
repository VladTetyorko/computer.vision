# 03 — Wall

**Files:** `features/wall/**`
**Wave:** 1 (full-bleed + fluid grid), 2 (page bar)

## Current state

`page-head` ("Wall" + 2-line description), a `Tiles per row` select at the top-right, a grid of video
tiles (one 270×170 tile in the capture), and a fixed ~230px `Events` rail pinned right with two
filter selects and a scrolling list of detection events.

## Problems

- **~27% of the viewport used** (F4). One tile sits at the top-left; the rest is empty while the
  Events rail is squeezed into 230px and truncates its own labels (`P…` for `Person`).
- 130px of `page-head` above a view whose entire purpose is video.
- Global header + page head = 170px of chrome on the app's second most operational screen (F11).
- `Tiles per row` is a manual number in a fluid layout — the wrong control. It also duplicates the
  `DEFAULT TILES PER ROW ON THE WALL` setting in `/settings`.
- Event rows truncate the label but keep a full-width `Details ›` — the least informative part
  survives, the most informative is cut.

## Suggested design

```
┌────┬──────────────────────────────────────────────┬─────────────────┐
│ ▎▦ │ 1 live · 50 events        [◧ density] [⟳]   │ EVENTS       50 │
│    ├──────────────────────────────────────────────┤ [labels ⌄]      │
│  🛩│ ┌──────────┐ ┌──────────┐ ┌──────────┐       │ [assets ⌄]      │
│  ☑ │ │  video   │ │  video   │ │  video   │       ├─────────────────┤
│    │ │ 11·video │ │          │ │          │       │ ● Person   11s  │
│  ◎ │ └──────────┘ └──────────┘ └──────────┘       │   11·video  92% │
│  ⚠ │ ┌──────────┐ ┌──────────┐ ┌──────────┐       ├─────────────────┤
│  ⟲ │ │          │ │          │ │          │       │ ● Car     1m48s │
│    │ └──────────┘ └──────────┘ └──────────┘       │   11·video  82% │
│    │                                            ⟩ │               ⟨ │
└────┴──────────────────────────────────────────────┴─────────────────┘
```

- **Full-bleed**: sidebar auto-collapses, no global header, `page-head` replaced by a 40px bar.
- **Tiles auto-fill**: `repeat(auto-fill, minmax(var(--tile-min), 1fr))` where a 3-stop
  **density** control (Comfortable 480px / Compact 320px / Dense 240px) sets `--tile-min`. The grid
  adapts to the window instead of being told a column count, and the `/settings` duplicate becomes the
  default for this control rather than a second mechanism.
- **Events rail widens to 320px and collapses.** At 320px `Person` and the asset name both fit.
  Rows: severity dot · label · relative time on line 1; asset · confidence on line 2; the whole row is
  the click target, so `Details ›` disappears.
- **Empty state matters here** — with zero live streams the Wall should say so and link to
  `/assets`, not render an empty grid.

## Refactor list

- **Add** `data: { fullBleed: true }` to the `/wall` route.
- **Replace** `page-head` with `vision-page-bar` (wave 2).
- **Replace** the `Tiles per row` select with a density segmented control writing `--tile-min`;
  migrate the persisted `tilesPerRow` preference to `density` with a one-time mapping (2→comfortable,
  3→compact, ≥4→dense).
- **Widen** `events-rail` to 320px, add a collapse toggle, make the row the anchor and drop
  `Details ›`. `shared/ui/events-rail.*` is shared with `/monitor/alerts` — change it once, both gain.
- **Add** an `empty-state` for zero live tiles.

> **Note.** The Wall does **not** use `shared/ui/tile-grid.ts` — it has always rendered `wall-tile.ts`
> into its own bespoke CSS grid. `tile-grid` was deleted in wave 1 as orphaned; the density work below
> targets the Wall's own grid CSS.

## Acceptance

- At 1854px with one stream the grid still reads as intentional (tile at comfortable size, not a
  lone thumbnail in a void).
- Event labels are never truncated at the default rail width.
- Resizing the window reflows tiles with no manual control touched.
