# OPERATOR-UX-6 — the page leads with its job

Sixth cycle of the operator-UX series (after OPERATOR-UX-5). Live walkthrough 2026-08-29 of Replay, Readiness, Command, Assets, Geo regions, Detection defaults, Training, Organization. Two themes this time: **hierarchy** (a page opens on the thing the operator came for, not on a form or a package) and, again, **honesty** (no dead tiles, no timestamps for things that never happened, no plural typos, no vehicle vocabulary the vehicle does not have).

## 1. Findings

| # | Where | What the operator sees | Why it is wrong |
|---|---|---|---|
| E1 | `/assets/:id/replay/:usageId` | A 635 px "Evidence package" card opens the page; the map, the recording and the Playback transport are below the fold, each a full-width card, so the scrub bar is ~1.4 k px from the map it drives | Hierarchy inverted: the replay *is* the map + video + transport; the package is a download link. Every card is `span-2` in a two-column `.detail-grid`, so the grid does nothing |
| E2 | same page | `Built for 00000000-0000-0000-0000-000000000000` | Root principal rendered raw. Cycle 5 already named it: `core/audit/summary-logic.ts#actorLabel` → `Station` |
| E3 | same page | `Included · 200 entrys` | `after-action-panel.ts` pluralises `entry` with `+ 's'` |
| E4 | same page, for a rover | `Flight replay`, `(1h 22m flight)`, `This flight is still in progress`, `clip covers the whole flight` | A rover does not fly. The product's vehicle-neutral word for a usage is *session* (`UsageSummary`, `/replay` library) |
| M1 | every map in the dark theme (`/command`, `/assets/:id`, replay) | Every tile reads `API KEY REQUIRED` | The `night` basemap is Carto `dark_all`, which now needs a key we do not have; dark theme picks it by default (`defaultLayerForTheme`). The default map of the default theme is dead |
| R1 | `/assets/:id/readiness` | `Evaluated 29.08.2026, 13:44:31` · `Last profiled Never` · eleven rows `Unknown / Never probed` · a live `Probe now` button on a rover offline for 18 h | A timestamp for an evaluation of nothing; a probe offered to a vehicle with no link |
| O1 | `/org` | The "Create a user" form (7 fields) opens the page; the 32-user roster starts below it | Same inversion as E1: the roster is the page, creating is the exception |

## 2. Design

**E1 — replay leads with the replay.** Order: page bar → time-range line → **Position** (map) and **Recording** side by side (`span-1` each on the two-column grid, stacked under 900 px) → **Playback** (transport + scrub, full width, directly under both) → Detections timeline → **Evidence package** last, collapsed to its header line + `Download` until opened (the manifest detail is one click away, not the first screen). `after-action-panel` keeps its inputs; only its host order and a `collapsed` default change.

**E2 — names, not ids.** `Built for {{ actorLabel(scopedTo) }}` — `Station` for the root principal, the user's display name when the roster knows it, else the 8-char short id. Reuse `summary-logic`; no new formatter.

**E3 — plurals from one place.** `pluralize(count, 'entry', 'entries')` in `shared/ui/text-logic.ts` (new, pure, spec'd); the panel uses it for every count (`1 frame`, `200 entries`).

**E4 — session, not flight.** Replay page and `replay-logic` wording: `Replay` (page bar title), `(1h 22m session)`, `This session is still in progress`, `whole session`. Function names may stay (`wholeFlightClipWindow` is code, not copy) — only operator-visible text changes. The replay library already says session.

**M1 — a night map that renders.** `night` becomes OSM raster tiles rendered through a CSS filter on the tile pane (`invert(1) hue-rotate(180deg) brightness(0.85) contrast(0.9) saturate(0.6)` — the standard Leaflet dark trick; markers, drawings and popups are *not* filtered, only `.leaflet-tile-pane`), attribution OSM only, no key, no new host. `MapLayerDef` gains `tileFilter?: string`; `tactical-map` applies it as a class on the container when the active basemap has one. Satellite/relief unchanged. The layer manager's "Night" label stays.

**R1 — readiness says what it knows.** When no feature has ever been probed: header reads `Not probed yet` (structural-label register) and the `Evaluated …` timestamp is omitted; the table stays (it is the feature list) with `—` remedy. `Probe now` is disabled for an asset that is not streaming, with the reason beside it: `Needs a live link — vehicle is offline (18h)` using `offlineLabel`/`humanAge`. When probes exist, the current layout is correct.

**O1 — roster first, create on demand.** `/org` opens on the Users/Groups roster; `Create user` is a primary button in the page bar that reveals the form in a `<details>`-style panel above the table (open state not persisted). Same for the Groups tab's create form if it has one.

## 3. Waves (disjoint files)

| Wave | Agent | Files |
|---|---|---|
| **W1 replay** | web-ui | `features/replay/**` (replay + after-action-panel), new `shared/ui/text-logic.ts` (+spec) |
| **W2 night map** | web-ui | `shared/map/tile-cache/leaflet-loader.ts` (+spec), `shared/map/tactical-map/**` (filter class only), `shared/map/MODULE.md` section if present |
| **W3 readiness + org** | web-ui | `features/readiness/**`, `features/org-settings/**` |

Every wave: `npx tsc --noEmit -p tsconfig.app.json` and `-p tsconfig.spec.json`, `npm run test:ci`, prod build, `station/vision-web/MODULE.md`, own files only, commit by path (the tree holds unrelated dirty files — never `git add -A`).

Status: W1 (`9ee5d945`), W2 (`8fd8e11e`), W3 (`da41be8c`) + two sweeps (`5ed99d94`, `767d792f`) built; **merged to master 2026-08-29** (154 files / 2904 web tests, prod build green). Facts learned: the replay map is its own Leaflet host, so a `tactical-map`-only filter never reached it — the tile layer now owns `tileFilter`; the tile cache served the dead Carto tiles under the unchanged `night/` key until the key gained the tile host; `deriveTrail` now drops Null Island samples (cycle 4's `hasFix`) so a no-fix session reads `No position data` instead of a trail in the Gulf of Guinea; `pluralize` had two byte-identical owners (page-bar + W1's text-logic) — one now. Open: `/replay` library and `openFlightNotice` vocabulary were also 'flight' (fixed in sweep); `UsageSummary` still has no last-activity time.
