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
**Build/test:** `./mvnw -B -pl contexts/vision-warehouse test` — 223 tests green.

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
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount, StreamId streamId, UsagePhase phase, UsageOrigin origin)` (flight → warehouse in **W1.6c**, C9 — the record was flight's but all four readers, `AssetDetails`/`DefaultAssetService`/`DefaultAssetStatsService`/`DefaultUsageService`, were already here) — a "flight"/session for an `Asset`: opened when the asset starts streaming, closed on stop, or opened/closed directly by an operator's `engage`/`disengage` verb (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2); `endedAt`/`startPosition`/`lastPosition`/`streamId` nullable, `endedAt>=startedAt` if present, `phase`/`origin` never null; **one canonical constructor, all 10 fields explicit at every call site** (wave R1 of docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md — the three legacy convenience ctors that used to default `origin=STREAM`/`phase=PREFLIGHT`/`streamId=null` are deleted; `.claude/skills/java-clean-code/SKILL.md` §3's "no overload chains" rule). `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)`, `withPhase(UsagePhase)`, `withOrigin(UsageOrigin)` all preserve every other field unchanged. `streamId` (docs/plans/done/MVP2-PLAN.md §R, R-a2) is the stream whose start opened this usage, recorded once by `UsageTracker` (perception) so `ReplayService` (events) can join a finished usage back to its detections; `null` for a legacy/streamless usage. `phase` (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) is this usage's aircraft-state phase, driven entirely by perception's `UsageTracker` — see `UsagePhase` below and this context's Gotchas for why the field lives here but the state machine does not. `origin` (`kernel.UsageOrigin{STREAM,OPERATOR}`, wave R2) is which verb opened this session — see `UsageSessionService#open` and perception's `UsageTracker#engage`/`#disengage` for the three stream/operator collision rules `origin` exists to arbitrate
- `enum UsagePhase { PREFLIGHT, IN_FLIGHT, LINK_LOST, POSTFLIGHT, ABANDONED, CLOSED }` (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O7) — `AssetUsage#phase`'s type; name-parallel to `vision-flight`'s `FlightPhase` on purpose (see Gotchas) but a wholly independent type with zero dependency on flight
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints)` — `parent` nullable (top-level)
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state, DeviceOrigin origin)` — `origin` (`kernel.DeviceOrigin`, docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4) added as the 6th component; 5-arg convenience ctor defaults `state=ACTIVE, origin=LIVE` (kept — many out-of-scope call sites); `isActive()`, `isDeleted()`, `withDetails(name, capabilities, stream, origin)` (4-arg, `origin` now explicit — no defaulting overload, every call site is in this module), `withState(...)`
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — `suggestedCategory`/`suggestedStream` nullable

### `com.drones.vision.warehouse.domain.port` (driven — implemented by adapters, or by perception for `AssetLiveStatePort`)
- `AssetImageRepositoryPort`: `void save(AssetId, AssetImage)` upsert; `Optional<AssetImage> findByAssetId(AssetId)`; `boolean existsByAssetId(AssetId)` — cheap presence check for a fleet list's `hasImage`; `void deleteByAssetId(AssetId)` idempotent. No dependency on `AssetRepositoryPort` (no referential integrity between repositories, a convention every port here follows)
- `AssetLiveStatePort` (**W1.6e** — the port inversion that killed the last module cycle): `Map<DeviceId,StreamId> activeStreamsByDevice()`; `int stopStreamsForDevices(Collection<DeviceId>)` — returns how many actually stopped, **synchronous** deliberately (W2 turns this into an event warehouse publishes and perception reacts to instead — a staging post, not the destination); `Optional<Telemetry> latestTelemetry(AssetId)`; `Map<AssetId,Integer> openDetectionEventCounts(int scanLimit)` — `scanLimit` stays the caller's own tuning constant, threaded through. Implemented by perception's `StreamBackedAssetLiveState`; consumed by `DefaultAssetService`, `DefaultDeviceService`, `DefaultAssetStatsService`, `DefaultFleetSummaryService`
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` idempotent
- `AssetUsageRepositoryPort` (flight → warehouse, **W1.6c**, C9): `AssetUsage save(AssetUsage)` upsert; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` newest-first; `List<AssetUsage> findRecent(int limit)` fleet-wide counterpart (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8); `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset; `Optional<AssetUsage> findByStream(StreamId)` (docs/plans/done/STREAM-STATE-PLAN.md §2.6) — the usage a stream opened, ≤1 per `StreamId`; empty for a streamless/legacy row, an absence rather than an error
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DeviceDiscoveryPort`: `String method()` stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — **blocking**, must self-time-box, empty list ≠ error
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` idempotent
- `FleetLiveUpdatePort` (W1.6b, warehouse's slice of the former god-port `LiveUpdatePublisherPort`): `void publishFleetChanged()` — no payload, a driving adapter re-derives its own snapshot

### `application.asset`
- `AssetService` (interface) → `DefaultAssetService` — **inventory/CRUD only since W1.6e**: starting a stream moved to `perception.application.stream.AssetStreamService` (it hands perception's own `PipelineConfig`/`TrackingConfigPatch` to the runtime — a CRUD context should not import the runtime's types). `stopStream` stays here — its signature names no perception type, and is expressible entirely through `AssetLiveStatePort`
  - `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, AssetLiveStatePort)` — reaches devices through `DeviceService` (never the device repository directly) and live runtime state through `AssetLiveStatePort` (never `StreamService`)
  - `Asset create(AssetSpec, Ownership, UserId actor)` — validates category, registers each `AssetSpec#devices()` entry, and assigns each `AssetSpec#existingDeviceIds()` entry via the same eligibility check `#assignDevice` uses (the "promote to asset" flow — backend follow-up batch, see Status); audits `CREATED`
  - `Asset createFromCandidate(AssetSpec, Ownership, UserId actor)` (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 stage 7 REGISTER, Wave O7) — same as `create`, plus a duplicate check run first, before anything is written: each of `spec.devices()`'s new registrations is checked, by `(protocol, uri, options.get("sysid"))`, against every **active** (`deviceService.devices(false)`, so a soft-deleted device's old address can be re-registered) device already on file; a match throws `IllegalStateException` naming the asset that already owns the matching device (or the device itself, if it exists but isn't yet assigned to any asset). `existingDeviceIds` entries are exempt — they name an already-registered device on purpose, and `#assignDevice`'s own eligibility check already refuses one owned elsewhere. Delegates to `create` once the check passes; no new persistence path
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default; status from `assetLiveStatePort.activeStreamsByDevice().keySet()`
  - `List<AssetSummary> assets(VisibilityScope scope, boolean includeDeleted)` — scoped read, keeps only `scope.includes(asset)`; `unbounded()` returns the unscoped result unchanged. Internal/system callers (simulation resume, live snapshot) keep calling the unscoped overload
  - `AssetDetails details(AssetId)` / `details(VisibilityScope, AssetId)` — the scoped form throws `NoSuchElementException` (404, not 403) for an out-of-scope asset, so a scoped read never reveals existence
  - `Asset update(AssetId, AssetEdit, UserId)` — partial; audits `UPDATED` with changed fields only
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED -> ACTIVE` throws `IllegalStateException`
  - `AssetDeletion delete(AssetId, UserId)` — **soft**: marks asset+devices `DELETED`, keeps usages/telemetry
  - `void stopStream(AssetId)` — resolves the asset, then `assetLiveStatePort.stopStreamsForDevices(asset.devices())`; unknown asset is a no-op
  - `Asset assignDevice(AssetId, DeviceId, UserId actor)` / `unassignDevice(...)` (docs/main/CYCLES-PLAN.md §8) — assign requires the device exist, not be soft-deleted, and not already belong to any asset (409 naming the owner); unassign requires leaving ≥1 device. Both share/mirror the private `requireAssignable` eligibility check `create`'s `existingDeviceIds` path also uses
- Records: `AssetSpec(displayName, category, attributes, devices, existingDeviceIds)`, `AssetEdit(displayName, category, attributes)` (partial, `NOTHING`; **`changesManagedFields()`** — true iff `category != null`, the operator-vs-manager authority split callers gate on so an assigned pilot may rename their own aircraft and edit its custom fields but not re-classify it, docs/plans/done/OPS-UX-PLAN.md §1), `AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition)`, `AssetStatus` enum `{OFFLINE, STREAMING}`, `AssetDetails(summary, devices, recentUsages)` (up to 20 most-recent usages), `AssetDeletion(id, displayName, devicesDeleted, usagesRetained, streamsStopped)`
- `AssetStatsService` (interface) → `DefaultAssetStatsService` — one asset's flight-utilization stats (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A), behind `GET /api/assets/{id}/stats`
  - `DefaultAssetStatsService(AssetUsageRepositoryPort, AssetLiveStatePort)` — package-private 3-arg test seam adds `Supplier<Instant> clock`
  - `AssetStats statsFor(AssetId)` — fetch-then-aggregate over `findRecentByAsset(assetId, STATS_FETCH_LIMIT=10_000)` (honest cap — an asset with more flights under-reports, biased toward its most recent history, until a real server-side aggregate query exists, same posture as events' `DefaultReplayService`); duration floored at zero against clock skew; `avgFlightSeconds` averages **closed** usages only; `lastKnownBatteryPercent` reuses `assetLiveStatePort.latestTelemetry`, rounded to the nearest whole percent (the endpoint's frozen contract wants an `Integer` where `AssetAttention` carries a raw `Double`). **Never throws and never checks asset existence** — an unknown asset just aggregates to zero/null; `vision-api`'s controller 404s it itself via `AssetService#details`
- `AssetStats(totalFlightSeconds, flightCount, firstFlownAt, lastFlownAt, avgFlightSeconds, lastKnownBatteryPercent, flightInProgress)` — the four optionals `null` (never fabricated `0`) when there's nothing to report

### `application.category`
- `CategoryService` (interface) → `DefaultCategoryService(CategoryRepositoryPort)` — `List<DeviceCategory> categories()`, sorted by `CategoryId.slug()`
- `CategoryCounts(categoryId, categoryName, total, active, deactivated, deleted, streaming)` — one `FleetSummary#categories()` row

### `application.directory` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3)
- `AssetDirectoryService` (interface) → `DefaultAssetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` — a deliberately narrow read-only seam so perception's `UsageTracker` can resolve "which asset/device does this stream/telemetry source belong to" **without** depending on `AssetService`/`DeviceService`. Those two already depend on `AssetLiveStatePort`, whose only implementation (`StreamBackedAssetLiveState`) depends on `UsageTracker` — so `UsageTracker -> AssetService -> AssetLiveStatePort -> UsageTracker` would be a Spring bean cycle. `AssetDirectoryService` is backed only by the two raw repository ports, breaking the cycle while still keeping perception off `AssetRepositoryPort`/`DeviceRepositoryPort` directly (its own `*RepositoryPort` imports from this module dropped to zero — see this context's and perception's Gotchas)
  - `Optional<Asset> findByDevice(DeviceId)` — the asset that owns a device, if any (`assetRepository.findByDeviceId`)
  - `Optional<Device> findDevice(DeviceId)` — a device by id (`deviceRepository.findById`)
  - `Optional<Asset> find(AssetId)` — an asset by id (`assetRepository.findById`), added wave R5c
    (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md) for `vision-learning`'s `DefaultLabelingService`,
    which held an `AssetId` from its own domain objects (a replay usage's `assetId()`, or one already
    resolved via `findByDevice`) and needed an unscoped lookup by that id — the same cycle-avoidance
    reasoning above applies unchanged; this is one more read on the same narrow seam, not a new one

### `application.device` (package `warehouse.application.device` — disambiguated from perception's own `device` leaf, W1.6d)
- `DeviceService` (interface) → `DefaultDeviceService(DeviceRepositoryPort, AssetLiveStatePort, AuditTrailPort, EventPublisherPort)` — stops a device's stream via `assetLiveStatePort.stopStreamsForDevices(Set.of(deviceId))`, never by iterating `StreamService` itself
  - `Device register(DeviceRegistration, UserId)` — publishes `DEVICE_ONLINE`, audits `CREATED`; `List<Device> devices()` / `devices(includeDeleted)`; `Optional<Device> find(DeviceId)`; `Device update(DeviceId, DeviceEdit, UserId)` partial; `Device setState(...)` idempotent, stops the stream leaving service; `Device delete(...)` **soft**, stays a member of its asset
- `DeviceRegistration(name, capabilities, stream, origin)` — 3-arg convenience ctor defaults `origin=LIVE` (kept — many out-of-scope call sites); `DeviceEdit(name, capabilities, stream, origin)` (all-nullable partial edit, `NOTHING`; no convenience overload — every call site is in this module)

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
  - `List<UsageSummary> recent(VisibilityScope, AssetId|null, int limit)` newest-first; `Optional<UsageSummary> byStream(VisibilityScope, StreamId)` (STREAM-STATE S5) — the same row shape reached by stream id, with out-of-scope and does-not-exist collapsed to the same empty answer
  - `List<UsageSummary> recent(VisibilityScope, AssetId assetIdOrNull, int limit)` — clamped to `MAX_LIMIT=500`; scope filtering runs **after** the repository's own `limit` (a known, accepted first-cut limitation — a scoped caller can see fewer than `limit` rows even when more of their own flights exist further back)
  - `UsageSummary(usageId, assetId, assetName, startedAt, endedAt, durationSeconds, sampleCount)` — `assetName=""` (never `null`) when the owning asset can't be resolved at all, and that row is included only for an `unbounded()` caller, silently dropped for every other scope (no `Ownership` left to check)
- `UsageSessionService` (interface) → `DefaultUsageSessionService(AssetUsageRepositoryPort)` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3) — **this is the only place in the codebase that constructs a new `AssetUsage` or writes one to `AssetUsageRepositoryPort`.** Before R3, perception's `UsageTracker` imported `AssetUsageRepositoryPort` directly and built/saved/closed the aggregate itself; this service pulls that ownership into warehouse, exposing exactly the verbs a session's lifecycle needs — perception now calls this instead of touching the repository port. `fold`/`updatePhase` are pure transforms (never write); `open`/`close` always persist (session boundaries); `save` is the explicit low-level write escape hatch perception's own batching (`UsageSummaryBatchSettings`, immediate-vs-deferred/coalesced) uses to control exactly when a mid-session update hits the repository — this service does not decide that timing, the caller does
  - `AssetUsage open(AssetId, StreamId streamIdOrNull, Instant startedAt)` — constructs+persists a new `PREFLIGHT` usage stamped `UsageOrigin.STREAM`; `streamId=null` opens a telemetry-only session (no video device). Kept as the interface's original **abstract** method (wave R2 could not change its signature: `vision-flight`'s `DefaultVehicleProfileServiceTest` hand-fakes exactly this shape, outside that wave's file scope) — see the `default` overload below for the one real caller-facing change
  - `AssetUsage open(AssetId, StreamId streamIdOrNull, UsageOrigin origin, Instant startedAt)` — `default` method (wave R2, docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2) falling back to the 3-arg abstract one (`origin=STREAM`) for any implementor that does not override it; `DefaultUsageSessionService` overrides both, with this 4-arg one as the real implementation and the 3-arg one delegating to it — the "update call sites, don't add an overload" rule (`.claude/skills/java-clean-code/SKILL.md` §3) applied at the interface level via a `default` fallback rather than a constructor overload, since the out-of-scope caller is a hand-fake `@Override` of a fixed signature, not a constructor call site that could be updated
  - `AssetUsage fold(AssetUsage, GeoPosition positionOrNull, UsagePhase)` — pins `startPosition` on the first positioned sample, advances `lastPosition`, always increments `sampleCount`, replaces `phase`; **never persists**
  - `AssetUsage updatePhase(AssetUsage, UsagePhase)` — replaces only `phase`; **never persists**
  - `AssetUsage close(AssetUsage, UsagePhase, Instant endedAt)` — stamps `endedAt` and `phase`, then persists; the session-boundary counterpart to `open`
  - `AssetUsage save(AssetUsage)` — persists as-is, for a caller (perception's batching) that already folded/updated and now decides it's time to write

## Conventions
- **Validation**: every domain record validates in its compact constructor (`if (…) throw new IllegalArgumentException(…)`); the application layer uses `Objects.requireNonNull`.
- **Defensive copies**: every `List`/`Set`/`Map` component reassigned via `List.copyOf`/`Set.copyOf`/`Map.copyOf`.
- **No referential integrity between repositories**: `AssetImageRepositoryPort` has no dependency on `AssetRepositoryPort`, `AssetUsageRepositoryPort` on `AssetRepositoryPort`, etc. — existence checks are the application layer's job.
- **The acting user is a method parameter (`UserId actor`)**, never a constructor dependency.
- **Constructor injection only**; collaborators wrapped in `Objects.requireNonNull`.
- **Soft-delete, not hard-delete**: `Asset#delete`/`Device#delete` mark `LifecycleState.DELETED` and keep every row (usages, telemetry) — nothing here ever issues a real `DELETE`.
- **Virtual threads**: `DefaultDiscoveryService` starts one `Thread.ofVirtual()` per requested discovery port per scan call; a hung adapter's thread is never tracked or interrupted (cheap + daemon).
- **One public constructor per class**: a new field means updating call sites, or bundling optional data into a settings/collaborators record with a `defaults()` factory — never one more constructor overload (`.claude/skills/java-clean-code/SKILL.md` §3, `CLAUDE.md` rule 10; the old "N-1-arg convenience constructor" idiom this replaces is withdrawn, docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R1). `Asset`'s 6-arg ctor (defaulting `state=ACTIVE`) and `Device`'s 5-arg ctor (defaulting `state=ACTIVE, origin=LIVE`) still keep such legacy overloads from before the rule changed — pre-existing, not a template to extend; each exists because an out-of-scope caller elsewhere in the codebase still constructs the record through it. `AssetUsage`, by contrast, has exactly one constructor (its 10-arg canonical one) as of this R1 follow-up: its three legacy convenience ctors (9-/8-/7-arg, defaulting `origin=STREAM`/`phase=PREFLIGHT`/`streamId=null`) are deleted and every one of the 18 files/78 call sites across this module and its downstream contexts/adapters now passes all 10 fields explicitly — see the API surface entry above. `DeviceEdit`'s `origin` field and `AssetUsage`'s R2 additions inside that wave's own file scope got no overload at the time — every call site touched by that wave was updated directly, which is exactly what this follow-up then did for every call site the wave itself didn't reach. **`UsageSessionService#open`'s wave-R2 `origin` parameter is the one exception at the interface level, not the constructor level**: a `default` method, not an overload, since the out-of-scope caller is a hand-implemented `@Override` of a fixed abstract signature (a test fake in `vision-flight`), which cannot be "updated" the way a constructor call site can — see the API surface entry above.

## Gotchas
- **`AssetLiveStatePort#stopStreamsForDevices` is synchronous by design, not an oversight** — warehouse still decides, in the same request, that a device's stream must stop before the device/asset is retired or deleted. W2 is where this becomes an event warehouse publishes and perception reacts to asynchronously; this port is a staging post, not the destination.
- **`Asset.devices` must be non-empty** — an asset with zero devices cannot even be constructed; `AssetSpec`'s own "at least one device" check duplicates this at the command layer for fail-fast, satisfied by `devices` or `existingDeviceIds` (or both).
- **`DefaultDiscoveryService`'s cross-method merge only triggers for a group spanning 2+ *distinct* methods** — two candidates from the *same* method landing in the same network-identity group (e.g. two mDNS services on one host) are intentionally left unmerged.
- **`DiscoveredDevice.method`'s compact ctor only rejects blank/null** — no single-token or lower-case pattern, unlike `StreamDescriptor.protocol`/`CategoryId.slug` — so a merged `"mdns+onvif"` value is valid without any domain change. `DeviceDiscoveryPort.method()`'s own "stable lower-case key" contract still applies to each *individual* port's key, not to this merged/derived field.
- **`DefaultAssetStatsService#statsFor` is a fetch-then-aggregate workaround, not a real server-side query** — `STATS_FETCH_LIMIT=10,000`, newest-first; an asset with more flights than that under-reports and is biased toward recent history (mirrors events' `DefaultReplayService#TELEMETRY_FETCH_LIMIT` caveat exactly). Not fixed here; would need a real aggregate query on `AssetUsageRepositoryPort`.
- **`AssetImage#data()`/`SampleImage`-style clone-in/clone-out**: `AssetImage`'s compact ctor clones `data` in, and `data()` clones it out on every access — the same defensive-copy discipline `VideoFrame#data()` established for `ByteBuffer`, adapted for `byte[]` (no read-only-view equivalent for arrays).
- **`AssetUsage#streamId` is stamped once, at open time, by perception's `UsageTracker`, and never changed afterward** — it exists purely so events' `ReplayService` can join a finished usage back to its detections; `null` for a legacy usage or one opened by an asset with no video device.
- **`DefaultAssetService`/`DefaultDeviceService`/`DefaultFleetSummaryService`/`DefaultAssetStatsService` may never import anything from `perception.**` again** — that is exactly the cycle W1.6e closed. Any new "read something live" need must add a method to `AssetLiveStatePort`, not a new direct collaborator.
- **`AssetUsage#phase` is typed `UsagePhase` (this module), not `vision-flight`'s `FlightPhase`, and that is deliberate, not a naming accident** (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.2/§7, Wave O7): the plan puts the phase *rule* (`FlightPhaseRule`) in flight and the `AssetUsage.phase` *field* here, in the pure leaf that may not depend on flight or any other context. `UsagePhase` mirrors `FlightPhase`'s six values by name only, as a wholly independent enum; **this module never runs the rule and never imports flight** — perception's `UsageTracker` (which legally depends on both) is the one place that runs `FlightPhaseRule` and maps its verdict onto `UsagePhase` before calling `AssetUsage#withPhase` (or `UsageSessionService#open`, for a session's initial phase). If a future change ever needs this module to reason about phase transitions itself, that is a sign the rule needs its own port here, not a warehouse → flight dependency.
- **`createFromCandidate`'s duplicate check only ever compares `spec.devices()` (new registrations)** — it does not re-check `existingDeviceIds` entries, which already go through `#assignDevice`'s own eligibility rule (must exist, not deleted, not owned elsewhere). A candidate spec mixing both lists gets both checks, just via two different code paths.
- **`AssetUsage` is constructed/persisted exclusively by `DefaultUsageSessionService`** (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3) — nothing else in the codebase, including any other service in this module, should call `new AssetUsage(...)` or `assetUsageRepositoryPort.save(...)` directly; go through `UsageSessionService`'s verbs instead. `AssetDirectoryService` is the sibling seam for asset/device *reads* from outside this module — the same wave's other half, and deliberately not folded into `AssetService`/`DeviceService` (see `application.directory` above for why: the Spring bean cycle those two would otherwise create with perception's `AssetLiveStatePort`/`UsageTracker`).

## Status

**ARCHITECTURE-AUDIT-2026-08-26 wave R2 done** (finding D2 — "a session can only ever be opened as
a side effect of a stream starting"): `AssetUsage` gained a 10th component, `UsageOrigin origin`
(`kernel`, new enum mirroring `DeviceOrigin`) — see the API surface entry above for the three legacy
convenience ctors kept for out-of-scope callers, and `withOrigin`. `UsageSessionService#open` gained
a `default` 4-arg overload taking an explicit `origin` (see API surface + Conventions above for why
this is a `default` method, not a constructor overload); `DefaultUsageSessionService` overrides both.
**This module's own scope stops at the domain/application layer** — the operator `engage`/`disengage`
verb itself, the three stream/operator collision rules, and the `onTelemetryDeviceDiscovered`
deletion decision all live in `vision-perception`'s `UsageTracker` (see that module's own MODULE.md
Status entry for the full writeup); `storage/persistence` (`V26__asset_usage_origin.sql`, backfilling
every existing row to `STREAM` — every usage ever written before this wave was, by construction,
opened by a stream) and `station/vision-api` (`AssetSessionController`, `POST`/`DELETE
/api/assets/{id}/session`) carry the rest. **210 → 223 tests** (`./mvnw -B -pl contexts/vision-warehouse
-am test`, green). This wave's own additions account for 7 of the 13: 4 in `AssetUsageTest` (origin
defaulting/rejection/`withOrigin`/preservation-through-`closed`/`withPositions`/`withSampleCount`)
and 3 in `DefaultUsageSessionServiceTest` (both `open` overloads + null-origin rejection) — the
remaining 6 were already present on this branch before this wave started (the 210 figure the task
brief quoted as a baseline undercounts the branch's actual pre-wave total by 6; not investigated
further, flagged here rather than silently reconciled).

**ARCHITECTURE-AUDIT-2026-08-26 wave R3 done** (finding D1 — "session is one concept wearing four
names, owned by nobody"): warehouse now owns the entire `AssetUsage` lifecycle. New
`application.usage.UsageSessionService`/`DefaultUsageSessionService` is the sole constructor/persister
of `AssetUsage` (see Gotchas); new `application.directory.AssetDirectoryService`/
`DefaultAssetDirectoryService` gives perception a read-only asset/device lookup that avoids a Spring
bean cycle through `AssetLiveStatePort`. `vision-perception`'s `UsageTracker` dropped from four
`*RepositoryPort` imports across two foreign contexts (`AssetRepositoryPort`, `DeviceRepositoryPort`,
`AssetUsageRepositoryPort` here, plus flight's `TelemetryRepositoryPort`) to zero — it now depends only
on these two new warehouse services plus flight's new `application.telemetry.TelemetryService` (see
that module's MODULE.md). All phase-translation rules, the telemetry-only-aircraft null-`streamId`
open, `UsageSummaryBatchSettings` batching, and the source backoff ladders are unchanged — `UsageTracker`
still runs `FlightPhaseRule`/translates to `UsagePhase` itself (warehouse still never depends on
flight), it just calls a service instead of a repository for the actual read/write. Wired in
`station/vision-app`'s `ApplicationServiceWiring`. **191 → 210 tests**
(`./mvnw -B -pl contexts/vision-warehouse -am test`, green); `ContextArchitectureTest` 4/4, no new or
changed cross-context edges.

**ARCHITECTURE-AUDIT-2026-08-26 wave R4 done** (this module's half — "the drone has no camera yet"):
`Device`/`DeviceRegistration`/`DeviceEdit` gained `origin` (`kernel.DeviceOrigin{LIVE,SIMULATED}`),
so "simulated" is now a property of one device instead of a whole asset's category. See the API
surface entries above for the exact ctor shapes and `contexts/vision-simulation`'s MODULE.md for
`SimulationService#fitSimulatedDevice` — the new entry point that fits a synthetic device onto an
*existing* asset (`simulate()` still only ever creates a brand-new one). `storage/persistence`'s
`V25__device_origin.sql` backfills existing rows (`LIVE` default; `SIMULATED` for devices already
on a `simulated`-category asset) and `station/vision-api`'s device/asset-create DTOs carry `origin`
end to end — see those modules' own MODULE.mds. **181 → 191 tests**
(`./mvnw -B -pl contexts/vision-warehouse test`, green).

**ARCHITECTURE-AUDIT-2026-08-26 wave R5c done** (the two cross-context repository-port reads R5b
flagged but could not fix — see `vision-flight`'s and `vision-learning`'s own MODULE.md Status
entries for each consumer's side): `AssetDirectoryService`/`DefaultAssetDirectoryService` gained
`Optional<Asset> find(AssetId)` — `vision-learning`'s `DefaultLabelingService` needed an unscoped
lookup by an `AssetId` it already held (from a replay usage, or from a prior `findByDevice` call),
and this is the same narrow cycle-avoidance seam `findByDevice`/`findDevice` already serve, not a new
one (see `application.directory`'s own entry above for why this bypasses `AssetService`, unchanged
reasoning). `UsageSessionService`/`DefaultUsageSessionService` gained
`boolean usageBelongsToAsset(UsageId, AssetId)` — `vision-flight`'s `DefaultVehicleProfileService`
needed an uncapped membership check, not a data read, for a `usageId` it was handed after already
scope-gating the asset itself. Filed on `UsageSessionService` rather than on `UsageService`
deliberately: every `UsageService` method takes a `VisibilityScope`, and this one method genuinely
should not (see this interface's own javadoc for the full reasoning) — adding it there would be an
unscoped method sitting beside an all-scoped surface, exactly the footgun the task's own instructions
warned against; `UsageSessionService`'s whole surface is already unscoped, so it is the honest home.
Both are collaborator swaps at each caller (`AssetRepositoryPort`→`AssetDirectoryService`,
`AssetUsageRepositoryPort`→`UsageSessionService`), net-zero on constructor parameter counts. Also
added this wave: `station/vision-app`'s `ContextArchitectureTest` gained
`noContextImportsAnotherContextsRepositoryPort` — an ArchUnit rule that fails the build if any
context's application/domain code imports (or, via a bundling record's accessor, reaches) another
context's `*RepositoryPort`, with a small, explicitly named allow-list (`REPOSITORY_PORT_EXEMPTIONS`)
for `vision-events`' three deliberate reads (see that module's own MODULE.md), two reads
`vision-learning`'s `DefaultLabelingService` makes one hop through `vision-events`' `ReplaySources`
bundling record (same shape as the `events` exemptions, discovered by this rule, not assigned to
fix), and `vision-perception`'s `DefaultStreamService`/`DefaultAssetStreamService` (pre-existing,
unpaid debt from the audit's own table, out of this wave's file scope, not this module's to fix).
216/216 tests green (`./mvnw -B -pl contexts/vision-warehouse test`); 333/333 `vision-flight`,
160/160 `vision-learning`, unchanged counts, all rewired in place.

**ARCHITECTURE-AUDIT-2026-08-26 wave R1 follow-up done** (`AssetUsage` was the one class wave R1
itself didn't reach — see that wave's own writeup in `.claude/skills/java-clean-code/SKILL.md` §3):
`AssetUsage`'s three legacy convenience constructors (9-arg defaulting `origin=STREAM`, 8-arg
defaulting `phase=PREFLIGHT`/`origin=STREAM`, 7-arg defaulting `streamId=null` on top of both) are
deleted, leaving the 10-arg canonical constructor as the record's only one. Every call site — 78 of
them across 18 files in this module, `vision-flight`, `vision-events`, `vision-learning`,
`storage/persistence` (`AssetUsageMapper` was already explicit; only its stale javadoc needed
fixing), `station/vision-api` and `station/vision-app` — now passes all 10 fields explicitly,
preserving exactly the value each deleted overload used to inject (behavior-neutral by construction:
no assertion changed, no test deleted/weakened/renamed). Reactor-wide test counts are byte-for-byte
unchanged from before this wave (223/223 here; every other module's own count untouched).

**W1.7b/c** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): `vision-domain`/`vision-application` dissolved; warehouse's `.domain`/`.application` packages became this one module, a directory move with the ArchUnit layer boundary preserved. No behavior change; 170/170 tests green.

**Backend follow-ups batch (2026-07-24), item 1 — `AssetSpec#existingDeviceIds`** (the "promote to asset" flow, docs/main/CYCLES-PLAN.md §8's contract extended): `DefaultAssetService#create` now assigns already-registered, unowned devices to the new asset in the same call, validated by a new shared private `requireAssignable` helper also used by `#assignDevice` — must exist (404), must not be soft-deleted (400), must not already belong to another asset (409, naming the owner). `AssetSpec`'s "at least one device" rule now passes if `devices` or `existingDeviceIds` (or both) is non-empty. (The batch's other two items — `SimulationService#resumeAll()` and the `detection-events` live topic — belong to the simulation and perception/events contexts respectively; see their own MODULE.mds.)

**docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A done** (per-asset flight stats): `AssetStats`/`AssetStatsService`/`DefaultAssetStatsService` — the manager asset page's KPI tile row, behind `GET /api/assets/{id}/stats`. Fetch-then-aggregate mirroring events' `DefaultReplayService` (see Gotchas); "now" is injectable via a package-private `Supplier<Instant> clock` seam, defaulting to `Instant::now` in production; battery reuse verified against `DefaultFleetSummaryService#toAttention`'s existing derivation rather than assumed. Never throws/never checks asset existence — 404-for-unknown is `vision-api`'s `AssetStatsController`'s job via `AssetService#details`.

**docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8 done** (replay library): new `usage` feature package — `UsageService`+`DefaultUsageService`, `UsageSummary` — behind `GET /api/usages`, joining events' `ReplayService`-backed endpoints on the same `UsageTimelineController`. Kept as its own service area rather than a method on `ReplayService` for the collaborator-set reason above. Built against `AssetUsageRepositoryPort#findRecent(int)`, additive.

**docs/plans/done/UX-REWORK-PLAN.md §U-d item 3** added this context's `AssetImage`/`AssetImageRepositoryPort` (two genuinely substitutable implementations exist — JPA/bytea and in-memory — earning the interface per `.claude/skills/java-clean-code/SKILL.md` §1); the probe service itself (`ProbeService`/`DefaultProbeService`) that CONTRACT 1 also delivered now lives in `perception.application.device` (moved W1.6d) — see that context's MODULE.md for the probe's own Status entry.

**docs/main/CYCLES-PLAN.md §8 (CW-a)** added `AssetService#assignDevice`/`#unassignDevice` — device↔asset reassignment; `DeviceService`/`AssetService` update/setState/delete already existed before this.

Fully implemented otherwise: category/device/discovery CRUD, fleet summary aggregation, per-asset stats, the replay-library list. Nothing in this context is a stub — every port listed above has at least one real implementation in `storage/persistence` or `vision-app`'s devsupport.

**docs/plans/done/STREAM-STATE-PLAN.md wave S5 done** (the ended-stream read path): `AssetUsageRepositoryPort#findByStream(StreamId)` + `UsageService#byStream(VisibilityScope, StreamId)`, behind `GET /api/usages/by-stream/{streamId}`. **No new persistence and no migration** — the plan's first draft proposed an "ended stream" table and that was wrong: `AssetUsage` already *is* that record, and `stream_id` already exists (`V4__usage_stream_id.sql`); the only gap was that nothing could look a usage up by it. `byStream` reuses `recent`'s own `toSummary` resolution, so scope filtering, the gone-asset rule, and the row shape are identical by construction rather than by a second implementation that could drift. 12/12 `DefaultUsageServiceTest`, module 186/186.

**docs/plans/active/DRONE-ONBOARDING-PLAN.md Wave O7 done** (this module's half): `AssetUsage.phase` (typed `UsagePhase`, a new domain enum, see Gotchas for why it is not `vision-flight`'s `FlightPhase`) and `AssetService#createFromCandidate` + its `(protocol, uri, sysid)` duplicate check on `DefaultAssetService`. Neither `storage/persistence`'s `AssetUsageMapper` nor any other out-of-module caller needed changes — `AssetUsage`'s 8-arg convenience ctor (defaulting `phase=PREFLIGHT`) keeps every pre-O7 call site compiling, and `createFromCandidate` is a wholly new method (`DefaultAssetService` is `AssetService`'s only implementation, so adding it broke nothing). The perception half (phase-driving `UsageTracker`, the telemetry-only-session entry point) lives in `vision-perception`'s own MODULE.md. **Not done in this wave** (flagged for the plan, not silently deferred): nothing here closes a telemetry-only usage on its own — see perception's Gotchas for the `ABANDONED`-but-never-`CLOSED` gap and `AssetUsageMapper`'s persistence of the new `phase` column, which is `storage/persistence`'s job (O5), not checked here.
