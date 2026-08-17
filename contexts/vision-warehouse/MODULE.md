# vision-warehouse

Asset/device inventory: `Asset` (the user-facing "my drone" — owned, categorized, 1..n devices,
attributes map), `Device` (low-level plumbing), `DeviceCategory`, discovered devices, flight-session
records (`AssetUsage` — moved in from flight in W1.6c, since every one of its readers was already
here), fleet-wide summaries and per-asset stats. Domain + application in one module
(docs/plans/active/DOMAIN-SEPARATION-W1.md §16 — the eight-context split dissolved `vision-domain`
and `vision-application`; this module holds warehouse's `.domain`/`.application` packages together,
the same layer boundary ArchUnit still enforces).

**What it deliberately does not do**: run any runtime itself — no video pipeline, no telemetry
ingestion, no CV probing (`ProbeService`/`DefaultProbeService`/`ProbeResult`/`ProbeFailedException`
moved to `perception.application.device` in W1.6d — a probe opens a `VideoSourcePort`/
`TelemetrySourcePort` exactly like a live stream does, which is ingest work, not inventory work, and
was filed here only because a `Device` is what gets probed). It reads the live facts it genuinely
needs — is this asset streaming right now, its freshest telemetry, its open detection-event count —
through `AssetLiveStatePort` (below), a port it declares and perception implements. It does not
command flight hardware (that's `vision-flight`) and does not resolve who may see what (that's
`VisibilityScope`, `vision-platform`, though `AssetService`/`FleetSummaryService`/`UsageService` all
accept one as a filter).

**Depends on:** `vision-kernel` (every id, `GeoPosition`, `Ownership`, `LifecycleState`,
`Capability`, `StreamDescriptor`, `Telemetry`…) · `vision-platform` (`AuditTrailPort`/`AuditEntry` —
every mutation writes one; `VisibilityScope` — every scoped read filters by it) · nothing else.
**Used by:** every other context, every adapter, `vision-app`, `vision-api`.
**Build/test:** `./mvnw -B -pl contexts/vision-warehouse test` — 170 tests green.

**Warehouse is the pure leaf of the whole context graph** (docs/plans/active/DOMAIN-SEPARATION-W1.md
§16's measured edge list: `warehouse -> (nothing)`) — nothing it owns reads any other context, and
every other context that touches inventory reads *this* one. §15's tie-breaker states the rule
generally ("inventory is the stable layer; runtime reads inventory, inventory never reads runtime"),
but until **W1.6e** that was a convention, not yet a fact: `DefaultAssetService`/
`DefaultDeviceService`/`DefaultFleetSummaryService`/`DefaultAssetStatsService` all reached perception's
`StreamService`/`UsageTracker`/`DetectionEventRepositoryPort` directly, which was the
`perception <-> warehouse` module cycle (§14's C3+C6) — the very last one standing before extraction
could happen at all. W1.6e closed it in two moves: (1) stream-*starting* orchestration (resolving a
device, handing it + `PipelineConfig`/`TrackingConfigPatch` to the runtime) moved off `AssetService`
entirely, into perception's own `perception.application.stream.AssetStreamService` — perception
resolves the asset itself (the legal direction), instead of the CRUD context driving the runtime and
importing its config types; (2) the four remaining, genuinely-live reads were inverted behind
`AssetLiveStatePort` (below), declared here in kernel-typed vocabulary, implemented by perception's
`StreamBackedAssetLiveState`. This matters beyond tidiness: a Maven module graph cannot contain a
cycle at all, so this inversion is literally what let `contexts/vision-warehouse` become module #1 of
the extraction — every other context's own module depends, directly or transitively, on this one
being real (see the dependency table in docs/plans/active/DOMAIN-SEPARATION-W1.md §16).

## API surface

### `com.drones.vision.warehouse.domain.model`
- `record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices, Map<String,String> attributes, LifecycleState state)` — devices must be non-empty; 6-arg convenience ctor defaults `state=ACTIVE`; `isActive()`, `isDeleted()`, `withDevices(Set)`, `withAttributes(Map)`, `withDetails(String,CategoryId,Map)`, `withState(LifecycleState)` return copies
- `record AssetImage(byte[] data, String contentType)` (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3) — the asset's user-facing photo, one per asset, no history; `data` non-empty, `contentType` non-blank (accepted-types/max-size is `vision-api`'s wire-boundary concern); `data()` returns a fresh `data.clone()` on every access, mirroring `VideoFrame#data()`'s defensive-copy discipline
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount, StreamId streamId)` (flight → warehouse in **W1.6c**, C9 — the record was flight's but all four readers, `AssetDetails`/`DefaultAssetService`/`DefaultAssetStatsService`/`DefaultUsageService`, were already here) — a "flight"/session for an `Asset`: opened when the asset starts streaming, closed on stop; `endedAt`/`startPosition`/`lastPosition`/`streamId` nullable, `endedAt>=startedAt` if present; 7-arg convenience ctor defaults `streamId=null`; `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)` all preserve `streamId` unchanged. `streamId` (docs/plans/done/MVP2-PLAN.md §R, R-a2) is the stream whose start opened this usage, recorded once by `UsageTracker` (perception) so `ReplayService` (events) can join a finished usage back to its detections; `null` for a legacy/streamless usage
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints)` — `parent` nullable (top-level)
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state)` — 4-arg convenience ctor defaults `state=ACTIVE`; `isActive()`, `isDeleted()`, `withDetails(...)`, `withState(...)`
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — `suggestedCategory`/`suggestedStream` nullable

### `com.drones.vision.warehouse.domain.port` (driven — implemented by adapters, or by perception for `AssetLiveStatePort`)
- `AssetImageRepositoryPort`: `void save(AssetId, AssetImage)` upsert; `Optional<AssetImage> findByAssetId(AssetId)`; `boolean existsByAssetId(AssetId)` — cheap presence check for a fleet list's `hasImage`; `void deleteByAssetId(AssetId)` idempotent. No dependency on `AssetRepositoryPort` (no referential integrity between repositories, a convention every port here follows)
- `AssetLiveStatePort` (**W1.6e** — the port inversion that killed the last module cycle): `Map<DeviceId,StreamId> activeStreamsByDevice()`; `int stopStreamsForDevices(Collection<DeviceId>)` — returns how many actually stopped, **synchronous** deliberately (W2 turns this into an event warehouse publishes and perception reacts to instead — a staging post, not the destination); `Optional<Telemetry> latestTelemetry(AssetId)`; `Map<AssetId,Integer> openDetectionEventCounts(int scanLimit)` — `scanLimit` stays the caller's own tuning constant, threaded through. Implemented by perception's `StreamBackedAssetLiveState`; consumed by `DefaultAssetService`, `DefaultDeviceService`, `DefaultAssetStatsService`, `DefaultFleetSummaryService`
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` idempotent
- `AssetUsageRepositoryPort` (flight → warehouse, **W1.6c**, C9): `AssetUsage save(AssetUsage)` upsert; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` newest-first; `List<AssetUsage> findRecent(int limit)` fleet-wide counterpart (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8); `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DeviceDiscoveryPort`: `String method()` stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — **blocking**, must self-time-box, empty list ≠ error
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` idempotent
- `FleetLiveUpdatePort` (W1.6b, warehouse's slice of the former god-port `LiveUpdatePublisherPort`): `void publishFleetChanged()` — no payload, a driving adapter re-derives its own snapshot

### `application.asset`
- `AssetService` (interface) → `DefaultAssetService` — **inventory/CRUD only since W1.6e**: starting a stream moved to `perception.application.stream.AssetStreamService` (it hands perception's own `PipelineConfig`/`TrackingConfigPatch` to the runtime — a CRUD context should not import the runtime's types). `stopStream` stays here — its signature names no perception type, and is expressible entirely through `AssetLiveStatePort`
  - `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, AssetLiveStatePort)` — reaches devices through `DeviceService` (never the device repository directly) and live runtime state through `AssetLiveStatePort` (never `StreamService`)
  - `Asset create(AssetSpec, Ownership, UserId actor)` — validates category, registers each `AssetSpec#devices()` entry, and assigns each `AssetSpec#existingDeviceIds()` entry via the same eligibility check `#assignDevice` uses (the "promote to asset" flow — backend follow-up batch, see Status); audits `CREATED`
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default; status from `assetLiveStatePort.activeStreamsByDevice().keySet()`
  - `List<AssetSummary> assets(VisibilityScope scope, boolean includeDeleted)` — scoped read, keeps only `scope.includes(asset)`; `unbounded()` returns the unscoped result unchanged. Internal/system callers (simulation resume, live snapshot) keep calling the unscoped overload
  - `AssetDetails details(AssetId)` / `details(VisibilityScope, AssetId)` — the scoped form throws `NoSuchElementException` (404, not 403) for an out-of-scope asset, so a scoped read never reveals existence
  - `Asset update(AssetId, AssetEdit, UserId)` — partial; audits `UPDATED` with changed fields only
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED -> ACTIVE` throws `IllegalStateException`
  - `AssetDeletion delete(AssetId, UserId)` — **soft**: marks asset+devices `DELETED`, keeps usages/telemetry
  - `void stopStream(AssetId)` — resolves the asset, then `assetLiveStatePort.stopStreamsForDevices(asset.devices())`; unknown asset is a no-op
  - `Asset assignDevice(AssetId, DeviceId, UserId actor)` / `unassignDevice(...)` (docs/main/CYCLES-PLAN.md §8) — assign requires the device exist, not be soft-deleted, and not already belong to any asset (409 naming the owner); unassign requires leaving ≥1 device. Both share/mirror the private `requireAssignable` eligibility check `create`'s `existingDeviceIds` path also uses
- Records: `AssetSpec(displayName, category, attributes, devices, existingDeviceIds)`, `AssetEdit(displayName, category, attributes)` (partial, `NOTHING`; **`changesManagedFields()`** — true iff `category != null`, the operator-vs-manager authority split callers gate on so an assigned pilot may rename their own aircraft and edit its custom fields but not re-classify it, docs/plans/active/OPS-UX-PLAN.md §1), `AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition)`, `AssetStatus` enum `{OFFLINE, STREAMING}`, `AssetDetails(summary, devices, recentUsages)` (up to 20 most-recent usages), `AssetDeletion(id, displayName, devicesDeleted, usagesRetained, streamsStopped)`
- `AssetStatsService` (interface) → `DefaultAssetStatsService` — one asset's flight-utilization stats (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A), behind `GET /api/assets/{id}/stats`
  - `DefaultAssetStatsService(AssetUsageRepositoryPort, AssetLiveStatePort)` — package-private 3-arg test seam adds `Supplier<Instant> clock`
  - `AssetStats statsFor(AssetId)` — fetch-then-aggregate over `findRecentByAsset(assetId, STATS_FETCH_LIMIT=10_000)` (honest cap — an asset with more flights under-reports, biased toward its most recent history, until a real server-side aggregate query exists, same posture as events' `DefaultReplayService`); duration floored at zero against clock skew; `avgFlightSeconds` averages **closed** usages only; `lastKnownBatteryPercent` reuses `assetLiveStatePort.latestTelemetry`, rounded to the nearest whole percent (the endpoint's frozen contract wants an `Integer` where `AssetAttention` carries a raw `Double`). **Never throws and never checks asset existence** — an unknown asset just aggregates to zero/null; `vision-api`'s controller 404s it itself via `AssetService#details`
- `AssetStats(totalFlightSeconds, flightCount, firstFlownAt, lastFlownAt, avgFlightSeconds, lastKnownBatteryPercent, flightInProgress)` — the four optionals `null` (never fabricated `0`) when there's nothing to report

### `application.category`
- `CategoryService` (interface) → `DefaultCategoryService(CategoryRepositoryPort)` — `List<DeviceCategory> categories()`, sorted by `CategoryId.slug()`
- `CategoryCounts(categoryId, categoryName, total, active, deactivated, deleted, streaming)` — one `FleetSummary#categories()` row

### `application.device` (package `warehouse.application.device` — disambiguated from perception's own `device` leaf, W1.6d)
- `DeviceService` (interface) → `DefaultDeviceService(DeviceRepositoryPort, AssetLiveStatePort, AuditTrailPort, EventPublisherPort)` — stops a device's stream via `assetLiveStatePort.stopStreamsForDevices(Set.of(deviceId))`, never by iterating `StreamService` itself
  - `Device register(DeviceRegistration, UserId)` — publishes `DEVICE_ONLINE`, audits `CREATED`; `List<Device> devices()` / `devices(includeDeleted)`; `Optional<Device> find(DeviceId)`; `Device update(DeviceId, DeviceEdit, UserId)` partial; `Device setState(...)` idempotent, stops the stream leaving service; `Device delete(...)` **soft**, stays a member of its asset
- `DeviceRegistration(name, capabilities, stream)`, `DeviceEdit(name, capabilities, stream)` (all-nullable partial edit, `NOTHING`)

### `application.discovery`
- `DiscoveryService` (interface) → `DefaultDiscoveryService(List<DeviceDiscoveryPort>)` — indexed by `port.method()`
  - `DiscoveryScanResult scan(DiscoveryScanSpec)` — one virtual thread per requested port; deadline = `timeout` + a `GRACE_PERIOD` (200ms); per-adapter failure isolated into `failedMethods`, never thrown; `IllegalArgumentException` only for an unknown requested method key
  - **Dedup is two-pass**: exact `(method,address)` within one scan, then a cross-method merge keyed by network identity (`URI.getHost()`, case-insensitive, or exact address) — only merges a group spanning **2+ distinct methods**. Merged `method` = sorted, `"+"`-joined methods (e.g. `"mdns+onvif"`); `name`/`suggestedStream`/`suggestedCategory` resolved by priority order (`onvif` > `mdns` > others); `details` unions every candidate's map, namespacing a key contributed by 2+ methods `"<method>.<key>"`
- `DiscoveryScanSpec(timeout, methods)` + `defaults()`; `DiscoveryScanResult(devices, failedMethods)`

### `application.fleet`
- `FleetSummaryService` (interface) → `DefaultFleetSummaryService` — the manager dashboard's single aggregated read (docs/plans/done/MVP3-PLAN.md C-a), behind `GET /api/fleet/summary`
  - `DefaultFleetSummaryService(AssetService, AssetLiveStatePort)` — **down from 4 collaborators to 2 in W1.6e** (`StreamService`/`UsageTracker`/`DetectionEventRepositoryPort` collapsed into `AssetLiveStatePort`)
  - `FleetSummary summary(boolean includeArchived)` / `summary(VisibilityScope, boolean)` — both fold through a shared private `summarize(List<AssetSummary>)`; category counts folded over every in-scope asset (never capped); per-asset attention rows sorted by name and capped at `MAX_ASSETS_IN_SUMMARY=500` (`FleetSummary#totalAssets()` always reports the true count, so a caller can detect truncation); `streaming`/`streamId`/`batteryPercent`/`telemetryAgeMs`/`openEventCount` all derived from `assetLiveStatePort`. Deliberately **no `sourceState` field** — there is no honest "reconnecting" signal to read
- `FleetSummary(categories, assets, totalAssets)`; `AssetAttention(assetId, displayName, categoryId, categoryName, lifecycle, streaming, streamId, batteryPercent, telemetryAgeMs, openEventCount, flightMode, armed, failsafe)` — `streamId`/`batteryPercent`/`telemetryAgeMs` nullable; `flightMode`/`armed`/`failsafe` (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b — see flight's own MODULE.md Status for the wave writeup) are the freshest sample's `FlightState#mode()`/`#armed()`/`#failsafe()` (`FlightState`/`Telemetry` live in `vision-kernel`), all three nullable under the same "no telemetry, or telemetry with no flight state" condition as `batteryPercent`

### `application.usage`
- `UsageService` (interface) → `DefaultUsageService` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8) — the fleet-wide "replay library" list, behind `GET /api/usages`. A sibling of events' `ReplayService`, not a method on it — `ReplayService` never needed `AssetRepositoryPort`, and bolting this on would have pushed it to 6 constructor params for a path most of its collaborators don't touch
  - `DefaultUsageService(AssetUsageRepositoryPort, AssetRepositoryPort)`
  - `List<UsageSummary> recent(VisibilityScope, AssetId assetIdOrNull, int limit)` — clamped to `MAX_LIMIT=500`; scope filtering runs **after** the repository's own `limit` (a known, accepted first-cut limitation — a scoped caller can see fewer than `limit` rows even when more of their own flights exist further back)
  - `UsageSummary(usageId, assetId, assetName, startedAt, endedAt, durationSeconds, sampleCount)` — `assetName=""` (never `null`) when the owning asset can't be resolved at all, and that row is included only for an `unbounded()` caller, silently dropped for every other scope (no `Ownership` left to check)

## Conventions
- **Validation**: every domain record validates in its compact constructor (`if (…) throw new IllegalArgumentException(…)`); the application layer uses `Objects.requireNonNull`.
- **Defensive copies**: every `List`/`Set`/`Map` component reassigned via `List.copyOf`/`Set.copyOf`/`Map.copyOf`.
- **No referential integrity between repositories**: `AssetImageRepositoryPort` has no dependency on `AssetRepositoryPort`, `AssetUsageRepositoryPort` on `AssetRepositoryPort`, etc. — existence checks are the application layer's job.
- **The acting user is a method parameter (`UserId actor`)**, never a constructor dependency.
- **Constructor injection only**; collaborators wrapped in `Objects.requireNonNull`.
- **Soft-delete, not hard-delete**: `Asset#delete`/`Device#delete` mark `LifecycleState.DELETED` and keep every row (usages, telemetry) — nothing here ever issues a real `DELETE`.
- **Virtual threads**: `DefaultDiscoveryService` starts one `Thread.ofVirtual()` per requested discovery port per scan call; a hung adapter's thread is never tracked or interrupted (cheap + daemon).
- **N-1-arg convenience constructor idiom**: e.g. `Asset`'s 6-arg ctor defaulting `state=ACTIVE`, `AssetUsage`'s 7-arg ctor defaulting `streamId=null` — every field a wave adds gets one more convenience ctor layer so every pre-existing call site keeps compiling.

## Gotchas
- **`AssetLiveStatePort#stopStreamsForDevices` is synchronous by design, not an oversight** — warehouse still decides, in the same request, that a device's stream must stop before the device/asset is retired or deleted. W2 is where this becomes an event warehouse publishes and perception reacts to asynchronously; this port is a staging post, not the destination.
- **`Asset.devices` must be non-empty** — an asset with zero devices cannot even be constructed; `AssetSpec`'s own "at least one device" check duplicates this at the command layer for fail-fast, satisfied by `devices` or `existingDeviceIds` (or both).
- **`DefaultDiscoveryService`'s cross-method merge only triggers for a group spanning 2+ *distinct* methods** — two candidates from the *same* method landing in the same network-identity group (e.g. two mDNS services on one host) are intentionally left unmerged.
- **`DiscoveredDevice.method`'s compact ctor only rejects blank/null** — no single-token or lower-case pattern, unlike `StreamDescriptor.protocol`/`CategoryId.slug` — so a merged `"mdns+onvif"` value is valid without any domain change. `DeviceDiscoveryPort.method()`'s own "stable lower-case key" contract still applies to each *individual* port's key, not to this merged/derived field.
- **`DefaultAssetStatsService#statsFor` is a fetch-then-aggregate workaround, not a real server-side query** — `STATS_FETCH_LIMIT=10,000`, newest-first; an asset with more flights than that under-reports and is biased toward recent history (mirrors events' `DefaultReplayService#TELEMETRY_FETCH_LIMIT` caveat exactly). Not fixed here; would need a real aggregate query on `AssetUsageRepositoryPort`.
- **`AssetImage#data()`/`SampleImage`-style clone-in/clone-out**: `AssetImage`'s compact ctor clones `data` in, and `data()` clones it out on every access — the same defensive-copy discipline `VideoFrame#data()` established for `ByteBuffer`, adapted for `byte[]` (no read-only-view equivalent for arrays).
- **`AssetUsage#streamId` is stamped once, at open time, by perception's `UsageTracker`, and never changed afterward** — it exists purely so events' `ReplayService` can join a finished usage back to its detections; `null` for a legacy usage or one opened by an asset with no video device.
- **`DefaultAssetService`/`DefaultDeviceService`/`DefaultFleetSummaryService`/`DefaultAssetStatsService` may never import anything from `perception.**` again** — that is exactly the cycle W1.6e closed. Any new "read something live" need must add a method to `AssetLiveStatePort`, not a new direct collaborator.

## Status

**W1.7b/c** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): `vision-domain`/`vision-application` dissolved; warehouse's `.domain`/`.application` packages became this one module, a directory move with the ArchUnit layer boundary preserved. No behavior change; 170/170 tests green.

**Backend follow-ups batch (2026-07-24), item 1 — `AssetSpec#existingDeviceIds`** (the "promote to asset" flow, docs/main/CYCLES-PLAN.md §8's contract extended): `DefaultAssetService#create` now assigns already-registered, unowned devices to the new asset in the same call, validated by a new shared private `requireAssignable` helper also used by `#assignDevice` — must exist (404), must not be soft-deleted (400), must not already belong to another asset (409, naming the owner). `AssetSpec`'s "at least one device" rule now passes if `devices` or `existingDeviceIds` (or both) is non-empty. (The batch's other two items — `SimulationService#resumeAll()` and the `detection-events` live topic — belong to the simulation and perception/events contexts respectively; see their own MODULE.mds.)

**docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A done** (per-asset flight stats): `AssetStats`/`AssetStatsService`/`DefaultAssetStatsService` — the manager asset page's KPI tile row, behind `GET /api/assets/{id}/stats`. Fetch-then-aggregate mirroring events' `DefaultReplayService` (see Gotchas); "now" is injectable via a package-private `Supplier<Instant> clock` seam, defaulting to `Instant::now` in production; battery reuse verified against `DefaultFleetSummaryService#toAttention`'s existing derivation rather than assumed. Never throws/never checks asset existence — 404-for-unknown is `vision-api`'s `AssetStatsController`'s job via `AssetService#details`.

**docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8 done** (replay library): new `usage` feature package — `UsageService`+`DefaultUsageService`, `UsageSummary` — behind `GET /api/usages`, joining events' `ReplayService`-backed endpoints on the same `UsageTimelineController`. Kept as its own service area rather than a method on `ReplayService` for the collaborator-set reason above. Built against `AssetUsageRepositoryPort#findRecent(int)`, additive.

**docs/plans/done/UX-REWORK-PLAN.md §U-d item 3** added this context's `AssetImage`/`AssetImageRepositoryPort` (two genuinely substitutable implementations exist — JPA/bytea and in-memory — earning the interface per `.claude/skills/java-clean-code/SKILL.md` §1); the probe service itself (`ProbeService`/`DefaultProbeService`) that CONTRACT 1 also delivered now lives in `perception.application.device` (moved W1.6d) — see that context's MODULE.md for the probe's own Status entry.

**docs/main/CYCLES-PLAN.md §8 (CW-a)** added `AssetService#assignDevice`/`#unassignDevice` — device↔asset reassignment; `DeviceService`/`AssetService` update/setState/delete already existed before this.

Fully implemented otherwise: category/device/discovery CRUD, fleet summary aggregation, per-asset stats, the replay-library list. Nothing in this context is a stub — every port listed above has at least one real implementation in `storage/persistence` or `vision-app`'s devsupport.
