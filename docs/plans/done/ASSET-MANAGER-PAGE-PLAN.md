# ASSET-MANAGER-PAGE-PLAN — separate the manager asset page from the pilot cockpit

Status: DONE (2026-07-30, both waves green). Wave A backend: `GET /api/assets/{id}/stats`
flight-time/count/avg/battery aggregate (vision-application 434, vision-api 287, vision-app
127). Wave B frontend: asset page stripped of the embedded player + stream controls, cockpit
link band, 5 KPI stat-tiles + a recent-flights bar chart, button-alignment audit (found+fixed
a real `.panel-actions` equal-fill bug in Command's asset-panel where an equal-width rule
couldn't reach a view-encapsulated child button); vision-web 1082 tests, tsc clean, prod build
clean. Original spec below.

Approved spec (2026-07-30). The asset detail page today is a hybrid — it embeds a
live `<vision-player>` and stream start/stop controls *and* the management info (telemetry,
usage history, hardware). The user's direction: **the asset page is for the manager, not for
piloting** — no embedded video, a link to the cockpit instead, plus utilization dashboards
(flight time, battery, …). Piloting already has its home: **Fly** (the cockpit) and the
`/live/:deviceId` watch page.

## How other fleet-ops systems do it (the pattern we copy + enhance)

Surveyed the conventions of vehicle/drone fleet platforms (Samsara, Geotab; DJI FlightHub 2,
Auterion Suite, Skydio). The consistent split:

- **Asset detail = management, not operation.** The per-asset page is glanceable KPIs, health,
  and history — *utilization*, not a live control surface. Live control/video is a separate,
  clearly-linked destination (one prominent CTA), never embedded in the management view.
- **KPI tile row up top**: total operating time (flight hours), trips/flights count, last-active,
  and a health/energy figure (battery). Glanceable in one row, biggest numbers first.
- **Utilization over time**: a small chart of flights (or flight-time) across recent history —
  the "is this asset being used / is it healthy" question a manager actually has.
- **Recent activity + history** below the fold; **specs/hardware** last.
- **One clear path to live**: a single, prominent "Open cockpit / Live" button — not a player
  the manager scrolls past.

Our enhancement: we already *have* the cockpit (Fly) and a real telemetry/events pipeline, so
the manager page links into a genuinely rich cockpit rather than a thin live tile, and the KPIs
come from real usage/telemetry data (honestly labeled, never faked).

## Wave A — per-asset flight stats (backend: vision-application + vision-api)

- `AssetStatsService` → `DefaultAssetStatsService(AssetUsageRepositoryPort, UsageTracker)`:
  `AssetStats statsFor(AssetId)` aggregating the asset's usages. Fetch-then-aggregate over
  `findRecentByAsset(assetId, STATS_FETCH_LIMIT)` (package-private, 10_000 — the documented
  `ReplayService` fetch-then-aggregate precedent, with the same honest cap caveat: an asset with
  more than 10k flights under-reports; a true SQL aggregate is a later enhancement). Open flights
  (`endedAt == null`) count toward flight time up to now.
- `AssetStats(long totalFlightSeconds, int flightCount, Instant firstFlownAt, Instant lastFlownAt,
  Long avgFlightSeconds, Integer lastKnownBatteryPercent, boolean flightInProgress)` — top-level
  record; `firstFlownAt`/`lastFlownAt`/`avgFlightSeconds`/`lastKnownBatteryPercent` nullable
  (null when no flights / no telemetry — never a fabricated zero). `avgFlightSeconds` over
  *closed* flights only (an open flight has no final duration). Battery from
  `UsageTracker.latestTelemetry(assetId)` (nullable, same source `FleetSummaryService` uses).
- **Frozen wire contract**: `GET /api/assets/{assetId}/stats` → 200 `AssetStatsResponse`
  `{totalFlightSeconds, flightCount, firstFlownAt?, lastFlownAt?, avgFlightSeconds?,
  lastKnownBatteryPercent?, flightInProgress}`; 404 unknown asset (same as the other asset reads).
  `@JsonInclude(NON_NULL)` for the nullable fields.
- Tests: aggregation (multiple closed flights → sum/avg/count/first/last), an open flight counted
  to now, zero flights → zeros + nulls, battery pass-through, 404. Follows the module's existing
  service/controller test style.

## Wave B — manager asset page rework + button alignment (frontend: vision-web only)

Load the `frontend-design` and `dataviz` skills before building UI/charts.

1. **Remove piloting from `features/asset-detail/**`**: delete the **Video** card
   (`<vision-player>` embed) and the inline **Stream** start/stop controls. The asset page no
   longer mounts a player or starts/stops streams — that's the cockpit's job.
2. **Cockpit link band** (replaces the video/stream cards): a compact status strip —
   live/offline chip — with **one prominent primary CTA "Open cockpit"** (→ `/fly` for this
   asset: set `SettingsStore.flyAssetId` then navigate) and a secondary **"Watch live"**
   (→ `/live/:deviceId`, the existing lightweight watch page). When offline, "Open cockpit"
   still navigates (the cockpit owns start/stop). No video, ever, on this page.
3. **KPI tile row** (`core/fleet/asset-stats-logic.ts` pure + tested; `VisionApi.assetStats(id)`):
   Total flight time, Flights, Last flown, Avg flight, Battery — a `dataviz` stat-tile row
   (biggest number first, honest units, "—" when null, `flightInProgress` shown as a live pip on
   the flight-time tile). Reuse the app's existing tokens; follow the `dataviz` stat-tile spec.
4. **Utilization dashboard**: a small chart of recent flights over time (from `recentUsages`,
   honestly labeled "recent flights" since it's the capped list, distinct from the KPI totals
   which are the real aggregate) — bars per day or a sparkline of flight durations. `dataviz`
   palette/marks; theme-aware; responsive (collapses cleanly on narrow viewports, per the
   ongoing responsive pass).
5. **Keep (manager-relevant, read-only)**: Position (last-known map inset), Telemetry (current
   readings), Events, Usage history (the flights list — now the detail behind the KPIs),
   Characteristics, Hardware.
6. **Button-alignment audit** (the "check UI and alignment of buttons" ask): normalize action
   rows app-wide where cheap — consistent `.actions`/`.panel-actions` flex alignment (gap,
   vertical centering, wrap behavior), consistent button sizing within a row (no mixed
   `small`/default in the same row unless intentional), primary-left/secondary-right ordering.
   Scope the CSS pass to the shared button styles + the asset-detail/command/fly action rows;
   report any inconsistency found but not fixed. Do NOT restyle the button *look* (colors/tokens)
   — only alignment/spacing/ordering.

Tests: vitest for `asset-stats-logic` (formatting flight time, "—" for nulls, in-progress label)
and any extracted chart-data logic; component specs only as far as existing precedent goes.
Build: `npm test` + `tsc --noEmit` + `ng build --configuration production` green, report bundle
delta. Update `vision-web/MODULE.md`.

## Non-goals / guardrails

- No change to Fly or `/live` — they already *are* the pilot/control surface; this only removes
  the duplicate piloting affordances from the manager page and links to them.
- No fake KPIs — every tile reads a real value or shows "—"; the utilization chart is labeled for
  exactly the window it covers (recent vs lifetime-aggregate).
- Video never returns to the asset page. The manager sees *that* it's live and clicks through.

## Sequencing

Wave A (backend stats) and wave B (frontend) parallel, disjoint — B builds against the frozen
`/api/assets/{id}/stats` contract; if the stats call fails/404s, the KPI row degrades to "—"
rather than blocking the page (same resilience as the existing enrichment reads).
