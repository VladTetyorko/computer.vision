# 08 — Alerts center

**Files:** `features/alerts/**`, `shared/ui/events-rail.*`
**Wave:** 2 (page bar), 3 (two-pane)

## Current state

`page-head` ("Alerts center" + description), a boxed notice ("Saved alert thresholds and
acknowledgement are coming — this feed already streams live via the header bell and Wall"), an
`Events / 50` header, two full-width filter selects (`All labels`, `All assets`), then a list of event
rows: status dot + `CLOSED` + label (`Person`) on line 1, `11 · video · 60%` on line 2, with
`12s ago` and `Details ›` right-aligned.

## Problems

- **Rows are ~1070px wide and hold ~40 characters** (F4). The left 25% carries all the content; the
  right 60% is empty until the time/`Details` cluster at the far edge. The eye has to travel the whole
  width to tie an event to its timestamp.
- Every row says `CLOSED`. With no open/closed distinction visible in the data, the badge is pure
  repetition — 50 identical chips.
- **No detail view** — `Details ›` is on every row and (per the notice) the page cannot actually
  acknowledge anything, so the primary affordance leads nowhere useful.
- The two filter selects are each ~530px wide for values like "All labels".
- 130px `page-head` + 40px notice + 40px filters = 210px before the first event.
- The same feed renders in three places (header bell, Wall rail, this page) via
  `shared/ui/events-rail.*` — but this page reimplements the row layout rather than reusing it.

## Suggested design

Two-pane triage: dense feed left, event detail right (frame + box + actions).

```
┌────┬──────────────────────────────────────────┬────────────────────┐
│ ▎⚠ │ ⚠ Alerts · 50   [labels ⌄][assets ⌄][⟳] │  Person · 92%      │
│    ├──────────────────────────────────────────┤  11 · video        │
│    │ ▎● Person   92%  11·video          12s   │  36s ago           │
│    │  ● Person   60%  11·video          36s   │ ┌────────────────┐ │
│    │  ● Car      82%  11·video       2m14s    │ │  frame + box   │ │
│    │  ● Person   89%  11·video       2m23s    │ └────────────────┘ │
│    │  ● Person   90%  11·video       2m48s    │  [Acknowledge]     │
│    │  ● Car      77%  11·video       7m39s    │  [Open cockpit]    │
│    │                                          │  [Jump to replay]  │
└────┴──────────────────────────────────────────┴────────────────────┘
```

- **Single-line rows**, columns aligned: severity dot · label · confidence · asset · relative time.
  Row height 32px, so ~25 events fit a screen instead of ~10.
- **The row is the click target**; `Details ›` is deleted. Selection drives `?sel=<eventId>`.
- **Right panel shows the captured frame with the detection box** — this is what makes an alert
  actionable, and the data already exists (the cockpit burns the same boxes in). Actions:
  `Acknowledge` (when built), `Open cockpit`, `Jump to replay`.
- **`CLOSED` chip only renders when a row differs from the norm** — an `OPEN` badge is signal, a
  universal `CLOSED` badge is noise.
- **Filters move to the page bar** as chips sized to their content.
- **The "coming" notice becomes one dismissible line** in the bar, not a boxed panel.
- **Reuse `shared/ui/events-rail`'s row component** here so the Wall rail, the bell dropdown and this
  page share one row implementation and one visual language.

## Refactor list

- **Extract** `event-row.ts` from `shared/ui/events-rail.*`; consume it here and in the Wall rail.
- **Adopt** `shared/ui/two-pane` + `side-panel`, `?sel=` param.
- **Add** `alert-detail-panel.ts` with the frame + box render.
- **Replace** `page-head` + notice + filter block with `vision-page-bar` + filter chips.
- **Conditional** `CLOSED`/`OPEN` chip.

## Acceptance

- ≥20 events visible at 1080p without scrolling.
- Label, confidence, asset and time are all readable within the left 600px.
- Selecting an event shows its frame; refresh restores the selection.
