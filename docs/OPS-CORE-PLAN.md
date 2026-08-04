# OPS-CORE-PLAN — geofencing, recording, weather, ops quick wins

Status: approved spec (2026-07-29). Implements FEATURE-MATRIX Tier 2's two "very high" rows
(geofencing, recording+clip export) plus the Tier-1 leftovers that serve pilot/crew/manager
(weather go/no-go chip, event→replay deep link, undo toast, replay polish). Command TX (I-e)
remains parked. Personas: P pilot, M manager, C crew/referee.

## G — Geofencing (P, M)

### Design
- Domain: `record GeofenceZone(ZoneId id, String name, ZoneKind kind, List<GeoPosition> polygon,
  Double maxAltitudeMeters, boolean enabled)` — `ZoneKind` enum `KEEP_IN | KEEP_OUT`; polygon ≥3
  vertices (closed implicitly, first≠last stored); `maxAltitudeMeters` nullable (no ceiling);
  zones are global (all assets) — per-group scoping arrives with U-e, not now.
  `boolean contains(GeoPosition p)` on the record: ray-casting point-in-polygon on lat/lon
  (equirectangular is fine at fence scale) — domain math, framework-free, heavily tested.
  `ZoneId` wraps UUID (standard `random()`/`of()`).
- `EventType` gains `GEOFENCE_BREACH` (existing enum, pure marker).
- Port: `GeofenceRepositoryPort` — `save` (upsert), `findAll`, `findById`, `deleteById`
  (idempotent). Implementations: JPA (V7 migration, jsonb polygon) + in-memory devsupport.
- Application: `GeofenceService` (CRUD, list) + breach evaluation on the telemetry hot path:
  `GeofenceMonitor` (application), called by `UsageTracker.applySample` (nullable collaborator,
  same idiom as `liveUpdatePublisherPort`). Per (asset, zone) breach state held in-memory;
  transitions only emit on EDGE: entering breach → one `Event(GEOFENCE_BREACH, streamId=null,
  attributes {assetId, zoneId, zoneName, kind, direction:"enter"})` via `EventPublisherPort` +
  `LiveUpdatePublisherPort.publishEvent`; leaving breach → same with `direction:"exit"`.
  Breach definition: KEEP_OUT + inside polygon (any altitude) = breach; KEEP_IN + outside any
  enabled KEEP_IN polygon = breach ONLY if at least one enabled KEEP_IN zone exists; altitude
  ceiling exceeded (sample altitude > zone.maxAltitudeMeters while inside polygon) = breach.
  Samples without lat/lon are ignored (no state change — honest unknown, never a breach).
- Must be cheap: zones cached in the monitor, refreshed on CRUD (service pokes monitor), not
  re-read per sample.

### Wire contract (frozen)
- `GET/POST /api/geofences`, `PUT/DELETE /api/geofences/{id}` — DTO `GeofenceZoneResponse
  {id, name, kind: "KEEP_IN"|"KEEP_OUT", polygon: [{latitude, longitude}], maxAltitudeMeters?,
  enabled}`; create/update request same minus id. 400 invalid polygon (<3 points), 404 unknown id.
- Breach events ride the existing `event` SSE topic / notification bell as EventType
  `GEOFENCE_BREACH` with the attributes map above. No new SSE topic.
- Frontend attention kind: `geofence-breach`, ranked at the very top (above `failsafe`).

### Phases
- **G-a** (vision-domain + vision-application): model, port, monitor, service, UsageTracker hook,
  tests. Also (for R below, same agent owns domain): `StreamPublisherPort` gains
  `default Optional<URI> playbackUrl(StreamId, Instant start, Duration duration)` → empty default.
- **G-b** (vision-api + adapter-persistence + vision-app): controller per contract, V7 migration
  (`geofence_zones`: uuid pk, name, kind, polygon jsonb, max_altitude_meters nullable, enabled),
  JPA + devsupport in-memory repos, wiring (monitor into UsageTracker's ctor site), ArchUnit green.
- **G-c** (vision-web): Command map gains a Zones layer (KEEP_OUT: red 12% fill + dashed border;
  KEEP_IN: accent dashed border, no fill; reuse flight-plan dialog's draw tooling for polygon
  authoring) + a Zones management panel (list, enable/disable, rename, delete with undo toast,
  "New keep-in/keep-out zone" draw flow); Fly map shows zones read-only; `geofence-breach`
  attention reason (top rank, red) from GEOFENCE_BREACH events; breach toast via existing
  notification bell. Poka-yoke: zone delete is undoable (10s), draw flow can't save <3 points,
  KEEP_IN zones warn when no asset is inside at save time ("3 assets currently outside this zone").

## R — Recording + clip export (M, C)

### Design — mediamtx does the heavy lifting
- mediamtx native recording: `MTX_PATHDEFAULTS_RECORD=yes`, fMP4 segments, named volume
  `mediamtx-recordings` (docker-named volume, NOT a repo bind mount — keeps recordings out of the
  worktree), `MTX_PATHDEFAULTS_RECORDDELETEAFTER` retention; playback server enabled
  (`MTX_PLAYBACK=yes`, host port **19996**, container 9996 — same 1:1 style as other mediamtx ports).
  - **Retention: 72h is the spec for a deployment recording real flights; `docker-compose.yml` now
    ships `1h`** (2026-08-04). `MTX_PATHDEFAULTS_RECORD=yes` records *every* path, and in the dev
    stack most paths are `adapter-simulation` sources that stream for as long as the app is up.
    Measured: **~1.8 GB per hour per stream** (hourly fMP4 segments of 1.76–1.84 GB), so one
    always-on synthetic source trends toward ~130 GB before 72h prunes anything — this is how the
    `vision_mediamtx-recordings` volume reached 8.4 GB across 15 stale device paths and contributed
    to filling a 159 GB disk. Restore 72h for real deployments; where simulated and real sources
    share a host, prefer `MTX_PATHDEFAULTS_RECORD=no` plus per-path enable via mediamtx's API
    (already noted as possible in `docker-compose.yml`, still unused) rather than a blanket default.
- mediamtx playback API (v1.19): `GET :19996/get?path=<name>&start=<RFC3339>&duration=<seconds>`
  → single MP4 for the range — this IS clip export; `GET /list?path=` → recorded ranges.
- `MediamtxStreamPublisher` implements `playbackUrl(streamId, start, duration)` → that /get URL
  (playback base configurable next to the existing HLS/WHEP bases). Never proxied (same
  reasoning as WHEP — MODULE.md documents it).
- Join to flights: `AssetUsage` already has `streamId` + `startedAt/endedAt`. New endpoint:
  `GET /api/usages/{usageId}/recording` → 200 `{available: true, url, start, durationSeconds}`
  (url = playback /get for the usage window) or `{available: false}` when the publisher has no
  playback URL or usage has no streamId/endedAt (open usages: use now as end). Availability
  check is honest-cheap: presence of configuration, not a mediamtx round-trip; the player's
  error state handles genuinely-missing segments (e.g. recording enabled after the flight).

### Phases
- **R-a** (adapter-publish-hls + docker-compose.yml): compose env/volume/port + publisher
  `playbackUrl` + config plumbing + tests (docker-gated integration: publish a short feed with
  record on, then fetch /get and assert MP4 bytes — follow the module's existing mediamtx
  docker test conventions).
- **R-b** (vision-api + vision-application): the `/api/usages/{usageId}/recording` endpoint
  (application-layer resolution usage→streamId→publisher.playbackUrl), DTO per contract, tests.
- **R-c** (vision-web): Replay page gains a video pane (plain `<video>` with the mp4 URL —
  scrubbing a fixed clip window; align clip t=0 with usage start on the existing timeline) +
  "Download clip" (current brush/window range → /get URL with that start/duration, `<a download>`)
  + graceful `available:false` / video-error empty state ("No recording for this flight —
  recording enabled?" linking docs). Event→replay deep link (Q1 below) lands here too so the
  chain event → flight moment → video evidence closes in one agent.

## W — Weather go/no-go chip (P, M) — frontend-only
- Open-Meteo (no key, CORS-open): `core/weather/weather-store.ts` fetches
  `forecast?latitude&longitude&current=wind_speed_10m,wind_gusts_10m,precipitation` for the
  fleet's centroid (or selected asset position), refresh ≤ every 10 min, cached, offline/failed →
  chip hidden entirely (never a stale reading without a timestamp).
- `core/weather/weather-logic.ts` (pure, tested): `windSeverity(speed, gusts, limitMps)` — limit
  from asset attributes key `windLimitMps` when present else default 10 m/s; ok < 70% limit,
  warn 70–100%, no-go > 100%.
- Chips: Command header + Fly OSD ("8.2 m/s gusts 12.1 — NO-GO" red / "GO" ok). Advisory only —
  it never blocks anything (poka-yoke: informs, doesn't fake authority).

## Q — ops quick wins (C, M) — frontend-only
1. **Event → replay deep link**: event rows (bell + any event lists) whose asset has a covering
   usage link to `/replay?usage=<id>&t=<offsetMs>` — replay opens scrubbed to that moment
   (lands in R-c's agent).
2. **Undo toast** (10s) for archive/deactivate/zone-delete instead of silent immediacy — shared
   `shared/ui` toast with action button; wire into existing archive flows (U-a2 verb sites).
3. **Replay strip cap + composite box hues**: cap the replay event strip (virtualize/cap at 200
   with "showing latest 200"), and give composite-model detections stable per-model hues in
   overlays (model id hash → hue; legend chip).

## Agent waves (disjoint scopes)

- **Wave A (parallel)**: G-a (domain+application) · R-a (adapter-publish-hls + compose) ·
  Q2+Q3 (vision-web quick wins — toast, strip cap, hues).
- **Wave B (after A, parallel)**: G-b (api+persistence+app wiring) · R-b (api+application usage
  endpoint — sequenced with G-b if both touch vision-api: G-b owns it; R-b's endpoint folds into
  G-b's agent if needed, else runs after) · G-c + R-c + W (vision-web: zones UI, replay video,
  weather chip, deep link).
- Every agent: read module MODULE.mds first, update after, scoped builds only, local-only.
