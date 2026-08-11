# Asset Model Plan — Categories, Assets, Ownership, Usage History

Companion to [ARCHITECTURE.md](../../../ARCHITECTURE.md). Replaces the `DeviceType` enum with a data-driven category model and introduces the user-facing **Asset** layer. **Milestone:** the UI shows "my drone" — an owned, categorized asset with live status and saved usage history (start position, telemetry trail) — while low-level devices remain the plumbing underneath.

---

## 0. Design decisions (usability × scalability)

1. **Categories are reference data, not code.** `DeviceCategory(CategoryId slug, name, parent?, attributeHints)` behind `CategoryRepositoryPort`, seeded with defaults, extensible at runtime (a new kind of robot is an INSERT, not a release). Optional single-parent hierarchy (`fpv-drone` → `drone`). `DeviceType` enum is **removed everywhere**.
2. **Asset = the thing the user owns and names; Device = a connection endpoint.** One asset references 1..n devices (`Set<DeviceId>`) — a drone with an FPV camera and a telemetry link is one asset, two devices. Free-form `Map<String,String> attributes` on the asset (category supplies `attributeHints` as UI suggestions, not a rigid schema) — generic and migration-free by construction.
3. **Ownership lives on the Asset** — `Ownership(UserId ownerId, GroupId groupId)`. Typed ids `UserId`/`GroupId` are introduced NOW; full accounts/roles stay in Phase 6 (ARCHITECTURE §6). Until then a constant dev principal (`UserId("dev")`, `GroupId("root")`) owns everything, applied in the app layer, never hard-coded in domain. Devices inherit scope through their asset — one scope rule for streams/detections/usages, as §6 requires.
4. **Usage history is a first-class aggregate.** `AssetUsage(UsageId, AssetId, startedAt, endedAt?, startPosition?, lastPosition?, sampleCount)` — opened when the asset starts streaming, closed on stop. Telemetry samples persist via `TelemetryRepositoryPort` keyed by usage; the usage row holds the summary (cheap list rendering; the trail is fetched only on demand). Append-only, time-keyed — TimescaleDB-ready.
5. **Users see read models, not entities.** Application-layer views: `AssetSummary` (displayName, category name, owner, status OFFLINE/STREAMING derived from active streams, lastUsedAt, lastKnownPosition) and `AssetDetails` (summary + devices + recent usages). Views are assembled behind use cases so they can be denormalized/cached later without touching the API.
6. **Registration UX is asset-first.** "Create asset" takes displayName + category + attributes + its device(s) in one call; discovery "Use" prefills it. Low-level `/api/devices` endpoints remain (advanced use, tests) but the UI leads with assets.
7. **Demoable today:** `adapter-simulation` gains `SimulatedTelemetrySource` (`TelemetrySourcePort`, protocol `sim`) emitting a slow circular GPS track with battery drain — so usage history, start position, and a telemetry trail are visible with zero hardware.
8. **GeoPosition** is a domain value: `record GeoPosition(double latitude, double longitude, Double altitudeMeters)` with range validation. `Telemetry` (existing) stays the sample type; `GeoPosition` is derived from it for summaries.
9. **Entity ids are UUIDs.** `DeviceId`, `StreamId`, `AssetId`, `UsageId`, `UserId`, `GroupId` wrap `java.util.UUID` (`record X(UUID value)` + `random()` + `of(String)` parse helper throwing `IllegalArgumentException` on bad input). **Exception:** `CategoryId` stays a kebab-case slug — it is a semantic reference-data key (`fpv-drone`), not a surrogate id. Dev principal constants become fixed UUIDs (e.g. `new UUID(0,0)` / `new UUID(0,1)`), named in wiring. API path variables/JSON carry the canonical string form; invalid UUIDs surface as 400 via the existing advice.

Breaking API/UI changes are fine (pre-1.0, no persistence to migrate — in-memory repos).

---

## 1. Task M1 — Domain refactor

**Scope:** `vision-domain/src/**` only.

### New model types (existing conventions: records, compact-ctor validation, defensive copies)
- `CategoryId(String slug)` — non-blank, lower-case-kebab validated; `UserId(String value)`, `GroupId(String value)`, `AssetId(String value)` + `random()`, `UsageId(String value)` + `random()` — all non-blank.
- `DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints)` — parent nullable, hints immutable copy.
- `Ownership(UserId ownerId, GroupId groupId)` — both required.
- `Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices, Map<String,String> attributes)` — displayName non-blank, ≥1 device, immutable copies; `with`-style helpers `withDevices`, `withAttributes` (records: return new instance).
- `GeoPosition(double latitude, double longitude, Double altitudeMeters)` — lat [-90,90], lon [-180,180].
- `AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount)` — endedAt/positions nullable, endedAt ≥ startedAt when present, sampleCount ≥ 0; helpers `closed(Instant)`, `withPositions(...)`, `withSampleCount(...)`.
- `Device`: **remove `DeviceType`** → `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream)`. Delete `DeviceType.java`.
- `DiscoveredDevice`: `suggestedType DeviceType` → `suggestedCategory CategoryId` (nullable).

### Ports
- out: `CategoryRepositoryPort` (`save`, `findById`, `findAll`), `AssetRepositoryPort` (`save`, `findById`, `findAll`, `findByDeviceId(DeviceId)` → Optional<Asset>, `deleteById`), `AssetUsageRepositoryPort` (`save`, `findById`, `findRecentByAsset(AssetId, int limit)`, `findOpenByAsset(AssetId)` → Optional), `TelemetryRepositoryPort` (`save(UsageId, Telemetry)`, `findByUsage(UsageId, int limit)`).
- in: `ListCategoriesUseCase`; `CreateAssetUseCase` — `Asset create(AssetSpec spec)`, nested `AssetSpec(String displayName, CategoryId category, Map<String,String> attributes, List<RegisterDeviceUseCase.Registration> devices)` (≥1 device); `ListAssetsUseCase` — `List<AssetSummary> assets()` with nested `record AssetSummary(Asset asset, String categoryName, AssetStatus status, Instant lastUsedAt, GeoPosition lastKnownPosition)` and `enum AssetStatus { OFFLINE, STREAMING }` (nested in the use case, it's a view state not a domain entity state); `GetAssetDetailsUseCase` — `AssetDetails details(AssetId id)` with nested `record AssetDetails(AssetSummary summary, List<Device> devices, List<AssetUsage> recentUsages)`; `StartAssetStreamUseCase` — `StreamId start(AssetId id, DeviceId device /*nullable = the asset's single video device*/, PipelineConfig config)`; `StopAssetStreamUseCase` — `void stop(AssetId id)`.
- `RegisterDeviceUseCase.Registration`: remove the `DeviceType` field (keep name/capabilities/stream).

### Tests
Update/extend in existing style: new record validations, Device/Registration/DiscoveredDevice changes, helper methods (`closed`, `withPositions`). Expect to touch existing Device/DiscoveredDevice tests.

**Done when:** `./mvnw -B -pl vision-domain test` green. (Downstream modules WILL be red — that's M2/M3's job; do NOT touch them.)

## 2. Task M2 — Application services + sim telemetry + discovery migration

**Scope:** `vision-application/src/**`, `adapters/adapter-simulation/src/**`, `adapters/adapter-discovery/src/**`.

- `AssetService` — implements `CreateAssetUseCase` (registers devices via `DeviceService`/`DeviceRepositoryPort`, validates category exists, applies the acting `Ownership` supplied via constructor — the dev principal comes from wiring), `ListAssetsUseCase`, `GetAssetDetailsUseCase` (status from `StreamService` active streams by device-ids; lastUsedAt/lastKnownPosition from latest usage).
- `UsageTracker` — collaborator invoked by `StreamService` on start/stop (constructor-injected, nullable-safe/optional): on first active stream of an asset → open `AssetUsage`; on last stop → close it. During an open usage, subscribes to `TelemetrySourcePort`s that `supports()` any of the asset's telemetry-capable devices; each sample → `TelemetryRepositoryPort.save`, update usage start/last position + sampleCount (throttle summary writes, e.g. every sample is fine in-memory but keep the write in one method for later batching).
- `StartAssetStreamUseCase`/`StopAssetStreamUseCase` — thin: resolve asset → device (error if ambiguous and no device specified: `IllegalArgumentException` listing candidates) → delegate to existing `StreamService`.
- `DeviceService`/`StreamService`: adapt to `Device` without type; `StreamService` notifies `UsageTracker`.
- `adapter-simulation`: `SimulatedTelemetrySource implements TelemetrySourcePort` — supports devices with `TELEMETRY` capability and protocol `sim`; emits 1 Hz `Telemetry` on a circular track (center from options `lat`/`lon` default 50.45,30.52; radius ~200 m; battery 100→drains 0.05%/s; heading tangent to circle). Same executor/publisher pattern as the video source. `SimulatedVideoSource` untouched.
- `adapter-discovery`: scanners map to `CategoryId` slugs (`"ip-camera"`, `"esp32-cam"`, `"usb-camera"`) instead of `DeviceType`.
- Tests: AssetService (create validates category + ≥1 device; summary status derivation; details assembly), UsageTracker lifecycle (open/close, telemetry sampling with a scripted TelemetrySourcePort, multi-device asset opens ONE usage), migrated existing tests, SimulatedTelemetrySource emission test, discovery mapping test updates.

**Done when:** `./mvnw -B -pl vision-application,adapters/adapter-simulation,adapters/adapter-discovery test` green twice.

## 3. Task M3 — Asset-first API + UI

**Scope:** `vision-api/src/**` only.

- New `CategoryController` (`GET /api/categories`), `AssetController`:
  - `POST /api/assets` — `CreateAssetRequest{displayName, category, attributes?, devices:[{name, protocol, uri, options?}]}` → 201 `AssetDetailsResponse`
  - `GET /api/assets` → `AssetSummaryResponse[]{assetId, displayName, category, categoryName, owner, status, lastUsedAt?, lastKnownPosition?, attributes}`
  - `GET /api/assets/{id}` → `AssetDetailsResponse` (+devices, +recentUsages with `{usageId, startedAt, endedAt?, startPosition?, lastPosition?, sampleCount}`)
  - `POST /api/assets/{id}/stream` (optional body: `deviceId?`, config overrides) → 201 `{streamId, viewUrl?}`; `DELETE /api/assets/{id}/stream` → 204
  - `GET /api/usages/{usageId}/telemetry?limit=` → samples (for a future map/trail view; plain JSON now)
- Device/stream/discovery endpoints: keep, adjust DTOs for the removed `DeviceType` (drop the field) and `DiscoveredDevice.suggestedCategory` (rename field to `suggestedCategory`).
- UI rework (`index.html`/`app.js`, same vanilla style): lead with **Assets** — card grid (displayName, category, owner, status badge, last used, last position if any; Start/Stop/Watch buttons; click → details panel with devices + usage history table). "Add asset" form (name, category dropdown from `/api/categories`, attributes key/value rows, one device subform) — discovery **Use** now prefills THIS form (device part + suggested category). Keep the raw devices/streams tables in a collapsed "Advanced" section.
- Tests: MockMvc for new controllers (happy paths, 404 unknown asset/category, 400 validation incl. zero-device asset and ambiguous-device start), migrated existing controller tests.

**Done when:** `./mvnw -B -pl vision-api test` green. (vision-app may still be red until M4.)

## 4. Task M4 — Wiring, seed data, full verify, live demo

**Scope:** `vision-app/src/**`, `application.properties` if needed, `README.md` (Quickstart refresh), full build.

- devsupport: `InMemoryCategoryRepository` (seeded: `drone`, `fpv-drone`(parent drone), `ip-camera`, `esp32-cam`(parent ip-camera), `usb-camera`, `robot`, `simulated` — with a few sensible attributeHints each), `InMemoryAssetRepository`, `InMemoryAssetUsageRepository`, `InMemoryTelemetryRepository`; dev principal constants (`UserId("dev")`/`GroupId("root")`) wired into `AssetService`.
- Wiring: `AssetService`, `UsageTracker` (with `List<TelemetrySourcePort>` incl. the new sim telemetry source), updated `StreamService` construction.
- Tests: context loads; ArchUnit still green; extend the sim smoke test → create an asset (sim video + sim telemetry devices, category `drone`), start via asset endpoint logic (use-case beans), observe frames AND ≥2 telemetry samples persisted, stop, assert usage closed with start/last positions set.
- Full `./mvnw -B verify`; then live E2E with the jar + mediamtx (docker): create asset via API, start, confirm HLS serves (existing path), let it run ~15 s, stop, then `GET /api/assets` (status/lastUsed/position populated) and `GET /api/assets/{id}` (usage listed, sampleCount > 0) and the telemetry endpoint — report actual JSON. Teardown.

**Done when:** full verify green + live demo transcript.

---

## Execution order

```
M1 ──▶ M2 ──▶ { M3 ∥ (nothing) } ──▶ M4      (M3 only needs M1, but runs after M2 to keep the tree buildable for its module test run)
```

Sequential M1→M2→M3→M4 — this is a cross-cutting refactor; each stage leaves its own scope green and reports which downstream modules remain red for the next stage.
