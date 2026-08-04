# 06 — Devices

**Files:** `features/devices/**`
**Wave:** 2 (page bar), 3 (side panel)

## Current state

Already the closest thing in the app to a good data page. `page-head` ("Devices" + a 2-line
description) with `Show archived`, `+ Add source`, `Refresh`. A search input and a `List | Grid`
toggle. Then a table: `NAME` (name + uuid) · `SOURCE` (protocol chip + URI) · `CAPABILITIES` ·
`STATE` (chips) · `OWNER` · actions.

## Problems

- The actions column wraps to two rows (`Watch live` / `Stop stream` on one line, `Stop simulation` +
  kebab on the next), so row heights are uneven and the table loses its scan line.
- Three to four buttons per row are rendered at full size for every row, whether relevant or not —
  the table is 40% action buttons by width.
- The `SOURCE` URI truncates mid-path (`file:///home/vladte/Videos/X2Twitter.com_h…`) with no way to
  see the full value without the kebab.
- The device UUID is rendered in full under every name — 36 characters of data nobody scans.
- `page-head` costs 130px; its second sentence ("Looking for an asset instead? Assets live on their
  own page now.") is migration signage that has outlived its purpose.
- `List | Grid` — a grid of raw devices has no use case that the list does not serve better.

## Suggested design

Keep the table. Fix the row, add the panel.

```
┌────┬────────────────────────────────────────────────┬──────────────┐
│ ▎⚙ │ ⚙ Devices · 2      [search]      [□ archived]  │ 11 · video   │
│    ├────────────────────────────────────────────────┤ VIDEO        │
│    │ NAME          SOURCE          CAP    STATE   ⋯ │ ● Live       │
│    ├────────────────────────────────────────────────┤ ──────────   │
│    │ ▎11·video     file ⋯X2Twitter VIDEO  ●Live   ⋯ │ file:///home/│
│    │  11·telemetry sim  //11-telem TELEM  Stopped ⋯ │ vladte/Video…│
│    │                                                │ 9d21dc6c-…   │
│    │                                                │ owner: 11    │
│    │                                                │ ──────────   │
│    │                                                │ [Stop stream]│
│    │                                                │ [Stop sim]   │
└────┴────────────────────────────────────────────────┴──────────────┘
```

- **One primary action per row** (`Watch live` / `Start stream` — whichever applies), everything else
  in the kebab. Rows become single-line and uniform; the table regains its scan line.
- **Right side panel** (`?sel=<deviceId>`) holds the full URI, the UUID, the owner link, and the full
  action set. This is where truncated data goes — not a tooltip.
- **UUID leaves the row**, replaced by the protocol chip already present. It stays in the panel with a
  copy button.
- **Drop the `Grid` toggle.**
- **`page-head` → page bar**; both sentences deleted (the sidebar now says where you are, and the
  Assets migration note is stale).
- Archived toggle becomes a filter chip in the bar.

## Refactor list

- **Adopt** `shared/ui/two-pane` + `shared/ui/side-panel.ts`, `?sel=` param.
- **Collapse** the actions column to one button + kebab; move the rest into the kebab and the panel.
- **Delete** the grid view and its toggle.
- **Replace** `page-head` with `vision-page-bar`.
- **Move** UUID + full URI into the panel with copy affordances.

## Acceptance

- Every row is one line at 1280px.
- The full source URI is readable without opening a menu.
- Selection is addressable via `?sel=`.
