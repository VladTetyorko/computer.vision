# LIVE-POLL-RETIREMENT — context

Owner report (2026-09-06): *"too many GET requests instead of stream — stream would be so much
better"*, naming `/api/map/marks`, `/api/map/layers`, `/api/map/drawings`, `/api/system/status`.

## What was measured, not assumed

The SSE plane already exists and is healthy: `GET /api/live` (`LiveController:97`) with nine topics
(`fleet`, `event`, `devices`, `detection-events`, `map`, `discovery`, `telemetry:<id>`,
`detections:<id>`, `geo:<id>`). Five stores already retire their poll while it is open via
`applyTransport(isLiveAvailable(...))`: `FleetStore`, `EventsStore`, `TelemetryStore`,
`DetectionsStore`, `GeoStore`.

The reported endpoints are the ones that never adopted that idiom.

| Endpoint | Store | Cadence | SSE topic today | Verdict |
|---|---|---|---|---|
| `/api/map/marks` | `MarksStore` | 30s | ✅ `map` | redundant while live is open |
| `/api/map/layers` | `LayersStore` | 30s | ✅ `map` | redundant while live is open |
| `/api/map/drawings` | `DrawingsStore` | 30s | ✅ `map` | redundant while live is open |
| `/api/map/tracks` | `TracksStore` | 30s | ✅ `map` | redundant while live is open |
| `/api/discovery/candidates` | `DiscoveryInboxStore` | 30s | ✅ `discovery` | redundant while live is open |
| `/api/geofence/zones` | `GeofenceStore` | 30s | ❌ none | needs a topic first |
| `/api/system/status` | `SystemStatusStore` | **15s** | ❌ none | root-scoped — runs on *every* page |
| `/api/fleet/summary` | command + wall facades | **5s** | ⚠️ shape mismatch | ALWAYS-ON-FLOW **B1** |

Steady-state cost on `/command` with SSE healthy: **~28–30 req/min**, nearly all of it re-fetching
lists the `map` topic is concurrently delivering as deltas.

## The one honest reason the safety-net poll exists — and why it does not hold

`marks-store.ts:35` documents it: the `map` topic is **not snapshot-on-connect**
(`LiveUpdateRegistry.seedIfEmpty` has no `MAP` case, by explicit design), so a client reconnecting
after a buffer-exceeding gap would silently miss deltas.

That argues for *reconciling on reconnect*, not for polling forever. `LiveStore.connectionState` is
already a signal, and `Last-Event-ID` resume (`replayFor`) already replays gap-free windows. So
**"re-fetch once on every transition into `open`, poll only while not `open`"** is strictly *more*
correct than today's fixed 30s: today a dropped connection is reconciled up to 30s late; that
version reconciles immediately, at zero steady-state cost.

## Two findings that change the obvious design

**1. Zones must NOT ride the `map` topic.** `GeofenceZone` lives in `vision-flight`
(`flight/domain/model/GeofenceZone.java`); `MapEvent` lives in `vision-map`. `vision-map`'s
dependency edges are `identity`, `perception`, `kernel`, `platform` — there is **no `map → flight`
edge**, and DOMAIN-SEPARATION-W1 §16's measured DAG does not contain one. Adding a `ZONE` entity to
`MapEvent.EntityType` would create a forbidden context edge and fail ArchUnit.

The established idiom is one `*LiveUpdatePort` per context, all implemented by the single
`LiveUpdateRegistry` in `vision-api`: `FleetLiveUpdatePort` (warehouse), `TelemetryLiveUpdatePort` +
`TrackCorrectionLiveUpdatePort` (flight), `MapLiveUpdatePort` (map), `DetectionLiveUpdatePort`
(perception). Zones therefore need a **`GeofenceLiveUpdatePort` in `vision-flight` + its own
`zones` topic**, not a new `MapEvent` entity.

**2. `system` cannot be a broadcast-on-change topic.** `SystemStatusController.status()` *pulls*
every `SubsystemStatusPort` on demand; nothing in the platform notifies on health change. So the
topic must be a **server-side sampled** broadcast: one sampler on the registry's scheduler,
emitting only when the rolled-up value actually changes. Note `SystemStatusResponse` carries
`Instant.now()`, so change-detection must compare `overall` + `subsystems` and ignore the
timestamp, or every sample would look like a change and the topic would be a 1:1 poll replacement
with extra steps.

This is a strict improvement in kind, not just degree: it moves the sample from *every browser tab,
on every page, forever* to *one place in the server*, and puts only deltas on the wire.

## Scope decision

Owner chose scope **C** (2026-09-06): the five live-covered stores, plus a `system` topic, plus
zones, plus ALWAYS-ON-FLOW **B1** (`/api/fleet/summary`). Plan-first per CLAUDE.md's delegation
model. Branch: `feat/live-poll-retirement`.

## Related plans

- `docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` — §4 Wave C3 made these five stores demand-gated
  (`activate()`/`release()`); this plan makes them *live-gated*, the orthogonal axis C3 left open.
  B1 is absorbed here.
- `docs/plans/done/REALTIME-PLAN.md` §4 — the original SSE plane and the `applyTransport` idiom.
- `docs/plans/done/MAP-REWORK-PLAN.md` §4.3 — the scoped `map` topic and its per-viewer filtering.

## Measurement caveat (added after a live attempt, 2026-09-06)

A browser measurement on `/command` was attempted and **must not be cited**: the backend went down
partway through the 74s window (every endpoint returned 503 through the Angular dev proxy, not just
`/api/live`), and a failing `/api/live` makes `FleetStore`/`EventsStore` correctly *resume* polling,
inflating the counts. The observed 27.4 req/min is therefore contaminated in both directions and was
also collected in a Chrome-throttled background tab.

**Use the cadence arithmetic instead**, which is derived from the code and needs no running app.
`/command`, SSE healthy, tab visible:

| Source | Cadence | req/min |
|---|---|---|
| `CommandFacade` `/api/fleet/summary` | 5s | 12 |
| `CommandFacade` `/api/assets` | 5s | 12 |
| `SystemStatusStore` `/api/system/status` | 15s | 4 |
| marks + layers + drawings + tracks | 30s each | 8 |
| `GeofenceStore` `/api/geofences` | 30s | 2 |
| **Total** | | **~38** |

`FleetStore` and `EventsStore` contribute 0 while live is open — they already gate correctly.

A clean re-measurement is still owed once the app is back up, and is an acceptance criterion for the
final wave rather than an input to the plan.

## The `fleet` topic scope leak (found 2026-09-06 while verifying the plan)

Verified in code, not inferred:

- `LiveUpdateRegistry#freshFleetEnvelope` (`:1037`) builds its snapshot from `assetService.assets()`
  — the no-arg **unscoped** overload. A scoped `assets(VisibilityScope, boolean)` exists and is what
  `AssetController` uses for `GET /api/assets`.
- `LiveConnection#mayReceive` filters only `MapEventPayload` (by `layerId`) and envelopes carrying a
  non-null `assetId`. A `fleet` envelope is neither, so it `return true`s unconditionally.
- `FLEET` is added to **every** connection's topic set in `connect()`, and `seedIfEmpty` appends a
  fresh envelope, so every connecting user is handed the full unscoped list immediately.
- It is **consumed and rendered**: `features/fly/drone-picker-facade.ts:152` assigns the snapshot to
  `pickerAssets`, which feeds `groups` (`:96`) — the drone picker's visible list — overwriting the
  scoped list that same facade fetched at `:200`. Also read by `shared/ui/notification-bell.ts:319`
  and `features/fly/cockpit-facade.ts:937`.

**Effect:** on `/fly`, any authenticated user's drone picker lists every asset in the deployment
regardless of visibility scope, as soon as the SSE snapshot arrives.

This is a live scope violation, not a latent one, and it is independent of the polling work. Wave L6
fixes it and should ship first and alone. The plan's original L6c/L7a wording claimed the topic had
no consumers; both were corrected.
