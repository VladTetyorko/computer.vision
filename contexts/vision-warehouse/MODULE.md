# vision-warehouse

Asset/device inventory: `Asset` (owned, categorized, 1..n devices, attributes map), `Device`
(low-level plumbing), `DeviceCategory`, discovery, `AssetUsage` ("flight" sessions), fleet-wide
summaries and per-asset stats. Domain + application in one module (`.domain`/`.application`
packages, ArchUnit-enforced layer boundary).

Runs no runtime itself — no video pipeline, telemetry ingestion, or CV probing (that's
`perception`). Live facts it needs (streaming now, freshest telemetry, open detection-event
count) come through `AssetLiveStatePort`, a port it declares and perception implements. It does
not command flight hardware (`vision-flight`) and does not resolve who may see what
(`VisibilityScope`, `vision-platform`, accepted as a filter by `AssetService`/
`FleetSummaryService`/`UsageService`).

**Warehouse is the pure leaf of the context graph** — nothing it owns reads any other context;
every other context that touches inventory reads this one. That property is what let this module
extract first; see `docs/plans/active/DOMAIN-SEPARATION-W1.md` §15-16 for the dependency-DAG
rationale and `docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md` for the session-ownership/
`origin` work below.

**Depends on:** `vision-kernel` · `vision-platform` (`AuditTrailPort`, `VisibilityScope`) ·
nothing else.
**Used by:** every other context, every adapter, `vision-app`, `vision-api`.
**Build/test:** `./mvnw -B -pl contexts/vision-warehouse test`

## API surface

### `domain.model`
- `record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices, Map<String,String> attributes, LifecycleState state)` — `devices` non-empty; 6-arg convenience ctor defaults `state=ACTIVE`; `isActive()`, `isDeleted()`, `withDevices(Set)`, `withAttributes(Map)`, `withDetails(String,CategoryId,Map)`, `withState(LifecycleState)`
- `record AssetImage(byte[] data, String contentType)` — one photo per asset, no history; `data` non-empty, `contentType` non-blank; `data()` returns a fresh clone on every access (mirrors `VideoFrame#data()`)
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase, UsageOrigin origin)` — **one constructor only** (the 10-component header + compact-ctor validation); `endedAt`/positions/`streamId` nullable, `endedAt>=startedAt` if present, `phase`/`origin` never null. `streamId` is the stream whose start opened this usage (`null` for legacy/streamless), stamped once by perception's `UsageTracker`. `phase` is this module's `UsagePhase`, not flight's `FlightPhase` (see Gotchas). `origin` (`kernel.UsageOrigin{STREAM,OPERATOR}`) is which verb opened the session. `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)`, `withPhase(UsagePhase)`, `withOrigin(UsageOrigin)` each return a copy with one field replaced
- `enum UsagePhase { PREFLIGHT, IN_FLIGHT, LINK_LOST, POSTFLIGHT, ABANDONED, CLOSED }` — `AssetUsage#phase`'s type
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints)` — `parent` nullable (top-level)
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state, DeviceOrigin origin)` — canonical 6-arg; 5-arg convenience ctor defaults `origin=LIVE`; 4-arg convenience ctor defaults `state=ACTIVE` (chains through the 5-arg, so also defaults `origin=LIVE`); `isActive()`, `isDeleted()`, `withDetails(name, capabilities, stream, origin)`, `withState(...)`
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — a suggestion, not a registered `Device`; `suggestedCategory`/`suggestedStream` nullable

### `domain.port` (driven — adapters, or perception for `AssetLiveStatePort`)
- `AssetImageRepositoryPort`: `void save(AssetId, AssetImage)` upsert; `Optional<AssetImage> findByAssetId(AssetId)`; `boolean existsByAssetId(AssetId)`; `void deleteByAssetId(AssetId)` idempotent. No dependency on `AssetRepositoryPort`
- `AssetLiveStatePort`: `Map<DeviceId,StreamId> activeStreamsByDevice()`; `int stopStreamsForDevices(Collection<DeviceId>)` — count actually stopped, **synchronous** by design; `Optional<Telemetry> latestTelemetry(AssetId)`; `Map<AssetId,Integer> openDetectionEventCounts(int scanLimit)`. Implemented by perception's `StreamBackedAssetLiveState`
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` idempotent
- `AssetUsageRepositoryPort`: `AssetUsage save(AssetUsage)` upsert; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` newest-first; `List<AssetUsage> findRecent(int limit)` fleet-wide; `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset; `Optional<AssetUsage> findByStream(StreamId)` — ≤1 per stream, empty for legacy/streamless
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DeviceDiscoveryPort`: `String method()` stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — blocking, self-time-boxed, empty list ≠ error
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` idempotent
- `FleetLiveUpdatePort`: `void publishFleetChanged()` — no payload, a driving adapter re-derives its own snapshot

### `application.asset`
- `AssetService` (interface) → `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, AssetLiveStatePort)` — inventory/CRUD only; reaches devices only through `DeviceService`, live runtime state only through `AssetLiveStatePort`. Starting a stream lives in perception's `AssetStreamService`; `stopStream` stays here
  - `Asset create(AssetSpec, Ownership, UserId actor)` — registers `spec.devices()`, assigns `spec.existingDeviceIds()` (same eligibility check as `assignDevice`); audits `CREATED`
  - `Asset createFromCandidate(AssetSpec, Ownership, UserId actor)` — same as `create`, plus a duplicate check on `spec.devices()`'s new registrations by `(protocol, uri, sysid)` against every active device first; throws `IllegalStateException` naming the owner on a match. `existingDeviceIds` entries are exempt (already covered by `assignDevice`'s eligibility rule)
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default
  - `List<AssetSummary> assets(VisibilityScope, boolean)` — filters the unscoped result; `unbounded()` returns it unchanged
  - `AssetDetails details(AssetId)` / `details(VisibilityScope, AssetId)` — scoped form throws `NoSuchElementException` (404, not 403) when out of scope
  - `Asset update(AssetId, AssetEdit, UserId)` — partial, audits `UPDATED` with changed fields only
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED -> ACTIVE` throws `IllegalStateException`
  - `AssetDeletion delete(AssetId, UserId)` — soft: marks asset+devices `DELETED`, keeps usages/telemetry
  - `void stopStream(AssetId)` — no-op for unknown asset
  - `Asset assignDevice(AssetId, DeviceId, UserId actor)` / `unassignDevice(...)` — assign requires the device exist, not be deleted, not already assigned (409 naming owner); unassign requires ≥1 device remains
- Records: `AssetSpec(displayName, category, attributes, devices, existingDeviceIds)` (4-arg convenience ctor defaults `existingDeviceIds=List.of()`; at least one device across both lists required), `AssetEdit(displayName, category, attributes)` (`NOTHING`; `changesManagedFields()` true iff `category != null` — the operator/manager authority split), `AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition)`, `AssetStatus{OFFLINE,STREAMING}`, `AssetDetails(summary, devices, recentUsages)`, `AssetDeletion(id, displayName, devicesDeleted, usagesRetained, streamsStopped)`
- `AssetStatsService` (interface) → `DefaultAssetStatsService` — one asset's flight-utilization stats behind `GET /api/assets/{id}/stats`
  - `DefaultAssetStatsService(AssetUsageRepositoryPort, AssetLiveStatePort)`; overloads add an explicit `statsFetchLimit` and/or a package-private `Supplier<Instant> clock` test seam
  - `AssetStats statsFor(AssetId)` — fetch-then-aggregate over `findRecentByAsset(assetId, STATS_FETCH_LIMIT=10_000)`; duration floored at zero; `avgFlightSeconds` averages **closed** usages only; `lastKnownBatteryPercent` from `assetLiveStatePort.latestTelemetry`, rounded to nearest percent. Never throws, never checks asset existence
- `AssetStats(totalFlightSeconds, flightCount, firstFlownAt, lastFlownAt, avgFlightSeconds, lastKnownBatteryPercent, flightInProgress)` — optionals `null` (never fabricated `0`) when nothing to report
- `AssetAttention(assetId, displayName, categoryId, categoryName, lifecycle, streaming, streamId, batteryPercent, telemetryAgeMs, openEventCount, flightMode, armed, failsafe)` — one `FleetSummary#assets()` row; `streamId`/`batteryPercent`/`telemetryAgeMs`/`flightMode`/`armed`/`failsafe` nullable under a no-telemetry (or no flight-state) condition

### `application.category`
- `CategoryService` (interface) → `DefaultCategoryService(CategoryRepositoryPort)` — `List<DeviceCategory> categories()`, sorted by `CategoryId.slug()`
- `CategoryCounts(categoryId, categoryName, total, active, deactivated, deleted, streaming)` — one `FleetSummary#categories()` row

### `application.directory`
- `AssetDirectoryService` (interface) → `DefaultAssetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` — narrow read-only seam for a caller that must not depend on `AssetService`/`DeviceService` (see Gotchas)
  - `Optional<Asset> findByDevice(DeviceId)`, `Optional<Device> findDevice(DeviceId)`, `Optional<Asset> find(AssetId)`

### `application.device`
- `DeviceService` (interface) → `DefaultDeviceService(DeviceRepositoryPort, AssetLiveStatePort, AuditTrailPort, EventPublisherPort)` — stops a device's stream via `assetLiveStatePort.stopStreamsForDevices(Set.of(id))`, never by touching runtime directly
  - `Device register(DeviceRegistration, UserId)` — publishes `DEVICE_ONLINE`, audits `CREATED`; `List<Device> devices()` / `devices(includeDeleted)`; `Optional<Device> find(DeviceId)`; `Device update(DeviceId, DeviceEdit, UserId)` partial; `Device setState(...)` idempotent, stops the stream leaving service; `Device delete(...)` soft, stays a member of its asset
- `DeviceRegistration(name, capabilities, stream, origin)` — 3-arg convenience ctor defaults `origin=LIVE`; `DeviceEdit(name, capabilities, stream, origin)` (all-nullable partial, `NOTHING`)

### `application.discovery`
- `DiscoveryService` (interface) → `DefaultDiscoveryService(List<DeviceDiscoveryPort>)` (+ overload with explicit `gracePeriod`) — indexed by `port.method()`
  - `DiscoveryScanResult scan(DiscoveryScanSpec)` — one virtual thread per requested port; deadline = `timeout + GRACE_PERIOD(200ms)`; per-adapter failure isolated into `failedMethods`; `IllegalArgumentException` only for an unknown requested method
  - Dedup is two-pass: exact `(method,address)` within one scan, then a cross-method merge keyed by network identity (`URI.getHost()`, case-insensitive, or exact address) — only merges a group spanning 2+ distinct methods (see Gotchas)
- `DiscoveryScanSpec(timeout, methods)` + `defaults()`; `DiscoveryScanResult(devices, failedMethods)`

### `application.fleet`
- `FleetSummaryService` (interface) → `DefaultFleetSummaryService(AssetService, AssetLiveStatePort)` (+ overload with explicit `maxAssetsInSummary`/`openEventsScanLimit`) — the manager dashboard's aggregated read behind `GET /api/fleet/summary`
  - `FleetSummary summary(boolean includeArchived)` / `summary(VisibilityScope, boolean)` — category counts folded over every in-scope asset (never capped); per-asset attention rows sorted by name, capped at `MAX_ASSETS_IN_SUMMARY=500` (`totalAssets()` always reports the true count). No `sourceState` field — no honest "reconnecting" signal exists to read
- `FleetSummary(categories, assets, totalAssets)`

### `application.usage`
- `UsageService` (interface) → `DefaultUsageService(AssetUsageRepositoryPort, AssetRepositoryPort)` — the fleet-wide "replay library" list behind `GET /api/usages`
  - `List<UsageSummary> recent(VisibilityScope, AssetId assetIdOrNull, int limit)` newest-first, clamped to `MAX_LIMIT=500`; scope filtering runs **after** the repository's own `limit` (a scoped caller can see fewer than `limit` rows even when more of their own history exists further back)
  - `Optional<UsageSummary> byStream(VisibilityScope, StreamId)` — same row shape reached by stream id; out-of-scope and does-not-exist collapse to the same empty answer
  - `UsageSummary(usageId, assetId, assetName, startedAt, endedAt, durationSeconds, sampleCount)` — `assetName=""` (never `null`) when the owning asset can't be resolved, included only for an `unbounded()` caller
- `UsageSessionService` (interface) → `DefaultUsageSessionService(AssetUsageRepositoryPort)` — **the only code in the platform that constructs or persists an `AssetUsage`**
  - `AssetUsage open(AssetId, StreamId streamIdOrNull, UsageOrigin origin, Instant startedAt)` — one method, no overload; constructs+persists a new `PREFLIGHT` usage; `streamId=null` opens a telemetry-only session
  - `AssetUsage fold(AssetUsage, GeoPosition positionOrNull, UsagePhase)` — pins `startPosition` on the first positioned sample, advances `lastPosition`, always increments `sampleCount`, replaces `phase`; **never persists**
  - `AssetUsage updatePhase(AssetUsage, UsagePhase)` — replaces only `phase`; **never persists**
  - `AssetUsage close(AssetUsage, UsagePhase, Instant endedAt)` — stamps `endedAt`+`phase`, persists
  - `AssetUsage save(AssetUsage)` — persists as-is, for a caller (perception's batching) that decides its own write timing
  - `boolean usageBelongsToAsset(UsageId, AssetId)` — unscoped membership check; filed here rather than on `UsageService` because every `UsageService` method is scope-checked and this one deliberately isn't
- `UsageIdleCloseService` (interface) → `DefaultUsageIdleCloseService(AssetUsageRepositoryPort, AssetLiveStatePort, UsageSessionService, IdleUsageCloseSettings)` (+ package-private test-seam ctor taking `Supplier<Instant> clock`) — `docs/plans/active/OPERATOR-UX-5-PLAN.md` finding U1, wave W1: closes every open usage whose last observed activity is stale, so a crashed process/killed station/lost MAVLink link no longer leaves a usage "Flying now" forever. `vision-app`'s `UsageIdleCloseRunner` calls this on a schedule; nothing in this module invokes it on its own
  - `int closeIdleUsages()` — fetch-then-filter over `AssetUsageRepositoryPort#findRecent(SWEEP_FETCH_LIMIT=10_000)` (same honest-cap workaround as `DefaultAssetStatsService#statsFor`; no fleet-wide "open usages" port method exists), skips already-`endedAt`-stamped rows; for each open one, last-activity = `AssetLiveStatePort#latestTelemetry(assetId)`'s sample instant, clamped up to `startedAt` if that sample predates this usage (a stale sample can outlive its own usage — perception's per-asset tracker is never evicted) or if no sample is known at all (e.g. after a station restart); closes through `usageSessionService.close(usage, phase, lastActivityAt)` — `endedAt` is always that observed instant, **never `Instant.now()`** (CLAUDE.md rule 9: "newest data... should be used", not fabricated). Closed `phase` mirrors `vision-flight`'s `FlightPhaseRule#onSessionClosed` by name only (see `UsagePhase`'s own gotcha below): `IN_FLIGHT`/`LINK_LOST` → `ABANDONED`, everything else → `CLOSED`. Returns the count actually closed
- `IdleUsageCloseSettings(Duration idleThreshold)` — validates positive/non-null in its compact ctor; `defaults()` → 10 minutes

## Conventions
- **Validation**: every domain record validates in its compact constructor (`if (…) throw new IllegalArgumentException(…)`); the application layer uses `Objects.requireNonNull`.
- **Defensive copies**: every `List`/`Set`/`Map` component reassigned via `List.copyOf`/`Set.copyOf`/`Map.copyOf`.
- **No referential integrity between repositories**: `AssetImageRepositoryPort` has no dependency on `AssetRepositoryPort`, `AssetUsageRepositoryPort` on `AssetRepositoryPort`, etc. — existence checks are the application layer's job.
- **The acting user is a method parameter (`UserId actor`)**, never a constructor dependency.
- **Constructor injection only**; collaborators wrapped in `Objects.requireNonNull`.
- **Soft-delete, not hard-delete**: `Asset#delete`/`Device#delete` mark `LifecycleState.DELETED` and keep every row (usages, telemetry) — nothing here ever issues a real `DELETE`.
- **Virtual threads**: `DefaultDiscoveryService` starts one `Thread.ofVirtual()` per requested discovery port per scan call; a hung adapter's thread is never tracked or interrupted (cheap + daemon).
- **One public constructor per class, current exceptions**: `Asset`'s 6-arg ctor (`state=ACTIVE`), `Device`'s 5-/4-arg ctors (`origin=LIVE`, `state=ACTIVE`), `AssetSpec`'s 4-arg ctor (`existingDeviceIds=[]`), and `DeviceRegistration`'s 3-arg ctor (`origin=LIVE`) are all convenience overloads kept for out-of-scope call sites elsewhere in the tree — the "N-1-arg convenience constructor" idiom is **withdrawn** (`.claude/skills/java-clean-code/SKILL.md` §3, `CLAUDE.md` rule 10); these are pre-existing debt, scheduled for removal, not a pattern to extend. `AssetUsage` has exactly one constructor (its 10-arg canonical one) — no exceptions. `UsageSessionService#open` likewise has exactly one method signature, no `default`-method fallback.

## Gotchas
- **`AssetLiveStatePort#stopStreamsForDevices` is synchronous by design, not an oversight** — warehouse still decides, in the same request, that a device's stream must stop before the device/asset is retired or deleted. A later wave turns this into an event warehouse publishes and perception reacts to; this port is a staging post, not the destination.
- **`Asset.devices` must be non-empty** — an asset with zero devices cannot even be constructed; `AssetSpec`'s own "at least one device" check duplicates this at the command layer, satisfied by `devices` or `existingDeviceIds` (or both).
- **`DefaultDiscoveryService`'s cross-method merge only triggers for a group spanning 2+ *distinct* methods** — two candidates from the *same* method landing in the same network-identity group (e.g. two mDNS services on one host) are intentionally left unmerged.
- **`DiscoveredDevice.method`'s compact ctor only rejects blank/null** — no single-token or lower-case pattern, unlike `StreamDescriptor.protocol`/`CategoryId.slug` — so a merged `"mdns+onvif"` value is valid without any domain change. `DeviceDiscoveryPort.method()`'s "stable lower-case key" contract applies to each *individual* port's key, not to this merged/derived field.
- **`DefaultAssetStatsService#statsFor` is a fetch-then-aggregate workaround, not a real server-side query** — `STATS_FETCH_LIMIT=10,000`, newest-first; an asset with more flights than that under-reports and is biased toward recent history. Would need a real aggregate query on `AssetUsageRepositoryPort` to fix.
- **`AssetImage#data()` clones in and out on every access** — the same defensive-copy discipline `VideoFrame#data()` established for `ByteBuffer`, adapted for `byte[]` (no read-only-view equivalent for arrays).
- **`AssetUsage#streamId` is stamped once, at open time, by perception's `UsageTracker`, and never changed afterward** — it exists so events' `ReplayService` can join a finished usage back to its detections; `null` for a legacy usage or one opened by an asset with no video device.
- **`DefaultAssetService`/`DefaultDeviceService`/`DefaultFleetSummaryService`/`DefaultAssetStatsService` may never import anything from `perception.**`** — that would reopen the module cycle this port inversion closed. Any new "read something live" need adds a method to `AssetLiveStatePort`, not a new direct collaborator.
- **`AssetUsage#phase` is typed `UsagePhase` (this module), not `vision-flight`'s `FlightPhase`, deliberately** — warehouse is the pure leaf and may never depend on flight or any other context, so it owns its own value type. `UsagePhase` mirrors `FlightPhase`'s six values by name only; this module never runs the phase-transition rule and never imports flight — perception's `UsageTracker` (which legally depends on both) is the one place that runs `FlightPhaseRule` and translates its verdict onto `UsagePhase`.
- **`createFromCandidate`'s duplicate check only ever compares `spec.devices()` (new registrations)** — it does not re-check `existingDeviceIds` entries, which already go through `assignDevice`'s own eligibility rule.
- **`AssetUsage` is constructed/persisted exclusively by `DefaultUsageSessionService`** — nothing else in the codebase, including any other service in this module, should call `new AssetUsage(...)` or `assetUsageRepositoryPort.save(...)` directly.
- **`AssetDirectoryService` deliberately wraps `AssetRepositoryPort`/`DeviceRepositoryPort` directly instead of reusing `AssetService`/`DeviceService`** — both of those depend on `AssetLiveStatePort`, whose only implementation (perception's `StreamBackedAssetLiveState`) depends on `UsageTracker`. Routing `UsageTracker`'s asset/device lookups through `AssetService` would wire `UsageTracker -> AssetService -> AssetLiveStatePort -> UsageTracker`, a Spring bean cycle constructor-only injection cannot resolve. Anyone "cleaning up" `AssetDirectoryService` to reuse `AssetService`/`DeviceService` reintroduces that cycle.

## Status
Fully implemented: asset/device/category CRUD, discovery aggregation, fleet summary, per-asset
stats, the usage-session lifecycle (`UsageSessionService`), the fleet-wide replay-library list
(`UsageService`), and the idle-usage-close sweep (`UsageIdleCloseService`). Nothing in this context
is a stub — every port above has at least one real implementation in `storage/persistence` or
`vision-app`'s devsupport.

Session ownership (`AssetUsage` construction/persistence, `AssetDirectoryService`) and the
`UsageOrigin`/`DeviceOrigin` fields are the product of
`docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md` waves R1-R5c; see that plan and
`vision-perception`'s MODULE.md for the runtime (`UsageTracker`) side of the same split. Backing
migrations (`V25__device_origin.sql`, `V26__asset_usage_origin.sql`) live in `storage/persistence`.

`UsageIdleCloseService` (`docs/plans/active/OPERATOR-UX-5-PLAN.md` finding U1, wave W1) fixes a
correctness defect found in `/api/usages`: `vision-perception` never closes a usage on a source or
pipeline failure alone (`DefaultStreamService#stop` is the only path that ever does, see that
module's MODULE.md), so a crashed process or a lost link on an offline rover used to leave a usage
open — and rendered as "Flying now" — indefinitely.
