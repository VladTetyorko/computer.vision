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

**Depends on:** `vision-kernel` · `vision-platform` (`AuditTrailPort`, `VisibilityScope`, `Authority`) ·
nothing else.
**Used by:** every other context, every adapter, `vision-app`, `vision-api`.
**Build/test:** `./mvnw -B -pl contexts/vision-warehouse test` (391 tests)

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
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase, UsageOrigin origin, UserId pilotId)` — **one constructor only** (the 11-component header + compact-ctor validation); `endedAt`/positions/`streamId`/`pilotId` nullable, `endedAt>=startedAt` if present, `phase`/`origin` never null. `streamId` is the stream whose start opened this usage (`null` for legacy/streamless), stamped once by perception's `UsageTracker`. `phase` is this module's `UsagePhase`, not flight's `FlightPhase` (see Gotchas). `origin` (`kernel.UsageOrigin{STREAM,OPERATOR}`) is which verb opened the session. `pilotId` (ASSET-FLOWS-PLAN §2 D1p) is the acting user attributed to this flight, `null` when genuinely unknown (a device-pushed stream opening with no acting-user context) — same "recorded once, honest-null-until-known" idiom `streamId` established; `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)`, `withPhase(UsagePhase)`, `withOrigin(UsageOrigin)`, `withPilot(UserId)` each return a copy with one field replaced
- `enum UsagePhase { PREFLIGHT, IN_FLIGHT, LINK_LOST, POSTFLIGHT, ABANDONED, CLOSED }` — `AssetUsage#phase`'s type
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints, boolean connected)` — `parent` nullable (top-level); `connected` (WAREHOUSE-UX-PLAN D4) means an `Asset` in this category must carry ≥1 device — enforced by `DefaultAssetService#create`, not by `Asset`'s own compact ctor (see Gotchas)
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state, DeviceOrigin origin)` — canonical 6-arg; 5-arg convenience ctor defaults `origin=LIVE`; 4-arg convenience ctor defaults `state=ACTIVE` (chains through the 5-arg, so also defaults `origin=LIVE`); `isActive()`, `isDeleted()`, `withDetails(name, capabilities, stream, origin)`, `withState(LifecycleState)` — **preserves `origin`** (SOURCE-ONBOARDING-2-PLAN B1/U2: used to silently route through the 5-arg ctor and reset a simulated device's `origin` to `LIVE` on every state change; fixed, regression-tested in `DeviceTest#withStatePreservesOrigin`)
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — a suggestion, not a registered `Device`; `suggestedCategory`/`suggestedStream` nullable
- `enum SourceStatus { OK, UNREACHABLE, NEVER_SCANNED }` (ASSET-FLOWS-PLAN §2, A3; `NEVER_SCANNED` added SOURCE-ONBOARDING-2-PLAN B1/U8) — reachability of one `DeviceDiscoveryPort`, as distinct from that port's `scan` returning zero candidates; `NEVER_SCANNED` means this station has not yet asked — see `DeviceDiscoveryPort#lastStatus()` and `application.discovery.SourceHealth`/`DefaultDiscoveryService#health()` below
- `record DiscoveryCandidateId(UUID value)` — `random()`/`of(String)`, mirrors `AssetId`
- `enum CandidateStatus { NEW, DISMISSED, REGISTERED }` — deliberately **no `EXPIRED` value**: staleness is derived by a reader from `lastSeen`, never stored (ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 honesty convention — a candidate the sweep hasn't seen in a while is still whatever it last was, not silently reclassified)
- `record DiscoveryCandidate(DiscoveryCandidateId id, String identityKey, DiscoveredDevice discovered, Instant firstSeen, Instant lastSeen, CandidateStatus status, AssetId registeredAsset)` — the discovery-inbox row behind Z2a; `identityKey` non-blank, `lastSeen>=firstSeen`, `registeredAsset` must be `null` unless `status==REGISTERED` (may still be `null` when `REGISTERED` — the matching device can be unowned). `static String identityKeyFor(DiscoveredDevice)` — `method + "|" + address` + optional `"|sysid=" + n`, sysid read leniently from `suggestedStream.options().get("sysid")` first, `details.get("sysid")` as fallback (mirrors how `DefaultAssetService#matchDevice`/adapter-mavlink read `options["sysid"]`). `static newlyReported(id, discovered, now)` stamps `firstSeen=lastSeen=now`, `status=NEW`. `reSeen(discovered, now)` refreshes `discovered`+`lastSeen`, preserves `firstSeen`/`status`/`registeredAsset` (a `DISMISSED` or `REGISTERED` candidate stays that way on re-report unless the caller explicitly calls `registeredTo`). `dismiss()` → `DISMISSED`, clears `registeredAsset`. `registeredTo(AssetId ownerOrNull)` → `REGISTERED` with the given (possibly `null`) owner. `restore()` (SOURCE-ONBOARDING-2-PLAN B1/C5) → `NEW`, clears `registeredAsset` — shared by `DiscoveryInboxService#restore` (operator-initiated) and `#report`'s auto-reopen rule (system-initiated)

### `domain.port` (driven — adapters, or perception for `AssetLiveStatePort`)
- `AssetImageRepositoryPort`: `void save(AssetId, AssetImage)` upsert; `Optional<AssetImage> findByAssetId(AssetId)`; `boolean existsByAssetId(AssetId)`; `void deleteByAssetId(AssetId)` idempotent. No dependency on `AssetRepositoryPort`
- `AssetLiveStatePort`: `Map<DeviceId,StreamId> activeStreamsByDevice()`; `int stopStreamsForDevices(Collection<DeviceId>)` — count actually stopped, **synchronous** by design; `Optional<Telemetry> latestTelemetry(AssetId)`; `Map<AssetId,Integer> openDetectionEventCounts(int scanLimit)`. Implemented by perception's `StreamBackedAssetLiveState`
- `AssetNoteRepositoryPort`: `AssetNote save(AssetNote)`; `List<AssetNote> findByAsset(AssetId)`
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` idempotent
- `AssetUsageRepositoryPort`: `AssetUsage save(AssetUsage)` upsert; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` newest-first; `List<AssetUsage> findRecent(int limit)` fleet-wide; `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset; `Optional<AssetUsage> findByStream(StreamId)` — ≤1 per stream, empty for legacy/streamless; `default Map<AssetId,Long> totalFlightSecondsByAsset()` (WAREHOUSE-UX W8) — one aggregate over every usage grouped by asset, an open usage counting up to now; default `Map.of()` so `vision-learning`'s hand-rolled test fake need not implement it (see Gotchas); a missing key means "no usages", never "unknown" — callers apply their own zero default
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DeviceDiscoveryPort`: `String method()` stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — blocking, self-time-boxed, empty list ≠ error; `default SourceStatus lastStatus()` (ASSET-FLOWS-PLAN §2, A3; default flipped to `NEVER_SCANNED` by SOURCE-ONBOARDING-2-PLAN B1/U8) — reports the adapter's own last-known reachability, since mDNS/ONVIF/V4L2 throw on genuine failure rather than swallowing it into an empty scan result (`DefaultDiscoveryService` isolates that throw into `DiscoveryScanResult#failedMethods`, not this method); only a scanner that itself collapses "unreachable" into an empty `scan()` result — today just `device-discovery/onvif-mdns-v4l2`'s `MediamtxPathScanner` — overrides it to report `UNREACHABLE`. Added as a `default` method (not a `scan` signature change) specifically so `drone-link/mavlink`'s `MavlinkHeartbeatScanner` needs no change; it now inherits `NEVER_SCANNED`, not `OK` — reporting `OK` before ever being asked was a fabricated fact (U8), and `DefaultDiscoveryService#health()` is the actual source of truth for "has this been asked at all" (see below), this default is only the backstop for a caller that reads a port's `lastStatus()` directly
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` idempotent
- `DiscoveryCandidateRepositoryPort`: `DiscoveryCandidate save(DiscoveryCandidate)` upsert; `Optional<DiscoveryCandidate> findById(DiscoveryCandidateId)`; `Optional<DiscoveryCandidate> findByIdentityKey(String)`; `List<DiscoveryCandidate> findAll()`. Implementations need **not** guarantee atomic upsert — `DefaultDiscoveryInboxService` serializes its own mutating calls with a coarse `synchronized`, so this port stays a plain read/write contract (see that service's class javadoc and Gotchas below)
- `FleetLiveUpdatePort`: `void publishFleetChanged()` — no payload, a driving adapter re-derives its own snapshot
- `MaintenanceRepositoryPort`: `MaintenanceRecord save(MaintenanceRecord)` upsert; `Optional<MaintenanceRecord> findById(MaintenanceId)`; `List<MaintenanceRecord> findByAsset(AssetId)` full history; `List<MaintenanceRecord> findOpenByAsset(AssetId)` — an asset may have several open at once; `List<MaintenanceRecord> findOpen()` — every open record fleet-wide, newest-opened-first, unbounded (WAREHOUSE-UX W8); `List<MaintenanceRecord> findRecentlyClosed(int limit)` — fleet-wide, newest-closed-first, bounded

### `application.asset`
- `AssetService` (interface) → `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, AssetLiveStatePort)` — inventory/CRUD only; reaches devices only through `DeviceService`, live runtime state only through `AssetLiveStatePort`. Starting a stream lives in perception's `AssetStreamService`; `stopStream` stays here
  - `Asset create(AssetSpec, Ownership, UserId actor)` — registers `spec.devices()`, assigns `spec.existingDeviceIds()` (same eligibility check as `assignDevice`); throws `IllegalArgumentException` when the category is `connected()` and both device lists are empty (WAREHOUSE-UX-PLAN D4); audits `CREATED`; seeds `identity`/`custody` from the spec via `Asset.register`
  - `Asset createFromCandidate(AssetSpec, Ownership, UserId actor)` — same as `create`, plus a duplicate check on `spec.devices()`'s new registrations by `(protocol, uri, sysid)` against every active device first; throws `IllegalStateException` naming the owner on a match. `existingDeviceIds` entries are exempt (already covered by `assignDevice`'s eligibility rule). **First production caller**: `application.discovery.DefaultDiscoveryInboxService#register` (Z2a, ZERO-CONFIG-ONBOARDING-CONTEXT.md §11) — before that it only had test callers
  - `Optional<DuplicateDeviceMatch> findDuplicateDevice(StreamDescriptor candidate)` (Z2a) — a non-throwing query form of the same `(protocol, uri, sysid)` match `createFromCandidate` enforces, for a caller that needs to know *whether* a stream is already registered without wanting the `IllegalStateException`; both share one private `matchDevice(StreamDescriptor)` helper in `DefaultAssetService` so the match rule can never drift between the two. Returns empty when no active device matches; when one does, `DuplicateDeviceMatch#owningAsset` is `null` if the matching device isn't yet assigned to any asset
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default
  - `List<AssetSummary> assets(VisibilityScope, boolean)` — filters the unscoped result; `unbounded()` returns it unchanged
  - `AssetDetails details(AssetId)` / `details(VisibilityScope, AssetId)` — scoped form throws `NoSuchElementException` (404, not 403) when out of scope
  - `Asset update(AssetId, AssetEdit, UserId)` — partial, audits `UPDATED` with changed fields only (now including `identity`); `edit.identity()==null` leaves it unchanged; stamps `updatedAt`
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED -> ACTIVE` throws `IllegalStateException`; stamps `updatedAt`
  - `AssetDeletion delete(AssetId, UserId)` — soft: marks asset+devices `DELETED`, keeps usages/telemetry; stamps `updatedAt`
  - `void stopStream(AssetId)` — no-op for unknown asset
  - `Asset assignDevice(AssetId, DeviceId, UserId actor)` / `unassignDevice(...)` — assign requires the device exist, not be deleted, not already assigned (409 naming owner); unassign requires ≥1 device remains; both stamp `updatedAt`
  - `toSummary` computes `AssetSummary#inventoryState` via `InventoryStates.effective(asset, usageRepository.findOpenByAsset(asset.id()).isPresent())`
- `DuplicateDeviceMatch(DeviceId deviceId, AssetId owningAsset)` (Z2a) — `findDuplicateDevice`'s result row; `deviceId` required, `owningAsset` nullable (unassigned device)
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
- `AssetCustodyService` (interface) → `DefaultAssetCustodyService(AssetRepositoryPort, MaintenanceRepositoryPort, AuditTrailPort)` — moves an asset through `IN_STOCK -> ISSUED -> IN_STOCK -> MAINTENANCE -> IN_STOCK -> RETIRED` (WAREHOUSE-UX-PLAN §3.4); `IN_FIELD` is never a verb here — it is derived the moment a usage opens. Every verb authorises via `Authority#mayManageFleet(ownership)` (AUTH-ROLES-PLAN wave B6, superseding the old `VisibilityScope#canManage(ownership)`; mirrors vision-flight's `DefaultVehicleProfileService`, not `AssetService#setState`, which does not itself authorise — see Gotchas), audits a denial before throwing `AccessDeniedException`, and on success writes an `AuditEntry` (`AuditAction.UPDATED`/`AuditTargetType.ASSET` — no new enum values added) and stamps `updatedAt`
  - `Asset issue(AssetId, UserId custodianId, String location, UserId actor, Authority)` — requires stored `IN_STOCK` **and** no custodian already set (an issued asset is *also* stored `IN_STOCK`, so re-issuing must go through `returnToStock` first); sets `Custody(custodianId, location, now)`
  - `Asset returnToStock(AssetId, UserId actor, Authority)` — requires stored `IN_STOCK` with a custodian set; clears to `Custody.NONE`
  - `Asset ground(AssetId, MaintenanceKind, String summary, UserId actor, Authority)` — refuses only `RETIRED`; opens a `MaintenanceRecord`, sets `MAINTENANCE`, **keeps** existing custody
  - `Asset release(AssetId, UserId actor, Authority)` — requires stored `MAINTENANCE`; closes every open record whose `kind().blocksFlight()` (a `REPAIR`/`NOTE` stays open), sets `IN_STOCK`, **clears** custody (a repaired asset always returns through the stockroom)
  - `Asset retire(AssetId, UserId actor, Authority)` — idempotent no-op if already `RETIRED`; refuses if a custodian is set; sets `RETIRED`, never deletes

### `application.maintenance`
- `MaintenanceService` (interface) → `DefaultMaintenanceService(MaintenanceRepositoryPort, AssetRepositoryPort, AuditTrailPort)` (also `implements MaintenanceQuery`) — `open`/`close` authorise on `Authority#mayManageFleet` like `AssetCustodyService` (AUTH-ROLES-PLAN wave B6); `listForAsset`/`fleetWide` stay `VisibilityScope`-typed and authorise on `scope.includes` instead (visibility, not authority — mirrors `AssetService#details`) — the private `requireManageable(AssetId, Authority)` helper backs only `open`/`close`
  - `MaintenanceRecord open(AssetId, MaintenanceKind, String summary, UserId actor, Authority)` — opens a record without touching inventory state (use `AssetCustodyService#ground` when the record should also ground the asset)
  - `MaintenanceRecord close(MaintenanceId, UserId actor, Authority)`
  - `List<MaintenanceRecord> listForAsset(AssetId, VisibilityScope)` — full history, open and closed
  - `List<MaintenanceRecordSummary> fleetWide(MaintenanceListState state, int limit, VisibilityScope)` (WAREHOUSE-UX W8, `GET /api/maintenance`'s backing method) — `OPEN`/`CLOSED`/`ALL` selects `maintenanceRepository.findOpen()`/`findRecentlyClosed(limit)`/both concatenated; `limit` must be positive (`IllegalArgumentException` otherwise); each surviving record is joined **in-context** to its asset's `displayName`/`category` via the already-injected `AssetRepositoryPort` (not a cross-context join — both `MaintenanceRecord` and `Asset` live in this module); silently drops (never throws) a record whose asset is soft-deleted or `!scope.includes(asset.id(), asset.ownership())`, the same "filter, don't 403" convention `DefaultFleetSummaryService` uses
- `MaintenanceListState` (enum, `application.maintenance`) — `OPEN`/`CLOSED`/`ALL`, `fleetWide`'s state selector
- `MaintenanceRecordSummary(MaintenanceRecord record, String assetName, CategoryId categoryId)` — one `fleetWide` row; compact ctor rejects a null `record`/`categoryId` or a blank `assetName`
- `MaintenanceQuery` (interface, filed in `application.maintenance`, **not** `domain.port` — see Gotchas) — `List<MaintenanceRecord> openBlockers(AssetId)`: the currently-open, flight-blocking records only (`blocksFlight()==true`); deliberately unscoped — an internal service-to-service read, the caller has already scope-checked the asset (mirrors `UsageSessionService#usageBelongsToAsset`). `DefaultMaintenanceService` is the one implementation; vision-flight's readiness evaluation reads this cross-context (WAREHOUSE-UX-CONTEXT.md OQ1: an open blocker is a NO-GO)

### `application.directory`
- `AssetDirectoryService` (interface) → `DefaultAssetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` — narrow read-only seam for a caller that must not depend on `AssetService`/`DeviceService` (see Gotchas)
  - `Optional<Asset> findByDevice(DeviceId)`, `Optional<Device> findDevice(DeviceId)`, `Optional<Asset> find(AssetId)`

### `application.device`
- `DeviceService` (interface) → `DefaultDeviceService(DeviceRepositoryPort, AssetLiveStatePort, AuditTrailPort, EventPublisherPort)` — stops a device's stream via `assetLiveStatePort.stopStreamsForDevices(Set.of(id))`, never by touching runtime directly
  - `Device register(DeviceRegistration, UserId)` — publishes `DEVICE_ONLINE`, audits `CREATED`; `List<Device> devices()` / `devices(includeDeleted)`; `Optional<Device> find(DeviceId)`; `Device update(DeviceId, DeviceEdit, UserId)` partial; `Device setState(...)` idempotent, stops the stream leaving service; `Device delete(...)` soft, stays a member of its asset
- `DeviceRegistration(name, capabilities, stream, origin)` — 3-arg convenience ctor defaults `origin=LIVE`; `DeviceEdit(name, capabilities, stream, origin)` (all-nullable partial, `NOTHING`)

### `application.discovery`
- `DiscoveryService` (interface) → `DefaultDiscoveryService(List<DeviceDiscoveryPort>)` (+ overload with explicit `gracePeriod`; + package-private test-seam ctor also taking `Supplier<Instant> clock`, added SOURCE-ONBOARDING-2-PLAN B1) — indexed by `port.method()`
  - `DiscoveryScanResult scan(DiscoveryScanSpec)` — one virtual thread per requested port; deadline = `timeout + GRACE_PERIOD(200ms)`; per-adapter failure isolated into `failedMethods`; `IllegalArgumentException` only for an unknown requested method; **stamps `lastScanAtByMethod.put(method, clock.get())` for every requested method it resolves — success, timeout, *or* thrown exception alike** (a `finally` block, so a failed attempt still counts as "asked")
  - Dedup is two-pass: exact `(method,address)` within one scan, then a cross-method merge keyed by network identity (`URI.getHost()`, case-insensitive, or exact address) — only merges a group spanning 2+ distinct methods (see Gotchas)
  - `List<SourceHealth> health()` (ASSET-FLOWS-PLAN §2, A3; `NEVER_SCANNED`/`lastScanAt` added SOURCE-ONBOARDING-2-PLAN B1/U8) — one `SourceHealth` per registered port; a method this service instance has scanned at least once reports `port.lastStatus()` + the tracked `lastScanAt`, a method it has never scanned (e.g. right after a station restart, or excluded from every `DiscoveryScanSpec#methods()` requested so far) reports `SourceHealth(id)` — `NEVER_SCANNED`, `lastScanAt=null` — **regardless of what `port.lastStatus()` itself would answer**; this service's own scan history, not the adapter's internal state, is authoritative for "has this been asked". `health()` itself never triggers a scan; order not guaranteed (`Map.copyOf`-backed). The found-devices inbox's honesty fix: lets a caller (`vision-api`'s `DiscoveryInboxController`) tell "mediamtx unreachable" from "mediamtx reachable, nothing plugged in" from "never even scanned" — three states `scan` alone cannot distinguish
- `DiscoveryScanSpec(timeout, methods)` + `defaults()`; `DiscoveryScanResult(devices, failedMethods)`; `record SourceHealth(String id, SourceStatus status, Instant lastScanAt)` — canonical 3-arg (SOURCE-ONBOARDING-2-PLAN B1 widened from 2-arg); `id` non-blank, `status` non-null, `lastScanAt` must be `null` when `status==NEVER_SCANNED` (compact-ctor enforced); 1-arg convenience ctor `SourceHealth(String id)` → `NEVER_SCANNED`/`null`
- `DiscoveryInboxService` (interface) → `DefaultDiscoveryInboxService(DiscoveryCandidateRepositoryPort, AssetService, DeviceService)` (+ package-private test-seam ctor also taking `Supplier<Instant> clock`, widened from 2-arg by SOURCE-ONBOARDING-2-PLAN B1 to add `DeviceService` for `attach`) — Z2a, the persisted discovery inbox behind `docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` §11. Sits above `DiscoveryService`/`DeviceDiscoveryPort`: a sweep runner (outside this module, `vision-app`'s wiring) calls `scan` then `report`s each result here so operators see a durable, dismissible, register-able candidate list rather than a point-in-time scan result
  - `ReportOutcome report(DiscoveredDevice)` (return type widened from bare `DiscoveryCandidate` by SOURCE-ONBOARDING-2-PLAN B1/C4) — upsert by `DiscoveryCandidate.identityKeyFor`; a first sighting is `NEW`; a re-report refreshes `discovered`/`lastSeen` and otherwise preserves status (so `DISMISSED` stays dismissed on re-report); independent of that, whenever `discovered.suggestedStream()` matches an already-registered device by `assetService.findDuplicateDevice`, the saved candidate is stamped `REGISTERED` with that device's owning asset (or `null` if unowned) — this overrides even a `DISMISSED` candidate, since "already registered" is a stronger fact than a prior dismissal. **Auto-reopen (C5):** when no duplicate match exists *and* the pre-report candidate was `REGISTERED`, it is reopened via `DiscoveryCandidate#restore()` instead of left unchanged — symmetric with the already-registered rule, closes the "asset/device was deleted but the candidate still reads REGISTERED" dead end (P4). `synchronized` — see class javadoc and Gotchas
  - `List<DiscoveryCandidate> candidates()` — unscoped, every candidate regardless of status (mirrors `CategoryService#categories`); the caller (vision-api) filters/sorts for display
  - `DiscoveryCandidate dismiss(DiscoveryCandidateId, UserId actor)` — `NoSuchElementException` for an unknown id; unauthorized dismissal is not currently gated by `VisibilityScope` (candidates carry no `Ownership` to check against, same rationale as `CategoryService`)
  - `Asset register(DiscoveryCandidateId, RegisterFromCandidateCommand, Authority, UserId actor)` (AUTH-ROLES-PLAN wave B6, widened from `VisibilityScope`) — authorizes like `DefaultGroupService#create` (`scope.mayManageOrg()` then `scope.scope().includesGroup(command.ownership().groupId())`, `AccessDeniedException` on either failure), builds an `AssetSpec` from the candidate's `DiscoveredDevice` + the command's operator overrides, and calls `AssetService#createFromCandidate` — this service's **only** caller of that method to date. On success stamps the candidate `REGISTERED` with the new asset's id; on `createFromCandidate`'s `IllegalStateException` (duplicate), the candidate is left untouched (no partial stamp)
  - `Asset register(DiscoveryCandidateId, RegisterFromCandidateCommand, VisibilityScope, UserId actor)` — authorizes like `DefaultGroupService#create` (`scope.canManageOrg()` then `scope.includesGroup(command.ownership().groupId())`, `AccessDeniedException` on either failure), builds an `AssetSpec` from the candidate's `DiscoveredDevice` + the command's operator overrides, and calls `AssetService#createFromCandidate` — this service's **only** caller of that method to date. On success stamps the candidate `REGISTERED` with the new asset's id; on `createFromCandidate`'s `IllegalStateException` (duplicate), the candidate is left untouched (no partial stamp)
  - `DiscoveryCandidate attach(DiscoveryCandidateId, AssetId, VisibilityScope, UserId actor)` (new, SOURCE-ONBOARDING-2-PLAN B1/C1) — the atomic server-side twin of `register` for attaching onto an *existing* asset instead of creating a new one; registers a device from `candidate.discovered().suggestedStream()` via `DeviceService#register`, assigns it via the existing `AssetService#assignDevice`, then stamps the candidate `REGISTERED` with `assetId` — all validated (in order: `canManageOrg`, candidate exists, not already `REGISTERED` elsewhere, has a `suggestedStream`, target asset visible to `scope`, stream not a duplicate) **before** the device is registered, so a failure never leaves an orphan device. `scope.canManageOrg()` failure → `AccessDeniedException`; unknown candidate, or target asset unknown/out of `scope`, → `NoSuchElementException` (404, **never** `AccessDeniedException` for the asset check — deliberate, so this one check never reveals an out-of-scope asset's existence); already `REGISTERED` to a *different* asset → `DiscoveryCandidateAlreadyRegisteredException`; no `suggestedStream`, or the stream duplicates an already-registered device (message names the owner, mirroring `createFromCandidate`'s throw), → `IllegalStateException`
  - `DiscoveryCandidate restore(DiscoveryCandidateId, UserId actor)` (new, SOURCE-ONBOARDING-2-PLAN B1/C5) — reopens to `NEW` via `DiscoveryCandidate#restore()`; `NoSuchElementException` for an unknown id; no `VisibilityScope` parameter, same rationale as `dismiss`
- `RegisterFromCandidateCommand(String displayName, CategoryId category, Map<String,String> attributes, Identity identity, Ownership ownership)` — `register`'s command; `displayName` non-blank, `category`/`ownership` non-null, `attributes` null→`Map.of()`, `identity` null→`Identity.NONE`
- `record ReportOutcome(DiscoveryCandidate candidate, boolean changed)` (new, SOURCE-ONBOARDING-2-PLAN B1/C4) — `report`'s return type; `changed` is `true` for a first sighting, or when `status`/`discovered` differ from the pre-report value, `false` for a routine re-report that only refreshed `lastSeen` — lets a caller (a later wave's live-update publisher) skip a no-op broadcast
- `DiscoveryCandidateAlreadyRegisteredException extends RuntimeException` (new, SOURCE-ONBOARDING-2-PLAN B1/C1) — thrown by `attach` when the candidate is already `REGISTERED` to a different asset; declared in this package (not reusing perception's `ProbeFailedException`) because warehouse is the pure leaf and may not depend on perception; unmapped to an HTTP status in this module — a later `vision-api` wave maps it to 422

### `application.fleet`
- `FleetSummaryService` (interface) → `DefaultFleetSummaryService(AssetService, AssetLiveStatePort)` (+ overload with explicit `maxAssetsInSummary`/`openEventsScanLimit`) — the manager dashboard's aggregated read behind `GET /api/fleet/summary`
  - `FleetSummary summary(boolean includeArchived)` / `summary(VisibilityScope, boolean)` — category counts folded over every in-scope asset (never capped); per-asset attention rows sorted by name, capped at `MAX_ASSETS_IN_SUMMARY=500` (`totalAssets()` always reports the true count). No `sourceState` field — no honest "reconnecting" signal exists to read
- `FleetSummary(categories, assets, totalAssets)`

### `application.usage`
- `UsageService` (interface) → `DefaultUsageService(AssetUsageRepositoryPort, AssetRepositoryPort)` — the fleet-wide "replay library" list behind `GET /api/usages`
  - `List<UsageSummary> recent(VisibilityScope, AssetId assetIdOrNull, int limit)` newest-first, clamped to `MAX_LIMIT=500`; scope filtering runs **after** the repository's own `limit` (a scoped caller can see fewer than `limit` rows even when more of their own history exists further back)
  - `Optional<UsageSummary> byStream(VisibilityScope, StreamId)` — same row shape reached by stream id; out-of-scope and does-not-exist collapse to the same empty answer
  - `UsageSummary(usageId, assetId, assetName, startedAt, endedAt, durationSeconds, sampleCount, pilotId)` — `assetName=""` (never `null`) when the owning asset can't be resolved, included only for an `unbounded()` caller; `pilotId` mirrors `AssetUsage#pilotId()` straight through, `null` when genuinely unknown
- `UsageSessionService` (interface) → `DefaultUsageSessionService(AssetUsageRepositoryPort)` — **the only code in the platform that constructs or persists an `AssetUsage`**
  - `AssetUsage open(AssetId, StreamId streamIdOrNull, UsageOrigin origin, Instant startedAt, UserId pilotIdOrNull)` — one method, no overload; constructs+persists a new `PREFLIGHT` usage; `streamId=null` opens a telemetry-only session; `pilotIdOrNull=null` when the caller has no acting-user context to attribute (ASSET-FLOWS-PLAN §2 D1p — widened the existing signature per CLAUDE.md rule 10, every call site updated, no new overload)
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
- **One public constructor per class, current exceptions**: `Device`'s 5-/4-arg ctors (`origin=LIVE`, `state=ACTIVE`), `AssetSpec`'s 4-arg ctor (`existingDeviceIds=[]`, `identity=Identity.NONE`, `custody=Custody.NONE`), and `DeviceRegistration`'s 3-arg ctor (`origin=LIVE`) are convenience overloads kept for out-of-scope call sites elsewhere in the tree — the "N-1-arg convenience constructor" idiom is **withdrawn** (`.claude/skills/java-clean-code/SKILL.md` §3, `CLAUDE.md` rule 10); these are pre-existing debt, scheduled for removal, not a pattern to extend. Each gets **at most one** such overload, never a second layered on top (W2 extended `AssetSpec`'s existing 4-arg ctor rather than adding a 5th constructor). `Asset` dropped its old 6-arg convenience ctor entirely in favor of the `Asset.register(...)` static factory (a named "new asset with defaults" factory, not a constructor overload) — every in-module call site was updated rather than kept compiling via another overload. `AssetUsage` has exactly one constructor (its 11-arg canonical one, widened for `pilotId` by ASSET-FLOWS wave BK4) — no exceptions. `UsageSessionService#open` likewise has exactly one method signature (widened the same wave to add `UserId pilotIdOrNull`), no `default`-method fallback.
- **Derived vs. stored inventory state**: `Asset#inventoryState()` only ever stores `IN_STOCK`/`MAINTENANCE`/`RETIRED` (enforced by `InventoryState#storable()` in `Asset`'s compact ctor); `ISSUED`/`IN_FIELD` are computed on read by `InventoryStates.effective(Asset, boolean hasOpenUsage)` and must never be persisted or passed to a `with*`/`register` call.

## Gotchas
- **`AssetLiveStatePort#stopStreamsForDevices` is synchronous by design, not an oversight** — warehouse still decides, in the same request, that a device's stream must stop before the device/asset is retired or deleted. A later wave turns this into an event warehouse publishes and perception reacts to; this port is a staging post, not the destination.
- **`Asset.devices` may now be empty — a category-connectedness rule, not a record invariant** (WAREHOUSE-UX-PLAN D4). Before W2, `Asset`'s own compact ctor rejected zero devices unconditionally; that invariant was too strong for a category like "battery" or "controller" that never has a video/telemetry device. The record now only requires `devices` to be non-null; `DefaultAssetService#create` looks up `DeviceCategory#connected()` and throws `IllegalArgumentException` iff the category is connected *and* both `spec.devices()` and `spec.existingDeviceIds()` are empty. Any other caller that constructs `Asset`/`AssetSpec` directly (tests, adapters) must supply a `connected` category consciously or accept zero devices.
- **`DefaultDiscoveryService`'s cross-method merge only triggers for a group spanning 2+ *distinct* methods** — two candidates from the *same* method landing in the same network-identity group (e.g. two mDNS services on one host) are intentionally left unmerged.
- **`DiscoveredDevice.method`'s compact ctor only rejects blank/null** — no single-token or lower-case pattern, unlike `StreamDescriptor.protocol`/`CategoryId.slug` — so a merged `"mdns+onvif"` value is valid without any domain change. `DeviceDiscoveryPort.method()`'s "stable lower-case key" contract applies to each *individual* port's key, not to this merged/derived field.
- **`DefaultAssetStatsService#statsFor` is a fetch-then-aggregate workaround, not a real server-side query** — `STATS_FETCH_LIMIT=10,000`, newest-first; an asset with more flights than that under-reports and is biased toward recent history. Would need a real aggregate query on `AssetUsageRepositoryPort` to fix.
- **`AssetImage#data()` clones in and out on every access** — the same defensive-copy discipline `VideoFrame#data()` established for `ByteBuffer`, adapted for `byte[]` (no read-only-view equivalent for arrays).
- **`AssetUsage#streamId` is stamped once, at open time, by perception's `UsageTracker`, and never changed afterward** — it exists so events' `ReplayService` can join a finished usage back to its detections; `null` for a legacy usage or one opened by an asset with no video device.
- **`AssetUsage#pilotId` is "first attribution wins," not "always current"** (ASSET-FLOWS-PLAN §2 D1p) — `UsageSessionService#open` stamps whatever `pilotIdOrNull` the caller has at open time; perception's `UsageTracker#engage` backfills a `null` pilot onto an already-open, `STREAM`-origin usage the moment an operator engages it (promote-time backfill), but never overwrites a pilot that is already recorded. A device-pushed stream opening carries no acting-user context and stays `null` until such a backfill happens, if ever — honest-unknown, never fabricated.
- **`DefaultAssetService`/`DefaultDeviceService`/`DefaultFleetSummaryService`/`DefaultAssetStatsService` may never import anything from `perception.**`** — that would reopen the module cycle this port inversion closed. Any new "read something live" need adds a method to `AssetLiveStatePort`, not a new direct collaborator.
- **`AssetUsage#phase` is typed `UsagePhase` (this module), not `vision-flight`'s `FlightPhase`, deliberately** — warehouse is the pure leaf and may never depend on flight or any other context, so it owns its own value type. `UsagePhase` mirrors `FlightPhase`'s six values by name only; this module never runs the phase-transition rule and never imports flight — perception's `UsageTracker` (which legally depends on both) is the one place that runs `FlightPhaseRule` and translates its verdict onto `UsagePhase`.
- **`createFromCandidate`'s duplicate check only ever compares `spec.devices()` (new registrations)** — it does not re-check `existingDeviceIds` entries, which already go through `assignDevice`'s own eligibility rule.
- **`AssetUsage` is constructed/persisted exclusively by `DefaultUsageSessionService`** — nothing else in the codebase, including any other service in this module, should call `new AssetUsage(...)` or `assetUsageRepositoryPort.save(...)` directly.
- **`AssetDirectoryService` deliberately wraps `AssetRepositoryPort`/`DeviceRepositoryPort` directly instead of reusing `AssetService`/`DeviceService`** — both of those depend on `AssetLiveStatePort`, whose only implementation (perception's `StreamBackedAssetLiveState`) depends on `UsageTracker`. Routing `UsageTracker`'s asset/device lookups through `AssetService` would wire `UsageTracker -> AssetService -> AssetLiveStatePort -> UsageTracker`, a Spring bean cycle constructor-only injection cannot resolve. Anyone "cleaning up" `AssetDirectoryService` to reuse `AssetService`/`DeviceService` reintroduces that cycle.
- **`AssetService#setState` does not itself call `canManage`** — despite the name suggesting a canonical in-module authorization example, that check actually lives one layer up in `vision-api`'s `AssetController`. `AssetCustodyService`/`MaintenanceService`'s `canManage(ownership)`-then-audit-then-`AccessDeniedException` pattern (taking `VisibilityScope` as a method parameter) was instead sourced from vision-flight's `DefaultVehicleProfileService#probe`, the actual in-application-layer precedent for authorizing directly inside a context module's own service.
- **`MaintenanceQuery` lives in `application.maintenance`, not `domain.port`** — this module's `domain.port` interfaces are driven ports *this module* calls outward into adapters; `MaintenanceQuery` is the opposite direction, a cross-context read contract *this module implements* for a caller in another context (vision-flight). It is filed beside its implementing service instead, exactly mirroring `UsageSessionService`/`DefaultUsageSessionService`'s own precedent, so a caller in vision-flight never has to import `domain.port` to reach it.
- **`AssetUsageRepositoryPort#totalFlightSecondsByAsset` is a `default` method, deliberately, not an addition to the interface's abstract contract** — the only other implementer besides `storage/persistence`'s `JpaAssetUsageRepository` is `vision-learning`'s hand-rolled `FakeAssetUsageRepositoryPort` test fixture, out of this task's file scope; a `default Map.of()` fallback widens the port without breaking it. This is ordinary interface-evolution, not the withdrawn "N-1-arg convenience constructor" idiom above — it adds one new capability with a safe fallback, not a parameter whose contract is "null means off".
- **`MaintenanceService#fleetWide`'s asset join is in-context, `AssetController`'s firmware/hours join (below) is not** — the distinction matters: `fleetWide` reads `AssetRepositoryPort`, a port this same module (`vision-warehouse`) already declares and consumes elsewhere, so joining `MaintenanceRecord` to its `Asset`'s name/category here is an ordinary same-module read. Firmware/flight-hours needed on `AssetSummaryResponse`/`AssetDetailsResponse` (`vision-api`'s `AssetRowFacts`, WAREHOUSE-UX D5) could **not** be done the same way — `vision-flight`'s `VehicleProfileRepositoryPort` is a different context's port, and warehouse must never read it (the pure-leaf rule above) — so that join lives one layer up, in `vision-api`, not here. See `station/vision-api/MODULE.md`.
- **`DefaultDiscoveryInboxService#report`/`dismiss`/`register` are coarse-grained `synchronized`, not backed by an atomic-upsert port contract** — `report` is a plain read (`findByIdentityKey`) then write (`save`) with no atomicity across the two; a periodic sweep runner and an operator-triggered manual scan could otherwise race and each insert a duplicate row for the same `identityKey`, or clobber each other's `lastSeen`. Rather than requiring every `DiscoveryCandidateRepositoryPort` implementation to guarantee atomic upsert, the service serializes its own three mutating methods against one lock — correctness over throughput for a call pattern this infrequent (a background sweep every tens of seconds, over at most a few dozen candidates). See the class's own javadoc.
- **`DiscoveryCandidate`'s duplicate-match check in `report` reuses `AssetService#findDuplicateDevice`, not a second copy of the `(protocol, uri, sysid)` rule** — that method and `createFromCandidate`'s duplicate check both delegate to one private `DefaultAssetService#matchDevice` helper, so the "is this device already registered" rule can never drift between the throwing form (`createFromCandidate`) and the query form (`findDuplicateDevice`/discovery inbox).
- **`DefaultDiscoveryInboxService#capabilitiesFor` (mavlink→`TELEMETRY`, else→`VIDEO`) is a local copy, not shared with `vision-api`'s `CapabilityParsing`** — this module may not depend on `vision-api` or `adapter-mavlink`, where an authoritative version could otherwise live (ArchUnit-enforced); the duplication mirrors the precedent `CapabilityParsing` itself and `DefaultSimulationService` already set for the same constraint.
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

**WAREHOUSE-UX wave W8** closed three backend follow-ups from W3/W4/W7: `MaintenanceRepositoryPort#findOpen`/`findRecentlyClosed`
+ `MaintenanceService#fleetWide`/`MaintenanceListState`/`MaintenanceRecordSummary` (backing `GET
/api/maintenance`, a fleet-wide maintenance read so the Inventory page needs one call rather than
one per grounded asset), and `AssetUsageRepositoryPort#totalFlightSecondsByAsset` (one aggregate
query the `vision-api` layer joins onto the asset row for cheap flight hours). Firmware-on-the-row
(the wave's third item) needed no change in this module — it is joined entirely at the `vision-api`
layer, since warehouse must never depend on `vision-flight`'s `VehicleProfileRepositoryPort`; see
`station/vision-api/MODULE.md`'s `AssetRowFacts`. `AssetSummary`/`AssetSpec`/etc. were **not**
widened for this wave — both new facts are read-model joins at the API layer, not new fields on the
canonical application record (avoids a ninth `new AssetSummary(...)` call-site migration across
5 modules for a purely additive read).

**ZERO-CONFIG-ONBOARDING wave Z2a** (`docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md` §11) added
the persisted discovery inbox: `DiscoveryCandidateId`/`CandidateStatus`/`DiscoveryCandidate`,
`DiscoveryCandidateRepositoryPort`, `DiscoveryInboxService`/`DefaultDiscoveryInboxService`/
`RegisterFromCandidateCommand`, and `AssetService#findDuplicateDevice`/`DuplicateDeviceMatch` (the
non-throwing sibling of `createFromCandidate`'s duplicate check, extracted into a shared
`DefaultAssetService#matchDevice` helper so the two never drift). `register` is
`AssetService#createFromCandidate`'s first production caller — before Z2a that method only had test
callers. Domain + application are fully implemented and unit-tested in this module; **no outer-layer
implementation exists yet** — `storage/persistence` has no `discovery_candidates` table, and
`vision-api`/`vision-app` wiring (the sweep runner that calls `report`, the REST surface over
`candidates`/`dismiss`/`register`) is a later Z2 sub-wave's job. `AfterActionAssemblerTest`'s
hand-written `FakeAssetService` in `station/vision-api` now needs a `findDuplicateDevice` override to
compile — flagged for that module's owner, out of this module's file scope to fix.

**ASSET-FLOWS wave BK6 (A3)** (`docs/plans/active/ASSET-FLOWS-PLAN.md` §2) closed the
"unreachable reads as empty" gap in the found-devices inbox: `SourceStatus`, `DeviceDiscoveryPort#lastStatus()`
(a `default OK` method, not a `scan` signature change — see `domain.port` above), `SourceHealth`,
and `DiscoveryService#health()`. `device-discovery/onvif-mdns-v4l2`'s `MediamtxPathScanner` is the
first (and, as of this wave, only) override of `lastStatus()`; every other registered port —
including `drone-link/mavlink`'s `MavlinkHeartbeatScanner`, untouched by this wave — inherits `OK`.
`vision-api`'s `DiscoveryInboxController` is the first caller of `health()`, surfaced as
`sources: [{id, status}]` alongside the existing `candidates` array (see that module's MODULE.md
for the wire shape).

**ASSET-FLOWS wave BK4 (D1p)** (`docs/plans/active/ASSET-FLOWS-PLAN.md` §2, closes
`PLATFORM-AUDIT-2026-08-21.md`'s R1 gap #4/T4 — "every flight record is anonymous") widened
`AssetUsage` with a nullable `pilotId: UserId` (11th component) and `UsageSessionService#open` with
a trailing `UserId pilotIdOrNull` parameter — both widened in place per CLAUDE.md rule 10, every
call site across the repo updated, no new overload. `UsageSummary` grew the same field for the
fleet-wide replay-library read (`GET /api/usages`). **No new Flyway migration** — `V28__asset_inventory.sql`
already added `asset_usages.pilot_id UUID` schema-only (see `storage/persistence/MODULE.md`'s V28
row); this wave only wires the domain field and `storage/persistence`'s entity/mapper onto that
pre-existing column. Population: `vision-api`'s `AssetSessionController#engage` passes
`CurrentUser#userId()` through to `UsageTracker#engage`, which now backfills a still-`null` pilot
onto an already-open `STREAM`-origin usage at promotion time, never overwriting an already-recorded
one (see the new Gotcha above); a device-pushed stream open (`UsageTracker#deviceStreamStarted`)
still passes `null` — no acting-user context exists at that call site, and this wave deliberately
did not invent one. `./mvnw -B -pl contexts/vision-warehouse test` — 367 → 376 tests, all green.

**AUTH-ROLES wave B6** (`docs/plans/active/AUTH-ROLES-PLAN.md` §3.6) migrated every deprecated
`VisibilityScope#canManageOrg()`/`#canManage(Ownership)` call site in this module onto `Authority`:
`AssetCustodyService`'s five verbs (`issue`/`returnToStock`/`ground`/`release`/`retire`) and its
private `requireManageable` widened `VisibilityScope scope` → `Authority scope`, body now
`scope.mayManageFleet(asset.ownership())`; `MaintenanceService#open`/`#close` and their shared
private `requireManageable(AssetId, Authority)` widened the same way — `listForAsset`/`fleetWide`
were **not** touched, since they authorise on `VisibilityScope#includes` (a non-deprecated method)
and stay plain-`VisibilityScope`-typed; `DiscoveryInboxService#register` widened `VisibilityScope
scope` → `Authority scope`, body now `scope.mayManageOrg()` then
`scope.scope().includesGroup(command.ownership().groupId())`. No behavior change for an `Authority`
built from `Authority.full()`/an unbounded-scope-with-every-capability caller — every existing
production caller (all of them pre-auth-rollout ADMIN-equivalent) answers identically; the
practical effect only appears once a `VIEWER`-role `Authority` (visibility-only, no
`MANAGE_FLEET`/`MANAGE_ORG` capability) is threaded in from `vision-api`, which this module does
not itself construct. Test-double fix pattern: `DefaultAssetCustodyServiceTest`'s `inScope`/
`outOfScope` fields were retyped `VisibilityScope`→`Authority` outright (every use was
gated-call-only); `DefaultMaintenanceServiceTest` instead added parallel `inAuthority`/
`outOfAuthority` fields alongside the unchanged `inScope`/`outOfScope` (its `inScope`/`outOfScope`
are shared with the untouched `listForAsset`/`fleetWide` calls); `DefaultDiscoveryInboxServiceTest`
wrapped its four `VisibilityScope.unbounded()` register-call arguments as `Authority.full()` and its
two locally-scoped denial-test variables as `new Authority(VisibilityScope..., Set.of(...))` — note
this test file already imports `com.drones.vision.kernel.Capability` (device capabilities), so
`com.drones.vision.platform.Capability` (the `Authority` capability enum) is referenced
fully-qualified there rather than imported, to avoid a simple-name collision.
`./mvnw -B -pl contexts/vision-warehouse test` — 376 → 376 tests, all green (no count change, only
argument types).
**SOURCE-ONBOARDING-2 wave B1** (`docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md` §3.2 C1/C4/C5, §3.2
U2/U8) closed the P4 re-onboarding dead ends and the health-reporting honesty gap: `DiscoveryInboxService#attach`+`#restore`,
`report`'s `ReportOutcome` return type + the `REGISTERED→NEW` auto-reopen rule, `SourceStatus#NEVER_SCANNED` +
`SourceHealth#lastScanAt` + `DeviceDiscoveryPort#lastStatus()`'s default flip, `DiscoveryCandidate#restore()`,
`DiscoveryCandidateAlreadyRegisteredException`, and the `Device#withState` origin-preservation fix (U2,
regression-tested). Domain + application are fully implemented and unit-tested in this module; **no
outer-layer implementation exists yet** — `vision-api`'s `DiscoveryInboxController` has no `attach`/`restore`
endpoints, `vision-app`'s sweep runner does not yet branch on `ReportOutcome#changed`, and neither
`RegisterDiscoveryCandidateResponse` nor `DiscoveryInboxController`'s error mapping knows about
`SourceHealth#lastScanAt` or `DiscoveryCandidateAlreadyRegisteredException` — all of that is a later
`spring-integrator` wave's (C1) job, out of this module's file scope. `./mvnw -B -pl contexts/vision-warehouse test`
— 376 → 391 tests, all green.

Two implementation choices not spelled out literally by the plan text, worth flagging for the C1
wave: (1) `ReportOutcome#changed` is computed as `existing.isEmpty() || status changed || discovered
changed` rather than literally comparing `firstSeen.equals(lastSeen)` on the saved candidate as the
plan's prose describes — semantically equivalent for a first sighting (`newlyReported` always stamps
both to the same instant) but robust against a fixed test clock producing a false tie. (2) `attach`'s
javadoc says "one audit entry, no orphan device on failure" — this implementation composes the
existing `DeviceService#register` (audits `CREATED`) and `AssetService#assignDevice` (audits
`UPDATED`), so two audit rows are written per `attach`, not one; "no orphan device on failure" is
honored exactly (every validation runs before the device is registered), but "one audit entry" is
read here as describing the operation's atomicity from an operator's perspective, not a literal
single-row invariant — reusing the two existing, already-audited services was judged truer to the
plan's own "registers... calls the existing `AssetService#assignDevice`" instruction than inventing a
bypass write. Flag this for the C1 wave if a literal single-audit-row contract turns out to matter.
