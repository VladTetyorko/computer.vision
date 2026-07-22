# vision-application

Control-plane services orchestrating `vision-domain` ports: asset/device/stream management, the per-stream pipeline runtime, and usage/telemetry tracking.

**One interface + one `Default*` implementation per service area** — there is no inbound-port package and no `*UseCase` type. Commands and read models are top-level records in this package, not types nested in interfaces. See `.claude/skills/java-clean-code/SKILL.md`.

**Depends on:** vision-domain (only) · **Used by:** adapters (adapter-simulation, adapter-rtsp, adapter-publish-hls, adapter-discovery, adapter-cv-grpc, adapter-overlay, adapter-persistence), vision-app, vision-api
**Build/test:** `./mvnw -B -pl vision-application test`

## API surface

All types in the single package `com.drones.vision.application`.

- **Records (commands + read models):** `DeviceRegistration(name, capabilities, stream)`, `DeviceEdit(name, capabilities, stream)` (all-nullable partial edit, `DeviceEdit.NOTHING`), `AssetSpec(displayName, category, attributes, devices)`, `AssetEdit(displayName, category, attributes)` (partial, `AssetEdit.NOTHING`), `AssetSummary(asset, categoryName, status, lastUsedAt, lastKnownPosition)`, `AssetStatus` enum `{OFFLINE, STREAMING}`, `AssetDetails(summary, devices, recentUsages)`, `AssetDeletion(id, displayName, devicesDeleted, usagesRetained, streamsStopped)`, `ActiveStream(streamId, deviceId, startedAt)`, `ScanRequest(timeout, methods)` + `ScanRequest.defaults()`, `ScanResult(devices, failedMethods)`

- **`AssetService`** (interface) → **`DefaultAssetService`**
  - `DefaultAssetService(AssetRepositoryPort, CategoryRepositoryPort, AssetUsageRepositoryPort, AuditTrailPort, DeviceService, StreamService)` — reaches devices through `DeviceService`, never the device repository, so a source's rules (stop its stream, write an audit line) live in one place
  - `Asset create(AssetSpec, Ownership, UserId actor)` — validates the category, registers each device, audits `CREATED`
  - `List<AssetSummary> assets()` / `assets(boolean includeDeleted)` — soft-deleted excluded by default; status from `streamService.activeDeviceIds()`
  - `AssetDetails details(AssetId)` — `NoSuchElementException` if unknown; up to 20 (`RECENT_USAGES_LIMIT`) most-recent usages
  - `Asset update(AssetId, AssetEdit, UserId)` — partial; audits `UPDATED` with changed fields only, and writes nothing when nothing changed
  - `Asset setState(AssetId, LifecycleState, UserId)` — idempotent; stops streams when leaving service; `DELETED → ACTIVE` throws `IllegalStateException` (restore first)
  - `AssetDeletion delete(AssetId, UserId)` — **soft**: marks the asset and its devices `DELETED`, keeps usages/telemetry; idempotent
  - `StreamId startStream(AssetId, DeviceId, PipelineConfig)` — `device` nullable: resolves the asset's single **active** `VIDEO`-capable device, `IllegalArgumentException` (naming candidates) if zero or >1; `IllegalStateException` if the asset is not in service
  - `void stopStream(AssetId)` — stops every active stream on the asset's devices; unknown asset is a no-op

- **`CategoryService`** (interface) → **`DefaultCategoryService`**
  - `DefaultCategoryService(CategoryRepositoryPort)`
  - `List<DeviceCategory> categories()` — thin pass-through over `CategoryRepositoryPort.findAll()`, sorted by `CategoryId.slug()` for a stable UI order

- **`DeviceService`** (interface) → **`DefaultDeviceService`**
  - `DefaultDeviceService(DeviceRepositoryPort, StreamService, AuditTrailPort, EventPublisherPort)`
  - `Device register(DeviceRegistration, UserId actor)` — assigns `DeviceId.random()`, saves, publishes `DEVICE_ONLINE`, audits `CREATED`
  - `List<Device> devices()` / `devices(boolean includeDeleted)`; `Optional<Device> find(DeviceId)`
  - `Device update(DeviceId, DeviceEdit, UserId)` — partial; a running stream keeps its settings until the next start
  - `Device setState(DeviceId, LifecycleState, UserId)` — idempotent; stops the device's stream when leaving service; `DELETED → ACTIVE` throws `IllegalStateException`
  - `Device delete(DeviceId, UserId)` — **soft**; the device stays a member of its asset, so removing an asset's last source is never refused

- **`DiscoveryService`** (interface) → **`DefaultDiscoveryService`**
  - `DefaultDiscoveryService(List<DeviceDiscoveryPort> ports)` — indexed by `port.method()` into an immutable map
  - `ScanResult scan(ScanRequest request)` — one virtual thread per requested port; overall deadline = `timeout` + a package-private 200ms grace period (`GRACE_PERIOD`); per-adapter exception/timeout isolated into `failedMethods`, not thrown; throws `IllegalArgumentException` only for an unknown requested method key
  - Dedup is two-pass: (1) exact `(method, address)` match within one scan, then (2) cross-method merge — candidates are grouped by network identity (`URI.getHost()`, case-insensitive, when present; exact address when the host is null, e.g. `v4l2` `file:` URIs) and a group is merged into one `DiscoveredDevice` only if it spans **2+ distinct methods** (a same-method group, even sharing a host, passes through unmerged). Merged fields: `method` = distinct methods sorted alphabetically and joined with `"+"` (e.g. `"mdns+onvif"`, deterministic regardless of discovery order); `name` = the first candidate, in priority order (`onvif` > `mdns` > others), whose name isn't just the address/host literal, else the top-priority candidate's name; `suggestedStream`/`address` = the first candidate (priority order) with an `rtsp`-protocol stream, else the first with any non-null stream (`address` follows whichever candidate supplied the stream, else the top-priority candidate's own address); `suggestedCategory` = first non-null (priority order); `details` = union of all candidates' maps, with a key contributed by 2+ distinct methods namespaced `"<method>.<key>"` for every contributing candidate (symmetric, not just the losing side). See `DefaultDiscoveryService.mergeGroup`/`mergeDetails` javadoc for the full rule set.

- **`StreamPipeline`** implements `Flow.Subscriber<VideoFrame>`, `AutoCloseable`
  - `StreamPipeline(StreamId, Device, PipelineConfig, Flow.Publisher<VideoFrame>, DetectionPort, StreamPublisherPort, DetectionRepositoryPort, EventPublisherPort)` — 8-arg public ctor, delegates to a package-private 9-arg ctor with `nanoTimeSource = System::nanoTime`
  - `StreamPipeline(..., LongSupplier nanoTimeSource)` — package-private 9-arg ctor; test seam for driving the frame-cadence measurement off a synthetic clock instead of real wall-clock time
  - `void start()` — call exactly once: `streamPublisherPort.streamStarted` then subscribes to the source
  - `List<Detection> latestDetections()` — `volatile`, updated on every completed inference (including empty results)
  - `void close()` — idempotent (`AtomicBoolean` compare-and-set)
  - Behavior: `request(1)` one frame at a time from the source; sampling interval `everyNth = max(1, round(effectiveFps / inferenceFps))`, recomputed on every frame; `effectiveFps` is the **measured** source arrival rate — an EWMA (`MEASURED_FPS_EWMA_ALPHA`=0.2, package-private) of inter-frame-arrival deltas taken from an injectable `LongSupplier` nanotime source — sanity-clamped to `[MIN_MEASURED_FPS=1, MAX_MEASURED_FPS=240]`, until `WARMUP_FRAMES`=5 frames have arrived, during which `ASSUMED_SOURCE_FPS`=30 is used instead (all four constants package-private); once `maxInFlightInferences` is reached a sampled frame is **skipped, never queued**; any source error or detection failure emits one `PIPELINE_ERROR` event then closes cleanly

- **`StreamService`** (interface) → **`DefaultStreamService`**
  - `DefaultStreamService(DeviceRepositoryPort, VideoSourceRegistry, DetectionPort, StreamPublisherPort, DetectionRepositoryPort, EventPublisherPort)` — 6-arg, delegates to the 7-arg ctor with `usageTracker=null`
  - `DefaultStreamService(..., UsageTracker usageTracker)` — 7-arg; `usageTracker` is **nullable**, not `requireNonNull`'d — passing `null` simply skips usage tracking
  - `StreamId start(DeviceId deviceId, PipelineConfig config)` — at most one active stream per device (Phase 0); `IllegalStateException` if the device already has one **or is not `ACTIVE`**, `NoSuchElementException` if unknown
  - `void stop(StreamId streamId)` — no-op for unknown/already-stopped
  - `List<ActiveStream> streams()`
  - `Set<DeviceId> activeDeviceIds()` — immutable snapshot; consumed by `AssetService` for STREAMING/OFFLINE derivation

- **`UnsupportedProtocolException extends RuntimeException`**
  - `UnsupportedProtocolException(String protocol)`
  - `String protocol()`

- **`UsageTracker`**
  - `UsageTracker(AssetRepositoryPort, DeviceRepositoryPort, AssetUsageRepositoryPort, TelemetryRepositoryPort, List<TelemetrySourcePort>)`
  - `void onStreamStarted(DeviceId deviceId)` — no-op if the device has no owning asset (`AssetRepositoryPort.findByDeviceId` empty); opens a new `AssetUsage` only on the asset's first currently-active device; subscribes telemetry for each `Capability.TELEMETRY` device with a matching source (first matching source wins)
  - `void onStreamStopped(DeviceId deviceId)` — closes the open `AssetUsage` only on the asset's last currently-active device; unsubscribes/closes all telemetry subscriptions opened for it

- **`VideoSourceRegistry`**
  - `VideoSourceRegistry(List<VideoSourcePort> sources)` — defensively copied to an immutable list
  - `VideoSourcePort sourceFor(StreamDescriptor descriptor)` — first adapter whose `supports()` is true; throws `UnsupportedProtocolException`

## Conventions
- No Spring/framework imports anywhere in this module (Spring only appears in adapters/vision-app/vision-api).
- **The acting user is a method parameter (`UserId actor`), never a constructor dependency** — it varies per request; the API edge resolves it from the token and passes it down.
- Constructor injection only; every collaborator is wrapped in `Objects.requireNonNull(x, "x must not be null")` **except** `DefaultStreamService`'s `usageTracker`, which is deliberately left nullable to make the class trivially testable/usable without usage tracking.
- Injected collections are defensively copied at construction (`List.copyOf`/`Map.copyOf`): `VideoSourceRegistry.sources`, `DefaultDiscoveryService.portsByMethod`, `UsageTracker.telemetrySources`.
- Virtual threads: `DefaultDiscoveryService.scanAsync` starts one `Thread.ofVirtual()` per requested discovery port per scan call; a hung adapter's thread is never tracked or interrupted (cheap + daemon, so it never blocks JVM shutdown).
- Concurrency primitives: `ConcurrentHashMap` for shared registries (`DefaultStreamService.activeStreams`/`streamByDevice`, `UsageTracker.trackingByAsset`); `AtomicInteger`/`AtomicBoolean` for pipeline counters/close-flags; `volatile` for single-writer/many-reader fields (`StreamPipeline.latestDetections`/`subscription`).
- `Flow.Subscriber` pattern used identically in two places — `StreamPipeline` (video) and `UsageTracker.TelemetrySubscriber` (telemetry) — both request exactly one item at a time and re-request after processing.
- Per-asset mutable state in `UsageTracker` (`Tracking`) is synchronized on the `Tracking` instance itself, not a module-wide lock — different assets' start/stop/telemetry never contend.

## Gotchas
- `StreamPipeline`'s sampling rate is driven by a *measured* source cadence (EWMA of inter-frame-arrival deltas), not a fixed assumption — but the measurement only starts being trusted after `WARMUP_FRAMES`=5 frames, and the EWMA is seeded directly from the first observed delta (not blended toward the 30fps fallback) so a constant-cadence source converges immediately once warmup ends, rather than drifting in slowly. A source whose rate changes *during* a stream still tracks (recomputed every frame), but a source that starts, drifts wildly, then stabilizes will have a skewed effective inference FPS for its first 5 frames.
- In-flight inference bound is **skip-not-queue**: once `maxInFlightInferences` in-flight calls are outstanding, `StreamPipeline` drops the sampled frame outright rather than buffering it — a slow `DetectionPort` degrades detection *rate*, never blocks the video path.
- `StreamPipeline.close()`/error handling both funnel through one `AtomicBoolean` CAS; a detection completing *after* close is a silent no-op (`closed.get()` re-checked inside the `whenComplete` callback).
- `DefaultStreamService` allows only one active stream per device (Phase 0 scope) — starting a second throws `IllegalStateException` rather than replacing the existing stream.
- `UsageTracker` drops a telemetry sample that arrives after its usage has closed (`tracking.usage == null`) instead of reopening a new usage for it.
- Overlay rendering is **not wired in yet** anywhere in this module — `OverlayPort`/`AnnotatedFrame` exist in `vision-domain` but `StreamPipeline` only ever publishes raw frames; the class javadoc marks this as the Phase 2 seam.
- `DefaultDiscoveryService` cross-method merge only triggers for a group spanning 2+ *distinct* methods — two candidates from the *same* method that happen to land in the same network-identity group (e.g. two mDNS services on one host) are intentionally left unmerged, passed through exactly as the exact-`(method,address)` dedup pass would have produced them.
- `DiscoveredDevice.method`'s compact constructor only rejects blank/null — no single-token or lower-case pattern (unlike `StreamDescriptor.protocol` or `CategoryId.slug`) — so a merged, `"+"`-joined value like `"mdns+onvif"` is valid without any `vision-domain` change. `DeviceDiscoveryPort.method()`'s own javadoc ("stable lower-case key") still applies to each *individual* port's method key, not to this merged/derived field.
- `UsageTracker.TelemetrySubscriber.onError` is an empty method (comment-only) by design — a telemetry source failure is completely silent, not even logged, because best-effort telemetry must never affect stream/usage lifecycle.

## Status
Fully implemented, including `ListCategoriesUseCase` (`CategoryService`, closing the M4 gap noted in vision-app/MODULE.md's prior Status). 92 tests, all passing (`./mvnw -B -pl vision-application test`); depends on vision-domain, itself fully implemented at 127 tests (`./mvnw -B -pl vision-domain test`).
