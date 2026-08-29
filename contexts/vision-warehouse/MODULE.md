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
- `record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices, Map<String,String> attributes, LifecycleState state, Identity identity, Custody custody, InventoryState inventoryState, Instant createdAt, Instant updatedAt)` — canonical 12-arg. `devices` non-null only (empty is legal — see Gotchas, WAREHOUSE-UX-PLAN D4); `inventoryState` must satisfy `InventoryState#storable()` (only `IN_STOCK`/`MAINTENANCE`/`RETIRED` may ever be the *stored* value — `ISSUED`/`IN_FIELD` are derived, see `InventoryStates`); `updatedAt` must not be before `createdAt`. `Asset.register(id, displayName, category, ownership, devices, attributes, identity, custody)` is the "new asset with defaults" factory: stamps `state=ACTIVE`, `inventoryState=IN_STOCK`, `createdAt=updatedAt=now`. `isActive()`, `isDeleted()`, `withDevices(Set)`, `withAttributes(Map)`, `withDetails(String,CategoryId,Map)`, `withState(LifecycleState)` (all four unchanged signatures, bodies now thread the 5 new fields through unmodified), plus `withIdentity(Identity, Instant updatedAt)`, `withInventory(Custody, InventoryState, Instant updatedAt)`, `touch(Instant updatedAt)` (stamps `updatedAt` only, e.g. after `assignDevice`)
- `record Identity(String serialNumber, String make, String model, String registration)` — every field nullable, blank normalizes to `null`; `Identity.NONE` is all-`null`
- `record Custody(UserId custodianId, String location, Instant since)` — `custodianId==null` means in stock (`Custody.NONE`); `location` blank→`null`; `since` required when `custodianId` is set, forced `null` when it is not
- `enum InventoryState { IN_STOCK, ISSUED, IN_FIELD, MAINTENANCE, RETIRED }` — `boolean storable()` (`true` for `IN_STOCK`/`MAINTENANCE`/`RETIRED` only); `ISSUED`/`IN_FIELD` are never a stored `Asset#inventoryState()` value, only ever the return of `InventoryStates.effective`
- `final class InventoryStates` — `static InventoryState effective(Asset, boolean hasOpenUsage)`: `IN_FIELD` if `hasOpenUsage` (wins over custody — a currently-flying aircraft is the more current fact, CLAUDE.md §9), else `ISSUED` if `asset.custody().custodianId() != null`, else `asset.inventoryState()` (the stored value)
- `record MaintenanceId(UUID value)` — `random()`/`of(String)`, mirrors `AssetId`
- `record NoteId(UUID value)` — `random()`/`of(String)`, mirrors `AssetId`
- `enum MaintenanceKind { GROUNDING, INSPECTION_DUE, REPAIR, NOTE }` — `boolean blocksFlight()` (`true` for `GROUNDING`/`INSPECTION_DUE` only)
- `record MaintenanceRecord(MaintenanceId id, AssetId assetId, MaintenanceKind kind, Instant openedAt, Instant closedAt, UserId openedBy, String summary, Long flightSecondsAt)` — `closedAt`/`flightSecondsAt` nullable; `closedAt` must not be before `openedAt` if present; `flightSecondsAt` must not be negative if present; `isOpen()` (`closedAt==null`), `close(Instant)` returns a new closed copy
- `record AssetNote(NoteId id, AssetId assetId, UserId author, Instant at, String text)` — a free-form asset-page note, all fields required
- `record AssetImage(byte[] data, String contentType)` — one photo per asset, no history; `data` non-empty, `contentType` non-blank; `data()` returns a fresh clone on every access (mirrors `VideoFrame#data()`)
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase, UsageOrigin origin)` — **one constructor only** (the 10-component header + compact-ctor validation); `endedAt`/positions/`streamId` nullable, `endedAt>=startedAt` if present, `phase`/`origin` never null. `streamId` is the stream whose start opened this usage (`null` for legacy/streamless), stamped once by perception's `UsageTracker`. `phase` is this module's `UsagePhase`, not flight's `FlightPhase` (see Gotchas). `origin` (`kernel.UsageOrigin{STREAM,OPERATOR}`) is which verb opened the session. `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)`, `withPhase(UsagePhase)`, `withOrigin(UsageOrigin)` each return a copy with one field replaced
- `enum UsagePhase { PREFLIGHT, IN_FLIGHT, LINK_LOST, POSTFLIGHT, ABANDONED, CLOSED }` — `AssetUsage#phase`'s type
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints, boolean connected)` — `parent` nullable (top-level); `connected` (WAREHOUSE-UX-PLAN D4) means an `Asset` in this category must carry ≥1 device — enforced by `DefaultAssetService#create`, not by `Asset`'s own compact ctor (see Gotchas)
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state, DeviceOrigin origin)` — canonical 6-arg; 5-arg convenience ctor defaults `origin=LIVE`; 4-arg convenience ctor defaults `state=ACTIVE` (chains through the 5-arg, so also defaults `origin=LIVE`); `isActive()`, `isDeleted()`, `withDetails(name, capabilities, stream, origin)`, `withState(...)`
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — a suggestion, not a registered `Device`; `suggestedCategory`/`suggestedStream` nullable

### `domain.port` (driven — adapters, or perception for `AssetLiveStatePort`)
- `AssetImageRepositoryPort`: `void save(AssetId, AssetImage)` upsert; `Optional<AssetImage> findByAssetId(AssetId)`; `boolean existsByAssetId(AssetId)`; `void deleteByAssetId(AssetId)` idempotent. No dependency on `AssetRepositoryPort`
- `AssetLiveStatePort`: `Map<DeviceId,StreamId> activeStreamsByDevice()`; `int stopStreamsForDevices(Collection<DeviceId>)` — count actually stopped, **synchronous** by design; `Optional<Telemetry> latestTelemetry(AssetId)`; `Map<AssetId,Integer> openDetectionEventCounts(int scanLimit)`. Implemented by perception's `StreamBackedAssetLiveState`
- `AssetNoteRepositoryPort`: `AssetNote save(AssetNote)`; `List<AssetNote> findByAsset(AssetId)`
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` idempotent
- `AssetUsageRepositoryPort`: `AssetUsage save(AssetUsage)` upsert; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` newest-first; `List<AssetUsage> findRecent(int limit)` fleet-wide; `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset; `Optional<AssetUsage> findByStream(StreamId)` — ≤1 per stream, empty for legacy/streamless
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DeviceDiscoveryPort`: `String method()` stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — blocking, self-time-boxed, empty list ≠ error
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` idempotent
- `FleetLiveUpdatePort`: `void publishFleetChanged()` — no payload, a driving adapter re-derives its own snapshot
- `MaintenanceRepositoryPort`: `MaintenanceRecord save(MaintenanceRecord)` upsert; `Optional<MaintenanceRecord> findById(MaintenanceId)`; `List<MaintenanceRecord> findByAsset(AssetId)` full history; `List<MaintenanceRecord> findOpenByAsset(AssetId)` — an asset may have several open at once

### `application.asset`
- `AssetService` (interface) → `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, AssetLiveStatePort)` — inventory/CRUD only; reaches devices only through `DeviceService`, live runtime state only through `AssetLiveStatePort`. Starting a stream lives in perception's `AssetStreamService`; `stopStream` stays here
  - `Asset create(AssetSpec, Ownership, UserId actor)` — registers `spec.devices()`, assigns `spec.existingDeviceIds()` (same eligibility check as `assignDevice`); throws `IllegalArgumentException` when the category is `connected()` and both device lists are empty (WAREHOUSE-UX-PLAN D4); audits `CREATED`; seeds `identity`/`custody` from the spec via `Asset.register`
  - `Asset createFromCandidate(AssetSpec, Ownership, UserId actor)` — same as `create`, plus a duplicate check on `spec.devices()`'s new registrations by `(protocol, uri, sysid)` against every active device first; throws `IllegalStateException` naming the owner on a match. `existingDeviceIds` entries are exempt (already covered by `assignDevice`'s eligibility rule)
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default
  - `List<AssetSummary> assets(VisibilityScope, boolean)` — filters the unscoped result; `unbounded()` returns it unchanged
  - `AssetDetails details(AssetId)` / `details(VisibilityScope, AssetId)` — scoped form throws `NoSuchElementException` (404, not 403) when out of scope
  - `Asset update(AssetId, AssetEdit, UserId)` — partial, audits `UPDATED` with changed fields only (now including `identity`); `edit.identity()==null` leaves it unchanged; stamps `updatedAt`
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED -> ACTIVE` throws `IllegalStateException`; stamps `updatedAt`
  - `AssetDeletion delete(AssetId, UserId)` — soft: marks asset+devices `DELETED`, keeps usages/telemetry; stamps `updatedAt`
  - `void stopStream(AssetId)` — no-op for unknown asset
  - `Asset assignDevice(AssetId, DeviceId, UserId actor)` / `unassignDevice(...)` — assign requires the device exist, not be deleted, not already assigned (409 naming owner); unassign requires ≥1 device remains; both stamp `updatedAt`
  - `toSummary` computes `AssetSummary#inventoryState` via `InventoryStates.effective(asset, usageRepository.findOpenByAsset(asset.id()).isPresent())`
- Records: `AssetSpec(displayName, category, attributes, devices, existingDeviceIds, identity, custody)` — canonical 7-arg; `devices` non-null only (the "at least one device" invariant moved to `DefaultAssetService#create`, see Gotchas); 4-arg convenience ctor defaults `existingDeviceIds=List.of()`, `identity=Identity.NONE`, `custody=Custody.NONE`. `AssetEdit(displayName, category, attributes, identity)` (`NOTHING`; `changesManagedFields()` true iff `category != null` — the operator/manager authority split; `identity==null` means unchanged). `AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition, inventoryState, identity, custody)` — `inventoryState` is the **effective/derived** value (see `InventoryStates`); `identity`/`custody` are flattened mirrors of the asset's own fields, not derived. `AssetStatus{OFFLINE,STREAMING}`, `AssetDetails(summary, devices, recentUsages)`, `AssetDeletion(id, displayName, devicesDeleted, usagesRetained, streamsStopped)`
- `AssetStatsService` (interface) → `DefaultAssetStatsService` — one asset's flight-utilization stats behind `GET /api/assets/{id}/stats`
  - `DefaultAssetStatsService(AssetUsageRepositoryPort, AssetLiveStatePort)`; overloads add an explicit `statsFetchLimit` and/or a package-private `Supplier<Instant> clock` test seam
  - `AssetStats statsFor(AssetId)` — fetch-then-aggregate over `findRecentByAsset(assetId, STATS_FETCH_LIMIT=10_000)`; duration floored at zero; `avgFlightSeconds` averages **closed** usages only; `lastKnownBatteryPercent` from `assetLiveStatePort.latestTelemetry`, rounded to nearest percent. Never throws, never checks asset existence
- `AssetStats(totalFlightSeconds, flightCount, firstFlownAt, lastFlownAt, avgFlightSeconds, lastKnownBatteryPercent, flightInProgress)` — optionals `null` (never fabricated `0`) when nothing to report
- `AssetAttention(assetId, displayName, categoryId, categoryName, lifecycle, streaming, streamId, batteryPercent, telemetryAgeMs, openEventCount, flightMode, armed, failsafe)` — one `FleetSummary#assets()` row; `streamId`/`batteryPercent`/`telemetryAgeMs`/`flightMode`/`armed`/`failsafe` nullable under a no-telemetry (or no flight-state) condition

### `application.category`
- `CategoryService` (interface) → `DefaultCategoryService(CategoryRepositoryPort)` — `List<DeviceCategory> categories()`, sorted by `CategoryId.slug()`; `DeviceCategory create(CategorySpec)` (WAREHOUSE-UX-PLAN §3.3, W3 — `vision-api`'s `POST /api/categories`) throws `IllegalStateException` (409) if the id already exists; `DeviceCategory update(CategoryId, CategoryEdit)` (`PUT /api/categories/{id}`) throws `NoSuchElementException` (404) for an unknown id — both are unauthorised at this layer, same as every other `CategoryService` method; `vision-api`'s `CategoryController` gates both behind `scope.canManageOrg()` itself (categories have no per-instance `Ownership` for a service-level `canManage` check to authorise against)
- `CategorySpec(id, name, parent, connected, attributeHints)` — the create command; compact ctor validates non-null `id`, non-blank `name`, non-null `attributeHints` (defensively copied). `CategoryEdit(name, parent, connected, attributeHints)` — a **whole-record replace**, not a partial patch like `AssetEdit`: `parent` can legitimately be `null` (top-level), so a per-field "null=unchanged" convention would be ambiguous with "null=clear to top-level" (see the record's own javadoc)
- `CategoryCounts(categoryId, categoryName, total, active, deactivated, deleted, streaming, inStock, issued, inField, maintenance, retired)` — one `FleetSummary#categories()` row; the 5 inventory-state counts are the category's subset in each effective `InventoryState`, filled by `DefaultFleetSummaryService`'s accumulator from each `AssetSummary#inventoryState()`

### `application.custody`
- `AssetCustodyService` (interface) → `DefaultAssetCustodyService(AssetRepositoryPort, MaintenanceRepositoryPort, AuditTrailPort)` — moves an asset through `IN_STOCK -> ISSUED -> IN_STOCK -> MAINTENANCE -> IN_STOCK -> RETIRED` (WAREHOUSE-UX-PLAN §3.4); `IN_FIELD` is never a verb here — it is derived the moment a usage opens. Every verb authorises via `VisibilityScope#canManage(ownership)` (mirrors vision-flight's `DefaultVehicleProfileService`, not `AssetService#setState`, which does not itself authorise — see Gotchas), audits a denial before throwing `AccessDeniedException`, and on success writes an `AuditEntry` (`AuditAction.UPDATED`/`AuditTargetType.ASSET` — no new enum values added) and stamps `updatedAt`
  - `Asset issue(AssetId, UserId custodianId, String location, UserId actor, VisibilityScope)` — requires stored `IN_STOCK` **and** no custodian already set (an issued asset is *also* stored `IN_STOCK`, so re-issuing must go through `returnToStock` first); sets `Custody(custodianId, location, now)`
  - `Asset returnToStock(AssetId, UserId actor, VisibilityScope)` — requires stored `IN_STOCK` with a custodian set; clears to `Custody.NONE`
  - `Asset ground(AssetId, MaintenanceKind, String summary, UserId actor, VisibilityScope)` — refuses only `RETIRED`; opens a `MaintenanceRecord`, sets `MAINTENANCE`, **keeps** existing custody
  - `Asset release(AssetId, UserId actor, VisibilityScope)` — requires stored `MAINTENANCE`; closes every open record whose `kind().blocksFlight()` (a `REPAIR`/`NOTE` stays open), sets `IN_STOCK`, **clears** custody (a repaired asset always returns through the stockroom)
  - `Asset retire(AssetId, UserId actor, VisibilityScope)` — idempotent no-op if already `RETIRED`; refuses if a custodian is set; sets `RETIRED`, never deletes

### `application.maintenance`
- `MaintenanceService` (interface) → `DefaultMaintenanceService(MaintenanceRepositoryPort, AssetRepositoryPort, AuditTrailPort)` (also `implements MaintenanceQuery`) — `open`/`close` authorise on `canManage` like `AssetCustodyService`; `listForAsset` authorises on `scope.includes` instead (visibility, not authority — mirrors `AssetService#details`) and throws `NoSuchElementException` (404) when out of scope
  - `MaintenanceRecord open(AssetId, MaintenanceKind, String summary, UserId actor, VisibilityScope)` — opens a record without touching inventory state (use `AssetCustodyService#ground` when the record should also ground the asset)
  - `MaintenanceRecord close(MaintenanceId, UserId actor, VisibilityScope)`
  - `List<MaintenanceRecord> listForAsset(AssetId, VisibilityScope)` — full history, open and closed
- `MaintenanceQuery` (interface, filed in `application.maintenance`, **not** `domain.port` — see Gotchas) — `List<MaintenanceRecord> openBlockers(AssetId)`: the currently-open, flight-blocking records only (`blocksFlight()==true`); deliberately unscoped — an internal service-to-service read, the caller has already scope-checked the asset (mirrors `UsageSessionService#usageBelongsToAsset`). `DefaultMaintenanceService` is the one implementation; vision-flight's readiness evaluation reads this cross-context (WAREHOUSE-UX-CONTEXT.md OQ1: an open blocker is a NO-GO)

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
- **One public constructor per class, current exceptions**: `Device`'s 5-/4-arg ctors (`origin=LIVE`, `state=ACTIVE`), `AssetSpec`'s 4-arg ctor (`existingDeviceIds=[]`, `identity=Identity.NONE`, `custody=Custody.NONE`), and `DeviceRegistration`'s 3-arg ctor (`origin=LIVE`) are convenience overloads kept for out-of-scope call sites elsewhere in the tree — the "N-1-arg convenience constructor" idiom is **withdrawn** (`.claude/skills/java-clean-code/SKILL.md` §3, `CLAUDE.md` rule 10); these are pre-existing debt, scheduled for removal, not a pattern to extend. Each gets **at most one** such overload, never a second layered on top (W2 extended `AssetSpec`'s existing 4-arg ctor rather than adding a 5th constructor). `Asset` dropped its old 6-arg convenience ctor entirely in favor of the `Asset.register(...)` static factory (a named "new asset with defaults" factory, not a constructor overload) — every in-module call site was updated rather than kept compiling via another overload. `AssetUsage` has exactly one constructor (its 10-arg canonical one) — no exceptions. `UsageSessionService#open` likewise has exactly one method signature, no `default`-method fallback.
- **Derived vs. stored inventory state**: `Asset#inventoryState()` only ever stores `IN_STOCK`/`MAINTENANCE`/`RETIRED` (enforced by `InventoryState#storable()` in `Asset`'s compact ctor); `ISSUED`/`IN_FIELD` are computed on read by `InventoryStates.effective(Asset, boolean hasOpenUsage)` and must never be persisted or passed to a `with*`/`register` call.

## Gotchas
- **`AssetLiveStatePort#stopStreamsForDevices` is synchronous by design, not an oversight** — warehouse still decides, in the same request, that a device's stream must stop before the device/asset is retired or deleted. A later wave turns this into an event warehouse publishes and perception reacts to; this port is a staging post, not the destination.
- **`Asset.devices` may now be empty — a category-connectedness rule, not a record invariant** (WAREHOUSE-UX-PLAN D4). Before W2, `Asset`'s own compact ctor rejected zero devices unconditionally; that invariant was too strong for a category like "battery" or "controller" that never has a video/telemetry device. The record now only requires `devices` to be non-null; `DefaultAssetService#create` looks up `DeviceCategory#connected()` and throws `IllegalArgumentException` iff the category is connected *and* both `spec.devices()` and `spec.existingDeviceIds()` are empty. Any other caller that constructs `Asset`/`AssetSpec` directly (tests, adapters) must supply a `connected` category consciously or accept zero devices.
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
- **`AssetService#setState` does not itself call `canManage`** — despite the name suggesting a canonical in-module authorization example, that check actually lives one layer up in `vision-api`'s `AssetController`. `AssetCustodyService`/`MaintenanceService`'s `canManage(ownership)`-then-audit-then-`AccessDeniedException` pattern (taking `VisibilityScope` as a method parameter) was instead sourced from vision-flight's `DefaultVehicleProfileService#probe`, the actual in-application-layer precedent for authorizing directly inside a context module's own service.
- **`MaintenanceQuery` lives in `application.maintenance`, not `domain.port`** — this module's `domain.port` interfaces are driven ports *this module* calls outward into adapters; `MaintenanceQuery` is the opposite direction, a cross-context read contract *this module implements* for a caller in another context (vision-flight). It is filed beside its implementing service instead, exactly mirroring `UsageSessionService`/`DefaultUsageSessionService`'s own precedent, so a caller in vision-flight never has to import `domain.port` to reach it.
- **`AssetCustodyService#issue`'s state guard checks both `inventoryState()` and `custody().custodianId()`, not just the stored state** — an issued asset's *stored* `inventoryState()` is still `IN_STOCK` (only `custody` carries the "who has it" fact), so a guard that checked stored state alone would silently let a second `issue()` call overwrite an existing custodian without an explicit `returnToStock` first. A unit test caught this during W2; treat any future custody-guard change with the same suspicion.

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

**WAREHOUSE-UX wave W2** (`docs/plans/active/WAREHOUSE-UX-CONTEXT.md`) added the inventory layer:
`Identity`/`Custody`/`InventoryState`/`InventoryStates`, `MaintenanceId`/`MaintenanceKind`/
`MaintenanceRecord`, `NoteId`/`AssetNote`, `MaintenanceRepositoryPort`/`AssetNoteRepositoryPort`,
`AssetCustodyService`/`DefaultAssetCustodyService`, `MaintenanceService`/`MaintenanceQuery`/
`DefaultMaintenanceService`, `DeviceCategory#connected`, and the corresponding widening of `Asset`,
`AssetSpec`, `AssetEdit`, `AssetSummary`, `CategoryCounts`. Domain + application are fully
implemented and unit-tested in this module; **no outer-layer implementation exists yet** —
`storage/persistence` has no `maintenance_records`/`asset_notes` tables or `assets` columns for the
new fields, and every out-of-module caller of `Asset`/`AssetSpec`/`AssetEdit`/`DeviceCategory`'s
constructors (station/vision-api, storage/persistence, vision-app, vision-flight, vision-identity,
vision-learning, vision-perception, vision-simulation — 41 files) will not compile until a later
wave (W3) updates them. See WAREHOUSE-UX-CONTEXT.md's "W2 → W3 handoff" section for the full file
list and the exact column set W3 must persist.
